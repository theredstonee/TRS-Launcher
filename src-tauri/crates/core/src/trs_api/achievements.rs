//! Launcher-Erfolge (Vertrag: `api/API.md` §31).
//!
//! - `GET /v1/me/achievements`: Katalog (wie ich ihn sehe), freigeschaltete Erfolge,
//!   Fortschritt und Punkte. Geheime, noch gesperrte Erfolge (`hidden`) kommen ohne
//!   Titel, Beschreibung, Belohnung und Ziel – der Kern entfernt das zur Sicherheit
//!   auch dann, wenn der Server es doch schickt.
//! - `GET /v1/players/{uuid}/achievements`: Punkte und freigeschaltete Erfolge
//!   eines Freundes (nur man selbst und Freunde, sonst `404 player_not_found`).
//! - `POST /v1/me/achievements/report`: der Launcher meldet, was nur er sieht
//!   (Spielstart mit lokaler Stunde, Mods/Modpack installiert, Clip gespeichert,
//!   Absturz behoben, Import aus einem anderen Launcher). Meldungen landen in einer
//!   Warteschlange im Speicher, werden zusammengefasst (`count`) und gehen im
//!   Hintergrund raus – nie blockierend, nur mit Einwilligung und vorhandener
//!   TRS-Anmeldung; bei „offline“ später erneut, ein `429` wird nicht wiederholt.
//! - Ereignis `achievement_unlocked` (in [`super::live`]).

use std::collections::{BTreeMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::Notify;

use super::{Req, validate};
use crate::{Error, Launcher, Result};

pub const CATEGORIES: [&str; 4] = ["playtime", "launcher", "community", "secret"];
pub const RARITIES: [&str; 5] = ["common", "uncommon", "rare", "epic", "legendary"];
pub const REWARD_KINDS: [&str; 2] = ["cape", "cosmetic"];
pub const UNITS: [&str; 3] = ["count", "minutes", "days"];
/// Mehr Einträge im Katalog nimmt der Launcher nicht an.
const MAX_ACHIEVEMENTS: usize = 500;
/// Größter angenommener Fortschritts-/Zielwert (z. B. Spielminuten).
const MAX_VALUE: u64 = 1_000_000_000_000;

/// Erfolgs-ID: `[a-z0-9]` am Anfang, dann `[a-z0-9_.-]`, 1–64 Zeichen.
pub fn achievement_id(id: &str) -> bool {
    let b = id.as_bytes();
    (1..=64).contains(&b.len())
        && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit())
        && b.iter().all(|&c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'_' | b'-' | b'.'))
}

fn time(t: Option<String>) -> Option<String> {
    t.map(|t| validate::text(t.trim(), 40)).filter(|t| !t.is_empty())
}

/// Zahl aus JSON (auch Kommazahlen) → ganze Zahl in `0..=MAX_VALUE`.
fn count(v: &Value) -> Option<u64> {
    let n = v.as_f64()?;
    n.is_finite().then(|| n.clamp(0.0, MAX_VALUE as f64).floor() as u64)
}

/// Text je Sprache (`{ en?, de?, es? }`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LocalText {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub en: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub de: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub es: Option<String>,
}

impl LocalText {
    /// Gesäubert; ganz leer → `None`.
    fn cleaned(self, max: usize) -> Option<Self> {
        let c = |t: Option<String>| t.map(|t| super::chat::chat_text(t.trim(), max)).filter(|t| !t.trim().is_empty());
        let out = Self { en: c(self.en), de: c(self.de), es: c(self.es) };
        (out.en.is_some() || out.de.is_some() || out.es.is_some()).then_some(out)
    }

    /// Nimmt `{en,de,es}` oder (tolerant) einen einfachen Text.
    fn from_value(v: Option<Value>, max: usize) -> Option<Self> {
        match v? {
            Value::String(s) => Self { en: Some(s), ..Self::default() }.cleaned(max),
            v @ Value::Object(_) => serde_json::from_value::<Self>(v).ok()?.cleaned(max),
            _ => None,
        }
    }

