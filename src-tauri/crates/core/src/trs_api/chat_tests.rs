//! Chat, Bilder, Meldungen und Echtzeit-Kanal gegen eine nachgebaute API.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use super::chat::{OutgoingMessage, PageAt};
use super::live::{LiveConfig, LiveEvent, LiveOut};
use super::media::UploadSource;
use super::moderation::{ChatReportReason, NewReport, ReportTarget};
use super::testkit::{MockServer, Request, Response};
use super::tests::{ACC, auth_routes, launcher};
use super::*;

const CONV: &str = "c1f0e2d3c4b5a6978899a";
const BOB: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";
const ATT: &str = "a0123456789abcdef01234567";

fn message(seq: u64, text: &str) -> serde_json::Value {
    json!({ "id": format!("m{:020x}", seq), "conversationId": CONV, "seq": seq, "kind": "text",
        "sender": { "uuid": BOB, "name": "Bob" }, "text": text, "invite": null, "attachments": [], "replyTo": null,
        "system": null, "reactions": [], "createdAt": "2026-09-26T10:00:00.000Z", "editedAt": null,
        "deleted": false, "deletedBy": null, "hidden": false, "nonce": null })
}

fn conversation() -> serde_json::Value {
    json!({ "id": CONV, "kind": "dm", "name": null, "owner": null,
        "members": [{ "uuid": ACC, "name": "Theredstonee", "role": "member", "joinedAt": "…" }, { "uuid": BOB, "name": "Bob", "role": "member", "joinedAt": "…" }],
        "peer": { "uuid": BOB, "name": "Bob" }, "canWrite": true, "readOnlyReason": null, "lastMessage": message(3, "Hi"),
        "lastSeq": 3, "unread": 1, "markedUnread": false, "readSeq": 2, "muted": false, "mutedUntil": null, "reads": [],
        "createdAt": "…", "updatedAt": "…" })
}

fn tiny_png() -> Vec<u8> {
    super::media::tests::png(8, 4, 255)
}

fn chat_api(images: Arc<AtomicUsize>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        let conv_path = format!("/v1/chat/conversations/{CONV}");
        let path = req.path.as_str();
        match (req.method.as_str(), path) {
            ("POST", "/v1/chat/dms") => Response::json(200, json!({ "conversation": conversation() })),
            ("GET", p) if p.starts_with("/v1/chat/conversations?") => {
                Response::json(200, json!({ "conversations": [conversation(), { "id": "bad" }], "nextCursor": "abc=" }))
            }
            ("GET", p) if p.starts_with(&format!("{conv_path}/messages")) => Response::json(
                200,
                json!({ "messages": [message(5, "neu"), message(4, "alt"), message(4, "doppelt")], "hasMore": false }),
            ),
            ("POST", p) if p == format!("{conv_path}/messages") => {
                let body = req.json();
                let mut m = message(6, body["text"].as_str().unwrap_or(""));
                m["sender"] = json!({ "uuid": ACC, "name": "Theredstonee" });
                m["nonce"] = body["nonce"].clone();
                Response::json(201, json!({ "message": m }))
            }
            ("PUT" | "DELETE", p) if p.ends_with("/reactions/fire") => Response::json(
                200,
                json!({ "reactions": if req.method == "PUT" { json!([{ "emoji": "fire", "count": 1, "users": [ACC] }]) } else { json!([]) } }),
            ),
            ("POST", p) if p == format!("{conv_path}/read") || p == format!("{conv_path}/unread") => {
                Response::json(200, json!({ "conversation": conversation() }))
            }
            ("PUT", p) if p == format!("{conv_path}/mute") => Response::json(200, json!({ "conversation": conversation() })),
            ("POST", p) if p == format!("{conv_path}/typing") => Response::empty(204),
            ("POST", "/v1/chat/attachments") => {
                if req.header("content-type") != Some("image/png") || !req.body.starts_with(b"\x89PNG") {
                    return Response::error(415, "unsupported_media_type");
                }
                Response::json(201, json!({ "attachment": { "id": ATT, "mime": "image/png", "width": 8, "height": 4, "bytes": req.body.len(),
                    "path": format!("/v1/chat/attachments/{ATT}"), "thumb": { "width": 8, "height": 4, "path": "x" } } }))
            }
            ("GET", p) if p == format!("/v1/chat/attachments/{ATT}?thumb=1") => {
                images.fetch_add(1, Ordering::SeqCst);
                Response::png(tiny_png())
            }
            ("GET", p) if p.starts_with("/v1/admin/reports/r0123456789abcdef/images/") => {
                Response { status: 200, headers: vec![("content-type".into(), "text/html".into())], body: b"<script>".to_vec() }
            }
            ("POST", "/v1/reports") => Response::json(
                201,
                json!({ "report": { "id": "r0123456789abcdef", "kind": "message", "reason": "spam", "status": "open", "outcome": null, "createdAt": "…", "updatedAt": "…" } }),
            ),
            ("GET", p) if p.starts_with("/v1/servers/status?address=play.example.net%3A25566") => Response::json(
                200,
                json!({ "status": { "address": "play.example.net:25566", "online": true, "players": { "online": 3, "max": 20 }, "icon": null } }),
            ),
            _ => Response::error(404, "not_found"),
        }
    }
}

