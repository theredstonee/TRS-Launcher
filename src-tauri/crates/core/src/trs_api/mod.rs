//! Anbindung an die TRS API (`trs-launcher.theredstonee.de`, früher nur
//! `api.theredstonee.de` – beide Adressen bleiben gültig; Vertrag: `api/API.md`).
//!
//! - **Anmeldung je Minecraft-Account** wie bei einem Minecraft-Server:
//!   `challenge` → Mojang `join` mit dem Minecraft-Token des Accounts →
//!   `verify` → Bearer-Token. Der Token liegt DPAPI-verschlüsselt in
//!   `<daten>/trs-api.json` und erreicht nie das Webview.
//! - Bei `401` wird genau einmal neu angemeldet, `429` respektiert
//!   `Retry-After`, Netzwerkfehler werden zu `trs_offline` (die Oberfläche
//!   zeigt dann still „offline“ statt Fehlermeldungen).
//! - **Einwilligung:** Solange der Nutzer nicht zugestimmt hat (oder
//!   abgelehnt hat), geht keine einzige Anfrage an die API.
//! - **Präsenz:** Der Launcher meldet alle 60 s `online` – aber nur, solange
//!   kein Spiel dieses Accounts läuft. Während eines Spiels meldet der
//!   TRS Client im Spiel selbst `in-game` (mit Server, falls geteilt); nach
//!   dem Spielende übernimmt der Launcher sofort wieder. Der Launcher gibt
//!   seinen Token nie an das Spiel weiter, der Mod meldet sich selbst an.

pub mod cape_import;
pub mod chat;
pub mod hosting;
pub mod moderation;
pub mod live;
pub mod media;
mod ops;
pub mod png;
mod presence;
pub mod sanctions;
pub mod store;
pub mod sync;
pub mod team;
mod texture;
pub mod types;
pub mod validate;

use std::sync::Arc;
use std::time::Duration;

use futures::future::BoxFuture;
use reqwest::Method;
use serde::de::DeserializeOwned;

use crate::auth::AccountStore;
use crate::launch::Session;
use crate::paths::Paths;
use crate::error::Msg;
use crate::{Error, Result, USER_AGENT};
pub use presence::{PRESENCE_INTERVAL, PresenceGame};
use presence::PresenceState;
pub(crate) use ops::encode_query;
pub use store::Consent;
use store::Store;
use types::{ApiChallenge, ApiMe, ApiVerify};

/// Adresse der TRS API (dort liegt auch die Website).
pub const DEFAULT_BASE: &str = "https://trs-launcher.theredstonee.de";
/// Bisherige Adresse – bleibt parallel erreichbar. Umhang-URLs mit diesem Host
/// (z. B. aus älteren Antworten oder Caches) gelten weiter als vertrauenswürdig.
pub const LEGACY_BASE: &str = "https://api.theredstonee.de";
/// Alle Adressen, unter denen die echte TRS API läuft.
pub const KNOWN_BASES: [&str; 2] = [DEFAULT_BASE, LEGACY_BASE];
pub const MOJANG_SESSION: &str = "https://sessionserver.mojang.com";
pub const MOJANG_API: &str = "https://api.mojang.com";

/// Größte JSON-Antwort, die angenommen wird.
const MAX_JSON_BYTES: usize = 4 * 1024 * 1024;
/// Kurze `429`-Wartezeiten wartet der Kern selbst ab (einmal), längere gehen als Fehler raus.
const MAX_INLINE_RETRY: Duration = Duration::from_secs(3);

/// Liefert die Minecraft-Sitzung eines Accounts (für den Mojang-Join).
pub trait SessionSource: Send + Sync {
    fn session<'a>(&'a self, account: &'a str, force_refresh: bool) -> BoxFuture<'a, Result<Option<Session>>>;
}

impl SessionSource for AccountStore {
    fn session<'a>(&'a self, account: &'a str, force_refresh: bool) -> BoxFuture<'a, Result<Option<Session>>> {
        Box::pin(self.session_for(account, force_refresh))
    }
}

