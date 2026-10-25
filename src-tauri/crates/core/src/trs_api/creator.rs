//! Creator-Programm (Vertrag: `api/API.md` §35): Creator-Codes einlösen und
//! Creator unterstützen, Fan-Symbole, Fan-Umhänge, Blockieren, das Creator-
//! Dashboard (Codes, Statistik, Symbol, Umhang, Vorschau-Builds, Packs,
//! Anträge) und die Team-Verwaltung (`creators.review`/`creators.manage`).
//!
//! Wie überall gilt: Der Token bleibt im Kern, alle Antworten gehen gesäubert
//! und typisiert ans Webview. Bilder (Symbol-Streifen, Fan-Umhänge) lädt der
//! Kern selbst und gibt sie als Data-URL weiter – die CSP bleibt eng. Freigegebene
//! Symbole sind unveränderlich (Name = SHA-256) und landen im Cache
//! `<daten>/cache/trs-creator-symbols/<sha256>.png`.

use std::path::PathBuf;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::chat::{chat_text, one_line};
use super::live::LiveEvent;
use super::types::{UserRef, clean_user};
use super::{Req, TrsApi, encode_query, png, validate};
use crate::{Error, Launcher, Result};

/// Stufen, Zustände und Meilensteine (§35.1).
pub const TIERS: [&str; 3] = ["starter", "partner", "top"];
pub const STATES: [&str; 3] = ["active", "revoked", "banned"];
pub const MILESTONES: [&str; 4] = ["bronze", "silver", "gold", "diamond"];
/// Arten von `creator_updated` (§35.7).
/// `pet_*` (Creator-Haustiere, §35.13): zugeordnet, entfernt, Wunsch beantwortet (`reason` = Antwort des Teams).
/// `team_changed` (§35.9): das Team hat im Team-Modus etwas geändert (`action`).
pub const UPDATE_KINDS: [&str; 16] = [
    "symbol_approved",
    "symbol_rejected",
    "cape_approved",
    "cape_rejected",
    "tier_changed",
    "revoked",
    "restored",
    "request_answered",
    "milestone",
    "became_creator",
    "helper_joined",
    "helper_left",
    "pet_assigned",
    "pet_removed",
    "pet_request_answered",
    "team_changed",
];
/// Rechte eines Helfers (§35.9). Stufe, Anträge, Helfer und Vorschau-Builds bleiben beim Creator.
pub const HELPER_RIGHTS: [&str; 4] = ["stats", "codes", "uploads", "packs"];
/// Bildtempo eines animierten Fan-Symbols (ms je Bild).
pub const SYMBOL_FRAME_MS: std::ops::RangeInclusive<u32> = 50..=1000;
pub const SYMBOL_FRAME_MS_DEFAULT: u32 = 150;
/// Größte Antwort für Symbol-Streifen (Upload höchstens 256 KiB, der Server kodiert neu).
const MAX_SYMBOL_BYTES: usize = 1024 * 1024;
/// Größte Umhang-Textur (wie `texture.rs`: HD-Streifen bis 1024 × 512·32).
const MAX_CAPE_BYTES: usize = 24 * 1024 * 1024;
const IMAGE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);

// --- Prüfungen --------------------------------------------------------------------------

pub(super) fn invalid(msg: crate::error::Msg) -> Error {
    Error::validation(msg)
}

fn invalid_code() -> Error {
    invalid(crate::msg!(
        "creator.invalidCode",
        "Creator-Codes haben 5 bis 20 Zeichen: Buchstaben, Ziffern und Bindestriche (z. B. TRS-PIXEL)."
    ))
}

/// Creator-Code normalisieren: getrimmt, groß, `^[A-Z0-9][A-Z0-9-]{3,18}[A-Z0-9]$`, kein Präfix nötig.
/// 20 Zeichen ohne Bindestrich sind ein Umhang-Code (§5.9) und deshalb nie ein Creator-Code.
pub fn creator_code(input: &str) -> Option<String> {
    let s = input.trim().to_ascii_uppercase();
    let b = s.as_bytes();
    let inner = |c: &u8| c.is_ascii_uppercase() || c.is_ascii_digit();
    let cape_like = b.len() == 20 && !b.contains(&b'-');
    (!cape_like
        && (5..=20).contains(&b.len())
        && inner(&b[0])
        && inner(&b[b.len() - 1])
        && b.iter().all(|c| inner(c) || *c == b'-'))
    .then_some(s)
}

/// Gehört die Eingabe ins Creator-Einlösen (§35.1)? Sonst ist es ein Umhang-Code (§5.9).
pub fn looks_like_creator_code(input: &str) -> bool {
    creator_code(input).is_some()
}

fn code_arg(input: &str) -> Result<String> {
    creator_code(input).ok_or_else(invalid_code)
}

fn uuid_arg(input: &str) -> Result<String> {
    validate::uuid(input).ok_or_else(|| invalid(crate::msg!("trsOps.invalidPlayerId", "Ungültige Spieler-ID.")))
}

fn tier_arg(tier: &str) -> Result<&str> {
    if TIERS.contains(&tier) { Ok(tier) } else { Err(invalid(crate::msg!("creator.invalidTier", "Unbekannte Creator-Stufe."))) }
}

fn sha256_hex(input: &str) -> bool {
    input.len() == 64 && input.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// IDs aus der API (Warteschlange, Anträge, Vorschauen): Zahl oder `[A-Za-z0-9_-]{1,40}`.
pub(super) fn id_of(value: &Value) -> Option<String> {
    match value {
        Value::Number(n) => n.as_u64().map(|n| n.to_string()),
        Value::String(s) if (1..=40).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') => {
            Some(s.clone())
        }
        _ => None,
    }
}

pub(super) fn id_arg(input: &str) -> Result<&str> {
    id_of(&Value::String(input.to_owned()))
        .map(|_| input)
        .ok_or_else(|| invalid(crate::msg!("creator.invalidId", "Ungültiger Eintrag.")))
}

/// Öffentliche `https`-Adresse (Kanäle, Vorschau-Builds) – nichts anderes geht an den Browser.
pub fn https_url(input: &str) -> Option<String> {
    let t = input.trim();
    if t.len() > 500 || t.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return None;
    }
    let url = url::Url::parse(t).ok()?;
    (url.scheme() == "https" && url.host_str().is_some_and(|h| h.contains('.')) && url.username().is_empty() && url.password().is_none())
        .then(|| url.to_string())
}

pub(super) fn time(t: Option<String>) -> Option<String> {
    t.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

fn reason_text(r: Option<String>) -> Option<String> {
    r.map(|r| chat_text(&r, 500)).map(|r| r.trim().to_owned()).filter(|r| !r.is_empty())
}

pub(super) fn pick(value: Option<String>, allowed: &[&str]) -> Option<String> {
    value.filter(|v| allowed.contains(&v.as_str()))
}

/// Ein Grund für das Team (Ablehnen, Entziehen): eine Zeile, 1–500 Zeichen.
fn reason_arg(input: &str) -> Result<String> {
    validate::plain_text(input, 500, validate::TextField::Reason)
}

/// Mehrzeiliger Text (Wunsch an das Team): Umbrüche erlaubt, 1–`max` Zeichen.
pub(super) fn long_text(input: &str, max: usize) -> Result<String> {
    let text: String = input.chars().filter(|c| *c != '\r').map(|c| if c == '\t' { ' ' } else { c }).collect();
    let text = text.trim();
    if text.is_empty() || text.chars().count() > max {
        return Err(invalid(crate::msg!("creator.textLength", "Text: 1 bis {max} Zeichen.", max = max)));
    }
    if text.split('\n').any(|line| validate::text(line, usize::MAX).chars().count() != line.chars().count()) {
        return Err(invalid(crate::msg!("creator.textInvalidChars", "Der Text enthält ungültige Zeichen.")));
    }
    Ok(text.to_owned())
}

// --- Ansichten ---------------------------------------------------------------------------

/// Fan-Symbol (§35.1): Streifen aus `frames` Bildern à `size`×`size`. `image` = Data-URL (vom Kern geladen).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Symbol {
    #[serde(default, skip_serializing)]
    pub url: String,
    pub sha256: String,
    pub size: u32,
    #[serde(default = "one")]
    pub frames: u32,
    #[serde(default = "default_frame_ms")]
    pub frame_ms: u32,
    #[serde(default, skip_deserializing)]
    pub image: Option<String>,
}

fn one() -> u32 {
    1
}

fn default_frame_ms() -> u32 {
    SYMBOL_FRAME_MS_DEFAULT
}

impl Symbol {
    fn cleaned(self) -> Option<Self> {
        let sha256 = self.sha256.to_ascii_lowercase();
        (sha256_hex(&sha256)
            && (png::SYMBOL_MIN_SIZE..=png::SYMBOL_MAX_SIZE).contains(&self.size)
            && (1..=png::SYMBOL_MAX_FRAMES).contains(&self.frames))
        .then(|| Self {
            sha256,
            frame_ms: if SYMBOL_FRAME_MS.contains(&self.frame_ms) { self.frame_ms } else { SYMBOL_FRAME_MS_DEFAULT },
            image: None,
            ..self
        })
    }

    fn from_value(value: Option<Value>) -> Option<Self> {
        serde_json::from_value::<Self>(value?).ok()?.cleaned()
    }
}

/// Kanal eines Creators (YouTube, Twitch …) – Link nur, wenn er `https` ist.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Channel {
    #[serde(default)]
    pub platform: String,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub followers: Option<u64>,
}

fn clean_channels(list: Option<Vec<Value>>) -> Vec<Channel> {
    list.unwrap_or_default()
        .into_iter()
        .take(8)
        .filter_map(|v| serde_json::from_value::<Channel>(v).ok())
        .filter_map(|c| {
            let platform = one_line(&c.platform, 32);
            let url = c.url.as_deref().and_then(https_url);
            (!platform.is_empty()).then(|| Channel { platform, url, followers: c.followers.map(|f| f.min(10_000_000_000)) })
        })
        .collect()
}

