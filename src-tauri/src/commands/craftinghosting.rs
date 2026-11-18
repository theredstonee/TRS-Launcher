//! Kostenloser Server beim Partner CraftingHosting – nur über die TRS API.

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use std::collections::BTreeMap;

use trs_core::trs_api::craftinghosting::{
    Info, ModsResult, NewServer, Server, Settings, SettingsPatch, Stats, SubdomainCheck, VersionPick,
};
use trs_core::trs_api::craftinghosting_backups::{Backups, VersionChange, backup_file_name};
use trs_core::trs_api::craftinghosting_files::{Addons, DirListing, FileContent, Properties, SavedFile};
use trs_core::trs_api::craftinghosting_world::{UploadTarget, WorldProgress, WorldSource};
use trs_core::trs_api::craftinghosting_worlds::{WorldDownload, Worlds, download_file_name};

use crate::LauncherState;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

#[tauri::command]
pub async fn craftinghosting_info(launcher: State<'_, LauncherState>) -> CommandResult<Info> {
    Ok(launcher.craftinghosting_info().await?)
}

/// `null` = noch kein Server.
#[tauri::command]
pub async fn craftinghosting_server(launcher: State<'_, LauncherState>) -> CommandResult<Option<Server>> {
    Ok(launcher.craftinghosting_server().await?)
}

#[tauri::command]
pub async fn craftinghosting_create(launcher: State<'_, LauncherState>, spec: NewServer) -> CommandResult<Server> {
    trs_core::platform::desktop_only()?;
    Ok(launcher.craftinghosting_create(&spec).await?)
}

#[tauri::command]
pub async fn craftinghosting_set_access(launcher: State<'_, LauncherState>, access: String) -> CommandResult<Server> {
    Ok(launcher.craftinghosting_set_access(&access).await?)
}

/// Starten braucht den Heartbeat – den gibt es nur am PC.
#[tauri::command]
pub async fn craftinghosting_start(launcher: State<'_, LauncherState>, accept_eula: bool) -> CommandResult<Server> {
    trs_core::platform::desktop_only()?;
    Ok(launcher.craftinghosting_start(accept_eula).await?)
}

#[tauri::command]
pub async fn craftinghosting_stop(launcher: State<'_, LauncherState>) -> CommandResult<Server> {
    Ok(launcher.craftinghosting_stop().await?)
}

#[tauri::command]
pub async fn craftinghosting_command(launcher: State<'_, LauncherState>, command: String) -> CommandResult<()> {
    Ok(launcher.craftinghosting_command(&command).await?)
}

#[tauri::command]
pub async fn craftinghosting_delete(launcher: State<'_, LauncherState>) -> CommandResult<()> {
    Ok(launcher.craftinghosting_delete().await?)
}

#[tauri::command]
pub async fn craftinghosting_rename(launcher: State<'_, LauncherState>, name: String) -> CommandResult<Server> {
    Ok(launcher.craftinghosting_rename(&name).await?)
}

/// Ist `<name>.trs-launcher.de` noch frei?
#[tauri::command]
pub async fn craftinghosting_check_subdomain(launcher: State<'_, LauncherState>, name: String) -> CommandResult<SubdomainCheck> {
    Ok(launcher.craftinghosting_check_subdomain(&name).await?)
}

#[tauri::command]
pub async fn craftinghosting_set_subdomain(launcher: State<'_, LauncherState>, name: String) -> CommandResult<Server> {
    Ok(launcher.craftinghosting_set_subdomain(&name).await?)
}

#[tauri::command]
pub async fn craftinghosting_settings(launcher: State<'_, LauncherState>) -> CommandResult<Settings> {
    Ok(launcher.craftinghosting_settings().await?)
}

#[tauri::command]
pub async fn craftinghosting_save_settings(launcher: State<'_, LauncherState>, patch: SettingsPatch) -> CommandResult<Settings> {
    Ok(launcher.craftinghosting_save_settings(&patch).await?)
}

#[tauri::command]
pub async fn craftinghosting_logs(launcher: State<'_, LauncherState>, lines: u32) -> CommandResult<Vec<String>> {
    Ok(launcher.craftinghosting_logs(lines).await?)
}

#[tauri::command]
pub async fn craftinghosting_stats(launcher: State<'_, LauncherState>) -> CommandResult<Stats> {
    Ok(launcher.craftinghosting_stats().await?)
}

