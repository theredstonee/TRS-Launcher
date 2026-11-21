//! Partner-Programm (§44) gegen eine nachgebaute API: gemeinsames Code-Feld mit
//! Rückfall auf die alten Routen, eigener Partner mit Logo, Beenden.

use std::sync::atomic::AtomicUsize;

use serde_json::json;

use super::partner::RedeemOutcome;
use super::png::tests::real_png;
use super::testkit::{MockServer, Response};
use super::tests::{ACC, auth_routes, launcher};

const LOGO: &str = "/v1/partners/craftinghosting/logo.png";

fn card() -> serde_json::Value {
    json!({ "slug": "craftinghosting", "name": "CraftingHosting", "category": "hosting", "logo": format!("{LOGO}?v=abc123"),
        "text": { "de": "Kostenlose Server", "en": "Free servers" }, "links": [], "code": "CRAFTING", "server": null,
        "wikiUrl": "https://trs-launcher.wiki/partners/craftinghosting", "supporters": 12, "cosmetics": [] })
}

fn gold() -> serde_json::Value {
    json!({ "id": "trs_gold", "name": "Gold", "kind": "builtin", "unlock": "code", "status": "approved", "url": "/v1/capes/trs_gold.png", "width": 64, "height": 32, "scale": 1 })
}

fn creator_card() -> serde_json::Value {
    json!({ "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Pixelmaus", "tier": "starter", "channels": [], "symbol": null, "fanCape": null, "milestone": null })
}

async fn server(unified: bool) -> MockServer {
    let issued = AtomicUsize::new(0);
    MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        let path = req.path.split('?').next().unwrap_or_default();
        match (req.method.as_str(), path) {
            ("POST", "/v1/me/codes/redeem") if unified => match req.json()["code"].as_str() {
                Some("CRAFTING") => Response::json(200, json!({ "kind": "partner", "partner": card(), "supportChanged": false,
                    "switchAvailableAt": "2026-12-20T10:00:00.000Z", "granted": { "capes": [{ "capeId": "partner_ch_cube", "name": "Würfel" }], "cosmetics": [] }, "oneTime": false })),
                Some("PIXELMAUS") => Response::json(200, json!({ "kind": "creator", "creator": creator_card(), "supportChanged": true, "switchAvailableAt": null, "fanCapeGranted": false })),
                Some("7K3QF-M2XPA-9RTVB-C4HJN") => Response::json(200, json!({ "kind": "cape", "cape": gold(), "alreadyOwned": false })),
                Some("CH-2222-3333") => Response::error(410, "partner_code_used"),
                Some("FUTURE") => Response::json(200, json!({ "kind": "badge" })),
                _ => Response::error(404, "code_not_found"),
            },
            ("POST", "/v1/me/creator/redeem") => {
                Response::json(200, json!({ "creator": creator_card(), "supportChanged": true, "switchAvailableAt": null, "fanCapeGranted": false }))
            }
            ("POST", "/v1/capes/redeem") => Response::json(200, json!({ "cape": gold(), "alreadyOwned": true })),
            ("GET", "/v1/me/partner") => Response::json(200, json!({
                "support": { "partner": card(), "startedAt": "2026-11-20T10:00:00.000Z", "switchAvailableAt": "2026-12-20T10:00:00.000Z" },
                "owned": [{ "kind": "cape", "id": "partner_ch_cube", "name": "Würfel", "partner": { "slug": "craftinghosting", "name": "CraftingHosting" } }],
                "member": [{ "slug": "craftinghosting", "name": "CraftingHosting", "rights": ["stats"], "owner": false }],
                "invites": [{ "slug": "other-host", "name": "Other", "rights": ["stats"], "createdAt": "2026-11-21T10:00:00.000Z" }] })),
            ("GET", LOGO) => Response::png(real_png(128, 128)),
            ("DELETE", "/v1/me/partner/support") => Response::empty(204),
            _ => Response::error(404, "not_found"),
        }
    })
    .await
}

#[tokio::test]
async fn unified_redeem() {
    let server = server(true).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let RedeemOutcome::Partner(p) = launcher.trs_redeem_any(" crafting ").await.unwrap() else { panic!("partner erwartet") };
    assert!(!p.support_changed);
    assert_eq!(p.switch_available_at.as_deref(), Some("2026-12-20T10:00:00.000Z"));
    assert_eq!(p.granted.capes[0].name, "Würfel");
    assert!(p.partner.logo.as_deref().is_some_and(|l| l.starts_with("data:image/png;base64,")));
    assert!(server.hits("GET", LOGO)[0].bearer().is_none(), "Logo ist öffentlich");

    assert!(matches!(launcher.trs_redeem_any("pixelmaus").await.unwrap(), RedeemOutcome::Creator(c) if c.support_changed));
    let RedeemOutcome::Cape(c) = launcher.trs_redeem_any("7k3qf-m2xpa-9rtvb-c4hjn").await.unwrap() else { panic!("Umhang erwartet") };
    assert_eq!((c.kind.as_str(), c.cape_id.as_deref()), ("cape", Some("trs_gold")));

    assert_eq!(launcher.trs_redeem_any("CH-2222-3333").await.unwrap_err().message_code(), "trsApi.partner_code_used");
    assert_eq!(launcher.trs_redeem_any("NOPE1").await.unwrap_err().message_code(), "trsApi.code_not_found");
    assert_eq!(launcher.trs_redeem_any("FUTURE").await.unwrap_err().message_code(), "trs.badResponse");
    assert_eq!(launcher.trs_redeem_any("a!").await.unwrap_err().message_code(), "partner.invalidCode");
    assert!(server.hits("POST", "/v1/me/creator/redeem").is_empty() && server.hits("POST", "/v1/capes/redeem").is_empty(), "kein Rückfall");
}

#[tokio::test]
async fn older_api_uses_old_routes() {
    let server = server(false).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    assert!(matches!(launcher.trs_redeem_any("PIXELMAUS").await.unwrap(), RedeemOutcome::Creator(_)));
    assert_eq!(server.hits("POST", "/v1/me/creator/redeem")[0].json()["code"], "PIXELMAUS");
    let RedeemOutcome::Cape(c) = launcher.trs_redeem_any("7K3QF-M2XPA-9RTVB-C4HJN").await.unwrap() else { panic!("Umhang erwartet") };
    assert!(c.already_owned);
    assert_eq!(server.hits("POST", "/v1/capes/redeem")[0].json()["code"], "7K3QFM2XPA9RTVBC4HJN");
}

#[tokio::test]
async fn my_partner_and_end() {
    let server = server(true).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let me = launcher.partner_me().await.unwrap();
    let partner = me.support.partner.unwrap();
    assert_eq!(partner.name, "CraftingHosting");
    assert!(partner.logo.is_some());
    assert_eq!(me.owned[0].partner.as_ref().unwrap().name, "CraftingHosting");
    assert_eq!(me.member[0].rights, ["stats"]);

    launcher.partner_end_support().await.unwrap();
    assert_eq!(server.hits("DELETE", "/v1/me/partner/support").len(), 1);
}
