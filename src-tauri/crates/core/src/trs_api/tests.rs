//! Tests gegen eine nachgebaute API (keine echten Anfragen).

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use futures::future::BoxFuture;
use serde_json::json;

use super::png::tests::png_with;
use super::testkit::{MockServer, Request, Response};
use super::types::{ApiMe, NewCodes, ReportReason, ReviewList, SettingsPatch, Visibility};
use super::*;
use crate::Launcher;
use crate::launch::Session;

const ACC: &str = "75c1a6f3112240abbdb57b9d21c64232";
const OTHER: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

fn token(n: u8) -> String {
    format!("trs_{}", (n as char).to_string().repeat(43))
}

fn me_json(uuid: &str, admin: bool) -> serde_json::Value {
    json!({
        "uuid": uuid, "name": "Theredstonee", "admin": admin, "createdAt": "2026-09-23T18:05:02.000Z",
        "settings": { "showBadge": true, "showCapeToOthers": true, "presenceVisibility": "friends", "shareServer": false },
        "activeCape": null
    })
}

/// Minecraft-Sitzungen für die Tests; `force_refresh` liefert ein neues Token.
struct FakeSessions {
    access: Mutex<String>,
    refreshed: AtomicUsize,
}

impl FakeSessions {
    fn new(access: &str) -> Self {
        Self { access: Mutex::new(access.into()), refreshed: AtomicUsize::new(0) }
    }
}

impl SessionSource for FakeSessions {
    fn session<'a>(&'a self, account: &'a str, force_refresh: bool) -> BoxFuture<'a, Result<Option<Session>>> {
        Box::pin(async move {
            if force_refresh {
                self.refreshed.fetch_add(1, Ordering::SeqCst);
                *self.access.lock().unwrap() = "fresh-mc-token".into();
            }
            Ok(Some(Session {
                player_name: "Theredstonee".into(),
                uuid: account.into(),
                access_token: self.access.lock().unwrap().clone(),
                xuid: "1".into(),
                demo: false,
            }))
        })
    }
}

/// Handler für Challenge/Join/Verify; vergibt der Reihe nach `token(b'A')`, `token(b'B')`, …
fn auth_routes(req: &Request, issued: &AtomicUsize, accept_mc: &str) -> Option<Response> {
    let server_id = "ab".repeat(20);
    Some(match (req.method.as_str(), req.path.as_str()) {
        ("POST", "/v1/auth/challenge") => {
            Response::json(201, json!({ "serverId": server_id, "expiresAt": "2099-01-01T00:00:00.000Z" }))
        }
        ("POST", "/session/minecraft/join") => {
            let body = req.json();
            if body["accessToken"] == accept_mc && body["serverId"] == server_id {
                Response::empty(204)
            } else {
                Response::error(403, "ForbiddenOperationException")
            }
        }
        ("POST", "/v1/auth/verify") => {
            let n = issued.fetch_add(1, Ordering::SeqCst) as u8;
            Response::json(
                200,
                json!({ "token": token(b'A' + n), "expiresAt": "2099-01-01T00:00:00.000Z", "user": me_json(ACC, true) }),
            )
        }
        _ => return None,
    })
}

async fn api(server: &MockServer) -> (tempfile::TempDir, TrsApi) {
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    paths.ensure().await.unwrap();
    let trs = TrsApi::with_endpoints(paths, &server.base, &server.base, &server.base).unwrap();
    trs.store.set_consent(Consent::Accepted).await;
    (dir, trs)
}

