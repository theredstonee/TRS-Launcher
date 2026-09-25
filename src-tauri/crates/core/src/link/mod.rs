//! TRS Link: lokaler Kanal zwischen TRS Client (Mod) und Launcher – Clips
//! und Kontowechsel im Spiel.
//!
//! Der Launcher lauscht nur auf `127.0.0.1:<zufälliger Port>`. Zeilenbasiertes
//! JSON, eingehend höchstens 1 KB je Zeile.
//!
//! ## Übergabe an das Spiel (Protokoll v2)
//! Je Spielstart ein neuer Schlüssel `K` (32 Zufallsbytes) und eine Sitzungs-ID
//! `sid` (8 Zufallsbytes). Beides geht **nur** über die Umgebungsvariable
//! `TRS_CLIENT_LINK=2:<port>:<sid>:<hex K>` an den Spielprozess – nie auf die
//! Platte, nie in die Befehlszeile, nie ins Log. `config/trsclient/clips.json`
//! enthält nur noch `{"version":2,"enabled":…,"port":…}` (kein Geheimnis; der
//! Port hilft der Mod, den Launcher nach einem Neustart wiederzufinden).
//! Mods ≤ 0.5.0 kennen nur das alte Format mit Token – für sie schreibt der
//! Launcher weiter `{"version":1,…,"token":…}`; diese v1-Verbindungen dürfen
//! nur Clips, nie Konten.
//!
//! ## Handschlag (gegenseitig, `K` geht nie über die Leitung)
//! 1. Mod → `{"type":"hello","v":2,"sid":…,"nonce":nc}`
//! 2. Launcher → `{"type":"challenge","nonce":nl,"proof":HMAC(K,"trs-link/2/launcher…"),"features":[…]}`
//! 3. Mod prüft den Beweis (echter Launcher?) → `{"type":"auth","proof":HMAC(K,"trs-link/2/game…")}`
//! 4. Launcher prüft (konstante Laufzeit) → Statuszeilen wie bisher.
//!
//! Zusätzlich muss die Gegenstelle zum Prozessbaum des gestarteten Spiels
//! gehören ([`peer`]); die erste Anmeldung muss binnen 15 Minuten nach dem
//! Start erfolgen. Das Minecraft-Token für `accounts.session` wird mit einem
//! aus `K` und beiden Nonces abgeleiteten Schlüssel versiegelt ([`proto`]).
//!
//! ## Warum das Minecraft-Token trotzdem in der Befehlszeile steht
//! Ein Start mit Platzhalter-Token, das die Mod dann über den Link holt, wäre
//! nicht robust: Der Minecraft-Konstruktor benutzt das Token auf mehreren
//! Versionen, bevor irgendein Mod-Code läuft (UserApiService, Profil-Schlüssel,
//! Realms, Telemetrie, die `Session` von Forge/Legacy); Vanilla-Instanzen und
//! andere Loader haben keine Mod, die es holen könnte; und ist der Link nicht
//! erreichbar (Launcher geschlossen, Virenscanner blockt Loopback), hätte das
//! Spiel keine gültige Sitzung und keinen Weg zurück. Deshalb wandert nur das
//! neue Link-Geheimnis von der Platte in die Umgebung des Spielprozesses.

pub(crate) mod bridge;
pub mod peer;
pub mod persist;
pub mod proto;

use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use futures::future::BoxFuture;
use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc, watch};

use crate::{Error, Result, fsutil};

/// Umgebungsvariable des Spielprozesses mit Port, Sitzungs-ID und Schlüssel.
pub const ENV_VAR: &str = "TRS_CLIENT_LINK";
pub const CONFIG_FILE: &str = "trsclient/clips.json";
/// Neueste Mod-Version, die nur das alte Token in clips.json kennt.
pub const LEGACY_MOD_MAX: &str = "0.5.0";
const MAX_LINE: usize = 1024;
const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_CONNECTIONS: usize = 16;
/// Tastendrücke schneller als das werden verworfen (Taste festgehalten, Prellen).
const COMMAND_COOLDOWN: Duration = Duration::from_millis(700);
/// Bis zur ersten erfolgreichen Anmeldung gilt eine Sitzung nur so lange.
const FIRST_AUTH_TTL: Duration = Duration::from_secs(15 * 60);
const DENY_DELAY: Duration = Duration::from_millis(300);
const LEGACY_VERSION: u32 = 1;
pub(crate) const PROTOCOL_VERSION: u32 = 2;

