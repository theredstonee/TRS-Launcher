//! Halloween 2026 (§36) gegen eine nachgebaute API: Stand laden, Kürbis melden, anklopfen,
//! Sticker mit Bildern, Abzeichen-Bilder – und eine ältere API ohne §36.

use std::sync::atomic::AtomicUsize;

use serde_json::json;

use super::png::tests::real_png;
use super::testkit::{MockServer, Response};
use super::tests::{ACC, auth_routes, launcher};
use super::*;

async fn server(with_halloween: bool) -> MockServer {
    let issued = AtomicUsize::new(0);
    MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        let path = req.path.split('?').next().unwrap_or_default();
        if !with_halloween {
            return Response::error(404, "not_found");
        }
        match (req.method.as_str(), path) {
            ("GET", "/v1/me/halloween") => Response::json(200, json!({
                "active": true, "endsAt": "2026-11-02T23:00:00Z",
                "pumpkins": { "total": 13, "found": ["web-home"], "nextHint": { "de": "Wo die Fenster schließen …", "en": "Where windows close …", "es": "Donde se cierran …" } },
                "trick": { "usedToday": false, "next": "2026-10-17T22:00:00Z" },
                "badges": ["halloween2026"], "effects": { "hits": true, "trail": false } })),
            ("POST", "/v1/me/halloween/pumpkins/launcher-titlebar") => {
                Response::json(200, json!({ "found": ["web-home", "launcher-titlebar"], "count": 2, "complete": false }))
            }
            ("POST", "/v1/me/halloween/trick") => Response::error(409, "trick_used"),
            ("PUT", "/v1/me/halloween/effects") => Response::empty(204),
            ("POST", "/v1/me/halloween/wings") if req.json()["id"] == "bat_wings" => Response::empty(204),
            ("POST", "/v1/me/halloween/wings") => Response::error(409, "wings_chosen"),
            ("PUT", "/v1/me/cosmetics") => Response::json(200, json!({ "equipped": {}, "emotes": [] })),
            ("GET", "/v1/stickers") => Response::json(200, json!({ "stickers": [
                { "id": "ghost_boo", "name": { "de": "Buh!", "en": "Boo!", "es": "¡Bu!" }, "url": "/v1/stickers/ghost_boo.png" },
                { "id": "grave_riplag", "name": { "de": "RIP Lag", "en": "RIP lag", "es": "RIP lag" }, "url": "https://evil.example/x.png" },
                { "id": "../evil", "name": {} } ] })),
            ("GET", "/v1/me/cosmetics") => Response::json(200, json!({ "equipped": {}, "emotes": [], "stickers": ["ghost_boo"] })),
            ("GET", "/v1/stickers/ghost_boo.png") | ("GET", "/v1/stickers/grave_riplag.png") => Response::png(real_png(96, 96)),
            ("GET", "/v1/badges/halloween2026.png") => Response::png(real_png(72, 64)),
            _ => Response::error(404, "not_found"),
        }
    })
    .await
}

#[tokio::test]
async fn hunt_trick_effects_and_stickers() {
    let server = server(true).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let state = launcher.halloween_state().await.unwrap();
    assert!(state.active);
    assert_eq!(state.pumpkins.found, ["web-home"]);
    assert_eq!(state.badges, ["halloween2026"]);

    let found = launcher.halloween_find_pumpkin("launcher-titlebar").await.unwrap();
    assert_eq!((found.count, found.complete), (2, false));
    assert_eq!(launcher.halloween_find_pumpkin("nowhere").await.unwrap_err().message_code(), "trsApi.pumpkin_unknown");
    assert!(server.hits("POST", "/v1/me/halloween/pumpkins/nowhere").is_empty(), "unbekannte Kürbisse gehen nicht raus");

    assert_eq!(launcher.halloween_trick().await.unwrap_err().message_code(), "trsApi.trick_used");

    launcher.halloween_set_effects(halloween::SpookyEffects { hits: false, trail: true }).await.unwrap();
    assert_eq!(server.hits("PUT", "/v1/me/halloween/effects")[0].json(), json!({ "hits": false, "trail": true }));

    launcher.halloween_choose_wings("bat_wings").await.unwrap();
    assert_eq!(launcher.halloween_choose_wings("demon_wings").await.unwrap_err().message_code(), "trsApi.wings_chosen");
    assert!(launcher.halloween_choose_wings("../x").await.is_err());
    launcher.trs_set_wings(Some("bat_wings".into())).await.unwrap();
    launcher.trs_set_wings(None).await.unwrap();
    let puts = server.hits("PUT", "/v1/me/cosmetics");
    assert_eq!((puts[0].json()["wings"].clone(), puts[1].json()["wings"].clone()), (json!("bat_wings"), json!(null)));

    let stickers = launcher.halloween_stickers().await.unwrap();
    assert_eq!(stickers.len(), 2, "kaputte IDs fallen weg");
    assert!(stickers[0].owned && !stickers[1].owned);
    assert_eq!(stickers[0].name.es, "¡Bu!");
    assert!(stickers.iter().all(|s| s.image.as_deref().is_some_and(|i| i.starts_with("data:image/png;base64,"))));
    assert!(server.hits("GET", "/v1/stickers/grave_riplag.png").len() == 1, "Bilder nur von der eigenen API");

    let badges = launcher.trs_badge_images(&["halloween2026".into(), "pumpkinhunter2026".into(), "<b>".into()]).await.unwrap();
    assert_eq!(badges.len(), 1);
    assert_eq!(badges[0].id, "halloween2026");
    let sent = &server.hits("GET", "/v1/badges/halloween2026.png")[0];
    assert!(sent.bearer().is_none(), "öffentliche Bilder ohne Token");
}

#[tokio::test]
async fn older_api_without_halloween_means_nothing_going_on() {
    let server = server(false).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let state = launcher.halloween_state().await.unwrap();
    assert!(!state.active && state.pumpkins.found.is_empty() && state.badges.is_empty());
}
