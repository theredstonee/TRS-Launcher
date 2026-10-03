//! PC-Fernbedienung (Vertrag `api/API.md` §33): Das Handy steuert den Launcher auf dem PC – beide mit DEMSELBEN
//! TRS-Konto.
//!
//! - **Geräte:** Je Konto und Rolle (PC/Handy) meldet sich der Launcher einmal an und bekommt eine Geräte-ID und ein
//!   Geheimnis. Das Geheimnis liegt verschlüsselt in `<daten>/trs-remote.json` (wie die TRS-Tokens) und geht nur als
//!   `X-TRS-Device`-Kopfzeile an die API, nie ans Webview.
//! - **Befehle** kommen über den Echtzeit-Kanal als `remote_command`. Der Kern prüft die HMAC-Signatur (Schlüssel =
//!   SHA-256 des eigenen Geheimnisses), Ziel-Gerät, Art und Argumente, bevor die Oberfläche überhaupt davon erfährt.
//!   Ausgeführt wird erst nach dem `claim` beim Server (genau einmal, nur vor dem Ablauf) und nur, wenn die
//!   Fernbedienung und die jeweilige Befehlsart in den Einstellungen eingeschaltet sind.
//! - Alles vom Server wird gesäubert: IDs nach festem Muster, Texte gekürzt und ohne Steuerzeichen.

use std::collections::{BTreeMap, HashMap, VecDeque};
use std::path::PathBuf;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use hmac::{Hmac, KeyInit, Mac};
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::{Digest, Sha256};
use tokio::sync::Mutex;

use super::live::LiveEvent;
use super::{Req, TrsApi, validate};
use crate::auth::crypto;
use crate::{Error, Launcher, Result, fsutil};

/// Kopfzeile mit `<geräte-id>.<geheimnis>`.
pub const DEVICE_HEADER: &str = "X-TRS-Device";
/// Link im QR-Code: `trs-launcher://remote-pair/<code>`.
pub const LINK_KIND: &str = "remote-pair";
/// Zeichen der Kopplungs-Codes (wie der Server: ohne 0/O, 1/I/L und U).
const CODE_ALPHABET: &str = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
/// So viele Instanzen bzw. Aufgaben gehen höchstens im Status mit (wie der Server).
pub const MAX_STATUS_INSTANCES: usize = 200;
pub const MAX_STATUS_TASKS: usize = 10;
/// Größter signierter Inhalt eines Befehls.
const MAX_PAYLOAD_BYTES: usize = 4096;
/// Ein Befehl ist 60 s gültig; die Uhr des PCs darf etwas daneben liegen – entschieden wird beim `claim`.
const CLOCK_SLACK_MS: i64 = 5 * 60_000;
/// So viele zuletzt gesehene Befehle merkt sich der Kern (doppelte Zustellung nach Wiederaufnahme).
const SEEN_COMMANDS: usize = 200;

/// Rolle dieses Geräts.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DeviceKind {
    Desktop,
    Phone,
}

impl DeviceKind {
    fn as_str(self) -> &'static str {
        match self {
            Self::Desktop => "desktop",
            Self::Phone => "phone",
        }
    }
}

/// Befehlsarten (wie der Server).
pub const COMMAND_TYPES: [&str; 4] = ["launch_instance", "stop_instance", "install_pack_code", "ping"];

// --- Prüfregeln ------------------------------------------------------------------------

