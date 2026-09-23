//! Neuigkeiten für die Startseite: Minecraft-Patchnotes und -News von Mojangs
//! Launcher-Inhalten, gerade beliebte Modrinth-Projekte und TRS-Launcher-Releases.
//!
//! Alles wird hier im Kern geholt, geprüft, gekürzt und mit kurzer Lebensdauer
//! auf der Platte zwischengespeichert. Offline liefert der Cache weiter Inhalte
//! (dann mit `stale: true`). Bilder lädt der Kern in seinen eigenen Cache – das
//! Webview bekommt nur freigegebene lokale Dateien zu sehen.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};

use crate::error::Msg;
use crate::modrinth;
use crate::paths::Paths;
use crate::{Error, Launcher, Result, fsutil};

const LAUNCHER_CONTENT: &str = "https://launchercontent.mojang.com";
const GITHUB_RELEASES: &str = "https://api.github.com/repos/theredstonee/TRS-Launcher/releases?per_page=10";
pub const RELEASES_PAGE: &str = "https://github.com/theredstonee/TRS-Launcher/releases";

/// So lange gilt ein Cache-Eintrag als frisch.
const TTL_MINUTES: i64 = 30;
/// So viele Einträge je Quelle.
const MAX_ITEMS: usize = 8;
/// „Gerade beliebt“ = Projekte aus den letzten 90 Tagen, nach Downloads.
const TRENDING_DAYS: i64 = 90;
const MAX_IMAGE_BYTES: u64 = 4 * 1024 * 1024;

// --- Datenmodell fürs Webview ---------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum NewsSource {
    /// Patchnotes zu einer Java-Version.
    PatchNotes,
    /// Meldungen aus dem offiziellen Launcher (Java-Edition-Teil).
    Mojang,
    Modrinth,
    Launcher,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewsItem {
    pub id: String,
    pub source: NewsSource,
    pub title: String,
    /// Kurzfassung als reiner Text (HTML ist schon entfernt).
    pub summary: String,
    pub date: Option<DateTime<Utc>>,
    /// Einordnung, z. B. `Release`, `Snapshot`, `Mod`.
    pub tag: Option<String>,
    /// Übersetzbare Fassung von `tag`, falls der Text von uns stammt
    /// (`errors.<code>` im Frontend). Fehlt bei fremden Bezeichnungen.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tag_info: Option<NewsText>,
    /// Downloads bei Modrinth-Projekten – fürs Formatieren in der UI-Sprache.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub downloads: Option<u64>,
    /// Bild-Adresse für [`Launcher::news_image`] (erlaubte Hosts, HTTPS).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_url: Option<String>,
    /// Link zum Öffnen im Browser (nur HTTPS).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub link: Option<String>,
    /// Pfad der Patchnotes bei Mojang – für den vollen Text im Launcher.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content_path: Option<String>,
}

/// Übersetzbarer Text wie [`Msg`] (`{ code, params, message }`), aber auch
/// aus dem Cache lesbar.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NewsText {
    pub code: String,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub params: BTreeMap<String, String>,
    pub message: String,
}

impl From<Msg> for NewsText {
    fn from(msg: Msg) -> Self {
        Self {
            code: msg.code.to_owned(),
            params: msg.params.into_iter().map(|(k, v)| (k.to_owned(), v)).collect(),
            message: msg.text,
        }
    }
}

