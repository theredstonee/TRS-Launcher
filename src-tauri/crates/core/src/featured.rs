//! Empfohlene Modpacks für die Startseite (Karussell): das TRS-Team wählt sie in der
//! Team-Verwaltung (Modrinth- oder CurseForge-Link), die Liste liegt auf der TRS API
//! (`GET /v1/site/featured-packs`, öffentlich, ohne Token). So ändern sie sich ohne Launcher-Update.
//!
//! Wie bei den TRS-News: nur mit eingeschalteten TRS-Diensten, kurze Lebensdauer im Cache,
//! offline bleibt der alte Stand. Jeder Eintrag wird geprüft – Bilder nur von der eigenen
//! TRS-Adresse, Links nur auf modrinth.com bzw. www.curseforge.com, Texte als reiner Text.
//! Kaputte Einträge fallen still weg.

use std::path::PathBuf;

use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::paths::Paths;
use crate::trs_api::validate;
use crate::{Error, Launcher, Result, fsutil};

/// Mehr zeigt die Startseite nicht (die API liefert schon höchstens so viele).
pub const MAX_PUBLIC: usize = 20;
/// Team-Liste: aktive, geplante und abgelaufene Einträge zusammen.
pub const MAX_ADMIN: usize = 100;
/// Längen der Team-Texte (wie die API).
pub const MAX_TEXT_TITLE: usize = 60;
pub const MAX_TEXT_BODY: usize = 200;
/// So lange gilt der Cache als frisch – Änderungen des Teams kommen spätestens danach an.
const TTL_MINUTES: i64 = 10;
/// Größte Antwort der öffentlichen Liste.
const MAX_FEED_BYTES: u64 = 1024 * 1024;
const MAX_TITLE_CHARS: usize = 120;
const MAX_SUMMARY_CHARS: usize = 320;
/// Pfad der Pack-Bilder auf der TRS API.
const IMAGE_PATH: &str = "/v1/site/featured-packs/";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum FeaturedSource {
    Modrinth,
    Curseforge,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum FeaturedBadge {
    Recommended,
    New,
    Event,
}

impl FeaturedBadge {
    fn parse(value: &str) -> Option<Self> {
        match value {
            "recommended" => Some(Self::Recommended),
            "new" => Some(Self::New),
            "event" => Some(Self::Event),
            _ => None,
        }
    }
}

/// Eigener Titel und Text des Teams in einer Sprache (`None` = Text von Modrinth/CurseForge).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FeaturedText {
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub text: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FeaturedTexts {
    #[serde(default)]
    pub en: FeaturedText,
    #[serde(default)]
    pub de: FeaturedText,
}

/// Ein empfohlenes Modpack, so wie es das Webview bekommt.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FeaturedPack {
    pub id: String,
    pub source: FeaturedSource,
    pub project_id: String,
    pub slug: String,
    /// Projektseite auf Modrinth bzw. CurseForge.
    pub url: String,
    pub title: String,
    pub summary: String,
    pub author: Option<String>,
    pub downloads: u64,
    pub loaders: Vec<String>,
    /// Neueste zuerst.
    pub game_versions: Vec<String>,
    pub icon_url: Option<String>,
    pub banner_url: Option<String>,
    pub badge: Option<FeaturedBadge>,
    pub texts: FeaturedTexts,
    pub pinned: bool,
    pub starts_at: Option<DateTime<Utc>>,
    pub ends_at: Option<DateTime<Utc>>,
    pub updated_at: Option<DateTime<Utc>>,
}

/// Eintrag in der Team-Verwaltung (`GET /v1/admin/featured-packs`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminFeaturedPack {
    #[serde(flatten)]
    pub pack: FeaturedPack,
    pub active: bool,
    pub created_by: String,
    pub created_at: Option<DateTime<Utc>>,
    pub refreshed_at: Option<DateTime<Utc>>,
}