#[tokio::test]
async fn login_uses_the_mojang_join_and_keeps_the_token_encrypted() {
    let issued = Arc::new(AtomicUsize::new(0));
    let i = Arc::clone(&issued);
    let server = MockServer::start(move |req| {
        auth_routes(req, &i, "mc-token").unwrap_or_else(|| match req.path.as_str() {
            "/v1/me" if req.bearer() == Some(token(b'A').as_str()) => Response::json(200, me_json(ACC, true)),
            _ => Response::error(401, "unauthorized"),
        })
    })
    .await;
    let (dir, trs) = api(&server).await;
    let sessions = FakeSessions::new("mc-token");

    let me: ApiMe = trs.call(&sessions, ACC, &Req::get("/v1/me")).await.unwrap();
    assert!(me.admin);

    let join = &server.hits("POST", "/session/minecraft/join")[0];
    let body = join.json();
    assert_eq!(body["serverId"], "ab".repeat(20), "serverId unverändert, nicht gehasht");
    assert_eq!(body["selectedProfile"], ACC);
    assert_eq!(body["accessToken"], "mc-token");
    assert_eq!(server.hits("POST", "/v1/auth/verify")[0].json(), json!({ "username": "Theredstonee", "serverId": "ab".repeat(20) }));
    assert!(server.hits("POST", "/v1/auth/challenge")[0].bearer().is_none());

    // Token liegt verschlüsselt auf der Platte und wird wiederverwendet.
    let raw = std::fs::read_to_string(dir.path().join("trs-api.json")).unwrap();
    assert!(!raw.contains(&token(b'A')));
    let _: ApiMe = trs.call(&sessions, ACC, &Req::get("/v1/me")).await.unwrap();
    assert_eq!(server.hits("POST", "/v1/auth/challenge").len(), 1, "kein zweiter Login");
}

#[tokio::test]
async fn expired_token_triggers_exactly_one_relogin() {
    let issued = Arc::new(AtomicUsize::new(1)); // nächster Token: B
    let i = Arc::clone(&issued);
    let server = MockServer::start(move |req| {
        auth_routes(req, &i, "mc-token").unwrap_or_else(|| match req.bearer() {
            Some(t) if t == token(b'B') => Response::json(200, json!({ "friends": [], "requests": { "incoming": [], "outgoing": [] } })),
            _ => Response::error(401, "unauthorized"),
        })
    })
    .await;
    let (_dir, trs) = api(&server).await;
    trs.store.put_token(ACC, &token(b'A'), chrono::Utc::now() + chrono::Duration::days(3)).await.unwrap();
    let sessions = FakeSessions::new("mc-token");

    let friends: types::FriendsView = trs.call(&sessions, ACC, &Req::get("/v1/friends")).await.unwrap();
    assert!(friends.friends.is_empty());
    let calls = server.hits("GET", "/v1/friends");
    assert_eq!(calls.len(), 2);
    assert_eq!(calls[0].bearer(), Some(token(b'A').as_str()));
    assert_eq!(calls[1].bearer(), Some(token(b'B').as_str()));

    // Lehnt die API auch den frischen Token ab, gibt es keine Endlosschleife.
    let server = MockServer::start(move |req| {
        auth_routes(req, &AtomicUsize::new(0), "mc-token").unwrap_or_else(|| Response::error(401, "unauthorized"))
    })
    .await;
    let (_dir, trs) = api(&server).await;
    let err = trs.call_raw(&sessions, ACC, &Req::get("/v1/me")).await.unwrap_err();
    assert_eq!(err.kind(), "trs_auth");
    assert_eq!(server.hits("GET", "/v1/me").len(), 1);
}

#[tokio::test]
async fn rejected_minecraft_token_is_refreshed_once() {
    let server = MockServer::start(move |req| {
        auth_routes(req, &AtomicUsize::new(0), "fresh-mc-token").unwrap_or_else(|| Response::json(200, me_json(ACC, false)))
    })
    .await;
    let (_dir, trs) = api(&server).await;
    let sessions = FakeSessions::new("stale-mc-token");
    let _: ApiMe = trs.call(&sessions, ACC, &Req::get("/v1/me")).await.unwrap();
    assert_eq!(sessions.refreshed.load(Ordering::SeqCst), 1);
    let joins = server.hits("POST", "/session/minecraft/join");
    assert_eq!(joins.len(), 2);
    assert_eq!(joins[1].json()["serverId"], joins[0].json()["serverId"], "dieselbe Challenge");
}

#[tokio::test]
async fn without_consent_nothing_is_sent() {
    let server = MockServer::start(|_| Response::json(200, json!({}))).await;
    let (_dir, trs) = api(&server).await;
    let sessions = FakeSessions::new("mc-token");

    trs.store.set_consent(Consent::Declined).await;
    let err = trs.call_raw(&sessions, ACC, &Req::get("/v1/me")).await.unwrap_err();
    assert_eq!(err.kind(), "trs_disabled");
    assert!(trs.login(&sessions, ACC).await.is_err());
    assert!(server.requests().is_empty(), "keine einzige Anfrage ohne Einwilligung");
}

