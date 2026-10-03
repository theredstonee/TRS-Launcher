//! PC-Fernbedienung (API §33). Geräte-Geheimnisse bleiben im Kern; Befehle vom Handy prüft der Kern (Signatur,
//! Ziel, Argumente) und gibt sie erst nach dem `claim` beim Server frei – und nur, wenn die Fernbedienung und die
//! Befehlsart in den Einstellungen eingeschaltet sind.

use tauri::State;
use trs_core::trs_api::remote::{ClaimedCommand, DeviceKind, PairCode, RemotePairings, RemotePeer, SentCommand, StatusInput};

use crate::LauncherState;
use crate::error::CommandResult;

/// PC: Kopplungs-Code (+ Link für den QR-Code) für ein Handy.
#[tauri::command]
pub async fn remote_pair_start(launcher: State<'_, LauncherState>) -> CommandResult<PairCode> {
    Ok(launcher.remote_pair_start().await?)
}

/// PC: offenen Kopplungs-Code zurückziehen.
#[tauri::command]
pub async fn remote_pair_cancel(launcher: State<'_, LauncherState>) -> CommandResult<()> {
    Ok(launcher.remote_pair_cancel().await?)
}

/// PC: eigenen Stand an die gekoppelten Handys melden (entprellt von der Oberfläche).
#[tauri::command]
pub async fn remote_publish_status(launcher: State<'_, LauncherState>, status: StatusInput) -> CommandResult<()> {
    Ok(launcher.remote_publish_status(status).await?)
}

/// PC: geprüften Befehl beim Server abholen (genau einmal, vor dem Ablauf).
#[tauri::command]
pub async fn remote_claim(launcher: State<'_, LauncherState>, id: String) -> CommandResult<ClaimedCommand> {
    Ok(launcher.remote_claim(&id).await?)
}

/// PC: Ergebnis eines abgeholten Befehls melden.
#[tauri::command]
pub async fn remote_result(launcher: State<'_, LauncherState>, id: String, ok: bool, error: Option<String>) -> CommandResult<()> {
    Ok(launcher.remote_result(&id, ok, error.as_deref()).await?)
}

/// Handy: Code des PCs einlösen (eingetippt oder aus dem QR-Code).
#[tauri::command]
pub async fn remote_pair_confirm(launcher: State<'_, LauncherState>, code: String) -> CommandResult<RemotePeer> {
    Ok(launcher.remote_pair_confirm(&code).await?)
}

/// Handy: Befehl an einen gekoppelten PC.
#[tauri::command]
pub async fn remote_send(
    launcher: State<'_, LauncherState>,
    desktop_id: String,
    command_type: String,
    instance_id: Option<String>,
    code: Option<String>,
    idempotency_key: String,
) -> CommandResult<SentCommand> {
    Ok(launcher
        .remote_send(&desktop_id, &command_type, instance_id.as_deref(), code.as_deref(), &idempotency_key)
        .await?)
}

/// Gekoppelte Geräte – `kind` = eigene Rolle (`desktop` oder `phone`).
#[tauri::command]
pub async fn remote_pairings(launcher: State<'_, LauncherState>, kind: DeviceKind) -> CommandResult<RemotePairings> {
    Ok(launcher.remote_pairings(kind).await?)
}

/// Kopplung lösen – `kind` = eigene Rolle.
#[tauri::command]
pub async fn remote_unpair(launcher: State<'_, LauncherState>, kind: DeviceKind, peer_id: String) -> CommandResult<()> {
    Ok(launcher.remote_unpair(kind, &peer_id).await?)
}
