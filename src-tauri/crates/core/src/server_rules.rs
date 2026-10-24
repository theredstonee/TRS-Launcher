//! „TRS Server Rules“ beim Server-Export mitinstallieren: das Server-Plugin bzw.
//! die Server-Mod, mit der ein Server TRS-Client-Module sperren oder erzwingen kann
//! (Quelltext in `server-plugin/`).
//!
//! Verteilt wird über das GitHub-Release `server-plugin`: Manifest
//! `server-plugin.json` (Version + alle Jars mit Plattform, Minecraft-Version,
//! SHA-256 und Größe), dessen Minisign-Signatur `server-plugin.json.sig` (Schlüssel
//! des Tauri-Updaters, wie beim TRS-Client-Kanal) und die Jars. Der Launcher
//!
//! 1. lädt Manifest + Signatur, wenn der Export-Dialog aufgeht (höchstens alle
//!    zehn Minuten neu, kurzer Timeout – offline gibt es die Option einfach nicht);
//! 2. prüft die Signatur und verwirft alles ohne gültige Signatur;
//! 3. wählt den Jar zur Server-Art (Fabric/Quilt, Forge, NeoForge) und genauen
//!    Minecraft-Version und lädt ihn beim Export direkt in `mods/` des Servers –
//!    geprüft über Größe und SHA-256 aus dem signierten Manifest.
//!
//! Die Config legt das Plugin beim ersten Start selbst an (`config/trs-server-rules.yml`).
//! Transport, Größenlimits und Signaturprüfung teilt sich das mit
//! [`crate::client_mod_update`].

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::client_mod_update::{self as channel, MAX_JAR_BYTES, MAX_MANIFEST_BYTES, MAX_SIGNATURE_BYTES};
use crate::instance::LoaderKind;
use crate::{Error, Result};

/// Fester Release-Kanal auf GitHub.
pub const CHANNEL_URL: &str = "https://github.com/theredstonee/TRS-Launcher/releases/download/server-plugin/";
pub const MANIFEST_FILE: &str = "server-plugin.json";
pub const SIGNATURE_FILE: &str = "server-plugin.json.sig";
/// Infoseite auf der TRS-Website (Link im Dialog).
pub const INFO_URL: &str = "https://trs-launcher.theredstonee.de/servers";
/// Jeder Jar im Kanal heißt so (`trs-server-rules-<plattform>[-<mc>]-<version>.jar`).
pub const FILE_PREFIX: &str = "trs-server-rules-";
/// So lange gilt ein geladenes Manifest.
const CACHE_FOR: Duration = Duration::from_secs(10 * 60);
const MAX_FILES: usize = 200;
const MAX_MC_LEN: usize = 32;

/// Plattform eines Jars. Unbekannte (neuere) Plattformen werden ignoriert.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Platform {
    Bukkit,
    Bungee,
    Velocity,
    Sponge,
    Fabric,
    NeoForge,
    Forge,
    #[serde(other)]
    Unknown,
}

impl Platform {
    /// Server-Mods gibt es je Minecraft-Version; Plugins laufen versionsübergreifend.
    pub fn is_mod(self) -> bool {
        matches!(self, Self::Fabric | Self::NeoForge | Self::Forge)
    }

    /// Ordner im Server, in den der Jar gehört.
    pub fn folder(self) -> &'static str {
        match self {
            Self::Bukkit | Self::Bungee | Self::Velocity => "plugins",
            _ => "mods",
        }
    }
}

/// Plattform für einen Server, den der Launcher aus einer Instanz baut. Quilt lädt
/// Fabric-Mods, Vanilla kann gar keine Mods.
pub fn platform_for(loader: LoaderKind) -> Option<Platform> {
    match loader {
        LoaderKind::Vanilla => None,
        LoaderKind::Fabric | LoaderKind::Quilt => Some(Platform::Fabric),
        LoaderKind::Forge => Some(Platform::Forge),
        LoaderKind::NeoForge => Some(Platform::NeoForge),
    }
}

