//! TRS-Synchronisation: eigene Skins („Meine Skins“), eigene Mod-Presets und
//! Theme/Akzentfarbe/Sprache über alle PCs eines Minecraft-Kontos gleich
//! halten (Vertrag: `GET /v1/me/sync` und Unterrouten).
//!
//! - Läuft nur mit TRS-Einwilligung **und** eingeschalteter Einstellung
//!   „Mit TRS-Konto synchronisieren“ (`trs_sync`), immer für den aktiven Account.
//! - Takt: kurz nach dem Start, ~3 s nach lokalen Änderungen (entprellt), alle
//!   5 Minuten und sofort nach einem Account-Wechsel. Fehler und offline sind
//!   still und werden mit wachsender Pause erneut versucht. Die Oberfläche
//!   wartet nie auf den Abgleich – sie bekommt nur ein Event, wenn sich lokal
//!   etwas geändert hat.
//! - Zusammenführen („letzter Schreiber gewinnt“):
//!   - **Skins** je ID: nur hier → hochladen (bzw. löschen, wenn das Konto sie
//!     inzwischen gelöscht hat), nur auf dem Konto → herunterladen (bzw. dort
//!     löschen, wenn sie hier gelöscht wurden – lokale Grabsteine), beide da
//!     → die jüngere Änderung von Name/Modell gewinnt.
//!   - **Presets** je Preset mit Grabsteinen (siehe [`crate::presets::merge`]).
//!   - **Einstellungen** je Schlüssel: hier nach der letzten Änderung auf dem
//!     Konto geändert → hier gewinnt, sonst das Konto.
//!   - Meldet der Server `409 stale`, wird sein Stand übernommen (zusammen-
//!     geführt) und einmal erneut gesendet.
//!   - Der allererste Abgleich eines PCs verliert nichts: Skins und Presets
//!     werden vereinigt; Einstellungen, die hier nie geändert wurden, kommen
//!     vom Konto.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::PathBuf;
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chrono::{DateTime, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::{Mutex, Notify};

use super::{Outcome, Req};
use crate::presets::{self, SyncData as PresetData};
use crate::settings::{Accent, Language, Theme, UiSettings};
use crate::skins::{self, LibrarySnapshot, SkinTombstone, SkinVariant};
use crate::{Error, Launcher, Result, fsutil};

/// Regelmäßiger Abgleich.
pub const SYNC_INTERVAL: Duration = Duration::from_secs(5 * 60);
/// So lange wird nach einer lokalen Änderung auf weitere gewartet.
#[cfg(not(test))]
pub const DEBOUNCE: Duration = Duration::from_secs(3);
#[cfg(test)]
pub const DEBOUNCE: Duration = Duration::from_millis(50);
/// Erste Pause nach einem Fehler; verdoppelt sich bis [`MAX_BACKOFF`].
const FIRST_BACKOFF: Duration = Duration::from_secs(30);
const MAX_BACKOFF: Duration = Duration::from_secs(15 * 60);
/// Größe von `data` (Presets/Einstellungen) laut Vertrag.
pub const MAX_DATA_BYTES: usize = 64 * 1024;
/// Mehr Einträge nimmt der Launcher vom Server nicht an.
const MAX_REMOTE_SKINS: usize = 200;
const MAX_REMOTE_GRAVES: usize = 2000;
/// Höchstens so viele Uploads bzw. Downloads je Abgleich (API: 30 Uploads und
/// 120 Anfragen je Minute) – der Rest folgt im nächsten Abgleich kurz danach.
const MAX_UPLOADS_PER_ROUND: usize = 20;
const MAX_DOWNLOADS_PER_ROUND: usize = 40;
/// Pause, wenn noch Skins ausstehen (ein Rate-Limit-Fenster).
const CONTINUE_AFTER: Duration = Duration::from_secs(65);
/// Abstand zwischen zwei Uploads (30/min).
#[cfg(not(test))]
const UPLOAD_GAP: Duration = Duration::from_millis(2100);
#[cfg(test)]
const UPLOAD_GAP: Duration = Duration::ZERO;

// --- Laufzeit-Zustand ----------------------------------------------------------------

/// Was ein Abgleich lokal geändert hat (die Oberfläche lädt dann neu).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncChanges {
    pub skins: bool,
    pub presets: bool,
    pub settings: bool,
}

impl SyncChanges {
    pub fn any(self) -> bool {
        self.skins || self.presets || self.settings
    }
}

/// Stand für die Oberfläche (kleiner Hinweis auf der Skins-Seite).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncStatus {
    /// Einwilligung + Einstellung + angemeldeter Account.
    pub active: bool,
    pub syncing: bool,
    /// Letzter erfolgreicher Abgleich des aktiven Accounts.
    pub last_sync_at: Option<DateTime<Utc>>,
    /// `offline` oder `error` (still – nur für den Hinweis).
    pub problem: Option<String>,
}

/// Event nach jedem Abgleich (`trs-sync` im Frontend).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncEvent {
    pub account: String,
    pub changes: SyncChanges,
    pub status: SyncStatus,
}

pub type SyncSink = Arc<dyn Fn(SyncEvent) + Send + Sync>;

