use serde::Serialize;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;

use crate::LauncherState;
use crate::error::CommandResult;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    version: &'static str,
    data_dir: String,
}

#[tauri::command]
pub fn app_info(launcher: State<'_, LauncherState>) -> AppInfo {
    AppInfo {
        version: trs_core::LAUNCHER_VERSION,
        data_dir: launcher.paths().root().display().to_string(),
    }
}

#[tauri::command]
pub fn open_data_dir(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<()> {
    let path = launcher.paths().root().display().to_string();
    app.opener().open_path(path, None::<&str>)?;
    Ok(())
}