/// `tag` (deutsch, für alte Oberflächen) und `tag_info` aus einer Meldung.
fn tag_of(msg: Msg) -> (Option<String>, Option<NewsText>) {
    (Some(msg.text.clone()), Some(msg.into()))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewsFeed {
    pub items: Vec<NewsItem>,
    pub fetched_at: DateTime<Utc>,
    /// `true`, wenn die Daten aus dem Cache stammen, weil das Laden scheiterte.
    pub stale: bool,
}

// --- Cache ----------------------------------------------------------------------

fn cache_dir(paths: &Paths) -> PathBuf {
    paths.root().join("cache").join("news")
}

#[derive(Debug, Serialize, Deserialize)]
struct CacheFile {
    fetched_at: DateTime<Utc>,
    items: Vec<NewsItem>,
}

async fn read_cache(paths: &Paths, key: &str) -> Option<CacheFile> {
    fsutil::read_json::<CacheFile>(&cache_dir(paths).join(format!("{key}.json"))).await.ok().flatten()
}

async fn write_cache(paths: &Paths, key: &str, items: &[NewsItem]) {
    let file = CacheFile { fetched_at: Utc::now(), items: items.to_vec() };
    if let Err(e) = fsutil::write_json(&cache_dir(paths).join(format!("{key}.json")), &file).await {
        tracing::debug!("News-Cache konnte nicht geschrieben werden: {e}");
    }
}

/// Holt eine Quelle mit Cache: frischer Cache gewinnt, sonst wird geladen;
/// scheitert das Laden, kommt der alte Stand zurück (`stale`).
async fn cached<F>(paths: &Paths, key: &str, force: bool, fetch: F) -> Result<NewsFeed>
where
    F: AsyncFnOnce() -> Result<Vec<NewsItem>>,
{
    let cache = read_cache(paths, key).await;
    if !force
        && let Some(cache) = &cache
        && Utc::now() - cache.fetched_at < Duration::minutes(TTL_MINUTES)
    {
        return Ok(NewsFeed { items: cache.items.clone(), fetched_at: cache.fetched_at, stale: false });
    }
    match fetch().await {
        Ok(items) => {
            write_cache(paths, key, &items).await;
            Ok(NewsFeed { items, fetched_at: Utc::now(), stale: false })
        }
        Err(e) => match cache {
            Some(cache) => {
                tracing::warn!("Neuigkeiten ({key}) konnten nicht geladen werden, zeige zwischengespeicherte: {e}");
                Ok(NewsFeed { items: cache.items, fetched_at: cache.fetched_at, stale: true })
            }
            None => Err(e),
        },
    }
}

// --- Textaufbereitung -------------------------------------------------------------

const MAX_SUMMARY_CHARS: usize = 320;
const MAX_TITLE_CHARS: usize = 120;

/// Entfernt HTML-Tags und macht daraus eine kurze, einzeilige Zusammenfassung.
/// Die Patchnotes von Mojang sind HTML – im Webview landet nur reiner Text.
pub fn plain_text(input: &str, max_chars: usize) -> String {
    let input = &strip_blocks(input, "script");
    let input = &strip_blocks(input, "style");
    let mut out = String::new();
    let mut in_tag = false;
    let mut entity = String::new();
    for c in input.chars() {
        match c {
            '<' => in_tag = true,
            '>' => {
                in_tag = false;
                out.push(' ');
            }
            _ if in_tag => {}
            '&' => entity.push('&'),
            ';' if !entity.is_empty() => {
                out.push_str(match entity.as_str() {
                    "&amp" => "&",
                    "&lt" => "<",
                    "&gt" => ">",
                    "&quot" => "\"",
                    "&#39" | "&apos" => "'",
                    "&nbsp" => " ",
                    _ => "",
                });
                entity.clear();
            }
            _ if !entity.is_empty() => {
                if entity.len() > 8 {
                    entity.clear();
                } else {
                    entity.push(c);
                }
            }
            c if c.is_control() || c.is_whitespace() => out.push(' '),
            c => out.push(c),
        }
    }
    // Mehrfache Leerzeichen zusammenfassen und sauber kürzen.
    let collapsed = out.split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.chars().count() <= max_chars {
        return collapsed;
    }
    let cut: String = collapsed.chars().take(max_chars).collect();
    let end = cut.rfind(' ').unwrap_or(cut.len());
    format!("{}…", cut[..end].trim_end_matches([',', '.', ';', ':']))
}

/// Wirft `<tag …> … </tag>` samt Inhalt weg (Skripte und Styles).
fn strip_blocks(input: &str, tag: &str) -> String {
    let lower = input.to_ascii_lowercase();
    let open = format!("<{tag}");
    let close = format!("</{tag}");
    let mut out = String::with_capacity(input.len());
    let mut rest = 0usize;
    while let Some(start) = lower[rest..].find(&open) {
        let start = rest + start;
        out.push_str(&input[rest..start]);
        let after = match lower[start..].find(&close) {
            Some(end) => lower[start + end..].find('>').map_or(lower.len(), |gt| start + end + gt + 1),
            None => lower.len(),
        };
        rest = after;
    }
    out.push_str(&input[rest.min(input.len())..]);
    out
}

fn clip_title(title: &str) -> String {
    plain_text(title, MAX_TITLE_CHARS)
}

// --- Mojang: Patchnotes ------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct PatchNotes {
    #[serde(default)]
    entries: Vec<PatchEntry>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PatchEntry {
    #[serde(default)]
    id: String,
    #[serde(default)]
    title: String,
    #[serde(rename = "type", default)]
    kind: String,
    #[serde(default)]
    date: Option<DateTime<Utc>>,
    #[serde(default)]
    short_text: String,
    #[serde(default)]
    image: Option<MojangImage>,
    #[serde(default)]
    content_path: String,
}

#[derive(Debug, Deserialize)]
struct MojangImage {
    #[serde(default)]
    url: String,
}

/// Mojangs JSON liefert Bilder als Pfad (`/v2/images/…`) – daraus wird eine URL.
fn launcher_content_url(path: &str) -> Option<String> {
    let ok = path.starts_with('/')
        && path.len() <= 200
        && !path.contains("..")
        && path.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '-' | '_'));
    ok.then(|| format!("{LAUNCHER_CONTENT}{path}"))
}

