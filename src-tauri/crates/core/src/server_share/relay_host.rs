//! Host-Seite des TRS Relay (`relay/PROTOCOL.md` §2) für lokale Server: Der Launcher hält
//! die Steuerverbindung des Raums, koppelt je Gast eine Datenverbindung (`PAIR`) und
//! reicht die Bytes roh an `127.0.0.1:<Port>` durch – der Server sieht ganz normale
//! Minecraft-Verbindungen (von 127.0.0.1).

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

use futures::future::BoxFuture;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, watch};

use super::{ShareEnd, ShareEvent, ShareEvents, stopped};
use crate::hosting_mods::relay::{RelayGrant, valid_relay_host};

pub const PREAMBLE: [u8; 5] = *b"TRSR\x01";
pub const HOST_HELLO: u8 = 0x01;
pub const PAIR: u8 = 0x03;
pub const GUEST_OPEN: u8 = 0x20;
pub const GUEST_CLOSED: u8 = 0x21;
pub const CLOSE_GUEST: u8 = 0x22;
pub const PING: u8 = 0x30;
pub const PONG: u8 = 0x31;
pub const WELCOME: u8 = 0x81;
pub const ERROR: u8 = 0x8F;
/// Größte Nutzlast im Handschlag und auf der Steuerverbindung.
pub const MAX_FRAME: usize = 1024;
/// Höchstens so viele Gast-Verbindungen gleichzeitig (das Relay erlaubt 9 Gäste × 3).
const MAX_PIPES: u32 = 32;

/// Zeiten (in Tests kürzer).
#[derive(Debug, Clone)]
pub struct Timing {
    pub connect: Duration,
    pub handshake: Duration,
    /// PING auf der Steuerverbindung (das Relay will spätestens alle 45 s etwas).
    pub ping_every: Duration,
    /// Kommt so lange gar nichts vom Relay (auch kein PONG) → neu verbinden.
    pub silence: Duration,
    /// Wartezeiten nach Fehlern; der letzte Wert gilt danach dauerhaft.
    pub backoff: Vec<Duration>,
}

impl Default for Timing {
    fn default() -> Self {
        Self {
            connect: Duration::from_secs(6),
            handshake: Duration::from_secs(12),
            ping_every: Duration::from_secs(15),
            silence: Duration::from_secs(50),
            backoff: [1, 2, 5, 15, 30, 60].into_iter().map(Duration::from_secs).collect(),
        }
    }
}

/// Frisches Host-Token holen (`POST …/connect`).
pub type GrantFn = Arc<dyn Fn() -> BoxFuture<'static, crate::Result<RelayGrant>> + Send + Sync>;

/// Frame `type u8 | length u16 | payload` (Nutzlast auf [`MAX_FRAME`] gekürzt).
pub fn frame(kind: u8, payload: &[u8]) -> Vec<u8> {
    let payload = &payload[..payload.len().min(MAX_FRAME)];
    let mut out = Vec::with_capacity(3 + payload.len());
    out.push(kind);
    out.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    out.extend_from_slice(payload);
    out
}

/// Liest ein Frame (ohne Vorauslesen – danach folgende Rohbytes bleiben im Strom).
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<(u8, Vec<u8>)> {
    let mut head = [0u8; 3];
    r.read_exact(&mut head).await?;
    let len = u16::from_be_bytes([head[1], head[2]]) as usize;
    if len > MAX_FRAME {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "frame too large"));
    }
    let mut payload = vec![0u8; len];
    r.read_exact(&mut payload).await?;
    Ok((head[0], payload))
}

/// Fehlercode aus einem ERROR-Frame (nur `[a-z_]`, sonst `error`).
pub fn error_code(raw: &[u8]) -> String {
    let s = String::from_utf8_lossy(raw);
    if !s.is_empty() && s.len() <= 40 && s.bytes().all(|b| b.is_ascii_lowercase() || b == b'_') { s.into_owned() } else { "error".into() }
}

/// Erster Teil einer Host-Verbindung: Präambel + HOST_HELLO bzw. PAIR.
pub fn hello(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = PREAMBLE.to_vec();
    out.extend(frame(kind, payload));
    out
}

fn grant_ok(g: &RelayGrant) -> bool {
    valid_relay_host(&g.host) && g.port != 0 && g.token.starts_with("trsr1.") && g.token.len() <= 512
}