    /// Text in `lang`, sonst Englisch, sonst irgendeiner.
    pub fn get(&self, lang: &str) -> Option<&str> {
        let pick = |l: &str| match l {
            "en" => self.en.as_deref(),
            "de" => self.de.as_deref(),
            "es" => self.es.as_deref(),
            _ => None,
        };
        pick(lang).or_else(|| ["en", "de", "es"].iter().find_map(|l| pick(l)))
    }
}

/// Belohnung eines Erfolgs (Umhang oder Kosmetik). Die API schickt nur Art und ID –
/// den Namen sucht die Oberfläche in den eigenen Umhang-/Kosmetik-Listen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Reward {
    pub kind: String,
    pub id: String,
    #[serde(default)]
    pub name: Option<String>,
}

impl Reward {
    pub(crate) fn from_value(v: Option<Value>) -> Option<Self> {
        let r: Self = serde_json::from_value(v?).ok()?;
        if !REWARD_KINDS.contains(&r.kind.as_str()) || !validate::cape_id(&r.id) {
            return None;
        }
        let name = r.name.map(|n| validate::text(n.trim(), 48)).filter(|n| !n.is_empty());
        Some(Self { name, ..r })
    }
}

/// Eintrag des Katalogs (`AchievementView`, §31.1) – gesäubert.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Achievement {
    pub id: String,
    pub category: String,
    pub secret: bool,
    /// Geheim und (für mich) gesperrt: ohne Titel, Beschreibung, Belohnung, Ziel und Einheit.
    pub hidden: bool,
    pub title: Option<LocalText>,
    pub description: Option<LocalText>,
    /// Symbol-Schlüssel der API (z. B. `clock`, `rocket`) – die Oberfläche hat einen Rückfall.
    pub icon: String,
    pub points: u32,
    /// `common` | `uncommon` | `rare` | `epic` | `legendary` (Unbekanntes → `common`).
    pub rarity: String,
    /// Ziel für einen Fortschrittsbalken (`None` = einmaliges Ereignis).
    pub goal: Option<u64>,
    /// `count` | `minutes` | `days` (nur mit Ziel).
    pub unit: Option<String>,
    /// Vom Server gezählt (`false` = vom Launcher gemeldet).
    pub verified: bool,
    pub reward: Option<Reward>,
    pub order: i64,
}

#[derive(Deserialize)]
struct ApiAchievement {
    id: String,
    #[serde(default)]
    category: String,
    #[serde(default)]
    secret: bool,
    #[serde(default)]
    hidden: bool,
    #[serde(default)]
    title: Option<Value>,
    #[serde(default)]
    description: Option<Value>,
    #[serde(default)]
    icon: Option<String>,
    #[serde(default)]
    points: Option<Value>,
    #[serde(default)]
    rarity: Option<String>,
    #[serde(default)]
    goal: Option<Value>,
    #[serde(default)]
    unit: Option<String>,
    #[serde(default)]
    verified: bool,
    #[serde(default)]
    reward: Option<Value>,
    #[serde(default)]
    order: Option<Value>,
}

impl Achievement {
    pub(crate) fn from_value(v: &Value) -> Option<Self> {
        let a: ApiAchievement = serde_json::from_value(v.clone()).ok()?;
        if !achievement_id(&a.id) {
            return None;
        }
        let category = if CATEGORIES.contains(&a.category.as_str()) {
            a.category
        } else if a.secret {
            "secret".into()
        } else {
            "launcher".into()
        };
        let icon = a
            .icon
            .map(|i| validate::text(i.trim(), 40))
            .filter(|i| !i.is_empty())
            .unwrap_or_else(|| "trophy".into());
        let rarity = a.rarity.filter(|r| RARITIES.contains(&r.as_str())).unwrap_or_else(|| "common".into());
        let goal = a.goal.as_ref().and_then(count).filter(|g| *g > 0);
        let mut out = Self {
            id: a.id,
            category,
            secret: a.secret,
            hidden: false,
            title: LocalText::from_value(a.title, 80),
            description: LocalText::from_value(a.description, 300),
            icon,
            points: a.points.as_ref().and_then(count).map_or(0, |p| p.min(100_000) as u32),
            rarity,
            unit: goal.and(a.unit.filter(|u| UNITS.contains(&u.as_str()))),
            goal,
            verified: a.verified,
            reward: Reward::from_value(a.reward),
            order: a.order.as_ref().and_then(Value::as_i64).unwrap_or(i64::MAX).clamp(-1_000_000, 1_000_000_000),
        };
        if a.hidden {
            out.hide();
        }
        Some(out)
    }