/// Fan-Umhang eines Creators (aktuell/in Prüfung) mit geladener Textur.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FanCape {
    pub cape_id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default, skip_serializing)]
    pub url: String,
    #[serde(default, skip_deserializing)]
    pub texture: Option<String>,
    #[serde(default = "one", skip_deserializing)]
    pub scale: u32,
    #[serde(default = "one")]
    pub frames: u32,
}

impl FanCape {
    fn from_value(value: Option<Value>) -> Option<Self> {
        let c = serde_json::from_value::<Self>(value?).ok()?;
        validate::cape_id(&c.cape_id).then(|| Self {
            name: validate::cape_name(&c.name, &c.cape_id),
            frames: c.frames.clamp(1, png::MAX_UPLOAD_FRAMES),
            texture: None,
            scale: 1,
            ..c
        })
    }
}

/// Öffentliche Karte eines Creators (`CreatorCard`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorCard {
    pub uuid: String,
    pub name: String,
    pub tier: String,
    pub channels: Vec<Channel>,
    pub symbol: Option<Symbol>,
    pub fan_cape: Option<FanCape>,
    pub milestone: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiCard {
    uuid: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    tier: String,
    #[serde(default)]
    channels: Option<Vec<Value>>,
    #[serde(default)]
    symbol: Option<Value>,
    #[serde(default)]
    fan_cape: Option<Value>,
    #[serde(default)]
    milestone: Option<String>,
}

impl CreatorCard {
    fn from_value(value: Option<Value>) -> Option<Self> {
        let c: ApiCard = serde_json::from_value(value?).ok()?;
        Some(Self {
            uuid: validate::uuid(&c.uuid)?,
            name: validate::display_name(&c.name),
            tier: pick(Some(c.tier), &TIERS)?,
            channels: clean_channels(c.channels),
            symbol: Symbol::from_value(c.symbol),
            fan_cape: FanCape::from_value(c.fan_cape),
            milestone: pick(c.milestone, &MILESTONES),
        })
    }
}

/// Wen der Spieler unterstützt, und ab wann ein anderer Creator geht (30-Tage-Regel).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Support {
    pub creator: Option<CreatorCard>,
    pub started_at: Option<String>,
    pub last_creator_uuid: Option<String>,
    pub switch_available_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OwnedFanCape {
    pub cape_id: String,
    pub name: String,
    pub creator: UserRef,
}

