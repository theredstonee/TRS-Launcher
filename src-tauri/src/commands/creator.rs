//! Creator-Programm (API §35): Codes einlösen, unterstützen, blockieren, das
//! Creator-Dashboard und die Team-Verwaltung. Prüfung der Eingaben im Kern;
//! Tokens bleiben dort, Bilder kommen als Data-URL.

use tauri::State;
use trs_core::trs_api::cape_import;
use trs_core::trs_api::creator::{
    AdminCreator, AdminRequest, CodePatch, CreatorCode, CreatorPatch, CreatorQuery, CreatorRedeem, CreatorRequest,
    CreatorSelf, ListedPacks, MyCreator, NewCreator, NewCreatorCode, NewRequest, PreviewBuild, QueueItem, Revoke,
};
use trs_core::trs_api::types::UserRef;

use crate::LauncherState;
use crate::error::CommandResult;

// --- Spieler / Fans -------------------------------------------------------------------------

#[tauri::command]
pub async fn creator_me(launcher: State<'_, LauncherState>) -> CommandResult<MyCreator> {
    Ok(launcher.creator_me().await?)
}

#[tauri::command]
pub async fn creator_redeem(launcher: State<'_, LauncherState>, code: String) -> CommandResult<CreatorRedeem> {
    Ok(launcher.creator_redeem(&code).await?)
}

#[tauri::command]
pub async fn creator_end_support(launcher: State<'_, LauncherState>) -> CommandResult<()> {
    Ok(launcher.creator_end_support().await?)
}

#[tauri::command]
pub async fn creator_block(launcher: State<'_, LauncherState>, code: String) -> CommandResult<Vec<UserRef>> {
    Ok(launcher.creator_block(&code).await?)
}

