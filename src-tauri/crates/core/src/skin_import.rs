//! Skins in die eigene Sammlung holen: mehrere PNG-Dateien auf einmal (Dialog
//! oder Drag & Drop), per Link, per Spielername und aus anderen Launchern.
//!
//! Ablauf in zwei Schritten: Zuerst wird jede Quelle hier im Kern gelesen,
//! geprüft (PNG, 64×64/64×32, ≤ 128 KB) und **vorgemerkt** – das Webview
//! bekommt nur eine Marke, einen Namensvorschlag, das erkannte Modell und das
//! Bild als Data-URL. Erst „Übernehmen“ schickt die Marken (evtl. mit
//! geändertem Namen/Modell) zurück; die Bytes kommen dann aus dem Vormerker,
//! nie aus dem Webview. Pfade verlassen den Kern nie.
//!
//! Andere Launcher (nur Formate, die wir an echten Dateien bzw. am Quellcode
//! geprüft haben):
//! - offizieller Launcher: `.minecraft/launcher_custom_skins.json`
//!   (`customSkins.<id>.{name, skinImage: "data:image/png;base64,…", slim}`),
//! - Prism Launcher: `<daten>/skins/` (bzw. `SkinsDir=` in `prismlauncher.cfg`)
//!   mit `index.json` (`skins[].{name, model: CLASSIC|SLIM}`, Datei `<name>.png`),
//! - Modrinth App: `app.db` (SQLite, nur lesend) – Tabellen
//!   `custom_minecraft_skins` (`texture_key`, `variant`) und
//!   `custom_minecraft_skin_textures` (`texture` = PNG).
//!
//! - ATLauncher: `configs/images/skins/<uuid>.png` – die zuletzt geladenen
//!   Skins der eigenen Konten (`FileSystem.SKINS`, `AbstractAccount.updateSkin`).
//!
//! Lunar Client, Badlion, Feather, Essential, OneClient, GDLauncher, TLauncher,
//! MultiMC und die CurseForge-App legen keine lesbare Skin-Liste auf der Platte
//! ab (geprüft an echten Dateien bzw. am Quellcode) – sie werden nur als
//! „gefunden, ohne Skins“ gemeldet; dafür gibt es „per Spielername/Link“.

use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::UserError;
use crate::import::LauncherHomes;
use crate::paths::Paths;
use crate::skins::{
    self, LibrarySkinView, MAX_SKIN_BYTES, SkinVariant, clean_name, data_url, png_size, validate_skin_png,
};
use crate::{Error, Launcher, Result};

/// So viele Dateien nimmt eine Auswahl bzw. ein Drag & Drop auf einmal.
pub const MAX_IMPORT_FILES: usize = 60;
/// So viele Skins liefert die Suche in anderen Launchern höchstens.
const MAX_SCAN: usize = 200;
/// Vorgemerkte Skins (je ≤ 128 KB → höchstens ~40 MB im Speicher).
const MAX_STAGED: usize = 300;
/// So lange bleibt eine Vormerkung gültig.
const STAGE_TTL: Duration = Duration::from_secs(30 * 60);
/// `launcher_custom_skins.json` ist Base64 – mehr als das ist keine Skin-Liste.
const MAX_LAUNCHER_JSON: u64 = 32 * 1024 * 1024;

const MOJANG_NAME_LOOKUP: &str = "https://api.mojang.com/users/profiles/minecraft/";
/// Weiterleitungen beim Laden per Link (jede wird einzeln geprüft).
const MAX_REDIRECTS: usize = 3;

/// Woher ein Skin kommt – bestimmt nur Anzeige und Gruppierung im Dialog.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ImportSource {
    File,
    Url,
    Player,
    /// Offizieller Minecraft Launcher.
    Minecraft,
    Prism,
    Modrinth,
    #[serde(rename = "atlauncher")]
    AtLauncher,
}

/// Ein vorgemerkter Skin, wie ihn das Webview sieht.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportCandidate {
    /// Marke für [`Launcher::import_staged_skins`].
    pub token: String,
    /// Vorschlag (Dateiname, Spielername, Name im anderen Launcher).
    pub name: String,
    /// Erkanntes bzw. mitgeliefertes Modell.
    pub variant: SkinVariant,
    /// Textur als Data-URL (Vorschau).
    pub texture: String,
    pub source: ImportSource,
    /// Genau dieses Bild liegt schon in der Sammlung.
    pub duplicate: bool,
}

/// Eine Datei/ein Eintrag, der nicht übernommen werden konnte.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportFailure {
    pub name: String,
    pub error: String,
    pub error_info: UserError,
}

impl ImportFailure {
    fn new(name: impl Into<String>, error: &Error) -> Self {
        let info = error.to_user();
        Self { name: name.into(), error: info.message.clone(), error_info: info }
    }
}

/// Ergebnis beim Vormerken mehrerer Dateien.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportBatch {
    pub candidates: Vec<ImportCandidate>,
    pub failed: Vec<ImportFailure>,
}

/// Suche in anderen Launchern: gefundene Skins und welche Launcher überhaupt da sind.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LauncherScan {
    pub candidates: Vec<ImportCandidate>,
    /// Launcher, deren Daten auf diesem PC liegen (auch ohne Skins).
    pub found: Vec<ImportSource>,
    /// Launcher, die hier liegen, aber keine lesbare Skin-Liste speichern.
    pub without_skins: Vec<crate::import::ImportSource>,
}

/// Übernehmen einer Vormerkung – Name/Modell optional überschrieben.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportRequest {
    pub token: String,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub variant: Option<SkinVariant>,
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportReport {
    pub added: Vec<LibrarySkinView>,
    pub failed: Vec<ImportFailure>,
}

// --- Vormerker ---------------------------------------------------------------------

struct Staged {
    bytes: Vec<u8>,
    name: String,
    variant: SkinVariant,
    at: Instant,
    /// Laufende Nummer – bestimmt, welche Vormerkung bei vollem Speicher weicht.
    seq: u64,
}

/// Geprüfte Skins, die auf „Übernehmen“ warten (nur im Speicher).
#[derive(Default)]
pub struct Staging {
    items: Mutex<HashMap<String, Staged>>,
    next: std::sync::atomic::AtomicU64,
}

