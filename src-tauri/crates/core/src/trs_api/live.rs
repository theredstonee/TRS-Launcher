//! Echtzeit-Kanal `GET /v1/events/me` (Vertrag: `api/API.md` §19).
//!
//! Der Kern hält **eine** Verbindung für den aktiven Account offen und gibt
//! jedes Ereignis gesäubert an die Oberfläche weiter (Tauri: `trs-live`,
//! Zustand der Verbindung: `trs-live-status`). So aktualisieren sich Chat,
//! Freunde, Präsenz, Umhang-Angebote, Meldungen und Einstellungen sofort –
//! ohne Neuladen und ohne Polling. Polling bleibt nur als Rückfall in der
//! Oberfläche, solange der Kanal nicht steht.
//!
//! - **Wiederaufnahme:** Die letzte Ereignis-ID je Account geht beim
//!   Neuverbinden als `Last-Event-ID` mit; der Server spielt Verpasstes nach
//!   oder schickt `resync` (dann lädt die Oberfläche per REST neu).
//! - **Backoff:** 1 s, 2 s, 5 s, 10 s, 20 s, 30 s; nach einem `hello` wieder von vorn.
//! - **Totmann:** Kommt 60 s gar nichts (auch kein `ping`), wird neu verbunden.
//! - **Account-Wechsel/Einwilligung:** `kick()` weckt die Schleife; sie prüft
//!   außerdem regelmäßig selbst und verbindet sich mit dem neuen Account.
//! - Der Token bleibt im Kern; `401` → genau einmal neu anmelden.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;

use futures::StreamExt;
use futures::future::BoxFuture;
use serde::{Deserialize, Serialize};
use tokio::sync::Notify;

use super::chat::{ApiConversation, ApiMessage, ChatConversation, ChatMessage, ChatReaction, clean_reactions, conversation_id, message_id};
use super::hosting::{self, ApiRoom, HostingRoom as Room};
use super::moderation::MyReport;
use super::types::{PresenceView, PrivacySettings, UserRef, clean_user};
use super::{SessionSource, TrsApi, validate};
use crate::Error;

/// Größte Zeile/größtes Ereignis im Strom.
const MAX_EVENT_BYTES: usize = 1024 * 1024;

/// Zeiten der Schleife (in Tests kürzer).
#[derive(Debug, Clone)]
pub struct LiveConfig {
    /// Ohne jedes Byte so lange → neu verbinden (Server pingt alle 20 s).
    pub ping_timeout: Duration,
    /// Wartezeiten nach Fehlern, der letzte Wert gilt danach dauerhaft.
    pub backoff: Vec<Duration>,
    /// So oft prüft eine offene Verbindung, ob Account/Einwilligung noch passen.
    pub recheck: Duration,
    /// Pause bei gesperrtem Konto.
    pub banned_wait: Duration,
}

impl Default for LiveConfig {
    fn default() -> Self {
        Self {
            ping_timeout: Duration::from_secs(60),
            backoff: [1, 2, 5, 10, 20, 30].into_iter().map(Duration::from_secs).collect(),
            recheck: Duration::from_secs(5),
            banned_wait: Duration::from_secs(10 * 60),
        }
    }
}

impl LiveConfig {
    /// Wartezeit nach `failures` Fehlschlägen in Folge (1 = erster).
    pub fn backoff(&self, failures: u32) -> Duration {
        let i = (failures.max(1) as usize - 1).min(self.backoff.len().saturating_sub(1));
        self.backoff.get(i).copied().unwrap_or(Duration::from_secs(30))
    }
}

// --- SSE-Parser -----------------------------------------------------------------------

/// Ein Ereignis aus dem Strom.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SseFrame {
    pub event: String,
    pub data: String,
}

/// Zu große Zeile (Schutz vor endlosem Speicherverbrauch).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SseOverflow;

/// Inkrementeller Parser für `text/event-stream` (Zeilenenden `\n`, `\r\n`, `\r`).
#[derive(Debug, Default)]
pub struct SseParser {
    line: Vec<u8>,
    event: Option<String>,
    data: Option<String>,
    skip_lf: bool,
    /// Letzte gesehene `id:` (wie `lastEventId` im Browser).
    pub last_id: Option<String>,
}

impl SseParser {
    pub fn feed(&mut self, chunk: &[u8]) -> std::result::Result<Vec<SseFrame>, SseOverflow> {
        let mut out = Vec::new();
        for &b in chunk {
            if std::mem::take(&mut self.skip_lf) && b == b'\n' {
                continue;
            }
            match b {
                b'\r' => {
                    self.skip_lf = true;
                    self.end_line(&mut out)?;
                }
                b'\n' => self.end_line(&mut out)?,
                _ => {
                    if self.line.len() >= MAX_EVENT_BYTES {
                        return Err(SseOverflow);
                    }
                    self.line.push(b);
                }
            }
        }
        Ok(out)
    }