// --- Clips ------------------------------------------------------------------

/// Was die Mod zu Clips anzeigt.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkState {
    /// Aufnahme grundsätzlich möglich (Clips an, FFmpeg da, Fenster gefunden).
    pub available: bool,
    /// Warum nicht: `disabled`, `starting`, `noWindow`, `ffmpeg`, `error`.
    pub reason: Option<&'static str>,
    /// Ringpuffer läuft.
    pub buffer: bool,
    pub recording: bool,
    /// Seit wann (in ms vor dem Senden) die normale Aufnahme läuft.
    pub recording_ms: u64,
    /// Länge eines Sofort-Clips in Sekunden.
    pub clip_seconds: u32,
}

impl LinkState {
    pub fn disabled() -> Self {
        Self { reason: Some("disabled"), ..Default::default() }
    }
}

/// Einmalige Clip-Meldungen an die Mod.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum LinkEvent {
    /// Clip bzw. Aufnahme gespeichert (`seconds` = tatsächliche Länge).
    #[serde(rename_all = "camelCase")]
    Saved { kind: &'static str, seconds: u32 },
    #[serde(rename_all = "camelCase")]
    Failed { kind: &'static str, code: &'static str },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkCommand {
    SaveClip,
    ToggleRecording,
}

/// Empfänger der Clip-Tasten (Instanz, Befehl).
pub type CommandSink = Arc<dyn Fn(&str, LinkCommand) + Send + Sync>;

/// Inhalt von `config/trsclient/clips.json`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LinkConfig {
    pub version: u32,
    pub enabled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
    /// Nur für Mods ≤ 0.5.0 (Protokoll v1).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    /// Clip-Ordner dieser Instanz (für die Clip-Liste im Spiel); kein Geheimnis.
    #[serde(default, rename = "clipsDir", skip_serializing_if = "Option::is_none")]
    pub clips_dir: Option<String>,
}

impl LinkConfig {
    /// Kein Spiel/kein Link: nichts verbinden.
    pub fn disabled() -> Self {
        Self { version: PROTOCOL_VERSION, enabled: false, port: None, token: None, clips_dir: None }
    }
}

/// Schreibt die Datei für die Mod (atomar; nie mit dem v2-Schlüssel).
pub async fn write_config(game_dir: &Path, config: &LinkConfig) -> Result<()> {
    let file = game_dir.join("config").join(CONFIG_FILE);
    let dir = file.parent().expect("Elternordner");
    fsutil::ensure_dir(dir).await?;
    let json = serde_json::to_string_pretty(config).map_err(|e| Error::json("clips.json", e))?;
    fsutil::write_atomic(&file, json.as_bytes()).await
}

/// 256 Bit Zufall als 64 Hex-Zeichen (altes v1-Token).
pub fn new_token() -> String {
    proto::hex(&proto::random_bytes(32))
}

// --- Konten -----------------------------------------------------------------

/// Ein Launcher-Konto, wie die Mod es sieht – ohne Tokens.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkAccount {
    pub id: String,
    pub name: String,
    pub skin_url: Option<String>,
    /// Aktives Konto des Launchers.
    pub active: bool,
}

/// Frische Spielsitzung eines Kontos (das Token wird vor dem Senden versiegelt).
#[derive(Clone)]
pub struct LinkSession {
    pub id: String,
    pub name: String,
    pub xuid: String,
    pub token: String,
}

/// Fehlercodes an die Mod – nie interne Meldungen.
pub type HandlerResult<T> = std::result::Result<T, &'static str>;

/// Kontenzugriff des Launchers (umgesetzt in `lib.rs` über das `AccountStore`).
pub trait AccountsHandler: Send + Sync {
    fn list(&self) -> BoxFuture<'static, HandlerResult<Vec<LinkAccount>>>;
    fn session(&self, instance_id: String, account: String) -> BoxFuture<'static, HandlerResult<LinkSession>>;
    fn add(&self, instance_id: String) -> BoxFuture<'static, HandlerResult<LinkAccount>>;
}

// --- Server -----------------------------------------------------------------