impl Staging {
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Staged>> {
        self.items.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn put(&self, bytes: Vec<u8>, name: String, variant: SkinVariant) -> String {
        let token = uuid::Uuid::new_v4().simple().to_string();
        let seq = self.next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let mut items = self.lock();
        items.retain(|_, s| s.at.elapsed() < STAGE_TTL);
        while items.len() >= MAX_STAGED {
            // Voll: die älteste Vormerkung fällt weg.
            let Some(oldest) = items.iter().min_by_key(|(_, s)| s.seq).map(|(k, _)| k.clone()) else { break };
            items.remove(&oldest);
        }
        items.insert(token.clone(), Staged { bytes, name, variant, at: Instant::now(), seq });
        token
    }

    fn take(&self, token: &str) -> Option<Staged> {
        self.lock().remove(token).filter(|s| s.at.elapsed() < STAGE_TTL)
    }

    fn discard(&self, tokens: &[String]) {
        let mut items = self.lock();
        for token in tokens {
            items.remove(token);
        }
    }
}

// --- Modell-Erkennung ----------------------------------------------------------------

/// Schlanke Arme (Alex) sind 3 statt 4 Pixel breit – bei 64×64-Skins bleiben
/// dadurch am rechten Arm zwei Spalten unbenutzt (oben/unten bei x 50–51,
/// Seiten bei x 54–55), am linken Arm entsprechend x 42–43 und 46–47.
/// Sind die Spalten des rechten Arms ganz durchsichtig (oder alle vier Stellen
/// einheitlich schwarz/weiß gefüllt, wie manche Editoren speichern) → schlank.
/// 64×32-Skins (vor 1.8) kennen nur das klassische Modell.
pub fn detect_variant(bytes: &[u8]) -> SkinVariant {
    if png_size(bytes) != Some((64, 64)) {
        return SkinVariant::Classic;
    }
    let Ok(image) = image::load_from_memory_with_format(bytes, image::ImageFormat::Png) else {
        return SkinVariant::Classic;
    };
    let image = image.to_rgba8();
    let area = |x0: u32, y0: u32, w: u32, h: u32, check: &dyn Fn([u8; 4]) -> bool| {
        (y0..y0 + h).all(|y| (x0..x0 + w).all(|x| check(image.get_pixel(x, y).0)))
    };
    let transparent = |p: [u8; 4]| p[3] == 0;
    let black = |p: [u8; 4]| p[3] == 255 && p[0] == 0 && p[1] == 0 && p[2] == 0;
    let white = |p: [u8; 4]| p[3] == 255 && p[0] == 255 && p[1] == 255 && p[2] == 255;
    const AREAS: [(u32, u32, u32, u32); 4] = [(50, 16, 2, 4), (54, 20, 2, 12), (42, 48, 2, 4), (46, 52, 2, 12)];
    let right_arm_empty = AREAS[..2].iter().all(|&(x, y, w, h)| area(x, y, w, h, &transparent));
    let filled = |check: &dyn Fn([u8; 4]) -> bool| AREAS.iter().all(|&(x, y, w, h)| area(x, y, w, h, check));
    if right_arm_empty || filled(&black) || filled(&white) { SkinVariant::Slim } else { SkinVariant::Classic }
}

fn sha256(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

/// Namensvorschlag aus einem Dateinamen (ohne Endung); sonst „Skin“.
fn name_from_file(path: &Path) -> String {
    path.file_stem().and_then(|s| s.to_str()).and_then(|s| clean_name(s).ok()).unwrap_or_else(|| "Skin".into())
}

/// Liest eine Skin-Datei – mit Größenprüfung vor dem Lesen.
async fn read_skin_file(path: &Path) -> Result<Vec<u8>> {
    let meta = tokio::fs::metadata(path).await.map_err(|e| Error::io(path, e))?;
    if !meta.is_file() || meta.len() > MAX_SKIN_BYTES as u64 {
        return Err(Error::validation(crate::msg!(
            "skins.fileTooLarge",
            "Die Skin-Datei ist zu groß (höchstens 128 KB)."
        )));
    }
    let bytes = tokio::fs::read(path).await.map_err(|e| Error::io(path, e))?;
    validate_skin_png(&bytes)?;
    Ok(bytes)
}

// --- Link (SSRF-Schutz) ----------------------------------------------------------------

fn url_invalid() -> Error {
    Error::validation(crate::msg!("skins.urlInvalid", "Bitte einen gültigen https-Link eingeben."))
}

fn url_not_allowed() -> Error {
    Error::validation(crate::msg!(
        "skins.urlNotAllowed",
        "Dieser Link ist nicht erlaubt – nur öffentliche https-Adressen."
    ))
}

/// Öffentliche Adresse im Internet? Private Netze, Loopback, Link-Local,
/// CGNAT, Multicast, Dokumentations- und reservierte Bereiche sind tabu –
/// so kann ein Link nicht auf Router, lokale Dienste oder das Heimnetz zeigen.
pub fn is_public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => is_public_v4(v4),
        IpAddr::V6(v6) => {
            // IPv4 in IPv6 verpackt (::ffff:a.b.c.d, 64:ff9b::/96) → die IPv4-Regeln.
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_public_v4(v4);
            }
            let seg = v6.segments();
            if seg[0] == 0x64 && seg[1] == 0xff9b && seg[2..6] == [0, 0, 0, 0] {
                let [a, b] = seg[6].to_be_bytes();
                let [c, d] = seg[7].to_be_bytes();
                return is_public_v4(Ipv4Addr::new(a, b, c, d));
            }
            !(v6.is_unspecified()
                || v6.is_loopback()
                || v6.is_multicast()
                || (seg[0] & 0xfe00) == 0xfc00 // Unique Local fc00::/7
                || (seg[0] & 0xffc0) == 0xfe80 // Link-Local fe80::/10
                || (seg[0] & 0xffc0) == 0xfec0 // Site-Local (veraltet)
                || (seg[0] == 0x2001 && seg[1] == 0x0db8) // Dokumentation
                || seg[..6] == [0, 0, 0, 0, 0, 0]) // IPv4-kompatibel (veraltet) u. Ä.
        }
    }
}

fn is_public_v4(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    !(ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_broadcast()
        || ip.is_multicast()
        || ip.is_documentation()
        || a == 0
        || (a == 100 && (64..128).contains(&b)) // CGNAT 100.64/10
        || (a == 192 && b == 0 && c == 0) // IETF 192.0.0/24
        || (a == 198 && (b == 18 || b == 19)) // Benchmark 198.18/15
        || a >= 240) // reserviert
}

/// Prüft einen eingegebenen Link: nur `https`, keine Zugangsdaten, Standard-Port,
/// kein `localhost`, eine IP-Adresse nur, wenn sie öffentlich ist.
pub fn check_import_url(raw: &str) -> Result<reqwest::Url> {
    let raw = raw.trim();
    if raw.is_empty() || raw.len() > 2048 || raw.chars().any(char::is_control) {
        return Err(url_invalid());
    }
    let url = reqwest::Url::parse(raw).map_err(|_| url_invalid())?;
    if url.scheme() != "https" || url.cannot_be_a_base() {
        return Err(url_invalid());
    }
    if !url.username().is_empty() || url.password().is_some() || url.port().is_some_and(|p| p != 443) {
        return Err(url_not_allowed());
    }
    match url.host() {
        Some(url::Host::Domain(domain)) => {
            let domain = domain.trim_end_matches('.').to_ascii_lowercase();
            if domain.is_empty() || domain == "localhost" || domain.ends_with(".localhost") || !domain.contains('.') {
                return Err(url_not_allowed());
            }
        }
        Some(url::Host::Ipv4(ip)) if is_public_v4(ip) => {}
        Some(url::Host::Ipv6(ip)) if is_public_ip(IpAddr::V6(ip)) => {}
        _ => return Err(url_not_allowed()),
    }
    Ok(url)
}

