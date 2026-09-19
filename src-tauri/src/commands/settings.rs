use tauri::State;
use trs_core::settings::Settings;

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn get_settings(launcher: State<'_, LauncherState>) -> CommandResult<Settings> {
    Ok(launcher.settings().await)
}

#[tauri::command]
pub async fn update_settings(
    launcher: State<'_, LauncherState>,
    settings: Settings,
) -> CommandResult<Settings> {
    Ok(launcher.update_settings(settings).await?)
}