/// Anfrage-Body.
#[derive(Debug, Clone)]
pub(crate) enum Body {
    Empty,
    Json(serde_json::Value),
    Png(Vec<u8>),
    /// Rohes Bild (Chat-Upload) mit seinem Typ.
    Image(&'static str, Vec<u8>),
}

/// Eine Anfrage an die API (Pfad ab `/v1/...`, Query schon kodiert angehängt).
#[derive(Debug, Clone)]
pub(crate) struct Req {
    pub method: Method,
    pub path: String,
    pub body: Body,
    /// Größte angenommene Antwort (JSON: [`MAX_JSON_BYTES`], Bilder mehr).
    pub limit: usize,
}

impl Req {
    pub fn get(path: impl Into<String>) -> Self {
        Self { method: Method::GET, path: path.into(), body: Body::Empty, limit: MAX_JSON_BYTES }
    }
    pub fn post(path: impl Into<String>, body: serde_json::Value) -> Self {
        Self { method: Method::POST, path: path.into(), body: Body::Json(body), limit: MAX_JSON_BYTES }
    }
    pub fn post_empty(path: impl Into<String>) -> Self {
        Self { method: Method::POST, path: path.into(), body: Body::Empty, limit: MAX_JSON_BYTES }
    }
    pub fn put(path: impl Into<String>, body: serde_json::Value) -> Self {
        Self { method: Method::PUT, path: path.into(), body: Body::Json(body), limit: MAX_JSON_BYTES }
    }
    pub fn patch(path: impl Into<String>, body: serde_json::Value) -> Self {
        Self { method: Method::PATCH, path: path.into(), body: Body::Json(body), limit: MAX_JSON_BYTES }
    }
    pub fn delete(path: impl Into<String>) -> Self {
        Self { method: Method::DELETE, path: path.into(), body: Body::Empty, limit: MAX_JSON_BYTES }
    }
    /// Beliebiger Body mit eigener Grenze für die Antwort.
    pub fn with(method: Method, path: impl Into<String>, body: Body, limit: usize) -> Self {
        Self { method, path: path.into(), body, limit }
    }
}

/// Warum eine Anfrage scheiterte (vor der Übersetzung in [`Error`]).
#[derive(Debug)]
pub(crate) enum Failure {
    Network,
    Api {
        status: u16,
        code: String,
        retry_after: Option<u64>,
        current: Option<serde_json::Value>,
        /// Strafe (und ggf. Einspruch-Token) bei `sanctioned`/`chat_muted`/`banned` (§22).
        detail: Option<Box<sanctions::SanctionDetail>>,
    },
}

impl Failure {
    fn into_error(self) -> Error {
        match self {
            Self::Network => offline(),
            Self::Api { status, code, retry_after, detail, .. } => {
                let error = api_error(status, &code, retry_after);
                match (error, detail.and_then(|d| d.sanction)) {
                    (Error::TrsApi { kind, code, msg }, Some(sanction)) => {
                        Error::TrsApi { kind, code, msg: sanctions::with_sanction(msg, &sanction) }
                    }
                    (error, _) => error,
                }
            }
        }
    }
}

/// Ergebnis einer Anfrage, bei der der Server einen neueren Stand melden darf
/// (`409 stale` mit `error.current`, „letzter Schreiber gewinnt“).
#[derive(Debug)]
pub(crate) enum Outcome {
    Done(Vec<u8>),
    Stale(serde_json::Value),
}

pub(crate) fn offline() -> Error {
    trs_error("trs_offline", "", crate::msg!("trs.offline", "Der TRS-Server ist gerade nicht erreichbar."))
}

pub(crate) fn disabled() -> Error {
    trs_error(
        "trs_disabled",
        "",
        crate::msg!("trs.disabled", "Die TRS-Dienste sind ausgeschaltet (Einstellungen → Datenschutz)."),
    )
}

pub(crate) fn no_account() -> Error {
    trs_error(
        "trs_no_account",
        "",
        crate::msg!("trs.noAccount", "Bitte melde dich zuerst unter „Accounts“ mit deinem Minecraft-Konto an."),
    )
}

pub(crate) fn bad_response() -> Error {
    trs_error("trs_api", "bad_response", crate::msg!("trs.badResponse", "Die TRS API hat unerwartet geantwortet."))
}

fn auth_failed(msg: Msg) -> Error {
    trs_error("trs_auth", "", msg)
}

fn trs_error(kind: &'static str, code: &str, msg: Msg) -> Error {
    Error::TrsApi { kind, code: code.to_owned(), msg }
}

/// API-Fehler → stabiler `kind` + Meldung mit Übersetzungs-Code.
pub(crate) fn api_error(status: u16, code: &str, retry_after: Option<u64>) -> Error {
    let (kind, msg): (&'static str, Msg) = match (status, code) {
        (_, "banned") => ("trs_banned", crate::msg!("trs.banned", "Dein Konto ist für die TRS-Dienste gesperrt.")),
        (429, _) => (
            "trs_rate_limited",
            crate::msg!(
                "trs.rateLimited",
                "Zu viele Anfragen – bitte in {seconds} s erneut versuchen.",
                seconds = retry_after.unwrap_or(60).max(1)
            ),
        ),
        (401, "not_joined") => (
            "trs_auth",
            crate::msg!("trs.notJoined", "Mojang hat die Anmeldung nicht bestätigt – bitte später erneut versuchen."),
        ),
        (401, "invalid_challenge") => (
            "trs_auth",
            crate::msg!("trs.challengeExpired", "Die Anmeldung hat zu lange gedauert – bitte erneut versuchen."),
        ),
        (401, _) => {
            ("trs_auth", crate::msg!("trs.sessionExpired", "Die TRS-Anmeldung ist abgelaufen – bitte erneut versuchen."))
        }
        (403, "forbidden") => ("trs_forbidden", crate::msg!("trs.forbidden", "Dafür fehlen dir die Rechte.")),
        // Welt-Hosting ohne Relay auf dem Server (503) ist kein „offline“.
        (_, "hosting_unavailable") => ("trs_api", message_for(code)),
        (500..=599, _) => ("trs_offline", crate::msg!("trs.offline", "Der TRS-Server ist gerade nicht erreichbar.")),
        (_, code) => ("trs_api", message_for(code)),
    };
    trs_error(kind, code, msg)
}

fn message_for(code: &str) -> Msg {
    use crate::msg;
    match code {
        "cape_not_found" => msg!("trsApi.cape_not_found", "Diesen Umhang gibt es nicht (mehr)."),
        "cape_locked" => msg!("trsApi.cape_locked", "Dieser Umhang ist für dich noch nicht freigeschaltet."),
        "invalid_code" => msg!("trsApi.invalid_code", "Dieser Code ist ungültig."),
        "code_expired" => msg!("trsApi.code_expired", "Dieser Code ist abgelaufen."),
        "code_used_up" => msg!("trsApi.code_used_up", "Dieser Code wurde bereits zu oft eingelöst."),
        "player_not_found" => msg!(
            "trsApi.player_not_found",
            "Spieler nicht gefunden – er muss den TRS Launcher schon einmal benutzt haben."
        ),
        "cannot_target_self" => msg!("trsApi.cannot_target_self", "Das bist du selbst."),
        "blocked" => msg!("trsApi.blocked", "Du hast diesen Spieler blockiert – entsperre ihn zuerst."),
        "already_friends" => msg!("trsApi.already_friends", "Ihr seid schon befreundet."),
        "already_requested" => msg!("trsApi.already_requested", "Du hast diesem Spieler schon eine Anfrage geschickt."),
        "too_many_requests" => msg!("trsApi.too_many_requests", "Du hast zu viele offene Anfragen (höchstens 50)."),
        "target_inbox_full" => msg!("trsApi.target_inbox_full", "Dieser Spieler hat zu viele offene Anfragen."),
        "friend_limit" => msg!("trsApi.friend_limit", "Deine Freundesliste ist voll (höchstens 200)."),
        "target_friend_limit" => msg!("trsApi.target_friend_limit", "Die Freundesliste dieses Spielers ist voll."),
        "request_not_found" => msg!("trsApi.request_not_found", "Diese Anfrage gibt es nicht mehr."),
        "friend_not_found" => msg!("trsApi.friend_not_found", "Ihr seid nicht (mehr) befreundet."),
        "block_not_found" => msg!("trsApi.block_not_found", "Dieser Spieler ist nicht blockiert."),
        "invalid_png" => msg!("trsApi.invalid_png", "Die Datei ist kein gültiges PNG-Bild."),
        "animated_png" => msg!("trsApi.animated_png", "Animierte PNGs sind für eigene Umhänge nicht erlaubt."),
        "invalid_dimensions" => {
            msg!("trsApi.invalid_dimensions", "Das Bild hat nicht die richtige Größe für einen Umhang.")
        }
        "empty_cape" => msg!("trsApi.empty_cape", "Der Umhang ist komplett durchsichtig."),
        "frame_time_required" => {
            msg!("trsApi.frame_time_required", "Animierte Umhänge brauchen ein Bildtempo.")
        }
        "too_many_pending" => {
            msg!("trsApi.too_many_pending", "Du hast schon 3 Umhänge in Prüfung – warte auf die Freigabe.")
        }
        "upload_limit" => msg!("trsApi.upload_limit", "Du hast schon 10 eigene Umhänge."),
        "duplicate_cape" => msg!("trsApi.duplicate_cape", "Diesen Umhang hast du schon hochgeladen."),
        "payload_too_large" => msg!("trsApi.payload_too_large", "Die Datei ist zu groß."),
        "unsupported_media_type" => msg!("trsApi.unsupported_media_type", "Dieses Dateiformat wird nicht unterstützt."),
        "user_not_found" => msg!("trsApi.user_not_found", "Dieser Spieler hat die TRS-Dienste noch nie benutzt."),
        "builtin_cape" => msg!("trsApi.builtin_cape", "Standard-Umhänge können nicht gelöscht werden."),
        "cape_is_free" => msg!("trsApi.cape_is_free", "Für freie Umhänge braucht es keinen Code."),
        "grant_not_found" => msg!("trsApi.grant_not_found", "Dieser Spieler hat den Umhang nicht."),
        "cannot_ban_admin" => msg!("trsApi.cannot_ban_admin", "Admins können nicht gesperrt werden."),
        "not_banned" => msg!("trsApi.not_banned", "Dieser Spieler ist nicht gesperrt."),
        "cape_not_approved" => {
            msg!("trsApi.cape_not_approved", "Teilen geht erst, wenn das Team den Umhang freigegeben hat.")
        }
        "cape_not_shareable" => msg!("trsApi.cape_not_shareable", "Nur eigene hochgeladene Umhänge können geteilt werden."),
        "already_shared" => msg!("trsApi.already_shared", "Dieser Spieler hat den Umhang schon oder ein Angebot dafür."),
        "share_limit" => msg!("trsApi.share_limit", "Dieser Umhang ist schon mit 20 Spielern geteilt."),
        "offer_inbox_full" => msg!("trsApi.offer_inbox_full", "Dieser Spieler hat zu viele offene Umhang-Angebote."),
        "offer_not_found" => msg!("trsApi.offer_not_found", "Dieses Angebot gibt es nicht mehr."),
        "holder_not_found" => msg!("trsApi.holder_not_found", "Dieser Spieler hat den Umhang nicht (mehr) von dir."),
        // --- Chat (§18) ---
        "not_friends" => msg!("trsApi.not_friends", "Ihr seid nicht (mehr) befreundet."),
        "chat_muted" => msg!("trsApi.chat_muted", "Du bist im Chat gerade stummgeschaltet."),
        "sanctioned" => msg!("trsApi.sanctioned", "Das ist für dein Konto wegen einer Strafe gerade gesperrt."),
        "conversation_not_found" => msg!("trsApi.conversation_not_found", "Diese Unterhaltung gibt es nicht (mehr)."),
        "message_not_found" => msg!("trsApi.message_not_found", "Diese Nachricht gibt es nicht (mehr)."),
        "attachment_not_found" => msg!("trsApi.attachment_not_found", "Dieses Bild gibt es nicht (mehr)."),
        "message_too_long" => msg!("trsApi.message_too_long", "Die Nachricht ist zu lang (höchstens 2000 Zeichen)."),
        "empty_message" => msg!("trsApi.empty_message", "Die Nachricht ist leer."),
        "too_many_attachments" => msg!("trsApi.too_many_attachments", "Höchstens 10 Bilder je Nachricht."),
        "links_not_allowed" => msg!(
            "trsApi.links_not_allowed",
            "In dieser Gruppe dürfen nur der Besitzer und Spieler, die mit allen befreundet sind, Links und Einladungen senden."
        ),
        "message_blocked" => msg!("trsApi.message_blocked", "Die Nachricht enthält ein gesperrtes Wort."),
        "spam_detected" => msg!("trsApi.spam_detected", "Das sieht nach Spam aus – bitte nicht dieselbe Nachricht mehrfach senden."),
        "not_sender" => msg!("trsApi.not_sender", "Das geht nur bei eigenen Nachrichten."),
        "message_deleted" => msg!("trsApi.message_deleted", "Die Nachricht wurde gelöscht."),
        "not_owner" => msg!("trsApi.not_owner", "Das darf nur der Besitzer der Gruppe."),
        "group_full" => msg!("trsApi.group_full", "Die Gruppe ist voll (höchstens 25 Mitglieder)."),
        "group_limit" => msg!("trsApi.group_limit", "Du bist schon in zu vielen Gruppen."),
        "target_group_limit" => msg!("trsApi.target_group_limit", "Ein Spieler ist schon in zu vielen Gruppen."),
        "invalid_name" => msg!("trsApi.invalid_name", "Gruppenname: 1 bis 32 Zeichen."),
        "member_not_found" => msg!("trsApi.member_not_found", "Dieser Spieler ist nicht in der Gruppe."),
        "nonce_reused" => msg!("trsApi.nonce_reused", "Die Nachricht konnte nicht gesendet werden – bitte erneut versuchen."),
        "invalid_until" => msg!("trsApi.invalid_until", "Dieser Zeitpunkt liegt in der Vergangenheit."),
        "invalid_cursor" => msg!("trsApi.invalid_cursor", "Die Liste hat sich geändert – bitte neu laden."),
        "image_too_large" => msg!("trsApi.image_too_large", "Das Bild ist zu groß (höchstens 8192 Pixel je Seite)."),
        "animated_image" => msg!("trsApi.animated_image", "Animierte Bilder können nicht gesendet werden."),
        "invalid_image" => msg!("trsApi.invalid_image", "Das Bild ist beschädigt."),
        "too_many_pending_attachments" => {
            msg!("trsApi.too_many_pending_attachments", "Zu viele Bilder warten aufs Senden – schick erst die vorigen ab.")
        }
        "storage_quota" => msg!("trsApi.storage_quota", "Dein Speicher für Chat-Bilder ist voll – lösch ein paar alte Bilder."),
        "storage_full" => msg!("trsApi.storage_full", "Der TRS-Server nimmt gerade keine Bilder an – bitte später erneut versuchen."),
        // --- Meldungen und Moderation (§20) ---
        "already_reported" => msg!("trsApi.already_reported", "Das hast du schon gemeldet – wir schauen es uns an."),
        "too_many_open_reports" => msg!("trsApi.too_many_open_reports", "Du hast schon viele offene Meldungen – warte auf die Prüfung."),
        "not_reportable" => msg!("trsApi.not_reportable", "Das kann nicht gemeldet werden."),
        "report_resolved" => msg!("trsApi.report_resolved", "Diese Meldung ist schon erledigt."),
        "report_not_found" => msg!("trsApi.report_not_found", "Diese Meldung gibt es nicht (mehr)."),
        "no_message" => msg!("trsApi.no_message", "Zu dieser Meldung gehört keine Nachricht."),
        "no_target" => msg!("trsApi.no_target", "Zu dieser Meldung gehört kein Spieler."),
        "cannot_moderate_admin" => msg!("trsApi.cannot_moderate_admin", "Admins können nicht stummgeschaltet werden."),
        "not_muted" => msg!("trsApi.not_muted", "Dieser Spieler ist nicht stummgeschaltet."),
        "invalid_word" => msg!("trsApi.invalid_word", "Wort: 2 bis 48 Buchstaben oder Ziffern."),
        "word_exists" => msg!("trsApi.word_exists", "Dieses Wort steht schon im Filter."),
        "word_not_found" => msg!("trsApi.word_not_found", "Dieses Wort steht nicht (mehr) im Filter."),
        // --- Welt-Hosting (§21) ---
        "room_not_found" => msg!("trsApi.room_not_found", "Diese Welt gibt es nicht (mehr) oder du kannst sie nicht sehen."),
        "banned_from_world" => msg!("trsApi.banned_from_world", "Du bist aus dieser Welt verbannt."),
        "world_closed" => msg!("trsApi.world_closed", "Diese Welt nimmt gerade keine neuen Anfragen an."),
        "room_full" => msg!("trsApi.room_full", "Diese Welt ist voll."),
        "cannot_join_own_world" => msg!("trsApi.cannot_join_own_world", "Das ist deine eigene Welt."),
        "not_accepted" => msg!("trsApi.not_accepted", "Der Host hat dich (noch) nicht in die Welt gelassen."),
        "hosting_unavailable" => {
            msg!("trsApi.hosting_unavailable", "Welt-Hosting ist auf dem TRS-Server gerade nicht verfügbar.")
        }
        // --- Moderation v2 (§22) ---
        "sanction_not_found" => msg!("trsApi.sanction_not_found", "Diese Strafe gibt es nicht (mehr)."),
        "sanction_not_active" => msg!("trsApi.sanction_not_active", "Diese Strafe ist nicht mehr aktiv."),
        "appeal_exists" => msg!("trsApi.appeal_exists", "Gegen diese Strafe hast du schon Einspruch eingelegt."),
        "appeal_not_found" => msg!("trsApi.appeal_not_found", "Diesen Einspruch gibt es nicht (mehr)."),
        "appeal_decided" => msg!("trsApi.appeal_decided", "Über diesen Einspruch wurde schon entschieden."),
        "own_sanction" => msg!("trsApi.own_sanction", "Über Einsprüche gegen eigene Strafen entscheidet jemand anderes."),
        "admin_only" => msg!("trsApi.admin_only", "Das dürfen nur Admins."),
        "duration_not_allowed" => msg!("trsApi.duration_not_allowed", "Diese Dauer darfst du nicht vergeben."),
        "cannot_moderate_staff" => msg!("trsApi.cannot_moderate_staff", "Team-Mitglieder kannst du nicht bestrafen."),
        "invalid_duration" => msg!("trsApi.invalid_duration", "Das Ende muss in der Zukunft liegen – zum Beenden „Aufheben“ nutzen."),
        "no_change" => msg!("trsApi.no_change", "Das ist schon das aktuelle Ende."),
        "role_locked" => msg!("trsApi.role_locked", "Diese Rolle ist fest eingestellt und lässt sich hier nicht ändern."),
        "cannot_change_self" => msg!("trsApi.cannot_change_self", "Deine eigene Rolle kannst du nicht ändern."),
        "role_not_found" => msg!("trsApi.role_not_found", "Dieser Spieler hat keine Team-Rolle."),
        "note_not_found" => msg!("trsApi.note_not_found", "Diese Notiz gibt es nicht (mehr)."),
        "bulk_too_large" => msg!("trsApi.bulk_too_large", "Höchstens 50 Einträge auf einmal."),
        "invalid_request" | "invalid_json" => msg!("trsApi.invalid_request", "Die Anfrage war ungültig."),
        "not_found" => msg!("trsApi.not_found", "Nicht gefunden."),
        _ => msg!("trsApi.rejected", "Die TRS API hat die Anfrage abgelehnt."),
    }
}

/// Client der TRS API. Hält Einwilligung, Tokens und den Präsenz-Zustand.
pub struct TrsApi {
    http: reqwest::Client,
    base: String,
    session_base: String,
    mojang_api: String,
    paths: Paths,
    store: Store,
    /// Es läuft höchstens eine Anmeldung gleichzeitig.
    login_lock: tokio::sync::Mutex<()>,
    pub(crate) presence: Arc<PresenceState>,
    /// Takt und Status der TRS-Synchronisation (siehe [`sync`]).
    pub(crate) sync: Arc<sync::SyncState>,
    pub(crate) sync_store: sync::SyncStore,
    /// Chat-Bilder: Zwischenspeicher, eigene Dateien, Favoriten.
    pub(crate) media: media::MediaState,
    /// Echtzeit-Kanal `GET /v1/events/me` (siehe [`live`]).
    pub(crate) live: Arc<live::LiveState>,
    /// HTTP-Client ohne Gesamt-Timeout für den Echtzeit-Kanal.
    stream_http: reqwest::Client,
    /// Einspruch-Tokens gesperrter Konten (§22.3, nur im Speicher).
    pub(crate) appeal_tokens: sanctions::AppealTokens,
}

impl TrsApi {
    pub fn new(paths: Paths) -> Result<Self> {
        // Debug-Builds dürfen gegen eine lokale API testen.
        let base = std::env::var("TRS_API_BASE")
            .ok()
            .filter(|b| cfg!(debug_assertions) && (b.starts_with("http://127.0.0.1:") || b.starts_with("https://")))
            .unwrap_or_else(|| DEFAULT_BASE.to_owned());
        Self::with_endpoints(paths, &base, MOJANG_SESSION, MOJANG_API)
    }

