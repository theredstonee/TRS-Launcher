//! Warteschlange für Skin- und Umhang-Änderungen.
//!
//! Das Webview bearbeitet einen lokalen Entwurf und schickt erst beim Klick auf
//! „Anwenden“ den gewünschten Endzustand. Hier wird daraus höchstens eine
//! Anfrage je Art (Skin, Umhang) gleichzeitig:
//!
//! * Ein neuer Wunsch ersetzt einen älteren, der noch nicht gesendet wurde –
//!   Zwischenstände erreichen Mojang nie.
//! * Der eigene Takt (Abstand + Obergrenze je Zeitfenster) lässt Anfragen
//!   **warten** statt sie abzulehnen.
//! * Antwortet Mojang mit 429, bleibt der Wunsch erhalten und wird nach
//!   `Retry-After` (sonst exponentiellem Backoff) automatisch erneut gesendet.
//!
//! Den Fortschritt fragt das Webview über [`SkinSyncStatus`] ab. Das Token
//! bleibt wie überall im Kern.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::skins::{self, ApiError, Profile, SkinVariant};
use crate::{Error, Launcher, Result};

/// Mindestabstand zwischen zwei Änderungen am Konto.
const MIN_GAP: Duration = Duration::from_secs(2);
/// Sicherung gegen Endlosschleifen: höchstens so viele Änderungen je Fenster –
/// danach wird gewartet, nicht abgelehnt.
const WINDOW: Duration = Duration::from_secs(600);
const MAX_IN_WINDOW: usize = 20;

/// Backoff ohne `Retry-After`: 30 s, 60 s, 120 s, … höchstens 5 min.
const BACKOFF_BASE: Duration = Duration::from_secs(30);
const BACKOFF_MAX: Duration = Duration::from_secs(300);
/// `Retry-After` wird auf diesen Bereich begrenzt.
const RETRY_MIN: Duration = Duration::from_secs(1);
const RETRY_MAX: Duration = Duration::from_secs(900);
/// Netzwerkfehler hintereinander, bevor aufgegeben wird.
const MAX_TRANSIENT: u32 = 4;

// --- Was das Webview schickt -------------------------------------------------------

/// Gewünschter Skin. `current` = getragene Textur behalten, nur das Modell ändern.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum SkinChange {
    Library { id: String, variant: SkinVariant },
    Current { variant: SkinVariant },
    Default,
}

/// Gewünschter Umhang; `id: null` = keinen tragen.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CapeChange {
    pub id: Option<String>,
}

/// Unterschied zwischen Entwurf und Konto. Fehlt eine Art, bleibt sie, wie sie ist.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkinChanges {
    #[serde(default)]
    pub skin: Option<SkinChange>,
    #[serde(default)]
    pub cape: Option<CapeChange>,
}

