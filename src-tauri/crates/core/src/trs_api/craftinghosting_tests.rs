//! CraftingHosting (§43) gegen eine nachgebaute API: Anlegen, Fehler, Heartbeat, Konsole.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use serde_json::json;

use super::craftinghosting::{NewServer, SettingsPatch, VersionPick, passthrough, quota, rules};
use super::craftinghosting_world::{UploadTarget, WorldSource};
use super::testkit::{MockServer, Request, Response};
use super::tests::{ACC, auth_routes, launcher};
use crate::Error;

fn server(active: bool) -> serde_json::Value {
    json!({
        "id": "0b6f6c0e-1111-2222-3333-444455556666", "name": "Meine Welt", "playerId": "P7KXGQXL",
        "state": if active { "running" } else { "offline" }, "address": "node1.example.com:25568",
        "serverType": "paper", "version": "latest",
        "resources": { "memoryMb": 4096, "cpuPercent": 200, "diskMb": 10240 },
        "eulaAccepted": true, "access": "friends",
        "session": { "active": active, "startedAt": null, "lastHeartbeatAt": null, "heartbeatTimeoutSeconds": 180,
                     "runSeconds": 0, "maxRunSeconds": 86400, "remainingSeconds": 86400, "cooldownUntil": null,
                     "lastStopReason": if active { serde_json::Value::Null } else { json!("host_timeout") } },
        "createdAt": "2026-11-18T15:59:20.000Z", "lastStartAt": null, "domain": "meinewelt.trs-launcher.de", "ip": "1.2.3.4:25568"
    })
}

struct Api {
    /// Läuft die Sitzung auf dem Server?
    running: AtomicBool,
    /// Antwortet `start` mit `cooldown`?
    cooldown: AtomicBool,
    /// Antwortet alles mit `craftinghosting_unavailable`?
    unavailable: AtomicBool,
    /// Startet die API den Server gerade nach der Ersteinrichtung neu?
    restarting: AtomicBool,
}

fn api(state: Arc<Api>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        if state.unavailable.load(Ordering::SeqCst) {
            return Response::error(503, "craftinghosting_unavailable");
        }
        let running = state.running.load(Ordering::SeqCst);
        match (req.method.as_str(), req.path.as_str()) {
            ("GET", "/v1/craftinghosting/info") => Response::json(
                200,
                json!({
                    "plan": { "name": "Free 4 GB", "memoryMb": 4096, "cpuPercent": 200, "diskMb": 10240 },
                    "serverTypes": [
                        { "id": "paper", "name": "Paper", "versions": ["latest", "26.3", "1.21.11", "bad version!"] },
                        { "id": "Bad Type", "name": "x", "versions": ["1.0"] }
                    ],
                    "limits": { "maxServersPerPlayer": 1, "sessionMaxHours": 24, "cooldownHours": 2,
                                "heartbeatTimeoutSeconds": 180, "deleteAfterIdleDays": 30, "serverNameMaxLength": 22 },
                    "eulaUrl": "javascript:alert(1)",
                    "full": false,
                    "features": { "files": true, "worlds": true, "backups": "yes", "teleport": true, "console": true },
                    "notice": "  Wartung heute 22 Uhr\u{7}  ",
                    "maintenance": { "active": true, "message": " Update " }
                }),
            ),
            ("GET", "/v1/craftinghosting/server") => Response::json(200, json!({ "server": server(running) })),
            ("POST", "/v1/craftinghosting/server") => {
                let body = req.json();
                if body["name"] == "Taken" {
                    return Response::error(409, "server_exists");
                }
                if body["name"] == "Bad Name" {
                    return Response::error(400, "invalid_name");
                }
                Response::json(201, json!({ "server": server(false) }))
            }
            ("POST", "/v1/craftinghosting/server/start") => {
                if state.cooldown.load(Ordering::SeqCst) {
                    return Response::json(
                        409,
                        json!({ "error": { "code": "cooldown", "message": "cooldown",
                                           "details": { "cooldownUntil": "2026-11-18T20:00:00.000Z" } } }),
                    );
                }
                state.running.store(true, Ordering::SeqCst);
                Response::json(200, json!({ "server": server(true) }))
            }
            ("POST", "/v1/craftinghosting/server/heartbeat") => {
                if state.restarting.load(Ordering::SeqCst) {
                    let mut s = server(false);
                    s["setup"] = json!("restarting");
                    Response::json(200, json!({ "server": s }))
                } else if running {
                    Response::json(200, json!({ "server": server(true) }))
                } else {
                    Response::json(
                        409,
                        json!({ "error": { "code": "not_running", "message": "x", "details": { "reason": "host_timeout" } } }),
                    )
                }
            }
            ("POST", "/v1/craftinghosting/server/stop") => {
                state.running.store(false, Ordering::SeqCst);
                Response::json(200, json!({ "server": server(false) }))
            }
            ("POST", "/v1/craftinghosting/server/command") => {
                if running { Response::empty(204) } else { Response::error(409, "not_running") }
            }
            ("DELETE", "/v1/craftinghosting/server") => Response::empty(204),
            _ => Response::error(404, "not_found"),
        }
    }
}

fn state() -> Arc<Api> {
    Arc::new(Api {
        running: AtomicBool::new(false),
        cooldown: AtomicBool::new(false),
        unavailable: AtomicBool::new(false),
        restarting: AtomicBool::new(false),
    })
}

fn new_server(name: &str) -> NewServer {
    NewServer { name: name.into(), server_type: "paper".into(), version: "latest".into(), access: "friends".into(), subdomain: None, accept_eula: false }
}

fn api_code(e: &Error) -> (&str, &str) {
    match e {
        Error::TrsApi { kind, code, .. } => (kind, code.as_str()),
        other => panic!("kein TRS-Fehler: {other:?}"),
    }
}

#[tokio::test]
async fn info_is_cleaned() {
    let server = MockServer::start(api(state())).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let info = launcher.craftinghosting_info().await.unwrap();
    assert_eq!(info.server_types.len(), 1);
    assert_eq!(info.server_types[0].versions, ["latest", "26.3", "1.21.11"]);
    assert_eq!(info.eula_url, "https://www.minecraft.net/eula");
    assert_eq!(info.plan.memory_mb, 4096);
    // Unbekannte Schlüssel und Nicht-Bool zählen als aus.
    assert!(info.features.files && info.features.worlds);
    assert!(!info.features.backups && !info.features.version_change && !info.features.addons);
    // Alte API: Hinweis als Klartext.
    let notice = info.notice.unwrap();
    assert_eq!((notice.markdown.as_str(), notice.level.as_str()), ("Wartung heute 22 Uhr", "info"));
    assert!(info.features.console && !info.features.create);
    assert!(info.maintenance.active);
    assert_eq!(info.maintenance.message.as_deref(), Some("Update"));
}