#[derive(Debug, Clone)]
enum Push {
    Clip(LinkEvent),
    AccountsChanged,
}

#[derive(Default)]
struct Limits {
    last_list: Option<Instant>,
    last_session: Option<Instant>,
    sessions: VecDeque<Instant>,
    add_running: bool,
    adds: VecDeque<Instant>,
}

const WINDOW: Duration = Duration::from_secs(10 * 60);

fn prune(times: &mut VecDeque<Instant>, now: Instant) {
    while times.front().is_some_and(|t| now.duration_since(*t) > WINDOW) {
        times.pop_front();
    }
}

impl Limits {
    fn allow_list(&mut self, now: Instant) -> bool {
        if self.last_list.is_some_and(|t| now.duration_since(t) < Duration::from_secs(1)) {
            return false;
        }
        self.last_list = Some(now);
        true
    }

    fn allow_session(&mut self, now: Instant) -> bool {
        prune(&mut self.sessions, now);
        if self.last_session.is_some_and(|t| now.duration_since(t) < Duration::from_secs(2)) || self.sessions.len() >= 10 {
            return false;
        }
        self.last_session = Some(now);
        self.sessions.push_back(now);
        true
    }

    fn begin_add(&mut self, now: Instant) -> HandlerResult<()> {
        prune(&mut self.adds, now);
        if self.add_running {
            return Err("busy");
        }
        if self.adds.len() >= 3 {
            return Err("rate_limited");
        }
        self.add_running = true;
        self.adds.push_back(now);
        Ok(())
    }
}

struct Session {
    sid: String,
    key: Vec<u8>,
    /// Mod ≤ 0.5.0: Clips über das alte Token.
    legacy: bool,
    legacy_token: Option<String>,
    pid: Option<u32>,
    opened: Instant,
    authed: bool,
    state: watch::Sender<LinkState>,
    push: broadcast::Sender<Push>,
    limits: Arc<Mutex<Limits>>,
}

impl Session {
    fn new(sid: String, key: Vec<u8>, legacy: bool, pid: Option<u32>, authed: bool) -> Self {
        let (state, _) = watch::channel(LinkState::disabled());
        let (push, _) = broadcast::channel(16);
        Self {
            sid,
            key,
            legacy,
            legacy_token: None,
            pid,
            opened: Instant::now(),
            authed,
            state,
            push,
            limits: Arc::default(),
        }
    }
}

struct Shared {
    hub: Mutex<HashMap<String, Session>>,
    handler: RwLock<Option<Arc<dyn AccountsHandler>>>,
    on_command: RwLock<Option<CommandSink>>,
    persist_file: Option<PathBuf>,
    persist_lock: tokio::sync::Mutex<()>,
    first_auth_ttl: Duration,
}

impl Shared {
    fn hub(&self) -> std::sync::MutexGuard<'_, HashMap<String, Session>> {
        self.hub.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn handler(&self) -> Option<Arc<dyn AccountsHandler>> {
        self.handler.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone()
    }

    fn command(&self, instance_id: &str, command: LinkCommand) {
        let sink = self.on_command.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        if let Some(sink) = sink {
            sink(instance_id, command);
        }
    }

    fn broadcast_accounts_changed(&self) {
        for session in self.hub().values() {
            let _ = session.push.send(Push::AccountsChanged);
        }
    }
}

/// Was der Launcher dem Spiel mitgibt. Absichtlich ohne `Debug` (Schlüssel!).
pub struct Handoff {
    pub port: u16,
    env_value: String,
    secret_hex: String,
}

impl Handoff {
    /// `(TRS_CLIENT_LINK, "2:<port>:<sid>:<schlüssel>")` für die Umgebung des Spiels.
    pub fn env(&self) -> (String, String) {
        (ENV_VAR.to_owned(), self.env_value.clone())
    }

    /// Zum Schwärzen in Logs.
    pub fn secret(&self) -> &str {
        &self.secret_hex
    }
}

/// Der Link-Server. Startet beim ersten Bedarf und läuft dann weiter.
pub struct TrsLink {
    shared: Arc<Shared>,
    port: tokio::sync::OnceCell<u16>,
}

impl TrsLink {
    /// `persist_file`: wohin laufende Sitzungen (verschlüsselt) gesichert werden.
    pub fn new(persist_file: Option<PathBuf>) -> Self {
        Self::with_ttl(persist_file, FIRST_AUTH_TTL)
    }

