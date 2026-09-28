//! Erfolge gegen eine nachgebaute API (§31): Katalog säubern, Freunde, Meldungen sammeln.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use serde_json::json;

use super::Consent;
use super::achievements::{
    Flush, MAX_PENDING, MAX_REQUESTS_PER_FLUSH, MyAchievements, Pending, PlayerAchievements, ReportKind, achievement_id,
    merge,
};
use super::testkit::{MockServer, Request, Response};
use super::tests::{ACC, auth_routes, launcher};

const FRIEND: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

fn text(en: &str) -> serde_json::Value {
    json!({ "en": en, "de": format!("{en} (de)"), "es": format!("{en} (es)") })
}

/// Antwort wie §31.3 – plus ein paar kaputte Einträge.
fn catalog() -> serde_json::Value {
    json!({
        "achievements": [
            { "id": "play_100h", "category": "playtime", "secret": false, "hidden": false, "title": text("Veteran"),
              "description": text("Play for 100 hours"), "icon": "clock", "points": 60, "rarity": "epic", "goal": 6000,
              "unit": "minutes", "verified": true, "reward": { "kind": "cape", "id": "veteran" }, "order": 7 },
            { "id": "first_launch", "category": "playtime", "secret": false, "hidden": false, "title": text("Liftoff"),
              "description": text("Start a game"), "icon": "rocket", "points": 5, "rarity": "common", "goal": null,
              "unit": null, "verified": false, "reward": null, "order": 1 },
            // Geheim + gesperrt, der Server hat trotzdem Text und Belohnung mitgeschickt → muss weg.
            { "id": "secret_01", "category": "secret", "secret": true, "hidden": true, "title": text("Night owl"),
              "description": text("Play at 3 am"), "icon": "moon", "points": 25, "rarity": "mythic", "goal": 3,
              "unit": "count", "verified": false, "reward": { "kind": "cosmetic", "id": "x" }, "order": 30 },
            { "id": "all_secrets", "category": "secret", "secret": false, "hidden": false, "title": text("Keeper of secrets"),
              "description": text("Unlock every secret"), "icon": "crown", "points": 50, "rarity": "legendary", "goal": 5,
              "unit": "count", "verified": true, "reward": { "kind": "cosmetic", "id": "secret-crown" }, "order": 40 },
            { "id": "../evil", "category": "launcher", "title": text("x"), "points": 1 },
            { "id": "first_launch", "category": "launcher", "title": text("Doppelt"), "points": 999 },
            { "id": "odd", "category": "weird", "title": "Plain text", "points": -3, "goal": 2.7, "unit": "lightyears",
              "reward": { "kind": "money", "id": "x" } }
        ],
        "unlocked": [
            { "id": "first_launch", "at": "2026-09-28T10:00:00.000Z" },
            { "id": "unknown_elsewhere", "at": "2026-09-28T10:00:00.000Z" },
            { "id": "first_launch", "at": "2026-09-29T10:00:00.000Z" }
        ],
        "progress": { "play_100h": 6812.4, "first_launch": 1, "ghost": 3, "all_secrets": 0 },
        "points": 5,
        "totalPoints": 940
    })
}

#[test]
fn ids_are_checked() {
    assert!(achievement_id("first_launch"));
    assert!(achievement_id("secret_01"));
    for bad in ["", "_x", "Upper", "a b", "../x", &"a".repeat(65)] {
        assert!(!achievement_id(bad), "{bad}");
    }
}

