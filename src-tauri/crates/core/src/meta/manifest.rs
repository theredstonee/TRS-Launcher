use std::time::{Duration, SystemTime};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::paths::Paths;
use crate::{Error, Result, fsutil};

pub const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

const CACHE_FILE: &str = "version_manifest_v2.json";
const CACHE_TTL: Duration = Duration::from_secs(60 * 60);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VersionType {
    Release,
    Snapshot,
    OldBeta,
    OldAlpha,
    /// Falls Mojang je einen neuen Typ einführt, soll das Manifest trotzdem laden.
    #[serde(other)]
    Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Latest {
    pub release: String,
    pub snapshot: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestVersion {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: VersionType,
    pub url: String,
    pub release_time: DateTime<Utc>,
    pub sha1: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionManifest {
    pub latest: Latest,
    pub versions: Vec<ManifestVersion>,
}

impl VersionManifest {
    pub fn find(&self, id: &str) -> Option<&ManifestVersion> {
        self.versions.iter().find(|v| v.id == id)
    }
}

/// Liefert das Manifest aus dem Cache, solange er frisch ist. Schlägt der
/// Download fehl (offline), wird ein beliebig alter Cache verwendet.
pub async fn fetch(
    http: &reqwest::Client,
    paths: &Paths,
    force_refresh: bool,
) -> Result<VersionManifest> {
    let cache = paths.meta_dir().join(CACHE_FILE);

    if !force_refresh
        && is_fresh(&cache).await
        && let Ok(Some(manifest)) = fsutil::read_json::<VersionManifest>(&cache).await
    {
        return Ok(manifest);
    }

    match download(http).await {
        Ok((manifest, bytes)) => {
            if let Err(e) = fsutil::write_atomic(&cache, &bytes).await {
                tracing::warn!("Manifest-Cache konnte nicht geschrieben werden: {e}");
            }
            Ok(manifest)
        }
        Err(err) => match fsutil::read_json::<VersionManifest>(&cache).await {
            Ok(Some(manifest)) => {
                tracing::warn!("Manifest-Download fehlgeschlagen, verwende Cache: {err}");
                Ok(manifest)
            }
            _ => Err(err),
        },
    }
}

async fn download(http: &reqwest::Client) -> Result<(VersionManifest, Vec<u8>)> {
    let bytes = http.get(MANIFEST_URL).send().await?.error_for_status()?.bytes().await?;
    let manifest = serde_json::from_slice(&bytes).map_err(|e| Error::json(MANIFEST_URL, e))?;
    Ok((manifest, bytes.to_vec()))
}

async fn is_fresh(path: &std::path::Path) -> bool {
    let Ok(meta) = tokio::fs::metadata(path).await else { return false };
    let Ok(modified) = meta.modified() else { return false };
    SystemTime::now().duration_since(modified).is_ok_and(|age| age < CACHE_TTL)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"{
        "latest": { "release": "26.2", "snapshot": "26.3-rc-2" },
        "versions": [
            { "id": "26.2", "type": "release",
              "url": "https://piston-meta.mojang.com/v1/packages/abc/26.2.json",
              "time": "2026-08-01T10:00:00+00:00", "releaseTime": "2026-07-30T09:00:00+00:00",
              "sha1": "bc42e43dfe43d65a2f6c2c1dbb322c75134e51fe", "complianceLevel": 1 },
            { "id": "b1.7.3", "type": "old_beta", "url": "https://x/b1.7.3.json",
              "time": "2011-07-08T22:00:00+00:00", "releaseTime": "2011-07-07T22:00:00+00:00",
              "sha1": "00", "complianceLevel": 0 },
            { "id": "future", "type": "experimental_thing", "url": "https://x/f.json",
              "time": "2030-01-01T00:00:00+00:00", "releaseTime": "2030-01-01T00:00:00+00:00",
              "sha1": "00", "complianceLevel": 1 }
        ]
    }"#;

    #[test]
    fn parses_manifest() {
        let m: VersionManifest = serde_json::from_str(SAMPLE).unwrap();
        assert_eq!(m.latest.release, "26.2");
        assert_eq!(m.find("26.2").unwrap().kind, VersionType::Release);
        assert_eq!(m.find("b1.7.3").unwrap().kind, VersionType::OldBeta);
        assert_eq!(m.find("future").unwrap().kind, VersionType::Unknown);
        assert!(m.find("nope").is_none());
    }

    #[test]
    fn serializes_for_frontend() {
        let m: VersionManifest = serde_json::from_str(SAMPLE).unwrap();
        let v = serde_json::to_value(&m.versions[1]).unwrap();
        assert_eq!(v["type"], "old_beta");
        assert!(v.get("releaseTime").is_some());
    }

    #[tokio::test]
    async fn uses_fresh_cache_without_network() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        tokio::fs::write(paths.meta_dir().join(CACHE_FILE), SAMPLE).await.unwrap();

        // Unroutbare Proxy-Adresse: jeder Netzwerkzugriff würde fehlschlagen.
        let http = reqwest::Client::builder()
            .proxy(reqwest::Proxy::all("http://127.0.0.1:9").unwrap())
            .build()
            .unwrap();
        let m = fetch(&http, &paths, false).await.unwrap();
        assert_eq!(m.versions.len(), 3);

        // Auch bei erzwungenem Refresh fällt er offline auf den Cache zurück.
        let m = fetch(&http, &paths, true).await.unwrap();
        assert_eq!(m.latest.snapshot, "26.3-rc-2");
    }
}
