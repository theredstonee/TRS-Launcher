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
    assert_eq!(full.unlock_kind(), Some(CosmeticUnlock::Code));

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
    assert_eq!(other.unlock_kind(), Some(CosmeticUnlock::Other));
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
    let mut after: Vec<_> = server.requests()[before..].iter().map(|r| r.path.clone()).collect();
    after.sort();
    // Nur Katalog + Getragenes (Optionen/Anzahl) werden neu geholt; `me/cosmetics` darf fehlen (404 → ohne Extras).
    assert_eq!(after, vec!["/v1/cosmetics".to_owned(), "/v1/me/cosmetics".to_owned()], "nur der Katalog wird neu geholt");
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

#[test]
fn event_unlock_and_companion_slot() {
    let hat = entry(json!({ "id": "witch_hat", "slot": "hat", "format": 2, "unlock": { "type": "event", "event": "halloween" } }));
    assert_eq!(hat.unlock_kind(), Some(CosmeticUnlock::Event));
    assert_eq!(hat.unlock_event().as_deref(), Some("halloween"));
    let bad = entry(json!({ "id": "x", "slot": "hat", "unlock": { "type": "event", "event": "../x" } }));
    assert_eq!(bad.unlock_event(), None);
    assert_eq!(entry(json!({ "id": "x", "slot": "hat", "unlock": "free" })).unlock_event(), None);
    let bat = entry(json!({ "id": "bat_buddy", "slot": "companion", "format": 2 }));
    assert!(wearable_hat(&bat) && listed(&bat));
    assert!(!wearable_hat(&entry(json!({ "id": "bat", "slot": "companion", "template": "duck" }))), "Begleiter nur als v2");
}

#[test]
fn companion_models_pass_the_check() {
    let mut m = model("bat_buddy", Some(12));
    m["slot"] = json!("companion");
    assert!(check_model(&m, "bat_buddy").is_some());
    m["slot"] = json!("back");
    assert!(check_model(&m, "bat_buddy").is_none());
}

#[test]
fn wings_hang_on_the_body() {
    let mut m = model("bat_wings", Some(12));
    m["slot"] = json!("wings");
    assert!(check_model(&m, "bat_wings").is_none(), "Flügel brauchen attach body");
    m["attach"] = json!("body");
    assert!(check_model(&m, "bat_wings").is_some());
    m["slot"] = json!("hat");
    assert!(check_model(&m, "bat_wings").is_none(), "Hüte bleiben am Kopf");
    let wings = entry(json!({ "id": "bat_wings", "slot": "wings", "format": 2, "unlock": { "type": "reward", "rewardOf": "pumpkinhunter2026" } }));
    assert!(wearable_hat(&wings) && listed(&wings));
    assert_eq!(wings.unlock_kind(), Some(CosmeticUnlock::Reward));
    assert!(!wearable_hat(&entry(json!({ "id": "w", "slot": "wings", "template": "duck" }))), "Flügel nur als v2");
}

// --- Winter 2026 (Anhang D): Begleiter am Boden, Optionen, Anzahl -------------------------------

#[test]
fn pets_stand_on_the_ground() {
    let mut m = model("test_pet", None);
    m["slot"] = json!("pet");
    assert!(check_model(&m, "test_pet").is_none(), "Begleiter am Boden brauchen attach ground");
    m["attach"] = json!("ground");
    assert!(check_model(&m, "test_pet").is_some());
    m["slot"] = json!("companion");
    assert!(check_model(&m, "test_pet").is_none(), "Begleiter am Kopf bleiben am Kopf");
    let pet = entry(json!({ "id": "test_pet", "slot": "pet", "format": 2, "unlock": "code" }));
    assert!(wearable_hat(&pet) && listed(&pet));
    assert!(!wearable_hat(&entry(json!({ "id": "p", "slot": "pet", "template": "duck" }))), "Begleiter am Boden nur als v2");
}

