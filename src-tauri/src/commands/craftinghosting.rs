//! Kostenloser Server beim Partner CraftingHosting – nur über die TRS API.

use tauri::State;
use tauri::ipc::Channel;
use std::collections::BTreeMap;

use trs_core::trs_api::craftinghosting::{Info, ModsResult, NewServer, Server, Settings, SettingsPatch, Stats, SubdomainCheck};
use trs_core::trs_api::craftinghosting_files::{Addons, DirListing, FileContent, Properties, SavedFile};
use trs_core::trs_api::craftinghosting_world::WorldProgress;

use crate::LauncherState;
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

/// Welt einer Instanz packen, hochladen und einspielen (Server muss aus sein).
#[tauri::command]
pub async fn craftinghosting_upload_world(
    launcher: State<'_, LauncherState>,
    instance_id: String,
    folder: String,
    on_progress: Channel<WorldProgress>,
) -> CommandResult<Server> {
    trs_core::platform::desktop_only()?;
    let launcher = launcher.inner().clone();
    Ok(launcher
        .craftinghosting_upload_world(&instance_id, &folder, move |p| {
            let _ = on_progress.send(p);
        })
        .await?)
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
