//! Präsenz-Zustand: welche Spiele laufen (mit welchem Account) und für wen der
//! Launcher zuletzt „online“ gemeldet hat.
//!
//! Aufteilung mit dem TRS Client im Spiel (fest abgesprochen):
//! - Der Launcher sendet `online` alle 60 s – **nur**, solange kein Spiel des
//!   Accounts läuft.
//! - Während ein Spiel läuft, sendet der Launcher nichts; der Mod meldet
//!   `in-game` (Version/Loader, Server nur mit `shareServer`).
//! - Nach dem Spielende meldet der Launcher sofort wieder `online`.
//!
//! Spiele, die nach einem Launcher-Neustart übernommen wurden, haben keinen
//! bekannten Account – dann schweigt der Launcher für alle Accounts, bis sie
//! beendet sind (lieber zu still als dem Mod dazwischenfunken).

use std::collections::HashMap;
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

#[derive(Default)]
pub(crate) struct PresenceState {
    pub(crate) notify: Notify,
    inner: Mutex<Inner>,
}

#[derive(Default)]
struct Inner {
    /// Laufende Spiele: Instanz → Account (`None` = unbekannt).
    games: HashMap<String, Option<String>>,
    /// Für diesen Account wurde zuletzt `online` gemeldet.
    online_for: Option<String>,
    last_sent: Option<Instant>,
}

/// Was in diesem Takt zu senden ist.
#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct Plan {
    pub offline: Option<String>,
    pub online: Option<String>,
}

impl PresenceState {
    fn inner(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Ein Spiel wurde gestartet (`account` = `None`, wenn unbekannt).
    pub fn game_started(&self, instance_id: &str, account: Option<&str>) {
        self.inner().games.insert(instance_id.to_owned(), account.map(str::to_owned));
    }

    /// Ein Spiel ist beendet – der Launcher übernimmt sofort wieder.
    pub fn game_exited(&self, instance_id: &str) {
        self.inner().games.remove(instance_id);
        self.notify.notify_one();
    }

    /// Außerplanmäßig senden (Account gewechselt, Einwilligung geändert).
    pub fn kick(&self) {
        self.notify.notify_one();
    }

    /// Läuft ein Spiel dieses Accounts (oder eines mit unbekanntem Account)?
    pub fn busy(&self, account: &str) -> bool {
        let inner = self.inner();
        busy(&inner.games, account)
    }

    pub fn plan(&self, active: Option<&str>) -> Plan {
        let inner = self.inner();
        plan(active, inner.online_for.as_deref(), &|a| busy(&inner.games, a))
    }

    pub fn online_for(&self) -> Option<String> {
        self.inner().online_for.clone()
    }

    pub fn set_online_for(&self, account: Option<&str>) {
        self.inner().online_for = account.map(str::to_owned);
    }

    /// Wie lange bis zur nächsten erlaubten Sendung.
    pub fn gap_left(&self) -> Duration {
        self.inner().last_sent.map_or(Duration::ZERO, |t| MIN_GAP.saturating_sub(t.elapsed()))
    }

    pub fn mark_sent(&self) {
        self.inner().last_sent = Some(Instant::now());
    }
}

fn busy(games: &HashMap<String, Option<String>>, account: &str) -> bool {
    games.values().any(|g| g.as_deref().is_none_or(|a| a == account))
}

/// Reine Entscheidung (getestet): wen offline melden, wen online.
pub(crate) fn plan(active: Option<&str>, online_for: Option<&str>, busy: &dyn Fn(&str) -> bool) -> Plan {
    let offline = online_for.filter(|prev| Some(*prev) != active && !busy(prev)).map(str::to_owned);
    let online = active.filter(|a| !busy(a)).map(str::to_owned);
    Plan { offline, online }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn online_only_without_running_game() {
        let state = PresenceState::default();
        assert_eq!(state.plan(Some("a")), Plan { offline: None, online: Some("a".into()) });

        state.game_started("inst", Some("a"));
        assert_eq!(state.plan(Some("a")), Plan::default(), "Spiel läuft → der Mod meldet");
        // Anderer Account ist frei.
        assert_eq!(state.plan(Some("b")).online.as_deref(), Some("b"));

        state.game_exited("inst");
        assert_eq!(state.plan(Some("a")).online.as_deref(), Some("a"));
    }

    #[test]
    fn switching_accounts_sends_offline_for_the_old_one() {
        let state = PresenceState::default();
        state.set_online_for(Some("a"));
        assert_eq!(state.plan(Some("b")), Plan { offline: Some("a".into()), online: Some("b".into()) });
        assert_eq!(state.plan(None), Plan { offline: Some("a".into()), online: None });

        // Läuft noch ein Spiel mit "a", bleibt dessen Präsenz dem Mod überlassen.
        state.game_started("inst", Some("a"));
        assert_eq!(state.plan(Some("b")), Plan { offline: None, online: Some("b".into()) });
    }

    #[test]
    fn unknown_games_silence_everyone() {
        let state = PresenceState::default();
        state.game_started("recovered", None);
        assert!(state.busy("a") && state.busy("b"));
        assert_eq!(state.plan(Some("a")), Plan::default());
    }

    #[test]
    fn gap_is_respected() {
        let state = PresenceState::default();
        assert_eq!(state.gap_left(), Duration::ZERO);
        state.mark_sent();
        assert!(state.gap_left() > Duration::ZERO && state.gap_left() <= MIN_GAP);
    }
}