    fn with_ttl(persist_file: Option<PathBuf>, first_auth_ttl: Duration) -> Self {
        Self {
            shared: Arc::new(Shared {
                hub: Mutex::default(),
                handler: RwLock::default(),
                on_command: RwLock::default(),
                persist_file,
                persist_lock: tokio::sync::Mutex::new(()),
                first_auth_ttl,
            }),
            port: tokio::sync::OnceCell::new(),
        }
    }

    pub fn set_handler(&self, handler: Arc<dyn AccountsHandler>) {
        *self.shared.handler.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(handler);
    }

    pub fn set_command_sink(&self, sink: CommandSink) {
        *self.shared.on_command.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(sink);
    }

    /// Port, falls der Server schon läuft.
    pub fn port(&self) -> Option<u16> {
        self.port.get().copied()
    }

    /// Bindet (einmal) ausschließlich an die Loopback-Adresse.
    pub async fn ensure_started(&self) -> Result<u16> {
        self.port
            .get_or_try_init(|| async {
                let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0);
                let listener = TcpListener::bind(addr).await.map_err(|e| Error::Internal(format!("TRS-Link: {e}")))?;
                let port = listener.local_addr().map_err(|e| Error::Internal(format!("TRS-Link: {e}")))?.port();
                tokio::spawn(accept_loop(listener, self.shared.clone(), port));
                tracing::info!("TRS-Link lauscht auf 127.0.0.1:{port}");
                Ok::<_, Error>(port)
            })
            .await
            .copied()
    }

    /// Neue Sitzung für einen Spielstart (eine alte derselben Instanz wird ungültig).
    /// `legacy`: Mod ≤ 0.5.0 (Clips nur über das alte Token).
    pub async fn open_session(&self, instance_id: &str, legacy: bool) -> Result<Handoff> {
        let port = self.ensure_started().await?;
        let sid = proto::hex(&proto::random_bytes(proto::SID_HEX_LEN / 2));
        let key = proto::random_bytes(proto::KEY_LEN);
        let secret_hex = proto::hex(&key);
        self.shared.hub().insert(instance_id.to_owned(), Session::new(sid.clone(), key, legacy, None, false));
        self.persist();
        Ok(Handoff { port, env_value: format!("{PROTOCOL_VERSION}:{port}:{sid}:{secret_hex}"), secret_hex })
    }

    pub fn has_session(&self, instance_id: &str) -> bool {
        self.shared.hub().contains_key(instance_id)
    }

    /// PID des Spiels (nach dem Start) – für die Prüfung der Gegenstelle.
    pub fn set_pid(&self, instance_id: &str, pid: u32) {
        if let Some(session) = self.shared.hub().get_mut(instance_id) {
            session.pid = Some(pid);
        }
        self.persist();
    }

    /// Spielende: Sitzung ungültig machen; offene Verbindungen schließen sich.
    pub fn close_session(&self, instance_id: &str) {
        let removed = self.shared.hub().remove(instance_id).is_some();
        if removed {
            self.persist();
        }
    }

    /// Inhalt von clips.json für diese Instanz (v2 mit Port bzw. altes v1-Token).
    pub fn clips_config(&self, instance_id: &str, clips_enabled: bool) -> LinkConfig {
        let port = self.port();
        let mut hub = self.shared.hub();
        let Some(session) = hub.get_mut(instance_id) else { return LinkConfig::disabled() };
        if session.legacy {
            if !clips_enabled {
                return LinkConfig { version: LEGACY_VERSION, enabled: false, port: None, token: None, clips_dir: None };
            }
            let token = session.legacy_token.get_or_insert_with(new_token).clone();
            return LinkConfig { version: LEGACY_VERSION, enabled: true, port, token: Some(token), clips_dir: None };
        }
        LinkConfig { version: PROTOCOL_VERSION, enabled: clips_enabled, port, token: None, clips_dir: None }
    }

    pub fn set_state(&self, instance_id: &str, state: LinkState) {
        if let Some(session) = self.shared.hub().get(instance_id) {
            session.state.send_if_modified(|current| {
                let changed = *current != state;
                *current = state;
                changed
            });
        }
    }

    pub fn send(&self, instance_id: &str, event: LinkEvent) {
        if let Some(session) = self.shared.hub().get(instance_id) {
            let _ = session.push.send(Push::Clip(event));
        }
    }

    /// Konten im Launcher geändert: alle angemeldeten Mods laden die Liste neu.
    pub fn notify_accounts_changed(&self) {
        self.shared.broadcast_accounts_changed();
    }

    /// Nach einem Launcher-Neustart: gesicherte Sitzungen der noch laufenden
    /// Spiele (gleiche PID) übernehmen. Liefert die übernommenen Instanzen.
    pub async fn restore(&self, running: &[(String, u32)]) -> Result<Vec<String>> {
        let Some(file) = self.shared.persist_file.clone() else { return Ok(Vec::new()) };
        let stored = persist::load(&file).await;
        let mut restored = Vec::new();
        if !stored.is_empty() {
            self.ensure_started().await?;
        }
        for s in stored {
            if !running.iter().any(|(id, pid)| *id == s.instance_id && Some(*pid) == s.pid) {
                continue;
            }
            let mut hub = self.shared.hub();
            if hub.contains_key(&s.instance_id) {
                continue;
            }
            hub.insert(s.instance_id.clone(), Session::new(s.sid, s.key, s.legacy, s.pid, true));
            restored.push(s.instance_id);
        }
        self.persist();
        Ok(restored)
    }

    /// Sitzungen verschlüsselt sichern (neuester Stand gewinnt).
    fn persist(&self) {
        let Some(file) = self.shared.persist_file.clone() else { return };
        let Ok(runtime) = tokio::runtime::Handle::try_current() else { return };
        let shared = self.shared.clone();
        runtime.spawn(async move {
            let _guard = shared.persist_lock.lock().await;
            let snapshot: Vec<persist::StoredSession> = shared
                .hub()
                .iter()
                .filter_map(|(id, s)| {
                    Some(persist::StoredSession {
                        instance_id: id.clone(),
                        sid: s.sid.clone(),
                        secret: persist::protect_key(&s.key).ok()?,
                        pid: s.pid,
                        legacy: s.legacy,
                    })
                })
                .collect();
            if let Err(e) = persist::save(&file, &snapshot).await {
                tracing::warn!("link-sessions.json konnte nicht geschrieben werden: {e}");
            }
        });
    }
}

