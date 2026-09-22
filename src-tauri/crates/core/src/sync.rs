//! Synchronisierung zwischen Instanzen: `options.txt`, `servers.dat`,
//! Ressourcenpakete, `command_history.txt` und `hotbar.nbt` liegen einmal in
//! `<daten>/shared/` und werden vor dem Start in die Instanz kopiert und nach
//! dem Beenden zurück.
//!
//! Sicherheitsregeln:
//! - Nichts wird gelöscht. Bevor eine Datei überschrieben wird, landet die
//!   alte Fassung als Sicherung (`instances/<id>/sync-backup/` bzw.
//!   `shared/.backup/`).
//! - Gibt es noch keine gemeinsame Fassung, wird sie aus der Instanz gesät,
//!   die eine hat – der erste Start überschreibt also nichts.
//! - Ressourcenpakete werden nur ergänzt (Vereinigung), nie entfernt.
//! - Wurde eine Sitzung nicht sauber abgeschlossen (Launcher zu, Spiel lief
//!   weiter), übernimmt der nächste Start erst die neueren Dateien der
//!   Instanz, bevor er die gemeinsame Fassung holt.

use std::path::{Path, PathBuf};
use std::time::SystemTime;

use serde::{Deserialize, Serialize};

use crate::{Error, Result};

const MARKER: &str = "sync-session.json";
const INSTANCE_BACKUP_DIR: &str = "sync-backup";
const SHARED_BACKUP_DIR: &str = ".backup";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SyncItem {
    Options,
    Servers,
    ResourcePacks,
    CommandHistory,
    Hotbar,
}

impl SyncItem {
    pub const ALL: [Self; 5] = [Self::Options, Self::Servers, Self::ResourcePacks, Self::CommandHistory, Self::Hotbar];

    /// Datei- bzw. Ordnername im Spielordner.
    pub fn name(self) -> &'static str {
        match self {
            Self::Options => "options.txt",
            Self::Servers => "servers.dat",
            Self::ResourcePacks => "resourcepacks",
            Self::CommandHistory => "command_history.txt",
            Self::Hotbar => "hotbar.nbt",
        }
    }

    fn is_dir(self) -> bool {
        self == Self::ResourcePacks
    }
}

/// Globale Schalter (Einstellungen). Standard: alles aus.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SyncSettings {
    pub options: bool,
    pub servers: bool,
    pub resource_packs: bool,
    pub command_history: bool,
    pub hotbar: bool,
}

impl SyncSettings {
    pub fn enabled(&self, item: SyncItem) -> bool {
        match item {
            SyncItem::Options => self.options,
            SyncItem::Servers => self.servers,
            SyncItem::ResourcePacks => self.resource_packs,
            SyncItem::CommandHistory => self.command_history,
            SyncItem::Hotbar => self.hotbar,
        }
    }
}

/// Global eingeschaltet und in der Instanz nicht „separat gehalten“.
pub fn active_items(global: &SyncSettings, keep_separate: &[SyncItem]) -> Vec<SyncItem> {
    SyncItem::ALL.into_iter().filter(|i| global.enabled(*i) && !keep_separate.contains(i)).collect()
}

