//! CurseForge (Core API v1) als zweite Quelle neben Modrinth: suchen,
//! Projekte ansehen, Dateien wählen, installieren, aktualisieren, Modpacks.
//!
//! - **API-Schlüssel:** wird beim Bauen eingebettet (siehe `build.rs`), geht
//!   nur als Header `x-api-key` an `api.curseforge.com` – nie an Download-Hosts,
//!   nie ins Frontend, nie in Logs oder Fehlermeldungen. Der API-Client folgt
//!   keinen Weiterleitungen, damit der Header nirgendwo anders landet.
//! - **Downloads:** nur HTTPS von `edge.forgecdn.net` / `mediafilez.forgecdn.net`
//!   (die API liefert `edge…`, das per 302 auf `mediafilez…` weiterleitet),
//!   höchstens drei Weiterleitungen, SHA1 aus `hashes` (algo 1).
//! - **Download-Sperre:** Erlaubt ein Autor keine Downloads über andere Apps
//!   (`downloadUrl: null`), lädt der Launcher die Datei NICHT über Umwege,
//!   sondern führt sie als „von Hand laden“ (siehe [`pack::BlockedFile`]).

mod install;
mod pack;
#[cfg(test)]
mod tests;

use std::collections::HashMap;
use std::time::{Duration, Instant};

use chrono::{DateTime, Utc};
use reqwest::header::{ACCEPT, HeaderValue, RETRY_AFTER};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Deserializer, Serialize};

use crate::content::ContentKind;
use crate::icon::is_allowed_icon_url;
use crate::instance::Instance;
use crate::modrinth::{
    self, CategoryTag, DependencyInfo, GalleryImage, ProjectCard, ProjectDetails, ProjectKind, ProjectLink, SearchHit,
    SearchParams, SearchResult, SortIndex, VersionSummary, clip, clip_markdown,
};
use crate::{Error, Result, USER_AGENT};

pub use install::InstallOutcome;
pub(crate) use pack::is_curseforge_pack;
pub use pack::{AdoptResult, BlockedFile, PackOutcome, adopt_downloads, blocked_files, dismiss_blocked, downloads_dir};

pub const API_BASE: &str = "https://api.curseforge.com/v1";
/// Minecraft bei CurseForge.
pub const GAME_ID: u32 = 432;
/// Hosts, von denen Dateien geladen werden dürfen (geprüft an echten Antworten).
const DOWNLOAD_HOSTS: [&str; 2] = ["edge.forgecdn.net", "mediafilez.forgecdn.net"];
const MAX_REDIRECTS: usize = 3;
/// Größte JSON-Antwort (100 Projekte mit allen Datei-Indizes sind ~2 MB).
const MAX_JSON_BYTES: usize = 24 * 1024 * 1024;
/// CurseForge erlaubt höchstens 50 Treffer je Seite und `index + pageSize <= 10 000`.
const MAX_PAGE_SIZE: u32 = 50;
const MAX_RESULT_WINDOW: u32 = 10_000;
/// Mehr als 10 Kategorien auf einmal ergeben bei CurseForge (UND-verknüpft) ohnehin nichts.
const MAX_CATEGORIES: usize = 10;
/// Kurze `429`-Wartezeiten wartet der Kern selbst ab (einmal), längere gehen als Fehler raus.
const MAX_INLINE_RETRY: Duration = Duration::from_secs(3);
const IDS_PER_REQUEST: usize = 100;
const MAX_BODY_CHARS: usize = 100_000;
const MAX_CHANGELOG_CHARS: usize = 16_000;
/// Wie viele Dateien die Detailansicht höchstens lädt (5 Seiten).
const MAX_ALL_FILES: usize = 250;
const SEARCH_CACHE_SECS: u64 = 120;
const SEARCH_CACHE_ENTRIES: usize = 32;
const CATEGORY_CACHE_SECS: u64 = 24 * 60 * 60;

// CurseForge-Klassen (geprüft über `/v1/categories?gameId=432&classesOnly=true`).
pub(crate) const CLASS_MODS: u32 = 6;
pub(crate) const CLASS_RESOURCE_PACKS: u32 = 12;
pub(crate) const CLASS_SHADERS: u32 = 6552;
pub(crate) const CLASS_DATA_PACKS: u32 = 6945;
pub(crate) const CLASS_MODPACKS: u32 = 4471;

/// Der beim Bauen eingebettete Schlüssel (leer = CurseForge aus).
fn compiled_key() -> Option<&'static str> {
    let key = include_str!(concat!(env!("OUT_DIR"), "/curseforge_api_key.txt")).trim();
    (!key.is_empty()).then_some(key)
}

// --- Fehler ----------------------------------------------------------------------------

pub(crate) fn disabled() -> Error {
    Error::validation(crate::msg!(
        "curseforge.disabled",
        "CurseForge ist in dieser Version des Launchers nicht verfügbar."
    ))
}

fn rate_limited(seconds: u64) -> Error {
    Error::validation(crate::msg!(
        "curseforge.rateLimited",
        "CurseForge bremst gerade die Anfragen – bitte in {seconds} Sekunden erneut versuchen.",
        seconds = seconds
    ))
}

fn access_denied() -> Error {
    Error::validation(crate::msg!(
        "curseforge.accessDenied",
        "CurseForge hat die Anfrage des Launchers abgelehnt. Bitte später erneut versuchen oder den Launcher aktualisieren."
    ))
}

