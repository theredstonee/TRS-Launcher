//! Modrinth (API v2): suchen, Projekte ansehen, Versionen wählen,
//! installieren, aktualisieren.

use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};

use crate::content::{self, ContentKind, ProjectMeta, Source};
use crate::download::{self, Task};
use crate::history::{self, HistoryEntry, HistoryKind};
use crate::icon::is_allowed_icon_url;
use crate::instance::{Instance, LoaderKind};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

pub(crate) const API: &str = "https://api.modrinth.com/v2";
pub(crate) const CDN_PREFIX: &str = "https://cdn.modrinth.com/";
const MAX_QUERY_LEN: usize = 100;
const MAX_DEPENDENCY_DEPTH: u8 = 4;
/// Projekt-Infos (Titel, Icon) so lange nicht neu laden.
const META_MAX_AGE_DAYS: i64 = 7;
/// Modrinth erlaubt lange Query-Strings, aber nicht beliebig lange.
const IDS_PER_REQUEST: usize = 100;
const MAX_CHANGELOG_CHARS: usize = 16_000;
const MAX_BODY_CHARS: usize = 100_000;
const MAX_ALL_VERSIONS: usize = 250;

/// Bewährte Client-Optimierungen. Pro Gruppe wird die erste Mod genommen, die
/// es für Version + Modloader der Instanz gibt – so landet nie Sodium UND
/// Embeddium in derselben Instanz.
const PERFORMANCE_PACK: &[&[&str]] = &[
    &["sodium", "embeddium"],
    &["lithium"],
    &["ferrite-core"],
    &["entityculling"],
    &["immediatelyfast"],
    &["modernfix"],
    &["dynamic-fps"],
    &["moreculling"],
    &["badoptimizations"],
    &["krypton"],
    // Nur für alte Versionen (bis 1.19) sinnvoll; neuere gibt es dort nicht.
    &["lazydfu"],
];

/// Was sich suchen lässt – Instanz-Inhalte plus Modpacks.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProjectKind {
    Mod,
    ResourcePack,
    ShaderPack,
    Modpack,
}

