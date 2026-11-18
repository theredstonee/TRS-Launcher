//! Kostenloser Server beim Partner CraftingHosting – nur über die TRS API.

use tauri::State;
use trs_core::trs_api::craftinghosting::{Info, NewServer, Server};

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
