//! Halloween 2026 „Nacht der langen Schatten“ (Vertrag: `api/API.md` §36): Kürbis-Suche
//! (13 versteckte Kürbisse), Süßes oder Saures (1× am Tag), Spuk-Effekte, Abzeichen und
//! Chat-Sticker.
//!
//! Alles ist großzügig gelesen (`serde(default)`): Eine ältere API ohne §36 liefert dann
//! einfach „nichts los“, statt dass die Oberfläche bricht. Abzeichen- und Sticker-Bilder
//! lädt der Kern selbst und gibt sie als Data-URL weiter (CSP bleibt eng); sie sind
//! öffentlich und werden für die Laufzeit im Speicher gehalten.

use std::collections::HashMap;
use std::sync::Mutex;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::live::LiveEvent;
use super::{Req, TrsApi, png, validate};
use crate::{Error, Launcher, Result};

/// Die 5 Kürbisse, die der Launcher versteckt (Vertrag, Reihenfolge = Rätsel 1–5).
pub const LAUNCHER_PUMPKINS: [&str; 5] = ["launcher-titlebar", "launcher-settings", "launcher-skins", "launcher-social", "launcher-logs"];
/// Alle 13 Verstecke (Launcher, Website, TRS Client).
pub const PUMPKINS: [&str; 13] = [
    "launcher-titlebar",
    "launcher-settings",
    "launcher-skins",
    "launcher-social",
    "launcher-logs",
    "web-home",
    "web-features",
    "web-roadmap",
    "web-creator",
    "client-title",
    "client-wardrobe",
    "client-hud",
    "client-modules",
];
/// Mehr Abzeichen/Sticker zeigt der Launcher nicht.
const MAX_BADGES: usize = 16;
const MAX_STICKERS: usize = 64;
/// Bilder: pixelige PNGs (×8 vergrößert), höchstens 512 px Kante.
const MAX_IMAGE_BYTES: usize = 256 * 1024;
const MAX_IMAGE_EDGE: u32 = 512;
const IMAGE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);

/// `^[a-z0-9][a-z0-9_-]{0,39}$` – Abzeichen, Sticker, Kürbisse, Belohnungen.
pub fn item_id(id: &str) -> bool {
    validate::cape_id(id)
}

/// Zeitpunkt aus der API (ISO 8601) – nur harmlose Zeichen, sonst `None`.
fn iso(v: Option<String>) -> Option<String> {
    v.filter(|s| (10..=40).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_digit() || matches!(b, b'-' | b':' | b'T' | b'Z' | b'.' | b'+')))
}

fn ids(raw: Vec<Value>, max: usize) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for v in raw {
        if let Some(id) = v.as_str().filter(|s| item_id(s))
            && !out.iter().any(|x| x == id)
            && out.len() < max
        {
            out.push(id.to_owned());
        }
    }
    out
}

/// Text in drei Sprachen (`{ de, en, es }`); fehlende Sprachen bleiben leer.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct LocalText {
    pub de: String,
    pub en: String,
    pub es: String,
}

impl LocalText {
    fn cleaned(self, max: usize) -> Option<Self> {
        let t = Self { de: validate::text(self.de.trim(), max), en: validate::text(self.en.trim(), max), es: validate::text(self.es.trim(), max) };
        (!t.de.is_empty() || !t.en.is_empty() || !t.es.is_empty()).then_some(t)
    }
}

/// Art einer Belohnung aus „Süßes oder Saures“.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RewardKind {
    Cape,
    Emote,
    Sticker,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HalloweenReward {
    pub kind: RewardKind,
    pub id: String,
    #[serde(default)]
    pub name: LocalText,
}