/// Geräte- und Befehls-IDs: 22 Zeichen base64url.
pub fn device_id(input: &str) -> bool {
    input.len() == 22 && input.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn secret(input: &str) -> bool {
    input.len() == 43 && input.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// Instanz-ID wie im Launcher (`[a-z0-9-]`, kein Bindestrich am Rand).
fn instance_id(input: &str) -> bool {
    crate::instance::validate_id(input).is_ok()
}

/// Eingabe (Code oder ganzer QR-Link) → `XXX-XXX`, sonst `None`.
pub fn normalize_pair_code(input: &str) -> Option<String> {
    let input = input.trim();
    if input.len() > 80 {
        return None;
    }
    let raw = match input.split_once("://") {
        Some((scheme, rest)) => {
            if !scheme.eq_ignore_ascii_case(crate::pack_share::LINK_SCHEME) {
                return None;
            }
            let rest = rest.strip_suffix('/').unwrap_or(rest);
            let (kind, code) = rest.split_once('/')?;
            if !kind.eq_ignore_ascii_case(LINK_KIND) {
                return None;
            }
            code
        }
        None => input,
    };
    let code: String = raw.to_ascii_uppercase().chars().filter(|c| !matches!(c, ' ' | '-')).collect();
    (code.len() == 6 && code.chars().all(|c| CODE_ALPHABET.contains(c))).then(|| format!("{}-{}", &code[..3], &code[3..]))
}

/// Code aus `trs-launcher://remote-pair/<code>` (für Links vom Kamera-Scan), sonst `None`.
pub fn pair_code_from_link(url: &str) -> Option<String> {
    url.contains("://").then(|| normalize_pair_code(url)).flatten()
}

fn idempotency_key(input: &str) -> bool {
    (8..=64).contains(&input.len()) && input.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn error_code(input: &str) -> bool {
    let b = input.as_bytes();
    (1..=64).contains(&b.len()) && b[0].is_ascii_lowercase() && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'_')
}

fn clean_text(input: &str, max: usize) -> String {
    let t = validate::text(input, max * 2);
    let t: String = t.split_whitespace().collect::<Vec<_>>().join(" ");
    t.chars().take(max).collect()
}

fn time(t: Option<String>) -> Option<String> {
    t.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

// --- Gespeicherte Geräte ------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredDevice {
    id: String,
    /// Verschlüsselt (DPAPI bzw. Schlüsselbund).
    secret: String,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct StoreFile {
    /// Konto (UUID) → Rolle → Gerät.
    #[serde(default)]
    devices: BTreeMap<String, BTreeMap<DeviceKind, StoredDevice>>,
}

/// Zugangsdaten eines Geräts (Klartext nur im Speicher des Kerns).
#[derive(Clone)]
pub(crate) struct DeviceCred {
    pub id: String,
    secret: String,
}

impl std::fmt::Debug for DeviceCred {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DeviceCred").field("id", &self.id).finish_non_exhaustive()
    }
}

impl DeviceCred {
    fn header(&self) -> String {
        format!("{}.{}", self.id, self.secret)
    }

    /// HMAC-Schlüssel der Befehle = SHA-256 des Geheimnisses (so rechnet es auch der Server).
    fn command_key(&self) -> [u8; 32] {
        Sha256::digest(self.secret.as_bytes()).into()
    }
}

struct Loaded {
    file: StoreFile,
    plain: HashMap<(String, DeviceKind), String>,
}

/// `<daten>/trs-remote.json` + zuletzt gesehene Befehle.
pub(crate) struct RemoteStore {
    path: PathBuf,
    inner: Mutex<Option<Loaded>>,
    /// Es meldet sich höchstens ein Gerät gleichzeitig an.
    register_lock: Mutex<()>,
    seen: std::sync::Mutex<VecDeque<String>>,
}

impl RemoteStore {
    pub fn new(path: PathBuf) -> Self {
        Self { path, inner: Mutex::new(None), register_lock: Mutex::new(()), seen: std::sync::Mutex::default() }
    }

    async fn with<R>(&self, f: impl FnOnce(&mut Loaded) -> R) -> R {
        let mut guard = self.inner.lock().await;
        if guard.is_none() {
            let file = match fsutil::read_json::<StoreFile>(&self.path).await {
                Ok(file) => file.unwrap_or_default(),
                Err(e) => {
                    tracing::warn!("trs-remote.json ist beschädigt – Fernbedienung wird neu eingerichtet: {e}");
                    StoreFile::default()
                }
            };
            *guard = Some(Loaded { file, plain: HashMap::new() });
        }
        f(guard.as_mut().expect("gerade geladen"))
    }

    async fn save(&self) {
        let json = {
            let guard = self.inner.lock().await;
            let Some(loaded) = guard.as_ref() else { return };
            match serde_json::to_vec_pretty(&loaded.file) {
                Ok(json) => json,
                Err(e) => {
                    tracing::warn!("trs-remote.json konnte nicht serialisiert werden: {e}");
                    return;
                }
            }
        };
        if let Err(e) = fsutil::write_atomic(&self.path, &json).await {
            tracing::warn!("trs-remote.json konnte nicht gespeichert werden: {e}");
        }
    }

    pub(crate) async fn get(&self, account: &str, kind: DeviceKind) -> Option<DeviceCred> {
        let (cred, dirty) = self
            .with(|l| {
                let stored = l.file.devices.get(account)?.get(&kind)?.clone();
                let key = (account.to_owned(), kind);
                if let Some(plain) = l.plain.get(&key) {
                    return Some((Some(DeviceCred { id: stored.id, secret: plain.clone() }), false));
                }
                match crypto::unprotect(&stored.secret) {
                    Ok(plain) if device_id(&stored.id) && secret(&plain) => {
                        l.plain.insert(key, plain.clone());
                        Some((Some(DeviceCred { id: stored.id, secret: plain }), false))
                    }
                    _ => {
                        // Anderer Benutzer/Rechner oder kaputt: neu anmelden.
                        if let Some(m) = l.file.devices.get_mut(account) {
                            m.remove(&kind);
                        }
                        Some((None, true))
                    }
                }
            })
            .await
            .unwrap_or((None, false));
        if dirty {
            self.save().await;
        }
        cred
    }

    async fn put(&self, account: &str, kind: DeviceKind, id: &str, plain: &str) -> Result<()> {
        let encrypted = crypto::protect(plain)?;
        self.with(|l| {
            l.file.devices.entry(account.to_owned()).or_default().insert(kind, StoredDevice { id: id.to_owned(), secret: encrypted });
            l.plain.insert((account.to_owned(), kind), plain.to_owned());
        })
        .await;
        self.save().await;
        Ok(())
    }

    async fn remove(&self, account: &str, kind: DeviceKind) {
        let removed = self
            .with(|l| {
                l.plain.remove(&(account.to_owned(), kind));
                let removed = l.file.devices.get_mut(account).and_then(|m| m.remove(&kind)).is_some();
                if l.file.devices.get(account).is_some_and(BTreeMap::is_empty) {
                    l.file.devices.remove(account);
                }
                removed
            })
            .await;
        if removed {
            self.save().await;
        }
    }

    /// `true`, wenn der Befehl neu ist (merkt ihn sich).
    fn first_time(&self, id: &str) -> bool {
        let mut seen = self.seen.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if seen.iter().any(|s| s == id) {
            return false;
        }
        if seen.len() >= SEEN_COMMANDS {
            seen.pop_front();
        }
        seen.push_back(id.to_owned());
        true
    }
}

// --- Ansichten ----------------------------------------------------------------------------

/// Instanz im Status eines PCs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusInstance {
    pub id: String,
    pub name: String,
    pub version: String,
    pub loader: String,
    #[serde(default)]
    pub icon_hash: Option<String>,
    pub running: bool,
}

/// Laufende Aufgabe im Status eines PCs (`progress` 0–1, `None` = unbestimmt).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusTask {
    pub title: String,
    #[serde(default)]
    pub progress: Option<f64>,
    #[serde(default)]
    pub instance_id: Option<String>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct StatusAllow {
    pub launch: bool,
    pub install: bool,
}

/// Stand eines PCs (§33.4).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteStatus {
    pub online: bool,
    #[serde(default)]
    pub allow: StatusAllow,
    #[serde(default)]
    pub instances: Vec<StatusInstance>,
    #[serde(default)]
    pub tasks: Vec<StatusTask>,
}

impl RemoteStatus {
    /// Wie der Server säubern: unbrauchbare/doppelte Instanzen weg, Texte gekürzt, Fortschritt 0–1.
    pub fn cleaned(self) -> Self {
        let mut seen = std::collections::HashSet::new();
        let instances = self
            .instances
            .into_iter()
            .filter(|i| instance_id(&i.id) && seen.insert(i.id.clone()))
            .take(MAX_STATUS_INSTANCES)
            .map(|i| {
                let name = clean_text(&i.name, 64);
                StatusInstance {
                    name: if name.is_empty() { i.id.clone() } else { name },
                    version: clean_text(&i.version, 32),
                    loader: clean_text(&i.loader, 16).to_ascii_lowercase(),
                    icon_hash: i
                        .icon_hash
                        .filter(|h| (8..=64).contains(&h.len()) && h.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))),
                    running: i.running,
                    id: i.id,
                }
            })
            .collect();
        let tasks = self
            .tasks
            .into_iter()
            .take(MAX_STATUS_TASKS)
            .map(|t| StatusTask {
                title: clean_text(&t.title, 80),
                progress: t.progress.filter(|p| p.is_finite()).map(|p| p.clamp(0.0, 1.0)),
                instance_id: t.instance_id.filter(|i| instance_id(i)),
            })
            .filter(|t| !t.title.is_empty())
            .collect();
        Self { online: self.online, allow: self.allow, instances, tasks }
    }
}

