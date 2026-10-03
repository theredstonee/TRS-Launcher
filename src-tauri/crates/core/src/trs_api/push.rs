//! Push-Benachrichtigungen der Handy-Apps (Vertrag `api/API.md` §33).
//!
//! - **Android:** UnifiedPush. Die App holt sich beim Verteiler (ntfy, NextPush …) eine Adresse und die
//!   Web-Push-Schlüssel; der Kern meldet sie hier als Gerät an. Entschlüsselt und angezeigt wird im Plugin.
//! - **iOS (ohne APNs):** Gerät der Art `poll`; die App holt wartende Hinweise im Hintergrund ab
//!   ([`TrsApi::push_poll`]).
//! - Ein Gerät gehört zur **Sitzung**, die es angemeldet hat. Neue Sitzung (neuer Token) = neues Gerät.
//! - `<daten>/trs-push.json` hält die Schalter und je Konto das angemeldete Gerät mit einem Abdruck dessen,
//!   was der Server kennt – geändert wird nur, was sich wirklich unterscheidet. Die Push-Adresse selbst ist
//!   ein Geheimnis und liegt nur beim Plugin, hier steht nur ihr SHA-256.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tokio::sync::Mutex;

use super::{Req, SessionSource, TrsApi, encode_query, validate};
use crate::{Error, Launcher, Result, fsutil};

/// Kategorien (§33.3) in der Reihenfolge der Einstellungen.
pub const CATEGORIES: [&str; 8] = ["chat", "friends", "friend_online", "invites", "hosting", "packs", "team", "achievements"];
/// Standard, solange der Server nichts anderes sagt (§33.1).
fn default_on(category: &str) -> bool {
    category != "friend_online"
}
/// So viele zuletzt angezeigte Hinweise merkt sich der Kern (doppelte Zustellung beim Abholen).
const SEEN_IDS: usize = 200;
/// Höchstens so viele Seiten je Abholen (je 50 Einträge).
const MAX_PAGES: usize = 5;
/// Server-Konfiguration so lange zwischenspeichern.
const CONFIG_TTL: Duration = Duration::from_secs(60 * 60);

// --- Prüfregeln (wie `schemas.ts`) ----------------------------------------------------------

/// `^d[0-9a-f]{20}$`
pub fn device_id(input: &str) -> bool {
    input.len() == 21 && input.starts_with('d') && input[1..].bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn base64url(input: &str, min: usize, max: usize) -> bool {
    (min..=max).contains(&input.len()) && input.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// VAPID-Schlüssel: 65 Byte unkomprimiert, base64url ohne `=` (87 Zeichen).
pub fn vapid_key(input: &str) -> bool {
    input.len() == 87 && base64url(input, 87, 87)
}

/// `^[0-9A-Za-z.+_-]{1,32}$`
pub fn app_version(input: &str) -> bool {
    (1..=32).contains(&input.len()) && input.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'+' | b'_' | b'-'))
}

/// `en`, `de-DE`, `es-419`, `pt-BR` … (`^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8}){0,2}$`).
pub fn locale(input: &str) -> bool {
    if input.len() > 20 {
        return false;
    }
    let mut parts = input.split(['-', '_']);
    let Some(lang) = parts.next() else { return false };
    if !(2..=3).contains(&lang.len()) || !lang.bytes().all(|b| b.is_ascii_alphabetic()) {
        return false;
    }
    let rest: Vec<&str> = parts.collect();
    rest.len() <= 2 && rest.iter().all(|p| (2..=8).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_alphanumeric()))
}

/// Push-Adresse: nur `https://`, ohne Zugangsdaten und Anker (den Rest prüft der Server).
pub fn endpoint(input: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(input) else { return false };
    (12..=2048).contains(&input.len())
        && url.scheme() == "https"
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none()
}

/// Gerätename: 1–64 Zeichen, ohne Steuerzeichen; sonst ein Ersatz.
fn device_name(input: &str, fallback: &str) -> String {
    let t: String = validate::text(input.trim(), 64);
    let t = t.trim();
    if t.is_empty() { fallback.to_owned() } else { t.to_owned() }
}

fn category(input: &str) -> bool {
    (1..=32).contains(&input.len()) && input.bytes().all(|b| b.is_ascii_lowercase() || b == b'_')
}

fn sha256_hex(input: &str) -> String {
    crate::link::proto::hex(&Sha256::digest(input.as_bytes()))
}

// --- Server-Antworten ---------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiConfig {
    #[serde(default)]
    unified_push: bool,
    #[serde(default)]
    vapid_public_key: Option<String>,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    defaults: BTreeMap<String, bool>,
    #[serde(default)]
    max_devices: Option<u32>,
}

