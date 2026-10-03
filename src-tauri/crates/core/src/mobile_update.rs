//! Update-Kanal der mobilen App (Android/iOS).
//!
//! Auf GitHub liegt im Release `mobile` ein Manifest `mobile.json` (Version,
//! Notizen, APK mit SHA-256 und Größe, IPA + AltStore-Quelle) und dessen
//! Minisign-Signatur `mobile.json.sig` – gleiches Verfahren und gleicher
//! Schlüssel wie beim TRS-Client-Kanal ([`crate::client_mod_update`]).
//!
//! Android: Die App prüft den Kanal, lädt die APK nach `<daten>/mobile-update/`
//! (Größe + SHA-256 aus dem signierten Manifest) und übergibt sie dem
//! PackageInstaller – installiert wird erst nach Bestätigung durch den Nutzer.
//! iOS: Installiert wird über AltStore/SideStore; die App zeigt nur, dass es
//! eine neue Version gibt, und die Quelle dafür.

use std::path::{Path, PathBuf};
use std::time::Duration;

use futures::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;

use crate::client_mod_update::{PUBLIC_KEY, file_matches, is_newer, is_valid_version, verify_signature};
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

/// Fester Release-Kanal (wie `updater` und `client-mod`).
pub const CHANNEL_URL: &str = "https://github.com/theredstonee/TRS-Launcher/releases/download/mobile/";
/// Downloads nur aus den Releases des eigenen Repos.
pub const DOWNLOAD_PREFIX: &str = "https://github.com/theredstonee/TRS-Launcher/releases/download/";
pub const MANIFEST_FILE: &str = "mobile.json";
pub const SIGNATURE_FILE: &str = "mobile.json.sig";
const MAX_MANIFEST_BYTES: u64 = 256 * 1024;
const MAX_SIGNATURE_BYTES: u64 = 4 * 1024;
/// Die APK liegt heute um die 30–60 MB – alles über diesem Limit ist verdächtig.
pub const MAX_APK_BYTES: u64 = 400 * 1024 * 1024;
const MAX_NOTES_CHARS: usize = 20_000;
const MAX_URL_LEN: usize = 512;
const MANIFEST_TIMEOUT: Duration = Duration::from_secs(10);
const APK_TIMEOUT: Duration = Duration::from_secs(30 * 60);
const MAX_REDIRECTS: usize = 5;
const UPDATE_DIR: &str = "mobile-update";

/// Eine Datei im Kanal (APK oder IPA).
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Asset {
    pub url: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IosAsset {
    #[serde(flatten)]
    pub file: Asset,
    /// AltStore-/SideStore-Quelle (JSON), die auf die IPA zeigt.
    #[serde(default)]
    pub altstore: Option<String>,
}

/// Geprüftes Manifest aus `mobile.json`.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub version: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub pub_date: Option<String>,
    #[serde(default)]
    pub android: Option<Asset>,
    #[serde(default)]
    pub ios: Option<IosAsset>,
}

/// Was die Oberfläche über den Kanal erfährt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateStatus {
    /// Installierte Version.
    pub current: String,
    /// Neueste Version im Kanal (`None`: Kanal leer oder nicht erreichbar).
    pub latest: Option<String>,
    /// Gibt es eine neuere Version für diese Plattform?
    pub available: bool,
    pub notes: String,
    pub pub_date: Option<String>,
    /// Größe der APK in Bytes (Android).
    pub size: Option<u64>,
    /// AltStore-/SideStore-Quelle (iOS).
    pub altstore_source: Option<String>,
}

/// Für welche Plattform der Kanal gelesen wird.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Target {
    Android,
    Ios,
}

impl Target {
    /// Die Plattform, für die dieser Build gebaut ist (Desktop: Android als Vorschau).
    pub fn current() -> Self {
        if cfg!(target_os = "ios") { Self::Ios } else { Self::Android }
    }
}

pub struct MobileUpdater {
    http: reqwest::Client,
    base_url: String,
    download_prefix: String,
    public_key: String,
    current: String,
    /// `<daten>/mobile-update/`
    dir: PathBuf,
    /// Zuletzt geprüftes Manifest (nur gültig signierte).
    latest: Mutex<Option<Manifest>>,
    download: Mutex<()>,
}

