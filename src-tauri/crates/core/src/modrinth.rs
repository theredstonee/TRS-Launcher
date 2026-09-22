//! Modrinth (API v2): suchen, Versionen wählen, installieren, aktualisieren.

use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::content::{self, ContentKind, Source};
use crate::download::{self, Task};
use crate::instance::{Instance, LoaderKind};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

pub(crate) const API: &str = "https://api.modrinth.com/v2";
pub(crate) const CDN_PREFIX: &str = "https://cdn.modrinth.com/";
const MAX_QUERY_LEN: usize = 100;
const MAX_DEPENDENCY_DEPTH: u8 = 4;

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
    dependency_type: String,
}

impl Version {
    pub(crate) fn primary_file(&self) -> Option<&VersionFile> {
        self.files.iter().find(|f| f.primary).or(self.files.first())
    }
}

/// Eine wählbare Version (für den Versions-Dialog).
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

fn json<T: Serialize + ?Sized>(value: &T) -> Result<String> {
    serde_json::to_string(value).map_err(|e| Error::Internal(e.to_string()))
}

fn clip(text: String, max: usize) -> String {
    text.chars().filter(|c| !c.is_control()).take(max).collect()
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
                icon_url: h.icon_url.filter(|u| u.starts_with(CDN_PREFIX)),
                downloads: h.downloads,
                categories: h.categories.into_iter().take(12).map(|c| clip(c, 30)).collect(),
            })
            .collect(),
    })
}

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
    Ok(versions
        .into_iter()
        .filter_map(|v| {
            let file = v.primary_file()?.clone();
            Some(VersionSummary {
                id: v.id,
                name: clip(v.name, 100),
                version_number: clip(v.version_number, 60),
                version_type: clip(v.version_type, 10),
                date_published: v.date_published,
                game_versions: v.game_versions.into_iter().take(40).collect(),
                loaders: v.loaders,
                file_name: clip(file.filename, 200),
                size: file.size,
            })
        })
        .take(100)
        .collect())
}

fn ensure_mods_allowed(kind: ContentKind, instance: &Instance) -> Result<()> {
    if kind == ContentKind::Mod && loader_tags(instance.loader.kind).is_empty() {
        return Err(Error::validation(
            "Diese Instanz ist Vanilla – Mods brauchen eine Instanz mit Fabric, Quilt, Forge oder NeoForge.",
        ));
    }
    Ok(())
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
    installed.push(install_version(http, paths, instance, kind, &root, None).await?);

    while let Some((id, depth)) = queue.pop() {
        if !visited.insert(id.clone()) {
            continue;
        }
        let Some(version) = compatible_versions(http, &id, ContentKind::Mod, instance).await?.into_iter().next() else {
            tracing::warn!("Abhängigkeit {id} hat keine passende Version – übersprungen");
            continue;
        };
        queue.extend(dependencies_of(&version, ContentKind::Mod, depth));
        installed.push(install_version(http, paths, instance, ContentKind::Mod, &version, None).await?);
    }
    Ok(installed)
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
) -> Result<String> {
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

    // Erst nach erfolgreichem Download die alte(n) Datei(en) entfernen.
    let mut obsolete = content::files_of_project(paths, &instance.id, &version.project_id).await;
    if let Some(old) = replace {
        obsolete.push((kind, old.to_owned()));
    }
    for (old_kind, old_file) in obsolete {
        if old_file != file.filename {
            content::delete(paths, &instance.id, old_kind, &old_file).await?;
        }
    }

    content::remember_source(
        paths,
        &instance.id,
        kind,
        &file.filename,
        Source { project_id: version.project_id.clone(), version_id: version.id.clone() },
    )
    .await?;
    Ok(file.filename.clone())
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

/// Sucht per Datei-Hash nach neueren Versionen – funktioniert auch für Mods,
/// die von Hand in den Ordner gelegt wurden.
pub async fn check_updates(http: &reqwest::Client, paths: &Paths, instance: &Instance) -> Result<Vec<UpdateInfo>> {
    let mut updates = Vec::new();
    for kind in [ContentKind::Mod, ContentKind::ResourcePack, ContentKind::ShaderPack] {
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

        let mut body = serde_json::json!({
            "hashes": by_hash.keys().collect::<Vec<_>>(),
            "algorithm": "sha1",
            "game_versions": [instance.game_version],
        });
        if kind == ContentKind::Mod {
            body["loaders"] = serde_json::json!(loader_tags(instance.loader.kind));
        }
        let latest: HashMap<String, Version> = http
            .post(format!("{API}/version_files/update"))
            .json(&body)
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;

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

/// Ersetzt `file_name` durch die angegebene Version.
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
    install_version(http, paths, instance, kind, &version, Some(file_name)).await
}

#[cfg(test)]
mod tests {
    use super::*;

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
    }
}
