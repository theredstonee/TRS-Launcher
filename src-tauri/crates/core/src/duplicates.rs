//! Dieselbe Mod-ID in mehreren aktivierten Jars.
//!
//! Fabric lädt jede Kopie. Zwei Sodium- oder MaliLib-Dateien verzögern das
//! Spielfenster, drücken die FPS und stürzen im Vollbild oft ab. Ältere
//! Kopien werden deaktiviert (Endung `.disabled`), nie gelöscht.

use std::collections::HashMap;
use std::time::SystemTime;

use serde::Serialize;

use crate::content::{self, ContentKind};
use crate::instance::validate_id;
use crate::modcompat::meta::{self, ModInfo};
use crate::modcompat::remote::local_mod_details;
use crate::modcompat::version::SemVer;
use crate::paths::Paths;
use crate::Result;

/// Loader und das Spiel selbst – keine Mods, die der Nutzer doppelt haben kann.
const LOADER_IDS: &[&str] = &["minecraft", "java", "fabric", "fabricloader", "forge", "neoforge", "quilt_loader"];

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DuplicateFile {
    pub file_name: String,
    pub version: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DuplicateModGroup {
    pub id: String,
    pub name: String,
    /// Neueste Datei (höchste Version, sonst neueste Änderungszeit). Bleibt aktiv.
    pub keep: DuplicateFile,
    /// Ältere Kopien. Werden nur deaktiviert.
    pub disable: Vec<DuplicateFile>,
}

/// Eine aktivierte Jar, schon auf die Haupt-Mod-ID reduziert.
#[derive(Debug, Clone)]
pub struct ModFile {
    pub file_name: String,
    pub id: String,
    pub name: String,
    pub version: Option<String>,
    pub modified: Option<SystemTime>,
}

fn is_newer(candidate: &ModFile, best: &ModFile) -> bool {
    // Wie der Absturz-Helfer: höhere SemVer gewinnt, sonst die neuere Datei.
    // Gleiche oder unlesbare Versionen fallen auf die Änderungszeit zurück.
    match (
        candidate.version.as_deref().and_then(SemVer::parse),
        best.version.as_deref().and_then(SemVer::parse),
    ) {
        (Some(a), Some(b)) if a != b => a > b,
        _ => candidate.modified.unwrap_or(SystemTime::UNIX_EPOCH) > best.modified.unwrap_or(SystemTime::UNIX_EPOCH),
    }
}

fn as_file(m: &ModFile) -> DuplicateFile {
    DuplicateFile { file_name: m.file_name.clone(), version: m.version.clone() }
}

/// Gruppen mit mindestens zwei Dateien derselben Haupt-ID. `provides` und
/// eingebettete Jars zählen nicht mit – sonst wäre jede Mod mit MixinExtras doppelt.
pub fn group_duplicates(candidates: &[ModFile]) -> Vec<DuplicateModGroup> {
    let mut order = Vec::new();
    let mut groups: HashMap<String, Vec<&ModFile>> = HashMap::new();
    for c in candidates {
        if !groups.contains_key(&c.id) {
            order.push(c.id.clone());
        }
        groups.entry(c.id.clone()).or_default().push(c);
    }
    let mut out = Vec::new();
    for id in order {
        let list = &groups[&id];
        if list.len() < 2 {
            continue;
        }
        let mut best = 0;
        for i in 1..list.len() {
            if is_newer(list[i], list[best]) {
                best = i;
            }
        }
        let keep = list[best];
        out.push(DuplicateModGroup {
            name: keep.name.clone(),
            keep: as_file(keep),
            disable: list.iter().enumerate().filter(|(i, _)| *i != best).map(|(_, m)| as_file(m)).collect(),
            id,
        });
    }
    out.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()).then(a.id.cmp(&b.id)));
    out
}