/// Gekoppeltes Gerät (am PC: Handys, am Handy: PCs mit Status).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RemotePeer {
    pub id: String,
    pub kind: DeviceKind,
    pub name: String,
    pub paired_at: Option<String>,
    pub last_seen_at: Option<String>,
    pub online: bool,
    pub status: Option<RemoteStatus>,
    pub status_at: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiPeer {
    id: String,
    kind: DeviceKind,
    #[serde(default)]
    name: String,
    #[serde(default)]
    paired_at: Option<String>,
    #[serde(default)]
    last_seen_at: Option<String>,
    #[serde(default)]
    online: Option<bool>,
    #[serde(default)]
    status: Option<RemoteStatus>,
    #[serde(default)]
    status_at: Option<String>,
}

impl ApiPeer {
    fn cleaned(self) -> Option<RemotePeer> {
        if !device_id(&self.id) {
            return None;
        }
        let name = clean_text(&self.name, 48);
        let desktop = self.kind == DeviceKind::Desktop;
        Some(RemotePeer {
            name: if name.is_empty() { "?".into() } else { name },
            kind: self.kind,
            paired_at: time(self.paired_at),
            last_seen_at: time(self.last_seen_at),
            online: desktop && self.online.unwrap_or(false),
            status: if desktop { self.status.map(RemoteStatus::cleaned) } else { None },
            status_at: if desktop { time(self.status_at) } else { None },
            id: self.id,
        })
    }
}

/// Gekoppelte Geräte + eigene Geräte-ID.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RemotePairings {
    pub device_id: String,
    pub peers: Vec<RemotePeer>,
}

/// Kopplungs-Code am PC (für Anzeige und QR-Code).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairCode {
    /// `XXX-XXX`
    pub code: String,
    /// `trs-launcher://remote-pair/XXXXXX` (selbst gebaut, nicht vom Server übernommen).
    pub link: String,
    pub expires_at: Option<String>,
    pub expires_in: u32,
}

/// Befehl, wie ihn das Handy nach dem Senden sieht.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SentCommand {
    pub id: String,
    pub desktop_id: String,
    pub command_type: String,
    pub state: String,
    pub expires_at: Option<String>,
    pub duplicate: bool,
}

/// Geprüfter Befehl vom Handy (an die Oberfläche des PCs).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteCommand {
    pub id: String,
    pub phone_id: String,
    pub phone_name: String,
    pub command_type: String,
    /// Bei `launch_instance`/`stop_instance`.
    pub instance_id: Option<String>,
    /// Bei `install_pack_code`: `TRS-XXXX-XXXX`.
    pub code: Option<String>,
}

/// Was der Server beim `claim` bestätigt (maßgeblich für die Ausführung).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClaimedCommand {
    pub command_type: String,
    pub instance_id: Option<String>,
    pub code: Option<String>,
}

/// Status-Meldung aus der Oberfläche (der Kern ergänzt `online`/`allow` aus den Einstellungen).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusInput {
    pub instances: Vec<StatusInstance>,
    #[serde(default)]
    pub tasks: Vec<StatusTask>,
}

// --- Prüfung der Befehle ------------------------------------------------------------------