    /// Geheim und gesperrt: nur „???“ (die API schickt dann ohnehin nichts – sicher ist sicher).
    fn hide(&mut self) {
        self.hidden = true;
        self.title = None;
        self.description = None;
        self.reward = None;
        self.goal = None;
        self.unit = None;
        self.icon = "secret".into();
    }
}

/// Freigeschaltet (`at` = Zeitpunkt, ISO).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Unlocked {
    pub id: String,
    #[serde(default)]
    pub at: Option<String>,
}

fn unlocked_list(raw: Vec<Value>) -> Vec<Unlocked> {
    let mut seen = HashSet::new();
    raw.into_iter()
        .take(MAX_ACHIEVEMENTS * 2)
        .filter_map(|v| serde_json::from_value::<Unlocked>(v).ok())
        .filter(|u| achievement_id(&u.id) && seen.insert(u.id.clone()))
        .map(|u| Unlocked { at: time(u.at), ..u })
        .take(MAX_ACHIEVEMENTS)
        .collect()
}

/// Katalog säubern: ohne kaputte/doppelte Einträge, nach `order` sortiert.
fn catalog(raw: &[Value]) -> Vec<Achievement> {
    let mut seen = HashSet::new();
    let mut list: Vec<Achievement> = raw
        .iter()
        .take(MAX_ACHIEVEMENTS * 2)
        .filter_map(Achievement::from_value)
        .filter(|a| seen.insert(a.id.clone()))
        .take(MAX_ACHIEVEMENTS)
        .collect();
    list.sort_by_key(|a| a.order);
    list
}

/// Eigene Erfolge (`GET /v1/me/achievements`, §31.3).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MyAchievements {
    pub achievements: Vec<Achievement>,
    pub unlocked: Vec<Unlocked>,
    /// Fortschritt je Erfolg (nur Erfolge mit Ziel, höchstens bis zum Ziel).
    pub progress: BTreeMap<String, u64>,
    pub points: u64,
    /// Punkte aller Erfolge zusammen.
    pub total_points: u64,
    /// Freunde dürfen meine Erfolge sehen (Standard: ja).
    pub visible_to_friends: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiMine {
    #[serde(default)]
    achievements: Vec<Value>,
    #[serde(default)]
    unlocked: Vec<Value>,
    #[serde(default)]
    progress: serde_json::Map<String, Value>,
    #[serde(default)]
    points: Option<Value>,
    #[serde(default)]
    total_points: Option<Value>,
    #[serde(default)]
    visible_to_friends: Option<bool>,
}

impl MyAchievements {
    pub(crate) fn from_api(raw: Value) -> Result<Self> {
        let api: ApiMine = serde_json::from_value(raw).map_err(|_| super::bad_response())?;
        let mut achievements = catalog(&api.achievements);
        let known: HashSet<String> = achievements.iter().map(|a| a.id.clone()).collect();
        // Nur freigeschaltete Erfolge, die es im Katalog gibt.
        let unlocked: Vec<Unlocked> = unlocked_list(api.unlocked).into_iter().filter(|u| known.contains(&u.id)).collect();
        let done: HashSet<&str> = unlocked.iter().map(|u| u.id.as_str()).collect();
        for a in &mut achievements {
            if a.secret && !done.contains(a.id.as_str()) {
                a.hide();
            }
        }
        let goals: BTreeMap<&str, u64> = achievements.iter().filter_map(|a| a.goal.map(|g| (a.id.as_str(), g))).collect();
        let progress = api
            .progress
            .iter()
            .filter_map(|(id, v)| {
                let goal = *goals.get(id.as_str())?;
                Some((id.clone(), count(v)?.min(goal)))
            })
            .collect();
        let sum: u64 = achievements.iter().map(|a| u64::from(a.points)).sum();
        Ok(Self {
            achievements,
            unlocked,
            progress,
            points: api.points.as_ref().and_then(count).unwrap_or(0),
            total_points: api.total_points.as_ref().and_then(count).filter(|t| *t > 0).unwrap_or(sum),
            visible_to_friends: api.visible_to_friends.unwrap_or(true),
        })
    }
}