/// Letzte Bewerbung auf eine Creator-Stelle (§24.3) – für den Knopf „Selbst Creator werden“.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorApplication {
    pub id: String,
    pub status: String,
    pub created_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorCode {
    pub code: String,
    /// `main` oder `extra`.
    pub kind: String,
    pub uses: u64,
    pub max_uses: Option<u64>,
    pub expires_at: Option<String>,
    pub active: bool,
    pub created_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorLimits {
    /// Zusatz-Codes (`None` = unbegrenzt).
    pub extra_codes: Option<u32>,
    /// Einlösungen je Zusatz-Code (`None` = ohne Obergrenze).
    pub max_uses_per_code: Option<u64>,
    pub symbol_frames: u32,
    /// `None` (kein Fan-Umhang), `standard` (512×256, ein Bild) oder `hd` (bis 1024×512, animiert).
    pub fan_cape: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DailyStat {
    pub day: String,
    pub new_fans: u64,
    pub redemptions: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorStats {
    pub active_fans: u64,
    pub redemptions: u64,
    pub fan_cape_holders: u64,
    pub new_players: u64,
    pub pack_installs: u64,
    /// Aktuelle Fans, die einen Fan-Umhang dieses Creators besitzen (`None` = ältere API).
    pub fan_cape_fans: Option<u64>,
    /// Letzte 30 Tage, älteste zuerst.
    pub daily: Vec<DailyStat>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NextMilestone {
    pub name: String,
    pub at: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Rejection {
    pub reason: Option<String>,
    pub at: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SymbolState {
    pub current: Option<Symbol>,
    pub pending: Option<Symbol>,
    pub rejected: Option<Rejection>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FanCapeState {
    pub current: Option<FanCape>,
    pub pending: Option<FanCape>,
    pub rejected: Option<Rejection>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorRequest {
    pub id: String,
    /// `upgrade` oder `team_cape`.
    pub kind: String,
    /// `open`, `accepted` oder `declined`.
    pub status: String,
    pub created_at: Option<String>,
    pub reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorPackRef {
    pub code: String,
    pub name: String,
    pub installs: u64,
    pub listed: bool,
}

/// Eigene Creator-Ansicht (`CreatorSelf`): Dashboard bzw. – entzogen/gesperrt – nur Zustand und Grund.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorSelf {
    pub uuid: String,
    pub name: String,
    pub tier: String,
    pub state: String,
    pub reason: Option<String>,
    pub since: Option<String>,
    pub channels: Vec<Channel>,
    pub codes: Vec<CreatorCode>,
    pub limits: CreatorLimits,
    pub stats: CreatorStats,
    pub milestone: Option<String>,
    pub next_milestone: Option<NextMilestone>,
    pub symbol: SymbolState,
    pub fan_cape: FanCapeState,
    pub requests: Vec<CreatorRequest>,
    pub packs: Vec<CreatorPackRef>,
    /// Helfer (nur für den Creator selbst).
    pub helpers: Vec<Helper>,
    /// Wer gerade schaut: `Some` = ein Helfer mit diesen Rechten bzw. das Team (`?as=`).
    pub viewer: Option<Viewer>,
    /// Änderungen des Teams im Team-Modus (neueste zuerst, ohne Namen).
    pub team_changes: Vec<TeamChange>,
}

/// Eine Änderung des Teams am Dashboard (§35.9, Team-Modus).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TeamChange {
    /// z. B. `code.create` oder `pet.code.revoke`.
    pub action: String,
    pub at: Option<String>,
}

/// Helfer eines Creators (§35.9).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Helper {
    pub uuid: String,
    pub name: String,
    pub rights: Vec<String>,
    /// `pending` (eingeladen) oder `active`.
    pub status: String,
    pub created_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Viewer {
    pub helper: bool,
    /// Team-Mitglied mit `creators.manage` im Team-Modus: alle Rechte, Änderungen werden protokolliert.
    pub team: bool,
    pub rights: Vec<String>,
}

/// Creator, bei dem der Spieler hilft.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Helping {
    pub creator: CreatorRef,
    pub rights: Vec<String>,
}

/// Offene Einladung, Helfer eines Creators zu werden.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HelperInvite {
    pub creator: UserRef,
    pub rights: Vec<String>,
    pub created_at: Option<String>,
}

/// Was der Creator-Seite des Launchers gehört (`GET /v1/me/creator`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MyCreator {
    pub support: Support,
    pub fan_capes: Vec<OwnedFanCape>,
    pub blocked: Vec<UserRef>,
    pub show_fan_symbols: bool,
    pub creator: Option<CreatorSelf>,
    pub application: Option<CreatorApplication>,
    /// Creator, bei denen der Spieler hilft (§35.9).
    pub helping: Vec<Helping>,
    pub helper_invites: Vec<HelperInvite>,
}

/// Ergebnis von „Code einlösen“.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorRedeem {
    pub creator: CreatorCard,
    pub support_changed: bool,
    pub switch_available_at: Option<String>,
    pub fan_cape_granted: bool,
}

/// Vorschau-Build (§35.3, ab Partner).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewBuild {
    pub id: String,
    /// `launcher` oder `client`.
    pub product: String,
    pub version: String,
    pub notes: Option<String>,
    pub url: String,
    pub sha256: Option<String>,
    pub published_at: Option<String>,
}

/// Gelistetes Creator-Modpack (§35.4).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ListedPack {
    pub code: String,
    pub name: String,
    pub summary: Option<String>,
    pub mc_version: Option<String>,
    pub loader: Option<String>,
    pub installs: u64,
    pub creator: CreatorRef,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorRef {
    pub uuid: String,
    pub name: String,
    pub tier: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ListedPacks {
    pub packs: Vec<ListedPack>,
    pub total: u64,
}

// --- Team -------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueCape {
    pub cape_id: String,
    pub name: String,
    pub frames: u32,
    pub scale: u32,
    pub texture: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueItem {
    pub id: String,
    /// `symbol` oder `fan_cape`.
    pub kind: String,
    pub creator: CreatorRef,
    pub symbol: Option<Symbol>,
    pub cape: Option<QueueCape>,
    pub created_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminCreator {
    pub uuid: String,
    pub name: String,
    pub tier: String,
    pub state: String,
    pub reason: Option<String>,
    pub fans: u64,
    pub main_code: Option<String>,
    pub since: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminRequest {
    pub id: String,
    pub kind: String,
    pub status: String,
    pub created_at: Option<String>,
    pub reason: Option<String>,
    pub creator: Option<CreatorRef>,
    pub channel_url: Option<String>,
    pub followers: Option<u64>,
    pub note: Option<String>,
    pub text: Option<String>,
}

// --- Rohdaten → Ansicht ------------------------------------------------------------------

pub(super) fn u64_of(v: &Value, key: &str) -> u64 {
    v.get(key).and_then(Value::as_u64).unwrap_or(0)
}

pub(super) fn opt_u64(v: &Value, key: &str) -> Option<u64> {
    v.get(key).and_then(Value::as_u64)
}

pub(super) fn str_of(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(str::to_owned)
}

fn creator_ref(v: Option<&Value>) -> Option<CreatorRef> {
    let v = v?;
    Some(CreatorRef {
        uuid: validate::uuid(v.get("uuid")?.as_str()?)?,
        name: validate::display_name(v.get("name").and_then(Value::as_str).unwrap_or_default()),
        tier: pick(str_of(v, "tier"), &TIERS).unwrap_or_else(|| "starter".into()),
    })
}

fn user_ref(v: Option<&Value>) -> Option<UserRef> {
    let v = v?;
    clean_user(UserRef { uuid: str_of(v, "uuid")?, name: str_of(v, "name").unwrap_or_default() })
}

fn rejection(v: Option<&Value>) -> Option<Rejection> {
    let v = v.filter(|v| v.is_object())?;
    Some(Rejection { reason: reason_text(str_of(v, "reason")), at: time(str_of(v, "at")) })
}

fn clean_code(v: &Value) -> Option<CreatorCode> {
    Some(CreatorCode {
        code: creator_code(v.get("code")?.as_str()?)?,
        kind: pick(str_of(v, "kind"), &["main", "extra"])?,
        uses: u64_of(v, "uses"),
        max_uses: opt_u64(v, "maxUses"),
        expires_at: time(str_of(v, "expiresAt")),
        active: v.get("active").and_then(Value::as_bool).unwrap_or(false),
        created_at: time(str_of(v, "createdAt")),
    })
}

fn clean_codes(v: Option<&Value>) -> Vec<CreatorCode> {
    let mut codes: Vec<_> = v.and_then(Value::as_array).into_iter().flatten().take(500).filter_map(clean_code).collect();
    // Haupt-Code zuerst, dann die Zusatz-Codes nach Alter.
    codes.sort_by(|a, b| (b.kind == "main").cmp(&(a.kind == "main")).then_with(|| a.created_at.cmp(&b.created_at)));
    codes
}

fn clean_limits(v: Option<&Value>, tier: &str) -> CreatorLimits {
    let null = Value::Null;
    let v = v.unwrap_or(&null);
    // Rückfall: die Grenzen der Stufe aus §35.1.
    let (extra, uses, frames, cape) = match tier {
        "top" => (None, None, 8, Some("hd")),
        "partner" => (Some(5), Some(1000), 8, Some("standard")),
        _ => (Some(1), Some(100), 1, None),
    };
    let extra_codes = match v.get("extraCodes") {
        Some(Value::Null) => None,
        Some(n) => n.as_u64().map(|n| n.min(1000) as u32).or(extra),
        None => extra,
    };
    let max_uses_per_code = match v.get("maxUsesPerCode") {
        Some(Value::Null) => None,
        Some(n) => n.as_u64().or(uses),
        None => uses,
    };
    let fan_cape = match v.get("fanCape") {
        Some(Value::Bool(false)) | Some(Value::Null) => None,
        Some(Value::String(s)) if s == "standard" || s == "hd" => Some(s.clone()),
        _ => cape.map(str::to_owned),
    };
    CreatorLimits {
        extra_codes,
        max_uses_per_code,
        symbol_frames: opt_u64(v, "symbolFrames").map_or(frames, |f| f.clamp(1, u64::from(png::SYMBOL_MAX_FRAMES)) as u32),
        fan_cape,
    }
}

fn clean_stats(v: Option<&Value>) -> CreatorStats {
    let Some(v) = v else { return CreatorStats::default() };
    let daily = v
        .get("daily")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|d| {
            let day = d.get("day")?.as_str()?;
            let ok = day.len() == 10 && chrono::NaiveDate::parse_from_str(day, "%Y-%m-%d").is_ok();
            ok.then(|| DailyStat { day: day.to_owned(), new_fans: u64_of(d, "newFans"), redemptions: u64_of(d, "redemptions") })
        })
        .collect::<Vec<_>>();
    // Höchstens die letzten 30 Tage, älteste zuerst.
    let mut daily = daily;
    daily.sort_by(|a, b| a.day.cmp(&b.day));
    daily.dedup_by(|a, b| a.day == b.day);
    let skip = daily.len().saturating_sub(30);
    CreatorStats {
        active_fans: u64_of(v, "activeFans"),
        redemptions: u64_of(v, "redemptions"),
        fan_cape_holders: u64_of(v, "fanCapeHolders"),
        new_players: u64_of(v, "newPlayers"),
        pack_installs: u64_of(v, "packInstalls"),
        fan_cape_fans: opt_u64(v, "fanCapeFans"),
        daily: daily.into_iter().skip(skip).collect(),
    }
}

fn clean_request(v: &Value) -> Option<CreatorRequest> {
    Some(CreatorRequest {
        id: id_of(v.get("id")?)?,
        kind: pick(str_of(v, "kind"), &["upgrade", "team_cape"])?,
        status: pick(str_of(v, "status"), &["open", "accepted", "declined"])?,
        created_at: time(str_of(v, "createdAt")),
        reason: reason_text(str_of(v, "reason")),
    })
}

fn clean_pack_ref(v: &Value) -> Option<CreatorPackRef> {
    Some(CreatorPackRef {
        code: super::packs::normalize_code(v.get("code")?.as_str()?)?,
        name: one_line(v.get("name").and_then(Value::as_str).unwrap_or_default(), 64),
        installs: u64_of(v, "installs"),
        listed: v.get("listed").and_then(Value::as_bool).unwrap_or(false),
    })
}

/// Rechte: nur bekannte, ohne Doppelte, in fester Reihenfolge.
fn clean_rights(v: Option<&Value>) -> Vec<String> {
    let list: Vec<&str> = v.and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str).collect();
    HELPER_RIGHTS.iter().filter(|r| list.contains(r)).map(|r| (*r).to_owned()).collect()
}

fn clean_helper(v: &Value) -> Option<Helper> {
    Some(Helper {
        uuid: validate::uuid(v.get("uuid")?.as_str()?)?,
        name: validate::display_name(v.get("name").and_then(Value::as_str).unwrap_or_default()),
        rights: clean_rights(v.get("rights")),
        status: pick(str_of(v, "status"), &["pending", "active"])?,
        created_at: time(str_of(v, "createdAt")),
    })
}

fn clean_self(v: &Value) -> Option<CreatorSelf> {
    let tier = pick(str_of(v, "tier"), &TIERS)?;
    let symbol = v.get("symbol");
    let cape = v.get("fanCape");
    let next = v.get("nextMilestone").and_then(|n| {
        Some(NextMilestone { name: pick(str_of(n, "name"), &MILESTONES)?, at: n.get("at")?.as_u64()? })
    });
    Some(CreatorSelf {
        uuid: validate::uuid(v.get("uuid")?.as_str()?)?,
        name: validate::display_name(v.get("name").and_then(Value::as_str).unwrap_or_default()),
        limits: clean_limits(v.get("limits"), &tier),
        tier,
        state: pick(str_of(v, "state"), &STATES)?,
        reason: reason_text(str_of(v, "reason")),
        since: time(str_of(v, "since")),
        channels: clean_channels(v.get("channels").and_then(|c| serde_json::from_value(c.clone()).ok())),
        codes: clean_codes(v.get("codes")),
        stats: clean_stats(v.get("stats")),
        milestone: pick(str_of(v, "milestone"), &MILESTONES),
        next_milestone: next,
        symbol: SymbolState {
            current: Symbol::from_value(symbol.and_then(|s| s.get("current")).cloned()),
            pending: Symbol::from_value(symbol.and_then(|s| s.get("pending")).cloned()),
            rejected: rejection(symbol.and_then(|s| s.get("rejected"))),
        },
        fan_cape: FanCapeState {
            current: FanCape::from_value(cape.and_then(|s| s.get("current")).cloned()),
            pending: FanCape::from_value(cape.and_then(|s| s.get("pending")).cloned()),
            rejected: rejection(cape.and_then(|s| s.get("rejected"))),
        },
        requests: v.get("requests").and_then(Value::as_array).into_iter().flatten().take(50).filter_map(clean_request).collect(),
        packs: v.get("packs").and_then(Value::as_array).into_iter().flatten().take(200).filter_map(clean_pack_ref).collect(),
        helpers: v.get("helpers").and_then(Value::as_array).into_iter().flatten().take(50).filter_map(clean_helper).collect(),
        viewer: v.get("viewer").filter(|w| w.is_object()).map(|w| {
            let team = w.get("team").and_then(Value::as_bool).unwrap_or(false);
            Viewer {
                // Team-Modus hat Vorrang (auch wenn man zusätzlich eingeschränkter Helfer ist).
                helper: !team && w.get("helper").and_then(Value::as_bool).unwrap_or(false),
                team,
                rights: if team { HELPER_RIGHTS.iter().map(|r| (*r).to_owned()).collect() } else { clean_rights(w.get("rights")) },
            }
        }),
        team_changes: v.get("teamChanges").and_then(Value::as_array).into_iter().flatten().take(20).filter_map(clean_team_change).collect(),
    })
}

/// Aktions-Kennung einer Team-Änderung: `[a-z_]` in Abschnitten mit Punkt, höchstens 40 Zeichen.
pub(super) fn team_action(input: &str) -> Option<String> {
    let ok = (1..=40).contains(&input.len())
        && input.split('.').all(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_lowercase() || b == b'_'));
    ok.then(|| input.to_owned())
}

fn clean_team_change(v: &Value) -> Option<TeamChange> {
    Some(TeamChange { action: team_action(v.get("action")?.as_str()?)?, at: time(str_of(v, "at")) })
}

fn clean_me(v: &Value) -> Option<MyCreator> {
    let s = v.get("support").filter(|s| s.is_object());
    let support = Support {
        creator: CreatorCard::from_value(s.and_then(|s| s.get("creator")).cloned()),
        started_at: time(s.and_then(|s| str_of(s, "startedAt"))),
        last_creator_uuid: s.and_then(|s| str_of(s, "lastCreatorUuid")).and_then(|u| validate::uuid(&u)),
        switch_available_at: time(s.and_then(|s| str_of(s, "switchAvailableAt"))),
    };
    let fan_capes = v
        .get("fanCapes")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .take(200)
        .filter_map(|c| {
            let id = c.get("capeId")?.as_str().filter(|id| validate::cape_id(id))?;
            Some(OwnedFanCape {
                cape_id: id.to_owned(),
                name: validate::cape_name(c.get("name").and_then(Value::as_str).unwrap_or_default(), id),
                creator: user_ref(c.get("creator"))?,
            })
        })
        .collect();
    let blocked = v.get("blocked").and_then(Value::as_array).into_iter().flatten().take(500).filter_map(|b| user_ref(Some(b))).collect();
    let application = v.get("application").filter(|a| a.is_object()).and_then(|a| {
        Some(CreatorApplication {
            id: str_of(a, "id").filter(|id| super::applications::application_id(id))?,
            status: pick(str_of(a, "status"), &super::applications::APPLICATION_STATUSES)?,
            created_at: time(str_of(a, "createdAt")),
        })
    });
    Some(MyCreator {
        support,
        fan_capes,
        blocked,
        show_fan_symbols: v.get("showFanSymbols").and_then(Value::as_bool).unwrap_or(true),
        creator: v.get("creator").filter(|c| c.is_object()).and_then(clean_self),
        application,
        helping: v
            .get("helping")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .take(50)
            .filter_map(|h| Some(Helping { creator: creator_ref(h.get("creator"))?, rights: clean_rights(h.get("rights")) }))
            .collect(),
        helper_invites: v
            .get("helperInvites")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .take(50)
            .filter_map(|i| {
                Some(HelperInvite { creator: user_ref(i.get("creator"))?, rights: clean_rights(i.get("rights")), created_at: time(str_of(i, "createdAt")) })
            })
            .collect(),
    })
}

fn clean_blocked(v: &Value) -> Vec<UserRef> {
    v.get("blocked").and_then(Value::as_array).into_iter().flatten().take(500).filter_map(|b| user_ref(Some(b))).collect()
}

fn clean_preview(v: &Value) -> Option<PreviewBuild> {
    Some(PreviewBuild {
        id: id_of(v.get("id")?)?,
        product: pick(str_of(v, "product"), &["launcher", "client"])?,
        version: Some(one_line(v.get("version")?.as_str()?, 40)).filter(|v| !v.is_empty())?,
        notes: str_of(v, "notes").map(|n| chat_text(&n, 1000)).filter(|n| !n.trim().is_empty()),
        url: https_url(v.get("url")?.as_str()?)?,
        sha256: str_of(v, "sha256").map(|s| s.to_ascii_lowercase()).filter(|s| sha256_hex(s)),
        published_at: time(str_of(v, "publishedAt")),
    })
}

fn clean_listed_pack(v: &Value) -> Option<ListedPack> {
    Some(ListedPack {
        code: super::packs::normalize_code(v.get("code")?.as_str()?)?,
        name: Some(one_line(v.get("name").and_then(Value::as_str).unwrap_or_default(), 64)).filter(|n| !n.is_empty())?,
        summary: str_of(v, "summary").map(|s| one_line(&s, 200)).filter(|s| !s.is_empty()),
        mc_version: str_of(v, "mcVersion").map(|s| one_line(&s, 32)).filter(|s| !s.is_empty()),
        loader: pick(str_of(v, "loader"), &["vanilla", "fabric", "quilt", "forge", "neoforge"]),
        installs: u64_of(v, "installs"),
        creator: creator_ref(v.get("creator"))?,
    })
}

fn clean_admin_creator(v: &Value) -> Option<AdminCreator> {
    Some(AdminCreator {
        uuid: validate::uuid(v.get("uuid")?.as_str()?)?,
        name: validate::display_name(v.get("name").and_then(Value::as_str).unwrap_or_default()),
        tier: pick(str_of(v, "tier"), &TIERS)?,
        state: pick(str_of(v, "state"), &STATES)?,
        reason: reason_text(str_of(v, "reason")),
        fans: u64_of(v, "fans"),
        main_code: str_of(v, "mainCode").and_then(|c| creator_code(&c)),
        since: time(str_of(v, "since")),
    })
}

fn clean_admin_request(v: &Value) -> Option<AdminRequest> {
    let r = clean_request(v)?;
    // Angaben des Antrags stehen in `data` (API §35.6), ältere Antworten hatten sie oben.
    let d = v.get("data").filter(|d| d.is_object()).unwrap_or(v);
    Some(AdminRequest {
        id: r.id,
        kind: r.kind,
        status: r.status,
        created_at: r.created_at,
        reason: r.reason,
        creator: creator_ref(v.get("creator")),
        channel_url: str_of(d, "channelUrl").and_then(|u| https_url(&u)),
        followers: opt_u64(d, "followers"),
        note: str_of(d, "note").map(|n| chat_text(&n, 1000)).filter(|n| !n.trim().is_empty()),
        text: str_of(d, "text").map(|n| chat_text(&n, 2000)).filter(|n| !n.trim().is_empty()),
    })
}

// --- Eingaben ----------------------------------------------------------------------------

/// Neuer Zusatz-Code (Dashboard „Codes“).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NewCreatorCode {
    pub code: String,
    #[serde(default)]
    pub max_uses: Option<u64>,
    #[serde(default)]
    pub expires_at: Option<String>,
}

impl NewCreatorCode {
    pub(crate) fn body(&self) -> Result<Value> {
        let mut body = json!({ "code": code_arg(&self.code)? });
        if let Some(n) = self.max_uses {
            body["maxUses"] = json!(max_uses_arg(n)?);
        }
        if let Some(at) = self.expires_at.as_deref().map(str::trim).filter(|a| !a.is_empty()) {
            body["expiresAt"] = json!(expiry_arg(at)?);
        }
        Ok(body)
    }
}

fn max_uses_arg(n: u64) -> Result<u64> {
    if (1..=10_000_000).contains(&n) {
        Ok(n)
    } else {
        Err(invalid(crate::msg!("creator.invalidMaxUses", "Höchstzahl der Einlösungen: mindestens 1.")))
    }
}

pub(super) fn expiry_arg(at: &str) -> Result<String> {
    let at = validate::iso_datetime(at)?;
    let parsed = chrono::DateTime::parse_from_rfc3339(&at).map_err(|_| invalid_expiry())?;
    if parsed <= chrono::Utc::now() {
        return Err(invalid_expiry());
    }
    Ok(at)
}

fn invalid_expiry() -> Error {
    invalid(crate::msg!("creator.expiryPast", "Das Ablaufdatum muss in der Zukunft liegen."))
}

/// Änderung an einem Code (an/aus, Grenze, Ablauf). `Some(None)` = Grenze/Ablauf entfernen.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CodePatch {
    #[serde(default)]
    pub active: Option<bool>,
    #[serde(default, with = "double_option")]
    pub max_uses: Option<Option<u64>>,
    #[serde(default, with = "double_option")]
    pub expires_at: Option<Option<String>>,
}