#[tokio::test]
async fn chat_client_talks_the_contract() {
    let images = Arc::new(AtomicUsize::new(0));
    let server = MockServer::start(chat_api(Arc::clone(&images))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let dm = launcher.chat_open_dm(&BOB.to_uppercase()).await.unwrap();
    assert_eq!(dm.id, CONV);
    assert_eq!(server.hits("POST", "/v1/chat/dms")[0].json(), json!({ "uuid": BOB }));

    let page = launcher.chat_conversations(None, Some(500)).await.unwrap();
    assert_eq!(page.conversations.len(), 1, "kaputte Einträge fallen weg");
    assert_eq!(page.next_cursor.as_deref(), Some("abc="));
    assert!(server.requests().iter().any(|r| r.path == "/v1/chat/conversations?limit=100"), "limit begrenzt");
    launcher.chat_conversations(Some("abc="), None).await.unwrap();
    assert!(server.requests().iter().any(|r| r.path == "/v1/chat/conversations?limit=50&cursor=abc%3D"));
    assert!(launcher.chat_conversations(Some("a&b"), None).await.is_err());

    let msgs = launcher.chat_messages(CONV, PageAt::After(3), None).await.unwrap();
    assert_eq!(msgs.messages.iter().map(|m| m.seq).collect::<Vec<_>>(), [4, 5], "aufsteigend, ohne Doppelte");
    assert!(server.requests().iter().any(|r| r.path.ends_with("/messages?limit=50&after=3")));

    let sent = launcher
        .chat_send(CONV, &OutgoingMessage { text: Some("Hallo\u{202E}".into()), nonce: Some("nonce-0001".into()), ..Default::default() })
        .await
        .unwrap();
    assert_eq!(sent.text.as_deref(), Some("Hallo"));
    assert_eq!(sent.nonce.as_deref(), Some("nonce-0001"));
    let body = server.hits("POST", &format!("/v1/chat/conversations/{CONV}/messages"))[0].json();
    assert_eq!(body, json!({ "text": "Hallo", "nonce": "nonce-0001" }));

    let on = launcher.chat_react("m00000000000000000006", "fire", true).await.unwrap();
    assert_eq!(on[0].count, 1);
    assert!(launcher.chat_react("m00000000000000000006", "fire", false).await.unwrap().is_empty());
    assert!(launcher.chat_react("m00000000000000000006", "poop", true).await.is_err());

    launcher.chat_read(CONV, 5).await.unwrap();
    launcher.chat_mark_unread(CONV, Some(4)).await.unwrap();
    assert_eq!(server.hits("POST", &format!("/v1/chat/conversations/{CONV}/unread"))[0].json(), json!({ "seq": 4 }));
    launcher.chat_mute(CONV, true, Some("2099-01-01T00:00:00+01:00")).await.unwrap();
    assert_eq!(
        server.hits("PUT", &format!("/v1/chat/conversations/{CONV}/mute"))[0].json(),
        json!({ "muted": true, "until": "2098-12-31T23:00:00.000Z" })
    );
    launcher.chat_typing(CONV, true).await.unwrap();

    // Gruppen: Eingaben werden vorher geprüft.
    assert!(launcher.chat_create_group("", &[]).await.is_err());
    let too_many: Vec<String> = (0..25).map(|i| format!("{i:032x}")).collect();
    assert_eq!(launcher.chat_create_group("Crew", &too_many).await.unwrap_err().message_code(), "chat.groupFull");

    // Meldung.
    let report = launcher
        .chat_report(&NewReport {
            target: ReportTarget::Message { message_id: "m00000000000000000005".into() },
            reason: ChatReportReason::Spam,
            note: None,
        })
        .await
        .unwrap();
    assert_eq!(report.status, "open");

    // Server-Status einer Einladung.
    let status = launcher.chat_server_status("Play.Example.net:25566").await.unwrap();
    assert_eq!((status.online, status.players_online), (true, Some(3)));
    assert!(launcher.chat_server_status("127.0.0.1; rm").await.is_err());
}

#[tokio::test]
async fn images_are_checked_uploaded_and_served_without_tokens() {
    let images = Arc::new(AtomicUsize::new(0));
    let server = MockServer::start(chat_api(Arc::clone(&images))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    assert!(launcher.chat_stage_bytes("x.txt", b"not an image".to_vec()).await.is_err());
    let local = launcher.chat_stage_bytes("bild.png", tiny_png()).await.unwrap();
    assert_eq!((local.width, local.height), (8, 4));
    assert_eq!(launcher.chat_local_images().await[0].id, local.id);

    let attachment = launcher.chat_upload(&UploadSource::Local { id: local.id.clone() }).await.unwrap();
    assert_eq!(attachment.id, ATT);
    assert!(launcher.chat_upload(&UploadSource::Local { id: "l00000000000000000000".into() }).await.is_err());
    assert!(
        launcher
            .chat_upload(&UploadSource::Screenshot { instance_id: "../x".into(), file_name: "a.png".into() })
            .await
            .is_err()
    );

    // Vorschau über trschat: – zweimal, aber nur eine Anfrage (Zwischenspeicher).
    for _ in 0..2 {
        let r = launcher.serve_chat_image(&format!("/t/{ATT}")).await;
        assert_eq!(r.status, 200);
        assert!(r.headers.iter().any(|(k, v)| *k == "Content-Type" && v == "image/png"));
    }
    assert_eq!(images.load(Ordering::SeqCst), 1);
    let thumb = launcher.serve_chat_image(&format!("/l/{}", local.id)).await;
    assert_eq!(thumb.status, 200, "Vorschau eigener Dateien");
    // Unsinn und Nicht-Bilder aus der API → nichts ausliefern.
    assert_eq!(launcher.serve_chat_image("/a/../../etc/passwd").await.status, 404);
    assert_eq!(launcher.serve_chat_image(&format!("/e/r0123456789abcdef/{ATT}")).await.status, 404);
    assert_eq!(launcher.serve_chat_image("/x").await.status, 404);

    // Favoriten brauchen eine echte Screenshot-Datei.
    assert!(launcher.set_screenshot_favorite("demo", "nope.png", true).await.is_err());
    assert!(launcher.screenshot_favorites().await.is_empty());
}

// --- Echtzeit-Kanal ---------------------------------------------------------------------------

fn test_config() -> LiveConfig {
    LiveConfig {
        ping_timeout: Duration::from_millis(400),
        backoff: vec![Duration::from_millis(20), Duration::from_millis(40)],
        recheck: Duration::from_millis(50),
        banned_wait: Duration::from_millis(200),
    }
}

fn sse(frames: &str) -> Response {
    Response {
        status: 200,
        headers: vec![("content-type".into(), "text/event-stream".into())],
        body: frames.as_bytes().to_vec(),
    }
}

fn collect(launcher: &crate::Launcher) -> Arc<Mutex<Vec<LiveOut>>> {
    let log = Arc::new(Mutex::new(Vec::new()));
    let sink = Arc::clone(&log);
    launcher.set_trs_live_sink(Arc::new(move |out| sink.lock().unwrap().push(out)));
    log
}

/// Lässt die Schleife laufen, bis `done` zutrifft (höchstens 5 s).
async fn run_until(launcher: &crate::Launcher, cfg: &LiveConfig, done: impl Fn() -> bool) {
    let run = launcher.trs.run_live(launcher.accounts(), launcher.accounts(), cfg);
    let wait = async {
        for _ in 0..500 {
            if done() {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("Zeitüberschreitung");
    };
    tokio::select! {
        () = run => unreachable!(),
        () = wait => {}
    }
}

fn events(log: &Arc<Mutex<Vec<LiveOut>>>) -> Vec<LiveEvent> {
    log.lock().unwrap().iter().filter_map(|o| if let LiveOut::Event(e) = o { Some(e.clone()) } else { None }).collect()
}

#[tokio::test]
async fn stream_resumes_with_last_event_id() {
    let connects = Arc::new(AtomicUsize::new(0));
    let c = Arc::clone(&connects);
    let issued = AtomicUsize::new(0);
    let server = MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if req.path != "/v1/events/me" {
            return Response::error(404, "not_found");
        }
        assert_eq!(req.header("accept"), Some("text/event-stream"));
        let msg = json!({ "type": "chat_message", "conversationId": CONV, "message": message(7, "hey") });
        match c.fetch_add(1, Ordering::SeqCst) {
            0 => {
                assert!(req.header("last-event-id").is_none(), "erste Verbindung ohne ID");
                sse(&format!(
                    "id: e1.1\nevent: hello\ndata: {{\"type\":\"hello\",\"resumed\":false}}\n\nid: e1.2\nevent: chat_message\ndata: {msg}\n\nevent: chat_typing\ndata: {{\"conversationId\":\"{CONV}\",\"uuid\":\"{BOB}\",\"typing\":true}}\n\n"
                ))
            }
            _ => {
                assert_eq!(req.header("last-event-id"), Some("e1.2"), "Wiederaufnahme mit der letzten ID");
                sse("id: e1.3\nevent: hello\ndata: {\"type\":\"hello\",\"resumed\":true}\n\nevent: resync\ndata: {\"type\":\"resync\",\"reason\":\"gap\"}\n\n")
            }
        }
    })
    .await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let log = collect(&launcher);
    let cfg = test_config();
    run_until(&launcher, &cfg, || events(&log).iter().any(|e| matches!(e, LiveEvent::Resync { .. }))).await;

    let ev = events(&log);
    assert_eq!(ev[0], LiveEvent::Hello { resumed: false, first: true });
    assert!(matches!(&ev[1], LiveEvent::ChatMessage { message, .. } if message.seq == 7));
    assert!(matches!(&ev[2], LiveEvent::ChatTyping { typing: true, .. }));
    assert_eq!(ev[3], LiveEvent::Hello { resumed: true, first: false });
    assert_eq!(ev[4], LiveEvent::Resync { reason: "gap".into() });
    let states: Vec<&str> = log
        .lock()
        .unwrap()
        .iter()
        .filter_map(|o| if let LiveOut::Status(s) = o { Some(s.state) } else { None })
        .collect();
    assert_eq!(&states[..4], ["connecting", "live", "down", "connecting"]);
}

#[tokio::test]
async fn stream_relogs_once_on_401_and_backs_off() {
    let connects = Arc::new(AtomicUsize::new(0));
    let c = Arc::clone(&connects);
    let issued = AtomicUsize::new(0);
    let server = MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        match c.fetch_add(1, Ordering::SeqCst) {
            0 => Response::error(401, "unauthorized"),
            1 => sse("id: e.1\nevent: hello\ndata: {\"resumed\":false}\n\n"),
            _ => Response::error(503, "too_many_streams"),
        }
    })
    .await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let log = collect(&launcher);
    let cfg = test_config();
    run_until(&launcher, &cfg, || connects.load(Ordering::SeqCst) >= 5).await;
    // Erst anmelden (kein Token), 401 → einmal neu anmelden, dann klappt es.
    assert_eq!(server.hits("POST", "/v1/auth/verify").len(), 2);
    assert!(events(&log).contains(&LiveEvent::Hello { resumed: false, first: true }));
    let downs: Vec<u64> = log
        .lock()
        .unwrap()
        .iter()
        .filter_map(|o| match o {
            LiveOut::Status(s) if s.state == "down" => s.retry_in_ms,
            _ => None,
        })
        .collect();
    // 401 direkt nach frischer Anmeldung → Pause; nach der Live-Verbindung wieder kurz, dann länger.
    assert!(downs.len() >= 3, "{downs:?}");
    assert_eq!(&downs[..3], [40, 20, 40]);
}

