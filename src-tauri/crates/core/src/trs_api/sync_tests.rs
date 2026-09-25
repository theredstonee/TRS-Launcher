//! TRS-Synchronisation gegen einen nachgebauten Sync-Server (Vertrag
//! `/v1/me/sync*`): Vereinigung beim ersten Abgleich, Grabsteine in beide
//! Richtungen, „letzter Schreiber gewinnt“ und `409 stale`.

use std::collections::BTreeMap;
use std::sync::atomic::AtomicUsize;
use std::sync::{Arc, Mutex};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chrono::{DateTime, SecondsFormat, Utc};
use serde_json::{Value, json};

use super::png::tests::png_with;
use super::sync::SyncChanges;
use super::testkit::{MockServer, Request, Response};
use super::tests::{ACC, auth_routes, launcher};
use crate::Launcher;
use crate::presets::{self, PresetInput};
use crate::settings::{Accent, Language, Theme};
use crate::skins::SkinVariant;

fn now_iso() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
}

fn iso(at: DateTime<Utc>) -> String {
    at.to_rfc3339_opts(SecondsFormat::Millis, true)
}

#[derive(Clone)]
struct CloudSkin {
    name: String,
    variant: String,
    png: Vec<u8>,
    updated_at: String,
}

/// Zustand des nachgebauten Servers.
#[derive(Default)]
struct Cloud {
    skins: BTreeMap<String, CloudSkin>,
    graves: BTreeMap<String, String>,
    presets: Option<(Value, String)>,
    settings: Option<(Value, String)>,
    /// Beim nächsten `PUT /v1/me/sync/presets` kommt ein anderer PC zuvor.
    race_presets: Option<Value>,
}

impl Cloud {
    fn skin_json(id: &str, s: &CloudSkin) -> Value {
        json!({ "id": id, "name": s.name, "variant": s.variant, "sha256": "0".repeat(64), "updatedAt": s.updated_at })
    }

    fn blob(blob: &Option<(Value, String)>) -> Value {
        blob.as_ref().map_or(Value::Null, |(data, at)| json!({ "data": data, "updatedAt": at }))
    }

    /// `PUT` mit „letzter Schreiber gewinnt“ (gleich alt überschreibt).
    fn put_lww(slot: &mut Option<(Value, String)>, body: &Value) -> Response {
        let sent = body["updatedAt"].as_str().unwrap_or_default().to_owned();
        let parse = |s: &str| DateTime::parse_from_rfc3339(s).unwrap();
        if let Some((data, at)) = slot.as_ref()
            && parse(at) > parse(&sent)
        {
            return Response::json(
                409,
                json!({ "error": { "code": "stale", "message": "stale", "current": { "data": data, "updatedAt": at } } }),
            );
        }
        *slot = Some((body["data"].clone(), sent));
        let (data, at) = slot.as_ref().unwrap();
        Response::json(200, json!({ "data": data, "updatedAt": at }))
    }

    fn handle(&mut self, req: &Request) -> Response {
        let path = req.path.as_str();
        let method = req.method.as_str();
        if method == "GET" && path == "/v1/me/sync" {
            return Response::json(
                200,
                json!({
                    "skins": self.skins.iter().map(|(id, s)| Self::skin_json(id, s)).collect::<Vec<_>>(),
                    "deletedSkins": self.graves.iter().map(|(id, at)| json!({ "id": id, "deletedAt": at })).collect::<Vec<_>>(),
                    "presets": Self::blob(&self.presets),
                    "settings": Self::blob(&self.settings),
                }),
            );
        }
        if path == "/v1/me/sync/presets" && method == "PUT" {
            if let Some(other) = self.race_presets.take() {
                // Ein anderer PC schreibt genau jetzt – mit einer Zeit knapp in der Zukunft.
                self.presets = Some((other, iso(Utc::now() + chrono::Duration::seconds(2))));
            }
            return Self::put_lww(&mut self.presets, &req.json());
        }
        if path == "/v1/me/sync/settings" && method == "PUT" {
            return Self::put_lww(&mut self.settings, &req.json());
        }
        let Some(rest) = path.strip_prefix("/v1/me/sync/skins/") else { return Response::error(404, "not_found") };
        if let Some(id) = rest.strip_suffix(".png") {
            return match self.skins.get(id) {
                Some(s) if method == "GET" => Response::png(s.png.clone()),
                _ => Response::error(404, "skin_not_found"),
            };
        }
        let id = rest.to_owned();
        match method {
            "PUT" => {
                if self.skins.len() >= 60 && !self.skins.contains_key(&id) {
                    return Response::error(409, "skin_limit");
                }
                let body = req.json();
                let skin = CloudSkin {
                    name: body["name"].as_str().unwrap_or_default().trim().to_owned(),
                    variant: body["variant"].as_str().unwrap_or_default().to_owned(),
                    png: STANDARD.decode(body["png"].as_str().unwrap_or_default()).unwrap_or_default(),
                    updated_at: now_iso(),
                };
                self.graves.remove(&id);
                let out = Self::skin_json(&id, &skin);
                self.skins.insert(id, skin);
                Response::json(200, json!({ "skin": out }))
            }
            "PATCH" => {
                let body = req.json();
                let Some(skin) = self.skins.get_mut(&id) else { return Response::error(404, "skin_not_found") };
                if let Some(name) = body["name"].as_str() {
                    skin.name = name.to_owned();
                }
                if let Some(variant) = body["variant"].as_str() {
                    skin.variant = variant.to_owned();
                }
                skin.updated_at = now_iso();
                Response::json(200, json!({ "skin": Self::skin_json(&id, skin) }))
            }
            "DELETE" => {
                self.skins.remove(&id);
                self.graves.insert(id, now_iso());
                Response::empty(204)
            }
            _ => Response::error(404, "not_found"),
        }
    }
}

