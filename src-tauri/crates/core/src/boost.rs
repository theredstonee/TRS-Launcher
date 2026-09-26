//! „TRS-Optimierung“ für Vanilla-Instanzen: Sie starten unter der Haube mit
//! Fabric, dem TRS Client und bewährten Performance-Mods (Sodium & Co.) –
//! wie bei Lunar oder Badlion. Abschaltbar pro Instanz, dann echtes Vanilla.

use serde::{Deserialize, Serialize};

use crate::client_mod::{self, Build};
use crate::instance::{Instance, Loader, LoaderKind};
use crate::paths::Paths;
use crate::presets::{ApplyProgress, ItemStatus};
use crate::{Error, Result, fsutil, loaders, presets};

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
/// (vor 1.14), aber einen TRS-Client-Build für Forge (z. B. 1.8.9), dann mit
/// Forge. Sonst – oder offline ohne Cache – bleibt es bei Vanilla.
pub async fn effective_instance(http: &reqwest::Client, paths: &Paths, builds: &[Build], instance: &Instance) -> Instance {
    if !wants_boost(instance) {
        return instance.clone();
    }
    let as_loader = |kind| Instance { loader: Loader { kind, version: None }, ..instance.clone() };
    if loaders::supports(http, paths, LoaderKind::Fabric, &instance.game_version).await {
        return as_loader(LoaderKind::Fabric);
    }
    match client_mod::boost_loader(builds, &instance.game_version) {
        Some(kind @ (LoaderKind::Forge | LoaderKind::NeoForge)) => as_loader(kind),
        _ => instance.clone(),
    }
}

/// Einmal pro Version bzw. Paket-Revision die Performance-Mods ergänzen.
/// Fehler (offline, Modrinth down) blockieren den Start nicht – dann wird beim
/// nächsten Start erneut ergänzt. Abbrechen bricht auch den Start ab.
pub async fn ensure_performance(
    http: &reqwest::Client,
    paths: &Paths,
    builds: &[Build],
    effective: &Instance,
    progress: &(dyn Fn(ApplyProgress) + Sync),
) -> Result<()> {
    let marker_file = paths.instance_dir(&effective.id).join(MARKER);
    if is_done(&marker_file, effective).await {
        return Ok(());
    }
    match presets::install_fps_boost_report(http, paths, builds, effective, progress).await {
        Ok(report) => {
            tracing::info!("TRS-Optimierung für '{}': {} Dateien", effective.id, report.files.len());
            if report.items.iter().any(|i| i.status == ItemStatus::Failed) {
                // Nicht als erledigt merken: Beim nächsten Start wird ergänzt, was fehlt.
                tracing::warn!("TRS-Optimierung für '{}' unvollständig – nächster Start versucht es erneut", effective.id);
                return Ok(());
            }
            fsutil::write_json(
                &marker_file,
                &Marker { pack_revision: PACK_REVISION, game_version: effective.game_version.clone() },
            )
            .await
        }
        Err(Error::Cancelled) => Err(Error::Cancelled),
        Err(e) => {
            tracing::warn!("Performance-Mods konnten nicht installiert werden: {e}");
            Ok(())
        }
    }
}

/// Wurde das Paket für diese Version schon vollständig ergänzt?
async fn is_done(marker_file: &std::path::Path, effective: &Instance) -> bool {
    let marker: Marker = fsutil::read_json(marker_file).await.ok().flatten().unwrap_or_default();
    marker.pack_revision == PACK_REVISION && marker.game_version == effective.game_version
}