/// Ist das ein Pfad, den [`Launcher::patch_notes_body`] laden darf?
fn is_content_path(path: &str) -> bool {
    !path.is_empty()
        && path.len() <= 120
        && !path.contains("..")
        && path.ends_with(".json")
        && path.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '-' | '_'))
}

fn parse_patch_notes(json: &str) -> Result<Vec<NewsItem>> {
    let notes: PatchNotes = serde_json::from_str(json).map_err(|e| Error::json("javaPatchNotes.json", e))?;
    Ok(notes
        .entries
        .into_iter()
        .filter(|e| !e.id.is_empty() && !e.title.is_empty())
        .take(MAX_ITEMS)
        .map(|e| {
            let (tag, tag_info) = match e.kind.as_str() {
                "release" => tag_of(crate::msg!("news.tagRelease", "Vollversion")),
                "snapshot" => tag_of(crate::msg!("news.tagSnapshot", "Snapshot")),
                other => (Some(plain_text(other, 24)).filter(|t| !t.is_empty()), None),
            };
            NewsItem {
                tag,
                tag_info,
                downloads: None,
                title: clip_title(&e.title),
                summary: plain_text(&e.short_text, MAX_SUMMARY_CHARS),
                date: e.date,
                image_url: e.image.and_then(|i| launcher_content_url(&i.url)),
                link: None,
                content_path: is_content_path(&e.content_path).then(|| e.content_path.clone()),
                id: format!("patch-{}", plain_text(&e.id, 64)),
                source: NewsSource::PatchNotes,
            }
        })
        .filter(|item| !item.title.is_empty())
        .collect())
}

// --- Mojang: News ------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct MojangNews {
    #[serde(default)]
    entries: Vec<NewsEntry>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct NewsEntry {
    #[serde(default)]
    id: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    category: String,
    #[serde(default)]
    date: String,
    #[serde(default)]
    text: String,
    #[serde(default)]
    news_page_image: Option<MojangImage>,
    #[serde(default)]
    play_page_image: Option<MojangImage>,
    #[serde(default)]
    read_more_link: String,
}

/// Mojangs News-Datum ist nur ein Tag (`2026-09-16`).
fn parse_day(value: &str) -> Option<DateTime<Utc>> {
    let day = chrono::NaiveDate::parse_from_str(value, "%Y-%m-%d").ok()?;
    Some(DateTime::from_naive_utc_and_offset(day.and_hms_opt(12, 0, 0)?, Utc))
}

