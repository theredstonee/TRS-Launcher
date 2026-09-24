//! Statuslogik und Schleife – ohne echtes Discord (Attrappe statt IPC).

use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use super::*;

const APP_ID: &str = "123456789012345678";

fn game(id: &str, version: &str, loader: LoaderKind, started_at: i64) -> GameInfo {
    GameInfo { instance_id: id.into(), game_version: version.into(), loader, started_at }
}

#[test]
fn launcher_only_shows_idle_status_with_logo_and_button() {
    let presence = DiscordPresence::new(APP_ID);
    presence.configure(true, Language::De);
    let a = presence.activity().unwrap();
    assert_eq!(a.details, "Im TRS Launcher");
    assert_eq!((a.state, a.timestamps), (None, None));
    assert_eq!((a.assets.large_image.as_str(), a.assets.large_text.as_str()), ("logo", "TRS Launcher"));
    assert_eq!(a.assets.small_image, None);
    assert_eq!(a.buttons, vec![Button { label: "TRS Launcher holen".into(), url: DOWNLOAD_URL.into() }]);

    presence.configure(true, Language::En);
    let a = presence.activity().unwrap();
    assert_eq!(a.details, "In the TRS Launcher");
    assert_eq!(a.buttons[0].label, "Get TRS Launcher");
    // Andere Sprachen: Englisch.
    presence.configure(true, Language::Fr);
    assert_eq!(presence.activity().unwrap().buttons[0].label, "Get TRS Launcher");
    for lang in Language::ALL {
        presence.configure(true, lang);
        assert!(presence.activity().unwrap().buttons[0].label.chars().count() <= MAX_BUTTON_LABEL);
    }
}

#[test]
fn running_game_shows_version_loader_and_play_time_only() {
    let presence = DiscordPresence::new(APP_ID);
    presence.game_started(game("my-secret-instance", "1.21.5", LoaderKind::Fabric, 1_700_000_000));
    let a = presence.activity().unwrap();
    assert_eq!(a.details, "Minecraft 1.21.5");
    assert_eq!(a.state.as_deref(), Some("Fabric"));
    assert_eq!(a.timestamps, Some(Timestamps { start: 1_700_000_000 }));
    assert_eq!(a.assets.small_image.as_deref(), Some("fabric"));
    assert_eq!(a.assets.small_text.as_deref(), Some("Fabric"));
    assert_eq!(a.buttons.len(), 1);
    // Keine Instanznamen im Status.
    assert!(!serde_json::to_string(&a).unwrap().contains("my-secret-instance"));

    presence.game_exited("my-secret-instance");
    presence.game_started(game("b", "1.8.9", LoaderKind::Vanilla, 5));
    let a = presence.activity().unwrap();
    assert_eq!((a.details.as_str(), a.state.as_deref()), ("Minecraft 1.8.9", Some("Vanilla")));
    assert_eq!(a.assets.small_image.as_deref(), Some("vanilla"));
}

#[test]
fn loader_keys() {
    let keys: Vec<_> = [LoaderKind::Vanilla, LoaderKind::Fabric, LoaderKind::Quilt, LoaderKind::Forge, LoaderKind::NeoForge]
        .into_iter()
        .map(|k| loader_label(k).1)
        .collect();
    assert_eq!(keys, ["vanilla", "fabric", "quilt", "forge", "neoforge"]);
}