// --- Prüfen --------------------------------------------------------------------------

/// Rohform aus der API – jedes Feld nachsichtig, geprüft wird in [`clean_pack`].
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct RawPack {
    id: Value,
    source: Value,
    project_id: Value,
    slug: Value,
    url: Value,
    title: Value,
    summary: Value,
    author: Value,
    downloads: Value,
    loaders: Value,
    game_versions: Value,
    icon_url: Value,
    banner_url: Value,
    badge: Value,
    texts: Value,
    pinned: Value,
    starts_at: Value,
    ends_at: Value,
    updated_at: Value,
    active: Value,
    created_by: Value,
    created_at: Value,
    refreshed_at: Value,
}

fn as_str(v: &Value) -> Option<&str> {
    v.as_str()
}

/// `^[A-Za-z0-9_-]{1,64}$`
fn is_pack_id(id: &str) -> bool {
    (1..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
}

/// Modrinth: ID oder Slug (`[A-Za-z0-9_-]`, bis 64), CurseForge: Zahl ohne führende Null.
pub fn is_project_id(source: FeaturedSource, id: &str) -> bool {
    match source {
        FeaturedSource::Modrinth => is_pack_id(id),
        FeaturedSource::Curseforge => {
            (1..=12).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_digit()) && !id.starts_with('0')
        }
    }
}

fn project_id(source: FeaturedSource, v: &Value) -> Option<String> {
    let id = match v {
        Value::String(s) => s.trim().to_owned(),
        Value::Number(n) => n.as_u64()?.to_string(),
        _ => return None,
    };
    is_project_id(source, &id).then_some(id)
}

fn is_slug(slug: &str) -> bool {
    (1..=100).contains(&slug.len())
        && slug.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'-' | b'_'))
}

/// Host einer `https://`-Adresse, ohne Benutzer, Port oder Pfad – `None`, wenn die Adresse nicht sauber ist.
fn https_host(url: &str) -> Option<&str> {
    if !crate::modrinth::is_safe_external_url(url) {
        return None;
    }
    let host = url.strip_prefix("https://")?.split(['/', '?', '#']).next()?;
    (!host.contains(':')).then_some(host)
}

/// Link zur Projektseite: nur modrinth.com bzw. (www.)curseforge.com, passend zur Quelle.
pub fn is_project_link(source: FeaturedSource, url: &str) -> bool {
    let Some(host) = https_host(url) else { return false };
    match source {
        FeaturedSource::Modrinth => matches!(host, "modrinth.com" | "www.modrinth.com"),
        FeaturedSource::Curseforge => matches!(host, "www.curseforge.com" | "curseforge.com"),
    }
}

/// Bild eines Packs: nur `/v1/site/featured-packs/…` auf einer der eigenen TRS-Adressen
/// (relative Pfade bekommen die eingestellte Adresse vorn dran).
pub fn image_url(url: &str, bases: &[&str]) -> Option<String> {
    if url.len() > 1024 || url.contains("..") {
        return None;
    }
    let path_ok = |path: &str| {
        path.strip_prefix(IMAGE_PATH).is_some_and(|rest| {
            !rest.is_empty()
                && rest.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'_' | b'-' | b'.' | b'?' | b'=' | b'&' | b'%'))
        })
    };
    if url.starts_with('/') {
        let base = bases.first()?;
        return path_ok(url).then(|| format!("{}{url}", base.trim_end_matches('/')));
    }
    bases
        .iter()
        .any(|base| url.strip_prefix(base.trim_end_matches('/')).is_some_and(path_ok))
        .then(|| url.to_owned())
}

