//! TRS Client: unser eigener In-Game-Mod (HUD, Zoom, Fullbright, Menü).
//! Der Launcher bringt die Jars mit und legt sie beim Start automatisch in
//! jede passende Instanz – inklusive Fabric API, die der Mod braucht.

use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::content::{self, ContentKind};
use crate::download;
use crate::instance::{Instance, LoaderKind};
use crate::paths::Paths;
use crate::{Error, Result, modrinth};

/// So heißt die Datei im Mods-Ordner – fester Name, damit Updates sie ersetzen.
const INSTALLED_NAME: &str = "trsclient.jar";
const FABRIC_API_PROJECT: &str = "P7dR8mSH";

/// Welche Builds es gibt: (Minecraft-Version, Dateiname im Ressourcen-Ordner).
const BUILDS: &[(&str, &str)] = &[("1.21.1", "trsclient-fabric-1.21.1.jar")];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Availability {
    /// Wird beim Start installiert.
    Available,
    /// Für diese Version/diesen Loader gibt es (noch) keinen Build.
    Unsupported,
    /// In den Instanz-Einstellungen abgeschaltet.
    Disabled,
}

fn build_for(instance: &Instance) -> Option<&'static str> {
    // Quilt lädt Fabric-Mods.
    if !matches!(instance.loader.kind, LoaderKind::Fabric | LoaderKind::Quilt) {
        return None;
    }
    BUILDS.iter().find(|(v, _)| *v == instance.game_version).map(|(_, jar)| *jar)
}

pub fn availability(instance: &Instance) -> Availability {
    if instance.overrides.trs_client == Some(false) {
        Availability::Disabled
    } else if build_for(instance).is_some() {
        Availability::Available
    } else {
        Availability::Unsupported
    }
}

/// Sorgt vor dem Start dafür, dass der TRS Client (und Fabric API) in der
/// Instanz liegt – bzw. entfernt ihn, wenn er abgeschaltet wurde.
pub async fn sync(http: &reqwest::Client, paths: &Paths, bundled_dir: Option<&Path>, instance: &Instance) -> Result<()> {
    let mods = content::content_dir(paths, &instance.id, ContentKind::Mod);
    let target = mods.join(INSTALLED_NAME);
    let disabled_copy = mods.join(format!("{INSTALLED_NAME}.disabled"));

    let (Some(jar), Some(dir)) = (build_for(instance), bundled_dir) else {
        return Ok(());
    };
    if instance.overrides.trs_client == Some(false) {
        for file in [&target, &disabled_copy] {
            if file.is_file() {
                tokio::fs::remove_file(file).await.map_err(|e| Error::io(file, e))?;
            }
        }
        return Ok(());
    }

    let source = dir.join(jar);
    if !source.is_file() {
        tracing::warn!("TRS Client fehlt im Launcher-Paket: {}", source.display());
        return Ok(());
    }
    // Nur kopieren, wenn sich etwas geändert hat (Launcher-Update bringt neue Version).
    if !same_file(&source, &target).await {
        tokio::fs::create_dir_all(&mods).await.map_err(|e| Error::io(&mods, e))?;
        tokio::fs::copy(&source, &target).await.map_err(|e| Error::io(&target, e))?;
        let _ = tokio::fs::remove_file(&disabled_copy).await;
        tracing::info!("TRS Client in '{}' installiert", instance.id);
    }

    ensure_fabric_api(http, paths, instance).await;
    Ok(())
}

async fn same_file(a: &PathBuf, b: &PathBuf) -> bool {
    let (Ok(ma), Ok(mb)) = (tokio::fs::metadata(a).await, tokio::fs::metadata(b).await) else { return false };
    if ma.len() != mb.len() {
        return false;
    }
    match (download::sha1_of_file(a).await, download::sha1_of_file(b).await) {
        (Ok(x), Ok(y)) => x == y,
        _ => false,
    }
}

