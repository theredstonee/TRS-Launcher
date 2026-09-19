//! Bringt alles auf die Platte, was ein Start braucht: Version-JSON, Java,
//! Client-Jar, Libraries, Natives, Assets.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::download::{self, Progress, Task};
use crate::instance::{Instance, LoaderKind};
use crate::meta::version::{self, Features, ResolvedLibrary, VersionInfo};
use crate::meta::{self, is_safe_id};
use crate::paths::Paths;
use crate::settings::Settings;
use crate::{Error, Result, fsutil, java, loaders};

const RESOURCES_URL: &str = "https://resources.download.minecraft.net";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Stage {
    Version,
    Java,
    Libraries,
    Assets,
    Starting,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StageProgress {
    pub stage: Stage,
    /// 0–100 innerhalb der Stufe.
    pub percent: f64,
    pub done_files: u64,
    pub total_files: u64,
}

impl StageProgress {
    pub fn new(stage: Stage, p: Progress) -> Self {
        Self { stage, percent: p.percent(), done_files: p.done_files, total_files: p.total_files }
    }

    pub fn begin(stage: Stage) -> Self {
        Self { stage, percent: 0.0, done_files: 0, total_files: 0 }
    }
}

pub type ProgressFn = dyn Fn(StageProgress) + Send + Sync;

/// Ergebnis der Vorbereitung – alles, was der Arg-Builder braucht.
#[derive(Debug)]
pub struct Prepared {
    pub version: VersionInfo,
    pub java: PathBuf,
    pub classpath: Vec<PathBuf>,
    pub natives_dir: PathBuf,
    /// `${game_assets}`: bei alten Versionen das virtuelle Verzeichnis.
    pub game_assets: PathBuf,
    pub log_config: Option<PathBuf>,
}

#[derive(Debug, Deserialize)]
struct AssetIndex {
    #[serde(default, rename = "virtual")]
    is_virtual: bool,
    #[serde(default)]
    map_to_resources: bool,
    objects: HashMap<String, AssetObject>,
}

#[derive(Debug, Deserialize)]
struct AssetObject {
    hash: String,
    size: u64,
}

pub async fn prepare(
    http: &reqwest::Client,
    paths: &Paths,
    settings: &Settings,
    instance: &Instance,
    features: &Features,
    on_progress: &ProgressFn,
) -> Result<Prepared> {
    let concurrency = usize::from(settings.concurrent_downloads);

    on_progress(StageProgress::begin(Stage::Version));
    let version = resolve_version(http, paths, instance).await?;
    validate_version(&version)?;

    on_progress(StageProgress::begin(Stage::Java));
    let custom_java = instance.overrides.java_path.clone().or_else(|| settings.java_path.clone());
    let java = match custom_java {
        Some(path) => {
            let path = PathBuf::from(path);
            if !path.is_file() {
                return Err(Error::launch("Der eingestellte Java-Pfad existiert nicht."));
            }
            path
        }
        None => {
            let component =
                version.java_version.as_ref().map_or(java::LEGACY_COMPONENT, |j| j.component.as_str());
            java::ensure_runtime(http, paths, component, concurrency, &|p| {
                on_progress(StageProgress::new(Stage::Java, p));
            })
            .await?
        }
    };

    on_progress(StageProgress::begin(Stage::Libraries));
    let mut libraries = Vec::new();
    for lib in &version.libraries {
        libraries.extend(lib.resolve(features)?);
    }

    let client = version
        .downloads
        .as_ref()
        .and_then(|d| d.client.as_ref())
        .ok_or_else(|| Error::launch("Diese Version enthält keinen Client-Download."))?;
    // Das Jar hängt an der Vanilla-Version, nicht am Loader-Profil.
    let jar_id = jar_version_id(instance, &version);
    let client_jar = paths.version_jar(&jar_id);

    let mut tasks: Vec<Task> = libraries
        .iter()
        .map(|l| Task {
            url: l.url.clone(),
            path: library_path(paths, l),
            sha1: l.sha1.clone(),
            size: l.size,
        })
        .collect();
    tasks.push(Task {
        url: client.url.clone(),
        path: client_jar.clone(),
        sha1: client.sha1.clone(),
        size: client.size,
    });

    let log_config = match version.logging.as_ref().and_then(|l| l.client.as_ref()) {
        Some(cfg) if is_safe_id(&cfg.file.id) => {
            let path = paths.log_config(&cfg.file.id);
            tasks.push(Task {
                url: cfg.file.url.clone(),
                path: path.clone(),
                sha1: Some(cfg.file.sha1.clone()),
                size: Some(cfg.file.size),
            });
            Some(path)
        }
        _ => None,
    };

    download::fetch_all(http, tasks, concurrency, &|p| {
        on_progress(StageProgress::new(Stage::Libraries, p));
    })
    .await?;

    let natives_dir = paths.natives_dir(&jar_id);
    extract_natives(paths, &libraries, &natives_dir).await?;

    on_progress(StageProgress::begin(Stage::Assets));
    let game_assets = install_assets(http, paths, &version, instance, concurrency, on_progress).await?;

    let mut classpath: Vec<PathBuf> =
        libraries.iter().filter(|l| l.on_classpath).map(|l| library_path(paths, l)).collect();
    classpath.push(client_jar);

    Ok(Prepared { version, java, classpath, natives_dir, game_assets, log_config })
}

fn jar_version_id(instance: &Instance, version: &VersionInfo) -> String {
    if instance.loader.kind == LoaderKind::Vanilla { version.id.clone() } else { instance.game_version.clone() }
}

fn library_path(paths: &Paths, lib: &ResolvedLibrary) -> PathBuf {
    // `lib.path` ist von `Library::resolve` bereits gegen Traversal geprüft.
    lib.path.split('/').fold(paths.libraries_dir(), |p, seg| p.join(seg))
}

async fn resolve_version(http: &reqwest::Client, paths: &Paths, instance: &Instance) -> Result<VersionInfo> {
    let vanilla = match meta::manifest::fetch(http, paths, false).await {
        Ok(manifest) => {
            let entry = manifest
                .find(&instance.game_version)
                .ok_or_else(|| Error::UnknownGameVersion(instance.game_version.clone()))?;
            version::fetch_vanilla(http, paths, entry).await?
        }
        // Offline: mit dem zuletzt geladenen JSON weiterarbeiten.
        Err(e) => fsutil::read_json(&paths.version_json(&instance.game_version)).await?.ok_or(e)?,
    };

    if instance.loader.kind == LoaderKind::Vanilla {
        return Ok(vanilla);
    }
    let profile = loaders::fetch_profile(http, paths, &instance.game_version, &instance.loader).await?;
    Ok(profile.merge_onto(vanilla))
}

fn validate_version(version: &VersionInfo) -> Result<()> {
    let ids_ok = is_safe_id(&version.id)
        && version.assets.as_deref().is_none_or(is_safe_id)
        && version.asset_index.as_ref().is_none_or(|a| is_safe_id(&a.id));
    if !ids_ok {
        return Err(Error::launch("Die Versions-Metadaten enthalten ungültige Bezeichner."));
    }
    if version.main_class.is_none() {
        return Err(Error::launch("Die Versions-Metadaten enthalten keine Hauptklasse."));
    }
    Ok(())
}

async fn install_assets(
    http: &reqwest::Client,
    paths: &Paths,
    version: &VersionInfo,
    instance: &Instance,
    concurrency: usize,
    on_progress: &ProgressFn,
) -> Result<PathBuf> {
    let Some(index_ref) = &version.asset_index else {
        return Ok(paths.assets_dir());
    };

    let index_file = paths.asset_index(&index_ref.id);
    let index_task = Task {
        url: index_ref.url.clone(),
        path: index_file.clone(),
        sha1: Some(index_ref.sha1.clone()),
        size: Some(index_ref.size),
    };
    if !download::is_valid(&index_task, true).await {
        // Offline mit vorhandenem (evtl. älterem) Index weiterstarten.
        if let Err(e) = download::fetch_one(http, &index_task).await
            && !index_file.is_file()
        {
            return Err(e);
        }
    }
    let index: AssetIndex = fsutil::read_json(&index_file)
        .await?
        .ok_or_else(|| Error::launch("Der Asset-Index fehlt."))?;

    for (name, obj) in &index.objects {
        let hash_ok = obj.hash.len() == 40 && obj.hash.bytes().all(|b| b.is_ascii_hexdigit());
        if !hash_ok || !is_safe_asset_name(name) {
            return Err(Error::launch("Der Asset-Index enthält ungültige Einträge."));
        }
    }

    let tasks = index
        .objects
        .values()
        .map(|o| Task {
            url: format!("{RESOURCES_URL}/{}/{}", &o.hash[..2], o.hash),
            path: paths.asset_object(&o.hash),
            sha1: Some(o.hash.clone()),
            size: Some(o.size),
        })
        .collect();
    download::fetch_all(http, tasks, concurrency, &|p| {
        on_progress(StageProgress::new(Stage::Assets, p));
    })
    .await?;

    // Alte Versionen erwarten die Assets unter ihrem echten Namen.
    let legacy_target = if index.map_to_resources {
        Some(paths.instance_game_dir(&instance.id).join("resources"))
    } else if index.is_virtual {
        Some(paths.virtual_assets_dir(&index_ref.id))
    } else {
        None
    };

    let Some(target) = legacy_target else {
        return Ok(paths.assets_dir());
    };
    for (name, obj) in &index.objects {
        let dest = name.split('/').fold(target.clone(), |p, seg| p.join(seg));
        let up_to_date = tokio::fs::metadata(&dest).await.is_ok_and(|m| m.len() == obj.size);
        if !up_to_date {
            if let Some(parent) = dest.parent() {
                fsutil::ensure_dir(parent).await?;
            }
            let src = paths.asset_object(&obj.hash);
            tokio::fs::copy(&src, &dest).await.map_err(|e| Error::io(&dest, e))?;
        }
    }
    Ok(target)
}

fn is_safe_asset_name(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('/')
        && !name.contains('\\')
        && !name.contains(':')
        && name.split('/').all(|seg| !seg.is_empty() && seg != "." && seg != "..")
}

/// Entpackt die Natives des alten Formats (bis 1.18). Ab 1.19 liegen die
/// Natives als normale Jars auf dem Classpath und LWJGL entpackt sie selbst.
async fn extract_natives(paths: &Paths, libraries: &[ResolvedLibrary], natives_dir: &Path) -> Result<()> {
    fsutil::ensure_dir(natives_dir).await?;

    for lib in libraries {
        let Some(extract) = lib.extract.clone() else { continue };
        let jar = library_path(paths, lib);
        let target = natives_dir.to_owned();

        tokio::task::spawn_blocking(move || -> Result<()> {
            let file = std::fs::File::open(&jar).map_err(|e| Error::io(&jar, e))?;
            let mut archive = zip::ZipArchive::new(file)
                .map_err(|e| Error::launch(format!("Natives-Archiv ist beschädigt: {e}")))?;

            for i in 0..archive.len() {
                let mut entry = archive
                    .by_index(i)
                    .map_err(|e| Error::launch(format!("Natives-Archiv ist beschädigt: {e}")))?;
                // `enclosed_name` verhindert Zip-Slip.
                let Some(rel) = entry.enclosed_name() else { continue };
                let rel_str = rel.to_string_lossy().replace('\\', "/");
                if entry.is_dir() || extract.exclude.iter().any(|ex| rel_str.starts_with(ex.as_str())) {
                    continue;
                }
                let dest = target.join(&rel);
                if dest.metadata().is_ok_and(|m| m.len() == entry.size()) {
                    continue;
                }
                if let Some(parent) = dest.parent() {
                    std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
                }
                let mut out = std::fs::File::create(&dest).map_err(|e| Error::io(&dest, e))?;
                std::io::copy(&mut entry, &mut out).map_err(|e| Error::io(&dest, e))?;
            }
            Ok(())
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))??;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn asset_names() {
        assert!(is_safe_asset_name("minecraft/sounds/ambient/cave/cave1.ogg"));
        assert!(is_safe_asset_name("icons/icon_16x16.png"));
        for bad in ["../x", "/x", "a/../../b", "c:/x", "a\\b", ""] {
            assert!(!is_safe_asset_name(bad), "{bad:?}");
        }
    }

    #[test]
    fn parses_asset_index_flags() {
        let idx: AssetIndex =
            serde_json::from_str(r#"{"virtual":true,"objects":{"a/b.png":{"hash":"00","size":1}}}"#).unwrap();
        assert!(idx.is_virtual && !idx.map_to_resources);
        let idx: AssetIndex = serde_json::from_str(r#"{"map_to_resources":true,"objects":{}}"#).unwrap();
        assert!(idx.map_to_resources);
    }
}