/// Löst den Host auf und verlangt, dass JEDE Adresse öffentlich ist. Die
/// geprüften Adressen werden dem Client fest vorgegeben – ein zweites DNS
/// („DNS-Rebinding“) kann so nicht doch noch ins Heimnetz führen.
async fn resolve_public(url: &reqwest::Url) -> Result<(String, Vec<SocketAddr>)> {
    let host = url.host_str().ok_or_else(url_invalid)?.to_owned();
    let port = url.port_or_known_default().unwrap_or(443);
    let addrs: Vec<SocketAddr> = match url.host() {
        Some(url::Host::Ipv4(ip)) => vec![SocketAddr::new(IpAddr::V4(ip), port)],
        Some(url::Host::Ipv6(ip)) => vec![SocketAddr::new(IpAddr::V6(ip), port)],
        _ => tokio::time::timeout(Duration::from_secs(10), tokio::net::lookup_host((host.as_str(), port)))
            .await
            .map_err(|_| url_unreachable())?
            .map_err(|_| url_unreachable())?
            .collect(),
    };
    if addrs.is_empty() {
        return Err(url_unreachable());
    }
    if addrs.iter().any(|a| !is_public_ip(a.ip())) {
        return Err(url_not_allowed());
    }
    Ok((host, addrs))
}

fn url_unreachable() -> Error {
    Error::validation(crate::msg!("skins.urlUnreachable", "Der Link ist nicht erreichbar."))
}

fn url_not_image() -> Error {
    Error::validation(crate::msg!("skins.urlNotImage", "Unter diesem Link liegt kein PNG-Bild."))
}

/// Lädt ein Skin-PNG von einem beliebigen öffentlichen https-Link: jede
/// Weiterleitung wird wie der Link selbst geprüft, der Inhalt ist auf
/// 128 KB begrenzt und muss ein PNG sein (Content-Type + Magic-Bytes).
async fn fetch_skin_url(raw: &str) -> Result<(Vec<u8>, reqwest::Url)> {
    let mut url = check_import_url(raw)?;
    for _ in 0..=MAX_REDIRECTS {
        let (host, addrs) = resolve_public(&url).await?;
        let client = reqwest::Client::builder()
            .user_agent(crate::USER_AGENT)
            .redirect(reqwest::redirect::Policy::none())
            .no_proxy()
            .https_only(true)
            .resolve_to_addrs(&host, &addrs)
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .build()?;
        let mut response = client
            .get(url.clone())
            .header(reqwest::header::ACCEPT, "image/png")
            .send()
            .await
            .map_err(|e| {
                tracing::warn!("Skin-Link nicht erreichbar: {e}");
                url_unreachable()
            })?;
        let status = response.status();
        if status.is_redirection() {
            let location = response
                .headers()
                .get(reqwest::header::LOCATION)
                .and_then(|v| v.to_str().ok())
                .ok_or_else(url_unreachable)?;
            let next = url.join(location).map_err(|_| url_not_allowed())?;
            url = check_import_url(next.as_str())?;
            continue;
        }
        if !status.is_success() {
            return Err(Error::validation(crate::msg!(
                "skins.urlFailed",
                "Der Link hat mit HTTP {status} geantwortet.",
                status = status.as_u16()
            )));
        }
        if let Some(kind) = response.headers().get(reqwest::header::CONTENT_TYPE).and_then(|v| v.to_str().ok()) {
            let kind = kind.split(';').next().unwrap_or("").trim().to_ascii_lowercase();
            if !matches!(kind.as_str(), "image/png" | "image/x-png" | "application/octet-stream" | "binary/octet-stream" | "") {
                return Err(url_not_image());
            }
        }
        let too_large = || Error::validation(crate::msg!("skins.fileTooLarge", "Die Skin-Datei ist zu groß (höchstens 128 KB)."));
        if response.content_length().is_some_and(|len| len > MAX_SKIN_BYTES as u64) {
            return Err(too_large());
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| url_unreachable())? {
            if bytes.len() + chunk.len() > MAX_SKIN_BYTES {
                return Err(too_large());
            }
            bytes.extend_from_slice(&chunk);
        }
        if png_size(&bytes).is_none() {
            return Err(url_not_image());
        }
        validate_skin_png(&bytes)?;
        return Ok((bytes, url));
    }
    Err(Error::validation(crate::msg!("skins.urlTooManyRedirects", "Der Link leitet zu oft weiter.")))
}

/// Namensvorschlag aus dem letzten Pfadteil eines Links.
fn name_from_url(url: &reqwest::Url) -> String {
    url.path_segments()
        .and_then(|mut s| s.next_back().map(str::to_owned))
        .map(|last| last.rsplit_once('.').map_or(last.clone(), |(stem, _)| stem.to_owned()))
        .and_then(|stem| clean_name(&stem).ok())
        .unwrap_or_else(|| "Skin".into())
}

// --- Spielername -----------------------------------------------------------------------

/// Minecraft-Namen: 1–16 Zeichen aus Buchstaben, Ziffern und `_`.
pub fn is_player_name(name: &str) -> bool {
    (1..=16).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

#[derive(Deserialize)]
struct NameLookup {
    id: String,
    name: String,
}

// --- Andere Launcher -----------------------------------------------------------------------

/// Ein Skin, den ein anderer Launcher gespeichert hat.
#[derive(Debug, Clone)]
pub(crate) struct FoundSkin {
    pub source: ImportSource,
    pub name: String,
    pub variant: SkinVariant,
    pub bytes: Vec<u8>,
}

/// PNG aus einer Data-URL (`data:image/png;base64,…`) bzw. reinem Base64.
fn decode_png_data(value: &str) -> Option<Vec<u8>> {
    let payload = match value.split_once(',') {
        Some((head, rest)) if head.starts_with("data:") => {
            if !head.ends_with(";base64") {
                return None;
            }
            rest
        }
        _ => value,
    };
    // Obergrenze vor dem Dekodieren (Base64 ≈ 4/3 der Bytes).
    if payload.len() > MAX_SKIN_BYTES * 4 / 3 + 8 {
        return None;
    }
    let bytes = STANDARD.decode(payload.trim()).ok()?;
    validate_skin_png(&bytes).ok()?;
    Some(bytes)
}

/// Offizieller Launcher: `launcher_custom_skins.json`.
pub(crate) fn parse_official_skins(json: &str) -> Vec<FoundSkin> {
    let Ok(root) = serde_json::from_str::<serde_json::Value>(json) else { return Vec::new() };
    let Some(map) = root.get("customSkins").and_then(|v| v.as_object()) else { return Vec::new() };
    let mut entries: Vec<&serde_json::Value> = map.values().filter(|v| v.is_object()).collect();
    // Älteste zuerst, wie im Launcher.
    entries.sort_by_key(|v| v.get("created").and_then(|c| c.as_str()).unwrap_or("").to_owned());
    entries
        .into_iter()
        .filter_map(|entry| {
            let bytes = decode_png_data(entry.get("skinImage")?.as_str()?)?;
            let slim = entry.get("slim").and_then(|s| s.as_bool()).unwrap_or(false);
            let name = entry
                .get("name")
                .and_then(|n| n.as_str())
                .and_then(|n| clean_name(n).ok())
                .unwrap_or_else(|| "Minecraft Launcher".into());
            Some(FoundSkin {
                source: ImportSource::Minecraft,
                name,
                variant: if slim { SkinVariant::Slim } else { SkinVariant::Classic },
                bytes,
            })
        })
        .collect()
}

fn scan_official(minecraft: &Path) -> Vec<FoundSkin> {
    let file = minecraft.join("launcher_custom_skins.json");
    let Ok(meta) = std::fs::metadata(&file) else { return Vec::new() };
    if !meta.is_file() || meta.len() > MAX_LAUNCHER_JSON {
        return Vec::new();
    }
    std::fs::read_to_string(&file).map(|text| parse_official_skins(&text)).unwrap_or_default()
}

/// Ein Dateiname (ohne Pfad) aus fremden Daten – nichts, was aus dem Ordner führt.
fn safe_file_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 200
        && name != "."
        && name != ".."
        && !name.contains(['/', '\\', ':', '\0'])
        && !name.chars().any(char::is_control)
}