    /// Wie [`Self::new`], mit eigenen Endpunkten (Tests).
    pub fn with_endpoints(paths: Paths, base: &str, session_base: &str, mojang_api: &str) -> Result<Self> {
        let http = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(8))
            .timeout(Duration::from_secs(20))
            .build()?;
        let stream_http = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(8))
            .build()?;
        Ok(Self {
            http,
            stream_http,
            media: media::MediaState::new(paths.root().to_path_buf()),
            live: Arc::default(),
            base: base.trim_end_matches('/').to_owned(),
            session_base: session_base.trim_end_matches('/').to_owned(),
            mojang_api: mojang_api.trim_end_matches('/').to_owned(),
            store: Store::new(paths.root().join("trs-api.json")),
            sync_store: sync::SyncStore::new(paths.root().join("trs-sync.json")),
            sync: Arc::default(),
            paths,
            login_lock: tokio::sync::Mutex::new(()),
            presence: Arc::new(PresenceState::default()),
            appeal_tokens: sanctions::AppealTokens::default(),
        })
    }

    /// Adressen, von denen Umhang-Texturen geladen werden dürfen: die
    /// eingestellte API und die bekannten Adressen der echten API.
    pub(crate) fn trusted_bases(&self) -> Vec<&str> {
        let mut bases = vec![self.base.as_str()];
        bases.extend(KNOWN_BASES.iter().copied().filter(|b| *b != self.base));
        bases
    }

    pub async fn consent(&self) -> Option<Consent> {
        self.store.consent().await
    }

    pub async fn enabled(&self) -> bool {
        self.store.consent().await == Some(Consent::Accepted)
    }

    async fn ensure_enabled(&self) -> Result<()> {
        if self.enabled().await { Ok(()) } else { Err(disabled()) }
    }

    /// Präsenz außerplanmäßig senden (z. B. nach einem Account-Wechsel).
    pub fn presence_kick(&self) {
        self.presence.kick();
        self.live.kick();
    }

    pub async fn has_token(&self, account: &str) -> bool {
        self.store.token(account).await.is_some()
    }

    // --- HTTP ---------------------------------------------------------------------------

    /// Schickt eine Anfrage genau einmal. `Ok` = 2xx mit Body-Bytes (leer bei 204).
    async fn send_once(&self, req: &Req, token: Option<&str>) -> std::result::Result<Vec<u8>, Failure> {
        let url = format!("{}{}", self.base, req.path);
        let mut builder = self.http.request(req.method.clone(), &url).header("Accept", "application/json");
        if let Some(token) = token {
            builder = builder.bearer_auth(token);
        }
        builder = match &req.body {
            Body::Empty => builder,
            Body::Json(value) => builder.json(value),
            Body::Png(bytes) => builder.header("Content-Type", "image/png").body(bytes.clone()),
            Body::Image(mime, bytes) => builder.header("Content-Type", *mime).body(bytes.clone()),
        };
        let response = builder.send().await.map_err(|e| {
            tracing::debug!("TRS API nicht erreichbar ({} {}): {e}", req.method, req.path);
            Failure::Network
        })?;
        let status = response.status();
        let retry_after = response
            .headers()
            .get(reqwest::header::RETRY_AFTER)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.trim().parse::<u64>().ok());
        if response.content_length().is_some_and(|l| l > req.limit as u64) {
            return Err(Failure::Network);
        }
        let bytes = response.bytes().await.map_err(|_| Failure::Network)?;
        if bytes.len() > req.limit {
            return Err(Failure::Network);
        }
        if status.is_success() {
            return Ok(bytes.to_vec());
        }
        let parsed: Option<serde_json::Value> = serde_json::from_slice(&bytes).ok();
        let error = parsed.as_ref().and_then(|v| v.get("error"));
        let code = error
            .and_then(|e| e.get("code"))
            .and_then(|c| c.as_str())
            .filter(|c| c.len() <= 64 && c.bytes().all(|b| b.is_ascii_lowercase() || b == b'_'))
            .unwrap_or("")
            .to_owned();
        let retry_after = retry_after.or_else(|| error.and_then(|e| e.get("retryAfter")).and_then(serde_json::Value::as_u64));
        if code == "invalid_request"
            && let Some(fields) = error.and_then(|e| e.get("fields"))
        {
            tracing::warn!("TRS API lehnt {} {} ab: {fields}", req.method, req.path);
        }
        let current = (status.as_u16() == 409 && code == "stale")
            .then(|| error.and_then(|e| e.get("current")).cloned())
            .flatten();
        let detail = sanctions::SanctionDetail::parse(&code, error).map(Box::new);
        Err(Failure::Api { status: status.as_u16(), code, retry_after, current, detail })
    }

    /// Wie [`Self::send_once`], wartet aber kurze `429` einmal selbst ab.
    async fn send(&self, req: &Req, token: Option<&str>) -> std::result::Result<Vec<u8>, Failure> {
        match self.send_once(req, token).await {
            Err(Failure::Api { status: 429, retry_after: Some(secs), .. })
                if Duration::from_secs(secs) <= MAX_INLINE_RETRY =>
            {
                tokio::time::sleep(Duration::from_secs(secs.max(1))).await;
                self.send_once(req, token).await
            }
            other => other,
        }
    }

    /// Angemeldete Anfrage für `account`: holt/erneuert den Token, meldet sich
    /// bei `401` genau einmal neu an.
    pub(crate) async fn call_raw(&self, sessions: &dyn SessionSource, account: &str, req: &Req) -> Result<Vec<u8>> {
        match self.call_outcome(sessions, account, req).await? {
            Outcome::Done(bytes) => Ok(bytes),
            Outcome::Stale(_) => Err(api_error(409, "stale", None)),
        }
    }

    /// Wie [`Self::call_raw`], liefert bei `409 stale` aber den Stand des Servers.
    pub(crate) async fn call_outcome(&self, sessions: &dyn SessionSource, account: &str, req: &Req) -> Result<Outcome> {
        self.ensure_enabled().await?;
        let mut fresh_login = false;
        loop {
            let token = match self.store.token(account).await {
                Some(token) => token,
                None => {
                    fresh_login = true;
                    self.login(sessions, account).await?
                }
            };
            match self.send(req, Some(&token)).await {
                Ok(bytes) => return Ok(Outcome::Done(bytes)),
                Err(Failure::Api { status: 409, current: Some(current), .. }) => return Ok(Outcome::Stale(current)),
                Err(Failure::Api { status: 401, .. }) if !fresh_login => {
                    // Token abgelaufen oder widerrufen: einmal neu anmelden.
                    let _ = self.store.take_token(account).await;
                    fresh_login = true;
                    let _ = self.login(sessions, account).await?;
                }
                Err(Failure::Api { status: 401, .. }) => {
                    let _ = self.store.take_token(account).await;
                    return Err(auth_failed(crate::msg!("trs.loginRejected", "Die TRS-Anmeldung wurde abgelehnt – bitte später erneut versuchen.")));
                }
                Err(e) => return Err(e.into_error()),
            }
        }
    }

    pub(crate) async fn call<T: DeserializeOwned>(&self, sessions: &dyn SessionSource, account: &str, req: &Req) -> Result<T> {
        let bytes = self.call_raw(sessions, account, req).await?;
        parse(&bytes, &req.path)
    }

    // --- Anmeldung ----------------------------------------------------------------------

    /// Meldet `account` bei der API an und speichert den Token. Liefert ihn.
    pub(crate) async fn login(&self, sessions: &dyn SessionSource, account: &str) -> Result<String> {
        self.ensure_enabled().await?;
        let _guard = self.login_lock.lock().await;
        // Hat eine parallele Anfrage schon angemeldet?
        if let Some(token) = self.store.token(account).await {
            return Ok(token);
        }
        let session = sessions.session(account, false).await?.filter(|s| !s.demo).ok_or_else(no_account)?;

        let challenge: ApiChallenge = parse(
            &self.send(&Req::post("/v1/auth/challenge", serde_json::json!({})), None).await.map_err(Failure::into_error)?,
            "challenge",
        )?;
        if !validate::server_id(&challenge.server_id) {
            return Err(auth_failed(crate::msg!("trs.badChallenge", "Die TRS API hat eine ungültige Anmelde-Aufgabe geschickt.")));
        }

        let name = match self.mojang_join(&session, &challenge.server_id).await {
            Ok(()) => session.player_name.clone(),
            Err(JoinError::Rejected) => {
                // Minecraft-Token abgelaufen/widerrufen: einmal erneuern und neu versuchen.
                let renewed =
                    sessions.session(account, true).await?.filter(|s| !s.demo).ok_or_else(no_account)?;
                match self.mojang_join(&renewed, &challenge.server_id).await {
                    Ok(()) => renewed.player_name.clone(),
                    Err(e) => return Err(e.into_error()),
                }
            }
            Err(e) => return Err(e.into_error()),
        };

        let verify_req = Req::post("/v1/auth/verify", serde_json::json!({ "username": name, "serverId": challenge.server_id }));
        let verify: ApiVerify = match self.send(&verify_req, None).await {
            Ok(bytes) => parse(&bytes, "verify")?,
            Err(mut failure) => {
                // Gesperrt: Einspruch-Token merken (nur im Speicher, nie ans Webview).
                if let Failure::Api { code, detail: Some(detail), .. } = &mut failure
                    && code == "banned"
                    && let Some((token, expires)) = detail.appeal_token.take()
                {
                    self.appeal_tokens.put(account, token, expires);
                }
                return Err(failure.into_error());
            }
        };
        if !validate::session_token(&verify.token) {
            return Err(auth_failed(crate::msg!("trs.badToken", "Die TRS API hat einen ungültigen Token geschickt.")));
        }
        if verify.user.uuid != account {
            return Err(auth_failed(crate::msg!("trs.wrongAccount", "Die TRS-Anmeldung gehört zu einem anderen Account.")));
        }
        let expires_at = chrono::DateTime::parse_from_rfc3339(&verify.expires_at)
            .map(|d| d.with_timezone(&chrono::Utc))
            .unwrap_or_else(|_| chrono::Utc::now() + chrono::Duration::days(1));
        self.store.put_token(account, &verify.token, expires_at).await?;
        self.appeal_tokens.forget(account);
        tracing::info!("Bei der TRS API angemeldet: {}", verify.user.name);
        Ok(verify.token)
    }

    /// Mojang-Session-Join mit dem Minecraft-Token (wie beim Serverbeitritt).
    /// Die `serverId` geht unverändert raus – kein Hash, es gibt kein Geheimnis.
    async fn mojang_join(&self, session: &Session, server_id: &str) -> std::result::Result<(), JoinError> {
        let response = self
            .http
            .post(format!("{}/session/minecraft/join", self.session_base))
            .json(&serde_json::json!({
                "accessToken": session.access_token,
                "selectedProfile": session.uuid,
                "serverId": server_id,
            }))
            .send()
            .await
            .map_err(|_| JoinError::Network)?;
        match response.status().as_u16() {
            200..=299 => Ok(()),
            401 | 403 => Err(JoinError::Rejected),
            429 => Err(JoinError::RateLimited),
            status => {
                tracing::warn!("Mojang-Join fehlgeschlagen: HTTP {status}");
                Err(JoinError::Other)
            }
        }
    }

    /// Meldet den Account beim Server ab (nur der Token dieses Launchers) und
    /// vergisst ihn lokal. Fehler sind egal – der Token läuft sonst von selbst ab.
    pub(crate) async fn logout(&self, account: &str) {
        let Some(token) = self.store.take_token(account).await else { return };
        let req = Req::post_empty("/v1/auth/logout");
        let _ = tokio::time::timeout(Duration::from_secs(5), self.send_once(&req, Some(&token))).await;
    }

    /// Nimmt die Präsenz eines Accounts sofort zurück (ohne neu anzumelden).
    pub(crate) async fn send_offline(&self, account: &str) {
        let Some(token) = self.store.token(account).await else { return };
        // `via: launcher` nimmt nur die Meldung des Launchers zurück – die des Mods im Spiel bleibt.
        let req = Req::post("/v1/presence", serde_json::json!({ "state": "offline", "via": "launcher" }));
        let _ = tokio::time::timeout(Duration::from_secs(3), self.send_once(&req, Some(&token))).await;
    }
}

