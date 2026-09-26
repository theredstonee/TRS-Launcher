//! Welt-Hosting (§21) gegen eine nachgebaute API: Beitreten, Anfragen, Listen,
//! Fehler – und dass Relay-Token/STUN nie aus dem Kern herauskommen.

use std::sync::atomic::AtomicUsize;

use serde_json::json;

use super::hosting::{HostedWorld, JoinTarget};
use super::testkit::{MockServer, Request, Response};
use super::tests::{ACC, auth_routes, launcher};
use crate::instance::{Loader, LoaderKind, NewInstance};
use crate::{Error, Join};

const ROOM: &str = "h0123456789abcdef0123";
const OPEN: &str = "h00000000000000000042";
const HOST: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

fn room(id: &str, my_state: Option<&str>) -> serde_json::Value {
    json!({ "id": id, "name": "Insel", "host": { "uuid": HOST, "name": "Bob" }, "mcVersion": "1.21.11", "loader": "fabric",
            "maxPlayers": 4, "gameMode": "survival", "pvp": true, "cheats": false, "open": true, "players": 2,
            "createdAt": "2026-09-26T10:00:00.000Z", "myState": my_state })
}

fn connect_info() -> serde_json::Value {
    json!({ "role": "guest",
            "relay": { "host": "relay.theredstonee.de", "tcpPort": 25503, "udpPort": 25504, "token": "trsr1.SECRET.SIG", "expiresAt": "…" },
            "stun": ["relay.theredstonee.de:25504"] })
}

fn hosting_api() -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        match (req.method.as_str(), req.path.as_str()) {
            ("GET", "/v1/hosting/friends-rooms") => Response::json(
                200,
                json!({ "rooms": [room(ROOM, Some("invited")), room(OPEN, None), room(ROOM, Some("invited")), { "id": "h1" }] }),
            ),
            ("GET", "/v1/hosting/rooms/mine") => {
                let mut mine = room("h0000000000000000000a", None);
                mine["host"] = json!({ "uuid": ACC, "name": "Theredstonee" });
                mine["code"] = json!("K7QM2X");
                mine["visibility"] = json!("friends");
                mine["members"] = json!([{ "uuid": HOST, "name": "Bob", "state": "requested", "since": "…" }]);
                Response::json(200, json!({ "rooms": [mine] }))
            }
            ("GET", p) if p == format!("/v1/hosting/rooms/{ROOM}") => Response::json(200, json!({ "room": room(ROOM, Some("accepted")) })),
            ("GET", p) if p.starts_with("/v1/hosting/rooms/") => Response::error(404, "room_not_found"),
            ("POST", "/v1/hosting/join") => {
                let body = req.json();
                match (body["roomId"].as_str(), body["code"].as_str()) {
                    // Eingeladen → sofort drin (mit Verbindungsdaten, die der Launcher verwerfen muss).
                    (Some(ROOM), None) => {
                        let mut ok = connect_info();
                        ok["status"] = json!("accepted");
                        ok["room"] = room(ROOM, Some("accepted"));
                        Response::json(200, ok)
                    }
                    (None, Some("K7QM2X")) => {
                        Response::json(202, json!({ "status": "requested", "room": room(OPEN, Some("requested")) }))
                    }
                    (None, Some("FXMM22")) => Response::error(409, "room_full"),
                    (None, Some("WQTT22")) => Response::error(409, "too_many_requests"),
                    (None, Some("DNWN22")) => Response::error(503, "hosting_unavailable"),
                    _ => Response::error(404, "room_not_found"),
                }
            }
            ("POST", p) if p.ends_with("/leave") => {
                if p.contains(ROOM) { Response::empty(204) } else { Response::error(404, "room_not_found") }
            }
            _ => Response::error(404, "not_found"),
        }
    }
}

