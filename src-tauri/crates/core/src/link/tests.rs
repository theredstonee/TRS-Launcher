use super::*;
use std::sync::atomic::{AtomicUsize, Ordering};

type R = BufReader<tokio::net::tcp::OwnedReadHalf>;
type W = tokio::net::tcp::OwnedWriteHalf;

async fn connect(port: u16) -> (R, W) {
    let stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    let (r, w) = stream.into_split();
    (BufReader::new(r), w)
}

async fn line(r: &mut R) -> serde_json::Value {
    let mut s = String::new();
    tokio::time::timeout(Duration::from_secs(10), r.read_line(&mut s)).await.unwrap().unwrap();
    serde_json::from_str(&s).unwrap_or(serde_json::Value::Null)
}

async fn send(w: &mut W, value: serde_json::Value) {
    let mut bytes = serde_json::to_vec(&value).unwrap();
    bytes.push(b'\n');
    w.write_all(&bytes).await.unwrap();
}

/// `2:<port>:<sid>:<schlüssel>` zerlegen, wie die Mod es tut.
fn parse_env(handoff: &Handoff) -> (u16, String, Vec<u8>) {
    let (name, value) = handoff.env();
    assert_eq!(name, "TRS_CLIENT_LINK");
    let parts: Vec<&str> = value.split(':').collect();
    assert_eq!(parts.len(), 4);
    assert_eq!(parts[0], "2");
    assert!(proto::is_hex(parts[2], 16) && proto::is_hex(parts[3], 64));
    (parts[1].parse().unwrap(), parts[2].to_owned(), proto::unhex(parts[3]).unwrap())
}

struct Client {
    r: R,
    w: W,
    seal_key: [u8; 32],
    first: serde_json::Value,
}

/// Handschlag wie die Mod; `tamper` verfälscht den Beweis des Spiels.
async fn v2_login(handoff: &Handoff, tamper: bool) -> std::result::Result<Client, serde_json::Value> {
    v2_login_with(handoff, tamper, None).await
}

/// Wie [`v2_login`], die Mod nennt im `auth` ihre Merkmale (z. B. `hosting.join`).
async fn v2_login_with(
    handoff: &Handoff,
    tamper: bool,
    features: Option<&[&str]>,
) -> std::result::Result<Client, serde_json::Value> {
    let (port, sid, key) = parse_env(handoff);
    let (mut r, mut w) = connect(port).await;
    let nc = proto::hex(&proto::random_bytes(16));
    send(&mut w, json!({ "type": "hello", "v": 2, "sid": sid, "nonce": nc })).await;
    let challenge = line(&mut r).await;
    if challenge["type"] != "challenge" {
        return Err(challenge);
    }
    let nl = challenge["nonce"].as_str().unwrap().to_owned();
    assert_eq!(challenge["proof"].as_str().unwrap(), proto::hex(&proto::launcher_proof(&key, &sid, &nc, &nl)), "echter Launcher");
    assert_eq!(
        challenge["features"],
        json!(["clips", "accounts", "clips.enable", "clips.preview", "clips.open", "hosting.join"])
    );
    let mut proof = proto::game_proof(&key, &sid, &nc, &nl);
    if tamper {
        proof[0] ^= 1;
    }
    let mut auth = json!({ "type": "auth", "proof": proto::hex(&proof) });
    if let Some(features) = features {
        auth["features"] = json!(features);
    }
    send(&mut w, auth).await;
    let first = line(&mut r).await;
    if first["type"] != "state" {
        return Err(first);
    }
    Ok(Client { r, w, seal_key: proto::seal_key(&key, &nc, &nl), first })
}

/// Liest bis zur Antwort mit dieser ID (Statuszeilen dazwischen überspringen).
async fn response(r: &mut R, id: u64) -> serde_json::Value {
    loop {
        let v = line(r).await;
        if v["type"] == "res" && v["id"] == id {
            return v;
        }
    }
}

#[derive(Default)]
struct FakeAccounts {
    adds: AtomicUsize,
    sessions: AtomicUsize,
}