fn parse_mojang_news(json: &str) -> Result<Vec<NewsItem>> {
    let news: MojangNews = serde_json::from_str(json).map_err(|e| Error::json("news.json", e))?;
    Ok(news
        .entries
        .into_iter()
        // Nur die Java Edition – Bedrock und Dungeons interessieren hier nicht.
        .filter(|e| e.category.contains("Java") && !e.id.is_empty() && !e.title.is_empty())
        .take(MAX_ITEMS)
        .map(|e| NewsItem {
            id: format!("mojang-{}", plain_text(&e.id, 64)),
            source: NewsSource::Mojang,
            title: clip_title(&e.title),
            summary: plain_text(&e.text, MAX_SUMMARY_CHARS),
            date: parse_day(&e.date),
            tag: Some("Minecraft".into()),
            tag_info: None,
            downloads: None,
            image_url: e
                .news_page_image
                .or(e.play_page_image)
                .and_then(|i| launcher_content_url(&i.url)),
            link: Some(e.read_more_link).filter(|l| modrinth::is_safe_external_url(l)),
            content_path: None,
        })
        .collect())
}

// --- Modrinth ---------------------------------------------------------------------

async fn fetch_trending(http: &reqwest::Client) -> Result<Vec<NewsItem>> {
    let hits = modrinth::trending(http, TRENDING_DAYS, MAX_ITEMS as u32).await?;
    Ok(hits
        .into_iter()
        .take(MAX_ITEMS)
        .map(|hit| {
            let (tag, tag_info) =
                tag_of(crate::msg!("news.tagDownloads", "{count} Downloads", count = thousands(hit.downloads)));
            NewsItem {
                id: format!("modrinth-{}", plain_text(&hit.project_id, 64)),
                source: NewsSource::Modrinth,
                title: clip_title(&hit.title),
                summary: plain_text(&hit.description, MAX_SUMMARY_CHARS),
                date: hit.date_modified,
                tag,
                tag_info,
                downloads: Some(hit.downloads),
                image_url: hit.icon_url.filter(|u| crate::icon::is_allowed_icon_url(u)),
                link: Some(format!("https://modrinth.com/mod/{}", hit.slug)).filter(|l| modrinth::is_safe_external_url(l)),
                content_path: None,
            }
        })
        .collect())
}

/// `1234567` → `1.234.567` (deutsche Tausenderpunkte).
fn thousands(value: u64) -> String {
    let digits = value.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i).is_multiple_of(3) {
            out.push('.');
        }
        out.push(c);
    }
    out
}

// --- TRS-Launcher-Releases ---------------------------------------------------------

#[derive(Debug, Deserialize)]
struct GithubRelease {
    #[serde(default)]
    tag_name: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    published_at: Option<DateTime<Utc>>,
    #[serde(default)]
    html_url: String,
    #[serde(default)]
    prerelease: bool,
}

fn parse_releases(json: &str) -> Result<Vec<NewsItem>> {
    let releases: Vec<GithubRelease> =
        serde_json::from_str(json).map_err(|e| Error::json("GitHub-Releases", e))?;
    Ok(releases
        .into_iter()
        // Der feste Kanal `updater` trägt nur latest.json – keine Meldung wert.
        .filter(|r| !r.draft && r.tag_name.starts_with('v'))
        .take(MAX_ITEMS)
        .map(|r| {
            let (tag, tag_info) = if r.prerelease {
                tag_of(crate::msg!("news.tagPreview", "Vorschau"))
            } else {
                tag_of(crate::msg!("news.tagUpdate", "Update"))
            };
            NewsItem {
                id: format!("release-{}", plain_text(&r.tag_name, 40)),
                source: NewsSource::Launcher,
                title: clip_title(r.name.as_deref().filter(|n| !n.is_empty()).unwrap_or(&r.tag_name)),
                summary: plain_text(r.body.as_deref().unwrap_or_default(), MAX_SUMMARY_CHARS),
                date: r.published_at,
                tag,
                tag_info,
                downloads: None,
                image_url: None,
                link: Some(r.html_url).filter(|l| modrinth::is_safe_external_url(l)),
                content_path: None,
            }
        })
        .collect())
}

// --- Bilder-Cache -------------------------------------------------------------------

fn images_dir(paths: &Paths) -> PathBuf {
    paths.root().join("cache").join("images")
}

/// Nur Bilder aus den Quellen, die dieser Feed selbst ausliefert.
fn is_allowed_image_url(url: &str) -> bool {
    (url.starts_with(&format!("{LAUNCHER_CONTENT}/")) || crate::icon::is_allowed_icon_url(url))
        && url.len() <= 512
        && !url.contains("..")
        && !url.chars().any(|c| c.is_whitespace() || c.is_control() || matches!(c, '\\' | '"' | '\'' | '<' | '>' | '@'))
}

