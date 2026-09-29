//! Kopf-Kosmetik v2 gegen eine nachgebaute API.

use std::sync::atomic::AtomicUsize;
use std::sync::{Arc, OnceLock};

use serde_json::json;

use super::*;
use crate::trs_api::png::tests::real_png;
use crate::trs_api::testkit::{MockServer, Request, Response};
use crate::trs_api::tests::{ACC, auth_routes, launcher};
use crate::trs_api::types::ApiCosmeticRef;

const BASES: [&str; 2] = crate::trs_api::KNOWN_BASES;
const HOST: &str = "https://trs-launcher.theredstonee.de";

fn model(id: &str, glow_frames: Option<u32>) -> serde_json::Value {
    let mut m = json!({
        "format": 2, "id": id, "name": "Krone", "slot": "hat", "attach": "head",
        "texture": { "file": "texture.png", "width": 16, "height": 8, "scale": 2, "frames": 1 },
        "bones": [{ "id": "root", "pivot": [0, 8, 0] }],
        "cubes": [{ "id": "band", "bone": "root", "from": [-4, 8, -4], "to": [4, 10, 4],
            "faces": { "south": { "uv": [0, 0, 8, 2] } } }],
        "animations": [], "halos": []
    });
    if let Some(frames) = glow_frames {
        m["glow"] = json!({ "file": "glow.png", "frames": frames, "frameTimeMs": 140, "blend": "additive" });
    }
    m
}

fn entry(json: serde_json::Value) -> ApiCosmeticRef {
    serde_json::from_value(json).unwrap()
}

#[test]
fn asset_urls_only_from_the_api_with_the_expected_path() {
    let ok = |raw: &str, asset| asset_url(HOST, &BASES, raw, "crown", asset);
    // API-relativ und absolut, neue und alte Adresse.
    assert_eq!(
        ok("/v1/cosmetics/crown/model.json?v=61749d72f375", Asset::Model),
        Some(AssetUrl { url: format!("{HOST}/v1/cosmetics/crown/model.json?v=61749d72f375"), version: Some("61749d72f375".into()) })
    );
    assert!(ok("https://api.theredstonee.de/v1/cosmetics/crown.png?v=abc", Asset::Texture).is_some());
    assert!(ok("/v1/cosmetics/crown/glow.png", Asset::Glow).is_some_and(|u| u.version.is_none()));
    assert!(ok("/v1/cosmetics/crown/card.png?v=1", Asset::Card).is_some());
    assert!(ok("/v1/cosmetics/crown/card-night.png?v=1", Asset::CardNight).is_some());
    // Falsche Datei, falsche ID, fremde Hosts, Tricks.
    assert_eq!(ok("/v1/cosmetics/crown/card.png", Asset::CardNight), None);
    assert_eq!(ok("/v1/cosmetics/crown.png", Asset::Model), None);
    assert_eq!(ok("/v1/cosmetics/other/model.json", Asset::Model), None);
    assert_eq!(ok("/v1/cosmetics/crown/../x/model.json", Asset::Model), None);
    assert_eq!(ok("/v1/cosmetics/crown/model.json?v=../x", Asset::Model), None);
    assert_eq!(ok("/v1/cosmetics/crown/model.json?x=1", Asset::Model), None);
    assert_eq!(ok("//evil.example/v1/cosmetics/crown/model.json", Asset::Model), None);
    assert_eq!(ok("https://evil.example/v1/cosmetics/crown/model.json", Asset::Model), None);
    assert_eq!(ok("https://trs-launcher.theredstonee.de.evil.example/v1/cosmetics/crown/model.json", Asset::Model), None);
    assert_eq!(ok("http://trs-launcher.theredstonee.de/v1/cosmetics/crown/model.json", Asset::Model), None, "nur HTTPS");
    assert_eq!(asset_url(HOST, &BASES, "/v1/cosmetics/../model.json", "..", Asset::Model), None);
}