mod double_option {
    use serde::{Deserialize, Deserializer};

    pub fn deserialize<'de, T: Deserialize<'de>, D: Deserializer<'de>>(d: D) -> Result<Option<Option<T>>, D::Error> {
        Option::<T>::deserialize(d).map(Some)
    }
}

impl CodePatch {
    pub(crate) fn body(&self) -> Result<Value> {
        let mut body = json!({});
        if let Some(active) = self.active {
            body["active"] = json!(active);
        }
        match self.max_uses {
            Some(Some(n)) => body["maxUses"] = json!(max_uses_arg(n)?),
            Some(None) => body["maxUses"] = Value::Null,
            None => {}
        }
        match self.expires_at.as_ref().map(|a| a.as_deref().map(str::trim).filter(|a| !a.is_empty())) {
            Some(Some(at)) => body["expiresAt"] = json!(expiry_arg(at)?),
            Some(None) => body["expiresAt"] = Value::Null,
            None => {}
        }
        if body.as_object().is_some_and(serde_json::Map::is_empty) {
            return Err(invalid(crate::msg!("creator.nothingToChange", "Es gibt nichts zu ändern.")));
        }
        Ok(body)
    }
}

/// Antrag an das Team: Aufstieg in die nächste Stufe oder Fan-Umhang vom Team.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", rename_all_fields = "camelCase", deny_unknown_fields)]
pub enum NewRequest {
    Upgrade { channel_url: String, followers: u64, #[serde(default)] note: Option<String> },
    TeamCape { text: String },
}

impl NewRequest {
    pub(crate) fn body(&self) -> Result<Value> {
        match self {
            Self::Upgrade { channel_url, followers, note } => {
                let url = https_url(channel_url).ok_or_else(|| {
                    invalid(crate::msg!("creator.invalidChannelUrl", "Bitte den Link zu deinem Kanal angeben (https://…)."))
                })?;
                if *followers > 10_000_000_000 {
                    return Err(invalid(crate::msg!("creator.invalidFollowers", "Ungültige Follower-Zahl.")));
                }
                let mut body = json!({ "kind": "upgrade", "channelUrl": url, "followers": followers });
                if let Some(note) = note.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
                    body["note"] = json!(validate::plain_text(note, 500, validate::TextField::Comment)?);
                }
                Ok(body)
            }
            Self::TeamCape { text } => Ok(json!({ "kind": "team_cape", "text": long_text(text, 1000)? })),
        }
    }
}

/// Rechte für einen Helfer: mindestens eins, nur bekannte.
fn rights_arg(rights: &[String]) -> Result<Vec<String>> {
    if rights.is_empty() || rights.iter().any(|r| !HELPER_RIGHTS.contains(&r.as_str())) {
        return Err(invalid(crate::msg!("creator.invalidRights", "Bitte mindestens ein Recht für den Helfer wählen.")));
    }
    Ok(HELPER_RIGHTS.iter().filter(|r| rights.iter().any(|x| x == *r)).map(|r| (*r).to_owned()).collect())
}

/// Dashboard-Anfrage für einen anderen Creator (Helfer, `?as=<uuid>`, §35.9).
pub(super) fn acting(path: String, as_creator: Option<&str>) -> Result<String> {
    match as_creator.filter(|a| !a.is_empty()) {
        None => Ok(path),
        Some(a) => {
            let uuid = uuid_arg(a)?;
            let sep = if path.contains('?') { '&' } else { '?' };
            Ok(format!("{path}{sep}as={uuid}"))
        }
    }
}

/// Creator hinzufügen (Team, `creators.manage`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NewCreator {
    /// Name oder UUID.
    pub player: String,
    pub tier: String,
    #[serde(default)]
    pub main_code: Option<String>,
}

