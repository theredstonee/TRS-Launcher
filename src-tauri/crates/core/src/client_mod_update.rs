//! Eigener Update-Kanal für den TRS Client: neue Versionen des In-Game-Mods
//! kommen ohne Launcher-Release in die Instanzen.
//!
//! Auf GitHub liegt im Release `client-mod` ein Manifest `client-mod.json`
//! (Version + alle Builds mit SHA-256 und Größe), dessen Minisign-Signatur
//! `client-mod.json.sig` und alle Jars. Der Launcher
//!
//! 1. lädt Manifest + Signatur beim Start und vor einem Spielstart (höchstens
//!    alle 30 Minuten) mit kurzem Timeout – offline passiert einfach nichts;
//! 2. prüft die Signatur gegen den eingebauten Schlüssel des Tauri-Updaters
//!    und verwirft alles ohne gültige Signatur;
//! 3. legt das geprüfte Manifest unter `<daten>/client-mod/` ab und lädt einen
//!    Jar erst, wenn eine Instanz ihn braucht – nach `<daten>/client-mod/<version>/`,
//!    geprüft über Größe und SHA-256 aus dem signierten Manifest.
//!
//! Warum keine Host-Liste für Weiterleitungen: GitHub leitet Release-Downloads
//! auf wechselnde CDN-Hosts um (`objects.githubusercontent.com`,
//! `release-assets.githubusercontent.com`, …). Die Echtheit hängt nicht am
//! Transportweg, sondern an der Signatur des Manifests und den darin fest
//! eingetragenen Prüfsummen der Jars. Zusätzlich gilt: nur HTTPS (auch für jede
//! Weiterleitung), höchstens fünf Weiterleitungen, harte Größenlimits.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use futures::StreamExt;
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;

use crate::client_mod::{self, Build, Manifest};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

/// Fester Release-Kanal (wie `updater` für den Launcher selbst).
pub const CHANNEL_URL: &str = "https://github.com/theredstonee/TRS-Launcher/releases/download/client-mod/";
/// Öffentlicher Schlüssel des Tauri-Updaters (`plugins.updater.pubkey` in
/// `tauri.conf.json`, Base64 einer Minisign-Schlüsseldatei). Ein Test stellt
/// sicher, dass beide übereinstimmen.
pub const PUBLIC_KEY: &str = "dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IDQxOEQ3OUEwNjk2NTQxRjkKUldUNVFXVnBvSG1OUVRQZk5iYnpLcVdXbndGbVhxbDZaa1F2VnYrNm1rUzVzTG1NTmcyQWpiYVkK";

pub const MANIFEST_FILE: &str = "client-mod.json";
pub const SIGNATURE_FILE: &str = "client-mod.json.sig";
/// Höchstens so oft wird vor einem Spielstart nachgesehen.
pub const CHECK_INTERVAL: Duration = Duration::from_secs(30 * 60);
pub const MAX_MANIFEST_BYTES: u64 = 512 * 1024;
const MAX_SIGNATURE_BYTES: u64 = 4 * 1024;
/// Die Jars sind heute unter 1 MB – alles über diesem Limit ist verdächtig.
pub const MAX_JAR_BYTES: u64 = 32 * 1024 * 1024;
const MAX_VERSION_LEN: usize = 32;
const MANIFEST_TIMEOUT: Duration = Duration::from_secs(8);
const JAR_TIMEOUT: Duration = Duration::from_secs(90);
const MAX_REDIRECTS: usize = 5;

/// Ergebnis einer Prüfung (vor allem für Log und Tests).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CheckOutcome {
    /// Letzte Prüfung ist keine 30 Minuten her.
    Skipped,
    /// Kanal nicht erreichbar oder Inhalt ungültig – es bleibt beim bisherigen Stand.
    Failed,
    /// Nichts Neues.
    Unchanged,
    /// Neues, geprüftes Manifest gespeichert.
    Updated(String),
}

pub struct ClientModUpdater {
    http: reqwest::Client,
    base_url: String,
    public_key: String,
    /// `<daten>/client-mod/`
    dir: PathBuf,
    last_check: Mutex<Option<Instant>>,
    /// Zwei gleichzeitige Starts sollen denselben Jar nicht doppelt laden.
    download: Mutex<()>,
}

impl ClientModUpdater {
    /// Offizieller Kanal auf GitHub, Cache unter `<daten>/client-mod/`.
    pub fn new(paths: &Paths) -> Result<Self> {
        Self::build(paths.client_mod_cache_dir(), CHANNEL_URL, PUBLIC_KEY, true)
    }