fn not_found() -> Error {
    Error::validation(crate::msg!("curseforge.notFound", "Das gibt es auf CurseForge nicht (mehr)."))
}

fn unavailable() -> Error {
    Error::validation(crate::msg!(
        "curseforge.unavailable",
        "CurseForge ist gerade nicht erreichbar. Bitte später erneut versuchen."
    ))
}

pub(crate) fn bad_response() -> Error {
    Error::validation(crate::msg!("curseforge.badResponse", "CurseForge hat unerwartete Daten geschickt."))
}

pub(crate) fn invalid_project_id() -> Error {
    Error::validation(crate::msg!("curseforge.invalidProjectId", "Ungültige CurseForge-Projekt-ID"))
}

pub(crate) fn invalid_file_id() -> Error {
    Error::validation(crate::msg!("curseforge.invalidFileId", "Ungültige CurseForge-Datei-ID"))
}

/// Projekt- und Datei-IDs sind bei CurseForge positive Zahlen.
pub(crate) fn parse_id(id: &str) -> Option<u64> {
    (!id.is_empty() && id.len() <= 12 && id.bytes().all(|b| b.is_ascii_digit()))
        .then(|| id.parse::<u64>().ok())
        .flatten()
        .filter(|n| *n > 0)
}

// --- Rohdaten der API ---------------------------------------------------------------

