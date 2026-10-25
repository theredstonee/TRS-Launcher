//! Creator-Haustiere gegen eine nachgebaute API (Form wie §35.13): Stand mit
//! Kartenbild, Codes erstellen/auflisten/widerrufen (auch als Helfer), Fehler,
//! Wunsch mit Referenzbild und Zurückziehen.

use std::sync::atomic::AtomicUsize;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde_json::json;

use super::creator_pet::{NewPetCodes, NewPetRequest};
use super::png::tests::real_png;
use super::testkit::{MockServer, Response};
use super::tests::{ACC, auth_routes, launcher};

const CREATOR: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

fn quota(used: u64) -> serde_json::Value {
    json!({ "month": "2026-10", "limit": 200, "used": used, "remaining": 200 - used, "resetsAt": "2026-11-01T00:00:00.000Z" })
}

fn wish(id: u64, image: bool) -> serde_json::Value {
    json!({ "id": id, "status": "open", "species": "Fuchs", "description": "Mit Schal", "colors": "Orange",
        "image": if image { json!({ "url": "/v1/creator-pet-images/ab.png", "mime": "image/png", "width": 16, "height": 16 }) } else { json!(null) },
        "reply": null, "cosmeticId": null, "createdAt": "2026-10-25T00:00:00.000Z", "updatedAt": "2026-10-25T00:00:00.000Z", "decidedAt": null })
}

async fn server() -> MockServer {
    let issued = AtomicUsize::new(0);
    MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        let path = req.path.split('?').next().unwrap_or_default();
        let helper = req.path.ends_with(&format!("?as={CREATOR}"));
        match (req.method.as_str(), path) {
            ("GET", "/v1/creator/pet") if helper => Response::json(200, json!({
                "pet": null, "quota": quota(0), "codes": null, "requests": [], "canRequest": false })),
            ("GET", "/v1/creator/pet") => Response::json(200, json!({
                "pet": { "cosmeticId": "penguin", "name": "Pinguin", "card": "/v1/cosmetics/penguin/card.png?v=ab12", "count": 2, "available": true },
                "quota": quota(190), "codes": [], "requests": [], "canRequest": false })),
            ("GET", "/v1/cosmetics/penguin_emperor/card.png") => Response::png(real_png(64, 80)),
            ("GET", "/v1/cosmetics/penguin/card.png") => {
                if req.bearer().is_some() { Response::png(real_png(64, 80)) } else { Response::error(404, "not_found") }
            }
            ("GET", "/v1/creator/pet/codes") => Response::json(200, json!({ "codes": [
                { "id": 1, "hint": "C4HJ", "maxUses": 10, "uses": 4, "revokedAt": null, "expiresAt": null, "note": null, "createdAt": "2026-10-20T00:00:00.000Z" }
            ], "quota": quota(190) })),
            ("POST", "/v1/creator/pet/codes") if helper => Response::error(409, "creator_pet_missing"),
            ("POST", "/v1/creator/pet/codes") => {
                let body = req.json();
                if body["count"].as_u64().unwrap_or(0) * body["maxUses"].as_u64().unwrap_or(0) > 10 {
                    return Response::error(403, "creator_pet_limit");
                }
                Response::json(201, json!({ "codes": [
                    { "id": 2, "code": "7K3QF-M2XPA-9RTVB-C4HJN", "hint": "C4HJ", "maxUses": body["maxUses"], "uses": 0, "expiresAt": null, "revokedAt": null,
                      "note": body["note"], "createdAt": "2026-10-25T00:00:00.000Z" }
                ], "quota": quota(200) }))
            }
            ("DELETE", "/v1/creator/pet/codes/2") => Response::json(200, json!({ "codes": [], "quota": quota(190) })),
            ("POST", "/v1/creator/pet/requests") => Response::json(201, json!({ "requests": [wish(7, false), { "id": 3, "status": "declined" }] })),
            ("PUT", "/v1/creator/pet/requests/7/image") => Response::json(200, json!({ "request": wish(7, true) })),
            ("PUT", "/v1/creator/pet/requests/8/image") => Response::error(409, "request_closed"),
            ("DELETE", "/v1/creator/pet/requests/7") => Response::empty(204),
            _ => Response::error(404, "not_found"),
        }
    })
    .await
}