#[tokio::test]
async fn joining_follows_the_contract_and_keeps_tokens_inside() {
    let server = MockServer::start(hosting_api()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    // Eingeladen (Raum-ID): 200 → drin.
    let joined = launcher.hosting_join(&JoinTarget { room_id: Some(ROOM.into()), code: None }).await.unwrap();
    assert_eq!(joined.status, "accepted");
    assert_eq!(joined.room.my_state.as_deref(), Some("accepted"));
    let out = serde_json::to_string(&joined).unwrap();
    assert!(!out.contains("trsr1") && !out.contains("relay") && !out.contains("stun"), "keine Verbindungsdaten ans Webview: {out}");
    assert_eq!(server.hits("POST", "/v1/hosting/join")[0].json(), json!({ "roomId": ROOM }));

    // Code (Karte/Eingabe): 202 → Anfrage wartet auf den Host.
    let requested = launcher.hosting_join(&JoinTarget { room_id: None, code: Some("k7q-m2x".into()) }).await.unwrap();
    assert_eq!((requested.status.as_str(), requested.room.id.as_str()), ("requested", OPEN));
    assert_eq!(server.hits("POST", "/v1/hosting/join")[1].json(), json!({ "code": "K7QM2X" }));

    // Ungültige Eingaben gehen gar nicht erst raus.
    let before = server.hits("POST", "/v1/hosting/join").len();
    assert!(launcher.hosting_join(&JoinTarget { room_id: None, code: Some("0000".into()) }).await.is_err());
    assert!(launcher.hosting_join(&JoinTarget { room_id: Some("h/../x".into()), code: None }).await.is_err());
    assert_eq!(server.hits("POST", "/v1/hosting/join").len(), before);

    // Fehler mit eigenen Meldungen.
    let code = |c: &str| JoinTarget { room_id: None, code: Some(c.into()) };
    let full = launcher.hosting_join(&code("FXMM22")).await.unwrap_err();
    assert_eq!((full.kind(), full.message_code()), ("trs_api", "trsApi.room_full"));
    let wait = launcher.hosting_join(&code("WQTT22")).await.unwrap_err();
    assert_eq!(wait.message_code(), "trsApi.world_too_many_requests", "nicht die Meldung der Freundschaftsanfragen");
    let down = launcher.hosting_join(&code("DNWN22")).await.unwrap_err();
    assert_eq!((down.kind(), down.code()), ("trs_api", Some("hosting_unavailable")), "503 ohne Relay ist kein „offline“");
    let gone = launcher.hosting_join(&code("ZZZZZZ")).await.unwrap_err();
    assert_eq!(gone.message_code(), "trsApi.room_not_found");
}

#[tokio::test]
async fn lists_rooms_and_leaving() {
    let server = MockServer::start(hosting_api()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let rooms = launcher.hosting_friends_rooms().await.unwrap();
    assert_eq!(rooms.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(), [ROOM, OPEN], "doppelte und kaputte fallen weg");
    assert_eq!(rooms[0].my_state.as_deref(), Some("invited"));
    assert!(rooms[1].my_state.is_none());

    let mine = launcher.hosting_my_rooms().await.unwrap();
    assert_eq!(mine[0].code.as_deref(), Some("K7QM2X"));
    assert_eq!(mine[0].members[0].state, "requested");

    assert_eq!(launcher.hosting_room(ROOM).await.unwrap().unwrap().my_state.as_deref(), Some("accepted"));
    assert!(launcher.hosting_room(OPEN).await.unwrap().is_none(), "404 = Welt zu");
    assert!(launcher.hosting_room("nope").await.is_err());

    launcher.hosting_leave(ROOM).await.unwrap();
    launcher.hosting_leave(OPEN).await.unwrap(); // schon weg → auch gut
}

fn world(version: &str, loader: &str) -> HostedWorld {
    HostedWorld {
        room_id: ROOM.into(),
        code: Some("K7QM2X".into()),
        name: "Insel".into(),
        host: None,
        mc_version: version.into(),
        loader: loader.into(),
    }
}

#[tokio::test]
async fn launching_into_a_world_needs_a_matching_instance() {
    let server = MockServer::start(hosting_api()).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let forge = launcher
        .instances
        .create(NewInstance {
            name: "Forge".into(),
            game_version: "1.21.11".into(),
            loader: Loader { kind: LoaderKind::Forge, version: None },
        })
        .await
        .unwrap();
    let noop = |_| {};

    // Falscher Loader: abgelehnt, bevor irgendetwas geladen wird.
    let w = world("1.21.11", "fabric");
    let err = launcher.launch(&forge.id, Some(Join::World(&w)), &noop).await.unwrap_err();
    assert_eq!(err.message_code(), "hosting.wrongInstance");
    // Falsche Version.
    let w = world("1.20.1", "forge");
    assert_eq!(launcher.launch(&forge.id, Some(Join::World(&w)), &noop).await.unwrap_err().message_code(), "hosting.wrongInstance");
    // Ungesäuberte Angaben (Kleinbuchstaben-Code) kommen nicht durch.
    let mut w = world("1.21.11", "forge");
    w.code = Some("k7qm2x".into());
    assert_eq!(launcher.launch(&forge.id, Some(Join::World(&w)), &noop).await.unwrap_err().message_code(), "hosting.invalidRoom");
    assert!(matches!(
        HostedWorld { code: Some("k7qm2x".into()), ..world("1.21.11", "forge") }.validated(),
        Ok(HostedWorld { code: Some(ref c), .. }) if c == "K7QM2X"
    ));

    // Kein Spiel läuft, aber es gibt auch keine Link-Sitzung: Übergabe an ein laufendes Spiel scheitert sauber.
    let w = world("1.21.11", "forge");
    let err = launcher.hand_world_to_running_game(&forge.id, &w).unwrap_err();
    assert!(matches!(err, Error::Launch(_)), "{err:?}");
    assert_eq!(err.message_code(), "hosting.gameNotLinked");
    assert_eq!(launcher.hosting_delivery(&forge.id), crate::link::HostingDelivery::None);
}

#[test]
fn instances_match_by_version_and_loader() {
    use crate::instance::Instance;
    let instance = |kind: LoaderKind| -> Instance {
        serde_json::from_value(json!({
            "id": "x", "name": "X", "gameVersion": "1.21.11", "loader": { "kind": kind, "version": null },
            "createdAt": "2026-09-26T00:00:00Z"
        }))
        .unwrap()
    };
    assert!(crate::check_world_instance(&instance(LoaderKind::Fabric), &world("1.21.11", "fabric")).is_ok());
    assert!(crate::check_world_instance(&instance(LoaderKind::Vanilla), &world("1.21.11", "fabric")).is_ok(), "Vanilla läuft als Fabric");
    assert!(crate::check_world_instance(&instance(LoaderKind::NeoForge), &world("1.21.11", "neoforge")).is_ok());
    assert!(crate::check_world_instance(&instance(LoaderKind::Quilt), &world("1.21.11", "fabric")).is_err());
    assert!(crate::check_world_instance(&instance(LoaderKind::Fabric), &world("1.21.10", "fabric")).is_err());
}
