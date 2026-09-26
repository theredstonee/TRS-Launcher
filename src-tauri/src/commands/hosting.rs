//! Welt-Hosting (§21): offene Welten von Freunden, Beitreten/Anfragen und der
//! Stand der Übergabe ans Spiel. Gehostet wird im Spiel; Verbindungsdaten
//! (Relay-Token, STUN, Signale) bleiben im Kern bzw. holt die Mod selbst.

use tauri::State;
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

/// Wurde der Welt-Beitritt schon ans Spiel dieser Instanz übergeben?
#[tauri::command]
pub fn hosting_delivery(launcher: State<'_, LauncherState>, instance_id: String) -> HostingDelivery {
    launcher.hosting_delivery(&instance_id)
}