#[test]
fn v2_entries_need_model_and_texture() {
    let full = entry(json!({
        "id": "crown", "name": "Krone", "slot": "hat", "format": 2, "unlock": "code", "owned": false,
        "model": "/v1/cosmetics/crown/model.json?v=aaa", "texture": "/v1/cosmetics/crown.png?v=aaa",
        "glow": "https://evil.example/glow.png", "card": "/v1/cosmetics/crown/card.png?v=aaa",
        "cardNight": null, "frames": 1, "glowFrames": 12, "glowFrameTimeMs": 140, "hash": "ABCDEF123456"
    }));
    let source = v2_source(HOST, &BASES, &full).unwrap();
    assert_eq!(source.hash, "abcdef123456");
    assert!(source.glow.is_none(), "fremde Glow-Adresse fällt weg");
    assert!(source.card.is_some() && source.card_night.is_none());
    assert_eq!(full.unlock, Some(CosmeticUnlock::Code));

    // Ohne Hash gilt die Version der Modell-Adresse.
    let no_hash = entry(json!({ "id": "crown", "slot": "hat", "format": 2,
        "model": "/v1/cosmetics/crown/model.json?v=v1", "texture": "/v1/cosmetics/crown.png?v=v1" }));
    assert_eq!(v2_source(HOST, &BASES, &no_hash).unwrap().hash, "v1");
    // Textur als v1-Objekt ist auch in Ordnung.
    let object = entry(json!({ "id": "crown", "slot": "hat", "format": 2, "hash": "h",
        "model": "/v1/cosmetics/crown/model.json", "texture": { "url": "/v1/cosmetics/crown.png?v=h", "width": 32 } }));
    assert!(v2_source(HOST, &BASES, &object).is_some());
    // Kein Modell oder v1 → keine 3D-Vorschau.
    let missing = entry(json!({ "id": "crown", "slot": "hat", "format": 2, "hash": "h", "texture": "/v1/cosmetics/crown.png" }));
    assert!(v2_source(HOST, &BASES, &missing).is_none());
    let v1 = entry(json!({ "id": "rubber_duck", "slot": "hat", "template": "duck", "texture": { "url": "x" } }));
    assert!(v2_source(HOST, &BASES, &v1).is_none());
    // Unbekannte Freischaltart bricht nichts.
    let other = entry(json!({ "id": "x", "slot": "hat", "unlock": "season-pass" }));
    assert_eq!(other.unlock, Some(CosmeticUnlock::Other));
}

#[test]
fn wearable_and_listed() {
    let v2 = entry(json!({ "id": "crown", "slot": "hat", "format": 2 }));
    let duck = entry(json!({ "id": "rubber_duck", "slot": "hat", "template": "duck", "hidden": true }));
    let cap_v1 = entry(json!({ "id": "cap", "slot": "hat", "template": "cap" }));
    let wings = entry(json!({ "id": "wings", "slot": "back", "format": 2 }));
    assert!(wearable_hat(&v2) && wearable_hat(&duck));
    assert!(!wearable_hat(&cap_v1) && !wearable_hat(&wings));
    assert!(listed(&v2));
    assert!(!listed(&duck), "versteckt und nicht besessen");
    assert!(listed(&entry(json!({ "id": "rubber_duck", "slot": "hat", "template": "duck", "hidden": true, "owned": true }))));
}

#[test]
fn models_are_checked() {
    let info = check_model(&model("crown", Some(12)), "crown").unwrap();
    assert_eq!(info.texture, (32, 16));
    assert_eq!(info.glow, Some((32, 16 * 12)));
    assert_eq!(check_model(&model("crown", None), "crown").unwrap().glow, None);
    assert!(check_model(&model("crown", None), "other").is_none(), "ID muss passen");
    let bad = |edit: &dyn Fn(&mut serde_json::Value)| {
        let mut m = model("crown", Some(2));
        edit(&mut m);
        check_model(&m, "crown")
    };
    assert!(bad(&|m| m["format"] = json!(1)).is_none());
    assert!(bad(&|m| m["slot"] = json!("back")).is_none());
    assert!(bad(&|m| m["texture"]["width"] = json!(12)).is_none(), "Vielfaches von 8");
    assert!(bad(&|m| m["texture"]["scale"] = json!(3)).is_none());
    assert!(bad(&|m| m["texture"]["width"] = json!(520)).is_none(), "Kante > 1024 px");
    assert!(bad(&|m| m["glow"]["frames"] = json!(17)).is_none());
    assert!(bad(&|m| m["glow"]["frameTimeMs"] = json!(null)).is_none());
    assert!(bad(&|m| m["cubes"] = json!([])).is_none());
    assert!(bad(&|m| m["cubes"] = json!(vec![json!({}); 65])).is_none());
    assert!(bad(&|m| m["halos"] = json!(vec![json!({}); 17])).is_none());
    // 16 Frames à 16 px Höhe bei Faktor 16 = 4096 px: gerade noch erlaubt.
    assert!(bad(&|m| {
        m["texture"]["scale"] = json!(16);
        m["glow"]["frames"] = json!(16);
    })
    .is_some());
    assert!(bad(&|m| {
        m["texture"]["height"] = json!(24);
        m["texture"]["scale"] = json!(16);
        m["glow"]["frames"] = json!(16);
    })
    .is_none(), "Streifen > 4096 px");
}