#[derive(Default)]
struct Runtime {
    /// Wann der nächste Abgleich fällig ist (`None` = gerade keiner geplant).
    due: Option<Instant>,
    running: bool,
    /// Während eines Abgleichs kam eine Änderung – danach gleich noch einmal.
    dirty: bool,
    failures: u32,
    problem: Option<(String, &'static str)>,
}

#[derive(Default)]
pub(crate) struct SyncState {
    notify: Notify,
    runtime: StdMutex<Runtime>,
    sink: StdMutex<Option<SyncSink>>,
}

impl SyncState {
    fn runtime(&self) -> std::sync::MutexGuard<'_, Runtime> {
        self.runtime.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Lokale Änderung: in [`DEBOUNCE`] abgleichen (weitere Änderungen schieben das hinaus).
    pub fn touch(&self) {
        {
            let mut rt = self.runtime();
            let now = Instant::now();
            rt.due = Some(match rt.due {
                Some(due) if due <= now => due,
                _ => now + DEBOUNCE,
            });
            if rt.running {
                rt.dirty = true;
            }
        }
        self.notify.notify_one();
    }

    /// Sofort abgleichen (Start, Account-Wechsel, Einwilligung, Schalter an).
    pub fn kick(&self) {
        {
            let mut rt = self.runtime();
            rt.due = Some(Instant::now());
            if rt.running {
                rt.dirty = true;
            }
        }
        self.notify.notify_one();
    }

    /// Wie lange bis zum nächsten Abgleich (`None` = nichts geplant).
    fn wait(&self) -> Option<Duration> {
        self.runtime().due.map(|d| d.saturating_duration_since(Instant::now()))
    }

    fn begin(&self) {
        let mut rt = self.runtime();
        rt.running = true;
        rt.dirty = false;
        rt.due = None;
    }

    /// Abgleich fertig: nächsten planen.
    fn finish(&self, next: Duration) {
        let mut rt = self.runtime();
        rt.running = false;
        let next = if std::mem::take(&mut rt.dirty) { DEBOUNCE } else { next };
        let at = Instant::now() + next;
        rt.due = Some(rt.due.map_or(at, |d| d.min(at)));
    }

    fn succeeded(&self) {
        let mut rt = self.runtime();
        rt.failures = 0;
        rt.problem = None;
    }

    /// Fehler merken; liefert die Pause bis zum nächsten Versuch.
    fn failed(&self, account: &str, problem: &'static str, at_least: Duration) -> Duration {
        let mut rt = self.runtime();
        rt.failures = rt.failures.saturating_add(1);
        rt.problem = Some((account.to_owned(), problem));
        backoff(rt.failures).max(at_least)
    }

    fn is_running(&self) -> bool {
        self.runtime().running
    }

    fn problem_for(&self, account: &str) -> Option<String> {
        self.runtime().problem.as_ref().filter(|(a, _)| a == account).map(|(_, p)| (*p).to_owned())
    }

    pub fn set_sink(&self, sink: SyncSink) {
        *self.sink.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(sink);
    }