    fn build(dir: PathBuf, base_url: &str, public_key: &str, https_only: bool) -> Result<Self> {
        let http = reqwest::Client::builder()
            .user_agent(crate::USER_AGENT)
            .https_only(https_only)
            .redirect(reqwest::redirect::Policy::custom(move |attempt| {
                if attempt.previous().len() >= MAX_REDIRECTS {
                    attempt.error("zu viele Weiterleitungen")
                } else if https_only && attempt.url().scheme() != "https" {
                    attempt.error("Weiterleitung ohne HTTPS")
                } else {
                    attempt.follow()
                }
            }))
            .connect_timeout(Duration::from_secs(5))
            .build()?;
        let base_url = if base_url.ends_with('/') { base_url.to_owned() } else { format!("{base_url}/") };
        Ok(Self {
            http,
            base_url,
            public_key: public_key.to_owned(),
            dir,
            last_check: Mutex::default(),
            download: Mutex::default(),
        })
    }

    /// Für Tests: eigener Server (auch HTTP) und eigener Schlüssel.
    #[cfg(test)]
    pub(crate) fn for_tests(dir: PathBuf, base_url: &str, public_key: &str) -> Self {
        Self::build(dir, base_url, public_key, false).expect("HTTP-Client")
    }

    /// Prüft den Kanal, wenn die letzte Prüfung mindestens 30 Minuten her ist
    /// (oder es noch keine gab). Fehler landen nur im Log.
    pub async fn check_if_due(&self, bundled_version: Option<&str>) -> CheckOutcome {
        let mut last = self.last_check.lock().await;
        if last.is_some_and(|t| t.elapsed() < CHECK_INTERVAL) {
            return CheckOutcome::Skipped;
        }
        let outcome = self.check_now(bundled_version).await;
        *last = Some(Instant::now());
        outcome
    }

    /// Lädt Manifest + Signatur, prüft beides und speichert ein neueres Manifest.
    pub async fn check_now(&self, bundled_version: Option<&str>) -> CheckOutcome {
        let outcome = match self.fetch_verified().await {
            Err(e) => {
                tracing::info!("TRS-Client-Update-Kanal nicht verfügbar: {e}");
                CheckOutcome::Failed
            }
            Ok((bytes, signature, manifest)) => match self.stored().await {
                // Ältere (aber gültig signierte) Stände nie übernehmen – Schutz vor
                // dem Wiedereinspielen alter Manifeste.
                Some(stored) if is_newer(&stored.version, &manifest.version) => {
                    tracing::warn!(
                        "TRS-Client-Kanal liefert {} – älter als gespeichertes {}, ignoriert",
                        manifest.version,
                        stored.version
                    );
                    CheckOutcome::Unchanged
                }
                Some(stored) if stored.version == manifest.version && self.stored_bytes_equal(&bytes).await => {
                    CheckOutcome::Unchanged
                }
                _ => match self.store(&bytes, &signature).await {
                    Ok(()) => {
                        tracing::info!("TRS-Client-Kanal: Version {} verfügbar", manifest.version);
                        CheckOutcome::Updated(manifest.version)
                    }
                    Err(e) => {
                        tracing::warn!("TRS-Client-Manifest konnte nicht gespeichert werden: {e}");
                        CheckOutcome::Failed
                    }
                },
            },
        };
        self.cleanup(bundled_version).await;
        outcome
    }

    async fn fetch_verified(&self) -> Result<(Vec<u8>, Vec<u8>, Manifest)> {
        let bytes = self.get(MANIFEST_FILE, MAX_MANIFEST_BYTES).await?;
        let signature = self.get(SIGNATURE_FILE, MAX_SIGNATURE_BYTES).await?;
        let manifest = verify_manifest(&self.public_key, &bytes, &signature)?;
        Ok((bytes, signature, manifest))
    }