#[tokio::test]
async fn rate_limits_and_errors_are_translated() {
    let attempts = Arc::new(AtomicUsize::new(0));
    let a = Arc::clone(&attempts);
    let server = MockServer::start(move |req| match req.path.as_str() {
        "/v1/friends" => {
            if a.fetch_add(1, Ordering::SeqCst) == 0 {
                Response::error(429, "rate_limited").with_header("retry-after", "1")
            } else {
                Response::json(200, json!({ "friends": [], "requests": {} }))
            }
        }
        "/v1/blocks" => Response::error(429, "rate_limited").with_header("retry-after", "30"),
        "/v1/me" => Response::error(403, "banned"),
        "/v1/capes/redeem" => Response::error(410, "code_expired"),
        _ => Response::error(503, "database_unavailable"),
    })
    .await;
    let (_dir, trs) = api(&server).await;
    trs.store.put_token(ACC, &token(b'A'), chrono::Utc::now() + chrono::Duration::days(3)).await.unwrap();
    let sessions = FakeSessions::new("mc-token");

    // Kurzes Retry-After wird abgewartet …
    let _: types::FriendsView = trs.call(&sessions, ACC, &Req::get("/v1/friends")).await.unwrap();
    assert_eq!(attempts.load(Ordering::SeqCst), 2);
    // … langes geht mit Wartezeit an die Oberfläche.
    let err = trs.call_raw(&sessions, ACC, &Req::get("/v1/blocks")).await.unwrap_err();
    assert_eq!(err.kind(), "trs_rate_limited");
    assert!(err.public_message().contains("30 s"));
    assert_eq!((err.message_code(), err.message_params()["seconds"].as_str()), ("trs.rateLimited", Some("30")));
    assert_eq!(server.hits("GET", "/v1/blocks").len(), 1);

    assert_eq!(trs.call_raw(&sessions, ACC, &Req::get("/v1/me")).await.unwrap_err().kind(), "trs_banned");
    let expired = trs.call_raw(&sessions, ACC, &Req::post("/v1/capes/redeem", json!({}))).await.unwrap_err();
    assert_eq!((expired.kind(), expired.code()), ("trs_api", Some("code_expired")));
    assert!(expired.public_message().contains("abgelaufen"));
    assert_eq!(expired.to_user().code, "trsApi.code_expired");
    assert_eq!(trs.call_raw(&sessions, ACC, &Req::get("/v1/health")).await.unwrap_err().kind(), "trs_offline");
}

#[tokio::test]
async fn offline_is_a_quiet_error() {
    // Port ohne Server.
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::new(dir.path());
    let trs = TrsApi::with_endpoints(paths, "http://127.0.0.1:9", "http://127.0.0.1:9", "http://127.0.0.1:9").unwrap();
    trs.store.set_consent(Consent::Accepted).await;
    trs.store.put_token(ACC, &token(b'A'), chrono::Utc::now() + chrono::Duration::days(3)).await.unwrap();
    let err = trs.call_raw(&FakeSessions::new("x"), ACC, &Req::get("/v1/me")).await.unwrap_err();
    assert_eq!(err.kind(), "trs_offline");
}

// --- Auf Launcher-Ebene (aktiver Account aus accounts.json) -----------------------------------

struct World {
    requests: Arc<Mutex<Vec<String>>>,
}

/// Launcher mit einem angemeldeten Account und TRS-Endpunkten auf dem Mock.
async fn launcher(server: &MockServer, accounts: &[&str]) -> (tempfile::TempDir, Arc<Launcher>) {
    let dir = tempfile::tempdir().unwrap();
    let list: Vec<_> = accounts
        .iter()
        .map(|id| {
            json!({
                "id": id, "name": "Theredstonee", "skinUrl": null, "xuid": "1",
                "refreshToken": crate::auth::crypto::protect("refresh").unwrap(),
                "accessToken": crate::auth::crypto::protect("mc-token").unwrap(),
                "accessExpiresAt": "2099-01-01T00:00:00Z", "addedAt": "2026-09-01T00:00:00Z"
            })
        })
        .collect();
    std::fs::write(dir.path().join("accounts.json"), json!({ "active": accounts[0], "accounts": list }).to_string()).unwrap();
    let mut launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
    launcher.trs = TrsApi::with_endpoints(launcher.paths.clone(), &server.base, &server.base, &server.base).unwrap();
    launcher.trs.store.set_consent(Consent::Accepted).await;
    (dir, Arc::new(launcher))
}