#[tokio::test]
async fn create_validates_before_sending() {
    let server = MockServer::start(api(state())).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    for bad in ["ab", "SMP #1", "x\ny z"] {
        assert!(matches!(launcher.craftinghosting_create(&new_server(bad)).await, Err(Error::Validation(_))));
    }
    let mut spec = new_server("Meine Welt");
    spec.access = "everyone".into();
    assert!(matches!(launcher.craftinghosting_create(&spec).await, Err(Error::Validation(_))));
    assert!(server.hits("POST", "/v1/craftinghosting/server").is_empty());

    let created = launcher.craftinghosting_create(&new_server("  Meine Welt ")).await.unwrap();
    assert_eq!(created.name, "Meine Welt");
    assert_eq!(created.address.as_deref(), Some("node1.example.com:25568"));
    let sent = &server.hits("POST", "/v1/craftinghosting/server")[0];
    assert_eq!(sent.json(), json!({ "name": "Meine Welt", "serverType": "paper", "version": "latest", "access": "friends" }));

    let e = launcher.craftinghosting_create(&new_server("Taken")).await.unwrap_err();
    assert_eq!(api_code(&e), ("trs_api", "server_exists"));
    // `invalid_name` heißt im Chat etwas anderes.
    let e = launcher.craftinghosting_create(&new_server("Bad Name")).await.unwrap_err();
    assert_eq!(e.message_code(), "trsApi.server_name_invalid");
}

