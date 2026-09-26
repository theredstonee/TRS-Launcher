//! Moderation v2 (§22) gegen eine nachgebaute API: gesperrte Anmeldung mit
//! Einspruch-Token, Strafdetails an Fehlern, eigene Strafen/Einspruch, Rolle
//! und die Team-Routen (Akte, Strafen, Einsprüche, Rollen, Sammelaktionen).

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use serde_json::json;

use super::team::{AppealDecision, BulkAction, BulkTarget, NewSanction, SanctionQuery};
use super::testkit::{MockServer, Request, Response};
use super::tests::{ACC, launcher};


const APPEAL: &str = "trs_AppealAppealAppealAppealAppealAppealAppealX";
const BOB: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

fn token(n: u8) -> String {
    format!("trs_{}", (n as char).to_string().repeat(43))
}

fn sanction(kind: &str, appeal: Option<serde_json::Value>) -> serde_json::Value {
    json!({ "id": 7, "kind": kind, "reasonCode": "harassment", "reason": "Beleidigungen im Chat",
        "startsAt": "2026-09-26T10:00:00.000Z", "endsAt": "2099-01-01T00:00:00.000Z", "status": "active",
        "liftedAt": null, "appealable": appeal.is_none(), "appeal": appeal })
}

fn banned_error() -> Response {
    Response::json(
        403,
        json!({ "error": { "code": "banned", "message": "This account is banned", "until": "2099-01-01T00:00:00.000Z",
            "sanction": sanction("account_ban", None), "appealToken": APPEAL, "appealTokenExpiresAt": "2099-01-01T00:00:00.000Z" } }),
    )
}

/// API, deren Konto erst gesperrt ist; nach `unban` klappt die Anmeldung wieder.
struct BanWorld {
    banned: AtomicBool,
    appealed: Mutex<Option<String>>,
    issued: AtomicUsize,
}

fn ban_api(world: Arc<BanWorld>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    move |req: &Request| {
        let server_id = "ab".repeat(20);
        match (req.method.as_str(), req.path.as_str()) {
            ("POST", "/v1/auth/challenge") => {
                return Response::json(201, json!({ "serverId": server_id, "expiresAt": "2099-01-01T00:00:00.000Z" }));
            }
            ("POST", "/session/minecraft/join") => return Response::empty(204),
            ("POST", "/v1/auth/verify") => {
                if world.banned.load(Ordering::SeqCst) {
                    return banned_error();
                }
                let n = world.issued.fetch_add(1, Ordering::SeqCst) as u8;
                return Response::json(
                    200,
                    json!({ "token": token(b'A' + n), "expiresAt": "2099-01-01T00:00:00.000Z", "user": {
                        "uuid": ACC, "name": "Theredstonee", "admin": false, "role": "moderator", "createdAt": "…",
                        "settings": { "showBadge": true, "showCapeToOthers": true, "presenceVisibility": "friends", "shareServer": false },
                        "activeCape": null } }),
                );
            }
            _ => {}
        }
        let bearer = req.bearer().unwrap_or_default();
        if bearer == APPEAL {
            if !world.banned.load(Ordering::SeqCst) {
                return Response::error(401, "unauthorized");
            }
            return match (req.method.as_str(), req.path.as_str()) {
                ("GET", "/v1/me/sanctions") => {
                    let appeal = world.appealed.lock().unwrap().clone().map(|_| {
                        json!({ "id": 3, "status": "open", "createdAt": "2026-09-26T11:00:00.000Z", "decidedAt": null, "response": null })
                    });
                    Response::json(200, json!({ "active": [sanction("account_ban", appeal)], "past": [{ "id": 2, "kind": "warn",
                        "reasonCode": "spam", "reason": null, "startsAt": "2026-08-01T00:00:00.000Z", "endsAt": "2026-08-31T00:00:00.000Z",
                        "status": "expired", "liftedAt": null, "appeal": null, "appealable": false }] }))
                }
                ("POST", "/v1/me/sanctions/7/appeal") => {
                    let mut appealed = world.appealed.lock().unwrap();
                    if appealed.is_some() {
                        return Response::error(409, "appeal_exists");
                    }
                    *appealed = req.json()["text"].as_str().map(str::to_owned);
                    Response::json(201, json!({ "sanction": sanction("account_ban", Some(json!({ "id": 3, "status": "open",
                        "createdAt": "2026-09-26T11:00:00.000Z", "decidedAt": null, "response": null }))) }))
                }
                _ => banned_error(),
            };
        }
        if !bearer.starts_with("trs_") {
            return Response::error(401, "unauthorized");
        }
        match (req.method.as_str(), req.path.as_str()) {
            ("GET", "/v1/me") => Response::json(200, json!({
                "uuid": ACC, "name": "Theredstonee", "admin": false, "role": "moderator", "createdAt": "…",
                "settings": { "showBadge": true, "showCapeToOthers": true, "presenceVisibility": "friends", "shareServer": false },
                "activeCape": null })),
            ("GET", "/v1/me/sanctions") => Response::json(200, json!({ "active": [], "past": [] })),
            ("POST", p) if p.starts_with("/v1/capes/upload") => Response::json(
                403,
                json!({ "error": { "code": "sanctioned", "message": "blocked", "until": "2099-01-01T00:00:00.000Z",
                    "sanction": sanction("upload_ban", None) } }),
            ),
            _ => Response::error(404, "not_found"),
        }
    }
}

