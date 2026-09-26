//! Chat über die TRS API (Vertrag: `api/API.md` §18): Direktnachrichten und
//! Gruppen, Reaktionen, Lesestatus, Server-Einladungen und Bilder.
//!
//! Alles, was von der API kommt, wird hier noch einmal geprüft und gesäubert:
//! IDs müssen ihr Format haben, Texte verlieren Steuer- und Richtungszeichen,
//! Server-Adressen müssen das enge Format des Launchers erfüllen. Bild-Pfade
//! der API (brauchen den Token) gehen **nie** ans Webview – es bekommt nur
//! IDs und lädt die Bilder über das Protokoll `trschat:` aus dem Kern.

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::hosting::{ApiWorld, ChatWorld};
use super::types::{UserRef, clean_user};
use super::{Req, validate};
use crate::{Error, Launcher, Result};

/// Die feste Reaktionsliste der API (Reihenfolge = Anzeige im Auswahlfeld).
pub const REACTIONS: [&str; 10] =
    ["thumbs_up", "heart", "laugh", "wow", "sad", "angry", "party", "fire", "eyes", "check"];

/// Höchstlänge einer Nachricht (Unicode-Codepunkte, wie die API).
pub const MAX_TEXT: usize = 2000;
/// Höchstens so viele Bilder je Nachricht.
pub const MAX_ATTACHMENTS: usize = 10;
/// Gruppen: höchstens so viele Mitglieder (inklusive Besitzer).
pub const MAX_GROUP_MEMBERS: usize = 25;

// --- IDs ------------------------------------------------------------------------------

