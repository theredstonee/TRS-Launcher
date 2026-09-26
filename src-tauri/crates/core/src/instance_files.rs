//! Dateibrowser einer Instanz (Tab „Dateien“).
//!
//! Alles läuft strikt im Spielordner der Instanz (`instances/<id>/minecraft`):
//! Pfade kommen vom Webview nur als relative Namen (`config/sodium.json`),
//! jede Komponente wird einzeln geprüft (kein `..`, keine Laufwerke, keine
//! reservierten Windows-Namen), Symlinks und Junctions werden nie betreten,
//! und am Ende muss der kanonische Pfad noch im kanonischen Wurzelordner
//! liegen. Dateien mit Geheimnissen (z. B. der Clip-Schlüssel des TRS
//! Clients) sind weder sichtbar noch bearbeitbar.

use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use serde::Serialize;

use crate::instance::validate_id;
use crate::paths::Paths;
use crate::{Error, Result};

/// Höchstens so viele Einträge je Ordner (Rest wird abgeschnitten).
const MAX_LISTED: usize = 10_000;
/// Tiefe relativer Pfade.
const MAX_DEPTH: usize = 32;
/// Länge eines Namens in Bytes (NTFS/ext4: 255).
const MAX_NAME_BYTES: usize = 255;
/// Einträge, die auf einmal gelöscht werden dürfen.
pub const MAX_DELETE: usize = 1000;
/// Hochladen (Dateidialog oder Drag & Drop): Grenzen je Vorgang.
pub const MAX_IMPORT_FILE_BYTES: u64 = 2 * 1024 * 1024 * 1024;
pub const MAX_IMPORT_TOTAL_BYTES: u64 = 4 * 1024 * 1024 * 1024;
pub const MAX_IMPORT_ENTRIES: usize = 20_000;

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub name: String,
    pub dir: bool,
    /// Bytes (Ordner: 0 – die Größe wird nicht rekursiv berechnet).
    pub size: u64,
    pub created: Option<DateTime<Utc>>,
    pub modified: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DirListing {
    /// Normalisierter relativer Pfad mit `/` (Wurzel = "").
    pub path: String,
    pub entries: Vec<FileEntry>,
    /// Mehr als [`MAX_LISTED`] Einträge – der Rest fehlt.
    pub truncated: bool,
}

#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ImportReport {
    /// Kopierte Dateien (ohne Ordner).
    pub files: u32,
    pub bytes: u64,
    /// Oberste Einträge, die übersprungen wurden (Name + Grund-Code).
    pub skipped: Vec<SkippedImport>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SkippedImport {
    pub name: String,
    /// `symlink` | `invalidName` | `unsupported` | `inside`
    pub reason: &'static str,
}

fn denied() -> Error {
    Error::validation(crate::msg!("files.denied", "Auf diesen Pfad hat der Dateibrowser keinen Zugriff."))
}

fn invalid_name() -> Error {
    Error::validation(crate::msg!("files.invalidName", "Ungültiger Datei- oder Ordnername"))
}

fn not_found() -> Error {
    Error::validation(crate::msg!("files.notFound", "Die Datei oder der Ordner existiert nicht mehr."))
}

fn exists() -> Error {
    Error::validation(crate::msg!("files.exists", "Hier gibt es schon eine Datei oder einen Ordner mit diesem Namen."))
}

fn is_reserved_windows_name(name: &str) -> bool {
    // „CON“, „nul.txt“, „COM1.log“ … sind unter Windows Geräte, egal mit welcher Endung.
    let stem = name.split('.').next().unwrap_or(name).trim_end().to_ascii_uppercase();
    matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$")
        || ((stem.starts_with("COM") || stem.starts_with("LPT"))
            && stem.len() == 4
            && stem[3..].chars().all(|c| c.is_ascii_digit() || matches!(c, '¹' | '²' | '³')))
}