#[tokio::test]
async fn banned_accounts_can_see_and_appeal_their_sanctions() {
    let world = Arc::new(BanWorld { banned: AtomicBool::new(true), appealed: Mutex::new(None), issued: AtomicUsize::new(0) });
    let server = MockServer::start(ban_api(Arc::clone(&world))).await;
    let (_dir, l) = launcher(&server, &[ACC]).await;

    // Gesperrt: Fehler trägt die Strafe als Parameter, der Token bleibt im Kern.
    let err = l.trs_me().await.unwrap_err();
    let user = err.to_user();
    assert_eq!(user.kind, "trs_banned");
    assert_eq!(user.api_code.as_deref(), Some("banned"));
    assert_eq!(user.params["sanctionKind"], "account_ban");
    assert_eq!(user.params["reasonCode"], "harassment");
    assert_eq!(user.params["appealable"], "true");
    let json = serde_json::to_string(&user).unwrap();
    assert!(!json.contains(APPEAL), "Einspruch-Token erreicht nie das Webview");

    // Eigene Strafen über den Einspruch-Token.
    let mine = l.trs_my_sanctions().await.unwrap();
    assert_eq!(mine.active.len(), 1);
    assert!(mine.active[0].appealable);
    assert_eq!(mine.past[0].kind, "warn");
    assert!(server.hits("GET", "/v1/me/sanctions").iter().all(|r| r.bearer() == Some(APPEAL)));

    // Zu kurz → gar keine Anfrage; danach einmal Einspruch, zweites Mal 409.
    assert!(l.trs_appeal(7, "zu kurz").await.is_err());
    assert!(server.hits("POST", "/v1/me/sanctions/7/appeal").is_empty());
    let text = "Ich habe niemanden beleidigt – bitte schaut euch den Verlauf noch einmal an.";
    let after = l.trs_appeal(7, text).await.unwrap();
    assert_eq!(after.appeal.as_ref().unwrap().status, "open");
    assert!(!after.appealable);
    assert_eq!(world.appealed.lock().unwrap().as_deref(), Some(text));
    let again = l.trs_appeal(7, text).await.unwrap_err();
    assert_eq!(again.code(), Some("appeal_exists"));

    // Sperre vorbei: Einspruch-Token wird verworfen, normale Anmeldung klappt, Rolle kommt mit.
    world.banned.store(false, Ordering::SeqCst);
    let mine = l.trs_my_sanctions().await.unwrap();
    assert!(mine.active.is_empty());
    let me = l.trs_me().await.unwrap();
    assert_eq!(me.role.as_deref(), Some("moderator"));
    assert!(!me.admin);
    assert!(l.trs.appeal_tokens.get(ACC).is_none());
}

#[tokio::test]
async fn sanctioned_errors_carry_the_sanction() {
    let world = Arc::new(BanWorld { banned: AtomicBool::new(false), appealed: Mutex::new(None), issued: AtomicUsize::new(0) });
    let server = MockServer::start(ban_api(world)).await;
    let (_dir, l) = launcher(&server, &[ACC]).await;
    let png = super::png::tests::real_png(64, 32);
    let err = l.trs_upload_cape(png, 1, None, None).await.unwrap_err();
    let user = err.to_user();
    assert_eq!(user.api_code.as_deref(), Some("sanctioned"));
    assert_eq!(user.code, "trsApi.sanctioned");
    assert_eq!(user.params["sanctionKind"], "upload_ban");
    assert_eq!(user.params["endsAt"], "2099-01-01T00:00:00.000Z");
    assert_eq!(user.params["reason"], "Beleidigungen im Chat");
}

fn admin_sanction(id: u64, status: &str) -> serde_json::Value {
    json!({ "id": id, "player": { "uuid": BOB, "name": "Bob" }, "kind": "chat_mute", "reasonCode": "spam", "reason": null,
        "note": "intern", "reportId": null, "auto": null, "createdAt": "…", "createdBy": { "uuid": ACC, "name": "Theredstonee" },
        "createdRole": "admin", "endsAt": "2099-01-01T00:00:00.000Z", "permanent": false, "status": status, "liftedAt": null,
        "liftedBy": null, "liftReason": null, "changes": [], "appeal": null, "migrated": false })
}

