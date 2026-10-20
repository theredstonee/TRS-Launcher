//! Empfohlene Modpacks gegen eine nachgebaute API: öffentliche Liste mit Cache, ohne Einwilligung
//! keine Anfrage, Team-Verwaltung (hinzufügen, ändern, Reihenfolge, neu laden, löschen) und Fehlercodes.

use std::sync::atomic::AtomicUsize;

use serde_json::{Value, json};

use super::featured::FeaturedPatch;
use super::testkit::{MockServer, Response};
use super::tests::{ACC, auth_routes, launcher};
use super::*;
use crate::featured::{FeaturedBadge, FeaturedSource};

fn pack(base: &str, id: &str, source: &str) -> Value {
    let (project, url) = match source {
        "curseforge" => (json!("925200"), "https://www.curseforge.com/minecraft/modpacks/all-the-mods-10"),
        _ => (json!("1KVo5zza"), "https://modrinth.com/modpack/fabulously-optimized"),
    };
    json!({
        "id": id, "source": source, "projectId": project, "slug": "pack", "url": url,
        "title": format!("Pack {id}"), "summary": "Summary", "author": "someone", "downloads": 1234,
        "loaders": ["fabric"], "gameVersions": ["1.21.4"],
        "iconUrl": format!("{base}/v1/site/featured-packs/{id}/icon?v=1"),
        "bannerUrl": "https://evil.example/banner.png",
        "badge": "new", "texts": { "en": { "title": null, "text": null }, "de": { "title": null, "text": null } },
        "pinned": false, "startsAt": null, "endsAt": null, "updatedAt": "2026-10-20T10:00:00.000Z",
        "active": true, "createdBy": "Theredstonee", "createdAt": "2026-10-20T10:00:00.000Z", "refreshedAt": null
    })
}

async fn server() -> MockServer {
    let issued = AtomicUsize::new(0);
    // Die Adresse kennt der Handler erst nach dem Start – Bild-URLs relativ, der Kern macht sie absolut.
    MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        let path = req.path.split('?').next().unwrap_or_default();
        match (req.method.as_str(), path) {
            ("GET", "/v1/site/featured-packs") => {
                Response::json(200, json!({ "packs": [pack("", "fp1", "modrinth"), pack("", "fp2", "curseforge"), { "id": "../bad" }] }))
            }
            ("GET", "/v1/admin/featured-packs") => Response::json(200, json!({ "packs": [pack("", "fp1", "modrinth"), pack("", "fp2", "curseforge")] })),
            ("POST", "/v1/admin/featured-packs") => match req.json()["url"].as_str() {
                Some("https://modrinth.com/modpack/fabulously-optimized") => Response::json(201, json!({ "pack": pack("", "fp3", "modrinth") })),
                Some("https://modrinth.com/mod/sodium") => Response::error(422, "not_a_modpack"),
                Some("https://www.curseforge.com/minecraft/modpacks/x") => Response::error(503, "curseforge_unavailable"),
                Some("https://modrinth.com/modpack/down") => Response::error(502, "upstream_failed"),
                _ => Response::error(409, "already_featured"),
            },
            ("PATCH", "/v1/admin/featured-packs/fp1") => {
                let mut p = pack("", "fp1", "modrinth");
                p["pinned"] = json!(true);
                p["badge"] = Value::Null;
                Response::json(200, json!({ "pack": p }))
            }
            ("PATCH", "/v1/admin/featured-packs/fp2") => Response::error(400, "invalid_window"),
            ("DELETE", "/v1/admin/featured-packs/fp1") => Response::empty(204),
            ("DELETE", "/v1/admin/featured-packs/gone") => Response::error(404, "not_found"),
            ("PUT", "/v1/admin/featured-packs/order") => {
                let ids: Vec<String> = serde_json::from_value(req.json()["ids"].clone()).unwrap_or_default();
                if ids.len() != 2 {
                    return Response::error(400, "invalid_order");
                }
                Response::json(200, json!({ "packs": ids.iter().map(|id| pack("", id, "modrinth")).collect::<Vec<_>>() }))
            }
            ("POST", "/v1/admin/featured-packs/fp2/refresh") => Response::json(200, json!({ "pack": pack("", "fp2", "curseforge") })),
            _ => Response::error(404, "not_found"),
        }
    })
    .await
}

#[tokio::test]
async fn featured_public_list_uses_cache_and_consent() {
    let server = server().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let packs = launcher.trs_featured_packs(false).await.unwrap();
    assert_eq!(packs.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), ["fp1", "fp2"], "kaputte Einträge fallen weg");
    assert_eq!(packs[1].source, FeaturedSource::Curseforge);
    assert_eq!(packs[0].icon_url.as_deref(), Some(format!("{}/v1/site/featured-packs/fp1/icon?v=1", server.base).as_str()));
    assert!(packs[0].banner_url.is_none(), "fremde Bilder fallen weg");
    let sent = &server.hits("GET", "/v1/site/featured-packs")[0];
    assert!(sent.bearer().is_none(), "öffentliche Liste ohne Token");

    // Frischer Cache: keine zweite Anfrage; `force` lädt neu.
    launcher.trs_featured_packs(false).await.unwrap();
    assert_eq!(server.hits("GET", "/v1/site/featured-packs").len(), 1);
    launcher.trs_featured_packs(true).await.unwrap();
    assert_eq!(server.hits("GET", "/v1/site/featured-packs").len(), 2);

    // Ohne Einwilligung: leer und kein Netz.
    launcher.trs.store.set_consent(Consent::Declined).await;
    assert!(launcher.trs_featured_packs(true).await.unwrap().is_empty());
    assert_eq!(server.hits("GET", "/v1/site/featured-packs").len(), 2);
}