#[derive(Deserialize)]
struct Envelope<T> {
    data: T,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pagination {
    #[serde(default)]
    index: u32,
    #[serde(default)]
    page_size: u32,
    #[serde(default, deserialize_with = "lenient_u64")]
    total_count: u64,
}

#[derive(Deserialize)]
struct SearchPage {
    #[serde(default)]
    data: Vec<RawMod>,
    pagination: Pagination,
}

#[derive(Deserialize)]
struct FilesPage {
    #[serde(default)]
    data: Vec<RawFile>,
    pagination: Option<Pagination>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RawLinks {
    #[serde(default)]
    pub website_url: Option<String>,
    #[serde(default)]
    pub wiki_url: Option<String>,
    #[serde(default)]
    pub issues_url: Option<String>,
    #[serde(default)]
    pub source_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RawCategory {
    pub id: u32,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub class_id: Option<u32>,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub is_class: Option<bool>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct RawAuthor {
    #[serde(default)]
    pub name: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RawAsset {
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RawFileIndex {
    #[serde(default)]
    pub game_version: String,
    pub file_id: u64,
    #[serde(default)]
    pub filename: String,
    #[serde(default)]
    pub release_type: u8,
    #[serde(default)]
    pub mod_loader: Option<u8>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RawMod {
    pub id: u64,
    #[serde(default)]
    pub game_id: u32,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub slug: String,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub links: RawLinks,
    #[serde(default, deserialize_with = "lenient_u64")]
    pub download_count: u64,
    #[serde(default, deserialize_with = "lenient_u64")]
    pub thumbs_up_count: u64,
    #[serde(default)]
    pub categories: Vec<RawCategory>,
    #[serde(default)]
    pub class_id: Option<u32>,
    #[serde(default)]
    pub authors: Vec<RawAuthor>,
    #[serde(default)]
    pub logo: Option<RawAsset>,
    #[serde(default)]
    pub screenshots: Vec<RawAsset>,
    #[serde(default)]
    pub latest_files_indexes: Vec<RawFileIndex>,
    #[serde(default, deserialize_with = "lenient_date")]
    pub date_modified: Option<DateTime<Utc>>,
    #[serde(default, deserialize_with = "lenient_date")]
    pub date_released: Option<DateTime<Utc>>,
    #[serde(default, deserialize_with = "lenient_date")]
    pub date_created: Option<DateTime<Utc>>,
    #[serde(default)]
    pub allow_mod_distribution: Option<bool>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct RawHash {
    #[serde(default)]
    pub value: String,
    #[serde(default)]
    pub algo: u8,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RawDependency {
    pub mod_id: u64,
    #[serde(default)]
    pub relation_type: u8,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RawFile {
    pub id: u64,
    #[serde(default)]
    pub mod_id: u64,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub file_name: String,
    #[serde(default)]
    pub release_type: u8,
    #[serde(default)]
    pub hashes: Vec<RawHash>,
    #[serde(default, deserialize_with = "lenient_date")]
    pub file_date: Option<DateTime<Utc>>,
    #[serde(default, deserialize_with = "lenient_u64")]
    pub file_length: u64,
    /// `null`, wenn der Autor Downloads über andere Apps nicht erlaubt.
    #[serde(default)]
    pub download_url: Option<String>,
    /// Spielversionen UND Loader-/Umgebungsnamen („1.20.1“, „Forge“, „Client“).
    #[serde(default)]
    pub game_versions: Vec<String>,
    #[serde(default)]
    pub dependencies: Vec<RawDependency>,
    #[serde(default)]
    pub is_server_pack: Option<bool>,
    #[serde(default)]
    pub is_available: Option<bool>,
}

/// Zahlen kommen manchmal als Gleitkomma – alles Nicht-Negative annehmen.
fn lenient_u64<'de, D: Deserializer<'de>>(d: D) -> std::result::Result<u64, D::Error> {
    let value = Option::<serde_json::Value>::deserialize(d)?;
    Ok(match value {
        Some(serde_json::Value::Number(n)) => n.as_u64().or_else(|| n.as_f64().filter(|f| *f >= 0.0).map(|f| f as u64)).unwrap_or(0),
        _ => 0,
    })
}

/// Kaputte Datumsangaben machen nicht die ganze Antwort unbrauchbar.
fn lenient_date<'de, D: Deserializer<'de>>(d: D) -> std::result::Result<Option<DateTime<Utc>>, D::Error> {
    let value = Option::<String>::deserialize(d)?;
    Ok(value.and_then(|s| DateTime::parse_from_rfc3339(&s).ok()).map(|d| d.with_timezone(&Utc)))
}

/// Namen, unter denen CurseForge Modloader in `gameVersions` führt.
const LOADER_NAMES: [(&str, &str); 4] = [("forge", "forge"), ("neoforge", "neoforge"), ("fabric", "fabric"), ("quilt", "quilt")];

impl RawFile {
    /// SHA1 (algo 1), nur wenn sie wie eine aussieht.
    pub(crate) fn sha1(&self) -> Option<String> {
        self.hashes
            .iter()
            .find(|h| h.algo == 1)
            .map(|h| h.value.trim().to_ascii_lowercase())
            .filter(|h| h.len() == 40 && h.bytes().all(|b| b.is_ascii_hexdigit()))
    }

    /// Modloader dieser Datei in Modrinth-Schreibweise (`forge`, `fabric` …).
    pub(crate) fn loaders(&self) -> Vec<&'static str> {
        let mut out = Vec::new();
        for entry in &self.game_versions {
            let lower = entry.to_ascii_lowercase();
            if let Some((_, tag)) = LOADER_NAMES.iter().find(|(name, _)| *name == lower)
                && !out.contains(tag)
            {
                out.push(*tag);
            }
        }
        out
    }

    /// Nur die Spielversionen (ohne „Forge“, „Client“, „Java 17“ …).
    pub(crate) fn minecraft_versions(&self) -> Vec<&str> {
        self.game_versions.iter().map(String::as_str).filter(|v| v.starts_with(|c: char| c.is_ascii_digit())).collect()
    }

    pub(crate) fn release_type(&self) -> &'static str {
        release_type_name(self.release_type)
    }

    /// Server-Packs und zurückgezogene Dateien kommen nie in Frage.
    pub(crate) fn usable(&self) -> bool {
        self.is_server_pack != Some(true) && self.is_available != Some(false) && !self.file_name.is_empty()
    }

    /// Passt die Datei zu Spielversion und Modloader der Instanz?
    pub(crate) fn fits(&self, instance: &Instance, kind: ContentKind) -> bool {
        if !self.minecraft_versions().contains(&instance.game_version.as_str()) {
            return false;
        }
        if kind != ContentKind::Mod {
            return true;
        }
        let tags = modrinth::loader_tags(modrinth::content_loader(instance));
        let loaders = self.loaders();
        if loaders.is_empty() {
            // Alte Forge-Dateien (bis ~1.12) nennen oft keinen Loader.
            return tags.contains(&"forge");
        }
        loaders.iter().any(|l| tags.contains(l))
    }

    /// Anzeige-Version: der Anzeigename ohne Dateiendung.
    pub(crate) fn version_label(&self) -> String {
        let name = if self.display_name.trim().is_empty() { &self.file_name } else { &self.display_name };
        let trimmed = name.trim();
        let lower = trimmed.to_ascii_lowercase();
        let stripped = [".jar", ".zip"].iter().find_map(|ext| lower.ends_with(ext).then(|| &trimmed[..trimmed.len() - ext.len()]));
        clip(stripped.unwrap_or(trimmed).to_owned(), 60)
    }
}

pub(crate) fn release_type_name(release_type: u8) -> &'static str {
    match release_type {
        2 => "beta",
        3 => "alpha",
        _ => "release",
    }
}

/// CurseForge-`modLoader`-Nummer → Modrinth-Schreibweise.
fn loader_tag_of(mod_loader: u8) -> Option<&'static str> {
    match mod_loader {
        1 => Some("forge"),
        4 => Some("fabric"),
        5 => Some("quilt"),
        6 => Some("neoforge"),
        _ => None,
    }
}

fn mod_loader_type(tag: &str) -> Option<u8> {
    match tag {
        "forge" => Some(1),
        "fabric" => Some(4),
        "quilt" => Some(5),
        "neoforge" => Some(6),
        _ => None,
    }
}

fn dependency_type(relation_type: u8) -> &'static str {
    match relation_type {
        3 => "required",
        5 => "incompatible",
        1 | 6 => "embedded",
        _ => "optional",
    }
}

pub(crate) fn class_of(kind: ProjectKind) -> u32 {
    match kind {
        ProjectKind::Mod => CLASS_MODS,
        ProjectKind::ResourcePack => CLASS_RESOURCE_PACKS,
        ProjectKind::ShaderPack => CLASS_SHADERS,
        ProjectKind::DataPack => CLASS_DATA_PACKS,
        ProjectKind::Modpack => CLASS_MODPACKS,
    }
}

pub(crate) fn content_kind_of_class(class: u32) -> Option<ContentKind> {
    match class {
        CLASS_MODS => Some(ContentKind::Mod),
        CLASS_RESOURCE_PACKS => Some(ContentKind::ResourcePack),
        CLASS_SHADERS => Some(ContentKind::ShaderPack),
        CLASS_DATA_PACKS => Some(ContentKind::DataPack),
        _ => None,
    }
}

/// Projekttyp in Modrinth-Schreibweise (das Frontend kennt nur die).
fn project_type_of_class(class: Option<u32>) -> &'static str {
    match class {
        Some(CLASS_MODS) => "mod",
        Some(CLASS_RESOURCE_PACKS) => "resourcepack",
        Some(CLASS_SHADERS) => "shader",
        Some(CLASS_DATA_PACKS) => "datapack",
        Some(CLASS_MODPACKS) => "modpack",
        _ => "project",
    }
}

/// Pfadteil der Projektseite auf curseforge.com.
fn site_section(class: Option<u32>) -> &'static str {
    match class {
        Some(CLASS_RESOURCE_PACKS) => "texture-packs",
        Some(CLASS_SHADERS) => "shaders",
        Some(CLASS_DATA_PACKS) => "data-packs",
        Some(CLASS_MODPACKS) => "modpacks",
        _ => "mc-mods",
    }
}

fn is_safe_slug(slug: &str) -> bool {
    !slug.is_empty() && slug.len() <= 100 && slug.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

impl RawMod {
    /// Projektseite auf curseforge.com (nur echte curseforge.com-Links).
    pub(crate) fn page_url(&self) -> String {
        let official = self.links.website_url.as_deref().filter(|u| {
            u.starts_with("https://www.curseforge.com/minecraft/") && modrinth::is_safe_external_url(u)
        });
        match official {
            Some(url) => url.trim_end_matches('/').to_owned(),
            None if is_safe_slug(&self.slug) => {
                format!("https://www.curseforge.com/minecraft/{}/{}", site_section(self.class_id), self.slug)
            }
            None => format!("https://www.curseforge.com/projects/{}", self.id),
        }
    }

    /// Download-Adresse einer Datei dieses Projekts – `None`, wenn der Autor
    /// Downloads über andere Apps nicht erlaubt (`downloadUrl: null` oder
    /// `allowModDistribution: false`). Dann wird NICHT über Umwege geladen.
    pub(crate) fn download_url<'a>(&self, file: &'a RawFile) -> Option<&'a str> {
        if self.allow_mod_distribution == Some(false) {
            return None;
        }
        file.download_url.as_deref().filter(|u| !u.is_empty())
    }

    /// Seite einer bestimmten Datei – dort lädt man gesperrte Dateien von Hand.
    pub(crate) fn file_page_url(&self, file_id: u64) -> String {
        format!("{}/files/{file_id}", self.page_url())
    }

    pub(crate) fn author(&self) -> Option<String> {
        self.authors.iter().map(|a| clip(a.name.clone(), 60)).find(|n| !n.is_empty())
    }

    pub(crate) fn icon_url(&self) -> Option<String> {
        let logo = self.logo.as_ref()?;
        [&logo.thumbnail_url, &logo.url].into_iter().flatten().find(|u| is_allowed_icon_url(u)).cloned()
    }

    pub(crate) fn title(&self) -> String {
        clip(self.name.clone(), 100)
    }

    /// Loader aus den Datei-Indizes (Modrinth-Schreibweise).
    fn loaders(&self) -> Vec<&'static str> {
        let mut out: Vec<&'static str> = Vec::new();
        for tag in self.latest_files_indexes.iter().filter_map(|i| i.mod_loader.and_then(loader_tag_of)) {
            if !out.contains(&tag) {
                out.push(tag);
            }
        }
        out
    }