    fn end_line(&mut self, out: &mut Vec<SseFrame>) -> std::result::Result<(), SseOverflow> {
        let line = std::mem::take(&mut self.line);
        if line.is_empty() {
            let event = self.event.take();
            if let Some(data) = self.data.take() {
                out.push(SseFrame { event: event.unwrap_or_else(|| "message".into()), data });
            }
            return Ok(());
        }
        if line[0] == b':' {
            return Ok(());
        }
        let line = String::from_utf8_lossy(&line);
        let (field, value) = match line.split_once(':') {
            Some((f, v)) => (f, v.strip_prefix(' ').unwrap_or(v)),
            None => (line.as_ref(), ""),
        };
        match field {
            "event" => self.event = Some(value.to_owned()),
            "data" => match &mut self.data {
                Some(data) => {
                    if data.len() + value.len() + 1 > MAX_EVENT_BYTES {
                        return Err(SseOverflow);
                    }
                    data.push('\n');
                    data.push_str(value);
                }
                None => self.data = Some(value.to_owned()),
            },
            "id" if !value.contains('\0') => self.last_id = Some(value.to_owned()),
            _ => {}
        }
        Ok(())
    }
}

/// Ereignis-IDs der API: `<epoch>.<n>` – nur harmlose Zeichen gehen als Header zurück.
pub fn valid_event_id(id: &str) -> bool {
    (3..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
}

// --- Ereignisse -----------------------------------------------------------------------

/// Gesäubertes Ereignis für die Oberfläche (`trs-live`).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum LiveEvent {
    /// Verbindung steht. `first` = erste Verbindung dieses Accounts seit dem Start.
    Hello { resumed: bool, first: bool },
    /// Lücke nicht füllbar: alles per REST neu laden.
    Resync { reason: String },
    ChatMessage { conversation_id: String, message: Box<ChatMessage> },
    ChatMessageEdited { conversation_id: String, message: Box<ChatMessage> },
    ChatMessageDeleted { conversation_id: String, message: Box<ChatMessage> },
    ChatReactions { conversation_id: String, message_id: String, reactions: Vec<ChatReaction> },
    ChatTyping { conversation_id: String, uuid: String, typing: bool, expires_in_ms: u32 },
    ChatRead { conversation_id: String, uuid: String, seq: u64, at: Option<String> },
    ChatState {
        conversation_id: String,
        unread: u32,
        marked_unread: bool,
        read_seq: u64,
        muted: bool,
        muted_until: Option<String>,
    },
    ChatConversation { conversation: Box<ChatConversation> },
    ChatConversationRemoved { conversation_id: String, reason: String },
    ChatReload { conversation_id: String },
    FriendRequest { from: UserRef },
    FriendRequestCancelled { uuid: String },
    FriendAdded { friend: UserRef },
    FriendRemoved { uuid: String },
    FriendsChanged,
    Presence { uuid: String, presence: Option<PresenceView> },
    FriendOnline { friend: UserRef, presence: Option<PresenceView> },
    CapeOffer { from: Option<UserRef>, cape: Option<String> },
    CapeOfferAccepted { cape_id: String, by: Option<UserRef> },
    CapeShareRemoved { cape_id: String },
    ReportUpdate { report: Box<MyReport> },
    Moderation { action: String, reason: Option<String>, until: Option<String>, auto: Option<String> },
    Settings { settings: PrivacySettings },
    /// Einladung in eine gehostete Welt (§21.5). `from` = Host.
    HostingInvite { room: Box<Room>, from: Option<UserRef> },
    HostingInviteRevoked { room_id: String },
    /// Nur an den Host: jemand möchte in die eigene Welt.
    HostingJoinRequest { room_id: String, from: UserRef },
    /// Der Host hat die eigene Anfrage angenommen – jetzt kann das Spiel verbinden.
    HostingJoinAccepted { room: Box<Room> },
    HostingJoinDeclined { room_id: String },
    HostingKicked { room_id: String, banned: bool },
    /// Nur an den Host (alle Geräte): voller Stand der eigenen Welt.
    HostingRoom { room: Box<Room> },
    /// Einstellungen/Spielerzahl einer sichtbaren Welt, oder eine neue Welt eines Freundes.
    HostingRoomUpdated { room: Box<Room> },
    HostingRoomClosed { room_id: String, reason: String },
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct D {
    #[serde(default)]
    conversation_id: Option<String>,
    #[serde(default)]
    message: Option<ApiMessage>,
    #[serde(default)]
    message_id: Option<String>,
    #[serde(default)]
    reactions: Option<Vec<ChatReaction>>,
    #[serde(default)]
    uuid: Option<String>,
    #[serde(default)]
    typing: Option<bool>,
    #[serde(default)]
    expires_in_ms: Option<u32>,
    #[serde(default)]
    seq: Option<u64>,
    #[serde(default)]
    at: Option<String>,
    #[serde(default)]
    unread: Option<u32>,
    #[serde(default)]
    marked_unread: Option<bool>,
    #[serde(default)]
    read_seq: Option<u64>,
    #[serde(default)]
    muted: Option<bool>,
    #[serde(default)]
    muted_until: Option<String>,
    #[serde(default)]
    conversation: Option<ApiConversation>,
    #[serde(default)]
    reason: Option<String>,
    #[serde(default)]
    from: Option<UserRef>,
    #[serde(default)]
    friend: Option<UserRef>,
    #[serde(default)]
    presence: Option<PresenceView>,
    #[serde(default)]
    offer: Option<ApiOffer>,
    #[serde(default)]
    cape_id: Option<String>,
    #[serde(default)]
    by: Option<UserRef>,
    #[serde(default)]
    report: Option<LiveReport>,
    #[serde(default)]
    action: Option<String>,
    #[serde(default)]
    until: Option<String>,
    #[serde(default)]
    auto: Option<String>,
    #[serde(default)]
    settings: Option<PrivacySettings>,
    #[serde(default)]
    resumed: Option<bool>,
    #[serde(default)]
    room: Option<ApiRoom>,
    #[serde(default)]
    room_id: Option<String>,
    #[serde(default)]
    banned: Option<bool>,
}

#[derive(Deserialize)]
struct ApiOfferCape {
    #[serde(default)]
    name: String,
    #[serde(default)]
    id: String,
}

#[derive(Deserialize)]
struct ApiOffer {
    #[serde(default)]
    from: Option<UserRef>,
    #[serde(default)]
    cape: Option<ApiOfferCape>,
}

/// `report_update` trägt nur einen Teil von `MyReportView`.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LiveReport {
    id: String,
    #[serde(default)]
    kind: String,
    #[serde(default)]
    reason: Option<String>,
    #[serde(default)]
    status: String,
    #[serde(default)]
    outcome: Option<String>,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    updated_at: Option<String>,
}

