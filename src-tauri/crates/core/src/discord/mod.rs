//! Discord-Status („Spielt TRS Launcher“) über die lokale Discord-App.
//!
//! Der Launcher spricht nur mit der Discord-App auf diesem Rechner (IPC, siehe
//! [`ipc`]) – kein Netzwerk, kein Konto. Gezeigt wird:
//! - nur der Launcher offen: „Im TRS Launcher“ mit Logo,
//! - Spiel läuft: „Minecraft <Version>“, der Modloader und die Spielzeit.
//!
//! Nie Server-Adressen, Instanz- oder Spielernamen. Läuft Discord nicht, passiert
//! still nichts; die Schleife versucht es alle 30 s erneut. Abschaltbar unter
//! Einstellungen → Datenschutz.

pub mod ipc;

use std::future::Future;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use serde::Serialize;
use tokio::sync::Notify;

use crate::instance::LoaderKind;
use crate::settings::Language;

/// Application ID der Discord-App „TRS Launcher“ (öffentlich, kein Secret).
/// Ein Build kann sie über die Umgebungsvariable `TRS_DISCORD_APP_ID` ersetzen
/// (leer = eingebaute ID, z. B. wenn die GitHub-Variable fehlt). Etwas anderes
/// als eine Ziffernfolge – etwa `off` – schaltet den Discord-Status im Build ab.
pub const DISCORD_APP_ID: &str = match option_env!("TRS_DISCORD_APP_ID") {
    Some(id) if !id.is_empty() => id,
    _ => "1552754069351702548",
};

/// Ziel des Knopfes unter dem Status.
pub const DOWNLOAD_URL: &str = "https://trs-launcher.theredstonee.de";

/// Asset-Key des Logos (im Discord-Developer-Portal hochgeladen).
const LOGO_KEY: &str = "logo";
const LOGO_TEXT: &str = "TRS Launcher";

/// Discord begrenzt Texte auf 128 Zeichen, Knopf-Beschriftungen auf 32.
const MAX_TEXT: usize = 128;
const MAX_BUTTON_LABEL: usize = 32;

/// Neuer Verbindungsversuch bzw. Lebenszeichen, solange nichts passiert.
#[cfg(not(test))]
const RETRY: Duration = Duration::from_secs(30);
#[cfg(test)]
const RETRY: Duration = Duration::from_millis(40);
/// Höchstdauer für Verbinden und jede Nachricht – Discord darf nichts aufhalten.
#[cfg(not(test))]
const IO_TIMEOUT: Duration = Duration::from_secs(5);
#[cfg(test)]
const IO_TIMEOUT: Duration = Duration::from_millis(200);
/// Beim Beenden des Launchers höchstens so lange auf das Löschen warten.
const SHUTDOWN_TIMEOUT: Duration = Duration::from_millis(1500);

// --- Status (reine Logik, getestet) ---------------------------------------------------