    fn emit(&self, event: SyncEvent) {
        let sink = self.sink.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        if let Some(sink) = sink {
            sink(event);
        }
    }
}

/// 30 s, 60 s, 2 min, 4 min … höchstens 15 min.
fn backoff(failures: u32) -> Duration {
    let factor = 1u32 << failures.saturating_sub(1).min(5);
    (FIRST_BACKOFF * factor).min(MAX_BACKOFF)
}

// --- Gespeicherter Zustand (`<daten>/trs-sync.json`) -----------------------------------

/// Wann Theme/Akzentfarbe/Sprache hier zuletzt von Hand geändert wurden.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UiTimes {
    #[serde(default)]
    pub theme: Option<DateTime<Utc>>,
    #[serde(default)]
    pub accent: Option<DateTime<Utc>>,
    #[serde(default)]
    pub language: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountState {
    /// Skins, die beim letzten Abgleich hier und auf dem Konto lagen.
    #[serde(default)]
    known_skins: BTreeSet<String>,
    #[serde(default)]
    last_sync_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SyncFile {
    #[serde(default)]
    ui_changed: UiTimes,
    #[serde(default)]
    accounts: BTreeMap<String, AccountState>,
}

pub(crate) struct SyncStore {
    path: PathBuf,
    inner: Mutex<Option<SyncFile>>,
}

impl SyncStore {
    pub fn new(path: PathBuf) -> Self {
        Self { path, inner: Mutex::new(None) }
    }

    /// Liest (einmal), ändert und speichert bei `true` aus `f`.
    async fn with<R>(&self, f: impl FnOnce(&mut SyncFile) -> (R, bool)) -> R {
        let mut guard = self.inner.lock().await;
        if guard.is_none() {
            let file = match fsutil::read_json::<SyncFile>(&self.path).await {
                Ok(file) => file.unwrap_or_default(),
                Err(e) => {
                    tracing::warn!("trs-sync.json ist beschädigt – der Abgleich beginnt neu: {e}");
                    SyncFile::default()
                }
            };
            *guard = Some(file);
        }
        let file = guard.as_mut().expect("gerade geladen");
        let (out, dirty) = f(file);
        if dirty {
            match serde_json::to_vec_pretty(file) {
                Ok(json) => {
                    if let Err(e) = fsutil::write_atomic(&self.path, &json).await {
                        tracing::warn!("trs-sync.json konnte nicht gespeichert werden: {e}");
                    }
                }
                Err(e) => tracing::warn!("trs-sync.json konnte nicht serialisiert werden: {e}"),
            }
        }
        out
    }

    pub async fn ui_times(&self) -> UiTimes {
        self.with(|f| (f.ui_changed, false)).await
    }

    /// Theme/Akzent/Sprache wurden hier geändert.
    pub async fn ui_changed(&self, theme: bool, accent: bool, language: bool, at: DateTime<Utc>) {
        self.with(|f| {
            if theme {
                f.ui_changed.theme = Some(at);
            }
            if accent {
                f.ui_changed.accent = Some(at);
            }
            if language {
                f.ui_changed.language = Some(at);
            }
            ((), theme || accent || language)
        })
        .await;
    }

    async fn known_skins(&self, account: &str) -> BTreeSet<String> {
        self.with(|f| (f.accounts.get(account).map(|a| a.known_skins.clone()).unwrap_or_default(), false)).await
    }

    async fn set_known_skins(&self, account: &str, known: BTreeSet<String>) {
        self.with(|f| {
            let entry = f.accounts.entry(account.to_owned()).or_default();
            let dirty = entry.known_skins != known;
            entry.known_skins = known;
            ((), dirty)
        })
        .await;
    }

    pub async fn last_sync_at(&self, account: &str) -> Option<DateTime<Utc>> {
        self.with(|f| (f.accounts.get(account).and_then(|a| a.last_sync_at), false)).await
    }

    async fn set_last_sync_at(&self, account: &str, at: DateTime<Utc>) {
        self.with(|f| {
            f.accounts.entry(account.to_owned()).or_default().last_sync_at = Some(at);
            ((), true)
        })
        .await;
    }

    /// Account entfernt bzw. seine TRS-Daten gelöscht: beim nächsten Mal
    /// beginnt der Abgleich wie beim ersten Mal (nichts wird hier gelöscht).
    pub async fn forget(&self, account: &str) {
        self.with(|f| ((), f.accounts.remove(account).is_some())).await;
    }
}

// --- Daten vom Server ------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiSync {
    #[serde(default)]
    skins: Vec<ApiSyncSkin>,
    #[serde(default)]
    deleted_skins: Vec<ApiGrave>,
    #[serde(default)]
    presets: Option<ApiBlob>,
    #[serde(default)]
    settings: Option<ApiBlob>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiSyncSkin {
    id: String,
    name: String,
    variant: String,
    updated_at: String,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiGrave {
    id: String,
    deleted_at: String,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiBlob {
    data: Value,
    updated_at: String,
}

#[derive(Debug, Deserialize)]
struct ApiSkinResponse {
    skin: ApiSyncSkin,
}

fn parse_time(text: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(text).ok().map(|d| d.with_timezone(&Utc))
}

fn iso(at: DateTime<Utc>) -> String {
    at.to_rfc3339_opts(SecondsFormat::Millis, true)
}

/// Zeitstempel für ein `PUT`: die echte aktuelle Zeit (auf Millisekunden
/// gekürzt). Geht die Uhr dieses PCs hinter dem Stand des Servers her, wird
/// genau dessen Zeit gesendet – bei gleichem `updatedAt` überschreibt der
/// Server (Vertrag), weiter „vorgerückt“ wird nie.
pub(crate) fn stamp_after(remote: Option<DateTime<Utc>>, now: DateTime<Utc>) -> DateTime<Utc> {
    let now = DateTime::from_timestamp_millis(now.timestamp_millis()).unwrap_or(now);
    match remote {
        Some(r) if r > now => DateTime::from_timestamp_millis(r.timestamp_millis()).unwrap_or(r),
        _ => now,
    }
}

fn parse_variant(value: &str) -> Option<SkinVariant> {
    match value {
        "classic" => Some(SkinVariant::Classic),
        "slim" => Some(SkinVariant::Slim),
        _ => None,
    }
}

/// Ein Skin auf dem Konto (geprüft).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct RemoteSkin {
    pub id: String,
    pub name: String,
    pub variant: SkinVariant,
    pub updated_at: DateTime<Utc>,
}

impl RemoteSkin {
    fn from_api(skin: &ApiSyncSkin) -> Option<Self> {
        if !skins::is_library_id(&skin.id) {
            return None;
        }
        Some(Self {
            id: skin.id.clone(),
            name: skins::clean_name(&skin.name).ok()?,
            variant: parse_variant(&skin.variant)?,
            updated_at: parse_time(&skin.updated_at)?,
        })
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct RemoteSkins {
    pub skins: Vec<RemoteSkin>,
    pub deleted: Vec<SkinTombstone>,
}

/// Ein Stand von Presets oder Einstellungen auf dem Konto.
#[derive(Debug, Clone)]
struct Blob {
    data: Value,
    updated_at: Option<DateTime<Utc>>,
}

impl Blob {
    fn from_api(blob: ApiBlob) -> Self {
        Self { updated_at: parse_time(&blob.updated_at), data: blob.data }
    }

    /// `error.current` aus einer `409 stale`-Antwort.
    fn from_current(value: Value) -> Option<Self> {
        serde_json::from_value::<ApiBlob>(value).ok().map(Self::from_api)
    }
}

struct Remote {
    skins: RemoteSkins,
    presets: Option<Blob>,
    settings: Option<Blob>,
}

impl Remote {
    fn from_api(api: ApiSync) -> Self {
        let mut skins: Vec<RemoteSkin> = Vec::new();
        for skin in api.skins.iter().take(MAX_REMOTE_SKINS).filter_map(RemoteSkin::from_api) {
            if !skins.iter().any(|s| s.id == skin.id) {
                skins.push(skin);
            }
        }
        let deleted = api
            .deleted_skins
            .iter()
            .take(MAX_REMOTE_GRAVES)
            .filter(|g| skins::is_library_id(&g.id))
            .filter_map(|g| Some(SkinTombstone { id: g.id.clone(), deleted_at: parse_time(&g.deleted_at)? }))
            .collect();
        Self {
            skins: RemoteSkins { skins, deleted },
            presets: api.presets.map(Blob::from_api),
            settings: api.settings.map(Blob::from_api),
        }
    }
}

// --- Skins: Plan (rein, getestet) ------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum SkinAction {
    /// Hier gelöscht → auf dem Konto löschen.
    DeleteRemote(String),
    /// Auf dem Konto gelöscht → hier löschen.
    DeleteLocal { id: String, at: DateTime<Utc> },
    /// Auf dem Konto neuer benannt/umgestellt → hier übernehmen.
    UpdateLocal(RemoteSkin),
    /// Hier neuer benannt → aufs Konto.
    Patch { id: String, name: String, variant: SkinVariant },
    /// Nur auf dem Konto → herunterladen.
    Download(RemoteSkin),
    /// Nur hier → hochladen.
    Upload(String),
}

impl SkinAction {
    fn phase(&self) -> u8 {
        match self {
            Self::DeleteRemote(_) => 0,
            Self::DeleteLocal { .. } => 1,
            Self::UpdateLocal(_) => 2,
            Self::Patch { .. } => 3,
            Self::Download(_) => 4,
            Self::Upload(_) => 5,
        }
    }
}

/// Was zu tun ist, damit Bibliothek und Konto gleich sind. `known` = Skins,
/// die beim letzten Abgleich an beiden Stellen lagen (fehlt so einer auf dem
/// Konto ohne Grabstein, wurde er dort vor langer Zeit gelöscht).
pub(crate) fn plan_skins(
    local: &LibrarySnapshot,
    remote: &RemoteSkins,
    known: &BTreeSet<String>,
    now: DateTime<Utc>,
) -> Vec<SkinAction> {
    let local_by_id: HashMap<&str, &skins::LibrarySkin> = local.skins.iter().map(|s| (s.id.as_str(), s)).collect();
    let local_graves: HashMap<&str, DateTime<Utc>> = local.deleted.iter().map(|t| (t.id.as_str(), t.deleted_at)).collect();
    let remote_graves: HashMap<&str, DateTime<Utc>> = remote.deleted.iter().map(|t| (t.id.as_str(), t.deleted_at)).collect();
    // Ein ganz leeres Konto (neu, zurückgesetzt) löscht nie etwas – dann wird alles hochgeladen.
    let server_empty = remote.skins.is_empty() && remote.deleted.is_empty();

    let mut actions = Vec::new();
    for r in &remote.skins {
        if let Some(l) = local_by_id.get(r.id.as_str()) {
            if l.name != r.name || l.variant != r.variant {
                if l.changed_at() > r.updated_at {
                    actions.push(SkinAction::Patch { id: r.id.clone(), name: l.name.clone(), variant: l.variant });
                } else {
                    actions.push(SkinAction::UpdateLocal(r.clone()));
                }
            }
        } else if local_graves.get(r.id.as_str()).is_some_and(|deleted| *deleted >= r.updated_at) {
            actions.push(SkinAction::DeleteRemote(r.id.clone()));
        } else {
            // Neu auf dem Konto – oder dort nach der Löschung hier erneut geändert.
            actions.push(SkinAction::Download(r.clone()));
        }
    }
    for l in &local.skins {
        if remote.skins.iter().any(|r| r.id == l.id) {
            continue;
        }
        match remote_graves.get(l.id.as_str()) {
            Some(deleted) if *deleted >= l.changed_at() => {
                actions.push(SkinAction::DeleteLocal { id: l.id.clone(), at: *deleted });
            }
            Some(_) => actions.push(SkinAction::Upload(l.id.clone())),
            None if known.contains(&l.id) && !server_empty => {
                actions.push(SkinAction::DeleteLocal { id: l.id.clone(), at: now });
            }
            None => actions.push(SkinAction::Upload(l.id.clone())),
        }
    }
    actions.sort_by_key(SkinAction::phase);
    actions
}

// --- Einstellungen: Zusammenführen (rein, getestet) ------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct UiValues {
    pub theme: Theme,
    pub accent: Accent,
    pub language: Language,
}

impl UiValues {
    pub fn of(ui: &UiSettings) -> Self {
        Self { theme: ui.theme, accent: ui.accent, language: ui.language }
    }
}

/// Ein Schlüssel auf dem Konto.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Key<T> {
    Missing,
    /// Wert, den diese Version nicht kennt (z. B. aus einer neueren) – bleibt erhalten.
    Unknown(String),
    Known(T),
}

fn key<T: serde::de::DeserializeOwned>(data: &Value, name: &str) -> Key<T> {
    match data.get(name).and_then(Value::as_str) {
        None => Key::Missing,
        Some(text) => match serde_json::from_value::<T>(Value::String(text.to_owned())) {
            Ok(value) => Key::Known(value),
            Err(_) if text.len() <= 32 && !text.chars().any(char::is_control) => Key::Unknown(text.to_owned()),
            Err(_) => Key::Missing,
        },
    }
}

/// Ergebnis für Einstellungen: was hier gilt, was aufs Konto soll und ob.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct MergedUi {
    pub values: UiValues,
    pub data: Value,
    pub push: bool,
}

fn merge_key<T: Copy + PartialEq + Serialize>(
    local: T,
    changed: Option<DateTime<Utc>>,
    remote: Key<T>,
    remote_at: Option<DateTime<Utc>>,
) -> (T, Value, bool) {
    let local_newer = changed.is_some_and(|c| remote_at.is_none_or(|r| c > r));
    let local_json = serde_json::to_value(local).unwrap_or(Value::Null);
    match remote {
        Key::Missing => (local, local_json, true),
        Key::Unknown(_) if local_newer => (local, local_json, true),
        Key::Unknown(text) => (local, Value::String(text), false),
        Key::Known(value) if local_newer => (local, local_json, value != local),
        Key::Known(value) => (value, serde_json::to_value(value).unwrap_or(Value::Null), false),
    }
}

/// Je Schlüssel: hier nach dem Stand des Kontos geändert → hier gewinnt,
/// sonst das Konto. Ohne Stand auf dem Konto wird der lokale hochgeladen.
pub(crate) fn merge_ui(local: UiValues, times: UiTimes, remote: Option<(&Value, Option<DateTime<Utc>>)>) -> MergedUi {
    let Some((data, at)) = remote else {
        return MergedUi {
            values: local,
            data: json!({ "theme": local.theme, "accent": local.accent, "language": local.language }),
            push: true,
        };
    };
    let (theme, theme_json, p1) = merge_key(local.theme, times.theme, key(data, "theme"), at);
    let (accent, accent_json, p2) = merge_key(local.accent, times.accent, key(data, "accent"), at);
    let (language, language_json, p3) = merge_key(local.language, times.language, key(data, "language"), at);
    MergedUi {
        values: UiValues { theme, accent, language },
        data: json!({ "theme": theme_json, "accent": accent_json, "language": language_json }),
        push: p1 || p2 || p3,
    }
}

/// Presets passend für den Server (höchstens 64 KB); zu groß → ohne Symbole, sonst `None`.
pub(crate) fn fit_presets(data: &PresetData) -> Option<PresetData> {
    let fits = |d: &PresetData| serde_json::to_vec(&d.to_value()).map(|v| v.len() <= MAX_DATA_BYTES - 64).unwrap_or(false);
    if fits(data) {
        return Some(data.clone());
    }
    let slim = data.without_icons();
    fits(&slim).then_some(slim)
}

// --- Ablauf ----------------------------------------------------------------------------

enum Put {
    Stored,
    Stale(Blob),
    /// Abgelehnt (z. B. zu groß) – nächstes Mal wieder versuchen.
    Rejected,
}

/// Fehler, bei denen der ganze Abgleich abbricht (sonst nur der eine Eintrag).
fn aborts(e: &Error) -> bool {
    matches!(e.kind(), "trs_offline" | "trs_rate_limited" | "trs_auth" | "trs_disabled" | "trs_banned" | "trs_no_account")
}

fn skin_path(id: &str) -> String {
    format!("/v1/me/sync/skins/{id}")
}

impl Launcher {
    /// Events nach jedem Abgleich (Tauri: `trs-sync`).
    pub fn set_trs_sync_sink(&self, sink: SyncSink) {
        self.trs.sync.set_sink(sink);
    }