// --- Aufträge ----------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum SkinOp {
    /// Bibliotheks-Skin hochladen (Bytes sind schon geprüft).
    Upload { library_id: String, variant: SkinVariant, bytes: Vec<u8> },
    /// Getragene Textur mit anderem Modell erneut hochladen.
    Reupload { variant: SkinVariant },
    /// Zurück zum Standard-Skin.
    Reset,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum CapeOp {
    Show(String),
    Hide,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Op {
    Skin(SkinOp),
    Cape(CapeOp),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Skin,
    Cape,
}

impl Op {
    fn kind(&self) -> Kind {
        match self {
            Self::Skin(_) => Kind::Skin,
            Self::Cape(_) => Kind::Cape,
        }
    }
}

/// Ein Platz je Art: Wunsch, laufende Anfrage und zuletzt Erledigtes.
#[derive(Debug)]
struct Slot<T> {
    /// Was noch gesendet werden soll (neuester Wunsch).
    desired: Option<T>,
    /// Was gerade unterwegs ist.
    in_flight: Option<T>,
    /// Was in diesem Durchlauf schon erfolgreich gesetzt wurde.
    applied: Option<T>,
}

impl<T> Default for Slot<T> {
    fn default() -> Self {
        Self { desired: None, in_flight: None, applied: None }
    }
}

impl<T: Clone + PartialEq> Slot<T> {
    /// Neuer Wunsch – ersetzt einen noch nicht gesendeten älteren.
    fn want(&mut self, op: Option<T>) {
        let already_done = self.in_flight.is_none() && op.is_some() && op == self.applied;
        self.desired = if already_done { None } else { op };
    }

    /// Nächsten Auftrag holen, sofern nichts unterwegs ist.
    fn take(&mut self) -> Option<T> {
        if self.in_flight.is_some() {
            return None;
        }
        let op = self.desired.clone()?;
        self.in_flight = Some(op.clone());
        Some(op)
    }

    fn succeeded(&mut self) {
        self.applied = self.in_flight.take();
        if self.desired == self.applied {
            self.desired = None;
        }
    }

    /// Später erneut: Der Wunsch bleibt – es sei denn, er wurde inzwischen ersetzt
    /// oder zurückgenommen, dann gilt der neuere.
    fn retry(&mut self) {
        self.in_flight = None;
    }

    fn failed(&mut self) {
        let failed = self.in_flight.take();
        if self.desired == failed {
            self.desired = None;
        }
    }

    fn pending(&self) -> bool {
        self.desired.is_some() || self.in_flight.is_some()
    }
}

/// Die eigentliche Warteschlange – ohne Netzwerk, damit sie testbar bleibt.
#[derive(Debug, Default)]
pub(crate) struct SyncQueue {
    account: Option<String>,
    skin: Slot<SkinOp>,
    cape: Slot<CapeOp>,
}

impl SyncQueue {
    /// Neuer Wunschzustand für `account`. Er ersetzt den vorherigen vollständig:
    /// Fehlt eine Art, wird ein noch wartender Auftrag dafür verworfen.
    fn submit(&mut self, account: &str, skin: Option<SkinOp>, cape: Option<CapeOp>) {
        if self.account.as_deref() != Some(account) {
            self.skin = Slot::default();
            self.cape = Slot::default();
            self.account = Some(account.to_owned());
        }
        self.skin.want(skin);
        self.cape.want(cape);
    }

    /// Alles Wartende verwerfen (laufende Anfragen lassen sich nicht zurückholen).
    fn cancel(&mut self) {
        self.skin.desired = None;
        self.cape.desired = None;
    }

    /// Nächster Auftrag: erst der Skin, dann der Umhang – nie zwei gleichzeitig.
    fn next(&mut self) -> Option<Op> {
        if self.skin.in_flight.is_some() || self.cape.in_flight.is_some() {
            return None;
        }
        self.skin.take().map(Op::Skin).or_else(|| self.cape.take().map(Op::Cape))
    }

    fn finish(&mut self, kind: Kind, outcome: Outcome) {
        let slot: &mut dyn SlotControl = match kind {
            Kind::Skin => &mut self.skin,
            Kind::Cape => &mut self.cape,
        };
        match outcome {
            Outcome::Ok => slot.succeeded(),
            Outcome::Retry => slot.retry(),
            Outcome::Failed => slot.failed(),
        }
    }

    fn pending(&self) -> bool {
        self.skin.pending() || self.cape.pending()
    }

    /// Nach einem vollständigen Durchlauf: Das Profil ist neu geladen, das
    /// Webview vergleicht ab jetzt wieder damit.
    fn settle(&mut self) {
        self.skin.applied = None;
        self.cape.applied = None;
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Outcome {
    Ok,
    Retry,
    Failed,
}

/// Typlose Sicht auf einen Slot (für `finish`).
trait SlotControl {
    fn succeeded(&mut self);
    fn retry(&mut self);
    fn failed(&mut self);
}

impl<T: Clone + PartialEq> SlotControl for Slot<T> {
    fn succeeded(&mut self) {
        Slot::succeeded(self);
    }
    fn retry(&mut self) {
        Slot::retry(self);
    }
    fn failed(&mut self) {
        Slot::failed(self);
    }
}

// --- Takt und Wartezeiten ------------------------------------------------------------

/// Eigener Takt für Konto-Änderungen. Liefert eine Wartezeit statt eines Fehlers.
#[derive(Debug)]
pub(crate) struct Pacer {
    min_gap: Duration,
    window: Duration,
    max_in_window: usize,
    recent: VecDeque<Instant>,
}

impl Default for Pacer {
    fn default() -> Self {
        Self::new(MIN_GAP, WINDOW, MAX_IN_WINDOW)
    }
}

impl Pacer {
    pub(crate) const fn new(min_gap: Duration, window: Duration, max_in_window: usize) -> Self {
        Self { min_gap, window, max_in_window, recent: VecDeque::new() }
    }

    /// Wie lange bis zur nächsten erlaubten Anfrage (`ZERO` = sofort).
    pub(crate) fn wait_time(&mut self, now: Instant) -> Duration {
        while self.recent.front().is_some_and(|front| now.duration_since(*front) >= self.window) {
            self.recent.pop_front();
        }
        let gap = self.recent.back().map_or(Duration::ZERO, |last| (*last + self.min_gap).saturating_duration_since(now));
        let full = if self.recent.len() >= self.max_in_window {
            self.recent.front().map_or(Duration::ZERO, |first| (*first + self.window).saturating_duration_since(now))
        } else {
            Duration::ZERO
        };
        gap.max(full)
    }

    pub(crate) fn record(&mut self, now: Instant) {
        self.recent.push_back(now);
    }
}

/// `Retry-After` lesen: Sekunden (`"42"`) oder HTTP-Datum (`"Wed, 21 Oct 2026 07:28:00 GMT"`).
pub(crate) fn parse_retry_after(value: &str, now: DateTime<Utc>) -> Option<Duration> {
    let value = value.trim();
    if let Ok(seconds) = value.parse::<u64>() {
        return Some(Duration::from_secs(seconds));
    }
    let at = DateTime::parse_from_rfc2822(value).ok()?.with_timezone(&Utc);
    Some((at - now).to_std().unwrap_or(Duration::ZERO))
}

/// Wartezeit nach einem 429: `Retry-After` (begrenzt), sonst exponentiell.
pub(crate) fn retry_delay(retry_after: Option<Duration>, attempt: u32) -> Duration {
    match retry_after {
        Some(wait) => wait.clamp(RETRY_MIN, RETRY_MAX),
        None => backoff(attempt),
    }
}

pub(crate) fn backoff(attempt: u32) -> Duration {
    BACKOFF_BASE.saturating_mul(1u32 << attempt.min(16)).min(BACKOFF_MAX)
}

// --- Status fürs Webview ---------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum SyncState {
    /// Nichts zu tun.
    Idle,
    /// Anfrage läuft.
    Applying,
    /// Wartet (siehe `reason`, `retry_at`) und macht dann von selbst weiter.
    Waiting,
    /// Fertig; `profile` ist der neue Stand (oder `null`, wenn er nicht geladen werden konnte).
    Done,
    /// Aufgegeben; `message` sagt warum.
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum WaitReason {
    /// Mojang hat mit 429 geantwortet.
    RateLimited,
    /// Eigener Takt (viele Änderungen kurz hintereinander).
    Pacing,
    /// Mojang nicht erreichbar – neuer Versuch folgt.
    Network,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SkinSyncStatus {
    /// Zählt bei jeder Änderung hoch – so erkennt das Webview Neues.
    pub version: u64,
    pub state: SyncState,
    /// Konto, auf das sich der Status bezieht (UUID ohne Bindestriche).
    pub account: Option<String>,
    pub reason: Option<WaitReason>,
    /// Wann es weitergeht (Unix-Zeit in Millisekunden).
    pub retry_at: Option<i64>,
    pub message: Option<String>,
    pub pending_skin: bool,
    pub pending_cape: bool,
    pub profile: Option<Profile>,
}

impl Default for SkinSyncStatus {
    fn default() -> Self {
        Self {
            version: 0,
            state: SyncState::Idle,
            account: None,
            reason: None,
            retry_at: None,
            message: None,
            pending_skin: false,
            pending_cape: false,
            profile: None,
        }
    }
}

#[derive(Debug, Default)]
struct Inner {
    queue: SyncQueue,
    pacer: Pacer,
    status: SkinSyncStatus,
    /// Läuft gerade ein Abarbeiter?
    worker: bool,
    /// Fehler aus diesem Durchlauf (für den Abschluss).
    error: Option<String>,
}

impl Inner {
    fn set(&mut self, state: SyncState, reason: Option<WaitReason>, wait: Option<Duration>) {
        let status = &mut self.status;
        status.version += 1;
        status.state = state;
        status.account.clone_from(&self.queue.account);
        status.reason = reason;
        status.retry_at = wait.map(|d| {
            let wait = chrono::Duration::from_std(d).unwrap_or(chrono::Duration::zero());
            (Utc::now() + wait).timestamp_millis()
        });
        status.pending_skin = self.queue.skin.pending();
        status.pending_cape = self.queue.cape.pending();
        if !matches!(state, SyncState::Done | SyncState::Failed) {
            status.message = None;
            status.profile = None;
        }
    }
}

/// Zustand der Warteschlange (ein Exemplar je Launcher).
#[derive(Debug, Default)]
pub struct SkinSync {
    inner: Mutex<Inner>,
}

impl SkinSync {
    fn with<R>(&self, f: impl FnOnce(&mut Inner) -> R) -> R {
        f(&mut self.inner.lock().unwrap_or_else(PoisonError::into_inner))
    }

    pub fn status(&self) -> SkinSyncStatus {
        self.with(|inner| inner.status.clone())
    }

    /// Wunsch einreihen. Liefert `true`, wenn ein neuer Abarbeiter starten muss.
    fn enqueue(&self, account: &str, skin: Option<SkinOp>, cape: Option<CapeOp>) -> (bool, SkinSyncStatus) {
        self.with(|inner| {
            inner.queue.submit(account, skin, cape);
            let start = !inner.worker && inner.queue.pending();
            if start {
                inner.worker = true;
                inner.error = None;
                inner.set(SyncState::Applying, None, None);
            } else if inner.worker {
                // Ein laufender Abarbeiter übernimmt – Zustand (z. B. Warten) bleibt.
                let (state, reason) = (inner.status.state, inner.status.reason);
                let wait = inner.status.retry_at.map(|at| {
                    Duration::from_millis(u64::try_from(at - Utc::now().timestamp_millis()).unwrap_or(0))
                });
                inner.set(state, reason, wait);
            }
            (start, inner.status.clone())
        })
    }

    pub fn cancel(&self) -> SkinSyncStatus {
        self.with(|inner| {
            inner.queue.cancel();
            let (state, reason) = (inner.status.state, inner.status.reason);
            let state = if inner.worker { state } else { SyncState::Idle };
            let wait = inner.status.retry_at.map(|at| {
                Duration::from_millis(u64::try_from(at - Utc::now().timestamp_millis()).unwrap_or(0))
            });
            inner.set(state, reason, wait);
            inner.status.clone()
        })
    }
}

// --- Abarbeiten ----------------------------------------------------------------------

/// Was der Abarbeiter von außen braucht – im Test durch eine Attrappe ersetzt.
pub(crate) trait Backend {
    async fn run(&self, op: &Op, account: &str) -> std::result::Result<(), ApiError>;
    async fn profile(&self, account: &str) -> std::result::Result<Profile, ApiError>;
    async fn sleep(&self, wait: Duration);
    fn now(&self) -> Instant;
}

/// Meldung, wenn ein Auftrag aufgegeben wird.
fn failure_message(error: ApiError) -> String {
    Error::from(error).public_message()
}

/// Arbeitet die Warteschlange ab, bis nichts mehr zu tun ist.
pub(crate) async fn drive<B: Backend>(sync: &SkinSync, backend: &B) {
    let mut attempt: u32 = 0;
    loop {
        // 1. Eigener Takt – gewartet wird, bevor ein Auftrag gezogen wird, damit
        //    neuere Wünsche den wartenden noch ersetzen können.
        let wait = sync.with(|inner| {
            if !inner.queue.pending() {
                return None;
            }
            let wait = inner.pacer.wait_time(backend.now());
            if !wait.is_zero() && wait > Duration::from_secs(3) {
                inner.set(SyncState::Waiting, Some(WaitReason::Pacing), Some(wait));
            }
            Some(wait)
        });
        match wait {
            Some(wait) if !wait.is_zero() => {
                backend.sleep(wait).await;
                continue;
            }
            Some(_) => {}
            None => {
                if finish(sync, backend).await {
                    return;
                }
                continue;
            }
        }

        // 2. Nächsten Auftrag ziehen.
        let Some((op, account)) = sync.with(|inner| {
            let op = inner.queue.next()?;
            inner.pacer.record(backend.now());
            inner.set(SyncState::Applying, None, None);
            Some((op, inner.queue.account.clone().unwrap_or_default()))
        }) else {
            continue;
        };

        // 3. Senden und das Ergebnis einordnen.
        let kind = op.kind();
        match backend.run(&op, &account).await {
            Ok(()) => {
                attempt = 0;
                sync.with(|inner| inner.queue.finish(kind, Outcome::Ok));
            }
            Err(ApiError::RateLimited(retry_after)) => {
                let wait = retry_delay(retry_after, attempt);
                attempt = attempt.saturating_add(1);
                tracing::info!("Mojang bremst – neuer Versuch in {} s", wait.as_secs());
                sync.with(|inner| {
                    inner.queue.finish(kind, Outcome::Retry);
                    inner.set(SyncState::Waiting, Some(WaitReason::RateLimited), Some(wait));
                });
                backend.sleep(wait).await;
            }
            Err(ApiError::Transient) if attempt + 1 < MAX_TRANSIENT => {
                let wait = backoff(attempt);
                attempt += 1;
                sync.with(|inner| {
                    inner.queue.finish(kind, Outcome::Retry);
                    inner.set(SyncState::Waiting, Some(WaitReason::Network), Some(wait));
                });
                backend.sleep(wait).await;
            }
            Err(error) => {
                attempt = 0;
                let message = failure_message(error);
                tracing::warn!("Skin-Änderung aufgegeben: {message}");
                sync.with(|inner| {
                    inner.queue.finish(kind, Outcome::Failed);
                    inner.error = Some(message);
                });
            }
        }
    }
}

/// Abschluss: frisches Profil laden und melden. `false`, wenn inzwischen
/// neue Wünsche eingetroffen sind – dann geht es weiter.
async fn finish<B: Backend>(sync: &SkinSync, backend: &B) -> bool {
    let account = sync.with(|inner| inner.queue.account.clone().unwrap_or_default());
    let profile = backend.profile(&account).await;
    sync.with(|inner| {
        if inner.queue.pending() {
            return false;
        }
        inner.queue.settle();
        inner.worker = false;
        let error = inner.error.take();
        let state = if error.is_some() { SyncState::Failed } else { SyncState::Done };
        inner.set(state, None, None);
        inner.status.message = error;
        match profile {
            Ok(profile) => inner.status.profile = Some(profile),
            Err(e) => {
                tracing::warn!("Profil nach der Änderung nicht geladen: {e:?}");
                inner.status.profile = None;
            }
        }
        true
    })
}

// --- Anbindung an den Launcher ----------------------------------------------------------

struct LauncherBackend(Arc<Launcher>);

impl LauncherBackend {
    async fn token(&self, account: &str) -> std::result::Result<String, ApiError> {
        let session = self.0.skin_session().await.map_err(|e| match e {
            Error::Http(_) => ApiError::Transient,
            other => ApiError::Fatal(other),
        })?;
        if !session.uuid.eq_ignore_ascii_case(account) {
            return Err(ApiError::Fatal(Error::auth(
                "Das aktive Konto wurde gewechselt – die Änderungen wurden verworfen.",
            )));
        }
        Ok(session.access_token)
    }
}

impl Backend for LauncherBackend {
    async fn run(&self, op: &Op, account: &str) -> std::result::Result<(), ApiError> {
        let token = self.token(account).await?;
        let (http, base) = (self.0.http(), skins::API);
        let (request, action) = match op {
            Op::Skin(SkinOp::Upload { variant, bytes, .. }) => {
                let request = skins::upload_skin_request(http, base, &token, *variant, bytes.clone());
                (request.map_err(ApiError::Fatal)?, skins::UPLOAD_ACTION)
            }
            Op::Skin(SkinOp::Reupload { variant }) => {
                let profile = skins::fetch_profile(http, base, &token).await?;
                let bytes = skins::active_skin_bytes(http, self.0.paths(), &profile).await?;
                let request = skins::upload_skin_request(http, base, &token, *variant, bytes);
                (request.map_err(ApiError::Fatal)?, skins::UPLOAD_ACTION)
            }
            Op::Skin(SkinOp::Reset) => (skins::reset_skin_request(http, base, &token), "Skin zurücksetzen"),
            Op::Cape(CapeOp::Show(id)) => {
                (skins::set_cape_request(http, base, &token, id).map_err(ApiError::Fatal)?, "Umhang setzen")
            }
            Op::Cape(CapeOp::Hide) => (skins::hide_cape_request(http, base, &token), "Umhang abnehmen"),
        };
        skins::send(request, action).await?;
        Ok(())
    }

    async fn profile(&self, account: &str) -> std::result::Result<Profile, ApiError> {
        let token = self.token(account).await?;
        let profile = skins::fetch_profile(self.0.http(), skins::API, &token).await?;
        self.0.to_profile(profile).await.map_err(ApiError::Fatal)
    }

    async fn sleep(&self, wait: Duration) {
        tokio::time::sleep(wait).await;
    }

    fn now(&self) -> Instant {
        Instant::now()
    }
}

impl Launcher {
    /// Nimmt den gewünschten Endzustand aus dem Entwurf entgegen und kehrt sofort
    /// zurück; gesendet wird im Hintergrund. `expected_account` ist das Konto,
    /// dessen Profil das Webview gerade zeigt.
    pub async fn submit_skin_changes(
        self: &Arc<Self>,
        expected_account: &str,
        changes: SkinChanges,
    ) -> Result<SkinSyncStatus> {
        let session = self.skin_session().await?;
        let account = session.uuid.to_ascii_lowercase();
        if !account.eq_ignore_ascii_case(expected_account.trim()) {
            return Err(Error::validation("Das aktive Konto hat gewechselt – bitte die Seite neu laden."));
        }

        // Alles vorher prüfen: Was hier durchfällt, geht gar nicht erst in die Schlange.
        let skin = match changes.skin {
            None => None,
            Some(SkinChange::Library { id, variant }) => {
                let bytes = self.library_skin_bytes(&id).await?;
                Some(SkinOp::Upload { library_id: id, variant, bytes })
            }
            Some(SkinChange::Current { variant }) => Some(SkinOp::Reupload { variant }),
            Some(SkinChange::Default) => Some(SkinOp::Reset),
        };
        let cape = match changes.cape {
            None => None,
            Some(CapeChange { id: Some(id) }) if skins::is_cape_id(&id) => Some(CapeOp::Show(id)),
            Some(CapeChange { id: Some(_) }) => return Err(Error::validation("Ungültiger Umhang.")),
            Some(CapeChange { id: None }) => Some(CapeOp::Hide),
        };

        let (start, status) = self.skin_sync.enqueue(&account, skin, cape);
        if start {
            let launcher = Arc::clone(self);
            tokio::spawn(async move {
                let backend = LauncherBackend(Arc::clone(&launcher));
                drive(&launcher.skin_sync, &backend).await;
            });
        }
        Ok(status)
    }

    /// Aktueller Stand der Warteschlange.
    pub fn skin_sync_status(&self) -> SkinSyncStatus {
        self.skin_sync.status()
    }

    /// Wartende Änderungen verwerfen.
    pub fn cancel_skin_sync(&self) -> SkinSyncStatus {
        self.skin_sync.cancel()
    }
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;

    use super::*;

    fn upload(id: &str) -> SkinOp {
        SkinOp::Upload { library_id: id.into(), variant: SkinVariant::Classic, bytes: vec![1, 2, 3] }
    }

    #[test]
    fn newer_wish_replaces_the_queued_one() {
        let mut queue = SyncQueue::default();
        queue.submit("acc", Some(upload("a")), None);
        queue.submit("acc", Some(upload("b")), None);
        queue.submit("acc", Some(upload("c")), Some(CapeOp::Show("cape-1".into())));
        assert_eq!(queue.next(), Some(Op::Skin(upload("c"))), "Zwischenstände werden nie gesendet");
        assert_eq!(queue.next(), None, "höchstens eine Anfrage gleichzeitig");
        queue.finish(Kind::Skin, Outcome::Ok);
        assert_eq!(queue.next(), Some(Op::Cape(CapeOp::Show("cape-1".into()))));
        queue.finish(Kind::Cape, Outcome::Ok);
        assert!(!queue.pending());
    }

    #[test]
    fn wish_during_flight_is_sent_afterwards_but_duplicates_are_not() {
        let mut queue = SyncQueue::default();
        queue.submit("acc", Some(upload("a")), None);
        assert_eq!(queue.next(), Some(Op::Skin(upload("a"))));
        // Gleicher Wunsch noch einmal (z. B. nur der Umhang kam dazu): kein zweiter Upload.
        queue.submit("acc", Some(upload("a")), Some(CapeOp::Hide));
        queue.finish(Kind::Skin, Outcome::Ok);
        assert_eq!(queue.next(), Some(Op::Cape(CapeOp::Hide)));
        queue.finish(Kind::Cape, Outcome::Ok);
        assert!(!queue.pending());

        // Neuer Wunsch während der Anfrage: danach genau einmal gesendet.
        queue.submit("acc", Some(upload("b")), None);
        assert_eq!(queue.next(), Some(Op::Skin(upload("b"))));
        queue.submit("acc", Some(upload("c")), None);
        queue.submit("acc", Some(upload("d")), None);
        queue.finish(Kind::Skin, Outcome::Ok);
        assert_eq!(queue.next(), Some(Op::Skin(upload("d"))));
    }

    #[test]
    fn already_applied_wish_is_skipped() {
        let mut queue = SyncQueue::default();
        queue.submit("acc", None, Some(CapeOp::Show("x".into())));
        queue.next();
        queue.finish(Kind::Cape, Outcome::Ok);
        // Das Webview kennt das neue Profil noch nicht und schickt den Umhang erneut mit.
        queue.submit("acc", Some(SkinOp::Reset), Some(CapeOp::Show("x".into())));
        assert_eq!(queue.next(), Some(Op::Skin(SkinOp::Reset)));
        queue.finish(Kind::Skin, Outcome::Ok);
        assert_eq!(queue.next(), None);
        assert!(!queue.pending());
    }

    #[test]
    fn rate_limited_wish_is_kept_unless_replaced_or_withdrawn() {
        let mut queue = SyncQueue::default();
        queue.submit("acc", Some(upload("a")), None);
        queue.next();
        queue.finish(Kind::Skin, Outcome::Retry);
        assert_eq!(queue.next(), Some(Op::Skin(upload("a"))), "Wunsch bleibt nach 429 erhalten");

        queue.submit("acc", Some(upload("b")), None);
        queue.finish(Kind::Skin, Outcome::Retry);
        assert_eq!(queue.next(), Some(Op::Skin(upload("b"))), "neuerer Wunsch gewinnt");

        queue.submit("acc", None, None);
        queue.finish(Kind::Skin, Outcome::Retry);
        assert_eq!(queue.next(), None, "zurückgenommen = nichts mehr senden");
        assert!(!queue.pending());
    }

    #[test]
    fn failure_drops_only_that_wish_and_account_switch_resets() {
        let mut queue = SyncQueue::default();
        queue.submit("acc", Some(upload("a")), Some(CapeOp::Hide));
        queue.next();
        queue.finish(Kind::Skin, Outcome::Failed);
        assert_eq!(queue.next(), Some(Op::Cape(CapeOp::Hide)));

        let mut queue = SyncQueue::default();
        queue.submit("acc", Some(upload("a")), None);
        queue.submit("other", None, Some(CapeOp::Hide));
        assert_eq!(queue.next(), Some(Op::Cape(CapeOp::Hide)), "Wünsche des alten Kontos verfallen");

        let mut queue = SyncQueue::default();
        queue.submit("acc", Some(upload("a")), Some(CapeOp::Hide));
        queue.cancel();
        assert!(!queue.pending());
    }

    #[test]
    fn pacer_waits_instead_of_rejecting() {
        let mut pacer = Pacer::new(Duration::from_secs(2), Duration::from_secs(60), 3);
        let start = Instant::now();
        assert_eq!(pacer.wait_time(start), Duration::ZERO);
        pacer.record(start);
        assert_eq!(pacer.wait_time(start + Duration::from_millis(500)), Duration::from_millis(1500));
        pacer.record(start + Duration::from_secs(3));
        pacer.record(start + Duration::from_secs(6));
        // Fenster voll: warten, bis der älteste Eintrag herausfällt.
        assert_eq!(pacer.wait_time(start + Duration::from_secs(9)), Duration::from_secs(51));
        assert_eq!(pacer.wait_time(start + Duration::from_secs(60)), Duration::ZERO);
    }

    #[test]
    fn retry_after_seconds_and_http_dates() {
        let now = DateTime::parse_from_rfc2822("Wed, 23 Sep 2026 10:00:00 GMT").unwrap().with_timezone(&Utc);
        assert_eq!(parse_retry_after("42", now), Some(Duration::from_secs(42)));
        assert_eq!(parse_retry_after(" 7 ", now), Some(Duration::from_secs(7)));
        assert_eq!(parse_retry_after("Wed, 23 Sep 2026 10:01:30 GMT", now), Some(Duration::from_secs(90)));
        assert_eq!(parse_retry_after("Wed, 23 Sep 2026 09:00:00 GMT", now), Some(Duration::ZERO), "Vergangenheit");
        assert_eq!(parse_retry_after("bald", now), None);
        assert_eq!(parse_retry_after("-5", now), None);
    }

    #[test]
    fn retry_delay_is_bounded() {
        assert_eq!(retry_delay(Some(Duration::from_secs(42)), 0), Duration::from_secs(42));
        assert_eq!(retry_delay(Some(Duration::ZERO), 0), RETRY_MIN);
        assert_eq!(retry_delay(Some(Duration::from_secs(86_400)), 0), RETRY_MAX);
        assert_eq!(retry_delay(None, 0), Duration::from_secs(30));
        assert_eq!(retry_delay(None, 1), Duration::from_secs(60));
        assert_eq!(retry_delay(None, 2), Duration::from_secs(120));
        assert_eq!(retry_delay(None, 3), Duration::from_secs(240));
        assert_eq!(retry_delay(None, 4), BACKOFF_MAX);
        assert_eq!(retry_delay(None, 200), BACKOFF_MAX, "kein Überlauf");
    }

    #[test]
    fn changes_parse_from_the_webview_shape() {
        let json = r#"{"skin":{"kind":"library","id":"0123456789ab","variant":"slim"},"cape":{"id":null}}"#;
        let changes: SkinChanges = serde_json::from_str(json).unwrap();
        assert_eq!(
            changes.skin,
            Some(SkinChange::Library { id: "0123456789ab".into(), variant: SkinVariant::Slim })
        );
        assert_eq!(changes.cape, Some(CapeChange { id: None }));
        let changes: SkinChanges = serde_json::from_str(r#"{"skin":{"kind":"default"}}"#).unwrap();
        assert_eq!(changes, SkinChanges { skin: Some(SkinChange::Default), cape: None });
        assert!(serde_json::from_str::<SkinChanges>(r#"{"skin":{"kind":"url","url":"x"}}"#).is_err());
    }

    // --- Abarbeiter mit Attrappe -----------------------------------------------------

    fn profile() -> Profile {
        Profile { name: "Test".into(), uuid: "acc".into(), variant: SkinVariant::Classic, skin: None, capes: vec![] }
    }

    /// Spielt vorgegebene Antworten ab und simuliert die Zeit.
    struct Fake<'a> {
        sync: &'a SkinSync,
        start: Instant,
        clock: RefCell<Duration>,
        answers: RefCell<VecDeque<std::result::Result<(), ApiError>>>,
        sent: RefCell<Vec<(Duration, Op)>>,
        sleeps: RefCell<Vec<Duration>>,
        /// Wird beim ersten Schlafen einmal ausgeführt (neuer Wunsch während des Wartens).
        on_sleep: RefCell<Option<SleepHook<'a>>>,
    }

    type SleepHook<'a> = Box<dyn FnOnce(&SkinSync) + 'a>;

    impl<'a> Fake<'a> {
        fn new(sync: &'a SkinSync, answers: Vec<std::result::Result<(), ApiError>>) -> Self {
            Self {
                sync,
                start: Instant::now(),
                clock: RefCell::new(Duration::ZERO),
                answers: RefCell::new(answers.into()),
                sent: RefCell::default(),
                sleeps: RefCell::default(),
                on_sleep: RefCell::default(),
            }
        }
    }

    impl Backend for Fake<'_> {
        async fn run(&self, op: &Op, _account: &str) -> std::result::Result<(), ApiError> {
            self.sent.borrow_mut().push((*self.clock.borrow(), op.clone()));
            self.answers.borrow_mut().pop_front().unwrap_or(Ok(()))
        }

        async fn profile(&self, _account: &str) -> std::result::Result<Profile, ApiError> {
            Ok(profile())
        }

        async fn sleep(&self, wait: Duration) {
            // Den Status während des Wartens prüfen können.
            self.sleeps.borrow_mut().push(wait);
            *self.clock.borrow_mut() += wait;
            let hook = self.on_sleep.borrow_mut().take();
            if let Some(hook) = hook {
                hook(self.sync);
            }
        }

        fn now(&self) -> Instant {
            self.start + *self.clock.borrow()
        }
    }

    #[tokio::test]
    async fn worker_retries_after_429_and_reports_done() {
        let sync = SkinSync::default();
        let (start, status) = sync.enqueue("acc", Some(upload("a")), Some(CapeOp::Hide));
        assert!(start);
        assert_eq!(status.state, SyncState::Applying);

        let fake = Fake::new(&sync, vec![Err(ApiError::RateLimited(Some(Duration::from_secs(42)))), Ok(()), Ok(())]);
        let seen = std::rc::Rc::new(RefCell::new(None));
        let seen_in_hook = std::rc::Rc::clone(&seen);
        *fake.on_sleep.borrow_mut() = Some(Box::new(move |sync: &SkinSync| {
            *seen_in_hook.borrow_mut() = Some(sync.status());
        }));
        drive(&sync, &fake).await;

        let waiting = seen.borrow().clone().expect("hat gewartet");
        assert_eq!(waiting.state, SyncState::Waiting);
        assert_eq!(waiting.reason, Some(WaitReason::RateLimited));
        assert!(waiting.pending_skin && waiting.pending_cape);
        let left = waiting.retry_at.unwrap() - Utc::now().timestamp_millis();
        assert!((40_000..=42_000).contains(&left), "Countdown ≈ 42 s, war {left} ms");

        let sent = fake.sent.borrow();
        assert_eq!(sent.len(), 3, "429-Versuch, Wiederholung, Umhang");
        assert_eq!(sent[1].1, Op::Skin(upload("a")));
        assert!(sent[1].0 >= Duration::from_secs(42), "erst nach Retry-After erneut gesendet");
        assert!(sent[2].0 - sent[1].0 >= MIN_GAP, "eigener Takt zwischen zwei Änderungen");

        let done = sync.status();
        assert_eq!(done.state, SyncState::Done);
        assert!(done.profile.is_some());
        assert!(!done.pending_skin && !done.pending_cape);
    }

    #[tokio::test]
    async fn worker_coalesces_wishes_that_arrive_while_waiting() {
        let sync = SkinSync::default();
        sync.enqueue("acc", Some(upload("a")), None);
        let fake = Fake::new(&sync, vec![Err(ApiError::RateLimited(None))]);
        *fake.on_sleep.borrow_mut() = Some(Box::new(|sync: &SkinSync| {
            // Während Mojang bremst, klickt der Nutzer weiter: b, c, d – nur d zählt.
            for id in ["b", "c", "d"] {
                let (start, status) = sync.enqueue("acc", Some(upload(id)), None);
                assert!(!start, "kein zweiter Abarbeiter");
                assert_eq!(status.state, SyncState::Waiting, "Warten wird weiter angezeigt");
            }
        }));
        drive(&sync, &fake).await;

        let sent: Vec<Op> = fake.sent.borrow().iter().map(|(_, op)| op.clone()).collect();
        assert_eq!(sent, vec![Op::Skin(upload("a")), Op::Skin(upload("d"))]);
        assert_eq!(fake.sleeps.borrow()[0], Duration::from_secs(30), "Backoff ohne Retry-After");
        assert_eq!(sync.status().state, SyncState::Done);
    }

    #[tokio::test]
    async fn worker_gives_up_on_fatal_errors_and_keeps_going_with_the_rest() {
        let sync = SkinSync::default();
        sync.enqueue("acc", Some(upload("a")), Some(CapeOp::Hide));
        let fake = Fake::new(&sync, vec![Err(ApiError::Fatal(Error::validation("Datei abgelehnt"))), Ok(())]);
        drive(&sync, &fake).await;
        assert_eq!(fake.sent.borrow().len(), 2, "Umhang wird trotzdem gesetzt");
        let status = sync.status();
        assert_eq!(status.state, SyncState::Failed);
        assert_eq!(status.message.as_deref(), Some("Datei abgelehnt"));
        assert!(status.profile.is_some(), "neuer Stand kommt trotzdem mit");
    }

    #[tokio::test]
    async fn worker_retries_network_errors_a_few_times() {
        let sync = SkinSync::default();
        sync.enqueue("acc", None, Some(CapeOp::Hide));
        let fake = Fake::new(&sync, (0..10).map(|_| Err(ApiError::Transient)).collect());
        drive(&sync, &fake).await;
        assert_eq!(fake.sent.borrow().len() as u32, MAX_TRANSIENT);
        assert_eq!(sync.status().state, SyncState::Failed);
        assert!(!sync.status().pending_cape);
    }

    #[tokio::test]
    async fn many_changes_wait_for_the_safety_cap_instead_of_failing() {
        let sync = SkinSync::default();
        let fake = Fake::new(&sync, vec![]);
        for i in 0..(MAX_IN_WINDOW + 2) {
            sync.enqueue("acc", Some(upload(&format!("{i}"))), None);
            drive(&sync, &fake).await;
            assert_eq!(sync.status().state, SyncState::Done, "Durchlauf {i}");
        }
        let sent = fake.sent.borrow();
        assert_eq!(sent.len(), MAX_IN_WINDOW + 2, "nichts abgelehnt");
        assert!(sent[MAX_IN_WINDOW].0 >= WINDOW, "die 21. Änderung wartet auf das Fenster");
    }

    #[tokio::test]
    async fn submit_needs_an_account_and_checks_input_first() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Arc::new(Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap());
        let changes = SkinChanges { skin: Some(SkinChange::Default), cape: None };
        assert!(matches!(launcher.submit_skin_changes("acc", changes).await, Err(Error::Auth(_))));
        assert_eq!(launcher.skin_sync_status().state, SyncState::Idle);
    }
}
