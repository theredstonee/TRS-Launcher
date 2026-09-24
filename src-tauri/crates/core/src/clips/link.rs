//! Lokaler Kanal zwischen TRS Client (Mod) und Launcher.
//!
//! Der Launcher lauscht auf `127.0.0.1:<zufälliger Port>` und schreibt Port
//! und ein Einmal-Token je Spielstart in `config/trsclient/clips.json` der
//! Instanz. Die Mod meldet darüber nur Tastendrücke („Clip speichern“,
//! „Aufnahme an/aus“) und bekommt den Status zurück. Zeilenbasiertes JSON,
//! höchstens 1 KB je Zeile; ohne gültiges Token passiert nichts.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc, watch};

use crate::{Error, Result, fsutil};

pub const CONFIG_FILE: &str = "trsclient/clips.json";
const MAX_LINE: usize = 1024;
const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_CONNECTIONS: usize = 16;
/// Tastendrücke schneller als das werden verworfen (Taste festgehalten, Prellen).
const COMMAND_COOLDOWN: Duration = Duration::from_millis(700);
const PROTOCOL_VERSION: u32 = 1;

/// Was die Mod anzeigt.
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

/// Einmalige Meldungen an die Mod.
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

#[derive(Deserialize)]
struct Incoming {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    token: Option<String>,
    #[serde(default)]
    v: Option<u32>,
}

struct Session {
    token: String,
    state: watch::Sender<LinkState>,
    events: broadcast::Sender<LinkEvent>,
}

#[derive(Default)]
struct Hub {
    sessions: HashMap<String, Session>,
}

/// Der Server. Wird beim ersten Spielstart mit Clips gestartet und läuft dann weiter.
pub struct ClipLink {
    port: u16,
    hub: Arc<Mutex<Hub>>,
}

/// Inhalt von `config/trsclient/clips.json`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LinkConfig {
    pub version: u32,
    pub enabled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
}

impl LinkConfig {
    pub fn disabled() -> Self {
        Self { version: PROTOCOL_VERSION, enabled: false, port: None, token: None }
    }
}

/// Schreibt die Datei für die Mod (atomar; nur Port + Token, nichts anderes).
pub async fn write_config(game_dir: &Path, config: &LinkConfig) -> Result<()> {
    let file = game_dir.join("config").join(CONFIG_FILE);
    let dir = file.parent().expect("Elternordner");
    fsutil::ensure_dir(dir).await?;
    let json = serde_json::to_string_pretty(config).map_err(|e| Error::json("clips.json", e))?;
    fsutil::write_atomic(&file, json.as_bytes()).await
}

/// 256 Bit aus dem Zufallsgenerator des Systems (über `uuid` v4 → getrandom).
pub fn new_token() -> String {
    format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple())
}

fn same_token(a: &str, b: &str) -> bool {
    // Konstante Laufzeit – die Länge ist fest und kein Geheimnis.
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

impl ClipLink {
    /// Bindet ausschließlich an die Loopback-Adresse.
    pub async fn start(commands: mpsc::UnboundedSender<(String, LinkCommand)>) -> Result<Self> {
        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0);
        let listener = TcpListener::bind(addr).await.map_err(|e| Error::Internal(format!("Clip-Kanal: {e}")))?;
        let port = listener.local_addr().map_err(|e| Error::Internal(format!("Clip-Kanal: {e}")))?.port();
        let hub: Arc<Mutex<Hub>> = Arc::default();
        tokio::spawn(accept_loop(listener, hub.clone(), commands));
        tracing::info!("Clip-Kanal lauscht auf 127.0.0.1:{port}");
        Ok(Self { port, hub })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    fn hub(&self) -> std::sync::MutexGuard<'_, Hub> {
        self.hub.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Neues Token für ein Spiel (ein altes derselben Instanz wird ungültig).
    pub fn open_session(&self, instance_id: &str) -> LinkConfig {
        let token = new_token();
        let (state, _) = watch::channel(LinkState { reason: Some("starting"), ..Default::default() });
        let (events, _) = broadcast::channel(16);
        self.hub().sessions.insert(instance_id.to_owned(), Session { token: token.clone(), state, events });
        LinkConfig { version: PROTOCOL_VERSION, enabled: true, port: Some(self.port), token: Some(token) }
    }

    /// Token ungültig machen; offene Verbindungen der Instanz werden geschlossen.
    pub fn close_session(&self, instance_id: &str) {
        self.hub().sessions.remove(instance_id);
    }

    pub fn set_state(&self, instance_id: &str, state: LinkState) {
        if let Some(session) = self.hub().sessions.get(instance_id) {
            session.state.send_if_modified(|current| {
                let changed = *current != state;
                *current = state;
                changed
            });
        }
    }

    pub fn send(&self, instance_id: &str, event: LinkEvent) {
        if let Some(session) = self.hub().sessions.get(instance_id) {
            let _ = session.events.send(event);
        }
    }
}

async fn accept_loop(listener: TcpListener, hub: Arc<Mutex<Hub>>, commands: mpsc::UnboundedSender<(String, LinkCommand)>) {
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
        let (hub, commands, open) = (hub.clone(), commands.clone(), open.clone());
        tokio::spawn(async move {
            let _ = serve(stream, hub, commands).await;
            open.fetch_sub(1, std::sync::atomic::Ordering::Relaxed);
        });
    }
}