#[tauri::command]
pub async fn creator_unblock(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<Vec<UserRef>> {
    Ok(launcher.creator_unblock(&uuid).await?)
}

#[tauri::command]
pub async fn creator_set_show_symbols(launcher: State<'_, LauncherState>, show: bool) -> CommandResult<bool> {
    Ok(launcher.creator_set_show_symbols(show).await?)
}

/// Gelistete Creator-Modpacks (Entdecken).
#[tauri::command]
pub async fn creator_packs(
    launcher: State<'_, LauncherState>,
    q: Option<String>,
    offset: Option<u32>,
    limit: Option<u32>,
) -> CommandResult<ListedPacks> {
    Ok(launcher.creator_packs(q.as_deref(), offset.unwrap_or(0), limit.unwrap_or(20)).await?)
}

// --- Creator-Dashboard ----------------------------------------------------------------------

#[tauri::command]
pub async fn creator_dashboard(launcher: State<'_, LauncherState>, as_creator: Option<String>) -> CommandResult<CreatorSelf> {
    Ok(launcher.creator_dashboard(as_creator.as_deref()).await?)
}

#[tauri::command]
pub async fn creator_create_code(
    launcher: State<'_, LauncherState>,
    code: NewCreatorCode,
    as_creator: Option<String>,
) -> CommandResult<Vec<CreatorCode>> {
    Ok(launcher.creator_create_code(&code, as_creator.as_deref()).await?)
}

#[tauri::command]
pub async fn creator_update_code(
    launcher: State<'_, LauncherState>,
    code: String,
    patch: CodePatch,
    as_creator: Option<String>,
) -> CommandResult<Vec<CreatorCode>> {
    Ok(launcher.creator_update_code(&code, &patch, as_creator.as_deref()).await?)
}

/// Fertiger Symbol-Streifen (PNG als Base64) – der Kern prüft ihn erneut.
#[tauri::command]
pub async fn creator_upload_symbol(
    launcher: State<'_, LauncherState>,
    png: String,
    frame_ms: Option<u32>,
    max_frames: u32,
    as_creator: Option<String>,
) -> CommandResult<()> {
    let bytes = cape_import::decode_upload(&png)?;
    Ok(launcher.creator_upload_symbol(bytes, frame_ms, max_frames, as_creator.as_deref()).await?)
}

#[tauri::command]
pub async fn creator_withdraw_symbol(launcher: State<'_, LauncherState>, as_creator: Option<String>) -> CommandResult<()> {
    Ok(launcher.creator_withdraw_symbol(as_creator.as_deref()).await?)
}

/// Fan-Umhang aus der Umhang-Werkstatt (PNG-Streifen als Base64).
#[tauri::command]
pub async fn creator_upload_fan_cape(
    launcher: State<'_, LauncherState>,
    png: String,
    frames: u32,
    frame_time_ms: Option<u32>,
    name: String,
    as_creator: Option<String>,
) -> CommandResult<()> {
    let bytes = cape_import::decode_upload(&png)?;
    Ok(launcher.creator_upload_fan_cape(bytes, frames, frame_time_ms, &name, as_creator.as_deref()).await?)
}

#[tauri::command]
pub async fn creator_withdraw_fan_cape(launcher: State<'_, LauncherState>, as_creator: Option<String>) -> CommandResult<()> {
    Ok(launcher.creator_withdraw_fan_cape(as_creator.as_deref()).await?)
}

#[tauri::command]
pub async fn creator_request(launcher: State<'_, LauncherState>, request: NewRequest) -> CommandResult<Vec<CreatorRequest>> {
    Ok(launcher.creator_request(&request).await?)
}

#[tauri::command]
pub async fn creator_previews(launcher: State<'_, LauncherState>) -> CommandResult<Vec<PreviewBuild>> {
    Ok(launcher.creator_previews().await?)
}

#[tauri::command]
pub async fn creator_set_pack_listed(
    launcher: State<'_, LauncherState>,
    code: String,
    listed: bool,
    as_creator: Option<String>,
) -> CommandResult<()> {
    Ok(launcher.creator_set_pack_listed(&code, listed, as_creator.as_deref()).await?)
}

// --- Helfer (§35.9) -------------------------------------------------------------------------

#[tauri::command]
pub async fn creator_invite_helper(launcher: State<'_, LauncherState>, uuid: String, rights: Vec<String>) -> CommandResult<()> {
    Ok(launcher.creator_invite_helper(&uuid, &rights).await?)
}

#[tauri::command]
pub async fn creator_update_helper(launcher: State<'_, LauncherState>, uuid: String, rights: Vec<String>) -> CommandResult<()> {
    Ok(launcher.creator_update_helper(&uuid, &rights).await?)
}

#[tauri::command]
pub async fn creator_remove_helper(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<()> {
    Ok(launcher.creator_remove_helper(&uuid).await?)
}

#[tauri::command]
pub async fn creator_answer_helper_invite(launcher: State<'_, LauncherState>, creator: String, accept: bool) -> CommandResult<()> {
    Ok(launcher.creator_answer_helper_invite(&creator, accept).await?)
}

#[tauri::command]
pub async fn creator_leave_helping(launcher: State<'_, LauncherState>, creator: String) -> CommandResult<()> {
    Ok(launcher.creator_leave_helping(&creator).await?)
}

// --- Team -----------------------------------------------------------------------------------

#[tauri::command]
pub async fn admin_creator_queue(launcher: State<'_, LauncherState>) -> CommandResult<Vec<QueueItem>> {
    Ok(launcher.admin_creator_queue().await?)
}

#[tauri::command]
pub async fn admin_creator_decide(
    launcher: State<'_, LauncherState>,
    id: String,
    approve: bool,
    reason: Option<String>,
) -> CommandResult<()> {
    Ok(launcher.admin_creator_decide(&id, approve, reason.as_deref()).await?)
}

#[tauri::command]
pub async fn admin_creators(launcher: State<'_, LauncherState>, query: CreatorQuery) -> CommandResult<Vec<AdminCreator>> {
    Ok(launcher.admin_creators(&query).await?)
}

#[tauri::command]
pub async fn admin_creator_add(launcher: State<'_, LauncherState>, creator: NewCreator) -> CommandResult<()> {
    Ok(launcher.admin_creator_add(&creator).await?)
}

#[tauri::command]
pub async fn admin_creator_update(launcher: State<'_, LauncherState>, uuid: String, patch: CreatorPatch) -> CommandResult<()> {
    Ok(launcher.admin_creator_update(&uuid, &patch).await?)
}

#[tauri::command]
pub async fn admin_creator_revoke(launcher: State<'_, LauncherState>, uuid: String, revoke: Revoke) -> CommandResult<()> {
    Ok(launcher.admin_creator_revoke(&uuid, &revoke).await?)
}

#[tauri::command]
pub async fn admin_creator_restore(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<()> {
    Ok(launcher.admin_creator_restore(&uuid).await?)
}

#[tauri::command]
pub async fn admin_creator_requests(launcher: State<'_, LauncherState>) -> CommandResult<Vec<AdminRequest>> {
    Ok(launcher.admin_creator_requests().await?)
}

#[tauri::command]
pub async fn admin_creator_answer(
    launcher: State<'_, LauncherState>,
    id: String,
    accept: bool,
    reason: Option<String>,
) -> CommandResult<()> {
    Ok(launcher.admin_creator_answer(&id, accept, reason.as_deref()).await?)
}