    async fn get(&self, file: &str, limit: u64) -> Result<Vec<u8>> {
        let url = format!("{}{file}", self.base_url);
        let response = self.http.get(&url).timeout(MANIFEST_TIMEOUT).send().await?.error_for_status()?;
        if response.content_length().is_some_and(|len| len > limit) {
            return Err(Error::download(url, "Datei zu groß"));
        }
        let mut body = Vec::new();
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk?;
            if (body.len() + chunk.len()) as u64 > limit {
                return Err(Error::download(url, "Datei zu groß"));
            }
            body.extend_from_slice(&chunk);
        }
        Ok(body)
    }

    async fn store(&self, bytes: &[u8], signature: &[u8]) -> Result<()> {
        fsutil::ensure_dir(&self.dir).await?;
        // Passen die beiden nach einem Absturz nicht zusammen, fällt das Paar bei der
        // nächsten Prüfung durch und wird neu geladen.
        fsutil::write_atomic(&self.dir.join(SIGNATURE_FILE), signature).await?;
        fsutil::write_atomic(&self.dir.join(MANIFEST_FILE), bytes).await
    }

    async fn stored_bytes_equal(&self, bytes: &[u8]) -> bool {
        tokio::fs::read(self.dir.join(MANIFEST_FILE)).await.is_ok_and(|b| b == bytes)
    }

    /// Das gespeicherte Manifest – bei jedem Lesen erneut gegen die Signatur geprüft.
    pub async fn stored(&self) -> Option<Manifest> {
        let bytes = tokio::fs::read(self.dir.join(MANIFEST_FILE)).await.ok()?;
        let signature = tokio::fs::read(self.dir.join(SIGNATURE_FILE)).await.ok()?;
        match verify_manifest(&self.public_key, &bytes, &signature) {
            Ok(manifest) => Some(manifest),
            Err(e) => {
                tracing::warn!("Gespeichertes TRS-Client-Manifest ungültig: {e}");
                None
            }
        }
    }

    /// Pfad des Jars zu `build` aus dem Kanal-Manifest – lädt ihn bei Bedarf
    /// herunter und prüft Größe + SHA-256.
    pub async fn jar(&self, manifest: &Manifest, build: &Build) -> Result<PathBuf> {
        let (Some(sha256), Some(size)) = (build.sha256.as_deref(), build.size) else {
            return Err(Error::validation("TRS-Client-Build ohne Prüfsumme"));
        };
        client_mod::validate_build_file(&build.file)?;
        if !is_valid_version(&manifest.version) {
            return Err(Error::validation("Ungültige TRS-Client-Version"));
        }
        let dir = self.dir.join(&manifest.version);
        let path = dir.join(&build.file);
        if file_matches(&path, sha256, size).await {
            return Ok(path);
        }
        let _guard = self.download.lock().await;
        if file_matches(&path, sha256, size).await {
            return Ok(path);
        }
        fsutil::ensure_dir(&dir).await?;
        let tmp = dir.join(format!("{}.part-{}", build.file, uuid::Uuid::new_v4().simple()));
        let result = self.download_to(&build.file, &tmp, sha256, size).await;
        let result = match result {
            Ok(()) => tokio::fs::rename(&tmp, &path).await.map_err(|e| Error::io(&path, e)),
            Err(e) => Err(e),
        };
        if result.is_err() {
            let _ = tokio::fs::remove_file(&tmp).await;
        }
        result.map(|()| path)
    }

    async fn download_to(&self, file: &str, tmp: &Path, sha256: &str, size: u64) -> Result<()> {
        let url = format!("{}{file}", self.base_url);
        let response = self.http.get(&url).timeout(JAR_TIMEOUT).send().await?.error_for_status()?;
        if response.content_length().is_some_and(|len| len != size) {
            return Err(Error::download(url, "unerwartete Größe"));
        }
        let mut out = tokio::fs::File::create(tmp).await.map_err(|e| Error::io(tmp, e))?;
        let mut hasher = Sha256::new();
        let mut written = 0u64;
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk?;
            written += chunk.len() as u64;
            if written > size {
                return Err(Error::download(url, "unerwartete Größe"));
            }
            hasher.update(&chunk);
            out.write_all(&chunk).await.map_err(|e| Error::io(tmp, e))?;
        }
        out.flush().await.map_err(|e| Error::io(tmp, e))?;
        drop(out);
        if written != size {
            return Err(Error::download(url, "unerwartete Größe"));
        }
        if !hex(&hasher.finalize()).eq_ignore_ascii_case(sha256) {
            return Err(Error::download(url, "Prüfsumme stimmt nicht"));
        }
        Ok(())
    }

    /// Behält nur den Ordner der gespeicherten Kanal-Version – und den auch nur,
    /// wenn sie neuer ist als die mitgelieferte. Alles andere (alte Versionen,
    /// abgebrochene Downloads) verschwindet.
    pub async fn cleanup(&self, bundled_version: Option<&str>) {
        let keep = self
            .stored()
            .await
            .filter(|m| bundled_version.is_none_or(|b| is_newer(&m.version, b)))
            .map(|m| m.version);
        let Ok(mut entries) = tokio::fs::read_dir(&self.dir).await else { return };
        while let Ok(Some(entry)) = entries.next_entry().await {
            let path = entry.path();
            if !entry.file_type().await.is_ok_and(|t| t.is_dir()) {
                continue;
            }
            let name = entry.file_name().to_string_lossy().into_owned();
            if keep.as_deref() == Some(name.as_str()) {
                remove_partial_downloads(&path).await;
            } else if let Err(e) = tokio::fs::remove_dir_all(&path).await {
                tracing::debug!("Alter TRS-Client-Cache {} bleibt vorerst: {e}", path.display());
            }
        }
    }
}

