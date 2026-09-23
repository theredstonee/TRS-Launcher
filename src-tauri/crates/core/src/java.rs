//! Java-Runtimes aus Mojangs eigenem Runtime-Manifest – dieselben Builds,
//! die auch der offizielle Launcher verwendet.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::download::{self, Progress, Task};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const ALL_RUNTIMES_URL: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// Versionen vor 1.7 haben kein `javaVersion` im JSON – die laufen mit Java 8.
pub const LEGACY_COMPONENT: &str = "jre-legacy";

const MARKER_FILE: &str = ".trs-runtime";

type AllRuntimes = HashMap<String, HashMap<String, Vec<RuntimeEntry>>>;

#[derive(Debug, Deserialize)]
struct RuntimeEntry {
    manifest: RemoteFile,
    version: RuntimeVersion,
}

#[derive(Debug, Deserialize)]
struct RuntimeVersion {
    name: String,
}

#[derive(Debug, Deserialize)]
struct RemoteFile {
    sha1: String,
    size: u64,
    url: String,
}

#[derive(Debug, Deserialize)]
struct RuntimeManifest {
    files: HashMap<String, RuntimeFile>,
}

#[derive(Debug, Deserialize)]
struct RuntimeFile {
    #[serde(rename = "type")]
    kind: String,
    downloads: Option<RuntimeDownloads>,
}

#[derive(Debug, Deserialize)]
struct RuntimeDownloads {
    raw: RemoteFile,
}

fn platform_key() -> &'static str {
    match std::env::consts::ARCH {
        "aarch64" => "windows-arm64",
        "x86" => "windows-x86",
        _ => "windows-x64",
    }
}

fn is_safe_component(component: &str) -> bool {
    !component.is_empty()
        && component.len() <= 64
        && component.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
}

fn is_safe_rel_path(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path.split('/').all(|seg| !seg.is_empty() && seg != "." && seg != "..")
}

/// Stellt sicher, dass die Runtime `component` installiert ist, und liefert
/// den Pfad zu `javaw.exe`.
pub async fn ensure_runtime(
    http: &reqwest::Client,
    paths: &Paths,
    component: &str,
    concurrency: usize,
    on_progress: &(dyn Fn(Progress) + Sync),
) -> Result<PathBuf> {
    if !is_safe_component(component) {
        return Err(Error::launch(crate::msg!("java.unknownRuntime", "Unbekannte Java-Runtime angefordert.")));
    }
    let dir = paths.java_dir().join(component);
    let javaw = dir.join("bin").join("javaw.exe");
    let marker = dir.join(MARKER_FILE);
    let installed = tokio::fs::read_to_string(&marker).await.ok();

    let entry = match find_runtime(http, component).await {
        Ok(entry) => entry,
        // Offline, aber schon installiert: dann eben ohne Update-Prüfung.
        Err(e) if installed.is_some() && javaw.is_file() => {
            tracing::warn!("Runtime-Manifest nicht erreichbar, verwende installierte Runtime: {e}");
            return Ok(javaw);
        }
        Err(e) => return Err(e),
    };

    if installed.as_deref() == Some(entry.version.name.as_str()) && javaw.is_file() {
        on_progress(Progress::default());
        return Ok(javaw);
    }

    tracing::info!("Installiere Java-Runtime {component} ({})", entry.version.name);
    let manifest_file = paths.meta_dir().join(format!("java-{component}.json"));
    download::fetch_one(
        http,
        &Task {
            url: entry.manifest.url.clone(),
            path: manifest_file.clone(),
            sha1: Some(entry.manifest.sha1.clone()),
            size: Some(entry.manifest.size),
        },
    )
    .await?;
    let manifest: RuntimeManifest = fsutil::read_json(&manifest_file)
        .await?
        .ok_or_else(|| Error::launch(crate::msg!("java.manifestMissing", "Java-Runtime-Manifest fehlt.")))?;

    let mut tasks = Vec::new();
    for (rel, file) in &manifest.files {
        if !is_safe_rel_path(rel) {
            return Err(Error::launch(crate::msg!("java.manifestInvalidPath", "Java-Runtime-Manifest enthält einen ungültigen Pfad.")));
        }
        let target = dir.join(rel);
        match (file.kind.as_str(), &file.downloads) {
            ("directory", _) => fsutil::ensure_dir(&target).await?,
            ("file", Some(d)) => tasks.push(Task {
                url: d.raw.url.clone(),
                path: target,
                sha1: Some(d.raw.sha1.clone()),
                size: Some(d.raw.size),
            }),
            // Symlinks gibt es nur in den Linux-/macOS-Runtimes.
            _ => {}
        }
    }

    // Marker erst nach vollständigem Download schreiben – bricht die
    // Installation ab, wird beim nächsten Start einfach fortgesetzt.
    let _ = tokio::fs::remove_file(&marker).await;
    download::fetch_all(http, tasks, concurrency, on_progress).await?;
    fsutil::write_atomic(&marker, entry.version.name.as_bytes()).await?;

    if !javaw.is_file() {
        return Err(Error::launch(crate::msg!("java.javawMissing", "Die Java-Runtime wurde installiert, enthält aber kein javaw.exe.")));
    }
    Ok(javaw)
}

