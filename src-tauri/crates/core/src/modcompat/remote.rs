//! Mod-Infos aus Jars auf Modrinths CDN, ohne das ganze Jar zu laden: Das
//! Inhaltsverzeichnis eines Zip-Archivs liegt am Ende – mit HTTP-Range werden
//! nur Ende, Verzeichnis und die paar kleinen Metadaten-Dateien geholt
//! (meist 2–3 kleine Anfragen). Kann der Server keine Teil-Downloads, wird
//! das Jar ganz geladen (mit SHA1-Prüfung). Ergebnisse landen je SHA1 im
//! Cache – dieselbe Version wird nie zweimal gelesen.

use std::collections::HashMap;
use std::future::Future;
use std::io::Read;
use std::path::{Path, PathBuf};

use sha1::{Digest, Sha1};

use super::meta::{self, ModInfo};
use crate::modrinth::{CDN_PREFIX, VersionFile};
use crate::{Error, Result, fsutil};

/// So viel vom Ende wird zuerst geholt (Ende-Eintrag + meist das ganze Verzeichnis).
const TAIL_BYTES: u64 = 64 * 1024;
/// Größeres Inhaltsverzeichnis → lieber ganz laden.
const MAX_DIRECTORY_BYTES: u64 = 4 * 1024 * 1024;
/// Größere Jars werden für die Prüfung nicht ganz geladen.
const MAX_FULL_DOWNLOAD: u64 = 64 * 1024 * 1024;
/// Platz für das „extra“-Feld im lokalen Kopf (unbekannt, bis er da ist).
const LOCAL_EXTRA_GUESS: u64 = 256;

const EOCD_SIG: u32 = 0x0605_4b50;
const CENTRAL_SIG: u32 = 0x0201_4b50;
const LOCAL_SIG: u32 = 0x0403_4b50;

fn u16_at(b: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_le_bytes(b.get(at..at + 2)?.try_into().ok()?))
}

fn u32_at(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_le_bytes(b.get(at..at + 4)?.try_into().ok()?))
}

/// Ein Eintrag des Zip-Inhaltsverzeichnisses.
#[derive(Debug, Clone, Copy)]
struct Entry {
    method: u16,
    compressed: u64,
    uncompressed: u64,
    offset: u64,
}

/// Sucht den Ende-Eintrag: (Anzahl, Größe, Beginn des Verzeichnisses).
fn find_eocd(tail: &[u8]) -> Option<(u64, u64)> {
    if tail.len() < 22 {
        return None;
    }
    let pos = (0..=tail.len() - 22).rev().find(|&i| u32_at(tail, i) == Some(EOCD_SIG))?;
    let size = u32_at(tail, pos + 12)?;
    let offset = u32_at(tail, pos + 16)?;
    // Zip64 (riesige Archive): Das kann der einfache Weg nicht.
    if size == u32::MAX || offset == u32::MAX {
        return None;
    }
    Some((u64::from(size), u64::from(offset)))
}

fn parse_directory(dir: &[u8]) -> HashMap<String, Entry> {
    let mut out = HashMap::new();
    let mut at = 0;
    while u32_at(dir, at) == Some(CENTRAL_SIG) {
        let (Some(method), Some(compressed), Some(uncompressed), Some(name_len), Some(extra_len), Some(comment_len), Some(offset)) = (
            u16_at(dir, at + 10),
            u32_at(dir, at + 20),
            u32_at(dir, at + 24),
            u16_at(dir, at + 28),
            u16_at(dir, at + 30),
            u16_at(dir, at + 32),
            u32_at(dir, at + 42),
        ) else {
            break;
        };
        let name_start = at + 46;
        let Some(name) = dir.get(name_start..name_start + usize::from(name_len)) else { break };
        if let Ok(name) = std::str::from_utf8(name)
            && meta::WANTED.contains(&name)
        {
            out.insert(
                name.to_owned(),
                Entry {
                    method,
                    compressed: u64::from(compressed),
                    uncompressed: u64::from(uncompressed),
                    offset: u64::from(offset),
                },
            );
        }
        at = name_start + usize::from(name_len) + usize::from(extra_len) + usize::from(comment_len);
    }
    out
}

fn inflate(method: u16, data: &[u8], uncompressed: u64) -> Option<String> {
    if uncompressed > meta::MAX_ENTRY_BYTES {
        return None;
    }
    let bytes = match method {
        0 => data.to_vec(),
        8 => {
            let mut out = Vec::new();
            flate2::read::DeflateDecoder::new(data).take(meta::MAX_ENTRY_BYTES).read_to_end(&mut out).ok()?;
            out
        }
        _ => return None,
    };
    Some(String::from_utf8_lossy(&bytes).into_owned())
}

