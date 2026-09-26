//! Bilder im Chat: Uploads vorbereiten und prüfen, Bilder aus der API holen
//! (mit kleinem Zwischenspeicher im Arbeitsspeicher) und über das Protokoll
//! `trschat:` ans Webview geben – ohne Token in URLs.
//!
//! - **Prüfen vor dem Senden:** Format an den Magic Bytes (PNG/JPEG/WebP),
//!   Maße aus dem Kopf (höchstens 8192 px je Seite, 24 Megapixel), animiertes
//!   WebP wird abgelehnt. Große Bilder (über 5 MiB oder über 2048 px) werden
//!   hier schon verkleinert und neu kodiert – der Server würde sie ohnehin auf
//!   2048 px bringen, und neu kodiert verlässt auch keine Metadaten den Rechner.
//! - **Eigene Dateien** (Dateidialog, Drag & Drop, Einfügen) landen in einer
//!   kleinen Liste im Kern; das Webview bekommt nur eine zufällige ID, nie den
//!   Pfad. Die letzten Dateien (nur Pfade, lokal) bilden den Reiter „Uploads“.
//! - **Favoriten:** markierte Screenshots (lokal, `screenshot-favorites.json`).

use std::collections::HashMap;
use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex as StdMutex};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use super::chat::{ChatAttachment, attachment_id, report_id};
use super::{Body, Req};
use crate::clips::serve::Response;
use crate::{Error, Launcher, Result};

/// Protokoll für Chat-Bilder im Webview: `trschat://localhost/<art>/<id>`.
pub const SCHEME: &str = "trschat";

/// Größte Datei, die überhaupt gelesen wird.
pub const MAX_INPUT_BYTES: u64 = 40 * 1024 * 1024;
/// Größte Datei, die an die API geht (§18.7).
pub const MAX_UPLOAD_BYTES: usize = 5 * 1024 * 1024;
/// Größte Seite und Pixelzahl, die die API annimmt.
pub const MAX_SIDE: u32 = 8192;
pub const MAX_PIXELS: u64 = 24_000_000;
/// Darüber wird hier schon verkleinert (der Server macht es sonst selbst).
pub const TARGET_SIDE: u32 = 2048;
/// Größte Antwort beim Laden eines Bildes.
const MAX_DOWNLOAD: usize = 24 * 1024 * 1024;
/// Zwischenspeicher für geladene Bilder (Inhalte einer ID ändern sich nie).
const CACHE_BYTES: usize = 48 * 1024 * 1024;
/// So viele eigene Dateien merkt sich der Reiter „Uploads“.
const MAX_RECENT: usize = 40;
/// Eingefügte Bilder (nur im Speicher) je Sitzung.
const MAX_MEMORY_IMAGES: usize = 12;
/// Vorschaubilder für eigene Dateien.
const LOCAL_THUMB_SIDE: u32 = 360;

fn invalid(msg: crate::error::Msg) -> Error {
    Error::validation(msg)
}

// --- Format & Maße ------------------------------------------------------------------

/// Typ an den Magic Bytes – nur PNG, JPEG und WebP.
pub fn sniff(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some("image/jpeg")
    } else if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some("image/webp")
    } else {
        None
    }
}

/// Animiertes WebP (VP8X mit Animations-Flag oder ANIM-Block)?
pub fn webp_animated(bytes: &[u8]) -> bool {
    if bytes.len() < 21 || &bytes[12..16] != b"VP8X" {
        return false;
    }
    bytes[20] & 0x02 != 0 || bytes.windows(4).take(4096).any(|w| w == b"ANIM")
}

/// Maße aus dem Dateikopf (ohne das ganze Bild zu dekodieren).
pub fn dimensions(bytes: &[u8]) -> Option<(u32, u32)> {
    image::ImageReader::new(Cursor::new(bytes)).with_guessed_format().ok()?.into_dimensions().ok()
}

/// Geprüftes, ggf. verkleinertes Bild für den Upload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreparedImage {
    pub mime: &'static str,
    pub bytes: Vec<u8>,
    pub width: u32,
    pub height: u32,
}

