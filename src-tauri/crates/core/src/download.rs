//! Parallele Downloads mit SHA1-Prüfung, Wiederholungen und Fortschritt.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use std::sync::OnceLock;

use futures::StreamExt;
use sha1::{Digest, Sha1};
use tokio::io::AsyncWriteExt;

use crate::{Error, Result, fsutil, task};

/// Versuche bei vorübergehenden Fehlern (Verbindungsabbruch, Zeitüberschreitung, 429, 5xx):
/// mit den Wartezeiten aus [`retry_delay`] gut eine Minute Geduld.
const MAX_ATTEMPTS: u32 = 8;
/// Falsche Prüfsumme kann ein abgeschnittener Download sein – ein paar Mal neu laden, dann aufgeben.
const MAX_CHECKSUM_ATTEMPTS: u32 = 3;
const PROGRESS_INTERVAL_MS: u64 = 80;

#[derive(Debug, Clone)]
pub struct Task {
    pub url: String,
    pub path: PathBuf,
    pub sha1: Option<String>,
    pub size: Option<u64>,
    /// SHA-512 aus dem Pack-Index, wenn vorhanden. Wird dann zusätzlich geprüft.
    pub sha512: Option<String>,
    /// Geteiltes Pack: die Größe muss exakt stimmen. Sonst gewinnt die Prüfsumme
    /// (manche fremden Packs liegen um ein Byte daneben).
    pub strict_size: bool,
    /// Modpack-Datei: Weiterleitungen nur auf die erlaubten Download-Hosts.
    pub pack: bool,
}

/// Hosts, von denen ein Modpack Dateien laden darf (https, ohne Umleitung woandershin).
const PACK_HOSTS: [&str; 6] = [
    "cdn.modrinth.com",
    "edge.forgecdn.net",
    "mediafilez.forgecdn.net",
    "github.com",
    "raw.githubusercontent.com",
    "gitlab.com",
];

/// Darf diese Adresse eine Modpack-Datei sein? Nur HTTPS, kein Benutzer, Port 443, Host aus der Liste.
pub(crate) fn allowed_pack_url(url: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(url) else { return false };
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none_or(|p| p == 443)
        && url.host_str().is_some_and(|h| PACK_HOSTS.contains(&h))
}

/// Client für Pack-Dateien: folgt Weiterleitungen nur, wenn das Ziel erlaubt bleibt.
fn pack_http() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(crate::USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::custom(|attempt| {
                if attempt.previous().len() > 5 {
                    attempt.error("redirect-host")
                } else if allowed_pack_url(attempt.url().as_str()) {
                    attempt.follow()
                } else {
                    attempt.error("redirect-host")
                }
            }))
            .build()
            .expect("pack http client")
    })
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
///
/// Die Prüfsumme hat Vorrang vor der Größe: Manche Modpacks geben die Größe falsch an
/// (z. B. um ein Byte), die SHA1 stimmt aber. Passt die Größe nicht, entscheidet dann der Hash.
pub async fn is_valid(task: &Task, verify_hash: bool) -> bool {
    let Ok(meta) = tokio::fs::metadata(&task.path).await else { return false };
    if !meta.is_file() {
        return false;
    }
    if task.strict_size && task.size.is_some_and(|s| s != meta.len()) {
        return false;
    }
    if task.size.is_some_and(|s| s != meta.len()) {
        return match &task.sha1 {
            Some(expected) => sha1_of_file(&task.path).await.is_ok_and(|h| h.eq_ignore_ascii_case(expected)) && sha512_matches(task).await,
            None => false,
        };
    }
    if !verify_hash {
        return true;
    }
    if let Some(expected) = &task.sha1
        && !sha1_of_file(&task.path).await.is_ok_and(|h| h.eq_ignore_ascii_case(expected))
    {
        return false;
    }
    sha512_matches(task).await
}

async fn sha512_matches(task: &Task) -> bool {
    let Some(expected) = &task.sha512 else { return true };
    sha512_of_file(&task.path).await.is_ok_and(|h| h.eq_ignore_ascii_case(expected))
}