fn conv(id: Option<String>) -> Option<String> {
    id.filter(|c| conversation_id(c))
}

fn room(id: Option<String>) -> Option<String> {
    id.filter(|r| hosting::room_id(r))
}

fn time(t: Option<String>) -> Option<String> {
    t.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

/// Wandelt ein Ereignis der API in ein gesäubertes [`LiveEvent`]. Unbekannte
/// oder kaputte Ereignisse → `None` (neue Typen kommen dazu, §19).
pub fn decode(event: &str, data: &str) -> Option<LiveEvent> {
    let d: D = serde_json::from_str(data).ok()?;
    let message = |d: D| -> Option<(String, Box<ChatMessage>)> {
        let c = conv(d.conversation_id)?;
        let m = d.message?.cleaned()?;
        (m.conversation_id == c).then(|| (c, Box::new(m)))
    };
    Some(match event {
        "resync" => LiveEvent::Resync {
            reason: d.reason.filter(|r| matches!(r.as_str(), "restart" | "gap" | "invalid")).unwrap_or_else(|| "gap".into()),
        },
        "chat_message" => {
            let (conversation_id, message) = message(d)?;
            LiveEvent::ChatMessage { conversation_id, message }
        }
        "chat_message_edited" => {
            let (conversation_id, message) = message(d)?;
            LiveEvent::ChatMessageEdited { conversation_id, message }
        }
        "chat_message_deleted" => {
            let (conversation_id, message) = message(d)?;
            LiveEvent::ChatMessageDeleted { conversation_id, message }
        }
        "chat_reactions" => LiveEvent::ChatReactions {
            conversation_id: conv(d.conversation_id)?,
            message_id: d.message_id.filter(|m| message_id(m))?,
            reactions: clean_reactions(d.reactions.unwrap_or_default()),
        },
        "chat_typing" => LiveEvent::ChatTyping {
            conversation_id: conv(d.conversation_id)?,
            uuid: validate::uuid(&d.uuid?)?,
            typing: d.typing.unwrap_or(false),
            expires_in_ms: d.expires_in_ms.unwrap_or(8000).clamp(1000, 30_000),
        },
        "chat_read" => LiveEvent::ChatRead {
            conversation_id: conv(d.conversation_id)?,
            uuid: validate::uuid(&d.uuid?)?,
            seq: d.seq?,
            at: time(d.at),
        },
        "chat_state" => LiveEvent::ChatState {
            conversation_id: conv(d.conversation_id)?,
            unread: d.unread.unwrap_or(0).min(100_000),
            marked_unread: d.marked_unread.unwrap_or(false),
            read_seq: d.read_seq.unwrap_or(0),
            muted: d.muted.unwrap_or(false),
            muted_until: time(d.muted_until),
        },
        "chat_conversation" => LiveEvent::ChatConversation { conversation: Box::new(d.conversation?.cleaned()?) },
        "chat_conversation_removed" => LiveEvent::ChatConversationRemoved {
            conversation_id: conv(d.conversation_id)?,
            reason: d.reason.filter(|r| matches!(r.as_str(), "left" | "removed" | "deleted")).unwrap_or_else(|| "removed".into()),
        },
        "chat_reload" => LiveEvent::ChatReload { conversation_id: conv(d.conversation_id)? },
        "friend_request" => LiveEvent::FriendRequest { from: clean_user(d.from?)? },
        "friend_request_cancelled" => LiveEvent::FriendRequestCancelled { uuid: validate::uuid(&d.uuid?)? },
        "friend_added" => LiveEvent::FriendAdded { friend: clean_user(d.friend?)? },
        "friend_removed" => LiveEvent::FriendRemoved { uuid: validate::uuid(&d.uuid?)? },
        "friends_changed" => LiveEvent::FriendsChanged,
        "presence" => LiveEvent::Presence { uuid: validate::uuid(&d.uuid?)?, presence: d.presence.and_then(PresenceView::cleaned) },
        "friend_online" => LiveEvent::FriendOnline {
            friend: clean_user(d.friend?)?,
            presence: d.presence.and_then(PresenceView::cleaned),
        },
        "cape_offer" => {
            let offer = d.offer?;
            LiveEvent::CapeOffer {
                from: offer.from.and_then(clean_user),
                cape: offer.cape.map(|c| validate::cape_name(&c.name, &c.id)).filter(|n| !n.is_empty()),
            }
        }
        "cape_offer_accepted" => LiveEvent::CapeOfferAccepted {
            cape_id: d.cape_id.filter(|c| validate::cape_id(c))?,
            by: d.by.and_then(clean_user),
        },
        "cape_share_removed" => LiveEvent::CapeShareRemoved { cape_id: d.cape_id.filter(|c| validate::cape_id(c))? },
        "report_update" => {
            let r = d.report?;
            let report = MyReport {
                id: r.id,
                kind: r.kind,
                reason: r.reason.unwrap_or_else(|| "other".into()),
                status: r.status,
                outcome: r.outcome,
                created_at: r.created_at,
                updated_at: r.updated_at,
            }
            .cleaned()?;
            LiveEvent::ReportUpdate { report: Box::new(report) }
        }
        "moderation" => LiveEvent::Moderation {
            action: d.action.filter(|a| matches!(a.as_str(), "warn" | "mute" | "unmute"))?,
            reason: d.reason.map(|r| super::chat::chat_text(&r, 200)).filter(|r| !r.is_empty()),
            until: time(d.until),
            auto: d.auto.filter(|a| matches!(a.as_str(), "reports" | "spam")),
        },
        "settings" => LiveEvent::Settings { settings: d.settings? },
        // --- Welt-Hosting (§21.5). `hosting_signal` bleibt bewusst draußen: Verbindungs-
        // kandidaten (IP-Adressen) sind Sache des Spiels, nicht der Oberfläche.
        "hosting_invite" => LiveEvent::HostingInvite { room: Box::new(d.room?.cleaned()?), from: d.from.and_then(clean_user) },
        "hosting_invite_revoked" => LiveEvent::HostingInviteRevoked { room_id: room(d.room_id)? },
        "hosting_join_request" => {
            LiveEvent::HostingJoinRequest { room_id: room(d.room_id)?, from: clean_user(d.from?)? }
        }
        "hosting_join_accepted" => LiveEvent::HostingJoinAccepted { room: Box::new(d.room?.cleaned()?) },
        "hosting_join_declined" => LiveEvent::HostingJoinDeclined { room_id: room(d.room_id)? },
        "hosting_kicked" => LiveEvent::HostingKicked { room_id: room(d.room_id)?, banned: d.banned.unwrap_or(false) },
        "hosting_room" => LiveEvent::HostingRoom { room: Box::new(d.room?.cleaned()?) },
        "hosting_room_updated" => LiveEvent::HostingRoomUpdated { room: Box::new(d.room?.cleaned()?) },
        "hosting_room_closed" => LiveEvent::HostingRoomClosed {
            room_id: room(d.room_id)?,
            reason: d.reason.filter(|r| hosting::CLOSE_REASONS.contains(&r.as_str())).unwrap_or_else(|| "closed".into()),
        },
        _ => return None,
    })
}

// --- Zustand & Schleife ---------------------------------------------------------------

/// Zustand der Verbindung (`trs-live-status`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LiveStatus {
    /// `off` (keine Einwilligung/kein Account), `connecting`, `live` oder `down`.
    pub state: &'static str,
    pub account: Option<String>,
    /// Nächster Versuch in … ms (nur bei `down`).
    pub retry_in_ms: Option<u64>,
}