/// Ein einzelner Datei- oder Ordnername (kein Pfad).
pub fn validate_name(name: &str) -> Result<()> {
    let ok = !name.is_empty()
        && name.len() <= MAX_NAME_BYTES
        && name != "."
        && name != ".."
        && !name.ends_with(['.', ' '])
        && !name.starts_with(' ')
        && !name.chars().any(|c| c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|'))
        && !is_reserved_windows_name(name);
    if ok { Ok(()) } else { Err(invalid_name()) }
}

/// Dateien mit Geheimnissen – im Browser unsichtbar und gesperrt.
/// `parts` ist der relative Pfad ab dem Spielordner.
pub fn is_sensitive(parts: &[String]) -> bool {
    let Some(last) = parts.last() else { return false };
    let name = last.to_ascii_lowercase();
    let joined = parts.iter().map(|p| p.to_ascii_lowercase()).collect::<Vec<_>>().join("/");
    // Clip-Schlüssel für den TRS Client (127.0.0.1-Token).
    joined == "config/trsclient/clips.json"
        // Konto-Dateien anderer Launcher, falls jemand sie hineinkopiert hat.
        || (name.contains("account") && name.ends_with(".json"))
        || name == "launcher_profiles.json"
        || name.ends_with(".token")
        || matches!(name.as_str(), "trs-api.json" | "trs-sync.json" | "link-sessions.json")
}

/// Relativer Pfad → geprüfte Komponenten. Leer = Wurzel. Trennzeichen `/` und `\`.
pub fn split_rel(rel: &str) -> Result<Vec<String>> {
    if rel.len() > 4096 {
        return Err(invalid_name());
    }
    let mut parts = Vec::new();
    for part in rel.split(['/', '\\']) {
        if part.is_empty() {
            continue;
        }
        validate_name(part)?;
        parts.push(part.to_owned());
    }
    if parts.len() > MAX_DEPTH {
        return Err(invalid_name());
    }
    if is_sensitive(&parts) {
        return Err(denied());
    }
    Ok(parts)
}

fn to_utc(time: std::io::Result<std::time::SystemTime>) -> Option<DateTime<Utc>> {
    time.ok().map(DateTime::<Utc>::from)
}

/// Der Spielordner einer Instanz als „Gefängnis“ für alle Dateioperationen.
#[derive(Debug, Clone)]
pub struct Jail {
    /// Wie übergeben – daraus werden alle Pfade gebaut. Unter Windows liefert
    /// `canonicalize` Pfade mit `\\?\`-Präfix, mit denen Papierkorb und Explorer nicht klarkommen.
    root: PathBuf,
    /// Kanonisch – nur für die Prüfung „liegt noch darin“.
    canonical: PathBuf,
}

impl Jail {
    pub fn for_instance(paths: &Paths, instance_id: &str) -> Result<Self> {
        validate_id(instance_id)?;
        Self::new(&paths.instance_game_dir(instance_id))
    }

    /// Legt den Ordner bei Bedarf an; der Wurzelpfad wird kanonisiert.
    pub fn new(dir: &Path) -> Result<Self> {
        std::fs::create_dir_all(dir).map_err(|e| Error::io(dir, e))?;
        let meta = std::fs::symlink_metadata(dir).map_err(|e| Error::io(dir, e))?;
        if meta.file_type().is_symlink() {
            // Ein verlinkter Spielordner (z. B. auf eine andere Platte) ist erlaubt –
            // die Prüfungen laufen dann gegen das kanonische Ziel.
            tracing::debug!("Spielordner ist ein Link: {}", dir.display());
        }
        let canonical = dir.canonicalize().map_err(|e| Error::io(dir, e))?;
        Ok(Self { root: dir.to_path_buf(), canonical })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Existierender Pfad ohne Symlink-Komponenten, sicher im Wurzelordner.
    pub fn resolve(&self, rel: &str) -> Result<(PathBuf, Vec<String>)> {
        let parts = split_rel(rel)?;
        let mut path = self.root.clone();
        for part in &parts {
            path.push(part);
            match std::fs::symlink_metadata(&path) {
                // Unter Windows gelten auch Junctions als Symlink (Reparse-Punkt).
                Ok(meta) if meta.file_type().is_symlink() => return Err(denied()),
                Ok(_) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Err(not_found()),
                Err(e) => return Err(Error::io(&path, e)),
            }
        }
        let canonical = path.canonicalize().map_err(|e| Error::io(&path, e))?;
        if !canonical.starts_with(&self.canonical) {
            return Err(denied());
        }
        Ok((path, parts))
    }

    /// Existierender Ordner.
    pub fn resolve_dir(&self, rel: &str) -> Result<(PathBuf, Vec<String>)> {
        let (path, parts) = self.resolve(rel)?;
        if !path.is_dir() {
            return Err(not_found());
        }
        Ok((path, parts))
    }

    /// Neuer Eintrag `name` in Ordner `parent` – darf noch nicht existieren.
    pub fn resolve_new(&self, parent: &str, name: &str) -> Result<(PathBuf, Vec<String>)> {
        validate_name(name)?;
        let (dir, mut parts) = self.resolve_dir(parent)?;
        parts.push(name.to_owned());
        if is_sensitive(&parts) {
            return Err(denied());
        }
        let path = dir.join(name);
        if std::fs::symlink_metadata(&path).is_ok() {
            return Err(exists());
        }
        Ok((path, parts))
    }

    pub fn list(&self, rel: &str) -> Result<DirListing> {
        let (dir, parts) = self.resolve_dir(rel)?;
        let mut entries = Vec::new();
        let mut truncated = false;
        let read = std::fs::read_dir(&dir).map_err(|e| Error::io(&dir, e))?;
        for entry in read {
            let Ok(entry) = entry else { continue };
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
            // Symlinks/Junctions werden nicht gezeigt – sie könnten aus der Instanz herausführen.
            let Ok(kind) = entry.file_type() else { continue };
            if kind.is_symlink() || validate_name(&name).is_err() {
                continue;
            }
            let mut child = parts.clone();
            child.push(name.clone());
            if is_sensitive(&child) {
                continue;
            }
            if entries.len() >= MAX_LISTED {
                truncated = true;
                break;
            }
            let Ok(meta) = entry.metadata() else { continue };
            entries.push(FileEntry {
                name,
                dir: meta.is_dir(),
                size: if meta.is_dir() { 0 } else { meta.len() },
                created: to_utc(meta.created()),
                modified: to_utc(meta.modified()),
            });
        }
        entries.sort_by(|a, b| b.dir.cmp(&a.dir).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
        Ok(DirListing { path: parts.join("/"), entries, truncated })
    }

    pub fn create_dir(&self, parent: &str, name: &str) -> Result<String> {
        let (path, parts) = self.resolve_new(parent, name)?;
        std::fs::create_dir(&path).map_err(|e| Error::io(&path, e))?;
        Ok(parts.join("/"))
    }

    pub fn create_file(&self, parent: &str, name: &str) -> Result<String> {
        let (path, parts) = self.resolve_new(parent, name)?;
        std::fs::OpenOptions::new().write(true).create_new(true).open(&path).map_err(|e| Error::io(&path, e))?;
        Ok(parts.join("/"))
    }

    /// Umbenennen im selben Ordner.
    pub fn rename(&self, rel: &str, new_name: &str) -> Result<String> {
        validate_name(new_name)?;
        let (path, mut parts) = self.resolve(rel)?;
        if parts.is_empty() {
            return Err(denied());
        }
        let old = parts.pop().unwrap_or_default();
        if old == new_name {
            parts.push(old);
            return Ok(parts.join("/"));
        }
        parts.push(new_name.to_owned());
        if is_sensitive(&parts) {
            return Err(denied());
        }
        let target = path.with_file_name(new_name);
        // Nur Groß-/Kleinschreibung geändert: unter Windows „existiert“ das Ziel schon.
        let case_only = old.to_lowercase() == new_name.to_lowercase();
        if !case_only && std::fs::symlink_metadata(&target).is_ok() {
            return Err(exists());
        }
        std::fs::rename(&path, &target).map_err(|e| Error::io(&path, e))?;
        Ok(parts.join("/"))
    }

    /// Einträge in den Papierkorb (Windows) bzw. XDG-Trash (Linux).
    pub fn trash(&self, rels: &[String]) -> Result<u32> {
        if rels.len() > MAX_DELETE {
            return Err(Error::validation(crate::msg!("files.tooMany", "Höchstens {max} Einträge auf einmal", max = MAX_DELETE)));
        }
        let mut targets = Vec::new();
        for rel in rels {
            let (path, parts) = self.resolve(rel)?;
            if parts.is_empty() {
                return Err(denied());
            }
            targets.push(path);
        }
        let mut done = 0;
        for path in targets {
            // Schon mit dem übergeordneten Ordner weg (Mehrfachauswahl)?
            if std::fs::symlink_metadata(&path).is_err() {
                continue;
            }
            crate::platform::move_to_trash(&path)?;
            done += 1;
        }
        Ok(done)
    }

    /// Kopiert Dateien/Ordner von außen (Dateidialog oder Drag & Drop) in `parent`.
    /// Bei Namensgleichheit wird „Name (2).ext“ gewählt. Symlinks werden nicht kopiert.
    pub fn import(&self, parent: &str, sources: &[PathBuf]) -> Result<ImportReport> {
        let (dir, parent_parts) = self.resolve_dir(parent)?;
        let mut report = ImportReport::default();

        // 1. Planen: Grenzen prüfen, bevor irgendetwas kopiert wird.
        let mut plan: Vec<(PathBuf, String)> = Vec::new();
        let mut total_bytes = 0u64;
        let mut total_entries = 0usize;
        for source in sources {
            let name = source.file_name().and_then(|n| n.to_str()).unwrap_or_default().to_owned();
            let label: String = name.chars().take(120).collect();
            let meta = match std::fs::symlink_metadata(source) {
                Ok(meta) => meta,
                Err(_) => {
                    report.skipped.push(SkippedImport { name: label, reason: "unsupported" });
                    continue;
                }
            };
            if meta.file_type().is_symlink() {
                report.skipped.push(SkippedImport { name: label, reason: "symlink" });
                continue;
            }
            if validate_name(&name).is_err() {
                report.skipped.push(SkippedImport { name: label, reason: "invalidName" });
                continue;
            }
            let mut child = parent_parts.clone();
            child.push(name.clone());
            if is_sensitive(&child) {
                report.skipped.push(SkippedImport { name: label, reason: "invalidName" });
                continue;
            }
            // Einen Ordner in sich selbst kopieren ergäbe eine Endlosschleife.
            if meta.is_dir()
                && let Ok(canonical) = source.canonicalize()
                && dir.canonicalize().is_ok_and(|d| d.starts_with(&canonical))
            {
                report.skipped.push(SkippedImport { name: label, reason: "inside" });
                continue;
            }
            if !meta.is_dir() && !meta.is_file() {
                report.skipped.push(SkippedImport { name: label, reason: "unsupported" });
                continue;
            }
            measure(source, &meta, &mut total_bytes, &mut total_entries, 0)?;
            plan.push((source.clone(), name));
        }
        if total_bytes > MAX_IMPORT_TOTAL_BYTES || total_entries > MAX_IMPORT_ENTRIES {
            return Err(Error::validation(crate::msg!(
                "files.importTooLarge",
                "Zu viel auf einmal: höchstens {gb} GB bzw. {max} Dateien je Vorgang.",
                gb = MAX_IMPORT_TOTAL_BYTES >> 30,
                max = MAX_IMPORT_ENTRIES
            )));
        }

        // 2. Kopieren.
        for (source, name) in plan {
            let target = dir.join(free_name(&dir, &name));
            let result = if source.is_dir() { copy_tree(&source, &target, &mut report, 0) } else { copy_file(&source, &target, &mut report) };
            if let Err(e) = result {
                // Halb kopierte Einträge nicht liegen lassen.
                let _ = if target.is_dir() { std::fs::remove_dir_all(&target) } else { std::fs::remove_file(&target) };
                return Err(e);
            }
        }
        Ok(report)
    }

    /// Geprüfter absoluter Pfad (zum Öffnen oder Zeigen im Explorer).
    pub fn existing_path(&self, rel: &str) -> Result<PathBuf> {
        Ok(self.resolve(rel)?.0)
    }
}

fn too_large() -> Error {
    Error::validation(crate::msg!(
        "files.fileTooLarge",
        "Einzelne Dateien dürfen höchstens {gb} GB groß sein.",
        gb = MAX_IMPORT_FILE_BYTES >> 30
    ))
}

fn measure(path: &Path, meta: &std::fs::Metadata, bytes: &mut u64, entries: &mut usize, depth: usize) -> Result<()> {
    *entries += 1;
    if *entries > MAX_IMPORT_ENTRIES || depth > MAX_DEPTH {
        return Err(Error::validation(crate::msg!(
            "files.importTooLarge",
            "Zu viel auf einmal: höchstens {gb} GB bzw. {max} Dateien je Vorgang.",
            gb = MAX_IMPORT_TOTAL_BYTES >> 30,
            max = MAX_IMPORT_ENTRIES
        )));
    }
    if meta.is_file() {
        if meta.len() > MAX_IMPORT_FILE_BYTES {
            return Err(too_large());
        }
        *bytes += meta.len();
        return Ok(());
    }
    if meta.is_dir() {
        for entry in std::fs::read_dir(path).map_err(|e| Error::io(path, e))? {
            let Ok(entry) = entry else { continue };
            let Ok(kind) = entry.file_type() else { continue };
            if kind.is_symlink() {
                continue;
            }
            let Ok(child) = entry.metadata() else { continue };
            measure(&entry.path(), &child, bytes, entries, depth + 1)?;
        }
    }
    Ok(())
}

/// „Name.ext“ → „Name (2).ext“, „Name (3).ext“ … bis frei.
pub fn free_name(dir: &Path, name: &str) -> String {
    if std::fs::symlink_metadata(dir.join(name)).is_err() {
        return name.to_owned();
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    for n in 2..10_000 {
        let candidate = format!("{stem} ({n}){ext}");
        if std::fs::symlink_metadata(dir.join(&candidate)).is_err() {
            return candidate;
        }
    }
    format!("{stem} ({}){ext}", uuid::Uuid::new_v4().simple())
}

fn copy_file(source: &Path, target: &Path, report: &mut ImportReport) -> Result<()> {
    let bytes = std::fs::copy(source, target).map_err(|e| Error::io(source, e))?;
    report.files += 1;
    report.bytes += bytes;
    Ok(())
}

fn copy_tree(source: &Path, target: &Path, report: &mut ImportReport, depth: usize) -> Result<()> {
    if depth > MAX_DEPTH {
        return Err(invalid_name());
    }
    std::fs::create_dir(target).map_err(|e| Error::io(target, e))?;
    for entry in std::fs::read_dir(source).map_err(|e| Error::io(source, e))? {
        let Ok(entry) = entry else { continue };
        let Ok(kind) = entry.file_type() else { continue };
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
        // Symlinks und ungültige Namen bleiben draußen.
        if kind.is_symlink() || validate_name(&name).is_err() {
            continue;
        }
        let to = target.join(&name);
        if kind.is_dir() {
            copy_tree(&entry.path(), &to, report, depth + 1)?;
        } else if kind.is_file() {
            copy_file(&entry.path(), &to, report)?;
        }
    }
    Ok(())
}

/// Dateien, die „Öffnen“ direkt mit dem Standardprogramm starten darf. Alles
/// andere (z. B. `.jar`, `.exe`, `.bat`, Skripte) wird nur im Explorer gezeigt –
/// ein Doppelklick im Launcher soll nie Programme ausführen.
pub fn opens_directly(name: &str) -> bool {
    const SAFE: [&str; 26] = [
        "txt", "log", "json", "json5", "toml", "cfg", "conf", "properties", "ini", "yml", "yaml", "md", "csv", "snbt",
        "mcmeta", "lang", "png", "jpg", "jpeg", "gif", "webp", "bmp", "ogg", "mp3", "wav", "mp4",
    ];
    let lower = name.to_ascii_lowercase();
    lower.rsplit_once('.').is_some_and(|(stem, ext)| !stem.is_empty() && SAFE.contains(&ext))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn jail() -> (tempfile::TempDir, Jail) {
        let dir = tempfile::tempdir().unwrap();
        let game = dir.path().join("game");
        std::fs::create_dir_all(game.join("config/trsclient")).unwrap();
        std::fs::create_dir_all(game.join("mods")).unwrap();
        std::fs::write(game.join("options.txt"), b"fov:70").unwrap();
        std::fs::write(game.join("mods/a.jar"), b"jar").unwrap();
        std::fs::write(game.join("config/trsclient/clips.json"), b"{\"token\":\"x\"}").unwrap();
        std::fs::write(game.join("config/trsclient/launcher-theme.json"), b"{}").unwrap();
        std::fs::write(dir.path().join("outside.txt"), b"geheim").unwrap();
        let jail = Jail::new(&game).unwrap();
        (dir, jail)
    }

    #[test]
    fn names_are_checked() {
        for bad in ["", ".", "..", "a/b", "a\\b", "C:", "con", "NUL.txt", "com1.log", "lpt9", "x?", "a\0b", "trail.", "trail ", " lead"] {
            assert!(validate_name(bad).is_err(), "{bad:?} sollte ungültig sein");
        }
        for good in ["mods", "options.txt", "Meine Welt", ".minecraft", "console.log", "com10", "config"] {
            assert!(validate_name(good).is_ok(), "{good:?} sollte gültig sein");
        }
        assert!(validate_name(&"a".repeat(256)).is_err());
    }

    #[test]
    fn traversal_is_rejected() {
        let (_dir, jail) = jail();
        for bad in ["..", "../outside.txt", "mods/../../outside.txt", "..\\outside.txt", "C:\\Windows", "/etc/passwd", "mods/./a.jar"] {
            assert!(jail.resolve(bad).is_err(), "{bad:?} darf nicht aufgelöst werden");
        }
        // Führende/doppelte Trenner sind harmlos und landen im Wurzelordner.
        assert_eq!(jail.resolve("/mods//a.jar").unwrap().1, vec!["mods".to_owned(), "a.jar".to_owned()]);
        assert!(jail.resolve("").unwrap().1.is_empty());
    }

    #[test]
    fn listing_hides_secrets_and_sorts_folders_first() {
        let (_dir, jail) = jail();
        let root = jail.list("").unwrap();
        let names: Vec<_> = root.entries.iter().map(|e| e.name.as_str()).collect();
        assert_eq!(names, ["config", "mods", "options.txt"]);
        assert!(root.entries[0].dir && !root.entries[2].dir);
        assert_eq!(root.entries[2].size, 6);

        let trs = jail.list("config/trsclient").unwrap();
        assert_eq!(trs.path, "config/trsclient");
        assert_eq!(trs.entries.iter().map(|e| e.name.as_str()).collect::<Vec<_>>(), ["launcher-theme.json"]);
        assert!(jail.resolve("config/trsclient/clips.json").is_err());
        assert!(jail.resolve("config\\TRSCLIENT\\Clips.json").is_err());
        assert!(jail.rename("config/trsclient/launcher-theme.json", "clips.json").is_err());
        assert!(jail.create_file("config/trsclient", "clips.json").is_err());
        assert!(jail.trash(&["config/trsclient/clips.json".into()]).is_err());
        assert!(jail.list("mods/a.jar").is_err());
        assert!(jail.list("fehlt").is_err());
    }

    #[test]
    fn create_rename_and_conflicts() {
        let (_dir, jail) = jail();
        assert_eq!(jail.create_dir("", "neu").unwrap(), "neu");
        assert!(jail.create_dir("", "neu").is_err());
        assert_eq!(jail.create_file("neu", "notiz.txt").unwrap(), "neu/notiz.txt");
        assert!(jail.root().join("neu/notiz.txt").is_file());
        assert!(jail.create_file("neu", "../ausbruch.txt").is_err());

        assert_eq!(jail.rename("neu/notiz.txt", "Notiz.txt").unwrap(), "neu/Notiz.txt");
        assert!(jail.root().join("neu/Notiz.txt").is_file());
        assert!(jail.rename("neu/Notiz.txt", "../x.txt").is_err());
        assert!(jail.rename("options.txt", "mods").is_err());
        assert!(jail.rename("", "x").is_err());
    }

    #[test]
    fn import_copies_with_limits_and_free_names() {
        let (dir, jail) = jail();
        let outside = dir.path().join("upload");
        std::fs::create_dir_all(outside.join("pack/sub")).unwrap();
        std::fs::write(outside.join("pack/sub/x.txt"), b"hallo").unwrap();
        std::fs::write(outside.join("options.txt"), b"neu").unwrap();

        let report = jail.import("", &[outside.join("pack"), outside.join("options.txt"), outside.join("fehlt.txt")]).unwrap();
        assert_eq!(report.files, 2);
        assert_eq!(report.bytes, 8);
        assert_eq!(report.skipped.len(), 1);
        assert!(jail.root().join("pack/sub/x.txt").is_file());
        // Vorhandene options.txt bleibt, die neue heißt „options (2).txt“.
        assert_eq!(std::fs::read(jail.root().join("options.txt")).unwrap(), b"fov:70");
        assert_eq!(std::fs::read(jail.root().join("options (2).txt")).unwrap(), b"neu");

        // Den Spielordner in sich selbst zu kopieren wird abgelehnt.
        let report = jail.import("mods", &[jail.root().to_path_buf()]).unwrap();
        assert_eq!(report.skipped[0].reason, "inside");
        assert!(jail.import("../", &[outside.join("options.txt")]).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn symlinks_are_never_followed() {
        let (dir, jail) = jail();
        std::os::unix::fs::symlink(dir.path(), jail.root().join("ausbruch")).unwrap();
        std::os::unix::fs::symlink(dir.path().join("outside.txt"), jail.root().join("link.txt")).unwrap();
        let names: Vec<_> = jail.list("").unwrap().entries.into_iter().map(|e| e.name).collect();
        assert!(!names.contains(&"ausbruch".to_owned()) && !names.contains(&"link.txt".to_owned()));
        assert!(jail.resolve("ausbruch/outside.txt").is_err());
        assert!(jail.resolve("link.txt").is_err());
        assert!(jail.list("ausbruch").is_err());
    }

    #[cfg(windows)]
    #[test]
    fn junctions_are_never_followed() {
        let (dir, jail) = jail();
        let link = jail.root().join("ausbruch");
        let status = std::process::Command::new("cmd")
            .args(["/C", "mklink", "/J"])
            .arg(&link)
            .arg(dir.path())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status();
        if !status.is_ok_and(|s| s.success()) {
            return; // mklink nicht verfügbar – nichts zu prüfen.
        }
        assert!(!jail.list("").unwrap().entries.iter().any(|e| e.name == "ausbruch"));
        assert!(jail.resolve("ausbruch/outside.txt").is_err());
        assert!(jail.list("ausbruch").is_err());
        // Aufräumen: nur die Junction entfernen, nie ihr Ziel.
        std::fs::remove_dir(&link).unwrap();
        assert!(dir.path().join("outside.txt").is_file());
    }

    #[test]
    fn only_harmless_files_open_directly() {
        assert!(opens_directly("options.txt"));
        assert!(opens_directly("latest.LOG"));
        assert!(opens_directly("screenshot.png"));
        for name in ["mod.jar", "run.bat", "x.exe", "script.ps1", "start.sh", "noext", ".txt", "a.lnk", "b.vbs"] {
            assert!(!opens_directly(name), "{name} darf nicht direkt geöffnet werden");
        }
    }

    #[test]
    fn free_names_count_up() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(free_name(dir.path(), "a.txt"), "a.txt");
        std::fs::write(dir.path().join("a.txt"), b"").unwrap();
        std::fs::write(dir.path().join("a (2).txt"), b"").unwrap();
        assert_eq!(free_name(dir.path(), "a.txt"), "a (3).txt");
        std::fs::create_dir(dir.path().join("ordner")).unwrap();
        assert_eq!(free_name(dir.path(), "ordner"), "ordner (2)");
        std::fs::write(dir.path().join(".hidden"), b"").unwrap();
        assert_eq!(free_name(dir.path(), ".hidden"), ".hidden (2)");
    }
}