impl AccountsHandler for FakeAccounts {
    fn list(&self) -> BoxFuture<'static, HandlerResult<Vec<LinkAccount>>> {
        Box::pin(async {
            Ok(vec![
                LinkAccount { id: "a".repeat(32), name: "Alex".into(), skin_url: None, active: true },
                LinkAccount {
                    id: "b".repeat(32),
                    name: "Steve".into(),
                    skin_url: Some("https://textures.minecraft.net/texture/abc".into()),
                    active: false,
                },
            ])
        })
    }

    fn session(&self, _instance_id: String, account: String) -> BoxFuture<'static, HandlerResult<LinkSession>> {
        self.sessions.fetch_add(1, Ordering::SeqCst);
        Box::pin(async move {
            if account == "b".repeat(32) {
                Ok(LinkSession { id: account, name: "Steve".into(), xuid: "123".into(), token: "mc-token-steve".into() })
            } else {
                Err("unknown_account")
            }
        })
    }

    fn add(&self, _instance_id: String) -> BoxFuture<'static, HandlerResult<LinkAccount>> {
        self.adds.fetch_add(1, Ordering::SeqCst);
        Box::pin(async {
            tokio::time::sleep(Duration::from_millis(300)).await;
            Ok(LinkAccount { id: "c".repeat(32), name: "Neu".into(), skin_url: None, active: false })
        })
    }
}

fn link_with_accounts() -> (TrsLink, Arc<FakeAccounts>) {
    let link = TrsLink::new(None);
    let fake = Arc::new(FakeAccounts::default());
    link.set_handler(fake.clone());
    (link, fake)
}

#[tokio::test]
async fn v2_handschlag_und_konten() {
    let (link, fake) = link_with_accounts();
    let handoff = link.open_session("survival", false).await.unwrap();
    // Gegenstelle = dieser Testprozess → als „Spiel“ eintragen.
    link.set_pid("survival", std::process::id());
    let mut c = v2_login(&handoff, false).await.expect("Anmeldung");
    assert_eq!(c.first["reason"], "disabled");

    send(&mut c.w, json!({ "type": "req", "id": 1, "op": "accounts.list" })).await;
    let res = response(&mut c.r, 1).await;
    assert_eq!(res["ok"], true);
    assert_eq!(res["accounts"][1]["name"], "Steve");
    assert_eq!(res["accounts"][1]["skinUrl"], "https://textures.minecraft.net/texture/abc");
    assert_eq!(res["accounts"][0]["active"], true);
    assert!(res["accounts"][0].get("token").is_none());

    // Sofort noch einmal: gebremst.
    send(&mut c.w, json!({ "type": "req", "id": 2, "op": "accounts.list" })).await;
    assert_eq!(response(&mut c.r, 2).await["error"], "rate_limited");

    send(&mut c.w, json!({ "type": "req", "id": 3, "op": "accounts.session", "account": "b".repeat(32) })).await;
    let res = response(&mut c.r, 3).await;
    assert_eq!(res["ok"], true, "{res}");
    assert_eq!(res["session"]["name"], "Steve");
    let sealed = res["session"]["token"].as_str().unwrap();
    assert!(!sealed.contains("mc-token"));
    assert_eq!(proto::unseal(&c.seal_key, sealed).unwrap(), b"mc-token-steve");

    // Zweite Sitzung innerhalb von 2 s: gebremst, der Launcher wird gar nicht gefragt.
    send(&mut c.w, json!({ "type": "req", "id": 4, "op": "accounts.session", "account": "b".repeat(32) })).await;
    assert_eq!(response(&mut c.r, 4).await["error"], "rate_limited");
    assert_eq!(fake.sessions.load(Ordering::SeqCst), 1);

    send(&mut c.w, json!({ "type": "req", "id": 5, "op": "accounts.session", "account": "kaputt" })).await;
    assert_eq!(response(&mut c.r, 5).await["error"], "unknown_account");

    send(&mut c.w, json!({ "type": "req", "id": 6, "op": "accounts.add" })).await;
    send(&mut c.w, json!({ "type": "req", "id": 7, "op": "accounts.add" })).await;
    assert_eq!(response(&mut c.r, 7).await["error"], "busy", "nur eine Anmeldung zugleich");
    // Nach dem Hinzufügen: Antwort + Hinweis an alle Verbindungen.
    let mut saw_changed = false;
    let res = loop {
        let v = line(&mut c.r).await;
        if v["type"] == "accountsChanged" {
            saw_changed = true;
        }
        if v["type"] == "res" && v["id"] == 6 {
            break v;
        }
    };
    assert_eq!(res["account"]["name"], "Neu");
    if !saw_changed {
        loop {
            if line(&mut c.r).await["type"] == "accountsChanged" {
                break;
            }
        }
    }
    assert_eq!(fake.adds.load(Ordering::SeqCst), 1);

    send(&mut c.w, json!({ "type": "req", "id": 8, "op": "gibt.es.nicht" })).await;
    assert_eq!(response(&mut c.r, 8).await["error"], "unknown_op");
    // Unsinnige IDs werden ignoriert, die Verbindung bleibt.
    send(&mut c.w, json!({ "type": "req", "id": -1, "op": "accounts.list" })).await;
    send(&mut c.w, json!({ "type": "req", "id": 9, "op": "gibt.es.nicht" })).await;
    assert_eq!(response(&mut c.r, 9).await["error"], "unknown_op");

    // Spielende: Sitzung weg, Verbindung zu.
    link.close_session("survival");
    let mut rest = Vec::new();
    let _ = tokio::time::timeout(Duration::from_secs(8), c.r.read_to_end(&mut rest)).await;
    assert!(v2_login(&handoff, false).await.is_err(), "nach Spielende gilt der Schlüssel nicht mehr");
}