#[tokio::test]
async fn pet_state_loads_with_card_image() {
    let server = server().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let state = launcher.creator_pet(None).await.unwrap();
    let pet = state.pet.as_ref().unwrap();
    assert_eq!((pet.cosmetic_id.as_str(), pet.count), ("penguin", Some(2)));
    assert!(pet.card.as_deref().unwrap().starts_with("data:image/png;base64,"), "Karte mit Token geladen");
    assert_eq!(state.limit.as_ref().unwrap().remaining(), 10);
    let json = serde_json::to_value(&state).unwrap();
    assert!(json["pet"].get("cardUrl").is_none(), "die Server-Adresse geht nicht ans Webview");

    // Helfer: ?as=<uuid>, hier ohne Haustier.
    let other = launcher.creator_pet(Some(CREATOR)).await.unwrap();
    assert!(other.pet.is_none() && other.request.is_none());
    assert_eq!(other.can_request, Some(false));
    assert!(launcher.creator_pet(Some("kaputt")).await.is_err());
}

#[tokio::test]
async fn pet_codes_create_list_revoke_and_errors() {
    let server = server().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let listed = launcher.creator_pet_codes(None).await.unwrap();
    assert_eq!((listed[0].code.as_deref(), listed[0].code_hint.as_deref()), (None, Some("C4HJ")));

    let new = NewPetCodes { count: 2, max_uses: 5, expires_at: None, note: Some("Stream".into()) };
    let created = launcher.creator_create_pet_codes(&new, None).await.unwrap();
    assert_eq!(created[0].code.as_deref(), Some("7K3QF-M2XPA-9RTVB-C4HJN"));
    assert_eq!(server.hits("POST", "/v1/creator/pet/codes")[0].json(), json!({ "count": 2, "maxUses": 5, "note": "Stream" }));

    let over = NewPetCodes { count: 3, max_uses: 5, expires_at: None, note: None };
    let err = launcher.creator_create_pet_codes(&over, None).await.unwrap_err();
    assert_eq!(err.message_code(), "trsApi.limit_reached");
    let err = launcher.creator_create_pet_codes(&new, Some(CREATOR)).await.unwrap_err();
    assert_eq!(err.message_code(), "trsApi.creator_no_pet");

    // Ungültige Eingaben gehen gar nicht erst raus.
    let before = server.hits("POST", "/v1/creator/pet/codes").len();
    let bad = NewPetCodes { count: 50, max_uses: 1, expires_at: None, note: None };
    assert_eq!(launcher.creator_create_pet_codes(&bad, None).await.unwrap_err().message_code(), "creatorPet.countRange");
    assert_eq!(server.hits("POST", "/v1/creator/pet/codes").len(), before);

    launcher.creator_revoke_pet_code("2", None).await.unwrap();
    assert!(launcher.creator_revoke_pet_code("../2", None).await.is_err());
    assert_eq!(server.hits("DELETE", "/v1/creator/pet/codes/2").len(), 1);
}

