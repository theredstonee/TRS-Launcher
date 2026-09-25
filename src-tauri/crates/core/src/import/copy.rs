//! Kopieren einer fremden Installation in den Spielordner einer neuen Instanz.
//!
//! Schutz: Ziele entstehen nur aus einzelnen Namen, die beim Durchlaufen des
//! Quellordners gelesen wurden (kein `..`, keine absoluten Pfade); Symlinks
//! und Junctions werden nur verfolgt, wenn ihr Ziel im Quellordner bzw. im
//! Datenordner des Launchers liegt; Zugangsdaten werden übersprungen; Anzahl
//! und Gesamtgröße der Dateien sind begrenzt. Die Quelle wird nur gelesen.

use std::collections::HashSet;
use std::path::{Component, Path, PathBuf};

use serde::Serialize;

use super::{CopyExtras, ImportProgressFn, is_excluded, is_secret_file};
use crate::{Error, Result};

/// Höchstens so viele Dateien …
pub(super) const MAX_FILES: usize = 300_000;
/// … und so viele Bytes (256 GB) je Import.
pub(super) const MAX_BYTES: u64 = 256 * 1024 * 1024 * 1024;
/// Verzeichnistiefe – schützt vor Schleifen über Links.
const MAX_DEPTH: usize = 48;

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportProgress {
    pub percent: f64,
    pub done_files: u64,
    pub total_files: u64,
}

#[derive(Debug)]
pub(super) struct Entry {
    /// Quelle (bei Links: das aufgelöste Ziel).
    pub src: PathBuf,
    /// Ziel relativ zum Spielordner der Instanz.
    pub rel: PathBuf,
    pub size: u64,
}

#[derive(Debug, PartialEq, Eq)]
pub(super) struct TooLarge;

pub(super) struct Limits {
    pub files: usize,
    pub bytes: u64,
}

impl Default for Limits {
    fn default() -> Self {
        Self { files: MAX_FILES, bytes: MAX_BYTES }
    }
}

struct Walker {
    /// Erlaubte Link-Ziele (kanonisch).
    roots: Vec<PathBuf>,
    /// Schon betretene Link-Ziele (Schleifenschutz).
    visited: HashSet<PathBuf>,
    files: Vec<Entry>,
    bytes: u64,
    limits: Limits,
}

impl Walker {
    fn new(roots: impl IntoIterator<Item = PathBuf>, limits: Limits) -> Self {
        let roots = roots.into_iter().filter_map(|r| std::fs::canonicalize(r).ok()).collect();
        Self { roots, visited: HashSet::new(), files: Vec::new(), bytes: 0, limits }
    }

    /// Datei oder Ordner hinter einem Eintrag – Links nur ins Erlaubte.
    fn resolve(&mut self, entry: &std::fs::DirEntry) -> Option<(PathBuf, bool, u64)> {
        let kind = entry.file_type().ok()?;
        if kind.is_symlink() {
            let target = std::fs::canonicalize(entry.path()).ok()?;
            if !self.roots.iter().any(|r| target.starts_with(r)) {
                return None;
            }
            let meta = std::fs::metadata(&target).ok()?;
            if meta.is_dir() {
                return self.visited.insert(target.clone()).then_some((target, true, 0));
            }
            return meta.is_file().then_some((target, false, meta.len()));
        }
        if kind.is_dir() {
            Some((entry.path(), true, 0))
        } else if kind.is_file() {
            Some((entry.path(), false, entry.metadata().map(|m| m.len()).unwrap_or(0)))
        } else {
            None
        }
    }

    fn push(&mut self, src: PathBuf, rel: PathBuf, size: u64) -> std::result::Result<(), TooLarge> {
        if self.files.len() >= self.limits.files || self.bytes.saturating_add(size) > self.limits.bytes {
            return Err(TooLarge);
        }
        self.bytes += size;
        self.files.push(Entry { src, rel, size });
        Ok(())
    }

    fn walk(
        &mut self,
        dir: &Path,
        rel: &Path,
        depth: usize,
        skip_top: Option<&dyn Fn(&str) -> bool>,
    ) -> std::result::Result<(), TooLarge> {
        let Ok(entries) = std::fs::read_dir(dir) else { return Ok(()) };
        for entry in entries.flatten() {
            let name = entry.file_name();
            // Namen, die kein gültiges Unicode sind, lassen wir aus (Windows kennt sie praktisch nicht).
            let Some(name) = name.to_str() else { continue };
            if skip_top.is_some_and(|skip| skip(name)) || is_secret_file(name) {
                continue;
            }
            let Some((path, is_dir, size)) = self.resolve(&entry) else { continue };
            let child = rel.join(name);
            if is_dir {
                if depth + 1 < MAX_DEPTH {
                    self.walk(&path, &child, depth + 1, None)?;
                }
            } else {
                self.push(path, child, size)?;
            }
        }
        Ok(())
    }

