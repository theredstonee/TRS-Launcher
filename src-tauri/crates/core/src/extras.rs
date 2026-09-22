//! Komfort rund um eine Instanz: Screenshots, Welten, Duplizieren.

use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use serde::Serialize;

use crate::instance::{Instance, validate_id};
use crate::paths::Paths;
use crate::{Error, Launcher, Result, fsutil};

const MAX_LISTED: usize = 500;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Screenshot {
    pub file_name: String,
    /// Absoluter Pfad – die Tauri-Schicht gibt genau diese Datei fürs Webview frei.
    pub path: PathBuf,
    pub size: u64,
    pub taken_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct World {
    pub folder: String,
    pub icon_path: Option<PathBuf>,
    pub last_played: Option<DateTime<Utc>>,
}

fn modified(meta: &std::fs::Metadata) -> Option<DateTime<Utc>> {
    meta.modified().ok().map(DateTime::<Utc>::from)
}

fn is_plain_file_name(name: &str, extension: &str) -> bool {
    !name.is_empty()
        && name.len() <= 200
        && !name.starts_with('.')
        && name.to_ascii_lowercase().ends_with(extension)
        && !name.chars().any(|c| c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|'))
}

pub fn screenshots_dir(paths: &Paths, instance_id: &str) -> PathBuf {
    paths.instance_game_dir(instance_id).join("screenshots")
}

pub async fn list_screenshots(paths: &Paths, instance_id: &str) -> Result<Vec<Screenshot>> {
    validate_id(instance_id)?;
    let dir = screenshots_dir(paths, instance_id);
    let mut entries = match tokio::fs::read_dir(&dir).await {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(Error::io(&dir, e)),
    };

    let mut shots = Vec::new();
    while let Some(entry) = entries.next_entry().await.map_err(|e| Error::io(&dir, e))? {
        let Some(file_name) = entry.file_name().to_str().map(str::to_owned) else { continue };
        let Ok(meta) = entry.metadata().await else { continue };
        if meta.is_file() && is_plain_file_name(&file_name, ".png") {
            shots.push(Screenshot { file_name, path: entry.path(), size: meta.len(), taken_at: modified(&meta) });
        }
    }
    shots.sort_by_key(|s| std::cmp::Reverse(s.taken_at));
    shots.truncate(MAX_LISTED);
    Ok(shots)
}

/// Geprüfter Pfad zu einem Screenshot (zum Öffnen oder Löschen).
pub fn screenshot_path(paths: &Paths, instance_id: &str, file_name: &str) -> Result<PathBuf> {
    validate_id(instance_id)?;
    if !is_plain_file_name(file_name, ".png") {
        return Err(Error::validation("Ungültiger Dateiname"));
    }
    let path = screenshots_dir(paths, instance_id).join(file_name);
    if path.is_file() { Ok(path) } else { Err(Error::validation("Der Screenshot existiert nicht mehr.")) }
}

pub async fn delete_screenshot(paths: &Paths, instance_id: &str, file_name: &str) -> Result<()> {
    let path = screenshot_path(paths, instance_id, file_name)?;
    tokio::fs::remove_file(&path).await.map_err(|e| Error::io(&path, e))
}

pub async fn list_worlds(paths: &Paths, instance_id: &str) -> Result<Vec<World>> {
    validate_id(instance_id)?;
    let dir = paths.instance_game_dir(instance_id).join("saves");
    let mut entries = match tokio::fs::read_dir(&dir).await {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(Error::io(&dir, e)),
    };

    let mut worlds = Vec::new();
    while let Some(entry) = entries.next_entry().await.map_err(|e| Error::io(&dir, e))? {
        let Some(folder) = entry.file_name().to_str().map(str::to_owned) else { continue };
        let level = entry.path().join("level.dat");
        // Nur echte Welten – in `saves` liegt gelegentlich auch anderes.
        let Ok(meta) = tokio::fs::metadata(&level).await else { continue };
        let icon = entry.path().join("icon.png");
        worlds.push(World {
            folder: folder.chars().filter(|c| !c.is_control()).take(100).collect(),
            icon_path: icon.is_file().then_some(icon),
            last_played: modified(&meta),
        });
    }
    worlds.sort_by_key(|w| std::cmp::Reverse(w.last_played));
    worlds.truncate(MAX_LISTED);
    Ok(worlds)
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
            std::fs::copy(entry.path(), &target)?;
        }
        // Symlinks werden bewusst nicht mitkopiert.
    }
    Ok(())
}

