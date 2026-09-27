//! Screenshots als Link teilen gegen eine nachgebaute API (§23).

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use serde_json::json;

use super::testkit::{MockServer, Request, Response};
use super::tests::{ACC, auth_routes, launcher};
use crate::instance::{Loader, NewInstance};

const ID: &str = "Qm9vLWJhei1xdXV4LTEyMw";

fn share_json(id: &str, base: &str) -> serde_json::Value {
    json!({ "id": id, "url": format!("https://evil.example/s/{id}"), "imageUrl": format!("{base}/v1/shares/{id}/image"),
        "thumbUrl": "javascript:alert(1)", "mime": "image/jpeg", "width": 640, "height": 360, "bytes": 1234,
        "createdAt": "2026-09-27T10:00:00.000Z", "expiresAt": "2026-10-27T10:00:00.000Z" })
}

/// Antworten der Attrappe: `uploads` zählt, `mode` schaltet Fehler (0 = ok, 1 = Grenze, 2 = Tagesgrenze, 3 = Strafe).
fn share_api(uploads: Arc<Mutex<Vec<(String, usize)>>>, mode: Arc<AtomicUsize>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        match (req.method.as_str(), req.path.as_str()) {
            ("POST", "/v1/shares") => {
                match mode.load(Ordering::SeqCst) {
                    1 => return Response::error(409, "shared_image_limit"),
                    2 => return Response::error(429, "share_daily_limit").with_header("retry-after", "3600"),
                    3 => return Response::error(403, "sanctioned"),
                    4 => return Response::error(507, "storage_full"),
                    _ => {}
                }
                let ct = req.header("content-type").unwrap_or_default().to_owned();
                if req.body.len() > super::share::MAX_SHARE_BYTES || !matches!(ct.as_str(), "image/png" | "image/jpeg") {
                    return Response::error(413, "payload_too_large");
                }
                uploads.lock().unwrap().push((ct, req.body.len()));
                Response::json(201, json!({ "share": share_json(ID, "http://127.0.0.1:1") }))
            }
            ("GET", "/v1/shares") => Response::json(
                200,
                json!({ "shares": [share_json(ID, "x"), { "id": "../../etc/passwd" }],
                    "limits": { "active": 1, "maxActive": 50, "uploadsToday": 3, "maxPerDay": 20 } }),
            ),
            ("DELETE", p) if p == format!("/v1/shares/{ID}") => Response::empty(204),
            ("DELETE", _) => Response::error(404, "share_not_found"),
            _ => Response::error(404, "not_found"),
        }
    }
}

fn png_bytes(width: u32, height: u32) -> Vec<u8> {
    let buffer = image::RgbImage::from_fn(width, height, |x, y| image::Rgb([(x % 256) as u8, (y % 256) as u8, 128]));
    let mut out = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(buffer).write_to(&mut out, image::ImageFormat::Png).unwrap();
    out.into_inner()
}

#[tokio::test]
async fn screenshots_are_shared_listed_and_deleted() {
    let uploads = Arc::new(Mutex::new(Vec::new()));
    let mode = Arc::new(AtomicUsize::new(0));
    let server = MockServer::start(share_api(Arc::clone(&uploads), Arc::clone(&mode))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let instance = launcher
        .instances()
        .create(NewInstance { name: "Alpha".into(), game_version: "1.21.1".into(), loader: Loader::vanilla() })
        .await
        .unwrap();
    let shots = launcher.paths().instance_game_dir(&instance.id).join("screenshots");
    tokio::fs::create_dir_all(shots.join("panorama")).await.unwrap();
    tokio::fs::write(shots.join("klein.png"), png_bytes(640, 360)).await.unwrap();
    tokio::fs::write(shots.join("riesig.png"), png_bytes(5000, 60)).await.unwrap();
    tokio::fs::write(shots.join("panorama").join("rund.png"), png_bytes(800, 400)).await.unwrap();
    tokio::fs::write(shots.join("notiz.png"), b"kein Bild").await.unwrap();

    // Kleines Bild geht unverändert als PNG hoch; Links nur von der eigenen API.
    let share = launcher.share_screenshot(&instance.id, "klein.png").await.unwrap();
    assert_eq!(share.id, ID);
    assert_eq!(share.url, format!("{}/s/{ID}", server.base), "fremder Link wird ersetzt");
    assert_eq!(share.thumb_url, format!("{}/v1/shares/{ID}/image?thumb=1", server.base));
    assert_eq!(uploads.lock().unwrap()[0].0, "image/png");

    // Zu große Kante → hier schon als JPEG verkleinert.
    launcher.share_screenshot(&instance.id, "riesig.png").await.unwrap();
    assert_eq!(uploads.lock().unwrap()[1].0, "image/jpeg");

    // Panorama (Equirectangular) ist ein ganz normales Bild.
    launcher.share_screenshot(&instance.id, "panorama/rund.png").await.unwrap();
    assert_eq!(uploads.lock().unwrap().len(), 3);

    // Pfad-Angriffe und Unsinn scheitern ohne Anfrage.
    for (inst, file) in [
        ("../x", "klein.png"),
        (instance.id.as_str(), "../klein.png"),
        (instance.id.as_str(), "panorama/../klein.png"),
        (instance.id.as_str(), "panorama/sub/rund.png"),
        (instance.id.as_str(), "andere/rund.png"),
        (instance.id.as_str(), "..\\klein.png"),
        (instance.id.as_str(), "klein.txt"),
        (instance.id.as_str(), "fehlt.png"),
        (instance.id.as_str(), "notiz.png"),
    ] {
        assert!(launcher.share_screenshot(inst, file).await.is_err(), "{inst} {file}");
    }
    assert_eq!(uploads.lock().unwrap().len(), 3);

    // Liste: kaputte Einträge fallen weg.
    let page = launcher.shares_list().await.unwrap();
    assert_eq!(page.shares.len(), 1);
    assert_eq!((page.limits.active, page.limits.max_active, page.limits.uploads_today, page.limits.max_per_day), (1, 50, 3, 20));

    // Löschen: eigene ID ok, fremde → share_not_found, kaputte ID ohne Anfrage.
    launcher.share_delete(ID).await.unwrap();
    let err = launcher.share_delete("AAAAAAAAAAAAAAAAAAAAAA").await.unwrap_err();
    assert_eq!((err.code(), err.message_code()), (Some("share_not_found"), "shareLink.notFound"));
    assert!(launcher.share_delete("../x").await.is_err());

    // Fehler der API mit eigenen Texten (eigene Texte statt allgemeiner Meldungen).
    for (m, code, msg) in [
        (1, "shared_image_limit", "shareLink.limit"),
        (2, "share_daily_limit", "shareLink.dailyLimit"),
        (3, "sanctioned", "trsApi.sanctioned"),
        (4, "storage_full", "shareLink.storageFull"),
    ] {
        mode.store(m, Ordering::SeqCst);
        let err = launcher.share_screenshot(&instance.id, "klein.png").await.unwrap_err();
        assert_eq!((err.code(), err.message_code()), (Some(code), msg), "Modus {m}");
        assert_ne!(err.kind(), "trs_offline");
    }
}
