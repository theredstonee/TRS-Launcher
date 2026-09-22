use std::path::PathBuf;

use serde::Serialize;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;
use trs_core::Launcher;
use trs_core::history::HistoryEntry;
use trs_core::instance::{Instance, Loader, LoaderKind, NewInstance, UpdateInstance};
use trs_core::loaders::LoaderVersionInfo;

use crate::LauncherState;
use crate::commands::extras::allow;
use crate::error::CommandResult;

/// Instanz plus Pfad zum Bild – die Datei ist einzeln fürs Webview freigegeben
/// (`convertFileSrc`), nicht der ganze Ordner.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceView {
    #[serde(flatten)]
    instance: Instance,
    icon_path: Option<PathBuf>,
    /// Breites Titelbild – ebenfalls nur diese eine Datei freigegeben.
    banner_path: Option<PathBuf>,
}

pub(crate) fn view(app: &AppHandle, launcher: &Launcher, instance: Instance) -> InstanceView {
    let icon_path = launcher.instance_icon_path(&instance).filter(|p| allow(app, p));
    let banner_path = launcher.instance_banner_path(&instance).filter(|p| allow(app, p));
    InstanceView { instance, icon_path, banner_path }
}

#[tauri::command]
pub async fn list_instances(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Vec<InstanceView>> {
    let list = launcher.instances().list().await?;
    Ok(list.into_iter().map(|i| view(&app, &launcher, i)).collect())
}

#[tauri::command]
pub async fn get_instance(app: AppHandle, launcher: State<'_, LauncherState>, id: String) -> CommandResult<InstanceView> {
    let instance = launcher.instances().get(&id).await?;
    Ok(view(&app, &launcher, instance))
}

#[tauri::command]
pub async fn create_instance(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    instance: NewInstance,
) -> CommandResult<InstanceView> {
    let created = launcher.create_instance(instance).await?;
    Ok(view(&app, &launcher, created))
}

#[tauri::command]
pub async fn update_instance(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    update: UpdateInstance,
) -> CommandResult<InstanceView> {
    let updated = launcher.update_instance(&id, update).await?;
    Ok(view(&app, &launcher, updated))
}

#[tauri::command]
pub async fn set_instance_group(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    group: Option<String>,
) -> CommandResult<InstanceView> {
    let updated = launcher.set_instance_group(&id, group.as_deref()).await?;
    Ok(view(&app, &launcher, updated))
}

/// Alle Loader-Versionen für eine Spielversion (neueste zuerst).
#[tauri::command]
pub async fn loader_versions(
    launcher: State<'_, LauncherState>,
    kind: LoaderKind,
    game_version: String,
) -> CommandResult<Vec<LoaderVersionInfo>> {
    Ok(trs_core::loaders::available_versions(launcher.http(), kind, &game_version).await?)
}

/// Was „neueste stabile“ für diese Spielversion gerade bedeutet.
#[tauri::command]
pub async fn latest_loader_version(
    launcher: State<'_, LauncherState>,
    kind: LoaderKind,
    game_version: String,
) -> CommandResult<Option<String>> {
    Ok(trs_core::loaders::latest_stable(launcher.http(), kind, &game_version).await?)
}

#[tauri::command]
pub async fn delete_instance(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.delete_instance(&id).await?)
}

#[tauri::command]
pub async fn open_instance_dir(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<()> {
    // `get` validiert die ID und stellt sicher, dass die Instanz existiert.
    let instance = launcher.instances().get(&id).await?;
    let path = launcher.paths().instance_game_dir(&instance.id).display().to_string();
    app.opener().open_path(path, None::<&str>)?;
    Ok(())
}

/// Öffnet den Windows-Bilddialog; der Pfad kommt aus dem nativen Dialog,
/// nicht aus dem Webview. `None` = abgebrochen.
#[tauri::command]
pub async fn pick_instance_icon(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<Option<InstanceView>> {
    let instance = launcher.instances().get(&id).await?;
    let dialog_app = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        dialog_app
            .dialog()
            .file()
            .set_title("Bild für die Instanz wählen")
            .add_filter("Bilder", &["png", "jpg", "jpeg", "webp"])
            .blocking_pick_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());

    let Some(file) = picked else { return Ok(None) };
    let updated = launcher.set_instance_icon_from_file(&instance.id, &file).await?;
    Ok(Some(view(&app, &launcher, updated)))
}

#[tauri::command]
pub async fn remove_instance_icon(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<InstanceView> {
    let updated = launcher.remove_instance_icon(&id).await?;
    Ok(view(&app, &launcher, updated))
}

/// Banner per Windows-Bilddialog wählen (Pfad bleibt in Rust). `None` = abgebrochen.
#[tauri::command]
pub async fn pick_instance_banner(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<Option<InstanceView>> {
    let instance = launcher.instances().get(&id).await?;
    let dialog_app = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        dialog_app
            .dialog()
            .file()
            .set_title("Banner für die Instanz wählen")
            .add_filter("Bilder", &["png", "jpg", "jpeg", "webp"])
            .blocking_pick_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());

    let Some(file) = picked else { return Ok(None) };
    let updated = launcher.set_instance_banner_from_file(&instance.id, &file).await?;
    Ok(Some(view(&app, &launcher, updated)))
}

/// Einen Screenshot der Instanz als Banner nehmen – nur der Dateiname kommt
/// aus dem Webview, der Kern prüft ihn gegen den Screenshot-Ordner.
#[tauri::command]
pub async fn set_instance_banner_screenshot(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    file_name: String,
) -> CommandResult<InstanceView> {
    let updated = launcher.set_instance_banner_from_screenshot(&id, &file_name).await?;
    Ok(view(&app, &launcher, updated))
}

#[tauri::command]
pub async fn remove_instance_banner(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
) -> CommandResult<InstanceView> {
    let updated = launcher.remove_instance_banner(&id).await?;
    Ok(view(&app, &launcher, updated))
}

/// Wechselt Minecraft-Version und/oder Modloader.
#[tauri::command]
pub async fn change_instance_version(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    game_version: String,
    loader: Loader,
) -> CommandResult<InstanceView> {
    let updated = launcher.change_instance_version(&id, &game_version, loader).await?;
    Ok(view(&app, &launcher, updated))
}

#[tauri::command]
pub async fn instance_history(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Vec<HistoryEntry>> {
    Ok(launcher.instance_history(&id).await?)
}