#[tokio::test]
async fn falscher_beweis_und_unbekannte_sitzung_werden_abgewiesen() {
    let (link, _) = link_with_accounts();
    let handoff = link.open_session("survival", false).await.unwrap();
    let denied = v2_login(&handoff, true).await.err().expect("falscher Beweis");
    assert_eq!(denied["type"], "denied");

    let (port, _, _) = parse_env(&handoff);
    let (mut r, mut w) = connect(port).await;
    send(&mut w, json!({ "type": "hello", "v": 2, "sid": "ffffffffffffffff", "nonce": "0".repeat(32) })).await;
    assert_eq!(line(&mut r).await["type"], "denied");

    // Neuer Start derselben Instanz: der alte Schlüssel gilt nicht mehr.
    let fresh = link.open_session("survival", false).await.unwrap();
    assert_eq!(v2_login(&handoff, false).await.err().unwrap()["type"], "denied");
    assert!(v2_login(&fresh, false).await.is_ok());
}

#[tokio::test]
async fn ohne_gepruefte_gegenstelle_keine_tokens() {
    let (link, fake) = link_with_accounts();
    let handoff = link.open_session("survival", false).await.unwrap();
    // PID unbekannt → Liste ja, Sitzung/Hinzufügen nein.
    let mut c = v2_login(&handoff, false).await.unwrap();
    send(&mut c.w, json!({ "type": "req", "id": 1, "op": "accounts.list" })).await;
    assert_eq!(response(&mut c.r, 1).await["ok"], true);
    send(&mut c.w, json!({ "type": "req", "id": 2, "op": "accounts.session", "account": "b".repeat(32) })).await;
    assert_eq!(response(&mut c.r, 2).await["error"], "not_allowed");
    send(&mut c.w, json!({ "type": "req", "id": 3, "op": "accounts.add" })).await;
    assert_eq!(response(&mut c.r, 3).await["error"], "not_allowed");
    assert_eq!(fake.sessions.load(Ordering::SeqCst) + fake.adds.load(Ordering::SeqCst), 0);
}

#[cfg(windows)]
#[tokio::test]
async fn fremder_prozess_wird_abgewiesen() {
    let (link, _) = link_with_accounts();
    let handoff = link.open_session("survival", false).await.unwrap();
    // Eine PID, die sicher nicht zu diesem Testprozess gehört (System-Leerlaufprozess).
    link.set_pid("survival", 0);
    let denied = v2_login(&handoff, false).await.err().expect("fremde Gegenstelle");
    assert_eq!(denied["type"], "denied");
    link.set_pid("survival", std::process::id());
    assert!(v2_login(&handoff, false).await.is_ok());
}

#[tokio::test]
async fn erste_anmeldung_nur_innerhalb_der_frist() {
    let link = TrsLink::with_ttl(None, Duration::from_millis(200));
    let handoff = link.open_session("survival", false).await.unwrap();
    tokio::time::sleep(Duration::from_millis(400)).await;
    assert_eq!(v2_login(&handoff, false).await.err().unwrap()["type"], "denied");

    // Einmal angemeldet, gilt die Sitzung bis zum Spielende weiter.
    let handoff = link.open_session("survival", false).await.unwrap();
    let c = v2_login(&handoff, false).await.unwrap();
    drop(c);
    tokio::time::sleep(Duration::from_millis(400)).await;
    assert!(v2_login(&handoff, false).await.is_ok());
}