/// Erfolge eines Freundes (`GET /v1/players/{uuid}/achievements`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerAchievements {
    pub uuid: String,
    /// Der Freund zeigt seine Erfolge nicht (dann leer und 0 Punkte).
    pub hidden: bool,
    pub unlocked: Vec<Unlocked>,
    pub points: u64,
}

#[derive(Deserialize)]
struct ApiPlayer {
    #[serde(default)]
    hidden: bool,
    #[serde(default)]
    unlocked: Vec<Value>,
    #[serde(default)]
    points: Option<Value>,
}

impl PlayerAchievements {
    pub(crate) fn from_api(uuid: String, raw: Value) -> Result<Self> {
        let api: ApiPlayer = serde_json::from_value(raw).map_err(|_| super::bad_response())?;
        let mut unlocked = unlocked_list(api.unlocked);
        // Neueste zuerst (ISO-Zeiten sortieren sich als Text richtig).
        unlocked.sort_by(|a, b| b.at.cmp(&a.at));
        if api.hidden {
            return Ok(Self { uuid, hidden: true, unlocked: Vec::new(), points: 0 });
        }
        Ok(Self { uuid, hidden: false, unlocked, points: api.points.as_ref().and_then(count).unwrap_or(0) })
    }
}

// --- Meldungen (§31.4) ------------------------------------------------------------------------

/// Was der Launcher meldet (`POST /v1/me/achievements/report`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ReportKind {
    /// Spiel gestartet, mit der lokalen Stunde (0–23) für Tageszeit-Erfolge.
    Launch { hour: u8 },
    /// Mods einer Instanz hinzugefügt (1–100 auf einmal).
    ModInstalled { count: u8 },
    ModpackInstalled,
    /// Clips gespeichert (1–100 auf einmal).
    ClipRecorded { count: u8 },
    /// Der Absturz-Helfer hat eine Behebung angewendet.
    CrashFixed,
    /// Instanzen aus einem anderen Launcher übernommen.
    LauncherImport,
}

/// Größte Zahl in einer Meldung (`count`).
pub const MAX_REPORT_COUNT: u8 = 100;

impl ReportKind {
    pub fn name(self) -> &'static str {
        match self {
            Self::Launch { .. } => "launch",
            Self::ModInstalled { .. } => "mod_installed",
            Self::ModpackInstalled => "modpack_installed",
            Self::ClipRecorded { .. } => "clip_recorded",
            Self::CrashFixed => "crash_fixed",
            Self::LauncherImport => "launcher_import",
        }
    }

    /// Spielstart „jetzt“ mit der Stunde der Ortszeit.
    pub fn launch_now() -> Self {
        use chrono::Timelike;
        Self::Launch { hour: chrono::Local::now().hour().min(23) as u8 }
    }

    /// Was die Oberfläche selbst melden darf (alles andere meldet der Kern an Ort und Stelle).
    pub fn from_ui(kind: &str) -> Option<Self> {
        match kind {
            "crash_fixed" => Some(Self::CrashFixed),
            _ => None,
        }
    }

    pub(crate) fn body(self) -> Value {
        let count = |c: u8| c.clamp(1, MAX_REPORT_COUNT);
        match self {
            Self::Launch { hour } => json!({ "kind": "launch", "hour": hour.min(23) }),
            Self::ModInstalled { count: c } => json!({ "kind": "mod_installed", "count": count(c) }),
            Self::ClipRecorded { count: c } => json!({ "kind": "clip_recorded", "count": count(c) }),
            other => json!({ "kind": other.name() }),
        }
    }
}

/// Eine Meldung für einen bestimmten Account.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Pending {
    pub account: String,
    pub kind: ReportKind,
}

