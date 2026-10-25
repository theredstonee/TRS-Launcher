//! Creator-Programm (§35) gegen eine nachgebaute API: Seite laden (mit Symbol-
//! und Umhang-Bildern), Code einlösen, Blockieren, Dashboard, Uploads, Team.

use std::sync::atomic::{AtomicUsize, Ordering};

use serde_json::json;

use super::chat_tests::{collect, events, run_until, sse, test_config};
use super::live::LiveEvent;
use super::png::tests::real_png;
use super::testkit::{MockServer, Response};
use super::tests::{ACC, auth_routes, launcher};
use super::*;

const CREATOR: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";
/// Creator, den das Team im Team-Modus öffnet, und ein Helfer von ihm.
const TEAMED: &str = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0";
const HELPER: &str = "e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0";

fn sha(n: u8) -> String {
    format!("{n:02x}").repeat(32)
}

fn symbol(n: u8, size: u32, frames: u32) -> serde_json::Value {
    json!({ "url": format!("/v1/creator-symbols/{}.png", sha(n)), "sha256": sha(n), "size": size, "frames": frames, "frameMs": 200 })
}

fn card() -> serde_json::Value {
    json!({ "uuid": CREATOR, "name": "Pixelmaus", "tier": "partner", "skin": null,
        "channels": [{ "platform": "YouTube", "url": "https://youtube.com/@pixelmaus", "followers": 18400 }],
        "symbol": symbol(1, 16, 2),
        "fanCape": { "capeId": "u0123456789abcdef0123", "name": "Pixels Drache", "url": "/v1/capes/u0123456789abcdef0123.png?v=abc" },
        "milestone": "gold" })
}

fn dashboard() -> serde_json::Value {
    json!({ "uuid": ACC, "name": "Theredstonee", "tier": "starter", "state": "active", "reason": null, "since": "2026-10-03T00:00:00.000Z",
        "channels": [], "codes": [{ "code": "TRS-THEREDSTONEE", "kind": "main", "uses": 3, "maxUses": null, "expiresAt": null, "active": true, "createdAt": "2026-10-03T00:00:00.000Z" }],
        "limits": { "extraCodes": 1, "maxUsesPerCode": 100, "symbolFrames": 1, "fanCape": false },
        "stats": { "activeFans": 3, "redemptions": 3, "fanCapeHolders": 0, "newPlayers": 1, "packInstalls": 0, "daily": [] },
        "milestone": null, "nextMilestone": { "name": "bronze", "at": 100 },
        "symbol": { "current": symbol(2, 32, 1), "pending": symbol(3, 16, 1), "rejected": null },
        "fanCape": { "current": null, "pending": null, "rejected": null },
        "requests": [], "packs": [] })
}