/// Holt einen Eintrag über seinen lokalen Kopf.
async fn fetch_entry<F, Fut>(size: u64, entry: Entry, fetch: &F) -> Result<Option<String>>
where
    F: Fn(u64, u64) -> Fut,
    Fut: Future<Output = Result<Vec<u8>>>,
{
    if entry.compressed > meta::MAX_ENTRY_BYTES {
        return Ok(None);
    }
    let guess = (entry.offset + 30 + 512 + LOCAL_EXTRA_GUESS + entry.compressed).min(size);
    let mut head = fetch(entry.offset, guess).await?;
    if u32_at(&head, 0) != Some(LOCAL_SIG) {
        return Ok(None);
    }
    let (Some(name_len), Some(extra_len)) = (u16_at(&head, 26), u16_at(&head, 28)) else { return Ok(None) };
    let start = 30 + u64::from(name_len) + u64::from(extra_len);
    let end = start + entry.compressed;
    if (head.len() as u64) < end {
        if entry.offset + end > size {
            return Ok(None);
        }
        head = fetch(entry.offset, entry.offset + end).await?;
    }
    let Some(data) = head.get(start as usize..end as usize) else { return Ok(None) };
    Ok(inflate(entry.method, data, entry.uncompressed))
}

/// Liest die Metadaten-Dateien über Teil-Downloads. `fetch(von, bis)` liefert
/// die Bytes `[von, bis)`. `Ok(None)` = so nicht lesbar (dann ganz laden).
pub(crate) async fn read_by_ranges<F, Fut>(size: u64, fetch: F) -> Result<Option<Vec<ModInfo>>>
where
    F: Fn(u64, u64) -> Fut,
    Fut: Future<Output = Result<Vec<u8>>>,
{
    if size < 22 {
        return Ok(None);
    }
    let tail_start = size.saturating_sub(TAIL_BYTES);
    let tail = fetch(tail_start, size).await?;
    let Some((dir_size, dir_offset)) = find_eocd(&tail) else { return Ok(None) };
    if dir_size > MAX_DIRECTORY_BYTES || dir_offset + dir_size > size {
        return Ok(None);
    }
    let directory = if dir_offset >= tail_start {
        let from = (dir_offset - tail_start) as usize;
        match tail.get(from..from + dir_size as usize) {
            Some(d) => d.to_vec(),
            None => return Ok(None),
        }
    } else {
        fetch(dir_offset, dir_offset + dir_size).await?
    };
    let entries = parse_directory(&directory);
    // Nur holen, was gebraucht wird: Fabric vor Quilt vor (Neo)Forge (+ Manifest).
    let needed: Vec<&str> = if entries.contains_key(meta::FABRIC) {
        vec![meta::FABRIC]
    } else if entries.contains_key(meta::QUILT) {
        vec![meta::QUILT]
    } else {
        vec![meta::NEOFORGE_TOML, meta::FORGE_TOML, meta::MANIFEST]
    };
    let mut texts: HashMap<&str, String> = HashMap::new();
    for name in needed {
        if let Some(&entry) = entries.get(name)
            && let Some(text) = fetch_entry(size, entry, &fetch).await?
        {
            texts.insert(name, text);
        }
    }
    Ok(Some(meta::from_entries(|name| texts.get(name).cloned())))
}

fn cache_file(cache_dir: &Path, sha1: &str) -> Option<PathBuf> {
    (sha1.len() == 40 && sha1.bytes().all(|b| b.is_ascii_hexdigit()))
        .then(|| cache_dir.join(format!("{}.json", sha1.to_ascii_lowercase())))
}

async fn fetch_range(http: &reqwest::Client, url: &str, start: u64, end: u64) -> Result<Vec<u8>> {
    if end <= start {
        return Ok(Vec::new());
    }
    let response = http.get(url).header(reqwest::header::RANGE, format!("bytes={start}-{}", end - 1)).send().await?;
    if response.status() != reqwest::StatusCode::PARTIAL_CONTENT {
        return Err(Error::download(url, "Server liefert keine Teil-Downloads"));
    }
    let bytes = response.bytes().await?;
    if bytes.len() as u64 > end - start {
        return Err(Error::download(url, "unerwartete Antwortgröße"));
    }
    Ok(bytes.to_vec())
}

async fn read_whole(http: &reqwest::Client, file: &VersionFile) -> Result<Vec<ModInfo>> {
    if file.size > MAX_FULL_DOWNLOAD {
        return Ok(Vec::new());
    }
    let bytes = http.get(&file.url).send().await?.error_for_status()?.bytes().await?;
    if bytes.len() as u64 > MAX_FULL_DOWNLOAD {
        return Ok(Vec::new());
    }
    let hash: String = Sha1::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect();
    if !hash.eq_ignore_ascii_case(&file.hashes.sha1) {
        return Err(Error::download(&file.url, "Prüfsumme stimmt nicht"));
    }
    let bytes = bytes.to_vec();
    tokio::task::spawn_blocking(move || meta::read_jar(std::io::Cursor::new(bytes)))
        .await
        .map_err(|e| Error::Internal(e.to_string()))
}

