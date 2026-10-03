//! Java-Runtimes für die eingebettete JVM: zur Laufzeit geladen (nie im APK/IPA),
//! per SHA-256 festgenagelt, in den App-Datenordner entpackt.
//!
//! Android: OpenJDK-Builds von AngelAuraMC (angelauramc-openjdk-build, GPLv2 +
//! Classpath Exception), `.tar.xz` mit einem normalen JRE-Baum.

use std::io::Read;
use std::path::{Component, Path, PathBuf};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;

use crate::models::{RuntimeInfo, RuntimePhase, RuntimeProgress};
use crate::{Error, Result};

/// Ein festgenageltes Runtime-Archiv.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuntimeArchive {
    pub java_major: u8,
    /// `aarch64` oder `x86_64` (Emulator).
    pub arch: &'static str,
    pub url: &'static str,
    pub sha256: &'static str,
    pub size: u64,
}

const AAMC: &str = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download";

macro_rules! aamc {
    ($major:literal, $arch:literal, $tag:literal, $file:literal, $sha:literal, $size:literal) => {
        RuntimeArchive {
            java_major: $major,
            arch: $arch,
            url: concat!("https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/", $tag, "/", $file),
            sha256: $sha,
            size: $size,
        }
    };
}

/// Android-Runtimes (Stand 2026-04-04, geprüft 2026-10-03).
pub const ANDROID_RUNTIMES: &[RuntimeArchive] = &[
    aamc!(8, "aarch64", "download_jre8", "jre8-android-arm64.tar.xz", "9a59124d9791957d55c68be664ab76831f336cf2e1e1cd4414220c6fdbf0e06d", 28_026_736),
    aamc!(17, "aarch64", "download_jre17", "jre17-android-arm64.tar.xz", "e162c860fe05ee4a4e4af7606437419879f6c748386a7b09fa77d10db6a64091", 26_900_804),
    aamc!(21, "aarch64", "download_jre21", "jre21-android-arm64.tar.xz", "8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe", 28_675_516),
    aamc!(25, "aarch64", "download_jre25", "jre25-android-arm64.tar.xz", "d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37", 38_031_580),
    aamc!(8, "x86_64", "download_jre8", "jre8-android-x86_64.tar.xz", "b1fbcef4965c17925894febe8216d089c6dd47b37950b5f945a89616443c1d0e", 29_132_884),
    aamc!(17, "x86_64", "download_jre17", "jre17-android-x86_64.tar.xz", "893e27d2aed8b40407f29fe939e2a0f193e5d55f72892a303db634b0808a2b61", 27_780_012),
    aamc!(21, "x86_64", "download_jre21", "jre21-android-x86_64.tar.xz", "cb88723961f5f9ad63afa1f212eb199816c27cabfd7dc66567bde1d8fb69713b", 29_662_988),
    aamc!(25, "x86_64", "download_jre25", "jre25-android-x86_64.tar.xz", "7fca862ee1b2d5fe23cd9c9c3d9b7ad3c241947ad1a6cc9464ef2e674867105d", 39_061_384),
];

/// iOS-Runtimes – trägt der iOS-Teil ein (gleiches Format, Amethyst-iOS-Builds).
pub const IOS_RUNTIMES: &[RuntimeArchive] = &[];

/// Obergrenze für entpackte Daten (Schutz vor Archiv-Bomben; echte JREs ≈ 150–250 MB).
const MAX_UNPACKED: u64 = 1024 * 1024 * 1024;
const MARKER: &str = ".trs-runtime.json";

#[derive(Debug, Serialize, Deserialize)]
struct Marker {
    java_major: u8,
    arch: String,
    sha256: String,
}

/// Archiv für diese Plattform/Architektur.
pub fn archive_for(table: &'static [RuntimeArchive], java_major: u8, arch: &str) -> Result<&'static RuntimeArchive> {
    if !crate::models::JAVA_MAJORS.contains(&java_major) {
        return Err(Error::UnsupportedJava(java_major));
    }
    table
        .iter()
        .find(|a| a.java_major == java_major && a.arch == arch)
        .ok_or_else(|| Error::UnsupportedArch(arch.to_owned()))
}

/// Tabelle des laufenden Systems.
pub fn platform_table() -> &'static [RuntimeArchive] {
    if cfg!(target_os = "ios") { IOS_RUNTIMES } else { ANDROID_RUNTIMES }
}