    /// Spielversionen aus den Datei-Indizes, neueste zuerst.
    fn game_versions(&self) -> Vec<String> {
        let mut versions: Vec<String> = self
            .latest_files_indexes
            .iter()
            .map(|i| i.game_version.clone())
            .filter(|v| v.starts_with(|c: char| c.is_ascii_digit()) && v.len() <= 32)
            .collect();
        versions.sort_by(|a, b| compare_versions(b, a));
        versions.dedup();
        versions
    }
}

/// Vergleicht Versionsnummern Teil für Teil numerisch („1.21.10“ > „1.21.9“).
fn compare_versions(a: &str, b: &str) -> std::cmp::Ordering {
    let parts = |v: &str| v.split(['.', '-']).map(|p| p.parse::<u64>().unwrap_or(0)).collect::<Vec<_>>();
    parts(a).cmp(&parts(b)).then_with(|| a.cmp(b))
}

// --- Umwandlung in die gemeinsamen (Modrinth-)Typen ------------------------------------

fn hit_from_raw(m: RawMod) -> SearchHit {
    let mut categories: Vec<String> = m.categories.iter().filter(|c| c.is_class != Some(true)).map(|c| c.id.to_string()).collect();
    categories.truncate(10);
    // Loader wie bei Modrinth als „Kategorien“ – die Liste zeigt sie als Loader-Abzeichen.
    categories.extend(m.loaders().into_iter().map(str::to_owned));
    SearchHit {
        project_id: m.id.to_string(),
        slug: clip(m.slug.clone(), 100),
        title: m.title(),
        description: clip(m.summary.clone(), 400),
        author: m.author().unwrap_or_default(),
        icon_url: m.icon_url(),
        downloads: m.download_count,
        follows: m.thumbs_up_count,
        categories,
        client_side: "unknown".into(),
        server_side: "unknown".into(),
        date_modified: m.date_modified,
        license: None,
    }
}

fn summarize(file: RawFile, is_mod: bool) -> Option<VersionSummary> {
    if file.id == 0 || !file.usable() {
        return None;
    }
    let mut loaders: Vec<String> = file.loaders().into_iter().map(str::to_owned).collect();
    if is_mod && loaders.is_empty() {
        // Siehe `RawFile::fits`: ohne Angabe gilt eine Mod als Forge-Mod.
        loaders.push("forge".into());
    }
    let game_versions = file.minecraft_versions().into_iter().take(200).map(|v| clip(v.to_owned(), 32)).collect();
    Some(VersionSummary {
        id: file.id.to_string(),
        name: clip(file.display_name.clone(), 100),
        version_number: file.version_label(),
        version_type: file.release_type().to_owned(),
        date_published: file.file_date,
        game_versions,
        loaders,
        file_name: clip(file.file_name.clone(), 200),
        size: file.file_length,
        changelog: None,
        dependencies: file
            .dependencies
            .iter()
            .take(50)
            .filter(|d| d.mod_id > 0)
            .map(|d| DependencyInfo {
                project_id: Some(d.mod_id.to_string()),
                version_id: None,
                dependency_type: dependency_type(d.relation_type).to_owned(),
            })
            .collect(),
    })
}

fn card_from_raw(m: &RawMod) -> ProjectCard {
    ProjectCard {
        project_id: m.id.to_string(),
        slug: clip(m.slug.clone(), 100),
        project_type: project_type_of_class(m.class_id).to_owned(),
        title: m.title(),
        description: clip(m.summary.clone(), 400),
        author: m.author(),
        icon_url: m.icon_url(),
        downloads: m.download_count,
    }
}

fn details_from_raw(m: RawMod, body: String) -> ProjectDetails {
    let mut links = vec![ProjectLink { kind: "curseforge", url: m.page_url() }];
    for (kind, url) in [("source", &m.links.source_url), ("issues", &m.links.issues_url), ("wiki", &m.links.wiki_url)] {
        if let Some(url) = url.as_ref().filter(|u| modrinth::is_safe_external_url(u)) {
            links.push(ProjectLink { kind, url: url.clone() });
        }
    }
    let gallery = m
        .screenshots
        .iter()
        .filter_map(|s| {
            let url = [&s.url, &s.thumbnail_url].into_iter().flatten().find(|u| is_allowed_icon_url(u))?.clone();
            Some(GalleryImage {
                url,
                title: s.title.clone().map(|t| clip(t, 120)).filter(|t| !t.is_empty()),
                description: s.description.clone().map(|t| clip(t, 400)).filter(|t| !t.is_empty()),
                featured: false,
            })
        })
        .take(60)
        .collect();
    ProjectDetails {
        project_id: m.id.to_string(),
        slug: clip(m.slug.clone(), 100),
        project_type: project_type_of_class(m.class_id).to_owned(),
        title: m.title(),
        description: clip(m.summary.clone(), 400),
        body: clip_markdown(body, MAX_BODY_CHARS),
        author: m.author(),
        icon_url: m.icon_url(),
        downloads: m.download_count,
        followers: m.thumbs_up_count,
        categories: m.categories.iter().filter(|c| c.is_class != Some(true)).map(|c| clip(c.name.clone(), 40)).take(20).collect(),
        loaders: m.loaders().into_iter().map(str::to_owned).collect(),
        game_versions: m.game_versions().into_iter().take(300).collect(),
        gallery,
        updated: m.date_modified,
        // `dateReleased` ist die neueste Datei – veröffentlicht wurde das Projekt beim Anlegen.
        published: m.date_created.or(m.date_released),
        license: None,
        client_side: "unknown".into(),
        server_side: "unknown".into(),
        links,
    }
}

fn category_from_raw(c: RawCategory) -> Option<CategoryTag> {
    let class = c.class_id?;
    let project_type = match class {
        CLASS_MODS | CLASS_RESOURCE_PACKS | CLASS_SHADERS | CLASS_DATA_PACKS | CLASS_MODPACKS => {
            project_type_of_class(Some(class))
        }
        _ => return None,
    };
    if c.is_class == Some(true) || c.name.trim().is_empty() {
        return None;
    }
    Some(CategoryTag {
        name: c.id.to_string(),
        project_type: project_type.to_owned(),
        header: "categories".into(),
        icon: None,
        label: Some(clip(c.name, 40)),
        icon_url: c.icon_url.filter(|u| is_allowed_icon_url(u)),
    })
}

/// Sortierung: „Relevanz“ ist bei CurseForge die Beliebtheit – die
/// Standard-Sortierung der API liefert bei Suchbegriffen Unbrauchbares.
fn sort_field(index: SortIndex) -> u8 {
    match index {
        SortIndex::Relevance => 2,
        SortIndex::Downloads => 6,
        SortIndex::Follows => 12,
        SortIndex::Newest => 11,
        SortIndex::Updated => 3,
    }
}

// --- Client ----------------------------------------------------------------------------

/// Schlüssel für den Such-Cache und Ergebnis.
type SearchCache = Vec<(String, Instant, SearchResult)>;

pub struct CurseForge {
    /// Nur für `api.curseforge.com`, folgt KEINEN Weiterleitungen.
    api: reqwest::Client,
    /// Für Dateien von forgecdn.net – ohne Schlüssel, begrenzte Weiterleitungen.
    downloads: reqwest::Client,
    base: String,
    key: HeaderValue,
    search_cache: tokio::sync::Mutex<SearchCache>,
    categories: tokio::sync::Mutex<Option<(Instant, Vec<CategoryTag>)>>,
}

impl std::fmt::Debug for CurseForge {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Der Schlüssel erscheint nie in Debug-Ausgaben.
        f.debug_struct("CurseForge").field("base", &self.base).finish_non_exhaustive()
    }
}