#[tokio::test]
async fn unavailable_is_not_offline() {
    let api_state = state();
    api_state.unavailable.store(true, Ordering::SeqCst);
    let server = MockServer::start(api(Arc::clone(&api_state))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let e = launcher.craftinghosting_server().await.unwrap_err();
    assert_eq!(api_code(&e), ("trs_api", "craftinghosting_unavailable"));
    assert_eq!(e.message_code(), "trsApi.craftinghosting_unavailable");
}

#[tokio::test]
async fn cooldown_carries_the_end() {
    let api_state = state();
    api_state.cooldown.store(true, Ordering::SeqCst);
    let server = MockServer::start(api(Arc::clone(&api_state))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let e = launcher.craftinghosting_start(true).await.unwrap_err();
    assert_eq!(api_code(&e), ("trs_api", "cooldown"));
    assert_eq!(e.message_params().get("cooldownUntil"), Some(&json!("2026-11-18T20:00:00.000Z")));
    assert_eq!(launcher.trs.craftinghosting.active(), None);
}

#[tokio::test]
async fn heartbeat_only_while_a_session_runs() {
    let api_state = state();
    let server = MockServer::start(api(Arc::clone(&api_state))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    const HB: &str = "/v1/craftinghosting/server/heartbeat";

    // Keine Sitzung: nichts senden.
    assert!(!launcher.craftinghosting_heartbeat().await);
    assert!(server.hits("POST", HB).is_empty());

    // Server läuft nicht: Laden markiert nichts.
    assert!(!launcher.craftinghosting_server().await.unwrap().unwrap().session.active);
    assert!(!launcher.craftinghosting_heartbeat().await);
    assert!(server.hits("POST", HB).is_empty());

    // Start → Heartbeat.
    launcher.craftinghosting_start(true).await.unwrap();
    assert_eq!(launcher.trs.craftinghosting.active().as_deref(), Some(ACC));
    assert!(!launcher.craftinghosting_heartbeat().await);
    assert_eq!(server.hits("POST", HB).len(), 1);

    // Server stoppt von selbst → `not_running` beendet die Sitzung, danach Ruhe.
    api_state.running.store(false, Ordering::SeqCst);
    assert!(launcher.craftinghosting_heartbeat().await);
    assert_eq!(launcher.trs.craftinghosting.active(), None);
    assert!(!launcher.craftinghosting_heartbeat().await);
    assert_eq!(server.hits("POST", HB).len(), 2);

    // Geladener Server mit laufender Sitzung (z. B. nach Neustart des Launchers) → wieder aktiv.
    api_state.running.store(true, Ordering::SeqCst);
    launcher.craftinghosting_server().await.unwrap();
    assert_eq!(launcher.trs.craftinghosting.active().as_deref(), Some(ACC));

    // Stop beendet die Sitzung.
    launcher.craftinghosting_stop().await.unwrap();
    assert_eq!(server.hits("POST", "/v1/craftinghosting/server/stop")[0].json(), json!({}));
    assert_eq!(launcher.trs.craftinghosting.active(), None);
    assert!(!launcher.craftinghosting_heartbeat().await);
    assert_eq!(server.hits("POST", HB).len(), 2);
}

#[tokio::test]
async fn heartbeat_stops_on_account_change() {
    let server = MockServer::start(api(state())).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    launcher.trs.craftinghosting.mark("b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0");
    assert!(launcher.craftinghosting_heartbeat().await);
    assert_eq!(launcher.trs.craftinghosting.active(), None);
    assert!(server.hits("POST", "/v1/craftinghosting/server/heartbeat").is_empty());
}

#[tokio::test]
async fn commands_are_cleaned_and_not_running_ends_the_session() {
    let api_state = state();
    let server = MockServer::start(api(Arc::clone(&api_state))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    const CMD: &str = "/v1/craftinghosting/server/command";

    assert!(matches!(launcher.craftinghosting_command("say a\nop me").await, Err(Error::Validation(_))));
    assert!(matches!(launcher.craftinghosting_command("/").await, Err(Error::Validation(_))));
    assert!(server.hits("POST", CMD).is_empty());

    launcher.craftinghosting_start(true).await.unwrap();
    launcher.craftinghosting_command(" /say Hallo ").await.unwrap();
    assert_eq!(server.hits("POST", CMD)[0].json(), json!({ "command": "say Hallo" }));

    api_state.running.store(false, Ordering::SeqCst);
    let e = launcher.craftinghosting_command("list").await.unwrap_err();
    assert_eq!(api_code(&e), ("trs_api", "not_running"));
    assert_eq!(launcher.trs.craftinghosting.active(), None);
}

#[tokio::test]
async fn delete_and_shutdown() {
    let api_state = state();
    let server = MockServer::start(api(Arc::clone(&api_state))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    // Ohne Sitzung schickt das Beenden nichts.
    launcher.craftinghosting_shutdown().await;
    assert!(server.hits("POST", "/v1/craftinghosting/server/stop").is_empty());

    launcher.craftinghosting_start(true).await.unwrap();
    launcher.craftinghosting_shutdown().await;
    let stops = server.hits("POST", "/v1/craftinghosting/server/stop");
    assert_eq!(stops.len(), 1);
    assert_eq!(stops[0].json(), json!({ "whenEmpty": true }));
    assert_eq!(launcher.trs.craftinghosting.active(), None);

    launcher.craftinghosting_delete().await.unwrap();
    assert_eq!(server.hits("DELETE", "/v1/craftinghosting/server").len(), 1);
}

// --- Einstellungen, Konsole, Werte, Mods, Welt ------------------------------------------

fn extras_api(base: Arc<std::sync::OnceLock<String>>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        // Upload geht direkt an den Partner – ohne TRS-Token.
        if req.path == "/upload" {
            return if req.bearer().is_none() { Response::empty(200) } else { Response::error(400, "token_leak") };
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        let settings = json!({ "motd": "Mein Server", "maxPlayers": 8, "gamemode": "survival", "difficulty": "normal",
            "hardcore": false, "pvp": true, "allowFlight": false, "seed": "", "levelType": "NORMAL", "spawnProtection": 16,
            "viewDistance": 10, "simulationDistance": 10, "whitelist": true });
        match (req.method.as_str(), req.path.as_str()) {
            ("GET", "/v1/craftinghosting/server") => {
                let mut s = server(false);
                s["deleteAt"] = json!("2026-12-18T15:59:20.000Z");
                s["session"]["hostPresent"] = json!(false);
                Response::json(200, json!({ "server": s }))
            }
            ("PATCH", "/v1/craftinghosting/server") => {
                let body = req.json();
                let mut s = server(false);
                if let Some(sub) = body["subdomain"].as_str() {
                    if sub == "taken" {
                        return Response::error(409, "subdomain_taken");
                    }
                    s["domain"] = json!(format!("{sub}.trs-launcher.de"));
                } else {
                    s["name"] = body["name"].clone();
                }
                Response::json(200, json!({ "server": s }))
            }
            ("GET", "/v1/craftinghosting/subdomain?name=meinewelt") => {
                Response::json(200, json!({ "name": "meinewelt", "available": true, "reason": null }))
            }
            ("GET", "/v1/craftinghosting/subdomain?name=play") => {
                Response::json(200, json!({ "name": "play", "available": false, "reason": "reserved" }))
            }
            ("GET", "/v1/craftinghosting/subdomain?name=weird") => {
                Response::json(200, json!({ "name": "<b>", "available": false, "reason": "<script>" }))
            }
            ("GET", "/v1/craftinghosting/subdomain?name=nodns") => Response::error(503, "dns_unavailable"),
            ("GET", "/v1/craftinghosting/server/settings") => Response::json(200, json!({ "settings": settings })),
            ("PATCH", "/v1/craftinghosting/server/settings") => {
                let mut merged = settings.clone();
                for (k, v) in req.json().as_object().unwrap() {
                    merged[k] = v.clone();
                }
                Response::json(200, json!({ "settings": merged }))
            }
            ("GET", "/v1/craftinghosting/server/logs?lines=200") => Response::json(
                200,
                json!({ "lines": ["\u{1b}[32m[12:00:01] Done (6.1s)!\u{1b}[0m", "[12:00:02] <Bob> hi\u{7}"] }),
            ),
            ("GET", "/v1/craftinghosting/server/stats") => Response::json(
                200,
                json!({ "cpuPercent": 12.53, "memory": { "usedBytes": 1_073_741_824u64, "limitBytes": 4_294_967_296u64 },
                        "disk": { "usedBytes": null, "limitBytes": 10_737_418_240u64 }, "uptimeSeconds": 3600, "players": null }),
            ),
            ("POST", "/v1/craftinghosting/server/mods") => Response::json(
                200,
                json!({ "installed": ["lithium"],
                        "skipped": [{ "project": "sodium", "reason": "client_only" }, { "project": "x", "reason": "weird" }] }),
            ),
            ("POST", "/v1/craftinghosting/server/world/upload") => {
                let base = base.get().cloned().unwrap_or_default();
                Response::json(200, json!({ "url": format!("{base}/upload"), "expiresAt": "2026-11-18T16:10:00.000Z" }))
            }
            ("POST", "/v1/craftinghosting/server/world/import") => Response::json(200, json!({ "server": server(false) })),
            _ => Response::error(404, "not_found"),
        }
    }
}

async fn extras() -> (MockServer, tempfile::TempDir, Arc<crate::Launcher>) {
    let base = Arc::new(std::sync::OnceLock::new());
    let server = MockServer::start(extras_api(Arc::clone(&base))).await;
    base.set(server.base.clone()).unwrap();
    let (dir, launcher) = launcher(&server, &[ACC]).await;
    (server, dir, launcher)
}

#[tokio::test]
async fn rename_settings_logs_stats() {
    let (server, _dir, launcher) = extras().await;

    let s = launcher.craftinghosting_server().await.unwrap().unwrap();
    assert_eq!(s.delete_at.as_deref(), Some("2026-12-18T15:59:20.000Z"));
    assert!(!s.session.host_present);

    assert!(matches!(launcher.craftinghosting_rename("x").await, Err(Error::Validation(_))));
    assert_eq!(launcher.craftinghosting_rename(" Neue Welt ").await.unwrap().name, "Neue Welt");
    assert_eq!(server.hits("PATCH", "/v1/craftinghosting/server")[0].json(), json!({ "name": "Neue Welt" }));

    let settings = launcher.craftinghosting_settings().await.unwrap();
    assert_eq!(settings.level_type, "normal");
    assert_eq!(settings.max_players, 8);

    let bad = SettingsPatch { view_distance: Some(32), ..Default::default() };
    assert!(matches!(launcher.craftinghosting_save_settings(&bad).await, Err(Error::Validation(_))));
    assert!(matches!(launcher.craftinghosting_save_settings(&SettingsPatch::default()).await, Err(Error::Validation(_))));
    let patch = SettingsPatch { motd: Some(" Hallo ".into()), gamemode: Some("creative".into()), ..Default::default() };
    let saved = launcher.craftinghosting_save_settings(&patch).await.unwrap();
    assert_eq!(saved.gamemode, "creative");
    assert_eq!(
        server.hits("PATCH", "/v1/craftinghosting/server/settings")[0].json(),
        json!({ "motd": "Hallo", "gamemode": "creative" })
    );

    let logs = launcher.craftinghosting_logs(200).await.unwrap();
    assert_eq!(logs, ["[12:00:01] Done (6.1s)!", "[12:00:02] <Bob> hi"]);

    let stats = launcher.craftinghosting_stats().await.unwrap();
    assert_eq!(stats.cpu_percent, Some(12.5));
    assert_eq!(stats.memory.limit_bytes, Some(4_294_967_296));
    assert_eq!(stats.disk.used_bytes, None);
    assert_eq!(stats.players, None);
}

#[tokio::test]
async fn mods_are_checked_and_cleaned() {
    let (server, _dir, launcher) = extras().await;
    assert!(matches!(launcher.craftinghosting_mods(&[]).await, Err(Error::Validation(_))));
    assert!(matches!(launcher.craftinghosting_mods(&["../etc".into()]).await, Err(Error::Validation(_))));
    let result = launcher.craftinghosting_mods(&["lithium".into(), "sodium".into(), "lithium".into()]).await.unwrap();
    assert_eq!(result.installed, ["lithium"]);
    assert_eq!(result.skipped[0].reason, "client_only");
    assert_eq!(result.skipped[1].reason, "unsupported");
    assert_eq!(server.hits("POST", "/v1/craftinghosting/server/mods")[0].json(), json!({ "projects": ["lithium", "sodium"] }));
}

fn instance(folder: &str) -> WorldSource {
    WorldSource::Instance { instance_id: "inst".into(), folder: folder.into() }
}

#[tokio::test]
async fn world_is_zipped_uploaded_and_imported() {
    let (server, _dir, launcher) = extras().await;
    let world = launcher.paths().instance_game_dir("inst").join("saves").join("Welt");
    std::fs::create_dir_all(world.join("region")).unwrap();
    std::fs::write(world.join("level.dat"), b"level").unwrap();
    std::fs::write(world.join("region/r.0.0.mca"), vec![7u8; 4096]).unwrap();
    std::fs::write(world.join("session.lock"), b"x").unwrap();

    let phases = Arc::new(std::sync::Mutex::new(Vec::new()));
    let seen = Arc::clone(&phases);
    launcher
        .craftinghosting_upload_world(instance("Welt"), &UploadTarget::default(), move |p| seen.lock().unwrap().push((p.phase, p.percent)))
        .await
        .unwrap();

    let upload = &server.hits("POST", "/upload")[0];
    assert!(upload.bearer().is_none());
    let body = String::from_utf8_lossy(&upload.body);
    assert!(body.contains("name=\"files\"; filename=\"trs-world.zip\""));
    assert!(upload.body.windows(2).any(|w| w == b"PK"));
    assert!(body.contains("world/level.dat") && body.contains("world/region/r.0.0.mca") && !body.contains("session.lock"));
    assert_eq!(server.hits("POST", "/v1/craftinghosting/server/world/import")[0].json(), json!({ "file": "trs-world.zip" }));
    {
        let phases = phases.lock().unwrap();
        assert!(phases.contains(&("zip", 100)) && phases.contains(&("upload", 100)) && phases.contains(&("import", 100)));
    }
    // Das Archiv bleibt nicht liegen.
    let tmp = launcher.paths().root().join("tmp");
    assert_eq!(std::fs::read_dir(&tmp).map(Iterator::count).unwrap_or(0), 0);

    // Unbekannte Welt: nichts wird gesendet.
    assert!(launcher.craftinghosting_upload_world(instance("Fehlt"), &UploadTarget::default(), |_| {}).await.is_err());
    assert_eq!(server.hits("POST", "/v1/craftinghosting/server/world/upload").len(), 1);
}

#[tokio::test]
async fn setup_restart_keeps_the_session() {
    let api_state = state();
    let server = MockServer::start(api(Arc::clone(&api_state))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    launcher.craftinghosting_start(true).await.unwrap();

    // Einrichtungs-Neustart: Sitzung kurz inaktiv, aber der Launcher bleibt dran.
    api_state.restarting.store(true, Ordering::SeqCst);
    assert!(!launcher.craftinghosting_heartbeat().await);
    assert_eq!(launcher.trs.craftinghosting.active().as_deref(), Some(ACC));

    api_state.restarting.store(false, Ordering::SeqCst);
    assert!(!launcher.craftinghosting_heartbeat().await);
    assert_eq!(launcher.trs.craftinghosting.active().as_deref(), Some(ACC));
}

#[tokio::test]
async fn own_address_is_checked_and_changed() {
    let (server, _dir, launcher) = extras().await;
    const CHECK: &str = "/v1/craftinghosting/subdomain";

    let s = launcher.craftinghosting_server().await.unwrap().unwrap();
    assert_eq!(s.domain.as_deref(), Some("meinewelt.trs-launcher.de"));
    assert_eq!(s.ip.as_deref(), Some("1.2.3.4:25568"));

    let free = launcher.craftinghosting_check_subdomain(" MeineWelt ").await.unwrap();
    assert_eq!((free.name.as_str(), free.available, free.reason.as_deref()), ("meinewelt", true, None));
    let reserved = launcher.craftinghosting_check_subdomain("play").await.unwrap();
    assert_eq!((reserved.available, reserved.reason.as_deref()), (false, Some("reserved")));
    // Unbekannter Grund wird zu „vergeben“, kaputter Name zum angefragten.
    let weird = launcher.craftinghosting_check_subdomain("weird").await.unwrap();
    assert_eq!((weird.name.as_str(), weird.reason.as_deref()), ("weird", Some("taken")));

    // Ungültig: keine Anfrage.
    let before = server.hits("GET", CHECK).len();
    let bad = launcher.craftinghosting_check_subdomain("-ab").await.unwrap();
    assert_eq!((bad.available, bad.reason.as_deref()), (false, Some("invalid")));
    assert_eq!(server.hits("GET", CHECK).len(), before);

    let e = launcher.craftinghosting_check_subdomain("nodns").await.unwrap_err();
    assert_eq!(api_code(&e), ("trs_api", "dns_unavailable"));
    assert_eq!(e.message_code(), "trsApi.dns_unavailable");

    assert!(matches!(launcher.craftinghosting_set_subdomain("a b").await, Err(Error::Validation(_))));
    let changed = launcher.craftinghosting_set_subdomain("NeueWelt").await.unwrap();
    assert_eq!(changed.domain.as_deref(), Some("neuewelt.trs-launcher.de"));
    assert_eq!(server.hits("PATCH", "/v1/craftinghosting/server")[0].json(), json!({ "subdomain": "neuewelt" }));
    let e = launcher.craftinghosting_set_subdomain("taken").await.unwrap_err();
    assert_eq!(e.message_code(), "trsApi.subdomain_taken");
}

#[tokio::test]
async fn create_sends_the_address() {
    let server = MockServer::start(api(state())).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let mut spec = new_server("Meine Welt");
    spec.subdomain = Some("-bad".into());
    assert!(matches!(launcher.craftinghosting_create(&spec).await, Err(Error::Validation(_))));
    spec.subdomain = Some(" MeineWelt ".into());
    let created = launcher.craftinghosting_create(&spec).await.unwrap();
    assert_eq!(created.domain.as_deref(), Some("meinewelt.trs-launcher.de"));
    assert_eq!(server.hits("POST", "/v1/craftinghosting/server")[0].json()["subdomain"], json!("meinewelt"));
}

// --- Dateien, server.properties, Plugins/Mods -------------------------------------------

const FILES: &str = "/v1/craftinghosting/server/files";

fn files_api() -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        let path = req.path.as_str();
        match req.method.as_str() {
            "GET" if path == format!("{FILES}?dir=%2F") => Response::json(
                200,
                json!({ "dir": "/", "writable": true, "entries": [
                    { "name": "plugins", "path": "/plugins", "type": "directory", "size": 0, "modifiedAt": "2026-11-18T10:00:00.000Z", "editable": true, "protected": false },
                    { "name": "server.properties", "path": "/server.properties", "type": "file", "size": 1234, "modifiedAt": null, "editable": true, "protected": false },
                    { "name": "whitelist.json", "path": "/whitelist.json", "type": "file", "size": 50, "editable": true, "protected": true, "lock": "access" },
                    { "name": "paper.jar", "path": "/paper.jar", "type": "file", "size": 9, "editable": false, "viewable": false, "protected": true, "lock": "system" },
                    { "name": "eula.txt", "path": "/eula.txt", "type": "file", "size": 9, "editable": false, "viewable": true, "protected": true, "lock": "system" },
                    { "name": "odd-lock", "path": "/odd-lock", "type": "file", "size": 1, "editable": true, "viewable": true, "protected": false, "lock": "weird" },
                    { "name": "../evil", "path": "/../evil", "type": "file", "size": 1 },
                    { "name": "odd", "path": "../odd", "type": "file", "size": -5, "editable": false },
                    { "name": "link", "path": "/link", "type": "symlink" }
                ] }),
            ),
            "GET" if path == format!("{FILES}?dir=%2Fplugins") => Response::json(200, json!({ "dir": "/plugins", "entries": [] })),
            "GET" if path == format!("{FILES}?dir=%2Flibraries") => {
                Response::json(200, json!({ "dir": "/libraries", "writable": false, "entries": [] }))
            }
            "GET" if path == format!("{FILES}/content?path=%2Fserver.properties") => {
                Response::json(200, json!({ "path": "/server.properties", "content": "motd=Hallo\npvp=true\n" }))
            }
            "GET" if path == format!("{FILES}/content?path=%2Fworld%2Flevel.dat") => Response::error(415, "file_not_text"),
            "GET" if path == format!("{FILES}/content?path=%2Flogs%2Fbig.log") => Response::error(413, "file_too_large"),
            "PUT" if path == format!("{FILES}/content?path=%2Fserver.properties") => {
                let len = req.json()["content"].as_str().map_or(0, str::len);
                Response::json(200, json!({ "path": "/server.properties", "size": len }))
            }
            "PUT" if path == format!("{FILES}/content?path=%2Fwhitelist.json") => Response::error(403, "file_protected"),
            "POST" if path == format!("{FILES}/delete") => {
                let paths = req.json()["paths"].clone();
                if paths.as_array().is_some_and(|a| a.iter().any(|p| p == "/plugins/TRS-Server-Rules.jar")) {
                    return Response::error(403, "file_protected");
                }
                Response::empty(204)
            }
            "POST" if path == format!("{FILES}/folder") => Response::empty(204),
            "GET" if path == "/v1/craftinghosting/server/properties" => Response::json(
                200,
                json!({ "properties": { "motd": "Hallo", "pvp": "true", "max-players": 8, "Bad Key": "x", "server-port": "25568" },
                        "locked": ["server-port", "white-list", "server-port", "BAD"] }),
            ),
            "PATCH" if path == "/v1/craftinghosting/server/properties" => {
                let changes = req.json()["properties"].clone();
                if changes.get("server-port").is_some() {
                    return Response::error(400, "property_locked");
                }
                let mut props = json!({ "motd": "Hallo", "pvp": "true", "server-port": "25568" });
                for (k, v) in changes.as_object().unwrap() {
                    props[k] = v.clone();
                }
                Response::json(200, json!({ "properties": props, "locked": ["server-port"] }))
            }
            "GET" if path == "/v1/craftinghosting/server/addons" => Response::json(
                200,
                json!({ "kind": "plugins", "dir": "/plugins", "items": [
                    { "file": "TRS-Server-Rules.jar", "size": 2048, "modifiedAt": "2026-11-18T10:00:00.000Z", "protected": true },
                    { "file": "LuckPerms-Bukkit-5.4.102.jar", "size": 1_500_000, "protected": false },
                    { "file": "../x.jar", "size": 1 }
                ] }),
            ),
            "DELETE" if path == "/v1/craftinghosting/server/addons?file=LuckPerms-Bukkit-5.4.102.jar" => Response::empty(204),
            "DELETE" if path == "/v1/craftinghosting/server/addons?file=TRS-Server-Rules.jar" => Response::error(403, "file_protected"),
            _ => Response::error(404, "not_found"),
        }
    }
}

async fn files() -> (MockServer, tempfile::TempDir, Arc<crate::Launcher>) {
    let server = MockServer::start(files_api()).await;
    let (dir, launcher) = launcher(&server, &[ACC]).await;
    (server, dir, launcher)
}

#[tokio::test]
async fn files_are_listed_read_and_saved() {
    let (server, _dir, launcher) = files().await;

    let root = launcher.craftinghosting_files("/").await.unwrap();
    assert_eq!(root.dir, "/");
    let names: Vec<&str> = root.entries.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, ["plugins", "server.properties", "whitelist.json", "paper.jar", "eula.txt", "odd-lock", "odd"]);
    assert!(root.writable);
    let e = |name: &str| root.entries.iter().find(|x| x.name == name).unwrap();
    // Die API entscheidet: Ordner nie editierbar, gesperrte nur lesbar (wenn `viewable`), Grund wie geliefert.
    assert!(!e("plugins").editable && !e("plugins").viewable);
    assert!(e("server.properties").editable && e("server.properties").viewable && e("server.properties").lock.is_none());
    assert!(e("whitelist.json").protected && !e("whitelist.json").editable && e("whitelist.json").viewable);
    assert_eq!(e("whitelist.json").lock.as_deref(), Some("access"));
    assert!(!e("paper.jar").viewable && e("paper.jar").lock.as_deref() == Some("system"));
    assert!(e("eula.txt").viewable && !e("eula.txt").editable);
    // Unbekannter Grund = allgemeine Sperre.
    assert!(e("odd-lock").protected && !e("odd-lock").editable && e("odd-lock").lock.as_deref() == Some("other"));
    assert_eq!((e("odd").path.as_str(), e("odd").size), ("/odd", 0));

    let plugins = launcher.craftinghosting_files("/plugins/").await.unwrap();
    assert_eq!(plugins.dir, "/plugins");
    // Alte API ohne `writable`: erlaubt.
    assert!(plugins.writable);
    assert!(!launcher.craftinghosting_files("/libraries").await.unwrap().writable);
    let before = server.requests().len();
    for bad in ["plugins", "/../etc", "/a/./b", "C:\\x"] {
        assert!(matches!(launcher.craftinghosting_files(bad).await, Err(Error::Validation(_))), "{bad}");
    }
    assert_eq!(server.requests().len(), before);

    let file = launcher.craftinghosting_file("/server.properties").await.unwrap();
    assert_eq!(file.content, "motd=Hallo\npvp=true\n");
    let e = launcher.craftinghosting_file("/world/level.dat").await.unwrap_err();
    assert_eq!(e.message_code(), "trsApi.file_not_text");
    let e = launcher.craftinghosting_file("/logs/big.log").await.unwrap_err();
    assert_eq!(e.message_code(), "trsApi.file_too_large");
    assert!(matches!(launcher.craftinghosting_file("/").await, Err(Error::Validation(_))));

    let saved = launcher.craftinghosting_save_file("/server.properties", "motd=Neu\n").await.unwrap();
    assert_eq!((saved.path.as_str(), saved.size), ("/server.properties", 9));
    let puts = server.hits("PUT", &format!("{FILES}/content"));
    let put = puts.iter().find(|r| r.path.ends_with("?path=%2Fserver.properties")).unwrap();
    assert_eq!(put.json(), json!({ "content": "motd=Neu\n" }));
    let e = launcher.craftinghosting_save_file("/whitelist.json", "[]").await.unwrap_err();
    assert_eq!(api_code(&e), ("trs_api", "file_protected"));
    assert_eq!(e.message_code(), "trsApi.file_protected");
    let big = "x".repeat(1024 * 1024 + 1);
    assert!(matches!(launcher.craftinghosting_save_file("/server.properties", &big).await, Err(Error::Validation(_))));
}

#[tokio::test]
async fn files_are_deleted_and_folders_created() {
    let (server, _dir, launcher) = files().await;
    let delete = format!("{FILES}/delete");

    assert!(matches!(launcher.craftinghosting_delete_files(&[]).await, Err(Error::Validation(_))));
    assert!(matches!(launcher.craftinghosting_delete_files(&["/".into()]).await, Err(Error::Validation(_))));
    let many: Vec<String> = (0..51).map(|i| format!("/f{i}")).collect();
    assert!(matches!(launcher.craftinghosting_delete_files(&many).await, Err(Error::Validation(_))));
    assert!(server.hits("POST", &delete).is_empty());

    launcher.craftinghosting_delete_files(&["/a.txt".into(), "/logs/".into(), "/a.txt".into()]).await.unwrap();
    assert_eq!(server.hits("POST", &delete)[0].json(), json!({ "paths": ["/a.txt", "/logs"] }));
    let e = launcher.craftinghosting_delete_files(&["/plugins/TRS-Server-Rules.jar".into()]).await.unwrap_err();
    assert_eq!(e.message_code(), "trsApi.file_protected");

    launcher.craftinghosting_create_folder("/plugins/Neu").await.unwrap();
    assert_eq!(server.hits("POST", &format!("{FILES}/folder"))[0].json(), json!({ "path": "/plugins/Neu" }));
    assert!(matches!(launcher.craftinghosting_create_folder("/a/../b").await, Err(Error::Validation(_))));
}

#[tokio::test]
async fn properties_are_cleaned_and_patched() {
    let (server, _dir, launcher) = files().await;
    const PROPS: &str = "/v1/craftinghosting/server/properties";
    let props = launcher.craftinghosting_properties().await.unwrap();
    assert_eq!(props.properties.get("max-players").map(String::as_str), Some("8"));
    assert!(!props.properties.contains_key("Bad Key"));
    assert_eq!(props.locked, ["server-port", "white-list"]);

    let mut changes = std::collections::BTreeMap::new();
    changes.insert("pvp".to_owned(), "false".to_owned());
    let saved = launcher.craftinghosting_save_properties(&changes).await.unwrap();
    assert_eq!(saved.properties.get("pvp").map(String::as_str), Some("false"));
    assert_eq!(server.hits("PATCH", PROPS)[0].json(), json!({ "properties": { "pvp": "false" } }));

    changes.insert("server-port".to_owned(), "1".to_owned());
    let e = launcher.craftinghosting_save_properties(&changes).await.unwrap_err();
    assert_eq!(e.message_code(), "trsApi.property_locked");

    let before = server.hits("PATCH", PROPS).len();
    for (key, value) in [("motd", "a\nb"), ("Motd", "a")] {
        let mut bad = std::collections::BTreeMap::new();
        bad.insert(key.to_owned(), value.to_owned());
        assert!(matches!(launcher.craftinghosting_save_properties(&bad).await, Err(Error::Validation(_))), "{key}");
    }
    assert_eq!(server.hits("PATCH", PROPS).len(), before);
}

#[tokio::test]
async fn addons_are_listed_and_removed() {
    let (server, _dir, launcher) = files().await;
    let addons = launcher.craftinghosting_addons().await.unwrap();
    assert_eq!(addons.kind.as_deref(), Some("plugins"));
    assert_eq!(addons.dir, "/plugins");
    let files: Vec<&str> = addons.items.iter().map(|a| a.file.as_str()).collect();
    assert_eq!(files, ["TRS-Server-Rules.jar", "LuckPerms-Bukkit-5.4.102.jar"]);
    assert!(addons.items[0].protected);

    launcher.craftinghosting_remove_addon("LuckPerms-Bukkit-5.4.102.jar").await.unwrap();
    assert_eq!(server.hits("DELETE", "/v1/craftinghosting/server/addons")[0].path, "/v1/craftinghosting/server/addons?file=LuckPerms-Bukkit-5.4.102.jar");
    let e = launcher.craftinghosting_remove_addon("TRS-Server-Rules.jar").await.unwrap_err();
    assert_eq!(e.message_code(), "trsApi.file_protected");
    assert!(matches!(launcher.craftinghosting_remove_addon("../x.jar").await, Err(Error::Validation(_))));
}

// --- Welten ------------------------------------------------------------------------------

fn worlds_api(base: Arc<std::sync::OnceLock<String>>, running: Arc<AtomicBool>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        // Dateien kommen direkt vom Partner – nie mit TRS-Token.
        if req.path.starts_with("/dl/") || req.path == "/upload" {
            if req.bearer().is_some() {
                return Response::error(400, "token_leak");
            }
            return match req.path.as_str() {
                "/dl/world/level.dat" => Response { status: 200, headers: Vec::new(), body: b"LEVEL".to_vec() },
                "/dl/world/region/r.0.0.mca" => Response { status: 200, headers: Vec::new(), body: vec![7u8; 5000] },
                "/dl/world_nether/DIM-1/region/r.0.0.mca" => Response { status: 200, headers: Vec::new(), body: vec![9u8; 300] },
                "/upload" => Response::empty(200),
                _ => Response::error(404, "not_found"),
            };
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        let worlds = json!({ "active": "world", "exists": true, "worlds": [
            { "name": "world", "active": true, "dimensions": ["overworld", "nether", "the_end"] },
            { "name": "skyblock", "active": false, "dimensions": ["overworld", "<b>"] },
            { "name": "../evil", "active": false, "dimensions": [] }
        ] });
        let base = base.get().cloned().unwrap_or_default();
        let path = req.path.as_str();
        match req.method.as_str() {
            "GET" if path == "/v1/craftinghosting/server" => {
                let mut s = server(false);
                if running.load(Ordering::SeqCst) {
                    s["state"] = json!("running");
                }
                Response::json(200, json!({ "server": s }))
            }
            "GET" if path == "/v1/craftinghosting/server/worlds" => Response::json(200, worlds),
            "PATCH" if path == "/v1/craftinghosting/server/worlds" => {
                if running.load(Ordering::SeqCst) {
                    return Response::error(409, "server_running");
                }
                let mut w = worlds.clone();
                w["active"] = req.json()["active"].clone();
                Response::json(200, w)
            }
            "DELETE" if path == "/v1/craftinghosting/server/worlds?name=skyblock" => {
                Response::json(200, json!({ "active": "world", "exists": true, "worlds": [worlds["worlds"][0].clone()] }))
            }
            "GET" if path == "/v1/craftinghosting/server/world/files?name=world" => Response::json(
                200,
                json!({ "name": "world", "truncated": false, "files": [
                    { "path": "/world/level.dat", "size": 5 },
                    { "path": "/world/region/r.0.0.mca", "size": 5000 },
                    { "path": "/world_nether/DIM-1/region/r.0.0.mca", "size": 300 },
                    { "path": "/world/session.lock", "size": 3 },
                    { "path": "/server.properties", "size": 10 },
                    { "path": "/world/../x", "size": 1 }
                ] }),
            ),
            "GET" if path == "/v1/craftinghosting/server/world/files?name=evil" => Response::json(
                200,
                json!({ "name": "evil", "truncated": true, "files": [{ "path": "/evil/level.dat", "size": 5 }] }),
            ),
            "POST" if path == "/v1/craftinghosting/server/files/download-urls" => {
                let paths: Vec<String> = req.json()["paths"].as_array().unwrap().iter().map(|p| p.as_str().unwrap().to_owned()).collect();
                let urls: Vec<serde_json::Value> = paths
                    .iter()
                    .map(|p| {
                        let url = if p.starts_with("/evil") { "https://evil.com/x".to_owned() } else { format!("{base}/dl{p}") };
                        json!({ "path": p, "url": url })
                    })
                    .collect();
                Response::json(200, json!({ "urls": urls, "expiresAt": "2026-11-18T16:10:00.000Z" }))
            }
            "POST" if path == "/v1/craftinghosting/server/world/upload" => {
                Response::json(200, json!({ "url": format!("{base}/upload"), "expiresAt": "2026-11-18T16:10:00.000Z" }))
            }
            "POST" if path == "/v1/craftinghosting/server/world/import" => Response::json(200, json!({ "server": server(false) })),
            _ => Response::error(404, "not_found"),
        }
    }
}

async fn worlds_setup() -> (MockServer, tempfile::TempDir, Arc<crate::Launcher>, Arc<AtomicBool>) {
    let base = Arc::new(std::sync::OnceLock::new());
    let running = Arc::new(AtomicBool::new(false));
    let server = MockServer::start(worlds_api(Arc::clone(&base), Arc::clone(&running))).await;
    base.set(server.base.clone()).unwrap();
    let (dir, launcher) = launcher(&server, &[ACC]).await;
    (server, dir, launcher, running)
}

#[tokio::test]
async fn worlds_are_listed_activated_and_deleted() {
    let (server, _dir, launcher, running) = worlds_setup().await;
    let worlds = launcher.craftinghosting_worlds().await.unwrap();
    assert_eq!(worlds.active, "world");
    let names: Vec<&str> = worlds.worlds.iter().map(|w| w.name.as_str()).collect();
    assert_eq!(names, ["world", "skyblock"]);
    assert_eq!(worlds.worlds[0].dimensions, ["overworld", "nether", "the_end"]);
    assert_eq!(worlds.worlds[1].dimensions, ["overworld"]);

    let set = launcher.craftinghosting_set_active_world("skyblock").await.unwrap();
    assert_eq!(set.active, "skyblock");
    assert_eq!(server.hits("PATCH", "/v1/craftinghosting/server/worlds")[0].json(), json!({ "active": "skyblock" }));
    assert!(matches!(launcher.craftinghosting_set_active_world("a b").await, Err(Error::Validation(_))));

    running.store(true, Ordering::SeqCst);
    let e = launcher.craftinghosting_set_active_world("world").await.unwrap_err();
    assert_eq!(e.message_code(), "trsApi.server_running");
    running.store(false, Ordering::SeqCst);

    let after = launcher.craftinghosting_delete_world("skyblock").await.unwrap();
    assert_eq!(after.worlds.len(), 1);
    assert!(matches!(launcher.craftinghosting_delete_world("../x").await, Err(Error::Validation(_))));
}

#[tokio::test]
async fn world_is_downloaded_into_a_zip() {
    let (server, dir, launcher, _) = worlds_setup().await;
    let dest = dir.path().join("Meine-Welt-world.zip");
    let phases = Arc::new(std::sync::Mutex::new(Vec::new()));
    let seen = Arc::clone(&phases);
    let result = launcher
        .craftinghosting_download_world("world", &dest, move |p| seen.lock().unwrap().push((p.phase, p.percent)))
        .await
        .unwrap();
    assert_eq!((result.files, result.bytes, result.truncated), (3, 5305, false));
    assert_eq!(result.file_name, "Meine-Welt-world.zip");
    assert!(phases.lock().unwrap().contains(&("download", 100)));

    // Nur Weltdateien, ohne session.lock und fremde Pfade; eine Anfrage nach Adressen.
    let urls = server.hits("POST", "/v1/craftinghosting/server/files/download-urls");
    assert_eq!(urls.len(), 1);
    assert_eq!(
        urls[0].json(),
        json!({ "paths": ["/world/level.dat", "/world/region/r.0.0.mca", "/world_nether/DIM-1/region/r.0.0.mca"] })
    );
    for hit in server.requests().iter().filter(|r| r.path.starts_with("/dl/")) {
        assert!(hit.bearer().is_none());
    }

    let mut zip = zip::ZipArchive::new(std::fs::File::open(&dest).unwrap()).unwrap();
    let mut names: Vec<String> = zip.file_names().filter_map(Result::ok).map(|n| n.to_string()).collect();
    names.sort();
    assert_eq!(names, ["world/level.dat", "world/region/r.0.0.mca", "world_nether/DIM-1/region/r.0.0.mca"]);
    let mut level = String::new();
    std::io::Read::read_to_string(&mut zip.by_name("world/level.dat").unwrap(), &mut level).unwrap();
    assert_eq!(level, "LEVEL");
    assert!(!dir.path().join("Meine-Welt-world.zip.part").exists());
}

#[tokio::test]
async fn foreign_download_hosts_are_refused() {
    let (server, dir, launcher, _) = worlds_setup().await;
    let dest = dir.path().join("evil.zip");
    assert!(launcher.craftinghosting_download_world("evil", &dest, |_| {}).await.is_err());
    assert!(!dest.exists());
    assert!(!dir.path().join("evil.zip.part").exists());
    assert!(server.requests().iter().all(|r| !r.path.starts_with("/x")));
    assert!(matches!(launcher.craftinghosting_download_world("a/b", &dest, |_| {}).await, Err(Error::Validation(_))));
}

#[tokio::test]
async fn named_world_from_a_zip_is_uploaded() {
    let (server, dir, launcher, running) = worlds_setup().await;
    // ZIP mit der Welt eine Ebene tiefer – oben landet der Zielname.
    let src = dir.path().join("download.zip");
    {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&src).unwrap());
        let options = zip::write::SimpleFileOptions::default();
        for (name, data) in [("Backup/Insel/level.dat", &b"L"[..]), ("Backup/Insel/region/r.0.0.mca", b"R"), ("Backup/Insel/session.lock", b"x"), ("Backup/readme.txt", b"t")] {
            zip.start_file(name, options).unwrap();
            std::io::Write::write_all(&mut zip, data).unwrap();
        }
        zip.finish().unwrap();
    }
    let target = UploadTarget { name: Some("insel".into()), activate: true };
    launcher.craftinghosting_upload_world(WorldSource::Zip(src.clone()), &target, |_| {}).await.unwrap();
    let upload = &server.hits("POST", "/upload")[0];
    let body = String::from_utf8_lossy(&upload.body);
    assert!(body.contains("insel/level.dat") && body.contains("insel/region/r.0.0.mca"));
    assert!(!body.contains("session.lock") && !body.contains("readme.txt") && !body.contains("Backup/"));
    assert_eq!(
        server.hits("POST", "/v1/craftinghosting/server/world/import")[0].json(),
        json!({ "file": "trs-world.zip", "name": "insel", "activate": true })
    );

    // Keine Welt in der ZIP, ungültiger Name, laufender Server: nichts wird hochgeladen.
    let empty = dir.path().join("empty.zip");
    {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&empty).unwrap());
        zip.start_file("a.txt", zip::write::SimpleFileOptions::default()).unwrap();
        zip.finish().unwrap();
    }
    assert!(matches!(launcher.craftinghosting_upload_world(WorldSource::Zip(empty), &target, |_| {}).await, Err(Error::Validation(_))));
    let bad = UploadTarget { name: Some("a b".into()), activate: false };
    assert!(matches!(launcher.craftinghosting_upload_world(WorldSource::Zip(src.clone()), &bad, |_| {}).await, Err(Error::Validation(_))));
    running.store(true, Ordering::SeqCst);
    assert!(matches!(launcher.craftinghosting_upload_world(WorldSource::Zip(src), &target, |_| {}).await, Err(Error::Validation(_))));
    assert_eq!(server.hits("POST", "/upload").len(), 1);
}