async fn accept_loop(listener: TcpListener, shared: Arc<Shared>, port: u16) {
    let open = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    loop {
        let Ok((stream, peer)) = listener.accept().await else {
            tokio::time::sleep(Duration::from_millis(200)).await;
            continue;
        };
        if !peer.ip().is_loopback() || open.load(std::sync::atomic::Ordering::Relaxed) >= MAX_CONNECTIONS {
            continue;
        }
        open.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let (shared, open) = (shared.clone(), open.clone());
        tokio::spawn(async move {
            let _ = serve(stream, peer, port, shared).await;
            open.fetch_sub(1, std::sync::atomic::Ordering::Relaxed);
        });
    }
}

type Reader = BufReader<tokio::net::tcp::OwnedReadHalf>;

async fn read_line(reader: &mut Reader, buf: &mut Vec<u8>) -> Option<()> {
    buf.clear();
    let mut limited = (&mut *reader).take(MAX_LINE as u64 + 1);
    let n = limited.read_until(b'\n', buf).await.ok()?;
    if n == 0 || n > MAX_LINE || buf.last() != Some(&b'\n') {
        return None;
    }
    Some(())
}

async fn write_json(writer: &mut tokio::net::tcp::OwnedWriteHalf, value: &impl Serialize) -> Option<()> {
    let mut line = serde_json::to_vec(value).ok()?;
    line.push(b'\n');
    writer.write_all(&line).await.ok()
}

#[derive(Serialize)]
struct StateLine<'a> {
    #[serde(rename = "type")]
    kind: &'static str,
    #[serde(flatten)]
    state: &'a LinkState,
}

#[derive(Deserialize, Default)]
struct Incoming {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    token: Option<String>,
    #[serde(default)]
    v: Option<u32>,
    #[serde(default)]
    sid: Option<String>,
    #[serde(default)]
    nonce: Option<String>,
    #[serde(default)]
    proof: Option<String>,
    #[serde(default)]
    id: Option<serde_json::Value>,
    #[serde(default)]
    op: Option<String>,
    #[serde(default)]
    account: Option<String>,
}