/// API mit einer v2-Krone (Glow 12 Frames), einem gesperrten v2-Hut, der Ente (versteckt, nicht besessen) und einer v1-Kappe.
fn api(base: Arc<OnceLock<String>>, model_json: serde_json::Value) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        let base = base.get().cloned().unwrap_or_default();
        match (req.method.as_str(), req.path.split('?').next().unwrap_or_default()) {
            ("GET", "/v1/cosmetics") => {
                if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
                    return Response::error(401, "unauthorized");
                }
                Response::json(
                    200,
                    json!({ "templates": [], "cosmetics": [
                        { "id": "crown", "name": "Krone", "slot": "hat", "format": 2, "unlock": "code", "owned": true, "equipped": true,
                          "model": "/v1/cosmetics/crown/model.json?v=h1",
                          // wie die echte API: Textur als Objekt (v1-Form), URL in `texture.url`
                          "texture": { "url": format!("{base}/v1/cosmetics/crown.png?v=h1"), "width": 32, "height": 16, "scale": 2, "frames": 1 },
                          "glow": format!("{base}/v1/cosmetics/crown/glow.png?v=h1"), "card": "/v1/cosmetics/crown/card.png?v=h1",
                          "cardNight": "/v1/cosmetics/crown/card-night.png?v=h1", "frames": 1, "frameTimeMs": null,
                          "glowFrames": 12, "glowFrameTimeMs": 140, "hash": "h1" },
                        { "id": "halo", "name": "Heiligenschein", "slot": "hat", "format": 2, "unlock": "achievement", "owned": false,
                          "model": "/v1/cosmetics/halo/model.json?v=h2", "texture": "/v1/cosmetics/halo.png?v=h2",
                          "card": "https://evil.example/v1/cosmetics/halo/card.png", "hash": "h2" },
                        { "id": "rubber_duck", "name": "Quietscheente", "slot": "hat", "template": "duck", "hidden": true, "owned": false },
                        { "id": "cap", "name": "Kappe", "slot": "hat", "template": "cap", "owned": true },
                        { "id": "wings", "name": "Flügel", "slot": "back", "format": 2, "owned": true }
                    ] }),
                )
            }
            ("GET", "/v1/cosmetics/crown/model.json") => Response::json(200, model_json.clone()),
            ("GET", "/v1/cosmetics/crown.png") => Response::png(real_png(32, 16)),
            ("GET", "/v1/cosmetics/crown/glow.png") => Response::png(real_png(32, 16 * 12)),
            ("GET", "/v1/cosmetics/crown/card.png") | ("GET", "/v1/cosmetics/crown/card-night.png") => Response::png(real_png(64, 64)),
            ("GET", "/v1/cosmetics/halo/model.json") => Response::json(200, json!({ "format": 2, "id": "falsch" })),
            ("POST", "/v1/capes/redeem") => Response::json(
                200,
                json!({ "cape": null, "alreadyOwned": false, "cosmetic": { "id": "halo", "name": "Heiligenschein", "slot": "hat",
                    "format": 2, "kind": "builtin", "unlock": "code", "model": "/v1/cosmetics/halo/model.json?v=h2" } }),
            ),
            _ => Response::error(404, "not_found"),
        }
    }
}