/// Architektur-Name wie in den Tabellen.
pub fn current_arch() -> &'static str {
    std::env::consts::ARCH
}

pub fn runtime_dir(root: &Path, java_major: u8) -> PathBuf {
    root.join(format!("jre-{java_major}"))
}

/// Bereits installierte Runtime (passender Marker), sonst `None`.
pub fn installed(root: &Path, archive: &RuntimeArchive) -> Option<RuntimeInfo> {
    let home = runtime_dir(root, archive.java_major);
    let marker: Marker = serde_json::from_slice(&std::fs::read(home.join(MARKER)).ok()?).ok()?;
    if marker.sha256 != archive.sha256 || marker.arch != archive.arch || marker.java_major != archive.java_major {
        return None;
    }
    Some(info(&home, archive))
}

fn info(home: &Path, archive: &RuntimeArchive) -> RuntimeInfo {
    RuntimeInfo { java_major: archive.java_major, home: home.to_owned(), version: release_version(home), arch: archive.arch.to_owned() }
}

/// `JAVA_VERSION="21.0.8"` aus `release`.
fn release_version(home: &Path) -> Option<String> {
    let text = std::fs::read_to_string(home.join("release")).ok()?;
    text.lines()
        .find_map(|l| l.strip_prefix("JAVA_VERSION="))
        .map(|v| v.trim().trim_matches('"').to_owned())
        .filter(|v| !v.is_empty() && v.len() < 64)
}

/// Nur eine Installation gleichzeitig (zwei Starts dürfen nicht dasselbe Verzeichnis entpacken).
fn install_lock() -> &'static tokio::sync::Mutex<()> {
    static LOCK: OnceLock<tokio::sync::Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| tokio::sync::Mutex::new(()))
}

/// Stellt die Runtime bereit: laden, prüfen, entpacken. Fortschritt über `on_progress`.
pub async fn ensure(
    http: &reqwest::Client,
    root: &Path,
    archive: &RuntimeArchive,
    on_progress: &(dyn Fn(RuntimeProgress) + Send + Sync),
) -> Result<RuntimeInfo> {
    let _guard = install_lock().lock().await;
    if let Some(info) = installed(root, archive) {
        return Ok(info);
    }
    let report = |phase, percent: f64, done: u64, total: u64| {
        on_progress(RuntimeProgress { java_major: archive.java_major, phase, percent, done_bytes: done, total_bytes: total })
    };
    tokio::fs::create_dir_all(root).await?;
    let download = root.join(format!(".jre-{}-{}.tar.xz.part", archive.java_major, archive.arch));
    let result = fetch(http, archive, &download, &report).await;
    if let Err(e) = result {
        let _ = tokio::fs::remove_file(&download).await;
        return Err(e);
    }

    report(RuntimePhase::Unpack, 0.0, 0, archive.size);
    let staging = root.join(format!(".jre-{}.tmp", archive.java_major));
    let _ = tokio::fs::remove_dir_all(&staging).await;
    let (src, dest) = (download.clone(), staging.clone());
    let unpacked = tokio::task::spawn_blocking(move || unpack_tar_xz(&src, &dest))
        .await
        .map_err(|e| Error::Archive(e.to_string()))?;
    let _ = tokio::fs::remove_file(&download).await;
    if let Err(e) = unpacked {
        let _ = tokio::fs::remove_dir_all(&staging).await;
        return Err(e);
    }
    if !staging.join("release").is_file() {
        let _ = tokio::fs::remove_dir_all(&staging).await;
        return Err(Error::Archive("release fehlt".into()));
    }
    let marker = Marker { java_major: archive.java_major, arch: archive.arch.to_owned(), sha256: archive.sha256.to_owned() };
    let marker = serde_json::to_vec(&marker).map_err(|e| Error::Archive(e.to_string()))?;
    tokio::fs::write(staging.join(MARKER), marker).await?;

    let home = runtime_dir(root, archive.java_major);
    let _ = tokio::fs::remove_dir_all(&home).await;
    tokio::fs::rename(&staging, &home).await?;
    report(RuntimePhase::Done, 100.0, archive.size, archive.size);
    log::info!("Java {} ({}) installiert: {}", archive.java_major, archive.arch, home.display());
    Ok(info(&home, archive))
}

