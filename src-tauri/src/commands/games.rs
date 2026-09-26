use tauri::ipc::Channel;
use tauri::{AppHandle, Manager, State};
use trs_core::gamelog::LogLine;
use trs_core::launch::RunningGame;
use trs_core::prepare::StageProgress;
use trs_core::trs_api::hosting::HostedWorld;

use crate::LauncherState;
use crate::commands::tasks::tracked;
use crate::error::CommandResult;

/// Lädt alles Nötige und startet das Spiel. Fortschritt kommt über den
/// Channel, Logs und Spielende über das Event `game-event`.
#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn launch_instance(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    join_server: Option<String>,
    join_address: Option<String>,
    join_world: Option<HostedWorld>,
    on_progress: Channel<StageProgress>,
    task_id: Option<String>,
) -> CommandResult<u32> {
    // Gehostete Welt: nur Raum-ID, Code und Eckdaten – geprüft, bevor sie ans Spiel gehen.
    let world = join_world.map(HostedWorld::validated).transpose()?;
    // Eine freie Adresse (Server eines Freundes) prüft der Kern wie jede Server-Adresse.
    let join = match (join_server.as_deref(), join_address.as_deref(), world.as_ref()) {
        (_, _, Some(world)) => Some(trs_core::Join::World(world)),
        (Some(id), _, None) => Some(trs_core::Join::Server(id)),
        (None, Some(address), None) => Some(trs_core::Join::Address(address)),
        (None, None, None) => None,
    };
    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = launcher.launch(&id, join, &report);
    let pid = tracked(&app, task_id, work).await?;

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
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    on_progress: Channel<StageProgress>,
    task_id: Option<String>,
) -> CommandResult<()> {
    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = launcher.repair_instance(&id, &report);
    Ok(tracked(&app, task_id, work).await?)
}

/// Lädt Spielversion und Bibliotheken der Instanz komplett neu.
#[tauri::command]
pub async fn reinstall_instance(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    on_progress: Channel<StageProgress>,
    task_id: Option<String>,
) -> CommandResult<()> {
    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = launcher.reinstall_instance(&id, &report);
    Ok(tracked(&app, task_id, work).await?)
}

/// Lädt den neuesten Log (Tokens und Benutzername geschwärzt) auf mclo.gs hoch
/// und liefert den Link.
#[tauri::command]
pub async fn share_log(launcher: State<'_, LauncherState>, id: String) -> CommandResult<String> {
    Ok(launcher.share_log(&id).await?)
}