impl NewCreator {
    pub(crate) fn body(&self) -> Result<Value> {
        let mut body = json!({ "player": validate::target(&self.player)?, "tier": tier_arg(&self.tier)? });
        if let Some(code) = self.main_code.as_deref().map(str::trim).filter(|c| !c.is_empty()) {
            body["mainCode"] = json!(code_arg(code)?);
        }
        Ok(body)
    }
}

/// Creator ändern (Team): Stufe und/oder Haupt-Code.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CreatorPatch {
    #[serde(default)]
    pub tier: Option<String>,
    #[serde(default)]
    pub main_code: Option<String>,
}

impl CreatorPatch {
    pub(crate) fn body(&self) -> Result<Value> {
        let mut body = json!({});
        if let Some(tier) = self.tier.as_deref() {
            body["tier"] = json!(tier_arg(tier)?);
        }
        if let Some(code) = self.main_code.as_deref() {
            body["mainCode"] = json!(code_arg(code)?);
        }
        if body.as_object().is_some_and(serde_json::Map::is_empty) {
            return Err(invalid(crate::msg!("creator.nothingToChange", "Es gibt nichts zu ändern.")));
        }
        Ok(body)
    }
}

/// Status entziehen bzw. sperren (Team).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Revoke {
    pub reason: String,
    #[serde(default)]
    pub ban: bool,
    #[serde(default)]
    pub remove_fan_capes: bool,
}

/// Filter der Creator-Liste (Team).
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CreatorQuery {
    #[serde(default)]
    pub q: Option<String>,
    #[serde(default)]
    pub state: Option<String>,
}

impl CreatorQuery {
    fn path(&self) -> Result<String> {
        let mut path = "/v1/admin/creators".to_owned();
        let mut sep = '?';
        if let Some(q) = self.q.as_deref().map(|q| one_line(q, 32)).filter(|q| !q.is_empty()) {
            path.push_str(&format!("{sep}q={}", encode_query(&q)));
            sep = '&';
        }
        if let Some(state) = self.state.as_deref().filter(|s| !s.is_empty() && *s != "all") {
            if !STATES.contains(&state) {
                return Err(invalid(crate::msg!("reports.invalidFilter", "Ungültiger Filter.")));
            }
            path.push_str(&format!("{sep}state={state}"));
        }
        Ok(path)
    }
}

// --- Echtzeit (§35.7) -------------------------------------------------------------------

#[derive(Deserialize)]
struct LiveData {
    #[serde(default)]
    kind: Option<String>,
    #[serde(default)]
    reason: Option<String>,
    #[serde(default)]
    tier: Option<String>,
    #[serde(default)]
    creator: Option<Value>,
    #[serde(default)]
    milestone: Option<String>,
    #[serde(default)]
    rights: Option<Value>,
    #[serde(default)]
    helper: Option<Value>,
    #[serde(default)]
    action: Option<String>,
}

/// Helfer-Ereignisse und `creator_updated` / `creator_milestone` → gesäubertes Ereignis (kaputt → `None`).
pub(crate) fn decode_live(event: &str, data: &str) -> Option<LiveEvent> {
    let d: LiveData = serde_json::from_str(data).ok()?;
    match event {
        "creator_updated" => Some(LiveEvent::CreatorUpdated {
            kind: pick(d.kind, &UPDATE_KINDS)?,
            reason: reason_text(d.reason),
            tier: pick(d.tier, &TIERS),
            milestone: pick(d.milestone, &MILESTONES),
            helper: user_ref(d.helper.as_ref()),
            action: d.action.as_deref().and_then(team_action),
        }),
        "creator_helper_invite" => Some(LiveEvent::CreatorHelperInvite {
            creator: user_ref(d.creator.as_ref())?,
            rights: clean_rights(d.rights.as_ref()),
        }),
        "creator_helper_removed" => Some(LiveEvent::CreatorHelperRemoved { creator: user_ref(d.creator.as_ref()) }),
        "creator_milestone" => Some(LiveEvent::CreatorMilestone {
            creator: user_ref(d.creator.as_ref())?,
            milestone: pick(d.milestone, &MILESTONES)?,
        }),
        _ => None,
    }
}

// --- Bilder ------------------------------------------------------------------------------

fn data_url(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", STANDARD.encode(bytes))
}

impl TrsApi {
    fn symbol_dir(&self) -> PathBuf {
        self.paths.root().join("cache").join("trs-creator-symbols")
    }

    /// Pfad ab `/v1/…` einer Bild-URL der eigenen API (relativ oder mit vertrauenswürdiger Adresse).
    pub(super) fn own_path<'a>(&self, url: &'a str) -> Option<&'a str> {
        let path = if url.starts_with("/v1/") { url } else { self.trusted_bases().into_iter().find_map(|b| url.strip_prefix(b))? };
        let file = path.split('?').next()?;
        let query_ok = path.split_once('?').is_none_or(|(_, q)| {
            q.len() <= 128 && q.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'=' | b'&' | b'_' | b'-'))
        });
        (path.starts_with("/v1/")
            && file.ends_with(".png")
            && !file.contains("..")
            && file.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'_' | b'-' | b'.'))
            && query_ok)
            .then_some(path)
    }

    /// Lädt ein Bild der eigenen API (mit Token, wenn es nicht öffentlich ist).
    pub(super) async fn own_png(&self, path: &str, token: Option<&str>, max: usize) -> Option<Vec<u8>> {
        let mut request = self.http.get(format!("{}{path}", self.base)).header("Accept", "image/png").timeout(IMAGE_TIMEOUT);
        if let Some(token) = token {
            request = request.bearer_auth(token);
        }
        let response = request.send().await.ok()?;
        if !response.status().is_success() || response.content_length().is_some_and(|l| l > max as u64) {
            return None;
        }
        let bytes = response.bytes().await.ok()?;
        (bytes.len() <= max).then(|| bytes.to_vec())
    }

    /// Symbol-Streifen als Data-URL. `approved` = öffentlich und unveränderlich (Cache nach SHA-256).
    pub(crate) async fn symbol_image(&self, symbol: &Symbol, token: Option<&str>, approved: bool) -> Option<String> {
        let path = self.own_path(&symbol.url).filter(|p| p.contains(&symbol.sha256))?.to_owned();
        let expected = (symbol.size, symbol.size.saturating_mul(symbol.frames));
        let file = self.symbol_dir().join(format!("{}.png", symbol.sha256));
        if approved
            && let Ok(bytes) = tokio::fs::read(&file).await
            && png::size(&bytes) == Some(expected)
        {
            return Some(data_url(&bytes));
        }
        let bytes = self.own_png(&path, if approved { None } else { token }, MAX_SYMBOL_BYTES).await?;
        if png::size(&bytes) != Some(expected) {
            tracing::warn!("Fan-Symbol {} passt nicht zu den Metadaten", symbol.sha256);
            return None;
        }
        if approved && let Err(e) = crate::fsutil::write_atomic(&file, &bytes).await {
            tracing::debug!("Fan-Symbol konnte nicht gecacht werden: {e}");
        }
        Some(data_url(&bytes))
    }

    /// Umhang-Textur ohne bekannte Maße: muss ein Streifen 64k × 32k·n sein (k 1…16, n 1…32).
    pub(crate) async fn loose_cape(&self, cape: &mut FanCape, token: Option<&str>) {
        let Some(path) = self.own_path(&cape.url).filter(|p| p.starts_with("/v1/")).map(str::to_owned) else { return };
        let Some(bytes) = self.own_png(&path, token, MAX_CAPE_BYTES).await else { return };
        let Some((w, h)) = png::size(&bytes) else { return };
        let layout = (1..=png::MAX_SCALE).find_map(|k| {
            let frames = h / (32 * k);
            (w == 64 * k && h.is_multiple_of(32 * k) && (1..=png::MAX_UPLOAD_FRAMES).contains(&frames)).then_some((k, frames))
        });
        if let Some((scale, frames)) = layout {
            cape.scale = scale;
            cape.frames = frames;
            cape.texture = Some(data_url(&bytes));
        }
    }
}

// --- Anfragen ----------------------------------------------------------------------------

impl Launcher {
    pub(super) async fn creator_token(&self) -> Option<String> {
        let account = self.trs_account().await.ok()?;
        self.trs.store.token(&account).await
    }

    /// Symbole und Umhänge einer Karte laden (öffentlich: freigegeben).
    async fn card_images(&self, card: &mut CreatorCard) {
        let CreatorCard { symbol, fan_cape, .. } = card;
        let symbol = async {
            if let Some(s) = symbol.as_mut() {
                s.image = self.trs.symbol_image(s, None, true).await;
            }
        };
        let cape = async {
            if let Some(c) = fan_cape.as_mut() {
                self.trs.loose_cape(c, None).await;
            }
        };
        futures::join!(symbol, cape);
    }

    async fn self_images(&self, me: &mut CreatorSelf) {
        let token = self.creator_token().await;
        let t = token.as_deref();
        let SymbolState { current, pending, .. } = &mut me.symbol;
        let FanCapeState { current: cape_now, pending: cape_next, .. } = &mut me.fan_cape;
        let a = async {
            if let Some(s) = current.as_mut() {
                s.image = self.trs.symbol_image(s, t, true).await;
            }
        };
        let b = async {
            if let Some(s) = pending.as_mut() {
                s.image = self.trs.symbol_image(s, t, false).await;
            }
        };
        let c = async {
            if let Some(c) = cape_now.as_mut() {
                self.trs.loose_cape(c, t).await;
            }
        };
        let d = async {
            if let Some(c) = cape_next.as_mut() {
                self.trs.loose_cape(c, t).await;
            }
        };
        futures::join!(a, b, c, d);
    }