#[tokio::test]
async fn pinned_versions_are_sent_as_versions() {
    let (server, _dir, launcher) = extras().await;
    let pick = |project: &str, version: &str| VersionPick { project: project.into(), version: version.into() };
    assert!(matches!(launcher.craftinghosting_install_versions(&[]).await, Err(Error::Validation(_))));
    assert!(matches!(launcher.craftinghosting_install_versions(&[pick("lithium", "../x")]).await, Err(Error::Validation(_))));
    let many: Vec<VersionPick> = (0..21).map(|i| pick("lithium", &format!("v{i}"))).collect();
    assert!(matches!(launcher.craftinghosting_install_versions(&many).await, Err(Error::Validation(_))));
    assert!(server.hits("POST", "/v1/craftinghosting/server/mods").is_empty());

    let result = launcher
        .craftinghosting_install_versions(&[pick(" lithium ", "Yp8wLY1P"), pick("lithium", "Yp8wLY1P"), pick("AANobbMI", "abc123")])
        .await
        .unwrap();
    assert_eq!(result.installed, ["lithium"]);
    assert_eq!(
        server.hits("POST", "/v1/craftinghosting/server/mods")[0].json(),
        json!({ "versions": [{ "project": "lithium", "version": "Yp8wLY1P" }, { "project": "AANobbMI", "version": "abc123" }] })
    );
}