/// Muss vor dem Start noch etwas ergänzt werden (für die Fortschrittsanzeige)?
pub async fn needs_performance(paths: &Paths, effective: &Instance) -> bool {
    !is_done(&paths.instance_dir(&effective.id).join(MARKER), effective).await
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
            group: None,
            overrides: InstanceOverrides { boost, ..Default::default() },
        }
    }

    /// Gegen das echte Modrinth: Erststart einer frischen Vanilla-1.21.11-Instanz mit
    /// TRS-Optimierung. Kein Mod darf ohne seine Pflicht-Abhängigkeit liegen.
    /// `cargo test -p trs-core real_modrinth_vanilla_boost -- --ignored --nocapture`
    #[tokio::test]
    #[ignore = "braucht Internet (Modrinth), lädt ~15 MB"]
    async fn real_modrinth_vanilla_boost_1_21_11() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let vanilla = Instance { game_version: "1.21.11".into(), ..instance(LoaderKind::Vanilla, None) };
        let builds = client_mod::load_builds(&std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../resources/client-mod"));
        assert!(!builds.is_empty());
        let http = reqwest::Client::builder().user_agent("theredstonee/trs-launcher (boost test)").build().unwrap();
        let effective = effective_instance(&http, &paths, &builds, &vanilla).await;
        assert_eq!(effective.loader.kind, LoaderKind::Fabric);
        ensure_performance(&http, &paths, &builds, &effective, &|_| {}).await.unwrap();
        assert!(!needs_performance(&paths, &effective).await, "vollständig → gemerkt");
        let mods = crate::content::content_dir(&paths, &effective.id, crate::content::ContentKind::Mod);
        let mut files: Vec<String> =
            std::fs::read_dir(&mods).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
        files.sort();
        println!("{files:#?}");
        assert!(files.iter().any(|f| f.starts_with("moreculling")), "More Culling installiert");
        assert!(files.iter().any(|f| f.starts_with("cloth-config")), "Cloth Config fehlt");
        let entries = crate::modcompat::installed_entries(&paths, &effective.id).await.unwrap();
        let bundled = client_mod::builtin_mods(&paths, &builds, &effective).await;
        let builtins: Vec<crate::modcompat::ModInfo> = bundled
            .iter()
            .map(|b| crate::modcompat::ModInfo {
                id: b.id.clone(),
                name: b.name.clone(),
                version: b.version.clone(),
                scheme: crate::modcompat::meta::Scheme::Fabric,
                provides: Vec::new(),
                depends: Vec::new(),
                breaks: Vec::new(),
            })
            .collect();
        let missing = crate::modcompat::missing_dependencies(&entries, &builtins);
        assert!(missing.is_empty(), "Jar-in-Jar der Fabric API zählt: {missing:?}");

        let cloth = files.iter().find(|f| f.starts_with("cloth-config")).unwrap().clone();
        let has_cloth = || std::fs::read_dir(&mods).unwrap().any(|e| e.unwrap().file_name().to_string_lossy() == cloth);
        // Abgebrochener erster Start (More Culling da, Cloth Config nicht, nichts gemerkt):
        // der nächste Start ergänzt die Abhängigkeit der schon installierten Mod.
        crate::content::delete(&paths, &effective.id, crate::content::ContentKind::Mod, &cloth).await.unwrap();
        std::fs::remove_file(paths.instance_dir(&effective.id).join(MARKER)).unwrap();
        ensure_performance(&http, &paths, &builds, &effective, &|_| {}).await.unwrap();
        assert!(has_cloth(), "vom Paket nachgeladen");

        // Schutznetz vor dem Start: Cloth Config fehlt (Paket schon erledigt) → wird ergänzt.
        crate::content::delete(&paths, &effective.id, crate::content::ContentKind::Mod, &cloth).await.unwrap();
        let fix = crate::depcheck::ensure_before_launch(&http, &paths, &builds, &effective, &|_| {}).await.unwrap();
        println!("{fix:?}");
        assert!(has_cloth(), "vor dem Start nachgeladen");
        assert_eq!(fix.needed_by, ["More Culling 1.6.2"]);
        // Deaktiviert: wird wieder eingeschaltet.
        crate::content::set_enabled(&paths, &effective.id, crate::content::ContentKind::Mod, &cloth, false).await.unwrap();
        let fix = crate::depcheck::ensure_before_launch(&http, &paths, &builds, &effective, &|_| {}).await.unwrap();
        assert!(has_cloth() && fix.added.len() == 1, "{fix:?}");
        // Alles da: nichts zu tun.
        assert!(crate::depcheck::ensure_before_launch(&http, &paths, &builds, &effective, &|_| {}).await.unwrap().is_empty());
    }

    #[test]
    fn boost_only_for_vanilla_unless_disabled() {
        assert!(wants_boost(&instance(LoaderKind::Vanilla, None)));
        assert!(!wants_boost(&instance(LoaderKind::Vanilla, Some(false))));
        assert!(!wants_boost(&instance(LoaderKind::Fabric, None)));
        assert!(!wants_boost(&instance(LoaderKind::Forge, None)));
    }
}