/// `GET /v1/push/config`, gesäubert.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PushConfig {
    /// Der Server kann UnifiedPush senden (hat VAPID-Schlüssel).
    pub unified_push: bool,
    pub vapid_public_key: Option<String>,
    pub categories: Vec<String>,
    pub defaults: BTreeMap<String, bool>,
    pub max_devices: u32,
}

impl From<ApiConfig> for PushConfig {
    fn from(c: ApiConfig) -> Self {
        let vapid_public_key = c.vapid_public_key.filter(|k| vapid_key(k));
        let mut categories: Vec<String> = c.categories.into_iter().filter(|c| category(c)).take(32).collect();
        if categories.is_empty() {
            categories = CATEGORIES.iter().map(|c| (*c).to_owned()).collect();
        }
        let defaults = categories
            .iter()
            .map(|cat| (cat.clone(), c.defaults.get(cat).copied().unwrap_or_else(|| default_on(cat))))
            .collect();
        Self {
            unified_push: c.unified_push && vapid_public_key.is_some(),
            vapid_public_key,
            categories,
            defaults,
            max_devices: c.max_devices.unwrap_or(10).clamp(1, 100),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiDevice {
    id: String,
    #[serde(default)]
    platform: String,
    #[serde(default)]
    kind: String,
    #[serde(default)]
    endpoint_host: Option<String>,
    #[serde(default)]
    device_name: String,
    #[serde(default)]
    app_version: String,
    #[serde(default)]
    categories: BTreeMap<String, bool>,
    #[serde(default)]
    preview: bool,
    #[serde(default)]
    push_while_playing: bool,
    #[serde(default)]
    current: bool,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    last_seen_at: Option<String>,
    #[serde(default)]
    last_success_at: Option<String>,
    #[serde(default)]
    failing: bool,
}

#[derive(Debug, Deserialize)]
struct ApiDevices {
    #[serde(default)]
    devices: Vec<ApiDevice>,
}

/// Ein Gerät des Kontos (Einstellungen → Benachrichtigungen → Geräte).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PushDevice {
    pub id: String,
    /// `android` | `ios`
    pub platform: String,
    /// `unifiedpush` | `poll`
    pub kind: String,
    /// Nur der Host des Push-Servers (z. B. `ntfy.sh`).
    pub endpoint_host: Option<String>,
    pub device_name: String,
    pub app_version: String,
    pub categories: BTreeMap<String, bool>,
    pub preview: bool,
    pub push_while_playing: bool,
    /// Mit der Sitzung dieser App angemeldet.
    pub current: bool,
    /// Dieses Gerät (laut lokalem Stand).
    pub this_device: bool,
    pub created_at: Option<String>,
    pub last_seen_at: Option<String>,
    pub last_success_at: Option<String>,
    pub failing: bool,
}

fn time(t: Option<String>) -> Option<String> {
    t.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

impl ApiDevice {
    fn view(self, own: Option<&str>) -> Option<PushDevice> {
        if !device_id(&self.id) {
            return None;
        }
        let pick = |v: &str, allowed: &[&str], fallback: &str| {
            if allowed.contains(&v) { v.to_owned() } else { fallback.to_owned() }
        };
        let host = self
            .endpoint_host
            .map(|h| validate::text(&h, 253))
            .filter(|h| !h.is_empty() && h.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b':' | b'[' | b']')));
        Some(PushDevice {
            this_device: own == Some(self.id.as_str()),
            platform: pick(&self.platform, &["android", "ios"], "android"),
            kind: pick(&self.kind, &["unifiedpush", "poll"], "poll"),
            endpoint_host: host,
            device_name: device_name(&self.device_name, "?"),
            app_version: if app_version(&self.app_version) { self.app_version } else { String::new() },
            categories: self.categories.into_iter().filter(|(k, _)| category(k)).take(32).collect(),
            preview: self.preview,
            push_while_playing: self.push_while_playing,
            current: self.current,
            created_at: time(self.created_at),
            last_seen_at: time(self.last_seen_at),
            last_success_at: time(self.last_success_at),
            failing: self.failing,
            id: self.id,
        })
    }
}

/// Inhalt einer Push-Nachricht bzw. ein Eintrag aus `GET /v1/push/pending` (§33.5).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushPayload {
    pub v: u32,
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub category: String,
    pub title: String,
    pub body: String,
    /// App-Route wie `/chat/c…` (§33.5).
    pub target: String,
    #[serde(default)]
    pub collapse: Option<String>,
    #[serde(default)]
    pub at: Option<String>,
}

