use serde::Serialize;
use tauri::{AppHandle, State};

use crate::LauncherState;
use crate::error::CommandResult;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    version: &'static str,
    data_dir: String,
    /// z. B. „Windows 11 (24H2, Build 26100)“ oder „Arch Linux (Kernel 6.10.2)“.
    os: String,
    /// Was es auf diesem System gibt (Plattform, Firewall, Papierkorb, Clips, Update-Weg).
    capabilities: trs_core::platform::Capabilities,
    /// Schutz der gespeicherten Anmeldedaten: `dpapi`, `keyring`, `file` oder `none`.
    token_protection: &'static str,
}

#[tauri::command]
pub fn app_info(launcher: State<'_, LauncherState>) -> AppInfo {
    AppInfo {
        version: trs_core::LAUNCHER_VERSION,
        data_dir: launcher.paths().root().display().to_string(),
        os: trs_core::system::os_description(),
        capabilities: trs_core::platform::capabilities(),
        token_protection: trs_core::auth::crypto::protection(),
    }
}

/// TRS Client: mitgelieferte Version und ein bereits geladenes Update.
#[tauri::command]
pub async fn client_mod_status(launcher: State<'_, LauncherState>) -> CommandResult<trs_core::client_mod::ClientModStatus> {
    Ok(launcher.client_mod_status().await)
}

#[tauri::command]
pub fn open_data_dir(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<()> {
    let path = launcher.paths().root().display().to_string();
    crate::open::path(&app, path)?;
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