fn prefixed_hex(input: &str, prefix: char, len: usize) -> bool {
    input.len() == len + 1
        && input.starts_with(prefix)
        && input[1..].bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// `^c[0-9a-f]{20}$`
pub fn conversation_id(input: &str) -> bool {
    prefixed_hex(input, 'c', 20)
}

/// `^m[0-9a-f]{20}$`
pub fn message_id(input: &str) -> bool {
    prefixed_hex(input, 'm', 20)
}

/// `^a[0-9a-f]{24}$`
pub fn attachment_id(input: &str) -> bool {
    prefixed_hex(input, 'a', 24)
}

/// `^r[0-9a-f]{16}$`
pub fn report_id(input: &str) -> bool {
    prefixed_hex(input, 'r', 16)
}

/// Idempotenz-Schlüssel einer Nachricht: `^[A-Za-z0-9_-]{8,64}$`.
pub fn nonce(input: &str) -> bool {
    (8..=64).contains(&input.len()) && input.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

/// Seiten-Cursor der API: undurchsichtig, aber nur aus harmlosen Zeichen.
pub fn cursor(input: &str) -> bool {
    (1..=256).contains(&input.len())
        && input.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.' | b':' | b'='))
}

pub fn reaction(input: &str) -> bool {
    REACTIONS.contains(&input)
}

fn invalid(msg: crate::error::Msg) -> Error {
    Error::validation(msg)
}

pub(crate) fn conversation_arg(id: &str) -> Result<&str> {
    if conversation_id(id) {
        Ok(id)
    } else {
        Err(invalid(crate::msg!("chat.invalidConversation", "Ungültige Unterhaltung.")))
    }
}

pub(crate) fn message_arg(id: &str) -> Result<&str> {
    if message_id(id) { Ok(id) } else { Err(invalid(crate::msg!("chat.invalidMessage", "Ungültige Nachricht."))) }
}

pub(crate) fn attachment_arg(id: &str) -> Result<&str> {
    if attachment_id(id) { Ok(id) } else { Err(invalid(crate::msg!("chat.invalidAttachment", "Ungültiges Bild."))) }
}

fn uuid_arg(input: &str) -> Result<String> {
    validate::uuid(input).ok_or_else(|| invalid(crate::msg!("trsOps.invalidPlayerId", "Ungültige Spieler-ID.")))
}

// --- Texte ----------------------------------------------------------------------------

/// Zeichen, die im Chat nichts zu suchen haben: Steuerzeichen (außer `\n`),
/// Richtungs-Überschreibungen, unsichtbare Formatzeichen, private Zeichen.
/// Emoji-Verbinder (ZWJ/ZWNJ) und Variantenwähler bleiben.
fn chat_forbidden(c: char) -> bool {
    (c.is_control() && c != '\n')
        || matches!(
            c,
            '\u{00AD}'
                | '\u{061C}'
                | '\u{200B}'
                | '\u{200E}'
                | '\u{200F}'
                | '\u{202A}'..='\u{202E}'
                | '\u{2060}'..='\u{2064}'
                | '\u{2066}'..='\u{2069}'
                | '\u{FEFF}'
        )
        || ('\u{E000}'..='\u{F8FF}').contains(&c)
}

/// Nachrichtentext für die Anzeige: ohne verbotene Zeichen, `\r` weg, Tabs
/// werden Leerzeichen, höchstens eine Leerzeile am Stück, gekürzt auf `max`.
pub fn chat_text(input: &str, max: usize) -> String {
    let mut out = String::with_capacity(input.len().min(max.saturating_mul(4)));
    let mut count = 0;
    let mut breaks = 0;
    for c in input.chars() {
        let c = if c == '\t' { ' ' } else { c };
        if c == '\r' || chat_forbidden(c) {
            continue;
        }
        if c == '\n' {
            breaks += 1;
            if breaks > 2 {
                continue;
            }
        } else {
            breaks = 0;
        }
        if count >= max {
            break;
        }
        out.push(c);
        count += 1;
    }
    out.trim_end().to_owned()
}

/// Einzeiliger Text (Gruppennamen, Vorschauen): Zeilenumbrüche werden Leerzeichen.
pub fn one_line(input: &str, max: usize) -> String {
    let flat: String = input.chars().map(|c| if c == '\n' { ' ' } else { c }).collect();
    chat_text(&flat, max).trim().to_owned()
}

/// Ausgehender Nachrichtentext: gesäubert, höchstens [`MAX_TEXT`] Zeichen.
/// Leer ist erlaubt (Bild oder Einladung ohne Text).
pub fn outgoing_text(input: &str) -> Result<String> {
    let cleaned = chat_text(input.trim_start_matches(['\n', ' ']), usize::MAX);
    if cleaned.chars().count() > MAX_TEXT {
        return Err(invalid(crate::msg!(
            "chat.textTooLong",
            "Die Nachricht ist zu lang (höchstens {max} Zeichen).",
            max = MAX_TEXT
        )));
    }
    Ok(cleaned)
}

/// Gruppenname: 1–32 Zeichen, eine Zeile.
pub fn group_name(input: &str) -> Result<String> {
    let name = one_line(input, 64);
    if name.is_empty() || name.chars().count() > 32 {
        return Err(invalid(crate::msg!("chat.groupName", "Gruppenname: 1 bis 32 Zeichen.")));
    }
    Ok(name)
}

/// Einladungs-Beschriftung: höchstens 32 Zeichen, eine Zeile.
fn invite_label(input: &str) -> Option<String> {
    let label = one_line(input, 32);
    (!label.is_empty()).then_some(label)
}

fn time(value: Option<String>) -> Option<String> {
    value.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

fn clean_uuid_list(list: Vec<String>, max: usize) -> Vec<String> {
    let mut out: Vec<String> = list.iter().filter_map(|u| validate::uuid(u)).collect();
    out.dedup();
    out.truncate(max);
    out
}

// --- Ansichten ------------------------------------------------------------------------

/// Mitglied einer Unterhaltung.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatMember {
    pub uuid: String,
    pub name: String,
    #[serde(default)]
    pub role: String,
    #[serde(default)]
    pub joined_at: Option<String>,
}

/// Lesestand eines anderen Mitglieds (nur bei gegenseitig geteilten Lesebestätigungen).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatReadMark {
    pub uuid: String,
    pub seq: u64,
    #[serde(default)]
    pub at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatInvite {
    pub address: String,
    #[serde(default)]
    pub name: Option<String>,
}

impl ChatInvite {
    fn cleaned(self) -> Option<Self> {
        let address = self.address.trim().to_ascii_lowercase();
        crate::servers::parse_address(&address).ok()?;
        Some(Self { address, name: self.name.as_deref().and_then(invite_label) })
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiThumb {
    #[serde(default)]
    width: u32,
    #[serde(default)]
    height: u32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiAttachment {
    id: String,
    #[serde(default)]
    mime: String,
    #[serde(default)]
    width: u32,
    #[serde(default)]
    height: u32,
    #[serde(default)]
    bytes: u64,
    #[serde(default)]
    thumb: Option<ApiThumb>,
}

/// Bild einer Nachricht – ohne Pfad (der bräuchte den Token). Das Webview
/// lädt es über `trschat://localhost/a/<id>` bzw. `/t/<id>` (Vorschau).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatAttachment {
    pub id: String,
    pub mime: String,
    pub width: u32,
    pub height: u32,
    pub bytes: u64,
    pub thumb_width: u32,
    pub thumb_height: u32,
}

impl ApiAttachment {
    pub(crate) fn cleaned(self) -> Option<ChatAttachment> {
        if !attachment_id(&self.id) {
            return None;
        }
        let mime = match self.mime.as_str() {
            "image/jpeg" | "image/png" | "image/webp" => self.mime,
            _ => "image/jpeg".into(),
        };
        let side = |v: u32| v.min(16_384);
        let thumb = self.thumb.unwrap_or(ApiThumb { width: 0, height: 0 });
        Some(ChatAttachment {
            id: self.id,
            mime,
            width: side(self.width),
            height: side(self.height),
            bytes: self.bytes,
            thumb_width: side(thumb.width),
            thumb_height: side(thumb.height),
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatReply {
    pub id: String,
    #[serde(default)]
    pub seq: u64,
    #[serde(default)]
    pub sender: Option<UserRef>,
    #[serde(default)]
    pub preview: Option<String>,
    #[serde(default)]
    pub attachments: u32,
    #[serde(default)]
    pub invite: bool,
    /// Antwort auf eine Weltkarte (§21.8).
    #[serde(default)]
    pub world: bool,
    #[serde(default)]
    pub deleted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatSystem {
    pub event: String,
    #[serde(default)]
    pub actor: Option<UserRef>,
    #[serde(default)]
    pub target: Option<UserRef>,
    #[serde(default)]
    pub name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatReaction {
    pub emoji: String,
    #[serde(default)]
    pub count: u32,
    #[serde(default)]
    pub users: Vec<String>,
}

pub(crate) fn clean_reactions(list: Vec<ChatReaction>) -> Vec<ChatReaction> {
    let mut out: Vec<ChatReaction> = Vec::new();
    for r in list {
        if !reaction(&r.emoji) || out.iter().any(|o| o.emoji == r.emoji) {
            continue;
        }
        let users = clean_uuid_list(r.users, 100);
        let count = r.count.max(users.len() as u32).min(10_000);
        if count > 0 {
            out.push(ChatReaction { emoji: r.emoji, count, users });
        }
    }
    out
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiMessage {
    id: String,
    #[serde(default)]
    conversation_id: String,
    #[serde(default)]
    seq: u64,
    #[serde(default)]
    kind: String,
    #[serde(default)]
    sender: Option<UserRef>,
    #[serde(default)]
    text: Option<String>,
    #[serde(default)]
    invite: Option<ChatInvite>,
    #[serde(default)]
    world: Option<ApiWorld>,
    #[serde(default)]
    attachments: Vec<ApiAttachment>,
    #[serde(default)]
    reply_to: Option<ChatReply>,
    #[serde(default)]
    system: Option<ChatSystem>,
    #[serde(default)]
    reactions: Vec<ChatReaction>,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    edited_at: Option<String>,
    #[serde(default)]
    deleted: bool,
    #[serde(default)]
    deleted_by: Option<String>,
    #[serde(default)]
    hidden: bool,
    #[serde(default)]
    nonce: Option<String>,
}

/// Nachricht fürs Webview (Vertrag §18.1 `MessageView`, gesäubert, ohne Bildpfade).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatMessage {
    pub id: String,
    pub conversation_id: String,
    pub seq: u64,
    /// `text` oder `system`.
    pub kind: String,
    pub sender: Option<UserRef>,
    pub text: Option<String>,
    pub invite: Option<ChatInvite>,
    /// Weltkarte einer gehosteten Welt (§21.8), sonst `null`.
    pub world: Option<ChatWorld>,
    pub attachments: Vec<ChatAttachment>,
    pub reply_to: Option<ChatReply>,
    pub system: Option<ChatSystem>,
    pub reactions: Vec<ChatReaction>,
    pub created_at: Option<String>,
    pub edited_at: Option<String>,
    pub deleted: bool,
    /// `sender`, `owner` oder `admin`.
    pub deleted_by: Option<String>,
    pub hidden: bool,
    pub nonce: Option<String>,
}

const SYSTEM_EVENTS: [&str; 6] = ["group_created", "member_added", "member_removed", "member_left", "renamed", "owner_changed"];

impl ApiMessage {
    pub(crate) fn cleaned(self) -> Option<ChatMessage> {
        if !message_id(&self.id) || !conversation_id(&self.conversation_id) {
            return None;
        }
        let kind = if self.kind == "system" { "system" } else { "text" };
        let sender = self.sender.and_then(clean_user);
        // Ausgeblendete (blockierte) und gelöschte Nachrichten tragen keinen Inhalt – auch
        // wenn eine fehlerhafte Antwort doch etwas mitschickt.
        let empty = self.deleted || self.hidden;
        let text = if empty || kind == "system" {
            None
        } else {
            self.text.map(|t| chat_text(&t, MAX_TEXT)).filter(|t| !t.is_empty())
        };
        let system = if kind == "system" {
            self.system.and_then(|s| {
                SYSTEM_EVENTS.contains(&s.event.as_str()).then(|| ChatSystem {
                    event: s.event,
                    actor: s.actor.and_then(clean_user),
                    target: s.target.and_then(clean_user),
                    name: s.name.map(|n| one_line(&n, 32)).filter(|n| !n.is_empty()),
                })
            })
        } else {
            None
        };
        let reply_to = if empty {
            None
        } else {
            self.reply_to.and_then(|r| {
                message_id(&r.id).then(|| {
                    let preview = if r.deleted { None } else { r.preview.map(|p| one_line(&p, 120)).filter(|p| !p.is_empty()) };
                    ChatReply {
                        id: r.id,
                        seq: r.seq,
                        sender: r.sender.and_then(clean_user),
                        preview,
                        attachments: r.attachments.min(MAX_ATTACHMENTS as u32),
                        invite: r.invite && !r.deleted,
                        world: r.world && !r.deleted,
                        deleted: r.deleted,
                    }
                })
            })
        };
        let deleted_by = self.deleted_by.filter(|b| matches!(b.as_str(), "sender" | "owner" | "admin"));
        Some(ChatMessage {
            id: self.id,
            conversation_id: self.conversation_id,
            seq: self.seq,
            kind: kind.into(),
            sender,
            text,
            invite: if empty { None } else { self.invite.and_then(ChatInvite::cleaned) },
            world: if empty { None } else { self.world.and_then(ApiWorld::cleaned) },
            attachments: if empty {
                Vec::new()
            } else {
                self.attachments.into_iter().filter_map(ApiAttachment::cleaned).take(MAX_ATTACHMENTS).collect()
            },
            reply_to,
            system,
            reactions: if self.deleted { Vec::new() } else { clean_reactions(self.reactions) },
            created_at: time(self.created_at),
            edited_at: time(self.edited_at),
            deleted: self.deleted,
            deleted_by: if self.deleted { deleted_by } else { None },
            hidden: self.hidden,
            nonce: self.nonce.filter(|n| nonce(n)),
        })
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiConversation {
    id: String,
    #[serde(default)]
    kind: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    owner: Option<String>,
    #[serde(default)]
    members: Vec<ChatMember>,
    #[serde(default)]
    peer: Option<UserRef>,
    #[serde(default)]
    can_write: bool,
    #[serde(default)]
    read_only_reason: Option<String>,
    #[serde(default)]
    last_message: Option<ApiMessage>,
    #[serde(default)]
    last_seq: u64,
    #[serde(default)]
    unread: u32,
    #[serde(default)]
    marked_unread: bool,
    #[serde(default)]
    read_seq: u64,
    #[serde(default)]
    muted: bool,
    #[serde(default)]
    muted_until: Option<String>,
    #[serde(default)]
    reads: Vec<ChatReadMark>,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    updated_at: Option<String>,
}

/// Unterhaltung fürs Webview (Vertrag §18.1 `ConversationView`, gesäubert).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatConversation {
    pub id: String,
    /// `dm` oder `group`.
    pub kind: String,
    pub name: Option<String>,
    pub owner: Option<String>,
    pub members: Vec<ChatMember>,
    pub peer: Option<UserRef>,
    pub can_write: bool,
    /// `not_friends`, `chat_muted` oder `null`.
    pub read_only_reason: Option<String>,
    pub last_message: Option<ChatMessage>,
    pub last_seq: u64,
    pub unread: u32,
    pub marked_unread: bool,
    pub read_seq: u64,
    pub muted: bool,
    pub muted_until: Option<String>,
    pub reads: Vec<ChatReadMark>,
    pub created_at: Option<String>,
    pub updated_at: Option<String>,
}

impl ApiConversation {
    pub(crate) fn cleaned(self) -> Option<ChatConversation> {
        if !conversation_id(&self.id) {
            return None;
        }
        let kind = match self.kind.as_str() {
            "dm" | "group" => self.kind,
            _ => return None,
        };
        let members: Vec<ChatMember> = self
            .members
            .into_iter()
            .filter_map(|m| {
                Some(ChatMember {
                    uuid: validate::uuid(&m.uuid)?,
                    name: validate::display_name(&m.name),
                    role: if m.role == "owner" { "owner".into() } else { "member".into() },
                    joined_at: time(m.joined_at),
                })
            })
            .take(MAX_GROUP_MEMBERS * 2)
            .collect();
        let peer = self.peer.and_then(clean_user);
        if kind == "dm" && peer.is_none() {
            return None;
        }
        let read_only_reason =
            self.read_only_reason.filter(|r| matches!(r.as_str(), "not_friends" | "chat_muted" | "blocked"));
        let last_message = self.last_message.and_then(ApiMessage::cleaned).filter(|m| m.conversation_id == self.id);
        Some(ChatConversation {
            name: if kind == "group" { self.name.map(|n| one_line(&n, 32)).filter(|n| !n.is_empty()) } else { None },
            owner: if kind == "group" { self.owner.and_then(|o| validate::uuid(&o)) } else { None },
            kind,
            members,
            peer,
            can_write: self.can_write,
            read_only_reason,
            last_message,
            last_seq: self.last_seq,
            unread: self.unread.min(100_000),
            marked_unread: self.marked_unread,
            read_seq: self.read_seq,
            muted: self.muted,
            muted_until: time(self.muted_until),
            reads: self
                .reads
                .into_iter()
                .filter_map(|r| Some(ChatReadMark { uuid: validate::uuid(&r.uuid)?, seq: r.seq, at: time(r.at) }))
                .take(MAX_GROUP_MEMBERS * 2)
                .collect(),
            created_at: time(self.created_at),
            updated_at: time(self.updated_at),
            id: self.id,
        })
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiConversationPage {
    #[serde(default)]
    conversations: Vec<ApiConversation>,
    #[serde(default)]
    next_cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConversationPage {
    pub conversations: Vec<ChatConversation>,
    pub next_cursor: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ApiConversationEnvelope {
    conversation: ApiConversation,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiMessagePage {
    #[serde(default)]
    messages: Vec<ApiMessage>,
    #[serde(default)]
    has_more: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessagePage {
    pub messages: Vec<ChatMessage>,
    pub has_more: bool,
}

#[derive(Debug, Deserialize)]
struct ApiMessageEnvelope {
    message: ApiMessage,
}

#[derive(Debug, Deserialize)]
struct ApiReactions {
    #[serde(default)]
    reactions: Vec<ChatReaction>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UnreadEntry {
    pub id: String,
    #[serde(default)]
    pub unread: u32,
    #[serde(default)]
    pub marked_unread: bool,
    #[serde(default)]
    pub muted: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct UnreadSummary {
    #[serde(default)]
    pub total: u32,
    #[serde(default)]
    pub conversations: Vec<UnreadEntry>,
}

impl UnreadSummary {
    fn cleaned(self) -> Self {
        Self {
            total: self.total.min(100_000),
            conversations: self
                .conversations
                .into_iter()
                .filter(|c| conversation_id(&c.id))
                .map(|c| UnreadEntry { unread: c.unread.min(100_000), ..c })
                .take(1000)
                .collect(),
        }
    }
}

/// Was beim Senden mitgeht (vom Webview; Unbekanntes wird abgelehnt).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OutgoingMessage {
    #[serde(default)]
    pub text: Option<String>,
    #[serde(default)]
    pub reply_to: Option<String>,
    #[serde(default)]
    pub attachments: Vec<String>,
    #[serde(default)]
    pub invite: Option<ChatInvite>,
    #[serde(default)]
    pub nonce: Option<String>,
}

impl OutgoingMessage {
    /// Prüft alles vor dem Senden und baut den Body für die API.
    pub(crate) fn body(&self) -> Result<serde_json::Value> {
        let mut body = serde_json::Map::new();
        let text = match self.text.as_deref() {
            Some(t) => outgoing_text(t)?,
            None => String::new(),
        };
        if !text.is_empty() {
            body.insert("text".into(), json!(text));
        }
        if let Some(reply) = &self.reply_to {
            body.insert("replyTo".into(), json!(message_arg(reply)?));
        }
        if self.attachments.len() > MAX_ATTACHMENTS {
            return Err(invalid(crate::msg!(
                "chat.tooManyImages",
                "Höchstens {max} Bilder je Nachricht.",
                max = MAX_ATTACHMENTS
            )));
        }
        let mut attachments = Vec::with_capacity(self.attachments.len());
        for id in &self.attachments {
            let id = attachment_arg(id)?;
            if !attachments.contains(&id) {
                attachments.push(id);
            }
        }
        if !attachments.is_empty() {
            body.insert("attachments".into(), json!(attachments));
        }
        if let Some(invite) = &self.invite {
            let cleaned = invite.clone().cleaned().ok_or_else(|| {
                invalid(crate::msg!("servers.invalidAddress", "Ungültige Server-Adresse"))
            })?;
            let mut value = json!({ "address": cleaned.address });
            if let Some(name) = cleaned.name {
                value["name"] = json!(name);
            }
            body.insert("invite".into(), value);
        }
        if body.is_empty() || (text.is_empty() && attachments.is_empty() && self.invite.is_none()) {
            return Err(invalid(crate::msg!("chat.emptyMessage", "Die Nachricht ist leer.")));
        }
        if let Some(n) = &self.nonce {
            if !nonce(n) {
                return Err(invalid(crate::msg!("chat.invalidMessage", "Ungültige Nachricht.")));
            }
            body.insert("nonce".into(), json!(n));
        }
        Ok(serde_json::Value::Object(body))
    }
}

/// Status eines eingeladenen Servers (der TRS-Server pingt ihn, §18.6).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InviteStatus {
    pub address: String,
    pub online: bool,
    pub reason: Option<String>,
    pub version: Option<String>,
    pub players_online: Option<u32>,
    pub players_max: Option<u32>,
    pub motd: Option<String>,
    pub icon: Option<String>,
    pub latency_ms: Option<u32>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiServerVersion {
    #[serde(default)]
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiServerPlayers {
    #[serde(default)]
    online: Option<u32>,
    #[serde(default)]
    max: Option<u32>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiServerStatus {
    #[serde(default)]
    address: String,
    #[serde(default)]
    online: bool,
    #[serde(default)]
    reason: Option<String>,
    #[serde(default)]
    version: Option<ApiServerVersion>,
    #[serde(default)]
    players: Option<ApiServerPlayers>,
    #[serde(default)]
    motd: Option<String>,
    #[serde(default)]
    icon: Option<String>,
    #[serde(default)]
    latency_ms: Option<u32>,
}

#[derive(Debug, Deserialize)]
struct ApiServerStatusEnvelope {
    status: ApiServerStatus,
}

impl ApiServerStatus {
    fn cleaned(self, asked: &str) -> InviteStatus {
        const REASONS: [&str; 7] =
            ["private_address", "unresolvable", "timeout", "refused", "invalid_response", "busy", "disabled"];
        let address = if self.address.is_empty() { asked.to_owned() } else { validate::text(&self.address, 261) };
        InviteStatus {
            address,
            online: self.online,
            reason: self.reason.filter(|r| REASONS.contains(&r.as_str())),
            version: self.version.and_then(|v| v.name).map(|n| one_line(&n, 64)).filter(|n| !n.is_empty()),
            players_online: self.players.as_ref().and_then(|p| p.online).map(|n| n.min(10_000_000)),
            players_max: self.players.as_ref().and_then(|p| p.max).map(|n| n.min(10_000_000)),
            motd: self.motd.map(|m| chat_text(&m, 256)).filter(|m| !m.is_empty()),
            icon: self.icon.filter(|i| crate::servers::is_safe_favicon(i)),
            latency_ms: self.latency_ms.map(|l| l.min(60_000)),
        }
    }
}

// --- Operationen ----------------------------------------------------------------------

fn bad_response() -> Error {
    super::bad_response()
}

fn conversation(value: ApiConversationEnvelope) -> Result<ChatConversation> {
    value.conversation.cleaned().ok_or_else(bad_response)
}

fn message(value: ApiMessageEnvelope) -> Result<ChatMessage> {
    value.message.cleaned().ok_or_else(bad_response)
}

/// Richtung beim Nachladen von Nachrichten.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PageAt {
    /// Die neuesten.
    Latest,
    /// Ältere als `seq`.
    Before(u64),
    /// Neuere als `seq` (Nachholen).
    After(u64),
}

impl Launcher {
    pub async fn chat_conversations(&self, next: Option<&str>, limit: Option<u32>) -> Result<ConversationPage> {
        let limit = limit.unwrap_or(50).clamp(1, 100);
        let mut path = format!("/v1/chat/conversations?limit={limit}");
        if let Some(c) = next {
            if !cursor(c) {
                return Err(invalid(crate::msg!("chat.invalidCursor", "Ungültige Seite.")));
            }
            path.push_str(&format!("&cursor={}", super::encode_query(c)));
        }
        let page: ApiConversationPage = self.trs_get(Req::get(path)).await?;
        Ok(ConversationPage {
            conversations: page.conversations.into_iter().filter_map(ApiConversation::cleaned).collect(),
            next_cursor: page.next_cursor.filter(|c| cursor(c)),
        })
    }

    pub async fn chat_conversation(&self, id: &str) -> Result<ChatConversation> {
        let id = conversation_arg(id)?;
        conversation(self.trs_get(Req::get(format!("/v1/chat/conversations/{id}"))).await?)
    }

    /// DM mit einem Freund öffnen (oder die bestehende holen).
    pub async fn chat_open_dm(&self, uuid: &str) -> Result<ChatConversation> {
        let uuid = uuid_arg(uuid)?;
        conversation(self.trs_get(Req::post("/v1/chat/dms", json!({ "uuid": uuid }))).await?)
    }

    pub async fn chat_unread(&self) -> Result<UnreadSummary> {
        Ok(self.trs_get::<UnreadSummary>(Req::get("/v1/chat/unread")).await?.cleaned())
    }

    pub async fn chat_create_group(&self, name: &str, members: &[String]) -> Result<ChatConversation> {
        let name = group_name(name)?;
        let mut list = Vec::new();
        for m in members {
            let uuid = uuid_arg(m)?;
            if !list.contains(&uuid) {
                list.push(uuid);
            }
        }
        if list.len() > MAX_GROUP_MEMBERS - 1 {
            return Err(invalid(crate::msg!(
                "chat.groupFull",
                "Eine Gruppe hat höchstens {max} Mitglieder.",
                max = MAX_GROUP_MEMBERS
            )));
        }
        conversation(self.trs_get(Req::post("/v1/chat/groups", json!({ "name": name, "members": list }))).await?)
    }

    pub async fn chat_rename_group(&self, id: &str, name: &str) -> Result<ChatConversation> {
        let id = conversation_arg(id)?;
        let name = group_name(name)?;
        conversation(self.trs_get(Req::patch(format!("/v1/chat/groups/{id}"), json!({ "name": name }))).await?)
    }

    pub async fn chat_add_members(&self, id: &str, members: &[String]) -> Result<ChatConversation> {
        let id = conversation_arg(id)?;
        let mut list = Vec::new();
        for m in members {
            let uuid = uuid_arg(m)?;
            if !list.contains(&uuid) {
                list.push(uuid);
            }
        }
        if list.is_empty() || list.len() > MAX_GROUP_MEMBERS - 1 {
            return Err(invalid(crate::msg!(
                "chat.groupFull",
                "Eine Gruppe hat höchstens {max} Mitglieder.",
                max = MAX_GROUP_MEMBERS
            )));
        }
        conversation(self.trs_get(Req::post(format!("/v1/chat/groups/{id}/members"), json!({ "members": list }))).await?)
    }

    pub async fn chat_remove_member(&self, id: &str, uuid: &str) -> Result<()> {
        let id = conversation_arg(id)?;
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::delete(format!("/v1/chat/groups/{id}/members/{uuid}"))).await
    }

    pub async fn chat_leave_group(&self, id: &str) -> Result<()> {
        let id = conversation_arg(id)?;
        self.trs_do(Req::post_empty(format!("/v1/chat/groups/{id}/leave"))).await
    }

    pub async fn chat_transfer_group(&self, id: &str, uuid: &str) -> Result<ChatConversation> {
        let id = conversation_arg(id)?;
        let uuid = uuid_arg(uuid)?;
        conversation(self.trs_get(Req::post(format!("/v1/chat/groups/{id}/owner"), json!({ "uuid": uuid }))).await?)
    }

    pub async fn chat_delete_group(&self, id: &str) -> Result<()> {
        let id = conversation_arg(id)?;
        self.trs_do(Req::delete(format!("/v1/chat/groups/{id}"))).await
    }

    pub async fn chat_messages(&self, id: &str, at: PageAt, limit: Option<u32>) -> Result<MessagePage> {
        let id = conversation_arg(id)?;
        let limit = limit.unwrap_or(50).clamp(1, 100);
        let mut path = format!("/v1/chat/conversations/{id}/messages?limit={limit}");
        match at {
            PageAt::Latest => {}
            PageAt::Before(seq) => path.push_str(&format!("&before={seq}")),
            PageAt::After(seq) => path.push_str(&format!("&after={seq}")),
        }
        let page: ApiMessagePage = self.trs_get(Req::get(path)).await?;
        let mut messages: Vec<ChatMessage> =
            page.messages.into_iter().filter_map(ApiMessage::cleaned).filter(|m| m.conversation_id == id).collect();
        messages.sort_by_key(|m| m.seq);
        messages.dedup_by_key(|m| m.seq);
        Ok(MessagePage { messages, has_more: page.has_more })
    }

    pub async fn chat_send(&self, id: &str, outgoing: &OutgoingMessage) -> Result<ChatMessage> {
        let id = conversation_arg(id)?;
        let body = outgoing.body()?;
        message(self.trs_get(Req::post(format!("/v1/chat/conversations/{id}/messages"), body)).await?)
    }

    pub async fn chat_edit(&self, message_id: &str, text: &str) -> Result<ChatMessage> {
        let message_id = message_arg(message_id)?;
        let text = outgoing_text(text)?;
        message(self.trs_get(Req::patch(format!("/v1/chat/messages/{message_id}"), json!({ "text": text }))).await?)
    }

    pub async fn chat_delete(&self, message_id: &str) -> Result<ChatMessage> {
        let message_id = message_arg(message_id)?;
        message(self.trs_get(Req::delete(format!("/v1/chat/messages/{message_id}"))).await?)
    }

    pub async fn chat_react(&self, message_id: &str, emoji: &str, on: bool) -> Result<Vec<ChatReaction>> {
        let message_id = message_arg(message_id)?;
        if !reaction(emoji) {
            return Err(invalid(crate::msg!("chat.invalidReaction", "Unbekannte Reaktion.")));
        }
        let path = format!("/v1/chat/messages/{message_id}/reactions/{emoji}");
        let req = if on { Req::put(path, json!({})) } else { Req::delete(path) };
        let result: ApiReactions = self.trs_get(req).await?;
        Ok(clean_reactions(result.reactions))
    }

    pub async fn chat_read(&self, id: &str, seq: u64) -> Result<ChatConversation> {
        let id = conversation_arg(id)?;
        conversation(self.trs_get(Req::post(format!("/v1/chat/conversations/{id}/read"), json!({ "seq": seq }))).await?)
    }

    /// Als ungelesen markieren; mit `seq` ab dieser Nachricht.
    pub async fn chat_mark_unread(&self, id: &str, seq: Option<u64>) -> Result<ChatConversation> {
        let id = conversation_arg(id)?;
        let path = format!("/v1/chat/conversations/{id}/unread");
        let req = match seq {
            Some(seq) => Req::post(path, json!({ "seq": seq.max(1) })),
            None => Req::post_empty(path),
        };
        conversation(self.trs_get(req).await?)
    }

    /// Benachrichtigungen einer Unterhaltung stummschalten (`until` = ISO-Zeitpunkt, `None` = bis zum Aufheben).
    pub async fn chat_mute(&self, id: &str, muted: bool, until: Option<&str>) -> Result<ChatConversation> {
        let id = conversation_arg(id)?;
        let mut body = json!({ "muted": muted });
        if muted && let Some(until) = until.map(str::trim).filter(|u| !u.is_empty()) {
            body["until"] = json!(validate::iso_datetime(until)?);
        }
        conversation(self.trs_get(Req::put(format!("/v1/chat/conversations/{id}/mute"), body)).await?)
    }

    /// „Schreibt …“ – Fehler sind egal (die Anzeige verschwindet von selbst).
    pub async fn chat_typing(&self, id: &str, typing: bool) -> Result<()> {
        let id = conversation_arg(id)?;
        self.trs_do(Req::post(format!("/v1/chat/conversations/{id}/typing"), json!({ "typing": typing }))).await
    }

    /// Status eines Servers für eine Einladungskarte (der TRS-Server pingt ihn).
    pub async fn chat_server_status(&self, address: &str) -> Result<InviteStatus> {
        let address = address.trim().to_ascii_lowercase();
        crate::servers::parse_address(&address)?;
        let path = format!("/v1/servers/status?address={}", super::encode_query(&address));
        let result: ApiServerStatusEnvelope = self.trs_get(Req::get(path)).await?;
        Ok(result.status.cleaned(&address))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg_json() -> serde_json::Value {
        json!({
            "id": "m0a1b2c3d4e5f60718293", "conversationId": "c1f0e2d3c4b5a6978899a", "seq": 42, "kind": "text",
            "sender": { "uuid": "B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0", "name": "Bob" },
            "text": "Hallo\u{202E}!\r\n\n\n\nZweite\tZeile\u{0007}",
            "invite": { "address": "Play.Example.net:25566", "name": "Survival\nWelt" },
            "attachments": [
                { "id": "a0123456789abcdef01234567", "mime": "image/jpeg", "width": 1920, "height": 1080, "bytes": 312345,
                  "path": "/v1/chat/attachments/a0123456789abcdef01234567",
                  "thumb": { "mime": "image/jpeg", "width": 400, "height": 225, "bytes": 21034, "path": "/x?thumb=1" } },
                { "id": "../../etc", "mime": "image/png" }
            ],
            "replyTo": { "id": "m0a1b2c3d4e5f60718290", "seq": 40, "sender": { "uuid": "75c1a6f3112240abbdb57b9d21c64232", "name": "Theredstonee" },
                         "preview": "erste\nZeile", "attachments": 0, "invite": false, "deleted": false },
            "system": null,
            "reactions": [ { "emoji": "fire", "count": 2, "users": ["b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "zz"] }, { "emoji": "poop", "count": 1, "users": [] } ],
            "createdAt": "2026-09-26T10:00:00.000Z", "editedAt": null, "deleted": false, "deletedBy": null, "hidden": false, "nonce": "abcdefgh12"
        })
    }

    #[test]
    fn messages_are_cleaned_and_lose_their_paths() {
        let api: ApiMessage = serde_json::from_value(msg_json()).unwrap();
        let m = api.cleaned().unwrap();
        assert_eq!(m.text.as_deref(), Some("Hallo!\n\nZweite Zeile"));
        assert_eq!(m.sender.as_ref().unwrap().uuid, "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0");
        assert_eq!(m.invite, Some(ChatInvite { address: "play.example.net:25566".into(), name: Some("Survival Welt".into()) }));
        assert_eq!(m.attachments.len(), 1, "kaputte ID fällt weg");
        assert_eq!(m.attachments[0].thumb_width, 400);
        let json = serde_json::to_string(&m).unwrap();
        assert!(!json.contains("/v1/chat/attachments"), "keine API-Pfade ans Webview");
        assert_eq!(m.reply_to.as_ref().unwrap().preview.as_deref(), Some("erste Zeile"));
        assert_eq!(m.reactions.len(), 1);
        assert_eq!(m.reactions[0].users.len(), 1);
        assert_eq!(m.nonce.as_deref(), Some("abcdefgh12"));
    }

    #[test]
    fn deleted_and_hidden_messages_carry_no_content() {
        let mut value = msg_json();
        value["deleted"] = json!(true);
        value["deletedBy"] = json!("admin");
        let m: ChatMessage = serde_json::from_value::<ApiMessage>(value).unwrap().cleaned().unwrap();
        assert!(m.text.is_none() && m.invite.is_none() && m.attachments.is_empty() && m.reactions.is_empty() && m.reply_to.is_none());
        assert!(m.world.is_none());
        assert_eq!(m.deleted_by.as_deref(), Some("admin"));

        let mut value = msg_json();
        value["hidden"] = json!(true);
        let m: ChatMessage = serde_json::from_value::<ApiMessage>(value).unwrap().cleaned().unwrap();
        assert!(m.hidden && m.text.is_none() && m.attachments.is_empty());
    }

    #[test]
    fn world_cards_are_cleaned() {
        let mut value = msg_json();
        value["invite"] = json!(null);
        value["world"] = json!({ "roomId": "h0123456789abcdef0123", "code": "k7qm2x", "name": "Insel\u{202E}",
            "mcVersion": "1.21.11", "loader": "fabric", "host": { "uuid": "75c1a6f3112240abbdb57b9d21c64232", "name": "Theredstonee" } });
        value["replyTo"]["world"] = json!(true);
        let m: ChatMessage = serde_json::from_value::<ApiMessage>(value.clone()).unwrap().cleaned().unwrap();
        let w = m.world.unwrap();
        assert_eq!((w.code.as_str(), w.name.as_str(), w.loader.as_str()), ("K7QM2X", "Insel", "fabric"));
        assert!(m.reply_to.unwrap().world);

        value["world"]["roomId"] = json!("h../../x");
        let m: ChatMessage = serde_json::from_value::<ApiMessage>(value).unwrap().cleaned().unwrap();
        assert!(m.world.is_none(), "kaputte Karte fällt weg");
    }

    #[test]
    fn system_messages_and_bad_ids() {
        let mut value = msg_json();
        value["kind"] = json!("system");
        value["system"] = json!({ "event": "renamed", "actor": { "uuid": "75c1a6f3112240abbdb57b9d21c64232", "name": "T" }, "name": "Bau\u{202E}-Crew" });
        let m: ChatMessage = serde_json::from_value::<ApiMessage>(value).unwrap().cleaned().unwrap();
        assert!(m.text.is_none());
        assert_eq!(m.system.unwrap().name.as_deref(), Some("Bau-Crew"));

        let mut value = msg_json();
        value["id"] = json!("m1");
        assert!(serde_json::from_value::<ApiMessage>(value).unwrap().cleaned().is_none());
    }

    #[test]
    fn conversations_need_a_peer_or_a_group() {
        let dm = json!({ "id": "c1f0e2d3c4b5a6978899a", "kind": "dm", "name": "x", "owner": "y",
            "members": [{ "uuid": "75c1a6f3112240abbdb57b9d21c64232", "name": "T", "role": "member", "joinedAt": "…" }],
            "peer": { "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Bob" }, "canWrite": false, "readOnlyReason": "not_friends",
            "lastMessage": msg_json(), "lastSeq": 42, "unread": 3, "markedUnread": false, "readSeq": 39, "muted": false, "mutedUntil": null,
            "reads": [{ "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "seq": 41, "at": "…" }], "createdAt": "…", "updatedAt": "…" });
        let c = serde_json::from_value::<ApiConversation>(dm.clone()).unwrap().cleaned().unwrap();
        assert!(c.name.is_none() && c.owner.is_none(), "DMs haben keinen Namen/Besitzer");
        assert_eq!(c.read_only_reason.as_deref(), Some("not_friends"));
        assert_eq!(c.last_message.as_ref().unwrap().seq, 42);

        let mut no_peer = dm.clone();
        no_peer["peer"] = json!(null);
        assert!(serde_json::from_value::<ApiConversation>(no_peer).unwrap().cleaned().is_none());

        let mut odd = dm;
        odd["kind"] = json!("channel");
        assert!(serde_json::from_value::<ApiConversation>(odd).unwrap().cleaned().is_none());
    }

    #[test]
    fn outgoing_messages_are_checked() {
        let ok = OutgoingMessage { text: Some("  Hallo\u{200B} du ".into()), nonce: Some("n0nce-123".into()), ..Default::default() };
        assert_eq!(ok.body().unwrap(), json!({ "text": "Hallo du", "nonce": "n0nce-123" }));
        assert!(OutgoingMessage::default().body().is_err(), "leer");
        assert!(OutgoingMessage { text: Some(" \n ".into()), ..Default::default() }.body().is_err());
        let long = OutgoingMessage { text: Some("x".repeat(2001)), ..Default::default() };
        assert_eq!(long.body().unwrap_err().message_code(), "chat.textTooLong");
        let images = OutgoingMessage { attachments: vec!["a0123456789abcdef01234567".into(); 11], ..Default::default() };
        assert_eq!(images.body().unwrap_err().message_code(), "chat.tooManyImages");
        let bad_image = OutgoingMessage { attachments: vec!["a/../x".into()], ..Default::default() };
        assert!(bad_image.body().is_err());
        let invite = OutgoingMessage {
            invite: Some(ChatInvite { address: "Play.Example.net".into(), name: Some("  ".into()) }),
            ..Default::default()
        };
        assert_eq!(invite.body().unwrap(), json!({ "invite": { "address": "play.example.net" } }));
        let bad_invite = OutgoingMessage {
            invite: Some(ChatInvite { address: "evil host; rm".into(), name: None }),
            ..Default::default()
        };
        assert!(bad_invite.body().is_err());
        let reply = OutgoingMessage { text: Some("a".into()), reply_to: Some("nope".into()), ..Default::default() };
        assert!(reply.body().is_err());
    }

    #[test]
    fn ids_and_text_rules() {
        assert!(conversation_id("c1f0e2d3c4b5a6978899a") && !conversation_id("c1F0e2d3c4b5a6978899a"));
        assert!(attachment_id("a0123456789abcdef01234567") && !attachment_id("a0123"));
        assert!(report_id("r0123456789abcdef") && !report_id("r0123456789abcdeg"));
        assert!(nonce("abc-DEF_12") && !nonce("short") && !nonce("with space1"));
        assert!(cursor("eyJ0IjoxfQ==") && !cursor("a&b") && !cursor(""));
        assert_eq!(chat_text("a\u{200D}b", 10), "a\u{200D}b", "Emoji-Verbinder bleiben");
        assert_eq!(chat_text("abc", 2), "ab");
        assert_eq!(group_name(" Bau\nCrew ").unwrap(), "Bau Crew");
        assert!(group_name("").is_err() && group_name(&"x".repeat(33)).is_err());
    }

    #[test]
    fn server_status_is_cleaned() {
        let api: ApiServerStatusEnvelope = serde_json::from_value(json!({ "status": {
            "address": "play.example.net:25566", "online": true, "reason": "weird",
            "version": { "name": "Paper\n1.21.4", "protocol": 769 }, "players": { "online": 12, "max": 100 },
            "motd": "Welcome\u{202E}", "icon": "javascript:alert(1)", "latencyMs": 38, "checkedAt": "…" } }))
        .unwrap();
        let s = api.status.cleaned("play.example.net:25566");
        assert_eq!(s.version.as_deref(), Some("Paper 1.21.4"));
        assert!(s.reason.is_none() && s.icon.is_none());
        assert_eq!(s.motd.as_deref(), Some("Welcome"));
        assert_eq!((s.players_online, s.players_max), (Some(12), Some(100)));
    }
}
