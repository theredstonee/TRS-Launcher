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
    assert_eq!(challenge["features"], json!(["clips", "accounts"]));
    let mut proof = proto::game_proof(&key, &sid, &nc, &nl);
    if tamper {
        proof[0] ^= 1;
    }
    send(&mut w, json!({ "type": "auth", "proof": proto::hex(&proof) })).await;
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