// --- Funktions-Schalter: Versionswechsel und Backups --------------------------------------

fn backups_api(base: Arc<std::sync::OnceLock<String>>, enabled: Arc<AtomicBool>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if req.path == "/dl/backup.zip" {
            if req.bearer().is_some() {
                return Response::error(400, "token_leak");
            }
            return Response { status: 200, headers: Vec::new(), body: vec![5u8; 2048] };
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        if !enabled.load(Ordering::SeqCst) {
            return Response::error(501, "feature_unavailable");
        }
        let base = base.get().cloned().unwrap_or_default();
        match (req.method.as_str(), req.path.as_str()) {
            ("POST", "/v1/craftinghosting/server/version") => Response::json(200, json!({ "server": server(false) })),
            ("GET", "/v1/craftinghosting/server/backups") => Response::json(
                200,
                json!({ "max": 3, "backups": [
                    { "id": "b1", "name": "Vor dem Update", "size": 2048, "createdAt": "2026-11-18T20:00:00.000Z" },
                    { "id": "../x", "name": "kaputt", "size": 1 }
                ] }),
            ),
            ("POST", "/v1/craftinghosting/server/backups") => Response::json(200, json!({ "ok": true })),
            ("POST", "/v1/craftinghosting/server/backups/b1/restore") => Response::json(200, json!({ "ok": true })),
            ("DELETE", "/v1/craftinghosting/server/backups/b1") => Response::empty(204),
            ("GET", "/v1/craftinghosting/server/backups/b1/download") => {
                Response::json(200, json!({ "url": format!("{base}/dl/backup.zip"), "expiresAt": "2026-11-18T21:00:00.000Z" }))
            }
            ("GET", "/v1/craftinghosting/server/backups/evil/download") => {
                Response::json(200, json!({ "url": "https://evil.com/backup.zip", "expiresAt": "2026-11-18T21:00:00.000Z" }))
            }
            _ => Response::error(404, "not_found"),
        }
    }
}

