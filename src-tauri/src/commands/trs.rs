//! TRS API: Umhänge, Freunde, Präsenz, Verwaltung. Alle Anfragen laufen im
//! Kern; das Webview bekommt nur Daten (Texturen als Data-URL), nie den Token.

use std::path::PathBuf;

use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::trs_api::cape_import::{self, CapeSource};
use trs_core::trs_api::sync::SyncStatus;
use trs_core::trs_api::types::{
    AdminCape, AdminStats, AdminUser, BlockedUser, CapeHolders, CapeItem, CapeOffers, CodeView, Friend,
    FriendRequestResult, FriendsView, Me, NewCodes, PlayerCape, RedeemResult, ReportReason, ReviewList,
    SettingsPatch, TrsStatus, UserRef,
};

use crate::LauncherState;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

#[tauri::command]
pub async fn trs_status(launcher: State<'_, LauncherState>) -> CommandResult<TrsStatus> {
    Ok(launcher.trs_status().await?)
}

/// Stand der TRS-Synchronisation (kleiner Hinweis auf der Skins-Seite).
#[tauri::command]
pub async fn trs_sync_status(launcher: State<'_, LauncherState>) -> CommandResult<SyncStatus> {
    Ok(launcher.trs_sync_status().await)
}

/// Einwilligung erteilen oder zurückziehen (dann keine Anfragen mehr).
#[tauri::command]
pub async fn trs_set_consent(launcher: State<'_, LauncherState>, accepted: bool) -> CommandResult<TrsStatus> {
    Ok(launcher.trs_set_consent(accepted).await?)
}

#[tauri::command]
pub async fn trs_me(launcher: State<'_, LauncherState>) -> CommandResult<Me> {
    Ok(launcher.trs_me().await?)
}

#[tauri::command]
pub async fn trs_update_me(launcher: State<'_, LauncherState>, patch: SettingsPatch) -> CommandResult<Me> {
    Ok(launcher.trs_update_me(patch).await?)
}

/// Löscht alle TRS-Daten des aktiven Accounts und schaltet die Dienste aus.
#[tauri::command]
pub async fn trs_delete_me(launcher: State<'_, LauncherState>) -> CommandResult<TrsStatus> {
    Ok(launcher.trs_delete_me().await?)
}

#[tauri::command]
pub async fn trs_capes(launcher: State<'_, LauncherState>) -> CommandResult<Vec<CapeItem>> {
    Ok(launcher.trs_capes().await?)
}

#[tauri::command]
pub async fn trs_set_cape(launcher: State<'_, LauncherState>, cape_id: Option<String>) -> CommandResult<Option<String>> {
    Ok(launcher.trs_set_cape(cape_id).await?)
}

/// Bilder für den Umhang-Dialog wählen (auch mehrere = Frames). Der Kern erkennt
/// das Format an den Magic Bytes, prüft die Maße vor dem Dekodieren und zerlegt
/// GIFs; das Webview bekommt nur Data-URLs. `None` = abgebrochen. Der Pfad
/// verlässt Rust nie.
#[tauri::command]
pub async fn trs_pick_cape_sources(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
) -> CommandResult<Option<Vec<CapeSource>>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::PickCape.text(lang))
            .add_filter(DialogText::Cape.text(lang), &["png", "jpg", "jpeg", "webp", "gif", "json"])
            .blocking_pick_files()
    })
    .await
    .ok()
    .flatten();
    let Some(picked) = picked else { return Ok(None) };
    let files: Vec<PathBuf> = picked.into_iter().filter_map(|p| p.into_path().ok()).collect();
    if files.is_empty() {
        return Ok(None);
    }
    Ok(Some(cape_import::read_sources(files).await?))
}

/// Lädt den fertigen Streifen aus dem Zuschneide-Dialog hoch (PNG als Base64,
/// `frames` Frames untereinander). Der Kern prüft alles noch einmal.
#[tauri::command]
pub async fn trs_upload_cape(
    launcher: State<'_, LauncherState>,
    png: String,
    frames: u32,
    frame_time_ms: Option<u32>,
    name: Option<String>,
) -> CommandResult<CapeItem> {
    let bytes = cape_import::decode_upload(&png)?;
    Ok(launcher.trs_upload_cape(bytes, frames, frame_time_ms, name.as_deref()).await?)
}

#[tauri::command]
pub async fn trs_delete_cape(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.trs_delete_cape(&id).await?)
}

#[tauri::command]
pub async fn trs_report_cape(
    launcher: State<'_, LauncherState>,
    id: String,
    reason: ReportReason,
    note: Option<String>,
) -> CommandResult<()> {
    Ok(launcher.trs_report_cape(&id, reason, note.as_deref()).await?)
}

#[tauri::command]
pub async fn trs_redeem(launcher: State<'_, LauncherState>, code: String) -> CommandResult<RedeemResult> {
    Ok(launcher.trs_redeem(&code).await?)
}

#[tauri::command]
pub async fn trs_player_capes(launcher: State<'_, LauncherState>, uuids: Vec<String>) -> CommandResult<Vec<PlayerCape>> {
    Ok(launcher.trs_player_capes(&uuids).await?)
}

#[tauri::command]
pub async fn trs_cape_offers(launcher: State<'_, LauncherState>) -> CommandResult<CapeOffers> {
    Ok(launcher.trs_cape_offers().await?)
}

#[tauri::command]
pub async fn trs_offer_cape(launcher: State<'_, LauncherState>, cape_id: String, friend: String) -> CommandResult<()> {
    Ok(launcher.trs_offer_cape(&cape_id, &friend).await?)
}