#[test]
fn model_options_are_checked() {
    let with = |options: serde_json::Value| {
        let mut m = model("test_cap", None);
        m["options"] = options;
        check_model(&m, "test_cap")
    };
    assert!(with(json!([{ "id": "beard", "bones": ["root"], "default": true, "name": { "de": "Bart" } }])).is_some());
    assert!(with(json!([])).is_some());
    assert!(with(json!([{ "id": "Beard", "bones": ["root"], "default": true }])).is_none());
    assert!(with(json!([{ "id": "beard", "bones": [], "default": true }])).is_none());
    assert!(with(json!([{ "id": "beard", "bones": ["root"], "default": "ja" }])).is_none());
    assert!(with(json!([{ "id": "a", "bones": ["root"], "default": true }, { "id": "a", "bones": ["root"], "default": false }])).is_none());
    let five: Vec<_> = ["a", "b", "c", "d", "e"].iter().map(|id| json!({ "id": id, "bones": ["root"], "default": true })).collect();
    assert!(with(json!(five)).is_none());
    assert!(with(json!({ "beard": true })).is_none());
}

#[test]
fn worn_extras_read_options_and_count() {
    let me = json!({ "equipped": {
        "hat": { "id": "test_cap", "options": { "beard": false, "Bad-Key": true, "glow": "ja" } },
        "pet": { "id": "test_pet", "count": 3, "source": "creator" },
        "wings": null,
        "companion": { "id": "../x" }
    } });
    let worn = worn_extras(&me);
    assert_eq!(worn.len(), 2);
    assert_eq!(worn["test_cap"].options, [("beard".to_owned(), false)].into_iter().collect());
    assert_eq!(worn["test_cap"].count, None, "Anzahl nur beim Begleiter am Boden");
    assert_eq!((worn["test_pet"].count, worn["test_pet"].creator), (Some(3), true));
    // Rückfall: oberstes `options.<slot>`, ungültige Anzahl, Code-Pinguin.
    let me = json!({ "equipped": { "hat": { "id": "test_cap" }, "pet": { "id": "test_pet", "count": 7 } }, "options": { "hat": { "beard": false } } });
    let worn = worn_extras(&me);
    assert_eq!(worn["test_cap"].options.get("beard"), Some(&false));
    assert_eq!((worn["test_pet"].count, worn["test_pet"].creator), (None, false));
    let me = json!({ "equipped": { "pet": { "id": "test_pet", "count": 2 } } });
    assert!(worn_extras(&me)["test_pet"].creator, "mehr als einer = Creator-Stufe");
    assert!(worn_extras(&json!({ "emotes": [] })).is_empty());
}

#[test]
fn equip_bodies_carry_slot_and_options() {
    use crate::trs_api::ops::equip_body;
    let beard_off: BTreeMap<String, bool> = [("beard".to_owned(), false)].into_iter().collect();
    assert_eq!(equip_body("pet", Some("test_pet"), None).unwrap(), json!({ "pet": "test_pet" }));
    assert_eq!(equip_body("pet", None, None).unwrap(), json!({ "pet": null }));
    assert_eq!(
        equip_body("hat", Some("test_cap"), Some(&beard_off)).unwrap(),
        json!({ "hat": "test_cap", "options": { "hat": { "beard": false } } })
    );
    assert_eq!(equip_body("hat", None, Some(&beard_off)).unwrap(), json!({ "hat": null }), "Optionen nur mit Teil");
    assert_eq!(equip_body("hat", Some("test_cap"), Some(&BTreeMap::new())).unwrap(), json!({ "hat": "test_cap" }));
    assert!(equip_body("back", Some("x"), None).is_err());
    assert!(equip_body("pet", Some("../x"), None).is_err());
    let bad: BTreeMap<String, bool> = [("Bad-Key".to_owned(), true)].into_iter().collect();
    assert!(equip_body("hat", Some("test_cap"), Some(&bad)).is_err());
    let many: BTreeMap<String, bool> = ["a", "b", "c", "d", "e"].iter().map(|k| ((*k).to_owned(), true)).collect();
    assert!(equip_body("hat", Some("test_cap"), Some(&many)).is_err());
}