#[tokio::test]
async fn pet_request_sends_fields_then_the_checked_image() {
    let server = server().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let image = real_png(16, 16);
    let request = NewPetRequest {
        species: "Fuchs".into(),
        description: "Mit Schal".into(),
        colors: Some("Orange".into()),
        image: Some(format!("data:image/png;base64,{}", STANDARD.encode(&image))),
    };
    let created = launcher.creator_request_pet(&request, None).await.unwrap();
    assert_eq!((created.id.as_str(), created.status.as_str(), created.has_image), ("7", "open", true));
    assert_eq!(server.hits("POST", "/v1/creator/pet/requests")[0].json(), json!({ "species": "Fuchs", "description": "Mit Schal", "colors": "Orange" }));
    let upload = &server.hits("PUT", "/v1/creator/pet/requests/7/image")[0];
    assert_eq!(upload.header("content-type"), Some("image/png"));

    // Ohne Bild: kein Upload.
    let plain = NewPetRequest { image: None, ..request.clone() };
    assert!(!launcher.creator_request_pet(&plain, None).await.unwrap().has_image);
    assert_eq!(server.hits("PUT", "/v1/creator/pet/requests/7/image").len(), 1);

    // GIF wird vor dem Senden abgelehnt.
    let gif = NewPetRequest { image: Some(STANDARD.encode(b"GIF89a")), ..request.clone() };
    assert_eq!(launcher.creator_request_pet(&gif, None).await.unwrap_err().message_code(), "creatorPet.invalidImage");
    assert_eq!(server.hits("POST", "/v1/creator/pet/requests").len(), 2);

    // Bild später anhängen, Wunsch in Arbeit → Fehler; zurückziehen.
    let jpg = STANDARD.encode([0xFF, 0xD8, 0xFF, 0xE0, 0, 1]);
    let err = launcher.creator_pet_request_image("8", &jpg, None).await.unwrap_err();
    assert_eq!(err.message_code(), "trsApi.request_closed");
    assert_eq!(server.hits("PUT", "/v1/creator/pet/requests/8/image")[0].header("content-type"), Some("image/jpeg"));
    launcher.creator_withdraw_pet_request("7", None).await.unwrap();
    assert_eq!(server.hits("DELETE", "/v1/creator/pet/requests/7").len(), 1);
}

#[tokio::test]
async fn extras_switch_puts_show_and_reloads_the_state_with_cards() {
    use std::sync::atomic::{AtomicBool, Ordering};
    let shown = std::sync::Arc::new(AtomicBool::new(true));
    let flag = std::sync::Arc::clone(&shown);
    let issued = AtomicUsize::new(0);
    let server = MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        match (req.method.as_str(), req.path.split('?').next().unwrap_or_default()) {
            ("PUT", "/v1/creator/pet/extras") => {
                flag.store(req.json()["show"].as_bool().unwrap(), Ordering::SeqCst);
                Response::json(200, json!({ "extras": [], "showExtras": flag.load(Ordering::SeqCst), "max": 4 }))
            }
            ("GET", "/v1/creator/pet") => Response::json(200, json!({
                "pet": { "cosmeticId": "penguin", "name": "Pinguin", "count": 1 },
                "extras": [{ "cosmeticId": "penguin_emperor", "name": "Kaiserpinguin", "card": "/v1/cosmetics/penguin_emperor/card.png?v=e1",
                             "petScale": 1.25, "scale": 8, "available": true }],
                "showExtras": flag.load(Ordering::SeqCst), "quota": quota(0) })),
            ("GET", "/v1/cosmetics/penguin_emperor/card.png") => Response::png(real_png(64, 80)),
            _ => Response::error(404, "not_found"),
        }
    })
    .await;
    let (_dir, api) = launcher(&server, &[ACC]).await;

    let state = api.creator_set_pet_extras(false, None).await.unwrap();
    assert!(!state.show_extras);
    let extra = &state.extras[0];
    assert_eq!((extra.cosmetic_id.as_str(), extra.name.as_str(), extra.pet_scale, extra.available), ("penguin_emperor", "Kaiserpinguin", 1.25, true));
    assert!(extra.card.as_deref().unwrap().starts_with("data:image/png;base64,"));
    assert_eq!(server.hits("PUT", "/v1/creator/pet/extras")[0].json(), json!({ "show": false }));
    assert!(api.creator_set_pet_extras(true, None).await.unwrap().show_extras);
    let json = serde_json::to_value(&state).unwrap();
    assert_eq!((json["showExtras"].clone(), json["extras"][0]["petScale"].clone()), (json!(false), json!(1.25)));

    // Ohne Gefolge-Felder (ältere API) gilt der Schalter als an und die Liste ist leer.
    let plain = self::server().await;
    let (_d2, old_launcher) = launcher(&plain, &[ACC]).await;
    let old = old_launcher.creator_pet(None).await.unwrap();
    assert!(old.extras.is_empty() && old.show_extras);
}