/// Route aus §33.5: `/wort` plus bis zu drei Teile `[A-Za-z0-9_-]{1,64}`.
pub fn push_target(input: &str) -> bool {
    let Some(rest) = input.strip_prefix('/') else { return false };
    let mut parts = rest.split('/');
    let Some(head) = parts.next() else { return false };
    let tail: Vec<&str> = parts.collect();
    (1..=24).contains(&head.len())
        && head.bytes().all(|b| b.is_ascii_lowercase())
        && tail.len() <= 3
        && tail.iter().all(|p| (1..=64).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-')))
}

impl PushPayload {
    /// Nur gültige Einträge (Version 1, Felder in ihren Grenzen); Texte ohne Steuerzeichen.
    pub fn checked(self) -> Option<Self> {
        let collapse = self
            .collapse
            .filter(|c| (1..=120).contains(&c.len()) && c.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b':' | b'_' | b'-' | b'.')));
        let one_line = |s: &str, max: usize| validate::text(&s.split_whitespace().collect::<Vec<_>>().join(" "), max);
        let ok = self.v == 1
            && super::live::valid_event_id(&self.id)
            && category(&self.kind)
            && category(&self.category)
            && push_target(&self.target);
        ok.then(|| Self {
            title: one_line(&self.title, 80),
            body: one_line(&self.body, 200),
            at: time(self.at),
            collapse,
            ..self
        })
    }
}

#[derive(Debug, Deserialize)]
struct ApiPending {
    #[serde(default)]
    notifications: Vec<Value>,
    cursor: String,
    #[serde(default)]
    more: bool,
}

// --- Lokaler Stand ----------------------------------------------------------------------

/// Schalter am Handy (gelten für alle Konten der App).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PushSettings {
    pub enabled: bool,
    /// Abweichungen vom Standard des Servers je Kategorie.
    pub categories: BTreeMap<String, bool>,
    /// Chat-Hinweise mit Textanfang und Gruppenname (Standard: nur „Neue Nachricht“).
    pub preview: bool,
    /// Auch Hinweise, während man am PC spielt.
    pub push_while_playing: bool,
    /// Android ohne UnifiedPush-Verteiler: Hinweise stattdessen im Hintergrund abholen (etwa alle 15 min).
    pub poll_fallback: bool,
}

impl Default for PushSettings {
    fn default() -> Self {
        Self { enabled: true, categories: BTreeMap::new(), preview: false, push_while_playing: false, poll_fallback: false }
    }
}

impl PushSettings {
    fn cleaned(mut self) -> Self {
        self.categories.retain(|k, _| CATEGORIES.contains(&k.as_str()));
        self
    }

    /// Alle Kategorien mit Wert (Abweichung, sonst Standard des Servers).
    pub fn effective(&self, config: Option<&PushConfig>) -> BTreeMap<String, bool> {
        CATEGORIES
            .iter()
            .map(|c| {
                let default = config.and_then(|cfg| cfg.defaults.get(*c).copied()).unwrap_or_else(|| default_on(c));
                ((*c).to_owned(), self.categories.get(*c).copied().unwrap_or(default))
            })
            .collect()
    }
}

/// Plattform der App.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Platform {
    Android,
    Ios,
}

impl Platform {
    fn as_str(self) -> &'static str {
        match self {
            Self::Android => "android",
            Self::Ios => "ios",
        }
    }
}

/// Art der Zustellung.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Unifiedpush,
    Poll,
}

/// Wie das Gerät Hinweise bekommt (aus dem Plugin).
#[derive(Clone, PartialEq, Eq)]
pub enum Transport {
    /// UnifiedPush-Adresse + Web-Push-Schlüssel (RFC 8291) vom Verteiler.
    UnifiedPush { endpoint: String, p256dh: String, auth: String },
    /// Abholen (iOS, Android ohne Verteiler).
    Poll,
}

impl std::fmt::Debug for Transport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Die Adresse ist ein Geheimnis: nie ins Log.
        match self {
            Self::UnifiedPush { .. } => f.write_str("UnifiedPush"),
            Self::Poll => f.write_str("Poll"),
        }
    }
}

impl Transport {
    fn kind(&self) -> Kind {
        match self {
            Self::UnifiedPush { .. } => Kind::Unifiedpush,
            Self::Poll => Kind::Poll,
        }
    }

    fn valid(&self) -> bool {
        match self {
            Self::UnifiedPush { endpoint: e, p256dh, auth } => endpoint(e) && base64url(p256dh, 86, 88) && base64url(auth, 21, 24),
            Self::Poll => true,
        }
    }
}