#[tokio::test]
async fn team_routes_send_checked_bodies() {
    let issued = Arc::new(AtomicUsize::new(0));
    let i = Arc::clone(&issued);
    let server = MockServer::start(move |req| {
        if let Some(r) = super::tests::auth_routes(req, &i, "mc-token") {
            return r;
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        let path = req.path.as_str();
        match (req.method.as_str(), path) {
            ("GET", "/v1/admin/dashboard") => Response::json(200, json!({ "reports": { "open": 2, "inReview": 1, "highPriority": 1,
                "oldestOpenAt": null }, "recentAudit": [{ "id": 1, "at": "…", "actor": "system", "actorName": null,
                "action": "chat.automute.spam", "target": BOB, "targetName": "Bob\u{202E}", "detail": null, "ref": null }] })),
            ("GET", p) if p.starts_with("/v1/admin/search?q=") => Response::json(200, json!({ "players": [], "reports": [], "capes": [], "cosmetics": [], "sanctions": [] })),
            ("GET", p) if p.starts_with("/v1/admin/sanctions?") => Response::json(200, json!({ "sanctions": [admin_sanction(7, "active")], "nextCursor": null })),
            ("POST", "/v1/admin/sanctions") => Response::json(201, json!({ "sanction": admin_sanction(8, "active") })),
            ("POST", "/v1/admin/sanctions/8/lift") => Response::json(200, json!({ "sanction": admin_sanction(8, "lifted") })),
            ("POST", "/v1/admin/sanctions/8/duration") => Response::error(409, "no_change"),
            ("POST", "/v1/admin/appeals/3/decide") => Response::error(403, "own_sanction"),
            ("GET", p) if p.starts_with("/v1/admin/players/") => Response::json(200, json!({ "file": { "player": { "uuid": BOB, "name": "Bob" } } })),
            ("POST", "/v1/admin/reports/bulk") => Response::json(200, json!({ "updated": ["r0123456789abcdef"], "skipped": [] })),
            ("PUT", p) if p == format!("/v1/admin/roles/{BOB}") => Response::json(200, json!({ "roles": [] })),
            _ => Response::error(404, "not_found"),
        }
    })
    .await;
    let (_dir, l) = launcher(&server, &[ACC]).await;

    let dash = l.admin_dashboard().await.unwrap();
    assert_eq!(dash["recentAudit"][0]["targetName"], "Bob", "Antworten werden gesäubert");
    l.admin_search("#7").await.unwrap();
    assert_eq!(server.hits("GET", "/v1/admin/search")[0].path, "/v1/admin/search?q=%237");

    let list = l.admin_sanctions(&SanctionQuery { uuid: Some(BOB.into()), ..Default::default() }).await.unwrap();
    assert_eq!(list["sanctions"][0]["id"], 7);

    let created = l
        .admin_sanction_create(&NewSanction {
            uuid: BOB.into(),
            kind: "chat_mute".into(),
            duration: "3d".into(),
            reason_code: "spam".into(),
            note: Some("Wiederholt".into()),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(created["sanction"]["id"], 8);
    assert_eq!(
        server.hits("POST", "/v1/admin/sanctions")[0].json(),
        json!({ "uuid": BOB, "kind": "chat_mute", "duration": "3d", "reasonCode": "spam", "note": "Wiederholt" })
    );

    // Aufheben/Ändern nur mit Begründung.
    assert!(l.admin_sanction_lift(8, "  ").await.is_err());
    let lifted = l.admin_sanction_lift(8, "Missverständnis").await.unwrap();
    assert_eq!(lifted["sanction"]["status"], "lifted");
    let err = l.admin_sanction_duration(8, Some("2999-01-01T00:00:00Z"), "Kürzer").await.unwrap_err();
    assert_eq!(err.to_user().code, "trsApi.no_change");
    assert_eq!(server.hits("POST", "/v1/admin/sanctions/8/duration")[0].json()["endsAt"], "2999-01-01T00:00:00.000Z");
    l.admin_sanction_duration(8, None, "Dauerhaft").await.unwrap_err();
    assert_eq!(server.hits("POST", "/v1/admin/sanctions/8/duration")[1].json()["endsAt"], serde_json::Value::Null);

    let err = l
        .admin_appeal_decide(3, &AppealDecision { decision: "uphold".into(), response: "Bleibt.".into(), ends_at: None })
        .await
        .unwrap_err();
    assert_eq!(err.to_user().code, "trsApi.own_sanction");

    let file = l.admin_player(&BOB.to_uppercase()).await.unwrap();
    assert_eq!(file["file"]["player"]["name"], "Bob");
    assert!(server.hits("GET", &format!("/v1/admin/players/{BOB}")).len() == 1, "UUID normalisiert");

    let bulk = l
        .admin_bulk(BulkTarget::Reports, &BulkAction { ids: vec!["r0123456789abcdef".into()], action: "resolve".into(), reason: None })
        .await
        .unwrap();
    assert_eq!(bulk["updated"][0], "r0123456789abcdef");
    assert!(l.admin_role_set(BOB, "owner", None).await.is_err());
    l.admin_role_set(BOB, "moderator", Some("Hilft im Discord")).await.unwrap();
    assert_eq!(server.hits("PUT", &format!("/v1/admin/roles/{BOB}"))[0].json(), json!({ "role": "moderator", "note": "Hilft im Discord" }));
}