#[tokio::test]
async fn switched_off_features_answer_not_available() {
    let base = Arc::new(std::sync::OnceLock::new());
    let enabled = Arc::new(AtomicBool::new(false));
    let server = MockServer::start(backups_api(Arc::clone(&base), Arc::clone(&enabled))).await;
    base.set(server.base.clone()).unwrap();
    let (dir, launcher) = launcher(&server, &[ACC]).await;

    // Schalter aus: eigener Fehler, kein „TRS-Server offline“.
    let e = launcher.craftinghosting_backups().await.unwrap_err();
    assert_eq!(api_code(&e), ("trs_api", "feature_unavailable"));
    assert_eq!(e.message_code(), "trsApi.feature_unavailable");

    enabled.store(true, Ordering::SeqCst);
    use super::craftinghosting_backups::VersionChange;
    let change = VersionChange { server_type: "purpur".into(), version: "1.21.11".into(), keep_world: false };
    launcher.craftinghosting_change_version(&change).await.unwrap();
    assert_eq!(
        server.hits("POST", "/v1/craftinghosting/server/version")[0].json(),
        json!({ "serverType": "purpur", "version": "1.21.11", "keepWorld": false })
    );

    let list = launcher.craftinghosting_backups().await.unwrap();
    assert_eq!((list.max, list.backups.len()), (3, 1));
    assert_eq!(list.backups[0].name, "Vor dem Update");
    launcher.craftinghosting_create_backup().await.unwrap();
    launcher.craftinghosting_restore_backup("b1").await.unwrap();
    launcher.craftinghosting_delete_backup("b1").await.unwrap();
    assert!(matches!(launcher.craftinghosting_restore_backup("../x").await, Err(Error::Validation(_))));

    let dest = dir.path().join("backup.zip");
    assert_eq!(launcher.craftinghosting_download_backup("b1", &dest, |_| {}).await.unwrap(), 2048);
    assert_eq!(std::fs::read(&dest).unwrap().len(), 2048);
    assert!(server.hits("GET", "/dl/backup.zip")[0].bearer().is_none());
    let evil = dir.path().join("evil.zip");
    assert!(launcher.craftinghosting_download_backup("evil", &evil, |_| {}).await.is_err());
    assert!(!evil.exists());
}