/// Eine angemeldete Verbindung.
struct Conn {
    instance_id: String,
    /// v2: Sitzungs-ID; v1: das alte Token.
    marker: String,
    v2: bool,
    /// Gegenstelle gehört nachweislich zum Spiel?
    peer: Option<bool>,
    seal_key: Option<[u8; 32]>,
    limits: Arc<Mutex<Limits>>,
}

type Subscribed = (Conn, watch::Receiver<LinkState>, broadcast::Receiver<Push>);

impl Conn {
    fn valid(&self, shared: &Shared) -> bool {
        shared.hub().get(&self.instance_id).is_some_and(|s| {
            if self.v2 {
                proto::ct_eq(s.sid.as_bytes(), self.marker.as_bytes())
            } else {
                s.legacy_token.as_deref().is_some_and(|t| proto::ct_eq(t.as_bytes(), self.marker.as_bytes()))
            }
        })
    }
}

async fn deny(write: &mut tokio::net::tcp::OwnedWriteHalf) -> Option<Subscribed> {
    // Kurz bremsen, damit Raten sinnlos bleibt.
    tokio::time::sleep(DENY_DELAY).await;
    let _ = write_json(write, &json!({ "type": "denied" })).await;
    None
}

/// Anmeldung (v1 mit Token oder v2 mit gegenseitigem Beweis).
async fn handshake(
    reader: &mut Reader,
    write: &mut tokio::net::tcp::OwnedWriteHalf,
    buf: &mut Vec<u8>,
    peer: SocketAddr,
    port: u16,
    shared: &Shared,
) -> Option<Subscribed> {
    let deadline = tokio::time::Instant::now() + HELLO_TIMEOUT;
    tokio::time::timeout_at(deadline, read_line(reader, buf)).await.ok()??;
    let hello: Incoming = serde_json::from_slice(buf).ok()?;
    if hello.kind != "hello" {
        return deny(write).await;
    }
    if hello.v.unwrap_or(1) < 2 {
        // Protokoll v1 (Mod ≤ 0.5.0): Token aus clips.json, nur Clips.
        let token = hello.token.unwrap_or_default();
        let found = {
            let hub = shared.hub();
            hub.iter()
                .find(|(_, s)| s.legacy_token.as_deref().is_some_and(|t| proto::ct_eq(t.as_bytes(), token.as_bytes())))
                .map(|(id, s)| (id.clone(), s.state.subscribe(), s.push.subscribe(), s.limits.clone()))
        };
        tracing::debug!("TRS-Link: v1-Anmeldung {}", if found.is_some() { "ok" } else { "abgewiesen" });
        let Some((instance_id, state_rx, push_rx, limits)) = found else { return deny(write).await };
        return Some((Conn { instance_id, marker: token, v2: false, peer: None, seal_key: None, limits }, state_rx, push_rx));
    }

    let (Some(sid), Some(nc)) = (hello.sid, hello.nonce) else { return deny(write).await };
    if !proto::is_hex(&sid, proto::SID_HEX_LEN) || !proto::is_hex(&nc, proto::NONCE_HEX_LEN) {
        return deny(write).await;
    }
    let found = {
        let hub = shared.hub();
        hub.iter()
            .find(|(_, s)| proto::ct_eq(s.sid.as_bytes(), sid.as_bytes()))
            .filter(|(_, s)| s.authed || s.opened.elapsed() <= shared.first_auth_ttl)
            .map(|(id, s)| (id.clone(), s.key.clone(), s.pid))
    };
    let Some((instance_id, key, pid)) = found else {
        tracing::debug!("TRS-Link: unbekannte oder abgelaufene Sitzung");
        return deny(write).await;
    };
    let peer_ok = tokio::task::spawn_blocking(move || peer::verify(peer.port(), port, pid)).await.ok().flatten();
    if peer_ok == Some(false) {
        tracing::warn!("TRS-Link: Verbindung kommt nicht vom Spielprozess – abgewiesen");
        return deny(write).await;
    }

    let nl = proto::hex(&proto::random_bytes(16));
    let challenge = json!({
        "type": "challenge",
        "nonce": nl,
        "proof": proto::hex(&proto::launcher_proof(&key, &sid, &nc, &nl)),
        "features": ["clips", "accounts"],
    });
    write_json(write, &challenge).await?;
    tokio::time::timeout_at(deadline, read_line(reader, buf)).await.ok()??;
    let auth: Incoming = serde_json::from_slice(buf).unwrap_or_default();
    let expected = proto::game_proof(&key, &sid, &nc, &nl);
    let proof_ok = auth.kind == "auth"
        && auth.proof.as_deref().and_then(proto::unhex).is_some_and(|p| proto::ct_eq(&p, &expected));
    if !proof_ok {
        tracing::debug!("TRS-Link: falscher Beweis – abgewiesen");
        return deny(write).await;
    }
    let subscribed = {
        let mut hub = shared.hub();
        hub.get_mut(&instance_id).filter(|s| proto::ct_eq(s.sid.as_bytes(), sid.as_bytes())).map(|s| {
            s.authed = true;
            (s.state.subscribe(), s.push.subscribe(), s.limits.clone())
        })
    };
    let Some((state_rx, push_rx, limits)) = subscribed else { return deny(write).await };
    tracing::debug!("TRS-Link: v2-Anmeldung ok ('{instance_id}', Gegenstelle geprüft: {peer_ok:?})");
    let conn = Conn {
        instance_id,
        marker: sid,
        v2: true,
        peer: peer_ok,
        seal_key: Some(proto::seal_key(&key, &nc, &nl)),
        limits,
    };
    Some((conn, state_rx, push_rx))
}

