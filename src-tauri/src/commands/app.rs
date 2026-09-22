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
    /// z. B. „Windows 11 (24H2, Build 26100)“.
    os: String,
}

#[tauri::command]
pub fn app_info(launcher: State<'_, LauncherState>) -> AppInfo {
    AppInfo {
        version: trs_core::LAUNCHER_VERSION,
        data_dir: launcher.paths().root().display().to_string(),
        os: trs_core::system::os_description(),
    }
}

#[tauri::command]
pub fn open_data_dir(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<()> {
    let path = launcher.paths().root().display().to_string();
    app.opener().open_path(path, None::<&str>)?;
    Ok(())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FirewallStatus {
    total: usize,
    missing: usize,
}

#[tauri::command]
pub async fn firewall_status(launcher: State<'_, LauncherState>) -> CommandResult<FirewallStatus> {
    Ok(FirewallStatus {
        total: trs_core::firewall::runtime_programs(launcher.paths()).len(),
        missing: trs_core::firewall::missing(launcher.paths()).await.len(),
    })
}

/// Trägt die Firewall-Freigabe für alle Java-Versionen des Launchers ein
/// (eine Windows-Admin-Abfrage).
#[tauri::command]
pub async fn firewall_allow_all(launcher: State<'_, LauncherState>) -> CommandResult<usize> {
    let programs = trs_core::firewall::runtime_programs(launcher.paths());
    Ok(trs_core::firewall::allow(launcher.paths(), programs).await?)
}