impl Launcher {
    /// Kopiert eine Instanz samt Welten, Mods und Einstellungen.
    pub async fn duplicate_instance(&self, id: &str, new_name: &str) -> Result<Instance> {
        let source = self.instances().get(id).await?;
        if self.games().is_running(id) {
            return Err(Error::launch("Die Instanz läuft gerade – bitte erst beenden."));
        }

        let copy = self
            .instances()
            .create(crate::instance::NewInstance {
                name: new_name.to_owned(),
                game_version: source.game_version.clone(),
                loader: source.loader.clone(),
            })
            .await?;

        let from = self.paths().instance_dir(&source.id);
        let to = self.paths().instance_dir(&copy.id);
        let copy_id = copy.id.clone();
        let icon = source.icon.clone().filter(|n| crate::icon::is_icon_file_name(n));
        let icon_file = icon.clone();
        let result = tokio::task::spawn_blocking(move || -> std::io::Result<()> {
            copy_dir(&from.join("minecraft"), &to.join("minecraft"))?;
            let index = from.join("content.json");
            if index.is_file() {
                std::fs::copy(index, to.join("content.json"))?;
            }
            if let Some(name) = icon_file.filter(|n| from.join(n).is_file()) {
                std::fs::copy(from.join(&name), to.join(&name))?;
            }
            if let Some(banner) = crate::icon::find_banner(&from)
                && let Some(name) = banner.file_name()
            {
                std::fs::copy(&banner, to.join(name))?;
            }
            Ok(())
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?;

        if let Err(e) = result {
            let _ = self.instances().delete(&copy_id).await;
            return Err(Error::io(self.paths().instance_dir(&copy_id), e));
        }

        // Einstellungen übernehmen, Spielzeit nicht.
        let updated = self
            .instances()
            .update(&copy.id, crate::instance::UpdateInstance { name: copy.name.clone(), overrides: source.overrides })
            .await?;
        let updated = match &source.group {
            Some(group) => self.instances().set_group(&updated.id, Some(group)).await?,
            None => updated,
        };
        let updated = match icon {
            Some(name) if self.paths().instance_dir(&updated.id).join(&name).is_file() => {
                self.instances().set_icon(&updated.id, Some(name)).await?
            }
            _ => updated,
        };
        fsutil::ensure_dir(&self.paths().instance_game_dir(&updated.id)).await?;
        crate::history::record(
            self.paths(),
            &updated.id,
            crate::history::HistoryEntry::new(crate::history::HistoryKind::Created).subject(&source.name).detail("duplicate"),
        )
        .await;
        Ok(updated)
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::instance::{InstanceOverrides, Loader, NewInstance, UpdateInstance};

    #[tokio::test]
    async fn screenshots_and_worlds() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let game = paths.instance_game_dir("test");
        tokio::fs::create_dir_all(game.join("screenshots")).await.unwrap();
        tokio::fs::create_dir_all(game.join("saves/Meine Welt")).await.unwrap();
        tokio::fs::create_dir_all(game.join("saves/kein-level")).await.unwrap();
        tokio::fs::write(game.join("screenshots/2026-09-19_12.00.00.png"), b"png").await.unwrap();
        tokio::fs::write(game.join("screenshots/notiz.txt"), b"x").await.unwrap();
        tokio::fs::write(game.join("saves/Meine Welt/level.dat"), b"x").await.unwrap();
        tokio::fs::write(game.join("saves/Meine Welt/icon.png"), b"x").await.unwrap();

        let shots = list_screenshots(&paths, "test").await.unwrap();
        assert_eq!(shots.len(), 1);
        let worlds = list_worlds(&paths, "test").await.unwrap();
        assert_eq!(worlds.len(), 1);
        assert!(worlds[0].icon_path.is_some());

        assert!(screenshot_path(&paths, "test", "..\\..\\instance.json").is_err());
        assert!(screenshot_path(&paths, "test", "fehlt.png").is_err());
        delete_screenshot(&paths, "test", "2026-09-19_12.00.00.png").await.unwrap();
        assert!(list_screenshots(&paths, "test").await.unwrap().is_empty());
        assert!(list_screenshots(&paths, "ohne-ordner").await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn duplicate_copies_files_and_overrides() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
        let original = launcher
            .instances()
            .create(NewInstance { name: "Original".into(), game_version: "1.21.1".into(), loader: Loader::vanilla() })
            .await
            .unwrap();
        launcher
            .instances()
            .update(
                &original.id,
                UpdateInstance {
                    name: "Original".into(),
                    overrides: InstanceOverrides { max_memory_mb: Some(6144), ..Default::default() },
                },
            )
            .await
            .unwrap();
        let game = launcher.paths().instance_game_dir(&original.id);
        tokio::fs::create_dir_all(game.join("mods")).await.unwrap();
        tokio::fs::write(game.join("mods/a.jar"), b"jar").await.unwrap();
        launcher.instances().add_play_time(&original.id, 500).await.unwrap();

        let copy = launcher.duplicate_instance(&original.id, "Original (Kopie)").await.unwrap();
        assert_ne!(copy.id, original.id);
        assert_eq!(copy.overrides.max_memory_mb, Some(6144));
        assert_eq!(copy.total_play_seconds, 0);
        assert!(launcher.paths().instance_game_dir(&copy.id).join("mods/a.jar").is_file());
    }
}
