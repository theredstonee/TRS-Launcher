//! Lokale Server teilen: Adressen, Raum-/Einladungs-Bodies, Relay-Host gegen ein
//! nachgebautes Relay und e4mc gegen einen nachgebauten QUIC-Broker.

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, watch};

use super::relay_host::{self, GrantFn, Timing};
use super::*;
use crate::hosting_mods::relay::RelayGrant;
use crate::trs_api::testkit::{MockServer, Request, Response};

// --- Adressen -------------------------------------------------------------------------

#[test]
fn addresses_are_formatted_like_the_server_field() {
    assert_eq!(addresses::display("192.168.1.20".parse().unwrap(), 25565), "192.168.1.20:25565");
    assert_eq!(addresses::display("2001:4860::8888".parse().unwrap(), 25570), "[2001:4860::8888]:25570");
    assert!(addresses::is_lan("192.168.1.20".parse().unwrap()) && addresses::is_lan("10.0.0.7".parse().unwrap()));
    assert!(!addresses::is_lan("127.0.0.1".parse().unwrap()) && !addresses::is_lan("8.8.8.8".parse().unwrap()));
}

#[test]
fn public_ip_answers_are_checked() {
    let ip = |s: &str| s.parse::<IpAddr>().unwrap();
    assert_eq!(addresses::parse_ip_answer("93.184.216.34\n"), Some(ip("93.184.216.34")));
    let trace = "fl=123\nh=1.1.1.1\nip=93.184.216.34\nts=1.2\nvisit_scheme=https\n";
    assert_eq!(addresses::parse_ip_answer(trace), Some(ip("93.184.216.34")));
    // Private, CGNAT und Unsinn taugen nicht als „öffentliche“ Adresse.
    for bad in ["192.168.0.2", "100.64.1.1", "127.0.0.1", "<html>", "", "ip=10.0.0.1"] {
        assert_eq!(addresses::parse_ip_answer(bad), None, "{bad}");
    }
}

// --- Raum, Einladungen, Signale -------------------------------------------------------

fn meta(loader: crate::instance::LoaderKind, max_players: u32) -> local_servers::ServerMeta {
    serde_json::from_value(json!({
        "name": "Mein\u{202E} Server\nzwei", "gameVersion": "1.21.11", "loader": loader, "javaMajor": 21,
        "javaComponent": "java-runtime-delta", "ramMb": 2048, "port": 25565, "maxPlayers": max_players,
        "launch": { "type": "jar", "jar": "server.jar" }, "createdAt": "2026-10-04T00:00:00Z"
    }))
    .unwrap()
}

#[test]
fn server_rooms_are_invite_only() {
    use crate::instance::LoaderKind;
    let body = room_spec(&meta(LoaderKind::NeoForge, 20)).body().unwrap();
    assert_eq!(
        body,
        json!({ "name": "Mein Server zwei", "mcVersion": "1.21.11", "loader": "neoforge", "maxPlayers": 10,
                "gameMode": "survival", "pvp": true, "cheats": false, "open": false, "visibility": "invited" })
    );
    assert_eq!(room_spec(&meta(LoaderKind::Vanilla, 1)).body().unwrap()["maxPlayers"], 2, "Host zählt mit, mindestens 2");
    assert_eq!(room_spec(&meta(LoaderKind::Quilt, 4)).body().unwrap()["loader"], "quilt");
    let mut bad = room_spec(&meta(LoaderKind::Fabric, 4));
    bad.mc_version = "1.21/../x".into();
    assert!(bad.body().is_err());
}

#[test]
fn only_valid_offers_are_answered() {
    let offer = json!({ "roomId": "h0123456789abcdef0123", "from": "B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0", "kind": "offer", "sid": "s_1-a", "data": "{}" });
    assert_eq!(
        parse_offer(&offer.to_string()),
        Some(("h0123456789abcdef0123".into(), "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0".into(), "s_1-a".into()))
    );
    for (key, value) in [("kind", json!("candidate")), ("sid", json!("a b")), ("from", json!("x")), ("roomId", json!("h1"))] {
        let mut bad = offer.clone();
        bad[key] = value;
        assert_eq!(parse_offer(&bad.to_string()), None, "{key}");
    }
    assert_eq!(parse_offer("kein json"), None);
}