#[tokio::test]
async fn altes_protokoll_nur_fuer_clips() {
    let (link, fake) = link_with_accounts();
    let clicks = Arc::new(Mutex::new(Vec::new()));
    let sink = clicks.clone();
    link.set_command_sink(Arc::new(move |id: &str, cmd| sink.lock().unwrap().push((id.to_owned(), cmd))));
    let _handoff = link.open_session("survival", true).await.unwrap();
    link.set_pid("survival", std::process::id());
    let config = link.clips_config("survival", true);
    let token = config.token.clone().unwrap();
    assert_eq!((config.version, config.port), (1, link.port()));

    let (mut r, mut w) = connect(link.port().unwrap()).await;
    send(&mut w, json!({ "type": "hello", "v": 1, "token": "falsch" })).await;
    assert_eq!(line(&mut r).await["type"], "denied");

    let (mut r, mut w) = connect(link.port().unwrap()).await;
    send(&mut w, json!({ "type": "hello", "v": 1, "token": token })).await;
    assert_eq!(line(&mut r).await["type"], "state");
    link.set_state("survival", LinkState { available: true, buffer: true, clip_seconds: 30, ..Default::default() });
    let next = line(&mut r).await;
    assert_eq!((next["buffer"].clone(), next["clipSeconds"].clone()), (json!(true), json!(30)));

    send(&mut w, json!({ "type": "clip" })).await;
    send(&mut w, json!({ "type": "clip" })).await; // prellt → verworfen
    send(&mut w, json!({ "type": "req", "id": 1, "op": "accounts.list" })).await;
    assert_eq!(response(&mut r, 1).await["error"], "not_allowed");
    assert_eq!(clicks.lock().unwrap().as_slice(), &[("survival".to_owned(), LinkCommand::SaveClip)]);
    assert_eq!(fake.sessions.load(Ordering::SeqCst), 0);

    link.send("survival", LinkEvent::Saved { kind: "clip", seconds: 30 });
    assert_eq!(line(&mut r).await, json!({ "type": "saved", "kind": "clip", "seconds": 30 }));
    // Konten-Hinweise bekommen alte Mods nicht.
    link.notify_accounts_changed();
    link.send("survival", LinkEvent::Failed { kind: "clip", code: "busy" });
    assert_eq!(line(&mut r).await["type"], "failed");
}

#[tokio::test]
async fn zu_lange_zeilen_beenden_die_verbindung() {
    let link = TrsLink::new(None);
    let port = link.ensure_started().await.unwrap();
    let (mut r, mut w) = connect(port).await;
    let _ = w.write_all(&[b'a'; 4000]).await;
    let _ = w.write_all(b"\n").await;
    let mut rest = Vec::new();
    let n = tokio::time::timeout(Duration::from_secs(3), r.read_to_end(&mut rest)).await.unwrap().unwrap_or(0);
    assert_eq!(n, 0, "keine Antwort auf Müll");
}

