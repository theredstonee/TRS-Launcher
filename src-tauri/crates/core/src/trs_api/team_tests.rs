//! Team-Rechte, neue Fehlercodes und Bewerbungen (§24) gegen eine nachgebaute API.

use std::sync::atomic::{AtomicUsize, Ordering};

use serde_json::json;

use super::chat_tests::{collect, events, run_until, sse, test_config};
use super::live::LiveEvent;
use super::testkit::{MockServer, Response};
use super::tests::{ACC, auth_routes, launcher};
use super::*;

fn me(extra: serde_json::Value) -> serde_json::Value {
    let mut me = json!({
        "uuid": ACC, "name": "Theredstonee", "admin": false, "createdAt": "2026-09-23T18:05:02.000Z",
        "settings": { "showBadge": true, "showCapeToOthers": true, "presenceVisibility": "friends", "shareServer": false },
        "activeCape": null
    });
    for (k, v) in extra.as_object().unwrap() {
        me[k] = v.clone();
    }
    me
}

fn application(status: &str) -> serde_json::Value {
    json!({ "id": "a0123456789abcdef", "job": { "id": "moderator", "title": { "en": "Moderator", "de": "Moderator" }, "open": true },
        "status": status, "response": if status == "rejected" { json!("Danke\u{202E} fürs Bewerben!") } else { json!(null) },
        "createdAt": "2026-09-27T08:00:00.000Z", "updatedAt": "2026-09-27T09:00:00.000Z", "decidedAt": null,
        "canWithdraw": matches!(status, "new" | "review" | "interview") })
}

/// `GET /v1/me` liefert der Reihe nach die Antworten aus `answers`.
async fn me_server(answers: Vec<serde_json::Value>) -> MockServer {
    let issued = AtomicUsize::new(0);
    let n = AtomicUsize::new(0);
    MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        match (req.method.as_str(), req.path.split('?').next().unwrap_or_default()) {
            ("GET", "/v1/me") => Response::json(200, answers[n.fetch_add(1, Ordering::SeqCst).min(answers.len() - 1)].clone()),
            ("POST", "/v1/admin/sanctions/9/lift") => {
                Response::json(403, json!({ "error": { "code": "rank_too_low", "message": "higher rank" } }))
            }
            ("GET", "/v1/admin/reports/r0123456789abcdef") => Response::json(
                403,
                json!({ "error": { "code": "missing_permission", "message": "…", "permission": "reports.view" } }),
            ),
            ("GET", "/v1/admin/audit") => Response::json(
                403,
                json!({ "error": { "code": "missing_permission", "message": "…", "permission": "evil\u{202E}" } }),
            ),
            ("GET", "/v1/me/applications") => Response::json(
                200,
                json!({ "applications": [application("review"), application("rejected"), { "id": "../x", "status": "new" }] }),
            ),
            ("POST", "/v1/me/applications/a0123456789abcdef/withdraw") => {
                Response::json(200, json!({ "application": application("withdrawn") }))
            }
            ("POST", "/v1/me/applications/a1111111111111111/withdraw") => Response::error(409, "application_closed"),
            _ => Response::error(404, "not_found"),
        }
    })
    .await
}

#[tokio::test]
async fn me_carries_the_team_with_permissions_and_limits() {
    let team = json!({ "owner": false, "rank": 300,
        "roles": [{ "id": "supporter", "name": null, "color": "#22c55e", "builtin": true }],
        "permissions": ["dashboard.view", "reports.view", "players.view", "sanctions.warn", "worlds.view", "made.up"],
        "limits": { "kinds": ["warn"], "maxMinutes": 1440, "maxWarnMinutes": 43200, "permanent": false } });
    let server = me_server(vec![
        // Neuer Server, Team-Mitglied (Supporter): `role` bleibt „moderator“ für ältere Stellen.
        me(json!({ "role": "moderator", "team": team })),
        // Neuer Server, kein Team: `team: null` schlägt auch ein altes `admin: true`.
        me(json!({ "admin": true, "role": null, "team": null })),
        // Alter Server ohne `team`: Rechte aus der Rolle.
        me(json!({ "role": "moderator" })),
        me(json!({ "admin": true })),
        // Kaputtes `team` gibt keine Rechte.
        me(json!({ "role": "admin", "team": { "rank": "hoch" } })),
    ])
    .await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let supporter = launcher.trs_me().await.unwrap();
    let t = supporter.team.as_ref().unwrap();
    assert_eq!(t.rank, 300);
    assert_eq!(t.permissions, ["dashboard.view", "reports.view", "sanctions.warn", "players.view", "worlds.view"]);
    assert_eq!(t.limits.max_minutes, Some(1440));
    assert_eq!(supporter.role.as_deref(), Some("moderator"));
    let view = serde_json::to_value(&supporter).unwrap();
    assert_eq!(view["team"]["limits"]["maxWarnMinutes"], 43200, "camelCase fürs Webview");
    assert_eq!(view["team"]["roles"][0]["id"], "supporter");

    let outsider = launcher.trs_me().await.unwrap();
    assert!(outsider.team.is_none() && outsider.role.is_none());

    let old_mod = launcher.trs_me().await.unwrap();
    let t = old_mod.team.unwrap();
    assert!(t.can("reports.handle") && !t.can("roles.manage"));
    assert_eq!(t.rank, access::MODERATOR_RANK);

    let old_admin = launcher.trs_me().await.unwrap();
    assert_eq!(old_admin.team.unwrap().rank, access::ADMIN_RANK);
    assert_eq!(old_admin.role.as_deref(), Some("admin"));

    let broken = launcher.trs_me().await.unwrap();
    assert!(broken.team.is_none() && broken.role.is_none());
}