#[test]
fn latest_game_wins_and_launcher_returns_when_all_exit() {
    let presence = DiscordPresence::new(APP_ID);
    presence.game_started(game("a", "1.20.1", LoaderKind::Forge, 10));
    presence.game_started(game("b", "1.21.5", LoaderKind::NeoForge, 20));
    assert_eq!(presence.activity().unwrap().state.as_deref(), Some("NeoForge"));

    // Das zuletzt gestartete endet → das ältere wird wieder gezeigt.
    presence.game_exited("b");
    let a = presence.activity().unwrap();
    assert_eq!((a.state.as_deref(), a.timestamps.map(|t| t.start)), (Some("Forge"), Some(10)));

    // Erneuter Start derselben Instanz rückt sie nach vorn.
    presence.game_started(game("b", "1.21.5", LoaderKind::NeoForge, 30));
    presence.game_started(game("a", "1.20.1", LoaderKind::Forge, 40));
    assert_eq!(presence.activity().unwrap().timestamps.map(|t| t.start), Some(40));

    presence.game_exited("a");
    presence.game_exited("b");
    assert_eq!(presence.activity().unwrap().details, "In the TRS Launcher");
}

#[test]
fn disabled_shows_nothing() {
    let presence = DiscordPresence::new(APP_ID);
    presence.game_started(game("a", "1.21.5", LoaderKind::Quilt, 1));
    presence.configure(false, Language::De);
    assert_eq!(presence.activity(), None);
}

#[test]
fn empty_or_invalid_app_id_is_inactive() {
    assert!(!DiscordPresence::new("").available());
    assert!(!DiscordPresence::new("   ").available());
    assert!(!DiscordPresence::new("abc").available());
    assert!(!DiscordPresence::new("12345678901234567x").available());
    assert!(DiscordPresence::new(APP_ID).available());
    assert!(!DiscordPresence::new("off").available());
    // Die eingebaute ID ist gültig (sofern der Build sie nicht bewusst abschaltet).
    if option_env!("TRS_DISCORD_APP_ID").is_none_or(str::is_empty) {
        assert!(DiscordPresence::from_build().available());
    }
}

#[test]
fn long_versions_are_clipped() {
    let presence = DiscordPresence::new(APP_ID);
    presence.game_started(game("a", &"9".repeat(300), LoaderKind::Fabric, 1));
    assert_eq!(presence.activity().unwrap().details.chars().count(), MAX_TEXT);
}

#[test]
fn serializes_like_discord_expects() {
    let presence = DiscordPresence::new(APP_ID);
    presence.game_started(game("a", "1.21.5", LoaderKind::Fabric, 7));
    let json = serde_json::to_value(presence.activity().unwrap()).unwrap();
    assert_eq!(json["timestamps"]["start"], 7);
    assert_eq!(json["assets"]["large_image"], "logo");
    assert_eq!(json["assets"]["small_image"], "fabric");
    assert_eq!(json["buttons"][0]["url"], DOWNLOAD_URL);

    let idle = serde_json::to_value(DiscordPresence::new(APP_ID).activity().unwrap()).unwrap();
    assert!(idle.get("state").is_none() && idle.get("timestamps").is_none());
    assert!(idle["assets"].get("small_image").is_none());
}

// --- Schleife mit Attrappe --------------------------------------------------------------

/// Nimmt alles auf, was „Discord“ gezeigt wurde.
#[derive(Default)]
struct Fake {
    online: AtomicBool,
    connects: AtomicUsize,
    sent: Mutex<Vec<Option<Activity>>>,
}

impl Fake {
    fn sent(&self) -> Vec<Option<Activity>> {
        self.sent.lock().unwrap().clone()
    }
    fn last(&self) -> Option<Option<Activity>> {
        self.sent().last().cloned()
    }
}

struct FakeConnector(Arc<Fake>);
struct FakeConn(Arc<Fake>);

impl Connector for FakeConnector {
    type Conn = FakeConn;
    async fn connect(&self, app_id: &str) -> io::Result<FakeConn> {
        assert_eq!(app_id, APP_ID);
        if !self.0.online.load(Ordering::SeqCst) {
            return Err(io::Error::new(io::ErrorKind::NotFound, "offline"));
        }
        self.0.connects.fetch_add(1, Ordering::SeqCst);
        Ok(FakeConn(self.0.clone()))
    }
}