/// Was an die Oberfläche geht.
#[derive(Debug, Clone, PartialEq)]
pub enum LiveOut {
    Status(LiveStatus),
    Event(LiveEvent),
}

pub type LiveSink = Arc<dyn Fn(LiveOut) + Send + Sync>;

#[derive(Default)]
struct Inner {
    status: LiveStatus,
    last_ids: HashMap<String, String>,
    connected: HashSet<String>,
    sink: Option<LiveSink>,
}

#[derive(Default)]
pub(crate) struct LiveState {
    notify: Notify,
    inner: StdMutex<Inner>,
}

impl LiveState {
    fn inner(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Schleife wecken (Account-Wechsel, Einwilligung, „jetzt neu verbinden“).
    pub fn kick(&self) {
        self.notify.notify_one();
    }

    pub fn set_sink(&self, sink: LiveSink) {
        self.inner().sink = Some(sink);
    }

    pub fn status(&self) -> LiveStatus {
        let s = self.inner().status.clone();
        if s.state.is_empty() { LiveStatus { state: "off", ..s } } else { s }
    }

    fn emit(&self, out: LiveOut) {
        let sink = self.inner().sink.clone();
        if let Some(sink) = sink {
            sink(out);
        }
    }

    fn set_status(&self, state: &'static str, account: Option<&str>, retry: Option<Duration>) {
        let next = LiveStatus {
            state,
            account: account.map(str::to_owned),
            retry_in_ms: retry.map(|d| d.as_millis().min(u128::from(u64::MAX)) as u64),
        };
        {
            let mut inner = self.inner();
            if inner.status == next {
                return;
            }
            inner.status = next.clone();
        }
        self.emit(LiveOut::Status(next));
    }

    fn last_id(&self, account: &str) -> Option<String> {
        self.inner().last_ids.get(account).cloned()
    }

    fn remember_id(&self, account: &str, id: &str) {
        if valid_event_id(id) {
            self.inner().last_ids.insert(account.to_owned(), id.to_owned());
        }
    }

    /// `true` beim ersten `hello` dieses Accounts seit dem Start.
    fn first_hello(&self, account: &str) -> bool {
        self.inner().connected.insert(account.to_owned())
    }
}

/// Liefert den aktiven Account (im Launcher: `AccountStore`).
pub trait ActiveAccount: Send + Sync {
    fn active(&self) -> BoxFuture<'_, Option<String>>;
}

/// Wie eine Verbindung endete.
enum Ended {
    /// Account/Einwilligung geändert → sofort neu.
    Switch,
    /// Fehler/Abbruch → nach Backoff (mindestens so lange).
    Retry { at_least: Duration, was_live: bool },
}

impl TrsApi {
    /// Zustand des Echtzeit-Kanals.
    pub fn live_status(&self) -> LiveStatus {
        self.live.status()
    }

