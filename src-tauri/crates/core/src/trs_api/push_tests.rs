//! Push-Geräte gegen eine nachgebaute API (§33): Abgleich, Abmelden, Abholen.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use serde_json::json;

use super::*;
use crate::trs_api::testkit::{MockServer, Request, Response};
use crate::trs_api::tests::{ACC, signed_in};

const DEV: &str = "d0123456789abcdef0123";
const DEV2: &str = "dabcdefabcdefabcdef01";
const P256: &str = "BOrX2Ttd2mMNdJ6RtnfgxwhApt6dq6ROVlInnpOaI_Ta-eVa7spDw4gG5ZMFEMQh_DN4_Mjn5a2WmQRCbDbdCZo";
const AUTH: &str = "8iHkXGHaY7-ZwbxPh0aXYw";

fn up(endpoint: &str) -> PushEnv {
    PushEnv {
        platform: Platform::Android,
        transport: Some(Transport::UnifiedPush { endpoint: endpoint.into(), p256dh: P256.into(), auth: AUTH.into() }),
        device_name: "Pixel 8".into(),
        app_version: "0.16.0".into(),
    }
}

fn device_json(id: &str, kind: &str) -> serde_json::Value {
    json!({ "id": id, "platform": "android", "kind": kind, "endpointHost": "ntfy.sh", "deviceName": "Pixel 8",
        "appVersion": "0.16.0", "locale": "en", "categories": { "chat": true }, "preview": false,
        "pushWhilePlaying": false, "current": true, "createdAt": "2026-10-03T10:00:00.000Z",
        "updatedAt": "2026-10-03T10:00:00.000Z", "lastSeenAt": null, "lastSuccessAt": null, "failing": false })
}

/// Attrappe: zählt angelegte Geräte; `gone` lässt PATCH/GET pending mit `device_not_found` scheitern.
struct World {
    created: AtomicUsize,
    gone: std::sync::atomic::AtomicBool,
    pending: Mutex<Vec<serde_json::Value>>,
}

fn api(world: Arc<World>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    move |req: &Request| {
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        let path = req.path.split('?').next().unwrap_or_default();
        match (req.method.as_str(), path) {
            ("GET", "/v1/push/config") => Response::json(
                200,
                json!({ "unifiedPush": true, "vapidPublicKey": P256, "categories": CATEGORIES,
                    "defaults": { "chat": true, "friends": true, "friend_online": false, "invites": true, "hosting": true,
                        "packs": true, "team": true, "achievements": false }, "maxDevices": 10 }),
            ),
            ("POST", "/v1/push/devices") => {
                let n = world.created.fetch_add(1, Ordering::SeqCst);
                let id = if n == 0 { DEV } else { DEV2 };
                world.gone.store(false, Ordering::SeqCst);
                Response::json(201, device_json(id, req.json()["kind"].as_str().unwrap_or("poll")))
            }
            ("GET", "/v1/push/devices") => Response::json(
                200,
                json!({ "devices": [device_json(DEV, "unifiedpush"), { "id": "../../x", "kind": "poll" },
                    { "id": DEV2, "platform": "ios", "kind": "poll", "deviceName": "iPhone\u{0007}", "endpointHost": "evil host/../" }] }),
            ),
            ("PATCH", p) | ("DELETE", p) if p.starts_with("/v1/push/devices/") => {
                if world.gone.load(Ordering::SeqCst) {
                    Response::error(404, "device_not_found")
                } else if req.method == "DELETE" {
                    Response::empty(204)
                } else {
                    Response::json(200, device_json(&p["/v1/push/devices/".len()..], "unifiedpush"))
                }
            }
            ("GET", "/v1/push/pending") => {
                if world.gone.load(Ordering::SeqCst) {
                    return Response::error(404, "device_not_found");
                }
                let since: u64 = req.path.split("since=").nth(1).and_then(|s| s.split('&').next()).and_then(|s| s.parse().ok()).unwrap_or(0);
                let all = world.pending.lock().unwrap().clone();
                let newer: Vec<_> = all.into_iter().filter(|e| e["n"].as_u64().unwrap_or(0) > since).collect();
                let page: Vec<_> = newer.iter().take(2).cloned().collect();
                let cursor = page.last().and_then(|e| e["n"].as_u64()).unwrap_or(since);
                Response::json(200, json!({ "notifications": page, "cursor": cursor.to_string(), "more": newer.len() > 2 }))
            }
            ("POST", "/v1/auth/logout") => Response::empty(204),
            ("GET", "/v1/events/me") => crate::trs_api::chat_tests::sse("id: e1.1\nevent: hello\ndata: {\"type\":\"hello\",\"resumed\":false}\n\n"),
            _ => Response::error(404, "not_found"),
        }
    }
}