async fn connect(g: &RelayGrant, timing: &Timing) -> Result<TcpStream, String> {
    let stream = tokio::time::timeout(timing.connect, TcpStream::connect((g.host.as_str(), g.port)))
        .await
        .map_err(|_| "relay_timeout".to_owned())?
        .map_err(|_| "relay_unreachable".to_owned())?;
    let _ = stream.set_nodelay(true);
    Ok(stream)
}

/// Handschlag lesen: WELCOME → ok, ERROR → Code.
async fn welcome(stream: &mut TcpStream, timing: &Timing) -> Result<(), String> {
    let (kind, payload) = tokio::time::timeout(timing.handshake, read_frame(stream))
        .await
        .map_err(|_| "relay_timeout".to_owned())?
        .map_err(|_| "relay_failed".to_owned())?;
    match kind {
        WELCOME => Ok(()),
        ERROR => Err(error_code(&payload)),
        _ => Err("bad_frame".into()),
    }
}

/// Wie eine Sitzung endete.
struct SessionEnd {
    /// `None` = gestoppt.
    code: Option<String>,
    /// War die Steuerverbindung zwischendurch angemeldet?
    online: bool,
}

/// Steuerverbindung halten, bis sie fällt oder `stop` kommt.
async fn session(
    grant: &RelayGrant,
    target: SocketAddr,
    timing: &Timing,
    stop: &mut watch::Receiver<bool>,
    events: &ShareEvents,
    pipes: &Arc<AtomicU32>,
) -> SessionEnd {
    let fail = |code: String, online: bool| SessionEnd { code: Some(code), online };
    if !grant_ok(grant) {
        return fail("bad_grant".into(), false);
    }
    let mut stream = match connect(grant, timing).await {
        Ok(s) => s,
        Err(code) => return fail(code, false),
    };
    if stream.write_all(&hello(HOST_HELLO, grant.token.as_bytes())).await.is_err() {
        return fail("relay_failed".into(), false);
    }
    if let Err(code) = welcome(&mut stream, timing).await {
        return fail(code, false);
    }
    events(ShareEvent::RelayOnline);

    let (mut rd, mut wr) = stream.into_split();
    // Lesen in eigener Aufgabe (ein halb gelesenes Frame darf kein `select!` abbrechen).
    let (tx, mut rx) = mpsc::channel::<(u8, Vec<u8>)>(64);
    let reader = tokio::spawn(async move {
        while let Ok(f) = read_frame(&mut rd).await {
            if tx.send(f).await.is_err() {
                break;
            }
        }
    });
    let _reader = AbortOnDrop(reader);
    let mut ping = tokio::time::interval_at(tokio::time::Instant::now() + timing.ping_every, timing.ping_every);
    let mut quiet_until = tokio::time::Instant::now() + timing.silence;
    let mut counter: u64 = 0;
    loop {
        tokio::select! {
            f = rx.recv() => {
                let Some((kind, payload)) = f else { return fail("closed".into(), true) };
                quiet_until = tokio::time::Instant::now() + timing.silence;
                match kind {
                    GUEST_OPEN if payload.len() == 32 => {
                        let mut pair_id = [0u8; 16];
                        pair_id.copy_from_slice(&payload[..16]);
                        if pipes.load(Ordering::SeqCst) >= MAX_PIPES {
                            if wr.write_all(&frame(CLOSE_GUEST, &pair_id)).await.is_err() {
                                return fail("closed".into(), true);
                            }
                            continue;
                        }
                        pipes.fetch_add(1, Ordering::SeqCst);
                        let relay = RelayGrant { host: grant.host.clone(), port: grant.port, token: String::new() };
                        let (timing, pipes) = (timing.clone(), pipes.clone());
                        tokio::spawn(async move {
                            if let Err(code) = pair(&relay, pair_id, target, &timing).await {
                                tracing::debug!("Relay: Gast nicht gekoppelt ({code})");
                            }
                            pipes.fetch_sub(1, Ordering::SeqCst);
                        });
                    }
                    PING => {
                        if wr.write_all(&frame(PONG, &payload[..payload.len().min(8)])).await.is_err() {
                            return fail("closed".into(), true);
                        }
                    }
                    ERROR => return fail(error_code(&payload), true),
                    // GUEST_CLOSED, PONG, Unbekanntes: nichts zu tun.
                    _ => {}
                }
            }
            _ = ping.tick() => {
                counter += 1;
                if wr.write_all(&frame(PING, &counter.to_be_bytes())).await.is_err() {
                    return fail("closed".into(), true);
                }
            }
            () = tokio::time::sleep_until(quiet_until) => return fail("timeout".into(), true),
            () = stopped(stop) => {
                let _ = wr.shutdown().await;
                return SessionEnd { code: None, online: true };
            }
        }
    }
}