    pub fn set_live_sink(&self, sink: LiveSink) {
        self.live.set_sink(sink);
    }

    /// Account, für den gerade verbunden sein sollte (`None` = aus).
    async fn live_target(&self, active: &dyn ActiveAccount) -> Option<String> {
        if !self.enabled().await {
            return None;
        }
        active.active().await
    }

    /// Hintergrund-Schleife – läuft, solange der Launcher läuft.
    pub(crate) async fn run_live(&self, sessions: &dyn SessionSource, active: &dyn ActiveAccount, cfg: &LiveConfig) {
        let mut failures = 0u32;
        loop {
            let Some(account) = self.live_target(active).await else {
                failures = 0;
                self.live.set_status("off", None, None);
                tokio::select! {
                    () = self.live.notify.notified() => {}
                    () = tokio::time::sleep(cfg.recheck) => {}
                }
                continue;
            };
            self.live.set_status("connecting", Some(&account), None);
            match self.live_session(sessions, active, &account, cfg).await {
                Ended::Switch => failures = 0,
                Ended::Retry { at_least, was_live } => {
                    failures = if was_live { 1 } else { failures.saturating_add(1) };
                    let wait = cfg.backoff(failures).max(at_least);
                    self.live.set_status("down", Some(&account), Some(wait));
                    tokio::select! {
                        () = self.live.notify.notified() => {}
                        () = tokio::time::sleep(wait) => {}
                    }
                }
            }
        }
    }

