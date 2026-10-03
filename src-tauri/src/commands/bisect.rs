//! „Schuldige Mod finden“ – binäre Suche über die Mods einer Instanz. Logik,
//! Prüfungen und das Wiederherstellen stecken im Kern (`trs_core::bisect`).

use tauri::State;
use trs_core::bisect::BisectView;

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn bisect_status(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Option<BisectView>> {
    Ok(launcher.bisect_status(&id).await?)
}

/// Alle laufenden Suchen (auch aus einer früheren Sitzung).
#[tauri::command]
pub async fn bisect_active(launcher: State<'_, LauncherState>) -> CommandResult<Vec<BisectView>> {
    Ok(launcher.bisect_active().await?)
}

#[tauri::command]
pub async fn bisect_start(launcher: State<'_, LauncherState>, id: String) -> CommandResult<BisectView> {
    Ok(launcher.bisect_start(&id).await?)
}

/// `failed`: Der Fehler trat in Runde `round` noch auf.
#[tauri::command]
pub async fn bisect_answer(
    launcher: State<'_, LauncherState>,
    id: String,
    round: u32,
    failed: bool,
) -> CommandResult<BisectView> {
    Ok(launcher.bisect_answer(&id, round, failed).await?)
}

#[tauri::command]
pub async fn bisect_continue(launcher: State<'_, LauncherState>, id: String) -> CommandResult<BisectView> {
    Ok(launcher.bisect_continue(&id).await?)
}

/// Beenden oder Abbrechen: stellt den ursprünglichen Zustand wieder her.
#[tauri::command]
pub async fn bisect_finish(launcher: State<'_, LauncherState>, id: String, disable_result: bool) -> CommandResult<()> {
    Ok(launcher.bisect_finish(&id, disable_result).await?)
}