fn world() -> Arc<World> {
    Arc::new(World { created: AtomicUsize::new(0), gone: false.into(), pending: Mutex::new(Vec::new()) })
}

fn payload(n: u64, id: &str) -> serde_json::Value {
    json!({ "n": n, "v": 1, "id": id, "type": "friend_request", "category": "friends", "title": "Alice\u{202E}",
        "body": "möchte\n deine   Freundin sein", "target": "/friends/requests", "collapse": "friend:alice", "at": "2026-10-03T12:00:00.000Z" })
}

#[test]
fn validators_follow_the_server_rules() {
    assert!(device_id(DEV) && !device_id("d0123") && !device_id("D0123456789abcdef0123") && !device_id("x0123456789abcdef0123"));
    assert!(vapid_key(P256) && !vapid_key("abc"));
    assert!(locale("de") && locale("pt-BR") && locale("es-419") && locale("zh_Hant_TW") && !locale("deutsch") && !locale("de-") && !locale(""));
    assert!(app_version("0.16.0+1") && !app_version("1 0") && !app_version(""));
    assert!(endpoint("https://ntfy.sh/upAbC?up=1"));
    assert!(!endpoint("http://ntfy.sh/up") && !endpoint("https://u:p@ntfy.sh/up") && !endpoint("https://ntfy.sh/up#x") && !endpoint("nope"));
    assert!(push_target("/chat/c0123456789abcdef0123") && push_target("/friends") && push_target("/worlds/r1/requests"));
    assert!(!push_target("friends") && !push_target("/Chat") && !push_target("/a/../b") && !push_target("/a/b/c/d/e") && !push_target("//x"));
}

#[test]
fn payloads_are_checked_and_cleaned() {
    let p: PushPayload = serde_json::from_value(payload(1, "mfz2k1a3b4c.1842")).unwrap();
    let p = p.checked().unwrap();
    assert_eq!(p.title, "Alice");
    assert_eq!(p.body, "möchte deine Freundin sein");
    assert_eq!(p.collapse.as_deref(), Some("friend:alice"));
    let bad = |patch: serde_json::Value| {
        let mut v = payload(1, "mfz2k1a3b4c.1842");
        for (k, val) in patch.as_object().unwrap() {
            v[k] = val.clone();
        }
        serde_json::from_value::<PushPayload>(v).unwrap().checked()
    };
    assert!(bad(json!({ "v": 2 })).is_none());
    assert!(bad(json!({ "target": "javascript:alert(1)" })).is_none());
    assert!(bad(json!({ "id": "a b" })).is_none());
    assert_eq!(bad(json!({ "collapse": "x y" })).unwrap().collapse, None);
    assert_eq!(bad(json!({ "title": "x".repeat(300) })).unwrap().title.len(), 80);
}

#[test]
fn config_is_cleaned() {
    let c = PushConfig::from(ApiConfig {
        unified_push: true,
        vapid_public_key: Some("kaputt".into()),
        categories: vec!["chat".into(), "Böse".into()],
        defaults: BTreeMap::new(),
        max_devices: Some(1000),
    });
    assert!(!c.unified_push, "ohne gültigen Schlüssel kein UnifiedPush");
    assert_eq!(c.categories, vec!["chat".to_owned()]);
    assert_eq!(c.defaults.get("chat"), Some(&true));
    assert_eq!(c.max_devices, 100);
}

fn sent(kind: Kind) -> Sent {
    Sent {
        platform: Platform::Android,
        kind,
        endpoint: None,
        keys: None,
        device_name: "Pixel".into(),
        app_version: "1.0.0".into(),
        locale: "de".into(),
        categories: PushSettings::default().effective(None),
        preview: false,
        push_while_playing: false,
    }
}