    /// Fehler beim Anmelden/Verbinden → wie lange mindestens warten.
    fn retry_after_error(e: &Error, cfg: &LiveConfig) -> Ended {
        let at_least = match e {
            Error::TrsApi { kind: "trs_banned", .. } => cfg.banned_wait,
            Error::TrsApi { kind: "trs_auth" | "trs_no_account", .. } => cfg.backoff(u32::MAX),
            _ => Duration::ZERO,
        };
        Ended::Retry { at_least, was_live: false }
    }

    async fn live_session(
        &self,
        sessions: &dyn SessionSource,
        active: &dyn ActiveAccount,
        account: &str,
        cfg: &LiveConfig,
    ) -> Ended {
        let mut relogged = false;
        let response = loop {
            let token = match self.store.token(account).await {
                Some(t) => t,
                None => {
                    relogged = true;
                    match self.login(sessions, account).await {
                        Ok(t) => t,
                        Err(Error::TrsApi { kind: "trs_disabled", .. }) => return Ended::Switch,
                        Err(e) => return Self::retry_after_error(&e, cfg),
                    }
                }
            };
            let mut req = self
                .stream_http
                .get(format!("{}/v1/events/me", self.base))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .bearer_auth(&token);
            if let Some(id) = self.live.last_id(account) {
                req = req.header("Last-Event-ID", id);
            }
            let response = match req.send().await {
                Ok(r) => r,
                Err(e) => {
                    tracing::debug!("Echtzeit-Kanal nicht erreichbar: {e}");
                    return Ended::Retry { at_least: Duration::ZERO, was_live: false };
                }
            };
            let status = response.status().as_u16();
            match status {
                200..=299 => break response,
                401 if !relogged => {
                    let _ = self.store.take_token(account).await;
                    relogged = true;
                }
                401 => {
                    let _ = self.store.take_token(account).await;
                    return Ended::Retry { at_least: cfg.backoff(u32::MAX), was_live: false };
                }
                403 => return Ended::Retry { at_least: cfg.banned_wait, was_live: false },
                429 => {
                    let secs = response
                        .headers()
                        .get(reqwest::header::RETRY_AFTER)
                        .and_then(|v| v.to_str().ok())
                        .and_then(|v| v.trim().parse::<u64>().ok())
                        .unwrap_or(30)
                        .min(600);
                    return Ended::Retry { at_least: Duration::from_secs(secs), was_live: false };
                }
                _ => return Ended::Retry { at_least: Duration::ZERO, was_live: false },
            }
        };

        let mut stream = response.bytes_stream();
        let mut parser = SseParser { last_id: self.live.last_id(account), ..SseParser::default() };
        let mut was_live = false;
        let mut deadline = tokio::time::Instant::now() + cfg.ping_timeout;
        loop {
            tokio::select! {
                chunk = stream.next() => {
                    let Some(Ok(chunk)) = chunk else {
                        return Ended::Retry { at_least: Duration::ZERO, was_live };
                    };
                    deadline = tokio::time::Instant::now() + cfg.ping_timeout;
                    let Ok(frames) = parser.feed(&chunk) else {
                        tracing::warn!("Echtzeit-Kanal: zu großes Ereignis – neu verbinden");
                        return Ended::Retry { at_least: Duration::ZERO, was_live };
                    };
                    if let Some(id) = &parser.last_id {
                        self.live.remember_id(account, id);
                    }
                    for frame in frames {
                        if frame.event == "hello" {
                            was_live = true;
                            let resumed = serde_json::from_str::<D>(&frame.data).ok().and_then(|d| d.resumed).unwrap_or(false);
                            self.live.set_status("live", Some(account), None);
                            let first = self.live.first_hello(account);
                            self.live.emit(LiveOut::Event(LiveEvent::Hello { resumed, first }));
                        } else if let Some(event) = decode(&frame.event, &frame.data) {
                            self.live.emit(LiveOut::Event(event));
                        }
                    }
                }
                () = tokio::time::sleep_until(deadline) => {
                    tracing::debug!("Echtzeit-Kanal: kein Lebenszeichen – neu verbinden");
                    return Ended::Retry { at_least: Duration::ZERO, was_live };
                }
                () = self.live.notify.notified() => {
                    if self.live_target(active).await.as_deref() != Some(account) {
                        return Ended::Switch;
                    }
                }
                () = tokio::time::sleep(cfg.recheck) => {
                    if self.live_target(active).await.as_deref() != Some(account) {
                        return Ended::Switch;
                    }
                }
            }
        }
    }
}

impl ActiveAccount for crate::auth::AccountStore {
    fn active(&self) -> BoxFuture<'_, Option<String>> {
        Box::pin(async move { self.active_id().await.ok().flatten() })
    }
}

