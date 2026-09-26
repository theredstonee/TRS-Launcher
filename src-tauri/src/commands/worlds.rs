//! Tab „Welten“: Einzelspieler-Welten und die Server der Instanz.

use std::path::PathBuf;

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use trs_core::servers::{InstanceServer, ServerInput};
use trs_core::worlds;

use super::tasks::tracked;
use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn instance_worlds(app: AppHandle, launcher: State<'_, LauncherState>, id: String) -> CommandResult<Vec<worlds::WorldInfo>> {
    let instance = launcher.instances().get(&id).await?;
    let list = worlds::list(launcher.paths(), &instance.id).await?;
    Ok(list
        .into_iter()
        .map(|mut world| {
            // Icon einzeln fürs Webview freigeben (nur diese Datei).
            world.icon_path = world.icon_path.take().filter(|p| super::extras::allow(&app, p));
            world
        })
        .collect())
}

#[tauri::command]
pub async fn open_world_folder(app: AppHandle, launcher: State<'_, LauncherState>, id: String, folder: String) -> CommandResult<()> {
    let dir: PathBuf = worlds::world_dir(launcher.paths(), &id, &folder)?;
    crate::open::path(&app, dir.display().to_string())?;
    Ok(())
}

/// ZIP-Sicherung nach `backups/`; liefert den Dateinamen.
#[tauri::command]
pub async fn backup_world(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    folder: String,
    on_progress: Channel<u8>,
    task_id: Option<String>,
) -> CommandResult<String> {
    let instance = launcher.instances().get(&id).await?;
    let work = worlds::backup(launcher.paths(), &instance.id, &folder, move |p| {
        let _ = on_progress.send(p);
    });
    Ok(tracked(&app, task_id, work).await?)
}

/// Welt in den Papierkorb – nicht, solange die Instanz läuft (die Welt könnte offen sein).
#[tauri::command]
pub async fn trash_world(launcher: State<'_, LauncherState>, id: String, folder: String) -> CommandResult<()> {
    let instance = launcher.instances().get(&id).await?;
    if launcher.games().is_running(&instance.id) {
        return Err(trs_core::Error::launch(trs_core::msg!(
            "launcher.instanceRunningStopFirst",
            "Die Instanz läuft gerade – bitte erst beenden."
        ))
        .into());
    }
    Ok(worlds::trash(launcher.paths(), &instance.id, &folder).await?)
}

// --- Server der Instanz (servers.dat) ------------------------------------------

#[tauri::command]
pub async fn instance_servers(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Vec<InstanceServer>> {
    let instance = launcher.instances().get(&id).await?;
    Ok(launcher.servers().list_instance(&launcher.paths().instance_game_dir(&instance.id)).await?)
}

#[tauri::command]
pub async fn add_instance_server(launcher: State<'_, LauncherState>, id: String, server: ServerInput) -> CommandResult<()> {
    let instance = launcher.instances().get(&id).await?;
    let game_dir = launcher.paths().instance_game_dir(&instance.id);
    trs_core::fsutil::ensure_dir(&game_dir).await?;
    Ok(launcher.servers().add_to_instance(&game_dir, server).await?)
}

#[tauri::command]
pub async fn update_instance_server(
    launcher: State<'_, LauncherState>,
    id: String,
    index: usize,
    address: String,
    server: ServerInput,
) -> CommandResult<()> {
    let instance = launcher.instances().get(&id).await?;
    let game_dir = launcher.paths().instance_game_dir(&instance.id);
    Ok(launcher.servers().update_in_instance(&game_dir, index, &address, server).await?)
}

#[tauri::command]
pub async fn remove_instance_server(
    launcher: State<'_, LauncherState>,
    id: String,
    index: usize,
    address: String,
) -> CommandResult<()> {
    let instance = launcher.instances().get(&id).await?;
    let game_dir = launcher.paths().instance_game_dir(&instance.id);
    Ok(launcher.servers().remove_from_instance(&game_dir, index, &address).await?)
}