/// Was die App über dieses Gerät weiß.
#[derive(Debug, Clone)]
pub struct PushEnv {
    pub platform: Platform,
    /// `None`: (noch) keine Zustellung möglich – z. B. Adresse vom Verteiler steht aus.
    pub transport: Option<Transport>,
    pub device_name: String,
    pub app_version: String,
}

/// Was der Server über das Gerät weiß (Abdruck, ohne Geheimnisse).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Sent {
    platform: Platform,
    kind: Kind,
    /// SHA-256 der Adresse.
    #[serde(default)]
    endpoint: Option<String>,
    /// SHA-256 von `p256dh.auth`.
    #[serde(default)]
    keys: Option<String>,
    device_name: String,
    app_version: String,
    locale: String,
    categories: BTreeMap<String, bool>,
    preview: bool,
    push_while_playing: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DeviceRecord {
    id: String,
    /// SHA-256 (gekürzt) des Tokens der Sitzung, die das Gerät angemeldet hat.
    session: String,
    sent: Sent,
    /// Abholen: Stand der letzten Antwort.
    #[serde(default)]
    cursor: u64,
    /// Abholen: zuletzt gemeldete Hinweise (gegen doppelte Anzeige).
    #[serde(default)]
    seen: Vec<String>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PushFile {
    #[serde(default)]
    settings: PushSettings,
    /// Konto (UUID) → Gerät.
    #[serde(default)]
    devices: BTreeMap<String, DeviceRecord>,
}

/// `<daten>/trs-push.json` + zwischengespeicherte Server-Konfiguration.
pub(crate) struct PushStore {
    path: PathBuf,
    file: Mutex<Option<PushFile>>,
    /// Abgleiche laufen nacheinander.
    sync_lock: Mutex<()>,
    config: std::sync::Mutex<Option<(Instant, PushConfig)>>,
}

impl PushStore {
    pub fn new(path: PathBuf) -> Self {
        Self { path, file: Mutex::new(None), sync_lock: Mutex::new(()), config: std::sync::Mutex::new(None) }
    }

    async fn with<R>(&self, f: impl FnOnce(&mut PushFile) -> R) -> R {
        let mut guard = self.file.lock().await;
        if guard.is_none() {
            let file = match fsutil::read_json::<PushFile>(&self.path).await {
                Ok(file) => file.unwrap_or_default(),
                Err(e) => {
                    tracing::warn!("trs-push.json ist beschädigt – Benachrichtigungen werden neu eingerichtet: {e}");
                    PushFile::default()
                }
            };
            *guard = Some(file);
        }
        f(guard.as_mut().expect("gerade geladen"))
    }

    async fn save(&self) {
        let json = {
            let guard = self.file.lock().await;
            let Some(file) = guard.as_ref() else { return };
            match serde_json::to_vec_pretty(file) {
                Ok(json) => json,
                Err(e) => {
                    tracing::warn!("trs-push.json konnte nicht serialisiert werden: {e}");
                    return;
                }
            }
        };
        if let Err(e) = fsutil::write_atomic(&self.path, &json).await {
            tracing::warn!("trs-push.json konnte nicht gespeichert werden: {e}");
        }
    }

    pub async fn settings(&self) -> PushSettings {
        self.with(|f| f.settings.clone()).await
    }

    async fn set_settings(&self, settings: PushSettings) {
        self.with(|f| f.settings = settings).await;
        self.save().await;
    }

    async fn record(&self, account: &str) -> Option<DeviceRecord> {
        self.with(|f| f.devices.get(account).cloned()).await
    }

    async fn put_record(&self, account: &str, record: DeviceRecord) {
        self.with(|f| f.devices.insert(account.to_owned(), record)).await;
        self.save().await;
    }

    async fn drop_record(&self, account: &str) -> Option<DeviceRecord> {
        let old = self.with(|f| f.devices.remove(account)).await;
        if old.is_some() {
            self.save().await;
        }
        old
    }

    fn cached_config(&self) -> Option<PushConfig> {
        let guard = self.config.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        guard.as_ref().filter(|(at, _)| at.elapsed() < CONFIG_TTL).map(|(_, c)| c.clone())
    }

    fn put_config(&self, config: &PushConfig) {
        *self.config.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some((Instant::now(), config.clone()));
    }
}

// --- Abgleich (rein, ohne Netz) -------------------------------------------------------------

/// Was der Abgleich tun muss.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Plan {
    /// Alles aktuell.
    Keep,
    /// Neu anmelden (`POST`).
    Register,
    /// Nur Geändertes senden (`PATCH`).
    Update(Value),
    /// Andere Art/Plattform: altes Gerät löschen, neu anmelden.
    Replace,
    /// Abmelden (`DELETE`).
    Remove,
}