/// Prism: Skin-Ordner aus `SkinsDir=` in `prismlauncher.cfg` (Standard `skins`).
fn prism_skins_dir(data_dir: &Path) -> PathBuf {
    let configured = std::fs::read_to_string(data_dir.join("prismlauncher.cfg")).ok().and_then(|text| {
        text.lines()
            .find_map(|l| l.trim().strip_prefix("SkinsDir="))
            .map(|v| v.trim().trim_matches('"').to_owned())
            .filter(|v| !v.is_empty() && !v.chars().any(char::is_control))
    });
    match configured {
        Some(dir) if Path::new(&dir).is_absolute() => PathBuf::from(dir),
        Some(dir) if !dir.split(['/', '\\']).any(|s| s == "..") => data_dir.join(dir),
        _ => data_dir.join("skins"),
    }
}

fn read_small_png(path: &Path) -> Option<Vec<u8>> {
    let meta = std::fs::metadata(path).ok()?;
    if !meta.is_file() || meta.len() > MAX_SKIN_BYTES as u64 {
        return None;
    }
    let mut bytes = Vec::with_capacity(meta.len() as usize);
    std::fs::File::open(path).ok()?.take(MAX_SKIN_BYTES as u64 + 1).read_to_end(&mut bytes).ok()?;
    validate_skin_png(&bytes).ok()?;
    Some(bytes)
}

/// Prism Launcher: `index.json` (`skins[].{name, model}`) + `<name>.png`;
/// PNGs ohne Eintrag im Index zeigt Prism auch – mit erkanntem Modell.
pub(crate) fn scan_prism(skins_dir: &Path) -> Vec<FoundSkin> {
    let mut out = Vec::new();
    let mut listed = HashSet::new();
    if let Ok(text) = std::fs::read_to_string(skins_dir.join("index.json"))
        && let Ok(root) = serde_json::from_str::<serde_json::Value>(&text)
        && let Some(skins) = root.get("skins").and_then(|s| s.as_array())
    {
        for entry in skins.iter().take(MAX_SCAN) {
            let Some(name) = entry.get("name").and_then(|n| n.as_str()) else { continue };
            if !safe_file_name(name) {
                continue;
            }
            let file = format!("{name}.png");
            listed.insert(file.to_ascii_lowercase());
            let Some(bytes) = read_small_png(&skins_dir.join(&file)) else { continue };
            let slim = entry.get("model").and_then(|m| m.as_str()).is_some_and(|m| m.eq_ignore_ascii_case("SLIM"));
            out.push(FoundSkin {
                source: ImportSource::Prism,
                name: clean_name(name).unwrap_or_else(|_| "Prism".into()),
                variant: if slim { SkinVariant::Slim } else { SkinVariant::Classic },
                bytes,
            });
        }
    }
    let Ok(entries) = std::fs::read_dir(skins_dir) else { return out };
    let mut extra: Vec<PathBuf> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_ascii_lowercase();
            name.ends_with(".png") && !listed.contains(&name)
        })
        .take(MAX_SCAN)
        .collect();
    extra.sort();
    for path in extra {
        let Some(bytes) = read_small_png(&path) else { continue };
        out.push(FoundSkin { source: ImportSource::Prism, name: name_from_file(&path), variant: detect_variant(&bytes), bytes });
    }
    out
}

/// Modrinth App: Skins in `app.db` (nur lesend geöffnet).
pub(crate) fn scan_modrinth(app_dir: &Path) -> Vec<FoundSkin> {
    let Ok(db) = rusqlite::Connection::open_with_flags(
        app_dir.join("app.db"),
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    ) else {
        return Vec::new();
    };
    let _ = db.busy_timeout(Duration::from_secs(2));
    let sql = "SELECT s.variant, t.texture FROM custom_minecraft_skins s \
               JOIN custom_minecraft_skin_textures t ON t.texture_key = s.texture_key \
               ORDER BY s.display_order ASC, s.rowid ASC LIMIT ?1";
    let Ok(mut stmt) = db.prepare(sql) else { return Vec::new() };
    let rows = stmt.query_map([MAX_SCAN as i64], |row| {
        let variant: String = row.get(0)?;
        let bytes = match row.get_ref(1)? {
            rusqlite::types::ValueRef::Blob(b) if b.len() <= MAX_SKIN_BYTES => Some(b.to_vec()),
            rusqlite::types::ValueRef::Text(t) => std::str::from_utf8(t).ok().and_then(decode_png_data),
            _ => None,
        };
        Ok((variant, bytes))
    });
    let Ok(rows) = rows else { return Vec::new() };
    let mut out = Vec::new();
    for (variant, bytes) in rows.flatten() {
        let Some(bytes) = bytes.filter(|b| validate_skin_png(b).is_ok()) else { continue };
        let variant = match variant.to_ascii_uppercase().as_str() {
            "SLIM" => SkinVariant::Slim,
            "CLASSIC" => SkinVariant::Classic,
            _ => detect_variant(&bytes),
        };
        out.push(FoundSkin { source: ImportSource::Modrinth, name: String::new(), variant, bytes });
    }
    out
}

/// ATLauncher: zwischengespeicherte Skins der eigenen Konten (`<uuid>.png`).
pub(crate) fn scan_atlauncher(root: &Path) -> Vec<FoundSkin> {
    let Ok(entries) = std::fs::read_dir(root.join("configs").join("images").join("skins")) else { return Vec::new() };
    let mut files: Vec<PathBuf> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x.eq_ignore_ascii_case("png")))
        // `default.png` ist der Standard-Skin, den ATLauncher selbst mitbringt.
        .filter(|p| !p.file_stem().is_some_and(|s| s.eq_ignore_ascii_case("default")))
        .take(MAX_SCAN)
        .collect();
    files.sort();
    files
        .into_iter()
        .filter_map(|path| {
            let bytes = read_small_png(&path)?;
            Some(FoundSkin { source: ImportSource::AtLauncher, name: String::new(), variant: detect_variant(&bytes), bytes })
        })
        .collect()
}

/// Launcher, die auf diesem PC liegen, aber keine Skin-Liste speichern.
pub(crate) fn launchers_without_skins(homes: &LauncherHomes) -> Vec<crate::import::ImportSource> {
    use crate::import::ImportSource as S;
    let any = |dirs: &[PathBuf]| dirs.iter().any(|d| d.is_dir());
    [
        (S::Lunar, any(&homes.lunar)),
        (S::Badlion, any(&homes.badlion)),
        (S::Feather, any(&homes.feather)),
        (S::OneClient, any(&homes.oneclient)),
        (S::GdLauncher, any(&homes.gdlauncher)),
        (S::GdLauncherCarbon, any(&homes.carbon)),
        (S::TLauncher, any(&homes.tlauncher)),
        (S::MultiMc, any(&homes.multimc)),
        (S::CurseForge, any(&homes.curseforge)),
    ]
    .into_iter()
    .filter_map(|(source, present)| present.then_some(source))
    .collect()
}

