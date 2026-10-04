//! e4mc („öffentlicher Link“) für lokale Server – das Protokoll ohne Transport.
//!
//! e4mc (<https://e4mc.link>, Mod von Skye, MIT) vermittelt über sein Relay eine
//! Adresse `*.e4mc.link`, über die jeder Minecraft-Spieler beitreten kann. Das Relay
//! spricht „quiclime“ über QUIC:
//!
//! - Broker: `GET https://broker.e4mc.link/getBestRelay` → `{"id","host","port"}`.
//! - QUIC zu `host:port`, ALPN `quiclime`, normales TLS gegen den Hostnamen.
//! - Steuer-Strom (der erste, vom Client geöffnete bidirektionale Strom):
//!   Client → Relay: VarInt-Länge + JSON (`probe_capabilities`, `request_domain_assignment`),
//!   Relay → Client: **ein** Längen-Byte + JSON (`domain_assignment_complete` mit `domain`,
//!   später evtl. `request_message_broadcast`).
//! - Jeder Spieler kommt als vom Relay geöffneter bidirektionaler Strom: voller
//!   Minecraft-Bytestrom (mit Handshake) – hier 1:1 an `127.0.0.1:<Port>`.
//!
//! Die Adresse lebt nur so lange wie die QUIC-Verbindung. Eigene Umsetzung des
//! Protokolls (kein übernommener Code); Hinweis in NOTICE.

use std::net::SocketAddr;
use std::time::Duration;

use serde::Deserialize;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const BROKER_URL: &str = "https://broker.e4mc.link/getBestRelay";
pub const ALPN: &[u8] = b"quiclime";
/// Größte Steuer-Nachricht vom Client (so prüft es das Relay).
const MAX_OUT: usize = 8192;
/// So viele Steuer-Nachrichten warten wir höchstens auf die Domain.
const MAX_BEFORE_DOMAIN: usize = 16;
const LOCAL_CONNECT: Duration = Duration::from_secs(5);

/// Client → Relay: VarInt-Länge + JSON.
pub fn encode_control(json: &str) -> Vec<u8> {
    let bytes = &json.as_bytes()[..json.len().min(MAX_OUT)];
    let mut out = Vec::with_capacity(bytes.len() + 2);
    let mut v = bytes.len() as u32;
    while v >= 0x80 {
        out.push((v as u8 & 0x7F) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
    out.extend_from_slice(bytes);
    out
}

/// Relay → Client: ein Längen-Byte + JSON.
pub async fn read_control<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<Vec<u8>> {
    let len = r.read_u8().await? as usize;
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await?;
    Ok(buf)
}

/// Eine Nachricht vom Relay.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ControlMsg {
    Domain(String),
    Broadcast(String),
    Other,
}

#[derive(Deserialize)]
struct RawControl {
    #[serde(default)]
    kind: String,
    #[serde(default)]
    domain: Option<String>,
    #[serde(default)]
    message: Option<String>,
}

pub fn parse_control(raw: &[u8]) -> ControlMsg {
    let Ok(msg) = serde_json::from_slice::<RawControl>(raw) else { return ControlMsg::Other };
    match msg.kind.as_str() {
        "domain_assignment_complete" => match msg.domain.map(|d| d.to_ascii_lowercase()).filter(|d| valid_domain(d)) {
            Some(d) => ControlMsg::Domain(d),
            None => ControlMsg::Other,
        },
        "request_message_broadcast" => ControlMsg::Broadcast(crate::trs_api::chat::one_line(&msg.message.unwrap_or_default(), 200)),
        _ => ControlMsg::Other,
    }
}

/// Hostname (Kleinbuchstaben, Ziffern, `-`, Punkte), höchstens 253 Zeichen, mindestens zwei Teile.
pub fn valid_domain(d: &str) -> bool {
    let labels: Vec<&str> = d.split('.').collect();
    (3..=253).contains(&d.len())
        && labels.len() >= 2
        && labels.iter().all(|l| {
            (1..=63).contains(&l.len())
                && !l.starts_with('-')
                && !l.ends_with('-')
                && l.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        })
}

#[derive(Deserialize)]
struct RawBroker {
    #[serde(default)]
    host: String,
    #[serde(default)]
    port: Option<u32>,
}

/// Antwort des Brokers → (Relay-Host, Port).
pub fn parse_broker(body: &str) -> Option<(String, u16)> {
    let raw: RawBroker = serde_json::from_str(body).ok()?;
    let host = raw.host.to_ascii_lowercase();
    let port = u16::try_from(raw.port?).ok().filter(|p| *p > 0)?;
    crate::hosting_mods::relay::valid_relay_host(&host).then_some((host, port))
}

/// Steuer-Strom: Fähigkeiten abfragen, Domain anfordern und auf sie warten.
/// Fehler = kurzer Code (`closed`, `no_domain`).
pub async fn request_domain<W, R>(send: &mut W, recv: &mut R) -> Result<String, &'static str>
where
    W: AsyncWrite + Unpin,
    R: AsyncRead + Unpin,
{
    let mut hello = encode_control(r#"{"kind":"probe_capabilities"}"#);
    hello.extend(encode_control(r#"{"kind":"request_domain_assignment"}"#));
    send.write_all(&hello).await.map_err(|_| "closed")?;
    send.flush().await.map_err(|_| "closed")?;
    for _ in 0..MAX_BEFORE_DOMAIN {
        let raw = read_control(recv).await.map_err(|_| "closed")?;
        if let ControlMsg::Domain(d) = parse_control(&raw) {
            return Ok(d);
        }
    }
    Err("no_domain")
}

/// Einen Spieler-Strom an den lokalen Server reichen (beide Richtungen, bis eine Seite schließt).
pub async fn pipe_to_local<R, W>(mut recv: R, mut send: W, target: SocketAddr)
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let Ok(Ok(local)) = tokio::time::timeout(LOCAL_CONNECT, tokio::net::TcpStream::connect(target)).await else {
        let _ = send.shutdown().await;
        return;
    };
    let _ = local.set_nodelay(true);
    let (mut lr, mut lw) = local.into_split();
    let up = async {
        let _ = tokio::io::copy(&mut recv, &mut lw).await;
        let _ = lw.shutdown().await;
    };
    let down = async {
        let _ = tokio::io::copy(&mut lr, &mut send).await;
        let _ = send.shutdown().await;
    };
    tokio::join!(up, down);
}