    /// Lokale Änderung an Skins/Presets/Theme/Sprache: entprellt abgleichen.
    pub fn trs_sync_touch(&self) {
        self.trs.sync.touch();
    }

    /// Sofort abgleichen (Account-Wechsel, Schalter an …).
    pub fn trs_sync_kick(&self) {
        self.trs.sync.kick();
    }

    /// Account, für den gerade abgeglichen würde – `None`, wenn aus.
    async fn trs_sync_target(&self) -> Option<String> {
        if !self.trs.enabled().await || !self.settings().await.trs_sync {
            return None;
        }
        self.accounts().active_id().await.ok().flatten()
    }

    pub async fn trs_sync_status(&self) -> SyncStatus {
        let Some(account) = self.trs_sync_target().await else { return SyncStatus::default() };
        SyncStatus {
            active: true,
            syncing: self.trs.sync.is_running(),
            last_sync_at: self.trs.sync_store.last_sync_at(&account).await,
            problem: self.trs.sync.problem_for(&account),
        }
    }

    /// Theme/Akzent/Sprache wurden in den Einstellungen geändert.
    pub(crate) async fn trs_ui_changed(&self, old: &UiSettings, new: &UiSettings) {
        let (theme, accent, language) = (old.theme != new.theme, old.accent != new.accent, old.language != new.language);
        if theme || accent || language {
            self.trs.sync_store.ui_changed(theme, accent, language, Utc::now()).await;
            self.trs.sync.touch();
        }
    }

