//! Kostenloser Server beim Partner CraftingHosting (Vertrag: `docs/craftinghosting.md`, API.md §43).
//!
//! Der Launcher spricht nur mit der TRS API; der Partner-Schlüssel liegt dort. Je TRS-Konto
//! gibt es höchstens einen Server. Läuft eine Sitzung, schickt der Launcher alle 45 s einen
//! Heartbeat ([`Launcher::run_craftinghosting_heartbeat`]) und beim Beenden `stop`.

use std::sync::Mutex;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::sync::Notify;

use super::{Req, validate};
use crate::{Error, Launcher, Result};

/// Takt des Heartbeats (Server-Timeout: 180 s).
pub const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(45);
pub const NAME_MIN: usize = 3;
pub const NAME_MAX: usize = 22;
pub const COMMAND_MAX: usize = 256;
pub const ACCESS: [&str; 2] = ["friends", "open"];
pub const STATES: [&str; 7] = ["installing", "offline", "starting", "running", "stopping", "failed", "suspended"];
pub const STOP_REASONS: [&str; 4] = ["host_stopped", "host_timeout", "session_limit", "admin"];
const DEFAULT_EULA: &str = "https://www.minecraft.net/eula";

// --- Sitzung (Heartbeat) ----------------------------------------------------------------

/// Für welches Konto gerade eine Sitzung läuft (nur im Speicher).
#[derive(Debug, Default)]
pub(crate) struct SessionState {
    active: Mutex<Option<String>>,
    pub(crate) notify: Notify,
}

impl SessionState {
    pub(crate) fn active(&self) -> Option<String> {
        self.active.lock().ok().and_then(|a| a.clone())
    }

    /// Markiert die Sitzung; weckt die Schleife nur beim Wechsel.
    pub(crate) fn mark(&self, account: &str) {
        let changed = match self.active.lock() {
            Ok(mut a) if a.as_deref() != Some(account) => {
                *a = Some(account.to_owned());
                true
            }
            _ => false,
        };
        if changed {
            self.notify.notify_one();
        }
    }

    /// Nimmt die Markierung zurück. Liefert, ob eine bestand.
    pub(crate) fn clear(&self, account: Option<&str>) -> bool {
        let Ok(mut a) = self.active.lock() else { return false };
        if a.is_some() && account.is_none_or(|acc| a.as_deref() == Some(acc)) {
            *a = None;
            return true;
        }
        false
    }
}

// --- Eingaben ---------------------------------------------------------------------------

fn invalid(msg: crate::error::Msg) -> Error {
    Error::validation(msg)
}

/// Servername: 3–22 Zeichen aus Buchstaben, Ziffern, Leerzeichen und `. _ ' -`.
pub fn server_name(input: &str) -> Result<String> {
    let name = input.trim();
    let len = name.chars().count();
    let ok = (NAME_MIN..=NAME_MAX).contains(&len)
        && name.chars().all(|c| c.is_alphanumeric() || matches!(c, ' ' | '.' | '_' | '\'' | '-'));
    if ok {
        Ok(name.to_owned())
    } else {
        Err(invalid(crate::msg!(
            "craftinghosting.invalidName",
            "Name: 3 bis 22 Zeichen – Buchstaben, Ziffern, Leerzeichen und . _ ' -"
        )))
    }
}

pub fn access(input: &str) -> Result<&str> {
    ACCESS
        .iter()
        .copied()
        .find(|a| *a == input)
        .ok_or_else(|| invalid(crate::msg!("craftinghosting.invalidAccess", "Ungültiger Zugang.")))
}

fn type_id(input: &str) -> bool {
    (1..=32).contains(&input.len()) && input.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'_' | b'-'))
}

fn version_id(input: &str) -> bool {
    (1..=32).contains(&input.len()) && input.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-' | b'+'))
}

/// Konsolenbefehl: ein führendes `/` fällt weg, 1–256 Zeichen, keine Steuerzeichen.
pub fn command(input: &str) -> Result<String> {
    let trimmed = input.trim();
    let cmd = trimmed.strip_prefix('/').unwrap_or(trimmed).trim();
    let len = cmd.chars().count();
    if len == 0 || len > COMMAND_MAX || cmd.chars().any(char::is_control) {
        return Err(invalid(crate::msg!(
            "craftinghosting.invalidCommand",
            "Befehl: 1 bis 256 Zeichen in einer Zeile."
        )));
    }
    Ok(cmd.to_owned())
}

