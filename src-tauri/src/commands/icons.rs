//! Symbol-Editor: fertiges Symbol speichern, Editor-Daten lesen, Minecraft-
//! Texturen aus dem geladenen Client und eigene Bilder über den nativen Dialog.

use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::icon_editor::{self, PreparedImage, TextureSet};

use crate::LauncherState;
use crate::commands::instances::{InstanceView, view};
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

/// Fertiges Symbol (PNG als Base64) + editierbare Quelle (JSON-Text). Der Kern prüft beides.
#[tauri::command]
pub async fn save_instance_icon(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    png: String,
    source: Option<String>,
) -> CommandResult<InstanceView> {
    let bytes = icon_editor::decode_base64(&png)?;
    let updated = launcher.save_instance_icon(&id, &bytes, source.as_deref()).await?;
    Ok(view(&app, &launcher, updated))
}

/// Editor-Daten des aktuellen Symbols; `None` = Bild ohne Editor-Daten.
#[tauri::command]
pub async fn instance_icon_source(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Option<String>> {
    Ok(launcher.instance_icon_source(&id).await?)
}

#[tauri::command]
pub async fn mc_texture_versions(launcher: State<'_, LauncherState>) -> CommandResult<Vec<String>> {
    Ok(launcher.mc_texture_versions().await)
}

/// Item- und Block-Texturen einer geladenen Version (ohne Angabe: die neueste).
#[tauri::command]
pub async fn mc_textures(launcher: State<'_, LauncherState>, version: Option<String>) -> CommandResult<TextureSet> {
    Ok(launcher.mc_textures(version.as_deref()).await?)
}

/// Eigenes Bild wählen (Pfad bleibt in Rust); `None` = abgebrochen.
#[tauri::command]
pub async fn pick_icon_image(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Option<PreparedImage>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::PickInstanceIcon.text(lang))
            .add_filter(DialogText::Images.text(lang), &["png", "jpg", "jpeg", "webp"])
            .blocking_pick_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(file) = picked else { return Ok(None) };
    Ok(Some(icon_editor::prepare_upload_file(&file).await?))
}

/// Pixel-Bild als PNG speichern („Exportieren“). `false` = abgebrochen.
#[tauri::command]
pub async fn save_icon_png(app: AppHandle, launcher: State<'_, LauncherState>, png: String, name: String) -> CommandResult<bool> {
    let bytes = icon_editor::decode_base64(&png)?;
    icon_editor::validate_composed(&bytes)?;
    let stem: String = name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | ' '))
        .take(40)
        .collect();
    let stem = stem.trim();
    let file_name = format!("{}.png", if stem.is_empty() { "icon" } else { stem });
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::SaveIcon.text(lang))
            .set_file_name(file_name)
            .add_filter(DialogText::Images.text(lang), &["png"])
            .blocking_save_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(dest) = picked else { return Ok(false) };
    trs_core::fsutil::write_atomic(&dest, &bytes).await?;
    Ok(true)
}
