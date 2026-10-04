//! Ende-zu-Ende-Probe „lokalen Server teilen“ (für Entwickler, braucht einen Server-Ordner
//! aus „Als Server exportieren“ und Java):
//!
//! ```text
//! cargo run -p trs-core --example server_share -- <server-ordner> <java.exe> e4mc
//! cargo run -p trs-core --example server_share -- <server-ordner> <java.exe> relay <relay-port> <relay-secret>
//! ```
//!
//! Startet den Server wie „Meine Server“, teilt ihn über e4mc (echter Dienst) bzw. ein
//! lokales TRS Relay (`relay/`, Tokens hier selbst signiert) und macht einen
//! Minecraft-Statusabruf durch den Tunnel. Danach wird der Server gestoppt.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use hmac::{Hmac, KeyInit, Mac};
use sha2::Sha256;
use tokio::io::AsyncWriteExt;
use tokio::sync::{mpsc, watch};
use trs_core::hosting_mods::relay::RelayGrant;
use trs_core::local_servers::{self, LocalServers, ServerState};
use trs_core::server_share::{ShareEvent, ShareEvents, relay_host};

type Res<T> = std::result::Result<T, Box<dyn std::error::Error>>;

#[tokio::main]
async fn main() -> Res<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [dir, java, mode, rest @ ..] = args.as_slice() else {
        return Err("Aufruf: <server-ordner> <java.exe> e4mc | relay <port> <secret>".into());
    };
    let dir = PathBuf::from(dir);
    let meta = local_servers::read_meta(&dir)?;
    let servers = Arc::new(LocalServers::default());
    servers.set_sink(Arc::new(|event| {
        if let local_servers::LocalServerEvent::Status { status, .. } = event {
            println!("[server] {:?} Spieler={:?}", status.state, status.players);
        }
    }));
    servers.start("e2e", &dir, &meta, &PathBuf::from(java))?;
    let started = std::time::Instant::now();
    while servers.status("e2e", meta.max_players).state != ServerState::Running {
        if started.elapsed() > Duration::from_secs(240) || !servers.is_running("e2e") {
            for line in servers.logs("e2e").iter().rev().take(20).rev() {
                println!("  {line}");
            }
            return Err("Server ist nicht bereit geworden".into());
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    println!("Server läuft auf Port {} nach {:.1} s", meta.port, started.elapsed().as_secs_f32());
    let target = SocketAddr::from(([127, 0, 0, 1], meta.port));
    let result = match mode.as_str() {
        "e4mc" => e4mc(target).await,
        "relay" => match rest {
            [port, secret] => relay(target, port.parse()?, secret).await,
            _ => Err("relay braucht <port> <secret>".into()),
        },
        _ => Err("Modus: e4mc oder relay".into()),
    };
    servers.stop("e2e")?;
    servers.wait_stopped("e2e", Duration::from_secs(60)).await;
    println!("Server gestoppt");
    result
}

fn events() -> (ShareEvents, mpsc::UnboundedReceiver<ShareEvent>) {
    let (tx, rx) = mpsc::unbounded_channel();
    (Arc::new(move |e| drop(tx.send(e))), rx)
}

async fn e4mc(target: SocketAddr) -> Res<()> {
    let (stop_tx, stop_rx) = watch::channel(false);
    let (events, mut seen) = events();
    let http = reqwest::Client::builder().user_agent(trs_core::USER_AGENT).build()?;
    let tunnel = tokio::spawn(trs_core::server_share::e4mc_quic::run(http, target, stop_rx, events));
    let domain = loop {
        match tokio::time::timeout(Duration::from_secs(30), seen.recv()).await? {
            Some(ShareEvent::E4mcDomain(d)) => break d,
            Some(other) => println!("[e4mc] {other:?}"),
            None => return Err("e4mc beendet".into()),
        }
    };
    println!("e4mc-Adresse: {domain}");
    // Wie ein echter Client (Protokoll 774 = 1.21.11): das e4mc-Relay leitet anhand der Adresse weiter.
    let result = status(&domain, 25565, &domain).await;
    stop_tx.send(true)?;
    let _ = tokio::time::timeout(Duration::from_secs(5), tunnel).await;
    println!("Status über e4mc: {}", result?);
    Ok(())
}

fn varint(out: &mut Vec<u8>, mut v: u32) {
    while v >= 0x80 {
        out.push((v as u8 & 0x7F) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

async fn read_varint(s: &mut tokio::net::TcpStream) -> Res<u32> {
    use tokio::io::AsyncReadExt;
    let mut v = 0u32;
    for i in 0..5 {
        let b = s.read_u8().await?;
        v |= u32::from(b & 0x7F) << (7 * i);
        if b & 0x80 == 0 {
            return Ok(v);
        }
    }
    Err("VarInt zu lang".into())
}

/// Minecraft-Statusabruf (Handshake + Status Request) → JSON der Antwort.
async fn status(host: &str, port: u16, handshake_host: &str) -> Res<String> {
    use tokio::io::AsyncReadExt;
    let mut s = tokio::time::timeout(Duration::from_secs(10), tokio::net::TcpStream::connect((host, port))).await??;
    let mut hs = vec![0x00];
    varint(&mut hs, 774);
    varint(&mut hs, handshake_host.len() as u32);
    hs.extend_from_slice(handshake_host.as_bytes());
    hs.extend_from_slice(&port.to_be_bytes());
    hs.push(1);
    let mut out = Vec::new();
    varint(&mut out, hs.len() as u32);
    out.extend(hs);
    out.extend([1, 0]);
    s.write_all(&out).await?;
    let json = tokio::time::timeout(Duration::from_secs(10), async {
        let _len = read_varint(&mut s).await?;
        let _id = read_varint(&mut s).await?;
        let n = read_varint(&mut s).await? as usize;
        let mut buf = vec![0u8; n.min(65536)];
        s.read_exact(&mut buf).await?;
        Ok::<_, Box<dyn std::error::Error>>(String::from_utf8_lossy(&buf).into_owned())
    })
    .await??;
    if !json.contains("TRS share e2e") {
        return Err(format!("unerwartete Antwort: {json}").into());
    }
    Ok(json.chars().take(200).collect())
}

/// Relay-Token wie die API (`trsr1.<payload>.<sig>`).
fn token(secret: &str, room: &str, uuid: &str, host: &str, role: &str) -> Res<String> {
    let now = chrono::Utc::now().timestamp();
    let payload = serde_json::json!({ "v": 1, "r": room, "u": uuid, "h": host, "role": role, "m": 10,
                                      "iat": now, "exp": now + 120, "n": "0123456789abcdef" });
    let payload = URL_SAFE_NO_PAD.encode(payload.to_string());
    let mut mac = Hmac::<Sha256>::new_from_slice(secret.as_bytes())?;
    mac.update(format!("trsr1.{payload}").as_bytes());
    Ok(format!("trsr1.{payload}.{}", URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())))
}

async fn relay(target: SocketAddr, relay_port: u16, secret: &str) -> Res<()> {
    const ROOM: &str = "h0123456789abcdef0123";
    const HOST: &str = "75c1a6f3112240abbdb57b9d21c64232";
    const GUEST: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";
    let first = RelayGrant { host: "127.0.0.1".into(), port: relay_port, token: token(secret, ROOM, HOST, HOST, "host")? };
    let grants: relay_host::GrantFn = {
        let secret = secret.to_owned();
        Arc::new(move || {
            let token = token(&secret, ROOM, HOST, HOST, "host").map_err(|e| trs_core::Error::Internal(e.to_string()));
            Box::pin(async move { Ok(RelayGrant { host: "127.0.0.1".into(), port: relay_port, token: token? }) })
        })
    };
    let (stop_tx, stop_rx) = watch::channel(false);
    let (events, mut seen) = events();
    let host = tokio::spawn(relay_host::run(grants, Some(first), target, relay_host::Timing::default(), stop_rx, events));
    match tokio::time::timeout(Duration::from_secs(10), seen.recv()).await? {
        Some(ShareEvent::RelayOnline) => println!("Relay: Host angemeldet"),
        other => return Err(format!("Relay: {other:?}").into()),
    }

    // Gast-Seite wie der TRS Client: lokaler Port → GUEST_HELLO → roh.
    let proxy = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let proxy_port = proxy.local_addr()?.port();
    let guest_token = token(secret, ROOM, GUEST, HOST, "guest")?;
    tokio::spawn(async move {
        while let Ok((mut game, _)) = proxy.accept().await {
            let guest_token = guest_token.clone();
            tokio::spawn(async move {
                let Ok(mut relay) = tokio::net::TcpStream::connect(("127.0.0.1", relay_port)).await else { return };
                if relay.write_all(&relay_host::hello(0x02, guest_token.as_bytes())).await.is_err() {
                    return;
                }
                match relay_host::read_frame(&mut relay).await {
                    Ok((relay_host::WELCOME, _)) => {}
                    other => {
                        println!("Gast: kein WELCOME ({other:?})");
                        return;
                    }
                }
                let _ = tokio::io::copy_bidirectional(&mut game, &mut relay).await;
            });
        }
    });
    let first_try = status("127.0.0.1", proxy_port, "127.84.82.83").await;
    // Ein zweiter Abruf zeigt, dass weitere Gäste gekoppelt werden.
    let second_try = status("127.0.0.1", proxy_port, "127.84.82.83").await;
    stop_tx.send(true)?;
    let _ = tokio::time::timeout(Duration::from_secs(5), host).await;
    println!("Status über das Relay: {}", first_try?);
    println!("Zweiter Abruf: {}", second_try?.len());
    Ok(())
}