/// Ein Jar aus dem Manifest.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PluginFile {
    pub platform: Platform,
    /// Genaue Minecraft-Version (nur Server-Mods).
    #[serde(default)]
    pub mc: Option<String>,
    pub file: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Manifest {
    pub version: String,
    pub files: Vec<PluginFile>,
}

/// Rohform: einzelne kaputte Einträge sollen nicht das ganze Manifest kippen.
#[derive(Deserialize)]
struct RawManifest {
    version: String,
    files: Vec<serde_json::Value>,
}

fn valid_mc(mc: &str) -> bool {
    !mc.is_empty() && mc.len() <= MAX_MC_LEN && mc.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
}

fn valid_file(f: &PluginFile) -> bool {
    f.platform != Platform::Unknown
        && channel::is_sha256(&f.sha256)
        && f.size > 0
        && f.size <= MAX_JAR_BYTES
        && f.file.starts_with(FILE_PREFIX)
        && crate::client_mod::validate_build_file(&f.file).is_ok()
        && match &f.mc {
            Some(mc) => valid_mc(mc),
            // Server-Mods ohne Minecraft-Version wären nicht zuzuordnen.
            None => !f.platform.is_mod(),
        }
}

/// Prüft die Signatur (`file:server-plugin.json` im signierten Kommentar) und liest
/// danach das Manifest. Einträge ohne gültige Prüfsumme, Größe, Dateinamen oder
/// Plattform fallen weg.
pub fn verify_manifest(public_key: &str, bytes: &[u8], signature: &[u8]) -> Result<Manifest> {
    channel::verify_signature(public_key, bytes, signature, MANIFEST_FILE)?;
    let unreadable = || Error::validation(crate::msg!("serverRules.manifestUnreadable", "Das Manifest von TRS Server Rules ist unlesbar."));
    let raw: RawManifest = serde_json::from_slice(bytes).map_err(|_| unreadable())?;
    if !channel::is_valid_version(&raw.version) {
        return Err(unreadable());
    }
    let files = raw
        .files
        .into_iter()
        .take(MAX_FILES)
        .filter_map(|v| serde_json::from_value::<PluginFile>(v).ok())
        .filter(valid_file)
        .collect();
    Ok(Manifest { version: raw.version, files })
}

/// Warum es die Option für einen Server nicht gibt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Unavailable {
    /// Vanilla-Server können keine Mods laden.
    Vanilla,
    /// Für diese Minecraft-Version gibt es (noch) keinen Jar.
    Version,
    /// Kanal nicht erreichbar oder Inhalt ungültig.
    Offline,
}

/// Den passenden Jar für einen Server wählen. Server-Mods müssen genau zur
/// Minecraft-Version passen (sie sind dafür gebaut).
pub fn select<'a>(manifest: &'a Manifest, loader: LoaderKind, game_version: &str) -> std::result::Result<&'a PluginFile, Unavailable> {
    let platform = platform_for(loader).ok_or(Unavailable::Vanilla)?;
    manifest
        .files
        .iter()
        .find(|f| f.platform == platform && (f.mc.as_deref() == Some(game_version) || (!platform.is_mod() && f.mc.is_none())))
        .ok_or(Unavailable::Version)
}

/// Mod-IDs, an denen Fabric API (bzw. QFAPI auf Quilt) zu erkennen ist.
pub fn is_fabric_api(ids: &[String]) -> bool {
    ids.iter().any(|id| matches!(id.as_str(), "fabric-api" | "fabric" | "quilted_fabric_api"))
}

/// Was der Export-Dialog zur Option anzeigt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerRulesOffer {
    pub available: bool,
    pub reason: Option<Unavailable>,
    /// Version des Plugins im Kanal.
    pub version: Option<String>,
    /// Name des Jars, der installiert würde.
    pub file: Option<String>,
    /// Die Fabric-Mod braucht Fabric API auf dem Server.
    pub needs_fabric_api: bool,
    /// Fabric API unter den Mods der Instanz (Dateiname) – muss mit auf den Server.
    pub fabric_api_file: Option<String>,
}