/// Ordner einer Instanz, zwischen denen synchronisiert wird.
#[derive(Debug, Clone)]
pub struct SyncDirs {
    pub shared: PathBuf,
    pub instance: PathBuf,
    pub game: PathBuf,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Marker {
    items: Vec<SyncItem>,
}

/// Vor dem Start: gemeinsame Fassung in die Instanz holen (bzw. säen).
pub async fn pull(dirs: &SyncDirs, items: &[SyncItem]) -> Result<()> {
    let (dirs, items) = (dirs.clone(), items.to_vec());
    blocking(move || pull_blocking(&dirs, &items)).await
}

/// Nach dem Beenden: Stand der Instanz in die gemeinsame Fassung übernehmen.
pub async fn push(dirs: &SyncDirs) -> Result<()> {
    let dirs = dirs.clone();
    blocking(move || {
        let Some(marker) = read_marker(&dirs.instance) else { return Ok(()) };
        push_items(&dirs, &marker.items, false)?;
        let _ = std::fs::remove_file(dirs.instance.join(MARKER));
        Ok(())
    })
    .await
}

async fn blocking(f: impl FnOnce() -> std::io::Result<()> + Send + 'static) -> Result<()> {
    tokio::task::spawn_blocking(f)
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
        .map_err(|e| Error::io("shared", e))
}

fn read_marker(instance_dir: &Path) -> Option<Marker> {
    std::fs::read(instance_dir.join(MARKER)).ok().and_then(|b| serde_json::from_slice(&b).ok())
}

fn pull_blocking(dirs: &SyncDirs, items: &[SyncItem]) -> std::io::Result<()> {
    // Letzte Sitzung nicht abgeschlossen? Erst Neueres aus der Instanz sichern.
    if let Some(stale) = read_marker(&dirs.instance) {
        tracing::info!("Synchronisierung: offene Sitzung gefunden – übernehme neuere Dateien der Instanz");
        push_items(dirs, &stale.items, true)?;
        let _ = std::fs::remove_file(dirs.instance.join(MARKER));
    }
    if items.is_empty() {
        return Ok(());
    }
    std::fs::create_dir_all(&dirs.shared)?;
    std::fs::create_dir_all(&dirs.game)?;
    for &item in items {
        let shared = dirs.shared.join(item.name());
        let local = dirs.game.join(item.name());
        if item.is_dir() {
            if shared.is_dir() {
                merge_dir(&shared, &local, &dirs.instance.join(INSTANCE_BACKUP_DIR).join(item.name()), true)?;
            } else if local.is_dir() {
                merge_dir(&local, &shared, &dirs.shared.join(SHARED_BACKUP_DIR).join(item.name()), true)?;
            }
        } else if shared.is_file() {
            if !same_file(&shared, &local) {
                backup(&local, &dirs.instance.join(INSTANCE_BACKUP_DIR))?;
                copy_atomic(&shared, &local)?;
            }
        } else if local.is_file() {
            // Erste Synchronisierung: die Instanz, die die Datei hat, sät sie.
            copy_atomic(&local, &shared)?;
        }
    }
    let marker = serde_json::to_vec(&Marker { items: items.to_vec() }).map_err(std::io::Error::other)?;
    std::fs::write(dirs.instance.join(MARKER), marker)
}

/// `only_newer`: nur übernehmen, wenn die Instanz-Fassung jünger ist
/// (Aufräumen nach einer nicht abgeschlossenen Sitzung).
fn push_items(dirs: &SyncDirs, items: &[SyncItem], only_newer: bool) -> std::io::Result<()> {
    std::fs::create_dir_all(&dirs.shared)?;
    for &item in items {
        let shared = dirs.shared.join(item.name());
        let local = dirs.game.join(item.name());
        if item.is_dir() {
            if local.is_dir() {
                merge_dir(&local, &shared, &dirs.shared.join(SHARED_BACKUP_DIR).join(item.name()), true)?;
            }
        } else if local.is_file() && !same_file(&local, &shared) {
            if only_newer && modified(&shared) >= modified(&local) {
                continue;
            }
            backup(&shared, &dirs.shared.join(SHARED_BACKUP_DIR))?;
            copy_atomic(&local, &shared)?;
        }
    }
    Ok(())
}

/// Ergänzt `to` um alles aus `from`. Vorhandene Dateien werden nur ersetzt,
/// wenn `from` neuer ist und `replace_newer` gesetzt ist – dann vorher gesichert.
/// Unterordner (entpackte Pakete) werden nur kopiert, wenn sie ganz fehlen.
fn merge_dir(from: &Path, to: &Path, backup_dir: &Path, replace_newer: bool) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        if kind.is_symlink() {
            continue;
        }
        let target = to.join(entry.file_name());
        if kind.is_dir() {
            if !target.exists() {
                copy_dir(&entry.path(), &target)?;
            }
        } else if !target.exists() {
            copy_atomic(&entry.path(), &target)?;
        } else if replace_newer && target.is_file() && !same_file(&entry.path(), &target) && modified(&entry.path()) > modified(&target) {
            backup(&target, backup_dir)?;
            copy_atomic(&entry.path(), &target)?;
        }
    }
    Ok(())
}