fn full_api(base: Arc<std::sync::OnceLock<String>>, world: &World) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let log = Arc::clone(&world.requests);
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        let base = base.get().cloned().unwrap_or_default();
        log.lock().unwrap().push(format!("{} {}", req.method, req.path));
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        let authed = req.bearer().is_some_and(|t| t.starts_with("trs_"));
        let path = req.path.as_str();
        match (req.method.as_str(), path) {
            ("GET", "/v1/capes/team.png?v=abc123") => Response::png(png_with(128, 64 * 4, &[])),
            ("GET", "/v1/capes/u0123456789abcdef0123.png?v=p1") if authed => Response::png(png_with(64, 32, &[])),
            ("GET", "/users/profiles/minecraft/Griefer") => Response::json(200, json!({ "id": OTHER, "name": "Griefer" })),
            _ if !authed => Response::error(401, "unauthorized"),
            ("GET", "/v1/capes") => Response::json(
                200,
                json!({ "capes": [
                    { "id": "team", "name": "TRS Team", "kind": "builtin", "unlock": "admin", "status": "approved",
                      "url": format!("{base}/v1/capes/team.png?v=abc123"), "width": 128, "height": 64, "scale": 2,
                      "animated": true, "frames": 4, "frameTimeMs": 150, "owned": true, "active": true },
                    { "id": "u0123456789abcdef0123", "name": "Mein Umhang", "kind": "upload", "unlock": "owner", "status": "pending",
                      "url": format!("{base}/v1/capes/u0123456789abcdef0123.png?v=p1"), "width": 64, "height": 32, "scale": 1,
                      "animated": false, "frames": 1, "frameTimeMs": null, "owned": true, "active": false, "rejectReason": null },
                    { "id": "evil", "name": "x", "kind": "builtin", "unlock": "free", "status": "approved",
                      "url": "https://evil.example/x.png", "width": 64, "height": 32, "scale": 1,
                      "animated": false, "frames": 1, "frameTimeMs": null, "owned": true, "active": false },
                    { "id": "BAD ID", "name": "x", "kind": "builtin", "unlock": "free", "status": "approved",
                      "url": "", "width": 64, "height": 32, "scale": 1, "frames": 1, "owned": true, "active": false }
                ] }),
            ),
            ("POST", "/v1/capes/upload?name=Mein%20Umhang%21") => Response::json(
                201,
                json!({ "cape": { "id": "u0123456789abcdef0123", "name": "Mein Umhang!", "kind": "upload", "unlock": "owner",
                    "status": "pending", "url": format!("{base}/v1/capes/u0123456789abcdef0123.png?v=p1"),
                    "width": 64, "height": 32, "scale": 1, "animated": false, "frames": 1, "frameTimeMs": null } }),
            ),
            ("GET", "/v1/me") => Response::json(200, me_json(ACC, true)),
            ("PUT", "/v1/me/cape") => Response::json(200, json!({ "activeCape": null })),
            ("POST", "/v1/capes/redeem") => Response::json(
                200,
                json!({ "cape": { "id": "team", "name": "TRS Team", "kind": "builtin", "unlock": "admin", "status": "approved",
                    "url": format!("{base}/v1/capes/team.png?v=abc123"), "width": 128, "height": 64, "scale": 2,
                    "frames": 4, "frameTimeMs": 150 }, "alreadyOwned": false }),
            ),
            ("PATCH", "/v1/me") => {
                let mut me = me_json(ACC, true);
                me["settings"]["presenceVisibility"] = req.json()["presenceVisibility"].clone();
                Response::json(200, me)
            }
            ("POST", "/v1/presence") => Response::json(200, json!({ "state": "online", "expiresInSec": 180 })),
            ("POST", "/v1/auth/logout") | ("DELETE", "/v1/me") => Response::empty(204),
            ("GET", "/v1/friends") => Response::json(
                200,
                json!({ "friends": [
                    { "uuid": OTHER, "name": "Bob", "since": "2026-09-23T18:05:10.000Z",
                      "presence": { "state": "in-game", "game": { "version": "1.21.1", "loader": "fabric", "server": "play.example.net" }, "updatedAt": "x" } },
                    { "uuid": "not-a-uuid", "name": "Broken" },
                    { "uuid": "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0", "name": "Eve",
                      "presence": { "state": "in-game", "game": { "version": "1.21.1", "loader": "fabric", "server": "evil.example/path?x" } } }
                ], "requests": { "incoming": [ { "uuid": "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0", "name": "Alex", "createdAt": "x" } ], "outgoing": [] } }),
            ),
            ("POST", "/v1/web-login/approve") => match req.json()["code"].as_str().unwrap_or_default() {
                "ABCD-1234" => Response::empty(204),
                "WITH-BODY" => Response::json(200, json!({ "approved": true, "extra": [1, 2] })),
                "EXPI-RED0" => Response::error(410, "expired"),
                "NOPE-0000" => Response::error(404, "invalid_code"),
                "USER-0000" => Response::error(403, "not_admin"),
                "SLOW-0000" => Response::error(429, "rate_limited").with_header("retry-after", "42"),
                _ => Response::error(400, "invalid_request"),
            },
            ("GET", "/v1/admin/users/Griefer") => Response::error(404, "user_not_found"),
            ("GET", "/v1/admin/users/Friend") => Response::json(200, json!({ "user": { "uuid": OTHER, "name": "Friend", "known": true } })),
            ("POST", p) if p == format!("/v1/admin/users/{OTHER}/ban") => {
                Response::json(200, json!({ "user": { "uuid": OTHER, "name": null, "known": false, "banned": { "reason": "Griefing" } } }))
            }
            ("POST", p) if p == format!("/v1/admin/users/{OTHER}/capes") => Response::json(201, json!({ "alreadyOwned": false })),
            ("POST", "/v1/admin/codes") => Response::json(
                201,
                json!({ "codes": [ { "id": 1, "hint": "HJN4", "capeId": "team", "maxUses": 1, "uses": 0, "code": "7K3QF-M2XPA-9RTVB-CHJN4" } ] }),
            ),
            ("GET", "/v1/admin/capes?status=pending") => Response::json(
                200,
                json!({ "capes": [ { "id": "u0123456789abcdef0123", "name": "Upload", "kind": "upload", "unlock": "owner", "status": "pending",
                    "url": format!("{base}/v1/capes/u0123456789abcdef0123.png?v=p1"), "width": 64, "height": 32, "scale": 1,
                    "frames": 1, "frameTimeMs": null, "owner": { "uuid": OTHER, "name": "Bob" }, "createdAt": "x",
                    "reviewedAt": null, "reviewedBy": null, "rejectReason": null, "reports": { "count": 0, "reasons": {} } } ] }),
            ),
            _ => Response::error(404, "not_found"),
        }
    }
}

