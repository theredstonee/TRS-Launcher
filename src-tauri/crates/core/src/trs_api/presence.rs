//! Präsenz-Zustand: welche Spiele laufen (mit welchem Account) und wofür der
//! Launcher zuletzt was gemeldet hat.
//!
//! Launcher und TRS Client melden getrennt (`via`), die API führt beides
//! zusammen (API.md §4.2):
//! - Der Launcher sendet alle 60 s `online` für den aktiven Account –
//!   **nur**, solange kein Spiel dieses Accounts läuft.
//! - Solange ein vom Launcher gestartetes Spiel läuft, sendet er für dessen
//!   Account `in-game` (Version/Loader, nie einen Server). Daran hängt das
//!   Live-TRS-Abzeichen: Es erscheint nur, solange jemand mit TRS spielt.
//! - Der Mod meldet zusätzlich `in-game` (mit Server nur bei `shareServer`),
//!   solange man in einer Welt ist.
//! - Nach dem Spielende meldet der Launcher sofort wieder `online` bzw. nimmt
//!   `in-game` zurück (`offline` mit `via: launcher` trifft nur seine Meldung).
//!
//! Spiele, die nach einem Launcher-Neustart übernommen wurden, haben keinen
//! bekannten Account – dann meldet der Launcher für niemanden `online` und für
//! sie kein `in-game`, bis sie beendet sind (lieber zu still als falsch).

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tokio::sync::Notify;

/// Takt der Heartbeats (API: spätestens alle 60 s, Präsenz verfällt nach 180 s).
pub const PRESENCE_INTERVAL: Duration = Duration::from_secs(60);
/// Mindestabstand zwischen zwei Sendungen (API-Limit: 6/min).
#[cfg(not(test))]
pub(crate) const MIN_GAP: Duration = Duration::from_secs(12);
#[cfg(test)]
pub(crate) const MIN_GAP: Duration = Duration::from_millis(50);

/// Was ein laufendes Spiel der API verrät (wie im Discord-Status: Version und
/// der gewählte Loader, nie Instanzname oder Server).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PresenceGame {
    pub version: String,
    /// `vanilla`, `fabric`, `quilt`, `forge` oder `neoforge`.
    pub loader: String,
}

impl PresenceGame {
    /// Nur Werte, die die API annimmt (`^[0-9A-Za-z._+ -]{1,32}$` und feste Loader) – sonst ohne Spielangabe.
    pub fn checked(version: &str, loader: &str) -> Option<Self> {
        let version_ok = (1..=32).contains(&version.len())
            && version.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '+' | ' ' | '-'));
        let loader_ok = matches!(loader, "vanilla" | "fabric" | "quilt" | "forge" | "neoforge");
        (version_ok && loader_ok).then(|| Self { version: version.to_owned(), loader: loader.to_owned() })
    }
}

#[derive(Default)]
pub(crate) struct PresenceState {
    pub(crate) notify: Notify,
    inner: Mutex<Inner>,
}

struct Running {
    /// `None` = unbekannt (nach einem Launcher-Neustart übernommen).
    account: Option<String>,
    game: Option<PresenceGame>,
}

#[derive(Default)]
struct Inner {
    /// Laufende Spiele: Instanz → Account + Spielangabe.
    games: HashMap<String, Running>,
    /// Für diesen Account wurde zuletzt `online` gemeldet.
    online_for: Option<String>,
    /// Für diese Accounts wurde zuletzt `in-game` (via launcher) gemeldet.
    in_game_for: BTreeSet<String>,
    last_sent: Option<Instant>,
}

/// Was in diesem Takt zu senden ist.
#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct Plan {
    /// `offline` für den vorher aktiven Account (Accountwechsel).
    pub offline: Option<String>,
    pub online: Option<String>,
    /// `in-game` je Account mit laufendem, vom Launcher gestartetem Spiel.
    pub in_game: Vec<(String, Option<PresenceGame>)>,
    /// Spiel beendet: `in-game` dieser Accounts zurücknehmen (sofern nicht gleich `online` folgt).
    pub ended: Vec<String>,
}

impl Plan {
    pub fn is_empty(&self) -> bool {
        self.offline.is_none() && self.online.is_none() && self.in_game.is_empty() && self.ended.is_empty()
    }
}