fn request_id(value: Option<&serde_json::Value>) -> Option<u64> {
    value?.as_u64().filter(|id| (1..=0x7fff_ffff).contains(id))
}

fn error_response(id: u64, code: &'static str) -> serde_json::Value {
    json!({ "type": "res", "id": id, "ok": false, "error": code })
}

/// Eine Konten-Anfrage bearbeiten; die Antwort geht über `out`.
fn handle_request(conn: &Conn, shared: &Arc<Shared>, msg: Incoming, id: u64, out: &mpsc::UnboundedSender<serde_json::Value>) {
    let reply = |value: serde_json::Value| {
        let _ = out.send(value);
    };
    if !conn.v2 {
        return reply(error_response(id, "not_allowed"));
    }
    let op = msg.op.unwrap_or_default();
    let now = Instant::now();
    let limits = || conn.limits.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(handler) = shared.handler() else { return reply(error_response(id, "error")) };
    let out = out.clone();
    match op.as_str() {
        "accounts.list" => {
            if !limits().allow_list(now) {
                return reply(error_response(id, "rate_limited"));
            }
            tokio::spawn(async move {
                let value = match handler.list().await {
                    Ok(accounts) => json!({ "type": "res", "id": id, "ok": true, "accounts": accounts }),
                    Err(code) => error_response(id, code),
                };
                let _ = out.send(value);
            });
        }
        "accounts.session" => {
            if conn.peer != Some(true) {
                return reply(error_response(id, "not_allowed"));
            }
            let Some(account) = msg.account.filter(|a| proto::is_hex(a, 32)) else {
                return reply(error_response(id, "unknown_account"));
            };
            if !limits().allow_session(now) {
                return reply(error_response(id, "rate_limited"));
            }
            let Some(seal_key) = conn.seal_key else { return reply(error_response(id, "not_allowed")) };
            let instance_id = conn.instance_id.clone();
            tokio::spawn(async move {
                let value = match handler.session(instance_id, account).await {
                    Ok(s) => json!({
                        "type": "res", "id": id, "ok": true,
                        "session": { "id": s.id, "name": s.name, "xuid": s.xuid, "token": proto::seal(&seal_key, s.token.as_bytes()) },
                    }),
                    Err(code) => error_response(id, code),
                };
                let _ = out.send(value);
            });
        }
        "accounts.add" => {
            if conn.peer != Some(true) {
                return reply(error_response(id, "not_allowed"));
            }
            if let Err(code) = limits().begin_add(now) {
                return reply(error_response(id, code));
            }
            let (instance_id, limits, shared) = (conn.instance_id.clone(), conn.limits.clone(), shared.clone());
            tokio::spawn(async move {
                let result = handler.add(instance_id).await;
                limits.lock().unwrap_or_else(std::sync::PoisonError::into_inner).add_running = false;
                let value = match result {
                    Ok(account) => {
                        shared.broadcast_accounts_changed();
                        json!({ "type": "res", "id": id, "ok": true, "account": account })
                    }
                    Err(code) => error_response(id, code),
                };
                let _ = out.send(value);
            });
        }
        _ => reply(error_response(id, "unknown_op")),
    }
}