/// Mod-Infos einer Modrinth-Datei (aus dem Cache oder über das Netz).
/// Fehler → leer (dann wird eben nicht geprüft).
pub(crate) async fn mod_info(http: &reqwest::Client, cache_dir: &Path, file: &VersionFile) -> Vec<ModInfo> {
    let Some(cache) = cache_file(cache_dir, &file.hashes.sha1) else { return Vec::new() };
    if let Ok(Some(infos)) = fsutil::read_json::<Vec<ModInfo>>(&cache).await {
        return infos;
    }
    if !file.url.starts_with(CDN_PREFIX) {
        return Vec::new();
    }
    let by_ranges = read_by_ranges(file.size, |start, end| fetch_range(http, &file.url, start, end)).await;
    let result = match by_ranges {
        Ok(Some(infos)) => Ok(infos),
        Ok(None) | Err(_) => read_whole(http, file).await,
    };
    match result {
        Ok(infos) => {
            if let Err(e) = fsutil::write_json(&cache, &infos).await {
                tracing::debug!("Mod-Infos nicht zwischengespeichert: {e}");
            }
            infos
        }
        Err(e) => {
            tracing::warn!("Mod-Infos von {} nicht lesbar: {e}", file.filename);
            Vec::new()
        }
    }
}

/// Mod-Infos einer Datei in der Instanz.
pub(crate) async fn local_mod_info(path: PathBuf) -> Vec<ModInfo> {
    tokio::task::spawn_blocking(move || std::fs::File::open(&path).map(meta::read_jar).unwrap_or_default())
        .await
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::*;
    use crate::modcompat::meta::tests::jar;

    /// Ein großes Jar (viele Einträge) – das Verzeichnis passt nicht ins erste Ende-Stück.
    fn big_jar(fabric: &str) -> Vec<u8> {
        let filler: Vec<(String, String)> = (0..1500).map(|i| (format!("net/example/some/pkg/Class{i:05}.class"), "x".repeat(40))).collect();
        let mut entries: Vec<(&str, &str)> = vec![(meta::FABRIC, fabric)];
        entries.extend(filler.iter().map(|(n, t)| (n.as_str(), t.as_str())));
        jar(&entries)
    }

    #[tokio::test]
    async fn reads_metadata_with_few_small_ranges() {
        let bytes = big_jar(r#"{"id":"sodium","version":"0.8.14+mc1.21.11","breaks":{"iris":"<=1.10.7"}}"#);
        let size = bytes.len() as u64;
        let calls = Mutex::new(Vec::new());
        let fetch = |start: u64, end: u64| {
            calls.lock().unwrap().push((start, end));
            let part = bytes[start as usize..end as usize].to_vec();
            async move { Ok(part) }
        };
        let infos = read_by_ranges(size, fetch).await.unwrap().unwrap();
        assert_eq!(infos[0].id, "sodium");
        assert_eq!(infos[0].breaks[0].any_of, ["<=1.10.7"]);
        let calls = calls.into_inner().unwrap();
        // Ende + Verzeichnis + Eintrag – kein Komplett-Download.
        assert!(calls.len() <= 4, "{calls:?}");
        let fetched: u64 = calls.iter().map(|(s, e)| e - s).sum();
        assert!(fetched < size, "{fetched} von {size}");
    }

    #[tokio::test]
    async fn small_jar_and_garbage() {
        let bytes = jar(&[(meta::QUILT, r#"{"quilt_loader":{"id":"q","version":"1.0.0"}}"#)]);
        let size = bytes.len() as u64;
        let fetch = |s: u64, e: u64| {
            let part = bytes[s as usize..e as usize].to_vec();
            async move { Ok(part) }
        };
        assert_eq!(read_by_ranges(size, fetch).await.unwrap().unwrap()[0].id, "q");

        let junk = vec![7u8; 5000];
        let fetch = |s: u64, e: u64| {
            let part = junk[s as usize..e as usize].to_vec();
            async move { Ok(part) }
        };
        assert!(read_by_ranges(5000, fetch).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn cached_metadata_is_used_without_network() {
        let dir = tempfile::tempdir().unwrap();
        let sha1 = "a".repeat(40);
        let info = meta::parse_fabric(r#"{"id":"x","version":"1.0.0"}"#).unwrap();
        fsutil::write_json(&dir.path().join(format!("{sha1}.json")), &vec![info.clone()]).await.unwrap();
        let file: VersionFile = serde_json::from_value(serde_json::json!({
            "url": "https://example.invalid/x.jar", "filename": "x.jar", "size": 1, "hashes": {"sha1": sha1}
        }))
        .unwrap();
        let http = reqwest::Client::new();
        assert_eq!(mod_info(&http, dir.path(), &file).await, vec![info]);
        // Fremde Adresse ohne Cache: gar nicht erst laden.
        let other: VersionFile = serde_json::from_value(serde_json::json!({
            "url": "https://example.invalid/y.jar", "filename": "y.jar", "size": 1, "hashes": {"sha1": "b".repeat(40)}
        }))
        .unwrap();
        assert!(mod_info(&http, dir.path(), &other).await.is_empty());
    }
}
