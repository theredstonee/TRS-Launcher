//! CraftingHosting (§43) gegen eine nachgebaute API: Anlegen, Fehler, Heartbeat, Konsole.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use serde_json::json;

use super::craftinghosting::{NewServer, SettingsPatch};
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
        "createdAt": "2026-11-18T15:59:20.000Z", "lastStartAt": null
    })
}

struct Api {
    /// Läuft die Sitzung auf dem Server?
    running: AtomicBool,
    /// Antwortet `start` mit `cooldown`?
    cooldown: AtomicBool,
    /// Antwortet alles mit `craftinghosting_unavailable`?
    unavailable: AtomicBool,
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
                    "full": false
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
                if running {
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
    Arc::new(Api { running: AtomicBool::new(false), cooldown: AtomicBool::new(false), unavailable: AtomicBool::new(false) })
}

fn new_server(name: &str) -> NewServer {
    NewServer { name: name.into(), server_type: "paper".into(), version: "latest".into(), access: "friends".into() }
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
                let mut s = server(false);
                s["name"] = req.json()["name"].clone();
                Response::json(200, json!({ "server": s }))
            }
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
        .craftinghosting_upload_world("inst", "Welt", move |p| seen.lock().unwrap().push((p.phase, p.percent)))
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
    assert!(launcher.craftinghosting_upload_world("inst", "Fehlt", |_| {}).await.is_err());
    assert_eq!(server.hits("POST", "/v1/craftinghosting/server/world/upload").len(), 1);
}