async fn read_line(reader: &mut BufReader<tokio::net::tcp::OwnedReadHalf>, buf: &mut Vec<u8>) -> Option<()> {
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

async fn serve(stream: TcpStream, hub: Arc<Mutex<Hub>>, commands: mpsc::UnboundedSender<(String, LinkCommand)>) -> Option<()> {
    stream.set_nodelay(true).ok();
    let (read, mut write) = stream.into_split();
    let mut reader = BufReader::new(read);
    let mut buf = Vec::with_capacity(256);

    // 1. Anmeldung mit Token.
    tokio::time::timeout(HELLO_TIMEOUT, read_line(&mut reader, &mut buf)).await.ok()??;
    let hello: Incoming = serde_json::from_slice(&buf).ok()?;
    let found = (hello.kind == "hello" && hello.v.unwrap_or(1) >= 1)
        .then(|| {
            let token = hello.token.as_deref()?;
            let hub = hub.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            hub.sessions.iter().find(|(_, s)| same_token(&s.token, token)).map(|(id, s)| {
                (id.clone(), s.token.clone(), s.state.subscribe(), s.events.subscribe())
            })
        })
        .flatten();
    tracing::debug!("Clip-Kanal: Anmeldung {}", if found.is_some() { "ok" } else { "abgewiesen" });
    let Some((instance_id, token, mut state_rx, mut events_rx)) = found else {
        // Kurz bremsen, damit Raten sinnlos bleibt.
        tokio::time::sleep(Duration::from_millis(300)).await;
        let _ = write_json(&mut write, &serde_json::json!({ "type": "denied" })).await;
        return None;
    };

    let valid = {
        let (hub, instance_id, token) = (hub.clone(), instance_id.clone(), token.clone());
        move || {
            let hub = hub.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            hub.sessions.get(&instance_id).is_some_and(|s| same_token(&s.token, &token))
        }
    };

    // Lesen in eigener Aufgabe (`read_until` ist im `select!` nicht abbruchsicher).
    let mut reader_task = {
        let valid = valid.clone();
        let instance_id = instance_id.clone();
        tokio::spawn(async move {
            let mut last_command = Instant::now().checked_sub(COMMAND_COOLDOWN).unwrap_or_else(Instant::now);
            while read_line(&mut reader, &mut buf).await.is_some() {
                let Ok(msg) = serde_json::from_slice::<Incoming>(&buf) else { continue };
                let command = match msg.kind.as_str() {
                    "clip" => LinkCommand::SaveClip,
                    "record" => LinkCommand::ToggleRecording,
                    _ => continue,
                };
                if last_command.elapsed() >= COMMAND_COOLDOWN && valid() {
                    last_command = Instant::now();
                    tracing::debug!("Clip-Kanal: {command:?} von '{instance_id}'");
                    let _ = commands.send((instance_id.clone(), command));
                } else {
                    tracing::debug!("Clip-Kanal: {command:?} verworfen (Sperrzeit)");
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
                event = events_rx.recv() => {
                    match event {
                        Ok(event) => write_json(&mut write, &event).await?,
                        Err(broadcast::error::RecvError::Lagged(_)) => {}
                        Err(broadcast::error::RecvError::Closed) => return None,
                    }
                }
                _ = heartbeat.tick() => {
                    if !valid() {
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
mod tests {
    use super::*;

    async fn connect(port: u16) -> (BufReader<tokio::net::tcp::OwnedReadHalf>, tokio::net::tcp::OwnedWriteHalf) {
        let stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        let (r, w) = stream.into_split();
        (BufReader::new(r), w)
    }

    async fn line(r: &mut BufReader<tokio::net::tcp::OwnedReadHalf>) -> serde_json::Value {
        let mut s = String::new();
        tokio::time::timeout(Duration::from_secs(5), r.read_line(&mut s)).await.unwrap().unwrap();
        serde_json::from_str(&s).unwrap()
    }

    #[tokio::test]
    async fn falsches_token_wird_abgewiesen() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let link = ClipLink::start(tx).await.unwrap();
        let _config = link.open_session("survival");
        let (mut r, mut w) = connect(link.port()).await;
        w.write_all(b"{\"type\":\"hello\",\"v\":1,\"token\":\"falsch\"}\n").await.unwrap();
        assert_eq!(line(&mut r).await["type"], "denied");
        w.write_all(b"{\"type\":\"clip\"}\n").await.ok();
        let mut rest = Vec::new();
        let _ = tokio::time::timeout(Duration::from_secs(2), r.read_to_end(&mut rest)).await;
        assert!(rx.try_recv().is_err(), "ohne Token kein Befehl");
    }

    #[tokio::test]
    async fn mod_bekommt_status_und_meldet_tasten() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let link = ClipLink::start(tx).await.unwrap();
        let config = link.open_session("survival");
        assert_eq!(config.port, Some(link.port()));
        let token = config.token.clone().unwrap();
        assert_eq!(token.len(), 64);
        assert!(token.bytes().all(|b| b.is_ascii_hexdigit()));

        let (mut r, mut w) = connect(link.port()).await;
        w.write_all(format!("{{\"type\":\"hello\",\"v\":1,\"token\":\"{token}\"}}\n").as_bytes()).await.unwrap();
        let first = line(&mut r).await;
        assert_eq!(first["type"], "state");
        assert_eq!(first["reason"], "starting");

        link.set_state("survival", LinkState { available: true, buffer: true, clip_seconds: 30, ..Default::default() });
        let next = line(&mut r).await;
        assert_eq!(next["buffer"], true);
        assert_eq!(next["clipSeconds"], 30);

        w.write_all(b"{\"type\":\"clip\"}\n").await.unwrap();
        w.write_all(b"{\"type\":\"clip\"}\n").await.unwrap(); // prellt → verworfen
        let (id, cmd) = tokio::time::timeout(Duration::from_secs(5), rx.recv()).await.unwrap().unwrap();
        assert_eq!((id.as_str(), cmd), ("survival", LinkCommand::SaveClip));
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert!(rx.try_recv().is_err(), "zweiter Druck innerhalb der Sperrzeit wird verworfen");

        link.send("survival", LinkEvent::Saved { kind: "clip", seconds: 30 });
        let saved = line(&mut r).await;
        assert_eq!(saved, serde_json::json!({ "type": "saved", "kind": "clip", "seconds": 30 }));

        // Neues Spiel = neues Token; das alte gilt nicht mehr.
        let _ = link.open_session("survival");
        let (mut r2, mut w2) = connect(link.port()).await;
        w2.write_all(format!("{{\"type\":\"hello\",\"v\":1,\"token\":\"{token}\"}}\n").as_bytes()).await.unwrap();
        assert_eq!(line(&mut r2).await["type"], "denied");
    }

    #[tokio::test]
    async fn einzelner_tastendruck_kommt_sofort_an() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let link = ClipLink::start(tx).await.unwrap();
        let token = link.open_session("survival").token.unwrap();
        let (mut r, mut w) = connect(link.port()).await;
        w.write_all(format!("{{\"type\":\"hello\",\"v\":1,\"token\":\"{token}\"}}\n").as_bytes()).await.unwrap();
        assert_eq!(line(&mut r).await["type"], "state");
        // Nur eine Zeile, danach nichts mehr – sie muss trotzdem sofort ankommen.
        w.write_all(b"{\"type\":\"record\"}\n").await.unwrap();
        let (_, cmd) = tokio::time::timeout(Duration::from_secs(2), rx.recv()).await.expect("kam nicht an").unwrap();
        assert_eq!(cmd, LinkCommand::ToggleRecording);
        tokio::time::sleep(Duration::from_millis(800)).await;
        w.write_all(b"{\"type\":\"clip\"}\n").await.unwrap();
        let (_, cmd) = tokio::time::timeout(Duration::from_secs(2), rx.recv()).await.expect("kam nicht an").unwrap();
        assert_eq!(cmd, LinkCommand::SaveClip);
    }

    #[tokio::test]
    async fn zu_lange_zeilen_beenden_die_verbindung() {
        let (tx, _rx) = mpsc::unbounded_channel();
        let link = ClipLink::start(tx).await.unwrap();
        let (mut r, mut w) = connect(link.port()).await;
        let long = vec![b'a'; 4000];
        let _ = w.write_all(&long).await;
        let _ = w.write_all(b"\n").await;
        let mut rest = Vec::new();
        let n = tokio::time::timeout(Duration::from_secs(3), r.read_to_end(&mut rest)).await.unwrap().unwrap_or(0);
        assert_eq!(n, 0, "keine Antwort auf Müll");
    }

    #[tokio::test]
    async fn konfigurationsdatei_enthaelt_nur_port_und_token() {
        let dir = tempfile::tempdir().unwrap();
        let config = LinkConfig { version: 1, enabled: true, port: Some(1234), token: Some("ab".repeat(32)) };
        write_config(dir.path(), &config).await.unwrap();
        let text = std::fs::read_to_string(dir.path().join("config/trsclient/clips.json")).unwrap();
        let back: LinkConfig = serde_json::from_str(&text).unwrap();
        assert_eq!(back, config);
        write_config(dir.path(), &LinkConfig::disabled()).await.unwrap();
        let text = std::fs::read_to_string(dir.path().join("config/trsclient/clips.json")).unwrap();
        assert!(!text.contains("token"));
    }
}
