//! e4mc über QUIC (quinn): Verbindung zum Relay, Domain, Spieler-Ströme an den lokalen
//! Server. Nur Desktop (lokale Server gibt es mobil nicht).

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

use tokio::sync::watch;

use super::e4mc::{self, ALPN, ControlMsg};
use super::{ShareEnd, ShareEvent, ShareEvents, stopped};

/// Höchstens so viele Spieler-Ströme gleichzeitig.
const MAX_STREAMS: u32 = 64;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const DOMAIN_TIMEOUT: Duration = Duration::from_secs(15);

/// TLS/QUIC-Einstellungen für das Relay: Zertifikat über das System prüfen, ALPN `quiclime`.
pub fn client_config() -> Result<quinn::ClientConfig, &'static str> {
    use rustls_platform_verifier::BuilderVerifierExt;
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut tls = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|_| "tls")?
        .with_platform_verifier()
        .map_err(|_| "tls")?
        .with_no_client_auth();
    tls.alpn_protocols = vec![ALPN.to_vec()];
    finish_config(tls)
}

/// Gemeinsamer Teil (auch für Tests mit eigener Wurzel).
pub(crate) fn finish_config(tls: rustls::ClientConfig) -> Result<quinn::ClientConfig, &'static str> {
    let crypto = quinn::crypto::rustls::QuicClientConfig::try_from(tls).map_err(|_| "tls")?;
    let mut config = quinn::ClientConfig::new(Arc::new(crypto));
    let mut transport = quinn::TransportConfig::default();
    transport
        .max_concurrent_bidi_streams(quinn::VarInt::from_u32(512))
        .max_concurrent_uni_streams(quinn::VarInt::from_u32(0))
        .keep_alive_interval(Some(Duration::from_secs(5)))
        .max_idle_timeout(Some(quinn::IdleTimeout::from(quinn::VarInt::from_u32(30_000))));
    config.transport_config(Arc::new(transport));
    Ok(config)
}

/// Relay-Adresse auflösen (IPv4 bevorzugt).
async fn resolve(host: &str, port: u16) -> Result<SocketAddr, &'static str> {
    let addrs: Vec<SocketAddr> = tokio::net::lookup_host((host, port)).await.map_err(|_| "unreachable")?.collect();
    addrs.iter().find(|a| a.is_ipv4()).or_else(|| addrs.first()).copied().ok_or("unreachable")
}

/// Eine Sitzung: verbinden, Domain holen, Spieler annehmen, bis die Verbindung fällt
/// oder `stop` kommt. `Ok(())` = gestoppt.
pub async fn session(
    config: quinn::ClientConfig,
    relay: (String, u16),
    target: SocketAddr,
    stop: &mut watch::Receiver<bool>,
    events: &ShareEvents,
) -> Result<(), &'static str> {
    let addr = resolve(&relay.0, relay.1).await?;
    let bind: SocketAddr = if addr.is_ipv6() { "[::]:0".parse().map_err(|_| "bind")? } else { "0.0.0.0:0".parse().map_err(|_| "bind")? };
    let endpoint = quinn::Endpoint::client(bind).map_err(|_| "bind")?;
    let result = serve(&endpoint, config, addr, &relay.0, target, stop, events).await;
    endpoint.close(quinn::VarInt::from_u32(0), b"bye");
    result
}

async fn serve(
    endpoint: &quinn::Endpoint,
    config: quinn::ClientConfig,
    addr: SocketAddr,
    server_name: &str,
    target: SocketAddr,
    stop: &mut watch::Receiver<bool>,
    events: &ShareEvents,
) -> Result<(), &'static str> {
    let connecting = endpoint.connect_with(config, addr, server_name).map_err(|_| "unreachable")?;
    let conn = tokio::time::timeout(CONNECT_TIMEOUT, connecting).await.map_err(|_| "timeout")?.map_err(|_| "unreachable")?;
    let (mut send, mut recv) = conn.open_bi().await.map_err(|_| "closed")?;
    let domain = tokio::time::timeout(DOMAIN_TIMEOUT, e4mc::request_domain(&mut send, &mut recv)).await.map_err(|_| "timeout")??;
    events(ShareEvent::E4mcDomain(domain));

    let streams = Arc::new(AtomicU32::new(0));
    // Steuer-Strom weiter lesen (Rundsendungen nur ins Log); Ende = Verbindung weg.
    let control = async move {
        while let Ok(raw) = e4mc::read_control(&mut recv).await {
            if let ControlMsg::Broadcast(text) = e4mc::parse_control(&raw) {
                tracing::info!("e4mc: {text}");
            }
        }
    };
    tokio::pin!(control);
    let result = loop {
        tokio::select! {
            incoming = conn.accept_bi() => {
                let Ok((tx, rx)) = incoming else { break Err("closed") };
                if streams.load(Ordering::SeqCst) >= MAX_STREAMS {
                    drop((tx, rx));
                    continue;
                }
                streams.fetch_add(1, Ordering::SeqCst);
                let streams = streams.clone();
                tokio::spawn(async move {
                    e4mc::pipe_to_local(rx, tx, target).await;
                    streams.fetch_sub(1, Ordering::SeqCst);
                });
            }
            () = &mut control => break Err("closed"),
            () = stopped(stop) => break Ok(()),
        }
    };
    let _ = send.finish();
    conn.close(quinn::VarInt::from_u32(0), b"bye");
    result
}

/// Schleife mit Neuverbinden (neue Domain nach jeder Unterbrechung).
pub async fn run(http: reqwest::Client, target: SocketAddr, mut stop: watch::Receiver<bool>, events: ShareEvents) -> ShareEnd {
    let backoff = [5u64, 15, 30, 60];
    let mut failures = 0usize;
    loop {
        if *stop.borrow() {
            return ShareEnd::Stopped;
        }
        let attempt = async {
            let relay = broker(&http).await?;
            let config = client_config()?;
            session(config, relay, target, &mut stop, &events).await
        };
        let started = std::time::Instant::now();
        match attempt.await {
            Ok(()) => return ShareEnd::Stopped,
            Err(code) => {
                tracing::info!("e4mc: Verbindung weg ({code})");
                events(ShareEvent::E4mcOffline(code.to_owned()));
                if started.elapsed() > Duration::from_secs(120) {
                    failures = 0;
                }
                let wait = Duration::from_secs(backoff[failures.min(backoff.len() - 1)]);
                failures += 1;
                tokio::select! {
                    () = tokio::time::sleep(wait) => {}
                    () = stopped(&mut stop) => return ShareEnd::Stopped,
                }
            }
        }
    }
}

/// Bestes Relay vom Broker (ohne Weiterleitungen, kleine Antwort).
async fn broker(http: &reqwest::Client) -> Result<(String, u16), &'static str> {
    let response = http
        .get(e4mc::BROKER_URL)
        .header(reqwest::header::ACCEPT, "application/json")
        .timeout(Duration::from_secs(10))
        .send()
        .await
        .map_err(|_| "broker")?;
    if !response.status().is_success() {
        return Err("broker");
    }
    let body = response.bytes().await.map_err(|_| "broker")?;
    if body.len() > 8192 {
        return Err("broker");
    }
    e4mc::parse_broker(&String::from_utf8_lossy(&body)).ok_or("broker")
}