/// Alle bekannten Launcher durchsuchen. Liefert die Skins (ohne doppelte
/// Bilder) und welche Launcher überhaupt Daten auf diesem PC haben.
pub(crate) fn scan_launchers(homes: &LauncherHomes) -> (Vec<FoundSkin>, Vec<ImportSource>) {
    let mut found = Vec::new();
    let mut skins = Vec::new();
    let minecraft: Vec<&PathBuf> = homes.minecraft.iter().filter(|d| d.join("launcher_custom_skins.json").is_file()).collect();
    if !minecraft.is_empty() {
        found.push(ImportSource::Minecraft);
    }
    for dir in minecraft {
        skins.extend(scan_official(dir));
    }
    let prism: Vec<&PathBuf> = homes.prism.iter().filter(|d| d.is_dir()).collect();
    if !prism.is_empty() {
        found.push(ImportSource::Prism);
    }
    for dir in prism {
        skins.extend(scan_prism(&prism_skins_dir(dir)));
    }
    let modrinth: Vec<&PathBuf> = homes.modrinth.iter().filter(|d| d.join("app.db").is_file()).collect();
    if !modrinth.is_empty() {
        found.push(ImportSource::Modrinth);
    }
    for dir in modrinth {
        skins.extend(scan_modrinth(dir));
    }
    let atl: Vec<&PathBuf> = homes.atlauncher.iter().filter(|d| d.join("configs").is_dir()).collect();
    if !atl.is_empty() {
        found.push(ImportSource::AtLauncher);
    }
    for dir in atl {
        skins.extend(scan_atlauncher(dir));
    }

    // Gleiches Bild mehrfach (z. B. in zwei Launchern) → nur einmal anbieten.
    let mut seen = HashSet::new();
    skins.retain(|s| seen.insert(sha256(&s.bytes)));
    skins.truncate(MAX_SCAN);
    // Modrinth und ATLauncher kennen keine Namen → je Quelle durchnummerieren.
    let mut counts: HashMap<ImportSource, u32> = HashMap::new();
    for skin in skins.iter_mut().filter(|s| s.name.is_empty()) {
        let n = counts.entry(skin.source).or_default();
        *n += 1;
        let label = if skin.source == ImportSource::AtLauncher { "ATLauncher" } else { "Modrinth App" };
        skin.name = format!("{label} {n}");
    }
    (skins, found)
}

/// Prüfsummen aller Bilder in der Sammlung (für den Hinweis „schon da“).
async fn library_hashes(paths: &Paths) -> HashSet<[u8; 32]> {
    let dir = skins::skins_dir(paths);
    let mut out = HashSet::new();
    for skin in skins::read_library(paths).await.skins {
        if let Ok(bytes) = tokio::fs::read(dir.join(&skin.file)).await {
            out.insert(sha256(&bytes));
        }
    }
    out
}

impl Launcher {
    fn stage(&self, bytes: Vec<u8>, name: String, variant: SkinVariant, source: ImportSource, known: &HashSet<[u8; 32]>) -> ImportCandidate {
        let texture = data_url(&bytes);
        let duplicate = known.contains(&sha256(&bytes));
        let token = self.skin_imports.put(bytes, name.clone(), variant);
        ImportCandidate { token, name, variant, texture, source, duplicate }
    }

    /// Merkt mehrere PNG-Dateien vor (aus dem nativen Dialog bzw. Drag & Drop –
    /// die Pfade kommen nie aus dem Webview).
    pub async fn stage_skin_files(&self, files: Vec<PathBuf>) -> Result<ImportBatch> {
        if files.len() > MAX_IMPORT_FILES {
            return Err(Error::validation(crate::msg!(
                "skins.tooManyFiles",
                "Höchstens {max} Dateien auf einmal.",
                max = MAX_IMPORT_FILES
            )));
        }
        let known = library_hashes(self.paths()).await;
        let mut batch = ImportBatch::default();
        for file in files {
            let label: String = file.file_name().and_then(|n| n.to_str()).unwrap_or("?").chars().take(120).collect();
            match read_skin_file(&file).await {
                Ok(bytes) => {
                    let variant = detect_variant(&bytes);
                    batch.candidates.push(self.stage(bytes, name_from_file(&file), variant, ImportSource::File, &known));
                }
                Err(e) => batch.failed.push(ImportFailure::new(label, &e)),
            }
        }
        Ok(batch)
    }

    /// Lädt einen Skin von einem öffentlichen https-Link und merkt ihn vor.
    pub async fn stage_skin_url(&self, url: &str) -> Result<ImportCandidate> {
        let (bytes, final_url) = fetch_skin_url(url).await?;
        let variant = detect_variant(&bytes);
        let known = library_hashes(self.paths()).await;
        Ok(self.stage(bytes, name_from_url(&final_url), variant, ImportSource::Url, &known))
    }

    /// Holt den Skin eines Spielers (Name → UUID bei Mojang → Session-Server)
    /// samt Modell und merkt ihn vor.
    pub async fn stage_player_skin(&self, name: &str) -> Result<ImportCandidate> {
        let name = name.trim();
        if !is_player_name(name) {
            return Err(Error::validation(crate::msg!(
                "skins.playerNameInvalid",
                "Spielernamen haben 1–16 Zeichen: Buchstaben, Ziffern oder _."
            )));
        }
        let not_found = || {
            Error::validation(crate::msg!("skins.playerNotFound", "Es gibt keinen Spieler namens „{name}“.", name = name))
        };
        let response = self
            .http()
            .get(format!("{MOJANG_NAME_LOOKUP}{name}"))
            .timeout(Duration::from_secs(10))
            .send()
            .await?;
        let profile: NameLookup = match response.status().as_u16() {
            200 => response.json().await.map_err(|_| not_found())?,
            204 | 404 => return Err(not_found()),
            429 => {
                return Err(Error::validation(crate::msg!(
                    "skins.rateLimited",
                    "Mojang bremst gerade – bitte ein paar Minuten warten."
                )));
            }
            s => return Err(Error::download(MOJANG_NAME_LOOKUP, format!("HTTP {s}"))),
        };
        let id = skins::compact_uuid(&profile.id).ok_or_else(not_found)?;
        let display = if is_player_name(&profile.name) { profile.name } else { name.to_owned() };

        let response = self
            .http()
            .get(format!("{}{id}", skins::SESSION_PROFILE))
            .timeout(Duration::from_secs(10))
            .send()
            .await?;
        let body: serde_json::Value = match response.status().as_u16() {
            200 => response.json().await.map_err(|_| not_found())?,
            204 | 404 => return Err(not_found()),
            429 => {
                return Err(Error::validation(crate::msg!(
                    "skins.rateLimited",
                    "Mojang bremst gerade – bitte ein paar Minuten warten."
                )));
            }
            s => return Err(Error::download(skins::SESSION_PROFILE, format!("HTTP {s}"))),
        };
        let Some((url, variant)) = skins::skin_from_session_profile(&body) else {
            return Err(Error::validation(crate::msg!(
                "skins.playerDefaultSkin",
                "{name} trägt einen Standard-Skin – da gibt es nichts zu übernehmen.",
                name = display
            )));
        };
        let bytes = skins::texture_bytes(self.http(), self.paths(), &url).await?;
        validate_skin_png(&bytes)?;
        let known = library_hashes(self.paths()).await;
        Ok(self.stage(bytes, display, variant, ImportSource::Player, &known))
    }