#[tokio::test]
async fn silent_stream_is_dropped_after_the_ping_timeout() {
    // Eigener Server: schickt nur Kopf + hello und schweigt dann.
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let base = format!("http://127.0.0.1:{}", listener.local_addr().unwrap().port());
    let connects = Arc::new(AtomicUsize::new(0));
    let c = Arc::clone(&connects);
    let task = tokio::spawn(async move {
        let mut open = Vec::new();
        loop {
            let Ok((mut stream, _)) = listener.accept().await else { return };
            let mut buf = [0u8; 4096];
            let _ = stream.read(&mut buf).await;
            c.fetch_add(1, Ordering::SeqCst);
            let _ = stream
                .write_all(b"HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\ntransfer-encoding: chunked\r\n\r\n")
                .await;
            let frame = b"id: e.1\nevent: hello\ndata: {}\n\n";
            let _ = stream.write_all(format!("{:x}\r\n", frame.len()).as_bytes()).await;
            let _ = stream.write_all(frame).await;
            let _ = stream.write_all(b"\r\n").await;
            let _ = stream.flush().await;
            open.push(stream);
        }
    });
    let mock = MockServer::start(|_| Response::error(404, "not_found")).await;
    let (_dir, mut launcher) = launcher(&mock, &[ACC]).await;
    let l = Arc::get_mut(&mut launcher).unwrap();
    l.trs = TrsApi::with_endpoints(l.paths.clone(), &base, &base, &base).unwrap();
    l.trs.store.set_consent(Consent::Accepted).await;
    l.trs.store.put_token(ACC, &format!("trs_{}", "A".repeat(43)), chrono::Utc::now() + chrono::Duration::days(1)).await.unwrap();
    let cfg = test_config();
    run_until(&launcher, &cfg, || connects.load(Ordering::SeqCst) >= 2).await;
    task.abort();
}

