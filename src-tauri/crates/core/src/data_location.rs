//! Wo die Launcher-Daten liegen.
//!
//! Reihenfolge: `TRS_LAUNCHER_HOME` (Entwicklung/Tests) > portabel (`portable.txt`
//! bzw. `.portable` neben der EXE → `<exe-ordner>/data`) > eigener Ordner aus der
//! Zeiger-Datei > Standard `<app-daten>/TRS-Launcher`.
//!
//! Die Zeiger-Datei (`<app-daten>/TRS-Launcher-location.json`) liegt bewusst
//! außerhalb des Datenordners – sonst fände der Launcher sie nach dem Umzug nicht.

use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use serde::{Deserialize, Serialize};

use crate::{Error, LAUNCHER_NAME, Result, fsutil};

/// Überschreibt das Datenverzeichnis – praktisch für Entwicklung und Tests.
pub const HOME_ENV: &str = "TRS_LAUNCHER_HOME";
/// Marker-Dateien neben der EXE für den portablen Modus.
pub const PORTABLE_MARKERS: [&str; 2] = ["portable.txt", ".portable"];
/// Datenordner im portablen Modus (neben der EXE).
pub const PORTABLE_DATA_DIR: &str = "data";
/// Zeiger auf einen eigenen Datenordner (im Standard-App-Datenordner, nicht darin).
pub const POINTER_FILE: &str = "TRS-Launcher-location.json";
/// Liegt nach einem Umzug im ALTEN Ordner und nennt den neuen – nur dann darf der alte gelöscht werden.
pub const MOVED_MARKER: &str = ".trs-moved-to";

const MAX_POINTER_SIZE: u64 = 16 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum RootSource {
    /// Standardordner im App-Datenordner des Benutzers.
    Default,
    /// Vom Nutzer gewählter Ordner (Zeiger-Datei).
    Custom,
    /// Portable Installation (Marker neben der EXE).
    Portable,
    /// `TRS_LAUNCHER_HOME`.
    Env,
}

/// Ergebnis der Auflösung beim Start.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DataLocation {
    pub root: PathBuf,
    pub source: RootSource,
    /// Standardordner (Ziel für „zurück zum Standard“).
    pub default_root: PathBuf,
    /// Eigener Ordner laut Zeiger-Datei, der gerade fehlt (Laufwerk getrennt) – dann gilt der Standard.
    pub missing_custom: Option<PathBuf>,
    #[serde(skip)]
    pub pointer_file: PathBuf,
}