async fn world() -> (World, MockServer) {
    let world = World { requests: Arc::new(Mutex::new(Vec::new())) };
    // Die Basis-URL steht erst nach dem Start fest.
    let base = Arc::new(std::sync::OnceLock::new());
    let server = MockServer::start(full_api(Arc::clone(&base), &world)).await;
    base.set(server.base.clone()).unwrap();
    (world, server)
}

#[tokio::test]
async fn catalog_loads_checked_textures_and_caches_only_approved_ones() {
    let (_world, server) = world().await;
    let (dir, launcher) = launcher(&server, &[ACC]).await;

    let capes = launcher.trs_capes().await.unwrap();
    let ids: Vec<_> = capes.iter().map(|c| c.id.as_str()).collect();
    assert_eq!(ids, ["team", "u0123456789abcdef0123", "evil"], "ungültige ID fällt weg");
    let team = &capes[0];
    assert!(team.texture.as_deref().is_some_and(|t| t.starts_with("data:image/png;base64,")));
    assert_eq!((team.frames, team.frame_time_ms, team.scale), (4, Some(150), 2));
    assert!(capes[1].texture.is_some(), "eigener Upload mit Token geladen");
    assert!(capes[2].texture.is_none(), "fremde Hosts werden nie geladen");

    let tex = server.hits("GET", "/v1/capes/team.png");
    assert!(tex[0].bearer().is_none(), "freigegebene Texturen ohne Token");
    assert!(server.hits("GET", "/v1/capes/u0123456789abcdef0123.png")[0].bearer().is_some());
    let cache = dir.path().join("cache").join("trs-capes");
    assert!(cache.join("team-abc123.png").is_file());
    assert!(!cache.join("u0123456789abcdef0123-p1.png").exists(), "Uploads in Prüfung nie auf die Platte");

    // Zweiter Aufruf kommt aus dem Cache.
    launcher.trs_capes().await.unwrap();
    assert_eq!(server.hits("GET", "/v1/capes/team.png").len(), 1);
}

