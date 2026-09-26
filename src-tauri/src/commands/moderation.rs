//! Moderation v2 (API §22): eigene Strafen + Einspruch und der Team-Bereich
//! (Übersicht, Suche, Akte, Strafen, Einsprüche, Rollen, Welten,
//! Sammelaktionen). Prüfung der Eingaben im Kern; Tokens bleiben dort.

use serde_json::Value;
use tauri::State;
use trs_core::trs_api::sanctions::{MySanction, MySanctions};
use trs_core::trs_api::team::{
    AppealDecision, AppealQuery, BulkAction, BulkTarget, NewSanction, PlayerQuery, SanctionQuery, UploadQuery,
};
use trs_core::trs_api::types::AdminCape;

use crate::LauncherState;
use crate::error::CommandResult;

// --- Spieler ------------------------------------------------------------------------------

#[tauri::command]
pub async fn trs_my_sanctions(launcher: State<'_, LauncherState>) -> CommandResult<MySanctions> {
    Ok(launcher.trs_my_sanctions().await?)
}

#[tauri::command]
pub async fn trs_appeal(launcher: State<'_, LauncherState>, id: u64, text: String) -> CommandResult<MySanction> {
    Ok(launcher.trs_appeal(id, &text).await?)
}

// --- Team ---------------------------------------------------------------------------------

#[tauri::command]
pub async fn admin_dashboard(launcher: State<'_, LauncherState>) -> CommandResult<Value> {
    Ok(launcher.admin_dashboard().await?)
}

#[tauri::command]
pub async fn admin_search(launcher: State<'_, LauncherState>, q: String) -> CommandResult<Value> {
    Ok(launcher.admin_search(&q).await?)
}

#[tauri::command]
pub async fn admin_players(launcher: State<'_, LauncherState>, query: PlayerQuery) -> CommandResult<Value> {
    Ok(launcher.admin_players(&query).await?)
}

#[tauri::command]
pub async fn admin_player(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<Value> {
    Ok(launcher.admin_player(&uuid).await?)
}

#[tauri::command]
pub async fn admin_player_note(launcher: State<'_, LauncherState>, uuid: String, text: String) -> CommandResult<Value> {
    Ok(launcher.admin_player_note(&uuid, &text).await?)
}

#[tauri::command]
pub async fn admin_player_note_delete(launcher: State<'_, LauncherState>, uuid: String, id: u64) -> CommandResult<Value> {
    Ok(launcher.admin_player_note_delete(&uuid, id).await?)
}

#[tauri::command]
pub async fn admin_sanctions(launcher: State<'_, LauncherState>, query: SanctionQuery) -> CommandResult<Value> {
    Ok(launcher.admin_sanctions(&query).await?)
}

#[tauri::command]
pub async fn admin_sanction(launcher: State<'_, LauncherState>, id: u64) -> CommandResult<Value> {
    Ok(launcher.admin_sanction(id).await?)
}

#[tauri::command]
pub async fn admin_sanction_create(launcher: State<'_, LauncherState>, sanction: NewSanction) -> CommandResult<Value> {
    Ok(launcher.admin_sanction_create(&sanction).await?)
}

#[tauri::command]
pub async fn admin_sanction_lift(launcher: State<'_, LauncherState>, id: u64, reason: String) -> CommandResult<Value> {
    Ok(launcher.admin_sanction_lift(id, &reason).await?)
}

#[tauri::command]
pub async fn admin_sanction_duration(
    launcher: State<'_, LauncherState>,
    id: u64,
    ends_at: Option<String>,
    reason: String,
) -> CommandResult<Value> {
    Ok(launcher.admin_sanction_duration(id, ends_at.as_deref(), &reason).await?)
}

#[tauri::command]
pub async fn admin_appeals(launcher: State<'_, LauncherState>, query: AppealQuery) -> CommandResult<Value> {
    Ok(launcher.admin_appeals(&query).await?)
}

#[tauri::command]
pub async fn admin_appeal_decide(launcher: State<'_, LauncherState>, id: u64, decision: AppealDecision) -> CommandResult<Value> {
    Ok(launcher.admin_appeal_decide(id, &decision).await?)
}

#[tauri::command]
pub async fn admin_roles(launcher: State<'_, LauncherState>) -> CommandResult<Value> {
    Ok(launcher.admin_roles().await?)
}

#[tauri::command]
pub async fn admin_role_set(
    launcher: State<'_, LauncherState>,
    uuid: String,
    role: String,
    note: Option<String>,
) -> CommandResult<Value> {
    Ok(launcher.admin_role_set(&uuid, &role, note.as_deref()).await?)
}

#[tauri::command]
pub async fn admin_role_remove(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<Value> {
    Ok(launcher.admin_role_remove(&uuid).await?)
}

#[tauri::command]
pub async fn admin_rooms(launcher: State<'_, LauncherState>) -> CommandResult<Value> {
    Ok(launcher.admin_rooms().await?)
}

#[tauri::command]
pub async fn admin_room_close(launcher: State<'_, LauncherState>, id: String, reason: Option<String>) -> CommandResult<()> {
    Ok(launcher.admin_room_close(&id, reason.as_deref()).await?)
}

#[tauri::command]
pub async fn admin_bulk(launcher: State<'_, LauncherState>, target: BulkTarget, bulk: BulkAction) -> CommandResult<Value> {
    Ok(launcher.admin_bulk(target, &bulk).await?)
}

#[tauri::command]
pub async fn admin_cosmetics(launcher: State<'_, LauncherState>, query: UploadQuery) -> CommandResult<Value> {
    Ok(launcher.admin_cosmetics(&query).await?)
}

#[tauri::command]
pub async fn admin_delete_cosmetic(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.admin_delete_cosmetic(&id).await?)
}

#[tauri::command]
pub async fn admin_capes(launcher: State<'_, LauncherState>, query: UploadQuery) -> CommandResult<Vec<AdminCape>> {
    Ok(launcher.trs_admin_capes_filtered(&query).await?)
}