const ROOM: &str = "h0123456789abcdef0123";

fn hosting_api() -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = crate::trs_api::tests::auth_routes(req, &issued, "mc-token") {
            return r;
        }
        let host = json!({ "uuid": crate::trs_api::tests::ACC, "name": "Theredstonee" });
        let room = json!({ "id": ROOM, "code": "K7QM2X", "name": "Mein Server", "host": host, "mcVersion": "1.21.11",
                           "loader": "fabric", "maxPlayers": 10, "open": false, "visibility": "invited", "players": 1 });
        match (req.method.as_str(), req.path.as_str()) {
            ("POST", "/v1/hosting/rooms") => Response::json(
                201,
                json!({ "room": room, "role": "host", "stun": [],
                        "relay": { "host": "relay.theredstonee.de", "tcpPort": 25503, "udpPort": 25504, "token": "trsr1.HOST.SIG", "expiresAt": "…" } }),
            ),
            ("POST", p) if p == format!("/v1/hosting/rooms/{ROOM}/invites") => match req.json()["uuid"].as_str() {
                Some("c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0") => Response::error(403, "not_friends"),
                _ => Response::json(201, json!({ "member": {}, "chatMessageId": "m1" })),
            },
            ("POST", p) if p == format!("/v1/hosting/rooms/{ROOM}/heartbeat") => Response::json(200, json!({ "expiresAt": "…" })),
            ("POST", p) if p == format!("/v1/hosting/rooms/{ROOM}/signal") => Response::json(200, json!({ "delivered": true })),
            ("DELETE", p) if p == format!("/v1/hosting/rooms/{ROOM}") => Response::empty(204),
            ("POST", p) if p.ends_with("/heartbeat") || p.ends_with("/invites") => Response::error(404, "room_not_found"),
            _ => Response::error(404, "not_found"),
        }
    }
}

