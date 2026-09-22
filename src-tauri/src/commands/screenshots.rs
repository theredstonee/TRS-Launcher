//! Screenshot-Galerie über alle Instanzen: Vorschaubilder, Vollbild, in die
//! Zwischenablage kopieren, im Ordner zeigen, in den Papierkorb legen.

use std::path::PathBuf;

use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;
use trs_core::screenshots::GalleryShot;

use crate::LauncherState;
use crate::error::CommandResult;

/// Alle Screenshots aller Instanzen, neueste zuerst.
#[tauri::command]
pub async fn all_screenshots(launcher: State<'_, LauncherState>) -> CommandResult<Vec<GalleryShot>> {
    Ok(launcher.all_screenshots().await?)
}

/// Vorschaubild (wird beim ersten Mal erzeugt). `None` = nicht lesbar.
#[tauri::command]
pub async fn screenshot_thumbnail(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<Option<PathBuf>> {
    match launcher.screenshot_thumbnail(&id, &file_name).await {
        Ok(path) => Ok(super::extras::allow(&app, &path).then_some(path)),
        Err(e) => {
            log::debug!("Vorschaubild übersprungen: {e}");
            Ok(None)
        }
    }
}

/// Gibt das Original fürs Vollbild frei.
#[tauri::command]
pub async fn screenshot_image(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<Option<PathBuf>> {
    let path = trs_core::extras::screenshot_path(launcher.paths(), &id, &file_name)?;
    Ok(super::extras::allow(&app, &path).then_some(path))
}

/// Legt das Bild als Bitmap in die Zwischenablage (zum Einfügen in Chats).
#[tauri::command]
pub async fn copy_screenshot(
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<()> {
    let image = launcher.screenshot_rgba(&id, &file_name).await?;
    tauri::async_runtime::spawn_blocking(move || -> Result<(), String> {
        let mut clipboard = arboard::Clipboard::new().map_err(|e| e.to_string())?;
        clipboard
            .set_image(arboard::ImageData {
                width: image.width as usize,
                height: image.height as usize,
                bytes: std::borrow::Cow::Owned(image.pixels),
            })
            .map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| {
        log::error!("Kopieren abgebrochen: {e}");
        trs_core::Error::Internal(e.to_string())
    })?
    .map_err(|e| {
        log::error!("Zwischenablage nicht verfügbar: {e}");
        trs_core::Error::Internal(e)
    })?;
    Ok(())
}

/// Öffnet den Ordner und markiert das Bild darin.
#[tauri::command]
pub async fn reveal_screenshot(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<()> {
    let path = trs_core::extras::screenshot_path(launcher.paths(), &id, &file_name)?;
    app.opener().reveal_item_in_dir(path)?;
    Ok(())
}

/// Löschen: bevorzugt in den Papierkorb, sonst endgültig.
#[tauri::command]
pub async fn trash_screenshot(
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<()> {
    Ok(launcher.trash_screenshot(&id, &file_name).await?)
}