/// Fasst Meldungen zusammen: Mods und Clips je Account als Zahl (höchstens 100 je Meldung),
/// Merker (`crash_fixed`, `launcher_import`) nur einmal; Spielstarts und Modpacks einzeln.
pub(crate) fn merge(items: Vec<Pending>) -> Vec<Pending> {
    let mut out: Vec<Pending> = Vec::with_capacity(items.len());
    for item in items {
        let same = |p: &Pending| p.account == item.account && p.kind.name() == item.kind.name();
        match item.kind {
            ReportKind::ModInstalled { count } | ReportKind::ClipRecorded { count } => {
                let slot = out.iter_mut().rev().find(|p| {
                    same(p)
                        && matches!(p.kind, ReportKind::ModInstalled { count: c } | ReportKind::ClipRecorded { count: c } if c < MAX_REPORT_COUNT)
                });
                let mut left = count.max(1);
                if let Some(p) = slot {
                    let (ReportKind::ModInstalled { count: c } | ReportKind::ClipRecorded { count: c }) = &mut p.kind else {
                        unreachable!()
                    };
                    let add = left.min(MAX_REPORT_COUNT - *c);
                    *c += add;
                    left -= add;
                }
                if left > 0 {
                    let kind = match item.kind {
                        ReportKind::ModInstalled { .. } => ReportKind::ModInstalled { count: left },
                        _ => ReportKind::ClipRecorded { count: left },
                    };
                    out.push(Pending { account: item.account, kind });
                }
            }
            ReportKind::CrashFixed | ReportKind::LauncherImport => {
                if !out.iter().any(same) {
                    out.push(item);
                }
            }
            ReportKind::Launch { .. } | ReportKind::ModpackInstalled => out.push(item),
        }
    }
    out
}

/// So lange sammelt der Kern nach der ersten Meldung, bevor er sendet.
pub(crate) const REPORT_DEBOUNCE: Duration = Duration::from_secs(8);
/// Nächster Versuch, wenn der Server nicht erreichbar war.
const REPORT_RETRY: Duration = Duration::from_secs(120);
/// Pause, bevor weitere Meldungen folgen (Grenze der API: 30 je Minute und Account).
const REPORT_PACE: Duration = Duration::from_secs(60);
/// Höchstens so viele Meldungen warten (ältere fallen zuerst weg).
pub(crate) const MAX_PENDING: usize = 200;
/// Höchstens so viele Anfragen je Durchgang (der Rest folgt nach [`REPORT_PACE`]).
pub(crate) const MAX_REQUESTS_PER_FLUSH: usize = 20;

/// Warteschlange der Meldungen (nur im Speicher).
#[derive(Default)]
pub(crate) struct ReportQueue {
    pending: StdMutex<VecDeque<Pending>>,
    pub(crate) notify: Notify,
}

impl ReportQueue {
    fn lock(&self) -> std::sync::MutexGuard<'_, VecDeque<Pending>> {
        self.pending.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    pub(crate) fn push(&self, account: &str, kind: ReportKind) {
        {
            let mut q = self.lock();
            while q.len() >= MAX_PENDING {
                q.pop_front();
            }
            q.push_back(Pending { account: account.to_owned(), kind });
        }
        self.notify.notify_one();
    }

    pub(crate) fn take_all(&self) -> Vec<Pending> {
        self.lock().drain(..).collect()
    }

    /// Nicht gesendete Meldungen wieder nach vorn (Reihenfolge bleibt).
    pub(crate) fn put_back(&self, items: Vec<Pending>) {
        let mut q = self.lock();
        for item in items.into_iter().rev() {
            q.push_front(item);
        }
        while q.len() > MAX_PENDING {
            q.pop_front();
        }
    }

    pub(crate) fn clear(&self) {
        self.lock().clear();
    }

    #[cfg(test)]
    pub(crate) fn len(&self) -> usize {
        self.lock().len()
    }

    #[cfg(test)]
    pub(crate) fn snapshot(&self) -> Vec<Pending> {
        self.lock().iter().cloned().collect()
    }
}

fn player_arg(input: &str) -> Result<String> {
    validate::uuid(input)
        .ok_or_else(|| Error::validation(crate::msg!("trsOps.invalidPlayerId", "Ungültige Spieler-ID.")))
}

