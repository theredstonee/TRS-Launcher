//! Touch-Steuerung der mobilen App: Layouts verwalten, teilen (Code/Datei)
//! und je Instanz wählen. Dateipfade aus den Dialogen bleiben in Rust.

use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::controls::{self, Layout, StoredLayout};

use crate::LauncherState;
use crate::commands::instances::{InstanceView, view};
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

#[tauri::command]
pub async fn controls_list(launcher: State<'_, LauncherState>) -> CommandResult<Vec<StoredLayout>> {
    Ok(controls::list(launcher.paths()).await?)
}

#[tauri::command]
pub async fn controls_save(launcher: State<'_, LauncherState>, layout: Layout) -> CommandResult<StoredLayout> {
    Ok(controls::save(launcher.paths(), layout).await?)
}

/// Kopie eines Layouts als neues eigenes Layout.
#[tauri::command]
pub async fn controls_duplicate(launcher: State<'_, LauncherState>, id: String, name: String) -> CommandResult<StoredLayout> {
    Ok(controls::duplicate(launcher.paths(), &id, &name).await?)
}

#[tauri::command]
pub async fn controls_delete(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(controls::delete(launcher.paths(), &id).await?)
}

/// Fertiges Layout auf den Auslieferungsstand.
#[tauri::command]
pub async fn controls_reset(launcher: State<'_, LauncherState>, id: String) -> CommandResult<StoredLayout> {
    Ok(controls::reset(launcher.paths(), &id).await?)
}

#[tauri::command]
pub async fn controls_export_code(launcher: State<'_, LauncherState>, id: String) -> CommandResult<String> {
    Ok(controls::export_code(launcher.paths(), &id).await?)
}

#[tauri::command]
pub async fn controls_import_code(launcher: State<'_, LauncherState>, code: String) -> CommandResult<StoredLayout> {
    Ok(controls::import_code(launcher.paths(), &code).await?)
}

/// Fragt nach dem Speicherort und schreibt das Layout. `false` = abgebrochen.
#[tauri::command]
pub async fn controls_export_file(app: AppHandle, launcher: State<'_, LauncherState>, id: String) -> CommandResult<bool> {
    let (file_name, bytes) = controls::export_file(launcher.paths(), &id).await?;
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::SaveControls.text(lang))
            .set_file_name(file_name)
            .add_filter(DialogText::ControlsFile.text(lang), &["json"])
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

/// Öffnet eine Steuerungs-Datei und legt daraus ein eigenes Layout an.
/// `None` = abgebrochen.
#[tauri::command]
pub async fn controls_import_file(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Option<StoredLayout>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::PickControls.text(lang))
            .add_filter(DialogText::ControlsFile.text(lang), &["json"])
            .blocking_pick_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(file) = picked else { return Ok(None) };
    Ok(Some(controls::import_file(launcher.paths(), &file).await?))
}

/// Touch-Layout einer Instanz (`None` = Standard/PvP).
#[tauri::command]
pub async fn set_instance_touch_profile(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    profile: Option<String>,
) -> CommandResult<InstanceView> {
    let updated = launcher.instances().set_touch_profile(&id, profile.as_deref()).await?;
    Ok(view(&app, &launcher, updated))
}
