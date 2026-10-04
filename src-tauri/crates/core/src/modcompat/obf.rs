//! Ab Minecraft 26.1 ist das Spiel nicht mehr verschleiert: Fabric/Quilt laden
//! es mit Mojangs Namen, ohne Intermediary. Eine Mod, die für 1.21.x oder
//! älter gebaut wurde, verweist aber auf `net/minecraft/class_2769` & Co. und
//! stürzt beim Laden ab (`NoClassDefFoundError`) – auch wenn ihre
//! `fabric.mod.json` „>=1.21“ erlaubt. Erkannt wird das an den Klassen im Jar
//! (auch eingebetteten Jars); das Ergebnis wird je Datei (SHA-1) gemerkt.

use std::collections::HashMap;
use std::io::{Read, Seek};
use std::path::{Path, PathBuf};

use super::meta;

/// Intermediary-Klassennamen im Bytecode.
const NEEDLE: &[u8] = b"net/minecraft/class_";
/// Größere Klassen lesen wir nicht.
const MAX_CLASS_BYTES: u64 = 4 * 1024 * 1024;
const MAX_NESTED_BYTES: u64 = 16 * 1024 * 1024;
const MAX_ENTRIES: usize = 40_000;
/// So viel wird je Datei höchstens entpackt.
const MAX_TOTAL_BYTES: u64 = 256 * 1024 * 1024;
const MAX_DEPTH: u8 = 2;

/// Läuft `game_version` ohne Verschleierung (26.1 und neuer; nur Vollversionen –
/// bei Snapshots raten wir nicht)?
pub(crate) fn is_unobfuscated(game_version: &str) -> bool {
    super::is_release_version(game_version)
        && game_version.split('.').next().and_then(|m| m.parse::<u32>().ok()).is_some_and(|m| m >= 26)
}

fn contains(haystack: &[u8]) -> bool {
    haystack.windows(NEEDLE.len()).any(|w| w == NEEDLE)
}

/// Verweist eine Klasse im Jar (oder in einem eingebetteten) auf Intermediary-Namen?
pub(crate) fn uses_intermediary<R: Read + Seek>(reader: R) -> bool {
    let Ok(mut archive) = zip::ZipArchive::new(reader) else { return false };
    let mut budget = MAX_TOTAL_BYTES;
    scan(&mut archive, 1, &mut budget)
}

fn scan<R: Read + Seek>(archive: &mut zip::ZipArchive<R>, depth: u8, budget: &mut u64) -> bool {
    let names: Vec<String> = archive.file_names().take(MAX_ENTRIES).map(str::to_owned).collect();
    let mut nested = Vec::new();
    for name in names {
        let lower = name.to_ascii_lowercase();
        if lower.ends_with(".jar") && ["meta-inf/jars/", "meta-inf/jarjar/"].iter().any(|d| lower.starts_with(d)) {
            nested.push(name);
            continue;
        }
        if !lower.ends_with(".class") {
            continue;
        }
        let Ok(entry) = archive.by_name(&name) else { continue };
        if entry.size() > MAX_CLASS_BYTES || entry.size() > *budget {
            continue;
        }
        let mut bytes = Vec::new();
        if entry.take(MAX_CLASS_BYTES).read_to_end(&mut bytes).is_err() {
            continue;
        }
        *budget = budget.saturating_sub(bytes.len() as u64);
        if contains(&bytes) {
            return true;
        }
    }
    if depth >= MAX_DEPTH {
        return false;
    }
    for name in nested {
        let Ok(entry) = archive.by_name(&name) else { continue };
        if entry.size() > MAX_NESTED_BYTES || entry.size() > *budget {
            continue;
        }
        let mut bytes = Vec::new();
        if entry.take(MAX_NESTED_BYTES).read_to_end(&mut bytes).is_err() {
            continue;
        }
        *budget = budget.saturating_sub(bytes.len() as u64);
        let Ok(mut inner) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) else { continue };
        if scan(&mut inner, depth + 1, budget) {
            return true;
        }
    }
    false
}

type Stamp = (u64, Option<std::time::SystemTime>);

/// Schon geprüfte Dateien dieser Sitzung (Größe + Änderungszeit → Ergebnis).
static SCANNED: std::sync::LazyLock<std::sync::Mutex<HashMap<PathBuf, (Stamp, bool)>>> =
    std::sync::LazyLock::new(Default::default);
const MAX_SCANNED: usize = 5000;

/// [`uses_intermediary`] für eine Datei der Instanz – gemerkt je Sitzung und
/// je SHA-1 auf der Platte (`cache_dir/<sha1>.intermediary`).
pub(crate) async fn local_uses_intermediary(cache_dir: &Path, path: PathBuf) -> bool {
    let stamp: Option<Stamp> = tokio::fs::metadata(&path).await.ok().map(|m| (m.len(), m.modified().ok()));
    let memory = || SCANNED.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(stamp) = stamp
        && let Some((s, found)) = memory().get(&path)
        && *s == stamp
    {
        return *found;
    }
    let sha1 = crate::download::sha1_of_file(&path).await.ok();
    let disk = sha1.as_ref().map(|s| cache_dir.join(format!("{s}.intermediary")));
    let cached = match &disk {
        Some(file) => tokio::fs::read_to_string(file).await.ok().map(|t| t.trim() == "1"),
        None => None,
    };
    let found = match cached {
        Some(found) => found,
        None => {
            let scan_path = path.clone();
            let found = tokio::task::spawn_blocking(move || {
                std::fs::File::open(&scan_path).map(|f| uses_intermediary(std::io::BufReader::new(f))).unwrap_or(false)
            })
            .await
            .unwrap_or(false);
            if let Some(file) = &disk {
                let _ = tokio::fs::create_dir_all(cache_dir).await;
                if let Err(e) = tokio::fs::write(file, if found { "1" } else { "0" }).await {
                    tracing::debug!("Intermediary-Prüfung nicht zwischengespeichert: {e}");
                }
            }
            found
        }
    };
    if let Some(stamp) = stamp {
        let mut memory = memory();
        if memory.len() >= MAX_SCANNED {
            memory.clear();
        }
        memory.insert(path, (stamp, found));
    }
    found
}