#[tokio::test]
async fn rooms_invites_and_signals_follow_the_contract() {
    let server = MockServer::start(hosting_api()).await;
    let (_dir, launcher) = crate::trs_api::tests::launcher(&server, &[crate::trs_api::tests::ACC]).await;

    let (room, grant) = launcher.hosting_create_server_room(&room_spec(&meta(crate::instance::LoaderKind::Fabric, 4))).await.unwrap();
    assert_eq!((room.id.as_str(), grant.host.as_str(), grant.port), (ROOM, "relay.theredstonee.de", 25503));
    assert_eq!(server.hits("POST", "/v1/hosting/rooms")[0].json()["visibility"], "invited");

    let uuids = vec![
        "B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0".to_owned(),
        "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0".to_owned(), // doppelt
        "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0".to_owned(),
        "../etc".to_owned(),
    ];
    let outcome = launcher.hosting_invite(ROOM, &uuids, true).await.unwrap();
    assert_eq!(
        serde_json::to_value(&outcome).unwrap(),
        json!([
            { "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "ok": true, "code": null },
            { "uuid": "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0", "ok": false, "code": "not_friends" }
        ])
    );
    let invites = server.hits("POST", &format!("/v1/hosting/rooms/{ROOM}/invites"));
    assert_eq!(invites[0].json(), json!({ "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "chat": true }), "mit Weltkarte im Chat");
    assert_eq!(invites.len(), 2);

    launcher.hosting_heartbeat(ROOM, 0).await.unwrap();
    launcher.hosting_heartbeat(ROOM, 40).await.unwrap();
    let beats = server.hits("POST", &format!("/v1/hosting/rooms/{ROOM}/heartbeat"));
    assert_eq!((beats[0].json()["players"].clone(), beats[1].json()["players"].clone()), (json!(1), json!(10)));
    let gone = launcher.hosting_heartbeat("h00000000000000000042", 1).await.unwrap_err();
    assert_eq!(gone.code(), Some("room_not_found"));
    assert_eq!(launcher.hosting_invite("h00000000000000000042", &uuids, false).await.unwrap_err().code(), Some("room_not_found"));

    launcher.hosting_signal_bye(ROOM, "B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0", "s1").await.unwrap();
    assert_eq!(
        server.hits("POST", &format!("/v1/hosting/rooms/{ROOM}/signal"))[0].json(),
        json!({ "to": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "kind": "bye", "sid": "s1", "data": "" })
    );
    assert!(launcher.hosting_signal_bye(ROOM, "x", "s1").await.is_err());
    launcher.hosting_close_room(ROOM).await.unwrap();
    launcher.hosting_close_room("h00000000000000000042").await.unwrap_err();
}

// --- Relay-Host gegen ein nachgebautes Relay -------------------------------------------

/// Minecraft-Server-Attrappe: antwortet auf jeden Block mit `pong:` + Block.
async fn echo_server() -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        while let Ok((mut s, _)) = listener.accept().await {
            tokio::spawn(async move {
                let mut buf = [0u8; 256];
                while let Ok(n) = s.read(&mut buf).await {
                    if n == 0 {
                        break;
                    }
                    let mut out = b"pong:".to_vec();
                    out.extend_from_slice(&buf[..n]);
                    if s.write_all(&out).await.is_err() {
                        break;
                    }
                }
            });
        }
    });
    addr
}

async fn expect_hello(s: &mut TcpStream) -> (u8, Vec<u8>) {
    let mut pre = [0u8; 5];
    s.read_exact(&mut pre).await.unwrap();
    assert_eq!(&pre, b"TRSR\x01");
    relay_host::read_frame(s).await.unwrap()
}

fn timing() -> Timing {
    Timing {
        connect: Duration::from_secs(2),
        handshake: Duration::from_secs(2),
        ping_every: Duration::from_millis(200),
        silence: Duration::from_secs(5),
        backoff: vec![Duration::from_millis(20)],
    }
}

fn grants(port: u16, fetched: Arc<AtomicUsize>) -> GrantFn {
    Arc::new(move || {
        let n = fetched.fetch_add(1, Ordering::SeqCst);
        Box::pin(async move { Ok(RelayGrant { host: "127.0.0.1".into(), port, token: format!("trsr1.fresh{n}") }) })
    })
}

fn collect() -> (ShareEvents, mpsc::UnboundedReceiver<ShareEvent>) {
    let (tx, rx) = mpsc::unbounded_channel();
    (Arc::new(move |e| drop(tx.send(e))), rx)
}

#[tokio::test]
async fn relay_host_pairs_guests_with_the_local_server() {
    let mc = echo_server().await;
    let relay = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = relay.local_addr().unwrap().port();
    let fetched = Arc::new(AtomicUsize::new(0));
    let (stop_tx, stop_rx) = watch::channel(false);
    let (events, mut seen) = collect();
    let first = RelayGrant { host: "127.0.0.1".into(), port, token: "trsr1.first".into() };
    let host = tokio::spawn(relay_host::run(grants(port, fetched.clone()), Some(first), mc, timing(), stop_rx, events));

    // 1. Versuch: Token abgelaufen → sofort mit frischem Token neu.
    let (mut control, _) = relay.accept().await.unwrap();
    assert_eq!(expect_hello(&mut control).await, (relay_host::HOST_HELLO, b"trsr1.first".to_vec()));
    control.write_all(&relay_host::frame(relay_host::ERROR, b"expired")).await.unwrap();
    drop(control);
    let (mut control, _) = relay.accept().await.unwrap();
    assert_eq!(expect_hello(&mut control).await, (relay_host::HOST_HELLO, b"trsr1.fresh0".to_vec()));
    control.write_all(&relay_host::frame(relay_host::WELCOME, br#"{"room":"h1","maxGuests":9}"#)).await.unwrap();
    assert_eq!(seen.recv().await, Some(ShareEvent::RelayOffline("expired".into())));
    assert_eq!(seen.recv().await, Some(ShareEvent::RelayOnline));

    // PING vom Relay → PONG mit denselben Bytes; der Host pingt selbst auch.
    control.write_all(&relay_host::frame(relay_host::PING, b"abc")).await.unwrap();
    let mut got_pong = false;
    let mut got_ping = false;
    while !(got_pong && got_ping) {
        let (kind, payload) = relay_host::read_frame(&mut control).await.unwrap();
        match kind {
            relay_host::PONG => {
                assert_eq!(payload, b"abc");
                got_pong = true;
            }
            relay_host::PING => got_ping = true,
            other => panic!("unerwartet {other:#x}"),
        }
    }

    // Gast kommt: GUEST_OPEN → Host öffnet Datenverbindung mit PAIR → roh zum Server.
    let pair_id = [7u8; 16];
    let mut open = pair_id.to_vec();
    open.extend_from_slice(&[9u8; 16]);
    control.write_all(&relay_host::frame(relay_host::GUEST_OPEN, &open)).await.unwrap();
    let (mut data, _) = relay.accept().await.unwrap();
    assert_eq!(expect_hello(&mut data).await, (relay_host::PAIR, pair_id.to_vec()));
    data.write_all(&relay_host::frame(relay_host::WELCOME, br#"{"room":"h1"}"#)).await.unwrap();
    data.write_all(b"\x10handshake").await.unwrap();
    let mut buf = vec![0u8; 15];
    tokio::time::timeout(Duration::from_secs(3), data.read_exact(&mut buf)).await.unwrap().unwrap();
    assert_eq!(buf, b"pong:\x10handshake");

    // Ausschalten beendet die Schleife.
    stop_tx.send(true).unwrap();
    assert_eq!(tokio::time::timeout(Duration::from_secs(3), host).await.unwrap().unwrap(), ShareEnd::Stopped);
    assert_eq!(fetched.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn relay_host_gives_up_when_replaced() {
    let relay = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = relay.local_addr().unwrap().port();
    let (_stop_tx, stop_rx) = watch::channel(false);
    let (events, _seen) = collect();
    let first = RelayGrant { host: "127.0.0.1".into(), port, token: "trsr1.first".into() };
    let host = tokio::spawn(relay_host::run(grants(port, Arc::default()), Some(first), "127.0.0.1:1".parse().unwrap(), timing(), stop_rx, events));
    let (mut control, _) = relay.accept().await.unwrap();
    expect_hello(&mut control).await;
    control.write_all(&relay_host::frame(relay_host::WELCOME, b"{}")).await.unwrap();
    control.write_all(&relay_host::frame(relay_host::ERROR, b"replaced")).await.unwrap();
    let end = tokio::time::timeout(Duration::from_secs(3), host).await.unwrap().unwrap();
    assert_eq!(end, ShareEnd::Fatal("replaced".into()));
}

#[test]
fn relay_frames() {
    assert_eq!(relay_host::frame(relay_host::PAIR, &[1, 2]), vec![0x03, 0, 2, 1, 2]);
    assert_eq!(relay_host::hello(relay_host::HOST_HELLO, b"t"), b"TRSR\x01\x01\x00\x01t".to_vec());
    assert_eq!(relay_host::frame(relay_host::PING, &[0u8; 2000]).len(), 3 + relay_host::MAX_FRAME);
    assert_eq!(relay_host::error_code(b"room_full"), "room_full");
    assert_eq!(relay_host::error_code(b"<script>"), "error");
}

#[tokio::test]
async fn oversized_relay_frames_are_rejected() {
    let (mut a, mut b) = tokio::io::duplex(4096);
    a.write_all(&[0x81, 0x10, 0x00]).await.unwrap();
    assert!(relay_host::read_frame(&mut b).await.is_err(), "4096 > 1024");
}

// --- e4mc -----------------------------------------------------------------------------

#[test]
fn e4mc_control_framing() {
    assert_eq!(e4mc::encode_control("{}"), b"\x02{}".to_vec());
    let long = "x".repeat(200);
    let encoded = e4mc::encode_control(&long);
    assert_eq!(&encoded[..2], &[0xC8, 0x01], "VarInt 200");
    assert_eq!(encoded.len(), 202);

    assert_eq!(
        e4mc::parse_control(br#"{"kind":"domain_assignment_complete","domain":"Calm-Fox.eu.e4mc.link"}"#),
        e4mc::ControlMsg::Domain("calm-fox.eu.e4mc.link".into())
    );
    assert_eq!(e4mc::parse_control(br#"{"kind":"domain_assignment_complete","domain":"evil/../x"}"#), e4mc::ControlMsg::Other);
    assert_eq!(
        e4mc::parse_control(br#"{"kind":"request_message_broadcast","message":"Hi\nall"}"#),
        e4mc::ControlMsg::Broadcast("Hi all".into())
    );
    assert_eq!(e4mc::parse_control(b"nope"), e4mc::ControlMsg::Other);
    assert!(e4mc::valid_domain("a-b.eu.e4mc.link") && !e4mc::valid_domain("-a.e4mc.link") && !e4mc::valid_domain("localhost"));

    assert_eq!(e4mc::parse_broker(r#"{"id":"eu","host":"eu.e4mc.link","port":25575}"#), Some(("eu.e4mc.link".into(), 25575)));
    assert_eq!(e4mc::parse_broker(r#"{"host":"x y","port":1}"#), None);
    assert_eq!(e4mc::parse_broker(r#"{"host":"eu.e4mc.link","port":70000}"#), None);
}

#[tokio::test]
async fn e4mc_domain_request_over_a_stream() {
    let (mut client, mut relay) = tokio::io::duplex(4096);
    let fake = tokio::spawn(async move {
        // Zwei Nachrichten mit VarInt-Länge lesen.
        let mut kinds = Vec::new();
        for _ in 0..2 {
            let len = relay.read_u8().await.unwrap() as usize;
            let mut buf = vec![0u8; len];
            relay.read_exact(&mut buf).await.unwrap();
            kinds.push(serde_json::from_slice::<serde_json::Value>(&buf).unwrap()["kind"].as_str().unwrap().to_owned());
        }
        for msg in [r#"{"kind":"has_capabilities","caps":[]}"#, r#"{"kind":"domain_assignment_complete","domain":"x-y.eu.e4mc.link"}"#] {
            let mut out = vec![msg.len() as u8];
            out.extend_from_slice(msg.as_bytes());
            relay.write_all(&out).await.unwrap();
        }
        kinds
    });
    let (mut rd, mut wr) = tokio::io::split(&mut client);
    assert_eq!(e4mc::request_domain(&mut wr, &mut rd).await, Ok("x-y.eu.e4mc.link".into()));
    assert_eq!(fake.await.unwrap(), ["probe_capabilities", "request_domain_assignment"]);
}

/// Selbstsigniertes Test-Zertifikat für `localhost` (nur für den nachgebauten Broker).
#[cfg(not(any(target_os = "android", target_os = "ios")))]
const TEST_CERT: &str = "MIIBqDCCAU2gAwIBAgIUIV0bmKPyEAB5cqpwPyoVWTjkmVAwCgYIKoZIzj0EAwIwFDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MTAwNDEzNTAzOVoYDzIxMjYwOTEwMTM1MDM5WjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARnNLzP3PI8I4zFtqWQr3wwE5qQ+2KCGo6o6wIYKDsDMiu9ZtlW5JHpTsuJsxXsQt1x4VhkhNOSVYOl6f4+6okwo3sweTAdBgNVHQ4EFgQUXs9K+VborX8YT0SOffUj/a2x4PgwHwYDVR0jBBgwFoAUXs9K+VborX8YT0SOffUj/a2x4PgwFAYDVR0RBA0wC4IJbG9jYWxob3N0MAwGA1UdEwEB/wQCMAAwEwYDVR0lBAwwCgYIKwYBBQUHAwEwCgYIKoZIzj0EAwIDSQAwRgIhALBzkj7UiMX9mgE61nK7D2rMiReLG2LZD/57LWMg+Zu6AiEA5oX/QUDWz30VyuCuoPAQ6pRKOmWxuuqTHnqX5F1PW9M=";
#[cfg(not(any(target_os = "android", target_os = "ios")))]
const TEST_KEY: &str = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg693zRhgo2/0sgKOcgHVhg4AjfAnMLmVvsaXuX19lfuyhRANCAARnNLzP3PI8I4zFtqWQr3wwE5qQ+2KCGo6o6wIYKDsDMiu9ZtlW5JHpTsuJsxXsQt1x4VhkhNOSVYOl6f4+6okw";

#[cfg(not(any(target_os = "android", target_os = "ios")))]
#[tokio::test]
async fn e4mc_tunnel_against_a_fake_quic_relay() {
    use base64::Engine;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    let b64 = base64::engine::general_purpose::STANDARD;
    let cert = CertificateDer::from(b64.decode(TEST_CERT).unwrap());
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(b64.decode(TEST_KEY).unwrap()));
    let provider = Arc::new(rustls::crypto::ring::default_provider());

    // Nachgebautes Relay: Domain vergeben, dann einen „Spieler“-Strom öffnen.
    let mut server_tls = rustls::ServerConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert.clone()], key)
        .unwrap();
    server_tls.alpn_protocols = vec![e4mc::ALPN.to_vec()];
    let server_config =
        quinn::ServerConfig::with_crypto(Arc::new(quinn::crypto::rustls::QuicServerConfig::try_from(server_tls).unwrap()));
    let endpoint = quinn::Endpoint::server(server_config, "127.0.0.1:0".parse().unwrap()).unwrap();
    let relay_port = endpoint.local_addr().unwrap().port();
    let relay = tokio::spawn(async move {
        let conn = endpoint.accept().await.unwrap().await.unwrap();
        let (mut send, mut recv) = conn.accept_bi().await.unwrap();
        for _ in 0..2 {
            let len = recv.read_u8().await.unwrap() as usize;
            let mut buf = vec![0u8; len];
            recv.read_exact(&mut buf).await.unwrap();
        }
        let msg = br#"{"kind":"domain_assignment_complete","domain":"calm-fox.eu.e4mc.link"}"#;
        let mut out = vec![msg.len() as u8];
        out.extend_from_slice(msg);
        send.write_all(&out).await.unwrap();
        // Ein Spieler: Handshake-Bytes rein, Antwort des Servers zurück.
        let (mut ps, mut pr) = conn.open_bi().await.unwrap();
        ps.write_all(b"\x0fhandshake").await.unwrap();
        let mut reply = vec![0u8; 15];
        pr.read_exact(&mut reply).await.unwrap();
        ps.finish().unwrap();
        (reply, conn, endpoint)
    });

    let mut roots = rustls::RootCertStore::empty();
    roots.add(cert).unwrap();
    let mut tls = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .with_root_certificates(roots)
        .with_no_client_auth();
    tls.alpn_protocols = vec![e4mc::ALPN.to_vec()];
    let config = super::e4mc_quic::finish_config(tls).unwrap();

    let mc = echo_server().await;
    let (stop_tx, mut stop_rx) = watch::channel(false);
    let (events, mut seen) = collect();
    let client = tokio::spawn(async move {
        super::e4mc_quic::session(config, ("localhost".into(), relay_port), mc, &mut stop_rx, &events).await
    });
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(5), seen.recv()).await.unwrap(),
        Some(ShareEvent::E4mcDomain("calm-fox.eu.e4mc.link".into()))
    );
    let (reply, _conn, _endpoint) = tokio::time::timeout(Duration::from_secs(5), relay).await.unwrap().unwrap();
    assert_eq!(reply, b"pong:\x0fhandshake");
    stop_tx.send(true).unwrap();
    assert_eq!(tokio::time::timeout(Duration::from_secs(5), client).await.unwrap().unwrap(), Ok(()));
}