#[tauri::command]
pub async fn craftinghosting_mods(launcher: State<'_, LauncherState>, projects: Vec<String>) -> CommandResult<ModsResult> {
    Ok(launcher.craftinghosting_mods(&projects).await?)
}

/// Bestimmte Modrinth-Versionen auf den Server (1–20).
#[tauri::command]
pub async fn craftinghosting_install_versions(launcher: State<'_, LauncherState>, versions: Vec<VersionPick>) -> CommandResult<ModsResult> {
    Ok(launcher.craftinghosting_install_versions(&versions).await?)
}

/// Welt einer Instanz packen, hochladen und einspielen (Server muss aus sein).
/// Ohne `target` ersetzt sie die Welt `world`, sonst kommt sie als eigene Welt dazu.
#[tauri::command]
pub async fn craftinghosting_upload_world(
    launcher: State<'_, LauncherState>,
    instance_id: String,
    folder: String,
    target: Option<UploadTarget>,
    on_progress: Channel<WorldProgress>,
) -> CommandResult<Server> {
    trs_core::platform::desktop_only()?;
    let launcher = launcher.inner().clone();
    let source = WorldSource::Instance { instance_id, folder };
    Ok(launcher
        .craftinghosting_upload_world(source, &target.unwrap_or_default(), move |p| {
            let _ = on_progress.send(p);
        })
        .await?)
}

/// Welt von der Festplatte (`kind` = `zip` oder `folder`) per Dialog wählen und hochladen.
/// `None` = Dialog abgebrochen. Der Pfad bleibt in Rust.
#[tauri::command]
pub async fn craftinghosting_upload_world_disk(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    kind: String,
    target: UploadTarget,
    on_progress: Channel<WorldProgress>,
) -> CommandResult<Option<Server>> {
    trs_core::platform::desktop_only()?;
    let lang = dialog_text::language(&launcher).await;
    let zip = kind == "zip";
    let dialog_app = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        if zip {
            dialog_app
                .dialog()
                .file()
                .set_title(DialogText::PickWorldZip.text(lang))
                .add_filter(DialogText::ZipArchive.text(lang), &["zip"])
                .blocking_pick_file()
                .and_then(|p| p.into_path().ok())
        } else {
            crate::commands::system::blocking_pick_folder(&dialog_app, DialogText::PickWorldFolder.text(lang))
        }
    })
    .await
    .ok()
    .flatten();
    let Some(path) = picked else { return Ok(None) };
    let source = if zip { WorldSource::Zip(path) } else { WorldSource::Folder(path) };
    let launcher = launcher.inner().clone();
    Ok(Some(
        launcher
            .craftinghosting_upload_world(source, &target, move |p| {
                let _ = on_progress.send(p);
            })
            .await?,
    ))
}

#[tauri::command]
pub async fn craftinghosting_worlds(launcher: State<'_, LauncherState>) -> CommandResult<Worlds> {
    Ok(launcher.craftinghosting_worlds().await?)
}

#[tauri::command]
pub async fn craftinghosting_set_active_world(launcher: State<'_, LauncherState>, name: String) -> CommandResult<Worlds> {
    Ok(launcher.craftinghosting_set_active_world(&name).await?)
}

#[tauri::command]
pub async fn craftinghosting_delete_world(launcher: State<'_, LauncherState>, name: String) -> CommandResult<Worlds> {
    Ok(launcher.craftinghosting_delete_world(&name).await?)
}

/// Welt als ZIP speichern (Dialog, Vorschlag `<server>-<welt>-<datum>.zip`). `None` = abgebrochen.
/// Abbrechen läuft über `taskId` (`cancel_task`).
#[tauri::command]
pub async fn craftinghosting_download_world(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    name: String,
    server_name: String,
    on_progress: Channel<WorldProgress>,
    task_id: Option<String>,
) -> CommandResult<Option<WorldDownload>> {
    trs_core::platform::desktop_only()?;
    let name = trs_core::trs_api::craftinghosting_worlds::world_name(&name)?;
    let suggestion = download_file_name(&server_name, &name, &chrono::Local::now().format("%Y-%m-%d").to_string());
    let lang = dialog_text::language(&launcher).await;
    let dialog_app = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        dialog_app
            .dialog()
            .file()
            .set_title(DialogText::SaveWorld.text(lang))
            .set_file_name(suggestion)
            .add_filter(DialogText::ZipArchive.text(lang), &["zip"])
            .blocking_save_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(dest) = picked else { return Ok(None) };
    let launcher = launcher.inner().clone();
    let work = launcher.craftinghosting_download_world(&name, &dest, move |p| {
        let _ = on_progress.send(p);
    });
    Ok(Some(crate::commands::tasks::tracked(&app, task_id, work).await?))
}

