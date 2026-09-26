//! Gespeicherte Clips: `<Clip-Ordner>/<Instanz-ID>/<Name>.mp4`.
//!
//! Die Dauer steht im `mvhd`-Kasten der MP4-Datei (dank `+faststart` am
//! Dateianfang) – dafür braucht es kein FFmpeg.

use std::path::{Path, PathBuf};

use chrono::{DateTime, Local, Utc};
use serde::Serialize;

use crate::instance::validate_id;
use crate::paths::Paths;
use crate::{Error, Result};

use super::settings::ClipSettings;

/// Höchstens so viele Clips werden gelistet (neueste zuerst).
const MAX_LISTED: usize = 5000;
const MAX_FILE_NAME: usize = 180;

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Clip {
    pub instance_id: String,
    pub file_name: String,
    pub size: u64,
    pub created_at: Option<DateTime<Utc>>,
    /// Aus der MP4-Datei gelesen; `None`, wenn sie (noch) unlesbar ist.
    pub duration_ms: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipUsage {
    pub used_bytes: u64,
    pub limit_bytes: u64,
    pub count: u32,
    /// Freier Platz auf dem Laufwerk des Clip-Ordners (falls ermittelbar).
    pub free_bytes: Option<u64>,
}

/// Ordner, in dem alle Clips liegen.
pub fn clips_root(paths: &Paths, settings: &ClipSettings) -> PathBuf {
    match &settings.folder {
        Some(folder) => PathBuf::from(folder),
        None => paths.root().join("clips"),
    }
}

pub fn instance_dir(root: &Path, instance_id: &str) -> Result<PathBuf> {
    validate_id(instance_id)?;
    Ok(root.join(instance_id))
}

/// Erlaubter Clip-Dateiname: `<name>.mp4`, keine Pfadteile, keine Gerätenamen.
pub fn is_clip_file_name(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    let Some(stem) = lower.strip_suffix(".mp4") else { return false };
    !stem.trim().is_empty()
        && name.len() <= MAX_FILE_NAME
        && !name.starts_with('.')
        && !name.starts_with(' ')
        && !stem.ends_with('.')
        && !stem.ends_with(' ')
        && !name.chars().any(|c| c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|'))
        && !is_reserved(stem.split('.').next().unwrap_or(stem).trim())
}

fn is_reserved(stem: &str) -> bool {
    matches!(stem, "con" | "prn" | "aux" | "nul")
        || (stem.len() == 4 && (stem.starts_with("com") || stem.starts_with("lpt")) && stem.as_bytes()[3].is_ascii_digit())
}

/// Geprüfter Pfad eines vorhandenen Clips.
pub fn clip_path(root: &Path, instance_id: &str, file_name: &str) -> Result<PathBuf> {
    if !is_clip_file_name(file_name) {
        return Err(Error::validation(crate::msg!("content.invalidFileName", "Ungültiger Dateiname")));
    }
    let path = instance_dir(root, instance_id)?.join(file_name);
    // Keine Verknüpfungen: nur echte Dateien im Clip-Ordner der Instanz.
    if std::fs::symlink_metadata(&path).is_ok_and(|m| m.is_file()) {
        Ok(path)
    } else {
        Err(Error::validation(crate::msg!("clips.gone", "Der Clip existiert nicht mehr.")))
    }
}

/// Lesbarer Teil des Dateinamens aus dem Instanz-Namen.
pub fn sanitize_name(name: &str, fallback: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| if c.is_alphanumeric() || matches!(c, ' ' | '-' | '_' | '(' | ')' | '+' | '\'') { c } else { '_' })
        .collect();
    let cleaned = cleaned.split_whitespace().collect::<Vec<_>>().join(" ");
    let cleaned: String = cleaned.chars().take(60).collect();
    let cleaned = cleaned.trim_matches(|c: char| c == ' ' || c == '_').to_owned();
    if cleaned.is_empty() || is_reserved(&cleaned.to_ascii_lowercase()) { fallback.to_owned() } else { cleaned }
}

/// Neuer, noch freier Dateiname: `<Instanz> 2026-09-24 15-30-12.mp4`.
pub fn new_clip_path(root: &Path, instance_id: &str, instance_name: &str, at: DateTime<Local>) -> Result<PathBuf> {
    let dir = instance_dir(root, instance_id)?;
    let base = format!("{} {}", sanitize_name(instance_name, instance_id), at.format("%Y-%m-%d %H-%M-%S"));
    for n in 1..1000 {
        let name = if n == 1 { format!("{base}.mp4") } else { format!("{base} ({n}).mp4") };
        let path = dir.join(&name);
        if !path.exists() {
            return Ok(path);
        }
    }
    Err(Error::Internal("kein freier Clip-Name".into()))
}

/// Alle Clips aller Instanzen, neueste zuerst (blockierend – im `spawn_blocking` aufrufen).
pub fn list(root: &Path) -> Vec<Clip> {
    let mut clips = Vec::new();
    let Ok(dirs) = std::fs::read_dir(root) else { return clips };
    for dir in dirs.flatten() {
        let Some(instance_id) = dir.file_name().to_str().map(str::to_owned) else { continue };
        if validate_id(&instance_id).is_err() || !dir.path().is_dir() {
            continue;
        }
        let Ok(files) = std::fs::read_dir(dir.path()) else { continue };
        for file in files.flatten() {
            let Some(file_name) = file.file_name().to_str().map(str::to_owned) else { continue };
            let Ok(meta) = file.metadata() else { continue };
            if !meta.is_file() || !is_clip_file_name(&file_name) {
                continue;
            }
            clips.push(Clip {
                duration_ms: mp4_duration_ms(&file.path()),
                instance_id: instance_id.clone(),
                file_name,
                size: meta.len(),
                created_at: meta.modified().ok().map(DateTime::<Utc>::from),
            });
        }
    }
    clips.sort_by_key(|c| std::cmp::Reverse(c.created_at));
    clips.truncate(MAX_LISTED);
    clips
}

pub fn usage(root: &Path, settings: &ClipSettings) -> ClipUsage {
    let clips = list(root);
    ClipUsage {
        used_bytes: clips.iter().map(|c| c.size).sum(),
        limit_bytes: settings.max_storage_bytes(),
        count: u32::try_from(clips.len()).unwrap_or(u32::MAX),
        free_bytes: free_space(root),
    }
}

/// Benennt einen Clip um (gleicher Instanz-Ordner, Endung bleibt `.mp4`).
pub fn rename(root: &Path, instance_id: &str, file_name: &str, new_name: &str) -> Result<String> {
    let from = clip_path(root, instance_id, file_name)?;
    let mut target = new_name.trim().to_owned();
    if !target.to_ascii_lowercase().ends_with(".mp4") {
        target.push_str(".mp4");
    }
    if !is_clip_file_name(&target) {
        return Err(Error::validation(crate::msg!("clips.invalidName", "Dieser Name ist als Dateiname nicht erlaubt.")));
    }
    if target == file_name {
        return Ok(target);
    }
    let to = from.with_file_name(&target);
    // Nur Groß-/Kleinschreibung geändert? Windows sieht dieselbe Datei.
    if to.exists() && !target.eq_ignore_ascii_case(file_name) {
        return Err(Error::validation(crate::msg!("clips.nameTaken", "Es gibt schon einen Clip mit diesem Namen.")));
    }
    std::fs::rename(&from, &to).map_err(|e| Error::io(&to, e))?;
    Ok(target)
}

/// Hält den Clip-Ordner unter dem Limit: die ältesten Clips wandern in den
/// Papierkorb. `keep` (der gerade gespeicherte Clip) bleibt immer.
pub fn enforce_limit(root: &Path, limit_bytes: u64, keep: &Path) -> Vec<Clip> {
    let mut clips = list(root);
    let mut used: u64 = clips.iter().map(|c| c.size).sum();
    let mut removed = Vec::new();
    // Älteste zuerst.
    clips.reverse();
    for clip in clips {
        if used <= limit_bytes {
            break;
        }
        let path = root.join(&clip.instance_id).join(&clip.file_name);
        if path == keep {
            continue;
        }
        if discard(&path) {
            used = used.saturating_sub(clip.size);
            removed.push(clip);
        }
    }
    removed
}

/// In den Papierkorb (in Tests direkt löschen – nichts im Papierkorb des Nutzers ablegen).
fn discard(path: &Path) -> bool {
    if cfg!(test) {
        return std::fs::remove_file(path).is_ok();
    }
    crate::screenshots::recycle(path).is_ok() || std::fs::remove_file(path).is_ok()
}

#[cfg(windows)]
pub fn free_space(path: &Path) -> Option<u64> {
    use std::os::windows::ffi::OsStrExt;

    use windows::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
    use windows::core::PCWSTR;

    // Der Ordner existiert evtl. noch nicht – dann das nächste vorhandene Elternteil.
    let existing = path.ancestors().find(|p| p.exists())?;
    let wide: Vec<u16> = existing.as_os_str().encode_wide().chain(Some(0)).collect();
    let mut free = 0u64;
    // SAFETY: `wide` ist nullterminiert und lebt während des Aufrufs; `free` ist ein gültiger Zeiger.
    unsafe { GetDiskFreeSpaceExW(PCWSTR(wide.as_ptr()), Some(&raw mut free), None, None) }.ok()?;
    Some(free)
}

#[cfg(not(windows))]
pub fn free_space(_path: &Path) -> Option<u64> {
    None
}

/// Dauer aus `moov/mvhd`. Große `mdat`-Kästen werden übersprungen, nicht gelesen.
pub fn mp4_duration_ms(path: &Path) -> Option<u64> {
    super::media::mvhd_duration_ms(&super::media::read_moov(path)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mp4_with_duration(timescale: u32, duration: u32, moov_first: bool) -> Vec<u8> {
        let mut mvhd = Vec::new();
        mvhd.extend_from_slice(&[0, 0, 0, 0]); // Version 0 + Flags
        mvhd.extend_from_slice(&0u32.to_be_bytes()); // Erstellt
        mvhd.extend_from_slice(&0u32.to_be_bytes()); // Geändert
        mvhd.extend_from_slice(&timescale.to_be_bytes());
        mvhd.extend_from_slice(&duration.to_be_bytes());
        mvhd.extend_from_slice(&[0u8; 80]);
        let mvhd_box = [((mvhd.len() + 8) as u32).to_be_bytes().to_vec(), b"mvhd".to_vec(), mvhd].concat();
        let moov = [((mvhd_box.len() + 8) as u32).to_be_bytes().to_vec(), b"moov".to_vec(), mvhd_box].concat();
        let ftyp = [16u32.to_be_bytes().to_vec(), b"ftypisom".to_vec(), vec![0, 0, 2, 0]].concat();
        let mdat = [(8u32 + 1000).to_be_bytes().to_vec(), b"mdat".to_vec(), vec![0u8; 1000]].concat();
        if moov_first { [ftyp, moov, mdat].concat() } else { [ftyp, mdat, moov].concat() }
    }

    #[test]
    fn dauer_wird_aus_mvhd_gelesen() {
        let dir = tempfile::tempdir().unwrap();
        let a = dir.path().join("a.mp4");
        std::fs::write(&a, mp4_with_duration(1000, 30_500, true)).unwrap();
        assert_eq!(mp4_duration_ms(&a), Some(30_500));
        let b = dir.path().join("b.mp4");
        std::fs::write(&b, mp4_with_duration(90_000, 90_000 * 12, false)).unwrap();
        assert_eq!(mp4_duration_ms(&b), Some(12_000));
        let c = dir.path().join("c.mp4");
        std::fs::write(&c, b"kein mp4").unwrap();
        assert_eq!(mp4_duration_ms(&c), None);
    }

    #[test]
    fn dateinamen_werden_streng_geprueft() {
        assert!(is_clip_file_name("Survival 2026-09-24 15-30-12.mp4"));
        assert!(is_clip_file_name("Mein Clip (2).MP4"));
        assert!(!is_clip_file_name("clip.mkv"));
        assert!(!is_clip_file_name(".mp4"));
        assert!(!is_clip_file_name("..\\x.mp4"));
        assert!(!is_clip_file_name("a/b.mp4"));
        assert!(!is_clip_file_name("con.mp4"));
        assert!(!is_clip_file_name("COM1.mp4"));
        assert!(!is_clip_file_name("name .mp4"));
        assert!(!is_clip_file_name(&format!("{}.mp4", "x".repeat(200))));
    }

    #[test]
    fn instanzname_wird_zum_dateinamen() {
        assert_eq!(sanitize_name("Mein Survival: Teil 2/3", "id"), "Mein Survival_ Teil 2_3");
        assert_eq!(sanitize_name("***", "fallback"), "fallback");
        assert_eq!(sanitize_name("CON", "fallback"), "fallback");
        let at = chrono::TimeZone::with_ymd_and_hms(&Local, 2026, 9, 24, 15, 30, 12).unwrap();
        let dir = tempfile::tempdir().unwrap();
        let p = new_clip_path(dir.path(), "survival", "Survival", at).unwrap();
        assert_eq!(p.file_name().unwrap(), "Survival 2026-09-24 15-30-12.mp4");
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(&p, b"x").unwrap();
        let q = new_clip_path(dir.path(), "survival", "Survival", at).unwrap();
        assert_eq!(q.file_name().unwrap(), "Survival 2026-09-24 15-30-12 (2).mp4");
        assert!(new_clip_path(dir.path(), "../x", "x", at).is_err());
    }

    #[test]
    fn liste_umbenennen_und_limit() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("survival")).unwrap();
        std::fs::create_dir_all(root.join("Kein Instanzordner")).unwrap();
        std::fs::write(root.join("survival/alt.mp4"), vec![0u8; 600]).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(30));
        std::fs::write(root.join("survival/neu.mp4"), mp4_with_duration(1000, 5000, true)).unwrap();
        std::fs::write(root.join("survival/notizen.txt"), b"x").unwrap();
        std::fs::write(root.join("Kein Instanzordner/x.mp4"), b"x").unwrap();

        let clips = list(root);
        assert_eq!(clips.iter().map(|c| c.file_name.as_str()).collect::<Vec<_>>(), ["neu.mp4", "alt.mp4"]);
        assert_eq!(clips[0].duration_ms, Some(5000));

        assert!(rename(root, "survival", "neu.mp4", "alt").is_err(), "Name vergeben");
        assert!(rename(root, "survival", "neu.mp4", "a/b").is_err());
        assert_eq!(rename(root, "survival", "neu.mp4", " Bester Clip ").unwrap(), "Bester Clip.mp4");
        assert!(root.join("survival/Bester Clip.mp4").is_file());

        let keep = root.join("survival/Bester Clip.mp4");
        let removed = enforce_limit(root, 200, &keep);
        assert_eq!(removed.len(), 1);
        assert_eq!(removed[0].file_name, "alt.mp4");
        assert!(keep.is_file(), "der neue Clip bleibt immer");
        assert!(clip_path(root, "survival", "alt.mp4").is_err());
    }
}