#[test]
fn plan_changes_only_what_differs() {
    let settings = PushSettings::default();
    let env = up("https://ntfy.sh/upA?up=1");
    let want = Desired::new(&env, env.transport.clone().unwrap(), &settings, None, "de");
    let record = DeviceRecord { id: DEV.into(), session: "s1".into(), sent: want.sent.clone(), cursor: 0, seen: Vec::new() };

    assert_eq!(plan(None, None, "s1"), Plan::Keep);
    assert_eq!(plan(None, Some(&want), "s1"), Plan::Register);
    assert_eq!(plan(Some(&record), Some(&want), "s1"), Plan::Keep);
    assert_eq!(plan(Some(&record), None, "s1"), Plan::Remove);
    // Neue Sitzung: das alte Gerät ist beim Server weg.
    assert_eq!(plan(Some(&record), Some(&want), "s2"), Plan::Register);
    // Anderer Weg (Verteiler deinstalliert → Abholen): ersetzen.
    let poll = Desired::new(&env, Transport::Poll, &settings, None, "de");
    assert_eq!(plan(Some(&record), Some(&poll), "s1"), Plan::Replace);

    // Nur geänderte Felder; Adresse und Schlüssel immer zusammen.
    let mut changed = PushSettings { preview: true, ..PushSettings::default() };
    changed.categories.insert("friend_online".into(), true);
    let env2 = up("https://ntfy.sh/upB?up=1");
    let want2 = Desired::new(&env2, env2.transport.clone().unwrap(), &changed, None, "pt-BR");
    let Plan::Update(patch) = plan(Some(&record), Some(&want2), "s1") else { panic!("Update erwartet") };
    assert_eq!(
        patch,
        json!({ "locale": "pt-BR", "categories": { "friend_online": true }, "preview": true,
            "endpoint": "https://ntfy.sh/upB?up=1", "keys": { "p256dh": P256, "auth": AUTH } })
    );

    // Ungültige Angaben der App werden ersetzt statt abgelehnt.
    let odd = PushEnv { device_name: " \u{0007} ".into(), app_version: "kaputt version".into(), ..env };
    let w = Desired::new(&odd, Transport::Poll, &settings, None, "klingonisch");
    assert_eq!((w.sent.device_name.as_str(), w.sent.app_version.as_str(), w.sent.locale.as_str()), ("Android", "0.0.0", "en"));
    assert_eq!(sent(Kind::Poll).kind, Kind::Poll);
}

#[tokio::test]
async fn unifiedpush_device_is_registered_updated_and_removed() {
    let w = world();
    let server = MockServer::start(api(Arc::clone(&w))).await;
    let (_dir, launcher) = signed_in(&server).await;
    let env = up("https://ntfy.sh/upAbC?up=1");

    let r = launcher.push_sync(&env).await.unwrap();
    assert_eq!(r, PushSync { device_id: Some(DEV.into()), kind: Some(Kind::Unifiedpush) });
    let post = server.hits("POST", "/v1/push/devices");
    assert_eq!(post.len(), 1);
    let body = post[0].json();
    assert_eq!(body["platform"], "android");
    assert_eq!(body["kind"], "unifiedpush");
    assert_eq!(body["endpoint"], "https://ntfy.sh/upAbC?up=1");
    assert_eq!(body["keys"], json!({ "p256dh": P256, "auth": AUTH }));
    assert_eq!(body["locale"], "en");
    assert_eq!(body["categories"]["achievements"], false, "Standard des Servers");
    assert_eq!(body["preview"], false);
    // Die Adresse steht nie im Klartext in der Datei.
    let file = std::fs::read_to_string(launcher.paths().root().join("trs-push.json")).unwrap();
    assert!(!file.contains("upAbC") && file.contains(DEV));
    assert_eq!(launcher.trs.push_live_device(ACC).await.as_deref(), Some(DEV));

    // Nichts geändert → keine Anfrage.
    launcher.push_sync(&env).await.unwrap();
    assert_eq!(server.hits("POST", "/v1/push/devices").len(), 1);
    assert!(server.hits("PATCH", &format!("/v1/push/devices/{DEV}")).is_empty());

    // Schalter geändert → nur die Änderung.
    let mut s = launcher.push_settings().await;
    s.categories.insert("chat".into(), false);
    s.categories.insert("unbekannt".into(), true);
    let s = launcher.push_set_settings(s).await;
    assert!(!s.categories.contains_key("unbekannt"));
    launcher.push_sync(&env).await.unwrap();
    let patch = server.hits("PATCH", &format!("/v1/push/devices/{DEV}"));
    assert_eq!(patch.len(), 1);
    assert_eq!(patch[0].json(), json!({ "categories": { "chat": false } }));

    // Gerät beim Server verschwunden → neu anmelden.
    w.gone.store(true, Ordering::SeqCst);
    let mut s = launcher.push_settings().await;
    s.preview = true;
    launcher.push_set_settings(s).await;
    let r = launcher.push_sync(&env).await.unwrap();
    assert_eq!(r.device_id.as_deref(), Some(DEV2));
    assert_eq!(server.hits("POST", "/v1/push/devices").len(), 2);

    // Wartet auf eine neue Adresse vom Verteiler → Gerät bleiben lassen.
    let waiting = PushEnv { transport: None, ..env.clone() };
    assert_eq!(launcher.push_sync(&waiting).await.unwrap().device_id.as_deref(), Some(DEV2));
    assert!(server.hits("DELETE", &format!("/v1/push/devices/{DEV2}")).is_empty());

    // Ausgeschaltet → abmelden.
    let s = PushSettings { enabled: false, ..launcher.push_settings().await };
    launcher.push_set_settings(s).await;
    assert_eq!(launcher.push_sync(&env).await.unwrap(), PushSync { device_id: None, kind: None });
    assert_eq!(server.hits("DELETE", &format!("/v1/push/devices/{DEV2}")).len(), 1);
    assert_eq!(launcher.trs.push_live_device(ACC).await, None);
}