/// Darf von dieser Adresse geladen werden? Nur HTTPS, nur die CurseForge-CDNs.
pub(crate) fn is_allowed_download(url: &reqwest::Url) -> bool {
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none_or(|p| p == 443)
        && url.host_str().is_some_and(|h| DOWNLOAD_HOSTS.contains(&h))
}

pub(crate) fn is_allowed_download_url(url: &str) -> bool {
    reqwest::Url::parse(url).is_ok_and(|u| is_allowed_download(&u))
}

impl CurseForge {
    /// Mit dem eingebauten Schlüssel; `None`, wenn der Build keinen hat.
    pub fn from_build() -> Result<Option<Self>> {
        compiled_key().map(|key| Self::with_endpoint(API_BASE, key)).transpose()
    }

    /// Mit eigener API-Adresse und eigenem Schlüssel (Tests).
    pub fn with_endpoint(base: &str, key: &str) -> Result<Self> {
        let mut key = HeaderValue::from_str(key).map_err(|_| Error::Internal("ungültiger CurseForge-Schlüssel".into()))?;
        key.set_sensitive(true);
        let api = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(30))
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        let downloads = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(60))
            .redirect(reqwest::redirect::Policy::custom(|attempt| {
                if attempt.previous().len() > MAX_REDIRECTS {
                    attempt.error("zu viele Weiterleitungen")
                } else if is_allowed_download(attempt.url()) {
                    attempt.follow()
                } else {
                    attempt.error("Weiterleitung auf einen nicht erlaubten Host")
                }
            }))
            .build()?;
        Ok(Self {
            api,
            downloads,
            base: base.trim_end_matches('/').to_owned(),
            key,
            search_cache: tokio::sync::Mutex::new(Vec::new()),
            categories: tokio::sync::Mutex::new(None),
        })
    }

    /// Client für Dateien (Downloads mit geprüften Weiterleitungen).
    pub(crate) fn download_client(&self) -> &reqwest::Client {
        &self.downloads
    }

    /// Schickt eine Anfrage an die API; `429` mit kurzer Wartezeit wird einmal wiederholt.
    async fn send(&self, method: reqwest::Method, path: &str, body: Option<&serde_json::Value>) -> Result<Vec<u8>> {
        let url = format!("{}{path}", self.base);
        for attempt in 0..2 {
            let mut request = self
                .api
                .request(method.clone(), &url)
                .header("x-api-key", self.key.clone())
                .header(ACCEPT, "application/json");
            if let Some(body) = body {
                request = request.json(body);
            }
            let response = request.send().await?;
            let status = response.status();
            if status.is_success() {
                if response.content_length().is_some_and(|len| len > MAX_JSON_BYTES as u64) {
                    return Err(bad_response());
                }
                let bytes = response.bytes().await?;
                if bytes.len() > MAX_JSON_BYTES {
                    return Err(bad_response());
                }
                return Ok(bytes.to_vec());
            }
            let retry_after = response
                .headers()
                .get(RETRY_AFTER)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.trim().parse::<u64>().ok());
            tracing::debug!("CurseForge antwortet mit {} auf {method} {path}", status.as_u16());
            return Err(match status.as_u16() {
                429 => {
                    let wait = Duration::from_secs(retry_after.unwrap_or(5).clamp(1, 15 * 60));
                    if attempt == 0 && wait <= MAX_INLINE_RETRY {
                        crate::task::sleep(wait).await?;
                        continue;
                    }
                    rate_limited(wait.as_secs())
                }
                401 | 403 => access_denied(),
                404 => not_found(),
                400 => bad_response(),
                _ => unavailable(),
            });
        }
        Err(rate_limited(MAX_INLINE_RETRY.as_secs()))
    }

    async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        let bytes = self.send(reqwest::Method::GET, path, None).await?;
        parse(&bytes)
    }

    async fn post<T: DeserializeOwned>(&self, path: &str, body: &serde_json::Value) -> Result<T> {
        let bytes = self.send(reqwest::Method::POST, path, Some(body)).await?;
        parse(&bytes)
    }

    // --- Lesen --------------------------------------------------------------------------

    pub(crate) async fn mod_info(&self, id: u64) -> Result<RawMod> {
        let m: Envelope<RawMod> = self.get(&format!("/mods/{id}")).await?;
        if m.data.game_id != GAME_ID || m.data.id != id {
            return Err(not_found());
        }
        Ok(m.data)
    }

    /// Mehrere Projekte auf einmal (fremde Spiele fliegen raus).
    pub(crate) async fn mods(&self, ids: &[u64]) -> Result<Vec<RawMod>> {
        let mut out = Vec::new();
        for chunk in ids.chunks(IDS_PER_REQUEST) {
            let page: Envelope<Vec<RawMod>> =
                self.post("/mods", &serde_json::json!({ "modIds": chunk, "filterPcOnly": true })).await?;
            out.extend(page.data.into_iter().filter(|m| m.game_id == GAME_ID));
        }
        Ok(out)
    }

    pub(crate) async fn file(&self, mod_id: u64, file_id: u64) -> Result<RawFile> {
        let f: Envelope<RawFile> = self.get(&format!("/mods/{mod_id}/files/{file_id}")).await?;
        if f.data.id != file_id || (f.data.mod_id != 0 && f.data.mod_id != mod_id) {
            return Err(Error::validation(crate::msg!(
                "curseforge.fileNotInProject",
                "Diese Datei gehört nicht zu dem Projekt."
            )));
        }
        Ok(f.data)
    }

    /// Dateien nach ID (für Modpacks und Updates), in Blöcken.
    pub(crate) async fn files(&self, ids: &[u64]) -> Result<Vec<RawFile>> {
        let mut out = Vec::new();
        for chunk in ids.chunks(IDS_PER_REQUEST * 2) {
            let page: Envelope<Vec<RawFile>> = self.post("/mods/files", &serde_json::json!({ "fileIds": chunk })).await?;
            out.extend(page.data);
        }
        Ok(out)
    }

    /// Dateien eines Projekts, neueste zuerst; optional nur für eine Spielversion.
    pub(crate) async fn project_files(&self, mod_id: u64, game_version: Option<&str>, max: usize) -> Result<Vec<RawFile>> {
        let mut out: Vec<RawFile> = Vec::new();
        let mut index = 0u32;
        loop {
            let mut path = format!("/mods/{mod_id}/files?index={index}&pageSize={MAX_PAGE_SIZE}");
            if let Some(version) = game_version {
                path.push_str("&gameVersion=");
                path.push_str(&urlencode(version));
            }
            let page: FilesPage = self.get(&path).await?;
            let count = page.data.len();
            out.extend(page.data);
            let total = page.pagination.map_or(0, |p| p.total_count);
            index += MAX_PAGE_SIZE;
            if count < MAX_PAGE_SIZE as usize || out.len() >= max || u64::from(index) >= total || index >= MAX_RESULT_WINDOW {
                break;
            }
        }
        out.retain(RawFile::usable);
        out.truncate(max);
        sort_newest_first(&mut out);
        Ok(out)
    }

    // --- Öffentliche Abfragen -----------------------------------------------------------

    pub async fn search(&self, params: &SearchParams) -> Result<SearchResult> {
        let query = search_query(params)?;
        {
            let mut cache = self.search_cache.lock().await;
            cache.retain(|(_, at, _)| at.elapsed().as_secs() < SEARCH_CACHE_SECS);
            if let Some((_, _, hit)) = cache.iter().find(|(q, _, _)| *q == query) {
                return Ok(hit.clone());
            }
        }
        let page: SearchPage = self.get(&format!("/mods/search?{query}")).await?;
        let result = SearchResult {
            total_hits: page.pagination.total_count.min(u64::from(u32::MAX)) as u32,
            offset: page.pagination.index,
            limit: page.pagination.page_size.max(1),
            hits: page.data.into_iter().filter(|m| m.game_id == GAME_ID && m.id > 0).map(hit_from_raw).collect(),
        };
        let mut cache = self.search_cache.lock().await;
        if cache.len() >= SEARCH_CACHE_ENTRIES {
            cache.remove(0);
        }
        cache.push((query, Instant::now(), result.clone()));
        Ok(result)
    }

    /// Kategorien für die Filterleiste (einen Tag gecacht). Schlägt das
    /// Nachladen fehl, bleibt die alte Liste gültig.
    pub async fn categories(&self) -> Result<Vec<CategoryTag>> {
        let mut cache = self.categories.lock().await;
        if let Some((at, list)) = cache.as_ref()
            && at.elapsed().as_secs() < CATEGORY_CACHE_SECS
        {
            return Ok(list.clone());
        }
        match self.get::<Envelope<Vec<RawCategory>>>(&format!("/categories?gameId={GAME_ID}")).await {
            Ok(raw) => {
                let mut list: Vec<CategoryTag> = raw.data.into_iter().filter_map(category_from_raw).take(500).collect();
                list.sort_by(|a, b| a.label.cmp(&b.label));
                *cache = Some((Instant::now(), list.clone()));
                Ok(list)
            }
            Err(e) => match cache.as_ref() {
                Some((_, list)) => {
                    tracing::debug!("CurseForge-Kategorien konnten nicht aktualisiert werden: {e}");
                    Ok(list.clone())
                }
                None => Err(e),
            },
        }
    }

    pub async fn project_details(&self, project_id: &str) -> Result<ProjectDetails> {
        let id = parse_id(project_id).ok_or_else(invalid_project_id)?;
        let m = self.mod_info(id).await?;
        // Ohne Beschreibung geht es auch.
        let body = self.get::<Envelope<String>>(&format!("/mods/{id}/description")).await.map(|b| b.data).unwrap_or_else(|e| {
            tracing::debug!("CurseForge-Beschreibung nicht geladen: {e}");
            String::new()
        });
        Ok(details_from_raw(m, body))
    }

    /// Alle Dateien eines Projekts (die Detailansicht filtert selbst).
    pub async fn project_versions(&self, project_id: &str) -> Result<Vec<VersionSummary>> {
        let id = parse_id(project_id).ok_or_else(invalid_project_id)?;
        let m = self.mod_info(id).await?;
        let is_mod = m.class_id == Some(CLASS_MODS);
        let files = self.project_files(id, None, MAX_ALL_FILES).await?;
        Ok(files.into_iter().filter_map(|f| summarize(f, is_mod)).collect())
    }

    pub async fn project_cards(&self, ids: &[String]) -> Result<Vec<ProjectCard>> {
        if ids.len() > 200 {
            return Err(Error::validation(crate::msg!("modrinth.tooManyProjects", "Zu viele Projekte auf einmal")));
        }
        let ids: Vec<u64> = ids.iter().filter_map(|id| parse_id(id)).collect();
        if ids.is_empty() {
            return Ok(Vec::new());
        }
        Ok(self.mods(&ids).await?.iter().map(card_from_raw).collect())
    }

    /// Zur Instanz passende Dateien, neueste zuerst.
    pub(crate) async fn compatible_files(&self, mod_id: u64, kind: ContentKind, instance: &Instance) -> Result<Vec<RawFile>> {
        let files = self.project_files(mod_id, Some(&instance.game_version), 100).await?;
        Ok(files.into_iter().filter(|f| f.fits(instance, kind)).collect())
    }

    pub async fn list_versions(&self, instance: &Instance, project_id: &str, kind: ContentKind) -> Result<Vec<VersionSummary>> {
        let id = parse_id(project_id).ok_or_else(invalid_project_id)?;
        let files = self.compatible_files(id, kind, instance).await?;
        Ok(files.into_iter().filter_map(|f| summarize(f, kind == ContentKind::Mod)).take(100).collect())
    }

    /// Änderungen einer Datei (HTML – das Frontend zeigt es nur bereinigt).
    pub async fn changelog(&self, project_id: &str, file_id: &str) -> Result<String> {
        let id = parse_id(project_id).ok_or_else(invalid_project_id)?;
        let file = parse_id(file_id).ok_or_else(invalid_file_id)?;
        let text: Envelope<String> = self.get(&format!("/mods/{id}/files/{file}/changelog")).await?;
        Ok(clip_markdown(text.data, MAX_CHANGELOG_CHARS))
    }
}