/// Lädt nach `dest` und prüft Größe + SHA-256 beim Schreiben.
async fn fetch(
    http: &reqwest::Client,
    archive: &RuntimeArchive,
    dest: &Path,
    report: &(dyn Fn(RuntimePhase, f64, u64, u64) + Sync),
) -> Result<()> {
    report(RuntimePhase::Download, 0.0, 0, archive.size);
    let mut response = http
        .get(archive.url)
        .timeout(Duration::from_secs(15 * 60))
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(|e| Error::Download(e.to_string()))?;
    let mut file = tokio::fs::File::create(dest).await?;
    let mut hasher = Sha256::new();
    let mut done: u64 = 0;
    let mut last = Instant::now();
    while let Some(chunk) = response.chunk().await.map_err(|e| Error::Download(e.to_string()))? {
        done += chunk.len() as u64;
        if done > archive.size {
            return Err(Error::Download("größer als erwartet".into()));
        }
        hasher.update(&chunk);
        file.write_all(&chunk).await?;
        if last.elapsed() >= Duration::from_millis(200) {
            last = Instant::now();
            report(RuntimePhase::Download, done as f64 * 100.0 / archive.size as f64, done, archive.size);
        }
    }
    file.flush().await?;
    drop(file);
    report(RuntimePhase::Verify, 100.0, done, archive.size);
    if done != archive.size || !hex(&hasher.finalize()).eq_ignore_ascii_case(archive.sha256) {
        return Err(Error::Checksum);
    }
    Ok(())
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Entpackt ein `.tar.xz` nach `dest`. Nur normale Dateien und Ordner; Links,
/// Geräte und Pfade außerhalb von `dest` werden übersprungen bzw. abgelehnt.
pub fn unpack_tar_xz(archive: &Path, dest: &Path) -> Result<()> {
    let file = std::fs::File::open(archive)?;
    let xz = lzma_rust2::XzReader::new(std::io::BufReader::new(file), true);
    unpack_tar(xz, dest)
}

fn unpack_tar(reader: impl Read, dest: &Path) -> Result<()> {
    std::fs::create_dir_all(dest)?;
    let mut tar = tar::Archive::new(reader);
    let mut total: u64 = 0;
    let entries = tar.entries().map_err(|e| Error::Archive(e.to_string()))?;
    for entry in entries {
        let mut entry = entry.map_err(|e| Error::Archive(e.to_string()))?;
        let kind = entry.header().entry_type();
        if !(kind.is_file() || kind.is_dir()) {
            // Symlinks (nur `legal/` und `libjsig`) und Sonderdateien braucht die JVM nicht.
            continue;
        }
        let path = entry.path().map_err(|e| Error::Archive(e.to_string()))?.into_owned();
        let Some(rel) = safe_relative(&path) else {
            return Err(Error::Archive(format!("unsicherer Pfad {}", path.display())));
        };
        if rel.as_os_str().is_empty() {
            continue;
        }
        let target = dest.join(&rel);
        if kind.is_dir() {
            std::fs::create_dir_all(&target)?;
            continue;
        }
        total += entry.header().size().map_err(|e| Error::Archive(e.to_string()))?;
        if total > MAX_UNPACKED {
            return Err(Error::Archive("zu groß".into()));
        }
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mode = entry.header().mode().unwrap_or(0o644);
        let mut out = std::fs::File::create(&target)?;
        std::io::copy(&mut entry, &mut out)?;
        set_mode(&target, mode)?;
    }
    Ok(())
}

/// Nur normale Bestandteile (kein `..`, keine Wurzel/Laufwerke).
fn safe_relative(path: &Path) -> Option<PathBuf> {
    let mut out = PathBuf::new();
    for comp in path.components() {
        match comp {
            Component::Normal(part) => out.push(part),
            Component::CurDir => {}
            _ => return None,
        }
    }
    Some(out)
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    // Ausführbar bleibt ausführbar; nie Gruppen-/Welt-Schreibrechte.
    let mode = if mode & 0o111 != 0 { 0o755 } else { 0o644 };
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))?;
    Ok(())
}