/// Eine Zeile Text: Steuer- und Richtungszeichen raus, Leerraum zusammengefasst, gekürzt (mit „…“).
/// Die Texte sind schon reiner Text (die API speichert keinen HTML-Code); das Webview setzt sie nur als Text ein.
pub(crate) fn clean_text(input: &str, max: usize) -> String {
    let spaced: String = input.chars().take(max * 4 + 16).map(|c| if c.is_whitespace() { ' ' } else { c }).collect();
    let collapsed = validate::text(&spaced, usize::MAX).split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.chars().count() <= max {
        return collapsed;
    }
    let cut: String = collapsed.chars().take(max).collect();
    let end = cut.rfind(' ').filter(|&i| i > cut.len() / 2).unwrap_or(cut.len());
    format!("{}…", cut[..end].trim_end_matches([',', '.', ';', ':', ' ']))
}

/// Eine Zeile Text aus der API; leer = `None`.
fn line(v: &Value, max: usize) -> Option<String> {
    let text = clean_text(as_str(v)?, max);
    (!text.is_empty()).then_some(text)
}

fn tags(v: &Value, max: usize, ok: impl Fn(&str) -> bool) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for item in v.as_array().map(Vec::as_slice).unwrap_or_default() {
        let Some(s) = item.as_str().map(str::trim) else { continue };
        if ok(s) && !out.iter().any(|o| o == s) {
            out.push(s.to_owned());
            if out.len() >= max {
                break;
            }
        }
    }
    out
}

fn is_loader(s: &str) -> bool {
    (1..=32).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'-' | b'_'))
}

fn is_game_version(s: &str) -> bool {
    (1..=32).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_' | b'+'))
}

fn time(v: &Value) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(as_str(v)?.trim()).ok().map(|d| d.with_timezone(&Utc))
}

fn text_of(v: &Value) -> FeaturedText {
    FeaturedText {
        title: line(&v["title"], MAX_TEXT_TITLE),
        text: line(&v["text"], MAX_TEXT_BODY),
    }
}

fn clean_pack(raw: &RawPack, bases: &[&str]) -> Option<FeaturedPack> {
    let id = as_str(&raw.id).map(str::trim).filter(|id| is_pack_id(id))?.to_owned();
    let source = match as_str(&raw.source)? {
        "modrinth" => FeaturedSource::Modrinth,
        "curseforge" => FeaturedSource::Curseforge,
        _ => return None,
    };
    let project_id = project_id(source, &raw.project_id)?;
    let url = as_str(&raw.url).map(str::trim).filter(|u| is_project_link(source, u))?.to_owned();
    let title = line(&raw.title, MAX_TITLE_CHARS)?;
    let downloads = match &raw.downloads {
        Value::Number(n) => n.as_u64().or_else(|| n.as_f64().filter(|f| f.is_finite() && *f >= 0.0).map(|f| f as u64)),
        _ => None,
    }
    .unwrap_or(0);
    Some(FeaturedPack {
        id,
        source,
        project_id,
        slug: as_str(&raw.slug).map(str::trim).filter(|s| is_slug(s)).unwrap_or_default().to_owned(),
        url,
        title,
        summary: line(&raw.summary, MAX_SUMMARY_CHARS).unwrap_or_default(),
        author: as_str(&raw.author).map(|a| validate::text(a.trim(), 64)).filter(|a| !a.trim().is_empty()),
        downloads,
        loaders: tags(&raw.loaders, 8, is_loader),
        game_versions: tags(&raw.game_versions, 32, is_game_version),
        icon_url: as_str(&raw.icon_url).and_then(|u| image_url(u.trim(), bases)),
        banner_url: as_str(&raw.banner_url).and_then(|u| image_url(u.trim(), bases)),
        badge: as_str(&raw.badge).and_then(FeaturedBadge::parse),
        texts: FeaturedTexts { en: text_of(&raw.texts["en"]), de: text_of(&raw.texts["de"]) },
        pinned: raw.pinned.as_bool().unwrap_or(false),
        starts_at: time(&raw.starts_at),
        ends_at: time(&raw.ends_at),
        updated_at: time(&raw.updated_at),
    })
}