#[test]
fn catalog_is_cleaned_sorted_and_secrets_stay_secret() {
    let mine = MyAchievements::from_api(catalog()).unwrap();
    let ids: Vec<_> = mine.achievements.iter().map(|a| a.id.as_str()).collect();
    assert_eq!(ids, ["first_launch", "play_100h", "secret_01", "all_secrets", "odd"], "nach order, ohne Kaputte/Doppelte");

    let first = &mine.achievements[0];
    assert_eq!(first.title.as_ref().unwrap().get("de"), Some("Liftoff (de)"));
    assert_eq!(first.title.as_ref().unwrap().get("fr"), Some("Liftoff"), "Rückfall Englisch");
    assert!(!first.verified);

    let play = &mine.achievements[1];
    assert_eq!((play.goal, play.unit.as_deref()), (Some(6000), Some("minutes")));
    let reward = play.reward.as_ref().unwrap();
    assert_eq!((reward.kind.as_str(), reward.id.as_str(), reward.name.as_deref()), ("cape", "veteran", None));

    let secret = &mine.achievements[2];
    assert!(secret.secret && secret.hidden);
    assert!(secret.title.is_none() && secret.description.is_none() && secret.reward.is_none(), "gesperrt + geheim → nur ???");
    assert_eq!((secret.goal, secret.unit.as_deref(), secret.icon.as_str()), (None, None, "secret"));
    assert_eq!(secret.rarity, "common", "unbekannte Seltenheit");

    let meta = &mine.achievements[3];
    assert!(!meta.secret && !meta.hidden, "all_secrets ist sichtbar");
    assert_eq!(meta.reward.as_ref().unwrap().id, "secret-crown");

    let odd = &mine.achievements[4];
    assert_eq!(odd.category, "launcher");
    assert_eq!(odd.title.as_ref().unwrap().en.as_deref(), Some("Plain text"));
    assert_eq!((odd.points, odd.goal, odd.unit.as_deref()), (0, Some(2), None));
    assert!(odd.reward.is_none());

    assert_eq!(mine.unlocked.len(), 1, "nur bekannte, ohne Doppelte");
    assert_eq!(mine.progress.get("play_100h"), Some(&6000), "höchstens bis zum Ziel");
    assert!(!mine.progress.contains_key("first_launch") && !mine.progress.contains_key("ghost"), "nur Erfolge mit Ziel");
    assert!(!mine.progress.contains_key("secret_01"), "versteckt → kein Ziel");
    assert_eq!((mine.points, mine.total_points), (5, 940));
    assert!(mine.visible_to_friends, "fehlt → sichtbar");

    let json = serde_json::to_value(&mine).unwrap();
    assert!(json["achievements"][2]["title"].is_null());
    assert_eq!(json["achievements"][1]["reward"]["kind"], "cape");
    assert_eq!(json["totalPoints"], 940);

    // Freigeschaltet → der geheime Erfolg zeigt seinen Text (wenn der Server ihn schickt).
    let mut raw = catalog();
    raw["unlocked"] = json!([{ "id": "secret_01", "at": null }]);
    raw["achievements"][2]["hidden"] = json!(false);
    let mine = MyAchievements::from_api(raw).unwrap();
    let secret = mine.achievements.iter().find(|a| a.id == "secret_01").unwrap();
    assert_eq!(secret.title.as_ref().unwrap().en.as_deref(), Some("Night owl"));
    assert!(MyAchievements::from_api(json!("nope")).is_err());
    let empty = MyAchievements::from_api(json!({})).unwrap();
    assert!(empty.achievements.is_empty() && empty.unlocked.is_empty() && empty.points == 0 && empty.visible_to_friends);
}

#[test]
fn friend_lists_are_sorted_newest_first() {
    let p = PlayerAchievements::from_api(
        FRIEND.into(),
        json!({ "unlocked": [{ "id": "a", "at": "2026-09-01T00:00:00Z" }, { "id": "b", "at": "2026-09-20T00:00:00Z" }, { "id": "Bad" }],
            "points": 42.9 }),
    )
    .unwrap();
    assert_eq!(p.unlocked.iter().map(|u| u.id.as_str()).collect::<Vec<_>>(), ["b", "a"]);
    assert_eq!(p.points, 42);
    assert!(!p.hidden);

    // Freund hat seine Erfolge privat gestellt.
    let p = PlayerAchievements::from_api(FRIEND.into(), json!({ "hidden": true, "unlocked": [{ "id": "a" }], "points": 9 })).unwrap();
    assert!(p.hidden && p.unlocked.is_empty() && p.points == 0);
}

#[test]
fn report_bodies_match_the_contract() {
    assert_eq!(ReportKind::Launch { hour: 23 }.body(), json!({ "kind": "launch", "hour": 23 }));
    assert_eq!(ReportKind::Launch { hour: 99 }.body(), json!({ "kind": "launch", "hour": 23 }));
    assert_eq!(ReportKind::ModInstalled { count: 12 }.body(), json!({ "kind": "mod_installed", "count": 12 }));
    assert_eq!(ReportKind::ModInstalled { count: 0 }.body(), json!({ "kind": "mod_installed", "count": 1 }));
    assert_eq!(ReportKind::ClipRecorded { count: 250 }.body(), json!({ "kind": "clip_recorded", "count": 100 }));
    assert_eq!(ReportKind::ModpackInstalled.body(), json!({ "kind": "modpack_installed" }));
    assert_eq!(ReportKind::CrashFixed.body(), json!({ "kind": "crash_fixed" }));
    assert_eq!(ReportKind::LauncherImport.body(), json!({ "kind": "launcher_import" }));
    let ReportKind::Launch { hour } = ReportKind::launch_now() else { panic!() };
    assert!(hour <= 23);
    assert_eq!(ReportKind::from_ui("crash_fixed"), Some(ReportKind::CrashFixed));
    assert_eq!(ReportKind::from_ui("launch"), None, "Spielstarts meldet nur der Kern");
}