#[cfg(not(unix))]
fn set_mode(_path: &Path, _mode: u32) -> Result<()> {
    Ok(())
}

/// Für Diagnose/Tests: Basis-URL der Android-Runtimes.
pub fn android_source() -> &'static str {
    AAMC
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tar_xz(entries: &[(&str, &[u8])], links: &[(&str, &str)]) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        for (name, data) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_size(data.len() as u64);
            header.set_mode(if name.contains("bin/") { 0o755 } else { 0o644 });
            header.set_entry_type(tar::EntryType::Regular);
            header.set_cksum();
            builder.append_data(&mut header, name, *data).unwrap();
        }
        for (name, target) in links {
            let mut header = tar::Header::new_gnu();
            header.set_entry_type(tar::EntryType::Symlink);
            header.set_size(0);
            builder.append_link(&mut header, name, target).unwrap();
        }
        let tar = builder.into_inner().unwrap();
        let mut out = Vec::new();
        {
            let mut xz = lzma_rust2::XzWriter::new(&mut out, lzma_rust2::XzOptions::default()).unwrap();
            std::io::Write::write_all(&mut xz, &tar).unwrap();
            xz.finish().unwrap();
        }
        out
    }

    #[test]
    fn table_is_complete_for_android() {
        for arch in ["aarch64", "x86_64"] {
            for major in crate::models::JAVA_MAJORS {
                let a = archive_for(ANDROID_RUNTIMES, major, arch).unwrap();
                assert_eq!(a.sha256.len(), 64);
                assert!(a.url.starts_with(android_source()));
                assert!(a.url.ends_with(".tar.xz"));
            }
        }
        assert!(archive_for(ANDROID_RUNTIMES, 11, "aarch64").is_err());
        assert!(archive_for(ANDROID_RUNTIMES, 21, "riscv64").is_err());
    }

    #[test]
    fn unpacks_files_and_skips_links() {
        let dir = tempfile::tempdir().unwrap();
        let archive = dir.path().join("jre.tar.xz");
        let data = tar_xz(
            &[("./release", b"JAVA_VERSION=\"21.0.8\"\n"), ("./bin/java", b"ELF"), ("./lib/server/libjvm.so", b"so")],
            &[("./legal/x/LICENSE", "../java.base/LICENSE")],
        );
        std::fs::write(&archive, data).unwrap();
        let dest = dir.path().join("out");
        unpack_tar_xz(&archive, &dest).unwrap();
        assert!(dest.join("lib/server/libjvm.so").is_file());
        assert!(!dest.join("legal/x/LICENSE").exists());
        assert_eq!(release_version(&dest).as_deref(), Some("21.0.8"));
    }

    #[test]
    fn rejects_traversal() {
        assert!(safe_relative(Path::new("../evil")).is_none());
        assert!(safe_relative(Path::new("/etc/passwd")).is_none());
        assert_eq!(safe_relative(Path::new("./lib/a.so")), Some(PathBuf::from("lib/a.so")));
    }

    #[test]
    fn marker_must_match_pinned_hash() {
        let dir = tempfile::tempdir().unwrap();
        let archive = archive_for(ANDROID_RUNTIMES, 17, "aarch64").unwrap();
        let home = runtime_dir(dir.path(), 17);
        std::fs::create_dir_all(&home).unwrap();
        let marker = Marker { java_major: 17, arch: "aarch64".into(), sha256: "00".repeat(32) };
        std::fs::write(home.join(MARKER), serde_json::to_vec(&marker).unwrap()).unwrap();
        assert!(installed(dir.path(), archive).is_none());
        let marker = Marker { java_major: 17, arch: "aarch64".into(), sha256: archive.sha256.into() };
        std::fs::write(home.join(MARKER), serde_json::to_vec(&marker).unwrap()).unwrap();
        assert_eq!(installed(dir.path(), archive).unwrap().java_major, 17);
    }
}