impl ServerRulesOffer {
    pub fn new(manifest: Option<&Manifest>, loader: LoaderKind, game_version: &str, fabric_api_file: Option<String>) -> Self {
        let selected = match manifest {
            None if loader == LoaderKind::Vanilla => Err(Unavailable::Vanilla),
            None => Err(Unavailable::Offline),
            Some(m) => select(m, loader, game_version),
        };
        let needs_fabric_api = platform_for(loader) == Some(Platform::Fabric);
        match selected {
            Ok(file) => Self {
                available: true,
                reason: None,
                version: manifest.map(|m| m.version.clone()),
                file: Some(file.file.clone()),
                needs_fabric_api,
                fabric_api_file,
            },
            Err(reason) => Self {
                available: false,
                reason: Some(reason),
                version: manifest.map(|m| m.version.clone()),
                file: None,
                needs_fabric_api,
                fabric_api_file,
            },
        }
    }
}

/// Kanal mit kurzem Zwischenspeicher im Speicher.
pub struct ServerRulesChannel {
    http: reqwest::Client,
    base_url: String,
    public_key: String,
    cache: Mutex<Option<(Instant, Manifest)>>,
}

impl ServerRulesChannel {
    pub fn new() -> Result<Self> {
        Self::build(CHANNEL_URL, channel::PUBLIC_KEY, true)
    }

    fn build(base_url: &str, public_key: &str, https_only: bool) -> Result<Self> {
        let base_url = if base_url.ends_with('/') { base_url.to_owned() } else { format!("{base_url}/") };
        Ok(Self { http: channel::channel_client(https_only)?, base_url, public_key: public_key.to_owned(), cache: Mutex::default() })
    }

    /// Für Tests: eigener Server (auch HTTP) und eigener Schlüssel.
    #[cfg(test)]
    pub(crate) fn for_tests(base_url: &str, public_key: &str) -> Self {
        Self::build(base_url, public_key, false).expect("HTTP-Client")
    }

    /// Das geprüfte Manifest (aus dem Zwischenspeicher, wenn jünger als zehn Minuten).
    /// Ein älteres, gültig signiertes Manifest ersetzt nie ein schon bekanntes neueres.
    pub async fn manifest(&self) -> Result<Manifest> {
        let mut cache = self.cache.lock().await;
        if let Some((at, manifest)) = cache.as_ref()
            && at.elapsed() < CACHE_FOR
        {
            return Ok(manifest.clone());
        }
        let fetched = self.fetch().await;
        let manifest = match (fetched, cache.take()) {
            (Ok(new), Some((_, old))) if channel::is_newer(&old.version, &new.version) => {
                tracing::warn!("TRS-Server-Rules-Kanal liefert {} – älter als bekanntes {}, ignoriert", new.version, old.version);
                old
            }
            (Ok(new), _) => new,
            (Err(e), Some((_, old))) => {
                tracing::info!("TRS-Server-Rules-Kanal nicht erreichbar, nehme bekannten Stand: {e}");
                old
            }
            (Err(e), None) => return Err(e),
        };
        *cache = Some((Instant::now(), manifest.clone()));
        Ok(manifest)
    }

    async fn fetch(&self) -> Result<Manifest> {
        let bytes = channel::fetch_limited(&self.http, &format!("{}{MANIFEST_FILE}", self.base_url), MAX_MANIFEST_BYTES).await?;
        let signature = channel::fetch_limited(&self.http, &format!("{}{SIGNATURE_FILE}", self.base_url), MAX_SIGNATURE_BYTES).await?;
        verify_manifest(&self.public_key, &bytes, &signature)
    }

