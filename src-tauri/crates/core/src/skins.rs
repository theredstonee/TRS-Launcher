//! Skins und Umhänge über die Minecraft-Services-API.
//!
//! Alle Aufrufe laufen hier im Kern mit dem Minecraft-Token des aktiven
//! Accounts – das Token verlässt den Rust-Prozess nie. Texturen werden
//! heruntergeladen, geprüft und als Data-URL ans Webview gegeben; dadurch
//! bleibt die CSP eng (kein `textures.minecraft.net` nötig) und WebGL kann
//! die Bilder ohne CORS-Probleme lesen.

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::paths::Paths;
use crate::{Error, Launcher, Result, fsutil};

/// Basis der Minecraft-Services-API (in Tests durch einen lokalen Server ersetzt).
pub const API: &str = "https://api.minecraftservices.com";
const TEXTURE_PREFIX: &str = "https://textures.minecraft.net/texture/";

/// Skins sind 64×64-PNGs – alles darüber ist keins.
pub const MAX_SKIN_BYTES: usize = 128 * 1024;
/// So viele eigene Skins werden gespeichert.
const MAX_LIBRARY: usize = 60;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SkinVariant {
    Classic,
    Slim,
}

impl SkinVariant {
    /// So heißt die Variante in der Mojang-API (Feld `variant` bzw. Upload-Feld).
    fn as_str(self) -> &'static str {
        match self {
            Self::Classic => "classic",
            Self::Slim => "slim",
        }
    }

    fn from_api(value: &str) -> Self {
        if value.eq_ignore_ascii_case("slim") { Self::Slim } else { Self::Classic }
    }
}

// --- PNG-Prüfung ---------------------------------------------------------------

/// Maße eines PNGs aus dem IHDR-Block – ohne das Bild zu dekodieren.
fn png_size(bytes: &[u8]) -> Option<(u32, u32)> {
    if !bytes.starts_with(b"\x89PNG\r\n\x1a\n") || bytes.len() < 33 || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let width = u32::from_be_bytes(bytes[16..20].try_into().ok()?);
    let height = u32::from_be_bytes(bytes[20..24].try_into().ok()?);
    Some((width, height))
}

/// Prüft Magic-Bytes, Größe und Maße einer Skin-Textur (64×64 oder 64×32).
pub fn validate_skin_png(bytes: &[u8]) -> Result<(u32, u32)> {
    if bytes.len() > MAX_SKIN_BYTES {
        return Err(Error::validation("Die Skin-Datei ist zu groß (höchstens 128 KB)."));
    }
    let (width, height) =
        png_size(bytes).ok_or_else(|| Error::validation("Das ist keine PNG-Datei."))?;
    if width == 64 && (height == 64 || height == 32) {
        Ok((width, height))
    } else {
        Err(Error::validation("Ein Skin muss 64×64 (oder 64×32 für alte Skins) Pixel groß sein."))
    }
}

/// Textur-URLs von Mojang: nur `textures.minecraft.net`, nur der Texturpfad.
/// Mojang liefert sie teils als `http://…` – daraus wird immer HTTPS.
pub fn normalize_texture_url(url: &str) -> Option<String> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let id = rest.strip_prefix("textures.minecraft.net/texture/")?;
    let ok = !id.is_empty() && id.len() <= 128 && id.chars().all(|c| c.is_ascii_alphanumeric());
    ok.then(|| format!("{TEXTURE_PREFIX}{id}"))
}

pub fn is_texture_url(url: &str) -> bool {
    normalize_texture_url(url).as_deref() == Some(url)
}

fn data_url(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", STANDARD.encode(bytes))
}

// --- Rate-Limit ----------------------------------------------------------------

/// Bremst Profil-Änderungen aus: Mojang antwortet sonst mit 429.
#[derive(Debug)]
struct RateLimiter {
    min_gap: Duration,
    window: Duration,
    max_in_window: usize,
    recent: VecDeque<Instant>,
}

impl RateLimiter {
    const fn new(min_gap: Duration, window: Duration, max_in_window: usize) -> Self {
        Self { min_gap, window, max_in_window, recent: VecDeque::new() }
    }