/// Was Discord anzeigen soll (Felder wie in Discords `SET_ACTIVITY`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Activity {
    pub details: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub state: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamps: Option<Timestamps>,
    pub assets: Assets,
    pub buttons: Vec<Button>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Timestamps {
    /// Unix-Sekunden – Discord zählt ab hier die Spielzeit hoch.
    pub start: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Assets {
    pub large_image: String,
    pub large_text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub small_image: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub small_text: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Button {
    pub label: String,
    pub url: String,
}

/// Ein laufendes Spiel – nur das, was im Status stehen darf.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GameInfo {
    pub instance_id: String,
    pub game_version: String,
    pub loader: LoaderKind,
    /// Spielstart in Unix-Sekunden.
    pub started_at: i64,
}

#[derive(Debug, Clone)]
struct State {
    enabled: bool,
    language: Language,
    /// Laufende Spiele in Startreihenfolge (das letzte wird gezeigt).
    games: Vec<GameInfo>,
}

impl Default for State {
    fn default() -> Self {
        Self { enabled: true, language: Language::En, games: Vec::new() }
    }
}

/// Anzeigename und Asset-Key des Modloaders.
pub fn loader_label(kind: LoaderKind) -> (&'static str, &'static str) {
    match kind {
        LoaderKind::Vanilla => ("Vanilla", "vanilla"),
        LoaderKind::Fabric => ("Fabric", "fabric"),
        LoaderKind::Quilt => ("Quilt", "quilt"),
        LoaderKind::Forge => ("Forge", "forge"),
        LoaderKind::NeoForge => ("NeoForge", "neoforge"),
    }
}

/// Texte in der Sprache des Launchers (Deutsch, sonst Englisch).
fn texts(language: Language) -> (&'static str, &'static str) {
    match language {
        Language::De => ("Im TRS Launcher", "TRS Launcher holen"),
        _ => ("In the TRS Launcher", "Get TRS Launcher"),
    }
}

fn clip(text: &str, max: usize) -> String {
    text.chars().filter(|c| !c.is_control()).take(max).collect()
}

/// Status für den aktuellen Zustand; `None` = nichts zeigen (abgeschaltet).
fn activity_for(state: &State) -> Option<Activity> {
    if !state.enabled {
        return None;
    }
    let (idle, button) = texts(state.language);
    let buttons = vec![Button { label: clip(button, MAX_BUTTON_LABEL), url: DOWNLOAD_URL.into() }];
    let logo = |small: Option<(&str, &str)>| Assets {
        large_image: LOGO_KEY.into(),
        large_text: LOGO_TEXT.into(),
        small_image: small.map(|(_, key)| key.into()),
        small_text: small.map(|(name, _)| name.into()),
    };
    Some(match state.games.last() {
        None => Activity { details: idle.into(), state: None, timestamps: None, assets: logo(None), buttons },
        Some(game) => {
            let loader = loader_label(game.loader);
            Activity {
                details: clip(&format!("Minecraft {}", game.game_version), MAX_TEXT),
                state: Some(loader.0.into()),
                timestamps: Some(Timestamps { start: game.started_at }),
                assets: logo(Some(loader)),
                buttons,
            }
        }
    })
}

/// Nur Ziffern (Discord-Snowflake) – alles andere schaltet die Funktion ab.
fn valid_app_id(id: &str) -> Option<String> {
    let id = id.trim();
    ((15..=25).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_digit())).then(|| id.to_owned())
}

// --- Verbindung (austauschbar für Tests) ----------------------------------------------

/// Baut Verbindungen zur Discord-App auf.
pub trait Connector: Send + Sync {
    type Conn: Connection;
    fn connect(&self, app_id: &str) -> impl Future<Output = std::io::Result<Self::Conn>> + Send;
}

/// Eine offene Verbindung; `None` löscht den Status.
pub trait Connection: Send {
    fn set_activity(&mut self, activity: Option<&Activity>) -> impl Future<Output = std::io::Result<()>> + Send;
}

// --- Dienst ------------------------------------------------------------------------------

/// Hält den Zustand und treibt die Hintergrund-Schleife ([`Self::run`]).
pub struct DiscordPresence {
    /// `None`: dieser Build hat keine (gültige) App-ID – alles bleibt still.
    app_id: Option<String>,
    state: Mutex<State>,
    notify: Notify,
    shutting_down: AtomicBool,
    stopped: Notify,
}

impl DiscordPresence {
    /// Mit der eingebauten App-ID.
    pub fn from_build() -> Self {
        Self::new(DISCORD_APP_ID)
    }

    pub fn new(app_id: &str) -> Self {
        Self {
            app_id: valid_app_id(app_id),
            state: Mutex::default(),
            notify: Notify::new(),
            shutting_down: AtomicBool::new(false),
            stopped: Notify::new(),
        }
    }

    /// Ob dieser Build überhaupt einen Discord-Status zeigen kann.
    pub fn available(&self) -> bool {
        self.app_id.is_some()
    }

