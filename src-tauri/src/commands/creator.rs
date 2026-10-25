//! Creator-Programm (API §35): Codes einlösen, unterstützen, blockieren, das
//! Creator-Dashboard und die Team-Verwaltung. Prüfung der Eingaben im Kern;
//! Tokens bleiben dort, Bilder kommen als Data-URL.

use tauri::State;
use trs_core::trs_api::cape_import;
use trs_core::trs_api::creator::{
    AdminCreator, AdminRequest, CodePatch, CreatorCode, CreatorPatch, CreatorQuery, CreatorRedeem, CreatorRequest,
    CreatorSelf, ListedPacks, MyCreator, NewCreator, NewCreatorCode, NewRequest, PreviewBuild, QueueItem, Revoke,
};
use trs_core::trs_api::creator_pet::{CreatorPetState, NewPetCodes, NewPetRequest, PetCode, PetRequest};
use trs_core::trs_api::creator_stats::{CreatorStatsDetail, CreatorStatsList, CreatorStatsQuery};
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
pub async fn creator_request(
    launcher: State<'_, LauncherState>,
    request: NewRequest,
    as_creator: Option<String>,
) -> CommandResult<Vec<CreatorRequest>> {
    Ok(launcher.creator_request(&request, as_creator.as_deref()).await?)
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

// --- Haustier (eigenes Creator-Haustier, Codes mit Monatslimit, Antrag) ---------------------

#[tauri::command]
pub async fn creator_pet(launcher: State<'_, LauncherState>, as_creator: Option<String>) -> CommandResult<CreatorPetState> {
    Ok(launcher.creator_pet(as_creator.as_deref()).await?)
}

/// Gefolge für andere zeigen oder verstecken → neuer Stand des Reiters.
#[tauri::command]
pub async fn creator_set_pet_extras(launcher: State<'_, LauncherState>, show: bool, as_creator: Option<String>) -> CommandResult<CreatorPetState> {
    Ok(launcher.creator_set_pet_extras(show, as_creator.as_deref()).await?)
}

#[tauri::command]
pub async fn creator_pet_codes(launcher: State<'_, LauncherState>, as_creator: Option<String>) -> CommandResult<Vec<PetCode>> {
    Ok(launcher.creator_pet_codes(as_creator.as_deref()).await?)
}

/// Neue Codes – die Antwort enthält den Klartext, der danach nie wieder kommt.
#[tauri::command]
pub async fn creator_create_pet_codes(
    launcher: State<'_, LauncherState>,
    codes: NewPetCodes,
    as_creator: Option<String>,
) -> CommandResult<Vec<PetCode>> {
    Ok(launcher.creator_create_pet_codes(&codes, as_creator.as_deref()).await?)
}

#[tauri::command]
pub async fn creator_revoke_pet_code(launcher: State<'_, LauncherState>, id: String, as_creator: Option<String>) -> CommandResult<()> {
    Ok(launcher.creator_revoke_pet_code(&id, as_creator.as_deref()).await?)
}

/// Wunsch nach einem eigenen Haustier (Tierart, Beschreibung, Farben + optional PNG/JPG als Base64, der Kern prüft es).
#[tauri::command]
pub async fn creator_request_pet(
    launcher: State<'_, LauncherState>,
    request: NewPetRequest,
    as_creator: Option<String>,
) -> CommandResult<PetRequest> {
    Ok(launcher.creator_request_pet(&request, as_creator.as_deref()).await?)
}

/// Referenzbild an einen offenen Wunsch hängen bzw. ersetzen.
#[tauri::command]
pub async fn creator_pet_request_image(
    launcher: State<'_, LauncherState>,
    id: String,
    image: String,
    as_creator: Option<String>,
) -> CommandResult<PetRequest> {
    Ok(launcher.creator_pet_request_image(&id, &image, as_creator.as_deref()).await?)
}

#[tauri::command]
pub async fn creator_withdraw_pet_request(launcher: State<'_, LauncherState>, id: String, as_creator: Option<String>) -> CommandResult<()> {
    Ok(launcher.creator_withdraw_pet_request(&id, as_creator.as_deref()).await?)
}

// --- Helfer (§35.9) -------------------------------------------------------------------------

#[tauri::command]
pub async fn creator_invite_helper(launcher: State<'_, LauncherState>, uuid: String, rights: Vec<String>) -> CommandResult<()> {
    Ok(launcher.creator_invite_helper(&uuid, &rights).await?)
}

#[tauri::command]
pub async fn creator_update_helper(
    launcher: State<'_, LauncherState>,
    uuid: String,
    rights: Vec<String>,
    as_creator: Option<String>,
) -> CommandResult<()> {
    Ok(launcher.creator_update_helper(&uuid, &rights, as_creator.as_deref()).await?)
}

#[tauri::command]
pub async fn creator_remove_helper(launcher: State<'_, LauncherState>, uuid: String, as_creator: Option<String>) -> CommandResult<()> {
    Ok(launcher.creator_remove_helper(&uuid, as_creator.as_deref()).await?)
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

// --- Team: Statistik (§35.14, `creators.manage`) ---------------------------------------------

/// Rangliste aller Creator mit Fans, neuen Spielern, Haustier-Codes und aktiven Fans.
#[tauri::command]
pub async fn admin_creator_stats(launcher: State<'_, LauncherState>, query: CreatorStatsQuery) -> CommandResult<CreatorStatsList> {
    Ok(launcher.admin_creator_stats(&query).await?)
}

/// Kennzahlen und Tagesreihen eines Creators (Standard 90 Tage).
#[tauri::command]
pub async fn admin_creator_stats_detail(
    launcher: State<'_, LauncherState>,
    uuid: String,
    days: Option<u32>,
) -> CommandResult<CreatorStatsDetail> {
    Ok(launcher.admin_creator_stats_detail(&uuid, days).await?)
}