    /// Hintergrund-Schleife; ohne Einwilligung/Schalter passiert nichts.
    pub async fn run_trs_sync(self: Arc<Self>) {
        // Nach dem Start kurz Luft lassen (Präsenz, Oberfläche).
        tokio::time::sleep(Duration::from_secs(6)).await;
        self.trs.sync.kick();
        loop {
            match self.trs.sync.wait() {
                Some(wait) if wait.is_zero() => {}
                Some(wait) => {
                    tokio::select! {
                        () = tokio::time::sleep(wait) => {}
                        () = self.trs.sync.notify.notified() => continue,
                    }
                    // Zwischendurch verschoben? Dann neu rechnen.
                    if self.trs.sync.wait().is_some_and(|w| !w.is_zero()) {
                        continue;
                    }
                }
                None => {
                    self.trs.sync.notify.notified().await;
                    continue;
                }
            }
            let next = self.trs_sync_round().await;
            self.trs.sync.finish(next);
        }
    }

    /// Ein Abgleich für den aktiven Account. Liefert die Pause bis zum nächsten.
    pub(crate) async fn trs_sync_round(&self) -> Duration {
        let Some(account) = self.trs_sync_target().await else {
            self.trs.sync.begin();
            return SYNC_INTERVAL;
        };
        self.trs.sync.begin();
        // „Synchronisiere …“ für die Oberfläche.
        let status = self.trs_sync_status().await;
        self.trs.sync.emit(SyncEvent { account: account.clone(), changes: SyncChanges::default(), status });
        let mut changes = SyncChanges::default();
        let result = self.trs_sync_with(&account, &mut changes).await;
        let next = match &result {
            Ok(pending) => {
                self.trs.sync_store.set_last_sync_at(&account, Utc::now()).await;
                self.trs.sync.succeeded();
                if *pending { CONTINUE_AFTER } else { SYNC_INTERVAL }
            }
            Err(e) => {
                tracing::debug!("TRS-Abgleich nicht fertig: {e}");
                match e.kind() {
                    "trs_disabled" | "trs_no_account" => SYNC_INTERVAL,
                    "trs_offline" => self.trs.sync.failed(&account, "offline", Duration::ZERO),
                    "trs_rate_limited" => self.trs.sync.failed(&account, "offline", Duration::from_secs(120)),
                    _ => self.trs.sync.failed(&account, "error", Duration::ZERO),
                }
            }
        };
        let mut status = self.trs_sync_status().await;
        status.syncing = false;
        self.trs.sync.emit(SyncEvent { account, changes, status });
        next
    }

