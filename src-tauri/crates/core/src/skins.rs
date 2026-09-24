//! Skins und Umhänge über die Minecraft-Services-API.
//!
//! Alle Aufrufe laufen hier im Kern mit dem Minecraft-Token des aktiven
//! Accounts – das Token verlässt den Rust-Prozess nie. Texturen werden
//! heruntergeladen, geprüft und als Data-URL ans Webview gegeben; dadurch
//! bleibt die CSP eng (kein `textures.minecraft.net` nötig) und WebGL kann
//! die Bilder ohne CORS-Probleme lesen.
//!
//! Änderungen am Konto (Skin hochladen, Umhang wechseln) laufen nicht direkt,
//! sondern über die Warteschlange in [`crate::skin_sync`]: Das Webview schickt
//! nur den fertigen Wunschzustand, der Kern sendet höchstens eine Anfrage je
//! Art gleichzeitig und wartet bei Mojang-429 selbst ab.

use std::path::{Path, PathBuf};
use std::time::Duration;

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
    pub(crate) fn as_str(self) -> &'static str {
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
        return Err(Error::validation(crate::msg!(
            "skins.fileTooLarge",
            "Die Skin-Datei ist zu groß (höchstens 128 KB)."
        )));
    }
    let (width, height) =
        png_size(bytes).ok_or_else(|| Error::validation(crate::msg!("skins.notPng", "Das ist keine PNG-Datei.")))?;
    if width == 64 && (height == 64 || height == 32) {
        Ok((width, height))
    } else {
        Err(Error::validation(crate::msg!(
            "skins.invalidSize",
            "Ein Skin muss 64×64 (oder 64×32 für alte Skins) Pixel groß sein."
        )))
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

// --- API -----------------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct ApiProfile {
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

pub(crate) fn is_cape_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
}

/// Wie eine Mojang-Anfrage gescheitert ist – davon hängt ab, ob die
/// Warteschlange es später erneut versucht.
#[derive(Debug)]
pub(crate) enum ApiError {
    /// HTTP 429: Mojang bremst. Enthält `Retry-After`, falls mitgeschickt.
    RateLimited(Option<Duration>),
    /// Netzwerkfehler oder 5xx – ein neuer Versuch kann klappen.
    Transient,
    /// Abgelehnt (Anmeldung, Datei, Konto) – ein neuer Versuch bringt nichts.
    Fatal(Error),
}

impl From<ApiError> for Error {
    fn from(e: ApiError) -> Self {
        match e {
            ApiError::RateLimited(_) => Error::validation(crate::msg!(
                "skins.rateLimited",
                "Mojang bremst gerade – bitte ein paar Minuten warten."
            )),
            ApiError::Transient => Error::launch(crate::msg!(
                "skins.mojangUnavailable",
                "Mojang ist gerade nicht erreichbar – bitte später erneut versuchen."
            )),
            ApiError::Fatal(e) => e,
        }
    }
}

/// Aktionsname beim Hochladen – bei 400 heißt das: Datei abgelehnt.
pub(crate) const UPLOAD_ACTION: &str = "Skin hochladen";

/// Mojang-Antworten einordnen und in Meldungen übersetzen, die dem Nutzer weiterhelfen.
pub(crate) fn classify(response: reqwest::Response, action: &str) -> std::result::Result<reqwest::Response, ApiError> {
    let status = response.status();
    if status.is_success() {
        return Ok(response);
    }
    tracing::warn!("{action} fehlgeschlagen: HTTP {status}");
    Err(match status.as_u16() {
        429 => {
            let retry_after = response
                .headers()
                .get(reqwest::header::RETRY_AFTER)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| crate::skin_sync::parse_retry_after(v, Utc::now()));
            ApiError::RateLimited(retry_after)
        }
        401 | 403 => ApiError::Fatal(Error::auth(crate::msg!(
            "skins.sessionExpired",
            "Die Anmeldung ist abgelaufen – bitte den Account neu anmelden."
        ))),
        404 => ApiError::Fatal(Error::validation(crate::msg!(
            "skins.noProfile",
            "Dieses Konto hat noch kein Minecraft-Profil."
        ))),
        400 | 422 if action == UPLOAD_ACTION => {
            ApiError::Fatal(Error::validation(crate::msg!(
                "skins.fileRejected",
                "Mojang hat die Datei abgelehnt. Ist es ein gültiger 64×64-Skin?"
            )))
        }
        400 | 422 => ApiError::Fatal(Error::validation(crate::msg!(
            "skins.changeRejected",
            "Mojang hat die Änderung abgelehnt."
        ))),
        _ => ApiError::Transient,
    })
}