/// Server-Adresse `host[:port]` (sonst keine).
fn address(input: Option<String>) -> Option<String> {
    let a = input?.trim().to_owned();
    let (host, port) = match a.rsplit_once(':') {
        Some((h, p)) => (h, Some(p)),
        None => (a.as_str(), None),
    };
    let host_ok = (1..=253).contains(&host.len())
        && host.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-'))
        && !host.starts_with(['.', '-']);
    let port_ok = port.is_none_or(|p| p.parse::<u16>().is_ok_and(|n| n > 0));
    (host_ok && port_ok).then_some(a)
}

fn time(value: Option<String>) -> Option<String> {
    value.map(|t| validate::text(t.trim(), 40)).filter(|t| !t.is_empty())
}

fn https_url(input: &str) -> Option<String> {
    let url = reqwest::Url::parse(input.trim()).ok()?;
    (url.scheme() == "https" && url.host_str().is_some() && input.len() <= 300).then(|| url.to_string())
}

/// Details zu `cooldown`/`not_running` als Parameter der Fehlermeldung.
pub(crate) fn error_params(code: &str, error: Option<&serde_json::Value>) -> Vec<(&'static str, String)> {
    let details = error.and_then(|e| e.get("details"));
    let get = |key: &str| details.and_then(|d| d.get(key)).and_then(serde_json::Value::as_str);
    match code {
        "cooldown" => get("cooldownUntil")
            .and_then(|t| validate::iso_datetime(t).ok())
            .map(|t| vec![("cooldownUntil", t)])
            .unwrap_or_default(),
        "not_running" => {
            get("reason").filter(|r| STOP_REASONS.contains(r)).map(|r| vec![("reason", r.to_owned())]).unwrap_or_default()
        }
        _ => Vec::new(),
    }
}

/// Fehlercodes, die anderswo etwas anderes heißen.
fn ch_error(e: Error) -> Error {
    match e {
        Error::TrsApi { kind: "trs_api", code, .. } if code == "invalid_name" => Error::TrsApi {
            kind: "trs_api",
            msg: crate::msg!("trsApi.server_name_invalid", "Dieser Servername geht nicht – bitte einen anderen wählen."),
            code,
        },
        other => other,
    }
}

fn is_code(e: &Error, codes: &[&str]) -> bool {
    matches!(e, Error::TrsApi { code, .. } if codes.contains(&code.as_str()))
}