async fn cloud() -> (Arc<Mutex<Cloud>>, MockServer) {
    let cloud = Arc::new(Mutex::new(Cloud::default()));
    let state = Arc::clone(&cloud);
    let issued = AtomicUsize::new(0);
    let server = MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        state.lock().unwrap().handle(req)
    })
    .await;
    (cloud, server)
}

fn skin_png() -> Vec<u8> {
    png_with(64, 64, &[])
}

fn cloud_skin(name: &str, updated_at: DateTime<Utc>) -> CloudSkin {
    CloudSkin { name: name.into(), variant: "slim".into(), png: skin_png(), updated_at: iso(updated_at) }
}

async fn sync(launcher: &Launcher) -> SyncChanges {
    let mut changes = SyncChanges::default();
    let pending = launcher.trs_sync_with(ACC, &mut changes).await.unwrap();
    assert!(!pending);
    changes
}

async fn library_names(launcher: &Launcher) -> Vec<String> {
    let mut names: Vec<String> = launcher.skin_library().await.unwrap().into_iter().map(|s| s.name).collect();
    names.sort();
    names
}

fn preset_input(name: &str) -> PresetInput {
    serde_json::from_value(json!({ "name": name, "auto": false, "items": [
        { "source": "modrinth", "projectId": "AANobbMI", "title": "Sodium", "kind": "mod" }
    ] }))
    .unwrap()
}

const REMOTE_PRESET: &str = "22222222222222222222222222222222";

#[tokio::test]
async fn first_sync_unites_everything_and_takes_the_account_settings() {
    let (cloud, server) = cloud().await;
    let earlier = Utc::now() - chrono::Duration::hours(2);
    {
        let mut c = cloud.lock().unwrap();
        c.skins.insert("bbbbbbbbbbbb".into(), cloud_skin("Vom Konto", earlier));
        c.presets = Some((
            json!({ "version": 1, "presets": [ { "id": REMOTE_PRESET, "name": "Vom Konto", "auto": false, "items": [],
                "updatedAt": iso(earlier) } ], "deleted": [], "layout": { "order": [REMOTE_PRESET], "auto": {} } }),
            iso(earlier),
        ));
        c.settings = Some((json!({ "theme": "oled", "accent": "lapis", "language": "es" }), iso(earlier)));
    }
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    launcher.add_skin_bytes(&skin_png(), "Von hier", SkinVariant::Classic).await.unwrap();
    presets::create(launcher.paths(), preset_input("Von hier")).await.unwrap();

    let changes = sync(&launcher).await;
    assert!(changes.skins && changes.presets && changes.settings);

    // Skins: beide Seiten haben beide.
    assert_eq!(library_names(&launcher).await, ["Vom Konto", "Von hier"]);
    let slim = launcher.skin_library().await.unwrap().into_iter().find(|s| s.id == "bbbbbbbbbbbb").unwrap();
    assert_eq!(slim.variant, SkinVariant::Slim);
    let cloud_names: Vec<String> = cloud.lock().unwrap().skins.values().map(|s| s.name.clone()).collect();
    assert_eq!(cloud_names.len(), 2);
    assert!(cloud_names.contains(&"Von hier".to_owned()));

    // Presets: vereinigt, lokal und auf dem Konto.
    let local: Vec<String> = presets::list(launcher.paths()).await.unwrap().into_iter().filter(|p| p.builtin.is_none()).map(|p| p.name).collect();
    assert_eq!(local, ["Vom Konto", "Von hier"]);
    let data = cloud.lock().unwrap().presets.clone().unwrap().0;
    assert_eq!(data["presets"].as_array().unwrap().len(), 2);
    assert!(data.to_string().len() < super::sync::MAX_DATA_BYTES);

    // Einstellungen: hier nie geändert → die vom Konto gelten, nichts hochgeladen.
    let ui = launcher.settings().await.ui;
    assert_eq!((ui.theme, ui.accent, ui.language), (Theme::Oled, Accent::Lapis, Language::Es));
    assert!(server.hits("PUT", "/v1/me/sync/settings").is_empty());

    // Zweiter Abgleich: alles gleich, keine Schreibzugriffe mehr.
    let before = server.requests().len();
    let changes = sync(&launcher).await;
    assert!(!changes.any());
    let writes = server.requests()[before..].iter().filter(|r| r.method != "GET").count();
    assert_eq!(writes, 0, "{:?}", server.requests()[before..].iter().map(|r| format!("{} {}", r.method, r.path)).collect::<Vec<_>>());
}