#[tokio::test]
async fn new_team_errors_are_translated() {
    let server = me_server(vec![me(json!({}))]).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let err = launcher.admin_sanction_lift(9, "Irrtum").await.unwrap_err();
    assert_eq!((err.kind(), err.message_code()), ("trs_api", "trsApi.rank_too_low"));
    assert_eq!(err.code(), Some("rank_too_low"));

    let err = launcher.admin_report("r0123456789abcdef").await.unwrap_err();
    assert_eq!((err.kind(), err.message_code()), ("trs_api", "trsApi.missing_permission"));
    assert_eq!(err.message_params()["permission"], "reports.view", "fehlendes Recht als Parameter");

    // Unbekannte/kaputte Rechte werden nicht weitergereicht.
    let err = launcher.admin_audit(&Default::default()).await.unwrap_err();
    assert_eq!(err.message_code(), "trsApi.missing_permission");
    assert!(err.message_params().get("permission").is_none());
}

#[tokio::test]
async fn own_applications_can_be_listed_and_withdrawn() {
    let server = me_server(vec![me(json!({}))]).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    let list = launcher.trs_my_applications().await.unwrap();
    assert_eq!(list.len(), 2, "kaputter Eintrag fällt weg");
    assert!(list[0].can_withdraw);
    assert_eq!(list[1].response.as_deref(), Some("Danke fürs Bewerben!"));

    let withdrawn = launcher.trs_withdraw_application("a0123456789abcdef").await.unwrap();
    assert_eq!(withdrawn.status, "withdrawn");
    assert!(!withdrawn.can_withdraw);
    let sent = &server.hits("POST", "/v1/me/applications/a0123456789abcdef/withdraw")[0];
    assert!(sent.bearer().is_some_and(|t| t.starts_with("trs_")), "Token bleibt im Kern");

    let closed = launcher.trs_withdraw_application("a1111111111111111").await.unwrap_err();
    assert_eq!(closed.message_code(), "trsApi.application_closed");
    let bad = launcher.trs_withdraw_application("../../v1/admin").await.unwrap_err();
    assert_eq!(bad.message_code(), "applications.invalidId");
    assert!(server.requests().iter().all(|r| !r.path.contains("..")), "ungültige IDs gehen nicht raus");
}

#[tokio::test]
async fn application_updates_arrive_over_the_stream() {
    let issued = AtomicUsize::new(0);
    let server = MockServer::start(move |req| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if req.path != "/v1/events/me" {
            return Response::error(404, "not_found");
        }
        let good = json!({ "type": "application_updated", "application": application("interview") });
        let bad = json!({ "type": "application_updated", "application": { "id": "a1", "status": "hired" } });
        sse(&format!(
            "id: e1.1\nevent: hello\ndata: {{\"type\":\"hello\",\"resumed\":false}}\n\nid: e1.2\nevent: application_updated\ndata: {bad}\n\nid: e1.3\nevent: application_updated\ndata: {good}\n\n"
        ))
    })
    .await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    let log = collect(&launcher);
    let cfg = test_config();
    run_until(&launcher, &cfg, || events(&log).iter().any(|e| matches!(e, LiveEvent::ApplicationUpdated { .. }))).await;

    let apps: Vec<_> = events(&log)
        .into_iter()
        .filter_map(|e| if let LiveEvent::ApplicationUpdated { application } = e { Some(application) } else { None })
        .collect();
    assert_eq!(apps.len(), 1, "kaputtes Ereignis wird verworfen");
    assert_eq!(apps[0].status, "interview");
    assert_eq!(apps[0].job.title.get("de"), Some("Moderator"));
}