/// Gewünschter Stand: Abdruck + Geheimnisse für den Versand.
#[derive(Debug, Clone)]
struct Desired {
    sent: Sent,
    transport: Transport,
}

impl Desired {
    fn new(env: &PushEnv, transport: Transport, settings: &PushSettings, config: Option<&PushConfig>, locale: &str) -> Self {
        let (endpoint, keys) = match &transport {
            Transport::UnifiedPush { endpoint, p256dh, auth } => (Some(sha256_hex(endpoint)), Some(sha256_hex(&format!("{p256dh}.{auth}")))),
            Transport::Poll => (None, None),
        };
        let fallback = match env.platform {
            Platform::Android => "Android",
            Platform::Ios => "iPhone",
        };
        Self {
            sent: Sent {
                platform: env.platform,
                kind: transport.kind(),
                endpoint,
                keys,
                device_name: device_name(&env.device_name, fallback),
                app_version: if app_version(&env.app_version) { env.app_version.clone() } else { "0.0.0".into() },
                locale: if self::locale(locale) { locale.to_owned() } else { "en".into() },
                categories: settings.effective(config),
                preview: settings.preview,
                push_while_playing: settings.push_while_playing,
            },
            transport,
        }
    }

    /// Body für `POST /v1/push/devices`.
    fn register_body(&self) -> Value {
        let s = &self.sent;
        let mut body = json!({
            "platform": s.platform.as_str(),
            "kind": match s.kind { Kind::Unifiedpush => "unifiedpush", Kind::Poll => "poll" },
            "deviceName": s.device_name,
            "appVersion": s.app_version,
            "locale": s.locale,
            "categories": s.categories,
            "preview": s.preview,
            "pushWhilePlaying": s.push_while_playing,
        });
        if let Transport::UnifiedPush { endpoint, p256dh, auth } = &self.transport {
            body["endpoint"] = json!(endpoint);
            body["keys"] = json!({ "p256dh": p256dh, "auth": auth });
        }
        body
    }

    /// Body für `PATCH` mit allem, was sich gegenüber `old` geändert hat (leer = nichts).
    fn patch_body(&self, old: &Sent) -> serde_json::Map<String, Value> {
        let s = &self.sent;
        let mut patch = serde_json::Map::new();
        if s.device_name != old.device_name {
            patch.insert("deviceName".into(), json!(s.device_name));
        }
        if s.app_version != old.app_version {
            patch.insert("appVersion".into(), json!(s.app_version));
        }
        if s.locale != old.locale {
            patch.insert("locale".into(), json!(s.locale));
        }
        let changed: BTreeMap<&String, &bool> = s.categories.iter().filter(|(k, v)| old.categories.get(*k) != Some(*v)).collect();
        if !changed.is_empty() {
            patch.insert("categories".into(), json!(changed));
        }
        if s.preview != old.preview {
            patch.insert("preview".into(), json!(s.preview));
        }
        if s.push_while_playing != old.push_while_playing {
            patch.insert("pushWhilePlaying".into(), json!(s.push_while_playing));
        }
        if (s.endpoint != old.endpoint || s.keys != old.keys)
            && let Transport::UnifiedPush { endpoint, p256dh, auth } = &self.transport
        {
            // Neue Adresse vom Verteiler: Adresse und Schlüssel immer zusammen.
            patch.insert("endpoint".into(), json!(endpoint));
            patch.insert("keys".into(), json!({ "p256dh": p256dh, "auth": auth }));
        }
        patch
    }
}

fn plan(record: Option<&DeviceRecord>, want: Option<&Desired>, session: &str) -> Plan {
    match (record, want) {
        (None, None) => Plan::Keep,
        (Some(_), None) => Plan::Remove,
        (None, Some(_)) => Plan::Register,
        // Das alte Gerät ging mit der alten Sitzung (Abmelden, Ablauf) – neu anmelden.
        (Some(r), Some(_)) if r.session != session => Plan::Register,
        (Some(r), Some(w)) if r.sent.kind != w.sent.kind || r.sent.platform != w.sent.platform => Plan::Replace,
        (Some(r), Some(w)) => {
            let patch = w.patch_body(&r.sent);
            if patch.is_empty() { Plan::Keep } else { Plan::Update(Value::Object(patch)) }
        }
    }
}