    /// Gleicht Skins, Presets und Einstellungen für `account` ab. Was lokal
    /// geändert wurde, steht danach in `changes` (auch bei einem Abbruch).
    /// `Ok(true)` = es stehen noch Skins aus (Rate-Limit), bald weitermachen.
    pub(crate) async fn trs_sync_with(&self, account: &str, changes: &mut SyncChanges) -> Result<bool> {
        let api: ApiSync = self.trs.call(self.accounts(), account, &Req::get("/v1/me/sync")).await?;
        let remote = Remote::from_api(api);
        let pending = self.sync_skins(account, &remote.skins, changes).await?;
        self.sync_presets(account, remote.presets, changes).await?;
        self.sync_settings(account, remote.settings, changes).await?;
        Ok(pending)
    }

    async fn sync_skins(&self, account: &str, remote: &RemoteSkins, changes: &mut SyncChanges) -> Result<bool> {
        let paths = self.paths().clone();
        let local = skins::library_snapshot(&paths).await;
        let known = self.trs.sync_store.known_skins(account).await;
        let mut on_server: BTreeSet<String> = remote.skins.iter().map(|s| s.id.clone()).collect();
        let mut limit_reached = false;
        let (mut uploads, mut downloads, mut pending) = (0usize, 0usize, false);
        let sessions = self.accounts();

        for action in plan_skins(&local, remote, &known, Utc::now()) {
            let result: Result<()> = async {
                match &action {
                    SkinAction::DeleteRemote(id) => {
                        self.trs.call_raw(sessions, account, &Req::delete(skin_path(id))).await?;
                        on_server.remove(id);
                    }
                    SkinAction::DeleteLocal { id, at } => {
                        skins::sync_remove(&paths, id, *at).await?;
                        changes.skins = true;
                    }
                    SkinAction::UpdateLocal(r) => {
                        skins::sync_update(&paths, &r.id, &r.name, r.variant, r.updated_at).await?;
                        changes.skins = true;
                    }
                    SkinAction::Patch { id, name, variant } => {
                        let body = json!({ "name": name, "variant": variant.as_str() });
                        let response: ApiSkinResponse = self.trs.call(sessions, account, &Req::patch(skin_path(id), body)).await?;
                        // Zeit des Servers übernehmen, damit beide Seiten gleich alt sind.
                        if let Some(at) = parse_time(&response.skin.updated_at) {
                            skins::sync_update(&paths, id, name, *variant, at).await?;
                        }
                    }
                    SkinAction::Download(r) => {
                        if downloads >= MAX_DOWNLOADS_PER_ROUND {
                            pending = true;
                            return Ok(());
                        }
                        downloads += 1;
                        let bytes = self.trs.call_raw(sessions, account, &Req::get(format!("{}.png", skin_path(&r.id)))).await?;
                        if skins::sync_insert(&paths, &r.id, &r.name, r.variant, &bytes, r.updated_at).await? {
                            changes.skins = true;
                        }
                    }
                    SkinAction::Upload(id) => {
                        if limit_reached {
                            return Ok(());
                        }
                        if uploads >= MAX_UPLOADS_PER_ROUND {
                            pending = true;
                            return Ok(());
                        }
                        let Some(skin) = local.skins.iter().find(|s| &s.id == id) else { return Ok(()) };
                        let bytes = self.library_skin_bytes(id).await?;
                        if uploads > 0 {
                            tokio::time::sleep(UPLOAD_GAP).await;
                        }
                        uploads += 1;
                        let body = json!({ "name": skin.name, "variant": skin.variant.as_str(), "png": STANDARD.encode(&bytes) });
                        match self.trs.call_raw(sessions, account, &Req::put(skin_path(id), body)).await {
                            Ok(_) => {
                                on_server.insert(id.clone());
                            }
                            Err(Error::TrsApi { code, .. }) if code == "skin_limit" => limit_reached = true,
                            Err(e) => return Err(e),
                        }
                    }
                }
                Ok(())
            }
            .await;
            match result {
                Ok(()) => {}
                Err(e) if aborts(&e) => return Err(e),
                Err(e) => tracing::warn!("TRS-Abgleich: Skin übersprungen ({action:?}): {e}"),
            }
        }

        let after = skins::library_snapshot(&paths).await;
        let known: BTreeSet<String> = after.skins.into_iter().map(|s| s.id).filter(|id| on_server.contains(id)).collect();
        self.trs.sync_store.set_known_skins(account, known).await;
        Ok(pending)
    }