#[tokio::test]
async fn upload_validates_before_sending() {
    let (_world, server) = world().await;
    let (dir, launcher) = launcher(&server, &[ACC]).await;

    let bad = dir.path().join("bad.png");
    std::fs::write(&bad, png_with(64, 64, &[])).unwrap();
    assert!(launcher.trs_upload_cape(&bad, None).await.is_err());
    assert!(launcher.trs_upload_cape(&bad, Some("<b>")).await.is_err());
    assert!(server.hits("POST", "/v1/capes/upload").is_empty(), "ungültige Dateien gehen nie raus");

    let good = dir.path().join("cape.png");
    let bytes = png_with(64, 32, &[]);
    std::fs::write(&good, &bytes).unwrap();
    let cape = launcher.trs_upload_cape(&good, Some("Mein Umhang!")).await.unwrap();
    assert_eq!(cape.status, types::CapeStatus::Pending);
    let sent = &server.hits("POST", "/v1/capes/upload")[0];
    assert_eq!(sent.header("content-type"), Some("image/png"));
    assert_eq!(sent.body, bytes, "rohes PNG, kein Multipart");
}

#[tokio::test]
async fn cape_code_and_settings_calls() {
    let (_world, server) = world().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let redeemed = launcher.trs_redeem("7k3qf m2xpa 9rtvb c4hjn").await.unwrap();
    assert_eq!(redeemed.cape_id, "team");
    assert_eq!(server.hits("POST", "/v1/capes/redeem")[0].json(), json!({ "code": "7K3QFM2XPA9RTVBC4HJN" }));
    assert!(launcher.trs_redeem("kurz").await.is_err());

    assert_eq!(launcher.trs_set_cape(None).await.unwrap(), None);
    assert_eq!(server.hits("PUT", "/v1/me/cape")[0].json(), json!({ "capeId": null }));
    assert!(launcher.trs_set_cape(Some("../x".into())).await.is_err());

    let me = launcher
        .trs_update_me(SettingsPatch { presence_visibility: Some(Visibility::Nobody), ..Default::default() })
        .await
        .unwrap();
    assert_eq!(me.settings.presence_visibility, Visibility::Nobody);
    assert_eq!(server.hits("PATCH", "/v1/me")[0].json(), json!({ "presenceVisibility": "nobody" }));
    assert!(launcher.trs_update_me(SettingsPatch::default()).await.is_err());

    assert!(launcher.trs_report_cape("team", ReportReason::Other, None).await.is_err(), "nur Uploads");
}

#[tokio::test]
async fn friends_are_sanitized() {
    let (_world, server) = world().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let view = launcher.trs_friends().await.unwrap();
    assert_eq!(view.friends.len(), 2, "kaputte UUID fällt weg");
    let bob = &view.friends[0];
    assert_eq!(bob.presence.as_ref().unwrap().game.as_ref().unwrap().server.as_deref(), Some("play.example.net"));
    let eve = &view.friends[1];
    assert_eq!(eve.presence.as_ref().unwrap().game.as_ref().unwrap().server, None, "Adresse mit Pfad verworfen");
    assert_eq!(view.requests.incoming[0].name, "Alex");
    assert!(launcher.trs_friend_request("nicht gültig!").await.is_err());
}