async fn find_runtime(http: &reqwest::Client, component: &str) -> Result<RuntimeEntry> {
    let mut all: AllRuntimes = http
        .get(ALL_RUNTIMES_URL)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    all.remove(platform_key())
        .and_then(|mut p| p.remove(component))
        .and_then(|entries| entries.into_iter().next())
        .ok_or_else(|| {
            Error::launch(crate::msg!(
                "java.noRuntimeForPlatform",
                "Für diese Plattform gibt es keine passende Java-Runtime ({component}). \
                 Bitte in den Einstellungen einen Java-Pfad angeben.",
                component = &component
            ))
        })
}

// --- Installierte Java-Versionen erkennen ------------------------------------------

/// Eine gefundene Java-Installation.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaInstall {
    /// Pfad zu `javaw.exe`.
    pub path: PathBuf,
    pub major: u32,
    /// Volle Version laut `release`-Datei, z. B. `21.0.7`.
    pub version: String,
    /// Vom Launcher selbst installiert (Mojang-Runtime).
    pub managed: bool,
}

/// Mojang-Runtime je Java-Hauptversion (für „Empfohlene installieren“).
pub fn component_for(major: u32) -> Option<&'static str> {
    match major {
        8 => Some(LEGACY_COMPONENT),
        17 => Some("java-runtime-gamma"),
        21 => Some("java-runtime-delta"),
        25 => Some("java-runtime-epsilon"),
        _ => None,
    }
}

/// `JAVA_VERSION="1.8.0_392"` → 8, `JAVA_VERSION="21.0.2"` → 21.
fn parse_release(text: &str) -> Option<(u32, String)> {
    let line = text.lines().find_map(|l| l.trim().strip_prefix("JAVA_VERSION="))?;
    let version = line.trim().trim_matches('"').to_owned();
    if version.is_empty() || version.len() > 40 || !version.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | '+')) {
        return None;
    }
    let mut parts = version.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    let major = if first == 1 { parts.next()?.parse().ok()? } else { first };
    Some((major, version))
}

/// Liest Hauptversion und Version aus der `release`-Datei neben `bin/` –
/// ohne Java auszuführen.
pub fn inspect(java_exe: &Path) -> Option<(u32, String)> {
    let home = java_exe.parent()?.parent()?;
    let text = std::fs::read_to_string(home.join("release")).ok()?;
    parse_release(&text)
}

fn scan_homes(homes: impl IntoIterator<Item = (PathBuf, bool)>) -> Vec<JavaInstall> {
    let mut out: Vec<JavaInstall> = Vec::new();
    for (home, managed) in homes {
        let exe = home.join("bin").join("javaw.exe");
        if !exe.is_file() {
            continue;
        }
        let Some((major, version)) = inspect(&exe) else { continue };
        let key = std::fs::canonicalize(&exe).unwrap_or_else(|_| exe.clone());
        if out.iter().any(|j| std::fs::canonicalize(&j.path).unwrap_or_else(|_| j.path.clone()) == key) {
            continue;
        }
        out.push(JavaInstall { path: exe, major, version, managed });
    }
    out.sort_by(|a, b| b.major.cmp(&a.major).then_with(|| a.path.cmp(&b.path)));
    out
}

fn child_dirs(dir: &Path) -> Vec<PathBuf> {
    std::fs::read_dir(dir)
        .map(|entries| entries.flatten().map(|e| e.path()).filter(|p| p.is_dir()).take(200).collect())
        .unwrap_or_default()
}