#[test]
fn reports_are_merged() {
    let p = |account: &str, kind| Pending { account: account.into(), kind };
    let merged = merge(vec![
        p(ACC, ReportKind::ModInstalled { count: 60 }),
        p(ACC, ReportKind::Launch { hour: 3 }),
        p(ACC, ReportKind::ModInstalled { count: 60 }),
        p(FRIEND, ReportKind::ModInstalled { count: 1 }),
        p(ACC, ReportKind::CrashFixed),
        p(ACC, ReportKind::CrashFixed),
        p(ACC, ReportKind::ClipRecorded { count: 1 }),
        p(ACC, ReportKind::ClipRecorded { count: 1 }),
        p(ACC, ReportKind::Launch { hour: 4 }),
        p(ACC, ReportKind::ModpackInstalled),
        p(ACC, ReportKind::ModpackInstalled),
    ]);
    let kinds: Vec<_> = merged.iter().map(|p| (p.account == ACC, p.kind)).collect();
    assert_eq!(
        kinds,
        vec![
            (true, ReportKind::ModInstalled { count: 100 }),
            (true, ReportKind::Launch { hour: 3 }),
            (true, ReportKind::ModInstalled { count: 20 }),
            (false, ReportKind::ModInstalled { count: 1 }),
            (true, ReportKind::CrashFixed),
            (true, ReportKind::ClipRecorded { count: 2 }),
            (true, ReportKind::Launch { hour: 4 }),
            (true, ReportKind::ModpackInstalled),
            (true, ReportKind::ModpackInstalled),
        ]
    );
}

/// Attrappe der Erfolgs-Routen; `fail` = 1 schaltet für Meldungen das Rate-Limit ein.
fn api(reports: Arc<Mutex<Vec<serde_json::Value>>>, fail: Arc<AtomicUsize>) -> impl Fn(&Request) -> Response + Send + Sync + 'static {
    let issued = AtomicUsize::new(0);
    move |req: &Request| {
        if let Some(r) = auth_routes(req, &issued, "mc-token") {
            return r;
        }
        if !req.bearer().is_some_and(|t| t.starts_with("trs_")) {
            return Response::error(401, "unauthorized");
        }
        match (req.method.as_str(), req.path.as_str()) {
            ("GET", "/v1/me/achievements") => Response::json(200, catalog()),
            ("GET", p) if p == format!("/v1/players/{FRIEND}/achievements") => {
                Response::json(200, json!({ "unlocked": [{ "id": "first_launch", "at": "2026-09-28T10:00:00.000Z" }], "points": 5 }))
            }
            ("GET", p) if p.starts_with("/v1/players/") => Response::error(404, "player_not_found"),
            ("PATCH", "/v1/me/achievements/settings") => {
                Response::json(200, json!({ "visibleToFriends": req.json()["visibleToFriends"] }))
            }
            ("POST", "/v1/me/achievements/report") => {
                if fail.load(Ordering::SeqCst) == 1 {
                    return Response::error(429, "rate_limited").with_header("retry-after", "30");
                }
                reports.lock().unwrap().push(req.json());
                Response::json(200, json!({ "newlyUnlocked": [] }))
            }
            _ => Response::error(404, "not_found"),
        }
    }
}

