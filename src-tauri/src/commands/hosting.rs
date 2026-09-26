//! Welt-Hosting (§21): offene Welten von Freunden, Beitreten/Anfragen und der
//! Stand der Übergabe ans Spiel. Gehostet wird im Spiel; Verbindungsdaten
//! (Relay-Token, STUN, Signale) bleiben im Kern bzw. holt die Mod selbst.

use tauri::State;
use tauri::ipc::Channel;
use trs_core::hosting_mods::{PreparePlan, PrepareProgress, PrepareResult, RoomContent};
use trs_core::link::HostingDelivery;
use trs_core::trs_api::hosting::{HostingJoinResult, HostingRoom, JoinTarget};

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn hosting_friends_rooms(launcher: State<'_, LauncherState>) -> CommandResult<Vec<HostingRoom>> {
    Ok(launcher.hosting_friends_rooms().await?)
}

#[tauri::command]
pub async fn hosting_my_rooms(launcher: State<'_, LauncherState>) -> CommandResult<Vec<HostingRoom>> {
    Ok(launcher.hosting_my_rooms().await?)
}

/// `null` = Welt geschlossen oder nicht (mehr) sichtbar.
#[tauri::command]
pub async fn hosting_room(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Option<HostingRoom>> {
    Ok(launcher.hosting_room(&id).await?)
}

#[tauri::command]
pub async fn hosting_join(launcher: State<'_, LauncherState>, target: JoinTarget) -> CommandResult<HostingJoinResult> {
    Ok(launcher.hosting_join(&target).await?)
}

#[tauri::command]
pub async fn hosting_leave(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.hosting_leave(&id).await?)
}

/// Geteilte Mods + Resource Pack einer Welt (§21.10), gesäubert.
#[tauri::command]
pub async fn hosting_room_content(launcher: State<'_, LauncherState>, id: String) -> CommandResult<RoomContent> {
    Ok(launcher.hosting_room_content(&id).await?)
}

/// SHA-1 der Mods einer Instanz (für „vorhanden“/„fehlt“ im Dialog).
#[tauri::command]
pub async fn hosting_instance_mods(launcher: State<'_, LauncherState>, instance_id: String) -> CommandResult<Vec<String>> {
    Ok(launcher.hosting_instance_mods(&instance_id).await?)
}

/// Instanz für eine Welt mit Mods vorbereiten (neu oder Kopie ergänzen), Mods laden und prüfen.
#[tauri::command]
pub async fn hosting_prepare(
    launcher: State<'_, LauncherState>,
    plan: PreparePlan,
    on_progress: Channel<PrepareProgress>,
) -> CommandResult<PrepareResult> {
    let launcher = launcher.inner().clone();
    Ok(launcher
        .hosting_prepare(plan, move |p| {
            let _ = on_progress.send(p);
        })
        .await?)
}

/// Wurde der Welt-Beitritt schon ans Spiel dieser Instanz übergeben?
#[tauri::command]
pub fn hosting_delivery(launcher: State<'_, LauncherState>, instance_id: String) -> HostingDelivery {
    launcher.hosting_delivery(&instance_id)
}