impl MobileUpdater {
    /// Offizieller Kanal, Downloads unter `<daten>/mobile-update/`.
    pub fn new(paths: &Paths) -> Result<Self> {
        Self::build(paths.root().join(UPDATE_DIR), CHANNEL_URL, DOWNLOAD_PREFIX, PUBLIC_KEY, crate::LAUNCHER_VERSION, true)
    }

    fn build(dir: PathBuf, base_url: &str, prefix: &str, public_key: &str, current: &str, https_only: bool) -> Result<Self> {
        let http = crate::net::client_builder()
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
            .connect_timeout(Duration::from_secs(10))
            .build()?;
        let base_url = if base_url.ends_with('/') { base_url.to_owned() } else { format!("{base_url}/") };
        Ok(Self {
            http,
            base_url,
            download_prefix: prefix.to_owned(),
            public_key: public_key.to_owned(),
            current: current.to_owned(),
            dir,
            latest: Mutex::default(),
            download: Mutex::default(),
        })
    }

    #[cfg(test)]
    fn for_tests(dir: PathBuf, base_url: &str, public_key: &str, current: &str) -> Self {
        Self::build(dir, base_url, base_url, public_key, current, false).expect("HTTP-Client")
    }

    /// Lädt Manifest + Signatur und prüft beides. Fehler (offline, ungültig)
    /// gehen an den Aufrufer – die Oberfläche fragt bewusst nach.
    pub async fn check(&self, target: Target) -> Result<UpdateStatus> {
        let bytes = self.get(MANIFEST_FILE, MAX_MANIFEST_BYTES).await?;
        let signature = self.get(SIGNATURE_FILE, MAX_SIGNATURE_BYTES).await?;
        let manifest = verify_manifest(&self.public_key, &self.download_prefix, &bytes, &signature)?;
        let status = self.status(&manifest, target);
        *self.latest.lock().await = Some(manifest);
        Ok(status)
    }