    /// Sucht Skins in anderen Launchern auf diesem PC und merkt sie vor.
    pub async fn scan_launcher_skins(&self) -> Result<LauncherScan> {
        self.scan_launcher_skins_in(LauncherHomes::candidates()).await
    }

    pub(crate) async fn scan_launcher_skins_in(&self, homes: LauncherHomes) -> Result<LauncherScan> {
        let (skins, found, without_skins) = tokio::task::spawn_blocking(move || {
            let (skins, found) = scan_launchers(&homes);
            (skins, found, launchers_without_skins(&homes))
        })
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;
        let known = library_hashes(self.paths()).await;
        let candidates = skins.into_iter().map(|s| self.stage(s.bytes, s.name, s.variant, s.source, &known)).collect();
        Ok(LauncherScan { candidates, found, without_skins })
    }

    /// Übernimmt vorgemerkte Skins in die Sammlung (jeder für sich – ein
    /// Fehler bricht die übrigen nicht ab). Jede Aufnahme stößt den Abgleich
    /// mit dem TRS-Konto an (über `add_skin_bytes`).
    pub async fn import_staged_skins(&self, requests: Vec<ImportRequest>) -> Result<ImportReport> {
        if requests.len() > MAX_STAGED {
            return Err(Error::validation(crate::msg!(
                "skins.tooManyFiles",
                "Höchstens {max} Dateien auf einmal.",
                max = MAX_STAGED
            )));
        }
        let mut report = ImportReport::default();
        for request in requests {
            let Some(staged) = self.skin_imports.take(&request.token) else {
                let label = request.name.clone().unwrap_or_else(|| "Skin".into());
                let error = Error::validation(crate::msg!(
                    "skins.importExpired",
                    "Die Auswahl ist abgelaufen – bitte erneut hinzufügen."
                ));
                report.failed.push(ImportFailure::new(label, &error));
                continue;
            };
            let name = request.name.unwrap_or(staged.name);
            let variant = request.variant.unwrap_or(staged.variant);
            match self.add_skin_bytes(&staged.bytes, &name, variant).await {
                Ok(view) => report.added.push(view),
                Err(e) => report.failed.push(ImportFailure::new(name.chars().take(48).collect::<String>(), &e)),
            }
        }
        Ok(report)
    }

