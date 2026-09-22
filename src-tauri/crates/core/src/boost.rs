//! „TRS-Optimierung“ für Vanilla-Instanzen: Sie starten unter der Haube mit
//! Fabric, dem TRS Client und bewährten Performance-Mods (Sodium & Co.) –
//! wie bei Lunar oder Badlion. Abschaltbar pro Instanz, dann echtes Vanilla.

use serde::{Deserialize, Serialize};

use crate::instance::{Instance, Loader, LoaderKind};
use crate::paths::Paths;
use crate::{Result, fsutil, loaders, modrinth};

/// Bei Änderungen am Performance-Paket hochzählen – dann wird es einmal neu
/// geprüft und ergänzt.
const PACK_REVISION: u32 = 1;
const MARKER: &str = "trs-boost.json";

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Marker {
    pack_revision: u32,
    game_version: String,
}

pub fn wants_boost(instance: &Instance) -> bool {
    instance.loader.kind == LoaderKind::Vanilla && instance.overrides.boost != Some(false)
}

/// Liefert die Instanz so, wie sie gestartet werden soll: bei aktiver
/// Optimierung mit Fabric statt Vanilla. Gibt es für die Version kein Fabric
/// (vor 1.14) oder ist offline nichts im Cache, bleibt es bei Vanilla.
pub async fn effective_instance(http: &reqwest::Client, paths: &Paths, instance: &Instance) -> Instance {
    if !wants_boost(instance) {
        return instance.clone();
    }
    if !loaders::supports(http, paths, LoaderKind::Fabric, &instance.game_version).await {
        return instance.clone();
    }
    Instance { loader: Loader { kind: LoaderKind::Fabric, version: None }, ..instance.clone() }
}

/// Einmal pro Version bzw. Paket-Revision die Performance-Mods ergänzen.
/// Fehler (offline, Modrinth down) blockieren den Start nicht.
pub async fn ensure_performance(http: &reqwest::Client, paths: &Paths, effective: &Instance) -> Result<()> {
    let marker_file = paths.instance_dir(&effective.id).join(MARKER);
    let marker: Marker = fsutil::read_json(&marker_file).await.ok().flatten().unwrap_or_default();
    if marker.pack_revision == PACK_REVISION && marker.game_version == effective.game_version {
        return Ok(());
    }
    match modrinth::install_performance_pack(http, paths, effective).await {
        Ok(files) => {
            tracing::info!("TRS-Optimierung für '{}': {} Dateien", effective.id, files.len());
            fsutil::write_json(
                &marker_file,
                &Marker { pack_revision: PACK_REVISION, game_version: effective.game_version.clone() },
            )
            .await
        }
        Err(e) => {
            tracing::warn!("Performance-Mods konnten nicht installiert werden: {e}");
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use chrono::Utc;

    use super::*;
    use crate::instance::InstanceOverrides;

    fn instance(kind: LoaderKind, boost: Option<bool>) -> Instance {
        Instance {
            id: "t".into(),
            name: "T".into(),
            game_version: "1.21.1".into(),
            loader: Loader { kind, version: None },
            created_at: Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            icon: None,
            overrides: InstanceOverrides { boost, ..Default::default() },
        }
    }

    #[test]
    fn boost_only_for_vanilla_unless_disabled() {
        assert!(wants_boost(&instance(LoaderKind::Vanilla, None)));
        assert!(!wants_boost(&instance(LoaderKind::Vanilla, Some(false))));
        assert!(!wants_boost(&instance(LoaderKind::Fabric, None)));
        assert!(!wants_boost(&instance(LoaderKind::Forge, None)));
    }
}