pub async fn sha512_of_file(path: &Path) -> Result<String> {
    let path = path.to_owned();
    tokio::task::spawn_blocking(move || {
        let mut file = std::fs::File::open(&path).map_err(|e| Error::io(&path, e))?;
        let mut hasher = sha2::Sha512::new();
        let mut buf = vec![0u8; 64 * 1024];
        loop {
            let n = std::io::Read::read(&mut file, &mut buf).map_err(|e| Error::io(&path, e))?;
            if n == 0 {
                break;
            }
            sha2::Digest::update(&mut hasher, &buf[..n]);
        }
        Ok(hex(&sha2::Digest::finalize(hasher)))
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))?
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

/// Wartezeit vor dem nächsten Versuch: Retry-After des Servers (höchstens
/// 60 s), sonst exponentiell 1 s → 2 s → 4 s → … höchstens 30 s.
fn retry_delay(err: &Error, attempt: u32) -> Duration {
    if let Error::Download { reason, .. } = err
        && let Some(secs) = reason.split("retry-after ").nth(1).and_then(|s| s.trim_end_matches(')').parse::<u64>().ok())
    {
        return Duration::from_secs(secs.min(60));
    }
    Duration::from_secs((1u64 << (attempt - 1).min(5)).min(30))
}

/// Wie viele Versuche lohnen sich? Dauerhafte Fehler (Datei gibt es nicht, kein Zugriff,
/// ungültige Adresse) sofort melden, vorübergehende (Netz, Drosselung, Serverfehler) geduldig wiederholen.
fn attempts_for(err: &Error) -> u32 {
    let Error::Download { reason, .. } = err else { return MAX_ATTEMPTS };
    if reason.starts_with("nicht unterstütztes URL-Schema") {
        return 1;
    }
    if reason == "Prüfsumme stimmt nicht" || reason == "unerwartete Dateigröße" {
        return MAX_CHECKSUM_ATTEMPTS;
    }
    if reason.contains("redirect-host") {
        return 1;
    }
    if let Some(code) = reason.strip_prefix("HTTP ").and_then(|r| r.get(..3)).and_then(|c| c.parse::<u16>().ok()) {
        // 408 (Timeout), 425 (zu früh) und 429 (gedrosselt) sind vorübergehend, andere 4xx nicht.
        if (400..500).contains(&code) && !matches!(code, 408 | 425 | 429) {
            return 1;
        }
    }
    MAX_ATTEMPTS
}

/// Ist der geladene Inhalt der richtige? Ohne `strict_size` zählt bei vorhandener Prüfsumme
/// nur diese (manche fremden Packs nennen die Größe falsch). Geteilte Packs verlangen beides,
/// und SHA-512, sobald der Index einen hat.
fn check_content(task: &Task, url: &str, written: u64, sha1: &str, sha512: Option<&str>) -> Result<()> {
    if let Some(expected) = &task.sha1
        && !sha1.eq_ignore_ascii_case(expected)
    {
        return Err(Error::download(url, "Prüfsumme stimmt nicht"));
    }
    if let Some(expected) = &task.sha512 {
        let Some(got) = sha512 else {
            return Err(Error::download(url, "Prüfsumme stimmt nicht"));
        };
        if !got.eq_ignore_ascii_case(expected) {
            return Err(Error::download(url, "Prüfsumme stimmt nicht"));
        }
    }
    if task.size.is_some_and(|s| s != written) {
        if task.strict_size || task.sha1.is_none() {
            return Err(Error::download(url, "unerwartete Dateigröße"));
        }
        tracing::debug!("Größe weicht ab ({written} statt {:?}), Prüfsumme stimmt: {url}", task.size);
    }
    Ok(())
}

pub async fn fetch_one(http: &reqwest::Client, task: &Task) -> Result<()> {
    // Zählt in eine laufende Aufgabe (Geschwindigkeit/Größe im Aufgaben-Panel).
    task::add_total(task.size.unwrap_or(0));
    fetch_with_retries(http, task, &task::add_done).await
}

async fn fetch_with_retries(
    http: &reqwest::Client,
    task: &Task,
    on_bytes: &(dyn Fn(i64) + Sync),
) -> Result<()> {
    let mut attempt = 0;
    loop {
        attempt += 1;
        // Abgebrochen oder pausiert? Dann keinen neuen Versuch starten.
        task::checkpoint().await?;
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
                if matches!(e, Error::Cancelled) {
                    return Err(e);
                }
                let allowed = attempts_for(&e);
                tracing::warn!("Download-Versuch {attempt}/{allowed} fehlgeschlagen: {e}");
                if attempt >= allowed {
                    return Err(e);
                }
                task::sleep(retry_delay(&e, attempt)).await?;
            }
        }
    }
}