impl PresenceState {
    fn inner(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Ein Spiel wurde gestartet (`account` = `None`, wenn unbekannt). Meldet bald `in-game`.
    pub fn game_started(&self, instance_id: &str, account: Option<&str>, game: Option<PresenceGame>) {
        self.inner()
            .games
            .insert(instance_id.to_owned(), Running { account: account.map(str::to_owned), game });
        self.notify.notify_one();
    }

    /// Im laufenden Spiel wurde das Konto gewechselt (TRS Link): Die Spielangabe
    /// bleibt, das alte Konto gilt für den Launcher als „Spiel beendet“.
    pub fn game_account_changed(&self, instance_id: &str, account: &str) {
        {
            let mut inner = self.inner();
            let game = inner.games.get(instance_id).and_then(|g| g.game.clone());
            inner.games.insert(instance_id.to_owned(), Running { account: Some(account.to_owned()), game });
        }
        self.notify.notify_one();
    }

    /// Ein Spiel ist beendet – der Launcher meldet sofort wieder.
    pub fn game_exited(&self, instance_id: &str) {
        self.inner().games.remove(instance_id);
        self.notify.notify_one();
    }

    /// Außerplanmäßig senden (Account gewechselt, Einwilligung geändert).
    pub fn kick(&self) {
        self.notify.notify_one();
    }

    /// Läuft ein Spiel dieses Accounts (oder eines mit unbekanntem Account)?
    #[cfg(test)]
    pub fn busy(&self, account: &str) -> bool {
        let inner = self.inner();
        busy(&inner.games, account)
    }

    pub fn plan(&self, active: Option<&str>) -> Plan {
        let inner = self.inner();
        plan(active, inner.online_for.as_deref(), &inner.in_game_for, &inner.games)
    }

    pub fn online_for(&self) -> Option<String> {
        self.inner().online_for.clone()
    }

    pub fn set_online_for(&self, account: Option<&str>) {
        let mut inner = self.inner();
        inner.online_for = account.map(str::to_owned);
        // `online` ersetzt beim Server die `in-game`-Meldung des Launchers für diesen Account.
        if let Some(a) = account {
            inner.in_game_for.remove(a);
        }
    }

    /// Für diese Accounts steht beim Server gerade `in-game` (via launcher).
    pub fn in_game_for(&self) -> Vec<String> {
        self.inner().in_game_for.iter().cloned().collect()
    }

    pub fn set_in_game(&self, account: &str, in_game: bool) {
        let mut inner = self.inner();
        if in_game {
            inner.in_game_for.insert(account.to_owned());
        } else {
            inner.in_game_for.remove(account);
        }
    }

    /// Wie lange bis zur nächsten erlaubten Sendung.
    pub fn gap_left(&self) -> Duration {
        self.inner().last_sent.map_or(Duration::ZERO, |t| MIN_GAP.saturating_sub(t.elapsed()))
    }

    pub fn mark_sent(&self) {
        self.inner().last_sent = Some(Instant::now());
    }
}

fn busy(games: &HashMap<String, Running>, account: &str) -> bool {
    games.values().any(|g| g.account.as_deref().is_none_or(|a| a == account))
}

/// Reine Entscheidung (getestet): wen offline melden, wen online, wen im Spiel.
fn plan(
    active: Option<&str>,
    online_for: Option<&str>,
    in_game_for: &BTreeSet<String>,
    games: &HashMap<String, Running>,
) -> Plan {
    let is_busy = |a: &str| busy(games, a);
    let offline = online_for.filter(|prev| Some(*prev) != active && !is_busy(prev)).map(str::to_owned);
    let online = active.filter(|a| !is_busy(a)).map(str::to_owned);
    // Je Account das erste bekannte Spiel (sortiert: stabile Reihenfolge).
    let mut playing: BTreeMap<String, Option<PresenceGame>> = BTreeMap::new();
    for g in games.values() {
        if let Some(account) = &g.account {
            let entry = playing.entry(account.clone()).or_insert(None);
            if entry.is_none() {
                entry.clone_from(&g.game);
            }
        }
    }
    let ended = in_game_for
        .iter()
        .filter(|a| !playing.contains_key(*a) && online.as_deref() != Some(a.as_str()))
        .cloned()
        .collect();
    Plan { offline, online, in_game: playing.into_iter().collect(), ended }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn game(version: &str) -> Option<PresenceGame> {
        PresenceGame::checked(version, "fabric")
    }

    #[test]
    fn online_only_without_running_game_and_in_game_while_it_runs() {
        let state = PresenceState::default();
        assert_eq!(state.plan(Some("a")), Plan { online: Some("a".into()), ..Plan::default() });

        state.game_started("inst", Some("a"), game("1.21.11"));
        assert_eq!(
            state.plan(Some("a")),
            Plan { in_game: vec![("a".into(), game("1.21.11"))], ..Plan::default() },
            "Spiel läuft → in-game statt online"
        );
        // Anderer Account ist frei.
        let other = state.plan(Some("b"));
        assert_eq!(other.online.as_deref(), Some("b"));
        assert_eq!(other.in_game, vec![("a".into(), game("1.21.11"))]);

        state.set_in_game("a", true);
        state.game_exited("inst");
        // Aktiver Account: online überschreibt in-game, kein eigenes Zurücknehmen nötig.
        assert_eq!(state.plan(Some("a")), Plan { online: Some("a".into()), ..Plan::default() });
        // Spiel eines anderen Accounts beendet → dessen in-game zurücknehmen.
        assert_eq!(state.plan(Some("b")), Plan { online: Some("b".into()), ended: vec!["a".into()], ..Plan::default() });
        state.set_online_for(Some("a"));
        assert!(state.in_game_for().is_empty(), "online ersetzt in-game");
    }

    #[test]
    fn switching_the_account_in_game_keeps_the_game_and_ends_the_old_account() {
        let state = PresenceState::default();
        state.game_started("inst", Some("a"), game("1.21.11"));
        state.set_in_game("a", true);
        state.game_account_changed("inst", "b");
        let plan = state.plan(None);
        assert_eq!(plan.in_game, vec![("b".into(), game("1.21.11"))]);
        assert_eq!(plan.ended, vec!["a".to_owned()]);
    }

    #[test]
    fn two_games_of_one_account_report_once() {
        let state = PresenceState::default();
        state.game_started("x", Some("a"), None);
        state.game_started("y", Some("a"), game("1.8.9"));
        let plan = state.plan(None);
        assert_eq!(plan.in_game, vec![("a".into(), game("1.8.9"))]);
        state.game_exited("y");
        assert_eq!(state.plan(None).in_game, vec![("a".into(), None)]);
    }

    #[test]
    fn switching_accounts_sends_offline_for_the_old_one() {
        let state = PresenceState::default();
        state.set_online_for(Some("a"));
        assert_eq!(state.plan(Some("b")), Plan { offline: Some("a".into()), online: Some("b".into()), ..Plan::default() });
        assert_eq!(state.plan(None), Plan { offline: Some("a".into()), ..Plan::default() });

        // Läuft noch ein Spiel mit "a", meldet der Launcher dafür in-game.
        state.game_started("inst", Some("a"), None);
        let plan = state.plan(Some("b"));
        assert_eq!(plan.offline, None);
        assert_eq!(plan.online.as_deref(), Some("b"));
        assert_eq!(plan.in_game, vec![("a".into(), None)]);
    }

    #[test]
    fn unknown_games_silence_everyone() {
        let state = PresenceState::default();
        state.game_started("recovered", None, None);
        assert!(state.busy("a") && state.busy("b"));
        assert_eq!(state.plan(Some("a")), Plan::default());
    }

    #[test]
    fn game_info_is_checked_like_the_api() {
        assert!(PresenceGame::checked("1.21.11", "neoforge").is_some());
        assert!(PresenceGame::checked("26w14a", "vanilla").is_some());
        assert!(PresenceGame::checked("", "fabric").is_none());
        assert!(PresenceGame::checked(&"1".repeat(33), "fabric").is_none());
        assert!(PresenceGame::checked("1.21<script>", "fabric").is_none());
        assert!(PresenceGame::checked("1.21", "liteloader").is_none());
    }

    #[test]
    fn gap_is_respected() {
        let state = PresenceState::default();
        assert_eq!(state.gap_left(), Duration::ZERO);
        state.mark_sent();
        assert!(state.gap_left() > Duration::ZERO && state.gap_left() <= MIN_GAP);
    }
}
