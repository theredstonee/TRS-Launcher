use tauri::ipc::Channel;
use tauri::{AppHandle, Manager, State};
use trs_core::gamelog::LogLine;
use trs_core::launch::RunningGame;
use trs_core::prepare::StageProgress;

use crate::LauncherState;
use crate::error::CommandResult;

/// Lädt alles Nötige und startet das Spiel. Fortschritt kommt über den
/// Channel, Logs und Spielende über das Event `game-event`.
#[tauri::command]
pub async fn launch_instance(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    join_server: Option<String>,
    on_progress: Channel<StageProgress>,
) -> CommandResult<u32> {
    let pid = launcher
        .launch(&id, join_server.as_deref(), &move |progress| {
            let _ = on_progress.send(progress);
        })
        .await?;

    if launcher.settings().await.close_on_launch
        && let Some(window) = app.get_webview_window("main")
    {
        let _ = window.minimize();
    }
    Ok(pid)
}

#[tauri::command]
pub fn stop_instance(launcher: State<'_, LauncherState>, id: String) -> bool {
    launcher.games().kill(&id)
}

#[tauri::command]
pub fn running_games(launcher: State<'_, LauncherState>) -> Vec<RunningGame> {
    launcher.games().running()
}

#[tauri::command]
pub fn get_game_logs(launcher: State<'_, LauncherState>, id: String) -> Vec<LogLine> {
    launcher.games().logs(&id)
}

/// Prüft alle Spieldateien per Prüfsumme und lädt beschädigte neu.
#[tauri::command]
pub async fn repair_instance(
    launcher: State<'_, LauncherState>,
    id: String,
    on_progress: Channel<StageProgress>,
) -> CommandResult<()> {
    Ok(launcher
        .repair_instance(&id, &move |progress| {
            let _ = on_progress.send(progress);
        })
        .await?)
}

/// Lädt den neuesten Log (Tokens und Benutzername geschwärzt) auf mclo.gs hoch
/// und liefert den Link.
#[tauri::command]
pub async fn share_log(launcher: State<'_, LauncherState>, id: String) -> CommandResult<String> {
    Ok(launcher.share_log(&id).await?)
}
