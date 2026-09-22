//! Speicherverwaltung: wie viel Platz Instanzen und gemeinsame Spieldateien
//! belegen, nicht mehr benötigte Spielversionen aufräumen und die geteilten
//! Asset-Dateien per Prüfsumme kontrollieren.
//!
//! Aufräumen ist bewusst vorsichtig: gelöscht werden nur Ordner unter
//! `versions/`, deren Namensschema wir sicher zuordnen können und die keine
//! Instanz mehr braucht. Alles Unbekannte gilt als benutzt.

use std::io::Read;
use std::path::{Path, PathBuf};

use serde::Serialize;
use sha1::{Digest, Sha1};

use crate::instance::{Instance, LoaderKind};
use crate::paths::Paths;
use crate::{Error, Result};

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageStats {
    pub instances: u64,
    pub libraries: u64,
    pub assets: u64,
    pub versions: u64,
    pub java: u64,
    /// Gemeinsam synchronisierte Dateien (`shared/`).
    pub shared: u64,
    /// Davon löschbar: Spielversionen, die keine Instanz mehr braucht.
    pub unused: u64,
    pub unused_versions: usize,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VerifyReport {
    pub checked: usize,
    /// Beschädigte Dateien, die entfernt wurden (werden beim nächsten Start neu geladen).
    pub removed: usize,
}

/// Größe eines Ordners samt Unterordnern; Symlinks werden nicht verfolgt.
pub fn dir_size(dir: &Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
    entries
        .flatten()
        .map(|e| match e.file_type() {
            Ok(t) if t.is_dir() => dir_size(&e.path()),
            Ok(t) if t.is_file() => e.metadata().map(|m| m.len()).unwrap_or(0),
            _ => 0,
        })
        .sum()
}

/// Braucht irgendeine Instanz diesen Versionsordner? Im Zweifel ja.
pub fn is_version_used(dir_name: &str, instances: &[Instance]) -> bool {
    let has_game = |g: &str| instances.iter().any(|i| i.game_version == g);
    if let Some(rest) = dir_name.strip_prefix("fabric-loader-").or_else(|| dir_name.strip_prefix("quilt-loader-")) {
        // `fabric-loader-<loader>-<spiel>`: Spielversion ist alles nach dem ersten `-`.
        return rest.split_once('-').is_none_or(|(_, game)| has_game(game));
    }
    if dir_name.starts_with("neoforge-") {
        return instances.iter().any(|i| i.loader.kind == LoaderKind::NeoForge || i.loader.kind == LoaderKind::Vanilla);
    }
    if let Some((game, _)) = dir_name.split_once("-forge") {
        return has_game(game);
    }
    // Reine Spielversionen: nur Zeichen, die Mojang-IDs haben.
    let looks_vanilla = dir_name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | ' '));
    !looks_vanilla || has_game(dir_name)
}

fn unused_version_dirs(paths: &Paths, instances: &[Instance]) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(paths.versions_dir()) else { return Vec::new() };
    entries
        .flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
        .filter(|e| e.file_name().to_str().is_some_and(|n| !is_version_used(n, instances)))
        .map(|e| e.path())
        .collect()
}

pub async fn stats(paths: &Paths, instances: Vec<Instance>) -> Result<StorageStats> {
    let paths = paths.clone();
    tokio::task::spawn_blocking(move || {
        let unused_dirs = unused_version_dirs(&paths, &instances);
        StorageStats {
            instances: dir_size(&paths.instances_dir()),
            libraries: dir_size(&paths.libraries_dir()),
            assets: dir_size(&paths.assets_dir()),
            versions: dir_size(&paths.versions_dir()),
            java: dir_size(&paths.java_dir()),
            shared: dir_size(&paths.shared_dir()),
            unused: unused_dirs.iter().map(|d| dir_size(d)).sum(),
            unused_versions: unused_dirs.len(),
        }
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))
}

/// Löscht nicht mehr benötigte Spielversionen; liefert die freigegebenen Bytes.
/// Der Aufrufer muss sicherstellen, dass gerade nichts gestartet wird.
pub async fn clean_unused(paths: &Paths, instances: Vec<Instance>) -> Result<u64> {
    let paths = paths.clone();
    tokio::task::spawn_blocking(move || {
        let mut freed = 0;
        for dir in unused_version_dirs(&paths, &instances) {
            // Nur direkte Unterordner von versions/ – nie etwas anderes.
            if dir.parent() != Some(paths.versions_dir().as_path()) {
                continue;
            }
            let size = dir_size(&dir);
            match std::fs::remove_dir_all(&dir) {
                Ok(()) => freed += size,
                Err(e) => tracing::warn!("{} konnte nicht gelöscht werden: {e}", dir.display()),
            }
        }
        freed
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))
}

