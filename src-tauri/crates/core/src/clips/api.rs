//! Clip-Bibliothek für die Oberfläche (Auflisten, Umbenennen, Löschen, Speicherplatz).

use std::collections::HashMap;
use std::path::PathBuf;

use chrono::{DateTime, Utc};
use serde::Serialize;

use super::library::{self, ClipUsage};
use crate::{Error, Launcher, Result};

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipView {
    pub instance_id: String,
    /// Name der Instanz; gelöschte Instanzen behalten ihre Clips (dann die ID).
    pub instance_name: String,
    pub file_name: String,
    pub size: u64,
    pub created_at: Option<DateTime<Utc>>,
    pub duration_ms: Option<u64>,
}

impl Launcher {
    /// Ordner aller Clips (Einstellung oder `<Daten>/clips`).
    pub async fn clips_root(&self) -> PathBuf {
        library::clips_root(self.paths(), &self.settings().await.clips)
    }

    pub async fn list_clips(&self) -> Result<Vec<ClipView>> {
        let root = self.clips_root().await;
        let clips = tokio::task::spawn_blocking(move || library::list(&root))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;
        let names: HashMap<String, String> =
            self.instances().list().await.unwrap_or_default().into_iter().map(|i| (i.id, i.name)).collect();
        Ok(clips
            .into_iter()
            .map(|c| ClipView {
                instance_name: names.get(&c.instance_id).cloned().unwrap_or_else(|| c.instance_id.clone()),
                instance_id: c.instance_id,
                file_name: c.file_name,
                size: c.size,
                created_at: c.created_at,
                duration_ms: c.duration_ms,
            })
            .collect())
    }

    /// Geprüfter Pfad eines Clips (zum Abspielen, Zeigen, Löschen).
    pub async fn clip_path(&self, instance_id: &str, file_name: &str) -> Result<PathBuf> {
        library::clip_path(&self.clips_root().await, instance_id, file_name)
    }

    pub async fn clip_usage(&self) -> Result<ClipUsage> {
        let settings = self.settings().await.clips;
        let root = library::clips_root(self.paths(), &settings);
        tokio::task::spawn_blocking(move || library::usage(&root, &settings))
            .await
            .map_err(|e| Error::Internal(e.to_string()))
    }

    /// Neuer Dateiname (ohne Pfad; `.mp4` wird ergänzt). Liefert den tatsächlichen Namen.
    pub async fn rename_clip(&self, instance_id: &str, file_name: &str, new_name: &str) -> Result<String> {
        let root = self.clips_root().await;
        let (id, file, new) = (instance_id.to_owned(), file_name.to_owned(), new_name.to_owned());
        tokio::task::spawn_blocking(move || library::rename(&root, &id, &file, &new))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?
    }

    /// In den Papierkorb (sonst endgültig löschen).
    pub async fn trash_clip(&self, instance_id: &str, file_name: &str) -> Result<()> {
        let path = self.clip_path(instance_id, file_name).await?;
        tokio::task::spawn_blocking(move || {
            if crate::screenshots::recycle(&path).is_err() {
                std::fs::remove_file(&path).map_err(|e| Error::io(&path, e))?;
            }
            Ok(())
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
    }

    /// Vorschaubild (braucht FFmpeg; sonst `None`).
    pub async fn clip_thumbnail(&self, instance_id: &str, file_name: &str) -> Result<Option<PathBuf>> {
        let path = self.clip_path(instance_id, file_name).await?;
        self.clips().thumbnail(&path).await
    }
}