fn copy_dir(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        let target = to.join(entry.file_name());
        if kind.is_dir() {
            copy_dir(&entry.path(), &target)?;
        } else if kind.is_file() {
            std::fs::copy(entry.path(), target)?;
        }
    }
    Ok(())
}

/// Sichert `file` (falls vorhanden) nach `backup_dir/<name>`; die jüngste
/// Sicherung ersetzt die vorige.
fn backup(file: &Path, backup_dir: &Path) -> std::io::Result<()> {
    if !file.is_file() {
        return Ok(());
    }
    let Some(name) = file.file_name() else { return Ok(()) };
    std::fs::create_dir_all(backup_dir)?;
    copy_atomic(file, &backup_dir.join(name))
}

/// Erst in eine Temp-Datei daneben, dann umbenennen – ein Abbruch hinterlässt
/// keine halbe Datei. Die Änderungszeit wird mitgenommen.
fn copy_atomic(from: &Path, to: &Path) -> std::io::Result<()> {
    if let Some(parent) = to.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = to.with_extension(format!("trs-sync-{}", uuid::Uuid::new_v4().simple()));
    if let Err(e) = std::fs::copy(from, &tmp) {
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }
    if let Ok(time) = std::fs::metadata(from).and_then(|m| m.modified()) {
        let _ = std::fs::File::options().write(true).open(&tmp).and_then(|f| f.set_modified(time));
    }
    std::fs::rename(&tmp, to).inspect_err(|_| {
        let _ = std::fs::remove_file(&tmp);
    })
}

fn modified(path: &Path) -> Option<SystemTime> {
    std::fs::metadata(path).and_then(|m| m.modified()).ok()
}