fn image_cache_name(url: &str, format: crate::icon::ImageFormat) -> String {
    use sha1::Digest;
    let digest = sha1::Sha1::digest(url.as_bytes());
    let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
    format!("{hex}.{}", format.extension())
}

// --- Öffentliche API -------------------------------------------------------------------

impl Launcher {
    /// Neuigkeiten für die Startseite. `force` umgeht den Cache.
    pub async fn news(&self, force: bool) -> Result<NewsFeed> {
        let (patch, mojang, modrinth, releases) = futures::join!(
            self.patch_notes(force),
            self.mojang_news(force),
            self.trending_projects(force),
            self.launcher_releases(force),
        );

        let mut items = Vec::new();
        let mut stale = false;
        let mut fetched_at = Utc::now();
        for feed in [patch, mojang, modrinth, releases] {
            match feed {
                Ok(feed) => {
                    stale |= feed.stale;
                    fetched_at = fetched_at.min(feed.fetched_at);
                    items.extend(feed.items);
                }
                Err(e) => {
                    stale = true;
                    tracing::warn!("Eine Neuigkeiten-Quelle fehlt: {e}");
                }
            }
        }
        if items.is_empty() {
            // Nichts geladen und nichts im Cache – das Frontend zeigt „offline“.
            return Err(Error::validation(crate::msg!("news.unavailable", "Neuigkeiten sind gerade nicht erreichbar.")));
        }
        Ok(NewsFeed { items, fetched_at, stale })
    }

    pub async fn patch_notes(&self, force: bool) -> Result<NewsFeed> {
        cached(self.paths(), "patch-notes", force, async || {
            let text = self
                .http()
                .get(format!("{LAUNCHER_CONTENT}/v2/javaPatchNotes.json"))
                .send()
                .await?
                .error_for_status()?
                .text()
                .await?;
            parse_patch_notes(&text)
        })
        .await
    }

    pub async fn mojang_news(&self, force: bool) -> Result<NewsFeed> {
        cached(self.paths(), "mojang-news", force, async || {
            let text = self
                .http()
                .get(format!("{LAUNCHER_CONTENT}/v2/news.json"))
                .send()
                .await?
                .error_for_status()?
                .text()
                .await?;
            parse_mojang_news(&text)
        })
        .await
    }

    pub async fn trending_projects(&self, force: bool) -> Result<NewsFeed> {
        cached(self.paths(), "modrinth-trending", force, async || fetch_trending(self.http()).await).await
    }

    pub async fn launcher_releases(&self, force: bool) -> Result<NewsFeed> {
        cached(self.paths(), "launcher-releases", force, async || {
            let text = self
                .http()
                .get(GITHUB_RELEASES)
                .header("accept", "application/vnd.github+json")
                .send()
                .await?
                .error_for_status()?
                .text()
                .await?;
            parse_releases(&text)
        })
        .await
    }

    /// Voller Text einer Patchnote (HTML von Mojang) – wird im Webview nur
    /// über den DOMPurify-Weg angezeigt.
    pub async fn patch_notes_body(&self, content_path: &str) -> Result<String> {
        if !is_content_path(content_path) {
            return Err(Error::validation(crate::msg!("news.patchNotesNotFound", "Diese Patchnotes gibt es nicht.")));
        }
        #[derive(Deserialize)]
        struct Body {
            #[serde(default)]
            body: String,
        }
        let body: Body = self
            .http()
            .get(format!("{LAUNCHER_CONTENT}/v2/{content_path}"))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        Ok(body.body.chars().take(200_000).collect())
    }