impl DataLocation {
    /// Nur Standard- oder eigener Ordner lassen sich verschieben (nicht portabel, nicht per Umgebungsvariable).
    pub fn movable(&self) -> bool {
        !crate::platform::MOBILE && matches!(self.source, RootSource::Default | RootSource::Custom)
    }
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Pointer {
    /// Eigener Datenordner; `None` = Standard.
    #[serde(default)]
    pub root: Option<PathBuf>,
    /// Alter Ordner, der beim nächsten Start gelöscht wird (nur mit passender Umzugs-Marke).
    #[serde(default)]
    pub delete_after_move: Option<PathBuf>,
}

/// Ordner der laufenden EXE.
pub fn exe_dir() -> Option<PathBuf> {
    std::env::current_exe().ok()?.parent().map(Path::to_path_buf)
}

/// `<exe-ordner>/data`, wenn neben der EXE eine Marker-Datei liegt.
pub fn portable_root(exe_dir: &Path) -> Option<PathBuf> {
    PORTABLE_MARKERS.iter().any(|m| exe_dir.join(m).is_file()).then(|| exe_dir.join(PORTABLE_DATA_DIR))
}

/// Läuft der Launcher portabel (Marker neben der EXE)? Einmal ermittelt.
pub fn is_portable() -> bool {
    static PORTABLE: OnceLock<bool> = OnceLock::new();
    *PORTABLE.get_or_init(|| exe_dir().is_some_and(|d| portable_root(&d).is_some()))
}

/// Liest die Zeiger-Datei; fehlt sie oder ist sie kaputt, gilt der Standard.
pub fn read_pointer(file: &Path) -> Pointer {
    let Ok(meta) = std::fs::metadata(file) else { return Pointer::default() };
    if !meta.is_file() || meta.len() > MAX_POINTER_SIZE {
        return Pointer::default();
    }
    let pointer: Pointer = std::fs::read(file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
    // Nur absolute Pfade – alles andere ignorieren.
    Pointer {
        root: pointer.root.filter(|p| p.is_absolute()),
        delete_after_move: pointer.delete_after_move.filter(|p| p.is_absolute()),
    }
}

pub fn write_pointer(file: &Path, pointer: &Pointer) -> Result<()> {
    if pointer == &Pointer::default() {
        return match std::fs::remove_file(file) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(Error::io(file, e)),
        };
    }
    let bytes = serde_json::to_vec_pretty(pointer).map_err(|e| Error::json(file.display().to_string(), e))?;
    fsutil::write_atomic_sync(file, &bytes)
}

/// Android/iOS: fester Ordner in der App-Sandbox (nicht verschiebbar).
pub fn sandbox(root: PathBuf) -> DataLocation {
    DataLocation {
        pointer_file: root.join(POINTER_FILE),
        default_root: root.clone(),
        root,
        source: RootSource::Default,
        missing_custom: None,
    }
}

/// Bestimmt den Datenordner. `app_data`: App-Datenordner des Systems
/// (Windows `%APPDATA%`, Linux `~/.local/share`).
pub fn resolve(env: Option<OsString>, exe_dir: Option<&Path>, app_data: &Path) -> DataLocation {
    let default_root = app_data.join(LAUNCHER_NAME);
    let pointer_file = app_data.join(POINTER_FILE);
    let location = |root: PathBuf, source, missing_custom| DataLocation {
        root,
        source,
        default_root: default_root.clone(),
        missing_custom,
        pointer_file: pointer_file.clone(),
    };
    if let Some(custom) = env.filter(|v| !v.is_empty()) {
        return location(PathBuf::from(custom), RootSource::Env, None);
    }
    if let Some(root) = exe_dir.and_then(portable_root) {
        return location(root, RootSource::Portable, None);
    }
    match read_pointer(&pointer_file).root {
        Some(root) if root.is_dir() => location(root, RootSource::Custom, None),
        // Laufwerk getrennt o. Ä.: Standard benutzen, Zeiger behalten (beim nächsten Start klappt es evtl. wieder).
        Some(root) => location(default_root.clone(), RootSource::Default, Some(root)),
        None => location(default_root.clone(), RootSource::Default, None),
    }
}

/// Stellt den Zeiger auf `new_root` um (Standardordner → Zeiger entfernen).
pub fn switch_root(location: &DataLocation, new_root: &Path) -> Result<()> {
    let mut pointer = read_pointer(&location.pointer_file);
    pointer.root = (!same_path(new_root, &location.default_root)).then(|| new_root.to_path_buf());
    pointer.delete_after_move = None;
    write_pointer(&location.pointer_file, &pointer)
}

/// Merkt den alten Ordner zum Löschen beim nächsten Start vor (`None` = behalten).
pub fn schedule_delete(location: &DataLocation, old_root: Option<&Path>) -> Result<()> {
    let mut pointer = read_pointer(&location.pointer_file);
    pointer.delete_after_move = old_root.map(Path::to_path_buf);
    write_pointer(&location.pointer_file, &pointer)
}

/// Beim Start: vorgemerkten alten Ordner löschen – nur, wenn seine Umzugs-Marke
/// genau auf den aktuellen Ordner zeigt. Blockiert (große Ordner) – im Hintergrund aufrufen.
pub fn finish_pending_delete(location: &DataLocation) {
    let mut pointer = read_pointer(&location.pointer_file);
    let Some(old) = pointer.delete_after_move.take() else { return };
    if old_may_be_deleted(&old, &location.root) {
        match std::fs::remove_dir_all(&old) {
            Ok(()) => tracing::info!("Alter Datenordner gelöscht: {}", old.display()),
            Err(e) => tracing::warn!("Alter Datenordner konnte nicht (ganz) gelöscht werden: {e}"),
        }
    } else {
        tracing::warn!("Alter Datenordner ohne passende Umzugs-Marke – bleibt liegen: {}", old.display());
    }
    if let Err(e) = write_pointer(&location.pointer_file, &pointer) {
        tracing::warn!("Zeiger-Datei konnte nicht aktualisiert werden: {e}");
    }
}

/// Nur ein Ordner, der den aktuellen per Marke als Umzugsziel nennt und ihn nicht enthält.
pub fn old_may_be_deleted(old: &Path, current: &Path) -> bool {
    if same_path(old, current) || current.starts_with(old) || old.parent().is_none() {
        return false;
    }
    let Ok(text) = std::fs::read_to_string(old.join(MOVED_MARKER)) else { return false };
    same_path(Path::new(text.trim()), current)
}

/// Vergleich zweier Pfade (Windows ohne Groß-/Kleinschreibung und Trennzeichen-Unterschiede).
pub fn same_path(a: &Path, b: &Path) -> bool {
    fn key(p: &Path) -> String {
        let s = p.to_string_lossy();
        let s = s.trim_end_matches(['/', '\\']);
        if cfg!(windows) { s.replace('/', "\\").to_lowercase() } else { s.to_owned() }
    }
    key(a) == key(b)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_env_portable_pointer_and_default() {
        let dir = tempfile::tempdir().unwrap();
        let app_data = dir.path().join("appdata");
        let exe = dir.path().join("exe");
        std::fs::create_dir_all(&exe).unwrap();
        std::fs::create_dir_all(&app_data).unwrap();

        let loc = resolve(None, Some(&exe), &app_data);
        assert_eq!(loc.source, RootSource::Default);
        assert_eq!(loc.root, app_data.join(LAUNCHER_NAME));
        assert!(loc.movable());

        // Eigener Ordner per Zeiger.
        let custom = dir.path().join("custom");
        std::fs::create_dir_all(&custom).unwrap();
        let loc = resolve(None, Some(&exe), &app_data);
        switch_root(&loc, &custom).unwrap();
        let loc = resolve(None, Some(&exe), &app_data);
        assert_eq!((loc.source, loc.root.clone()), (RootSource::Custom, custom.clone()));

        // Fehlt der Ordner, gilt der Standard – der Zeiger bleibt.
        std::fs::remove_dir(&custom).unwrap();
        let loc = resolve(None, Some(&exe), &app_data);
        assert_eq!(loc.source, RootSource::Default);
        assert_eq!(loc.missing_custom.as_deref(), Some(custom.as_path()));
        assert_eq!(read_pointer(&loc.pointer_file).root.as_deref(), Some(custom.as_path()));

        // Portabel schlägt den Zeiger …
        std::fs::write(exe.join(".portable"), b"").unwrap();
        let loc = resolve(None, Some(&exe), &app_data);
        assert_eq!((loc.source, loc.root.clone()), (RootSource::Portable, exe.join("data")));
        assert!(!loc.movable());
        // … und die Umgebungsvariable alles.
        let loc = resolve(Some("X:/dev".into()), Some(&exe), &app_data);
        assert_eq!((loc.source, loc.root), (RootSource::Env, PathBuf::from("X:/dev")));
        // Leere Variable zählt nicht.
        assert_eq!(resolve(Some("".into()), Some(&exe), &app_data).source, RootSource::Portable);

        // Zurück auf den Standard entfernt die Zeiger-Datei.
        std::fs::remove_file(exe.join(".portable")).unwrap();
        let loc = resolve(None, Some(&exe), &app_data);
        switch_root(&loc, &loc.default_root).unwrap();
        assert!(!loc.pointer_file.exists());
    }

    #[test]
    fn broken_or_relative_pointer_is_ignored() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join(POINTER_FILE);
        std::fs::write(&file, b"{kaputt").unwrap();
        assert_eq!(read_pointer(&file), Pointer::default());
        std::fs::write(&file, br#"{"root":"relativ/ordner","deleteAfterMove":"auch"}"#).unwrap();
        assert_eq!(read_pointer(&file), Pointer::default());
    }

    #[test]
    fn deletes_old_folder_only_with_matching_marker() {
        let dir = tempfile::tempdir().unwrap();
        let app_data = dir.path().join("appdata");
        let old = dir.path().join("old");
        let new = dir.path().join("new");
        std::fs::create_dir_all(old.join("instances")).unwrap();
        std::fs::create_dir_all(&new).unwrap();
        std::fs::create_dir_all(&app_data).unwrap();
        let loc = resolve(None, None, &app_data);
        switch_root(&loc, &new).unwrap();
        let loc = resolve(None, None, &app_data);
        assert_eq!(loc.root, new);

        // Ohne Marke bleibt der alte Ordner liegen, die Vormerkung verschwindet.
        schedule_delete(&loc, Some(&old)).unwrap();
        finish_pending_delete(&loc);
        assert!(old.exists());
        assert_eq!(read_pointer(&loc.pointer_file).delete_after_move, None);

        // Marke auf einen anderen Ordner: ebenfalls nicht.
        std::fs::write(old.join(MOVED_MARKER), dir.path().join("woanders").display().to_string()).unwrap();
        schedule_delete(&loc, Some(&old)).unwrap();
        finish_pending_delete(&loc);
        assert!(old.exists());

        // Passende Marke: gelöscht, Zeiger bleibt auf dem neuen Ordner.
        std::fs::write(old.join(MOVED_MARKER), new.display().to_string()).unwrap();
        schedule_delete(&loc, Some(&old)).unwrap();
        finish_pending_delete(&loc);
        assert!(!old.exists());
        assert_eq!(read_pointer(&loc.pointer_file), Pointer { root: Some(new.clone()), delete_after_move: None });

        // Nie den aktuellen Ordner oder einen, der ihn enthält.
        std::fs::write(new.join(MOVED_MARKER), new.display().to_string()).unwrap();
        assert!(!old_may_be_deleted(&new, &new));
        assert!(!old_may_be_deleted(dir.path(), &new));
    }

    #[test]
    fn compares_paths_like_the_file_system() {
        assert!(same_path(Path::new("/a/b/"), Path::new("/a/b")));
        assert_eq!(same_path(Path::new(r"C:\Data\TRS"), Path::new("c:/data/trs")), cfg!(windows));
    }
}