    fn check(&mut self, now: Instant) -> Result<()> {
        while let Some(front) = self.recent.front() {
            if now.duration_since(*front) > self.window {
                self.recent.pop_front();
            } else {
                break;
            }
        }
        let too_fast = self.recent.back().is_some_and(|last| now.duration_since(*last) < self.min_gap);
        if too_fast || self.recent.len() >= self.max_in_window {
            return Err(Error::validation("Zu viele Änderungen kurz hintereinander – bitte einen Moment warten."));
        }
        self.recent.push_back(now);
        Ok(())
    }
}

static PROFILE_LIMIT: LazyLock<Mutex<RateLimiter>> =
    LazyLock::new(|| Mutex::new(RateLimiter::new(Duration::from_secs(2), Duration::from_secs(600), 20)));

fn check_rate_limit() -> Result<()> {
    PROFILE_LIMIT.lock().unwrap_or_else(std::sync::PoisonError::into_inner).check(Instant::now())
}

// --- API -----------------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
struct ApiProfile {
    id: String,
    name: String,
    #[serde(default)]
    skins: Vec<ApiSkin>,
    #[serde(default)]
    capes: Vec<ApiCape>,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiSkin {
    #[serde(default)]
    state: String,
    #[serde(default)]
    url: String,
    #[serde(default)]
    variant: String,
}

#[derive(Debug, Clone, Deserialize)]
struct ApiCape {
    #[serde(default)]
    id: String,
    #[serde(default)]
    state: String,
    #[serde(default)]
    url: String,
    #[serde(default)]
    alias: String,
}

/// Was das Webview über das Mojang-Profil erfährt – ohne Tokens, ohne URLs.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub name: String,
    pub uuid: String,
    pub variant: SkinVariant,
    /// Aktive Skin-Textur als Data-URL (`null`, wenn Mojang keine liefert).
    pub skin: Option<String>,
    pub capes: Vec<Cape>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Cape {
    pub id: String,
    pub name: String,
    pub active: bool,
    /// Umhang-Textur als Data-URL.
    pub texture: Option<String>,
}

fn is_cape_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
}

/// Mojang-Fehler in Meldungen übersetzen, die dem Nutzer weiterhelfen.
async fn check_response(response: reqwest::Response, action: &str) -> Result<reqwest::Response> {
    let status = response.status();
    if status.is_success() {
        return Ok(response);
    }
    tracing::warn!("{action} fehlgeschlagen: HTTP {status}");
    Err(match status.as_u16() {
        401 | 403 => Error::auth("Die Anmeldung ist abgelaufen – bitte den Account neu anmelden."),
        404 => Error::validation("Dieses Konto hat noch kein Minecraft-Profil."),
        429 => Error::validation("Mojang bremst gerade – bitte ein paar Minuten warten."),
        400 | 422 => Error::validation("Mojang hat die Datei abgelehnt. Ist es ein gültiger 64×64-Skin?"),
        _ => Error::Launch("Mojang ist gerade nicht erreichbar – bitte später erneut versuchen.".into()),
    })
}

async fn get_profile(http: &reqwest::Client, base: &str, token: &str) -> Result<ApiProfile> {
    let response = http.get(format!("{base}/minecraft/profile")).bearer_auth(token).send().await?;
    Ok(check_response(response, "Profil laden").await?.json().await?)
}

/// Lädt einen Skin hoch (multipart wie der offizielle Launcher).
async fn upload_skin(
    http: &reqwest::Client,
    base: &str,
    token: &str,
    variant: SkinVariant,
    bytes: Vec<u8>,
) -> Result<ApiProfile> {
    validate_skin_png(&bytes)?;
    let part = reqwest::multipart::Part::bytes(bytes)
        .file_name("skin.png")
        .mime_str("image/png")
        .map_err(|e| Error::Internal(e.to_string()))?;
    let form = reqwest::multipart::Form::new().text("variant", variant.as_str()).part("file", part);
    let response =
        http.post(format!("{base}/minecraft/profile/skins")).bearer_auth(token).multipart(form).send().await?;
    Ok(check_response(response, "Skin hochladen").await?.json().await?)
}

async fn reset_skin(http: &reqwest::Client, base: &str, token: &str) -> Result<()> {
    let response = http.delete(format!("{base}/minecraft/profile/skins/active")).bearer_auth(token).send().await?;
    check_response(response, "Skin zurücksetzen").await?;
    Ok(())
}