/// Schickt eine Anfrage ab; Verbindungsfehler gelten als vorübergehend.
pub(crate) async fn send(
    request: reqwest::RequestBuilder,
    action: &str,
) -> std::result::Result<reqwest::Response, ApiError> {
    match request.send().await {
        Ok(response) => classify(response, action),
        Err(e) => {
            tracing::warn!("{action} fehlgeschlagen: {e}");
            Err(ApiError::Transient)
        }
    }
}

pub(crate) async fn fetch_profile(
    http: &reqwest::Client,
    base: &str,
    token: &str,
) -> std::result::Result<ApiProfile, ApiError> {
    let response = send(http.get(format!("{base}/minecraft/profile")).bearer_auth(token), "Profil laden").await?;
    response.json().await.map_err(|e| {
        tracing::warn!("Profil von Mojang unlesbar: {e}");
        ApiError::Transient
    })
}

async fn get_profile(http: &reqwest::Client, base: &str, token: &str) -> Result<ApiProfile> {
    Ok(fetch_profile(http, base, token).await?)
}

/// Skin hochladen (multipart wie der offizielle Launcher). Die Datei wird vorher geprüft.
pub(crate) fn upload_skin_request(
    http: &reqwest::Client,
    base: &str,
    token: &str,
    variant: SkinVariant,
    bytes: Vec<u8>,
) -> Result<reqwest::RequestBuilder> {
    validate_skin_png(&bytes)?;
    let part = reqwest::multipart::Part::bytes(bytes)
        .file_name("skin.png")
        .mime_str("image/png")
        .map_err(|e| Error::Internal(e.to_string()))?;
    let form = reqwest::multipart::Form::new().text("variant", variant.as_str()).part("file", part);
    Ok(http.post(format!("{base}/minecraft/profile/skins")).bearer_auth(token).multipart(form))
}

pub(crate) fn reset_skin_request(http: &reqwest::Client, base: &str, token: &str) -> reqwest::RequestBuilder {
    http.delete(format!("{base}/minecraft/profile/skins/active")).bearer_auth(token)
}

pub(crate) fn set_cape_request(
    http: &reqwest::Client,
    base: &str,
    token: &str,
    cape_id: &str,
) -> Result<reqwest::RequestBuilder> {
    if !is_cape_id(cape_id) {
        return Err(Error::validation(crate::msg!("skins.invalidCape", "Ungültiger Umhang.")));
    }
    Ok(http
        .put(format!("{base}/minecraft/profile/capes/active"))
        .bearer_auth(token)
        .json(&serde_json::json!({ "capeId": cape_id })))
}

pub(crate) fn hide_cape_request(http: &reqwest::Client, base: &str, token: &str) -> reqwest::RequestBuilder {
    http.delete(format!("{base}/minecraft/profile/capes/active")).bearer_auth(token)
}

/// Bytes des gerade getragenen Skins (für einen Modellwechsel ohne neue Datei).
pub(crate) async fn active_skin_bytes(
    http: &reqwest::Client,
    paths: &Paths,
    profile: &ApiProfile,
) -> std::result::Result<Vec<u8>, ApiError> {
    let url = profile
        .skins
        .iter()
        .filter(|s| s.state.eq_ignore_ascii_case("ACTIVE"))
        .find_map(|s| normalize_texture_url(&s.url))
        .ok_or_else(|| ApiError::Fatal(Error::validation(crate::msg!(
            "skins.noCustomSkin",
            "Dieses Konto trägt gerade keinen eigenen Skin."
        ))))?;
    texture_bytes(http, paths, &url).await.map_err(|e| match e {
        Error::Http(_) => ApiError::Transient,
        other => ApiError::Fatal(other),
    })
}

// --- Texturen-Cache ------------------------------------------------------------

fn textures_dir(paths: &Paths) -> PathBuf {
    paths.root().join("cache").join("textures")
}

