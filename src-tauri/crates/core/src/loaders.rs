//! Modloader. Fabric und Quilt liefern über ihre Meta-APIs ein fertiges
//! Version-JSON mit `inheritsFrom` – das ist ein reiner Merge. Forge und
//! NeoForge brauchen einen Installer mit Processor-Kette (folgt).

use serde::Deserialize;

use crate::instance::{Loader, LoaderKind};
use crate::meta::version::VersionInfo;
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const FABRIC_META: &str = "https://meta.fabricmc.net/v2";
const QUILT_META: &str = "https://meta.quiltmc.org/v3";

#[derive(Deserialize)]
struct LoaderEntry {
    loader: LoaderVersion,
}

#[derive(Deserialize)]
struct LoaderVersion {
    version: String,
    /// Nur Fabric kennzeichnet stabile Versionen.
    stable: Option<bool>,
}

fn meta_base(kind: LoaderKind) -> Result<&'static str> {
    match kind {
        LoaderKind::Fabric => Ok(FABRIC_META),
        LoaderKind::Quilt => Ok(QUILT_META),
        LoaderKind::Forge | LoaderKind::NeoForge => Err(Error::launch(
            "Forge und NeoForge werden in einer der nächsten Versionen unterstützt.",
        )),
        LoaderKind::Vanilla => Err(Error::Internal("Vanilla hat kein Loader-Profil".into())),
    }
}

/// Liefert das Loader-Profil (noch nicht mit Vanilla gemergt).
pub async fn fetch_profile(
    http: &reqwest::Client,
    paths: &Paths,
    game_version: &str,
    loader: &Loader,
) -> Result<VersionInfo> {
    let base = meta_base(loader.kind)?;

    let loader_version = match &loader.version {
        Some(v) => v.clone(),
        None => latest_loader_version(http, base, game_version).await?,
    };

    let url = format!("{base}/versions/loader/{game_version}/{loader_version}/profile/json");
    let result = async {
        let profile: VersionInfo = http.get(&url).send().await?.error_for_status()?.json().await?;
        Ok::<_, Error>(profile)
    }
    .await;

    match result {
        Ok(profile) if crate::meta::is_safe_id(&profile.id) => {
            // Für Offline-Starts cachen.
            fsutil::write_json(&paths.version_json(&profile.id), &profile).await?;
            Ok(profile)
        }
        Ok(_) => Err(Error::launch("Das Loader-Profil enthält eine ungültige ID.")),
        Err(e) => cached_profile(paths, game_version, loader, &loader_version).await.ok_or(e),
    }
}

async fn cached_profile(
    paths: &Paths,
    game_version: &str,
    loader: &Loader,
    loader_version: &str,
) -> Option<VersionInfo> {
    let id = match loader.kind {
        LoaderKind::Fabric => format!("fabric-loader-{loader_version}-{game_version}"),
        LoaderKind::Quilt => format!("quilt-loader-{loader_version}-{game_version}"),
        _ => return None,
    };
    fsutil::read_json(&paths.version_json(&id)).await.ok().flatten()
}

async fn latest_loader_version(http: &reqwest::Client, base: &str, game_version: &str) -> Result<String> {
    let url = format!("{base}/versions/loader/{game_version}");
    let entries: Vec<LoaderEntry> = http.get(&url).send().await?.error_for_status()?.json().await?;
    entries
        .iter()
        .find(|e| e.loader.stable.unwrap_or(false))
        // Quilt hat kein `stable`-Flag: Betas überspringen, sonst die neueste.
        .or_else(|| entries.iter().find(|e| !e.loader.version.contains('-')))
        .or(entries.first())
        .map(|e| e.loader.version.clone())
        .ok_or_else(|| {
            Error::launch(format!("Für Minecraft {game_version} gibt es diesen Modloader (noch) nicht."))
        })
}