    /// Lädt ein Bild aus dem Feed in den Cache und liefert den lokalen Pfad.
    /// Die Tauri-Schicht gibt genau diese Datei fürs Webview frei.
    pub async fn news_image(&self, url: &str) -> Result<PathBuf> {
        if !is_allowed_image_url(url) {
            return Err(Error::validation(crate::msg!(
                "news.imageSourceNotAllowed",
                "Diese Bildquelle ist nicht erlaubt."
            )));
        }
        let dir = images_dir(self.paths());
        // Schon im Cache? Dann ohne Netz antworten.
        for format in [crate::icon::ImageFormat::Png, crate::icon::ImageFormat::Jpeg, crate::icon::ImageFormat::Webp] {
            let candidate = dir.join(image_cache_name(url, format));
            if candidate.is_file() {
                return Ok(candidate);
            }
        }
        let response = self.http().get(url).send().await?.error_for_status()?;
        if response.content_length().is_some_and(|len| len > MAX_IMAGE_BYTES) {
            return Err(Error::validation(crate::msg!("news.imageTooLarge", "Das Bild ist zu groß.")));
        }
        let bytes = response.bytes().await?;
        let format = crate::icon::validate_image(&bytes)?;
        let path = dir.join(image_cache_name(url, format));
        fsutil::write_atomic(&path, &bytes).await?;
        Ok(path)
    }
}

/// Alte Bilder und Feeds aus dem Cache räumen (aufgerufen beim Aufräumen).
pub async fn clean_cache(paths: &Paths, max_age_days: i64) -> Result<u64> {
    let mut freed = 0;
    for dir in [images_dir(paths), cache_dir(paths)] {
        freed += clean_dir(&dir, max_age_days).await;
    }
    Ok(freed)
}

