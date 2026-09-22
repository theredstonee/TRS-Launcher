use tauri::State;
use trs_core::servers::{self, Server, ServerInput, ServerStatus};

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn list_servers(launcher: State<'_, LauncherState>) -> CommandResult<Vec<Server>> {
    Ok(launcher.servers().list().await?)
}

#[tauri::command]
pub async fn add_server(launcher: State<'_, LauncherState>, server: ServerInput) -> CommandResult<Server> {
    Ok(launcher.servers().add(server).await?)
}

#[tauri::command]
pub async fn update_server(
    launcher: State<'_, LauncherState>,
    id: String,
    server: ServerInput,
) -> CommandResult<Server> {
    Ok(launcher.servers().update(&id, server).await?)
}

#[tauri::command]
pub async fn remove_server(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.servers().remove(&id).await?)
}

/// Live-Status eines gespeicherten Servers. Bewusst per ID statt per Adresse:
/// das Webview soll den Launcher nicht beliebige Hosts anpingen lassen.
#[tauri::command]
pub async fn ping_server(launcher: State<'_, LauncherState>, id: String) -> CommandResult<ServerStatus> {
    let server = launcher.servers().get(&id).await?;
    Ok(servers::ping(&server.address).await?)
}