/// Mods ab 26.1 (Fabric/Quilt), die für ein verschleiertes Minecraft gebaut
/// wurden: setzt [`super::Entry::old_build`]. Der TRS Client wird nie markiert.
pub(crate) async fn mark_old_builds(paths: &crate::paths::Paths, instance: &crate::instance::Instance, entries: &mut [super::Entry]) {
    use futures::StreamExt;

    use crate::content::{self, ContentKind};
    use crate::instance::LoaderKind;

    if !matches!(instance.loader.kind, LoaderKind::Fabric | LoaderKind::Quilt) || !is_unobfuscated(&instance.game_version) {
        return;
    }
    let cache_dir = super::cache_dir(paths);
    let jobs: Vec<(usize, PathBuf)> = entries
        .iter()
        .enumerate()
        .filter(|(_, e)| e.mods.iter().any(|m| m.scheme == meta::Scheme::Fabric))
        .filter_map(|(i, e)| {
            let file = e.file_name.as_deref()?;
            if crate::client_mod::is_client_mod_file(ContentKind::Mod, file) {
                return None;
            }
            content::existing_file(paths, &instance.id, ContentKind::Mod, file).map(|p| (i, p))
        })
        .collect();
    let results: Vec<(usize, bool)> = futures::stream::iter(jobs.into_iter().map(|(i, path)| {
        let cache_dir = &cache_dir;
        async move { (i, local_uses_intermediary(cache_dir, path).await) }
    }))
    .buffered(4)
    .collect()
    .await;
    for (i, old) in results {
        if old {
            tracing::info!(
                "{} ist für ein älteres Minecraft gebaut (Intermediary-Namen) – passt nicht zu {}",
                entries[i].file_name.as_deref().unwrap_or_default(),
                instance.game_version
            );
        }
        entries[i].old_build = old;
        entries[i].installed_old_build = old;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn jar(entries: &[(&str, &[u8])]) -> Vec<u8> {
        use std::io::Write;
        let mut out = std::io::Cursor::new(Vec::new());
        let mut zip = zip::ZipWriter::new(&mut out);
        for (name, bytes) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
        out.into_inner()
    }

    #[test]
    fn unobfuscated_from_26_1() {
        assert!(is_unobfuscated("26.1"));
        assert!(is_unobfuscated("26.1.2"));
        assert!(is_unobfuscated("27.3"));
        assert!(!is_unobfuscated("1.21.11"));
        assert!(!is_unobfuscated("26.1-snapshot-3"));
        assert!(!is_unobfuscated(""));
    }

    #[test]
    fn finds_intermediary_in_classes_and_nested_jars() {
        let old_class: &[u8] = b"\xCA\xFE\xBA\xBE...Lnet/minecraft/class_2769;...";
        let new_class: &[u8] = b"\xCA\xFE\xBA\xBE...Lnet/minecraft/world/level/block/state/properties/Property;...";
        let fabric = br#"{"id":"x","version":"1"}"#;
        // Für 1.21.x gebaut (wie Dynamic Crosshair 9.12).
        let old = jar(&[("fabric.mod.json", fabric), ("mod/crend/Dyn.class", old_class)]);
        assert!(uses_intermediary(std::io::Cursor::new(old)));
        // Für 26.1 gebaut: Mojangs Namen.
        let new = jar(&[("fabric.mod.json", fabric), ("a/B.class", new_class)]);
        assert!(!uses_intermediary(std::io::Cursor::new(new.clone())));
        // Nur Texte (Refmap, Lang) zählen nicht.
        let text = jar(&[("x.refmap.json", b"net/minecraft/class_1234")]);
        assert!(!uses_intermediary(std::io::Cursor::new(text)));
        // Eingebettet (Jar-in-Jar).
        let inner = jar(&[("lib/C.class", old_class)]);
        let outer = jar(&[("fabric.mod.json", fabric), ("a/B.class", new_class), ("META-INF/jars/lib.jar", &inner)]);
        assert!(uses_intermediary(std::io::Cursor::new(outer)));
        // Kein Zip.
        assert!(!uses_intermediary(std::io::Cursor::new(b"nope".to_vec())));
    }

    #[tokio::test]
    async fn remembers_the_result_per_file() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("old.jar");
        std::fs::write(&file, jar(&[("A.class", b"net/minecraft/class_1")])).unwrap();
        let cache = dir.path().join("cache");
        assert!(local_uses_intermediary(&cache, file.clone()).await);
        let sha1 = crate::download::sha1_of_file(&file).await.unwrap();
        assert_eq!(std::fs::read_to_string(cache.join(format!("{sha1}.intermediary"))).unwrap(), "1");
        // Aus dem Speicher (gleiche Größe + Zeit), auch wenn die Platte etwas anderes sagt.
        std::fs::write(cache.join(format!("{sha1}.intermediary")), "0").unwrap();
        assert!(local_uses_intermediary(&cache, file).await);
    }
}