/// Kopf prüfen: Typ, Animation, Maße. Liefert Typ und Maße.
pub fn inspect(bytes: &[u8]) -> Result<(&'static str, u32, u32)> {
    let mime = sniff(bytes)
        .ok_or_else(|| invalid(crate::msg!("chatImage.unsupported", "Nur PNG-, JPEG- und WebP-Bilder können gesendet werden.")))?;
    if mime == "image/webp" && webp_animated(bytes) {
        return Err(invalid(crate::msg!("chatImage.animated", "Animierte Bilder können nicht gesendet werden.")));
    }
    let (w, h) = dimensions(bytes)
        .filter(|(w, h)| *w > 0 && *h > 0)
        .ok_or_else(|| invalid(crate::msg!("chatImage.broken", "Das Bild ist beschädigt.")))?;
    if w > MAX_SIDE || h > MAX_SIDE || u64::from(w) * u64::from(h) > MAX_PIXELS {
        return Err(invalid(crate::msg!(
            "chatImage.tooLarge",
            "Das Bild ist zu groß (höchstens {side} Pixel je Seite).",
            side = MAX_SIDE
        )));
    }
    Ok((mime, w, h))
}

/// Prüft ein Bild und bringt es auf eine Größe, die die API annimmt.
/// Blockierend (dekodiert ggf.) – im Kern über `spawn_blocking` aufrufen.
pub fn prepare(bytes: Vec<u8>) -> Result<PreparedImage> {
    if bytes.len() as u64 > MAX_INPUT_BYTES {
        return Err(invalid(crate::msg!("chatImage.fileTooLarge", "Die Datei ist zu groß.")));
    }
    let (mime, width, height) = inspect(&bytes)?;
    if bytes.len() <= MAX_UPLOAD_BYTES && width.max(height) <= TARGET_SIDE {
        return Ok(PreparedImage { mime, bytes, width, height });
    }
    let decoded = image::load_from_memory(&bytes)
        .map_err(|_| invalid(crate::msg!("chatImage.broken", "Das Bild ist beschädigt.")))?;
    drop(bytes);
    let scaled = if width.max(height) > TARGET_SIDE {
        decoded.resize(TARGET_SIDE, TARGET_SIDE, image::imageops::FilterType::Triangle)
    } else {
        decoded
    };
    let transparent = scaled.color().has_alpha() && scaled.to_rgba8().pixels().any(|p| p.0[3] < 255);
    for quality in [90u8, 82, 72] {
        let (mime, encoded) = if transparent && quality == 90 {
            ("image/png", encode_png(&scaled)?)
        } else {
            ("image/jpeg", encode_jpeg(&scaled, quality)?)
        };
        if encoded.len() <= MAX_UPLOAD_BYTES {
            return Ok(PreparedImage { mime, bytes: encoded, width: scaled.width(), height: scaled.height() });
        }
    }
    Err(invalid(crate::msg!("chatImage.fileTooLarge", "Die Datei ist zu groß.")))
}

fn encode_png(img: &image::DynamicImage) -> Result<Vec<u8>> {
    let mut out = Cursor::new(Vec::new());
    img.write_to(&mut out, image::ImageFormat::Png)
        .map_err(|_| invalid(crate::msg!("chatImage.broken", "Das Bild ist beschädigt.")))?;
    Ok(out.into_inner())
}

fn encode_jpeg(img: &image::DynamicImage, quality: u8) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    let rgb = img.to_rgb8();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, quality)
        .encode_image(&rgb)
        .map_err(|_| invalid(crate::msg!("chatImage.broken", "Das Bild ist beschädigt.")))?;
    Ok(out)
}

/// Größtes eingefügtes Bild (Zwischenablage, Base64 vom Webview).
pub const MAX_PASTE_BYTES: usize = 16 * 1024 * 1024;

/// Eingefügtes Bild aus dem Webview (Base64) dekodieren.
pub fn decode_pasted(encoded: &str) -> Result<Vec<u8>> {
    use base64::Engine as _;
    if encoded.len() > MAX_PASTE_BYTES.div_ceil(3) * 4 + 4 {
        return Err(invalid(crate::msg!("chatImage.fileTooLarge", "Die Datei ist zu groß.")));
    }
    base64::engine::general_purpose::STANDARD
        .decode(encoded.trim())
        .map_err(|_| invalid(crate::msg!("chatImage.broken", "Das Bild ist beschädigt.")))
}

/// Kleines JPEG-Vorschaubild (für eigene Dateien im Auswahlfeld).
pub fn thumbnail(bytes: &[u8], side: u32) -> Option<Vec<u8>> {
    let (_, w, h) = inspect(bytes).ok()?;
    let img = image::load_from_memory(bytes).ok()?;
    let img = if w.max(h) > side { img.thumbnail(side, side) } else { img };
    encode_jpeg(&img, 80).ok()
}