impl ProjectKind {
    fn modrinth_type(self) -> &'static str {
        match self {
            Self::Mod => "mod",
            Self::ResourcePack => "resourcepack",
            Self::ShaderPack => "shader",
            Self::Modpack => "modpack",
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchParams {
    pub query: String,
    pub kind: ProjectKind,
    /// Filtert auf Kompatibilität mit dieser Instanz.
    pub game_version: Option<String>,
    pub loader: Option<LoaderKind>,
    #[serde(default)]
    pub offset: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchHit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub categories: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub hits: Vec<SearchHit>,
    pub total_hits: u32,
    pub offset: u32,
    pub limit: u32,
}

#[derive(Deserialize)]
struct RawSearch {
    hits: Vec<RawHit>,
    total_hits: u32,
    offset: u32,
    limit: u32,
}

#[derive(Deserialize)]
struct RawHit {
    project_id: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    author: String,
    icon_url: Option<String>,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    categories: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct Version {
    pub id: String,
    pub project_id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub version_number: String,
    #[serde(default)]
    pub version_type: String,
    pub date_published: Option<DateTime<Utc>>,
    #[serde(default)]
    pub game_versions: Vec<String>,
    #[serde(default)]
    pub loaders: Vec<String>,
    #[serde(default)]
    pub files: Vec<VersionFile>,
    #[serde(default)]
    pub dependencies: Vec<Dependency>,
    #[serde(default)]
    pub changelog: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct VersionFile {
    pub url: String,
    pub filename: String,
    #[serde(default)]
    pub primary: bool,
    pub size: u64,
    pub hashes: Hashes,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct Hashes {
    pub sha1: String,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct Dependency {
    project_id: Option<String>,
    #[serde(default)]
    version_id: Option<String>,
    dependency_type: String,
}

impl Version {
    pub(crate) fn primary_file(&self) -> Option<&VersionFile> {
        self.files.iter().find(|f| f.primary).or(self.files.first())
    }
}

/// Eine Abhängigkeit, wie sie das Frontend sieht.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DependencyInfo {
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    /// `required`, `optional`, `incompatible` oder `embedded`.
    pub dependency_type: String,
}

/// Eine wählbare Version (für Versions-Dialog, Changelog und Detailansicht).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionSummary {
    pub id: String,
    pub name: String,
    pub version_number: String,
    /// `release`, `beta` oder `alpha`.
    pub version_type: String,
    pub date_published: Option<DateTime<Utc>>,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub file_name: String,
    pub size: u64,
    /// Markdown von Modrinth – das Frontend rendert es nur bereinigt.
    pub changelog: Option<String>,
    pub dependencies: Vec<DependencyInfo>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub kind: ContentKind,
    pub file_name: String,
    pub project_id: String,
    pub version_id: String,
    pub version_number: String,
}

/// Loader-Namen, mit denen Modrinth Mods für diese Instanz kennzeichnet.
/// Quilt lädt auch Fabric-Mods.
fn loader_tags(loader: LoaderKind) -> &'static [&'static str] {
    match loader {
        LoaderKind::Fabric => &["fabric"],
        LoaderKind::Quilt => &["quilt", "fabric"],
        LoaderKind::Forge => &["forge"],
        LoaderKind::NeoForge => &["neoforge"],
        LoaderKind::Vanilla => &[],
    }
}

pub(crate) fn is_safe_project_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn is_safe_game_version(v: &str) -> bool {
    crate::meta::is_safe_id(v) && v.len() <= 32
}

/// Externe Links (Quelltext, Wiki, Discord …): nur HTTPS, ohne Zugangsdaten.
pub fn is_safe_external_url(url: &str) -> bool {
    let Some(rest) = url.strip_prefix("https://") else { return false };
    let host = rest.split(['/', '?', '#']).next().unwrap_or("");
    url.len() <= 2048
        && !host.is_empty()
        && !host.contains('@')
        && host.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | ':'))
        && !url.chars().any(|c| c.is_whitespace() || c.is_control() || c == '\\')
}

fn json<T: Serialize + ?Sized>(value: &T) -> Result<String> {
    serde_json::to_string(value).map_err(|e| Error::Internal(e.to_string()))
}

fn clip(text: String, max: usize) -> String {
    text.chars().filter(|c| !c.is_control()).take(max).collect()
}

/// Wie [`clip`], behält aber Zeilenumbrüche (für Markdown).
fn clip_markdown(text: String, max: usize) -> String {
    text.chars().filter(|c| !c.is_control() || matches!(c, '\n' | '\t')).take(max).collect()
}

fn summarize(v: Version) -> Option<VersionSummary> {
    let file = v.primary_file()?.clone();
    if !is_safe_project_id(&v.id) {
        return None;
    }
    Some(VersionSummary {
        id: v.id,
        name: clip(v.name, 100),
        version_number: clip(v.version_number, 60),
        version_type: clip(v.version_type, 10),
        date_published: v.date_published,
        game_versions: v.game_versions.into_iter().take(200).map(|g| clip(g, 32)).collect(),
        loaders: v.loaders.into_iter().take(10).map(|l| clip(l, 20)).collect(),
        file_name: clip(file.filename, 200),
        size: file.size,
        changelog: v.changelog.map(|c| clip_markdown(c, MAX_CHANGELOG_CHARS)).filter(|c| !c.trim().is_empty()),
        dependencies: v
            .dependencies
            .into_iter()
            .take(50)
            .map(|d| DependencyInfo {
                project_id: d.project_id.filter(|id| is_safe_project_id(id)),
                version_id: d.version_id.filter(|id| is_safe_project_id(id)),
                dependency_type: clip(d.dependency_type, 20),
            })
            .collect(),
    })
}

pub async fn search(http: &reqwest::Client, params: &SearchParams) -> Result<SearchResult> {
    let query: String = params.query.trim().chars().filter(|c| !c.is_control()).take(MAX_QUERY_LEN).collect();

    // Facets als JSON bauen (nicht per String-Verkettung): innen ODER, außen UND.
    let mut facets = vec![vec![format!("project_type:{}", params.kind.modrinth_type())]];
    if let Some(v) = params.game_version.as_deref().filter(|v| is_safe_game_version(v)) {
        facets.push(vec![format!("versions:{v}")]);
    }
    if matches!(params.kind, ProjectKind::Mod | ProjectKind::Modpack)
        && let Some(loader) = params.loader
    {
        let tags = loader_tags(loader);
        if !tags.is_empty() {
            facets.push(tags.iter().map(|t| format!("categories:{t}")).collect());
        }
    }

    let raw: RawSearch = http
        .get(format!("{API}/search"))
        .query(&[
            ("query", query.as_str()),
            ("facets", json(&facets)?.as_str()),
            ("index", if query.is_empty() { "downloads" } else { "relevance" }),
            ("offset", &params.offset.min(10_000).to_string()),
            ("limit", "20"),
        ])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    Ok(SearchResult {
        total_hits: raw.total_hits,
        offset: raw.offset,
        limit: raw.limit,
        hits: raw
            .hits
            .into_iter()
            .filter(|h| is_safe_project_id(&h.project_id))
            .map(|h| SearchHit {
                project_id: h.project_id,
                slug: clip(h.slug, 100),
                title: clip(h.title, 100),
                description: clip(h.description, 400),
                author: clip(h.author, 60),
                // Bilder nur von Modrinths eigenem CDN.
                icon_url: h.icon_url.filter(|u| is_allowed_icon_url(u)),
                downloads: h.downloads,
                categories: h.categories.into_iter().take(12).map(|c| clip(c, 30)).collect(),
            })
            .collect(),
    })
}

// --- Projekte ------------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
struct RawProject {
    id: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    project_type: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    body: String,
    icon_url: Option<String>,
    #[serde(default)]
    team: String,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    followers: u64,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    additional_categories: Vec<String>,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    gallery: Vec<RawGalleryImage>,
    updated: Option<DateTime<Utc>>,
    published: Option<DateTime<Utc>>,
    license: Option<RawLicense>,
    #[serde(default)]
    client_side: String,
    #[serde(default)]
    server_side: String,
    source_url: Option<String>,
    issues_url: Option<String>,
    wiki_url: Option<String>,
    discord_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct RawGalleryImage {
    url: String,
    #[serde(default)]
    featured: bool,
    title: Option<String>,
    description: Option<String>,
    #[serde(default)]
    ordering: i64,
}

#[derive(Debug, Clone, Deserialize)]
struct RawLicense {
    #[serde(default)]
    id: String,
    #[serde(default)]
    name: String,
}

#[derive(Debug, Clone, Deserialize)]
struct RawMember {
    #[serde(default)]
    team_id: String,
    user: RawUser,
    #[serde(default)]
    role: String,
    #[serde(default)]
    ordering: i64,
}

#[derive(Debug, Clone, Deserialize)]
struct RawUser {
    username: String,
}

/// Kurzinfo zu einem Projekt (Abhängigkeiten, Inhalte-Liste).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectCard {
    pub project_id: String,
    pub slug: String,
    pub project_type: String,
    pub title: String,
    pub description: String,
    pub author: Option<String>,
    pub icon_url: Option<String>,
    pub downloads: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GalleryImage {
    pub url: String,
    pub title: Option<String>,
    pub description: Option<String>,
    pub featured: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectLink {
    /// `source`, `issues`, `wiki`, `discord`, `modrinth`.
    pub kind: &'static str,
    pub url: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectDetails {
    pub project_id: String,
    pub slug: String,
    pub project_type: String,
    pub title: String,
    pub description: String,
    /// Markdown – nur bereinigt anzeigen.
    pub body: String,
    pub author: Option<String>,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub followers: u64,
    pub categories: Vec<String>,
    pub loaders: Vec<String>,
    pub game_versions: Vec<String>,
    pub gallery: Vec<GalleryImage>,
    pub updated: Option<DateTime<Utc>>,
    pub published: Option<DateTime<Utc>>,
    pub license: Option<String>,
    pub client_side: String,
    pub server_side: String,
    pub links: Vec<ProjectLink>,
}

/// Wer als Autor angezeigt wird: der Besitzer, sonst das erste Mitglied.
fn pick_author(members: &[RawMember]) -> Option<String> {
    members
        .iter()
        .find(|m| m.role.eq_ignore_ascii_case("owner"))
        .or_else(|| members.iter().min_by_key(|m| m.ordering))
        .map(|m| clip(m.user.username.clone(), 60))
        .filter(|name| !name.is_empty())
}

fn safe_icon(url: Option<String>) -> Option<String> {
    url.filter(|u| is_allowed_icon_url(u))
}

/// Mehrere Projekte (plus Autoren) in zwei Anfragen.
async fn fetch_projects(http: &reqwest::Client, ids: &[String]) -> Result<Vec<(RawProject, Option<String>)>> {
    let ids: Vec<&String> = ids.iter().filter(|id| is_safe_project_id(id)).collect();
    let mut out = Vec::new();
    for chunk in ids.chunks(IDS_PER_REQUEST) {
        let projects: Vec<RawProject> = http
            .get(format!("{API}/projects"))
            .query(&[("ids", json(chunk)?)])
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let projects: Vec<RawProject> = projects.into_iter().filter(|p| is_safe_project_id(&p.id)).collect();

        let team_ids: Vec<&String> = projects.iter().map(|p| &p.team).filter(|t| is_safe_project_id(t)).collect();
        let mut authors: HashMap<String, String> = HashMap::new();
        if !team_ids.is_empty() {
            // Ohne Autoren geht es auch – Fehler hier nicht durchreichen.
            let ids = json(&team_ids)?;
            let teams: Vec<Vec<RawMember>> = async {
                http.get(format!("{API}/teams")).query(&[("ids", ids)]).send().await?.error_for_status()?.json().await
            }
            .await
            .unwrap_or_else(|e: reqwest::Error| {
                tracing::debug!("Teams konnten nicht geladen werden: {e}");
                Vec::new()
            });
            for members in teams {
                let Some(team_id) = members.first().map(|m| m.team_id.clone()) else { continue };
                if let Some(author) = pick_author(&members) {
                    authors.insert(team_id, author);
                }
            }
        }
        out.extend(projects.into_iter().map(|p| {
            let author = authors.get(&p.team).cloned();
            (p, author)
        }));
    }
    Ok(out)
}

pub async fn project_cards(http: &reqwest::Client, ids: &[String]) -> Result<Vec<ProjectCard>> {
    if ids.len() > 200 {
        return Err(Error::validation("Zu viele Projekte auf einmal"));
    }
    Ok(fetch_projects(http, ids)
        .await?
        .into_iter()
        .map(|(p, author)| ProjectCard {
            project_id: p.id,
            slug: clip(p.slug, 100),
            project_type: clip(p.project_type, 20),
            title: clip(p.title, 100),
            description: clip(p.description, 400),
            author,
            icon_url: safe_icon(p.icon_url),
            downloads: p.downloads,
        })
        .collect())
}

/// Alles für die Detailansicht eines Projekts. `id_or_slug` wie bei Modrinth.
pub async fn project_details(http: &reqwest::Client, id_or_slug: &str) -> Result<ProjectDetails> {
    if !is_safe_project_id(id_or_slug) {
        return Err(Error::validation("Ungültige Projekt-ID"));
    }
    let p: RawProject =
        http.get(format!("{API}/project/{id_or_slug}")).send().await?.error_for_status()?.json().await?;
    if !is_safe_project_id(&p.id) {
        return Err(Error::validation("Ungültige Projekt-ID"));
    }
    let members: Vec<RawMember> = match http.get(format!("{API}/project/{}/members", p.id)).send().await {
        Ok(resp) if resp.status().is_success() => resp.json().await.unwrap_or_default(),
        _ => Vec::new(),
    };

    let mut gallery = p.gallery.clone();
    gallery.sort_by_key(|g| (!g.featured, g.ordering));
    let mut links = Vec::new();
    for (kind, url) in [("source", &p.source_url), ("issues", &p.issues_url), ("wiki", &p.wiki_url), ("discord", &p.discord_url)] {
        if let Some(url) = url.as_ref().filter(|u| is_safe_external_url(u)) {
            links.push(ProjectLink { kind, url: url.clone() });
        }
    }
    let modrinth_type = match p.project_type.as_str() {
        t @ ("mod" | "modpack" | "resourcepack" | "shader" | "plugin" | "datapack") => t,
        _ => "project",
    };
    if is_safe_project_id(&p.slug) {
        links.push(ProjectLink { kind: "modrinth", url: format!("https://modrinth.com/{modrinth_type}/{}", p.slug) });
    }

    Ok(ProjectDetails {
        author: pick_author(&members),
        project_id: p.id,
        slug: clip(p.slug, 100),
        project_type: clip(p.project_type, 20),
        title: clip(p.title, 100),
        description: clip(p.description, 400),
        body: clip_markdown(p.body, MAX_BODY_CHARS),
        icon_url: safe_icon(p.icon_url),
        downloads: p.downloads,
        followers: p.followers,
        categories: p.categories.into_iter().chain(p.additional_categories).take(20).map(|c| clip(c, 30)).collect(),
        loaders: p.loaders.into_iter().take(20).map(|c| clip(c, 20)).collect(),
        game_versions: p.game_versions.into_iter().rev().take(300).map(|c| clip(c, 32)).collect(),
        gallery: gallery
            .into_iter()
            .filter(|g| is_allowed_icon_url(&g.url))
            .take(60)
            .map(|g| GalleryImage {
                url: g.url,
                title: g.title.map(|t| clip(t, 120)).filter(|t| !t.is_empty()),
                description: g.description.map(|t| clip(t, 400)).filter(|t| !t.is_empty()),
                featured: g.featured,
            })
            .collect(),
        updated: p.updated,
        published: p.published,
        license: p.license.map(|l| clip(if l.name.is_empty() { l.id } else { l.name }, 60)).filter(|l| !l.is_empty()),
        client_side: clip(p.client_side, 20),
        server_side: clip(p.server_side, 20),
        links,
    })
}

/// Alle Versionen eines Projekts (für die Detailansicht, dort gefiltert).
pub async fn project_versions(http: &reqwest::Client, project_id: &str) -> Result<Vec<VersionSummary>> {
    if !is_safe_project_id(project_id) {
        return Err(Error::validation("Ungültige Projekt-ID"));
    }
    let versions: Vec<Version> =
        http.get(format!("{API}/project/{project_id}/version")).send().await?.error_for_status()?.json().await?;
    Ok(versions.into_iter().take(MAX_ALL_VERSIONS).filter_map(summarize).collect())
}

// --- Versionen & Installation ------------------------------------------------------

/// Alle zur Instanz passenden Versionen, neueste zuerst.
async fn compatible_versions(
    http: &reqwest::Client,
    project_id: &str,
    kind: ContentKind,
    instance: &Instance,
) -> Result<Vec<Version>> {
    let mut query = vec![("game_versions", json(&[&instance.game_version])?)];
    if kind == ContentKind::Mod {
        query.push(("loaders", json(loader_tags(instance.loader.kind))?));
    }
    Ok(http
        .get(format!("{API}/project/{project_id}/version"))
        .query(&query)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?)
}

pub(crate) async fn version_by_id(http: &reqwest::Client, version_id: &str) -> Result<Version> {
    if !is_safe_project_id(version_id) {
        return Err(Error::validation("Ungültige Versions-ID"));
    }
    Ok(http.get(format!("{API}/version/{version_id}")).send().await?.error_for_status()?.json().await?)
}

pub async fn list_versions(
    http: &reqwest::Client,
    instance: &Instance,
    project_id: &str,
    kind: ContentKind,
) -> Result<Vec<VersionSummary>> {
    if !is_safe_project_id(project_id) {
        return Err(Error::validation("Ungültige Projekt-ID"));
    }
    let versions = compatible_versions(http, project_id, kind, instance).await?;
    Ok(versions.into_iter().filter_map(summarize).take(100).collect())
}

/// Versionen, die neuer sind als die installierte – `versions` neueste zuerst.
/// Ist die installierte nicht dabei, entscheidet das Datum.
fn newer_than(versions: Vec<Version>, installed_id: &str, installed_date: Option<DateTime<Utc>>) -> Vec<Version> {
    if let Some(pos) = versions.iter().position(|v| v.id == installed_id) {
        return versions.into_iter().take(pos).collect();
    }
    match installed_date {
        Some(date) => versions.into_iter().filter(|v| v.date_published.is_some_and(|d| d > date)).collect(),
        None => versions,
    }
}

/// Änderungen zwischen der installierten und der neuesten passenden Version.
pub async fn changelog_since(
    http: &reqwest::Client,
    instance: &Instance,
    project_id: &str,
    kind: ContentKind,
    installed_version_id: &str,
) -> Result<Vec<VersionSummary>> {
    if !is_safe_project_id(project_id) || !is_safe_project_id(installed_version_id) {
        return Err(Error::validation("Ungültige Projekt-ID"));
    }
    let versions = compatible_versions(http, project_id, kind, instance).await?;
    let installed_date = if versions.iter().any(|v| v.id == installed_version_id) {
        None
    } else {
        version_by_id(http, installed_version_id).await.ok().and_then(|v| v.date_published)
    };
    Ok(newer_than(versions, installed_version_id, installed_date).into_iter().filter_map(summarize).take(50).collect())
}

fn ensure_mods_allowed(kind: ContentKind, instance: &Instance) -> Result<()> {
    if kind == ContentKind::Mod && loader_tags(instance.loader.kind).is_empty() {
        return Err(Error::validation(
            "Diese Instanz ist Vanilla – Mods brauchen eine Instanz mit Fabric, Quilt, Forge oder NeoForge.",
        ));
    }
    Ok(())
}

/// Was eine einzelne Installation verändert hat (für Verlauf und Projekt-Infos).
struct Installed {
    file_name: String,
    project_id: String,
    version_number: String,
    previous: Option<Source>,
    dependency: bool,
}

/// Installiert eine Version samt Pflicht-Abhängigkeiten. Ohne `version_id`
/// die neueste passende. Liefert die Namen der neu installierten Dateien.
pub async fn install(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    project_id: &str,
    kind: ContentKind,
    version_id: Option<&str>,
) -> Result<Vec<String>> {
    if !is_safe_project_id(project_id) {
        return Err(Error::validation("Ungültige Projekt-ID"));
    }
    ensure_mods_allowed(kind, instance)?;

    let mut installed = Vec::new();
    let mut visited: HashSet<String> = content::installed_project_ids(paths, &instance.id).await?.into_iter().collect();

    // Das angefragte Projekt selbst: gewählte oder neueste Version (auch als Update).
    let root = match version_id {
        Some(id) => {
            let version = version_by_id(http, id).await?;
            // Slug oder ID: bei einer ID muss sie zur Version passen.
            if version.project_id != project_id && !compatible_versions(http, project_id, kind, instance).await?.iter().any(|v| v.id == version.id) {
                return Err(Error::validation("Diese Version gehört nicht zu dem Projekt."));
            }
            version
        }
        None => compatible_versions(http, project_id, kind, instance).await?.into_iter().next().ok_or_else(|| {
            Error::validation(format!(
                "Für Minecraft {} mit diesem Modloader gibt es keine passende Version.",
                instance.game_version
            ))
        })?,
    };
    visited.insert(root.project_id.clone());
    let mut queue = dependencies_of(&root, kind, 0);
    installed.push(install_version(http, paths, instance, kind, &root, None, false).await?);

    while let Some((id, depth)) = queue.pop() {
        if !visited.insert(id.clone()) {
            continue;
        }
        let Some(version) = compatible_versions(http, &id, ContentKind::Mod, instance).await?.into_iter().next() else {
            tracing::warn!("Abhängigkeit {id} hat keine passende Version – übersprungen");
            continue;
        };
        queue.extend(dependencies_of(&version, ContentKind::Mod, depth));
        installed.push(install_version(http, paths, instance, ContentKind::Mod, &version, None, true).await?);
    }

    after_install(http, paths, &instance.id, &installed).await;
    Ok(installed.into_iter().map(|i| i.file_name).collect())
}

fn dependencies_of(version: &Version, kind: ContentKind, depth: u8) -> Vec<(String, u8)> {
    if kind != ContentKind::Mod || depth >= MAX_DEPENDENCY_DEPTH {
        return Vec::new();
    }
    version
        .dependencies
        .iter()
        .filter(|d| d.dependency_type == "required")
        .filter_map(|d| d.project_id.clone().filter(|id| is_safe_project_id(id)))
        .map(|id| (id, depth + 1))
        .collect()
}

/// `replace`: Datei, die durch diese Version ersetzt wird (Update einer von
/// Hand hinzugefügten Mod, die nicht im Herkunfts-Index steht).
async fn install_version(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    kind: ContentKind,
    version: &Version,
    replace: Option<&str>,
    dependency: bool,
) -> Result<Installed> {
    let file = version.primary_file().ok_or_else(|| Error::validation("Diese Version enthält keine Datei."))?;

    content::validate_file_name(kind, &file.filename)?;
    if !file.url.starts_with(CDN_PREFIX) {
        return Err(Error::download(&file.url, "Download liegt nicht auf Modrinths CDN"));
    }

    let dir = content::content_dir(paths, &instance.id, kind);
    fsutil::ensure_dir(&dir).await?;

    download::fetch_one(
        http,
        &Task {
            url: file.url.clone(),
            path: dir.join(&file.filename),
            sha1: Some(file.hashes.sha1.clone()),
            size: Some(file.size),
        },
    )
    .await?;

    let previous = content::source_of_project(paths, &instance.id, &version.project_id).await;

    // Erst nach erfolgreichem Download die alte(n) Datei(en) entfernen.
    let mut obsolete = content::files_of_project(paths, &instance.id, &version.project_id).await;
    if let Some(old) = replace {
        obsolete.push((kind, old.to_owned()));
    }
    let replaced_any = obsolete.iter().any(|(_, old)| old != &file.filename);
    for (old_kind, old_file) in obsolete {
        if old_file != file.filename {
            content::remove_file(paths, &instance.id, old_kind, &old_file).await?;
        }
    }

    let version_number = clip(version.version_number.clone(), 60);
    content::remember_source(
        paths,
        &instance.id,
        kind,
        &file.filename,
        Source {
            project_id: version.project_id.clone(),
            version_id: version.id.clone(),
            version_number: Some(version_number.clone()).filter(|v| !v.is_empty()),
        },
    )
    .await?;
    Ok(Installed {
        file_name: file.filename.clone(),
        project_id: version.project_id.clone(),
        version_number,
        // Von Hand ersetzte Datei ohne Index-Eintrag zählt auch als Update.
        previous: previous.or_else(|| {
            replaced_any.then(|| Source { project_id: String::new(), version_id: String::new(), version_number: None })
        }),
        dependency,
    })
}

/// Projekt-Infos nachladen und den Verlauf schreiben.
async fn after_install(http: &reqwest::Client, paths: &Paths, instance_id: &str, installed: &[Installed]) {
    let ids: Vec<String> = installed.iter().map(|i| i.project_id.clone()).collect();
    if let Err(e) = store_project_meta(http, paths, instance_id, &ids).await {
        tracing::debug!("Projekt-Infos konnten nicht geladen werden: {e}");
    }
    let index = content::read_index(paths, instance_id).await;
    for item in installed {
        let title = index.projects.get(&item.project_id).map(|p| p.title.clone()).unwrap_or_else(|| item.file_name.clone());
        let entry = match &item.previous {
            Some(prev) => {
                let mut e = HistoryEntry::new(HistoryKind::ModUpdated).subject(&title).to(&item.version_number);
                if let Some(from) = &prev.version_number {
                    e = e.from(from);
                }
                e
            }
            None => {
                let e = HistoryEntry::new(HistoryKind::ModInstalled).subject(&title).to(&item.version_number);
                if item.dependency { e.detail("dependency") } else { e }
            }
        };
        history::record(paths, instance_id, entry).await;
    }
}

/// Lädt Titel, Autor und Icon für diese Projekte und legt sie im Index ab.
async fn store_project_meta(http: &reqwest::Client, paths: &Paths, instance_id: &str, ids: &[String]) -> Result<()> {
    if ids.is_empty() {
        return Ok(());
    }
    let fetched = fetch_projects(http, ids).await?;
    let now = Utc::now();
    content::modify_index(paths, instance_id, |index| {
        for (p, author) in fetched {
            index.projects.insert(
                p.id.clone(),
                ProjectMeta {
                    title: clip(p.title, 100),
                    slug: clip(p.slug, 100),
                    author,
                    description: Some(clip(p.description, 300)).filter(|d| !d.is_empty()),
                    icon_url: safe_icon(p.icon_url),
                    fetched_at: now,
                },
            );
        }
    })
    .await
}

/// Ergänzt die Inhalte-Liste um Modrinth-Infos: erkennt von Hand abgelegte
/// Dateien per Hash, lädt Titel/Autor/Icon und merkt sich alles im Index.
/// Liefert `true`, wenn sich etwas geändert hat.
pub async fn refresh_metadata(http: &reqwest::Client, paths: &Paths, instance: &Instance) -> Result<bool> {
    let index = content::read_index(paths, &instance.id).await;
    let mut changed = false;

    // 1. Unbekannte Dateien per SHA1 identifizieren.
    let mut by_hash: HashMap<String, (ContentKind, String, String)> = HashMap::new();
    let mut unknown_prints: Vec<(String, String)> = Vec::new();
    for kind in ContentKind::ALL {
        for item in content::list(paths, &instance.id, kind).await? {
            let key = content::index_key(kind, &item.file_name);
            if index.files.contains_key(&key) {
                continue;
            }
            let Some(path) = content::existing_file(paths, &instance.id, kind, &item.file_name) else { continue };
            let Ok(meta) = tokio::fs::metadata(&path).await else { continue };
            let print = content::fingerprint(&meta);
            if index.unknown.get(&key) == Some(&print) {
                continue;
            }
            if let Ok(hash) = download::sha1_of_file(&path).await {
                by_hash.insert(hash, (kind, item.file_name.clone(), print.clone()));
            }
            unknown_prints.push((key, print));
        }
    }
    if !by_hash.is_empty() {
        let hashes: Vec<&String> = by_hash.keys().collect();
        let found: HashMap<String, Version> = http
            .post(format!("{API}/version_files"))
            .json(&serde_json::json!({ "hashes": hashes, "algorithm": "sha1" }))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let mut identified = Vec::new();
        for (hash, version) in found {
            let Some((kind, file_name, _)) = by_hash.get(&hash) else { continue };
            if is_safe_project_id(&version.project_id) && is_safe_project_id(&version.id) {
                identified.push((content::index_key(*kind, file_name), Source {
                    project_id: version.project_id,
                    version_id: version.id,
                    version_number: Some(clip(version.version_number, 60)).filter(|v| !v.is_empty()),
                }));
            }
        }
        content::modify_index(paths, &instance.id, |index| {
            for (key, print) in unknown_prints {
                if !identified.iter().any(|(k, _)| k == &key) {
                    index.unknown.insert(key, print);
                }
            }
            for (key, source) in identified {
                index.unknown.remove(&key);
                index.files.insert(key, source);
            }
        })
        .await?;
        changed = true;
    }

    // 2. Projekt-Infos für alle Projekte ohne (frische) Infos laden.
    let index = content::read_index(paths, &instance.id).await;
    let stale = Utc::now() - Duration::days(META_MAX_AGE_DAYS);
    let mut missing: Vec<String> = index
        .files
        .values()
        .map(|s| s.project_id.clone())
        .filter(|id| index.projects.get(id).is_none_or(|p| p.fetched_at < stale))
        .collect();
    missing.sort();
    missing.dedup();
    if !missing.is_empty() {
        store_project_meta(http, paths, &instance.id, &missing).await?;
        changed = true;
    }
    Ok(changed)
}

/// Installiert das Performance-Paket; nicht verfügbare Mods werden übersprungen.
pub async fn install_performance_pack(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
) -> Result<Vec<String>> {
    ensure_mods_allowed(ContentKind::Mod, instance)?;
    let mut installed = Vec::new();
    for group in PERFORMANCE_PACK {
        for slug in *group {
            match install(http, paths, instance, slug, ContentKind::Mod, None).await {
                Ok(files) => {
                    installed.extend(files);
                    break;
                }
                // Keine passende Version / Projekt unbekannt: nächste Alternative.
                Err(Error::Validation(_)) => {}
                Err(Error::Http(e)) if e.status() == Some(reqwest::StatusCode::NOT_FOUND) => {}
                Err(e) => return Err(e),
            }
        }
    }
    if installed.is_empty() {
        return Err(Error::validation("Für diese Version gibt es keine der Optimierungs-Mods."));
    }
    installed.sort();
    installed.dedup();
    Ok(installed)
}

/// Neueste zur Instanz passende Version je Datei-Hash.
async fn latest_for_hashes(
    http: &reqwest::Client,
    instance: &Instance,
    kind: ContentKind,
    hashes: &[&String],
) -> Result<HashMap<String, Version>> {
    let mut body = serde_json::json!({
        "hashes": hashes,
        "algorithm": "sha1",
        "game_versions": [instance.game_version],
    });
    if kind == ContentKind::Mod {
        body["loaders"] = serde_json::json!(loader_tags(instance.loader.kind));
    }
    Ok(http.post(format!("{API}/version_files/update")).json(&body).send().await?.error_for_status()?.json().await?)
}

/// Sucht per Datei-Hash nach neueren Versionen – funktioniert auch für Mods,
/// die von Hand in den Ordner gelegt wurden.
pub async fn check_updates(http: &reqwest::Client, paths: &Paths, instance: &Instance) -> Result<Vec<UpdateInfo>> {
    let mut updates = Vec::new();
    for kind in ContentKind::ALL {
        if kind == ContentKind::Mod && loader_tags(instance.loader.kind).is_empty() {
            continue;
        }
        let items = content::list(paths, &instance.id, kind).await?;
        if items.is_empty() {
            continue;
        }

        let mut by_hash = HashMap::new();
        for item in items {
            if let Some(path) = content::existing_file(paths, &instance.id, kind, &item.file_name)
                && let Ok(hash) = download::sha1_of_file(&path).await
            {
                by_hash.insert(hash, item.file_name);
            }
        }

        let latest = latest_for_hashes(http, instance, kind, &by_hash.keys().collect::<Vec<_>>()).await?;
        for (hash, version) in latest {
            let Some(file_name) = by_hash.get(&hash) else { continue };
            let is_newer = version.primary_file().is_some_and(|f| !f.hashes.sha1.eq_ignore_ascii_case(&hash));
            if is_newer && is_safe_project_id(&version.project_id) && is_safe_project_id(&version.id) {
                updates.push(UpdateInfo {
                    kind,
                    file_name: file_name.clone(),
                    project_id: version.project_id,
                    version_id: version.id,
                    version_number: clip(version.version_number, 60),
                });
            }
        }
    }
    updates.sort_by_key(|u| u.file_name.to_lowercase());
    Ok(updates)
}

/// Ersetzt `file_name` durch die angegebene Version (Update, Wechsel oder
/// Downgrade).
pub async fn apply_update(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    kind: ContentKind,
    file_name: &str,
    version_id: &str,
) -> Result<String> {
    content::validate_file_name(kind, file_name)?;
    let version = version_by_id(http, version_id).await?;
    let installed = install_version(http, paths, instance, kind, &version, Some(file_name), false).await?;
    let file = installed.file_name.clone();
    after_install(http, paths, &instance.id, &[installed]).await;
    Ok(file)
}

// --- Versionswechsel der Instanz --------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MigrationStatus {
    /// Die installierte Datei passt schon zur neuen Version.
    Compatible,
    /// Es gibt eine passende Version – `target_*` gesetzt.
    Update,
    /// Für die neue Version/den neuen Loader gibt es nichts.
    Missing,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MigrationItem {
    pub kind: ContentKind,
    pub file_name: String,
    pub title: String,
    pub icon_url: Option<String>,
    pub project_id: String,
    pub current_version: Option<String>,
    pub status: MigrationStatus,
    pub target_version_id: Option<String>,
    pub target_version_number: Option<String>,
}

/// Für alle über Modrinth installierten Inhalte: passt die Datei nach einem
/// Versionswechsel noch, gibt es eine passende Version, oder gar keine?
pub async fn plan_migration(http: &reqwest::Client, paths: &Paths, instance: &Instance) -> Result<Vec<MigrationItem>> {
    let mut plan = Vec::new();
    for kind in ContentKind::ALL {
        if kind == ContentKind::Mod && loader_tags(instance.loader.kind).is_empty() {
            continue;
        }
        let items: Vec<_> = content::list(paths, &instance.id, kind).await?.into_iter().filter(|i| i.source.is_some()).collect();
        let mut by_hash = HashMap::new();
        for item in items {
            if let Some(path) = content::existing_file(paths, &instance.id, kind, &item.file_name)
                && let Ok(hash) = download::sha1_of_file(&path).await
            {
                by_hash.insert(hash, item);
            }
        }
        if by_hash.is_empty() {
            continue;
        }
        let latest = latest_for_hashes(http, instance, kind, &by_hash.keys().collect::<Vec<_>>()).await?;
        for (hash, item) in by_hash {
            let Some(source) = item.source.clone() else { continue };
            let found = latest.get(&hash).filter(|v| is_safe_project_id(&v.id));
            let (status, target) = match found {
                None => (MigrationStatus::Missing, None),
                Some(v) if v.primary_file().is_some_and(|f| f.hashes.sha1.eq_ignore_ascii_case(&hash)) => {
                    (MigrationStatus::Compatible, None)
                }
                Some(v) => (MigrationStatus::Update, Some(v)),
            };
            plan.push(MigrationItem {
                kind,
                title: item.title.clone().unwrap_or_else(|| item.file_name.clone()),
                icon_url: item.icon_url.clone().filter(|u| is_allowed_icon_url(u)),
                file_name: item.file_name,
                project_id: source.project_id,
                current_version: item.version.or(source.version_number),
                status,
                target_version_id: target.map(|v| v.id.clone()),
                target_version_number: target.map(|v| clip(v.version_number.clone(), 60)),
            });
        }
    }
    plan.sort_by_key(|p| (p.status != MigrationStatus::Missing, p.title.to_lowercase()));
    Ok(plan)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn version(id: &str, day: u32) -> Version {
        serde_json::from_value(serde_json::json!({
            "id": id, "project_id": "p", "date_published": format!("2026-01-{day:02}T00:00:00Z"),
            "changelog": format!("Änderungen in {id}\n\n- Punkt"),
            "files": [{"url": "https://cdn.modrinth.com/x.jar", "filename": "x.jar", "size": 1, "hashes": {"sha1": "a"}}]
        }))
        .unwrap()
    }

    #[test]
    fn id_validation() {
        assert!(is_safe_project_id("AANobbMI"));
        assert!(is_safe_project_id("fabric-api"));
        for bad in ["", "../x", "a/b", "a?b=c", "a b"] {
            assert!(!is_safe_project_id(bad), "{bad:?}");
        }
    }

    #[test]
    fn quilt_accepts_fabric_mods() {
        assert_eq!(loader_tags(LoaderKind::Quilt), ["quilt", "fabric"]);
        assert!(loader_tags(LoaderKind::Vanilla).is_empty());
    }

    #[test]
    fn performance_pack_never_pairs_renderers() {
        let group = PERFORMANCE_PACK.iter().find(|g| g.contains(&"sodium")).unwrap();
        assert!(group.contains(&"embeddium"));
        let all: Vec<_> = PERFORMANCE_PACK.iter().flat_map(|g| g.iter()).collect();
        assert!(all.iter().all(|s| is_safe_project_id(s)));
    }

    #[test]
    fn parses_version_payload_and_dependencies() {
        let v: Vec<Version> = serde_json::from_str(
            r#"[{"id":"v1","project_id":"P7dR8mSH","name":"x","version_number":"1","version_type":"release",
                "date_published":"2026-01-02T03:04:05.123456Z","game_versions":["1.21.1"],"loaders":["fabric"],
                "files":[{"url":"https://cdn.modrinth.com/data/P7dR8mSH/versions/v1/extra.jar","filename":"extra.jar",
                          "primary":false,"size":1,"hashes":{"sha1":"cc","sha512":"dd"}},
                         {"url":"https://cdn.modrinth.com/data/P7dR8mSH/versions/v1/fabric-api.jar",
                          "filename":"fabric-api.jar","primary":true,"size":10,"hashes":{"sha1":"aa","sha512":"bb"}}],
                "dependencies":[{"project_id":"abc","version_id":null,"dependency_type":"required"},
                                {"project_id":"opt","version_id":null,"dependency_type":"optional"},
                                {"project_id":null,"version_id":null,"dependency_type":"embedded"}]}]"#,
        )
        .unwrap();
        assert_eq!(v[0].primary_file().unwrap().filename, "fabric-api.jar");
        assert_eq!(dependencies_of(&v[0], ContentKind::Mod, 0), [("abc".to_owned(), 1)]);
        assert!(dependencies_of(&v[0], ContentKind::ResourcePack, 0).is_empty());
        assert!(dependencies_of(&v[0], ContentKind::Mod, MAX_DEPENDENCY_DEPTH).is_empty());

        let summary = summarize(v[0].clone()).unwrap();
        assert_eq!(summary.dependencies.len(), 3);
        assert!(summary.changelog.is_none());
    }

    #[test]
    fn changelog_range() {
        let all = || vec![version("v4", 4), version("v3", 3), version("v2", 2), version("v1", 1)];
        let ids = |list: Vec<Version>| list.into_iter().map(|v| v.id).collect::<Vec<_>>();
        assert_eq!(ids(newer_than(all(), "v2", None)), ["v4", "v3"]);
        assert!(newer_than(all(), "v4", None).is_empty());
        // Installierte Version passt nicht mehr zur Instanz: nach Datum.
        let date = version("x", 2).date_published;
        assert_eq!(ids(newer_than(all(), "unbekannt", date)), ["v4", "v3"]);
        assert_eq!(newer_than(all(), "unbekannt", None).len(), 4);
        // Zeilenumbrüche bleiben für Markdown erhalten.
        assert!(summarize(version("v1", 1)).unwrap().changelog.unwrap().contains("\n- Punkt"));
    }

    #[test]
    fn external_links() {
        assert!(is_safe_external_url("https://github.com/CaffeineMC/sodium"));
        assert!(is_safe_external_url("https://discord.gg/abc?x=1"));
        for bad in [
            "http://github.com/x",
            "javascript:alert(1)",
            "https://user:pw@evil.example/",
            "https:///x",
            "https://exa mple.com",
            "file:///C:/Windows",
            "https://evil.example\\@github.com",
        ] {
            assert!(!is_safe_external_url(bad), "{bad:?}");
        }
    }

    #[test]
    fn author_prefers_owner() {
        let members: Vec<RawMember> = serde_json::from_str(
            r#"[{"team_id":"t","user":{"username":"helper"},"role":"Developer","ordering":0},
                {"team_id":"t","user":{"username":"boss"},"role":"Owner","ordering":1}]"#,
        )
        .unwrap();
        assert_eq!(pick_author(&members).as_deref(), Some("boss"));
        assert_eq!(pick_author(&members[..1]).as_deref(), Some("helper"));
        assert!(pick_author(&[]).is_none());
    }
}