async fn set_cape(http: &reqwest::Client, base: &str, token: &str, cape_id: &str) -> Result<()> {
    if !is_cape_id(cape_id) {
        return Err(Error::validation("Ungültiger Umhang."));
    }
    let response = http
        .put(format!("{base}/minecraft/profile/capes/active"))
        .bearer_auth(token)
        .json(&serde_json::json!({ "capeId": cape_id }))
        .send()
        .await?;
    check_response(response, "Umhang setzen").await?;
    Ok(())
}

async fn hide_cape(http: &reqwest::Client, base: &str, token: &str) -> Result<()> {
    let response = http.delete(format!("{base}/minecraft/profile/capes/active")).bearer_auth(token).send().await?;
    check_response(response, "Umhang abnehmen").await?;
    Ok(())
}

// --- Texturen-Cache ------------------------------------------------------------

fn textures_dir(paths: &Paths) -> PathBuf {
    paths.root().join("cache").join("textures")
}

/// Lädt eine Mojang-Textur (Skin oder Umhang) und legt sie im Cache ab.
/// Liefert die Bytes; ungültige Bilder werden abgelehnt.
async fn texture_bytes(http: &reqwest::Client, paths: &Paths, url: &str) -> Result<Vec<u8>> {
    if !is_texture_url(url) {
        return Err(Error::validation("Diese Texturquelle ist nicht erlaubt."));
    }
    let id = &url[TEXTURE_PREFIX.len()..];
    let file = textures_dir(paths).join(format!("{id}.png"));
    if let Ok(bytes) = tokio::fs::read(&file).await
        && png_size(&bytes).is_some()
    {
        return Ok(bytes);
    }
    let response = http.get(url).send().await?.error_for_status()?;
    let bytes = response.bytes().await?.to_vec();
    if bytes.len() > MAX_SKIN_BYTES || png_size(&bytes).is_none() {
        return Err(Error::validation("Die Textur von Mojang ist ungültig."));
    }
    fsutil::write_atomic(&file, &bytes).await?;
    Ok(bytes)
}

// --- Eigene Skin-Bibliothek ----------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibrarySkin {
    pub id: String,
    pub name: String,
    pub variant: SkinVariant,
    /// Dateiname im Ordner `skins/`.
    pub file: String,
    pub added_at: DateTime<Utc>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct LibraryFile {
    #[serde(default)]
    skins: Vec<LibrarySkin>,
}

/// Bibliothekseintrag samt Textur fürs Webview.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LibrarySkinView {
    pub id: String,
    pub name: String,
    pub variant: SkinVariant,
    pub added_at: DateTime<Utc>,
    pub texture: String,
}

fn skins_dir(paths: &Paths) -> PathBuf {
    paths.root().join("skins")
}

fn library_file(paths: &Paths) -> PathBuf {
    skins_dir(paths).join("library.json")
}

/// Namen sind frei wählbar, aber kurz und ohne Steuerzeichen.
pub fn clean_name(name: &str) -> Result<String> {
    let cleaned: String = name.trim().chars().filter(|c| !c.is_control()).take(48).collect();
    if cleaned.is_empty() { Err(Error::validation("Bitte einen Namen für den Skin eingeben.")) } else { Ok(cleaned) }
}