    /// Stand für die Creator-Seite (§35.2).
    pub async fn creator_me(&self) -> Result<MyCreator> {
        let raw: Value = self.trs_get(Req::get("/v1/me/creator")).await?;
        let mut me = clean_me(&raw).ok_or_else(super::bad_response)?;
        let MyCreator { support, creator, .. } = &mut me;
        let card = async {
            if let Some(card) = support.creator.as_mut() {
                self.card_images(card).await;
            }
        };
        let own = async {
            if let Some(own) = creator.as_mut() {
                self.self_images(own).await;
            }
        };
        futures::join!(card, own);
        Ok(me)
    }

    /// Creator-Code einlösen (§35.2): unterstützen und/oder Fan-Umhang freischalten.
    pub async fn creator_redeem(&self, code: &str) -> Result<CreatorRedeem> {
        let code = code_arg(code)?;
        let raw: Value = self.trs_get(Req::post("/v1/me/creator/redeem", json!({ "code": code }))).await?;
        let mut creator = CreatorCard::from_value(raw.get("creator").cloned()).ok_or_else(super::bad_response)?;
        self.card_images(&mut creator).await;
        Ok(CreatorRedeem {
            creator,
            support_changed: raw.get("supportChanged").and_then(Value::as_bool).unwrap_or(false),
            switch_available_at: time(str_of(&raw, "switchAvailableAt")),
            fan_cape_granted: raw.get("fanCapeGranted").and_then(Value::as_bool).unwrap_or(false),
        })
    }

    /// Nicht mehr unterstützen: Abzeichen weg, Fan-Umhänge bleiben.
    pub async fn creator_end_support(&self) -> Result<()> {
        self.trs_do(Req::delete("/v1/me/creator/support")).await
    }

    /// Creator per Code blockieren – liefert die neue Liste.
    pub async fn creator_block(&self, code: &str) -> Result<Vec<UserRef>> {
        let code = code_arg(code)?;
        let raw: Value = self.trs_get(Req::post("/v1/me/creator/blocks", json!({ "code": code }))).await?;
        Ok(clean_blocked(&raw))
    }

    pub async fn creator_unblock(&self, uuid: &str) -> Result<Vec<UserRef>> {
        let uuid = uuid_arg(uuid)?;
        let raw: Value = self.trs_get(Req::delete(format!("/v1/me/creator/blocks/{uuid}"))).await?;
        Ok(clean_blocked(&raw))
    }

    /// „Fan-Symbole anderer Spieler anzeigen“ (Server-Einstellung) – liefert den neuen Stand.
    pub async fn creator_set_show_symbols(&self, show: bool) -> Result<bool> {
        let raw: Value = self.trs_get(Req::patch("/v1/me/creator/settings", json!({ "showFanSymbols": show }))).await?;
        Ok(raw.get("showFanSymbols").and_then(Value::as_bool).unwrap_or(show))
    }

    // --- Dashboard ---

    /// Dashboard – eigenes oder (`as_creator`) das eines Creators, dem man hilft.
    pub async fn creator_dashboard(&self, as_creator: Option<&str>) -> Result<CreatorSelf> {
        let raw: Value = self.trs_get(Req::get(acting("/v1/creator/dashboard".into(), as_creator)?)).await?;
        let mut me = clean_self(&raw).ok_or_else(super::bad_response)?;
        self.self_images(&mut me).await;
        Ok(me)
    }

    pub async fn creator_create_code(&self, new: &NewCreatorCode, as_creator: Option<&str>) -> Result<Vec<CreatorCode>> {
        let raw: Value = self.trs_get(Req::post(acting("/v1/creator/codes".into(), as_creator)?, new.body()?)).await?;
        Ok(clean_codes(raw.get("codes")))
    }

    pub async fn creator_update_code(&self, code: &str, patch: &CodePatch, as_creator: Option<&str>) -> Result<Vec<CreatorCode>> {
        let code = code_arg(code)?;
        let raw: Value = self.trs_get(Req::patch(acting(format!("/v1/creator/codes/{code}"), as_creator)?, patch.body()?)).await?;
        Ok(clean_codes(raw.get("codes")))
    }

    /// Fan-Symbol hochladen (PNG-Streifen, geht in die Prüfung). `max_frames`: 1 (Starter) oder 8.
    pub async fn creator_upload_symbol(&self, bytes: Vec<u8>, frame_ms: Option<u32>, max_frames: u32, as_creator: Option<&str>) -> Result<()> {
        let layout = png::validate_symbol(&bytes, max_frames)?;
        let ms = frame_ms.unwrap_or(SYMBOL_FRAME_MS_DEFAULT);
        if layout.frames > 1 && !SYMBOL_FRAME_MS.contains(&ms) {
            return Err(invalid(crate::msg!("trsPng.frameTime", "Das Bildtempo muss zwischen 50 und 1000 ms je Frame liegen.")));
        }
        let ms = ms.clamp(*SYMBOL_FRAME_MS.start(), *SYMBOL_FRAME_MS.end());
        let path = acting(format!("/v1/creator/symbol?frameMs={ms}"), as_creator)?;
        let req = Req::with(reqwest::Method::PUT, path, super::Body::Png(bytes), super::MAX_JSON_BYTES);
        self.trs_do(req).await
    }

    pub async fn creator_withdraw_symbol(&self, as_creator: Option<&str>) -> Result<()> {
        self.trs_do(Req::delete(acting("/v1/creator/symbol/pending".into(), as_creator)?)).await
    }

    /// Fan-Umhang einreichen (gleiche Prüfung wie eigene Umhänge, dazu ein Name).
    pub async fn creator_upload_fan_cape(
        &self,
        bytes: Vec<u8>,
        frames: u32,
        frame_time_ms: Option<u32>,
        name: &str,
        as_creator: Option<&str>,
    ) -> Result<()> {
        let name = validate::upload_name(name)?;
        let layout = png::validate_upload(&bytes, frames, frame_time_ms)?;
        let mut path = format!("/v1/creator/fan-cape?frames={}&name={}", layout.frames, encode_query(&name));
        if layout.frames > 1
            && let Some(ms) = frame_time_ms
        {
            path.push_str(&format!("&frameTimeMs={ms}"));
        }
        let req = Req::with(reqwest::Method::PUT, acting(path, as_creator)?, super::Body::Png(bytes), super::MAX_JSON_BYTES)
            .timeout(std::time::Duration::from_secs(300));
        self.trs_do(req).await
    }

    pub async fn creator_withdraw_fan_cape(&self, as_creator: Option<&str>) -> Result<()> {
        self.trs_do(Req::delete(acting("/v1/creator/fan-cape/pending".into(), as_creator)?)).await
    }

    /// Antrag ans Team; `as_creator` nur im Team-Modus (Helfer dürfen das nicht).
    pub async fn creator_request(&self, request: &NewRequest, as_creator: Option<&str>) -> Result<Vec<CreatorRequest>> {
        let raw: Value = self.trs_get(Req::post(acting("/v1/creator/requests".into(), as_creator)?, request.body()?)).await?;
        Ok(raw.get("requests").and_then(Value::as_array).into_iter().flatten().take(50).filter_map(clean_request).collect())
    }

    /// Vorschau-Builds (ab Partner), neueste zuerst.
    pub async fn creator_previews(&self) -> Result<Vec<PreviewBuild>> {
        let raw: Value = self.trs_get(Req::get("/v1/creator/previews")).await?;
        let mut list: Vec<_> = raw.get("previews").and_then(Value::as_array).into_iter().flatten().take(100).filter_map(clean_preview).collect();
        list.sort_by(|a, b| b.published_at.cmp(&a.published_at));
        Ok(list)
    }

    /// Eigenes geteiltes Pack als Creator-Pack listen bzw. zurückziehen.
    pub async fn creator_set_pack_listed(&self, code: &str, listed: bool, as_creator: Option<&str>) -> Result<()> {
        let code = super::packs::normalize_code(code).ok_or_else(|| {
            invalid(crate::msg!("packShare.invalidCode", "Das ist kein gültiger Modpack-Code (Form: TRS-XXXX-XXXX)."))
        })?;
        self.trs_do(Req::put(acting(format!("/v1/creator/packs/{code}"), as_creator)?, json!({ "listed": listed }))).await
    }

    // --- Helfer (§35.9) ---