/// Liste aus `{ packs: [...] }` (oder einer nackten Liste): geprüft, ohne doppelte IDs, höchstens `max`.
fn clean_list<T>(json: &Value, max: usize, mut each: impl FnMut(&Value, RawPack) -> Option<T>, id_of: impl Fn(&T) -> &str) -> Vec<T> {
    let list = json.get("packs").unwrap_or(json).as_array().map(Vec::as_slice).unwrap_or_default();
    let mut out: Vec<T> = Vec::new();
    for value in list {
        let Ok(raw) = serde_json::from_value::<RawPack>(value.clone()) else { continue };
        let Some(item) = each(value, raw) else { continue };
        if out.iter().any(|o| id_of(o) == id_of(&item)) {
            continue;
        }
        out.push(item);
        if out.len() >= max {
            break;
        }
    }
    out
}

/// Öffentliche Liste (`GET /v1/site/featured-packs`).
pub fn parse_featured(json: &str, bases: &[&str]) -> Result<Vec<FeaturedPack>> {
    let value: Value = serde_json::from_str(json).map_err(|e| Error::json("Empfohlene Modpacks", e))?;
    Ok(clean_list(&value, MAX_PUBLIC, |_, raw| clean_pack(&raw, bases), |p| p.id.as_str()))
}

/// Ein einzelner Eintrag der Team-Verwaltung (Antworten `{ pack }` und Listeneinträge).
pub(crate) fn clean_admin(value: &Value, bases: &[&str]) -> Option<AdminFeaturedPack> {
    let raw: RawPack = serde_json::from_value(value.clone()).ok()?;
    let pack = clean_pack(&raw, bases)?;
    Some(AdminFeaturedPack {
        pack,
        active: raw.active.as_bool().unwrap_or(false),
        created_by: as_str(&raw.created_by).map(|c| validate::text(c.trim(), 64)).unwrap_or_default(),
        created_at: time(&raw.created_at),
        refreshed_at: time(&raw.refreshed_at),
    })
}

/// Team-Liste (`{ packs }`).
pub(crate) fn parse_admin_list(value: &Value, bases: &[&str]) -> Vec<AdminFeaturedPack> {
    clean_list(value, MAX_ADMIN, |v, _| clean_admin(v, bases), |p| p.pack.id.as_str())
}

/// Läuft der Zeitraum gerade? (Die API filtert schon – der Cache kann aber älter sein.)
pub fn is_live(pack: &FeaturedPack, now: DateTime<Utc>) -> bool {
    pack.starts_at.is_none_or(|s| s <= now) && pack.ends_at.is_none_or(|e| e > now)
}

// --- Cache und Laden -------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CacheFile {
    fetched_at: DateTime<Utc>,
    /// Für welche API-Adresse (ein Wechsel per `TRS_API_BASE` soll nicht den alten Stand zeigen).
    base: String,
    packs: Vec<FeaturedPack>,
}

fn cache_path(paths: &Paths) -> PathBuf {
    paths.root().join("cache").join("featured-packs.json")
}

/// Cache verwerfen (nach Änderungen des Teams), damit die Startseite gleich den neuen Stand lädt.
pub(crate) async fn forget_cache(paths: &Paths) {
    let _ = tokio::fs::remove_file(cache_path(paths)).await;
}

fn unavailable() -> Error {
    Error::validation(crate::msg!("featured.unavailable", "Die empfohlenen Modpacks sind gerade nicht erreichbar."))
}