async fn server(symbol_hits: std::sync::Arc<AtomicUsize>) -> MockServer {
    let issued = AtomicUsize::new(0);
    MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        let path = req.path.split('?').next().unwrap_or_default();
        match (req.method.as_str(), path) {
            ("GET", "/v1/me/creator") => Response::json(200, json!({
                "support": { "creator": card(), "startedAt": "2026-10-01T00:00:00.000Z", "lastCreatorUuid": CREATOR, "switchAvailableAt": "2026-10-31T00:00:00.000Z" },
                "fanCapes": [], "blocked": [], "showFanSymbols": false, "creator": dashboard(),
                "application": null })),
            ("GET", p) if p == format!("/v1/creator-symbols/{}.png", sha(1)) => {
                symbol_hits.fetch_add(1, Ordering::SeqCst);
                Response::png(real_png(16, 32))
            }
            ("GET", p) if p == format!("/v1/creator-symbols/{}.png", sha(2)) => Response::png(real_png(32, 32)),
            // Noch nicht freigegeben: nur mit Token.
            ("GET", p) if p == format!("/v1/creator-symbols/{}.png", sha(3)) => {
                if req.bearer().is_some() { Response::png(real_png(16, 16)) } else { Response::error(404, "not_found") }
            }
            ("GET", "/v1/capes/u0123456789abcdef0123.png") => Response::png(real_png(512, 256)),
            ("POST", "/v1/me/creator/redeem") => match req.json()["code"].as_str() {
                Some("TRS-PIXEL") => Response::json(200, json!({ "creator": card(), "supportChanged": false, "switchAvailableAt": "2026-10-31T00:00:00.000Z", "fanCapeGranted": true })),
                Some("TRS-OLD") => Response::error(410, "creator_code_expired"),
                Some("TRS-BLOCKED") => Response::error(409, "creator_blocked"),
                Some("TRS-THEREDSTONEE") => Response::error(409, "creator_self"),
                _ => Response::error(404, "creator_code_not_found"),
            },
            ("POST", "/v1/me/creator/blocks") => match req.json()["code"].as_str() {
                Some("TRS-PIXEL") => Response::error(409, "creator_supported"),
                _ => Response::json(200, json!({ "blocked": [{ "uuid": "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0", "name": "Spammer" }] })),
            },
            ("DELETE", "/v1/me/creator/blocks/c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0") => Response::json(200, json!({ "blocked": [] })),
            ("PATCH", "/v1/me/creator/settings") => Response::json(200, json!({ "showFanSymbols": req.json()["showFanSymbols"] })),
            ("DELETE", "/v1/me/creator/support") => Response::empty(204),
            ("PUT", "/v1/creator/symbol") => Response::json(200, json!({ "symbol": symbol(4, 16, 1) })),
            ("PUT", "/v1/creator/fan-cape") => Response::error(403, "creator_tier"),
            ("POST", "/v1/creator/codes") => Response::error(403, "creator_code_limit"),
            ("PATCH", "/v1/creator/codes/TRS-THEREDSTONEE") => Response::json(200, json!({ "codes": dashboard()["codes"] })),
            ("GET", "/v1/creator/previews") => Response::json(200, json!({ "previews": [
                { "id": 1, "product": "launcher", "version": "0.22.0-preview.1", "notes": "Erste Vorschau", "url": "https://github.com/x/y/releases/download/a.exe", "sha256": sha(9), "publishedAt": "2026-10-07T00:00:00.000Z" },
                { "id": 2, "product": "client", "version": "0.18.0-preview.1", "notes": null, "url": "https://example.com/c.jar", "sha256": null, "publishedAt": "2026-10-12T00:00:00.000Z" },
                { "id": 3, "product": "launcher", "version": "evil", "url": "file:///C:/Windows/x.exe" }
            ] })),
            ("PUT", "/v1/creator/packs/TRS-AB12-CD34") => Response::empty(204),
            ("GET", "/v1/creators/packs") => Response::json(200, json!({ "total": 1, "packs": [
                { "code": "TRS-AB12-CD34", "name": "Redstone Pack", "summary": "Alles für Redstone", "mcVersion": "1.21.1", "loader": "fabric", "installs": 42,
                  "creator": { "uuid": CREATOR, "name": "Pixelmaus", "tier": "partner" } },
                { "code": "kaputt", "name": "x", "creator": { "uuid": CREATOR, "name": "Pixelmaus", "tier": "partner" } }
            ] })),
            ("GET", "/v1/admin/creators/queue") => Response::json(200, json!({ "items": [
                { "id": 7, "kind": "symbol", "creator": { "uuid": CREATOR, "name": "Pixelmaus", "tier": "partner" }, "symbol": symbol(3, 16, 1), "createdAt": "2026-10-16T08:00:00.000Z" },
                { "id": "q8", "kind": "fan_cape", "creator": { "uuid": CREATOR, "name": "Pixelmaus", "tier": "partner" },
                  "cape": { "capeId": "u0123456789abcdef0123", "url": "/v1/capes/u0123456789abcdef0123.png?v=abc", "name": "Frost", "frames": 1 }, "createdAt": "2026-10-16T09:00:00.000Z" },
                { "id": "../x", "kind": "symbol", "creator": { "uuid": CREATOR, "name": "Pixelmaus", "tier": "partner" } }
            ] })),
            ("POST", "/v1/admin/creators/queue/7/approve") => Response::empty(204),
            ("POST", "/v1/admin/creators/queue/q8/reject") => Response::empty(204),
            ("GET", "/v1/admin/creators") => Response::json(200, json!({ "creators": [
                { "uuid": CREATOR, "name": "Pixelmaus", "tier": "partner", "state": "active", "reason": null, "fans": 842, "mainCode": "TRS-PIXEL", "since": "2026-10-03T00:00:00.000Z" },
                { "uuid": "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0", "name": "Spammer42", "tier": "starter", "state": "banned", "reason": "Codes gespammt", "fans": 3, "mainCode": "TRS-SPAM" }
            ] })),
            ("POST", p) if p == format!("/v1/admin/creators/{CREATOR}/revoke") => Response::empty(204),
            ("POST", "/v1/admin/creators") => Response::error(409, "already_creator"),
            ("GET", "/v1/admin/creators/requests") => Response::json(200, json!({ "requests": [
                { "id": 4, "kind": "upgrade", "status": "open", "createdAt": "2026-10-16T08:00:00.000Z", "reason": null,
                  "creator": { "uuid": CREATOR, "name": "Pixelmaus", "tier": "starter" },
                  "data": { "channelUrl": "https://youtube.com/@pixelmaus", "followers": 18400, "note": "Neues Video" }, "decidedAt": null }
            ] })),
            ("GET", "/v1/creator/dashboard") => {
                let mut d = dashboard();
                if req.path.ends_with(&format!("?as={CREATOR}")) {
                    d["uuid"] = json!(CREATOR);
                    d["viewer"] = json!({ "helper": true, "rights": ["codes", "stats"] });
                }
                // Team-Modus: alle Rechte, auch wenn man zugleich eingeschränkter Helfer ist.
                if req.path.ends_with(&format!("?as={TEAMED}")) {
                    d["uuid"] = json!(TEAMED);
                    d["viewer"] = json!({ "helper": true, "team": true, "rights": ["stats"] });
                    d["helpers"] = json!([{ "uuid": HELPER, "name": "Cutter", "rights": ["stats"], "status": "active" }]);
                    d["teamChanges"] = json!([{ "action": "code.create", "at": "2026-10-25T09:00:00.000Z" }, { "action": "Böse" }]);
                    d["stats"]["fanCapeFans"] = json!(2);
                }
                Response::json(200, d)
            }
            ("POST", "/v1/creator/helpers") => Response::error(403, "creator_helper_limit"),
            ("PATCH", p) | ("DELETE", p) if p == format!("/v1/creator/helpers/{HELPER}") => Response::empty(204),
            ("POST", "/v1/creator/requests") => Response::json(201, json!({ "requests": [{ "id": 9, "kind": "team_cape", "status": "open", "createdAt": "2026-10-25T08:00:00.000Z" }] })),
            ("GET", "/v1/admin/creators/stats") => Response::json(200, json!({
                "generatedAt": "2026-10-25T12:00:00.000Z",
                "definitions": { "newPlayerWindowHours": 24, "activitySince": "2026-10-25" },
                "totals": { "creators": 1, "fans": 4, "fans7d": 2 },
                "creators": [{ "uuid": CREATOR, "name": "Pixelmaus", "tier": "top", "state": "active", "mainCode": "PIXELMAUS",
                    "fans": { "total": 4, "today": 1, "d7": 2, "d30": 4 }, "newPlayers": { "total": 2, "d7": 1, "d30": 2 },
                    "pets": { "pet": null, "codes": 0, "activeCodes": 0, "redemptions": 0, "month": { "month": "2026-10", "used": 0, "limit": 200, "redemptions": 0 } },
                    "activity": { "online7d": 3, "online30d": 4, "played7d": 2, "played30d": 3 } }]
            })),
            ("GET", p) if p == format!("/v1/admin/creators/{CREATOR}/stats") => Response::json(200, json!({
                "definitions": { "newPlayerWindowHours": 24, "activitySince": null },
                "creator": { "uuid": CREATOR, "name": "Pixelmaus", "tier": "top", "state": "active" },
                "fanCape": { "holders": 6, "fanHolders": 4 },
                "series": [{ "day": "2026-10-25", "newFans": 1, "newPlayers": 1, "redemptions": 1, "petRedemptions": 0, "activeFans": null, "playingFans": null }]
            })),
            ("GET", p) if p == format!("/v1/admin/creators/{}/stats", "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0") => Response::error(404, "creator_not_found"),
            ("POST", p) if p == format!("/v1/me/creator/helper-invites/{CREATOR}") => Response::empty(204),
            ("DELETE", p) if p == format!("/v1/me/creator/helping/{CREATOR}") => Response::empty(204),
            _ => Response::error(404, "not_found"),
        }
    })
    .await
}