#[tokio::test]
async fn stream_stays_off_without_consent() {
    let server = MockServer::start(|_| Response::error(500, "internal_error")).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    launcher.trs.store.set_consent(Consent::Declined).await;
    let log = collect(&launcher);
    let cfg = test_config();
    run_until(&launcher, &cfg, || !log.lock().unwrap().is_empty()).await;
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert!(server.requests().is_empty(), "ohne Einwilligung keine einzige Anfrage");
    assert_eq!(launcher.trs_live_status().state, "off");
}

// --- Gegen die echte API (lokal gestartet) -------------------------------------------------------

/// Launcher mit einem Account (Name frei) gegen eine echte, lokal laufende API.
async fn live_launcher(base: &str, mojang: &str, uuid: &str, name: &str) -> (tempfile::TempDir, Arc<crate::Launcher>) {
    let dir = tempfile::tempdir().unwrap();
    let account = json!({
        "id": uuid, "name": name, "skinUrl": null, "xuid": "1",
        "refreshToken": crate::auth::crypto::protect("refresh").unwrap(),
        "accessToken": crate::auth::crypto::protect("mc-token").unwrap(),
        "accessExpiresAt": "2099-01-01T00:00:00Z", "addedAt": "2026-09-01T00:00:00Z"
    });
    std::fs::write(dir.path().join("accounts.json"), json!({ "active": uuid, "accounts": [account] }).to_string()).unwrap();
    let mut launcher = crate::Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
    launcher.trs = TrsApi::with_endpoints(launcher.paths.clone(), base, mojang, mojang).unwrap();
    launcher.trs.store.set_consent(Consent::Accepted).await;
    (dir, Arc::new(launcher))
}