fn is_library_id(id: &str) -> bool {
    id.len() == 12 && id.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

async fn read_library(paths: &Paths) -> LibraryFile {
    fsutil::read_json(&library_file(paths)).await.ok().flatten().unwrap_or_default()
}

impl Launcher {
    /// Minecraft-Token des aktiven Accounts (wird bei Bedarf erneuert).
    async fn skin_session(&self) -> Result<crate::launch::Session> {
        self.accounts()
            .active_session()
            .await?
            .filter(|s| !s.demo)
            .ok_or_else(|| Error::auth("Bitte melde dich zuerst unter „Accounts“ an."))
    }

    /// Profil des aktiven Accounts inklusive Skin- und Umhang-Texturen.
    pub async fn skin_profile(&self) -> Result<Profile> {
        let session = self.skin_session().await?;
        let profile = get_profile(self.http(), API, &session.access_token).await?;
        self.to_profile(profile).await
    }

    async fn to_profile(&self, profile: ApiProfile) -> Result<Profile> {
        let active = profile.skins.iter().find(|s| s.state.eq_ignore_ascii_case("ACTIVE"));
        let variant = active.map_or(SkinVariant::Classic, |s| SkinVariant::from_api(&s.variant));
        let skin_url = active.and_then(|s| normalize_texture_url(&s.url));
        let skin = match &skin_url {
            Some(url) => match texture_bytes(self.http(), self.paths(), url).await {
                Ok(bytes) => Some(data_url(&bytes)),
                Err(e) => {
                    tracing::warn!("Skin-Textur konnte nicht geladen werden: {e}");
                    None
                }
            },
            None => None,
        };
        // Den Skin-Link merkt sich auch der Account (Kopf in der Seitenleiste).
        if let Some(url) = &skin_url {
            let _ = self.accounts().set_skin_url(&profile.id, url).await;
        }

        let mut capes = Vec::new();
        for cape in profile.capes.iter().filter(|c| is_cape_id(&c.id)) {
            let texture = match normalize_texture_url(&cape.url) {
                Some(url) => texture_bytes(self.http(), self.paths(), &url).await.ok().map(|b| data_url(&b)),
                None => None,
            };
            capes.push(Cape {
                id: cape.id.clone(),
                name: clean_name(&cape.alias).unwrap_or_else(|_| "Umhang".into()),
                active: cape.state.eq_ignore_ascii_case("ACTIVE"),
                texture,
            });
        }

        Ok(Profile {
            name: profile.name.chars().filter(|c| !c.is_control()).take(32).collect(),
            uuid: profile.id.to_ascii_lowercase(),
            variant,
            skin,
            capes,
        })
    }

    /// Eigene Skin-Bibliothek (rein lokal, funktioniert auch offline).
    pub async fn skin_library(&self) -> Result<Vec<LibrarySkinView>> {
        let library = read_library(self.paths()).await;
        let mut views = Vec::new();
        for skin in library.skins {
            let path = skins_dir(self.paths()).join(&skin.file);
            let Ok(bytes) = tokio::fs::read(&path).await else { continue };
            if png_size(&bytes).is_none() {
                continue;
            }
            views.push(LibrarySkinView {
                id: skin.id,
                name: skin.name,
                variant: skin.variant,
                added_at: skin.added_at,
                texture: data_url(&bytes),
            });
        }
        views.sort_by_key(|s| std::cmp::Reverse(s.added_at));
        Ok(views)
    }

    /// Nimmt eine PNG-Datei in die Bibliothek auf (Pfad aus dem nativen Dialog).
    pub async fn add_skin_from_file(&self, file: &Path, name: &str, variant: SkinVariant) -> Result<LibrarySkinView> {
        let meta = tokio::fs::metadata(file).await.map_err(|e| Error::io(file, e))?;
        if !meta.is_file() || meta.len() as usize > MAX_SKIN_BYTES {
            return Err(Error::validation("Die Skin-Datei ist zu groß (höchstens 128 KB)."));
        }
        let bytes = tokio::fs::read(file).await.map_err(|e| Error::io(file, e))?;
        self.add_skin_bytes(&bytes, name, variant).await
    }

    /// Speichert Textur-Bytes als neuen Bibliothekseintrag.
    pub async fn add_skin_bytes(&self, bytes: &[u8], name: &str, variant: SkinVariant) -> Result<LibrarySkinView> {
        validate_skin_png(bytes)?;
        let name = clean_name(name)?;
        let paths = self.paths();
        let mut library = read_library(paths).await;
        if library.skins.len() >= MAX_LIBRARY {
            return Err(Error::validation("Die Skin-Bibliothek ist voll – bitte erst einen Skin löschen."));
        }
        let id = uuid::Uuid::new_v4().simple().to_string()[..12].to_owned();
        let file = format!("skin-{id}.png");
        fsutil::write_atomic(&skins_dir(paths).join(&file), bytes).await?;
        let entry = LibrarySkin { id, name, variant, file, added_at: Utc::now() };
        library.skins.push(entry.clone());
        fsutil::write_json(&library_file(paths), &library).await?;
        Ok(LibrarySkinView {
            id: entry.id,
            name: entry.name,
            variant: entry.variant,
            added_at: entry.added_at,
            texture: data_url(bytes),
        })
    }

    /// Legt den aktuell getragenen Skin in der Bibliothek ab.
    pub async fn save_active_skin(&self, name: &str) -> Result<LibrarySkinView> {
        let session = self.skin_session().await?;
        let profile = get_profile(self.http(), API, &session.access_token).await?;
        let active = profile
            .skins
            .iter()
            .find(|s| s.state.eq_ignore_ascii_case("ACTIVE") && normalize_texture_url(&s.url).is_some())
            .ok_or_else(|| Error::validation("Dieses Konto trägt gerade keinen eigenen Skin."))?;
        let variant = SkinVariant::from_api(&active.variant);
        let url = normalize_texture_url(&active.url).unwrap_or_default();
        let bytes = texture_bytes(self.http(), self.paths(), &url).await?;
        self.add_skin_bytes(&bytes, name, variant).await
    }

    pub async fn delete_skin(&self, id: &str) -> Result<()> {
        if !is_library_id(id) {
            return Err(Error::validation("Diesen Skin gibt es nicht."));
        }
        let paths = self.paths();
        let mut library = read_library(paths).await;
        let Some(index) = library.skins.iter().position(|s| s.id == id) else {
            return Err(Error::validation("Diesen Skin gibt es nicht."));
        };
        let removed = library.skins.remove(index);
        fsutil::write_json(&library_file(paths), &library).await?;
        let _ = tokio::fs::remove_file(skins_dir(paths).join(&removed.file)).await;
        Ok(())
    }

    /// Setzt einen Skin aus der Bibliothek auf das Mojang-Konto.
    pub async fn apply_skin(&self, id: &str, variant: Option<SkinVariant>) -> Result<Profile> {
        if !is_library_id(id) {
            return Err(Error::validation("Diesen Skin gibt es nicht."));
        }
        let library = read_library(self.paths()).await;
        let entry = library
            .skins
            .into_iter()
            .find(|s| s.id == id)
            .ok_or_else(|| Error::validation("Diesen Skin gibt es nicht."))?;
        let path = skins_dir(self.paths()).join(&entry.file);
        let bytes = tokio::fs::read(&path).await.map_err(|e| Error::io(&path, e))?;
        validate_skin_png(&bytes)?;

        let session = self.skin_session().await?;
        check_rate_limit()?;
        let profile =
            upload_skin(self.http(), API, &session.access_token, variant.unwrap_or(entry.variant), bytes).await?;
        self.to_profile(profile).await
    }

    /// Zurück zum Standard-Skin (Steve/Alex).
    pub async fn reset_skin(&self) -> Result<Profile> {
        let session = self.skin_session().await?;
        check_rate_limit()?;
        reset_skin(self.http(), API, &session.access_token).await?;
        let profile = get_profile(self.http(), API, &session.access_token).await?;
        self.to_profile(profile).await
    }

    /// Wählt einen Umhang aus (`None` = keinen tragen).
    pub async fn choose_cape(&self, cape_id: Option<&str>) -> Result<Profile> {
        let session = self.skin_session().await?;
        check_rate_limit()?;
        match cape_id {
            Some(id) => set_cape(self.http(), API, &session.access_token, id).await?,
            None => hide_cape(self.http(), API, &session.access_token).await?,
        }
        let profile = get_profile(self.http(), API, &session.access_token).await?;
        self.to_profile(profile).await
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    use super::*;

    /// Minimaler PNG-Kopf mit IHDR – reicht für alle Prüfungen.
    fn png(width: u32, height: u32) -> Vec<u8> {
        let mut bytes = b"\x89PNG\r\n\x1a\n".to_vec();
        bytes.extend_from_slice(&13u32.to_be_bytes());
        bytes.extend_from_slice(b"IHDR");
        bytes.extend_from_slice(&width.to_be_bytes());
        bytes.extend_from_slice(&height.to_be_bytes());
        bytes.extend_from_slice(&[8, 6, 0, 0, 0]);
        bytes.extend_from_slice(&[0; 8]);
        bytes
    }

    /// Nimmt genau eine HTTP-Anfrage an, antwortet mit `body` und liefert die Anfrage zurück.
    async fn one_shot_server(status: &'static str, body: &'static str) -> (String, tokio::task::JoinHandle<String>) {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = Vec::new();
            let mut buf = [0u8; 4096];
            loop {
                let n = stream.read(&mut buf).await.unwrap();
                request.extend_from_slice(&buf[..n]);
                let text = String::from_utf8_lossy(&request);
                // Header vollständig und Body gelesen? Dann antworten.
                if n == 0 || body_complete(&text) {
                    break;
                }
            }
            let response = format!(
                "HTTP/1.1 {status}\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                body.len()
            );
            stream.write_all(response.as_bytes()).await.unwrap();
            stream.flush().await.unwrap();
            String::from_utf8_lossy(&request).into_owned()
        });
        (format!("http://127.0.0.1:{port}"), handle)
    }

    fn body_complete(text: &str) -> bool {
        let Some((head, body)) = text.split_once("\r\n\r\n") else { return false };
        let length = head
            .lines()
            .find_map(|l| l.to_ascii_lowercase().strip_prefix("content-length:").map(|v| v.trim().parse().ok()))
            .flatten()
            .unwrap_or(0usize);
        body.len() >= length
    }

    const PROFILE_JSON: &str = r#"{"id":"abcdefabcdefabcdefabcdefabcdef12","name":"Theredstonee",
        "skins":[{"id":"1","state":"ACTIVE","url":"https://textures.minecraft.net/texture/abc123","variant":"SLIM"},
                 {"id":"2","state":"INACTIVE","url":"https://textures.minecraft.net/texture/old","variant":"CLASSIC"}],
        "capes":[{"id":"cape-1","state":"ACTIVE","url":"https://textures.minecraft.net/texture/capetex","alias":"Migrator"}]}"#;

    #[test]
    fn validates_png_header_and_size() {
        assert_eq!(validate_skin_png(&png(64, 64)).unwrap(), (64, 64));
        assert_eq!(validate_skin_png(&png(64, 32)).unwrap(), (64, 32));
        assert!(validate_skin_png(&png(128, 128)).is_err(), "HD-Skins lehnt Mojang ab");
        assert!(validate_skin_png(&png(32, 64)).is_err());
        assert!(validate_skin_png(b"GIF89a....").is_err(), "Magic-Bytes zählen, nicht die Endung");
        assert!(validate_skin_png(&[]).is_err());
        let mut huge = png(64, 64);
        huge.resize(MAX_SKIN_BYTES + 1, 0);
        assert!(validate_skin_png(&huge).is_err());
    }

    #[test]
    fn only_mojang_texture_urls_are_allowed() {
        assert!(is_texture_url("https://textures.minecraft.net/texture/abc123"));
        // Mojang schickt die Profil-Texturen als http – daraus wird https.
        assert_eq!(
            normalize_texture_url("http://textures.minecraft.net/texture/abc123").as_deref(),
            Some("https://textures.minecraft.net/texture/abc123")
        );
        assert!(!is_texture_url("http://textures.minecraft.net/texture/abc123"));
        assert!(normalize_texture_url("https://textures.minecraft.net/texture/../../etc").is_none());
        assert!(normalize_texture_url("https://evil.example/texture/abc123").is_none());
        assert!(normalize_texture_url("https://textures.minecraft.net/texture/").is_none());
        assert!(normalize_texture_url("https://textures.minecraft.net/texture/a?x=1").is_none());
    }

    #[test]
    fn rate_limiter_blocks_bursts_and_recovers() {
        let mut limiter = RateLimiter::new(Duration::from_secs(2), Duration::from_secs(60), 3);
        let start = Instant::now();
        assert!(limiter.check(start).is_ok());
        assert!(limiter.check(start + Duration::from_millis(500)).is_err(), "zu schnell hintereinander");
        assert!(limiter.check(start + Duration::from_secs(3)).is_ok());
        assert!(limiter.check(start + Duration::from_secs(6)).is_ok());
        assert!(limiter.check(start + Duration::from_secs(9)).is_err(), "Fenster voll");
        // Nach dem Fenster ist wieder Platz.
        assert!(limiter.check(start + Duration::from_secs(90)).is_ok());
    }

    #[test]
    fn cape_ids_are_checked() {
        assert!(is_cape_id("2340c0e0-3a24-4b8a-9d54-1d1a1b1c1d1e"));
        assert!(!is_cape_id(""));
        assert!(!is_cape_id("../../etc/passwd"));
    }

    #[tokio::test]
    async fn upload_sends_multipart_with_variant_and_token() {
        let (base, server) = one_shot_server("200 OK", PROFILE_JSON).await;
        let http = reqwest::Client::new();
        let profile = upload_skin(&http, &base, "geheimes-token", SkinVariant::Slim, png(64, 64)).await.unwrap();
        assert_eq!(profile.name, "Theredstonee");

        let request = server.await.unwrap();
        assert!(request.starts_with("POST /minecraft/profile/skins "), "{request}");
        assert!(request.to_ascii_lowercase().contains("authorization: bearer geheimes-token"));
        assert!(request.contains("multipart/form-data"));
        assert!(request.contains("name=\"variant\""));
        assert!(request.contains("slim"));
        assert!(request.contains("name=\"file\"; filename=\"skin.png\""));
        assert!(request.contains("PNG"), "die Bilddaten stecken im Body");
    }

    #[tokio::test]
    async fn upload_rejects_invalid_png_before_sending() {
        let http = reqwest::Client::new();
        // Ein Server wird gar nicht erst gebraucht – die Prüfung greift vorher.
        let err = upload_skin(&http, "http://127.0.0.1:1", "t", SkinVariant::Classic, png(128, 128)).await;
        assert!(matches!(err, Err(Error::Validation(_))));
    }

    #[tokio::test]
    async fn api_errors_become_helpful_messages() {
        for (status, expect_auth) in [("401 Unauthorized", true), ("429 Too Many Requests", false)] {
            let (base, server) = one_shot_server(status, "{}").await;
            let result = get_profile(&reqwest::Client::new(), &base, "t").await;
            let err = result.unwrap_err();
            assert_eq!(matches!(err, Error::Auth(_)), expect_auth, "{status}: {err}");
            server.await.unwrap();
        }
    }

    #[tokio::test]
    async fn cape_requests_use_the_right_verbs() {
        let (base, server) = one_shot_server("200 OK", "{}").await;
        set_cape(&reqwest::Client::new(), &base, "t", "cape-1").await.unwrap();
        let request = server.await.unwrap();
        assert!(request.starts_with("PUT /minecraft/profile/capes/active "), "{request}");
        assert!(request.contains("\"capeId\":\"cape-1\""));

        let (base, server) = one_shot_server("200 OK", "{}").await;
        hide_cape(&reqwest::Client::new(), &base, "t").await.unwrap();
        assert!(server.await.unwrap().starts_with("DELETE /minecraft/profile/capes/active "));

        assert!(set_cape(&reqwest::Client::new(), "http://127.0.0.1:1", "t", "böse/../id").await.is_err());
    }

    #[tokio::test]
    async fn library_add_list_and_delete() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();

        let added = launcher.add_skin_bytes(&png(64, 64), "  Mein Skin  ", SkinVariant::Slim).await.unwrap();
        assert_eq!(added.name, "Mein Skin");
        assert!(added.texture.starts_with("data:image/png;base64,"));

        let list = launcher.skin_library().await.unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].variant, SkinVariant::Slim);

        assert!(launcher.add_skin_bytes(&png(64, 64), "   ", SkinVariant::Classic).await.is_err());
        assert!(launcher.add_skin_bytes(b"kein png", "X", SkinVariant::Classic).await.is_err());

        assert!(launcher.delete_skin("gibt-es-nicht").await.is_err());
        launcher.delete_skin(&added.id).await.unwrap();
        assert!(launcher.skin_library().await.unwrap().is_empty());
        assert!(!dir.path().join("skins").join(format!("skin-{}.png", added.id)).exists());
    }

    #[tokio::test]
    async fn without_account_nothing_is_sent() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
        assert!(matches!(launcher.skin_profile().await, Err(Error::Auth(_))));
        assert!(matches!(launcher.reset_skin().await, Err(Error::Auth(_))));
    }
}
