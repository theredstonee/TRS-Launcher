use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;
use trs_core::instance::{Instance, NewInstance, UpdateInstance};

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn list_instances(launcher: State<'_, LauncherState>) -> CommandResult<Vec<Instance>> {
    Ok(launcher.instances().list().await?)
}

#[tauri::command]
pub async fn get_instance(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Instance> {
    Ok(launcher.instances().get(&id).await?)
}

#[tauri::command]
pub async fn create_instance(
    launcher: State<'_, LauncherState>,
    instance: NewInstance,
) -> CommandResult<Instance> {
    Ok(launcher.create_instance(instance).await?)
}

#[tauri::command]
pub async fn update_instance(
    launcher: State<'_, LauncherState>,
    id: String,
    update: UpdateInstance,
) -> CommandResult<Instance> {
    Ok(launcher.instances().update(&id, update).await?)
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
