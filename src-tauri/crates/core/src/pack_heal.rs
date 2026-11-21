//! Performance-Mods, die der Launcher selbst auswählt (FPS-Boost, Nvidium), dürfen
//! den Start nie kaputt machen. Bekannt unverträgliche Kombinationen fallen schon
//! bei der Auswahl weg; nennt ein Absturz trotzdem eindeutig eine davon, wird sie
//! abgeschaltet und für die Instanz gemerkt – das Spiel startet dann neu.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use crate::content::{self, ContentKind, Platform};
use crate::instance::LoaderKind;
use crate::paths::Paths;
use crate::{fsutil, presets};

const SKIP_FILE: &str = "trs-pack-skip.json";

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PackSkip {
    #[serde(default)]
    projects: Vec<String>,
}

/// Getestet unverträglich: (Modrinth-Projekt, Loader, Minecraft-Versionen).
/// More Culling ab 0.20 greift in Sodium 0.5 ein – für 1.20 gibt es nur Sodium 0.4.
const KNOWN_BAD: &[(&str, LoaderKind, &[&str])] = &[("51shyZVL", LoaderKind::Fabric, &["1.20"])];

fn known_bad(game_version: &str, loader: LoaderKind) -> impl Iterator<Item = &'static str> + '_ {
    KNOWN_BAD
        .iter()
        .filter(move |(_, l, versions)| (*l == loader || (*l == LoaderKind::Fabric && loader == LoaderKind::Quilt)) && versions.contains(&game_version))
        .map(|(project, _, _)| *project)
}

/// Projekte, die diese Instanz nicht automatisch bekommen soll.
pub(crate) async fn skipped(paths: &Paths, instance_id: &str, game_version: &str, loader: LoaderKind) -> HashSet<String> {
    let file = paths.instance_dir(instance_id).join(SKIP_FILE);
    let stored: PackSkip = fsutil::read_json(&file).await.ok().flatten().unwrap_or_default();
    stored.projects.into_iter().chain(known_bad(game_version, loader).map(str::to_owned)).collect()
}

/// Schaltet die genannten Dateien ab, sofern sie aus den Performance-Presets
/// stammen, und merkt sich ihre Projekte. Liefert die Namen der abgeschalteten Mods.
pub(crate) async fn heal(paths: &Paths, instance_id: &str, files: &[String]) -> Vec<String> {
    if files.is_empty() {
        return Vec::new();
    }
    let Ok(items) = content::list(paths, instance_id, ContentKind::Mod).await else { return Vec::new() };
    let pack = presets::auto_heal_projects();
    let mut names = Vec::new();
    let mut projects = Vec::new();
    for item in items.iter().filter(|i| i.enabled && files.contains(&i.file_name)) {
        let Some(source) = item.source.as_ref().filter(|s| s.platform == Platform::Modrinth && pack.contains(&s.project_id.as_str())) else {
            continue;
        };
        if let Err(e) = content::set_enabled(paths, instance_id, ContentKind::Mod, &item.file_name, false).await {
            tracing::warn!("{} konnte nicht abgeschaltet werden: {e}", item.file_name);
            continue;
        }
        tracing::info!("Paket-Mod {} nach Absturz abgeschaltet ({instance_id})", item.file_name);
        projects.push(source.project_id.clone());
        names.push(item.title.clone().unwrap_or_else(|| item.file_name.clone()));
    }
    if !projects.is_empty() {
        let file = paths.instance_dir(instance_id).join(SKIP_FILE);
        let mut stored: PackSkip = fsutil::read_json(&file).await.ok().flatten().unwrap_or_default();
        for p in projects {
            if !stored.projects.contains(&p) {
                stored.projects.push(p);
            }
        }
        if let Err(e) = fsutil::write_json(&file, &stored).await {
            tracing::warn!("Paket-Sperrliste nicht gespeichert: {e}");
        }
    }
    names
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_bad_matches_version_and_loader() {
        assert_eq!(known_bad("1.20", LoaderKind::Fabric).collect::<Vec<_>>(), ["51shyZVL"]);
        assert_eq!(known_bad("1.20", LoaderKind::Quilt).count(), 1);
        assert_eq!(known_bad("1.20.1", LoaderKind::Fabric).count(), 0);
        assert_eq!(known_bad("1.20", LoaderKind::Forge).count(), 0);
    }

    #[tokio::test]
    async fn switches_off_only_pack_mods_and_remembers_them() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        let mods = paths.instance_game_dir("test").join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        for (file, project) in [("moreculling.jar", "51shyZVL"), ("own.jar", "ownProj1")] {
            std::fs::write(mods.join(file), b"x").unwrap();
            content::remember_source(&paths, "test", ContentKind::Mod, file, content::Source::modrinth(project.into(), "v1".into(), None))
                .await
                .unwrap();
        }
        let files = vec!["moreculling.jar".to_owned(), "own.jar".to_owned()];
        let healed = heal(&paths, "test", &files).await;
        assert_eq!(healed.len(), 1);
        assert!(mods.join("moreculling.jar.disabled").is_file());
        assert!(mods.join("own.jar").is_file());
        let skip = skipped(&paths, "test", "1.21.1", LoaderKind::Fabric).await;
        assert!(skip.contains("51shyZVL") && !skip.contains("ownProj1"));
        // Schon abgeschaltet: nichts mehr zu tun.
        assert!(heal(&paths, "test", &files[..1]).await.is_empty());
    }
}