    /// `PUT` mit „letzter Schreiber gewinnt“.
    async fn put_lww(&self, account: &str, path: &str, data: Value, remote_at: Option<DateTime<Utc>>) -> Result<Put> {
        let body = json!({ "data": data, "updatedAt": iso(stamp_after(remote_at, Utc::now())) });
        match self.trs.call_outcome(self.accounts(), account, &Req::put(path, body)).await {
            Ok(Outcome::Done(_)) => Ok(Put::Stored),
            Ok(Outcome::Stale(current)) => Ok(Blob::from_current(current).map_or(Put::Rejected, Put::Stale)),
            Err(e) if aborts(&e) => Err(e),
            Err(e) => {
                tracing::warn!("TRS-Abgleich: {path} abgelehnt: {e}");
                Ok(Put::Rejected)
            }
        }
    }

    async fn sync_presets(&self, account: &str, remote: Option<Blob>, changes: &mut SyncChanges) -> Result<()> {
        let paths = self.paths().clone();
        let mut remote = remote.map(|b| (PresetData::from_value(&b.data), b.updated_at));
        for attempt in 0..2 {
            let local = match &remote {
                Some((data, _)) => {
                    let (after, changed) = presets::sync_merge(&paths, data).await?;
                    changes.presets |= changed;
                    after
                }
                None => presets::sync_local(&paths).await,
            };
            let Some(outgoing) = fit_presets(&local) else {
                tracing::warn!("TRS-Abgleich: Presets sind zu groß für das Konto – nicht hochgeladen");
                return Ok(());
            };
            if remote.as_ref().is_some_and(|(data, _)| *data == outgoing) {
                return Ok(());
            }
            let remote_at = remote.as_ref().and_then(|(_, at)| *at);
            match self.put_lww(account, "/v1/me/sync/presets", outgoing.to_value(), remote_at).await? {
                Put::Stale(current) if attempt == 0 => {
                    remote = Some((PresetData::from_value(&current.data), current.updated_at));
                }
                _ => return Ok(()),
            }
        }
        Ok(())
    }