    /// Nicht mehr gebrauchte Vormerkungen verwerfen (Dialog geschlossen).
    pub fn discard_staged_skins(&self, tokens: &[String]) {
        self.skin_imports.discard(tokens);
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;

    /// Echtes 64×64-PNG; `slim` lässt die freien Spalten des Alex-Modells durchsichtig.
    fn skin_png(width: u32, height: u32, slim: bool) -> Vec<u8> {
        let mut image = image::RgbaImage::from_pixel(width, height, image::Rgba([120, 80, 60, 255]));
        if slim && height == 64 {
            for &(x0, y0, w, h) in &[(50u32, 16u32, 2u32, 4u32), (54, 20, 2, 12), (42, 48, 2, 4), (46, 52, 2, 12)] {
                for y in y0..y0 + h {
                    for x in x0..x0 + w {
                        image.put_pixel(x, y, image::Rgba([0, 0, 0, 0]));
                    }
                }
            }
        }
        let mut out = std::io::Cursor::new(Vec::new());
        image.write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    }

    #[test]
    fn detects_slim_arms() {
        assert_eq!(detect_variant(&skin_png(64, 64, false)), SkinVariant::Classic);
        assert_eq!(detect_variant(&skin_png(64, 64, true)), SkinVariant::Slim);
        // Alte 64×32-Skins kennen nur das klassische Modell.
        assert_eq!(detect_variant(&skin_png(64, 32, false)), SkinVariant::Classic);
        assert_eq!(detect_variant(b"kein png"), SkinVariant::Classic);

        // Einheitlich schwarz gefüllte Lücken (manche Editoren) zählen auch als schlank.
        let mut image = image::load_from_memory(&skin_png(64, 64, false)).unwrap().to_rgba8();
        for &(x0, y0, w, h) in &[(50u32, 16u32, 2u32, 4u32), (54, 20, 2, 12), (42, 48, 2, 4), (46, 52, 2, 12)] {
            for y in y0..y0 + h {
                for x in x0..x0 + w {
                    image.put_pixel(x, y, image::Rgba([0, 0, 0, 255]));
                }
            }
        }
        let mut out = std::io::Cursor::new(Vec::new());
        image.write_to(&mut out, image::ImageFormat::Png).unwrap();
        assert_eq!(detect_variant(&out.into_inner()), SkinVariant::Slim);
    }

    #[test]
    fn url_guard_allows_only_public_https() {
        assert!(check_import_url("https://example.com/skins/winter.png").is_ok());
        assert!(check_import_url("  https://example.com/a.png  ").is_ok());
        assert!(check_import_url("https://8.8.8.8/a.png").is_ok());
        for bad in [
            "http://example.com/a.png",
            "file:///C:/Windows/win.ini",
            "ftp://example.com/a.png",
            "https://user:pw@example.com/a.png",
            "https://example.com:8443/a.png",
            "https://localhost/a.png",
            "https://foo.localhost/a.png",
            "https://intranet/a.png",
            "https://127.0.0.1/a.png",
            "https://10.0.0.5/a.png",
            "https://192.168.1.1/a.png",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/a.png",
            "https://[::1]/a.png",
            "https://[fd00::1]/a.png",
            "https://[fe80::1]/a.png",
            "https://[::ffff:127.0.0.1]/a.png",
            "https://0.0.0.0/a.png",
            "javascript:alert(1)",
            "",
            "https://example.com/\u{0}",
        ] {
            assert!(check_import_url(bad).is_err(), "{bad} muss abgelehnt werden");
        }
        assert!(check_import_url(&format!("https://example.com/{}", "a".repeat(3000))).is_err());
    }

    #[test]
    fn public_ip_ranges() {
        for ip in ["1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "2a00:1450:4001::200e"] {
            assert!(is_public_ip(ip.parse().unwrap()), "{ip}");
        }
        for ip in [
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.1",
            "192.168.0.1",
            "169.254.1.1",
            "100.100.0.1",
            "198.18.0.1",
            "224.0.0.1",
            "255.255.255.255",
            "240.0.0.1",
            "::1",
            "::",
            "fc00::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::1",
            "::ffff:10.0.0.1",
            "64:ff9b::7f00:1",
        ] {
            assert!(!is_public_ip(ip.parse().unwrap()), "{ip}");
        }
    }

    #[tokio::test]
    async fn fetch_refuses_local_targets_before_connecting() {
        // Keine Verbindung nötig: der Schutz greift vor dem Laden.
        assert!(fetch_skin_url("https://127.0.0.1/skin.png").await.is_err());
        assert!(fetch_skin_url("https://localhost/skin.png").await.is_err());
    }

    #[test]
    fn player_names_are_checked() {
        assert!(is_player_name("Theredstonee"));
        assert!(is_player_name("a_b_1"));
        assert!(!is_player_name(""));
        assert!(!is_player_name("einvielzulangername"));
        assert!(!is_player_name("böse"));
        assert!(!is_player_name("../x"));
        assert!(!is_player_name("a b"));
    }

    #[test]
    fn session_profile_carries_the_model() {
        let textures = serde_json::json!({ "textures": { "SKIN": {
            "url": "http://textures.minecraft.net/texture/abc123", "metadata": { "model": "slim" } } } });
        let value = STANDARD.encode(textures.to_string());
        let body = serde_json::json!({ "properties": [{ "name": "textures", "value": value }] });
        let (url, variant) = skins::skin_from_session_profile(&body).unwrap();
        assert_eq!(url, "https://textures.minecraft.net/texture/abc123");
        assert_eq!(variant, SkinVariant::Slim);
    }

    /// Aufbau wie eine echte `launcher_custom_skins.json` des offiziellen Launchers
    /// (Felder und Reihenfolge von einer echten Datei übernommen).
    fn official_fixture(png: &[u8], slim_png: &[u8]) -> String {
        serde_json::json!({
            "customSkins": {
                "skin_2": {
                    "created": "2025-07-06T10:00:00.000Z",
                    "id": "skin_2",
                    "modelImage": "data:image/png;base64,AAAA",
                    "name": "Zweiter",
                    "skinImage": format!("data:image/png;base64,{}", STANDARD.encode(png)),
                    "slim": false,
                    "textureId": "abc",
                    "updated": "2025-07-06T10:00:00.000Z"
                },
                "skin_1": {
                    "capeId": "b5e2a8a3-b36f-4869-ac4d-f1deac717bbc",
                    "created": "2025-07-05T12:10:10.645Z",
                    "id": "skin_1",
                    "modelImage": "data:image/png;base64,AAAA",
                    "name": "TJC Craftattack 12",
                    "skinImage": format!("data:image/png;base64,{}", STANDARD.encode(slim_png)),
                    "slim": true,
                    "textureId": "7e34",
                    "updated": "2025-07-05T12:10:33.579Z"
                },
                "kaputt": { "created": "2025-01-01", "name": "Kein Bild", "skinImage": "data:image/png;base64,####" },
                "zu_gross": { "created": "2025-01-02", "name": "HD", "skinImage": format!("data:image/png;base64,{}", STANDARD.encode(skin_png(128, 128, false))) }
            },
            "version": 1
        })
        .to_string()
    }

    #[test]
    fn parses_official_launcher_skins() {
        let skins = parse_official_skins(&official_fixture(&skin_png(64, 64, false), &skin_png(64, 64, true)));
        assert_eq!(skins.len(), 2, "kaputte und zu große Bilder fallen weg");
        assert_eq!(skins[0].name, "TJC Craftattack 12", "älteste zuerst");
        assert_eq!(skins[0].variant, SkinVariant::Slim);
        assert_eq!(skins[1].name, "Zweiter");
        assert_eq!(skins[1].variant, SkinVariant::Classic);
        assert!(skins.iter().all(|s| s.source == ImportSource::Minecraft));
        assert!(parse_official_skins("{}").is_empty());
        assert!(parse_official_skins("kein json").is_empty());
        assert!(parse_official_skins(r#"{"customSkins":{"x":{"skinImage":"data:text/html,<b>"}}}"#).is_empty());
    }

    #[test]
    fn scans_prism_and_modrinth() {
        let dir = tempfile::tempdir().unwrap();
        // Prism: Index mit Modell + eine Datei ohne Eintrag + ein Ausbruchsversuch.
        let prism = dir.path().join("PrismLauncher");
        let skins_dir = prism.join("skins");
        std::fs::create_dir_all(&skins_dir).unwrap();
        std::fs::write(skins_dir.join("Winter.png"), skin_png(64, 64, false)).unwrap();
        std::fs::write(skins_dir.join("lose.png"), skin_png(64, 64, true)).unwrap();
        std::fs::write(skins_dir.join("kaputt.png"), b"nope").unwrap();
        std::fs::write(
            skins_dir.join("index.json"),
            r#"{"skins":[{"name":"Winter","capeId":"","url":"","model":"SLIM"},{"name":"../../geheim","model":"CLASSIC"}]}"#,
        )
        .unwrap();
        let found = scan_prism(&prism_skins_dir(&prism));
        assert_eq!(found.iter().map(|s| (s.name.as_str(), s.variant)).collect::<Vec<_>>(), [
            ("Winter", SkinVariant::Slim),
            ("lose", SkinVariant::Slim)
        ]);

        // Eigener Skin-Ordner über prismlauncher.cfg.
        std::fs::write(prism.join("prismlauncher.cfg"), "[General]\nSkinsDir=meine-skins\n").unwrap();
        assert_eq!(prism_skins_dir(&prism), prism.join("meine-skins"));
        std::fs::write(prism.join("prismlauncher.cfg"), "SkinsDir=../../weg\n").unwrap();
        assert_eq!(prism_skins_dir(&prism), prism.join("skins"));
        std::fs::remove_file(prism.join("prismlauncher.cfg")).unwrap();

        // Modrinth App: Schema wie in einer echten app.db.
        let modrinth = dir.path().join("ModrinthApp");
        std::fs::create_dir_all(&modrinth).unwrap();
        {
            let db = rusqlite::Connection::open(modrinth.join("app.db")).unwrap();
            db.execute_batch(
                "CREATE TABLE custom_minecraft_skin_textures ( texture_key TEXT NOT NULL, texture PNG BLOB NOT NULL, PRIMARY KEY (texture_key) );
                 CREATE TABLE custom_minecraft_skins ( minecraft_user_uuid TEXT NOT NULL, texture_key TEXT NOT NULL,
                   variant TEXT NOT NULL CHECK (variant IN ('CLASSIC', 'SLIM', 'UNKNOWN')), cape_id TEXT,
                   display_order INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (minecraft_user_uuid, texture_key, variant, cape_id) );",
            )
            .unwrap();
            let classic = skin_png(64, 32, false);
            db.execute("INSERT INTO custom_minecraft_skin_textures VALUES ('a', ?1)", [&classic]).unwrap();
            db.execute("INSERT INTO custom_minecraft_skin_textures VALUES ('b', ?1)", [&skin_png(64, 64, true)]).unwrap();
            db.execute("INSERT INTO custom_minecraft_skin_textures VALUES ('c', ?1)", [b"kaputt".to_vec()]).unwrap();
            db.execute_batch(
                "INSERT INTO custom_minecraft_skins VALUES ('u1', 'a', 'CLASSIC', NULL, 1);
                 INSERT INTO custom_minecraft_skins VALUES ('u1', 'b', 'UNKNOWN', NULL, 0);
                 INSERT INTO custom_minecraft_skins VALUES ('u1', 'c', 'SLIM', NULL, 2);",
            )
            .unwrap();
        }
        let found = scan_modrinth(&modrinth);
        assert_eq!(found.iter().map(|s| s.variant).collect::<Vec<_>>(), [SkinVariant::Slim, SkinVariant::Classic]);

        // Alles zusammen: Namen für Modrinth, doppelte Bilder nur einmal.
        let mc = dir.path().join(".minecraft");
        std::fs::create_dir_all(&mc).unwrap();
        std::fs::write(mc.join("launcher_custom_skins.json"), official_fixture(&skin_png(64, 64, false), &skin_png(64, 64, true)))
            .unwrap();
        let homes = LauncherHomes {
            minecraft: vec![mc],
            prism: vec![prism, dir.path().join("gibt-es-nicht")],
            modrinth: vec![modrinth],
            ..LauncherHomes::default()
        };
        let (skins, found) = scan_launchers(&homes);
        assert_eq!(found, [ImportSource::Minecraft, ImportSource::Prism, ImportSource::Modrinth]);
        // Vanilla: 2, Prism: „Winter“ = gleiches Bild wie Vanilla „Zweiter“, „lose“ = gleiches wie
        // „TJC“ → weg; Modrinth: 64×32 neu, schlanker = doppelt → weg.
        assert_eq!(skins.iter().map(|s| s.name.as_str()).collect::<Vec<_>>(), ["TJC Craftattack 12", "Zweiter", "Modrinth App 1"]);
    }

    #[test]
    fn scans_atlauncher_skin_cache_and_reports_launchers_without_skins() {
        let dir = tempfile::tempdir().unwrap();
        // Aufbau wie ATLauncher: configs/images/skins/<uuid ohne Striche>.png (+ default.png).
        let atl = dir.path().join("ATLauncher");
        let skins_dir = atl.join("configs/images/skins");
        std::fs::create_dir_all(&skins_dir).unwrap();
        std::fs::write(skins_dir.join("0123456789abcdef0123456789abcdef.png"), skin_png(64, 64, true)).unwrap();
        std::fs::write(skins_dir.join("default.png"), skin_png(64, 64, false)).unwrap();
        std::fs::write(skins_dir.join("kaputt.png"), b"nope").unwrap();
        std::fs::write(atl.join("configs/accounts.json"), b"geheim").unwrap();
        let found = scan_atlauncher(&atl);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].variant, SkinVariant::Slim);

        let lunar = dir.path().join(".lunarclient");
        std::fs::create_dir_all(&lunar).unwrap();
        let homes = LauncherHomes { atlauncher: vec![atl], lunar: vec![lunar], ..LauncherHomes::default() };
        let (skins, found) = scan_launchers(&homes);
        assert_eq!(found, [ImportSource::AtLauncher]);
        assert_eq!(skins[0].name, "ATLauncher 1");
        assert_eq!(launchers_without_skins(&homes), [crate::import::ImportSource::Lunar]);
    }

    #[tokio::test]
    async fn stage_and_import_into_the_library() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path().join("daten"), Arc::new(|_| {})).await.unwrap();
        let files = dir.path().join("dateien");
        std::fs::create_dir_all(&files).unwrap();
        std::fs::write(files.join("Alex Style.png"), skin_png(64, 64, true)).unwrap();
        std::fs::write(files.join("steve.png"), skin_png(64, 64, false)).unwrap();
        std::fs::write(files.join("fake.png"), b"GIF89a").unwrap();

