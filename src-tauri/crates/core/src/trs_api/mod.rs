//! Anbindung an die TRS API (`api.theredstonee.de`, Vertrag: `api/API.md`).
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

mod ops;
pub mod png;
mod presence;
pub mod store;
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
use crate::{Error, Result, USER_AGENT};
pub use presence::PRESENCE_INTERVAL;
use presence::PresenceState;
pub use store::Consent;
use store::Store;
use types::{ApiChallenge, ApiMe, ApiVerify};

pub const DEFAULT_BASE: &str = "https://api.theredstonee.de";
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
}

/// Eine Anfrage an die API (Pfad ab `/v1/...`, Query schon kodiert angehängt).
#[derive(Debug, Clone)]
pub(crate) struct Req {
    pub method: Method,
    pub path: String,
    pub body: Body,
}

impl Req {
    pub fn get(path: impl Into<String>) -> Self {
        Self { method: Method::GET, path: path.into(), body: Body::Empty }
    }
    pub fn post(path: impl Into<String>, body: serde_json::Value) -> Self {
        Self { method: Method::POST, path: path.into(), body: Body::Json(body) }
    }
    pub fn post_empty(path: impl Into<String>) -> Self {
        Self { method: Method::POST, path: path.into(), body: Body::Empty }
    }
    pub fn put(path: impl Into<String>, body: serde_json::Value) -> Self {
        Self { method: Method::PUT, path: path.into(), body: Body::Json(body) }
    }
    pub fn patch(path: impl Into<String>, body: serde_json::Value) -> Self {
        Self { method: Method::PATCH, path: path.into(), body: Body::Json(body) }
    }
    pub fn delete(path: impl Into<String>) -> Self {
        Self { method: Method::DELETE, path: path.into(), body: Body::Empty }
    }
}

/// Warum eine Anfrage scheiterte (vor der Übersetzung in [`Error`]).
#[derive(Debug)]
pub(crate) enum Failure {
    Network,
    Api { status: u16, code: String, retry_after: Option<u64> },
}

impl Failure {
    fn into_error(self) -> Error {
        match self {
            Self::Network => offline(),
            Self::Api { status, code, retry_after } => api_error(status, &code, retry_after),
        }
    }
}

pub(crate) fn offline() -> Error {
    Error::TrsApi {
        kind: "trs_offline",
        code: String::new(),
        message: "Der TRS-Server ist gerade nicht erreichbar.".into(),
    }
}

pub(crate) fn disabled() -> Error {
    Error::TrsApi {
        kind: "trs_disabled",
        code: String::new(),
        message: "Die TRS-Dienste sind ausgeschaltet (Einstellungen → Datenschutz).".into(),
    }
}

pub(crate) fn no_account() -> Error {
    Error::TrsApi {
        kind: "trs_no_account",
        code: String::new(),
        message: "Bitte melde dich zuerst unter „Accounts“ mit deinem Minecraft-Konto an.".into(),
    }
}

fn auth_failed(message: &str) -> Error {
    Error::TrsApi { kind: "trs_auth", code: String::new(), message: message.into() }
}

/// API-Fehler → stabiler `kind` + deutsche Meldung.
pub(crate) fn api_error(status: u16, code: &str, retry_after: Option<u64>) -> Error {
    let (kind, message): (&'static str, String) = match (status, code) {
        (_, "banned") => ("trs_banned", "Dein Konto ist für die TRS-Dienste gesperrt.".into()),
        (429, _) => (
            "trs_rate_limited",
            format!("Zu viele Anfragen – bitte in {} s erneut versuchen.", retry_after.unwrap_or(60).max(1)),
        ),
        (401, "not_joined") => ("trs_auth", "Mojang hat die Anmeldung nicht bestätigt – bitte später erneut versuchen.".into()),
        (401, "invalid_challenge") => ("trs_auth", "Die Anmeldung hat zu lange gedauert – bitte erneut versuchen.".into()),
        (401, _) => ("trs_auth", "Die TRS-Anmeldung ist abgelaufen – bitte erneut versuchen.".into()),
        (403, "forbidden") => ("trs_forbidden", "Dafür fehlen dir die Rechte.".into()),
        (500..=599, _) => ("trs_offline", "Der TRS-Server ist gerade nicht erreichbar.".into()),
        (_, code) => ("trs_api", message_for(code).into()),
    };
    Error::TrsApi { kind, code: code.to_owned(), message }
}