    /// Nur `.jar`-Dateien (auch deaktivierte) eines Ordners nach `mods/`.
    fn jars(&mut self, dir: &Path, suffix: &str) -> std::result::Result<(), TooLarge> {
        let Ok(entries) = std::fs::read_dir(dir) else { return Ok(()) };
        for entry in entries.flatten() {
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            let lower = name.to_ascii_lowercase();
            if !(lower.ends_with(".jar") || lower.ends_with(".jar.disabled")) || is_secret_file(name) {
                continue;
            }
            let Some((path, false, size)) = self.resolve(&entry) else { continue };
            let target = if lower.ends_with(".disabled") { name.to_owned() } else { format!("{name}{suffix}") };
            self.push(path, Path::new("mods").join(target), size)?;
        }
        Ok(())
    }
}

/// Stellt die Liste der zu kopierenden Dateien zusammen.
pub(super) fn collect(from: &Path, extras: &CopyExtras, include_mods: bool, limits: Limits) -> std::result::Result<Vec<Entry>, TooLarge> {
    let mut roots = vec![from.to_owned()];
    roots.extend(extras.roots.iter().cloned());
    roots.extend(extras.mods_dir.iter().cloned());
    let mut walker = Walker::new(roots, limits);

    let game_mods = include_mods && !extras.skip_game_mods && extras.mods_dir.is_none();
    let skip_top = |name: &str| {
        is_excluded(name)
            || extras.excludes.iter().any(|e| e.eq_ignore_ascii_case(name))
            || (!game_mods && name.eq_ignore_ascii_case("mods"))
            || extras.disabled_mods_dir.is_some_and(|d| d.eq_ignore_ascii_case(name))
    };
    walker.walk(from, Path::new(""), 0, Some(&skip_top))?;

    if include_mods {
        if let Some(dir) = extras.disabled_mods_dir.filter(|_| game_mods) {
            walker.jars(&from.join(dir), ".disabled")?;
        }
        if let Some(dir) = &extras.mods_dir {
            walker.jars(dir, "")?;
        }
    }
    // Doppelte Ziele (z. B. `mods/x.jar` und `disabledmods/x.jar`): das erste gewinnt.
    let mut seen = HashSet::new();
    walker.files.retain(|e| seen.insert(e.rel.to_string_lossy().to_ascii_lowercase()));
    Ok(walker.files)
}

/// Nur einfache Namen – nie `..`, Laufwerke oder absolute Pfade.
fn safe_rel(rel: &Path) -> bool {
    rel.components().next().is_some() && rel.components().all(|c| matches!(c, Component::Normal(_)))
}

pub(super) fn too_large() -> Error {
    Error::validation(crate::msg!(
        "import.tooLarge",
        "Diese Installation ist zu groß für den Import (mehr als {files} Dateien oder {gb} GB).",
        files = MAX_FILES,
        gb = MAX_BYTES / (1024 * 1024 * 1024)
    ))
}

pub(super) fn copy_entries(entries: &[Entry], to: &Path, on_progress: &ImportProgressFn) -> Result<()> {
    let total_bytes: u64 = entries.iter().map(|e| e.size).sum::<u64>().max(1);
    let total_files = entries.len() as u64;
    let mut done_bytes = 0u64;
    let mut last_percent = -1.0;

    for (i, entry) in entries.iter().enumerate() {
        if !safe_rel(&entry.rel) {
            continue;
        }
        let target = to.join(&entry.rel);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
        }
        std::fs::copy(&entry.src, &target).map_err(|e| Error::io(&target, e))?;
        done_bytes += entry.size;
        let percent = (done_bytes as f64 / total_bytes as f64 * 100.0).min(100.0).floor();
        if percent > last_percent {
            last_percent = percent;
            on_progress(ImportProgress { percent, done_files: i as u64 + 1, total_files });
        }
    }
    on_progress(ImportProgress { percent: 100.0, done_files: total_files, total_files });
    Ok(())
}

/// Sammelt und kopiert alles, was zu einem Kandidaten gehört.
pub(super) fn copy_candidate(from: &Path, to: &Path, extras: &CopyExtras, include_mods: bool, on_progress: &ImportProgressFn) -> Result<()> {
    let entries = collect(from, extras, include_mods, Limits::default()).map_err(|TooLarge| too_large())?;
    copy_entries(&entries, to, on_progress)
}