async fn serve(stream: TcpStream, peer: SocketAddr, port: u16, shared: Arc<Shared>) -> Option<()> {
    stream.set_nodelay(true).ok();
    let (read, mut write) = stream.into_split();
    let mut reader = BufReader::new(read);
    let mut buf = Vec::with_capacity(256);

    let (conn, mut state_rx, mut push_rx) = handshake(&mut reader, &mut write, &mut buf, peer, port, &shared).await?;
    let conn = Arc::new(conn);
    let (out_tx, mut out_rx) = mpsc::unbounded_channel::<serde_json::Value>();

    // Lesen in eigener Aufgabe (`read_until` ist im `select!` nicht abbruchsicher).
    let mut reader_task = {
        let (conn, shared) = (conn.clone(), shared.clone());
        tokio::spawn(async move {
            let mut last_command = Instant::now().checked_sub(COMMAND_COOLDOWN).unwrap_or_else(Instant::now);
            while read_line(&mut reader, &mut buf).await.is_some() {
                let Ok(msg) = serde_json::from_slice::<Incoming>(&buf) else { continue };
                match msg.kind.as_str() {
                    "clip" | "record" => {
                        let command = if msg.kind == "clip" { LinkCommand::SaveClip } else { LinkCommand::ToggleRecording };
                        if last_command.elapsed() >= COMMAND_COOLDOWN && conn.valid(&shared) {
                            last_command = Instant::now();
                            tracing::debug!("TRS-Link: {command:?} von '{}'", conn.instance_id);
                            shared.command(&conn.instance_id, command);
                        } else {
                            tracing::debug!("TRS-Link: {command:?} verworfen (Sperrzeit)");
                        }
                    }
                    "req" => {
                        let Some(id) = request_id(msg.id.as_ref()) else { continue };
                        if !conn.valid(&shared) {
                            break;
                        }
                        handle_request(&conn, &shared, msg, id, &out_tx);
                    }
                    _ => {}
                }
            }
        })
    };

    let result = async {
        let mut sent_at = Instant::now();
        let initial = state_rx.borrow_and_update().clone();
        write_json(&mut write, &StateLine { kind: "state", state: &initial }).await?;
        let mut heartbeat = tokio::time::interval(Duration::from_secs(5));
        heartbeat.tick().await;
        loop {
            tokio::select! {
                _ = &mut reader_task => return None,
                changed = state_rx.changed() => {
                    changed.ok()?;
                    let state = state_rx.borrow_and_update().clone();
                    sent_at = Instant::now();
                    write_json(&mut write, &StateLine { kind: "state", state: &state }).await?;
                }
                pushed = push_rx.recv() => {
                    match pushed {
                        Ok(Push::Clip(event)) => write_json(&mut write, &event).await?,
                        Ok(Push::AccountsChanged) if conn.v2 => {
                            write_json(&mut write, &json!({ "type": "accountsChanged" })).await?;
                        }
                        Ok(Push::AccountsChanged) | Err(broadcast::error::RecvError::Lagged(_)) => {}
                        Err(broadcast::error::RecvError::Closed) => return None,
                    }
                }
                Some(response) = out_rx.recv() => {
                    write_json(&mut write, &response).await?;
                }
                _ = heartbeat.tick() => {
                    if !conn.valid(&shared) {
                        return None;
                    }
                    // Laufende Aufnahmezeit nachführen (die Mod rechnet selbst weiter).
                    let mut state = state_rx.borrow().clone();
                    if state.recording {
                        state.recording_ms = state.recording_ms.saturating_add(sent_at.elapsed().as_millis() as u64);
                    }
                    write_json(&mut write, &StateLine { kind: "state", state: &state }).await?;
                }
            }
        }
    }
    .await;
    reader_task.abort();
    result
}

#[cfg(test)]
mod tests;