/// Ergebnis eines Abgleichs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PushSync {
    /// Angemeldetes Gerät dieser App (falls eins).
    pub device_id: Option<String>,
    /// `unifiedpush` | `poll` | `null`
    pub kind: Option<Kind>,
}

fn session_fp(token: &str) -> String {
    sha256_hex(token)[..16].to_owned()
}

// --- Netz ------------------------------------------------------------------------------------

impl TrsApi {
    /// `GET /v1/push/config` (eine Stunde zwischengespeichert).
    pub(crate) async fn push_config(&self, sessions: &dyn SessionSource, account: &str) -> Result<PushConfig> {
        if let Some(config) = self.push.cached_config() {
            return Ok(config);
        }
        let raw: ApiConfig = self.call(sessions, account, &Req::get("/v1/push/config")).await?;
        let config = PushConfig::from(raw);
        self.push.put_config(&config);
        Ok(config)
    }

    /// Abdruck der aktuellen Sitzung (ohne Token: keine).
    async fn push_session(&self, sessions: &dyn SessionSource, account: &str) -> Result<String> {
        let token = match self.store.token(account).await {
            Some(token) => token,
            None => self.login(sessions, account).await?,
        };
        Ok(session_fp(&token))
    }

    /// Gerät für den Echtzeit-Kanal (`?pushDevice=`): nur, wenn es mit der aktuellen Sitzung angemeldet ist.
    pub(crate) async fn push_live_device(&self, account: &str) -> Option<String> {
        let record = self.push.record(account).await?;
        let token = self.store.token(account).await?;
        (record.session == session_fp(&token) && device_id(&record.id)).then_some(record.id)
    }

    /// Gleicht das Gerät dieses Kontos mit dem Server ab (anmelden, ändern, abmelden).
    pub(crate) async fn push_sync(&self, sessions: &dyn SessionSource, account: &str, env: &PushEnv, locale: &str) -> Result<PushSync> {
        let _guard = self.push.sync_lock.lock().await;
        let settings = self.push.settings().await.cleaned();
        let record = self.push.record(account).await;
        let transport = env.transport.clone().filter(Transport::valid);
        // Eingeschaltet, aber (noch) keine Adresse vom Verteiler: angemeldetes Gerät behalten.
        if settings.enabled && transport.is_none() {
            return Ok(PushSync { device_id: record.as_ref().map(|r| r.id.clone()), kind: record.map(|r| r.sent.kind) });
        }
        let transport = transport.filter(|_| settings.enabled);
        if transport.is_none() && record.is_none() {
            return Ok(PushSync { device_id: None, kind: None });
        }
        let session = self.push_session(sessions, account).await?;
        let want = match transport {
            Some(t) => {
                let config = self.push_config(sessions, account).await?;
                if matches!(t, Transport::UnifiedPush { .. }) && !config.unified_push {
                    return Err(super::api_error(503, "push_unavailable", None));
                }
                Some(Desired::new(env, t, &settings, Some(&config), locale))
            }
            None => None,
        };
        let step = plan(record.as_ref(), want.as_ref(), &session);
        tracing::debug!("Push-Abgleich: {}", match &step {
            Plan::Keep => "aktuell",
            Plan::Register => "anmelden",
            Plan::Update(_) => "ändern",
            Plan::Replace => "ersetzen",
            Plan::Remove => "abmelden",
        });
        match (step, want) {
            (Plan::Keep, want) => Ok(PushSync {
                device_id: record.as_ref().map(|r| r.id.clone()),
                kind: want.map(|w| w.sent.kind),
            }),
            (Plan::Remove, _) => {
                if let Some(r) = record {
                    // Gerät einer alten Sitzung ist beim Server schon weg.
                    if r.session == session {
                        self.push_delete_remote(sessions, account, &r.id).await?;
                    }
                    self.push.drop_record(account).await;
                }
                Ok(PushSync { device_id: None, kind: None })
            }
            (Plan::Replace, Some(want)) => {
                if let Some(r) = &record {
                    self.push_delete_remote(sessions, account, &r.id).await?;
                }
                self.push.drop_record(account).await;
                self.push_register(sessions, account, &want, &session).await
            }
            (Plan::Register, Some(want)) => self.push_register(sessions, account, &want, &session).await,
            (Plan::Update(patch), Some(want)) => {
                let Some(mut r) = record else { return self.push_register(sessions, account, &want, &session).await };
                match self.call_raw(sessions, account, &Req::patch(format!("/v1/push/devices/{}", r.id), patch)).await {
                    Ok(_) => {
                        r.sent = want.sent.clone();
                        let id = r.id.clone();
                        self.push.put_record(account, r).await;
                        Ok(PushSync { device_id: Some(id), kind: Some(want.sent.kind) })
                    }
                    // Beim Server weg (entfernt, zu oft fehlgeschlagen): neu anmelden.
                    Err(Error::TrsApi { code, .. }) if code == "device_not_found" || code == "not_unifiedpush" => {
                        self.push.drop_record(account).await;
                        self.push_register(sessions, account, &want, &session).await
                    }
                    Err(e) => Err(e),
                }
            }
            (_, None) => Ok(PushSync { device_id: None, kind: None }),
        }
    }

