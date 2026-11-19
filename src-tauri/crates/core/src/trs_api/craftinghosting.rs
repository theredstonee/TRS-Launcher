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
pub const SUBDOMAIN_MIN: usize = 3;
pub const SUBDOMAIN_MAX: usize = 24;
pub const DOMAIN_SUFFIX: &str = ".trs-launcher.de";
const SUBDOMAIN_REASONS: [&str; 3] = ["invalid", "reserved", "taken"];
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

fn subdomain_ok(name: &str) -> bool {
    (SUBDOMAIN_MIN..=SUBDOMAIN_MAX).contains(&name.len())
        && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        && !name.starts_with('-')
        && !name.ends_with('-')
}

/// Eigene Adresse `<name>.trs-launcher.de`: 3–24 Zeichen `a-z 0-9 -`, kein `-` am Rand.
pub fn subdomain(input: &str) -> Result<String> {
    let name = input.trim().to_ascii_lowercase();
    if subdomain_ok(&name) {
        Ok(name)
    } else {
        Err(invalid(crate::msg!(
            "craftinghosting.invalidSubdomain",
            "Adresse: 3 bis 24 Zeichen – a–z, 0–9 und -, kein - am Anfang oder Ende."
        )))
    }
}

/// `domain` aus der API, nur in der Form `<name>.trs-launcher.de`.
fn domain(input: Option<String>) -> Option<String> {
    let d = input?.trim().to_ascii_lowercase();
    let name = d.strip_suffix(DOMAIN_SUFFIX)?;
    (name.len() >= SUBDOMAIN_MIN && name.len() <= SUBDOMAIN_MAX && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-'))
        .then_some(d)
}

/// Node-IP `a.b.c.d:port` (nur IPv4 mit Port).
fn ip(input: Option<String>) -> Option<String> {
    let a = input?.trim().to_owned();
    let (host, port) = a.split_once(':')?;
    let ok = host.parse::<std::net::Ipv4Addr>().is_ok() && port.len() <= 5 && port.parse::<u16>().is_ok_and(|p| p > 0);
    ok.then_some(a)
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
        "hosting_maintenance" => get("message").map(|m| validate::text(m.trim(), 500)).filter(|m| !m.is_empty()).map(|m| vec![("message", m)]).unwrap_or_default(),
        "server_blocked" => get("reason").map(|r| validate::text(r.trim(), 300)).filter(|r| !r.is_empty()).map(|r| vec![("reason", r)]).unwrap_or_default(),
        "feature_disabled" => get("feature")
            .filter(|f| (1..=32).contains(&f.len()) && f.bytes().all(|b| b.is_ascii_alphanumeric()))
            .map(|f| vec![("feature", f.to_owned())])
            .unwrap_or_default(),
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
    /// Welche Bereiche der Launcher zeigt – schaltet die TRS API, ohne Launcher-Update.
    pub features: Features,
    /// Hinweis oben im Hosting-Reiter (Markdown, nur bereinigt anzeigen) oder `None`.
    pub notice: Option<Notice>,
    /// Wartung durch das TRS-Team: Anlegen, Starten und Beitreten sind dann aus.
    pub maintenance: Maintenance,
    /// Laufzeit-Regeln der TRS API (§43.17), `None` = aus.
    pub rules: Option<Rules>,
    /// Regel-Kacheln als fertige Texte der API (§43.18); das Frontend prüft den Aufbau.
    pub tiles: Option<serde_json::Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Rules {
    pub budget_hours: u32,
    pub cooldown_hours: u32,
    pub idle_reset_days: u32,
    pub weekly_offline_hours: u32,
    pub warn_minutes: u32,
}

/// Laufzeit-Kontingent eines Servers (§43.17).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Quota {
    pub used_seconds: u64,
    pub budget_seconds: u64,
    pub remaining_seconds: u64,
    pub week_used_seconds: u64,
    pub week_cap_seconds: u64,
    pub week_resets_at: Option<String>,
    pub idle_reset_at: Option<String>,
    pub cooldown_until: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Notice {
    pub markdown: String,
    /// `info`, `warning` oder `success`.
    pub level: String,
    pub updated_at: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct Maintenance {
    pub active: bool,
    pub message: Option<String>,
}

const NOTICE_LEVELS: [&str; 3] = ["info", "warning", "success"];

/// Hinweis: Objekt `{ markdown, level, updatedAt }` oder (alte API) Klartext.
fn notice(raw: &serde_json::Value) -> Option<Notice> {
    let text = |v: Option<&serde_json::Value>, max: usize| {
        v.and_then(serde_json::Value::as_str).map(|s| validate::text(s.trim(), max)).filter(|s| !s.is_empty())
    };
    match raw {
        serde_json::Value::String(_) => text(Some(raw), NOTICE_MAX).map(|markdown| Notice { markdown, level: "info".into(), updated_at: None }),
        serde_json::Value::Object(o) => {
            let markdown = text(o.get("markdown"), NOTICE_MAX)?;
            let level = o.get("level").and_then(serde_json::Value::as_str).filter(|l| NOTICE_LEVELS.contains(l)).unwrap_or("info");
            Some(Notice { markdown, level: level.into(), updated_at: time(text(o.get("updatedAt"), 40)) })
        }
        _ => None,
    }
}

fn maintenance(raw: &serde_json::Value) -> Maintenance {
    let active = raw.get("active").and_then(serde_json::Value::as_bool).unwrap_or(false);
    let message = raw.get("message").and_then(serde_json::Value::as_str).map(|m| validate::text(m.trim(), 500)).filter(|m| !m.is_empty());
    Maintenance { active, message }
}

/// Funktions-Schalter aus `info.features`; fehlende oder unbekannte Schlüssel = aus.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Features {
    pub files: bool,
    pub properties: bool,
    pub addons: bool,
    pub worlds: bool,
    pub world_download: bool,
    pub own_address: bool,
    pub wake_on_join: bool,
    pub version_change: bool,
    pub backups: bool,
    pub create: bool,
    pub console: bool,
}

pub const NOTICE_MAX: usize = 4000;

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
    /// `false` = der Launcher meldet sich nicht mehr, der Server läuft nur wegen Spielern weiter.
    pub host_present: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Server {
    pub id: String,
    pub name: String,
    pub player_id: String,
    pub state: String,
    pub address: Option<String>,
    /// Eigene Adresse `<name>.trs-launcher.de` (`None` = noch keine).
    pub domain: Option<String>,
    /// Node als `IPv4:Port`.
    pub ip: Option<String>,
    pub server_type: String,
    pub version: String,
    pub resources: Resources,
    pub eula_accepted: bool,
    pub access: String,
    pub session: SessionView,
    pub created_at: Option<String>,
    pub last_start_at: Option<String>,
    /// Ohne Start bis dahin wird der Server gelöscht.
    pub delete_at: Option<String>,
    /// Ersteinrichtung der API: `pending`, `restarting` (Server startet einmal neu) oder `done`.
    pub setup: String,
    /// Vom TRS-Team gesperrt (Starten/Beitreten aus).
    pub blocked: Option<Blocked>,
    /// Laufzeit-Kontingent nach den Regeln der TRS API.
    pub quota: Option<Quota>,
    /// Fertige Anzeige der API (§43.18); das Frontend prüft den Aufbau.
    pub display: Option<serde_json::Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Blocked {
    pub reason: String,
    pub since: Option<String>,
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
    /// Nicht-boolesche Werte zählen als aus.
    features: serde_json::Map<String, serde_json::Value>,
    notice: serde_json::Value,
    maintenance: serde_json::Value,
    rules: serde_json::Value,
    tiles: serde_json::Value,
}

/// Fremdes JSON nur in vernünftiger Größe weiterreichen.
pub(crate) fn passthrough(raw: serde_json::Value, array: bool) -> Option<serde_json::Value> {
    let kind_ok = if array { raw.is_array() } else { raw.is_object() };
    (kind_ok && serde_json::to_vec(&raw).is_ok_and(|b| b.len() <= 32 * 1024)).then_some(raw)
}

pub(crate) fn rules(raw: &serde_json::Value) -> Option<Rules> {
    let o = raw.as_object()?;
    let n = |key: &str, max: u64| o.get(key).and_then(serde_json::Value::as_u64).map(|v| v.min(max) as u32);
    Some(Rules {
        budget_hours: n("budgetHours", 168).filter(|h| *h > 0)?,
        cooldown_hours: n("cooldownHours", 48).unwrap_or(0),
        idle_reset_days: n("idleResetDays", 30).unwrap_or(0),
        weekly_offline_hours: n("weeklyOfflineHours", 48).unwrap_or(0),
        warn_minutes: n("warnMinutes", 120).unwrap_or(0),
    })
}

pub(crate) fn quota(raw: &serde_json::Value) -> Option<Quota> {
    let o = raw.as_object()?;
    let n = |key: &str| o.get(key).and_then(serde_json::Value::as_u64).unwrap_or(0);
    let t = |key: &str| {
        o.get(key)
            .and_then(serde_json::Value::as_str)
            .filter(|v| v.len() <= 40 && chrono::DateTime::parse_from_rfc3339(v).is_ok())
            .map(str::to_owned)
    };
    let budget = n("budgetSeconds");
    if budget == 0 {
        return None;
    }
    Some(Quota {
        used_seconds: n("usedSeconds"),
        budget_seconds: budget,
        remaining_seconds: n("remainingSeconds").min(budget),
        week_used_seconds: n("weekUsedSeconds"),
        week_cap_seconds: n("weekCapSeconds"),
        week_resets_at: t("weekResetsAt"),
        idle_reset_at: t("idleResetAt"),
        cooldown_until: t("cooldownUntil"),
    })
}

fn features(raw: &serde_json::Map<String, serde_json::Value>) -> Features {
    let on = |key: &str| raw.get(key).and_then(serde_json::Value::as_bool).unwrap_or(false);
    Features {
        files: on("files"),
        properties: on("properties"),
        addons: on("addons"),
        worlds: on("worlds"),
        world_download: on("worldDownload"),
        own_address: on("ownAddress"),
        wake_on_join: on("wakeOnJoin"),
        version_change: on("versionChange"),
        backups: on("backups"),
        create: on("create"),
        console: on("console"),
    }
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
            features: features(&self.features),
            notice: notice(&self.notice),
            maintenance: maintenance(&self.maintenance),
            rules: rules(&self.rules),
            tiles: passthrough(self.tiles, true),
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
    host_present: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub(crate) struct ApiServer {
    id: String,
    name: String,
    player_id: String,
    state: String,
    address: Option<String>,
    domain: Option<String>,
    ip: Option<String>,
    server_type: String,
    version: String,
    resources: ApiResources,
    eula_accepted: bool,
    access: String,
    session: ApiSession,
    created_at: Option<String>,
    last_start_at: Option<String>,
    delete_at: Option<String>,
    setup: String,
    blocked: Option<serde_json::Value>,
    quota: serde_json::Value,
    display: serde_json::Value,
}

impl ApiServer {
    pub(crate) fn cleaned(self) -> Option<Server> {
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
            domain: domain(self.domain),
            ip: ip(self.ip),
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
                host_present: s.host_present.unwrap_or(true),
            },
            created_at: time(self.created_at),
            last_start_at: time(self.last_start_at),
            delete_at: time(self.delete_at),
            setup: match self.setup.as_str() {
                "pending" | "restarting" => self.setup,
                _ => "done".into(),
            },
            blocked: self.blocked.filter(serde_json::Value::is_object).map(|b| Blocked {
                reason: b.get("reason").and_then(serde_json::Value::as_str).map(|r| validate::text(r.trim(), 300)).unwrap_or_default(),
                since: time(b.get("since").and_then(serde_json::Value::as_str).map(str::to_owned)),
            }),
            quota: quota(&self.quota),
            display: passthrough(self.display, false),
        })
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
pub(crate) struct ApiEnvelope {
    pub(crate) server: Option<ApiServer>,
}

/// Neuer Server.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewServer {
    pub name: String,
    pub server_type: String,
    pub version: String,
    pub access: String,
    /// Wunsch-Adresse; ohne leitet die API eine aus dem Namen ab.
    #[serde(default)]
    pub subdomain: Option<String>,
    /// Minecraft-EULA im Assistenten bestätigt.
    #[serde(default)]
    pub accept_eula: bool,
}

impl NewServer {
    pub(crate) fn body(&self) -> Result<serde_json::Value> {
        let name = server_name(&self.name)?;
        let access = access(&self.access)?;
        if !type_id(&self.server_type) || !version_id(&self.version) {
            return Err(invalid(crate::msg!("craftinghosting.invalidType", "Bitte Servertyp und Version wählen.")));
        }
        let mut body = json!({ "name": name, "serverType": self.server_type, "version": self.version, "access": access });
        if let Some(sub) = self.subdomain.as_deref().filter(|s| !s.trim().is_empty()) {
            body["subdomain"] = subdomain(sub)?.into();
        }
        if self.accept_eula {
            body["acceptEula"] = true.into();
        }
        Ok(body)
    }
}

/// Antwort der Adress-Prüfung.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SubdomainCheck {
    pub name: String,
    pub available: bool,
    /// `invalid`, `reserved`, `taken` oder `None`.
    pub reason: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiSubdomainCheck {
    name: String,
    available: bool,
    reason: Option<String>,
}

// --- Operationen ------------------------------------------------------------------------

pub(crate) const BASE: &str = "/v1/craftinghosting";

impl Launcher {
    /// Anfrage mit dem aktiven Konto; `not_running`/`no_server` beenden die Sitzung.
    pub(crate) async fn ch_send(&self, req: Req) -> Result<(String, Vec<u8>)> {
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
    pub(crate) async fn ch_server(&self, req: Req) -> Result<Option<Server>> {
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
        if server.is_some_and(|s| s.session.active || s.setup == "restarting") {
            self.trs.craftinghosting.mark(account);
        } else {
            self.trs.craftinghosting.clear(Some(account));
        }
    }

    pub(crate) async fn ch_required(&self, req: Req) -> Result<Server> {
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

    pub async fn craftinghosting_rename(&self, name: &str) -> Result<Server> {
        let name = server_name(name)?;
        self.ch_required(Req::patch(format!("{BASE}/server"), json!({ "name": name }))).await
    }

    /// Ist `<name>.trs-launcher.de` frei? Ungültige Namen fragen gar nicht erst.
    pub async fn craftinghosting_check_subdomain(&self, input: &str) -> Result<SubdomainCheck> {
        let Ok(name) = subdomain(input) else {
            let name = validate::text(&input.trim().to_ascii_lowercase(), SUBDOMAIN_MAX);
            return Ok(SubdomainCheck { name, available: false, reason: Some("invalid".into()) });
        };
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/subdomain?name={name}"))).await?;
        let raw: ApiSubdomainCheck = super::parse(&bytes, "craftinghosting/subdomain")?;
        let reason = raw.reason.filter(|r| SUBDOMAIN_REASONS.contains(&r.as_str()));
        let available = raw.available && reason.is_none();
        Ok(SubdomainCheck {
            name: if subdomain_ok(&raw.name) { raw.name } else { name },
            available,
            reason: if available { None } else { reason.or_else(|| Some("taken".into())) },
        })
    }

    /// Adresse ändern – die alte gilt danach nicht mehr.
    pub async fn craftinghosting_set_subdomain(&self, input: &str) -> Result<Server> {
        let name = subdomain(input)?;
        self.ch_required(Req::patch(format!("{BASE}/server"), json!({ "subdomain": name }))).await
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
        let server = self.ch_required(Req::post(format!("{BASE}/server/stop"), json!({}))).await?;
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

    pub async fn craftinghosting_settings(&self) -> Result<Settings> {
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/server/settings"))).await?;
        let raw: ApiSettingsEnvelope = super::parse(&bytes, "craftinghosting/settings")?;
        Ok(raw.settings.cleaned())
    }

    pub async fn craftinghosting_save_settings(&self, patch: &SettingsPatch) -> Result<Settings> {
        let body = patch.body()?;
        let (_, bytes) = self.ch_send(Req::patch(format!("{BASE}/server/settings"), body)).await?;
        let raw: ApiSettingsEnvelope = super::parse(&bytes, "craftinghosting/settings")?;
        Ok(raw.settings.cleaned())
    }

    /// Letzte Zeilen der Konsole (neueste zuletzt), ohne Steuerzeichen.
    pub async fn craftinghosting_logs(&self, lines: u32) -> Result<Vec<String>> {
        let lines = lines.clamp(1, LOG_LINES_MAX);
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/server/logs?lines={lines}"))).await?;
        let raw: ApiLogs = super::parse(&bytes, "craftinghosting/logs")?;
        let skip = raw.lines.len().saturating_sub(LOG_LINES_MAX as usize);
        Ok(raw.lines.iter().skip(skip).map(|l| log_line(l)).collect())
    }

    pub async fn craftinghosting_stats(&self) -> Result<Stats> {
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/server/stats"))).await?;
        let raw: ApiStats = super::parse(&bytes, "craftinghosting/stats")?;
        Ok(raw.cleaned())
    }

    /// Bestimmte Modrinth-Versionen installieren (`{ versions: [{ project, version }] }`, 1–20).
    pub async fn craftinghosting_install_versions(&self, picks: &[VersionPick]) -> Result<ModsResult> {
        if picks.is_empty() || picks.len() > VERSIONS_MAX {
            return Err(invalid_projects());
        }
        let mut versions: Vec<serde_json::Value> = Vec::with_capacity(picks.len());
        for p in picks {
            let (project, version) = (p.project.trim(), p.version.trim());
            if !project_id(project) || !project_id(version) {
                return Err(invalid_projects());
            }
            let entry = json!({ "project": project, "version": version });
            if !versions.contains(&entry) {
                versions.push(entry);
            }
        }
        let (_, bytes) = self.ch_send(Req::post(format!("{BASE}/server/mods"), json!({ "versions": versions }))).await?;
        let raw: ApiModsResult = super::parse(&bytes, "craftinghosting/mods")?;
        Ok(raw.cleaned())
    }

    /// Modrinth-Projekte (IDs oder Slugs) auf den Server; Client-Mods überspringt die API.
    pub async fn craftinghosting_mods(&self, projects: &[String]) -> Result<ModsResult> {
        let projects = project_list(projects)?;
        let (_, bytes) = self.ch_send(Req::post(format!("{BASE}/server/mods"), json!({ "projects": projects }))).await?;
        let raw: ApiModsResult = super::parse(&bytes, "craftinghosting/mods")?;
        Ok(raw.cleaned())
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
                    Some(s) if !s.session.active && s.setup != "restarting" => state.clear(Some(&account)),
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
        // Freunde, die noch spielen, behalten den Server – er stoppt, wenn der Letzte geht.
        let req = Req::post(format!("{BASE}/server/stop"), json!({ "whenEmpty": true })).timeout(Duration::from_secs(3));
        let stop = self.trs.call_raw(self.accounts(), &account, &req);
        if tokio::time::timeout(Duration::from_secs(3), stop).await.is_ok() {
            self.trs.craftinghosting.clear(Some(&account));
        }
    }
}

// --- Einstellungen, Konsole, Werte, Mods ------------------------------------------------

pub const GAMEMODES: [&str; 4] = ["survival", "creative", "adventure", "spectator"];
pub const DIFFICULTIES: [&str; 4] = ["peaceful", "easy", "normal", "hard"];
pub const LEVEL_TYPES: [&str; 4] = ["normal", "flat", "large_biomes", "amplified"];
pub const LOG_LINES_MAX: u32 = 1000;
const LOG_LINE_MAX: usize = 4000;
const SKIP_REASONS: [&str; 3] = ["client_only", "not_found", "unsupported"];

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub motd: String,
    pub max_players: u32,
    pub gamemode: String,
    pub difficulty: String,
    pub hardcore: bool,
    pub pvp: bool,
    pub allow_flight: bool,
    pub seed: String,
    pub level_type: String,
    pub spawn_protection: u32,
    pub view_distance: u32,
    pub simulation_distance: u32,
    pub whitelist: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiSettings {
    motd: String,
    max_players: u32,
    gamemode: String,
    difficulty: String,
    hardcore: bool,
    pvp: bool,
    allow_flight: bool,
    seed: serde_json::Value,
    level_type: String,
    spawn_protection: u32,
    view_distance: u32,
    simulation_distance: u32,
    whitelist: bool,
}

impl Default for ApiSettings {
    fn default() -> Self {
        Self {
            motd: String::new(),
            max_players: 20,
            gamemode: "survival".into(),
            difficulty: "normal".into(),
            hardcore: false,
            pvp: true,
            allow_flight: false,
            seed: serde_json::Value::Null,
            level_type: "normal".into(),
            spawn_protection: 16,
            view_distance: 10,
            simulation_distance: 10,
            whitelist: true,
        }
    }
}

fn one_of(value: String, allowed: &[&str], fallback: &str) -> String {
    if allowed.contains(&value.as_str()) { value } else { fallback.to_owned() }
}

impl ApiSettings {
    fn cleaned(self) -> Settings {
        let seed = match self.seed {
            serde_json::Value::String(s) => validate::text(s.trim(), 64),
            serde_json::Value::Number(n) => n.to_string(),
            _ => String::new(),
        };
        Settings {
            motd: validate::text(&self.motd, 120),
            max_players: self.max_players.clamp(1, 100),
            gamemode: one_of(self.gamemode, &GAMEMODES, "survival"),
            difficulty: one_of(self.difficulty, &DIFFICULTIES, "normal"),
            hardcore: self.hardcore,
            pvp: self.pvp,
            allow_flight: self.allow_flight,
            seed,
            level_type: one_of(self.level_type.to_ascii_lowercase(), &LEVEL_TYPES, "normal"),
            spawn_protection: self.spawn_protection.min(64),
            view_distance: self.view_distance.clamp(3, 16),
            simulation_distance: self.simulation_distance.clamp(3, 16),
            whitelist: self.whitelist,
        }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiSettingsEnvelope {
    settings: ApiSettings,
}

/// Änderungen an den Einstellungen; `None` = unverändert. `whitelist` folgt dem Zugang.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SettingsPatch {
    pub motd: Option<String>,
    pub max_players: Option<u32>,
    pub gamemode: Option<String>,
    pub difficulty: Option<String>,
    pub hardcore: Option<bool>,
    pub pvp: Option<bool>,
    pub allow_flight: Option<bool>,
    pub seed: Option<String>,
    pub level_type: Option<String>,
    pub spawn_protection: Option<u32>,
    pub view_distance: Option<u32>,
    pub simulation_distance: Option<u32>,
}

fn invalid_setting() -> Error {
    invalid(crate::msg!("craftinghosting.invalidSettings", "Diese Einstellung geht so nicht."))
}

fn in_range(value: Option<u32>, min: u32, max: u32) -> Result<Option<u32>> {
    match value {
        Some(v) if !(min..=max).contains(&v) => Err(invalid_setting()),
        other => Ok(other),
    }
}

fn pick(value: Option<&String>, allowed: &[&str]) -> Result<Option<String>> {
    match value {
        Some(v) if !allowed.contains(&v.as_str()) => Err(invalid_setting()),
        other => Ok(other.cloned()),
    }
}

fn single_line(value: Option<&String>, max: usize) -> Result<Option<String>> {
    let Some(v) = value else { return Ok(None) };
    let v = v.trim();
    if v.chars().count() > max || v.chars().any(char::is_control) {
        return Err(invalid_setting());
    }
    Ok(Some(v.to_owned()))
}

impl SettingsPatch {
    pub(crate) fn body(&self) -> Result<serde_json::Value> {
        let mut body = serde_json::Map::new();
        let mut put = |key: &str, value: Option<serde_json::Value>| {
            if let Some(v) = value {
                body.insert(key.to_owned(), v);
            }
        };
        put("motd", single_line(self.motd.as_ref(), 120)?.map(Into::into));
        put("maxPlayers", in_range(self.max_players, 1, 100)?.map(Into::into));
        put("gamemode", pick(self.gamemode.as_ref(), &GAMEMODES)?.map(Into::into));
        put("difficulty", pick(self.difficulty.as_ref(), &DIFFICULTIES)?.map(Into::into));
        put("hardcore", self.hardcore.map(Into::into));
        put("pvp", self.pvp.map(Into::into));
        put("allowFlight", self.allow_flight.map(Into::into));
        put("seed", single_line(self.seed.as_ref(), 64)?.map(Into::into));
        put("levelType", pick(self.level_type.as_ref(), &LEVEL_TYPES)?.map(Into::into));
        put("spawnProtection", in_range(self.spawn_protection, 0, 64)?.map(Into::into));
        put("viewDistance", in_range(self.view_distance, 3, 16)?.map(Into::into));
        put("simulationDistance", in_range(self.simulation_distance, 3, 16)?.map(Into::into));
        if body.is_empty() {
            return Err(invalid_setting());
        }
        Ok(serde_json::Value::Object(body))
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiLogs {
    lines: Vec<String>,
}

/// Konsolenzeile: ANSI-Farben und Steuerzeichen raus, gekürzt.
fn log_line(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\u{1b}' {
            // CSI-Folge bis zum Buchstaben am Ende überspringen.
            if chars.peek() == Some(&'[') {
                chars.next();
                for n in chars.by_ref() {
                    if n.is_ascii_alphabetic() {
                        break;
                    }
                }
            }
            continue;
        }
        out.push(c);
    }
    validate::text(&out, LOG_LINE_MAX)
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Usage {
    pub used_bytes: Option<u64>,
    pub limit_bytes: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Players {
    pub online: u32,
    pub max: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Stats {
    pub cpu_percent: Option<f64>,
    pub memory: Usage,
    pub disk: Usage,
    pub uptime_seconds: Option<u64>,
    pub players: Option<Players>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiUsage {
    used_bytes: Option<f64>,
    limit_bytes: Option<f64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiPlayers {
    online: Option<f64>,
    max: Option<f64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiStats {
    cpu_percent: Option<f64>,
    memory: Option<ApiUsage>,
    disk: Option<ApiUsage>,
    uptime_seconds: Option<f64>,
    players: Option<ApiPlayers>,
}

#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn amount(v: Option<f64>) -> Option<u64> {
    v.filter(|n| n.is_finite() && *n >= 0.0).map(|n| n as u64)
}

impl ApiStats {
    fn cleaned(self) -> Stats {
        let usage = |u: Option<ApiUsage>| {
            let u = u.unwrap_or_default();
            Usage { used_bytes: amount(u.used_bytes), limit_bytes: amount(u.limit_bytes) }
        };
        let players = self.players.and_then(|p| {
            let online = u32::try_from(amount(p.online)?.min(10_000)).ok()?;
            let max = u32::try_from(amount(p.max).unwrap_or(0).min(10_000)).ok()?;
            Some(Players { online, max })
        });
        Stats {
            cpu_percent: self.cpu_percent.filter(|n| n.is_finite() && *n >= 0.0).map(|n| (n * 10.0).round() / 10.0),
            memory: usage(self.memory),
            disk: usage(self.disk),
            uptime_seconds: amount(self.uptime_seconds),
            players,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Skipped {
    pub project: String,
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ModsResult {
    pub installed: Vec<String>,
    pub skipped: Vec<Skipped>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiSkipped {
    project: String,
    reason: String,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiModsResult {
    installed: Vec<String>,
    skipped: Vec<ApiSkipped>,
}

fn project_id(input: &str) -> bool {
    (1..=64).contains(&input.len()) && input.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
}

impl ApiModsResult {
    fn cleaned(self) -> ModsResult {
        ModsResult {
            installed: self.installed.into_iter().filter(|p| project_id(p)).take(100).collect(),
            skipped: self
                .skipped
                .into_iter()
                .filter(|s| project_id(&s.project))
                .map(|s| Skipped {
                    reason: if SKIP_REASONS.contains(&s.reason.as_str()) { s.reason } else { "unsupported".into() },
                    project: s.project,
                })
                .take(100)
                .collect(),
        }
    }
}

/// Eine bestimmte Modrinth-Version eines Projekts.
#[derive(Debug, Clone, Deserialize)]
pub struct VersionPick {
    pub project: String,
    pub version: String,
}

/// Höchstens so viele feste Versionen je Anfrage.
pub const VERSIONS_MAX: usize = 20;

fn invalid_projects() -> Error {
    invalid(crate::msg!("craftinghosting.invalidProjects", "Diese Mods lassen sich nicht übertragen."))
}

/// 1–100 Modrinth-IDs oder Slugs, ohne doppelte.
pub fn project_list(input: &[String]) -> Result<Vec<String>> {
    let mut out: Vec<String> = Vec::new();
    for p in input {
        let p = p.trim();
        if !project_id(p) {
            return Err(invalid_projects());
        }
        if !out.iter().any(|o| o == p) {
            out.push(p.to_owned());
        }
    }
    if out.is_empty() || out.len() > 100 {
        return Err(invalid_projects());
    }
    Ok(out)
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
    fn subdomains() {
        assert_eq!(subdomain(" MeineWelt ").unwrap(), "meinewelt");
        assert_eq!(subdomain("bob-smp-2").unwrap(), "bob-smp-2");
        for bad in ["ab", "-abc", "abc-", "a_b_c", "mein welt", "grün", &"x".repeat(25)] {
            assert!(subdomain(bad).is_err(), "{bad}");
        }
        assert_eq!(domain(Some("MeineWelt.trs-launcher.de".into())).as_deref(), Some("meinewelt.trs-launcher.de"));
        assert_eq!(domain(Some("ab.trs-launcher.de".into())), None);
        assert_eq!(domain(Some("evil.com".into())), None);
        assert_eq!(domain(Some("x.evil.trs-launcher.de".into())), None);
        assert_eq!(domain(Some("meinewelt.trs-launcher.de.evil.com".into())), None);
        assert_eq!(domain(None), None);
        assert_eq!(ip(Some("1.2.3.4:25011".into())).as_deref(), Some("1.2.3.4:25011"));
        for bad in ["1.2.3.4", "1.2.3:25011", "999.2.3.4:1", "1.2.3.4:0", "1.2.3.4:99999", "evil.com:25565", "1.2.3.4:25565x"] {
            assert_eq!(ip(Some(bad.into())), None, "{bad}");
        }
    }

    #[test]
    fn error_details() {
        let e = json!({ "code": "cooldown", "details": { "cooldownUntil": "2026-11-18T18:00:00Z" } });
        assert_eq!(error_params("cooldown", Some(&e)), vec![("cooldownUntil", "2026-11-18T18:00:00.000Z".to_owned())]);
        let e = json!({ "code": "not_running", "details": { "reason": "<script>" } });
        assert!(error_params("not_running", Some(&e)).is_empty());
        let e = json!({ "code": "not_running", "details": { "reason": "host_timeout" } });
        assert_eq!(error_params("not_running", Some(&e)), vec![("reason", "host_timeout".to_owned())]);
        let e = json!({ "details": { "message": " Update bis 18 Uhr " } });
        assert_eq!(error_params("hosting_maintenance", Some(&e)), vec![("message", "Update bis 18 Uhr".to_owned())]);
        let e = json!({ "details": { "reason": "Spam" } });
        assert_eq!(error_params("server_blocked", Some(&e)), vec![("reason", "Spam".to_owned())]);
        let e = json!({ "details": { "feature": "../x" } });
        assert!(error_params("feature_disabled", Some(&e)).is_empty());
        let e = json!({ "details": { "feature": "console" } });
        assert_eq!(error_params("feature_disabled", Some(&e)), vec![("feature", "console".to_owned())]);
    }

    #[test]
    fn team_control() {
        let n = notice(&json!({ "markdown": "**Neu:** Backups", "level": "success", "updatedAt": "2026-11-18T20:00:00.000Z" })).unwrap();
        assert_eq!((n.markdown.as_str(), n.level.as_str()), ("**Neu:** Backups", "success"));
        assert_eq!(n.updated_at.as_deref(), Some("2026-11-18T20:00:00.000Z"));
        assert_eq!(notice(&json!({ "markdown": "x", "level": "<b>" })).unwrap().level, "info");
        assert_eq!(notice(&json!({ "markdown": "  " })), None);
        assert_eq!(notice(&json!(null)), None);
        assert_eq!(maintenance(&json!(null)), Maintenance::default());

        let mut raw = json!({ "id": "abc", "name": "Welt", "state": "offline", "serverType": "paper", "version": "latest",
                              "blocked": { "reason": " Spam ", "since": "2026-11-18T20:00:00.000Z" } });
        let s = serde_json::from_value::<ApiServer>(raw.clone()).unwrap().cleaned().unwrap();
        assert_eq!(s.blocked, Some(Blocked { reason: "Spam".into(), since: Some("2026-11-18T20:00:00.000Z".into()) }));
        raw["blocked"] = json!(null);
        assert_eq!(serde_json::from_value::<ApiServer>(raw).unwrap().cleaned().unwrap().blocked, None);
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
