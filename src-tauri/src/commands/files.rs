//! Tab „Dateien“: Dateibrowser im Spielordner der Instanz. Alle Pfade prüft
//! `trs_core::instance_files::Jail` – das Webview schickt nur relative Namen.

use std::path::PathBuf;

use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;
use trs_core::instance_files::{DirListing, ImportReport, Jail, opens_directly};

use super::system::DropState;
use crate::LauncherState;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

async fn jail(launcher: &LauncherState, id: &str) -> trs_core::Result<Jail> {
    let instance = launcher.instances().get(id).await?;
    Jail::for_instance(launcher.paths(), &instance.id)
}

/// Dateisystem-Arbeit gehört nicht auf den async-Thread.
async fn blocking<T: Send + 'static>(work: impl FnOnce() -> trs_core::Result<T> + Send + 'static) -> trs_core::Result<T> {
    tauri::async_runtime::spawn_blocking(work).await.map_err(|e| trs_core::Error::Internal(e.to_string()))?
}

#[tauri::command]
pub async fn list_instance_files(launcher: State<'_, LauncherState>, id: String, path: String) -> CommandResult<DirListing> {
    let jail = jail(&launcher, &id).await?;
    Ok(blocking(move || jail.list(&path)).await?)
}

#[tauri::command]
pub async fn create_instance_folder(
    launcher: State<'_, LauncherState>,
    id: String,
    parent: String,
    name: String,
) -> CommandResult<String> {
    let jail = jail(&launcher, &id).await?;
    Ok(blocking(move || jail.create_dir(&parent, name.trim())).await?)
}

#[tauri::command]
pub async fn create_instance_file(
    launcher: State<'_, LauncherState>,
    id: String,
    parent: String,
    name: String,
) -> CommandResult<String> {
    let jail = jail(&launcher, &id).await?;
    Ok(blocking(move || jail.create_file(&parent, name.trim())).await?)
}

#[tauri::command]
pub async fn rename_instance_file(
    launcher: State<'_, LauncherState>,
    id: String,
    path: String,
    new_name: String,
) -> CommandResult<String> {
    let jail = jail(&launcher, &id).await?;
    Ok(blocking(move || jail.rename(&path, new_name.trim())).await?)
}

/// In den Papierkorb (Windows) bzw. XDG-Trash (Linux). Liefert die Anzahl.
#[tauri::command]
pub async fn trash_instance_files(launcher: State<'_, LauncherState>, id: String, paths: Vec<String>) -> CommandResult<u32> {
    let jail = jail(&launcher, &id).await?;
    Ok(blocking(move || jail.trash(&paths)).await?)
}

/// Ordner öffnen bzw. harmlose Dateien (Text, Bilder …) mit dem Standardprogramm.
/// Alles andere (z. B. `.jar`, `.exe`, Skripte) wird nur im Explorer markiert.
#[tauri::command]
pub async fn open_instance_file(app: AppHandle, launcher: State<'_, LauncherState>, id: String, path: String) -> CommandResult<()> {
    let jail = jail(&launcher, &id).await?;
    let target = blocking(move || jail.existing_path(&path)).await?;
    let name = target.file_name().and_then(|n| n.to_str()).unwrap_or_default();
    if target.is_dir() || opens_directly(name) {
        crate::open::path(&app, target.display().to_string())?;
    } else {
        app.opener().reveal_item_in_dir(target)?;
    }
    Ok(())
}

#[tauri::command]
pub async fn reveal_instance_file(app: AppHandle, launcher: State<'_, LauncherState>, id: String, path: String) -> CommandResult<()> {
    let jail = jail(&launcher, &id).await?;
    let target = blocking(move || jail.existing_path(&path)).await?;
    app.opener().reveal_item_in_dir(target)?;
    Ok(())
}

/// Dateien per Dateidialog in den Ordner `parent` kopieren. `None` = abgebrochen.
#[tauri::command]
pub async fn pick_instance_upload(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    parent: String,
) -> CommandResult<Option<ImportReport>> {
    let jail = jail(&launcher, &id).await?;
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog().file().set_title(DialogText::UploadFiles.text(lang)).blocking_pick_files()
    })
    .await
    .ok()
    .flatten();
    let Some(picked) = picked else { return Ok(None) };
    let files: Vec<PathBuf> = picked.into_iter().filter_map(|p| p.into_path().ok()).collect();
    Ok(Some(blocking(move || jail.import(&parent, &files)).await?))
}

/// Ins Fenster gezogene Dateien/Ordner (Marke aus dem `file-drop`-Event) nach `parent` kopieren.
#[tauri::command]
pub async fn import_dropped_instance_files(
    launcher: State<'_, LauncherState>,
    drops: State<'_, DropState>,
    id: String,
    parent: String,
    token: u64,
) -> CommandResult<ImportReport> {
    let jail = jail(&launcher, &id).await?;
    let files = drops.take(token).ok_or_else(|| {
        trs_core::Error::validation(trs_core::msg!(
            "commands.dropExpired",
            "Die gezogenen Dateien sind nicht mehr verfügbar – bitte erneut ziehen."
        ))
    })?;
    Ok(blocking(move || jail.import(&parent, &files)).await?)
}