#[tokio::test]
async fn featured_team_operations() {
    let server = server().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let list = launcher.trs_admin_featured_packs().await.unwrap();
    assert_eq!(list.len(), 2);
    assert!(list[0].active && list[0].created_by == "Theredstonee");
    assert!(server.hits("GET", "/v1/admin/featured-packs")[0].bearer().is_some(), "Team-Anfragen mit Token");

    // Hinzufügen: Link wird normalisiert, Fehler bekommen eigene Meldungen.
    let added = launcher.trs_admin_add_featured_pack("modrinth.com/modpack/fabulously-optimized").await.unwrap();
    assert_eq!(added.pack.id, "fp3");
    assert_eq!(
        server.hits("POST", "/v1/admin/featured-packs")[0].json(),
        json!({ "url": "https://modrinth.com/modpack/fabulously-optimized" })
    );
    let err = launcher.trs_admin_add_featured_pack("https://modrinth.com/mod/sodium").await.unwrap_err();
    assert_eq!(err.message_code(), "trsApi.not_a_modpack");
    let err = launcher.trs_admin_add_featured_pack("https://modrinth.com/modpack/other").await.unwrap_err();
    assert_eq!(err.message_code(), "trsApi.already_featured");
    let err = launcher.trs_admin_add_featured_pack("https://www.curseforge.com/minecraft/modpacks/x").await.unwrap_err();
    assert_eq!((err.kind(), err.message_code()), ("trs_api", "trsApi.curseforge_unavailable"), "503 ist hier kein „offline“");
    let err = launcher.trs_admin_add_featured_pack("https://modrinth.com/modpack/down").await.unwrap_err();
    assert_eq!((err.kind(), err.message_code()), ("trs_api", "trsApi.upstream_failed"));
    let before = server.hits("POST", "/v1/admin/featured-packs").len();
    assert_eq!(launcher.trs_admin_add_featured_pack("https://evil.example/x").await.unwrap_err().message_code(), "featured.invalidLink");
    assert_eq!(server.hits("POST", "/v1/admin/featured-packs").len(), before, "fremde Links gehen nicht raus");

    // Ändern: nur die gesetzten Felder gehen raus.
    let patch: FeaturedPatch = serde_json::from_value(json!({ "pinned": true, "badge": null })).unwrap();
    let updated = launcher.trs_admin_update_featured_pack("fp1", &patch).await.unwrap();
    assert!(updated.pack.pinned && updated.pack.badge.is_none());
    assert_eq!(server.hits("PATCH", "/v1/admin/featured-packs/fp1")[0].json(), json!({ "pinned": true, "badge": null }));
    let window: FeaturedPatch = serde_json::from_value(json!({ "endsAt": "2026-10-01T00:00:00Z" })).unwrap();
    assert_eq!(launcher.trs_admin_update_featured_pack("fp2", &window).await.unwrap_err().message_code(), "trsApi.invalid_window");
    assert!(launcher.trs_admin_update_featured_pack("../x", &patch).await.is_err());

    // Reihenfolge, neu laden, löschen.
    let ordered = launcher.trs_admin_order_featured_packs(&["fp2".into(), "fp1".into()]).await.unwrap();
    assert_eq!(ordered.iter().map(|p| p.pack.id.as_str()).collect::<Vec<_>>(), ["fp2", "fp1"]);
    assert_eq!(server.hits("PUT", "/v1/admin/featured-packs/order")[0].json(), json!({ "ids": ["fp2", "fp1"] }));
    let err = launcher.trs_admin_order_featured_packs(&["fp1".into(), "fp2".into(), "fp3".into()]).await.unwrap_err();
    assert_eq!(err.message_code(), "trsApi.invalid_order");
    let refreshed = launcher.trs_admin_refresh_featured_pack("fp2").await.unwrap();
    assert_eq!((refreshed.pack.source, refreshed.pack.badge), (FeaturedSource::Curseforge, Some(FeaturedBadge::New)));
    launcher.trs_admin_delete_featured_pack("fp1").await.unwrap();
    assert_eq!(launcher.trs_admin_delete_featured_pack("gone").await.unwrap_err().message_code(), "trsApi.not_found");

    // Nach einer Änderung lädt die Startseite neu statt aus dem Cache.
    launcher.trs_featured_packs(false).await.unwrap();
    launcher.trs_admin_refresh_featured_pack("fp2").await.unwrap();
    launcher.trs_featured_packs(false).await.unwrap();
    assert_eq!(server.hits("GET", "/v1/site/featured-packs").len(), 2);
}