/// Lädt eine Mojang-Textur (Skin oder Umhang) und legt sie im Cache ab.
/// Liefert die Bytes; ungültige Bilder werden abgelehnt.
async fn texture_bytes(http: &reqwest::Client, paths: &Paths, url: &str) -> Result<Vec<u8>> {
    if !is_texture_url(url) {
        return Err(Error::validation(crate::msg!(
            "skins.textureSourceNotAllowed",
            "Diese Texturquelle ist nicht erlaubt."
        )));
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
        return Err(Error::validation(crate::msg!("skins.invalidTexture", "Die Textur von Mojang ist ungültig.")));
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
    if cleaned.is_empty() { Err(Error::validation(crate::msg!(
        "skins.nameRequired",
        "Bitte einen Namen für den Skin eingeben."
    ))) } else { Ok(cleaned) }
}

fn is_library_id(id: &str) -> bool {
    id.len() == 12 && id.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

async fn read_library(paths: &Paths) -> LibraryFile {
    fsutil::read_json(&library_file(paths)).await.ok().flatten().unwrap_or_default()
}

// --- Köpfe anderer Spieler (Freunde, Admin-Suche) ---------------------------------

const SESSION_PROFILE: &str = "https://sessionserver.mojang.com/session/minecraft/profile/";
/// Wie lange ein Skin-Link (oder „kein Skin“) im Speicher bleibt.
const PLAYER_SKIN_TTL: Duration = Duration::from_secs(30 * 60);

type SkinCache = std::sync::Mutex<std::collections::HashMap<String, (std::time::Instant, Option<String>)>>;

fn player_skin_cache() -> &'static SkinCache {
    static CACHE: std::sync::OnceLock<SkinCache> = std::sync::OnceLock::new();
    CACHE.get_or_init(Default::default)
}

/// UUID mit oder ohne Bindestriche → 32 Hex-Zeichen klein, sonst `None`.
fn compact_uuid(uuid: &str) -> Option<String> {
    let s: String = uuid.chars().filter(|c| *c != '-').collect::<String>().to_ascii_lowercase();
    (s.len() == 32 && s.bytes().all(|b| b.is_ascii_hexdigit())).then_some(s)
}

/// Skin-Link aus der Antwort des Session-Servers (Eigenschaft `textures`, Base64-JSON).
fn skin_url_from_session_profile(body: &serde_json::Value) -> Option<String> {
    let props = body.get("properties")?.as_array()?;
    let textures = props.iter().find(|p| p.get("name").and_then(|n| n.as_str()) == Some("textures"))?;
    let decoded = STANDARD.decode(textures.get("value")?.as_str()?).ok()?;
    let json: serde_json::Value = serde_json::from_slice(&decoded).ok()?;
    normalize_texture_url(json.pointer("/textures/SKIN/url")?.as_str()?)
}

impl Launcher {
    /// Skin-Link eines beliebigen Spielers für das kleine Gesicht in Listen. `None` = Standard-Skin oder
    /// unbekannt. Öffentlicher Session-Server von Mojang, 30 Minuten im Speicher.
    pub async fn player_skin_url(&self, uuid: &str) -> Result<Option<String>> {
        let Some(id) = compact_uuid(uuid) else {
            return Err(Error::validation(crate::msg!("skins.invalidUuid", "Das ist keine gültige Spieler-ID.")));
        };
        let cached = player_skin_cache().lock().map_err(|_| Error::Internal("Skin-Cache gesperrt".into()))?.get(&id).cloned();
        if let Some((at, url)) = cached
            && at.elapsed() < PLAYER_SKIN_TTL
        {
            return Ok(url);
        }
        let response = self
            .http()
            .get(format!("{SESSION_PROFILE}{id}"))
            .timeout(Duration::from_secs(10))
            .send()
            .await?;
        let url = match response.status().as_u16() {
            200 => response.json::<serde_json::Value>().await.ok().and_then(|b| skin_url_from_session_profile(&b)),
            204 | 404 => None,
            s => return Err(Error::download(format!("{SESSION_PROFILE}{id}"), format!("HTTP {s}"))),
        };
        if let Ok(mut cache) = player_skin_cache().lock() {
            if cache.len() > 2000 {
                cache.clear();
            }
            cache.insert(id, (std::time::Instant::now(), url.clone()));
        }
        Ok(url)
    }

    /// Minecraft-Token des aktiven Accounts (wird bei Bedarf erneuert).
    pub(crate) async fn skin_session(&self) -> Result<crate::launch::Session> {
        self.accounts()
            .active_session()
            .await?
            .filter(|s| !s.demo)
            .ok_or_else(|| Error::auth(crate::msg!(
                "skins.signInFirst",
                "Bitte melde dich zuerst unter „Accounts“ an."
            )))
    }

    /// Profil des aktiven Accounts inklusive Skin- und Umhang-Texturen.
    pub async fn skin_profile(&self) -> Result<Profile> {
        let session = self.skin_session().await?;
        let profile = get_profile(self.http(), API, &session.access_token).await?;
        self.to_profile(profile).await
    }

    pub(crate) async fn to_profile(&self, profile: ApiProfile) -> Result<Profile> {
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
            return Err(Error::validation(crate::msg!(
                "skins.fileTooLarge",
                "Die Skin-Datei ist zu groß (höchstens 128 KB)."
            )));
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
            return Err(Error::validation(crate::msg!(
                "skins.libraryFull",
                "Die Skin-Bibliothek ist voll – bitte erst einen Skin löschen."
            )));
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
            .ok_or_else(|| Error::validation(crate::msg!(
                "skins.noCustomSkin",
                "Dieses Konto trägt gerade keinen eigenen Skin."
            )))?;
        let variant = SkinVariant::from_api(&active.variant);
        let url = normalize_texture_url(&active.url).unwrap_or_default();
        let bytes = texture_bytes(self.http(), self.paths(), &url).await?;
        self.add_skin_bytes(&bytes, name, variant).await
    }

    pub async fn delete_skin(&self, id: &str) -> Result<()> {
        if !is_library_id(id) {
            return Err(Error::validation(crate::msg!("skins.notFound", "Diesen Skin gibt es nicht.")));
        }
        let paths = self.paths();
        let mut library = read_library(paths).await;
        let Some(index) = library.skins.iter().position(|s| s.id == id) else {
            return Err(Error::validation(crate::msg!("skins.notFound", "Diesen Skin gibt es nicht.")));
        };
        let removed = library.skins.remove(index);
        fsutil::write_json(&library_file(paths), &library).await?;
        let _ = tokio::fs::remove_file(skins_dir(paths).join(&removed.file)).await;
        Ok(())
    }

    /// Bytes eines Bibliotheks-Skins (geprüft) – Grundlage für einen Upload.
    pub(crate) async fn library_skin_bytes(&self, id: &str) -> Result<Vec<u8>> {
        if !is_library_id(id) {
            return Err(Error::validation(crate::msg!("skins.notFound", "Diesen Skin gibt es nicht.")));
        }
        let library = read_library(self.paths()).await;
        let entry = library
            .skins
            .into_iter()
            .find(|s| s.id == id)
            .ok_or_else(|| Error::validation(crate::msg!("skins.notFound", "Diesen Skin gibt es nicht.")))?;
        let path = skins_dir(self.paths()).join(&entry.file);
        let bytes = tokio::fs::read(&path).await.map_err(|e| Error::io(&path, e))?;
        validate_skin_png(&bytes)?;
        Ok(bytes)
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn player_skin_from_session_profile() {
        use super::{compact_uuid, skin_url_from_session_profile};
        let textures = serde_json::json!({ "textures": { "SKIN": { "url": "http://textures.minecraft.net/texture/abc123" } } });
        let value = base64::engine::general_purpose::STANDARD.encode(textures.to_string());
        let body = serde_json::json!({ "id": "x", "name": "Steve", "properties": [{ "name": "textures", "value": value }] });
        assert_eq!(skin_url_from_session_profile(&body).as_deref(), Some("https://textures.minecraft.net/texture/abc123"));
        // Fremde Hosts und fehlende Skins ergeben nichts.
        let evil = serde_json::json!({ "textures": { "SKIN": { "url": "https://evil.example/x.png" } } });
        let value = base64::engine::general_purpose::STANDARD.encode(evil.to_string());
        assert!(skin_url_from_session_profile(&serde_json::json!({ "properties": [{ "name": "textures", "value": value }] })).is_none());
        assert!(skin_url_from_session_profile(&serde_json::json!({ "properties": [] })).is_none());
        assert_eq!(compact_uuid("1EEFDEDC-86BD-4905-86E0-F5B716CD280E").as_deref(), Some("1eefdedc86bd490586e0f5b716cd280e"));
        assert!(compact_uuid("../etc").is_none());
    }

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
        one_shot_server_with(status, "", body).await
    }

    /// Wie [`one_shot_server`], mit zusätzlichen Antwort-Headern (`"name: wert\r\n"`).
    async fn one_shot_server_with(
        status: &'static str,
        headers: &'static str,
        body: &'static str,
    ) -> (String, tokio::task::JoinHandle<String>) {
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
                "HTTP/1.1 {status}\r\ncontent-type: application/json\r\n{headers}content-length: {}\r\nconnection: close\r\n\r\n{body}",
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
    fn cape_ids_are_checked() {
        assert!(is_cape_id("2340c0e0-3a24-4b8a-9d54-1d1a1b1c1d1e"));
        assert!(!is_cape_id(""));
        assert!(!is_cape_id("../../etc/passwd"));
    }

    #[tokio::test]
    async fn upload_sends_multipart_with_variant_and_token() {
        let (base, server) = one_shot_server("200 OK", PROFILE_JSON).await;
        let http = reqwest::Client::new();
        let request = upload_skin_request(&http, &base, "geheimes-token", SkinVariant::Slim, png(64, 64)).unwrap();
        let profile: ApiProfile = send(request, UPLOAD_ACTION).await.unwrap().json().await.unwrap();
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
        let err = upload_skin_request(&http, "http://127.0.0.1:1", "t", SkinVariant::Classic, png(128, 128));
        assert!(matches!(err, Err(Error::Validation(_))));
    }

    #[tokio::test]
    async fn rate_limit_carries_retry_after() {
        let (base, server) = one_shot_server_with("429 Too Many Requests", "retry-after: 42\r\n", "{}").await;
        let result = fetch_profile(&reqwest::Client::new(), &base, "t").await;
        assert!(matches!(result, Err(ApiError::RateLimited(Some(d))) if d == Duration::from_secs(42)), "{result:?}");
        server.await.unwrap();

        // Ohne Header bleibt es ein 429 – die Wartezeit bestimmt dann der Backoff.
        let (base, server) = one_shot_server("429 Too Many Requests", "{}").await;
        let result = fetch_profile(&reqwest::Client::new(), &base, "t").await;
        assert!(matches!(result, Err(ApiError::RateLimited(None))), "{result:?}");
        server.await.unwrap();
    }

    #[tokio::test]
    async fn errors_are_sorted_into_retry_or_give_up() {
        for (status, retry) in
            [("500 Internal Server Error", true), ("503 Service Unavailable", true), ("400 Bad Request", false)]
        {
            let (base, server) = one_shot_server(status, "{}").await;
            let request = set_cape_request(&reqwest::Client::new(), &base, "t", "cape-1").unwrap();
            let result = send(request, "Umhang").await;
            assert_eq!(matches!(result, Err(ApiError::Transient)), retry, "{status}");
            server.await.unwrap();
        }
        // Keine Verbindung: später erneut versuchen.
        let result = send(reqwest::Client::new().get("http://127.0.0.1:1/"), "x").await;
        assert!(matches!(result, Err(ApiError::Transient)));
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
        send(set_cape_request(&reqwest::Client::new(), &base, "t", "cape-1").unwrap(), "Umhang").await.unwrap();
        let request = server.await.unwrap();
        assert!(request.starts_with("PUT /minecraft/profile/capes/active "), "{request}");
        assert!(request.contains("\"capeId\":\"cape-1\""));

        let (base, server) = one_shot_server("200 OK", "{}").await;
        send(hide_cape_request(&reqwest::Client::new(), &base, "t"), "Umhang").await.unwrap();
        assert!(server.await.unwrap().starts_with("DELETE /minecraft/profile/capes/active "));

        assert!(set_cape_request(&reqwest::Client::new(), "http://127.0.0.1:1", "t", "böse/../id").is_err());

        let (base, server) = one_shot_server("200 OK", "{}").await;
        send(reset_skin_request(&reqwest::Client::new(), &base, "t"), "Skin").await.unwrap();
        assert!(server.await.unwrap().starts_with("DELETE /minecraft/profile/skins/active "));
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
        assert!(launcher.library_skin_bytes("gibt-es-nicht").await.is_err());
    }
}