#[tokio::test]
async fn creator_page_loads_with_images_and_caches_approved_symbols() {
    let hits = std::sync::Arc::new(AtomicUsize::new(0));
    let server = server(hits.clone()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let me = launcher.creator_me().await.unwrap();
    assert!(!me.show_fan_symbols);
    let card = me.support.creator.as_ref().unwrap();
    assert_eq!(card.milestone.as_deref(), Some("gold"));
    assert!(card.symbol.as_ref().unwrap().image.as_deref().is_some_and(|i| i.starts_with("data:image/png;base64,")));
    let cape = card.fan_cape.as_ref().unwrap();
    assert_eq!((cape.scale, cape.frames), (8, 1), "Maße aus der Textur");
    assert!(cape.texture.is_some());
    let own = me.creator.as_ref().unwrap();
    assert!(own.symbol.current.as_ref().unwrap().image.is_some());
    assert!(own.symbol.pending.as_ref().unwrap().image.is_some(), "Symbol in Prüfung mit Token geladen");
    assert_eq!(own.limits.fan_cape, None);
    assert_eq!(own.next_milestone, Some(creator::NextMilestone { name: "bronze".into(), at: 100 }));

    // Freigegebene Symbole sind unveränderlich: beim zweiten Mal aus dem Cache.
    launcher.creator_me().await.unwrap();
    assert_eq!(hits.load(Ordering::SeqCst), 1);
    let sent = &server.hits("GET", &format!("/v1/creator-symbols/{}.png", sha(1)))[0];
    assert!(sent.bearer().is_none(), "öffentliche Bilder ohne Token");
}

#[tokio::test]
async fn redeeming_blocking_and_settings() {
    let server = server(Default::default()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let r = launcher.creator_redeem(" trs-pixel ").await.unwrap();
    assert!(!r.support_changed && r.fan_cape_granted);
    assert_eq!(r.switch_available_at.as_deref(), Some("2026-10-31T00:00:00.000Z"));
    assert_eq!(server.hits("POST", "/v1/me/creator/redeem")[0].json()["code"], "TRS-PIXEL", "groß geschrieben");

    for (code, expected) in [
        ("TRS-OLD", "trsApi.creator_code_expired"),
        ("TRS-BLOCKED", "trsApi.creator_blocked"),
        ("TRS-THEREDSTONEE", "trsApi.creator_self"),
        ("TRS-NOPE", "trsApi.creator_code_not_found"),
    ] {
        assert_eq!(launcher.creator_redeem(code).await.unwrap_err().message_code(), expected, "{code}");
    }
    let bad = launcher.creator_redeem("x y").await.unwrap_err();
    assert_eq!(bad.message_code(), "creator.invalidCode");
    assert_eq!(server.hits("POST", "/v1/me/creator/redeem").len(), 5, "ungültige Codes gehen nicht raus");

    assert_eq!(launcher.creator_block("TRS-PIXEL").await.unwrap_err().message_code(), "trsApi.creator_supported");
    let blocked = launcher.creator_block("trs-spam").await.unwrap();
    assert_eq!(blocked[0].name, "Spammer");
    assert!(launcher.creator_unblock("c0c0c0c0-c0c0-c0c0-c0c0-c0c0c0c0c0c0").await.unwrap().is_empty());
    assert!(launcher.creator_unblock("../x").await.is_err());

    assert!(launcher.creator_set_show_symbols(true).await.unwrap());
    launcher.creator_end_support().await.unwrap();
    assert_eq!(server.hits("DELETE", "/v1/me/creator/support").len(), 1);
}

#[tokio::test]
async fn dashboard_uploads_and_previews() {
    let server = server(Default::default()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    // Starter: nur ein Bild; der Kern prüft vor dem Senden.
    let err = launcher.creator_upload_symbol(real_png(16, 32), Some(150), 1, None).await.unwrap_err();
    assert_eq!(err.message_code(), "trsPng.symbolSize");
    assert!(server.hits("PUT", "/v1/creator/symbol").is_empty());
    launcher.creator_upload_symbol(real_png(16, 32), Some(120), 8, None).await.unwrap();
    let sent = &server.hits("PUT", "/v1/creator/symbol")[0];
    assert!(sent.path.ends_with("?frameMs=120"));
    assert_eq!(sent.header("content-type"), Some("image/png"));

    let cape = launcher.creator_upload_fan_cape(real_png(512, 256), 1, None, "Pixels Drache", None).await.unwrap_err();
    assert_eq!(cape.message_code(), "trsApi.creator_tier");
    assert!(server.hits("PUT", "/v1/creator/fan-cape")[0].path.contains("name=Pixels%20Drache"));

    let limit = launcher.creator_create_code(&creator::NewCreatorCode { code: "pixel-yt".into(), ..Default::default() }, None).await.unwrap_err();
    assert_eq!(limit.message_code(), "trsApi.creator_code_limit");
    let codes = launcher.creator_update_code("trs-theredstonee", &creator::CodePatch { active: Some(true), ..Default::default() }, None).await.unwrap();
    assert_eq!(codes[0].kind, "main");

    let previews = launcher.creator_previews().await.unwrap();
    assert_eq!(previews.iter().map(|p| p.version.as_str()).collect::<Vec<_>>(), ["0.18.0-preview.1", "0.22.0-preview.1"], "neueste zuerst, file:// fällt weg");

    launcher.creator_set_pack_listed("trsab12cd34", true, None).await.unwrap();
    assert_eq!(server.hits("PUT", "/v1/creator/packs/TRS-AB12-CD34")[0].json(), json!({ "listed": true }));
    let packs = launcher.creator_packs(Some("Red stone"), 0, 20).await.unwrap();
    assert_eq!((packs.total, packs.packs.len()), (1, 1));
    assert_eq!(packs.packs[0].creator.tier, "partner");
    assert!(server.hits("GET", "/v1/creators/packs")[0].path.contains("q=Red%20stone"));
}

#[tokio::test]
async fn team_queue_and_roster() {
    let server = server(Default::default()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let queue = launcher.admin_creator_queue().await.unwrap();
    assert_eq!(queue.len(), 2, "kaputte ID fällt weg");
    assert_eq!(queue[0].id, "7");
    assert!(queue[0].symbol.as_ref().unwrap().image.is_some());
    let cape = queue[1].cape.as_ref().unwrap();
    assert_eq!((cape.scale, cape.texture.is_some()), (8, true));

    launcher.admin_creator_decide("7", true, None).await.unwrap();
    assert!(launcher.admin_creator_decide("q8", false, Some("  ")).await.is_err(), "Ablehnen braucht einen Grund");
    launcher.admin_creator_decide("q8", false, Some("Fremdes Logo")).await.unwrap();
    assert_eq!(server.hits("POST", "/v1/admin/creators/queue/q8/reject")[0].json(), json!({ "reason": "Fremdes Logo" }));
    assert!(launcher.admin_creator_decide("../x", true, None).await.is_err());

    let roster = launcher.admin_creators(&Default::default()).await.unwrap();
    assert_eq!(roster[1].state, "banned");
    assert_eq!(roster[1].reason.as_deref(), Some("Codes gespammt"));

    let revoke = creator::Revoke { reason: "Codes gespammt".into(), ban: true, remove_fan_capes: false };
    launcher.admin_creator_revoke(CREATOR, &revoke).await.unwrap();
    assert_eq!(
        server.hits("POST", &format!("/v1/admin/creators/{CREATOR}/revoke"))[0].json(),
        json!({ "reason": "Codes gespammt", "ban": true, "removeFanCapes": false })
    );
    let requests = launcher.admin_creator_requests().await.unwrap();
    assert_eq!(requests[0].channel_url.as_deref(), Some("https://youtube.com/@pixelmaus"), "Angaben aus `data`");
    assert_eq!(requests[0].followers, Some(18400));

    let add = creator::NewCreator { player: "Pixelmaus".into(), tier: "starter".into(), main_code: None };
    assert_eq!(launcher.admin_creator_add(&add).await.unwrap_err().message_code(), "trsApi.already_creator");
}

#[tokio::test]
async fn creator_events_arrive_over_the_stream() {
    let issued = AtomicUsize::new(0);
    let server = MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if req.path != "/v1/events/me" {
            return Response::error(404, "not_found");
        }
        let updated = json!({ "type": "creator_updated", "kind": "cape_rejected", "reason": "Unscharf" });
        let milestone = json!({ "type": "creator_milestone", "creator": { "uuid": CREATOR, "name": "Pixelmaus" }, "milestone": "silver" });
        sse(&format!(
            "id: e1.1\nevent: hello\ndata: {{\"type\":\"hello\",\"resumed\":false}}\n\nid: e1.2\nevent: creator_updated\ndata: {updated}\n\nid: e1.3\nevent: creator_milestone\ndata: {milestone}\n\n"
        ))
    })
    .await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let log = collect(&launcher);
    let cfg = test_config();
    run_until(&launcher, &cfg, || events(&log).iter().any(|e| matches!(e, LiveEvent::CreatorMilestone { .. }))).await;

    let all = events(&log);
    assert!(all.iter().any(|e| matches!(e, LiveEvent::CreatorUpdated { kind, reason: Some(r), .. } if kind == "cape_rejected" && r == "Unscharf")));
    assert!(all.iter().any(|e| matches!(e, LiveEvent::CreatorMilestone { milestone, creator } if milestone == "silver" && creator.name == "Pixelmaus")));
}

#[tokio::test]
async fn helpers_act_for_their_creator() {
    let server = server(Default::default()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let own = launcher.creator_dashboard(None).await.unwrap();
    assert!(own.viewer.is_none());
    let helping = launcher.creator_dashboard(Some(CREATOR)).await.unwrap();
    assert_eq!(helping.viewer.unwrap().rights, ["stats", "codes"]);

    let err = launcher.creator_invite_helper(CREATOR, &["stats".into()]).await.unwrap_err();
    assert_eq!(err.message_code(), "trsApi.creator_helper_limit");
    assert_eq!(server.hits("POST", "/v1/creator/helpers")[0].json(), json!({ "uuid": CREATOR, "rights": ["stats"] }));
    assert!(launcher.creator_invite_helper(CREATOR, &[]).await.is_err(), "ohne Rechte geht nichts raus");
    assert_eq!(server.hits("POST", "/v1/creator/helpers").len(), 1);

    launcher.creator_answer_helper_invite(CREATOR, true).await.unwrap();
    assert_eq!(server.hits("POST", &format!("/v1/me/creator/helper-invites/{CREATOR}"))[0].json(), json!({ "accept": true }));
    launcher.creator_leave_helping(CREATOR).await.unwrap();

    // Uploads als Helfer tragen `?as=`.
    launcher.creator_upload_symbol(real_png(16, 16), None, 1, Some(CREATOR)).await.unwrap();
    assert!(server.hits("PUT", "/v1/creator/symbol")[0].path.ends_with(&format!("?frameMs=150&as={CREATOR}")));
}

#[tokio::test]
async fn team_mode_acts_with_all_rights() {
    let server = server(Default::default()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let d = launcher.creator_dashboard(Some(TEAMED)).await.unwrap();
    let viewer = d.viewer.as_ref().unwrap();
    assert!(viewer.team && !viewer.helper, "Team-Modus hat Vorrang vor der Helfer-Rolle");
    assert_eq!(viewer.rights, ["stats", "codes", "uploads", "packs"]);
    assert_eq!(d.helpers.len(), 1);
    assert_eq!(d.team_changes, vec![creator::TeamChange { action: "code.create".into(), at: Some("2026-10-25T09:00:00.000Z".into()) }]);
    assert_eq!(d.stats.fan_cape_fans, Some(2));
    assert_eq!(launcher.creator_dashboard(None).await.unwrap().stats.fan_cape_fans, None, "ältere API ohne Feld");

    // Helfer-Verwaltung und Anträge tragen `?as=`.
    launcher.creator_update_helper(HELPER, &["codes".into()], Some(TEAMED)).await.unwrap();
    let sent = &server.hits("PATCH", &format!("/v1/creator/helpers/{HELPER}"))[0];
    assert!(sent.path.ends_with(&format!("?as={TEAMED}")));
    assert_eq!(sent.json(), json!({ "rights": ["codes"] }));
    launcher.creator_remove_helper(HELPER, Some(TEAMED)).await.unwrap();
    assert!(server.hits("DELETE", &format!("/v1/creator/helpers/{HELPER}"))[0].path.ends_with(&format!("?as={TEAMED}")));
    launcher.creator_remove_helper(HELPER, None).await.unwrap();
    assert_eq!(server.hits("DELETE", &format!("/v1/creator/helpers/{HELPER}"))[1].path, format!("/v1/creator/helpers/{HELPER}"));
    let requests = launcher.creator_request(&creator::NewRequest::TeamCape { text: "Drache".into() }, Some(TEAMED)).await.unwrap();
    assert_eq!(requests[0].kind, "team_cape");
    assert!(server.hits("POST", "/v1/creator/requests")[0].path.ends_with(&format!("?as={TEAMED}")));
}

#[tokio::test]
async fn team_statistics_are_loaded() {
    let server = server(Default::default()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let query = creator_stats::CreatorStatsQuery { q: Some("pix".into()), tier: Some("top".into()), sort: Some("fans7d".into()), ..Default::default() };
    let list = launcher.admin_creator_stats(&query).await.unwrap();
    assert_eq!(server.hits("GET", "/v1/admin/creators/stats")[0].path, "/v1/admin/creators/stats?q=pix&tier=top&sort=fans7d");
    assert_eq!(list.creators[0].fans.d7, 2);
    assert_eq!(list.totals.creators, 1);
    assert_eq!(list.definitions.activity_since.as_deref(), Some("2026-10-25"));
    let bad = creator_stats::CreatorStatsQuery { sort: Some("x".into()), ..Default::default() };
    assert!(launcher.admin_creator_stats(&bad).await.is_err());
    assert_eq!(server.hits("GET", "/v1/admin/creators/stats").len(), 1, "ungültige Filter gehen nicht raus");

    let detail = launcher.admin_creator_stats_detail(CREATOR, Some(9999)).await.unwrap();
    assert!(server.hits("GET", &format!("/v1/admin/creators/{CREATOR}/stats"))[0].path.ends_with("?days=365"), "auf 365 Tage begrenzt");
    assert_eq!(detail.fan_cape.fan_holders, 4);
    assert_eq!(detail.series[0].active_fans, None);
    let missing = launcher.admin_creator_stats_detail("c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0", None).await.unwrap_err();
    assert_eq!(missing.message_code(), "trsApi.creator_not_found");
    assert!(launcher.admin_creator_stats_detail("../x", None).await.is_err());
}