#[tokio::test]
async fn achievements_are_loaded_and_reports_are_batched() {
    let reports = Arc::new(Mutex::new(Vec::new()));
    let fail = Arc::new(AtomicUsize::new(0));
    let server = MockServer::start(api(Arc::clone(&reports), Arc::clone(&fail))).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;

    // Ohne bestehende TRS-Anmeldung wird für eine Meldung niemand angemeldet – sie fällt weg.
    launcher.trs_achievement_event(ReportKind::ModInstalled { count: 1 }, None).await;
    assert_eq!(launcher.flush_achievement_reports().await, Flush::Done);
    assert!(server.hits("POST", "/v1/auth/challenge").is_empty());
    assert!(reports.lock().unwrap().is_empty());

    // Laden meldet an (Token da); danach gehen Meldungen raus.
    let mine = launcher.trs_achievements().await.unwrap();
    assert_eq!(mine.points, 5);
    let friend = launcher.trs_player_achievements(&FRIEND.to_uppercase()).await.unwrap();
    assert_eq!((friend.uuid.as_str(), friend.points), (FRIEND, 5));
    assert!(launcher.trs_player_achievements(ACC).await.is_err(), "404 → Fehler");
    assert!(launcher.trs_player_achievements("../x").await.is_err());
    assert!(!launcher.trs_set_achievements_visible(false).await.unwrap());
    assert_eq!(server.hits("PATCH", "/v1/me/achievements/settings")[0].json(), json!({ "visibleToFriends": false }));

    launcher.trs_achievement_event(ReportKind::Launch { hour: 3 }, Some(ACC)).await;
    launcher.trs_achievement_event(ReportKind::ModInstalled { count: 2 }, None).await;
    launcher.trs_achievement_event(ReportKind::ModpackInstalled, None).await;
    launcher.trs_achievement_event(ReportKind::ModInstalled { count: 3 }, None).await;
    launcher.trs_achievement_event(ReportKind::ClipRecorded { count: 1 }, Some("kaputt")).await;
    assert_eq!(launcher.flush_achievement_reports().await, Flush::Done);
    assert_eq!(
        *reports.lock().unwrap(),
        vec![
            json!({ "kind": "launch", "hour": 3 }),
            json!({ "kind": "mod_installed", "count": 5 }),
            json!({ "kind": "modpack_installed" }),
            json!({ "kind": "clip_recorded", "count": 1 }),
        ]
    );

    // Rate-Limit: die Meldung nicht wiederholen, der Rest kommt später.
    fail.store(1, Ordering::SeqCst);
    launcher.trs_achievement_event(ReportKind::CrashFixed, None).await;
    launcher.trs_achievement_event(ReportKind::LauncherImport, None).await;
    assert!(matches!(launcher.flush_achievement_reports().await, Flush::Retry(_)));
    assert_eq!(launcher.trs.achievements.snapshot().iter().map(|p| p.kind).collect::<Vec<_>>(), vec![ReportKind::LauncherImport]);
    fail.store(0, Ordering::SeqCst);
    assert_eq!(launcher.flush_achievement_reports().await, Flush::Done);
    assert_eq!(reports.lock().unwrap().last(), Some(&json!({ "kind": "launcher_import" })));

    // Viele Meldungen: begrenzt, höchstens 20 Anfragen je Durchgang, dann Pause.
    for hour in 0..(MAX_PENDING + 20) {
        launcher.trs_achievement_event(ReportKind::Launch { hour: (hour % 24) as u8 }, None).await;
    }
    assert_eq!(launcher.trs.achievements.len(), MAX_PENDING);
    let before = reports.lock().unwrap().len();
    assert!(matches!(launcher.flush_achievement_reports().await, Flush::Retry(_)));
    assert_eq!(reports.lock().unwrap().len() - before, MAX_REQUESTS_PER_FLUSH);
    assert_eq!(launcher.trs.achievements.len(), MAX_PENDING - MAX_REQUESTS_PER_FLUSH);

    // Einwilligung zurückgezogen → Warteschlange leer, keine Anfrage.
    let before = reports.lock().unwrap().len();
    launcher.trs.store.set_consent(Consent::Declined).await;
    assert_eq!(launcher.flush_achievement_reports().await, Flush::Done);
    assert_eq!(launcher.trs.achievements.len(), 0);
    assert_eq!(reports.lock().unwrap().len(), before);
}

#[tokio::test]
async fn offline_reports_wait_for_the_server() {
    let server = MockServer::start(|_: &Request| Response::empty(204)).await;
    let (_dir, launcher) = launcher(&server, &[ACC]).await;
    // Token vorhanden, aber der Server ist weg.
    launcher
        .trs
        .store
        .put_token(ACC, &format!("trs_{}", "A".repeat(43)), chrono::Utc::now() + chrono::Duration::days(1))
        .await
        .unwrap();
    drop(server);
    launcher.trs_achievement_event(ReportKind::ClipRecorded { count: 1 }, None).await;
    launcher.trs_achievement_event(ReportKind::ClipRecorded { count: 1 }, None).await;
    assert!(matches!(launcher.flush_achievement_reports().await, Flush::Retry(_)));
    assert_eq!(
        launcher.trs.achievements.snapshot().iter().map(|p| p.kind).collect::<Vec<_>>(),
        vec![ReportKind::ClipRecorded { count: 2 }],
        "bleibt (zusammengefasst) für später"
    );
}