#[test]
fn eula_from_the_wizard_goes_into_the_create_body() {
    let mut spec = new_server("Meine Welt");
    assert!(spec.body().unwrap().get("acceptEula").is_none());
    spec.accept_eula = true;
    assert_eq!(spec.body().unwrap()["acceptEula"], true);
}

#[test]
fn quota_and_rules_from_the_api_reach_the_launcher() {
    let q = quota(&json!({ "usedSeconds": 1200, "budgetSeconds": 86400, "remainingSeconds": 999_999, "weekUsedSeconds": 5,
        "weekCapSeconds": 576000, "weekResetsAt": "2026-11-23T23:00:00.000Z", "idleResetAt": "bad", "cooldownUntil": null }))
    .unwrap();
    assert_eq!((q.used_seconds, q.remaining_seconds, q.week_cap_seconds), (1200, 86400, 576000));
    assert_eq!(q.idle_reset_at, None);
    assert!(quota(&json!({ "budgetSeconds": 0 })).is_none());
    assert!(quota(&json!(null)).is_none());
    let r = rules(&json!({ "budgetHours": 24, "cooldownHours": 2, "idleResetDays": 3, "weeklyOfflineHours": 8, "warnMinutes": 30 })).unwrap();
    assert_eq!((r.budget_hours, r.idle_reset_days, r.warn_minutes), (24, 3, 30));
    assert!(rules(&json!({ "budgetHours": 0 })).is_none());
}

#[test]
fn display_from_the_api_is_passed_through_within_limits() {
    assert!(passthrough(json!({ "runtime": null, "lines": [], "alerts": [] }), false).is_some());
    assert!(passthrough(json!([{ "icon": "clock", "text": { "en": "x" } }]), true).is_some());
    assert!(passthrough(json!("text"), false).is_none());
    assert!(passthrough(json!({ "a": "x".repeat(40_000) }), false).is_none());
}