async fn fetch_attempt(
    http: &reqwest::Client,
    task: &Task,
    on_bytes: &(dyn Fn(u64) + Sync),
) -> Result<()> {
    let url = secure_url(&task.url)?;
    if task.pack && !allowed_pack_url(&url) {
        return Err(Error::download(&url, "redirect-host"));
    }
    if let Some(parent) = task.path.parent() {
        fsutil::ensure_dir(parent).await?;
    }

    let client = if task.pack { pack_http() } else { http };
    let response = client
        .get(&url)
        // Das globale Timeout wäre für große Dateien zu knapp; der Stream
        // unten hat stattdessen ein Timeout pro Chunk.
        .timeout(Duration::from_secs(60 * 30))
        .send()
        .await
        .map_err(|e| Error::download(&url, e.without_url().to_string()))?;
    let status = response.status();
    if !status.is_success() {
        // Retry-After (Sekunden) merken – Modrinth & Co. drosseln mit 429.
        let wait = response
            .headers()
            .get(reqwest::header::RETRY_AFTER)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.trim().parse::<u64>().ok());
        let reason = match wait {
            Some(secs) => format!("HTTP {} (retry-after {secs})", status.as_u16()),
            None => format!("HTTP {}", status.as_u16()),
        };
        return Err(Error::download(&url, reason));
    }

    let tmp = task.path.with_extension(format!("part-{}", uuid::Uuid::new_v4().simple()));
    // Räumt die Teil-Datei auch dann weg, wenn das Future verworfen wird
    // (erster Fehler eines Stapels, abgebrochene Aufgabe).
    let mut part = PartFile(Some(tmp.clone()));
    let result = async {
        let mut file = tokio::fs::File::create(&tmp).await.map_err(|e| Error::io(&tmp, e))?;
        let mut hasher = Sha1::new();
        let mut hasher512 = task.sha512.is_some().then(sha2::Sha512::new);
        let mut written = 0u64;
        let mut stream = response.bytes_stream();

        loop {
            // Pause hält hier an, Abbruch endet hier.
            task::checkpoint().await?;
            let chunk = tokio::time::timeout(Duration::from_secs(30), stream.next())
                .await
                .map_err(|_| Error::download(&url, "Zeitüberschreitung"))?;
            let Some(chunk) = chunk else { break };
            let chunk = chunk.map_err(|e| Error::download(&url, e.without_url().to_string()))?;
            hasher.update(&chunk);
            if let Some(h) = hasher512.as_mut() {
                sha2::Digest::update(h, &chunk);
            }
            file.write_all(&chunk).await.map_err(|e| Error::io(&tmp, e))?;
            written += chunk.len() as u64;
            on_bytes(chunk.len() as u64);
        }
        file.flush().await.map_err(|e| Error::io(&tmp, e))?;
        drop(file);

        let sha512 = hasher512.map(|h| hex(&sha2::Digest::finalize(h)));
        check_content(task, &url, written, &hex(&hasher.finalize()), sha512.as_deref())?;
        tokio::fs::rename(&tmp, &task.path).await.map_err(|e| Error::io(&task.path, e))
    }
    .await;

    if result.is_ok() {
        part.0 = None;
    } else if let Some(tmp) = part.0.take() {
        let _ = tokio::fs::remove_file(&tmp).await;
    }
    result
}

/// Teil-Datei eines Downloads; wird beim Verwerfen gelöscht, solange sie
/// nicht umbenannt wurde.
struct PartFile(Option<PathBuf>);

impl Drop for PartFile {
    fn drop(&mut self) {
        if let Some(path) = self.0.take() {
            let _ = std::fs::remove_file(path);
        }
    }
}