impl Launcher {
    /// Empfohlene Modpacks für die Startseite – leer, solange die TRS-Dienste aus sind (ohne Einwilligung
    /// geht keine Anfrage an den TRS-Server). Öffentliche Daten, ohne Token. `force` lädt am Cache vorbei.
    pub async fn trs_featured_packs(&self, force: bool) -> Result<Vec<FeaturedPack>> {
        if !self.trs().enabled().await {
            return Ok(Vec::new());
        }
        let base = self.trs().base().to_owned();
        let path = cache_path(self.paths());
        let now = Utc::now();
        let cache = fsutil::read_json::<CacheFile>(&path).await.ok().flatten().filter(|c| c.base == base);
        if !force
            && let Some(cache) = &cache
            && now - cache.fetched_at < Duration::minutes(TTL_MINUTES)
        {
            return Ok(cache.packs.iter().filter(|p| is_live(p, now)).cloned().collect());
        }
        match self.fetch_featured(&base).await {
            Ok(packs) => {
                let file = CacheFile { fetched_at: now, base, packs: packs.clone() };
                if let Err(e) = fsutil::write_json(&path, &file).await {
                    tracing::debug!("Cache der empfohlenen Modpacks nicht geschrieben: {e}");
                }
                Ok(packs.into_iter().filter(|p| is_live(p, now)).collect())
            }
            Err(e) => match cache {
                Some(cache) => {
                    tracing::warn!("Empfohlene Modpacks nicht geladen, zeige zwischengespeicherte: {e}");
                    Ok(cache.packs.into_iter().filter(|p| is_live(p, now)).collect())
                }
                None => Err(e),
            },
        }
    }