#[tokio::test]
async fn admin_actions() {
    let (_world, server) = world().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    // Unbekannter Name → Mojang liefert die UUID → Sperre per UUID.
    let banned = launcher.trs_admin_ban("Griefer", Some("Griefing")).await.unwrap();
    assert_eq!(banned.uuid, OTHER);
    assert_eq!(server.hits("POST", &format!("/v1/admin/users/{OTHER}/ban"))[0].json(), json!({ "reason": "Griefing" }));
    assert_eq!(server.hits("GET", "/users/profiles/minecraft/Griefer").len(), 1);

    // Umhang vergeben: Spieler muss bekannt sein.
    assert!(!launcher.trs_admin_grant("Friend", "team").await.unwrap());
    assert!(launcher.trs_admin_grant("Griefer", "team").await.is_err());

    let codes = launcher
        .trs_admin_create_codes(NewCodes { cape_id: "team".into(), max_uses: 5, count: 1, expires_at: None, note: Some("Giveaway".into()) })
        .await
        .unwrap();
    assert_eq!(codes[0].code.as_deref(), Some("7K3QF-M2XPA-9RTVB-CHJN4"));
    assert_eq!(
        server.hits("POST", "/v1/admin/codes")[0].json(),
        json!({ "capeId": "team", "maxUses": 5, "count": 1, "note": "Giveaway" })
    );

    let pending = launcher.trs_admin_capes(ReviewList::Pending).await.unwrap();
    assert_eq!(pending[0].owner.as_ref().unwrap().name, "Bob");
    assert!(pending[0].cape.texture.is_some(), "Vorschau mit Admin-Token");
    assert!(launcher.trs_admin_revoke_code(0).await.is_err());
}

#[tokio::test]
async fn website_login_is_approved_with_the_token_of_the_active_account() {
    let (_world, server) = world().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    // Ungültige Formate gehen gar nicht erst raus.
    for bad in ["", "ABCD", "ABC-12345", "ABCD-1234-5678", "ÄBCD-1234"] {
        let err = launcher.trs_web_login_approve(bad).await.unwrap_err();
        assert_eq!(err.message_code(), "trsOps.invalidWebLoginCode", "{bad}");
    }
    assert!(server.hits("POST", "/v1/web-login/approve").is_empty());

    // Kleinbuchstaben/ohne Bindestrich werden normalisiert; 204 = bestätigt.
    launcher.trs_web_login_approve(" abcd1234 ").await.unwrap();
    let sent = &server.hits("POST", "/v1/web-login/approve")[0];
    assert_eq!(sent.json(), json!({ "code": "ABCD-1234" }));
    assert!(sent.bearer().is_some_and(|t| t.starts_with("trs_")), "mit dem TRS-Token des aktiven Accounts");
    // Tolerant: auch 200 mit beliebigem Body gilt als bestätigt.
    launcher.trs_web_login_approve("with-body").await.unwrap();

    for (code, expected) in [
        ("EXPI-RED0", "trsWebLogin.expired"),
        ("NOPE-0000", "trsWebLogin.invalidCode"),
        ("USER-0000", "trsWebLogin.notAdmin"),
    ] {
        let err = launcher.trs_web_login_approve(code).await.unwrap_err();
        assert_eq!((err.kind(), err.message_code()), ("trs_api", expected), "{code}");
        assert_eq!(err.to_user().code, expected, "übersetzbar im Frontend");
    }
    let limited = launcher.trs_web_login_approve("SLOW-0000").await.unwrap_err();
    assert_eq!((limited.kind(), limited.message_code()), ("trs_rate_limited", "trs.rateLimited"));
    assert_eq!(limited.message_params()["seconds"], "42");
    // Unbekannte Fehler behalten die allgemeine Meldung.
    let other = launcher.trs_web_login_approve("ZZZZ-9999").await.unwrap_err();
    assert_eq!(other.message_code(), "trsApi.invalid_request");

    // Ohne Einwilligung keine Anfrage.
    launcher.trs.store.set_consent(Consent::Declined).await;
    let before = server.hits("POST", "/v1/web-login/approve").len();
    assert_eq!(launcher.trs_web_login_approve("ABCD-1234").await.unwrap_err().kind(), "trs_disabled");
    assert_eq!(server.hits("POST", "/v1/web-login/approve").len(), before);
}