    async fn sync_settings(&self, account: &str, remote: Option<Blob>, changes: &mut SyncChanges) -> Result<()> {
        let mut remote = remote;
        for attempt in 0..2 {
            let times = self.trs.sync_store.ui_times().await;
            let local = UiValues::of(&self.settings().await.ui);
            let merged = merge_ui(local, times, remote.as_ref().map(|b| (&b.data, b.updated_at)));
            if merged.values != local {
                self.apply_synced_ui(merged.values.theme, merged.values.accent, merged.values.language).await?;
                changes.settings = true;
            }
            if !merged.push {
                return Ok(());
            }
            let remote_at = remote.as_ref().and_then(|b| b.updated_at);
            match self.put_lww(account, "/v1/me/sync/settings", merged.data, remote_at).await? {
                Put::Stale(current) if attempt == 0 => remote = Some(current),
                _ => return Ok(()),
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(minutes: i64) -> DateTime<Utc> {
        DateTime::parse_from_rfc3339("2026-09-25T10:00:00Z").unwrap().with_timezone(&Utc) + chrono::Duration::minutes(minutes)
    }

    fn local_skin(id: &str, name: &str, changed: i64) -> skins::LibrarySkin {
        skins::LibrarySkin {
            id: id.into(),
            name: name.into(),
            variant: SkinVariant::Classic,
            file: format!("skin-{id}.png"),
            added_at: at(0),
            updated_at: Some(at(changed)),
        }
    }

    fn remote_skin(id: &str, name: &str, changed: i64) -> RemoteSkin {
        RemoteSkin { id: id.into(), name: name.into(), variant: SkinVariant::Classic, updated_at: at(changed) }
    }

    fn grave(id: &str, minutes: i64) -> SkinTombstone {
        SkinTombstone { id: id.into(), deleted_at: at(minutes) }
    }

    const A: &str = "aaaaaaaaaaaa";
    const B: &str = "bbbbbbbbbbbb";
    const C: &str = "cccccccccccc";
    const D: &str = "dddddddddddd";

    #[test]
    fn first_sync_is_a_union() {
        let local = LibrarySnapshot { skins: vec![local_skin(A, "Hier", 1)], deleted: vec![] };
        let remote = RemoteSkins { skins: vec![remote_skin(B, "Dort", 1)], deleted: vec![] };
        let plan = plan_skins(&local, &remote, &BTreeSet::new(), at(10));
        assert_eq!(plan, [SkinAction::Download(remote_skin(B, "Dort", 1)), SkinAction::Upload(A.into())]);
    }

    #[test]
    fn tombstones_work_in_both_directions() {
        // Hier gelöscht (nach der letzten Änderung dort) → dort löschen; dort gelöscht → hier löschen.
        let local = LibrarySnapshot { skins: vec![local_skin(B, "B", 1)], deleted: vec![grave(A, 5)] };
        let remote = RemoteSkins { skins: vec![remote_skin(A, "A", 1)], deleted: vec![grave(B, 6)] };
        let plan = plan_skins(&local, &remote, &BTreeSet::new(), at(10));
        assert_eq!(plan, [SkinAction::DeleteRemote(A.into()), SkinAction::DeleteLocal { id: B.into(), at: at(6) }]);

        // Nach der Löschung woanders erneut geändert → der jüngere Stand gewinnt.
        let local = LibrarySnapshot { skins: vec![local_skin(B, "B", 9)], deleted: vec![grave(A, 5)] };
        let remote = RemoteSkins { skins: vec![remote_skin(A, "A", 7)], deleted: vec![grave(B, 6)] };
        let plan = plan_skins(&local, &remote, &BTreeSet::new(), at(10));
        assert_eq!(plan, [SkinAction::Download(remote_skin(A, "A", 7)), SkinAction::Upload(B.into())]);
    }

    #[test]
    fn newer_name_wins() {
        let local = LibrarySnapshot { skins: vec![local_skin(A, "Neu hier", 5), local_skin(B, "Alt hier", 1)], deleted: vec![] };
        let remote = RemoteSkins { skins: vec![remote_skin(A, "Alt dort", 2), remote_skin(B, "Neu dort", 4)], deleted: vec![] };
        let plan = plan_skins(&local, &remote, &BTreeSet::new(), at(10));
        assert_eq!(
            plan,
            [
                SkinAction::UpdateLocal(remote_skin(B, "Neu dort", 4)),
                SkinAction::Patch { id: A.into(), name: "Neu hier".into(), variant: SkinVariant::Classic },
            ]
        );
        // Gleich → nichts zu tun.
        let same = RemoteSkins { skins: vec![remote_skin(A, "Neu hier", 5), remote_skin(B, "Alt hier", 9)], deleted: vec![] };
        assert!(plan_skins(&local, &same, &BTreeSet::new(), at(10)).is_empty());
    }

    #[test]
    fn known_skins_missing_on_the_account_were_deleted_there() {
        let local = LibrarySnapshot { skins: vec![local_skin(A, "A", 1), local_skin(C, "C", 1)], deleted: vec![] };
        let remote = RemoteSkins { skins: vec![remote_skin(D, "D", 1)], deleted: vec![] };
        let known: BTreeSet<String> = [A.to_owned()].into();
        let plan = plan_skins(&local, &remote, &known, at(10));
        assert!(plan.contains(&SkinAction::DeleteLocal { id: A.into(), at: at(10) }));
        assert!(plan.contains(&SkinAction::Upload(C.into())));
        // Ein völlig leeres Konto (zurückgesetzt) löscht hier nie etwas.
        let plan = plan_skins(&local, &RemoteSkins::default(), &known, at(10));
        assert_eq!(plan, [SkinAction::Upload(A.into()), SkinAction::Upload(C.into())]);
    }

    fn values(theme: Theme, language: Language) -> UiValues {
        UiValues { theme, accent: Accent::Redstone, language }
    }

    #[test]
    fn settings_merge_per_key() {
        let local = values(Theme::Light, Language::De);
        // Nichts auf dem Konto → hochladen.
        let merged = merge_ui(local, UiTimes::default(), None);
        assert!(merged.push);
        assert_eq!(merged.data, json!({ "theme": "light", "accent": "redstone", "language": "de" }));

        // Neuer PC (hier nie geändert) → das Konto gewinnt, nichts hochladen.
        let remote = json!({ "theme": "oled", "accent": "lapis", "language": "es" });
        let merged = merge_ui(local, UiTimes::default(), Some((&remote, Some(at(0)))));
        assert_eq!(merged.values, UiValues { theme: Theme::Oled, accent: Accent::Lapis, language: Language::Es });
        assert!(!merged.push);

        // Sprache hier nach dem Stand des Kontos geändert → die gewinnt, der Rest vom Konto.
        let times = UiTimes { language: Some(at(5)), theme: Some(at(-5)), ..Default::default() };
        let merged = merge_ui(local, times, Some((&remote, Some(at(0)))));
        assert_eq!(merged.values, UiValues { theme: Theme::Oled, accent: Accent::Lapis, language: Language::De });
        assert!(merged.push);
        assert_eq!(merged.data, json!({ "theme": "oled", "accent": "lapis", "language": "de" }));

        // Unbekannter Wert (neuere Version) bleibt auf dem Konto erhalten.
        let remote = json!({ "theme": "neon", "accent": "lapis", "language": "de" });
        let merged = merge_ui(local, UiTimes::default(), Some((&remote, Some(at(0)))));
        assert_eq!(merged.values.theme, Theme::Light);
        assert!(!merged.push);
        assert_eq!(merged.data["theme"], "neon");
    }

    #[test]
    fn stamps_are_after_the_server_state() {
        assert_eq!(stamp_after(Some(at(0)), at(5)), at(5));
        // Uhr geht nach: genau der Stand des Servers (gleich alt überschreibt), nie weiter vor.
        assert_eq!(stamp_after(Some(at(5)), at(0)), at(5));
        assert_eq!(stamp_after(None, at(1)), at(1));
        assert_eq!(iso(at(0)), "2026-09-25T10:00:00.000Z");
    }

    #[test]
    fn scheduling_debounces_and_backs_off() {
        let state = SyncState::default();
        assert_eq!(state.wait(), None);
        state.kick();
        assert_eq!(state.wait(), Some(Duration::ZERO));
        // Ein sofortiger Abgleich wird durch eine Änderung nicht hinausgeschoben.
        state.touch();
        assert_eq!(state.wait(), Some(Duration::ZERO));

        state.begin();
        assert_eq!(state.wait(), None);
        state.touch();
        state.finish(SYNC_INTERVAL);
        assert!(state.wait().is_some_and(|w| w <= DEBOUNCE), "Änderung während des Abgleichs → gleich noch einmal");

        state.begin();
        state.finish(SYNC_INTERVAL);
        assert!(state.wait().is_some_and(|w| w > DEBOUNCE));

        assert_eq!(backoff(1), FIRST_BACKOFF);
        assert_eq!(backoff(2), FIRST_BACKOFF * 2);
        assert_eq!(backoff(40), MAX_BACKOFF);
        assert_eq!(state.failed("a", "offline", Duration::ZERO), FIRST_BACKOFF);
        assert_eq!(state.problem_for("a").as_deref(), Some("offline"));
        assert_eq!(state.problem_for("b"), None);
        state.succeeded();
        assert_eq!(state.problem_for("a"), None);
    }
}