fn from_mod(file_name: String, modified: Option<SystemTime>, main: ModInfo) -> Option<ModFile> {
    if !meta::is_mod_id(&main.id) || LOADER_IDS.contains(&main.id.as_str()) {
        return None;
    }
    let name = main.display_name().to_owned();
    let version = (!main.version.is_empty()).then(|| main.version.clone());
    Some(ModFile { file_name, id: main.id, name, version, modified })
}

/// Aktivierte Mods der Instanz, gruppiert nach der Haupt-ID aus
/// `fabric.mod.json`, `quilt.mod.json` oder `mods.toml` – nicht nach dem Dateinamen.
pub async fn find(paths: &Paths, instance_id: &str) -> Result<Vec<DuplicateModGroup>> {
    validate_id(instance_id)?;
    let items = content::list(paths, instance_id, ContentKind::Mod).await?;
    let mut candidates = Vec::new();
    for item in items.into_iter().filter(|i| i.enabled) {
        let Some(path) = content::existing_file(paths, instance_id, ContentKind::Mod, &item.file_name) else { continue };
        let modified = tokio::fs::metadata(&path).await.ok().and_then(|m| m.modified().ok());
        let (mods, _nested) = local_mod_details(path).await;
        let Some(main) = mods.into_iter().next() else { continue };
        if let Some(file) = from_mod(item.file_name, modified, main) {
            candidates.push(file);
        }
    }
    Ok(group_duplicates(&candidates))
}

/// Deaktiviert die älteren Kopien. Die Dateien bleiben als `name.jar.disabled` liegen.
pub async fn disable_older(paths: &Paths, instance_id: &str) -> Result<Vec<String>> {
    let groups = find(paths, instance_id).await?;
    let mut disabled = Vec::new();
    for group in groups {
        for file in group.disable {
            content::set_enabled(paths, instance_id, ContentKind::Mod, &file.file_name, false).await?;
            disabled.push(file.file_name);
        }
    }
    Ok(disabled)
}

/// Nur für Tests: Jar mit den angegebenen Einträgen schreiben.
#[cfg(test)]
fn write_jar(path: &std::path::Path, files: &[(&str, &[u8])]) {
    use std::io::Write;
    let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    for (name, data) in files {
        zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(data).unwrap();
    }
    zip.finish().unwrap();
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, SystemTime};

    use super::*;
    use crate::fsutil;

    fn file(name: &str, id: &str, version: Option<&str>, secs: u64) -> ModFile {
        ModFile {
            file_name: name.into(),
            id: id.into(),
            name: id.into(),
            version: version.map(str::to_owned),
            modified: Some(SystemTime::UNIX_EPOCH + Duration::from_secs(secs)),
        }
    }

    #[test]
    fn keeps_the_higher_semver_then_the_newer_file() {
        let sodium = [
            file("sodium-0.8.7.jar", "sodium", Some("0.8.7"), 50),
            file("sodium-0.8.14.jar", "sodium", Some("0.8.14"), 10),
        ];
        let groups = group_duplicates(&sodium);
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].keep.file_name, "sodium-0.8.14.jar");
        assert_eq!(groups[0].disable[0].file_name, "sodium-0.8.7.jar");

        // Gleiche Version: die neuere Datei bleibt.
        let same = [
            file("a.jar", "malilib", Some("0.27.20"), 10),
            file("b.jar", "malilib", Some("0.27.20"), 40),
        ];
        let groups = group_duplicates(&same);
        assert_eq!(groups[0].keep.file_name, "b.jar");
        assert_eq!(groups[0].disable[0].file_name, "a.jar");

        // Unlesbare Versionen: ebenfalls die neuere Datei.
        let text = [file("old.jar", "iris", Some("beta"), 5), file("new.jar", "iris", Some("also-beta"), 9)];
        assert_eq!(group_duplicates(&text)[0].keep.file_name, "new.jar");

        // Eine Datei ist keine Gruppe. Andere IDs vermischen sich nicht.
        let mixed = [
            file("only.jar", "lithium", Some("0.21.4"), 1),
            file("s1.jar", "sodium", Some("0.8.7"), 1),
            file("i1.jar", "iris", Some("1.10.7"), 1),
        ];
        assert!(group_duplicates(&mixed).is_empty());
    }

    #[tokio::test]
    async fn finds_duplicate_ids_and_disables_the_older_jar() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        let mods = content::content_dir(&paths, "test", ContentKind::Mod);
        fsutil::ensure_dir(&mods).await.unwrap();

        // Dateiname sagt nichts: die neuere Sodium steckt in `not-sodium.jar` (mods.toml).
        let toml = br#"