impl crate::Launcher {
    /// Echtzeit-Kanal im Hintergrund (ohne Einwilligung/Account passiert nichts).
    pub async fn run_trs_live(self: Arc<Self>) {
        tokio::time::sleep(Duration::from_secs(2)).await;
        self.trs.run_live(self.accounts(), self.accounts(), &LiveConfig::default()).await;
    }

    pub fn set_trs_live_sink(&self, sink: LiveSink) {
        self.trs.set_live_sink(sink);
    }

    pub fn trs_live_status(&self) -> LiveStatus {
        self.trs.live_status()
    }

    /// „Jetzt neu verbinden“ (z. B. nach dem Aufwachen aus dem Standby).
    pub fn trs_live_kick(&self) {
        self.trs.live.kick();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parser_handles_split_chunks_and_line_endings() {
        let mut p = SseParser::default();
        let text = "id: e.1\r\nevent: hello\r\ndata: {\"type\":\"hello\"}\r\n\r\n: kommentar\nevent: ping\ndata: {}\n\nid: e.2\nevent: chat\ndata: a\ndata: b\n\n";
        let mut frames = Vec::new();
        for chunk in text.as_bytes().chunks(3) {
            frames.extend(p.feed(chunk).unwrap());
        }
        assert_eq!(frames.len(), 3);
        assert_eq!(frames[0], SseFrame { event: "hello".into(), data: "{\"type\":\"hello\"}".into() });
        assert_eq!(frames[1].event, "ping");
        assert_eq!(frames[2].data, "a\nb");
        assert_eq!(p.last_id.as_deref(), Some("e.2"));
    }

    #[test]
    fn parser_keeps_last_id_without_new_id_and_limits_lines() {
        let mut p = SseParser { last_id: Some("e.9".into()), ..Default::default() };
        let frames = p.feed(b"event: chat_typing\ndata: {}\n\n").unwrap();
        assert_eq!(frames.len(), 1);
        assert_eq!(p.last_id.as_deref(), Some("e.9"), "Tippen hat keine ID");
        let mut p = SseParser::default();
        let big = vec![b'x'; MAX_EVENT_BYTES + 1];
        assert_eq!(p.feed(&big), Err(SseOverflow));
        let mut p = SseParser::default();
        assert!(p.feed(b"data\n\n").unwrap()[0].data.is_empty(), "Feld ohne Doppelpunkt");
        assert!(p.feed(b"id: bad\0id\n").unwrap().is_empty());
        assert!(p.last_id.is_none(), "IDs mit NUL werden ignoriert");
    }

    #[test]
    fn event_ids_are_checked_before_they_go_back_as_header() {
        assert!(valid_event_id("mfz2k1a3b4c.1842"));
        assert!(!valid_event_id("a\r\nX-Evil: 1") && !valid_event_id("") && !valid_event_id(&"a".repeat(65)));
    }

    #[test]
    fn backoff_grows_and_caps() {
        let cfg = LiveConfig::default();
        let secs: Vec<u64> = (1..=8).map(|n| cfg.backoff(n).as_secs()).collect();
        assert_eq!(secs, [1, 2, 5, 10, 20, 30, 30, 30]);
        assert_eq!(cfg.backoff(0).as_secs(), 1);
    }

    fn msg(conv: &str) -> serde_json::Value {
        json!({ "id": "m0a1b2c3d4e5f60718293", "conversationId": conv, "seq": 7, "kind": "text",
            "sender": { "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Bob" }, "text": "Hi\u{202E}",
            "attachments": [], "reactions": [], "createdAt": "2026-09-26T10:00:00.000Z", "deleted": false, "hidden": false })
    }

    #[test]
    fn events_are_decoded_and_cleaned() {
        let c = "c1f0e2d3c4b5a6978899a";
        let ev = decode("chat_message", &json!({ "type": "chat_message", "conversationId": c, "message": msg(c) }).to_string()).unwrap();
        let LiveEvent::ChatMessage { message, .. } = &ev else { panic!("{ev:?}") };
        assert_eq!(message.text.as_deref(), Some("Hi"));
        let out = serde_json::to_value(&ev).unwrap();
        assert_eq!(out["type"], "chat_message");
        assert_eq!(out["conversationId"], c);

        // Nachricht aus einer anderen Unterhaltung als angegeben → verworfen.
        assert!(decode("chat_message", &json!({ "conversationId": c, "message": msg("c0000000000000000000a") }).to_string()).is_none());

        let typing = decode("chat_typing", &json!({ "conversationId": c, "uuid": "B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0", "typing": true, "expiresInMs": 8000 }).to_string()).unwrap();
        assert_eq!(serde_json::to_value(&typing).unwrap(), json!({ "type": "chat_typing", "conversationId": c, "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "typing": true, "expiresInMs": 8000 }));

        let presence = decode("presence", &json!({ "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "presence": { "state": "in-game", "game": { "version": "1.21.1", "loader": "evil", "server": "x y" } } }).to_string()).unwrap();
        let LiveEvent::Presence { presence: Some(p), .. } = presence else { panic!() };
        assert_eq!(p.game.as_ref().unwrap().loader, "vanilla");
        assert!(p.game.unwrap().server.is_none());

        assert_eq!(decode("friends_changed", "{}"), Some(LiveEvent::FriendsChanged));
        assert!(decode("brand_new_event", "{}").is_none(), "unbekannte Typen ignorieren");
        assert!(decode("chat_reload", "{\"conversationId\":\"../x\"}").is_none());
        assert!(decode("moderation", "{\"action\":\"nuke\"}").is_none());
        let m = decode("moderation", "{\"action\":\"mute\",\"reason\":null,\"until\":null,\"auto\":\"spam\"}").unwrap();
        assert_eq!(serde_json::to_value(&m).unwrap()["auto"], "spam");
        let r = decode("report_update", "{\"report\":{\"id\":\"r0123456789abcdef\",\"kind\":\"message\",\"status\":\"resolved\",\"outcome\":\"actioned\",\"updatedAt\":\"x\"}}").unwrap();
        assert!(matches!(r, LiveEvent::ReportUpdate { .. }));
        let s = decode("settings", &json!({ "settings": { "showBadge": true, "showCapeToOthers": true, "presenceVisibility": "friends", "shareServer": false } }).to_string()).unwrap();
        let LiveEvent::Settings { settings } = s else { panic!() };
        assert!(settings.chat_read_receipts, "fehlende Chat-Schalter = an");
        let offer = decode("cape_offer", &json!({ "offer": { "cape": { "id": "u0123456789abcdef0123", "name": "Blitz" }, "from": { "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Bob" } } }).to_string()).unwrap();
        assert_eq!(serde_json::to_value(&offer).unwrap()["cape"], "Blitz");
    }

    fn room_view() -> serde_json::Value {
        json!({ "id": "h0123456789abcdef0123", "name": "Insel", "host": { "uuid": "75c1a6f3112240abbdb57b9d21c64232", "name": "Theredstonee" },
                "mcVersion": "1.21.11", "loader": "fabric", "maxPlayers": 4, "gameMode": "survival", "pvp": true, "cheats": false,
                "open": true, "players": 2, "createdAt": "2026-09-26T10:00:00Z", "myState": "invited" })
    }

    #[test]
    fn hosting_events_are_decoded() {
        let bob = json!({ "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Bob" });
        let invite = decode("hosting_invite", &json!({ "room": room_view(), "from": bob }).to_string()).unwrap();
        let out = serde_json::to_value(&invite).unwrap();
        assert_eq!(out["type"], "hosting_invite");
        assert_eq!(out["room"]["myState"], "invited");
        assert_eq!(out["room"]["mcVersion"], "1.21.11");
        assert_eq!(out["from"]["name"], "Bob");
        assert!(out["room"]["code"].is_null(), "Gäste sehen den Code nicht");

        let accepted = decode("hosting_join_accepted", &json!({ "room": room_view() }).to_string()).unwrap();
        assert_eq!(serde_json::to_value(&accepted).unwrap()["type"], "hosting_join_accepted");
        let closed = decode("hosting_room_closed", "{\"roomId\":\"h0123456789abcdef0123\",\"reason\":\"exploded\"}").unwrap();
        assert_eq!(serde_json::to_value(&closed).unwrap(), json!({ "type": "hosting_room_closed", "roomId": "h0123456789abcdef0123", "reason": "closed" }));
        let kicked = decode("hosting_kicked", "{\"roomId\":\"h0123456789abcdef0123\",\"banned\":true}").unwrap();
        assert_eq!(kicked, LiveEvent::HostingKicked { room_id: "h0123456789abcdef0123".into(), banned: true });
        let request = decode("hosting_join_request", &json!({ "roomId": "h0123456789abcdef0123", "from": bob }).to_string()).unwrap();
        assert!(matches!(request, LiveEvent::HostingJoinRequest { .. }));

        assert!(decode("hosting_join_declined", "{\"roomId\":\"../x\"}").is_none());
        assert!(decode("hosting_invite", "{\"room\":{\"id\":\"h0123456789abcdef0123\"}}").is_none(), "Raum ohne Host/Version");
        let signal = json!({ "roomId": "h0123456789abcdef0123", "from": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "kind": "offer", "data": "{\"ip\":\"203.0.113.9\"}" });
        assert!(decode("hosting_signal", &signal.to_string()).is_none(), "Signale (IP-Adressen) gehen nie ans Webview");
    }
}