    async fn fetch_featured(&self, base: &str) -> Result<Vec<FeaturedPack>> {
        let response = self
            .http()
            .get(format!("{base}/v1/site/featured-packs"))
            .header("accept", "application/json")
            .send()
            .await?
            .error_for_status()?;
        if response.content_length().is_some_and(|len| len > MAX_FEED_BYTES) {
            return Err(unavailable());
        }
        let bytes = response.bytes().await?;
        if bytes.len() as u64 > MAX_FEED_BYTES {
            return Err(unavailable());
        }
        parse_featured(&String::from_utf8_lossy(&bytes), &self.trs().trusted_bases())
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    const BASES: [&str; 2] = ["https://trs-launcher.theredstonee.de", "https://api.theredstonee.de"];

    fn pack(id: &str) -> Value {
        json!({
            "id": id, "source": "modrinth", "projectId": "1KVo5zza", "slug": "fabulously-optimized",
            "url": "https://modrinth.com/modpack/fabulously-optimized",
            "title": "Fabulously Optimized", "summary": "Improve your graphics &\u{0007} performance\n  quickly",
            "author": "robotkoer", "downloads": 12_345_678,
            "loaders": ["fabric", "Fabric!", "quilt"], "gameVersions": ["1.21.4", "1.21.1", "<x>"],
            "iconUrl": "https://trs-launcher.theredstonee.de/v1/site/featured-packs/fp1/icon?v=3",
            "bannerUrl": "https://api.theredstonee.de/v1/site/featured-packs/fp1/banner?v=3",
            "badge": "recommended",
            "texts": { "en": { "title": "Our pick", "text": "Fast and pretty." }, "de": { "title": null, "text": "Schnell und schön." } },
            "pinned": true, "startsAt": null, "endsAt": "2099-01-01T00:00:00.000Z", "updatedAt": "2026-10-20T10:00:00.000Z"
        })
    }

    #[test]
    fn featured_parses_and_cleans_a_pack() {
        let list = parse_featured(&json!({ "packs": [pack("fp1")] }).to_string(), &BASES).unwrap();
        assert_eq!(list.len(), 1);
        let p = &list[0];
        assert_eq!((p.source, p.project_id.as_str(), p.downloads), (FeaturedSource::Modrinth, "1KVo5zza", 12_345_678));
        assert_eq!(p.summary, "Improve your graphics & performance quickly");
        assert_eq!(p.loaders, ["fabric", "quilt"]);
        assert_eq!(p.game_versions, ["1.21.4", "1.21.1"]);
        assert_eq!(p.badge, Some(FeaturedBadge::Recommended));
        assert_eq!(p.texts.en.title.as_deref(), Some("Our pick"));
        assert_eq!((p.texts.de.title.as_deref(), p.texts.de.text.as_deref()), (None, Some("Schnell und schön.")));
        assert!(p.icon_url.as_deref().unwrap().ends_with("/fp1/icon?v=3"));
        assert!(p.banner_url.is_some(), "die alte TRS-Adresse gilt auch");
        assert!(p.pinned && p.starts_at.is_none() && p.ends_at.is_some());
        // Rundreise fürs Webview: camelCase, Quelle/Abzeichen klein.
        let out = serde_json::to_value(p).unwrap();
        assert_eq!((out["source"].clone(), out["badge"].clone()), (json!("modrinth"), json!("recommended")));
        assert_eq!(out["gameVersions"][0], "1.21.4");
    }

    #[test]
    fn featured_rejects_foreign_hosts_and_bad_sources() {
        let mut foreign_image = pack("a");
        foreign_image["iconUrl"] = json!("https://evil.example/v1/site/featured-packs/a/icon");
        foreign_image["bannerUrl"] = json!("http://trs-launcher.theredstonee.de/v1/site/featured-packs/a/banner");
        let mut other_path = pack("b");
        other_path["iconUrl"] = json!("https://trs-launcher.theredstonee.de/v1/me/capes/x.png");
        other_path["bannerUrl"] = json!("https://trs-launcher.theredstonee.de/v1/site/featured-packs/../../me");
        let mut foreign_link = pack("c");
        foreign_link["url"] = json!("https://modrinth.com.evil.example/modpack/x");
        let mut js_link = pack("d");
        js_link["url"] = json!("javascript:alert(1)");
        let mut wrong_site = pack("e");
        wrong_site["url"] = json!("https://www.curseforge.com/minecraft/modpacks/x");
        let mut bad_source = pack("f");
        bad_source["source"] = json!("github");
        let mut bad_cf_id = pack("g");
        bad_cf_id["source"] = json!("curseforge");
        bad_cf_id["projectId"] = json!("12ab");
        bad_cf_id["url"] = json!("https://www.curseforge.com/minecraft/modpacks/x");
        let mut cf = pack("h");
        cf["source"] = json!("curseforge");
        cf["projectId"] = json!(925200);
        cf["url"] = json!("https://www.curseforge.com/minecraft/modpacks/all-the-mods-10");
        let mut bad_id = pack("../x");
        bad_id["badge"] = json!("hot");
        let mut no_title = pack("i");
        no_title["title"] = json!(" \u{202E} \n ");
        let mut user_link = pack("j");
        user_link["url"] = json!("https://user@modrinth.com/modpack/x");

        let feed = json!({ "packs": [foreign_image, other_path, foreign_link, js_link, wrong_site, bad_source, bad_cf_id, cf, bad_id, no_title, user_link, "x", 7] });
        let list = parse_featured(&feed.to_string(), &BASES).unwrap();
        let ids: Vec<_> = list.iter().map(|p| p.id.as_str()).collect();
        assert_eq!(ids, ["a", "b", "h"]);
        assert_eq!((list[0].icon_url.as_ref(), list[0].banner_url.as_ref()), (None, None), "fremde Hosts und http fallen weg");
        assert_eq!((list[1].icon_url.as_ref(), list[1].banner_url.as_ref()), (None, None), "nur der Bilder-Pfad, kein ..");
        assert_eq!((list[2].source, list[2].project_id.as_str()), (FeaturedSource::Curseforge, "925200"));
    }

    #[test]
    fn featured_limits_entries_and_drops_duplicates() {
        let packs: Vec<Value> = (0..30).map(|i| pack(&format!("p{i}"))).chain([pack("p0")]).collect();
        let list = parse_featured(&json!({ "packs": packs }).to_string(), &BASES).unwrap();
        assert_eq!(list.len(), MAX_PUBLIC);
        let dupes = parse_featured(&json!({ "packs": [pack("x"), pack("x"), pack("y")] }).to_string(), &BASES).unwrap();
        assert_eq!(dupes.len(), 2);
        assert!(parse_featured("{}", &BASES).unwrap().is_empty());
        assert!(parse_featured("kein json", &BASES).is_err());
        // Riesige Texte werden gekürzt, Team-Texte auf 60/200 Zeichen.
        let mut long = pack("long");
        long["summary"] = json!("a ".repeat(2000));
        long["texts"]["en"]["title"] = json!("t".repeat(500));
        long["texts"]["en"]["text"] = json!("w ".repeat(500));
        let p = &parse_featured(&json!([long]).to_string(), &BASES).unwrap()[0];
        assert!(p.summary.chars().count() <= MAX_SUMMARY_CHARS + 1);
        assert!(p.texts.en.title.as_ref().unwrap().chars().count() <= MAX_TEXT_TITLE + 1);
        assert!(p.texts.en.text.as_ref().unwrap().chars().count() <= MAX_TEXT_BODY + 1);
    }

    #[test]
    fn featured_relative_images_and_time_window() {
        let mut p = pack("r");
        p["iconUrl"] = json!("/v1/site/featured-packs/r/icon?v=1");
        p["startsAt"] = json!("2026-10-01T00:00:00Z");
        p["endsAt"] = json!("2026-10-10T00:00:00Z");
        let list = parse_featured(&json!({ "packs": [p] }).to_string(), &["http://127.0.0.1:8787"]).unwrap();
        assert_eq!(list[0].icon_url.as_deref(), Some("http://127.0.0.1:8787/v1/site/featured-packs/r/icon?v=1"));
        let at = |s: &str| DateTime::parse_from_rfc3339(s).unwrap().with_timezone(&Utc);
        assert!(!is_live(&list[0], at("2026-09-30T23:59:59Z")));
        assert!(is_live(&list[0], at("2026-10-05T12:00:00Z")));
        assert!(!is_live(&list[0], at("2026-10-10T00:00:00Z")));
    }

    #[test]
    fn featured_project_ids_and_links() {
        assert!(is_project_id(FeaturedSource::Modrinth, "AANobbMI") && is_project_id(FeaturedSource::Modrinth, "fabulously-optimized"));
        assert!(!is_project_id(FeaturedSource::Modrinth, "a/b") && !is_project_id(FeaturedSource::Modrinth, ""));
        assert!(is_project_id(FeaturedSource::Curseforge, "925200"));
        assert!(!is_project_id(FeaturedSource::Curseforge, "0123") && !is_project_id(FeaturedSource::Curseforge, "1234567890123"));
        assert!(is_project_link(FeaturedSource::Modrinth, "https://modrinth.com/modpack/x"));
        assert!(!is_project_link(FeaturedSource::Modrinth, "https://modrinth.com:8443/modpack/x"));
        assert!(is_project_link(FeaturedSource::Curseforge, "https://curseforge.com/minecraft/modpacks/x"));
        assert!(!is_project_link(FeaturedSource::Curseforge, "https://legacy.curseforge.com/x"));
    }

    #[test]
    fn featured_admin_entries_keep_team_fields() {
        let mut v = pack("adm");
        v["active"] = json!(false);
        v["createdBy"] = json!("Theredstonee\u{0007}");
        v["createdAt"] = json!("2026-10-19T08:00:00Z");
        v["refreshedAt"] = json!("kaputt");
        let list = parse_admin_list(&json!({ "packs": [v, pack("bad id!")] }), &BASES);
        assert_eq!(list.len(), 1);
        let a = &list[0];
        assert!(!a.active);
        assert_eq!(a.created_by, "Theredstonee");
        assert!(a.created_at.is_some() && a.refreshed_at.is_none());
        let out = serde_json::to_value(a).unwrap();
        assert_eq!((out["id"].clone(), out["active"].clone()), (json!("adm"), json!(false)));
    }
}