    /// Lädt `file` nach `<server>/<plugins|mods>/` (Größe + SHA-256 geprüft) und gibt
    /// den Pfad zurück. Bei Fehler bleibt nichts liegen.
    pub async fn install(&self, file: &PluginFile, server_dir: &Path) -> Result<PathBuf> {
        if !valid_file(file) {
            return Err(Error::validation(crate::msg!("serverRules.manifestUnreadable", "Das Manifest von TRS Server Rules ist unlesbar.")));
        }
        let dir = server_dir.join(file.platform.folder());
        crate::fsutil::ensure_dir(&dir).await?;
        let path = dir.join(&file.file);
        let tmp = dir.join(format!("{}.part-{}", file.file, uuid::Uuid::new_v4().simple()));
        let url = format!("{}{}", self.base_url, file.file);
        let result = match channel::download_verified(&self.http, &url, &tmp, &file.sha256, file.size).await {
            Ok(()) => tokio::fs::rename(&tmp, &path).await.map_err(|e| Error::io(&path, e)),
            Err(e) => Err(e),
        };
        if result.is_err() {
            let _ = tokio::fs::remove_file(&tmp).await;
        }
        result.map(|()| path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client_mod_update::tests::{TestKey, TestServer, sha256_hex};

    fn entry(platform: &str, mc: Option<&str>, file: &str, jar: &[u8]) -> serde_json::Value {
        let mut v = serde_json::json!({ "platform": platform, "file": file, "sha256": sha256_hex(jar), "size": jar.len() });
        if let Some(mc) = mc {
            v["mc"] = mc.into();
        }
        v
    }

    fn manifest_json(version: &str, files: Vec<serde_json::Value>) -> Vec<u8> {
        serde_json::json!({ "version": version, "files": files }).to_string().into_bytes()
    }

    /// Ein Manifest wie es `scripts/publish-server-plugin.mjs` schreibt.
    fn full_manifest(version: &str) -> Vec<u8> {
        let mut files = vec![];
        for p in ["bukkit", "bungee", "velocity", "sponge"] {
            files.push(entry(p, None, &format!("trs-server-rules-{p}-{version}.jar"), p.as_bytes()));
        }
        for mc in ["1.20.1", "1.21.1", "1.21.4", "1.21.11"] {
            files.push(entry("fabric", Some(mc), &format!("trs-server-rules-fabric-{mc}-{version}.jar"), mc.as_bytes()));
        }
        for mc in ["1.21.1", "1.21.11"] {
            files.push(entry("neoforge", Some(mc), &format!("trs-server-rules-neoforge-{mc}-{version}.jar"), mc.as_bytes()));
        }
        files.push(entry("forge", Some("1.20.1"), &format!("trs-server-rules-forge-1.20.1-{version}.jar"), b"forge"));
        manifest_json(version, files)
    }

    fn verified(key: &TestKey, bytes: &[u8]) -> Result<Manifest> {
        verify_manifest(&key.public, bytes, &key.sign(bytes, MANIFEST_FILE))
    }

    #[test]
    fn signature_is_required_and_bound_to_the_file_name() {
        let key = TestKey::generate();
        let other = TestKey::generate();
        let data = full_manifest("1.0.0");
        let manifest = verified(&key, &data).unwrap();
        assert_eq!(manifest.version, "1.0.0");
        assert_eq!(manifest.files.len(), 11);

        let mut tampered = data.clone();
        tampered[3] ^= 1;
        assert!(verify_manifest(&key.public, &tampered, &key.sign(&data, MANIFEST_FILE)).is_err());
        assert!(verify_manifest(&other.public, &data, &key.sign(&data, MANIFEST_FILE)).is_err());
        // Signatur des TRS-Client-Manifests gilt hier nicht.
        assert!(verify_manifest(&key.public, &data, &key.sign(&data, "client-mod.json")).is_err());
        assert!(verify_manifest(&key.public, &data, b"").is_err());
    }

    #[test]
    fn broken_entries_are_dropped() {
        let key = TestKey::generate();
        assert!(verified(&key, br#"{"version":"../1","files":[]}"#).is_err());
        assert!(verified(&key, br#"{"files":[]}"#).is_err());
        let ok = entry("fabric", Some("1.21.11"), "trs-server-rules-fabric-1.21.11-1.0.0.jar", b"ok");
        let mut no_sha = ok.clone();
        no_sha["sha256"] = "00".into();
        let mut too_big = ok.clone();
        too_big["size"] = (MAX_JAR_BYTES + 1).into();
        let m = verified(
            &key,
            &manifest_json("1.0.0", vec![
                ok.clone(),
                no_sha,
                too_big,
                entry("fabric", None, "trs-server-rules-fabric-1.0.0.jar", b"x"),
                entry("fabric", Some("1.21/../x"), "trs-server-rules-fabric-x-1.0.0.jar", b"x"),
                entry("bukkit", None, "..\\trs-server-rules-bukkit-1.0.0.jar", b"x"),
                entry("bukkit", None, "other-plugin.jar", b"x"),
                entry("minestom", None, "trs-server-rules-minestom-1.0.0.jar", b"x"),
                serde_json::json!({"platform": "forge"}),
                entry("velocity", None, "trs-server-rules-velocity-1.0.0.jar", b"v"),
            ]),
        )
        .unwrap();
        assert_eq!(
            m.files.iter().map(|f| f.file.as_str()).collect::<Vec<_>>(),
            ["trs-server-rules-fabric-1.21.11-1.0.0.jar", "trs-server-rules-velocity-1.0.0.jar"]
        );
    }

    #[test]
    fn picks_the_jar_for_loader_and_exact_version() {
        let key = TestKey::generate();
        let m = verified(&key, &full_manifest("1.0.0")).unwrap();
        let file = |loader, mc| select(&m, loader, mc).map(|f| f.file.as_str());
        assert_eq!(file(LoaderKind::Fabric, "1.21.11"), Ok("trs-server-rules-fabric-1.21.11-1.0.0.jar"));
        assert_eq!(file(LoaderKind::Fabric, "1.21.4"), Ok("trs-server-rules-fabric-1.21.4-1.0.0.jar"));
        assert_eq!(file(LoaderKind::Quilt, "1.20.1"), Ok("trs-server-rules-fabric-1.20.1-1.0.0.jar"));
        assert_eq!(file(LoaderKind::NeoForge, "1.21.1"), Ok("trs-server-rules-neoforge-1.21.1-1.0.0.jar"));
        assert_eq!(file(LoaderKind::Forge, "1.20.1"), Ok("trs-server-rules-forge-1.20.1-1.0.0.jar"));
        // Keine Näherung: 1.21.10 ist nicht 1.21.11, Forge 1.21.1 gibt es nicht.
        assert_eq!(file(LoaderKind::Fabric, "1.21.10"), Err(Unavailable::Version));
        assert_eq!(file(LoaderKind::Fabric, "1.21"), Err(Unavailable::Version));
        assert_eq!(file(LoaderKind::Forge, "1.21.1"), Err(Unavailable::Version));
        assert_eq!(file(LoaderKind::NeoForge, "1.20.1"), Err(Unavailable::Version));
        assert_eq!(file(LoaderKind::Vanilla, "1.21.11"), Err(Unavailable::Vanilla));
        assert_eq!(Platform::Fabric.folder(), "mods");
        assert_eq!(Platform::Bukkit.folder(), "plugins");
        assert_eq!(Platform::Sponge.folder(), "mods");
    }

    #[test]
    fn offer_for_the_dialog() {
        let key = TestKey::generate();
        let m = verified(&key, &full_manifest("1.0.0")).unwrap();
        let fabric = ServerRulesOffer::new(Some(&m), LoaderKind::Fabric, "1.21.11", Some("fabric-api-0.1.jar".into()));
        assert!(fabric.available && fabric.needs_fabric_api);
        assert_eq!(fabric.file.as_deref(), Some("trs-server-rules-fabric-1.21.11-1.0.0.jar"));
        assert_eq!(fabric.version.as_deref(), Some("1.0.0"));
        let neo = ServerRulesOffer::new(Some(&m), LoaderKind::NeoForge, "1.21.11", None);
        assert!(neo.available && !neo.needs_fabric_api);
        let old = ServerRulesOffer::new(Some(&m), LoaderKind::Forge, "1.12.2", None);
        assert_eq!((old.available, old.reason), (false, Some(Unavailable::Version)));
        assert_eq!(ServerRulesOffer::new(None, LoaderKind::Fabric, "1.21.11", None).reason, Some(Unavailable::Offline));
        assert_eq!(ServerRulesOffer::new(None, LoaderKind::Vanilla, "1.21.11", None).reason, Some(Unavailable::Vanilla));
        assert!(is_fabric_api(&["lithium".into(), "fabric-api".into()]));
        assert!(!is_fabric_api(&["fabric-language-kotlin".into()]));
    }

    #[tokio::test]
    async fn downloads_the_verified_jar_into_mods() {
        let key = TestKey::generate();
        let (server, url) = TestServer::start().await;
        let jar = b"server-rules-jar".to_vec();
        let name = "trs-server-rules-fabric-1.21.11-1.0.0.jar";
        let bytes = manifest_json("1.0.0", vec![entry("fabric", Some("1.21.11"), name, &jar)]);
        server.put(SIGNATURE_FILE, key.sign(&bytes, MANIFEST_FILE));
        server.put(MANIFEST_FILE, bytes);
        server.put(name, jar.clone());

        let channel = ServerRulesChannel::for_tests(&url, &key.public);
        let manifest = channel.manifest().await.unwrap();
        // Zweiter Aufruf kommt aus dem Zwischenspeicher.
        channel.manifest().await.unwrap();
        assert_eq!(server.hits(MANIFEST_FILE), 1);

        let dir = tempfile::tempdir().unwrap();
        let file = select(&manifest, LoaderKind::Fabric, "1.21.11").unwrap();
        let path = channel.install(file, dir.path()).await.unwrap();
        assert_eq!(path, dir.path().join("mods").join(name));
        assert_eq!(std::fs::read(&path).unwrap(), jar);

        // Ausgetauschter Jar auf dem Server: abgelehnt, keine halben Dateien.
        std::fs::remove_file(&path).unwrap();
        server.put(name, b"server-rules-XXX".to_vec());
        assert!(channel.install(file, dir.path()).await.is_err());
        assert_eq!(std::fs::read_dir(dir.path().join("mods")).unwrap().count(), 0);
    }

    #[tokio::test]
    async fn bad_signature_or_offline_gives_no_manifest() {
        let key = TestKey::generate();
        let other = TestKey::generate();
        let (server, url) = TestServer::start().await;
        let bytes = full_manifest("1.0.0");
        server.put(SIGNATURE_FILE, other.sign(&bytes, MANIFEST_FILE));
        server.put(MANIFEST_FILE, bytes);
        assert!(ServerRulesChannel::for_tests(&url, &key.public).manifest().await.is_err());
        assert!(ServerRulesChannel::for_tests("http://127.0.0.1:9/", &key.public).manifest().await.is_err());
    }

    /// Prüft ein mit `scripts/publish-server-plugin.mjs --dry-run` erzeugtes Manifest
    /// gegen den echten Schlüssel:
    /// `TRS_SERVER_PLUGIN_CHANNEL_DIR=<ordner> cargo test -p trs-core published_server_plugin -- --ignored`
    #[test]
    #[ignore = "braucht ein erzeugtes server-plugin.json (TRS_SERVER_PLUGIN_CHANNEL_DIR)"]
    fn published_server_plugin_manifest_verifies_with_the_real_key() {
        let dir = PathBuf::from(std::env::var("TRS_SERVER_PLUGIN_CHANNEL_DIR").expect("TRS_SERVER_PLUGIN_CHANNEL_DIR"));
        let bytes = std::fs::read(dir.join(MANIFEST_FILE)).unwrap();
        let sig = std::fs::read(dir.join(SIGNATURE_FILE)).unwrap();
        let manifest = verify_manifest(channel::PUBLIC_KEY, &bytes, &sig).expect("Signatur");
        let raw: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(manifest.files.len(), raw["files"].as_array().unwrap().len(), "Einträge verworfen");
        println!("{} Jars, Version {} – Signatur gültig", manifest.files.len(), manifest.version);
    }
}
