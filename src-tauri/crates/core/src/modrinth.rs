//! Modrinth (API v2): suchen und in eine Instanz installieren.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use crate::content::{self, ContentKind, Source};
use crate::download::{self, Task};
use crate::instance::{Instance, LoaderKind};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const API: &str = "https://api.modrinth.com/v2";
const CDN_PREFIX: &str = "https://cdn.modrinth.com/";
const MAX_QUERY_LEN: usize = 100;
const MAX_DEPENDENCY_DEPTH: u8 = 4;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchParams {
    pub query: String,
    pub kind: ContentKind,
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

#[derive(Debug, Deserialize)]
struct Version {
    id: String,
    project_id: String,
    #[serde(default)]
    files: Vec<VersionFile>,
    #[serde(default)]
    dependencies: Vec<Dependency>,
}

#[derive(Debug, Deserialize)]
struct VersionFile {
    url: String,
    filename: String,
    #[serde(default)]
    primary: bool,
    size: u64,
    hashes: Hashes,
}

#[derive(Debug, Deserialize)]
struct Hashes {
    sha1: String,
}

#[derive(Debug, Deserialize)]
struct Dependency {
    project_id: Option<String>,
    dependency_type: String,
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

fn is_safe_project_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn is_safe_game_version(v: &str) -> bool {
    crate::meta::is_safe_id(v) && v.len() <= 32
}

pub async fn search(http: &reqwest::Client, params: &SearchParams) -> Result<SearchResult> {
    let query: String = params.query.trim().chars().filter(|c| !c.is_control()).take(MAX_QUERY_LEN).collect();

    // Facets als JSON bauen (nicht per String-Verkettung): innen ODER, außen UND.
    let mut facets = vec![vec![format!("project_type:{}", params.kind.modrinth_type())]];
    if let Some(v) = params.game_version.as_deref().filter(|v| is_safe_game_version(v)) {
        facets.push(vec![format!("versions:{v}")]);
    }
    if params.kind == ContentKind::Mod
        && let Some(loader) = params.loader
    {
        let tags = loader_tags(loader);
        if !tags.is_empty() {
            facets.push(tags.iter().map(|t| format!("categories:{t}")).collect());
        }
    }
    let facets = serde_json::to_string(&facets).map_err(|e| Error::Internal(e.to_string()))?;

    let raw: RawSearch = http
        .get(format!("{API}/search"))
        .query(&[
            ("query", query.as_str()),
            ("facets", facets.as_str()),
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
                slug: h.slug,
                title: h.title,
                description: h.description,
                author: h.author,
                // Bilder nur von Modrinths eigenem CDN.
                icon_url: h.icon_url.filter(|u| u.starts_with(CDN_PREFIX)),
                downloads: h.downloads,
                categories: h.categories,
            })
            .collect(),
    })
}

async fn latest_compatible(
    http: &reqwest::Client,
    project_id: &str,
    kind: ContentKind,
    instance: &Instance,
) -> Result<Option<Version>> {
    let game_versions = serde_json::to_string(&[&instance.game_version]).map_err(|e| Error::Internal(e.to_string()))?;
    let mut query = vec![("game_versions", game_versions)];
    if kind == ContentKind::Mod {
        let tags = loader_tags(instance.loader.kind);
        query.push(("loaders", serde_json::to_string(tags).map_err(|e| Error::Internal(e.to_string()))?));
    }

    // Modrinth liefert neueste zuerst.
    let versions: Vec<Version> = http
        .get(format!("{API}/project/{project_id}/version"))
        .query(&query)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    Ok(versions.into_iter().next())
}

/// Installiert die neueste passende Version samt Pflicht-Abhängigkeiten.
/// Liefert die Namen der neu installierten Dateien.
pub async fn install(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    project_id: &str,
    kind: ContentKind,
) -> Result<Vec<String>> {
    if !is_safe_project_id(project_id) {
        return Err(Error::validation("Ungültige Projekt-ID"));
    }
    if kind == ContentKind::Mod && loader_tags(instance.loader.kind).is_empty() {
        return Err(Error::validation(
            "Diese Instanz ist Vanilla – Mods brauchen eine Instanz mit Fabric, Quilt, Forge oder NeoForge.",
        ));
    }

    let mut installed = Vec::new();
    let mut visited: HashSet<String> = content::installed_project_ids(paths, &instance.id).await?.into_iter().collect();
    // Das angefragte Projekt darf neu installiert werden (= Update).
    visited.remove(project_id);

    let mut queue = vec![(project_id.to_owned(), kind, 0u8)];
    while let Some((id, kind, depth)) = queue.pop() {
        if !visited.insert(id.clone()) {
            continue;
        }
        let Some(version) = latest_compatible(http, &id, kind, instance).await? else {
            if depth == 0 {
                return Err(Error::validation(format!(
                    "Für Minecraft {} mit diesem Modloader gibt es keine passende Version.",
                    instance.game_version
                )));
            }
            tracing::warn!("Abhängigkeit {id} hat keine passende Version – übersprungen");
            continue;
        };

        installed.push(install_version(http, paths, instance, kind, &version).await?);

        if kind == ContentKind::Mod && depth < MAX_DEPENDENCY_DEPTH {
            for dep in version.dependencies {
                if dep.dependency_type == "required"
                    && let Some(dep_id) = dep.project_id.filter(|d| is_safe_project_id(d))
                {
                    queue.push((dep_id, ContentKind::Mod, depth + 1));
                }
            }
        }
    }
    Ok(installed)
}

async fn install_version(
    http: &reqwest::Client,
    paths: &Paths,
    instance: &Instance,
    kind: ContentKind,
    version: &Version,
) -> Result<String> {
    let file = version
        .files
        .iter()
        .find(|f| f.primary)
        .or(version.files.first())
        .ok_or_else(|| Error::validation("Diese Version enthält keine Datei."))?;

    content::validate_file_name(kind, &file.filename)?;
    if !file.url.starts_with(CDN_PREFIX) {
        return Err(Error::download(&file.url, "Download liegt nicht auf Modrinths CDN"));
    }

    let dir = content::content_dir(paths, &instance.id, kind);
    fsutil::ensure_dir(&dir).await?;

    // Update: alte Datei(en) desselben Projekts entfernen.
    for (old_kind, old_file) in content::files_of_project(paths, &instance.id, &version.project_id).await {
        if old_file != file.filename {
            content::delete(paths, &instance.id, old_kind, &old_file).await?;
        }
    }

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
    fn parses_version_payload() {
        let v: Vec<Version> = serde_json::from_str(
            r#"[{"id":"v1","project_id":"P7dR8mSH","name":"x","version_number":"1",
                "files":[{"url":"https://cdn.modrinth.com/data/P7dR8mSH/versions/v1/fabric-api.jar",
                          "filename":"fabric-api.jar","primary":true,"size":10,
                          "hashes":{"sha1":"aa","sha512":"bb"}}],
                "dependencies":[{"project_id":"abc","version_id":null,"dependency_type":"required"},
                                {"project_id":null,"version_id":null,"dependency_type":"embedded"}]}]"#,
        )
        .unwrap();
        assert_eq!(v[0].files[0].hashes.sha1, "aa");
        assert_eq!(v[0].dependencies.len(), 2);
    }
}