/// Art + Argumente prüfen (`instanceId` wie im Launcher, Pack-Code normalisiert). `None` = verwerfen.
fn checked_args(kind: &str, args: &serde_json::Value) -> Option<(String, Option<String>, Option<String>)> {
    if !COMMAND_TYPES.contains(&kind) {
        return None;
    }
    let get = |k: &str| args.get(k).and_then(serde_json::Value::as_str);
    match kind {
        "launch_instance" | "stop_instance" => {
            let id = get("instanceId").filter(|i| instance_id(i))?;
            Some((kind.to_owned(), Some(id.to_owned()), None))
        }
        "install_pack_code" => Some((kind.to_owned(), None, Some(super::packs::normalize_code(get("code")?)?))),
        _ => Some((kind.to_owned(), None, None)),
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Envelope {
    desktop_id: String,
    payload: String,
    sig: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Payload {
    v: u32,
    id: String,
    desktop_id: String,
    phone_id: String,
    #[serde(default)]
    phone_name: String,
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    args: serde_json::Value,
    expires_at: i64,
}

/// Prüft ein `remote_command`-Ereignis gegen das eigene Gerät: Ziel, Signatur (konstante Laufzeit), Version, Art,
/// Argumente und grob den Ablauf. `now_ms` = eigene Uhr.
pub(crate) fn verify_command(cred: &DeviceCred, data: &str, now_ms: i64) -> Option<RemoteCommand> {
    let env: Envelope = serde_json::from_str(data).ok()?;
    if env.desktop_id != cred.id || env.payload.len() > MAX_PAYLOAD_BYTES || env.sig.len() > 64 {
        return None;
    }
    let sig = URL_SAFE_NO_PAD.decode(env.sig.as_bytes()).ok()?;
    let mut mac = <Hmac<Sha256> as KeyInit>::new_from_slice(&cred.command_key()).ok()?;
    mac.update(env.payload.as_bytes());
    mac.verify_slice(&sig).ok()?;
    let p: Payload = serde_json::from_str(&env.payload).ok()?;
    if p.v != 1 || p.desktop_id != cred.id || !device_id(&p.id) || !device_id(&p.phone_id) {
        return None;
    }
    if now_ms > p.expires_at.saturating_add(CLOCK_SLACK_MS) {
        return None;
    }
    let (command_type, instance_id, code) = checked_args(&p.kind, &p.args)?;
    let phone_name = clean_text(&p.phone_name, 48);
    Some(RemoteCommand {
        id: p.id,
        phone_id: p.phone_id,
        phone_name: if phone_name.is_empty() { "?".into() } else { phone_name },
        command_type,
        instance_id,
        code,
    })
}

// --- Echtzeit-Ereignisse ---------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LiveData {
    #[serde(default)]
    command_id: Option<String>,
    #[serde(default)]
    desktop_id: Option<String>,
    #[serde(default)]
    phone_id: Option<String>,
    #[serde(default)]
    command_type: Option<String>,
    #[serde(default)]
    state: Option<String>,
    #[serde(default)]
    error: Option<String>,
    #[serde(default)]
    online: Option<bool>,
    #[serde(default)]
    status: Option<RemoteStatus>,
    #[serde(default)]
    at: Option<String>,
    #[serde(default)]
    action: Option<String>,
    #[serde(default)]
    desktop: Option<ApiPeer>,
    #[serde(default)]
    phone: Option<ApiPeer>,
}

/// `remote_command_update`, `remote_status`, `remote_pairing` → gesäubert. `remote_command` läuft NICHT hierüber
/// (braucht die Signaturprüfung mit dem eigenen Geheimnis, siehe [`TrsApi::remote_verify`]).
pub(crate) fn decode_live(event: &str, data: &str) -> Option<LiveEvent> {
    let d: LiveData = serde_json::from_str(data).ok()?;
    let id = |v: Option<String>| v.filter(|i| device_id(i));
    Some(match event {
        "remote_command_update" => LiveEvent::RemoteCommandUpdate {
            command_id: id(d.command_id)?,
            desktop_id: id(d.desktop_id)?,
            phone_id: id(d.phone_id)?,
            command_type: d.command_type.filter(|t| COMMAND_TYPES.contains(&t.as_str()))?,
            state: d.state.filter(|s| matches!(s.as_str(), "pending" | "running" | "done" | "failed"))?,
            error: d.error.filter(|e| error_code(e)),
        },
        "remote_status" => LiveEvent::RemoteStatus {
            desktop_id: id(d.desktop_id)?,
            online: d.online.unwrap_or(false),
            status: Box::new(d.status?.cleaned()),
            at: time(d.at),
        },
        "remote_pairing" => LiveEvent::RemotePairing {
            action: d.action.filter(|a| matches!(a.as_str(), "added" | "removed"))?,
            desktop_id: id(d.desktop_id)?,
            phone_id: id(d.phone_id)?,
            desktop: d.desktop.and_then(ApiPeer::cleaned).map(Box::new),
            phone: d.phone.and_then(ApiPeer::cleaned).map(Box::new),
        },
        _ => return None,
    })
}

impl TrsApi {
    /// `remote_command` aus dem Echtzeit-Kanal prüfen: nur Befehle an das eigene PC-Gerät dieses Kontos, mit
    /// gültiger Signatur, jeder nur einmal.
    pub(crate) async fn remote_verify(&self, account: &str, data: &str) -> Option<RemoteCommand> {
        let cred = self.remote.get(account, DeviceKind::Desktop).await?;
        let command = verify_command(&cred, data, chrono::Utc::now().timestamp_millis())?;
        self.remote.first_time(&command.id).then_some(command)
    }
}

// --- Fehler ---------------------------------------------------------------------------------

fn disabled() -> Error {
    Error::validation(crate::msg!("remote.disabled", "Die Fernbedienung ist in den Einstellungen ausgeschaltet."))
}

fn not_allowed() -> Error {
    Error::validation(crate::msg!("remote.notAllowed", "Diese Aktion ist für die Fernbedienung nicht erlaubt (Einstellungen → Fernbedienung)."))
}

fn invalid_code() -> Error {
    Error::validation(crate::msg!("remote.invalidCode", "Das ist kein gültiger Kopplungs-Code (Form: XXX-XXX)."))
}

fn invalid_command() -> Error {
    Error::validation(crate::msg!("remote.invalidCommand", "Dieser Befehl ist ungültig."))
}

/// Eigene Texte für die Fehler der Fernbedienung.
fn remote_error(e: Error) -> Error {
    let Error::TrsApi { kind, code, msg } = e else { return e };
    let msg = match code.as_str() {
        "remote_device_invalid" => crate::msg!("remote.deviceInvalid", "Dieses Gerät ist für die Fernbedienung nicht (mehr) angemeldet – bitte neu koppeln."),
        "remote_wrong_account" => crate::msg!(
            "remote.wrongAccount",
            "Der PC ist mit einem anderen TRS-Konto angemeldet – nutze auf beiden Geräten dasselbe Konto."
        ),
        "remote_code_expired" => crate::msg!("remote.codeExpired", "Diesen Kopplungs-Code gibt es nicht (mehr) – lass am PC einen neuen anzeigen."),
        "invalid_code" => crate::msg!("remote.invalidCode", "Das ist kein gültiger Kopplungs-Code (Form: XXX-XXX)."),
        "remote_pairing_limit" => crate::msg!("remote.pairingLimit", "An diesem PC sind schon 10 Handys gekoppelt – entferne zuerst eins."),
        "remote_peer_not_found" => crate::msg!("remote.peerNotFound", "Dieses Gerät ist nicht (mehr) gekoppelt."),
        "remote_offline" => crate::msg!("remote.offline", "Der PC ist offline oder die Fernbedienung ist dort ausgeschaltet."),
        "remote_command_disabled" => crate::msg!("remote.commandDisabled", "Das ist im Launcher am PC ausgeschaltet."),
        "remote_command_expired" => crate::msg!("remote.commandExpired", "Der Befehl ist abgelaufen."),
        "remote_command_claimed" | "remote_command_not_running" => crate::msg!("remote.commandHandled", "Der Befehl wurde schon bearbeitet."),
        "invalid_args" => crate::msg!("remote.invalidCommand", "Dieser Befehl ist ungültig."),
        _ => msg,
    };
    Error::TrsApi { kind, code, msg }
}

/// Name dieses Geräts für die Liste der gekoppelten Geräte.
fn default_device_name(kind: DeviceKind) -> String {
    let name = match kind {
        DeviceKind::Desktop => std::env::var("COMPUTERNAME")
            .ok()
            .or_else(|| std::env::var("HOSTNAME").ok())
            .or_else(|| std::fs::read_to_string("/etc/hostname").ok())
            .unwrap_or_default(),
        DeviceKind::Phone if cfg!(target_os = "android") => "Android".into(),
        DeviceKind::Phone if cfg!(target_os = "ios") => "iPhone".into(),
        DeviceKind::Phone => String::new(),
    };
    let name = clean_text(&name, 48);
    if !name.is_empty() {
        name
    } else if kind == DeviceKind::Desktop {
        "PC".into()
    } else {
        "Phone".into()
    }
}

#[derive(Deserialize)]
struct ApiRegistered {
    device: ApiRegisteredDevice,
    secret: String,
}

#[derive(Deserialize)]
struct ApiRegisteredDevice {
    id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiPairCode {
    code: String,
    #[serde(default)]
    expires_at: Option<String>,
    #[serde(default)]
    expires_in: Option<u32>,
}

#[derive(Deserialize)]
struct ApiPairings {
    #[serde(default)]
    peers: Vec<ApiPeer>,
}

#[derive(Deserialize)]
struct ApiConfirmed {
    desktop: ApiPeer,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiCommand {
    id: String,
    desktop_id: String,
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    state: String,
    #[serde(default)]
    expires_at: Option<String>,
}

#[derive(Deserialize)]
struct ApiSent {
    command: ApiCommand,
    #[serde(default)]
    duplicate: bool,
}

#[derive(Deserialize)]
struct ApiClaimed {
    command: ApiClaimedCommand,
}

#[derive(Deserialize)]
struct ApiClaimedCommand {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    args: serde_json::Value,
}

// --- Launcher ---------------------------------------------------------------------------------

impl Launcher {
    /// Zugangsdaten des Geräts (meldet es beim ersten Mal an).
    async fn remote_device(&self, account: &str, kind: DeviceKind) -> Result<DeviceCred> {
        let store = &self.trs.remote;
        if let Some(cred) = store.get(account, kind).await {
            return Ok(cred);
        }
        let _guard = store.register_lock.lock().await;
        if let Some(cred) = store.get(account, kind).await {
            return Ok(cred);
        }
        let req = Req::post("/v1/remote/devices", json!({ "kind": kind.as_str(), "name": default_device_name(kind) }));
        let bytes = self.trs.call_raw(self.accounts(), account, &req).await.map_err(remote_error)?;
        let r: ApiRegistered = super::parse(&bytes, "remote devices")?;
        if !device_id(&r.device.id) || !secret(&r.secret) {
            return Err(super::bad_response());
        }
        store.put(account, kind, &r.device.id, &r.secret).await?;
        tracing::info!("Fernbedienung: Gerät angemeldet ({})", kind.as_str());
        Ok(DeviceCred { id: r.device.id, secret: r.secret })
    }

    /// Anfrage als Gerät `kind` des aktiven Kontos. Kennt der Server das Gerät nicht mehr, wird es genau einmal neu
    /// angemeldet (am Handy sind die Kopplungen dann weg).
    async fn remote_call(&self, kind: DeviceKind, req: Req) -> Result<(Vec<u8>, String)> {
        let account = self.trs_account().await?;
        let mut retried = false;
        loop {
            let cred = self.remote_device(&account, kind).await?;
            let request = req.clone().header(DEVICE_HEADER, cred.header());
            match self.trs.call_raw(self.accounts(), &account, &request).await {
                Err(Error::TrsApi { code, .. }) if !retried && code == "remote_device_invalid" => {
                    self.trs.remote.remove(&account, kind).await;
                    retried = true;
                }
                Ok(bytes) => return Ok((bytes, cred.id)),
                Err(e) => return Err(remote_error(e)),
            }
        }
    }

    async fn remote_settings(&self) -> crate::settings::RemoteSettings {
        self.settings().await.remote
    }

    // --- PC -------------------------------------------------------------------------------

    /// PC: Kopplungs-Code für ein Handy (2 min, einmalig). Nur mit eingeschalteter Fernbedienung.
    pub async fn remote_pair_start(&self) -> Result<PairCode> {
        if !self.remote_settings().await.enabled {
            return Err(disabled());
        }
        let (bytes, _) = self.remote_call(DeviceKind::Desktop, Req::post_empty("/v1/remote/pair")).await?;
        let r: ApiPairCode = super::parse(&bytes, "remote pair")?;
        let code = normalize_pair_code(&r.code).ok_or_else(super::bad_response)?;
        Ok(PairCode {
            link: format!("{}://{LINK_KIND}/{}", crate::pack_share::LINK_SCHEME, code.replace('-', "")),
            code,
            expires_at: time(r.expires_at),
            expires_in: r.expires_in.unwrap_or(120).clamp(1, 600),
        })
    }

    /// PC: offenen Code zurückziehen (Dialog geschlossen). Fehler sind egal – der Code läuft ohnehin ab.
    pub async fn remote_pair_cancel(&self) -> Result<()> {
        let account = self.trs_account().await?;
        if self.trs.remote.get(&account, DeviceKind::Desktop).await.is_none() {
            return Ok(());
        }
        let _ = self.remote_call(DeviceKind::Desktop, Req::delete("/v1/remote/pair")).await;
        Ok(())
    }

    /// PC: eigenen Stand melden. `online`/`allow` kommen aus den Einstellungen; mit ausgeschalteter Fernbedienung
    /// geht nur „offline“ raus (und auch das nur, wenn das Gerät schon angemeldet ist).
    pub async fn remote_publish_status(&self, input: StatusInput) -> Result<()> {
        let settings = self.remote_settings().await;
        let account = self.trs_account().await?;
        if !settings.enabled && self.trs.remote.get(&account, DeviceKind::Desktop).await.is_none() {
            return Ok(());
        }
        let status = RemoteStatus {
            online: settings.enabled,
            allow: StatusAllow { launch: settings.allow_launch, install: settings.allow_install },
            instances: if settings.enabled { input.instances } else { Vec::new() },
            tasks: if settings.enabled { input.tasks } else { Vec::new() },
        }
        .cleaned();
        let body = serde_json::to_value(&status).map_err(|e| Error::json("remote status", e))?;
        self.remote_call(DeviceKind::Desktop, Req::post("/v1/remote/status", body)).await?;
        Ok(())
    }

    /// PC: Befehl beim Server abholen – nur mit eingeschalteter Fernbedienung und erlaubter Befehlsart. Die
    /// Antwort des Servers ist maßgeblich (Art und Argumente werden erneut geprüft).
    pub async fn remote_claim(&self, id: &str) -> Result<ClaimedCommand> {
        if !device_id(id) {
            return Err(invalid_command());
        }
        let settings = self.remote_settings().await;
        if !settings.enabled {
            return Err(disabled());
        }
        let (bytes, _) = self.remote_call(DeviceKind::Desktop, Req::post_empty(format!("/v1/remote/commands/{id}/claim"))).await?;
        let r: ApiClaimed = super::parse(&bytes, "remote claim")?;
        let (command_type, instance_id, code) = checked_args(&r.command.kind, &r.command.args).ok_or_else(invalid_command)?;
        let allowed = match command_type.as_str() {
            "launch_instance" | "stop_instance" => settings.allow_launch,
            "install_pack_code" => settings.allow_install,
            _ => true,
        };
        if !allowed {
            // Abgeholt, aber nicht erlaubt: dem Handy sagen, warum nichts passiert.
            let _ = self.remote_result(id, false, Some("disabled")).await;
            return Err(not_allowed());
        }
        Ok(ClaimedCommand { command_type, instance_id, code })
    }

    /// PC: Ergebnis eines abgeholten Befehls (`error` = kurzer Code wie `instance_not_found`).
    pub async fn remote_result(&self, id: &str, ok: bool, error: Option<&str>) -> Result<()> {
        if !device_id(id) {
            return Err(invalid_command());
        }
        let error = if ok { None } else { Some(error.filter(|e| error_code(e)).unwrap_or("failed")) };
        let req = Req::post(format!("/v1/remote/commands/{id}/result"), json!({ "ok": ok, "error": error }));
        self.remote_call(DeviceKind::Desktop, req).await.map(|_| ())
    }

    /// Beim Beenden: gekoppelten Handys „offline“ melden (kurz, Fehler egal).
    pub(crate) async fn remote_shutdown(&self) {
        if !self.remote_settings().await.enabled {
            return;
        }
        let Ok(Some(account)) = self.accounts().active_id().await else { return };
        let Some(cred) = self.trs.remote.get(&account, DeviceKind::Desktop).await else { return };
        let body = json!({ "online": false, "allow": { "launch": false, "install": false }, "instances": [], "tasks": [] });
        let req = Req::post("/v1/remote/status", body).header(DEVICE_HEADER, cred.header());
        let _ = tokio::time::timeout(Duration::from_secs(3), self.trs.call_raw(self.accounts(), &account, &req)).await;
    }

    // --- Handy ------------------------------------------------------------------------------

    /// Handy: Code des PCs einlösen (eingetippt oder aus dem QR-Code).
    pub async fn remote_pair_confirm(&self, input: &str) -> Result<RemotePeer> {
        let code = normalize_pair_code(input).ok_or_else(invalid_code)?;
        let req = Req::post("/v1/remote/pair/confirm", json!({ "code": code }));
        let (bytes, _) = self.remote_call(DeviceKind::Phone, req).await?;
        let r: ApiConfirmed = super::parse(&bytes, "remote confirm")?;
        r.desktop.cleaned().filter(|p| p.kind == DeviceKind::Desktop).ok_or_else(super::bad_response)
    }

    /// Handy: Befehl an einen gekoppelten PC. `idempotency_key` vom Aufrufer (gleicher Schlüssel = derselbe Befehl).
    pub async fn remote_send(&self, desktop_id: &str, command_type: &str, instance: Option<&str>, pack_code: Option<&str>, key: &str) -> Result<SentCommand> {
        if !device_id(desktop_id) || !idempotency_key(key) {
            return Err(invalid_command());
        }
        let args = match command_type {
            "launch_instance" | "stop_instance" => json!({ "instanceId": instance.filter(|i| instance_id(i)).ok_or_else(invalid_command)? }),
            "install_pack_code" => json!({ "code": super::packs::normalize_code(pack_code.unwrap_or_default()).ok_or_else(|| Error::validation(crate::msg!("packShare.invalidCode", "Das ist kein gültiger Modpack-Code (Form: TRS-XXXX-XXXX).")))? }),
            "ping" => json!({}),
            _ => return Err(invalid_command()),
        };
        let req = Req::post(
            format!("/v1/remote/{desktop_id}/commands"),
            json!({ "type": command_type, "args": args, "idempotencyKey": key }),
        );
        let (bytes, _) = self.remote_call(DeviceKind::Phone, req).await?;
        let r: ApiSent = super::parse(&bytes, "remote command")?;
        let c = r.command;
        if !device_id(&c.id) || c.desktop_id != desktop_id || !COMMAND_TYPES.contains(&c.kind.as_str()) {
            return Err(super::bad_response());
        }
        Ok(SentCommand {
            id: c.id,
            desktop_id: c.desktop_id,
            command_type: c.kind,
            state: if matches!(c.state.as_str(), "pending" | "running" | "done" | "failed") { c.state } else { "pending".into() },
            expires_at: time(c.expires_at),
            duplicate: r.duplicate,
        })
    }

    // --- Beide Seiten -------------------------------------------------------------------------

    /// Gekoppelte Geräte (am PC die Handys, am Handy die PCs mit Status).
    pub async fn remote_pairings(&self, kind: DeviceKind) -> Result<RemotePairings> {
        let (bytes, device_id) = self.remote_call(kind, Req::get("/v1/remote/pairings")).await?;
        let r: ApiPairings = super::parse(&bytes, "remote pairings")?;
        let want = match kind {
            DeviceKind::Desktop => DeviceKind::Phone,
            DeviceKind::Phone => DeviceKind::Desktop,
        };
        Ok(RemotePairings { device_id, peers: r.peers.into_iter().filter_map(ApiPeer::cleaned).filter(|p| p.kind == want).take(50).collect() })
    }

    /// Kopplung lösen.
    pub async fn remote_unpair(&self, kind: DeviceKind, peer_id: &str) -> Result<()> {
        if !device_id(peer_id) {
            return Err(remote_error(super::api_error(404, "remote_peer_not_found", None)));
        }
        self.remote_call(kind, Req::delete(format!("/v1/remote/pairings/{peer_id}"))).await.map(|_| ())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cred() -> DeviceCred {
        DeviceCred { id: "AAAAAAAAAAAAAAAAAAAAAA".into(), secret: "s".repeat(43) }
    }

    fn sign(cred: &DeviceCred, payload: &str) -> String {
        let mut mac = <Hmac<Sha256> as KeyInit>::new_from_slice(&cred.command_key()).unwrap();
        mac.update(payload.as_bytes());
        URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
    }

    fn event(cred: &DeviceCred, payload: &serde_json::Value) -> String {
        let payload = payload.to_string();
        json!({ "type": "remote_command", "desktopId": cred.id, "sig": sign(cred, &payload), "payload": payload }).to_string()
    }

    fn payload(kind: &str, args: serde_json::Value) -> serde_json::Value {
        json!({ "v": 1, "id": "BBBBBBBBBBBBBBBBBBBBBB", "desktopId": "AAAAAAAAAAAAAAAAAAAAAA", "phoneId": "CCCCCCCCCCCCCCCCCCCCCC",
            "phoneName": "Pixel\u{202E} 9", "type": kind, "args": args, "issuedAt": 1_000, "expiresAt": 61_000 })
    }

    #[test]
    fn pair_codes_accept_links_and_any_spelling() {
        assert_eq!(normalize_pair_code("k7q 2mx").as_deref(), Some("K7Q-2MX"));
        assert_eq!(normalize_pair_code("trs-launcher://remote-pair/K7Q2MX").as_deref(), Some("K7Q-2MX"));
        assert_eq!(pair_code_from_link("TRS-LAUNCHER://Remote-Pair/k7q2mx/").as_deref(), Some("K7Q-2MX"));
        for bad in ["K7Q2M", "K0Q2MX", "https://remote-pair/K7Q2MX", "trs-launcher://pack/K7Q2MX", "", &"x".repeat(100)] {
            assert!(normalize_pair_code(bad).is_none(), "{bad}");
        }
        assert!(pair_code_from_link("K7Q2MX").is_none(), "nur Links");
    }

    #[test]
    fn verifies_signature_target_and_arguments() {
        let c = cred();
        let ok = verify_command(&c, &event(&c, &payload("launch_instance", json!({ "instanceId": "fabric-1-21" }))), 2_000).unwrap();
        assert_eq!(ok.command_type, "launch_instance");
        assert_eq!(ok.instance_id.as_deref(), Some("fabric-1-21"));
        assert_eq!(ok.phone_name, "Pixel 9");
        let pack = verify_command(&c, &event(&c, &payload("install_pack_code", json!({ "code": "trs-7k2m-q9xa" }))), 2_000).unwrap();
        assert_eq!(pack.code.as_deref(), Some("TRS-7K2M-Q9XA"));

        // Falsche Signatur / anderes Geheimnis.
        let other = DeviceCred { id: c.id.clone(), secret: "t".repeat(43) };
        assert!(verify_command(&c, &event(&other, &payload("ping", json!({}))), 2_000).is_none());
        // Inhalt nach dem Signieren geändert.
        let p = payload("ping", json!({})).to_string();
        let tampered = json!({ "desktopId": c.id, "sig": sign(&c, &p), "payload": p.replace("ping", "stop_instance") }).to_string();
        assert!(verify_command(&c, &tampered, 2_000).is_none());
        // Anderer PC.
        let mut wrong = payload("ping", json!({}));
        wrong["desktopId"] = json!("DDDDDDDDDDDDDDDDDDDDDD");
        assert!(verify_command(&c, &event(&c, &wrong), 2_000).is_none());
        // Unbekannte Art, Pfad als Instanz-ID, kaputter Code.
        assert!(verify_command(&c, &event(&c, &payload("format_disk", json!({}))), 2_000).is_none());
        assert!(verify_command(&c, &event(&c, &payload("launch_instance", json!({ "instanceId": "../../x" }))), 2_000).is_none());
        assert!(verify_command(&c, &event(&c, &payload("launch_instance", json!({ "instanceId": "con" }))), 2_000).is_none());
        assert!(verify_command(&c, &event(&c, &payload("install_pack_code", json!({ "code": "nope" }))), 2_000).is_none());
        // Weit abgelaufen.
        assert!(verify_command(&c, &event(&c, &payload("ping", json!({}))), 61_000 + CLOCK_SLACK_MS + 1).is_none());
        // Kaputt.
        assert!(verify_command(&c, "{}", 0).is_none());
        assert!(verify_command(&c, &json!({ "desktopId": c.id, "sig": "!!", "payload": "x" }).to_string(), 0).is_none());
    }

    #[test]
    fn status_is_cleaned_like_the_server() {
        let raw: RemoteStatus = serde_json::from_value(json!({
            "online": true, "allow": { "launch": true, "install": false },
            "instances": [
                { "id": "fabric-1-21", "name": "My\u{202E}  Pack", "version": "1.21.11", "loader": "Fabric", "iconHash": "AB", "running": true },
                { "id": "../evil", "name": "x", "version": "1", "loader": "x", "running": false },
                { "id": "fabric-1-21", "name": "dup", "version": "1", "loader": "x", "running": false }
            ],
            "tasks": [ { "title": "Laden", "progress": 7.0, "instanceId": "NOPE" }, { "title": "\u{0007}", "progress": null } ]
        }))
        .unwrap();
        let s = raw.cleaned();
        assert_eq!(s.instances.len(), 1);
        assert_eq!(s.instances[0].name, "My Pack");
        assert_eq!(s.instances[0].loader, "fabric");
        assert_eq!(s.instances[0].icon_hash, None);
        assert_eq!(s.tasks, vec![StatusTask { title: "Laden".into(), progress: Some(1.0), instance_id: None }]);
    }

    #[test]
    fn live_events_are_decoded_and_checked() {
        let ev = decode_live(
            "remote_command_update",
            &json!({ "commandId": "BBBBBBBBBBBBBBBBBBBBBB", "desktopId": "AAAAAAAAAAAAAAAAAAAAAA", "phoneId": "CCCCCCCCCCCCCCCCCCCCCC",
                "commandType": "launch_instance", "state": "failed", "error": "instance_not_found" })
            .to_string(),
        )
        .unwrap();
        let out = serde_json::to_value(&ev).unwrap();
        assert_eq!(out["type"], "remote_command_update");
        assert_eq!(out["error"], "instance_not_found");
        assert!(decode_live("remote_command_update", &json!({ "commandId": "x", "state": "done" }).to_string()).is_none());
        let status = decode_live(
            "remote_status",
            &json!({ "desktopId": "AAAAAAAAAAAAAAAAAAAAAA", "online": true, "at": "2026-10-03T10:00:00.000Z",
                "status": { "online": true, "allow": { "launch": true, "install": true }, "instances": [], "tasks": [] } })
            .to_string(),
        )
        .unwrap();
        assert_eq!(serde_json::to_value(&status).unwrap()["status"]["allow"]["launch"], true);
        let pairing = decode_live(
            "remote_pairing",
            &json!({ "action": "added", "desktopId": "AAAAAAAAAAAAAAAAAAAAAA", "phoneId": "CCCCCCCCCCCCCCCCCCCCCC",
                "phone": { "id": "CCCCCCCCCCCCCCCCCCCCCC", "kind": "phone", "name": "Pixel", "createdAt": "x", "pairedAt": "y", "lastSeenAt": "z" } })
            .to_string(),
        )
        .unwrap();
        assert_eq!(serde_json::to_value(&pairing).unwrap()["phone"]["name"], "Pixel");
        // remote_command nur über die Signaturprüfung.
        assert!(decode_live("remote_command", "{}").is_none());
    }

    #[tokio::test]
    async fn store_keeps_secrets_encrypted_and_remembers_commands_once() {
        let dir = tempfile::tempdir().unwrap();

        let path = dir.path().join("trs-remote.json");
        let store = RemoteStore::new(path.clone());
        let account = "0123456789abcdef0123456789abcdef";
        store.put(account, DeviceKind::Desktop, "AAAAAAAAAAAAAAAAAAAAAA", &"s".repeat(43)).await.unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(!text.contains(&"s".repeat(43)), "Geheimnis nie im Klartext");
        let fresh = RemoteStore::new(path);
        let cred = fresh.get(account, DeviceKind::Desktop).await.unwrap();
        assert_eq!(cred.header(), format!("AAAAAAAAAAAAAAAAAAAAAA.{}", "s".repeat(43)));
        assert!(fresh.get(account, DeviceKind::Phone).await.is_none());
        fresh.remove(account, DeviceKind::Desktop).await;
        assert!(fresh.get(account, DeviceKind::Desktop).await.is_none());
        assert!(fresh.first_time("x") && !fresh.first_time("x"));
        assert!(!format!("{cred:?}").contains("sss"), "Debug zeigt kein Geheimnis");
    }

    // --- Auf Launcher-Ebene gegen eine nachgebaute API ---------------------------------------------

    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::super::testkit::{MockServer, Request, Response};
    use super::super::tests::signed_in;

    const DEV: &str = "DDDDDDDDDDDDDDDDDDDDDD";
    const CMD: &str = "BBBBBBBBBBBBBBBBBBBBBB";

    /// Fernbedienungs-API: Gerät `n` bekommt das Geheimnis `n`×43; gültig ist nur das zuletzt angemeldete Gerät.
    fn remote_api(registered: Arc<AtomicUsize>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
        move |req: &Request| {
            let secret = |n: usize| ((b'a' + n as u8) as char).to_string().repeat(43);
            let current = registered.load(Ordering::SeqCst);
            let device_ok = current > 0 && req.header("x-trs-device") == Some(format!("{DEV}.{}", secret(current - 1)).as_str());
            match (req.method.as_str(), req.path.as_str()) {
                ("POST", "/v1/remote/devices") => {
                    let n = registered.fetch_add(1, Ordering::SeqCst);
                    Response::json(201, json!({ "device": { "id": DEV, "kind": "desktop", "name": "PC" }, "secret": secret(n) }))
                }
                _ if !device_ok => Response::error(403, "remote_device_invalid"),
                ("POST", "/v1/remote/pair") => Response::json(
                    201,
                    json!({ "code": "K7Q-2MX", "link": "https://evil.example/x", "expiresAt": "2026-10-03T10:02:00.000Z", "expiresIn": 120 }),
                ),
                ("POST", p) if p == format!("/v1/remote/commands/{CMD}/claim") => Response::json(
                    200,
                    json!({ "command": { "type": "launch_instance", "args": { "instanceId": "fabric-1-21" } } }),
                ),
                ("POST", p) if p == format!("/v1/remote/commands/{CMD}/result") => Response::empty(204),
                ("POST", "/v1/remote/status") => Response::empty(204),
                _ => Response::error(404, "not_found"),
            }
        }
    }

    async fn set_remote(launcher: &Launcher, remote: crate::settings::RemoteSettings) {
        let settings = crate::settings::Settings { remote, ..launcher.settings().await };
        launcher.update_settings(settings).await.unwrap();
    }

    #[tokio::test]
    async fn nothing_goes_out_while_remote_control_is_off() {
        let registered = Arc::new(AtomicUsize::new(0));
        let server = MockServer::start(remote_api(Arc::clone(&registered))).await;
        let (_dir, launcher) = signed_in(&server).await;
        let err = launcher.remote_pair_start().await.unwrap_err();
        assert_eq!(err.message_code(), "remote.disabled");
        assert_eq!(launcher.remote_claim(CMD).await.unwrap_err().message_code(), "remote.disabled");
        launcher.remote_publish_status(StatusInput { instances: Vec::new(), tasks: Vec::new() }).await.unwrap();
        assert!(server.requests().is_empty(), "aus = keine einzige Anfrage");
    }

    #[tokio::test]
    async fn pairing_registers_once_and_re_registers_a_dropped_device() {
        let registered = Arc::new(AtomicUsize::new(0));
        let server = MockServer::start(remote_api(Arc::clone(&registered))).await;
        let (dir, launcher) = signed_in(&server).await;
        set_remote(&launcher, crate::settings::RemoteSettings { enabled: true, ..Default::default() }).await;

        let code = launcher.remote_pair_start().await.unwrap();
        assert_eq!(code.code, "K7Q-2MX");
        assert_eq!(code.link, "trs-launcher://remote-pair/K7Q2MX", "Link selbst gebaut, nicht vom Server");
        assert_eq!(server.hits("POST", "/v1/remote/devices")[0].json()["kind"], "desktop");
        let raw = std::fs::read_to_string(dir.path().join("trs-remote.json")).unwrap();
        assert!(!raw.contains(&"a".repeat(43)), "Geheimnis verschlüsselt");

        // Server kennt das Gerät nicht mehr (z. B. als ältestes verdrängt): genau einmal neu anmelden.
        registered.fetch_add(1, Ordering::SeqCst);
        launcher.remote_pair_start().await.unwrap();
        assert_eq!(server.hits("POST", "/v1/remote/devices").len(), 2);
        launcher.remote_pair_start().await.unwrap();
        assert_eq!(server.hits("POST", "/v1/remote/devices").len(), 2, "danach wieder das gespeicherte Gerät");
    }

    #[tokio::test]
    async fn claim_checks_the_per_command_switch_and_tells_the_phone() {
        let registered = Arc::new(AtomicUsize::new(0));
        let server = MockServer::start(remote_api(Arc::clone(&registered))).await;
        let (_dir, launcher) = signed_in(&server).await;
        set_remote(&launcher, crate::settings::RemoteSettings { enabled: true, allow_launch: false, allow_install: true }).await;
        let err = launcher.remote_claim(CMD).await.unwrap_err();
        assert_eq!(err.message_code(), "remote.notAllowed");
        let result = server.hits("POST", &format!("/v1/remote/commands/{CMD}/result"));
        assert_eq!(result[0].json(), json!({ "ok": false, "error": "disabled" }));

        set_remote(&launcher, crate::settings::RemoteSettings { enabled: true, allow_launch: true, allow_install: false }).await;
        let claimed = launcher.remote_claim(CMD).await.unwrap();
        assert_eq!(claimed.instance_id.as_deref(), Some("fabric-1-21"));
        assert!(launcher.remote_claim("../../x").await.is_err(), "IDs werden vor dem Senden geprüft");

        // Status: `online`/`allow` aus den Einstellungen, Instanzen gesäubert.
        launcher
            .remote_publish_status(StatusInput {
                instances: vec![StatusInstance {
                    id: "../evil".into(),
                    name: "x".into(),
                    version: "1".into(),
                    loader: "x".into(),
                    icon_hash: None,
                    running: false,
                }],
                tasks: Vec::new(),
            })
            .await
            .unwrap();
        let body = server.hits("POST", "/v1/remote/status")[0].json();
        assert_eq!(body["online"], true);
        assert_eq!(body["allow"], json!({ "launch": true, "install": false }));
        assert_eq!(body["instances"], json!([]));
    }
}