async fn clean_dir(dir: &Path, max_age_days: i64) -> u64 {
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else { return 0 };
    let mut freed = 0;
    while let Ok(Some(entry)) = entries.next_entry().await {
        let Ok(meta) = entry.metadata().await else { continue };
        let Some(modified) = meta.modified().ok().map(DateTime::<Utc>::from) else { continue };
        if meta.is_file() && Utc::now() - modified > Duration::days(max_age_days) {
            freed += meta.len();
            let _ = tokio::fs::remove_file(entry.path()).await;
        }
    }
    freed
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;

    #[test]
    fn strips_html_and_shortens() {
        let text = plain_text("<p>Hallo <b>Welt</b> &amp; mehr</p>", 100);
        assert_eq!(text, "Hallo Welt & mehr");
        assert!(!plain_text("<script>alert(1)</script>Text", 100).contains("alert"));
        let long = plain_text(&"wort ".repeat(200), 40);
        assert!(long.ends_with('…') && long.chars().count() <= 41, "{long}");
        assert_eq!(plain_text("mehrere    Leerzeichen\n\nund Zeilen", 100), "mehrere Leerzeichen und Zeilen");
    }

    #[test]
    fn parses_patch_notes() {
        let json = r#"{"version":1,"entries":[
            {"title":"Minecraft 26.4 Snapshot 1","version":"26.4-snapshot-1","type":"snapshot",
             "image":{"title":"x","url":"/v2/images/a.jpg"},"contentPath":"javaPatchNotes/26-4-snapshot-1.json",
             "id":"26-4-snapshot-1","date":"2026-09-22T13:38:53.000Z","shortText":"<p>Neuer Snapshot</p>"},
            {"title":"","id":"leer","type":"release","date":"2026-09-15T11:23:02.000Z","shortText":"x",
             "contentPath":"javaPatchNotes/leer.json"}]}"#;
        let items = parse_patch_notes(json).unwrap();
        assert_eq!(items.len(), 1, "Einträge ohne Titel fliegen raus");
        let item = &items[0];
        assert_eq!(item.title, "Minecraft 26.4 Snapshot 1");
        assert_eq!(item.tag.as_deref(), Some("Snapshot"));
        assert_eq!(item.summary, "Neuer Snapshot");
        assert_eq!(item.image_url.as_deref(), Some("https://launchercontent.mojang.com/v2/images/a.jpg"));
        assert_eq!(item.content_path.as_deref(), Some("javaPatchNotes/26-4-snapshot-1.json"));
        assert!(item.date.is_some());
    }

    #[test]
    fn parses_mojang_news_only_java() {
        let json = r#"{"version":1,"entries":[
            {"title":"Java Realms","category":"Minecraft: Java Edition","date":"2026-08-28","text":"Text",
             "newsPageImage":{"url":"/v2/images/b.png"},"readMoreLink":"https://www.minecraft.net/article/x","id":"aa"},
            {"title":"Bedrock-Kram","category":"Minecraft for Windows","date":"2026-09-16","text":"Nein","id":"bb"},
            {"title":"Böser Link","category":"Minecraft: Java Edition","date":"2026-09-16","text":"x",
             "readMoreLink":"javascript:alert(1)","id":"cc"}]}"#;
        let items = parse_mojang_news(json).unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].title, "Java Realms");
        assert_eq!(items[0].link.as_deref(), Some("https://www.minecraft.net/article/x"));
        assert!(items[1].link.is_none(), "javascript:-Links werden verworfen");
    }

    #[test]
    fn parses_releases_and_skips_updater_channel() {
        let json = r#"[
            {"tag_name":"v0.2.1","name":"TRS Launcher v0.2.1","body":"Neue Version","draft":false,"prerelease":true,
             "published_at":"2026-09-22T17:21:25Z","html_url":"https://github.com/theredstonee/TRS-Launcher/releases/tag/v0.2.1"},
            {"tag_name":"updater","name":"updater","body":"latest.json","draft":false,"prerelease":false,
             "published_at":"2026-09-22T17:22:00Z","html_url":"https://github.com/theredstonee/TRS-Launcher/releases/tag/updater"}]"#;
        let items = parse_releases(json).unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].tag.as_deref(), Some("Vorschau"));
        assert_eq!(items[0].tag_info.as_ref().map(|t| t.code.as_str()), Some("news.tagPreview"));
        assert_eq!(items[0].title, "TRS Launcher v0.2.1");
    }

    #[test]
    fn image_and_content_paths_are_checked() {
        assert!(launcher_content_url("/v2/images/a.jpg").is_some());
        assert!(launcher_content_url("/v2/../../etc/passwd").is_none());
        assert!(launcher_content_url("v2/images/a.jpg").is_none());
        assert!(is_content_path("javaPatchNotes/26-3.json"));
        assert!(!is_content_path("../secrets.json"));
        assert!(!is_content_path("javaPatchNotes/26-3.html"));

        assert!(is_allowed_image_url("https://launchercontent.mojang.com/v2/images/a.jpg"));
        assert!(is_allowed_image_url("https://cdn.modrinth.com/data/x/icon.png"));
        assert!(!is_allowed_image_url("https://evil.example/a.jpg"));
        assert!(!is_allowed_image_url("https://launchercontent.mojang.com/v2/../a.jpg"));
    }

    #[test]
    fn formats_thousands() {
        assert_eq!(thousands(0), "0");
        assert_eq!(thousands(999), "999");
        assert_eq!(thousands(1_234_567), "1.234.567");
    }

    #[tokio::test]
    async fn cache_serves_stale_items_when_offline() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        let item = NewsItem {
            id: "x".into(),
            source: NewsSource::Launcher,
            title: "Titel".into(),
            summary: "Text".into(),
            date: None,
            tag: None,
            tag_info: None,
            downloads: None,
            image_url: None,
            link: None,
            content_path: None,
        };

        // Erster Aufruf lädt und speichert.
        let items = vec![item.clone()];
        let feed = cached(&paths, "test", false, async || Ok(items.clone())).await.unwrap();
        assert!(!feed.stale);

        // Zweiter Aufruf nimmt den frischen Cache, ohne zu laden.
        let feed = cached(&paths, "test", false, async || Err(Error::validation(crate::msg!(
            "test.unexpected",
            "darf nicht passieren"
        )))).await.unwrap();
        assert_eq!(feed.items.len(), 1);
        assert!(!feed.stale);

        // Erzwungenes Laden scheitert → alter Stand, als `stale` markiert.
        let feed = cached(&paths, "test", true, async || Err(Error::validation(crate::msg!(
            "test.offline",
            "offline"
        )))).await.unwrap();
        assert!(feed.stale);
        assert_eq!(feed.items[0].title, "Titel");

        // Ohne Cache schlägt der Fehler durch.
        assert!(cached(&paths, "leer", false, async || Err(Error::validation(crate::msg!(
            "test.offline",
            "offline"
        )))).await.is_err());
    }

    #[tokio::test]
    async fn rejects_foreign_image_hosts() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
        assert!(launcher.news_image("https://evil.example/a.png").await.is_err());
        assert!(launcher.patch_notes_body("../../etc/passwd").await.is_err());
    }
}