/// Eine Gast-Verbindung koppeln und an den lokalen Server reichen.
async fn pair(grant: &RelayGrant, pair_id: [u8; 16], target: SocketAddr, timing: &Timing) -> Result<(), String> {
    let mut relay = connect(grant, timing).await?;
    relay.write_all(&hello(PAIR, &pair_id)).await.map_err(|_| "relay_failed".to_owned())?;
    welcome(&mut relay, timing).await?;
    let mut local = tokio::time::timeout(timing.connect, TcpStream::connect(target))
        .await
        .map_err(|_| "local_timeout".to_owned())?
        .map_err(|_| "local_refused".to_owned())?;
    let _ = local.set_nodelay(true);
    let _ = tokio::io::copy_bidirectional(&mut relay, &mut local).await;
    Ok(())
}

struct AbortOnDrop(tokio::task::JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// Schleife mit Neuverbinden. `first` = Zugang aus dem Anlegen des Raums.
pub async fn run(
    grants: GrantFn,
    first: Option<RelayGrant>,
    target: SocketAddr,
    timing: Timing,
    mut stop: watch::Receiver<bool>,
    events: ShareEvents,
) -> ShareEnd {
    let pipes = Arc::new(AtomicU32::new(0));
    let mut next = first;
    let mut failures = 0usize;
    let mut fresh_token = false;
    loop {
        if *stop.borrow() {
            return ShareEnd::Stopped;
        }
        let grant = match next.take() {
            Some(g) => g,
            None => {
                let fetched = tokio::select! {
                    g = grants() => g,
                    () = stopped(&mut stop) => return ShareEnd::Stopped,
                };
                match fetched {
                    Ok(g) => g,
                    // Raum zu (abgelaufen, ersetzt, gesperrt) → aus.
                    Err(e) if matches!(e.code(), Some("sanctioned" | "banned")) => {
                        return ShareEnd::Fatal(e.code().unwrap_or("banned").to_owned());
                    }
                    Err(e) if matches!(e.code(), Some("room_not_found" | "not_found")) => {
                        return ShareEnd::Fatal("room_closed".into());
                    }
                    Err(e) => {
                        let code = if e.kind() == "trs_api" && e.code() == Some("hosting_unavailable") { "hosting_unavailable" } else { "api" };
                        events(ShareEvent::RelayOffline(code.into()));
                        if !wait(&timing, &mut failures, &mut stop).await {
                            return ShareEnd::Stopped;
                        }
                        continue;
                    }
                }
            }
        };
        let end = session(&grant, target, &timing, &mut stop, &events, &pipes).await;
        let Some(code) = end.code else { return ShareEnd::Stopped };
        events(ShareEvent::RelayOffline(code.clone()));
        if end.online {
            failures = 0;
            fresh_token = false;
        }
        match code.as_str() {
            // Ein anderes Gerät hostet jetzt diesen Raum.
            "replaced" => return ShareEnd::Fatal(code),
            // Token abgelaufen/ungültig: einmal sofort mit neuem versuchen.
            "expired" | "bad_token" if !fresh_token => {
                fresh_token = true;
                continue;
            }
            _ => {}
        }
        if !wait(&timing, &mut failures, &mut stop).await {
            return ShareEnd::Stopped;
        }
    }
}

/// Wartet nach einem Fehler; `false` = währenddessen gestoppt.
async fn wait(timing: &Timing, failures: &mut usize, stop: &mut watch::Receiver<bool>) -> bool {
    let i = (*failures).min(timing.backoff.len().saturating_sub(1));
    let delay = timing.backoff.get(i).copied().unwrap_or(Duration::from_secs(30));
    *failures += 1;
    tokio::select! {
        () = tokio::time::sleep(delay) => true,
        () = stopped(stop) => false,
    }
}