#[derive(Debug)]
enum JoinError {
    Network,
    Rejected,
    RateLimited,
    Other,
}

impl JoinError {
    fn into_error(self) -> Error {
        match self {
            Self::Network => offline(),
            Self::Rejected => trs_error(
                "trs_auth",
                "",
                crate::msg!(
                    "trs.mojangRejected",
                    "Mojang hat die Anmeldung abgelehnt – bitte melde deinen Account unter „Accounts“ neu an."
                ),
            ),
            Self::RateLimited => trs_error(
                "trs_rate_limited",
                "",
                crate::msg!(
                    "trs.mojangRateLimited",
                    "Mojang begrenzt gerade die Anmeldungen – bitte in einer Minute erneut versuchen."
                ),
            ),
            Self::Other => trs_error(
                "trs_auth",
                "",
                crate::msg!("trs.mojangFailed", "Die Mojang-Anmeldung ist fehlgeschlagen – bitte später erneut versuchen."),
            ),
        }
    }
}

fn parse<T: DeserializeOwned>(bytes: &[u8], context: &str) -> Result<T> {
    let bytes = if bytes.is_empty() { b"null".as_slice() } else { bytes };
    serde_json::from_slice(bytes).map_err(|e| {
        tracing::warn!("Unerwartete Antwort der TRS API ({context}): {e}");
        bad_response()
    })
}

/// `ApiMe` → Ansicht; kaputte Antworten werden zum Fehler.
pub(crate) fn me_view(me: ApiMe) -> Result<types::Me> {
    me.into_view().ok_or_else(bad_response)
}

#[cfg(test)]
pub(crate) mod testkit;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod sync_tests;
#[cfg(test)]
mod chat_tests;
#[cfg(test)]
mod hosting_tests;
#[cfg(test)]
mod sanctions_tests;