    fn status(&self, manifest: &Manifest, target: Target) -> UpdateStatus {
        let (has_asset, size, altstore) = match target {
            Target::Android => (manifest.android.is_some(), manifest.android.as_ref().map(|a| a.size), None),
            Target::Ios => (manifest.ios.is_some(), None, manifest.ios.as_ref().and_then(|i| i.altstore.clone())),
        };
        UpdateStatus {
            current: self.current.clone(),
            latest: Some(manifest.version.clone()),
            available: has_asset && is_newer(&manifest.version, &self.current),
            notes: manifest.notes.clone(),
            pub_date: manifest.pub_date.clone(),
            size,
            altstore_source: altstore,
        }
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

    /// Lädt die APK der zuletzt geprüften (neueren) Version und prüft Größe +
    /// SHA-256. Liegt sie schon vollständig da, wird nichts geladen. Fortschritt
    /// und Abbrechen laufen über die Aufgabe ([`crate::task`]).
    pub async fn download_apk(&self) -> Result<PathBuf> {
        let manifest = self.latest.lock().await.clone().ok_or_else(|| {
            Error::validation(crate::msg!("mobileUpdate.notChecked", "Bitte zuerst nach Updates suchen."))
        })?;
        let asset = manifest
            .android
            .as_ref()
            .filter(|_| is_newer(&manifest.version, &self.current))
            .ok_or_else(|| Error::validation(crate::msg!("mobileUpdate.noUpdate", "Es gibt keine neuere Version.")))?;
        let path = self.dir.join(format!("TRS-Launcher-{}.apk", manifest.version));
        let _guard = self.download.lock().await;
        if file_matches(&path, &asset.sha256, asset.size).await {
            return Ok(path);
        }
        fsutil::ensure_dir(&self.dir).await?;
        self.cleanup(None).await;
        let tmp = self.dir.join(format!("TRS-Launcher-{}.apk.part-{}", manifest.version, uuid::Uuid::new_v4().simple()));
        let result = match self.download_to(asset, &tmp).await {
            Ok(()) => tokio::fs::rename(&tmp, &path).await.map_err(|e| Error::io(&path, e)),
            Err(e) => Err(e),
        };
        if result.is_err() {
            let _ = tokio::fs::remove_file(&tmp).await;
        }
        result.map(|()| path)
    }

    async fn download_to(&self, asset: &Asset, tmp: &Path) -> Result<()> {
        let response = self.http.get(&asset.url).timeout(APK_TIMEOUT).send().await?.error_for_status()?;
        if response.content_length().is_some_and(|len| len != asset.size) {
            return Err(Error::download(&asset.url, "unerwartete Größe"));
        }
        let mut out = tokio::fs::File::create(tmp).await.map_err(|e| Error::io(tmp, e))?;
        let mut hasher = Sha256::new();
        let mut written = 0u64;
        let mut stream = response.bytes_stream();
        crate::task::add_total(asset.size);
        loop {
            crate::task::checkpoint().await?;
            let Some(chunk) = stream.next().await else { break };
            let chunk = chunk?;
            written += chunk.len() as u64;
            crate::task::add_done(chunk.len() as i64);
            if written > asset.size {
                return Err(Error::download(&asset.url, "unerwartete Größe"));
            }
            hasher.update(&chunk);
            out.write_all(&chunk).await.map_err(|e| Error::io(tmp, e))?;
        }
        out.flush().await.map_err(|e| Error::io(tmp, e))?;
        drop(out);
        if written != asset.size {
            return Err(Error::download(&asset.url, "unerwartete Größe"));
        }
        let digest: String = hasher.finalize().iter().map(|b| format!("{b:02x}")).collect();
        if !digest.eq_ignore_ascii_case(&asset.sha256) {
            return Err(Error::download(&asset.url, "Prüfsumme stimmt nicht"));
        }
        Ok(())
    }

    /// Löscht alte APKs und abgebrochene Downloads (bis auf `keep`).
    pub async fn cleanup(&self, keep: Option<&Path>) {
        let Ok(mut entries) = tokio::fs::read_dir(&self.dir).await else { return };
        while let Ok(Some(entry)) = entries.next_entry().await {
            let path = entry.path();
            if keep == Some(path.as_path()) || !entry.file_type().await.is_ok_and(|t| t.is_file()) {
                continue;
            }
            let _ = tokio::fs::remove_file(&path).await;
        }
    }

    /// Liegt `path` im Download-Ordner dieses Kanals? (Schutz, bevor die App
    /// eine Datei an den Installer gibt.)
    pub fn owns(&self, path: &Path) -> bool {
        let Ok(dir) = std::fs::canonicalize(&self.dir) else { return false };
        std::fs::canonicalize(path).is_ok_and(|p| p.starts_with(&dir) && p.extension().is_some_and(|e| e == "apk"))
    }
}

/// Prüft Signatur (`file:mobile.json` im signierten Kommentar) und Inhalt:
/// gültige Version, Notizen begrenzt, Dateien nur per HTTPS aus den eigenen
/// Releases mit SHA-256 und plausibler Größe.
pub fn verify_manifest(public_key: &str, prefix: &str, bytes: &[u8], signature: &[u8]) -> Result<Manifest> {
    verify_signature(public_key, bytes, signature, MANIFEST_FILE)?;
    let unreadable = || Error::validation(crate::msg!("mobileUpdate.manifestUnreadable", "Update-Manifest unlesbar"));
    let mut manifest: Manifest = serde_json::from_slice(bytes).map_err(|_| unreadable())?;
    if !is_valid_version(&manifest.version) {
        return Err(unreadable());
    }
    if manifest.notes.chars().count() > MAX_NOTES_CHARS {
        manifest.notes = manifest.notes.chars().take(MAX_NOTES_CHARS).collect();
    }
    if manifest.pub_date.as_ref().is_some_and(|d| d.len() > 64) {
        manifest.pub_date = None;
    }
    let asset_ok = |a: &Asset| {
        a.url.len() <= MAX_URL_LEN
            && a.url.starts_with(prefix)
            && url::Url::parse(&a.url).is_ok()
            && a.sha256.len() == 64
            && a.sha256.bytes().all(|b| b.is_ascii_hexdigit())
            && a.size > 0
            && a.size <= MAX_APK_BYTES
    };
    manifest.android = manifest.android.filter(asset_ok);
    manifest.ios = manifest.ios.filter(|i| asset_ok(&i.file));
    if let Some(ios) = manifest.ios.as_mut() {
        ios.altstore = ios.altstore.take().filter(|s| s.len() <= MAX_URL_LEN && s.starts_with(prefix));
    }
    Ok(manifest)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client_mod_update::tests::{TestKey, TestServer, sha256_hex};

    fn manifest_json(url: &str, version: &str, apk: &[u8]) -> Vec<u8> {
        serde_json::json!({
            "version": version,
            "notes": "Neu: Android",
            "pubDate": "2026-10-03T00:00:00Z",
            "android": { "url": format!("{url}TRS-Launcher-{version}.apk"), "sha256": sha256_hex(apk), "size": apk.len() },
            "ios": { "url": format!("{url}TRS-Launcher-{version}.ipa"), "sha256": "a".repeat(64), "size": 5, "altstore": format!("{url}altstore.json") },
        })
        .to_string()
        .into_bytes()
    }

    fn publish(server: &TestServer, url: &str, key: &TestKey, version: &str, apk: &[u8]) {
        let manifest = manifest_json(url, version, apk);
        server.put(SIGNATURE_FILE, key.sign(&manifest, MANIFEST_FILE));
        server.put(MANIFEST_FILE, manifest);
        server.put(&format!("TRS-Launcher-{version}.apk"), apk.to_vec());
    }

    #[test]
    fn manifest_needs_signature_and_safe_urls() {
        let key = TestKey::generate();
        let prefix = "https://github.com/theredstonee/TRS-Launcher/releases/download/";
        let data = manifest_json(&format!("{prefix}mobile/"), "1.0.0", b"apk");
        let m = verify_manifest(&key.public, prefix, &data, &key.sign(&data, MANIFEST_FILE)).unwrap();
        assert_eq!(m.version, "1.0.0");
        assert!(m.android.is_some());
        assert_eq!(m.ios.as_ref().and_then(|i| i.altstore.as_deref()), Some(format!("{prefix}mobile/altstore.json").as_str()));

        // Falsche Datei im Kommentar, fremder Schlüssel, verändert.
        assert!(verify_manifest(&key.public, prefix, &data, &key.sign(&data, "client-mod.json")).is_err());
        assert!(verify_manifest(&TestKey::generate().public, prefix, &data, &key.sign(&data, MANIFEST_FILE)).is_err());
        let mut tampered = data.clone();
        tampered[3] ^= 1;
        assert!(verify_manifest(&key.public, prefix, &tampered, &key.sign(&data, MANIFEST_FILE)).is_err());

        // Fremder Host / ohne Prüfsumme: Datei fällt weg, Manifest bleibt lesbar.
        let other = manifest_json("https://evil.example/", "1.0.0", b"apk");
        let m = verify_manifest(&key.public, prefix, &other, &key.sign(&other, MANIFEST_FILE)).unwrap();
        assert!(m.android.is_none() && m.ios.is_none());
        let bad = serde_json::json!({"version": "../1", "android": null}).to_string().into_bytes();
        assert!(verify_manifest(&key.public, prefix, &bad, &key.sign(&bad, MANIFEST_FILE)).is_err());
    }

    #[tokio::test]
    async fn check_and_download_verified_apk() {
        let key = TestKey::generate();
        let (server, url) = TestServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let updater = MobileUpdater::for_tests(dir.path().join(UPDATE_DIR), &url, &key.public, "0.17.0");

        // Ohne Prüfung kein Download.
        assert!(updater.download_apk().await.is_err());

        publish(&server, &url, &key, "0.18.0", b"apk-0.18");
        let status = updater.check(Target::Android).await.unwrap();
        assert!(status.available);
        assert_eq!(status.latest.as_deref(), Some("0.18.0"));
        assert_eq!(status.size, Some(8));
        let ios = updater.check(Target::Ios).await.unwrap();
        assert!(ios.available && ios.altstore_source.is_some());

        let path = updater.download_apk().await.unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"apk-0.18");
        assert!(updater.owns(&path));
        assert!(!updater.owns(dir.path()));
        // Schon da: kein zweiter Download.
        updater.download_apk().await.unwrap();
        assert_eq!(server.hits("TRS-Launcher-0.18.0.apk"), 1);

        // Auf dem Server ausgetauscht: abgelehnt, keine halben Dateien.
        std::fs::remove_file(&path).unwrap();
        server.put("TRS-Launcher-0.18.0.apk", b"apk-boese".to_vec());
        assert!(updater.download_apk().await.is_err());
        assert_eq!(std::fs::read_dir(dir.path().join(UPDATE_DIR)).unwrap().count(), 0);

        // Gleiche oder ältere Version: nichts zu tun.
        let same = MobileUpdater::for_tests(dir.path().join("x"), &url, &key.public, "0.18.0");
        assert!(!same.check(Target::Android).await.unwrap().available);
        assert!(same.download_apk().await.is_err());

        // Falsch signiert: Fehler.
        publish(&server, &url, &TestKey::generate(), "0.19.0", b"x");
        assert!(updater.check(Target::Android).await.is_err());
    }
}
