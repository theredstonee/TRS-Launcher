//! Sozial: Chat, Bilder, Meldungen, Moderation (Admin) und Benachrichtigungen.
//! Alle Anfragen laufen im Kern; das Webview bekommt nur gesäuberte Daten,
//! nie den Token und nie Dateipfade. Bilder lädt es über `trschat:`.

use std::path::PathBuf;

use serde_json::Value;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_notification::NotificationExt;
use trs_core::trs_api::chat::{
    ChatConversation, ChatMessage, ChatReaction, ConversationPage, InviteStatus, MessagePage, OutgoingMessage, PageAt,
    UnreadSummary,
};
use trs_core::trs_api::live::LiveStatus;
use trs_core::trs_api::media::{self, LocalImage, StagedImages, UploadSource};
use trs_core::trs_api::moderation::{AuditQuery, MyModeration, MyReport, NewFilterWord, NewReport, ReportAction, ReportQuery};

use super::system::DropState;
use crate::LauncherState;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

// --- Echtzeit-Kanal ---------------------------------------------------------------------

#[tauri::command]
pub fn trs_live_status(launcher: State<'_, LauncherState>) -> LiveStatus {
    launcher.trs_live_status()
}

/// Sofort neu verbinden (z. B. „Erneut versuchen“ oder nach dem Standby).
#[tauri::command]
pub fn trs_live_reconnect(launcher: State<'_, LauncherState>) {
    launcher.trs_live_kick();
}

// --- Unterhaltungen ---------------------------------------------------------------------

#[tauri::command]
pub async fn chat_conversations(
    launcher: State<'_, LauncherState>,
    cursor: Option<String>,
    limit: Option<u32>,
) -> CommandResult<ConversationPage> {
    Ok(launcher.chat_conversations(cursor.as_deref(), limit).await?)
}

#[tauri::command]
pub async fn chat_conversation(launcher: State<'_, LauncherState>, id: String) -> CommandResult<ChatConversation> {
    Ok(launcher.chat_conversation(&id).await?)
}

#[tauri::command]
pub async fn chat_open_dm(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<ChatConversation> {
    Ok(launcher.chat_open_dm(&uuid).await?)
}

#[tauri::command]
pub async fn chat_unread(launcher: State<'_, LauncherState>) -> CommandResult<UnreadSummary> {
    Ok(launcher.chat_unread().await?)
}

#[tauri::command]
pub async fn chat_create_group(
    launcher: State<'_, LauncherState>,
    name: String,
    members: Vec<String>,
) -> CommandResult<ChatConversation> {
    Ok(launcher.chat_create_group(&name, &members).await?)
}

#[tauri::command]
pub async fn chat_rename_group(launcher: State<'_, LauncherState>, id: String, name: String) -> CommandResult<ChatConversation> {
    Ok(launcher.chat_rename_group(&id, &name).await?)
}

#[tauri::command]
pub async fn chat_add_members(
    launcher: State<'_, LauncherState>,
    id: String,
    members: Vec<String>,
) -> CommandResult<ChatConversation> {
    Ok(launcher.chat_add_members(&id, &members).await?)
}

#[tauri::command]
pub async fn chat_remove_member(launcher: State<'_, LauncherState>, id: String, uuid: String) -> CommandResult<()> {
    Ok(launcher.chat_remove_member(&id, &uuid).await?)
}

#[tauri::command]
pub async fn chat_leave_group(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.chat_leave_group(&id).await?)
}

#[tauri::command]
pub async fn chat_transfer_group(launcher: State<'_, LauncherState>, id: String, uuid: String) -> CommandResult<ChatConversation> {
    Ok(launcher.chat_transfer_group(&id, &uuid).await?)
}

#[tauri::command]
pub async fn chat_delete_group(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.chat_delete_group(&id).await?)
}

// --- Nachrichten ------------------------------------------------------------------------

/// Neueste Nachrichten; mit `before` ältere, mit `after` neuere (Nachholen).
#[tauri::command]
pub async fn chat_messages(
    launcher: State<'_, LauncherState>,
    id: String,
    before: Option<u64>,
    after: Option<u64>,
    limit: Option<u32>,
) -> CommandResult<MessagePage> {
    let at = match (before, after) {
        (Some(b), _) => PageAt::Before(b),
        (None, Some(a)) => PageAt::After(a),
        (None, None) => PageAt::Latest,
    };
    Ok(launcher.chat_messages(&id, at, limit).await?)
}

#[tauri::command]
pub async fn chat_send(launcher: State<'_, LauncherState>, id: String, message: OutgoingMessage) -> CommandResult<ChatMessage> {
    Ok(launcher.chat_send(&id, &message).await?)
}