/// Wartet höchstens 3 s auf ein Ereignis, das `pick` erkennt.
async fn expect_event<T>(log: &Arc<Mutex<Vec<LiveOut>>>, what: &str, pick: impl Fn(&LiveEvent) -> Option<T>) -> T {
    let start = std::time::Instant::now();
    loop {
        if let Some(found) = events(log).iter().rev().find_map(&pick) {
            println!("  ✓ {what} nach {} ms", start.elapsed().as_millis());
            return found;
        }
        assert!(start.elapsed() < Duration::from_secs(3), "kein Ereignis „{what}“ innerhalb von 3 s: {:?}", events(log));
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

/// Voller Durchlauf gegen die echte API (Vertrag §18–§20) inklusive Echtzeit-Kanal.
///
/// `TRS_LIVE_BASE=http://127.0.0.1:28910 TRS_LIVE_MOJANG=http://127.0.0.1:28911 \
///  cargo test -p trs-core live_local_api -- --ignored --nocapture`
///
/// Mit `TRS_LIVE_SAMPLES=<datei>` schreibt der Test die Ansichten (so wie sie
/// ans Webview gehen) als JSON – damit prüft ein Vitest die zod-Schemas.
#[tokio::test]
#[ignore = "braucht eine lokal laufende TRS API"]
async fn live_local_api() {
    let (Ok(base), Ok(mojang)) = (std::env::var("TRS_LIVE_BASE"), std::env::var("TRS_LIVE_MOJANG")) else {
        eprintln!("TRS_LIVE_BASE/TRS_LIVE_MOJANG fehlen – übersprungen");
        return;
    };
    let mut samples = serde_json::Map::new();
    let (_da, a) = live_launcher(&base, &mojang, ACC, "Theredstonee").await;
    let (_db, b) = live_launcher(&base, &mojang, BOB, "Bob").await;
    let me = a.trs_me().await.unwrap();
    assert!(me.admin, "Theredstonee ist Admin (ADMIN_UUIDS)");
    assert!(me.settings.chat_read_receipts);
    b.trs_me().await.unwrap();

    // Freunde werden (falls schon befreundet: egal).
    let _ = a.trs_friend_request("Bob").await;
    let _ = b.trs_friend_accept(ACC).await;
    assert!(a.trs_friends().await.unwrap().friends.iter().any(|f| f.uuid == BOB));

    // Echtzeit-Kanal für A.
    let log = collect(&a);
    let la = Arc::clone(&a);
    let live = tokio::spawn(async move { la.trs.run_live(la.accounts(), la.accounts(), &LiveConfig::default()).await });
    expect_event(&log, "hello", |e| matches!(e, LiveEvent::Hello { .. }).then_some(())).await;

    // DM, Tippen, Nachricht.
    let dm = b.chat_open_dm(ACC).await.unwrap();
    b.chat_typing(&dm.id, true).await.unwrap();
    expect_event(&log, "chat_typing", |e| matches!(e, LiveEvent::ChatTyping { typing: true, .. }).then_some(())).await;
    let sent = b
        .chat_send(&dm.id, &OutgoingMessage { text: Some("Hallo 👋 https://example.com".into()), nonce: Some(format!("live-{}", chrono::Utc::now().timestamp_millis())), ..Default::default() })
        .await
        .unwrap();
    let got = expect_event(&log, "chat_message", |e| match e {
        LiveEvent::ChatMessage { message, .. } if message.id == sent.id => Some(message.clone()),
        _ => None,
    })
    .await;
    assert_eq!(got.text.as_deref(), Some("Hallo 👋 https://example.com"));

    let page = a.chat_conversations(None, None).await.unwrap();
    let conv = page.conversations.iter().find(|c| c.id == dm.id).expect("DM in der Liste");
    assert!(conv.unread >= 1);
    samples.insert("conversationPage".into(), serde_json::to_value(&page).unwrap());
    let msgs = a.chat_messages(&dm.id, PageAt::Latest, None).await.unwrap();
    samples.insert("messagePage".into(), serde_json::to_value(&msgs).unwrap());
    samples.insert("unread".into(), serde_json::to_value(a.chat_unread().await.unwrap()).unwrap());
    a.chat_read(&dm.id, sent.seq).await.unwrap();
    expect_event(&log, "chat_state (gelesen)", |e| matches!(e, LiveEvent::ChatState { unread: 0, .. }).then_some(())).await;

    // Reaktion, Antwort, Bearbeiten.
    let reactions = a.chat_react(&sent.id, "fire", true).await.unwrap();
    assert_eq!(reactions[0].emoji, "fire");
    expect_event(&log, "chat_reactions", |e| matches!(e, LiveEvent::ChatReactions { .. }).then_some(())).await;
    let mine = a
        .chat_send(&dm.id, &OutgoingMessage { text: Some("Antwort".into()), reply_to: Some(sent.id.clone()), ..Default::default() })
        .await
        .unwrap();
    assert_eq!(mine.reply_to.as_ref().map(|r| r.id.as_str()), Some(sent.id.as_str()));
    a.chat_edit(&mine.id, "Antwort (neu)").await.unwrap();
    expect_event(&log, "chat_message_edited", |e| matches!(e, LiveEvent::ChatMessageEdited { .. }).then_some(())).await;

    // Bild + Einladung.
    let local = b.chat_stage_bytes("bild.png", super::media::tests::png(640, 360, 255)).await.unwrap();
    let att = b.chat_upload(&UploadSource::Local { id: local.id }).await.unwrap();
    let with_image = b
        .chat_send(
            &dm.id,
            &OutgoingMessage {
                attachments: vec![att.id.clone()],
                invite: Some(super::chat::ChatInvite { address: "play.example.net".into(), name: Some("Survival".into()) }),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let got = expect_event(&log, "chat_message mit Bild", |e| match e {
        LiveEvent::ChatMessage { message, .. } if message.id == with_image.id => Some(message.clone()),
        _ => None,
    })
    .await;
    assert_eq!(got.attachments.len(), 1);
    assert_eq!(got.invite.as_ref().map(|i| i.address.as_str()), Some("play.example.net"));
    for kind in ["t", "a"] {
        let r = a.serve_chat_image(&format!("/{kind}/{}", att.id)).await;
        assert_eq!(r.status, 200, "Bild {kind}");
    }
    let status = a.chat_server_status("play.example.net").await.unwrap();
    println!("  Server-Status: online={} reason={:?}", status.online, status.reason);
    samples.insert("inviteStatus".into(), serde_json::to_value(&status).unwrap());

    // Stummschalten, als ungelesen markieren.
    a.chat_mute(&dm.id, true, None).await.unwrap();
    expect_event(&log, "chat_state (stumm)", |e| matches!(e, LiveEvent::ChatState { muted: true, .. }).then_some(())).await;
    a.chat_mute(&dm.id, false, None).await.unwrap();
    a.chat_mark_unread(&dm.id, Some(sent.seq)).await.unwrap();
    expect_event(&log, "chat_state (ungelesen)", |e| matches!(e, LiveEvent::ChatState { marked_unread: true, .. }).then_some(())).await;

    // Gruppe.
    // (Der Ersteller bekommt die neue Gruppe aus der Antwort, nicht als Ereignis.)
    let group = a.chat_create_group("Bau-Crew", &[BOB.to_owned()]).await.unwrap();
    assert_eq!(group.name.as_deref(), Some("Bau-Crew"));
    a.chat_rename_group(&group.id, "Bau-Crew 2").await.unwrap();
    expect_event(&log, "chat_conversation (umbenannt)", |e| match e {
        LiveEvent::ChatConversation { conversation } if conversation.id == group.id && conversation.name.as_deref() == Some("Bau-Crew 2") => Some(()),
        _ => None,
    })
    .await;
    b.chat_leave_group(&group.id).await.unwrap();
    expect_event(&log, "Systemnachricht member_left", |e| match e {
        LiveEvent::ChatMessage { message, .. } if message.system.as_ref().is_some_and(|s| s.event == "member_left") => Some(()),
        _ => None,
    })
    .await;
    a.chat_delete_group(&group.id).await.unwrap();

    // Melden + Moderation.
    let report = a
        .chat_report(&NewReport {
            target: ReportTarget::Message { message_id: sent.id.clone() },
            reason: ChatReportReason::Spam,
            note: Some("Test".into()),
        })
        .await
        .unwrap();
    assert_eq!(report.status, "open");
    samples.insert("myReports".into(), serde_json::to_value(a.chat_my_reports().await.unwrap()).unwrap());
    let list = a.admin_reports(&super::moderation::ReportQuery::default()).await.unwrap();
    assert!(list["reports"].as_array().unwrap().iter().any(|r| r["id"] == report.id.as_str()));
    samples.insert("adminReports".into(), list);
    let detail = a.admin_report(&report.id).await.unwrap();
    assert!(detail["report"]["evidence"]["messages"].as_array().is_some_and(|m| !m.is_empty()));
    samples.insert("adminReport".into(), detail);
    a.admin_report_note(&report.id, "Notiz").await.unwrap();
    let after = a
        .admin_report_action(
            &report.id,
            &super::moderation::ReportAction { action: "dismiss".into(), ..Default::default() },
        )
        .await
        .unwrap();
    assert_eq!(after["report"]["status"], "resolved");
    expect_event(&log, "report_update", |e| matches!(e, LiveEvent::ReportUpdate { .. }).then_some(())).await;

    let modv = a.admin_mute(BOB, Some(5), Some("Test")).await.unwrap();
    samples.insert("adminModeration".into(), modv);
    let muted = b.chat_my_moderation().await.unwrap();
    assert!(muted.mute.is_some());
    let err = b.chat_send(&dm.id, &OutgoingMessage { text: Some("still?".into()), ..Default::default() }).await.unwrap_err();
    assert!(matches!(&err, crate::Error::TrsApi { code, .. } if code == "chat_muted"), "{err:?}");
    a.admin_unmute(BOB).await.unwrap();

    let word = a
        .admin_add_word(&super::moderation::NewFilterWord { word: "doofwort".into(), mode: None, action: Some("mask".into()) })
        .await;
    let words = a.admin_word_filter().await.unwrap();
    samples.insert("wordFilter".into(), words.clone());
    if let Ok(w) = word
        && let Some(id) = w["word"]["id"].as_u64()
    {
        a.admin_delete_word(id).await.unwrap();
    }
    samples.insert("audit".into(), a.admin_audit(&super::moderation::AuditQuery::default()).await.unwrap());
    samples.insert("stats".into(), serde_json::to_value(a.trs_admin_stats().await.unwrap()).unwrap());

    // Entfreunden → DM nur noch lesbar (Ereignis an A selbst).
    a.trs_friend_remove(BOB).await.unwrap();
    expect_event(&log, "friend_removed", |e| matches!(e, LiveEvent::FriendRemoved { .. }).then_some(())).await;
    expect_event(&log, "chat_conversation (nur lesbar)", |e| match e {
        LiveEvent::ChatConversation { conversation } if conversation.id == dm.id && !conversation.can_write => Some(()),
        _ => None,
    })
    .await;

    let kinds: std::collections::BTreeSet<String> = events(&log)
        .iter()
        .map(|e| serde_json::to_value(e).unwrap()["type"].as_str().unwrap_or("?").to_owned())
        .collect();
    println!("  Ereignisse: {kinds:?}");
    samples.insert("events".into(), serde_json::to_value(events(&log)).unwrap());
    if let Ok(path) = std::env::var("TRS_LIVE_SAMPLES") {
        std::fs::write(path, serde_json::to_string_pretty(&serde_json::Value::Object(samples)).unwrap()).unwrap();
    }
    live.abort();
}