// --- Dateien, server.properties, Plugins/Mods --------------------------------------------

#[tauri::command]
pub async fn craftinghosting_files(launcher: State<'_, LauncherState>, dir: String) -> CommandResult<DirListing> {
    Ok(launcher.craftinghosting_files(&dir).await?)
}

#[tauri::command]
pub async fn craftinghosting_file(launcher: State<'_, LauncherState>, path: String) -> CommandResult<FileContent> {
    Ok(launcher.craftinghosting_file(&path).await?)
}

#[tauri::command]
pub async fn craftinghosting_save_file(launcher: State<'_, LauncherState>, path: String, content: String) -> CommandResult<SavedFile> {
    Ok(launcher.craftinghosting_save_file(&path, &content).await?)
}

#[tauri::command]
pub async fn craftinghosting_delete_files(launcher: State<'_, LauncherState>, paths: Vec<String>) -> CommandResult<()> {
    Ok(launcher.craftinghosting_delete_files(&paths).await?)
}

#[tauri::command]
pub async fn craftinghosting_create_folder(launcher: State<'_, LauncherState>, path: String) -> CommandResult<()> {
    Ok(launcher.craftinghosting_create_folder(&path).await?)
}

#[tauri::command]
pub async fn craftinghosting_properties(launcher: State<'_, LauncherState>) -> CommandResult<Properties> {
    Ok(launcher.craftinghosting_properties().await?)
}

#[tauri::command]
pub async fn craftinghosting_save_properties(
    launcher: State<'_, LauncherState>,
    changes: BTreeMap<String, String>,
) -> CommandResult<Properties> {
    Ok(launcher.craftinghosting_save_properties(&changes).await?)
}

#[tauri::command]
pub async fn craftinghosting_addons(launcher: State<'_, LauncherState>) -> CommandResult<Addons> {
    Ok(launcher.craftinghosting_addons().await?)
}

#[tauri::command]
pub async fn craftinghosting_remove_addon(launcher: State<'_, LauncherState>, file: String) -> CommandResult<()> {
    Ok(launcher.craftinghosting_remove_addon(&file).await?)
}

// --- Versionswechsel und Backups (nur mit Funktions-Schalter der TRS API) ------------------

#[tauri::command]
pub async fn craftinghosting_change_version(launcher: State<'_, LauncherState>, change: VersionChange) -> CommandResult<Server> {
    Ok(launcher.craftinghosting_change_version(&change).await?)
}

#[tauri::command]
pub async fn craftinghosting_backups(launcher: State<'_, LauncherState>) -> CommandResult<Backups> {
    Ok(launcher.craftinghosting_backups().await?)
}

#[tauri::command]
pub async fn craftinghosting_create_backup(launcher: State<'_, LauncherState>) -> CommandResult<()> {
    Ok(launcher.craftinghosting_create_backup().await?)
}

#[tauri::command]
pub async fn craftinghosting_restore_backup(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.craftinghosting_restore_backup(&id).await?)
}

#[tauri::command]
pub async fn craftinghosting_delete_backup(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.craftinghosting_delete_backup(&id).await?)
}

/// Backup als ZIP speichern (Dialog). `None` = abgebrochen; Abbrechen über `taskId`.
#[tauri::command]
pub async fn craftinghosting_download_backup(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    name: String,
    server_name: String,
    on_progress: Channel<WorldProgress>,
    task_id: Option<String>,
) -> CommandResult<Option<String>> {
    trs_core::platform::desktop_only()?;
    let suggestion = backup_file_name(&server_name, &name);
    let lang = dialog_text::language(&launcher).await;
    let dialog_app = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        dialog_app
            .dialog()
            .file()
            .set_title(DialogText::SaveBackup.text(lang))
            .set_file_name(suggestion)
            .add_filter(DialogText::ZipArchive.text(lang), &["zip"])
            .blocking_save_file()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    let Some(dest) = picked else { return Ok(None) };
    let file_name = dest.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let launcher = launcher.inner().clone();
    let work = launcher.craftinghosting_download_backup(&id, &dest, move |p| {
        let _ = on_progress.send(p);
    });
    crate::commands::tasks::tracked(&app, task_id, work).await?;
    Ok(Some(file_name))
}