/// API mit Winter-Teilen (synthetisch): Testmütze mit Option, Test-Tier am Boden (Creator-Stufe 2).
fn winter_api() -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        match (req.method.as_str(), req.path.split('?').next().unwrap_or_default()) {
            ("GET", "/v1/cosmetics") => Response::json(
                200,
                json!({ "cosmetics": [
                    { "id": "test_cap", "name": "Testmütze", "slot": "hat", "format": 2, "unlock": "code", "owned": true, "equipped": true,
                      "model": "/v1/cosmetics/test_cap/model.json?v=w1", "texture": "/v1/cosmetics/test_cap.png?v=w1", "hash": "w1" },
                    { "id": "test_pet", "name": "Testtier", "slot": "pet", "format": 2, "unlock": "code", "owned": true, "equipped": true,
                      "model": "/v1/cosmetics/test_pet/model.json?v=w2", "texture": "/v1/cosmetics/test_pet.png?v=w2", "hash": "w2" },
                    { "id": "test_pet2", "name": "Testtier 2", "slot": "pet", "format": 2, "unlock": "code", "owned": false, "equipped": false,
                      "count": 4, "model": "/v1/cosmetics/test_pet2/model.json?v=w3", "texture": "/v1/cosmetics/test_pet2.png?v=w3", "hash": "w3" }
                ] }),
            ),
            ("GET", "/v1/me/cosmetics") => Response::json(
                200,
                json!({ "equipped": {
                    "hat": { "id": "test_cap", "options": { "beard": false } },
                    "pet": { "id": "test_pet", "count": 2, "source": "creator" },
                    "wings": null, "companion": null
                }, "emotes": [] }),
            ),
            ("PUT", "/v1/me/cosmetics") => Response::json(200, json!({ "equipped": {} })),
            ("POST", "/v1/capes/redeem") => Response::json(
                200,
                json!({ "cape": null, "alreadyOwned": false, "cosmetic": { "id": "test_pet", "name": "Testtier", "slot": "pet", "format": 2 } }),
            ),
            _ => Response::error(404, "not_found"),
        }
    }
}

#[tokio::test]
async fn pets_with_count_and_options_end_to_end() {
    let server = MockServer::start(winter_api()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let list = launcher.trs_head_cosmetics().await.unwrap();
    let by = |id: &str| list.iter().find(|c| c.id == id).unwrap();
    assert_eq!(list.len(), 3);
    assert_eq!((by("test_cap").options.get("beard"), by("test_cap").count), (Some(&false), None));
    let pet = by("test_pet");
    assert_eq!((pet.slot.as_str(), pet.count, pet.creator, pet.equipped), ("pet", Some(2), true, true));
    assert_eq!(by("test_pet2").count, None, "Anzahl nur am getragenen Teil");

    // Setzen/Leeren + Optionen.
    launcher.trs_set_pet(Some("test_pet".into())).await.unwrap();
    launcher.trs_set_pet(None).await.unwrap();
    let beard_on = [("beard".to_owned(), true)].into_iter().collect();
    launcher.trs_equip_cosmetic("hat", Some("test_cap".into()), Some(beard_on)).await.unwrap();
    assert!(launcher.trs_equip_cosmetic("aura", Some("test_cap".into()), None).await.is_err());
    let puts: Vec<_> = server.hits("PUT", "/v1/me/cosmetics").iter().map(Request::json).collect();
    assert_eq!(
        puts,
        vec![json!({ "pet": "test_pet" }), json!({ "pet": null }), json!({ "hat": "test_cap", "options": { "hat": { "beard": true } } })]
    );

    // Einlösen erkennt den Begleiter am Boden samt Fach.
    let redeemed = launcher.trs_redeem("7K3QF-M2XPA-9RTVB-C4HJN").await.unwrap();
    assert!(redeemed.wearable_hat);
    assert_eq!(redeemed.slot.as_deref(), Some("pet"));
}

#[tokio::test]
async fn older_api_without_me_cosmetics_still_lists() {
    let issued = AtomicUsize::new(0);
    let server = MockServer::start(move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        match (req.method.as_str(), req.path.split('?').next().unwrap_or_default()) {
            ("GET", "/v1/cosmetics") => Response::json(
                200,
                json!({ "cosmetics": [{ "id": "test_pet", "name": "Testtier", "slot": "pet", "format": 2, "owned": true, "equipped": true,
                    "count": 3, "options": { "beard": false } }] }),
            ),
            _ => Response::error(404, "not_found"),
        }
    })
    .await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let list = launcher.trs_head_cosmetics().await.unwrap();
    // Rückfall auf die Angaben am Katalog-Eintrag.
    assert_eq!((list[0].count, list[0].options.get("beard")), (Some(3), Some(&false)));
}