impl Connection for FakeConn {
    async fn set_activity(&mut self, activity: Option<&Activity>) -> io::Result<()> {
        if !self.0.online.load(Ordering::SeqCst) {
            return Err(io::Error::new(io::ErrorKind::BrokenPipe, "Discord beendet"));
        }
        self.0.sent.lock().unwrap().push(activity.cloned());
        Ok(())
    }
}

async fn until(what: &str, check: impl Fn() -> bool) {
    for _ in 0..200 {
        if check() {
            return;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("Zeitüberschreitung: {what}");
}

#[tokio::test]
async fn loop_connects_later_follows_games_and_clears_on_shutdown() {
    let fake = Arc::new(Fake::default());
    let presence = Arc::new(DiscordPresence::new(APP_ID));
    presence.configure(true, Language::De);
    let runner = tokio::spawn({
        let (presence, fake) = (presence.clone(), fake.clone());
        async move { presence.run(FakeConnector(fake)).await }
    });

    // Discord läuft nicht: nichts gesendet, kein Fehler.
    tokio::time::sleep(RETRY * 2).await;
    assert!(fake.sent().is_empty());

    // Discord startet später → nach dem nächsten Versuch erscheint der Status.
    fake.online.store(true, Ordering::SeqCst);
    until("Status im Launcher", || fake.last().flatten().is_some_and(|a| a.details == "Im TRS Launcher")).await;

    presence.game_started(game("a", "1.21.5", LoaderKind::Fabric, 99));
    until("Spielstatus", || fake.last().flatten().is_some_and(|a| a.details == "Minecraft 1.21.5")).await;
    presence.game_exited("a");
    until("zurück im Launcher", || fake.last().flatten().is_some_and(|a| a.details == "Im TRS Launcher")).await;

    // Discord beendet und neu gestartet → neu verbinden, Status wieder da.
    fake.online.store(false, Ordering::SeqCst);
    tokio::time::sleep(RETRY * 3).await;
    fake.online.store(true, Ordering::SeqCst);
    until("neu verbunden", || fake.connects.load(Ordering::SeqCst) >= 2).await;
    until("Status nach Neustart", || fake.last().flatten().is_some()).await;

    presence.shutdown().await;
    runner.await.unwrap();
    assert_eq!(fake.last(), Some(None), "beim Beenden gelöscht");
}

#[tokio::test]
async fn switching_off_clears_once_and_stays_quiet() {
    let fake = Arc::new(Fake { online: AtomicBool::new(true), ..Default::default() });
    let presence = Arc::new(DiscordPresence::new(APP_ID));
    let runner = tokio::spawn({
        let (presence, fake) = (presence.clone(), fake.clone());
        async move { presence.run(FakeConnector(fake)).await }
    });
    until("Status", || fake.last().flatten().is_some()).await;

    presence.configure(false, Language::En);
    until("gelöscht", || fake.last() == Some(None)).await;
    let count = fake.sent().len();
    tokio::time::sleep(RETRY * 3).await;
    assert_eq!(fake.sent().len(), count, "abgeschaltet: keine weiteren Nachrichten");
    assert_eq!(fake.connects.load(Ordering::SeqCst), 1);

    // Wieder an → verbindet neu.
    presence.configure(true, Language::En);
    until("wieder da", || fake.last().flatten().is_some()).await;

    presence.shutdown().await;
    runner.await.unwrap();
}

#[tokio::test]
async fn without_app_id_nothing_happens() {
    let fake = Arc::new(Fake { online: AtomicBool::new(true), ..Default::default() });
    let presence = DiscordPresence::new("");
    // Kehrt sofort zurück, ohne zu verbinden.
    tokio::time::timeout(Duration::from_secs(1), presence.run(FakeConnector(fake.clone()))).await.unwrap();
    presence.shutdown().await;
    assert_eq!(fake.connects.load(Ordering::SeqCst), 0);
    assert!(fake.sent().is_empty());
}