fn message_for(code: &str) -> &'static str {
    match code {
        "cape_not_found" => "Diesen Umhang gibt es nicht (mehr).",
        "cape_locked" => "Dieser Umhang ist für dich noch nicht freigeschaltet.",
        "invalid_code" => "Dieser Code ist ungültig.",
        "code_expired" => "Dieser Code ist abgelaufen.",
        "code_used_up" => "Dieser Code wurde bereits zu oft eingelöst.",
        "player_not_found" => "Spieler nicht gefunden – er muss den TRS Launcher schon einmal benutzt haben.",
        "cannot_target_self" => "Das bist du selbst.",
        "blocked" => "Du hast diesen Spieler blockiert – entsperre ihn zuerst.",
        "already_friends" => "Ihr seid schon befreundet.",
        "already_requested" => "Du hast diesem Spieler schon eine Anfrage geschickt.",
        "too_many_requests" => "Du hast zu viele offene Anfragen (höchstens 50).",
        "target_inbox_full" => "Dieser Spieler hat zu viele offene Anfragen.",
        "friend_limit" => "Deine Freundesliste ist voll (höchstens 200).",
        "target_friend_limit" => "Die Freundesliste dieses Spielers ist voll.",
        "request_not_found" => "Diese Anfrage gibt es nicht mehr.",
        "friend_not_found" => "Ihr seid nicht (mehr) befreundet.",
        "block_not_found" => "Dieser Spieler ist nicht blockiert.",
        "invalid_png" => "Die Datei ist kein gültiges PNG-Bild.",
        "animated_png" => "Animierte PNGs sind für eigene Umhänge nicht erlaubt.",
        "invalid_dimensions" => "Das Bild hat nicht die richtige Größe für einen Umhang.",
        "empty_cape" => "Der Umhang ist komplett durchsichtig.",
        "too_many_pending" => "Du hast schon 3 Umhänge in Prüfung – warte auf die Freigabe.",
        "upload_limit" => "Du hast schon 10 eigene Umhänge.",
        "duplicate_cape" => "Diesen Umhang hast du schon hochgeladen.",
        "payload_too_large" => "Die Datei ist zu groß.",
        "unsupported_media_type" => "Dieses Dateiformat wird nicht unterstützt.",
        "user_not_found" => "Dieser Spieler hat die TRS-Dienste noch nie benutzt.",
        "builtin_cape" => "Standard-Umhänge können nicht gelöscht werden.",
        "cape_is_free" => "Für freie Umhänge braucht es keinen Code.",
        "grant_not_found" => "Dieser Spieler hat den Umhang nicht.",
        "cannot_ban_admin" => "Admins können nicht gesperrt werden.",
        "not_banned" => "Dieser Spieler ist nicht gesperrt.",
        "invalid_request" | "invalid_json" => "Die Anfrage war ungültig.",
        "not_found" => "Nicht gefunden.",
        _ => "Die TRS API hat die Anfrage abgelehnt.",
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
        Ok(Self {
            http,
            base: base.trim_end_matches('/').to_owned(),
            session_base: session_base.trim_end_matches('/').to_owned(),
            mojang_api: mojang_api.trim_end_matches('/').to_owned(),
            store: Store::new(paths.root().join("trs-api.json")),
            paths,
            login_lock: tokio::sync::Mutex::new(()),
            presence: Arc::new(PresenceState::default()),
        })
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
        if response.content_length().is_some_and(|l| l > MAX_JSON_BYTES as u64) {
            return Err(Failure::Network);
        }
        let bytes = response.bytes().await.map_err(|_| Failure::Network)?;
        if bytes.len() > MAX_JSON_BYTES {
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
        Err(Failure::Api { status: status.as_u16(), code, retry_after })
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
                Ok(bytes) => return Ok(bytes),
                Err(Failure::Api { status: 401, .. }) if !fresh_login => {
                    // Token abgelaufen oder widerrufen: einmal neu anmelden.
                    let _ = self.store.take_token(account).await;
                    fresh_login = true;
                    let _ = self.login(sessions, account).await?;
                }
                Err(Failure::Api { status: 401, .. }) => {
                    let _ = self.store.take_token(account).await;
                    return Err(auth_failed("Die TRS-Anmeldung wurde abgelehnt – bitte später erneut versuchen."));
                }
                Err(Failure::Api { status: 403, code, .. }) if code == "banned" => {
                    return Err(api_error(403, &code, None));
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
            return Err(auth_failed("Die TRS API hat eine ungültige Anmelde-Aufgabe geschickt."));
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

        let verify: ApiVerify = parse(
            &self
                .send(&Req::post("/v1/auth/verify", serde_json::json!({ "username": name, "serverId": challenge.server_id })), None)
                .await
                .map_err(Failure::into_error)?,
            "verify",
        )?;
        if !validate::session_token(&verify.token) {
            return Err(auth_failed("Die TRS API hat einen ungültigen Token geschickt."));
        }
        if verify.user.uuid != account {
            return Err(auth_failed("Die TRS-Anmeldung gehört zu einem anderen Account."));
        }
        let expires_at = chrono::DateTime::parse_from_rfc3339(&verify.expires_at)
            .map(|d| d.with_timezone(&chrono::Utc))
            .unwrap_or_else(|_| chrono::Utc::now() + chrono::Duration::days(1));
        self.store.put_token(account, &verify.token, expires_at).await?;
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
        let req = Req::post("/v1/presence", serde_json::json!({ "state": "offline" }));
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
            Self::Rejected => {
                auth_failed("Mojang hat die Anmeldung abgelehnt – bitte melde deinen Account unter „Accounts“ neu an.")
            }
            Self::RateLimited => Error::TrsApi {
                kind: "trs_rate_limited",
                code: String::new(),
                message: "Mojang begrenzt gerade die Anmeldungen – bitte in einer Minute erneut versuchen.".into(),
            },
            Self::Other => auth_failed("Die Mojang-Anmeldung ist fehlgeschlagen – bitte später erneut versuchen."),
        }
    }
}

fn parse<T: DeserializeOwned>(bytes: &[u8], context: &str) -> Result<T> {
    let bytes = if bytes.is_empty() { b"null".as_slice() } else { bytes };
    serde_json::from_slice(bytes).map_err(|e| {
        tracing::warn!("Unerwartete Antwort der TRS API ({context}): {e}");
        Error::TrsApi {
            kind: "trs_api",
            code: "bad_response".into(),
            message: "Die TRS API hat unerwartet geantwortet.".into(),
        }
    })
}

/// `ApiMe` → Ansicht; kaputte Antworten werden zum Fehler.
pub(crate) fn me_view(me: ApiMe) -> Result<types::Me> {
    me.into_view().ok_or_else(|| Error::TrsApi {
        kind: "trs_api",
        code: "bad_response".into(),
        message: "Die TRS API hat unerwartet geantwortet.".into(),
    })
}

#[cfg(test)]
pub(crate) mod testkit;
#[cfg(test)]
mod tests;