    /// Freund als Helfer einladen.
    pub async fn creator_invite_helper(&self, uuid: &str, rights: &[String]) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::post("/v1/creator/helpers", json!({ "uuid": uuid, "rights": rights_arg(rights)? }))).await
    }

    /// Rechte eines Helfers ändern – der Creator selbst oder das Team im Team-Modus (`as_creator`).
    pub async fn creator_update_helper(&self, uuid: &str, rights: &[String], as_creator: Option<&str>) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::patch(acting(format!("/v1/creator/helpers/{uuid}"), as_creator)?, json!({ "rights": rights_arg(rights)? }))).await
    }

    /// Helfer entfernen – der Creator selbst oder das Team im Team-Modus (`as_creator`).
    pub async fn creator_remove_helper(&self, uuid: &str, as_creator: Option<&str>) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::delete(acting(format!("/v1/creator/helpers/{uuid}"), as_creator)?)).await
    }

    /// Einladung als Helfer annehmen oder ablehnen.
    pub async fn creator_answer_helper_invite(&self, creator: &str, accept: bool) -> Result<()> {
        let creator = uuid_arg(creator)?;
        self.trs_do(Req::post(format!("/v1/me/creator/helper-invites/{creator}"), json!({ "accept": accept }))).await
    }

    /// Nicht mehr helfen.
    pub async fn creator_leave_helping(&self, creator: &str) -> Result<()> {
        let creator = uuid_arg(creator)?;
        self.trs_do(Req::delete(format!("/v1/me/creator/helping/{creator}"))).await
    }

    /// Gelistete Creator-Modpacks (§35.4), nach Installationen sortiert.
    pub async fn creator_packs(&self, q: Option<&str>, offset: u32, limit: u32) -> Result<ListedPacks> {
        let mut path = format!("/v1/creators/packs?offset={}&limit={}", offset.min(10_000), limit.clamp(1, 50));
        if let Some(q) = q.map(|q| one_line(q, 64)).filter(|q| !q.is_empty()) {
            path.push_str(&format!("&q={}", encode_query(&q)));
        }
        let raw: Value = self.trs_get(Req::get(path)).await?;
        let packs: Vec<_> = raw.get("packs").and_then(Value::as_array).into_iter().flatten().take(50).filter_map(clean_listed_pack).collect();
        Ok(ListedPacks { total: u64_of(&raw, "total").max(packs.len() as u64), packs })
    }

    // --- Team ---

    pub async fn admin_creator_queue(&self) -> Result<Vec<QueueItem>> {
        let raw: Value = self.trs_get(Req::get("/v1/admin/creators/queue")).await?;
        let token = self.creator_token().await;
        let items: Vec<QueueItem> = raw
            .get("items")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .take(200)
            .filter_map(|v| {
                let kind = pick(str_of(v, "kind"), &["symbol", "fan_cape"])?;
                let cape = v.get("cape").and_then(|c| {
                    let id = c.get("capeId")?.as_str()?;
                    validate::cape_id(id).then(|| QueueCape {
                        cape_id: id.to_owned(),
                        name: validate::cape_name(c.get("name").and_then(Value::as_str).unwrap_or_default(), id),
                        frames: opt_u64(c, "frames").map_or(1, |f| f.clamp(1, 32) as u32),
                        scale: 1,
                        texture: str_of(c, "url"),
                    })
                });
                Some(QueueItem {
                    id: id_of(v.get("id")?)?,
                    creator: creator_ref(v.get("creator"))?,
                    symbol: Symbol::from_value(v.get("symbol").cloned()),
                    cape,
                    created_at: time(str_of(v, "createdAt")),
                    kind,
                })
            })
            .collect();
        let jobs = items.into_iter().map(|mut item| {
            let token = token.clone();
            async move {
                if let Some(s) = item.symbol.as_mut() {
                    s.image = self.trs.symbol_image(s, token.as_deref(), false).await;
                }
                if let Some(c) = item.cape.as_mut() {
                    // `texture` trägt bis hierher die URL.
                    let mut cape = FanCape { cape_id: c.cape_id.clone(), name: c.name.clone(), url: c.texture.take().unwrap_or_default(), texture: None, scale: 1, frames: c.frames };
                    self.trs.loose_cape(&mut cape, token.as_deref()).await;
                    c.texture = cape.texture;
                    c.scale = cape.scale;
                    c.frames = cape.frames;
                }
                item
            }
        });
        Ok(futures::future::join_all(jobs).await)
    }

    pub async fn admin_creator_decide(&self, id: &str, approve: bool, reason: Option<&str>) -> Result<()> {
        let id = id_arg(id)?;
        if approve {
            return self.trs_do(Req::post_empty(format!("/v1/admin/creators/queue/{id}/approve"))).await;
        }
        let reason = reason_arg(reason.unwrap_or_default())?;
        self.trs_do(Req::post(format!("/v1/admin/creators/queue/{id}/reject"), json!({ "reason": reason }))).await
    }

    pub async fn admin_creators(&self, query: &CreatorQuery) -> Result<Vec<AdminCreator>> {
        let raw: Value = self.trs_get(Req::get(query.path()?)).await?;
        Ok(raw.get("creators").and_then(Value::as_array).into_iter().flatten().take(1000).filter_map(clean_admin_creator).collect())
    }

    pub async fn admin_creator_add(&self, new: &NewCreator) -> Result<()> {
        self.trs_do(Req::post("/v1/admin/creators", new.body()?)).await
    }

    pub async fn admin_creator_update(&self, uuid: &str, patch: &CreatorPatch) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::patch(format!("/v1/admin/creators/{uuid}"), patch.body()?)).await
    }

    pub async fn admin_creator_revoke(&self, uuid: &str, revoke: &Revoke) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        let body = json!({ "reason": reason_arg(&revoke.reason)?, "ban": revoke.ban, "removeFanCapes": revoke.remove_fan_capes });
        self.trs_do(Req::post(format!("/v1/admin/creators/{uuid}/revoke"), body)).await
    }

    pub async fn admin_creator_restore(&self, uuid: &str) -> Result<()> {
        let uuid = uuid_arg(uuid)?;
        self.trs_do(Req::post_empty(format!("/v1/admin/creators/{uuid}/restore"))).await
    }

    pub async fn admin_creator_requests(&self) -> Result<Vec<AdminRequest>> {
        let raw: Value = self.trs_get(Req::get("/v1/admin/creators/requests")).await?;
        Ok(raw.get("requests").and_then(Value::as_array).into_iter().flatten().take(500).filter_map(clean_admin_request).collect())
    }

    pub async fn admin_creator_answer(&self, id: &str, accept: bool, reason: Option<&str>) -> Result<()> {
        let id = id_arg(id)?;
        let mut body = json!({ "status": if accept { "accepted" } else { "declined" } });
        if let Some(r) = reason.map(str::trim).filter(|r| !r.is_empty()) {
            body["reason"] = json!(reason_arg(r)?);
        }
        self.trs_do(Req::post(format!("/v1/admin/creators/requests/{id}"), body)).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creator_codes_follow_the_pattern() {
        assert_eq!(creator_code(" trs-pixel ").as_deref(), Some("TRS-PIXEL"));
        assert_eq!(creator_code("PIXEL").as_deref(), Some("PIXEL"));
        assert!(creator_code("ABCD").is_none(), "zu kurz");
        assert!(creator_code(&"A".repeat(21)).is_none(), "zu lang");
        assert!(creator_code("-PIXEL").is_none() && creator_code("PIXEL-").is_none());
        assert!(creator_code("PIX EL").is_none() && creator_code("PIXÄL").is_none());
        assert!(looks_like_creator_code("trs-pixel"));
        assert!(looks_like_creator_code("PIXELMAUS"), "kein Präfix nötig");
        assert!(!looks_like_creator_code("7K3QFM2XPA9RTVBC4HJN"), "20 Zeichen ohne Bindestrich = Umhang-Code");
        assert!(!looks_like_creator_code("7K3QF-M2XPA-9RTVB-C4HJN"), "Umhang-Codes sind länger");
    }

    #[test]
    fn urls_and_ids_are_checked() {
        assert_eq!(https_url("https://youtube.com/@pixelmaus").as_deref(), Some("https://youtube.com/@pixelmaus"));
        assert!(https_url("http://youtube.com").is_none());
        assert!(https_url("javascript:alert(1)").is_none());
        assert!(https_url("https://user:pw@evil.example/x").is_none());
        assert!(https_url("https://localhost/x").is_none());
        assert_eq!(id_of(&json!(12)).as_deref(), Some("12"));
        assert_eq!(id_of(&json!("q_1-a")).as_deref(), Some("q_1-a"));
        assert!(id_of(&json!("../x")).is_none() && id_of(&json!(-1)).is_none());
    }

    fn symbol(sha: &str) -> Value {
        json!({ "url": format!("/v1/creator-symbols/{sha}.png"), "size": 16, "frames": 2, "frameMs": 5, "sha256": sha })
    }

    #[test]
    fn me_is_cleaned() {
        let sha = "ab".repeat(32);
        let raw = json!({
            "support": { "creator": { "uuid": "B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0", "name": "Pixel\u{202E}maus", "tier": "partner", "skin": null,
                "channels": [{ "platform": "YouTube", "url": "javascript:x", "followers": 18400 }, { "platform": "", "url": null }],
                "symbol": symbol(&sha), "fanCape": { "capeId": "u0123456789abcdef0123", "name": "Pixels Drache", "url": "/v1/capes/u0123456789abcdef0123.png" },
                "milestone": "platinum" },
                "startedAt": "2026-10-01T00:00:00.000Z", "lastCreatorUuid": "nope", "switchAvailableAt": "2026-10-31T00:00:00.000Z" },
            "fanCapes": [{ "capeId": "u0123456789abcdef0123", "name": "Pixels Drache", "creator": { "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Pixelmaus" } },
                { "capeId": "../x", "name": "x", "creator": { "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "x" } }],
            "blocked": [{ "uuid": "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0", "name": "Spammer" }, { "uuid": "x", "name": "y" }],
            "creator": null,
            "application": { "id": "a0123456789abcdef", "status": "review", "createdAt": "2026-10-14T10:00:00.000Z" }
        });
        let me = clean_me(&raw).unwrap();
        let card = me.support.creator.as_ref().unwrap();
        assert_eq!(card.uuid, "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0");
        assert_eq!(card.name, "Pixelmaus");
        assert_eq!(card.channels.len(), 1);
        assert!(card.channels[0].url.is_none(), "nur https-Links");
        let s = card.symbol.as_ref().unwrap();
        assert_eq!((s.size, s.frames, s.frame_ms), (16, 2, SYMBOL_FRAME_MS_DEFAULT));
        assert!(card.milestone.is_none(), "unbekannter Meilenstein fällt weg");
        assert!(me.support.last_creator_uuid.is_none());
        assert_eq!(me.fan_capes.len(), 1);
        assert_eq!(me.blocked.len(), 1);
        assert!(me.show_fan_symbols, "Standard: an");
        assert_eq!(me.application.as_ref().unwrap().status, "review");
        let view = serde_json::to_value(&me).unwrap();
        assert!(view["support"]["creator"]["symbol"].get("url").is_none(), "URL bleibt im Kern");
        assert_eq!(view["support"]["creator"]["fanCape"]["capeId"], "u0123456789abcdef0123");
    }

    #[test]
    fn dashboard_is_cleaned_with_tier_limits() {
        let days: Vec<_> = (1..=35).map(|d| json!({ "day": format!("2026-{:02}-{:02}", 9 + (d > 30) as u32, if d > 30 { d - 30 } else { d }), "newFans": d, "redemptions": 1 })).collect();
        let raw = json!({
            "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Pixelmaus", "tier": "partner", "state": "active", "reason": null, "since": "2026-10-03T00:00:00.000Z",
            "codes": [
                { "code": "pixel-yt", "kind": "extra", "uses": 1000, "maxUses": 1000, "expiresAt": null, "active": false, "createdAt": "2026-10-05T00:00:00.000Z" },
                { "code": "TRS-PIXEL", "kind": "main", "uses": 1284, "maxUses": null, "active": true, "createdAt": "2026-10-03T00:00:00.000Z" },
                { "code": "x", "kind": "extra" }
            ],
            "limits": { "extraCodes": 5, "maxUsesPerCode": 1000, "symbolFrames": 8, "fanCape": "standard" },
            "stats": { "activeFans": 842, "redemptions": 1596, "fanCapeHolders": 1103, "newPlayers": 213, "packInstalls": 7, "daily": days },
            "milestone": "bronze", "nextMilestone": { "name": "silver", "at": 500 },
            "symbol": { "current": null, "pending": symbol(&"cd".repeat(32)), "rejected": { "reason": "Fremdes\u{202E} Logo", "at": "2026-10-10T00:00:00.000Z" } },
            "fanCape": { "current": null, "pending": null, "rejected": null },
            "requests": [{ "id": 3, "kind": "upgrade", "status": "open", "createdAt": "2026-10-15T00:00:00.000Z" }, { "id": 4, "kind": "nope", "status": "open" }],
            "packs": [{ "code": "trs-ab12-cd34", "name": "Redstone Pack", "installs": 7, "listed": true }]
        });
        let me = clean_self(&raw).unwrap();
        assert_eq!(me.codes.iter().map(|c| c.code.as_str()).collect::<Vec<_>>(), ["TRS-PIXEL", "PIXEL-YT"], "Haupt-Code zuerst");
        assert_eq!(me.limits, CreatorLimits { extra_codes: Some(5), max_uses_per_code: Some(1000), symbol_frames: 8, fan_cape: Some("standard".into()) });
        assert_eq!(me.stats.daily.len(), 30, "höchstens 30 Tage");
        assert_eq!(me.stats.daily[0].day, "2026-09-06", "älteste zuerst");
        assert_eq!(me.symbol.rejected.as_ref().unwrap().reason.as_deref(), Some("Fremdes Logo"));
        assert!(me.symbol.pending.is_some());
        assert_eq!(me.requests.len(), 1);
        assert_eq!(me.packs[0].code, "TRS-AB12-CD34");

        // Top ohne Grenzen (null = unbegrenzt), Starter ohne Angaben → Grenzen der Stufe.
        let top = clean_limits(Some(&json!({ "extraCodes": null, "maxUsesPerCode": null, "symbolFrames": 8, "fanCape": "hd" })), "top");
        assert_eq!((top.extra_codes, top.max_uses_per_code, top.fan_cape.as_deref()), (None, None, Some("hd")));
        let starter = clean_limits(None, "starter");
        assert_eq!((starter.extra_codes, starter.max_uses_per_code, starter.symbol_frames, starter.fan_cape), (Some(1), Some(100), 1, None));
        assert_eq!(clean_limits(Some(&json!({ "fanCape": false })), "partner").fan_cape, None);
    }

    #[test]
    fn inputs_are_checked() {
        let new = NewCreatorCode { code: "pixel-stream".into(), max_uses: Some(100), expires_at: Some("2999-10-31T23:59:59+01:00".into()) };
        assert_eq!(new.body().unwrap(), json!({ "code": "PIXEL-STREAM", "maxUses": 100, "expiresAt": "2999-10-31T22:59:59.000Z" }));
        assert!(NewCreatorCode { code: "x".into(), ..Default::default() }.body().is_err());
        assert!(NewCreatorCode { code: "PIXEL-1".into(), max_uses: Some(0), ..Default::default() }.body().is_err());
        assert!(NewCreatorCode { code: "PIXEL-1".into(), expires_at: Some("2001-01-01T00:00:00Z".into()), ..Default::default() }.body().is_err());

        let off: CodePatch = serde_json::from_value(json!({ "active": false })).unwrap();
        assert_eq!(off.body().unwrap(), json!({ "active": false }));
        let unlimited: CodePatch = serde_json::from_value(json!({ "maxUses": null })).unwrap();
        assert_eq!(unlimited.body().unwrap(), json!({ "maxUses": null }));
        assert!(CodePatch::default().body().is_err());

        let up: NewRequest = serde_json::from_value(json!({ "kind": "upgrade", "channelUrl": "https://youtube.com/@pixel", "followers": 18400, "note": " Video " })).unwrap();
        assert_eq!(up.body().unwrap(), json!({ "kind": "upgrade", "channelUrl": "https://youtube.com/@pixel", "followers": 18400, "note": "Video" }));
        let bad: NewRequest = serde_json::from_value(json!({ "kind": "upgrade", "channelUrl": "ftp://x", "followers": 1 })).unwrap();
        assert_eq!(bad.body().unwrap_err().message_code(), "creator.invalidChannelUrl");
        let cape: NewRequest = serde_json::from_value(json!({ "kind": "team_cape", "text": "lila Drache\r\nmit Logo" })).unwrap();
        assert_eq!(cape.body().unwrap()["text"], "lila Drache\nmit Logo");
        assert!(serde_json::from_value::<NewRequest>(json!({ "kind": "nuke" })).is_err());

        assert_eq!(NewCreator { player: "Pixelmaus".into(), tier: "starter".into(), main_code: Some("trs-pixel".into()) }.body().unwrap()["mainCode"], "TRS-PIXEL");
        assert!(NewCreator { player: "Pixelmaus".into(), tier: "gold".into(), main_code: None }.body().is_err());
        assert!(CreatorPatch::default().body().is_err());
        assert_eq!(CreatorPatch { tier: Some("top".into()), main_code: None }.body().unwrap(), json!({ "tier": "top" }));
        assert_eq!(CreatorQuery { q: Some("Pix el".into()), state: Some("banned".into()) }.path().unwrap(), "/v1/admin/creators?q=Pix%20el&state=banned");
        assert_eq!(CreatorQuery::default().path().unwrap(), "/v1/admin/creators");
        assert!(CreatorQuery { q: None, state: Some("x".into()) }.path().is_err());
    }

    #[test]
    fn live_events_are_cleaned() {
        let e = decode_live("creator_updated", &json!({ "type": "creator_updated", "kind": "symbol_rejected", "reason": "Fremdes\u{202E} Logo" }).to_string()).unwrap();
        assert_eq!(e, LiveEvent::CreatorUpdated { kind: "symbol_rejected".into(), reason: Some("Fremdes Logo".into()), tier: None, milestone: None, helper: None, action: None });
        let team = decode_live("creator_updated", r#"{"kind":"team_changed","action":"pet.code.create"}"#).unwrap();
        assert!(matches!(team, LiveEvent::CreatorUpdated { ref kind, action: Some(ref a), .. } if kind == "team_changed" && a == "pet.code.create"));
        let bad = decode_live("creator_updated", r#"{"kind":"team_changed","action":"../Drop"}"#).unwrap();
        assert!(matches!(bad, LiveEvent::CreatorUpdated { action: None, .. }), "unbekannte Form fällt weg");
        let t = decode_live("creator_updated", r#"{"kind":"tier_changed","tier":"top"}"#).unwrap();
        assert!(matches!(t, LiveEvent::CreatorUpdated { tier: Some(ref t), .. } if t == "top"));
        assert!(decode_live("creator_updated", r#"{"kind":"nuke"}"#).is_none());
        let m = decode_live("creator_milestone", r#"{"creator":{"uuid":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","name":"Pixelmaus"},"milestone":"gold"}"#).unwrap();
        assert_eq!(serde_json::to_value(&m).unwrap(), json!({ "type": "creator_milestone", "creator": { "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Pixelmaus" }, "milestone": "gold" }));
        assert!(decode_live("creator_milestone", r#"{"creator":{"uuid":"x","name":"y"},"milestone":"gold"}"#).is_none());
        let invite = decode_live(
            "creator_helper_invite",
            r#"{"creator":{"uuid":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","name":"Pixelmaus"},"rights":["packs","stats","root"]}"#,
        )
        .unwrap();
        assert!(matches!(invite, LiveEvent::CreatorHelperInvite { ref rights, .. } if rights == &["stats", "packs"]));
        let joined =
            decode_live("creator_updated", r#"{"kind":"helper_joined","helper":{"uuid":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0","name":"Cutter"}}"#).unwrap();
        assert!(matches!(joined, LiveEvent::CreatorUpdated { helper: Some(ref h), .. } if h.name == "Cutter"));
        assert!(matches!(decode_live("creator_helper_removed", "{}").unwrap(), LiveEvent::CreatorHelperRemoved { creator: None }));
    }

    #[test]
    fn helpers_are_cleaned_and_checked() {
        let me = clean_me(&json!({
            "support": null, "fanCapes": [], "blocked": [], "showFanSymbols": true, "creator": null, "application": null,
            "helping": [{ "creator": { "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", "name": "Pixelmaus", "tier": "top" }, "rights": ["codes", "codes", "admin"] }],
            "helperInvites": [{ "creator": { "uuid": "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0", "name": "Redfox" }, "rights": ["uploads"], "createdAt": "2026-10-16T10:00:00.000Z" },
                { "creator": { "uuid": "x", "name": "y" }, "rights": [] }]
        }))
        .unwrap();
        assert_eq!(me.helping[0].rights, ["codes"]);
        assert_eq!(me.helper_invites.len(), 1);
        assert_eq!(rights_arg(&["packs".into(), "stats".into()]).unwrap(), ["stats", "packs"]);
        assert!(rights_arg(&[]).is_err() && rights_arg(&["tier".into()]).is_err());
        assert_eq!(acting("/v1/creator/dashboard".into(), None).unwrap(), "/v1/creator/dashboard");
        assert_eq!(
            acting("/v1/creator/symbol?frameMs=150".into(), Some("B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0")).unwrap(),
            "/v1/creator/symbol?frameMs=150&as=b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"
        );
        assert!(acting("/v1/creator/dashboard".into(), Some("../x")).is_err());
    }
}