/// Lädt alle fehlenden Dateien. `on_progress` wird gedrosselt aufgerufen und
/// garantiert einmal am Ende mit dem Endstand.
pub async fn fetch_all(
    http: &reqwest::Client,
    tasks: Vec<Task>,
    concurrency: usize,
    on_progress: &(dyn Fn(Progress) + Sync),
) -> Result<()> {
    fetch_all_with(http, tasks, concurrency, false, on_progress).await
}

/// Wie [`fetch_all`]; mit `verify` wird jede vorhandene Datei per SHA1 geprüft
/// und bei Abweichung neu geladen (Reparieren).
pub async fn fetch_all_with(
    http: &reqwest::Client,
    tasks: Vec<Task>,
    concurrency: usize,
    verify: bool,
    on_progress: &(dyn Fn(Progress) + Sync),
) -> Result<()> {
    // Dieselbe Datei taucht oft mehrfach auf (Assets mit gleichem Hash).
    let mut seen = HashSet::new();
    let unique: Vec<Task> = tasks.into_iter().filter(|t| seen.insert(t.path.clone())).collect();

    // Benannte async-Funktionen statt Closures: Closures, die hier Referenzen
    // einfangen, machen das Future für den Compiler nicht mehr `Send`-beweisbar.
    let checked: Vec<Option<Task>> = if verify {
        futures::stream::iter(unique.into_iter().map(keep_if_invalid)).buffer_unordered(16).collect().await
    } else {
        futures::stream::iter(unique.into_iter().map(keep_if_missing)).buffer_unordered(64).collect().await
    };
    let missing: Vec<Task> = checked.into_iter().flatten().collect();

    let total_files = missing.len() as u64;
    let total_bytes =
        if missing.iter().all(|t| t.size.is_some()) { missing.iter().filter_map(|t| t.size).sum() } else { 0 };
    let done_bytes = AtomicU64::new(0);
    let done_files = AtomicU64::new(0);
    let started = Instant::now();
    let last_emit = AtomicU64::new(0);
    task::add_total(total_bytes);

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
        task::add_done(delta);
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

async fn keep_if_invalid(task: Task) -> Option<Task> {
    if is_valid(&task, true).await { None } else { Some(task) }
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
    fn retry_delays() {
        let throttled = Error::download("https://x", "HTTP 429 (retry-after 7)");
        assert_eq!(retry_delay(&throttled, 1), Duration::from_secs(7));
        let huge = Error::download("https://x", "HTTP 429 (retry-after 9999)");
        assert_eq!(retry_delay(&huge, 1), Duration::from_secs(60));
        let other = Error::download("https://x", "HTTP 503");
        assert_eq!(retry_delay(&other, 1), Duration::from_secs(1));
        assert_eq!(retry_delay(&other, 3), Duration::from_secs(4));
        assert_eq!(retry_delay(&other, 8), Duration::from_secs(30));
        // Insgesamt gut eine Minute Geduld bei vorübergehenden Fehlern.
        let total: Duration = (1..MAX_ATTEMPTS).map(|a| retry_delay(&other, a)).sum();
        assert!(total >= Duration::from_secs(60), "{total:?}");
    }

    #[test]
    fn permanent_errors_fail_fast_transient_ones_wait() {
        let e = |r: &str| Error::download("https://x", r);
        assert_eq!(attempts_for(&e("HTTP 404")), 1);
        assert_eq!(attempts_for(&e("HTTP 403")), 1);
        assert_eq!(attempts_for(&e("nicht unterstütztes URL-Schema")), 1);
        assert_eq!(attempts_for(&e("HTTP 429 (retry-after 5)")), MAX_ATTEMPTS);
        assert_eq!(attempts_for(&e("HTTP 408")), MAX_ATTEMPTS);
        assert_eq!(attempts_for(&e("HTTP 503")), MAX_ATTEMPTS);
        assert_eq!(attempts_for(&e("Zeitüberschreitung")), MAX_ATTEMPTS);
        assert_eq!(attempts_for(&e("connection reset")), MAX_ATTEMPTS);
        assert_eq!(attempts_for(&e("Prüfsumme stimmt nicht")), MAX_CHECKSUM_ATTEMPTS);
        assert_eq!(attempts_for(&e("unerwartete Dateigröße")), MAX_CHECKSUM_ATTEMPTS);
        assert_eq!(attempts_for(&e("redirect-host")), 1);
    }

    #[test]
    fn checksum_wins_over_a_wrong_size() {
        // Better MC (BMC4) nennt für Balm 591397 Bytes, die Datei hat 591398 – die SHA1 stimmt.
        let sha = "c689f4cbe1a5250177aced15b66ca251d9476d35";
        let task = Task {
            url: String::new(),
            path: PathBuf::new(),
            sha1: Some(sha.into()),
            size: Some(591_397),
            sha512: None,
            strict_size: false,
            pack: false,
        };
        assert!(check_content(&task, "https://x", 591_398, sha, None).is_ok());
        assert!(check_content(&task, "https://x", 591_398, &"0".repeat(40), None).is_err());
        let no_hash = Task { sha1: None, ..task.clone() };
        assert!(check_content(&no_hash, "https://x", 591_398, sha, None).is_err());
        assert!(check_content(&no_hash, "https://x", 591_397, sha, None).is_ok());
        let strict = Task { strict_size: true, ..task.clone() };
        assert!(check_content(&strict, "https://x", 591_398, sha, None).is_err());
        assert!(check_content(&strict, "https://x", 591_397, sha, None).is_ok());
        let sha512 = "ab".repeat(64);
        let with_512 = Task { sha512: Some(sha512.clone()), ..task };
        assert!(check_content(&with_512, "https://x", 591_397, sha, Some(&sha512)).is_ok());
        assert!(check_content(&with_512, "https://x", 591_397, sha, Some(&"00".repeat(64))).is_err());
        assert!(check_content(&with_512, "https://x", 591_397, sha, None).is_err());
    }

    #[test]
    fn pack_urls_stay_on_the_allowed_hosts() {
        assert!(allowed_pack_url("https://cdn.modrinth.com/data/x/versions/y/a.jar"));
        assert!(allowed_pack_url("https://edge.forgecdn.net/files/1/2/a.jar"));
        assert!(allowed_pack_url("https://mediafilez.forgecdn.net/files/1/2/a.jar"));
        assert!(allowed_pack_url("https://github.com/owner/repo/releases/download/v1/a.jar"));
        assert!(allowed_pack_url("https://raw.githubusercontent.com/owner/repo/main/a.jar"));
        assert!(allowed_pack_url("https://gitlab.com/owner/repo/-/raw/main/a.jar"));
        assert!(!allowed_pack_url("https://cdn.modrinth.com.evil/a.jar"));
        assert!(!allowed_pack_url("https://evil.example/a.jar"));
        assert!(!allowed_pack_url("https://user:pw@cdn.modrinth.com/a.jar"));
        assert!(!allowed_pack_url("http://cdn.modrinth.com/a.jar"));
        assert!(!allowed_pack_url("https://cdn.modrinth.com:8443/a.jar"));
        assert!(!allowed_pack_url("https://objects.githubusercontent.com/a.jar"));
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

        let ok = Task { url: String::new(), path: path.clone(), sha1: Some(sha.into()), size: Some(5), sha512: None, strict_size: false, pack: false };
        assert!(is_valid(&ok, true).await);

        // Falsche Größe, aber passende Prüfsumme: die Datei ist richtig (Modpack-Angabe falsch).
        let wrong_size = Task { size: Some(6), ..ok.clone() };
        assert!(is_valid(&wrong_size, false).await);
        let strict = Task { strict_size: true, ..wrong_size.clone() };
        assert!(!is_valid(&strict, false).await);
        let wrong_size_no_hash = Task { size: Some(6), sha1: None, ..ok.clone() };
        assert!(!is_valid(&wrong_size_no_hash, false).await);
        let wrong_size_and_hash = Task { size: Some(6), sha1: Some("00".repeat(20)), ..ok.clone() };
        assert!(!is_valid(&wrong_size_and_hash, false).await);

        let wrong_hash = Task { sha1: Some("00".repeat(20)), ..ok.clone() };
        assert!(is_valid(&wrong_hash, false).await);
        assert!(!is_valid(&wrong_hash, true).await);

        let missing = Task { path: dir.path().join("nope"), ..ok };
        assert!(!is_valid(&missing, false).await);
    }
}
