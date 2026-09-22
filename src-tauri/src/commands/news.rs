//! Neuigkeiten für die Startseite. Bilder werden im Kern zwischengespeichert;
//! das Webview bekommt nur einzeln freigegebene lokale Dateien.

use std::path::PathBuf;

use tauri::{AppHandle, State};
use trs_core::news::NewsFeed;

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn get_news(launcher: State<'_, LauncherState>, force: bool) -> CommandResult<NewsFeed> {
    Ok(launcher.news(force).await?)
}

/// Lädt ein Bild aus dem Feed in den Cache und gibt genau diese Datei frei.
/// `None`, wenn das Bild nicht geladen werden konnte (dann bleibt die Kachel leer).
#[tauri::command]
pub async fn news_image(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    url: String,
) -> CommandResult<Option<PathBuf>> {
    match launcher.news_image(&url).await {
        Ok(path) => Ok(super::extras::allow(&app, &path).then_some(path)),
        Err(e) => {
            log::debug!("News-Bild übersprungen: {e}");
            Ok(None)
        }
    }
}

/// Voller Text einer Patchnote (HTML von Mojang). Das Frontend zeigt ihn nur
/// über den DOMPurify-Weg an.
#[tauri::command]
pub async fn patch_notes_body(launcher: State<'_, LauncherState>, content_path: String) -> CommandResult<String> {
    Ok(launcher.patch_notes_body(&content_path).await?)
}