/// Fabric API nachinstallieren, falls sie fehlt. Offline oder bei Fehlern
/// nur warnen – das Spiel meldet eine fehlende Abhängigkeit dann selbst.
async fn ensure_fabric_api(http: &reqwest::Client, paths: &Paths, instance: &Instance) {
    let installed = content::installed_project_ids(paths, &instance.id).await.unwrap_or_default();
    if installed.iter().any(|id| id == FABRIC_API_PROJECT) || has_fabric_api_file(paths, instance).await {
        return;
    }
    match modrinth::install(http, paths, instance, FABRIC_API_PROJECT, ContentKind::Mod, None).await {
        Ok(_) => tracing::info!("Fabric API für den TRS Client in '{}' installiert", instance.id),
        Err(e) => tracing::warn!("Fabric API konnte nicht installiert werden: {e}"),
    }
}

/// Auch von Hand hinzugefügte Fabric API zählt (z. B. aus einem Import).
async fn has_fabric_api_file(paths: &Paths, instance: &Instance) -> bool {
    content::list(paths, &instance.id, ContentKind::Mod)
        .await
        .map(|items| {
            items.iter().any(|i| {
                i.enabled
                    && (i.title.as_deref() == Some("Fabric API") || i.file_name.to_ascii_lowercase().starts_with("fabric-api"))
            })
        })
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use chrono::Utc;

    use super::*;
    use crate::instance::{InstanceOverrides, Loader};

    fn instance(version: &str, kind: LoaderKind, enabled: Option<bool>) -> Instance {
        Instance {
            id: "test".into(),
            name: "Test".into(),
            game_version: version.into(),
            loader: Loader { kind, version: None },
            created_at: Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            overrides: InstanceOverrides { trs_client: enabled, ..Default::default() },
        }
    }

    #[test]
    fn availability_rules() {
        assert_eq!(availability(&instance("1.21.1", LoaderKind::Fabric, None)), Availability::Available);
        assert_eq!(availability(&instance("1.21.1", LoaderKind::Quilt, None)), Availability::Available);
        assert_eq!(availability(&instance("1.21.1", LoaderKind::Vanilla, None)), Availability::Unsupported);
        assert_eq!(availability(&instance("1.20.1", LoaderKind::Fabric, None)), Availability::Unsupported);
        assert_eq!(availability(&instance("1.21.1", LoaderKind::Fabric, Some(false))), Availability::Disabled);
    }

    #[tokio::test]
    async fn installs_updates_and_removes_jar() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("root"));
        paths.ensure().await.unwrap();
        let bundled = dir.path().join("bundled");
        tokio::fs::create_dir_all(&bundled).await.unwrap();
        tokio::fs::write(bundled.join("trsclient-fabric-1.21.1.jar"), b"v1").await.unwrap();

        // Offline-Client: die Fabric-API-Nachinstallation schlägt fehl, darf aber nichts blockieren.
        let http = reqwest::Client::builder().proxy(reqwest::Proxy::all("http://127.0.0.1:9").unwrap()).build().unwrap();
        let mods = content::content_dir(&paths, "test", ContentKind::Mod);

        let on = instance("1.21.1", LoaderKind::Fabric, None);
        sync(&http, &paths, Some(&bundled), &on).await.unwrap();
        assert_eq!(tokio::fs::read(mods.join(INSTALLED_NAME)).await.unwrap(), b"v1");

        tokio::fs::write(bundled.join("trsclient-fabric-1.21.1.jar"), b"v2").await.unwrap();
        sync(&http, &paths, Some(&bundled), &on).await.unwrap();
        assert_eq!(tokio::fs::read(mods.join(INSTALLED_NAME)).await.unwrap(), b"v2");

        let off = instance("1.21.1", LoaderKind::Fabric, Some(false));
        sync(&http, &paths, Some(&bundled), &off).await.unwrap();
        assert!(!mods.join(INSTALLED_NAME).exists());

        // Nicht unterstützte Version: nichts anfassen.
        let other = instance("1.20.1", LoaderKind::Fabric, None);
        sync(&http, &paths, Some(&bundled), &other).await.unwrap();
        assert!(!mods.join(INSTALLED_NAME).exists());
    }
}