    async fn push_register(&self, sessions: &dyn SessionSource, account: &str, want: &Desired, session: &str) -> Result<PushSync> {
        let raw: ApiDevice = self.call(sessions, account, &Req::post("/v1/push/devices", want.register_body())).await?;
        if !device_id(&raw.id) {
            return Err(super::bad_response());
        }
        // Während der Anfrage neu angemeldet? Dann gehört das Gerät zur neuen Sitzung.
        let session = self.store.token(account).await.map_or_else(|| session.to_owned(), |t| session_fp(&t));
        let record = DeviceRecord { id: raw.id.clone(), session, sent: want.sent.clone(), cursor: 0, seen: Vec::new() };
        self.push.put_record(account, record).await;
        tracing::info!("Benachrichtigungen: Gerät angemeldet ({:?})", want.sent.kind);
        // Echtzeit-Kanal mit `?pushDevice=` neu aufbauen.
        self.live.kick();
        Ok(PushSync { device_id: Some(raw.id), kind: Some(want.sent.kind) })
    }

    async fn push_delete_remote(&self, sessions: &dyn SessionSource, account: &str, id: &str) -> Result<()> {
        if !device_id(id) {
            return Ok(());
        }
        match self.call_raw(sessions, account, &Req::delete(format!("/v1/push/devices/{id}"))).await {
            Ok(_) => Ok(()),
            Err(Error::TrsApi { code, .. }) if code == "device_not_found" => Ok(()),
            Err(e) => Err(e),
        }
    }

    /// Beim Abmelden (vor `/v1/auth/logout`): Gerät löschen und vergessen. Fehler sind egal – der Server löscht
    /// Geräte einer beendeten Sitzung ohnehin.
    pub(crate) async fn push_forget(&self, account: &str, token: &str) {
        let Some(record) = self.push.drop_record(account).await else { return };
        if record.session != session_fp(token) || !device_id(&record.id) {
            return;
        }
        let req = Req::delete(format!("/v1/push/devices/{}", record.id));
        let _ = tokio::time::timeout(Duration::from_secs(4), self.send_once(&req, Some(token))).await;
    }

    /// Abholen (Gerät der Art `poll`): neue Hinweise seit dem letzten Mal. Ohne Poll-Gerät: leer.
    pub(crate) async fn push_poll(&self, sessions: &dyn SessionSource, account: &str) -> Result<Vec<PushPayload>> {
        let _guard = self.push.sync_lock.lock().await;
        let Some(mut record) = self.push.record(account).await else { return Ok(Vec::new()) };
        if record.sent.kind != Kind::Poll || !device_id(&record.id) {
            return Ok(Vec::new());
        }
        let mut out = Vec::new();
        for _ in 0..MAX_PAGES {
            let path = format!("/v1/push/pending?device={}&since={}&limit=50", encode_query(&record.id), record.cursor);
            let page: ApiPending = match self.call(sessions, account, &Req::get(path)).await {
                Ok(page) => page,
                Err(Error::TrsApi { code, .. }) if code == "device_not_found" || code == "not_poll_device" => {
                    // Gerät beim Server weg: beim nächsten Abgleich neu anmelden.
                    self.push.drop_record(account).await;
                    return Ok(out);
                }
                Err(e) => return Err(e),
            };
            let cursor = page.cursor.parse::<u64>().ok().filter(|c| page.cursor.len() <= 15 && *c >= record.cursor);
            for entry in page.notifications.into_iter().take(100) {
                let Some(payload) = serde_json::from_value::<PushPayload>(entry).ok().and_then(PushPayload::checked) else { continue };
                if record.seen.contains(&payload.id) {
                    continue;
                }
                record.seen.push(payload.id.clone());
                out.push(payload);
            }
            let overflow = record.seen.len().saturating_sub(SEEN_IDS);
            record.seen.drain(..overflow);
            let Some(cursor) = cursor else { break };
            let advanced = cursor > record.cursor;
            record.cursor = cursor;
            if !page.more || !advanced {
                break;
            }
        }
        self.push.put_record(account, record).await;
        Ok(out)
    }
}