#[tauri::command]
pub async fn chat_edit(launcher: State<'_, LauncherState>, message_id: String, text: String) -> CommandResult<ChatMessage> {
    Ok(launcher.chat_edit(&message_id, &text).await?)
}

#[tauri::command]
pub async fn chat_delete(launcher: State<'_, LauncherState>, message_id: String) -> CommandResult<ChatMessage> {
    Ok(launcher.chat_delete(&message_id).await?)
}

#[tauri::command]
pub async fn chat_react(
    launcher: State<'_, LauncherState>,
    message_id: String,
    emoji: String,
    on: bool,
) -> CommandResult<Vec<ChatReaction>> {
    Ok(launcher.chat_react(&message_id, &emoji, on).await?)
}

#[tauri::command]
pub async fn chat_read(launcher: State<'_, LauncherState>, id: String, seq: u64) -> CommandResult<ChatConversation> {
    Ok(launcher.chat_read(&id, seq).await?)
}

#[tauri::command]
pub async fn chat_mark_unread(launcher: State<'_, LauncherState>, id: String, seq: Option<u64>) -> CommandResult<ChatConversation> {
    Ok(launcher.chat_mark_unread(&id, seq).await?)
}

#[tauri::command]
pub async fn chat_mute(
    launcher: State<'_, LauncherState>,
    id: String,
    muted: bool,
    until: Option<String>,
) -> CommandResult<ChatConversation> {
    Ok(launcher.chat_mute(&id, muted, until.as_deref()).await?)
}

#[tauri::command]
pub async fn chat_typing(launcher: State<'_, LauncherState>, id: String, typing: bool) -> CommandResult<()> {
    Ok(launcher.chat_typing(&id, typing).await?)
}

#[tauri::command]
pub async fn chat_server_status(launcher: State<'_, LauncherState>, address: String) -> CommandResult<InviteStatus> {
    Ok(launcher.chat_server_status(&address).await?)
}

// --- Bilder -----------------------------------------------------------------------------

/// Dateidialog für Bilder (Mehrfachauswahl). Die Pfade bleiben im Kern.
/// `None` = abgebrochen.
#[tauri::command]
pub async fn chat_pick_images(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Option<StagedImages>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(DialogText::PickChatImages.text(lang))
            .add_filter(DialogText::Images.text(lang), &["png", "jpg", "jpeg", "webp"])
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
    Ok(Some(launcher.chat_stage_files(files).await?))
}

/// Ins Fenster gezogene Bilder übernehmen (Marke aus dem Event `file-drop`).
#[tauri::command]
pub async fn chat_stage_dropped(
    launcher: State<'_, LauncherState>,
    drops: State<'_, DropState>,
    token: u64,
) -> CommandResult<StagedImages> {
    let files = drops.take(token).ok_or_else(|| {
        trs_core::Error::validation(trs_core::msg!(
            "commands.dropExpired",
            "Die gezogenen Dateien sind nicht mehr verfügbar – bitte erneut ziehen."
        ))
    })?;
    Ok(launcher.chat_stage_files(files).await?)
}

/// Eingefügtes Bild (Zwischenablage) – Base64, der Kern prüft alles.
#[tauri::command]
pub async fn chat_stage_pasted(launcher: State<'_, LauncherState>, name: String, data: String) -> CommandResult<LocalImage> {
    let bytes = media::decode_pasted(&data)?;
    Ok(launcher.chat_stage_bytes(&name, bytes).await?)
}

#[tauri::command]
pub async fn chat_local_images(launcher: State<'_, LauncherState>) -> CommandResult<Vec<LocalImage>> {
    Ok(launcher.chat_local_images().await)
}

#[tauri::command]
pub async fn chat_forget_local(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    launcher.chat_forget_local(&id).await;
    Ok(())
}

#[tauri::command]
pub async fn chat_upload(
    launcher: State<'_, LauncherState>,
    source: UploadSource,
) -> CommandResult<trs_core::trs_api::chat::ChatAttachment> {
    Ok(launcher.chat_upload(&source).await?)
}

#[tauri::command]
pub async fn screenshot_favorites(launcher: State<'_, LauncherState>) -> CommandResult<Vec<String>> {
    Ok(launcher.screenshot_favorites().await)
}

#[tauri::command]
pub async fn set_screenshot_favorite(
    launcher: State<'_, LauncherState>,
    instance_id: String,
    file_name: String,
    favorite: bool,
) -> CommandResult<Vec<String>> {
    Ok(launcher.set_screenshot_favorite(&instance_id, &file_name, favorite).await?)
}

// --- Meldungen (Spieler) ----------------------------------------------------------------