async fn remove_partial_downloads(dir: &Path) {
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else { return };
    while let Ok(Some(entry)) = entries.next_entry().await {
        if entry.file_name().to_string_lossy().contains(".part-") {
            let _ = tokio::fs::remove_file(entry.path()).await;
        }
    }
}

pub(crate) async fn file_matches(path: &Path, sha256: &str, size: u64) -> bool {
    if !tokio::fs::metadata(path).await.is_ok_and(|m| m.is_file() && m.len() == size) {
        return false;
    }
    sha256_of_file(path).await.is_ok_and(|h| h.eq_ignore_ascii_case(sha256))
}

pub async fn sha256_of_file(path: &Path) -> Result<String> {
    let path = path.to_owned();
    tokio::task::spawn_blocking(move || {
        let mut file = std::fs::File::open(&path).map_err(|e| Error::io(&path, e))?;
        let mut hasher = Sha256::new();
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

/// Prüft die Signatur (Format von `tauri signer sign`: Base64 einer Minisign-
/// Signaturdatei) und liest danach das Manifest. Verlangt werden: gültige
/// Prehash-Signatur des richtigen Schlüssels, `file:client-mod.json` im
/// signierten Kommentar, eine gültige Version und je Build Prüfsumme + Größe.
pub fn verify_manifest(public_key: &str, bytes: &[u8], signature: &[u8]) -> Result<Manifest> {
    verify_signature(public_key, bytes, signature, MANIFEST_FILE)?;
    let manifest = client_mod::parse_manifest(bytes).ok_or_else(|| Error::validation("TRS-Client-Manifest unlesbar"))?;
    if !is_valid_version(&manifest.version) {
        return Err(Error::validation("TRS-Client-Manifest ohne gültige Version"));
    }
    let builds = manifest
        .builds
        .into_iter()
        .filter(|b| {
            b.sha256.as_deref().is_some_and(is_sha256)
                && b.size.is_some_and(|s| s > 0 && s <= MAX_JAR_BYTES)
                && client_mod::validate_build_file(&b.file).is_ok()
        })
        .collect();
    Ok(Manifest { version: manifest.version, builds })
}

pub fn verify_signature(public_key: &str, data: &[u8], signature: &[u8], file_name: &str) -> Result<()> {
    let invalid = |what: &str| Error::validation(format!("Signatur ungültig ({what})"));
    let key_text = decode_base64_text(public_key.as_bytes()).ok_or_else(|| invalid("Schlüssel"))?;
    let key = minisign_verify::PublicKey::decode(&key_text).map_err(|_| invalid("Schlüssel"))?;
    let sig_text = decode_base64_text(signature).ok_or_else(|| invalid("Format"))?;
    let sig = minisign_verify::Signature::decode(&sig_text).map_err(|_| invalid("Format"))?;
    // Nur Prehash-Signaturen (so signiert `tauri signer sign`).
    key.verify(data, &sig, false).map_err(|_| invalid("Prüfung"))?;
    let expected = format!("file:{file_name}");
    if !sig.trusted_comment().split('\t').any(|part| part == expected) {
        return Err(invalid("falsche Datei"));
    }
    Ok(())
}

fn decode_base64_text(raw: &[u8]) -> Option<String> {
    let text = std::str::from_utf8(raw).ok()?.trim();
    String::from_utf8(STANDARD.decode(text).ok()?).ok()
}

fn is_sha256(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit())
}

/// `1.2.3` oder `1.2.3-beta.1` – nur Zeichen, die auch als Ordnername taugen.
pub fn is_valid_version(version: &str) -> bool {
    version.len() <= MAX_VERSION_LEN && parse_version(version).is_some()
}

fn parse_version(version: &str) -> Option<(Vec<u64>, Option<&str>)> {
    let (core, pre) = match version.split_once('-') {
        Some((core, pre)) => (core, Some(pre)),
        None => (version, None),
    };
    let numbers = core.split('.').map(|p| if p.is_empty() || p.len() > 9 { None } else { p.parse().ok() }).collect::<Option<Vec<u64>>>()?;
    if numbers.is_empty() || numbers.len() > 4 {
        return None;
    }
    // Vorabversion: durch Punkte getrennte, nicht leere alphanumerische Teile ("beta.1").
    if let Some(pre) = pre
        && !pre.split('.').all(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_alphanumeric()))
    {
        return None;
    }
    Some((numbers, pre))
}

/// Ist `a` neuer als `b`? Ungültige oder leere Versionen (alte `builds.json`)
/// gelten als älteste.
pub fn is_newer(a: &str, b: &str) -> bool {
    let Some((na, pa)) = parse_version(a) else { return false };
    let Some((nb, pb)) = parse_version(b) else { return true };
    let len = na.len().max(nb.len());
    let pad = |v: &[u64]| (0..len).map(|i| v.get(i).copied().unwrap_or(0)).collect::<Vec<_>>();
    match pad(&na).cmp(&pad(&nb)) {
        std::cmp::Ordering::Greater => true,
        std::cmp::Ordering::Less => false,
        // Gleiche Nummer: Release schlägt Vorabversion.
        std::cmp::Ordering::Equal => match (pa, pb) {
            (None, Some(_)) => true,
            (Some(x), Some(y)) => x > y,
            _ => false,
        },
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use std::collections::HashMap;
    use std::sync::Arc;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::*;

    /// Ein frisches Minisign-Schlüsselpaar – `public` im selben Format wie in tauri.conf.json.
    pub(crate) struct TestKey {
        pub(crate) public: String,
        secret: minisign::SecretKey,
        pk: minisign::PublicKey,
    }

    impl TestKey {
        pub(crate) fn generate() -> Self {
            let pair = minisign::KeyPair::generate_unencrypted_keypair().unwrap();
            let public = STANDARD.encode(pair.pk.to_box().unwrap().to_string());
            Self { public, secret: pair.sk, pk: pair.pk }
        }

        /// Wie `tauri signer sign`: Base64 der Signaturdatei, Kommentar mit Dateiname.
        pub(crate) fn sign(&self, data: &[u8], file_name: &str) -> Vec<u8> {
            let comment = format!("timestamp:1700000000\tfile:{file_name}");
            let sig = minisign::sign(Some(&self.pk), &self.secret, data, Some(&comment), Some("signature from tauri secret key"))
                .unwrap();
            STANDARD.encode(sig.to_string()).into_bytes()
        }
    }

    /// Kleiner HTTP-Server für die Tests: Pfad → Inhalt, alles andere 404.
    #[derive(Clone, Default)]
    pub(crate) struct TestServer {
        files: Arc<std::sync::Mutex<HashMap<String, Vec<u8>>>>,
        hits: Arc<std::sync::Mutex<Vec<String>>>,
    }

    impl TestServer {
        pub(crate) async fn start() -> (Self, String) {
            let server = Self::default();
            let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
            let url = format!("http://{}/", listener.local_addr().unwrap());
            let s = server.clone();
            tokio::spawn(async move {
                while let Ok((mut stream, _)) = listener.accept().await {
                    let s = s.clone();
                    tokio::spawn(async move {
                        let mut buf = vec![0u8; 4096];
                        let n = stream.read(&mut buf).await.unwrap_or(0);
                        let request = String::from_utf8_lossy(&buf[..n]);
                        let path = request.split_whitespace().nth(1).unwrap_or("/").trim_start_matches('/').to_owned();
                        s.hits.lock().unwrap().push(path.clone());
                        let body = s.files.lock().unwrap().get(&path).cloned();
                        let (status, body) = match body {
                            Some(b) => ("200 OK", b),
                            None => ("404 Not Found", Vec::new()),
                        };
                        let head = format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                        let _ = stream.write_all(head.as_bytes()).await;
                        let _ = stream.write_all(&body).await;
                        let _ = stream.shutdown().await;
                    });
                }
            });
            (server, url)
        }

        pub(crate) fn put(&self, path: &str, body: impl Into<Vec<u8>>) {
            self.files.lock().unwrap().insert(path.to_owned(), body.into());
        }

        pub(crate) fn hits(&self, path: &str) -> usize {
            self.hits.lock().unwrap().iter().filter(|p| *p == path).count()
        }
    }

    pub(crate) fn sha256_hex(data: &[u8]) -> String {
        hex(&Sha256::digest(data))
    }

    /// Manifest mit einem Fabric-Build für 1.21/1.21.1.
    pub(crate) fn manifest_json(version: &str, jar: &[u8]) -> Vec<u8> {
        serde_json::json!({
            "version": version,
            "builds": [{
                "loader": "fabric",
                "minecraft": ["1.21", "1.21.1"],
                "file": "trsclient-fabric-1.21.jar",
                "requires": ["fabric-api"],
                "sha256": sha256_hex(jar),
                "size": jar.len(),
            }]
        })
        .to_string()
        .into_bytes()
    }

    /// Veröffentlicht Manifest, Signatur und Jar auf dem Testserver.
    pub(crate) fn publish(server: &TestServer, key: &TestKey, version: &str, jar: &[u8]) {
        let manifest = manifest_json(version, jar);
        server.put(SIGNATURE_FILE, key.sign(&manifest, MANIFEST_FILE));
        server.put(MANIFEST_FILE, manifest);
        server.put("trsclient-fabric-1.21.jar", jar.to_vec());
    }

    #[test]
    fn embedded_key_matches_tauri_updater_key() {
        let conf = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../tauri.conf.json");
        let conf: serde_json::Value = serde_json::from_slice(&std::fs::read(conf).unwrap()).unwrap();
        assert_eq!(conf["plugins"]["updater"]["pubkey"].as_str(), Some(PUBLIC_KEY));
        assert!(decode_base64_text(PUBLIC_KEY.as_bytes()).is_some_and(|k| minisign_verify::PublicKey::decode(&k).is_ok()));
    }

    #[test]
    fn signature_valid_invalid_and_other_key() {
        let key = TestKey::generate();
        let other = TestKey::generate();
        let data = manifest_json("0.3.0", b"jar");
        let sig = key.sign(&data, MANIFEST_FILE);

        assert!(verify_signature(&key.public, &data, &sig, MANIFEST_FILE).is_ok());
        let manifest = verify_manifest(&key.public, &data, &sig).unwrap();
        assert_eq!(manifest.version, "0.3.0");
        assert_eq!(manifest.builds.len(), 1);

        // Verändert, anderer Schlüssel, fremde Datei, Müll, gar keine Signatur.
        let mut tampered = data.clone();
        tampered[5] ^= 1;
        assert!(verify_manifest(&key.public, &tampered, &sig).is_err());
        assert!(verify_manifest(&other.public, &data, &sig).is_err());
        assert!(verify_manifest(&key.public, &data, &key.sign(&data, "latest.json")).is_err());
        assert!(verify_manifest(&key.public, &data, b"bm9wZQ==").is_err());
        assert!(verify_manifest(&key.public, &data, b"").is_err());
        assert!(verify_manifest("kein base64", &data, &sig).is_err());
    }

    #[test]
    fn signed_manifest_needs_version_and_checksums() {
        let key = TestKey::generate();
        let sign = |v: serde_json::Value| {
            let bytes = v.to_string().into_bytes();
            let sig = key.sign(&bytes, MANIFEST_FILE);
            verify_manifest(&key.public, &bytes, &sig)
        };
        // Alte builds.json (Liste ohne Version) reicht für den Kanal nicht.
        assert!(sign(serde_json::json!([])).is_err());
        assert!(sign(serde_json::json!({"version": "../x", "builds": []})).is_err());
        let m = sign(serde_json::json!({"version": "1.0.0", "builds": [
            {"loader":"fabric","minecraft":["1.21"],"file":"a.jar","sha256":"00","size":1},
            {"loader":"fabric","minecraft":["1.21"],"file":"b.jar","size":1},
            {"loader":"fabric","minecraft":["1.21"],"file":"..\\c.jar","sha256":"0".repeat(64),"size":1},
            {"loader":"fabric","minecraft":["1.21"],"file":"d.jar","sha256":"0".repeat(64),"size":MAX_JAR_BYTES + 1},
            {"loader":"fabric","minecraft":["1.21"],"file":"ok.jar","sha256":"A".repeat(64),"size":10},
        ]}))
        .unwrap();
        assert_eq!(m.builds.iter().map(|b| b.file.as_str()).collect::<Vec<_>>(), ["ok.jar"]);
    }

    #[test]
    fn version_order() {
        assert!(is_newer("0.3.0", "0.2.0"));
        assert!(is_newer("0.10.0", "0.9.9"));
        assert!(is_newer("1.0", "0.9.9"));
        assert!(is_newer("1.0.0", "1.0.0-beta.1"));
        assert!(is_newer("1.0.0-beta.2", "1.0.0-beta.1"));
        assert!(is_newer("0.2.1", ""), "alte builds.json ohne Version ist immer älter");
        assert!(!is_newer("0.2.0", "0.2.0"));
        assert!(!is_newer("1.0.0", "1.0"));
        assert!(!is_newer("0.2.0", "0.3.0"));
        assert!(!is_newer("", "0.1.0"));
        assert!(!is_newer("abc", "0.1.0"));
        for bad in ["", "1..2", "1.2.3-", "1.2.3/..", "../1", "1.2.3-a b", "1-..", "1.0.0-beta.", &"1".repeat(40)] {
            assert!(!is_valid_version(bad), "{bad}");
        }
        assert!(is_valid_version("0.3.0"));
        assert!(is_valid_version("1.2.3-rc.1"));
    }

    #[tokio::test]
    async fn check_stores_newer_manifests_only() {
        let key = TestKey::generate();
        let (server, url) = TestServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let updater = ClientModUpdater::for_tests(dir.path().to_owned(), &url, &key.public);

        publish(&server, &key, "0.3.0", b"jar-v3");
        assert_eq!(updater.check_if_due(Some("0.2.0")).await, CheckOutcome::Updated("0.3.0".into()));
        assert_eq!(updater.check_if_due(Some("0.2.0")).await, CheckOutcome::Skipped, "höchstens alle 30 Minuten");
        assert_eq!(updater.check_now(Some("0.2.0")).await, CheckOutcome::Unchanged);
        assert_eq!(updater.stored().await.unwrap().version, "0.3.0");

        // Ein älteres, gültig signiertes Manifest wird nicht übernommen.
        publish(&server, &key, "0.2.5", b"jar-v25");
        assert_eq!(updater.check_now(Some("0.2.0")).await, CheckOutcome::Unchanged);
        assert_eq!(updater.stored().await.unwrap().version, "0.3.0");

        // Falsch signiert: verworfen, der alte Stand bleibt.
        let other = TestKey::generate();
        publish(&server, &other, "0.9.0", b"boese");
        assert_eq!(updater.check_now(Some("0.2.0")).await, CheckOutcome::Failed);
        assert_eq!(updater.stored().await.unwrap().version, "0.3.0");

        publish(&server, &key, "0.4.0", b"jar-v4");
        assert_eq!(updater.check_now(Some("0.2.0")).await, CheckOutcome::Updated("0.4.0".into()));

        // Lokal manipuliertes Manifest gilt nicht mehr.
        std::fs::write(dir.path().join(MANIFEST_FILE), manifest_json("9.9.9", b"x")).unwrap();
        assert!(updater.stored().await.is_none());
    }

    #[tokio::test]
    async fn offline_is_silent() {
        let key = TestKey::generate();
        let dir = tempfile::tempdir().unwrap();
        // Port 9 (discard) ist lokal nie offen.
        let updater = ClientModUpdater::for_tests(dir.path().to_owned(), "http://127.0.0.1:9/", &key.public);
        let started = Instant::now();
        assert_eq!(updater.check_if_due(None).await, CheckOutcome::Failed);
        assert!(started.elapsed() < Duration::from_secs(15));
        assert_eq!(updater.check_if_due(None).await, CheckOutcome::Skipped);
        assert!(updater.stored().await.is_none());
    }

    #[tokio::test]
    async fn jar_download_is_verified() {
        let key = TestKey::generate();
        let (server, url) = TestServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let updater = ClientModUpdater::for_tests(dir.path().to_owned(), &url, &key.public);
        publish(&server, &key, "0.3.0", b"jar-v3");
        updater.check_now(None).await;
        let manifest = updater.stored().await.unwrap();
        let build = &manifest.builds[0];

        let path = updater.jar(&manifest, build).await.unwrap();
        assert_eq!(path, dir.path().join("0.3.0").join("trsclient-fabric-1.21.jar"));
        assert_eq!(std::fs::read(&path).unwrap(), b"jar-v3");
        // Liegt er schon da, wird nichts mehr geladen.
        updater.jar(&manifest, build).await.unwrap();
        assert_eq!(server.hits("trsclient-fabric-1.21.jar"), 1);

        // Jar auf dem Server ausgetauscht (andere Prüfsumme): abgelehnt, nichts bleibt liegen.
        std::fs::remove_file(&path).unwrap();
        server.put("trsclient-fabric-1.21.jar", b"jar-XX".to_vec());
        assert!(updater.jar(&manifest, build).await.is_err());
        server.put("trsclient-fabric-1.21.jar", b"jar-v3-aber-laenger".to_vec());
        assert!(updater.jar(&manifest, build).await.is_err());
        assert_eq!(std::fs::read_dir(dir.path().join("0.3.0")).unwrap().count(), 0, "keine halben Dateien");

        // Lokal beschädigt: wird neu geladen.
        server.put("trsclient-fabric-1.21.jar", b"jar-v3".to_vec());
        std::fs::write(&path, b"jar-v?").unwrap();
        assert_eq!(std::fs::read(updater.jar(&manifest, build).await.unwrap()).unwrap(), b"jar-v3");
    }

    #[tokio::test]
    async fn cleanup_keeps_only_the_newest_version() {
        let key = TestKey::generate();
        let (server, url) = TestServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let updater = ClientModUpdater::for_tests(dir.path().to_owned(), &url, &key.public);

        publish(&server, &key, "0.3.0", b"jar-v3");
        updater.check_now(Some("0.2.0")).await;
        let m3 = updater.stored().await.unwrap();
        updater.jar(&m3, &m3.builds[0]).await.unwrap();
        std::fs::write(dir.path().join("0.3.0").join("x.jar.part-123"), b"halb").unwrap();

        publish(&server, &key, "0.4.0", b"jar-v4");
        updater.check_now(Some("0.2.0")).await;
        let m4 = updater.stored().await.unwrap();
        updater.jar(&m4, &m4.builds[0]).await.unwrap();
        updater.cleanup(Some("0.2.0")).await;
        assert!(!dir.path().join("0.3.0").exists(), "alte Version weg");
        assert!(dir.path().join("0.4.0").join("trsclient-fabric-1.21.jar").is_file());

        std::fs::write(dir.path().join("0.4.0").join("y.jar.part-1"), b"halb").unwrap();
        updater.cleanup(Some("0.2.0")).await;
        assert!(!dir.path().join("0.4.0").join("y.jar.part-1").exists(), "abgebrochene Downloads weg");

        // Launcher-Update bringt 0.4.0 selbst mit: Cache wird nicht mehr gebraucht.
        updater.cleanup(Some("0.4.0")).await;
        assert!(!dir.path().join("0.4.0").exists());
        assert!(dir.path().join(MANIFEST_FILE).is_file(), "Manifest bleibt als Stand gegen alte Kopien");
    }

    /// Prüft ein mit `scripts/publish-client-mod.mjs --dry-run` erzeugtes Manifest
    /// gegen den echten Schlüssel:
    /// `TRS_CLIENT_MOD_CHANNEL_DIR=<ordner> cargo test -p trs-core published_manifest -- --ignored`
    /// Mit `TRS_CLIENT_MOD_DIST=<ordner mit den Jars>` auch alle Prüfsummen.
    #[test]
    #[ignore = "braucht ein erzeugtes client-mod.json (TRS_CLIENT_MOD_CHANNEL_DIR)"]
    fn published_manifest_verifies_with_the_real_key() {
        let dir = PathBuf::from(std::env::var("TRS_CLIENT_MOD_CHANNEL_DIR").expect("TRS_CLIENT_MOD_CHANNEL_DIR"));
        let bytes = std::fs::read(dir.join(MANIFEST_FILE)).unwrap();
        let sig = std::fs::read(dir.join(SIGNATURE_FILE)).unwrap();
        let manifest = verify_manifest(PUBLIC_KEY, &bytes, &sig).expect("Signatur");
        let raw: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(manifest.builds.len(), raw["builds"].as_array().unwrap().len(), "Builds verworfen");
        if let Ok(dist) = std::env::var("TRS_CLIENT_MOD_DIST") {
            for b in &manifest.builds {
                let data = std::fs::read(Path::new(&dist).join(&b.file)).unwrap();
                assert_eq!(Some(data.len() as u64), b.size, "{}", b.file);
                assert_eq!(b.sha256.as_deref(), Some(sha256_hex(&data).as_str()), "{}", b.file);
            }
        }
        println!("{} Builds, Version {} – Signatur gültig", manifest.builds.len(), manifest.version);
    }
}
