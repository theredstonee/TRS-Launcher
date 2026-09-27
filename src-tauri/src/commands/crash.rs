//! Absturz-Helfer: gespeicherte Analysen lesen, Log-Dateien analysieren und
//! die Behebungen, die es nicht schon als eigene Befehle gibt (RAM, Java,
//! TRS Client). Mods deaktivieren/installieren laufen über die bestehenden
//! Inhalts-Befehle. Alles lokal – gesendet wird nichts.

use tauri::State;
use tauri::ipc::Channel;
use trs_core::crash::{CrashAnalysis, CrashSummary};
use trs_core::download::Progress;

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn list_crashes(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Vec<CrashSummary>> {
    Ok(launcher.list_crashes(&id).await?)
}

#[tauri::command]
pub async fn get_crash(launcher: State<'_, LauncherState>, id: String, crash_id: String) -> CommandResult<CrashAnalysis> {
    Ok(launcher.get_crash(&id, &crash_id).await?)
}

/// Log-Datei oder Crash-Report aus dem Logs-Tab analysieren (Quelle-ID wie `read_log_source`).
#[tauri::command]
pub async fn analyze_log_source(launcher: State<'_, LauncherState>, id: String, source: String) -> CommandResult<CrashAnalysis> {
    Ok(launcher.analyze_log_source(&id, &source).await?)
}

#[tauri::command]
pub async fn set_instance_memory(launcher: State<'_, LauncherState>, id: String, mb: u32) -> CommandResult<()> {
    Ok(launcher.set_instance_memory(&id, mb).await?)
}

/// `major`: diese Java-Version installieren und einstellen; `null` = automatisch.
#[tauri::command]
pub async fn switch_instance_java(
    app: tauri::AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    major: Option<u32>,
    on_progress: Channel<f64>,
    task_id: Option<String>,
) -> CommandResult<Option<u32>> {
    let report = move |p: Progress| {
        let _ = on_progress.send(p.percent().floor());
    };
    let work = launcher.switch_instance_java(&id, major, &report);
    Ok(crate::commands::tasks::tracked(&app, task_id, work).await?)
}

#[tauri::command]
pub async fn update_trs_client_now(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Option<String>> {
    Ok(launcher.update_trs_client_now(&id).await?)
}