impl HalloweenReward {
    fn from_value(v: Option<Value>) -> Option<Self> {
        let r: Self = serde_json::from_value(v?).ok()?;
        item_id(&r.id).then(|| Self { name: r.name.cleaned(48).unwrap_or_default(), ..r })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TrickResult {
    Sweet,
    Sour,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PumpkinHunt {
    pub total: u32,
    pub found: Vec<String>,
    pub next_hint: Option<LocalText>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrickState {
    pub used_today: bool,
    pub result: Option<TrickResult>,
    pub reward: Option<HalloweenReward>,
    /// Ab wann wieder angeklopft werden darf.
    pub next: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SpookyEffects {
    /// Fledermäuse bei Treffern (für andere TRS-Spieler sichtbar).
    pub hits: bool,
    /// Geister-Spur beim Sprinten.
    pub trail: bool,
}

impl Default for SpookyEffects {
    fn default() -> Self {
        Self { hits: true, trail: true }
    }
}

/// Flügel-Wahl nach allen 13 Kürbissen (genau einer von vier, endgültig).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WingsChoice {
    pub choice: Option<String>,
    pub can_choose: bool,
    pub options: Vec<String>,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct ApiWings {
    choice: Option<String>,
    can_choose: bool,
    options: Vec<Value>,
}

/// Stand von `GET /v1/me/halloween` (auch Nutzlast von `halloween_updated`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HalloweenState {
    /// Funktionen laufen (Event an und vor dem Ende).
    pub active: bool,
    pub ends_at: Option<String>,
    pub pumpkins: PumpkinHunt,
    pub trick: TrickState,
    pub badges: Vec<String>,
    pub effects: SpookyEffects,
    /// Flügel-Belohnung der Kürbis-Suche (ältere API: nichts zu wählen).
    pub wings: WingsChoice,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct ApiHunt {
    total: Option<u32>,
    found: Vec<Value>,
    next_hint: Option<Value>,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct ApiTrick {
    used_today: bool,
    result: Option<Value>,
    reward: Option<Value>,
    next: Option<String>,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct ApiState {
    active: bool,
    ends_at: Option<String>,
    pumpkins: ApiHunt,
    trick: ApiTrick,
    badges: Vec<Value>,
    effects: Option<Value>,
    wings: Option<Value>,
}

fn trick_result(v: Option<Value>) -> Option<TrickResult> {
    serde_json::from_value(v?).ok()
}

fn hint(v: Option<Value>) -> Option<LocalText> {
    serde_json::from_value::<LocalText>(v?).ok()?.cleaned(200)
}

impl HalloweenState {
    /// Gesäubert aus einer beliebigen JSON-Antwort; `None` nur bei völlig fremden Daten.
    pub fn from_value(v: Value) -> Option<Self> {
        if !v.is_object() {
            return None;
        }
        let raw: ApiState = serde_json::from_value(v).unwrap_or_default();
        let found: Vec<String> = ids(raw.pumpkins.found, PUMPKINS.len());
        Some(Self {
            active: raw.active,
            ends_at: iso(raw.ends_at),
            pumpkins: PumpkinHunt {
                total: raw.pumpkins.total.filter(|t| (1..=64).contains(t)).unwrap_or(PUMPKINS.len() as u32),
                found,
                next_hint: hint(raw.pumpkins.next_hint),
            },
            trick: TrickState {
                used_today: raw.trick.used_today,
                result: trick_result(raw.trick.result),
                reward: HalloweenReward::from_value(raw.trick.reward),
                next: iso(raw.trick.next),
            },
            badges: ids(raw.badges, MAX_BADGES),
            effects: raw.effects.and_then(|e| serde_json::from_value(e).ok()).unwrap_or_default(),
            wings: raw
                .wings
                .and_then(|w| serde_json::from_value::<ApiWings>(w).ok())
                .map(|w| {
                    let options = ids(w.options, 8);
                    let choice = w.choice.filter(|c| item_id(c));
                    WingsChoice { can_choose: w.can_choose && choice.is_none() && !options.is_empty(), choice, options }
                })
                .unwrap_or_default(),
        })
    }

    /// Ältere API ohne §36: nichts aktiv, nichts gefunden.
    pub fn inactive() -> Self {
        Self {
            active: false,
            ends_at: None,
            pumpkins: PumpkinHunt { total: PUMPKINS.len() as u32, found: Vec::new(), next_hint: None },
            trick: TrickState { used_today: false, result: None, reward: None, next: None },
            badges: Vec::new(),
            effects: SpookyEffects::default(),
            wings: WingsChoice::default(),
        }
    }
}

/// Belohnung, wenn alle 13 Kürbisse gefunden sind.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HunterReward {
    pub badge: Option<String>,
    pub cape: Option<String>,
}

/// Antwort auf `POST /v1/me/halloween/pumpkins/{id}`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PumpkinFound {
    pub found: Vec<String>,
    pub count: u32,
    pub complete: bool,
    pub reward: Option<HunterReward>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct ApiFound {
    found: Vec<Value>,
    count: Option<u32>,
    complete: bool,
    reward: Option<Value>,
}

impl PumpkinFound {
    fn from_value(v: Value) -> Self {
        let raw: ApiFound = serde_json::from_value(v).unwrap_or_default();
        let found = ids(raw.found, PUMPKINS.len());
        let count = raw.count.filter(|c| *c <= 64).unwrap_or(found.len() as u32);
        let reward = raw.reward.filter(Value::is_object).map(|r| HunterReward {
            badge: r.get("badge").and_then(Value::as_str).filter(|s| item_id(s)).map(str::to_owned),
            cape: r.get("cape").and_then(Value::as_str).filter(|s| item_id(s)).map(str::to_owned),
        });
        Self { found, count, complete: raw.complete, reward }
    }
}

/// Antwort auf `POST /v1/me/halloween/trick`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrickOutcome {
    pub result: TrickResult,
    pub reward: Option<HalloweenReward>,
    pub next: Option<String>,
}

impl TrickOutcome {
    fn from_value(v: Value) -> Option<Self> {
        let result = trick_result(v.get("result").cloned())?;
        let reward = if result == TrickResult::Sweet { HalloweenReward::from_value(v.get("reward").cloned()) } else { None };
        Some(Self { result, reward, next: iso(v.get("next").and_then(Value::as_str).map(str::to_owned)) })
    }
}

/// Chat-Sticker (`GET /v1/stickers`) mit Bild und Besitz.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Sticker {
    pub id: String,
    pub name: LocalText,
    pub image: Option<String>,
    pub owned: bool,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct ApiSticker {
    id: String,
    name: Option<Value>,
    url: Option<String>,
}

/// Abzeichen-Bild als Data-URL.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BadgeImage {
    pub id: String,
    pub image: String,
}

fn data_url(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", STANDARD.encode(bytes))
}

/// Bilder der Laufzeit (Schlüssel: API-Adresse + Pfad).
static IMAGES: Mutex<Option<HashMap<String, Option<String>>>> = Mutex::new(None);

impl TrsApi {
    /// Öffentliches PNG der eigenen API (`/v1/badges/…`, `/v1/stickers/…`) als Data-URL.
    async fn public_png(&self, path: &str) -> Option<String> {
        let key = format!("{}{path}", self.base);
        if let Ok(cache) = IMAGES.lock()
            && let Some(hit) = cache.as_ref().and_then(|c| c.get(&key))
        {
            return hit.clone();
        }
        let image = self.fetch_png(&key).await;
        // Fehlschläge (offline) nicht merken – nächstes Mal neu versuchen.
        if image.is_some()
            && let Ok(mut cache) = IMAGES.lock()
        {
            let map = cache.get_or_insert_with(HashMap::new);
            if map.len() > 256 {
                map.clear();
            }
            map.insert(key, image.clone());
        }
        image
    }

    async fn fetch_png(&self, url: &str) -> Option<String> {
        let response = self.http.get(url).header("Accept", "image/png").timeout(IMAGE_TIMEOUT).send().await.ok()?;
        if !response.status().is_success() || response.content_length().is_some_and(|l| l > MAX_IMAGE_BYTES as u64) {
            return None;
        }
        let bytes = response.bytes().await.ok()?;
        let (w, h) = png::size(&bytes)?;
        (bytes.len() <= MAX_IMAGE_BYTES && (1..=MAX_IMAGE_EDGE).contains(&w) && (1..=MAX_IMAGE_EDGE).contains(&h)).then(|| data_url(&bytes))
    }
}

/// `halloween_updated` (§36) → gesäubertes Ereignis.
pub fn decode_live(event: &str, data: &str) -> Option<LiveEvent> {
    match event {
        "halloween_updated" => {
            let v: Value = serde_json::from_str(data).ok()?;
            Some(LiveEvent::HalloweenUpdated(Box::new(HalloweenState::from_value(v)?)))
        }
        _ => None,
    }
}

fn pumpkin_arg(id: &str) -> Result<&str> {
    if PUMPKINS.contains(&id) {
        Ok(id)
    } else {
        Err(Error::validation(crate::msg!("trsApi.pumpkin_unknown", "Diesen Kürbis gibt es nicht.")))
    }
}

impl Launcher {
    /// Stand des Halloween-Events. Eine API ohne §36 (404) gilt als „nichts los“.
    pub async fn halloween_state(&self) -> Result<HalloweenState> {
        match self.trs_get::<Value>(Req::get("/v1/me/halloween")).await {
            Ok(v) => HalloweenState::from_value(v).ok_or_else(super::bad_response),
            // Unbekannte Route einer älteren API (404 ohne oder mit `not_found`).
            Err(e) if e.kind() == "trs_api" && matches!(e.code(), None | Some("not_found")) => Ok(HalloweenState::inactive()),
            Err(e) => Err(e),
        }
    }

    /// Kürbis gefunden melden (idempotent, Server prüft alles).
    pub async fn halloween_find_pumpkin(&self, id: &str) -> Result<PumpkinFound> {
        let id = pumpkin_arg(id)?;
        let v: Value = self.trs_get(Req::post_empty(format!("/v1/me/halloween/pumpkins/{id}"))).await?;
        Ok(PumpkinFound::from_value(v))
    }

    /// Süßes oder Saures – einmal pro Tag (Europe/Berlin), der Server würfelt.
    pub async fn halloween_trick(&self) -> Result<TrickOutcome> {
        let v: Value = self.trs_get(Req::post_empty("/v1/me/halloween/trick")).await?;
        TrickOutcome::from_value(v).ok_or_else(super::bad_response)
    }

    /// Flügel nach allen 13 Kürbissen wählen – genau einmal, endgültig. Gibt den neuen Stand zurück.
    pub async fn halloween_choose_wings(&self, id: &str) -> Result<HalloweenState> {
        if !item_id(id) {
            return Err(Error::validation(crate::msg!("trsOps.invalidCosmeticId", "Ungültige Kosmetik-ID.")));
        }
        self.trs_do(Req::post("/v1/me/halloween/wings", json!({ "id": id }))).await?;
        self.halloween_state().await
    }

    /// Ob andere TRS-Spieler meine Spuk-Effekte sehen dürfen.
    pub async fn halloween_set_effects(&self, effects: SpookyEffects) -> Result<()> {
        self.trs_do(Req::put("/v1/me/halloween/effects", json!({ "hits": effects.hits, "trail": effects.trail }))).await
    }

    /// Alle Chat-Sticker mit Bild; `owned` aus `GET /v1/me/cosmetics` (fehlt dort → keiner).
    pub async fn halloween_stickers(&self) -> Result<Vec<Sticker>> {
        let raw: Value = self.trs_get(Req::get("/v1/stickers")).await?;
        let owned: Vec<String> = match self.trs_get::<Value>(Req::get("/v1/me/cosmetics")).await {
            Ok(me) => ids(me.get("stickers").and_then(Value::as_array).cloned().unwrap_or_default(), MAX_STICKERS),
            Err(e) => {
                tracing::debug!("Eigene Sticker nicht lesbar: {e}");
                Vec::new()
            }
        };
        let list = raw.get("stickers").and_then(Value::as_array).cloned().unwrap_or_default();
        let mut seen: Vec<String> = Vec::new();
        let mut jobs = Vec::new();
        for v in list.into_iter().take(MAX_STICKERS) {
            let Ok(s) = serde_json::from_value::<ApiSticker>(v) else { continue };
            if !item_id(&s.id) || seen.contains(&s.id) {
                continue;
            }
            seen.push(s.id.clone());
            let name = s.name.and_then(|n| serde_json::from_value::<LocalText>(n).ok()).and_then(|n| n.cleaned(32)).unwrap_or_default();
            let owned = owned.contains(&s.id);
            // Nur Bilder der eigenen API (`/v1/stickers/<id>.png`), egal was in `url` steht.
            let _ = s.url;
            let path = format!("/v1/stickers/{}.png", s.id);
            jobs.push(async move {
                let image = self.trs.public_png(&path).await;
                Sticker { id: s.id, name, image, owned }
            });
        }
        Ok(futures::future::join_all(jobs).await)
    }

    /// Bilder der Abzeichen (`/v1/badges/<id>.png`); fehlende werden ausgelassen.
    pub async fn trs_badge_images(&self, ids_in: &[String]) -> Result<Vec<BadgeImage>> {
        let mut list: Vec<String> = ids_in.iter().filter(|i| item_id(i)).cloned().collect();
        list.sort();
        list.dedup();
        list.truncate(MAX_BADGES * 2);
        let jobs = list.into_iter().map(|id| async move {
            let image = self.trs.public_png(&format!("/v1/badges/{id}.png")).await?;
            Some(BadgeImage { id, image })
        });
        Ok(futures::future::join_all(jobs).await.into_iter().flatten().collect())
    }
}

/// Abzeichen/Effekte aus dem Spieler-Lookup (§4.1) säubern.
pub(crate) fn clean_badges(raw: Vec<Value>) -> Vec<String> {
    ids(raw, MAX_BADGES)
}

pub(crate) fn clean_fx(raw: Vec<Value>) -> Vec<String> {
    ids(raw, 4).into_iter().filter(|f| f == "hits" || f == "trail").collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn state_parses_the_contract_and_drops_junk() {
        let v = json!({
            "active": true,
            "endsAt": "2026-11-02T23:00:00Z",
            "pumpkins": { "total": 13, "found": ["launcher-titlebar", "web-home", "web-home", "../x", 7],
                "nextHint": { "de": "Ganz unten …", "en": "At the very bottom …", "es": "Abajo …" } },
            "trick": { "usedToday": true, "result": "sweet", "next": "2026-10-17T22:00:00Z",
                "reward": { "kind": "cape", "id": "halloween_blood_moon", "name": { "de": "Blutmond", "en": "Blood moon", "es": "Luna de sangre" } } },
            "badges": ["halloween2026", "Bad Id"],
            "effects": { "hits": false, "trail": true }
        });
        let s = HalloweenState::from_value(v).unwrap();
        assert!(s.active);
        assert_eq!(s.ends_at.as_deref(), Some("2026-11-02T23:00:00Z"));
        assert_eq!(s.pumpkins.found, ["launcher-titlebar", "web-home"]);
        assert_eq!(s.pumpkins.next_hint.as_ref().unwrap().en, "At the very bottom …");
        assert_eq!(s.trick.result, Some(TrickResult::Sweet));
        assert_eq!(s.trick.reward.as_ref().unwrap().kind, RewardKind::Cape);
        assert_eq!(s.badges, ["halloween2026"]);
        assert_eq!(s.effects, SpookyEffects { hits: false, trail: true });
        assert_eq!(s.wings, WingsChoice::default(), "ohne Feld nichts zu wählen");
        let w = HalloweenState::from_value(json!({ "wings": { "choice": null, "canChoose": true, "options": ["bat_wings", "demon_wings", "../x"] } })).unwrap();
        assert!(w.wings.can_choose);
        assert_eq!(w.wings.options, ["bat_wings", "demon_wings"]);
        let chosen = HalloweenState::from_value(json!({ "wings": { "choice": "raven_wings", "canChoose": true, "options": ["raven_wings"] } })).unwrap();
        assert!(!chosen.wings.can_choose, "schon gewählt = endgültig");
        let json = serde_json::to_value(&s).unwrap();
        assert_eq!(json["pumpkins"]["nextHint"]["de"], "Ganz unten …");
        assert_eq!(json["trick"]["usedToday"], true);
    }

    #[test]
    fn empty_or_old_answers_degrade_gracefully() {
        let s = HalloweenState::from_value(json!({})).unwrap();
        assert!(!s.active && s.pumpkins.found.is_empty() && s.badges.is_empty());
        assert_eq!(s.pumpkins.total, 13);
        assert_eq!(s.effects, SpookyEffects { hits: true, trail: true }, "ab Werk dürfen andere die Effekte sehen");
        assert!(HalloweenState::from_value(json!("nope")).is_none());
        // Kaputte Teile fallen einzeln weg.
        let s = HalloweenState::from_value(json!({ "active": true, "endsAt": "<script>", "trick": { "result": "boom" }, "pumpkins": { "nextHint": {} } })).unwrap();
        assert!(s.active && s.ends_at.is_none() && s.trick.result.is_none() && s.pumpkins.next_hint.is_none());
        assert_eq!(HalloweenState::inactive().pumpkins.total, 13);
    }

    #[test]
    fn found_and_trick_answers() {
        let f = PumpkinFound::from_value(json!({ "found": ["launcher-logs"], "count": 13, "complete": true, "reward": { "badge": "pumpkinhunter2026", "cape": "halloween_hunter_2026" } }));
        assert_eq!(f.count, 13);
        assert!(f.complete);
        assert_eq!(f.reward.unwrap().cape.as_deref(), Some("halloween_hunter_2026"));
        let f = PumpkinFound::from_value(json!({ "found": ["launcher-logs", "web-home"] }));
        assert_eq!((f.count, f.complete, f.reward), (2, false, None));

        let sweet = TrickOutcome::from_value(json!({ "result": "sweet", "reward": { "kind": "sticker", "id": "ghost_boo", "name": { "en": "Boo!" } }, "next": "2026-10-17T22:00:00Z" })).unwrap();
        assert_eq!(sweet.result, TrickResult::Sweet);
        assert_eq!(sweet.reward.unwrap().name.en, "Boo!");
        let sour = TrickOutcome::from_value(json!({ "result": "sour", "reward": { "kind": "cape", "id": "x" } })).unwrap();
        assert_eq!((sour.result, sour.reward), (TrickResult::Sour, None), "Saures bringt nie etwas");
        assert!(TrickOutcome::from_value(json!({})).is_none());
        let odd = HalloweenReward::from_value(Some(json!({ "kind": "hat", "id": "witch_hat" }))).unwrap();
        assert_eq!(odd.kind, RewardKind::Other);
    }

    #[test]
    fn pumpkin_ids_are_checked_locally() {
        assert!(pumpkin_arg("launcher-logs").is_ok());
        assert!(pumpkin_arg("../launcher-logs").is_err());
        assert!(pumpkin_arg("launcher-nowhere").is_err());
        assert!(LAUNCHER_PUMPKINS.iter().all(|p| PUMPKINS.contains(p)));
    }

    #[test]
    fn live_event_carries_the_full_state() {
        let ev = decode_live("halloween_updated", r#"{"active":true,"pumpkins":{"found":["web-home"]},"badges":["halloween2026"]}"#).unwrap();
        let json = serde_json::to_value(&ev).unwrap();
        assert_eq!(json["type"], "halloween_updated");
        assert_eq!(json["active"], true);
        assert_eq!(json["pumpkins"]["found"][0], "web-home");
        assert!(decode_live("halloween_other", "{}").is_none());
        assert!(decode_live("halloween_updated", "[]").is_none());
    }

    #[test]
    fn lookup_extras_are_whitelisted() {
        assert_eq!(clean_badges(vec![json!("halloween2026"), json!("pumpkinhunter2026"), json!("<b>")]), ["halloween2026", "pumpkinhunter2026"]);
        assert_eq!(clean_fx(vec![json!("hits"), json!("laser"), json!("trail")]), ["hits", "trail"]);
    }
}