    fn state(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn change(&self, f: impl FnOnce(&mut State) -> bool) {
        if f(&mut self.state()) {
            self.notify.notify_one();
        }
    }

    /// Einstellungen übernehmen (Schalter „Discord-Status zeigen“, Sprache).
    pub fn configure(&self, enabled: bool, language: Language) {
        self.change(|s| {
            let changed = s.enabled != enabled || s.language != language;
            (s.enabled, s.language) = (enabled, language);
            changed
        });
    }

    /// Ein Spiel startet – es wird ab jetzt gezeigt (auch vor älteren).
    pub fn game_started(&self, game: GameInfo) {
        self.change(|s| {
            s.games.retain(|g| g.instance_id != game.instance_id);
            s.games.push(game);
            true
        });
    }

    /// Ein Spiel ist beendet – dann das zuvor gestartete oder wieder der Launcher.
    pub fn game_exited(&self, instance_id: &str) {
        self.change(|s| {
            let before = s.games.len();
            s.games.retain(|g| g.instance_id != instance_id);
            s.games.len() != before
        });
    }

    /// Was Discord gerade zeigen soll (`None` = nichts).
    pub fn activity(&self) -> Option<Activity> {
        activity_for(&self.state())
    }

    /// Hintergrund-Schleife: verbindet sich mit Discord, sobald es läuft, und
    /// hält den Status aktuell. Endet erst mit [`Self::shutdown`].
    pub async fn run<C: Connector>(&self, connector: C) {
        let Some(app_id) = self.app_id.clone() else {
            tracing::debug!("Discord-Status: keine App-ID in diesem Build");
            return;
        };
        let mut conn: Option<C::Conn> = None;
        // Was Discord zuletzt bestätigt hat (`None` = unbekannt, neu senden).
        let mut shown: Option<Option<Activity>> = None;
        let mut timer_expired = false;
        loop {
            if self.shutting_down.load(Ordering::Acquire) {
                if let Some(c) = conn.as_mut() {
                    let _ = tokio::time::timeout(IO_TIMEOUT, c.set_activity(None)).await;
                }
                self.stopped.notify_one();
                return;
            }
            let desired = self.activity();
            let wait = self.step(&connector, &app_id, &mut conn, &mut shown, desired, timer_expired).await;
            timer_expired = tokio::select! {
                () = async {
                    match wait {
                        Some(wait) => tokio::time::sleep(wait).await,
                        None => std::future::pending().await,
                    }
                } => true,
                () = self.notify.notified() => false,
            };
        }
    }

    /// Ein Durchgang; liefert, wie lange bis zum nächsten gewartet wird (`None` = bis sich etwas ändert).
    async fn step<C: Connector>(
        &self,
        connector: &C,
        app_id: &str,
        conn: &mut Option<C::Conn>,
        shown: &mut Option<Option<Activity>>,
        desired: Option<Activity>,
        timer_expired: bool,
    ) -> Option<Duration> {
        if desired.is_none() {
            // Abgeschaltet: einmal löschen, Verbindung schließen, nicht mehr verbinden.
            if let Some(mut c) = conn.take()
                && shown.as_ref().is_none_or(Option::is_some)
            {
                let _ = tokio::time::timeout(IO_TIMEOUT, c.set_activity(None)).await;
            }
            *shown = None;
            return None;
        }
        if conn.is_none() {
            match tokio::time::timeout(IO_TIMEOUT, connector.connect(app_id)).await {
                Ok(Ok(c)) => {
                    tracing::info!("Discord-Status: mit Discord verbunden");
                    *conn = Some(c);
                    *shown = None;
                }
                Ok(Err(e)) => {
                    tracing::trace!("Discord-Status: Discord nicht erreichbar ({e})");
                    return Some(RETRY);
                }
                Err(_) => {
                    tracing::debug!("Discord-Status: Zeitüberschreitung beim Verbinden");
                    return Some(RETRY);
                }
            }
        }
        // Unverändert? Nach Ablauf des Takts trotzdem senden – so merkt die Schleife,
        // wenn Discord inzwischen beendet oder neu gestartet wurde.
        if shown.as_ref() == Some(&desired) && !timer_expired {
            return Some(RETRY);
        }
        let Some(c) = conn.as_mut() else { return Some(RETRY) };
        match tokio::time::timeout(IO_TIMEOUT, c.set_activity(desired.as_ref())).await {
            Ok(Ok(())) => *shown = Some(desired),
            Ok(Err(e)) => {
                tracing::debug!("Discord-Status: Verbindung verloren ({e})");
                (*conn, *shown) = (None, None);
            }
            Err(_) => {
                tracing::debug!("Discord-Status: Discord antwortet nicht");
                (*conn, *shown) = (None, None);
            }
        }
        Some(RETRY)
    }

    /// Beim Beenden des Launchers: Status löschen (höchstens ~1,5 s).
    pub async fn shutdown(&self) {
        if self.app_id.is_none() {
            return;
        }
        self.shutting_down.store(true, Ordering::Release);
        self.notify.notify_one();
        let _ = tokio::time::timeout(SHUTDOWN_TIMEOUT, self.stopped.notified()).await;
    }
}

#[cfg(test)]
mod tests;