#[tauri::command]
pub async fn chat_report(launcher: State<'_, LauncherState>, report: NewReport) -> CommandResult<MyReport> {
    Ok(launcher.chat_report(&report).await?)
}

#[tauri::command]
pub async fn chat_my_reports(launcher: State<'_, LauncherState>) -> CommandResult<Vec<MyReport>> {
    Ok(launcher.chat_my_reports().await?)
}

#[tauri::command]
pub async fn chat_my_moderation(launcher: State<'_, LauncherState>) -> CommandResult<MyModeration> {
    Ok(launcher.chat_my_moderation().await?)
}

// --- Moderation (Admins; der Server prüft die Rechte selbst) ------------------------------

#[tauri::command]
pub async fn admin_reports(launcher: State<'_, LauncherState>, query: ReportQuery) -> CommandResult<Value> {
    Ok(launcher.admin_reports(&query).await?)
}

#[tauri::command]
pub async fn admin_report(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Value> {
    Ok(launcher.admin_report(&id).await?)
}

#[tauri::command]
pub async fn admin_report_status(launcher: State<'_, LauncherState>, id: String, status: String) -> CommandResult<Value> {
    Ok(launcher.admin_report_status(&id, &status).await?)
}

#[tauri::command]
pub async fn admin_report_action(launcher: State<'_, LauncherState>, id: String, action: ReportAction) -> CommandResult<Value> {
    Ok(launcher.admin_report_action(&id, &action).await?)
}

#[tauri::command]
pub async fn admin_report_note(launcher: State<'_, LauncherState>, id: String, text: String) -> CommandResult<Value> {
    Ok(launcher.admin_report_note(&id, &text).await?)
}

#[tauri::command]
pub async fn admin_moderation_user(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<Value> {
    Ok(launcher.admin_moderation_user(&uuid).await?)
}

#[tauri::command]
pub async fn admin_mute(
    launcher: State<'_, LauncherState>,
    uuid: String,
    minutes: Option<u32>,
    reason: Option<String>,
) -> CommandResult<Value> {
    Ok(launcher.admin_mute(&uuid, minutes, reason.as_deref()).await?)
}

#[tauri::command]
pub async fn admin_unmute(launcher: State<'_, LauncherState>, uuid: String) -> CommandResult<Value> {
    Ok(launcher.admin_unmute(&uuid).await?)
}

#[tauri::command]
pub async fn admin_warn(launcher: State<'_, LauncherState>, uuid: String, reason: String) -> CommandResult<Value> {
    Ok(launcher.admin_warn(&uuid, &reason).await?)
}

#[tauri::command]
pub async fn admin_word_filter(launcher: State<'_, LauncherState>) -> CommandResult<Value> {
    Ok(launcher.admin_word_filter().await?)
}

#[tauri::command]
pub async fn admin_add_word(launcher: State<'_, LauncherState>, word: NewFilterWord) -> CommandResult<Value> {
    Ok(launcher.admin_add_word(&word).await?)
}

#[tauri::command]
pub async fn admin_delete_word(launcher: State<'_, LauncherState>, id: u64) -> CommandResult<()> {
    Ok(launcher.admin_delete_word(id).await?)
}

#[tauri::command]
pub async fn admin_audit(launcher: State<'_, LauncherState>, query: AuditQuery) -> CommandResult<Value> {
    Ok(launcher.admin_audit(&query).await?)
}

// --- Benachrichtigungen -----------------------------------------------------------------

/// Läuft eine Vollbild-Anwendung oder ist Windows auf „Nicht stören“?
#[tauri::command]
pub fn social_quiet_hours() -> bool {
    trs_core::platform::quiet_hours()
}

fn short(text: &str, max: usize) -> String {
    let clean: String = text.chars().filter(|c| !c.is_control() || *c == '\n').take(max).collect();
    clean.trim().to_owned()
}

/// Benachrichtigung des Betriebssystems (Fenster nicht im Vordergrund) und
/// Taskleisten-Hinweis. Texte werden gekürzt, Steuerzeichen fallen weg.
#[tauri::command]
pub fn social_notify_native(app: AppHandle, title: String, body: String) {
    let title = short(&title, 64);
    let body = short(&body, 180);
    if title.is_empty() {
        return;
    }
    if let Err(e) = app.notification().builder().title(title).body(body).show() {
        log::debug!("Benachrichtigung nicht gezeigt: {e}");
    }
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.request_user_attention(Some(tauri::UserAttentionType::Informational));
    }
}

/// Fenster nach vorn (Klick auf eine Benachrichtigung).
#[tauri::command]
pub fn social_focus_window(app: AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}