modLoader="javafml"
loaderVersion="[1,)"
[[mods]]
modId="sodium"
version="0.8.14"
displayName="Sodium"
"#;
        write_jar(&mods.join("not-sodium.jar"), &[("META-INF/mods.toml", toml.as_slice())]);
        write_jar(
            &mods.join("sodium-0.8.7.jar"),
            &[("fabric.mod.json", br#"{"schemaVersion":1,"id":"sodium","name":"Sodium","version":"0.8.7"}"#)],
        );
        write_jar(
            &mods.join("malilib-0.27.15.jar"),
            &[("fabric.mod.json", br#"{"schemaVersion":1,"id":"malilib","name":"MaliLib","version":"0.27.15"}"#)],
        );
        write_jar(
            &mods.join("malilib-0.27.20.jar"),
            &[("fabric.mod.json", br#"{"schemaVersion":1,"id":"malilib","name":"MaliLib","version":"0.27.20"}"#)],
        );
        write_jar(
            &mods.join("iris.jar"),
            &[("fabric.mod.json", br#"{"schemaVersion":1,"id":"iris","name":"Iris","version":"1.10.7"}"#)],
        );
        // Deaktiviert und Loader-IDs zählen nicht.
        write_jar(
            &mods.join("sodium-old.jar.disabled"),
            &[("fabric.mod.json", br#"{"schemaVersion":1,"id":"sodium","version":"0.5.0"}"#)],
        );
        write_jar(
            &mods.join("loader-a.jar"),
            &[("fabric.mod.json", br#"{"schemaVersion":1,"id":"minecraft","version":"1.21.11"}"#)],
        );
        write_jar(
            &mods.join("loader-b.jar"),
            &[("fabric.mod.json", br#"{"schemaVersion":1,"id":"minecraft","version":"1.21.1"}"#)],
        );

        let groups = find(&paths, "test").await.unwrap();
        assert_eq!(groups.iter().map(|g| g.id.as_str()).collect::<Vec<_>>(), ["malilib", "sodium"]);
        let sodium = groups.iter().find(|g| g.id == "sodium").unwrap();
        assert_eq!(sodium.keep.file_name, "not-sodium.jar");
        assert_eq!(sodium.keep.version.as_deref(), Some("0.8.14"));
        assert_eq!(sodium.disable.len(), 1);
        assert_eq!(sodium.disable[0].file_name, "sodium-0.8.7.jar");
        let malilib = groups.iter().find(|g| g.id == "malilib").unwrap();
        assert_eq!(malilib.keep.file_name, "malilib-0.27.20.jar");
        assert_eq!(malilib.disable[0].file_name, "malilib-0.27.15.jar");

        let mut disabled = disable_older(&paths, "test").await.unwrap();
        disabled.sort();
        assert_eq!(disabled, ["malilib-0.27.15.jar", "sodium-0.8.7.jar"]);
        assert!(mods.join("sodium-0.8.7.jar.disabled").is_file());
        assert!(!mods.join("sodium-0.8.7.jar").exists());
        assert!(mods.join("not-sodium.jar").is_file());
        assert!(mods.join("malilib-0.27.20.jar").is_file());
        assert!(mods.join("malilib-0.27.15.jar.disabled").is_file());
        assert!(mods.join("iris.jar").is_file());
        assert!(mods.join("sodium-old.jar.disabled").is_file());
        assert!(find(&paths, "test").await.unwrap().is_empty());
    }
}