#[tokio::test]
async fn logout_and_new_sessions_drop_the_device() {
    let w = world();
    let server = MockServer::start(api(Arc::clone(&w))).await;
    let (_dir, launcher) = signed_in(&server).await;
    let env = up("https://ntfy.sh/upAbC?up=1");
    launcher.push_sync(&env).await.unwrap();

    // Neue Sitzung (Token erneuert): das Gerät gehörte zur alten → neu anmelden, keine PATCH.
    let next = format!("trs_{}", "z".repeat(43));
    launcher.trs.store.put_token(ACC, &next, chrono::Utc::now() + chrono::Duration::days(1)).await.unwrap();
    assert_eq!(launcher.trs.push_live_device(ACC).await, None);
    launcher.push_sync(&env).await.unwrap();
    assert_eq!(server.hits("POST", "/v1/push/devices").len(), 2);

    // Abmelden: Gerät löschen (mit dem Token der Sitzung), dann `/v1/auth/logout`.
    launcher.trs_forget_account(ACC).await;
    let delete = server.hits("DELETE", &format!("/v1/push/devices/{DEV2}"));
    assert_eq!(delete.len(), 1);
    assert_eq!(delete[0].bearer(), Some(next.as_str()));
    let order: Vec<String> = server.requests().into_iter().map(|r| r.path).collect();
    let del = order.iter().position(|p| p.starts_with("/v1/push/devices/")).unwrap();
    let out = order.iter().rposition(|p| p == "/v1/auth/logout").unwrap();
    assert!(del < out);
    assert!(launcher.trs.push.record(ACC).await.is_none());
}