#[tokio::test]
async fn sitzungen_ueberleben_einen_launcher_neustart() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("link-sessions.json");
    let first = TrsLink::new(Some(file.clone()));
    let handoff = first.open_session("survival", false).await.unwrap();
    let me = std::process::id();
    first.set_pid("survival", me);
    let _other = first.open_session("creative", false).await.unwrap();
    first.set_pid("creative", 5555);
    // Sicherung läuft im Hintergrund.
    let (_, _, key) = parse_env(&handoff);
    let mut raw = String::new();
    for _ in 0..50 {
        raw = std::fs::read_to_string(&file).unwrap_or_default();
        if raw.contains(&me.to_string()) && raw.contains("5555") {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(!raw.contains(handoff.secret()) && !raw.is_empty(), "Schlüssel nie im Klartext");

    // Neuer Launcher: nur „survival“ läuft noch (gleiche PID).
    let second = TrsLink::new(Some(file.clone()));
    let restored = second.restore(&[("survival".into(), me), ("creative".into(), 1)]).await.unwrap();
    assert_eq!(restored, vec!["survival".to_owned()]);
    assert!(second.has_session("survival") && !second.has_session("creative"));
    assert_ne!(second.port(), Some(handoff.port));
    // Die Mod benutzt ihren alten Schlüssel mit dem neuen Port (aus clips.json).
    let env = format!("2:{}:{}:{}", second.port().unwrap(), parse_env(&handoff).1, proto::hex(&key));
    let moved = Handoff { port: second.port().unwrap(), env_value: env, secret_hex: proto::hex(&key) };
    assert!(v2_login(&moved, false).await.is_ok());
    assert_eq!(second.clips_config("survival", true), LinkConfig { version: 2, enabled: true, port: second.port(), token: None, clips_dir: None });
}

/// Zählt `clips.enable` und liefert 30 s bzw. einen festen Fehler.
fn clips_enabler(calls: Arc<AtomicUsize>, result: HandlerResult<u32>) -> ClipsEnabler {
    Arc::new(move |instance_id: String| {
        calls.fetch_add(1, Ordering::SeqCst);
        assert_eq!(instance_id, "survival");
        Box::pin(async move { result })
    })
}

#[tokio::test]
async fn clips_einschalten_aus_dem_spiel() {
    // Ohne Kontenzugriff (kein AccountsHandler) – Clips gehen trotzdem.
    let link = TrsLink::new(None);
    let calls = Arc::new(AtomicUsize::new(0));
    link.set_clips_enabler(clips_enabler(calls.clone(), Ok(30)));
    let handoff = link.open_session("survival", false).await.unwrap();
    link.set_pid("survival", std::process::id());
    link.set_state("survival", LinkState::disabled().capturing(true, false));
    let mut c = v2_login(&handoff, false).await.expect("Anmeldung");
    // Die Mod erfährt, was beim Einschalten aufgenommen würde.
    assert_eq!(
        (c.first["reason"].clone(), c.first["audio"].clone(), c.first["mic"].clone()),
        (json!("disabled"), json!(true), json!(false))
    );
    assert!(c.first.get("progress").is_none(), "kein Fortschritt ohne Download");

    send(&mut c.w, json!({ "type": "req", "id": 1, "op": "clips.enable" })).await;
    let res = response(&mut c.r, 1).await;
    assert_eq!((res["ok"].clone(), res["clipSeconds"].clone()), (json!(true), json!(30)), "{res}");
    // Sofort noch einmal: gebremst, der Launcher wird nicht gefragt.
    send(&mut c.w, json!({ "type": "req", "id": 2, "op": "clips.enable" })).await;
    assert_eq!(response(&mut c.r, 2).await["error"], "rate_limited");
    assert_eq!(calls.load(Ordering::SeqCst), 1);

    // Status mit FFmpeg-Fortschritt kommt als Statuszeile an.
    link.set_state("survival", LinkState { reason: Some("ffmpeg"), progress: Some(42), ..Default::default() }.capturing(true, false));
    let state = loop {
        let v = line(&mut c.r).await;
        if v["type"] == "state" && v["reason"] == "ffmpeg" {
            break v;
        }
    };
    assert_eq!(state["progress"], 42);
}

#[tokio::test]
async fn clips_einschalten_nur_vom_spielprozess_und_nie_mit_altem_token() {
    let link = TrsLink::new(None);
    let calls = Arc::new(AtomicUsize::new(0));
    link.set_clips_enabler(clips_enabler(calls.clone(), Ok(30)));
    // PID unbekannt → Gegenstelle nicht geprüft → abgelehnt.
    let handoff = link.open_session("survival", false).await.unwrap();
    let mut c = v2_login(&handoff, false).await.unwrap();
    send(&mut c.w, json!({ "type": "req", "id": 1, "op": "clips.enable" })).await;
    assert_eq!(response(&mut c.r, 1).await["error"], "not_allowed");

    // Alte Mod (Protokoll v1, Token aus clips.json): keine Anfragen.
    let _handoff = link.open_session("survival", true).await.unwrap();
    link.set_pid("survival", std::process::id());
    let token = link.clips_config("survival", true).token.unwrap();
    let (mut r, mut w) = connect(link.port().unwrap()).await;
    send(&mut w, json!({ "type": "hello", "v": 1, "token": token })).await;
    assert_eq!(line(&mut r).await["type"], "state");
    send(&mut w, json!({ "type": "req", "id": 2, "op": "clips.enable" })).await;
    assert_eq!(response(&mut r, 2).await["error"], "not_allowed");
    assert_eq!(calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn clips_einschalten_meldet_fehlercodes() {
    let link = TrsLink::new(None);
    let handoff = link.open_session("survival", false).await.unwrap();
    link.set_pid("survival", std::process::id());
    let mut c = v2_login(&handoff, false).await.unwrap();
    // Noch nicht angebunden → neutraler Fehler.
    send(&mut c.w, json!({ "type": "req", "id": 1, "op": "clips.enable" })).await;
    assert_eq!(response(&mut c.r, 1).await["error"], "error");

    let calls = Arc::new(AtomicUsize::new(0));
    link.set_clips_enabler(clips_enabler(calls.clone(), Err("unsupported")));
    send(&mut c.w, json!({ "type": "req", "id": 2, "op": "clips.enable" })).await;
    assert_eq!(response(&mut c.r, 2).await["error"], "unsupported");
    assert_eq!(calls.load(Ordering::SeqCst), 1);
}

#[test]
fn clips_einschalten_hoechstens_fuenfmal_in_zehn_minuten() {
    let mut limits = Limits::default();
    let start = Instant::now();
    for i in 0..5 {
        assert!(limits.allow_enable(start + Duration::from_secs(i * 4)), "Versuch {i}");
    }
    assert!(!limits.allow_enable(start + Duration::from_secs(30)), "sechster Versuch gebremst");
    assert!(limits.allow_enable(start + Duration::from_secs(11 * 60)), "nach zehn Minuten wieder frei");
}

/// Attrappe für Clip-Vorschau/-Öffnen: kennt nur „a.mp4“.
#[derive(Default)]
struct FakeClips {
    previews: AtomicUsize,
    opened: Mutex<Vec<(String, String)>>,
}

impl ClipsHandler for FakeClips {
    fn preview(&self, instance_id: String, clip: String) -> BoxFuture<'static, HandlerResult<LinkPreview>> {
        self.previews.fetch_add(1, Ordering::SeqCst);
        Box::pin(async move {
            tokio::time::sleep(Duration::from_millis(150)).await;
            if clip != "a.mp4" {
                return Err("unknown_clip");
            }
            Ok(LinkPreview {
                path: format!("/cache/{instance_id}.png"),
                frames: 30,
                cols: 10,
                rows: 3,
                frame_width: 160,
                frame_height: 90,
                interval_ms: 1000,
                duration_ms: 30_000,
            })
        })
    }

    fn open(&self, instance_id: String, clip: String) -> BoxFuture<'static, HandlerResult<()>> {
        let known = clip == "a.mp4";
        if known {
            self.opened.lock().unwrap().push((instance_id, clip));
        }
        Box::pin(async move { if known { Ok(()) } else { Err("unknown_clip") } })
    }
}

#[tokio::test]
async fn clip_vorschau_und_oeffnen_aus_dem_spiel() {
    let link = TrsLink::new(None);
    let clips = Arc::new(FakeClips::default());
    link.set_clips_handler(clips.clone());
    let handoff = link.open_session("survival", false).await.unwrap();
    link.set_pid("survival", std::process::id());
    let mut c = v2_login(&handoff, false).await.expect("Anmeldung");

    send(&mut c.w, json!({ "type": "req", "id": 1, "op": "clips.preview", "clip": "a.mp4" })).await;
    // Solange die erste läuft: „busy“ (kein zweiter FFmpeg-Lauf gleichzeitig).
    send(&mut c.w, json!({ "type": "req", "id": 2, "op": "clips.preview", "clip": "a.mp4" })).await;
    assert_eq!(response(&mut c.r, 2).await["error"], "busy");
    let res = response(&mut c.r, 1).await;
    assert_eq!(res["ok"], true, "{res}");
    assert_eq!(res["preview"]["path"], "/cache/survival.png", "Vorschau der eigenen Instanz");
    assert_eq!(res["preview"]["frameWidth"], 160);
    assert_eq!(res["preview"]["intervalMs"], 1000);
    assert_eq!(clips.previews.load(Ordering::SeqCst), 1);

    // Pfade statt Namen werden gar nicht erst weitergereicht.
    tokio::time::sleep(Duration::from_millis(300)).await;
    for (id, bad) in [(3, "../other/a.mp4"), (4, "C:\\x\\a.mp4"), (5, "a.txt"), (6, "")] {
        send(&mut c.w, json!({ "type": "req", "id": id, "op": "clips.preview", "clip": bad })).await;
        assert_eq!(response(&mut c.r, id).await["error"], "unknown_clip", "{bad}");
    }
    send(&mut c.w, json!({ "type": "req", "id": 7, "op": "clips.preview" })).await;
    assert_eq!(response(&mut c.r, 7).await["error"], "unknown_clip");
    assert_eq!(clips.previews.load(Ordering::SeqCst), 1, "ungültige Namen erreichen den Launcher nie");
    // Unbekannter Clip (gültiger Name): der Launcher lehnt ab.
    send(&mut c.w, json!({ "type": "req", "id": 8, "op": "clips.preview", "clip": "fremd.mp4" })).await;
    assert_eq!(response(&mut c.r, 8).await["error"], "unknown_clip");

    send(&mut c.w, json!({ "type": "req", "id": 9, "op": "clips.open", "clip": "a.mp4" })).await;
    assert_eq!(response(&mut c.r, 9).await["ok"], true);
    assert_eq!(*clips.opened.lock().unwrap(), vec![("survival".to_owned(), "a.mp4".to_owned())]);
    // Sofort noch einmal: gebremst (holt sonst dauernd das Fenster nach vorn).
    send(&mut c.w, json!({ "type": "req", "id": 10, "op": "clips.open", "clip": "a.mp4" })).await;
    assert_eq!(response(&mut c.r, 10).await["error"], "rate_limited");
    assert_eq!(clips.opened.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn clip_anfragen_nie_mit_altem_token() {
    let link = TrsLink::new(None);
    let clips = Arc::new(FakeClips::default());
    link.set_clips_handler(clips.clone());
    link.open_session("survival", true).await.unwrap();
    let token = link.clips_config("survival", true).token.unwrap();
    let (mut r, mut w) = connect(link.port().unwrap()).await;
    send(&mut w, json!({ "type": "hello", "v": 1, "token": token })).await;
    assert_eq!(line(&mut r).await["type"], "state");
    send(&mut w, json!({ "type": "req", "id": 1, "op": "clips.open", "clip": "a.mp4" })).await;
    assert_eq!(response(&mut r, 1).await["error"], "not_allowed");
    assert!(clips.opened.lock().unwrap().is_empty());
}

#[test]
fn clip_vorschau_grenzen() {
    let mut limits = Limits::default();
    let t0 = Instant::now();
    assert_eq!(limits.begin_preview(t0), Ok(()));
    assert_eq!(limits.begin_preview(t0 + Duration::from_secs(1)), Err("busy"));
    limits.preview_running = false;
    assert_eq!(limits.begin_preview(t0 + Duration::from_millis(100)), Err("rate_limited"));
    let mut t = t0;
    for _ in 1..120 {
        t += Duration::from_millis(300);
        assert_eq!(limits.begin_preview(t), Ok(()));
        limits.preview_running = false;
    }
    assert_eq!(limits.begin_preview(t + Duration::from_secs(1)), Err("rate_limited"), "120 je 10 Minuten");
    assert_eq!(limits.begin_preview(t0 + Duration::from_secs(11 * 60)), Ok(()));
    assert!(limits.allow_open(t0));
    assert!(!limits.allow_open(t0 + Duration::from_millis(500)));
    assert!(limits.allow_open(t0 + Duration::from_secs(2)));
}

// --- Welt-Beitritt (hosting.join) ----------------------------------------------------------

fn world() -> HostedWorld {
    HostedWorld {
        room_id: "h0123456789abcdef0123".into(),
        code: Some("K7QM2X".into()),
        name: "Insel".into(),
        host: Some(crate::trs_api::types::UserRef { uuid: "75c1a6f3112240abbdb57b9d21c64232".into(), name: "Theredstonee".into() }),
        mc_version: "1.21.11".into(),
        loader: "fabric".into(),
    }
}

/// Liest bis zur ersten Zeile dieses Typs (Statuszeilen überspringen).
async fn line_of(r: &mut R, kind: &str) -> serde_json::Value {
    loop {
        let v = line(r).await;
        if v["type"] == kind {
            return v;
        }
    }
}

#[tokio::test]
async fn welt_beitritt_kommt_nach_der_anmeldung_genau_einmal() {
    let link = TrsLink::new(None);
    let handoff = link.open_session("survival", false).await.unwrap();
    assert!(link.queue_hosting_join("survival", world()));
    assert_eq!(link.hosting_delivery("survival"), HostingDelivery::Pending { room_id: world().room_id });

    let mut c = v2_login_with(&handoff, false, Some(&["hosting.join"])).await.expect("Anmeldung");
    let push = line_of(&mut c.r, "hostingJoin").await;
    assert_eq!(
        push["join"],
        json!({ "roomId": "h0123456789abcdef0123", "code": "K7QM2X", "name": "Insel",
                "host": { "uuid": "75c1a6f3112240abbdb57b9d21c64232", "name": "Theredstonee" },
                "mcVersion": "1.21.11", "loader": "fabric" })
    );
    let text = push.to_string();
    assert!(!text.contains("token") && !text.contains("relay"), "keine Zugangsdaten ans Spiel");
    assert_eq!(link.hosting_delivery("survival"), HostingDelivery::Delivered { room_id: world().room_id });

    // Abholen danach: nichts mehr da (genau einmal).
    send(&mut c.w, json!({ "type": "req", "id": 1, "op": "hosting.join" })).await;
    let res = response(&mut c.r, 1).await;
    assert_eq!(res["ok"], true);
    assert!(res["join"].is_null());
    // Dauerfeuer wird gebremst.
    send(&mut c.w, json!({ "type": "req", "id": 2, "op": "hosting.join" })).await;
    assert_eq!(response(&mut c.r, 2).await["error"], "rate_limited");

    // Läuft das Spiel schon, kommt ein neuer Beitritt sofort als Push.
    let mut other = world();
    other.room_id = "h00000000000000000009".into();
    assert!(link.queue_hosting_join("survival", other));
    let push = line_of(&mut c.r, "hostingJoin").await;
    assert_eq!(push["join"]["roomId"], "h00000000000000000009");
    assert_eq!(link.hosting_delivery("survival"), HostingDelivery::Delivered { room_id: "h00000000000000000009".into() });
}

#[tokio::test]
async fn welt_beitritt_abholen_statt_push() {
    let link = TrsLink::new(None);
    let handoff = link.open_session("survival", false).await.unwrap();
    let mut c = v2_login_with(&handoff, false, Some(&["hosting.join"])).await.expect("Anmeldung");
    // Direkt vormerken, ohne Push (z. B. zwischen zwei Verbindungen).
    {
        let mut hub = link.shared.hub();
        let session = hub.get_mut("survival").unwrap();
        session.hosting = Some((world(), Instant::now()));
        session.hosting_state = HostingDelivery::Pending { room_id: world().room_id };
    }
    send(&mut c.w, json!({ "type": "req", "id": 1, "op": "hosting.join" })).await;
    let res = response(&mut c.r, 1).await;
    assert_eq!(res["join"]["code"], "K7QM2X");
    assert_eq!(link.hosting_delivery("survival"), HostingDelivery::Delivered { room_id: world().room_id });
}

#[tokio::test]
async fn alte_mod_ohne_merkmal_bekommt_keinen_welt_beitritt() {
    let link = TrsLink::new(None);
    let handoff = link.open_session("survival", false).await.unwrap();
    link.queue_hosting_join("survival", world());
    let mut c = v2_login(&handoff, false).await.expect("Anmeldung");
    // Kein Push – stattdessen weiß die Oberfläche: TRS Client zu alt.
    let mut rest = Vec::new();
    for _ in 0..2 {
        rest.push(line(&mut c.r).await);
    }
    assert!(rest.iter().all(|v| v["type"] != "hostingJoin"), "{rest:?}");
    assert_eq!(link.hosting_delivery("survival"), HostingDelivery::Unsupported { room_id: world().room_id });

    // Spiel läuft schon ohne das Merkmal: gleich „zu alt“, nichts bleibt liegen.
    assert!(link.queue_hosting_join("survival", world()));
    assert_eq!(link.hosting_delivery("survival"), HostingDelivery::Unsupported { room_id: world().room_id });
    assert!(link.shared.hub().get("survival").unwrap().hosting.is_none());
}

#[tokio::test]
async fn welt_beitritt_nie_fuer_fremde_oder_alte_sitzungen() {
    let link = TrsLink::new(None);
    assert!(!link.queue_hosting_join("gibt-es-nicht", world()));
    link.open_session("legacy", true).await.unwrap();
    assert!(!link.queue_hosting_join("legacy", world()), "Mods ≤ 0.5.0 (v1) können es nicht");
    assert_eq!(link.hosting_delivery("legacy"), HostingDelivery::None);

    // Neue Sitzung derselben Instanz (Neustart des Spiels) verwirft den alten Beitritt.
    link.open_session("survival", false).await.unwrap();
    link.queue_hosting_join("survival", world());
    link.open_session("survival", false).await.unwrap();
    assert_eq!(link.hosting_delivery("survival"), HostingDelivery::None);

    // Abgelaufen: nach 10 Minuten ohne Spiel.
    link.queue_hosting_join("survival", world());
    link.shared.hub().get_mut("survival").unwrap().hosting.as_mut().unwrap().1 =
        Instant::now().checked_sub(Duration::from_secs(11 * 60)).unwrap();
    assert_eq!(link.hosting_delivery("survival"), HostingDelivery::Expired { room_id: world().room_id });
    // Spielende: Sitzung weg.
    link.close_session("survival");
    assert_eq!(link.hosting_delivery("survival"), HostingDelivery::None);
    assert_eq!(
        serde_json::to_value(HostingDelivery::Pending { room_id: "h1".into() }).unwrap(),
        json!({ "state": "pending", "roomId": "h1" })
    );
}
