//! Parallele Downloads mit SHA1-Prüfung, Wiederholungen und Fortschritt.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use futures::StreamExt;
use sha1::{Digest, Sha1};
use tokio::io::AsyncWriteExt;

use crate::{Error, Result, fsutil};

const MAX_ATTEMPTS: u32 = 4;
const PROGRESS_INTERVAL_MS: u64 = 80;

#[derive(Debug, Clone)]
pub struct Task {
    pub url: String,
    pub path: PathBuf,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Progress {
    pub done_bytes: u64,
    pub total_bytes: u64,
    pub done_files: u64,
    pub total_files: u64,
}

impl Progress {
    /// 0–100. Nach Bytes, wenn die Größen bekannt sind, sonst nach Dateien.
    pub fn percent(&self) -> f64 {
        let (done, total) = if self.total_bytes > 0 {
            (self.done_bytes, self.total_bytes)
        } else {
            (self.done_files, self.total_files)
        };
        if total == 0 { 100.0 } else { (done as f64 / total as f64 * 100.0).min(100.0) }
    }
}

/// Ist die Datei schon da? Ohne `verify_hash` reicht Existenz + passende Größe
/// (schnell genug, um es bei jedem Start für tausende Assets zu prüfen).
pub async fn is_valid(task: &Task, verify_hash: bool) -> bool {
    let Ok(meta) = tokio::fs::metadata(&task.path).await else { return false };
    if !meta.is_file() || task.size.is_some_and(|s| s != meta.len()) {
        return false;
    }
    match (&task.sha1, verify_hash) {
        (Some(expected), true) => {
            sha1_of_file(&task.path).await.is_ok_and(|h| h.eq_ignore_ascii_case(expected))
        }
        _ => true,
    }
}

pub async fn sha1_of_file(path: &Path) -> Result<String> {
    let path = path.to_owned();
    tokio::task::spawn_blocking(move || {
        let mut file = std::fs::File::open(&path).map_err(|e| Error::io(&path, e))?;
        let mut hasher = Sha1::new();
        let mut buf = vec![0u8; 64 * 1024];
        loop {
            let n = std::io::Read::read(&mut file, &mut buf).map_err(|e| Error::io(&path, e))?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
        Ok(hex(&hasher.finalize()))
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))?
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Nur HTTPS. Alte Metadaten enthalten teils `http://` – die Hosts können
/// alle TLS, also heben wir an statt Klartext zu sprechen.
fn secure_url(url: &str) -> Result<String> {
    if let Some(rest) = url.strip_prefix("http://") {
        Ok(format!("https://{rest}"))
    } else if url.starts_with("https://") {
        Ok(url.to_owned())
    } else {
        Err(Error::download(url, "nicht unterstütztes URL-Schema"))
    }
}

pub async fn fetch_one(http: &reqwest::Client, task: &Task) -> Result<()> {
    fetch_with_retries(http, task, &|_| {}).await
}

async fn fetch_with_retries(
    http: &reqwest::Client,
    task: &Task,
    on_bytes: &(dyn Fn(i64) + Sync),
) -> Result<()> {
    let mut last_err = None;
    for attempt in 1..=MAX_ATTEMPTS {
        let counted = AtomicU64::new(0);
        let track = |n: u64| {
            counted.fetch_add(n, Ordering::Relaxed);
            on_bytes(n as i64);
        };
        match fetch_attempt(http, task, &track).await {
            Ok(()) => return Ok(()),
            Err(e) => {
                // Bereits gezählte Bytes des Fehlversuchs zurücknehmen.
                on_bytes(-(counted.load(Ordering::Relaxed) as i64));
                tracing::warn!("Download-Versuch {attempt}/{MAX_ATTEMPTS} fehlgeschlagen: {e}");
                last_err = Some(e);
                if attempt < MAX_ATTEMPTS {
                    tokio::time::sleep(Duration::from_millis(400 * u64::from(attempt))).await;
                }
            }
        }
    }
    Err(last_err.expect("mindestens ein Versuch"))
}

async fn fetch_attempt(
    http: &reqwest::Client,
    task: &Task,
    on_bytes: &(dyn Fn(u64) + Sync),
) -> Result<()> {
    let url = secure_url(&task.url)?;
    if let Some(parent) = task.path.parent() {
        fsutil::ensure_dir(parent).await?;
    }

    let response = http
        .get(&url)
        // Das globale Timeout wäre für große Dateien zu knapp; der Stream
        // unten hat stattdessen ein Timeout pro Chunk.
        .timeout(Duration::from_secs(60 * 30))
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(|e| Error::download(&url, e.without_url().to_string()))?;

    let tmp = task.path.with_extension(format!("part-{}", uuid::Uuid::new_v4().simple()));
    let result = async {
        let mut file = tokio::fs::File::create(&tmp).await.map_err(|e| Error::io(&tmp, e))?;
        let mut hasher = Sha1::new();
        let mut written = 0u64;
        let mut stream = response.bytes_stream();

        loop {
            let chunk = tokio::time::timeout(Duration::from_secs(30), stream.next())
                .await
                .map_err(|_| Error::download(&url, "Zeitüberschreitung"))?;
            let Some(chunk) = chunk else { break };
            let chunk = chunk.map_err(|e| Error::download(&url, e.without_url().to_string()))?;
            hasher.update(&chunk);
            file.write_all(&chunk).await.map_err(|e| Error::io(&tmp, e))?;
            written += chunk.len() as u64;
            on_bytes(chunk.len() as u64);
        }
        file.flush().await.map_err(|e| Error::io(&tmp, e))?;
        drop(file);

        if task.size.is_some_and(|s| s != written) {
            return Err(Error::download(&url, "unerwartete Dateigröße"));
        }
        if let Some(expected) = &task.sha1
            && !hex(&hasher.finalize()).eq_ignore_ascii_case(expected)
        {
            return Err(Error::download(&url, "Prüfsumme stimmt nicht"));
        }
        tokio::fs::rename(&tmp, &task.path).await.map_err(|e| Error::io(&task.path, e))
    }
    .await;

    if result.is_err() {
        let _ = tokio::fs::remove_file(&tmp).await;
    }
    result
}

/// Lädt alle fehlenden Dateien. `on_progress` wird gedrosselt aufgerufen und
/// garantiert einmal am Ende mit dem Endstand.
pub async fn fetch_all(
    http: &reqwest::Client,
    tasks: Vec<Task>,
    concurrency: usize,
    on_progress: &(dyn Fn(Progress) + Sync),
) -> Result<()> {
    // Dieselbe Datei taucht oft mehrfach auf (Assets mit gleichem Hash).
    let mut seen = HashSet::new();
    let unique: Vec<Task> = tasks.into_iter().filter(|t| seen.insert(t.path.clone())).collect();

    // Benannte async-Funktionen statt Closures: Closures, die hier Referenzen
    // einfangen, machen das Future für den Compiler nicht mehr `Send`-beweisbar.
    let checked: Vec<Option<Task>> =
        futures::stream::iter(unique.into_iter().map(keep_if_missing)).buffer_unordered(64).collect().await;
    let missing: Vec<Task> = checked.into_iter().flatten().collect();

    let total_files = missing.len() as u64;
    let total_bytes =
        if missing.iter().all(|t| t.size.is_some()) { missing.iter().filter_map(|t| t.size).sum() } else { 0 };
    let done_bytes = AtomicU64::new(0);
    let done_files = AtomicU64::new(0);
    let started = Instant::now();
    let last_emit = AtomicU64::new(0);

    let snapshot = || Progress {
        done_bytes: done_bytes.load(Ordering::Relaxed).min(total_bytes),
        total_bytes,
        done_files: done_files.load(Ordering::Relaxed),
        total_files,
    };
    let emit_throttled = || {
        let now = started.elapsed().as_millis() as u64;
        let last = last_emit.load(Ordering::Relaxed);
        if now.saturating_sub(last) >= PROGRESS_INTERVAL_MS
            && last_emit.compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed).is_ok()
        {
            on_progress(snapshot());
        }
    };

    on_progress(snapshot());

    let on_bytes = |delta: i64| {
        if delta >= 0 {
            done_bytes.fetch_add(delta as u64, Ordering::Relaxed);
        } else {
            done_bytes.fetch_sub(delta.unsigned_abs(), Ordering::Relaxed);
        }
        emit_throttled();
    };

    let on_bytes: &(dyn Fn(i64) + Sync) = &on_bytes;
    let emit: &(dyn Fn() + Sync) = &emit_throttled;
    let jobs: Vec<_> = missing.iter().map(|task| fetch_counted(http, task, on_bytes, &done_files, emit)).collect();
    let mut results = futures::stream::iter(jobs).buffer_unordered(concurrency.max(1));

    // Beim ersten endgültigen Fehler abbrechen; laufende Downloads enden mit dem Drop.
    while let Some(result) = results.next().await {
        result?;
    }
    drop(results);

    on_progress(snapshot());
    Ok(())
}

async fn keep_if_missing(task: Task) -> Option<Task> {
    if is_valid(&task, false).await { None } else { Some(task) }
}

async fn fetch_counted(
    http: &reqwest::Client,
    task: &Task,
    on_bytes: &(dyn Fn(i64) + Sync),
    done_files: &AtomicU64,
    emit: &(dyn Fn() + Sync),
) -> Result<()> {
    fetch_with_retries(http, task, on_bytes).await?;
    done_files.fetch_add(1, Ordering::Relaxed);
    emit();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn percent() {
        assert_eq!(Progress::default().percent(), 100.0);
        let p = Progress { done_bytes: 25, total_bytes: 100, done_files: 9, total_files: 10 };
        assert_eq!(p.percent(), 25.0);
        let p = Progress { done_files: 1, total_files: 4, ..Default::default() };
        assert_eq!(p.percent(), 25.0);
    }

    #[test]
    fn url_policy() {
        assert_eq!(secure_url("http://files.minecraftforge.net/x").unwrap(), "https://files.minecraftforge.net/x");
        assert!(secure_url("file:///c:/windows/x").is_err());
        assert!(secure_url("ftp://x/y").is_err());
    }

    #[tokio::test]
    async fn validity_checks_size_and_hash() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("a.txt");
        tokio::fs::write(&path, b"hello").await.unwrap();
        let sha = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";

        let ok = Task { url: String::new(), path: path.clone(), sha1: Some(sha.into()), size: Some(5) };
        assert!(is_valid(&ok, true).await);

        let wrong_size = Task { size: Some(6), ..ok.clone() };
        assert!(!is_valid(&wrong_size, false).await);

        let wrong_hash = Task { sha1: Some("00".repeat(20)), ..ok.clone() };
        assert!(is_valid(&wrong_hash, false).await);
        assert!(!is_valid(&wrong_hash, true).await);

        let missing = Task { path: dir.path().join("nope"), ..ok };
        assert!(!is_valid(&missing, false).await);
    }
}