        let batch = launcher
            .stage_skin_files(vec![files.join("Alex Style.png"), files.join("steve.png"), files.join("fake.png"), files.join("fehlt.png")])
            .await
            .unwrap();
        assert_eq!(batch.candidates.len(), 2);
        assert_eq!(batch.failed.len(), 2);
        assert_eq!(batch.failed[0].name, "fake.png");
        assert_eq!(batch.failed[0].error_info.code, "skins.notPng");
        let alex = &batch.candidates[0];
        assert_eq!((alex.name.as_str(), alex.variant, alex.duplicate), ("Alex Style", SkinVariant::Slim, false));
        assert!(alex.texture.starts_with("data:image/png;base64,"));

        // Einzelimport mit geändertem Namen/Modell, Bulk mit den Vorschlägen.
        let report = launcher
            .import_staged_skins(vec![
                ImportRequest { token: alex.token.clone(), name: Some("Meine Alex".into()), variant: Some(SkinVariant::Classic) },
                ImportRequest { token: batch.candidates[1].token.clone(), name: None, variant: None },
                ImportRequest { token: "gibt-es-nicht".into(), name: None, variant: None },
            ])
            .await
            .unwrap();
        assert_eq!(report.added.iter().map(|s| (s.name.as_str(), s.variant)).collect::<Vec<_>>(), [
            ("Meine Alex", SkinVariant::Classic),
            ("steve", SkinVariant::Classic)
        ]);
        assert_eq!(report.failed[0].error_info.code, "skins.importExpired");
        assert_eq!(launcher.skin_library().await.unwrap().len(), 2);

        // Marken gelten nur einmal; dasselbe Bild erneut → als „schon da“ markiert.
        let again = launcher.import_staged_skins(vec![ImportRequest { token: alex.token.clone(), name: None, variant: None }]).await.unwrap();
        assert!(again.added.is_empty());
        let batch = launcher.stage_skin_files(vec![files.join("steve.png")]).await.unwrap();
        assert!(batch.candidates[0].duplicate);
        launcher.discard_staged_skins(&[batch.candidates[0].token.clone()]);
        assert!(launcher.skin_imports.take(&batch.candidates[0].token).is_none());

        // Zu viele Dateien auf einmal.
        let many = (0..=MAX_IMPORT_FILES).map(|i| files.join(format!("{i}.png"))).collect();
        assert!(launcher.stage_skin_files(many).await.is_err());

        // Aus anderen Launchern (Test-Ordner statt echter Launcher).
        let mc = dir.path().join(".minecraft");
        std::fs::create_dir_all(&mc).unwrap();
        std::fs::write(mc.join("launcher_custom_skins.json"), official_fixture(&skin_png(64, 64, false), &skin_png(64, 64, true))).unwrap();
        let scan = launcher
            .scan_launcher_skins_in(LauncherHomes { minecraft: vec![mc], ..LauncherHomes::default() })
            .await
            .unwrap();
        assert_eq!(scan.found, [ImportSource::Minecraft]);
        assert_eq!(scan.candidates.len(), 2);
        assert!(scan.candidates.iter().all(|c| c.duplicate), "beide Bilder liegen schon in der Sammlung");
    }

    #[test]
    fn staging_expires_and_is_bounded() {
        let staging = Staging::default();
        let first = staging.put(vec![1], "a".into(), SkinVariant::Classic);
        for _ in 0..MAX_STAGED {
            staging.put(vec![2], "b".into(), SkinVariant::Classic);
        }
        assert!(staging.lock().len() <= MAX_STAGED);
        assert!(staging.take(&first).is_none(), "die älteste Vormerkung fällt zuerst weg");
    }

    #[test]
    fn names_from_files_and_links() {
        assert_eq!(name_from_file(Path::new("C:/x/Mein Skin.png")), "Mein Skin");
        assert_eq!(name_from_file(Path::new("C:/x/\u{7}.png")), "Skin");
        assert_eq!(name_from_url(&reqwest::Url::parse("https://example.com/skins/winter.png?x=1").unwrap()), "winter");
        assert_eq!(name_from_url(&reqwest::Url::parse("https://example.com/").unwrap()), "Skin");
        assert!(safe_file_name("Winter"));
        assert!(!safe_file_name("../x"));
        assert!(!safe_file_name("a/b"));
        assert!(!safe_file_name("C:x"));
    }
}