fn sha1_hex(path: &Path) -> Option<String> {
    let mut file = std::fs::File::open(path).ok()?;
    let mut hasher = Sha1::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Some(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

/// Prüft alle Asset-Objekte (Dateiname = SHA-1) und entfernt beschädigte.
pub async fn verify_assets(paths: &Paths) -> Result<VerifyReport> {
    let objects = paths.assets_dir().join("objects");
    tokio::task::spawn_blocking(move || {
        let mut report = VerifyReport::default();
        let Ok(buckets) = std::fs::read_dir(&objects) else { return report };
        for bucket in buckets.flatten() {
            let Ok(files) = std::fs::read_dir(bucket.path()) else { continue };
            for file in files.flatten() {
                let Some(name) = file.file_name().to_str().map(str::to_owned) else { continue };
                if name.len() != 40 || !name.bytes().all(|b| b.is_ascii_hexdigit()) {
                    continue;
                }
                report.checked += 1;
                if sha1_hex(&file.path()).is_none_or(|h| !h.eq_ignore_ascii_case(&name)) && std::fs::remove_file(file.path()).is_ok() {
                    report.removed += 1;
                }
            }
        }
        report
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))
}

#[cfg(test)]
mod tests {
    use chrono::Utc;

    use super::*;
    use crate::instance::{InstanceOverrides, Loader};

    fn inst(game: &str, kind: LoaderKind) -> Instance {
        Instance {
            id: "x".into(),
            name: "x".into(),
            game_version: game.into(),
            loader: Loader { kind, version: None },
            created_at: Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            overrides: InstanceOverrides::default(),
            icon: None,
            group: None,
        }
    }

    #[test]
    fn version_usage() {
        let list = [inst("1.21.1", LoaderKind::Fabric), inst("1.20.1", LoaderKind::Forge)];
        assert!(is_version_used("1.21.1", &list));
        assert!(!is_version_used("1.19.4", &list));
        assert!(is_version_used("fabric-loader-0.16.10-1.21.1", &list));
        assert!(!is_version_used("fabric-loader-0.16.10-1.19.4", &list));
        assert!(is_version_used("1.20.1-forge-47.4.10", &list));
        assert!(!is_version_used("1.12.2-forge-14.23.5.2860", &list));
        assert!(!is_version_used("neoforge-21.1.251", &list));
        assert!(is_version_used("neoforge-21.1.251", &[inst("1.21.1", LoaderKind::NeoForge)]));
        // Unbekanntes Schema bleibt.
        assert!(is_version_used("OptiFine@1.8.9", &list));
    }

    #[tokio::test]
    async fn stats_clean_and_verify() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        let write = |p: PathBuf, bytes: &[u8]| {
            std::fs::create_dir_all(p.parent().unwrap()).unwrap();
            std::fs::write(p, bytes).unwrap();
        };
        write(paths.version_jar("1.21.1"), &[0; 100]);
        write(paths.version_jar("1.8.9"), &[0; 50]);
        write(paths.instance_game_dir("a").join("options.txt"), &[0; 10]);

        let list = vec![inst("1.21.1", LoaderKind::Vanilla)];
        let s = stats(&paths, list.clone()).await.unwrap();
        assert_eq!((s.versions, s.unused, s.unused_versions, s.instances), (150, 50, 1, 10));
        assert_eq!(clean_unused(&paths, list.clone()).await.unwrap(), 50);
        assert!(paths.version_jar("1.21.1").is_file());
        assert!(!paths.version_dir("1.8.9").exists());

        let good = paths.asset_object("a9993e364706816aba3e25717850c26c9cd0d89d");
        write(good, b"abc");
        write(paths.asset_object("0000000000000000000000000000000000000000"), b"kaputt");
        let r = verify_assets(&paths).await.unwrap();
        assert_eq!((r.checked, r.removed), (2, 1));
        assert!(paths.asset_object("a9993e364706816aba3e25717850c26c9cd0d89d").is_file());
    }
}
