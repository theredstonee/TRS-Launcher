//! Modloader. Fabric und Quilt liefern über ihre Meta-APIs ein fertiges
//! Version-JSON mit `inheritsFrom` – das ist ein reiner Merge. Forge und
//! NeoForge brauchen einen Installer mit Processor-Kette, siehe [`crate::forge`].

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
        LoaderKind::Forge | LoaderKind::NeoForge => {
            Err(Error::Internal("Forge/NeoForge laufen über forge::ensure_installed".into()))
        }
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
        None => match latest_loader_version(http, base, game_version).await {
            Ok(v) => v,
            // Offline: das neueste gecachte Profil nehmen.
            Err(e) => match newest_cached_version(paths, loader.kind, game_version).await {
                Some(v) => {
                    tracing::info!("Loader-Liste nicht erreichbar ({e}), nehme gecachtes Profil {v}");
                    return cached_profile(paths, game_version, loader, &v).await.ok_or(e);
                }
                None => return Err(e),
            },
        },
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
        Ok(_) => Err(Error::launch(crate::msg!("loaders.invalidProfileId", "Das Loader-Profil enthält eine ungültige ID."))),
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

/// Gibt es diesen Loader für die Spielversion? Offline zählt ein bereits
/// gecachtes Profil als „ja“.
pub async fn supports(http: &reqwest::Client, paths: &Paths, kind: LoaderKind, game_version: &str) -> bool {
    let Ok(base) = meta_base(kind) else { return false };
    match latest_loader_version(http, base, game_version).await {
        Ok(_) => true,
        Err(Error::Http(e)) if e.is_connect() || e.is_timeout() => has_cached_profile(paths, kind, game_version).await,
        Err(_) => false,
    }
}

async fn has_cached_profile(paths: &Paths, kind: LoaderKind, game_version: &str) -> bool {
    newest_cached_version(paths, kind, game_version).await.is_some()
}

async fn newest_cached_version(paths: &Paths, kind: LoaderKind, game_version: &str) -> Option<String> {
    let prefix = match kind {
        LoaderKind::Fabric => "fabric-loader-",
        LoaderKind::Quilt => "quilt-loader-",
        _ => return None,
    };
    let mut names = Vec::new();
    let mut entries = tokio::fs::read_dir(paths.versions_dir()).await.ok()?;
    while let Ok(Some(entry)) = entries.next_entry().await {
        let name = entry.file_name().to_string_lossy().into_owned();
        if paths.version_json(&name).is_file() {
            names.push(name);
        }
    }
    pick_newest(&names, prefix, game_version)
}

fn pick_newest(names: &[String], prefix: &str, game_version: &str) -> Option<String> {
    let suffix = format!("-{game_version}");
    let numeric = |v: &str| -> Vec<u64> {
        v.split(|c: char| !c.is_ascii_digit()).filter(|s| !s.is_empty()).map(|s| s.parse().unwrap_or(0)).collect()
    };
    names
        .iter()
        .filter_map(|n| n.strip_prefix(prefix)?.strip_suffix(&suffix))
        .filter(|v| is_listable(v))
        .max_by(|a, b| numeric(a).cmp(&numeric(b)).then_with(|| a.cmp(b)))
        .map(str::to_owned)
}

/// Eine wählbare Loader-Version.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoaderVersionInfo {
    pub version: String,
    pub stable: bool,
}

/// Höchstens so viele Versionen gehen ans Frontend.
const MAX_LISTED: usize = 300;

fn is_listable(v: &str) -> bool {
    !v.is_empty() && v.len() <= 64 && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+'))
}

/// Alle Loader-Versionen für eine Spielversion, neueste zuerst.
pub async fn available_versions(http: &reqwest::Client, kind: LoaderKind, game_version: &str) -> Result<Vec<LoaderVersionInfo>> {
    if !crate::meta::is_safe_id(game_version) {
        return Err(Error::validation(crate::msg!("loaders.invalidGameVersion", "Ungültige Minecraft-Version")));
    }
    let list = match kind {
        LoaderKind::Vanilla => Vec::new(),
        LoaderKind::Fabric | LoaderKind::Quilt => {
            let url = format!("{}/versions/loader/{game_version}", meta_base(kind)?);
            let entries: Vec<LoaderEntry> = http.get(&url).send().await?.error_for_status()?.json().await?;
            entries
                .into_iter()
                .map(|e| {
                    // Quilt kennt kein `stable`: Versionen mit `-beta` o. Ä. sind keine.
                    let stable = e.loader.stable.unwrap_or(!e.loader.version.contains('-'));
                    LoaderVersionInfo { version: e.loader.version, stable }
                })
                .collect()
        }
        LoaderKind::Forge | LoaderKind::NeoForge => crate::forge::available_versions(http, kind, game_version)
            .await?
            .into_iter()
            .map(|v| LoaderVersionInfo { stable: !v.contains("beta") && !v.contains("alpha"), version: v })
            .collect(),
    };
    Ok(list.into_iter().filter(|v| is_listable(&v.version)).take(MAX_LISTED).collect())
}

/// Welche Version „neueste stabile“ gerade bedeutet (Anzeige in den Einstellungen).
pub async fn latest_stable(http: &reqwest::Client, kind: LoaderKind, game_version: &str) -> Result<Option<String>> {
    if !crate::meta::is_safe_id(game_version) {
        return Err(Error::validation(crate::msg!("loaders.invalidGameVersion", "Ungültige Minecraft-Version")));
    }
    let version = match kind {
        LoaderKind::Vanilla => return Ok(None),
        LoaderKind::Fabric | LoaderKind::Quilt => latest_loader_version(http, meta_base(kind)?, game_version).await?,
        LoaderKind::Forge | LoaderKind::NeoForge => crate::forge::latest_version(http, kind, game_version).await?,
    };
    Ok(Some(version).filter(|v| is_listable(v)))
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
            Error::launch(crate::msg!(
                "loaders.notAvailable",
                "Für Minecraft {version} gibt es diesen Modloader (noch) nicht.",
                version = game_version
            ))
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn newest_cached_profile_wins() {
        let names: Vec<String> = [
            "fabric-loader-0.16.9-1.21.1",
            "fabric-loader-0.16.14-1.21.1",
            "fabric-loader-0.17.0-1.21.4",
            "quilt-loader-0.29.0-1.21.1",
            "1.21.1",
        ]
        .map(String::from)
        .into();
        assert_eq!(pick_newest(&names, "fabric-loader-", "1.21.1").as_deref(), Some("0.16.14"));
        assert_eq!(pick_newest(&names, "quilt-loader-", "1.21.1").as_deref(), Some("0.29.0"));
        assert_eq!(pick_newest(&names, "fabric-loader-", "1.20.1"), None);
    }
}