#[tokio::test]
async fn deletions_travel_in_both_directions() {
    let (cloud, server) = cloud().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let here = launcher.add_skin_bytes(&skin_png(), "Hier", SkinVariant::Classic).await.unwrap();
    let there = launcher.add_skin_bytes(&skin_png(), "Dort", SkinVariant::Classic).await.unwrap();
    let old = launcher.add_skin_bytes(&skin_png(), "Uralt", SkinVariant::Classic).await.unwrap();
    let preset = presets::create(launcher.paths(), preset_input("Weg damit")).await.unwrap();
    sync(&launcher).await;
    assert_eq!(cloud.lock().unwrap().skins.len(), 3);

    // Hier gelöscht → auf dem Konto gelöscht (mit Grabstein).
    launcher.delete_skin(&here.id).await.unwrap();
    presets::delete(launcher.paths(), &preset.id).await.unwrap();
    sync(&launcher).await;
    {
        let c = cloud.lock().unwrap();
        assert!(!c.skins.contains_key(&here.id));
        assert!(c.graves.contains_key(&here.id));
        let data = &c.presets.as_ref().unwrap().0;
        assert!(data["presets"].as_array().unwrap().is_empty());
        assert_eq!(data["deleted"][0]["id"], preset.id);
    }

    // Auf einem anderen PC gelöscht (Grabstein) → hier gelöscht; ohne Grabstein
    // (vor über 30 Tagen gelöscht) → ebenfalls, weil der Skin schon abgeglichen war.
    {
        let mut c = cloud.lock().unwrap();
        c.skins.remove(&there.id);
        c.graves.insert(there.id.clone(), now_iso());
        c.skins.remove(&old.id);
    }
    let changes = sync(&launcher).await;
    assert!(changes.skins);
    assert!(launcher.skin_library().await.unwrap().is_empty());
    // Und nichts wird wieder hochgeladen.
    assert!(cloud.lock().unwrap().skins.is_empty());
}

#[tokio::test]
async fn the_newer_change_wins() {
    let (cloud, server) = cloud().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let skin = launcher.add_skin_bytes(&skin_png(), "Alt", SkinVariant::Classic).await.unwrap();
    sync(&launcher).await;

    // Hier umbenannt → PATCH.
    launcher.rename_skin(&skin.id, "Neu hier").await.unwrap();
    sync(&launcher).await;
    assert_eq!(cloud.lock().unwrap().skins[&skin.id].name, "Neu hier");
    assert_eq!(server.hits("PATCH", &format!("/v1/me/sync/skins/{}", skin.id)).len(), 1);

    // Auf dem Konto später umbenannt → hier übernommen.
    {
        let mut c = cloud.lock().unwrap();
        let s = c.skins.get_mut(&skin.id).unwrap();
        s.name = "Neu dort".into();
        s.updated_at = iso(Utc::now() + chrono::Duration::seconds(1));
    }
    assert!(sync(&launcher).await.skins);
    assert_eq!(library_names(&launcher).await, ["Neu dort"]);

    // Einstellungen: hier geändert (nach dem Stand des Kontos) → hochgeladen.
    let old = (Utc::now() - chrono::Duration::hours(1)).to_rfc3339_opts(SecondsFormat::Millis, true);
    cloud.lock().unwrap().settings = Some((json!({ "theme": "dark", "accent": "emerald", "language": "fr" }), old));
    let mut settings = launcher.settings().await;
    settings.ui.theme = Theme::Light;
    launcher.update_settings(settings).await.unwrap();
    sync(&launcher).await;
    let stored = cloud.lock().unwrap().settings.clone().unwrap().0;
    assert_eq!(stored, json!({ "theme": "light", "accent": "emerald", "language": "fr" }));
    let ui = launcher.settings().await.ui;
    assert_eq!((ui.theme, ui.accent, ui.language), (Theme::Light, Accent::Emerald, Language::Fr));
    // Echte Zeit, nicht in der Zukunft.
    let sent = server.hits("PUT", "/v1/me/sync/settings")[0].json()["updatedAt"].as_str().unwrap().to_owned();
    assert!(DateTime::parse_from_rfc3339(&sent).unwrap() <= Utc::now());
}