impl Launcher {
    /// Einstellungen → Benachrichtigungen (Handy): Schalter.
    pub async fn push_settings(&self) -> PushSettings {
        self.trs.push.settings().await.cleaned()
    }

    /// Schalter speichern. Abgleichen muss danach die App ([`Self::push_sync`]), sie kennt Verteiler und Adresse.
    pub async fn push_set_settings(&self, settings: PushSettings) -> PushSettings {
        let settings = settings.cleaned();
        self.trs.push.set_settings(settings.clone()).await;
        settings
    }

    /// Server-Konfiguration (Kategorien, Standards, VAPID-Schlüssel).
    pub async fn push_config(&self) -> Result<PushConfig> {
        let account = self.trs_account().await?;
        self.trs.push_config(self.accounts(), &account).await
    }

    /// Gerät dieses Kontos abgleichen. Ohne Einwilligung/Konto: nichts zu tun.
    pub async fn push_sync(&self, env: &PushEnv) -> Result<PushSync> {
        let account = self.trs_account().await?;
        let locale = self.settings().await.ui.language.code();
        self.trs.push_sync(self.accounts(), &account, env, locale).await
    }

    /// Alle Geräte des Kontos.
    pub async fn push_devices(&self) -> Result<Vec<PushDevice>> {
        let account = self.trs_account().await?;
        let own = self.trs.push.record(&account).await.map(|r| r.id);
        let raw: ApiDevices = self.trs.call(self.accounts(), &account, &Req::get("/v1/push/devices")).await?;
        Ok(raw.devices.into_iter().take(50).filter_map(|d| d.view(own.as_deref())).collect())
    }

    /// Gerät entfernen. Ist es dieses Handy, schaltet das die Benachrichtigungen hier aus.
    pub async fn push_remove_device(&self, id: &str) -> Result<()> {
        if !device_id(id) {
            return Err(super::api_error(404, "device_not_found", None));
        }
        let account = self.trs_account().await?;
        let _guard = self.trs.push.sync_lock.lock().await;
        self.trs.push_delete_remote(self.accounts(), &account, id).await?;
        if self.trs.push.record(&account).await.is_some_and(|r| r.id == id) {
            self.trs.push.drop_record(&account).await;
            let settings = PushSettings { enabled: false, ..self.trs.push.settings().await };
            self.trs.push.set_settings(settings).await;
            self.trs.live.kick();
        }
        Ok(())
    }

    /// Wartende Hinweise abholen (iOS, Android ohne Verteiler).
    pub async fn push_poll(&self) -> Result<Vec<PushPayload>> {
        let account = self.trs_account().await?;
        self.trs.push_poll(self.accounts(), &account).await
    }

    /// Abholen: zuletzt gemeldete Hinweise (zeigt die App als System-Benachrichtigung; nachgeholt im
    /// Echtzeit-Kanal dann ohne Hinweis).
    pub async fn push_shown_ids(&self) -> Vec<String> {
        let Ok(account) = self.trs_account().await else { return Vec::new() };
        self.trs.push.record(&account).await.map(|r| r.seen).unwrap_or_default()
    }

    /// Gerät der Art `poll` angemeldet?
    pub async fn push_polling(&self) -> bool {
        let Ok(account) = self.trs_account().await else { return false };
        self.trs.push.record(&account).await.is_some_and(|r| r.sent.kind == Kind::Poll)
    }
}

/// Abholen ohne laufende App (iOS-Hintergrundabruf, Android-Hintergrundarbeit): nur Datenordner, Konto und
/// TRS-Token – keine Spiele, kein Echtzeit-Kanal.
pub async fn poll_detached(root: &Path) -> Result<Vec<PushPayload>> {
    let paths = crate::paths::Paths::new(root);
    let key_root = root.to_owned();
    tokio::task::spawn_blocking(move || crate::auth::crypto::init(&key_root))
        .await
        .map_err(|e| Error::Internal(e.to_string()))?;
    let http = crate::net::client_builder()
        .user_agent(crate::USER_AGENT)
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(20))
        .build()?;
    let accounts = crate::auth::AccountStore::new(paths.clone(), http);
    let trs = TrsApi::new(paths)?;
    if !trs.enabled().await {
        return Ok(Vec::new());
    }
    let Some(account) = accounts.active_id().await? else { return Ok(Vec::new()) };
    trs.push_poll(&accounts, &account).await
}

#[cfg(test)]
#[path = "push_tests.rs"]
mod tests;