#[tokio::test]
async fn poll_device_fetches_pages_once_and_survives_removal() {
    let w = world();
    let server = MockServer::start(api(Arc::clone(&w))).await;
    let (dir, launcher) = signed_in(&server).await;
    let env = PushEnv { platform: Platform::Ios, transport: Some(Transport::Poll), device_name: "iPhone".into(), app_version: "0.16.0".into() };
    let r = launcher.push_sync(&env).await.unwrap();
    assert_eq!(r.kind, Some(Kind::Poll));
    let body = server.hits("POST", "/v1/push/devices")[0].json();
    assert_eq!((body["platform"].as_str(), body["kind"].as_str()), (Some("ios"), Some("poll")));
    assert!(body.get("endpoint").is_none() && body.get("keys").is_none());
    assert!(launcher.push_polling().await);

    *w.pending.lock().unwrap() = vec![
        payload(1, "e1.1"),
        json!({ "n": 2, "v": 1, "id": "e1.2", "type": "x", "category": "chat", "title": "t", "body": "b", "target": "nope" }),
        payload(3, "e1.3"),
        payload(4, "e1.1"),
        payload(5, "e1.5"),
    ];
    let got = launcher.push_poll().await.unwrap();
    let ids: Vec<&str> = got.iter().map(|p| p.id.as_str()).collect();
    assert_eq!(ids, ["e1.1", "e1.3", "e1.5"], "ungültige und doppelte fallen weg, alle Seiten");
    let since: Vec<String> = server.hits("GET", "/v1/push/pending").iter().map(|r| r.path.clone()).collect();
    assert!(since[0].contains(&format!("device={DEV}&since=0&limit=50")), "{since:?}");
    assert!(since.last().unwrap().contains("since=4"), "{since:?}");

    // Nichts Neues.
    assert!(launcher.push_poll().await.unwrap().is_empty());

    // Ohne laufende App (Hintergrundabruf) mit demselben Datenordner.
    w.pending.lock().unwrap().push(payload(6, "e1.6"));
    // `poll_detached` nimmt die echte API-Adresse – hier nur prüfen, dass ohne Einwilligung nichts passiert.
    let empty = tempfile::tempdir().unwrap();
    assert!(poll_detached(empty.path()).await.unwrap().is_empty());
    let got = launcher.push_poll().await.unwrap();
    assert_eq!(got.len(), 1);

    // Gerät beim Server weg → vergessen, nächster Abgleich meldet neu an.
    w.gone.store(true, Ordering::SeqCst);
    assert!(launcher.push_poll().await.unwrap().is_empty());
    assert!(!launcher.push_polling().await);
    launcher.push_sync(&env).await.unwrap();
    assert!(launcher.push_polling().await);
    drop(dir);
}

#[tokio::test]
async fn device_list_is_cleaned_and_own_device_removal_turns_push_off() {
    let w = world();
    let server = MockServer::start(api(Arc::clone(&w))).await;
    let (_dir, launcher) = signed_in(&server).await;
    launcher.push_sync(&up("https://ntfy.sh/upAbC?up=1")).await.unwrap();

    let devices = launcher.push_devices().await.unwrap();
    assert_eq!(devices.len(), 2, "ungültige IDs fallen weg");
    assert!(devices[0].this_device && devices[0].current);
    assert_eq!(devices[0].endpoint_host.as_deref(), Some("ntfy.sh"));
    assert_eq!(devices[1].device_name, "iPhone");
    assert_eq!(devices[1].endpoint_host, None);
    assert!(!devices[1].this_device);

    assert!(launcher.push_remove_device("../etc").await.is_err());
    launcher.push_remove_device(DEV2).await.unwrap();
    assert!(launcher.push_settings().await.enabled, "fremdes Gerät: Schalter bleibt");
    launcher.push_remove_device(DEV).await.unwrap();
    assert!(!launcher.push_settings().await.enabled);
    assert!(launcher.trs.push.record(ACC).await.is_none());
}

#[tokio::test]
async fn live_stream_names_the_push_device_and_pauses_in_the_background() {
    use crate::trs_api::chat_tests::{collect, run_until, test_config};
    use crate::trs_api::live::LiveOut;

    let w = world();
    let server = MockServer::start(api(Arc::clone(&w))).await;
    let (_dir, launcher) = signed_in(&server).await;
    launcher.push_sync(&up("https://ntfy.sh/upAbC?up=1")).await.unwrap();
    let log = collect(&launcher);

    // Im Hintergrund: kein Stream (der Server schickt dann Push-Nachrichten).
    launcher.trs_live_pause(true);
    let resume = Arc::clone(&launcher);
    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_millis(150)).await;
        resume.trs_live_pause(false);
    });
    run_until(&launcher, &test_config(), || !server.hits("GET", "/v1/events/me").is_empty()).await;
    let states: Vec<&str> = log.lock().unwrap().iter().filter_map(|o| if let LiveOut::Status(s) = o { Some(s.state) } else { None }).collect();
    assert_eq!(&states[..2], ["off", "connecting"]);
    let streams = server.hits("GET", "/v1/events/me");
    assert_eq!(streams[0].path, format!("/v1/events/me?pushDevice={DEV}"));
}