// --- Eigene Dateien -----------------------------------------------------------------

#[derive(Debug, Clone)]
enum LocalSource {
    File(PathBuf),
    Memory(Arc<Vec<u8>>),
}

#[derive(Debug, Clone)]
struct LocalEntry {
    id: String,
    source: LocalSource,
    name: String,
    width: u32,
    height: u32,
    bytes: u64,
    added_at: DateTime<Utc>,
}

/// Eigene Datei im Auswahlfeld (ohne Pfad).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalImage {
    pub id: String,
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub bytes: u64,
    pub added_at: String,
}

impl LocalEntry {
    fn view(&self) -> LocalImage {
        LocalImage {
            id: self.id.clone(),
            name: self.name.clone(),
            width: self.width,
            height: self.height,
            bytes: self.bytes,
            added_at: self.added_at.to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
        }
    }
}

/// Ergebnis beim Hinzufügen eigener Dateien.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StagedImages {
    pub images: Vec<LocalImage>,
    /// Dateien, die keine gültigen Bilder waren (oder zu groß).
    pub rejected: u32,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RecentFile {
    path: PathBuf,
    added_at: DateTime<Utc>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct RecentList {
    #[serde(default)]
    files: Vec<RecentFile>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Favorites {
    #[serde(default)]
    screenshots: Vec<String>,
}

fn new_id() -> String {
    format!("l{}", &uuid::Uuid::new_v4().simple().to_string()[..20])
}

fn local_id(id: &str) -> bool {
    id.len() == 21 && id.starts_with('l') && id[1..].bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

fn display_name(path: &Path) -> String {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let clean = super::validate::text(&name, 80);
    if clean.is_empty() { "?".into() } else { clean }
}

#[derive(Default)]
struct Cache {
    entries: HashMap<String, (Arc<Vec<u8>>, &'static str, u64)>,
    bytes: usize,
    tick: u64,
}

impl Cache {
    fn get(&mut self, key: &str) -> Option<(Arc<Vec<u8>>, &'static str)> {
        self.tick += 1;
        let tick = self.tick;
        let entry = self.entries.get_mut(key)?;
        entry.2 = tick;
        Some((Arc::clone(&entry.0), entry.1))
    }

    fn put(&mut self, key: String, bytes: Arc<Vec<u8>>, mime: &'static str) {
        if bytes.len() > CACHE_BYTES / 4 {
            return;
        }
        self.tick += 1;
        self.bytes += bytes.len();
        if let Some(old) = self.entries.insert(key, (bytes, mime, self.tick)) {
            self.bytes -= old.0.len();
        }
        while self.bytes > CACHE_BYTES {
            let Some(oldest) = self.entries.iter().min_by_key(|(_, v)| v.2).map(|(k, _)| k.clone()) else { break };
            if let Some(removed) = self.entries.remove(&oldest) {
                self.bytes -= removed.0.len();
            }
        }
    }
}

/// Zustand der Chat-Bilder im Kern.
pub(crate) struct MediaState {
    root: PathBuf,
    local: StdMutex<Option<Vec<LocalEntry>>>,
    cache: StdMutex<Cache>,
}

impl MediaState {
    pub(crate) fn new(root: PathBuf) -> Self {
        Self { root, local: StdMutex::new(None), cache: StdMutex::default() }
    }

    fn recent_path(&self) -> PathBuf {
        self.root.join("chat-uploads.json")
    }

    fn favorites_path(&self) -> PathBuf {
        self.root.join("screenshot-favorites.json")
    }

    fn cache(&self) -> std::sync::MutexGuard<'_, Cache> {
        self.cache.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Liste laden (beim ersten Zugriff aus `chat-uploads.json`, fehlende Dateien fallen weg).
    async fn entries(&self) -> Vec<LocalEntry> {
        if let Some(list) = self.local.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone() {
            return list;
        }
        let stored: RecentList = crate::fsutil::read_json(&self.recent_path()).await.ok().flatten().unwrap_or_default();
        let mut list = Vec::new();
        for file in stored.files.into_iter().take(MAX_RECENT) {
            let path = file.path.clone();
            let head = tokio::task::spawn_blocking(move || read_head(&path)).await.ok().flatten();
            if let Some((bytes, width, height)) = head {
                list.push(LocalEntry {
                    id: new_id(),
                    name: display_name(&file.path),
                    source: LocalSource::File(file.path),
                    width,
                    height,
                    bytes,
                    added_at: file.added_at,
                });
            }
        }
        let mut guard = self.local.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        guard.get_or_insert(list).clone()
    }

    async fn save_recent(&self) {
        let files: Vec<RecentFile> = self
            .local
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()
            .map(|list| {
                list.iter()
                    .filter_map(|e| match &e.source {
                        LocalSource::File(p) => Some(RecentFile { path: p.clone(), added_at: e.added_at }),
                        LocalSource::Memory(_) => None,
                    })
                    .take(MAX_RECENT)
                    .collect()
            })
            .unwrap_or_default();
        if let Err(e) = crate::fsutil::write_json(&self.recent_path(), &RecentList { files }).await {
            tracing::warn!("Liste der Chat-Uploads nicht gespeichert: {e}");
        }
    }

    fn push(&self, entry: LocalEntry) {
        let mut guard = self.local.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let list = guard.get_or_insert_with(Vec::new);
        if let LocalSource::File(path) = &entry.source {
            list.retain(|e| !matches!(&e.source, LocalSource::File(p) if p == path));
        }
        list.insert(0, entry);
        let mut memory = 0;
        list.retain(|e| match e.source {
            LocalSource::Memory(_) => {
                memory += 1;
                memory <= MAX_MEMORY_IMAGES
            }
            LocalSource::File(_) => true,
        });
        list.truncate(MAX_RECENT + MAX_MEMORY_IMAGES);
    }

    fn find(&self, id: &str) -> Option<LocalEntry> {
        self.local
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()?
            .iter()
            .find(|e| e.id == id)
            .cloned()
    }
}

/// Dateigröße + Maße einer Bilddatei (liest nur den Kopf).
fn read_head(path: &Path) -> Option<(u64, u32, u32)> {
    let meta = std::fs::metadata(path).ok()?;
    if !meta.is_file() || meta.len() > MAX_INPUT_BYTES || meta.len() == 0 {
        return None;
    }
    let mut head = vec![0u8; 64 * 1024];
    let mut file = std::fs::File::open(path).ok()?;
    let n = std::io::Read::read(&mut file, &mut head).ok()?;
    head.truncate(n);
    let mime = sniff(&head)?;
    if mime == "image/webp" && webp_animated(&head) {
        return None;
    }
    // Maße: der Kopf reicht für PNG/WebP immer, für JPEG meistens – sonst ganze Datei.
    let (w, h) = dimensions(&head).or_else(|| dimensions(&std::fs::read(path).ok()?))?;
    (w > 0 && h > 0 && w <= MAX_SIDE && h <= MAX_SIDE && u64::from(w) * u64::from(h) <= MAX_PIXELS)
        .then_some((meta.len(), w, h))
}

/// Welche Datei hochgeladen wird.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum UploadSource {
    /// Screenshot einer Instanz (der Kern prüft Instanz und Dateinamen).
    #[serde(rename_all = "camelCase")]
    Screenshot { instance_id: String, file_name: String },
    /// Eigene Datei aus der Liste (Dateidialog, Drag & Drop, Einfügen).
    Local { id: String },
}

#[derive(Debug, Deserialize)]
struct ApiAttachmentEnvelope {
    attachment: super::chat::ApiAttachment,
}

impl Launcher {
    fn media(&self) -> &MediaState {
        &self.trs.media
    }

    /// Eigene Dateien (Dateidialog/Drag & Drop) aufnehmen – nur gültige Bilder.
    pub async fn chat_stage_files(&self, paths: Vec<PathBuf>) -> Result<StagedImages> {
        self.media().entries().await;
        let mut images = Vec::new();
        let mut rejected = 0;
        for path in paths.into_iter().take(50) {
            let p = path.clone();
            match tokio::task::spawn_blocking(move || read_head(&p)).await.ok().flatten() {
                Some((bytes, width, height)) => {
                    let entry = LocalEntry {
                        id: new_id(),
                        name: display_name(&path),
                        source: LocalSource::File(path),
                        width,
                        height,
                        bytes,
                        added_at: Utc::now(),
                    };
                    images.push(entry.view());
                    self.media().push(entry);
                }
                None => rejected += 1,
            }
        }
        if !images.is_empty() {
            self.media().save_recent().await;
        }
        Ok(StagedImages { images, rejected })
    }

    /// Eingefügtes Bild (Zwischenablage im Webview) aufnehmen – nur im Speicher.
    pub async fn chat_stage_bytes(&self, name: &str, bytes: Vec<u8>) -> Result<LocalImage> {
        if bytes.len() as u64 > MAX_INPUT_BYTES {
            return Err(invalid(crate::msg!("chatImage.fileTooLarge", "Die Datei ist zu groß.")));
        }
        let (_, width, height) = inspect(&bytes)?;
        self.media().entries().await;
        let name = super::validate::text(name.trim(), 80);
        let entry = LocalEntry {
            id: new_id(),
            name: if name.is_empty() { "image".into() } else { name },
            bytes: bytes.len() as u64,
            source: LocalSource::Memory(Arc::new(bytes)),
            width,
            height,
            added_at: Utc::now(),
        };
        let view = entry.view();
        self.media().push(entry);
        Ok(view)
    }

    /// Reiter „Uploads“: zuletzt gewählte eigene Dateien (neueste zuerst).
    pub async fn chat_local_images(&self) -> Vec<LocalImage> {
        self.media().entries().await.iter().map(LocalEntry::view).collect()
    }

    /// Eigene Datei aus der Liste nehmen (die Datei selbst bleibt).
    pub async fn chat_forget_local(&self, id: &str) {
        self.media().entries().await;
        if let Some(list) = self.media().local.lock().unwrap_or_else(std::sync::PoisonError::into_inner).as_mut() {
            list.retain(|e| e.id != id);
        }
        self.media().save_recent().await;
    }

    async fn source_bytes(&self, source: &UploadSource) -> Result<Vec<u8>> {
        let gone = || invalid(crate::msg!("chatImage.gone", "Das Bild gibt es nicht mehr."));
        let path = match source {
            UploadSource::Screenshot { instance_id, file_name } => {
                crate::extras::screenshot_path(&self.paths, instance_id, file_name)?
            }
            UploadSource::Local { id } => {
                if !local_id(id) {
                    return Err(gone());
                }
                match self.media().find(id).ok_or_else(gone)?.source {
                    LocalSource::File(p) => p,
                    LocalSource::Memory(bytes) => return Ok(bytes.as_ref().clone()),
                }
            }
        };
        let meta = tokio::fs::metadata(&path).await.map_err(|_| gone())?;
        if meta.len() > MAX_INPUT_BYTES {
            return Err(invalid(crate::msg!("chatImage.fileTooLarge", "Die Datei ist zu groß.")));
        }
        tokio::fs::read(&path).await.map_err(|_| gone())
    }

    /// Bild prüfen, ggf. verkleinern und hochladen. Liefert die ID fürs Senden.
    pub async fn chat_upload(&self, source: &UploadSource) -> Result<ChatAttachment> {
        let bytes = self.source_bytes(source).await?;
        let prepared = tokio::task::spawn_blocking(move || prepare(bytes))
            .await
            .map_err(|_| invalid(crate::msg!("chatImage.broken", "Das Bild ist beschädigt.")))??;
        let req = Req::with(reqwest::Method::POST, "/v1/chat/attachments", Body::Image(prepared.mime, prepared.bytes), super::MAX_JSON_BYTES);
        let result: ApiAttachmentEnvelope = self.trs_get(req).await?;
        result.attachment.cleaned().ok_or_else(super::bad_response)
    }

    /// Bild aus der API holen (Zwischenspeicher, Typ an den Magic Bytes).
    async fn chat_image(&self, id: &str, thumb: bool) -> Result<(Arc<Vec<u8>>, &'static str)> {
        let key = format!("{id}:{}", u8::from(thumb));
        if let Some(hit) = self.media().cache().get(&key) {
            return Ok(hit);
        }
        let path = if thumb { format!("/v1/chat/attachments/{id}?thumb=1") } else { format!("/v1/chat/attachments/{id}") };
        let bytes = self.trs_raw(Req::with(reqwest::Method::GET, path, Body::Empty, MAX_DOWNLOAD)).await?;
        let mime = sniff(&bytes).ok_or_else(super::bad_response)?;
        let bytes = Arc::new(bytes);
        self.media().cache().put(key, Arc::clone(&bytes), mime);
        Ok((bytes, mime))
    }

    /// Beweisbild einer Meldung (nur Admins; wird nicht zwischengespeichert).
    async fn evidence_image(&self, report: &str, id: &str) -> Result<(Vec<u8>, &'static str)> {
        let path = format!("/v1/admin/reports/{report}/images/{id}");
        let bytes = self.trs_raw(Req::with(reqwest::Method::GET, path, Body::Empty, MAX_DOWNLOAD)).await?;
        let mime = sniff(&bytes).ok_or_else(super::bad_response)?;
        Ok((bytes, mime))
    }

    async fn local_thumbnail(&self, id: &str) -> Option<(Arc<Vec<u8>>, &'static str)> {
        let key = format!("{id}:local");
        if let Some(hit) = self.media().cache().get(&key) {
            return Some(hit);
        }
        let bytes = self.source_bytes(&UploadSource::Local { id: id.to_owned() }).await.ok()?;
        let thumb = tokio::task::spawn_blocking(move || thumbnail(&bytes, LOCAL_THUMB_SIDE)).await.ok().flatten()?;
        let thumb = Arc::new(thumb);
        self.media().cache().put(key, Arc::clone(&thumb), "image/jpeg");
        Some((thumb, "image/jpeg"))
    }

    /// Antwort für `trschat://localhost/<pfad>`:
    /// `/a/<id>` Bild, `/t/<id>` Vorschau, `/l/<id>` eigene Datei (Vorschau),
    /// `/e/<meldung>/<id>` Beweisbild (Admin). Alles andere: 404.
    pub async fn serve_chat_image(&self, path: &str) -> Response {
        let parts: Vec<&str> = path.trim_start_matches('/').split('/').collect();
        let ok = |bytes: &[u8], mime: &'static str, cache: &str| Response {
            status: 200,
            headers: vec![
                ("Content-Type", mime.to_owned()),
                ("Cache-Control", cache.to_owned()),
                ("X-Content-Type-Options", "nosniff".to_owned()),
            ],
            body: bytes.to_vec(),
        };
        let failed = |e: Error| {
            let status = match &e {
                Error::TrsApi { kind: "trs_offline", .. } => 503,
                Error::TrsApi { kind: "trs_rate_limited", .. } => 429,
                _ => 404,
            };
            tracing::debug!("Chat-Bild nicht geladen: {e}");
            Response::status(status)
        };
        match parts.as_slice() {
            [kind @ ("a" | "t"), id] if attachment_id(id) => match self.chat_image(id, *kind == "t").await {
                Ok((bytes, mime)) => ok(&bytes, mime, "private, max-age=31536000, immutable"),
                Err(e) => failed(e),
            },
            ["l", id] if local_id(id) => match self.local_thumbnail(id).await {
                Some((bytes, mime)) => ok(&bytes, mime, "no-store"),
                None => Response::status(404),
            },
            ["e", report, id] if report_id(report) && attachment_id(id) => match self.evidence_image(report, id).await {
                Ok((bytes, mime)) => ok(&bytes, mime, "no-store"),
                Err(e) => failed(e),
            },
            _ => Response::status(404),
        }
    }

    // --- Favoriten (Screenshots) --------------------------------------------------------

    pub async fn screenshot_favorites(&self) -> Vec<String> {
        let stored: Favorites =
            crate::fsutil::read_json(&self.media().favorites_path()).await.ok().flatten().unwrap_or_default();
        stored.screenshots
    }

    /// Screenshot als Favorit markieren (`instanz/datei`); liefert die neue Liste.
    pub async fn set_screenshot_favorite(&self, instance_id: &str, file_name: &str, favorite: bool) -> Result<Vec<String>> {
        // Prüft Instanz-ID und Dateinamen (und dass es die Datei gibt, wenn sie dazukommt).
        if favorite {
            crate::extras::screenshot_path(&self.paths, instance_id, file_name)?;
        }
        let key = format!("{instance_id}/{file_name}");
        let mut list = self.screenshot_favorites().await;
        list.retain(|k| k != &key);
        if favorite {
            list.insert(0, key);
            list.truncate(2000);
        }
        crate::fsutil::write_json(&self.media().favorites_path(), &Favorites { screenshots: list.clone() }).await?;
        Ok(list)
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub(crate) fn png(width: u32, height: u32, alpha: u8) -> Vec<u8> {
        let img = image::RgbaImage::from_pixel(width, height, image::Rgba([200, 30, 20, alpha]));
        let mut out = Cursor::new(Vec::new());
        image::DynamicImage::ImageRgba8(img).write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    }

    fn noisy_png(width: u32, height: u32) -> Vec<u8> {
        let mut seed = 0x1234_5678u32;
        let img = image::RgbImage::from_fn(width, height, |_, _| {
            seed ^= seed << 13;
            seed ^= seed >> 17;
            seed ^= seed << 5;
            image::Rgb([seed as u8, (seed >> 8) as u8, (seed >> 16) as u8])
        });
        let mut out = Cursor::new(Vec::new());
        image::DynamicImage::ImageRgb8(img).write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    }

    #[test]
    fn formats_are_detected_by_magic_bytes() {
        assert_eq!(sniff(&png(2, 2, 255)), Some("image/png"));
        assert_eq!(sniff(&[0xFF, 0xD8, 0xFF, 0xE0]), Some("image/jpeg"));
        assert_eq!(sniff(b"RIFF\0\0\0\0WEBPVP8 "), Some("image/webp"));
        assert_eq!(sniff(b"GIF89a"), None);
        assert_eq!(sniff(b"<svg"), None);
        let mut anim = b"RIFF\0\0\0\0WEBPVP8X\x0a\0\0\0\x02\0\0\0".to_vec();
        anim.extend_from_slice(&[0; 16]);
        assert!(webp_animated(&anim));
        assert_eq!(inspect(&anim).unwrap_err().message_code(), "chatImage.animated");
        assert_eq!(inspect(b"hello").unwrap_err().message_code(), "chatImage.unsupported");
        let mut broken = png(4, 4, 255);
        broken.truncate(20);
        assert_eq!(inspect(&broken).unwrap_err().message_code(), "chatImage.broken");
    }

    #[test]
    fn small_images_pass_through_unchanged() {
        let bytes = png(64, 32, 255);
        let prepared = prepare(bytes.clone()).unwrap();
        assert_eq!(prepared.bytes, bytes);
        assert_eq!((prepared.mime, prepared.width, prepared.height), ("image/png", 64, 32));
    }

    #[test]
    fn large_images_are_scaled_and_reencoded() {
        let prepared = prepare(png(3000, 1500, 255)).unwrap();
        assert_eq!((prepared.width, prepared.height), (2048, 1024));
        assert_eq!(prepared.mime, "image/jpeg", "undurchsichtig → JPEG");
        assert_eq!(sniff(&prepared.bytes), Some("image/jpeg"));

        let transparent = prepare(png(2500, 100, 128)).unwrap();
        assert_eq!(transparent.mime, "image/png", "Transparenz bleibt");
        assert!(transparent.width <= 2048);

        // Groß in Bytes (Rauschen), aber klein genug in Pixeln: nur neu kodieren.
        let noisy = noisy_png(1400, 1400);
        assert!(noisy.len() > MAX_UPLOAD_BYTES, "Testbild muss über 5 MiB liegen ({})", noisy.len());
        let prepared = prepare(noisy).unwrap();
        assert!(prepared.bytes.len() <= MAX_UPLOAD_BYTES);
        assert_eq!((prepared.width, prepared.height), (1400, 1400));
    }

    #[test]
    fn oversized_images_are_rejected() {
        let huge = png(8193, 1, 255);
        assert_eq!(inspect(&huge).unwrap_err().message_code(), "chatImage.tooLarge");
        let many = png(5000, 5000, 255);
        assert_eq!(inspect(&many).unwrap_err().message_code(), "chatImage.tooLarge");
    }

    #[test]
    fn cache_evicts_the_oldest() {
        let mut cache = Cache::default();
        let big = Arc::new(vec![0u8; CACHE_BYTES / 5]);
        for i in 0..6 {
            cache.put(format!("k{i}"), Arc::clone(&big), "image/png");
        }
        assert!(cache.bytes <= CACHE_BYTES);
        assert!(cache.get("k0").is_none(), "ältester Eintrag fliegt raus");
        assert!(cache.get("k5").is_some());
    }

    #[test]
    fn local_ids() {
        let id = new_id();
        assert!(local_id(&id), "{id}");
        assert!(!local_id("l../../x") && !local_id("a0123456789abcdef01234567"));
    }
}