impl Launcher {
    /// Eigene Erfolge mit Katalog, Fortschritt und Punkten.
    pub async fn trs_achievements(&self) -> Result<MyAchievements> {
        let raw: Value = self.trs_get(Req::get("/v1/me/achievements")).await?;
        MyAchievements::from_api(raw)
    }

    /// Punkte und freigeschaltete Erfolge eines Freundes (oder von sich selbst; sonst `404 player_not_found`).
    pub async fn trs_player_achievements(&self, uuid: &str) -> Result<PlayerAchievements> {
        let uuid = player_arg(uuid)?;
        let raw: Value = self.trs_get(Req::get(format!("/v1/players/{uuid}/achievements"))).await?;
        PlayerAchievements::from_api(uuid, raw)
    }

    /// Erfolge für Freunde sichtbar (`true`) oder privat – gibt den neuen Stand zurück.
    pub async fn trs_set_achievements_visible(&self, visible: bool) -> Result<bool> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Answer {
            visible_to_friends: bool,
        }
        let req = Req::patch("/v1/me/achievements/settings", json!({ "visibleToFriends": visible }));
        let answer: Answer = self.trs_get(req).await?;
        Ok(answer.visible_to_friends)
    }

    /// Meldung für die Erfolge vormerken – kehrt sofort zurück, gesendet wird im
    /// Hintergrund ([`Self::run_achievement_reports`]). `account` = Account des
    /// Spiels, sonst der aktive. Ohne Einwilligung passiert später einfach nichts.
    pub async fn trs_achievement_event(&self, kind: ReportKind, account: Option<&str>) {
        let account = match account.and_then(validate::uuid) {
            Some(a) => a,
            None => match self.accounts().active_id().await {
                Ok(Some(a)) => a,
                _ => return,
            },
        };
        self.trs.achievements.push(&account, kind);
    }

    /// Hintergrund-Schleife: sammelt Meldungen kurz und schickt sie gesammelt.
    pub async fn run_achievement_reports(self: Arc<Self>) {
        loop {
            self.trs.achievements.notify.notified().await;
            tokio::time::sleep(REPORT_DEBOUNCE).await;
            while let Flush::Retry(wait) = self.flush_achievement_reports().await {
                tokio::time::sleep(wait).await;
            }
        }
    }

    /// Ein Durchgang: Meldungen zusammenfassen, höchstens [`MAX_REQUESTS_PER_FLUSH`] Anfragen.
    pub(crate) async fn flush_achievement_reports(&self) -> Flush {
        let queue = &self.trs.achievements;
        if !self.trs.enabled().await {
            queue.clear();
            return Flush::Done;
        }
        let mut batch = merge(queue.take_all());
        if batch.is_empty() {
            return Flush::Done;
        }
        let later = if batch.len() > MAX_REQUESTS_PER_FLUSH { batch.split_off(MAX_REQUESTS_PER_FLUSH) } else { Vec::new() };
        let mut rest = batch.into_iter();
        while let Some(item) = rest.next() {
            // Nur mit bestehender TRS-Anmeldung – für eine Meldung meldet der Launcher niemanden neu an.
            if !self.trs.has_token(&item.account).await {
                continue;
            }
            let req = Req::post("/v1/me/achievements/report", item.kind.body());
            match self.trs.call_raw(self.accounts(), &item.account, &req).await {
                Ok(_) => {}
                // Offline: nichts geht verloren, später erneut.
                Err(Error::TrsApi { kind: "trs_offline", .. }) => {
                    queue.put_back(std::iter::once(item).chain(rest).chain(later).collect());
                    return Flush::Retry(REPORT_RETRY);
                }
                // Grenze erreicht: diese Meldung nicht wiederholen (§31.4), den Rest später.
                Err(Error::TrsApi { kind: "trs_rate_limited", .. }) => {
                    queue.put_back(rest.chain(later).collect());
                    return Flush::Retry(REPORT_PACE);
                }
                Err(e) => tracing::debug!("Erfolgs-Meldung {} verworfen: {e}", item.kind.name()),
            }
        }
        if later.is_empty() {
            Flush::Done
        } else {
            queue.put_back(later);
            Flush::Retry(REPORT_PACE)
        }
    }
}

/// Ergebnis eines Sende-Durchgangs.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Flush {
    Done,
    Retry(Duration),
}