/// Neueste Datei im Update-Kanal (Liste neueste zuerst); gibt es dort nichts,
/// die neueste überhaupt – wie bei Modrinth.
pub(crate) fn newest_in_channel(files: Vec<RawFile>, channel: crate::instance::UpdateChannel) -> Option<RawFile> {
    let fallback = files.first().cloned();
    files.into_iter().find(|f| channel.allows(f.release_type())).or(fallback)
}

pub(crate) fn sort_newest_first(files: &mut [RawFile]) {
    files.sort_by(|a, b| b.file_date.cmp(&a.file_date).then(b.id.cmp(&a.id)));
}

fn parse<T: DeserializeOwned>(bytes: &[u8]) -> Result<T> {
    serde_json::from_slice(bytes).map_err(|e| {
        tracing::debug!("CurseForge-Antwort nicht lesbar: {e}");
        bad_response()
    })
}

/// Prozent-Kodierung für Query-Werte (alles außer unreservierten Zeichen).
fn urlencode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for b in value.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Baut die Query für `/mods/search` – streng aus geprüften Werten, JSON-Listen
/// per `serde_json`, alles prozentkodiert.
fn search_query(params: &SearchParams) -> Result<String> {
    modrinth::validate_search(params)?;
    let categories: Vec<u32> = params
        .categories
        .iter()
        .map(|c| c.parse::<u32>().ok().filter(|n| *n > 0))
        .collect::<Option<Vec<_>>>()
        .filter(|list| list.len() <= MAX_CATEGORIES)
        .ok_or_else(|| Error::validation(crate::msg!("modrinth.invalidCategory", "Ungültige Kategorie")))?;
    let limit = params.limit.clamp(1, MAX_PAGE_SIZE);
    if params.offset.saturating_add(limit) > MAX_RESULT_WINDOW {
        return Err(Error::validation(crate::msg!("modrinth.invalidPage", "Ungültige Seite")));
    }

    let mut pairs: Vec<(&str, String)> = vec![
        ("gameId", GAME_ID.to_string()),
        ("classId", class_of(params.kind).to_string()),
        ("sortField", sort_field(params.index).to_string()),
        ("sortOrder", "desc".into()),
        ("index", params.offset.to_string()),
        ("pageSize", limit.to_string()),
    ];
    let text: String = params.query.trim().chars().filter(|c| !c.is_control()).collect();
    if !text.is_empty() {
        pairs.push(("searchFilter", text));
    }
    match params.game_versions.as_slice() {
        [] => {}
        [one] => pairs.push(("gameVersion", one.clone())),
        many => pairs.push(("gameVersions", json(many)?)),
    }
    if params.kind.has_loaders() {
        let loaders: Vec<u8> = params.loaders.iter().filter_map(|l| mod_loader_type(l)).collect();
        match loaders.as_slice() {
            [] => {}
            [one] => pairs.push(("modLoaderType", one.to_string())),
            many => pairs.push(("modLoaderTypes", json(many)?)),
        }
    }
    if !categories.is_empty() {
        pairs.push(("categoryIds", json(&categories)?));
    }
    Ok(pairs.into_iter().map(|(k, v)| format!("{k}={}", urlencode(&v))).collect::<Vec<_>>().join("&"))
}

fn json<T: Serialize + ?Sized>(value: &T) -> Result<String> {
    serde_json::to_string(value).map_err(|e| Error::Internal(e.to_string()))
}

/// Schlüssel-freie Kurzinfo für die Oberfläche.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CurseForgeStatus {
    /// Hat dieser Build einen API-Schlüssel?
    pub available: bool,
}

/// Löst eine ID-Liste in Projekte auf (für Metadaten, Updates, Versionswechsel).
pub(crate) async fn mods_by_id(cf: &CurseForge, ids: &[u64]) -> Result<HashMap<u64, RawMod>> {
    Ok(cf.mods(ids).await?.into_iter().map(|m| (m.id, m)).collect())
}