#[tokio::test]
async fn stale_answers_take_the_server_state() {
    let (cloud, server) = cloud().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    presets::create(launcher.paths(), preset_input("Von hier")).await.unwrap();
    // Ein anderer PC schreibt zwischen GET und PUT.
    cloud.lock().unwrap().race_presets = Some(json!({
        "version": 1,
        "presets": [ { "id": REMOTE_PRESET, "name": "Vom anderen PC", "auto": false, "items": [], "updatedAt": now_iso() } ],
        "deleted": [], "layout": { "order": [REMOTE_PRESET], "auto": {} }
    }));
    let changes = sync(&launcher).await;
    assert!(changes.presets, "Stand vom Server übernommen");
    let puts = server.hits("PUT", "/v1/me/sync/presets");
    assert_eq!(puts.len(), 2, "nach 409 einmal erneut");
    let names: Vec<String> = presets::list(launcher.paths()).await.unwrap().into_iter().filter(|p| p.builtin.is_none()).map(|p| p.name).collect();
    assert_eq!(names.len(), 2);
    assert!(names.contains(&"Vom anderen PC".to_owned()));
    let data = cloud.lock().unwrap().presets.clone().unwrap().0;
    assert_eq!(data["presets"].as_array().unwrap().len(), 2);
}

#[tokio::test]
async fn nothing_is_sent_when_switched_off() {
    let (_cloud, server) = cloud().await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let mut settings = launcher.settings().await;
    settings.trs_sync = false;
    launcher.update_settings(settings).await.unwrap();
    launcher.trs_sync_round().await;
    assert!(server.hits("GET", "/v1/me/sync").is_empty());
    assert!(!launcher.trs_sync_status().await.active);

    // Wieder an: der Abgleich läuft, merkt sich die Zeit und meldet sich bei der Oberfläche.
    let events = Arc::new(Mutex::new(Vec::new()));
    let log = Arc::clone(&events);
    launcher.set_trs_sync_sink(Arc::new(move |e| log.lock().unwrap().push(e)));
    let mut settings = launcher.settings().await;
    settings.trs_sync = true;
    launcher.update_settings(settings).await.unwrap();
    launcher.trs_sync_round().await;
    assert_eq!(server.hits("GET", "/v1/me/sync").len(), 1);
    let status = launcher.trs_sync_status().await;
    assert!(status.active && status.last_sync_at.is_some() && status.problem.is_none());
    {
        let events = events.lock().unwrap();
        assert_eq!(events.len(), 2);
        assert!(events[0].status.syncing && !events[1].status.syncing);
        assert!(events[1].status.last_sync_at.is_some());
        assert_eq!(events[1].account, ACC);
    }

    // Ohne Einwilligung ebenfalls nichts (auch keine Events).
    launcher.trs_set_consent(false).await.unwrap();
    launcher.trs_sync_round().await;
    assert_eq!(server.hits("GET", "/v1/me/sync").len(), 1);
    assert_eq!(events.lock().unwrap().len(), 2);
}

#[tokio::test]
async fn offline_is_quiet_and_backs_off() {
    let issued = AtomicUsize::new(0);
    let server =
        MockServer::start(move |req| auth_routes(req, &issued, "mc-token").unwrap_or_else(|| Response::error(503, "unavailable")))
            .await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let local = launcher.add_skin_bytes(&skin_png(), "Bleibt", SkinVariant::Classic).await.unwrap();
    assert_eq!(launcher.trs_sync_round().await, std::time::Duration::from_secs(30));
    assert_eq!(launcher.trs_sync_status().await.problem.as_deref(), Some("offline"));
    // Zweiter Fehlschlag: doppelt so lange warten. Lokal bleibt alles, wie es ist.
    assert_eq!(launcher.trs_sync_round().await, std::time::Duration::from_secs(60));
    assert_eq!(launcher.skin_library().await.unwrap()[0].id, local.id);
}