fn same_file(a: &Path, b: &Path) -> bool {
    match (std::fs::metadata(a), std::fs::metadata(b)) {
        (Ok(ma), Ok(mb)) if ma.len() == mb.len() => std::fs::read(a).ok() == std::fs::read(b).ok(),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;

    fn dirs(root: &Path, name: &str) -> SyncDirs {
        SyncDirs {
            shared: root.join("shared"),
            instance: root.join(name),
            game: root.join(name).join("minecraft"),
        }
    }

    fn write(path: &Path, text: &str) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, text).unwrap();
    }

    fn read(path: &Path) -> String {
        fs::read_to_string(path).unwrap()
    }

    #[test]
    fn active_items_respect_overrides() {
        let global = SyncSettings { options: true, servers: true, ..Default::default() };
        assert_eq!(active_items(&global, &[]), [SyncItem::Options, SyncItem::Servers]);
        assert_eq!(active_items(&global, &[SyncItem::Servers]), [SyncItem::Options]);
        assert!(active_items(&SyncSettings::default(), &[]).is_empty());
    }

    #[tokio::test]
    async fn first_sync_seeds_and_later_instances_receive() {
        let root = tempfile::tempdir().unwrap();
        let a = dirs(root.path(), "a");
        let b = dirs(root.path(), "b");
        write(&a.game.join("options.txt"), "fov:90");
        write(&b.game.join("options.txt"), "fov:70");

        // A startet zuerst: noch keine gemeinsame Fassung → A sät sie.
        pull(&a, &[SyncItem::Options]).await.unwrap();
        assert_eq!(read(&a.shared.join("options.txt")), "fov:90");
        assert_eq!(read(&a.game.join("options.txt")), "fov:90");

        // A ändert etwas und wird beendet → zurück in die gemeinsame Fassung.
        write(&a.game.join("options.txt"), "fov:100");
        push(&a).await.unwrap();
        assert_eq!(read(&a.shared.join("options.txt")), "fov:100");
        assert!(!a.instance.join(MARKER).exists());

        // B bekommt die gemeinsame Fassung; seine alte Datei ist gesichert.
        pull(&b, &[SyncItem::Options]).await.unwrap();
        assert_eq!(read(&b.game.join("options.txt")), "fov:100");
        assert_eq!(read(&b.instance.join(INSTANCE_BACKUP_DIR).join("options.txt")), "fov:70");

        // B ändert → die vorherige gemeinsame Fassung landet in .backup.
        write(&b.game.join("options.txt"), "fov:110");
        push(&b).await.unwrap();
        assert_eq!(read(&b.shared.join("options.txt")), "fov:110");
        assert_eq!(read(&b.shared.join(SHARED_BACKUP_DIR).join("options.txt")), "fov:100");
    }

    #[tokio::test]
    async fn push_without_session_does_nothing() {
        let root = tempfile::tempdir().unwrap();
        let a = dirs(root.path(), "a");
        write(&a.game.join("options.txt"), "x");
        push(&a).await.unwrap();
        assert!(!a.shared.join("options.txt").exists());
    }

    #[tokio::test]
    async fn resource_packs_are_merged_never_deleted() {
        let root = tempfile::tempdir().unwrap();
        let a = dirs(root.path(), "a");
        let b = dirs(root.path(), "b");
        write(&a.game.join("resourcepacks/faithful.zip"), "F");
        write(&a.game.join("resourcepacks/entpackt/pack.mcmeta"), "{}");
        write(&b.game.join("resourcepacks/nur-b.zip"), "B");

        pull(&a, &[SyncItem::ResourcePacks]).await.unwrap();
        assert!(a.shared.join("resourcepacks/faithful.zip").is_file());
        assert!(a.shared.join("resourcepacks/entpackt/pack.mcmeta").is_file());
        push(&a).await.unwrap();

        pull(&b, &[SyncItem::ResourcePacks]).await.unwrap();
        assert!(b.game.join("resourcepacks/faithful.zip").is_file());
        assert!(b.game.join("resourcepacks/entpackt/pack.mcmeta").is_file());
        assert!(b.game.join("resourcepacks/nur-b.zip").is_file());
        push(&b).await.unwrap();
        // B's eigenes Paket ist jetzt auch gemeinsam – A's Pakete sind noch da.
        assert!(b.shared.join("resourcepacks/nur-b.zip").is_file());
        assert!(b.shared.join("resourcepacks/faithful.zip").is_file());
    }

    #[tokio::test]
    async fn unfinished_session_keeps_newer_local_changes() {
        let root = tempfile::tempdir().unwrap();
        let a = dirs(root.path(), "a");
        write(&a.game.join("hotbar.nbt"), "alt");
        pull(&a, &[SyncItem::Hotbar]).await.unwrap();
        // Spiel läuft weiter, Launcher wird geschlossen: kein push. Das Spiel
        // schreibt später noch etwas.
        std::thread::sleep(std::time::Duration::from_millis(30));
        write(&a.game.join("hotbar.nbt"), "neu");

        // Nächster Start: erst die neuere Instanz-Datei übernehmen, dann holen.
        pull(&a, &[SyncItem::Hotbar]).await.unwrap();
        assert_eq!(read(&a.shared.join("hotbar.nbt")), "neu");
        assert_eq!(read(&a.game.join("hotbar.nbt")), "neu");
    }

    #[tokio::test]
    async fn nothing_to_sync_leaves_no_files() {
        let root = tempfile::tempdir().unwrap();
        let a = dirs(root.path(), "a");
        pull(&a, &[]).await.unwrap();
        assert!(!a.shared.exists());
        pull(&a, &[SyncItem::Servers]).await.unwrap();
        assert!(!a.shared.join("servers.dat").exists());
        assert!(!a.game.join("servers.dat").exists());
    }
}