// --- Ansichten --------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Plan {
    pub name: String,
    pub memory_mb: u32,
    pub cpu_percent: u32,
    pub disk_mb: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerType {
    pub id: String,
    pub name: String,
    pub versions: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Limits {
    pub max_servers_per_player: u32,
    pub session_max_hours: u32,
    pub cooldown_hours: u32,
    pub heartbeat_timeout_seconds: u32,
    pub delete_after_idle_days: u32,
    pub server_name_max_length: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Info {
    pub plan: Plan,
    pub server_types: Vec<ServerType>,
    pub limits: Limits,
    pub eula_url: String,
    pub full: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Resources {
    pub memory_mb: u32,
    pub cpu_percent: u32,
    pub disk_mb: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionView {
    pub active: bool,
    pub started_at: Option<String>,
    pub last_heartbeat_at: Option<String>,
    pub heartbeat_timeout_seconds: u32,
    pub run_seconds: u64,
    pub max_run_seconds: u64,
    pub remaining_seconds: u64,
    pub cooldown_until: Option<String>,
    pub last_stop_reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Server {
    pub id: String,
    pub name: String,
    pub player_id: String,
    pub state: String,
    pub address: Option<String>,
    pub server_type: String,
    pub version: String,
    pub resources: Resources,
    pub eula_accepted: bool,
    pub access: String,
    pub session: SessionView,
    pub created_at: Option<String>,
    pub last_start_at: Option<String>,
}

// --- Draht ------------------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiPlan {
    name: String,
    memory_mb: u32,
    cpu_percent: u32,
    disk_mb: u32,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiServerType {
    id: String,
    name: String,
    versions: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiLimits {
    max_servers_per_player: u32,
    session_max_hours: u32,
    cooldown_hours: u32,
    heartbeat_timeout_seconds: u32,
    delete_after_idle_days: u32,
    server_name_max_length: u32,
}

impl Default for ApiLimits {
    fn default() -> Self {
        Self {
            max_servers_per_player: 1,
            session_max_hours: 24,
            cooldown_hours: 2,
            heartbeat_timeout_seconds: 180,
            delete_after_idle_days: 30,
            server_name_max_length: NAME_MAX as u32,
        }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiInfo {
    plan: ApiPlan,
    server_types: Vec<ApiServerType>,
    limits: ApiLimits,
    eula_url: String,
    full: bool,
}

impl ApiInfo {
    fn cleaned(self) -> Info {
        let server_types = self
            .server_types
            .into_iter()
            .filter(|t| type_id(&t.id))
            .map(|t| {
                let mut versions: Vec<String> = t.versions.into_iter().filter(|v| version_id(v)).take(200).collect();
                versions.dedup();
                let name = validate::text(t.name.trim(), 40);
                ServerType { name: if name.is_empty() { t.id.clone() } else { name }, id: t.id, versions }
            })
            .filter(|t| !t.versions.is_empty())
            .take(30)
            .collect();
        let l = self.limits;
        Info {
            plan: Plan {
                name: validate::text(self.plan.name.trim(), 40),
                memory_mb: self.plan.memory_mb,
                cpu_percent: self.plan.cpu_percent,
                disk_mb: self.plan.disk_mb,
            },
            server_types,
            limits: Limits {
                max_servers_per_player: l.max_servers_per_player,
                session_max_hours: l.session_max_hours,
                cooldown_hours: l.cooldown_hours,
                heartbeat_timeout_seconds: l.heartbeat_timeout_seconds,
                delete_after_idle_days: l.delete_after_idle_days,
                server_name_max_length: l.server_name_max_length.clamp(NAME_MIN as u32, NAME_MAX as u32),
            },
            eula_url: https_url(&self.eula_url).unwrap_or_else(|| DEFAULT_EULA.to_owned()),
            full: self.full,
        }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiResources {
    memory_mb: u32,
    cpu_percent: u32,
    disk_mb: u32,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiSession {
    active: bool,
    started_at: Option<String>,
    last_heartbeat_at: Option<String>,
    heartbeat_timeout_seconds: u32,
    run_seconds: u64,
    max_run_seconds: u64,
    remaining_seconds: u64,
    cooldown_until: Option<String>,
    last_stop_reason: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub(crate) struct ApiServer {
    id: String,
    name: String,
    player_id: String,
    state: String,
    address: Option<String>,
    server_type: String,
    version: String,
    resources: ApiResources,
    eula_accepted: bool,
    access: String,
    session: ApiSession,
    created_at: Option<String>,
    last_start_at: Option<String>,
}

impl ApiServer {
    fn cleaned(self) -> Option<Server> {
        let id_ok = (1..=64).contains(&self.id.len()) && self.id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-');
        if !id_ok || !type_id(&self.server_type) || !version_id(&self.version) {
            return None;
        }
        let name = validate::text(self.name.trim(), NAME_MAX);
        let player_id = validate::text(self.player_id.trim(), 16);
        let state = if STATES.contains(&self.state.as_str()) { self.state } else { "offline".to_owned() };
        let access = if ACCESS.contains(&self.access.as_str()) { self.access } else { "friends".to_owned() };
        let s = self.session;
        Some(Server {
            name: if name.is_empty() { self.id.clone() } else { name },
            id: self.id,
            player_id,
            state,
            address: address(self.address),
            server_type: self.server_type,
            version: self.version,
            resources: Resources {
                memory_mb: self.resources.memory_mb,
                cpu_percent: self.resources.cpu_percent,
                disk_mb: self.resources.disk_mb,
            },
            eula_accepted: self.eula_accepted,
            access,
            session: SessionView {
                active: s.active,
                started_at: time(s.started_at),
                last_heartbeat_at: time(s.last_heartbeat_at),
                heartbeat_timeout_seconds: s.heartbeat_timeout_seconds,
                run_seconds: s.run_seconds,
                max_run_seconds: s.max_run_seconds,
                remaining_seconds: s.remaining_seconds,
                cooldown_until: time(s.cooldown_until),
                last_stop_reason: s.last_stop_reason.filter(|r| STOP_REASONS.contains(&r.as_str())),
            },
            created_at: time(self.created_at),
            last_start_at: time(self.last_start_at),
        })
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiEnvelope {
    server: Option<ApiServer>,
}

/// Neuer Server.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewServer {
    pub name: String,
    pub server_type: String,
    pub version: String,
    pub access: String,
}

impl NewServer {
    fn body(&self) -> Result<serde_json::Value> {
        let name = server_name(&self.name)?;
        let access = access(&self.access)?;
        if !type_id(&self.server_type) || !version_id(&self.version) {
            return Err(invalid(crate::msg!("craftinghosting.invalidType", "Bitte Servertyp und Version wählen.")));
        }
        Ok(json!({ "name": name, "serverType": self.server_type, "version": self.version, "access": access }))
    }
}

// --- Operationen ------------------------------------------------------------------------

const BASE: &str = "/v1/craftinghosting";

impl Launcher {
    /// Anfrage mit dem aktiven Konto; `not_running`/`no_server` beenden die Sitzung.
    async fn ch_send(&self, req: Req) -> Result<(String, Vec<u8>)> {
        let account = self.trs_account().await?;
        match self.trs.call_raw(self.accounts(), &account, &req).await {
            Ok(bytes) => Ok((account, bytes)),
            Err(e) => {
                if is_code(&e, &["not_running", "no_server"]) {
                    self.trs.craftinghosting.clear(Some(&account));
                }
                Err(ch_error(e))
            }
        }
    }

    /// Server-Antwort lesen und die Sitzungs-Markierung nachziehen.
    async fn ch_server(&self, req: Req) -> Result<Option<Server>> {
        let path = req.path.clone();
        let (account, bytes) = self.ch_send(req).await?;
        let envelope: ApiEnvelope = super::parse(&bytes, &path)?;
        let server = match envelope.server {
            Some(raw) => Some(raw.cleaned().ok_or_else(super::bad_response)?),
            None => None,
        };
        self.ch_observe(&account, server.as_ref());
        Ok(server)
    }

    fn ch_observe(&self, account: &str, server: Option<&Server>) {
        if server.is_some_and(|s| s.session.active) {
            self.trs.craftinghosting.mark(account);
        } else {
            self.trs.craftinghosting.clear(Some(account));
        }
    }

    async fn ch_required(&self, req: Req) -> Result<Server> {
        self.ch_server(req).await?.ok_or_else(super::bad_response)
    }

    pub async fn craftinghosting_info(&self) -> Result<Info> {
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/info"))).await?;
        let info: ApiInfo = super::parse(&bytes, "craftinghosting/info")?;
        Ok(info.cleaned())
    }

    /// Eigener Server oder `None`.
    pub async fn craftinghosting_server(&self) -> Result<Option<Server>> {
        self.ch_server(Req::get(format!("{BASE}/server"))).await
    }

    pub async fn craftinghosting_create(&self, spec: &NewServer) -> Result<Server> {
        let body = spec.body()?;
        self.ch_required(Req::post(format!("{BASE}/server"), body)).await
    }

    pub async fn craftinghosting_set_access(&self, value: &str) -> Result<Server> {
        let value = access(value)?;
        self.ch_required(Req::patch(format!("{BASE}/server"), json!({ "access": value }))).await
    }

    pub async fn craftinghosting_start(&self, accept_eula: bool) -> Result<Server> {
        let server = self.ch_required(Req::post(format!("{BASE}/server/start"), json!({ "acceptEula": accept_eula }))).await?;
        // Ab jetzt Heartbeats, auch wenn die Antwort die Sitzung noch nicht zeigt.
        if let Ok(account) = self.trs_account().await {
            self.trs.craftinghosting.mark(&account);
        }
        Ok(server)
    }

    pub async fn craftinghosting_stop(&self) -> Result<Server> {
        let server = self.ch_required(Req::post_empty(format!("{BASE}/server/stop"))).await?;
        self.trs.craftinghosting.clear(None);
        Ok(server)
    }

    pub async fn craftinghosting_command(&self, input: &str) -> Result<()> {
        let cmd = command(input)?;
        self.ch_send(Req::post(format!("{BASE}/server/command"), json!({ "command": cmd }))).await.map(|_| ())
    }

    pub async fn craftinghosting_delete(&self) -> Result<()> {
        match self.ch_send(Req::delete(format!("{BASE}/server"))).await {
            // Schon weg – Ziel erreicht.
            Err(e) if is_code(&e, &["no_server"]) => {}
            other => {
                other?;
            }
        }
        self.trs.craftinghosting.clear(None);
        Ok(())
    }

    /// Hintergrund-Schleife: Heartbeat alle 45 s, solange eine Sitzung läuft.
    /// `on_end` meldet, dass die Sitzung vorbei ist (Oberfläche lädt neu).
    pub async fn run_craftinghosting_heartbeat(self: std::sync::Arc<Self>, on_end: std::sync::Arc<dyn Fn() + Send + Sync>) {
        loop {
            tokio::select! {
                () = tokio::time::sleep(HEARTBEAT_INTERVAL) => {}
                () = self.trs.craftinghosting.notify.notified() => {}
            }
            if self.craftinghosting_heartbeat().await {
                on_end();
            }
        }
    }

    /// Ein Heartbeat, falls eine Sitzung des aktiven Kontos läuft. Liefert `true`, wenn sie endete.
    pub(crate) async fn craftinghosting_heartbeat(&self) -> bool {
        let state = &self.trs.craftinghosting;
        let Some(account) = state.active() else { return false };
        let active = self.accounts().active_id().await.ok().flatten();
        if !self.trs.enabled().await || active.as_deref() != Some(account.as_str()) {
            return state.clear(Some(&account));
        }
        let req = Req::post_empty(format!("{BASE}/server/heartbeat"));
        match self.trs.call_raw(self.accounts(), &account, &req).await {
            Ok(bytes) => {
                let server = super::parse::<ApiEnvelope>(&bytes, "craftinghosting/heartbeat")
                    .ok()
                    .and_then(|e| e.server)
                    .and_then(ApiServer::cleaned);
                match server {
                    Some(s) if !s.session.active => state.clear(Some(&account)),
                    _ => false,
                }
            }
            Err(e) if is_code(&e, &["not_running", "no_server"]) => state.clear(Some(&account)),
            Err(e) => {
                tracing::debug!("CraftingHosting-Heartbeat nicht gesendet: {e}");
                false
            }
        }
    }

    /// Beim Beenden: laufende Sitzung stoppen (kurzer Timeout, Fehler egal).
    pub async fn craftinghosting_shutdown(&self) {
        let Some(account) = self.trs.craftinghosting.active() else { return };
        if !self.trs.enabled().await {
            return;
        }
        let req = Req::post_empty(format!("{BASE}/server/stop")).timeout(Duration::from_secs(3));
        let stop = self.trs.call_raw(self.accounts(), &account, &req);
        if tokio::time::timeout(Duration::from_secs(3), stop).await.is_ok() {
            self.trs.craftinghosting.clear(Some(&account));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names() {
        assert_eq!(server_name("  Meine Welt ").unwrap(), "Meine Welt");
        assert_eq!(server_name("Bob's_SMP-2.0").unwrap(), "Bob's_SMP-2.0");
        assert!(server_name("Grüne Insel").is_ok());
        assert!(server_name("ab").is_err());
        assert!(server_name(&"x".repeat(23)).is_err());
        assert!(server_name("SMP #1").is_err());
        assert!(server_name("a\nb c").is_err());
    }

    #[test]
    fn commands() {
        assert_eq!(command("/say hi").unwrap(), "say hi");
        assert_eq!(command("  time set day ").unwrap(), "time set day");
        assert_eq!(command("//wand").unwrap(), "/wand");
        assert!(command("/").is_err());
        assert!(command("say a\nop me").is_err());
        assert!(command("say \u{7}").is_err());
        assert!(command(&"a".repeat(257)).is_err());
        assert!(command(&"a".repeat(256)).is_ok());
    }

    #[test]
    fn addresses() {
        assert_eq!(address(Some("node1.example.com:25568".into())).as_deref(), Some("node1.example.com:25568"));
        assert_eq!(address(Some("play.example.com".into())).as_deref(), Some("play.example.com"));
        assert_eq!(address(Some("evil.com:99999".into())), None);
        assert_eq!(address(Some("a b:1".into())), None);
        assert_eq!(address(None), None);
    }

    #[test]
    fn error_details() {
        let e = json!({ "code": "cooldown", "details": { "cooldownUntil": "2026-11-18T18:00:00Z" } });
        assert_eq!(error_params("cooldown", Some(&e)), vec![("cooldownUntil", "2026-11-18T18:00:00.000Z".to_owned())]);
        let e = json!({ "code": "not_running", "details": { "reason": "<script>" } });
        assert!(error_params("not_running", Some(&e)).is_empty());
        let e = json!({ "code": "not_running", "details": { "reason": "host_timeout" } });
        assert_eq!(error_params("not_running", Some(&e)), vec![("reason", "host_timeout".to_owned())]);
    }

    #[test]
    fn session_marks() {
        let s = SessionState::default();
        s.mark("a");
        assert_eq!(s.active().as_deref(), Some("a"));
        assert!(!s.clear(Some("b")));
        assert!(s.clear(Some("a")));
        assert!(!s.clear(None));
    }
}