#[tokio::test]
async fn list_model_and_cache() {
    let base = Arc::new(OnceLock::new());
    let server = MockServer::start(api(Arc::clone(&base), model("crown", Some(12)))).await;
    base.set(server.base.clone()).unwrap();
    let (dir, launcher) = launcher(&server, &[ACC]).await;

    let list = launcher.trs_head_cosmetics().await.unwrap();
    assert_eq!(list.iter().map(|c| c.id.as_str()).collect::<Vec<_>>(), vec!["crown", "halo"], "Ente versteckt, v1-Kappe/Flügel nicht");
    let crown = &list[0];
    assert!(crown.owned && crown.equipped && crown.preview);
    assert_eq!((crown.format, crown.unlock, crown.glow_frames), (2, CosmeticUnlock::Code, 12));
    assert!(crown.card.as_deref().is_some_and(|c| c.starts_with("data:image/png;base64,")));
    assert!(crown.card_night.is_some());
    let halo = &list[1];
    assert_eq!((halo.owned, halo.unlock, halo.card.as_deref()), (false, CosmeticUnlock::Achievement, None));

    let loaded = launcher.trs_head_cosmetic_model("crown").await.unwrap();
    assert_eq!((loaded.id.as_str(), loaded.hash.as_str()), ("crown", "h1"));
    assert_eq!(loaded.model["cubes"][0]["id"], "band");
    assert!(loaded.texture.starts_with("data:image/png;base64,") && loaded.glow.is_some());
    // Kaputtes Modell → verständlicher Fehler statt Absturz.
    assert!(launcher.trs_head_cosmetic_model("halo").await.is_err());
    assert!(launcher.trs_head_cosmetic_model("../x").await.is_err());
    assert!(launcher.trs_head_cosmetic_model("unknown").await.is_err());

    // Zweites Laden kommt aus dem Cache (nach Hash), ohne neue Anfragen.
    let cache = dir.path().join("cache").join("trs-cosmetics").join("crown");
    for file in ["h1-model.json", "h1-texture.png", "h1-glow.png", "h1-card.png", "h1-card-night.png"] {
        assert!(cache.join(file).exists(), "{file} im Cache");
    }
    let before = server.requests().len();
    launcher.trs_head_cosmetic_model("crown").await.unwrap();
    launcher.trs_head_cosmetics().await.unwrap();
    let after: Vec<_> = server.requests()[before..].iter().map(|r| r.path.clone()).collect();
    assert_eq!(after, vec!["/v1/cosmetics".to_owned()], "nur der Katalog wird neu geholt");
}

#[tokio::test]
async fn texture_size_must_match_the_model() {
    let base = Arc::new(OnceLock::new());
    // Modell verlangt 64×32 – die API liefert 32×16.
    let mut wrong = model("crown", None);
    wrong["texture"]["scale"] = json!(4);
    let server = MockServer::start(api(Arc::clone(&base), wrong)).await;
    base.set(server.base.clone()).unwrap();
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    // Ohne vorher geladene Liste holt der Kern den Katalog selbst.
    assert!(launcher.trs_head_cosmetic_model("crown").await.is_err());
    assert_eq!(server.hits("GET", "/v1/cosmetics").len(), 1);
}

#[tokio::test]
async fn v2_hats_are_wearable_and_redeemable() {
    let base = Arc::new(OnceLock::new());
    let server = MockServer::start(api(Arc::clone(&base), model("crown", None))).await;
    base.set(server.base.clone()).unwrap();
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let hats = launcher.trs_hats().await.unwrap();
    assert_eq!(hats.iter().map(|h| h.id.as_str()).collect::<Vec<_>>(), vec!["crown"], "besessene v2-Hüte, keine v1-Kappe");
    // Code für ein v2-Teil: gleich aufsetzbar.
    let redeemed = launcher.trs_redeem("7K3QF-M2XPA-9RTVB-C4HJN").await.unwrap();
    assert_eq!((redeemed.kind.as_str(), redeemed.cosmetic_id.as_deref()), ("cosmetic", Some("halo")));
    assert!(redeemed.wearable_hat);
}
