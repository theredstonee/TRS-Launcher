//! Java-Runtimes aus Mojangs eigenem Runtime-Manifest – dieselben Builds,
//! die auch der offizielle Launcher verwendet.

use std::collections::HashMap;
use std::path::PathBuf;

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
        return Err(Error::launch("Unbekannte Java-Runtime angefordert."));
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
        .ok_or_else(|| Error::launch("Java-Runtime-Manifest fehlt."))?;

    let mut tasks = Vec::new();
    for (rel, file) in &manifest.files {
        if !is_safe_rel_path(rel) {
            return Err(Error::launch("Java-Runtime-Manifest enthält einen ungültigen Pfad."));
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
        return Err(Error::launch("Die Java-Runtime wurde installiert, enthält aber kein javaw.exe."));
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
            Error::launch(format!(
                "Für diese Plattform gibt es keine passende Java-Runtime ({component}). \
                 Bitte in den Einstellungen einen Java-Pfad angeben."
            ))
        })
}

#[cfg(test)]
mod tests {
    use super::*;

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