/// Sucht Java in den üblichen Ordnern: Launcher-Runtimes, `JAVA_HOME`,
/// Program Files (Oracle, Adoptium, Microsoft, Zulu, …) und `~/.jdks`.
pub fn detect(paths: &Paths) -> Vec<JavaInstall> {
    let mut homes: Vec<(PathBuf, bool)> = child_dirs(&paths.java_dir()).into_iter().map(|d| (d, true)).collect();
    if let Some(home) = std::env::var_os("JAVA_HOME").filter(|v| !v.is_empty()) {
        homes.push((PathBuf::from(home), false));
    }
    const VENDORS: [&str; 10] = [
        "Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft", "Amazon Corretto", "Semeru",
        "Eclipse Foundation", "AdoptOpenJDK", "OpenJDK",
    ];
    let mut bases: Vec<PathBuf> = ["ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"]
        .iter()
        .filter_map(|v| std::env::var_os(v).map(PathBuf::from))
        .collect();
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        bases.push(PathBuf::from(local).join("Programs"));
    }
    for base in bases {
        for vendor in VENDORS {
            homes.extend(child_dirs(&base.join(vendor)).into_iter().map(|d| (d, false)));
        }
    }
    if let Some(profile) = std::env::var_os("USERPROFILE") {
        homes.extend(child_dirs(&PathBuf::from(profile).join(".jdks")).into_iter().map(|d| (d, false)));
    }
    scan_homes(homes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn release_file_versions() {
        assert_eq!(parse_release("IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"1.8.0_392\"\n"), Some((8, "1.8.0_392".into())));
        assert_eq!(parse_release("JAVA_VERSION=\"21.0.2\""), Some((21, "21.0.2".into())));
        assert_eq!(parse_release("JAVA_VERSION=\"17\""), Some((17, "17".into())));
        assert_eq!(parse_release("JAVA_VERSION=\"25-ea\""), Some((25, "25-ea".into())));
        assert_eq!(parse_release("JAVA_VERSION=\"$(evil)\""), None);
        assert_eq!(parse_release("nichts"), None);
        assert_eq!(component_for(21), Some("java-runtime-delta"));
        assert_eq!(component_for(11), None);
    }

    #[test]
    fn scans_homes_with_release_file() {
        let dir = tempfile::tempdir().unwrap();
        let make = |name: &str, release: Option<&str>| {
            let home = dir.path().join(name);
            std::fs::create_dir_all(home.join("bin")).unwrap();
            std::fs::write(home.join("bin/javaw.exe"), b"MZ").unwrap();
            if let Some(r) = release {
                std::fs::write(home.join("release"), r).unwrap();
            }
            home
        };
        let j21 = make("jdk-21", Some("JAVA_VERSION=\"21.0.7\""));
        let j8 = make("jre8", Some("JAVA_VERSION=\"1.8.0_451\""));
        let broken = make("kaputt", None);
        let found = scan_homes([(j8.clone(), false), (j21.clone(), true), (broken, false), (j21.clone(), false)]);
        assert_eq!(found.len(), 2, "doppelt und ohne release-Datei fallen raus");
        assert_eq!((found[0].major, found[0].managed), (21, true));
        assert_eq!(found[1].major, 8);
        assert_eq!(inspect(&j8.join("bin/javaw.exe")).unwrap().0, 8);
    }

    #[test]
    fn path_safety() {
        assert!(is_safe_rel_path("bin/javaw.exe"));
        for bad in ["", "/abs", "../x", "a/../b", "a//b", "c:/x", "a\\b"] {
            assert!(!is_safe_rel_path(bad), "{bad:?}");
        }
        assert!(is_safe_component("java-runtime-delta"));
        assert!(!is_safe_component("../evil"));
    }

    #[test]
    fn parses_runtime_manifest() {
        let m: RuntimeManifest = serde_json::from_str(
            r#"{"files":{
                "bin":{"type":"directory"},
                "bin/javaw.exe":{"type":"file","executable":true,"downloads":{
                    "lzma":{"sha1":"a","size":1,"url":"https://x/l"},
                    "raw":{"sha1":"b","size":2,"url":"https://x/r"}}},
                "legal/x":{"type":"link","target":"../y"}}}"#,
        )
        .unwrap();
        assert_eq!(m.files.len(), 3);
        assert_eq!(m.files["bin/javaw.exe"].downloads.as_ref().unwrap().raw.size, 2);
    }
}