#[test]
fn worn_extras_read_scale_and_escort() {
    let me = json!({ "equipped": { "pet": { "id": "penguin", "count": 1, "scale": 1.25, "extras": [
        { "id": "penguin_beanie", "scale": 1 }, "kaputt", { "id": "penguin_shades" }, { "id": "a" }, { "id": "b" }, { "id": "c" }
    ] }, "hat": { "id": "test_cap", "scale": 3, "extras": [{ "id": "x" }] } } });
    let worn = worn_extras(&me);
    assert_eq!(worn["penguin"].scale, Some(1.25));
    assert_eq!(worn["penguin"].extras.len(), 4, "höchstens 4 Einträge");
    assert_eq!((worn["test_cap"].scale, worn["test_cap"].extras.len()), (None, 0), "Gefolge und Größe nur beim Begleiter am Boden");
    let bad = worn_extras(&json!({ "equipped": { "pet": { "id": "penguin", "scale": 99 } } }));
    assert_eq!(bad["penguin"].scale, None, "Größe nur von 0,25 bis 3");
}

#[tokio::test]
async fn pet_escort_models_load_even_when_not_in_the_catalog() {
    let base = Arc::new(OnceLock::new());
    let base2 = Arc::clone(&base);
    let issued = AtomicUsize::new(0);
    let server = MockServer::start(move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        let host: &String = base2.get().unwrap();
        match (req.method.as_str(), req.path.split('?').next().unwrap_or_default()) {
            ("GET", "/v1/cosmetics") => Response::json(
                200,
                json!({ "cosmetics": [{ "id": "penguin", "name": "Pinguin", "slot": "pet", "format": 2, "owned": true, "equipped": true,
                    "model": "/v1/cosmetics/penguin/model.json?v=p1", "texture": "/v1/cosmetics/penguin.png?v=p1", "hash": "p1" }] }),
            ),
            ("GET", "/v1/me/cosmetics") => Response::json(
                200,
                json!({ "equipped": { "pet": { "id": "penguin", "count": 1, "extras": [
                    { "id": "penguin_emperor", "name": "Kaiserpinguin", "scale": 1.25, "hash": "e1",
                      "model": format!("{host}/v1/cosmetics/penguin_emperor/model.json?v=e1"), "url": format!("{host}/v1/cosmetics/penguin_emperor.png?v=e1") },
                    { "id": "evil", "name": "Fremd", "model": "https://evil.example/v1/cosmetics/evil/model.json", "url": "https://evil.example/v1/cosmetics/evil.png" }
                ] } } }),
            ),
            ("GET", "/v1/cosmetics/penguin_emperor/model.json") => Response::json(200, model("penguin_emperor", None)),
            ("GET", "/v1/cosmetics/penguin_emperor.png") => Response::png(real_png(32, 16)),
            _ => Response::error(404, "not_found"),
        }
    })
    .await;
    base.set(server.base.clone()).unwrap();
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let list = launcher.trs_head_cosmetics().await.unwrap();
    assert_eq!(list.len(), 1, "Gefolge steht nicht im Katalog");
    let pet = &list[0];
    assert_eq!(pet.scale, 1.0);
    assert_eq!(pet.extras.len(), 2);
    let emperor = &pet.extras[0];
    assert_eq!((emperor.id.as_str(), emperor.name.as_str(), emperor.scale, emperor.preview), ("penguin_emperor", "Kaiserpinguin", 1.25, true));
    assert!(!pet.extras[1].preview, "fremde Adressen bekommen keine Vorschau");
    // Das Modell des Gefolges lässt sich laden – auch nach erneutem Laden der Liste.
    let loaded = launcher.trs_head_cosmetic_model("penguin_emperor").await.unwrap();
    assert_eq!(loaded.hash, "e1");
    launcher.trs_head_cosmetics().await.unwrap();
    assert!(launcher.trs_head_cosmetic_model("penguin_emperor").await.is_ok());
    assert!(launcher.trs_head_cosmetic_model("evil").await.is_err());
}

#[test]
fn models_may_have_twelve_animations() {
    let mut m = model("crown", None);
    m["animations"] = serde_json::Value::Array((0..12).map(|i| json!({ "id": format!("a{i}"), "driver": "grab", "lengthMs": 100, "tracks": [] })).collect());
    assert!(check_model(&m, "crown").is_some());
    m["animations"].as_array_mut().unwrap().push(json!({ "id": "x" }));
    assert!(check_model(&m, "crown").is_none(), "13 sind zu viele");
}
