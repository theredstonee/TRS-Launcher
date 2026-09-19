use tauri::State;
use trs_core::meta::VersionManifest;

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn get_version_manifest(
    launcher: State<'_, LauncherState>,
    force_refresh: Option<bool>,
) -> CommandResult<VersionManifest> {
    Ok(launcher.version_manifest(force_refresh.unwrap_or(false)).await?)
}