#[tauri::command]
pub async fn trs_accept_cape_offer(launcher: State<'_, LauncherState>, cape_id: String) -> CommandResult<()> {
    Ok(launcher.trs_accept_cape_offer(&cape_id).await?)
}

#[tauri::command]
pub async fn trs_decline_cape_offer(launcher: State<'_, LauncherState>, cape_id: String) -> CommandResult<()> {
    Ok(launcher.trs_decline_cape_offer(&cape_id).await?)
}

#[tauri::command]
pub async fn trs_cape_holders(launcher: State<'_, LauncherState>, cape_id: String) -> CommandResult<CapeHolders> {
    Ok(launcher.trs_cape_holders(&cape_id).await?)
}

#[tauri::command]
pub async fn trs_revoke_cape_share(
    launcher: State<'_, LauncherState>,
    cape_id: String,
    holder: String,
) -> CommandResult<()> {
    Ok(launcher.trs_revoke_cape_share(&cape_id, &holder).await?)
}

#[tauri::command]
pub async fn trs_friends(launcher: State<'_, LauncherState>) -> CommandResult<FriendsView> {
    Ok(launcher.trs_friends().await?)
}

#[tauri::command]
pub async fn trs_blocks(launcher: State<'_, LauncherState>) -> CommandResult<Vec<BlockedUser>> {
    Ok(launcher.trs_blocks().await?)
}

#[tauri::command]
pub async fn trs_friend_request(launcher: State<'_, LauncherState>, target: String) -> CommandResult<FriendRequestResult> {
    Ok(launcher.trs_friend_request(&target).await?)
}

#[tauri::command]
pub async fn trs_friend_accept(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<Friend> {
    Ok(launcher.trs_friend_accept(&uuid).await?)
}

#[tauri::command]
pub async fn trs_friend_decline(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<()> {
    Ok(launcher.trs_friend_decline(&uuid).await?)
}

#[tauri::command]
pub async fn trs_friend_cancel(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<()> {
    Ok(launcher.trs_friend_cancel(&uuid).await?)
}

#[tauri::command]
pub async fn trs_friend_remove(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<()> {
    Ok(launcher.trs_friend_remove(&uuid).await?)
}

#[tauri::command]
pub async fn trs_block(launcher: State<'_, LauncherState>, target: String) -> CommandResult<UserRef> {
    Ok(launcher.trs_block(&target).await?)
}

#[tauri::command]
pub async fn trs_unblock(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<()> {
    Ok(launcher.trs_unblock(&uuid).await?)
}

/// Anmeldung auf der Website bestätigen (Code von der Website). Der Kern
/// schickt ihn mit dem TRS-Token des aktiven Accounts – der Token bleibt dort.
#[tauri::command]
pub async fn trs_web_login_approve(launcher: State<'_, LauncherState>, code: String) -> CommandResult<()> {
    Ok(launcher.trs_web_login_approve(&code).await?)
}

// --- Verwaltung ------------------------------------------------------------------------

#[tauri::command]
pub async fn trs_admin_stats(launcher: State<'_, LauncherState>) -> CommandResult<AdminStats> {
    Ok(launcher.trs_admin_stats().await?)
}

#[tauri::command]
pub async fn trs_admin_capes(launcher: State<'_, LauncherState>, list: ReviewList) -> CommandResult<Vec<AdminCape>> {
    Ok(launcher.trs_admin_capes(list).await?)
}

#[tauri::command]
pub async fn trs_admin_approve(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.trs_admin_approve(&id).await?)
}

#[tauri::command]
pub async fn trs_admin_reject(launcher: State<'_, LauncherState>, id: String, reason: Option<String>) -> CommandResult<()> {
    Ok(launcher.trs_admin_reject(&id, reason.as_deref()).await?)
}

#[tauri::command]
pub async fn trs_admin_delete_cape(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.trs_admin_delete_cape(&id).await?)
}

#[tauri::command]
pub async fn trs_admin_codes(launcher: State<'_, LauncherState>) -> CommandResult<Vec<CodeView>> {
    Ok(launcher.trs_admin_codes().await?)
}

#[tauri::command]
pub async fn trs_admin_create_codes(launcher: State<'_, LauncherState>, request: NewCodes) -> CommandResult<Vec<CodeView>> {
    Ok(launcher.trs_admin_create_codes(request).await?)
}

#[tauri::command]
pub async fn trs_admin_revoke_code(launcher: State<'_, LauncherState>, id: u64) -> CommandResult<()> {
    Ok(launcher.trs_admin_revoke_code(id).await?)
}

#[tauri::command]
pub async fn trs_admin_user(launcher: State<'_, LauncherState>, query: String) -> CommandResult<AdminUser> {
    Ok(launcher.trs_admin_user(&query).await?)
}

/// Liefert `true`, wenn der Spieler den Umhang schon hatte.
#[tauri::command]
pub async fn trs_admin_grant(launcher: State<'_, LauncherState>, player: String, cape_id: String) -> CommandResult<bool> {
    Ok(launcher.trs_admin_grant(&player, &cape_id).await?)
}

#[tauri::command]
pub async fn trs_admin_revoke_grant(launcher: State<'_, LauncherState>, uuid: String, cape_id: String) -> CommandResult<()> {
    Ok(launcher.trs_admin_revoke_grant(&uuid, &cape_id).await?)
}

#[tauri::command]
pub async fn trs_admin_ban(
    launcher: State<'_, LauncherState>,
    player: String,
    reason: Option<String>,
) -> CommandResult<AdminUser> {
    Ok(launcher.trs_admin_ban(&player, reason.as_deref()).await?)
}

#[tauri::command]
pub async fn trs_admin_unban(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<()> {
    Ok(launcher.trs_admin_unban(&uuid).await?)
}