#[test]
fn both_api_addresses_are_trusted_for_textures() {
    assert_eq!(DEFAULT_BASE, "https://trs-launcher.theredstonee.de");
    assert!(KNOWN_BASES.contains(&"https://api.theredstonee.de"), "alte Adresse bleibt erlaubt");
    let dir = tempfile::tempdir().unwrap();
    let real = TrsApi::with_endpoints(Paths::new(dir.path()), DEFAULT_BASE, MOJANG_SESSION, MOJANG_API).unwrap();
    assert_eq!(real.trusted_bases(), KNOWN_BASES.to_vec(), "keine doppelten Einträge");
    let local = TrsApi::with_endpoints(Paths::new(dir.path()), "http://127.0.0.1:8787/", MOJANG_SESSION, MOJANG_API).unwrap();
    assert_eq!(local.trusted_bases(), ["http://127.0.0.1:8787", DEFAULT_BASE, LEGACY_BASE]);
}

#[tokio::test]
async fn presence_only_while_no_game_of_the_account_runs() {
    let (world, server) = world().await;
    let (_dir, launcher) = launcher(&server, &[ACC, OTHER]).await;
    launcher.trs.store.put_token(OTHER, &token(b'Z'), chrono::Utc::now() + chrono::Duration::days(3)).await.unwrap();
    let presence = || server.hits("POST", "/v1/presence");

    launcher.presence_tick().await;
    assert_eq!(presence().len(), 1);
    assert_eq!(presence()[0].json(), json!({ "state": "online" }));
    assert_eq!(launcher.trs.presence.online_for().as_deref(), Some(ACC));

    // Spiel läuft → der Mod meldet, der Launcher schweigt.
    launcher.trs.presence.game_started("inst", Some(ACC));
    launcher.presence_tick().await;
    assert_eq!(presence().len(), 1);

    // Spiel zu → sofort wieder online (Mindestabstand wird abgewartet).
    launcher.trs.presence.game_exited("inst");
    launcher.trs.presence.set_online_for(Some(OTHER));
    launcher.presence_tick().await;
    let sent = presence();
    assert_eq!(sent.len(), 3, "offline für den alten + online für den aktiven Account");
    assert_eq!(sent[1].json(), json!({ "state": "offline" }));
    assert_eq!(sent[2].json(), json!({ "state": "online" }));
    drop(world);

    // Beenden nimmt die Präsenz zurück.
    launcher.trs_shutdown().await;
    assert_eq!(presence().last().unwrap().json(), json!({ "state": "offline" }));
}

#[tokio::test]
async fn opting_out_and_deleting_data() {
    let (_world, server) = world().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    launcher.trs_me().await.unwrap();
    assert!(launcher.trs_status().await.unwrap().signed_in);

    // Account entfernen → offline + Logout, Token weg.
    launcher.trs_forget_account(ACC).await;
    assert_eq!(server.hits("POST", "/v1/auth/logout").len(), 1);
    assert!(!launcher.trs.has_token(ACC).await);

    // Alle Daten löschen → DELETE /v1/me, danach sind die Dienste aus.
    let status = launcher.trs_delete_me().await.unwrap();
    assert_eq!(server.hits("DELETE", "/v1/me").len(), 1);
    assert_eq!(status.consent, Some(Consent::Declined));
    assert!(!status.signed_in);

    let before = server.requests().len();
    assert_eq!(launcher.trs_friends().await.unwrap_err().kind(), "trs_disabled");
    launcher.presence_tick().await;
    assert_eq!(server.requests().len(), before, "nach dem Abschalten keine Anfragen mehr");
}

#[test]
fn hd_builtin_capes_up_to_scale_8_are_shown() {
    let cape = |scale: u32, width: u32| -> types::ApiCape {
        serde_json::from_value(json!({
            "id": "nether", "name": "Nether", "kind": "builtin", "unlock": "free", "status": "approved",
            "url": "https://trs-launcher.theredstonee.de/v1/capes/nether.png?v=1",
            "width": width, "height": width / 2, "scale": scale, "frames": 4, "frameTimeMs": 150
        }))
        .unwrap()
    };
    assert!(cape(4, 256).is_valid());
    assert!(cape(8, 512).is_valid());
    assert!(!cape(8, 256).is_valid(), "Breite muss zum Faktor passen");
    assert!(!cape(9, 576).is_valid(), "mehr als Faktor 8 wird nicht angezeigt");
}
