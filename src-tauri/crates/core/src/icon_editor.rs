//! Symbol-Editor: fertig zusammengesetzte Instanz-Bilder speichern und
//! Item-/Block-Texturen aus einem schon geladenen Minecraft-Client lesen.
//!
//! - **Speichern:** Das Webview setzt das Symbol (Hintergrund + Motiv) selbst
//!   zusammen und schickt ein kleines quadratisches PNG plus die editierbare
//!   Quelle (`icon-source.json`, Format `docs/icon-format.md`). Beides wird hier
//!   noch einmal geprüft (Magic Bytes, Maße, Größe, JSON).
//! - **Texturen:** Mojangs Bilder liegen nie im Launcher. Sie kommen zur Laufzeit
//!   aus `versions/<id>/<id>.jar` des Spielers (nur `textures/item|block/*.png`,
//!   feste Größen- und Mengengrenzen), werden als erstes Animationsbild neu
//!   kodiert und unter `meta/mc-textures/<id>/` zwischengespeichert.
//! - **Eigenes Bild:** Datei aus dem nativen Dialog, Format am Dateikopf, auf
//!   höchstens 512 px verkleinert und als frisches PNG ans Webview.

use std::collections::BTreeSet;
use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde::{Deserialize, Serialize};

use crate::icon::{ImageFormat, MAX_ICON_BYTES, sniff, validate_image};
use crate::instance::Instance;
use crate::paths::Paths;
use crate::{Error, Launcher, Result, fsutil};

/// Editierbare Quelle neben dem Bild.
pub const SOURCE_FILE: &str = "icon-source.json";
/// Das fertige Symbol ist klein (128 px) – mehr braucht niemand.
pub const MAX_COMPOSED_BYTES: usize = 1024 * 1024;
const MIN_SIDE: u32 = 16;
const MAX_SIDE: u32 = 512;
/// Quelle: Pixel-Daten (höchstens 32 × 32) oder ein kleines Bild als Data-URL.
pub const MAX_SOURCE_BYTES: usize = 256 * 1024;
/// Hochgeladene Bilder: so groß dürfen sie im Original sein …
const MAX_UPLOAD_SIDE: u32 = 4096;
/// … und so groß kommen sie im Webview an.
const UPLOAD_PREVIEW_SIDE: u32 = 512;

const TEXTURE_PREFIX: &str = "assets/minecraft/textures/";
/// Cache-Format; ändert es sich, wird neu entpackt.
const CACHE_FORMAT: u32 = 1;
const INDEX_FILE: &str = "index.json";
const MAX_TEXTURE_ENTRY_BYTES: u64 = 64 * 1024;
const MAX_TEXTURES: usize = 4000;
const MAX_TEXTURE_TOTAL: u64 = 24 * 1024 * 1024;
const MAX_TEXTURE_WIDTH: u32 = 64;
const MAX_STRIP_HEIGHT: u32 = 4096;
const MAX_NAME_LEN: usize = 64;

fn invalid_icon() -> Error {
    Error::validation(crate::msg!("iconEditor.invalidIcon", "Das Symbol ist kein gültiges PNG-Bild."))
}

fn invalid_source() -> Error {
    Error::validation(crate::msg!("iconEditor.invalidSource", "Die Daten des Symbols sind ungültig."))
}

fn no_client() -> Error {
    Error::validation(crate::msg!(
        "iconEditor.noClientJar",
        "Noch kein Minecraft geladen – starte einmal eine Instanz."
    ))
}

fn broken_image() -> Error {
    Error::validation(crate::msg!("iconEditor.brokenImage", "Das Bild ist beschädigt oder zu groß (höchstens 4096×4096 Pixel)."))
}

/// Base64 aus dem Webview → Bytes; die Länge wird vor dem Dekodieren geprüft.
pub fn decode_base64(encoded: &str) -> Result<Vec<u8>> {
    let encoded = encoded.trim();
    let encoded = encoded.strip_prefix("data:image/png;base64,").unwrap_or(encoded);
    if encoded.len() > MAX_COMPOSED_BYTES.div_ceil(3) * 4 {
        return Err(invalid_icon());
    }
    STANDARD.decode(encoded).map_err(|_| invalid_icon())
}

/// Fertiges Symbol: PNG, quadratisch, 16–512 px, dekodierbar.
pub fn validate_composed(bytes: &[u8]) -> Result<u32> {
    if bytes.is_empty() || bytes.len() > MAX_COMPOSED_BYTES || sniff(bytes) != Some(ImageFormat::Png) {
        return Err(invalid_icon());
    }
    let (width, height) = image::ImageReader::with_format(Cursor::new(bytes), image::ImageFormat::Png)
        .into_dimensions()
        .map_err(|_| invalid_icon())?;
    if width != height || !(MIN_SIDE..=MAX_SIDE).contains(&width) {
        return Err(invalid_icon());
    }
    decode_limited(bytes, image::ImageFormat::Png, width, height).ok_or_else(invalid_icon)?;
    Ok(width)
}

/// Quelle prüfen: JSON-Objekt mit `"v": 1`, höchstens 256 KB.
pub fn validate_source(text: &str) -> Result<()> {
    if text.is_empty() || text.len() > MAX_SOURCE_BYTES {
        return Err(invalid_source());
    }
    let value: serde_json::Value = serde_json::from_str(text).map_err(|_| invalid_source())?;
    let ok = value.as_object().is_some_and(|o| o.get("v").and_then(|v| v.as_u64()) == Some(1));
    if ok { Ok(()) } else { Err(invalid_source()) }
}

/// Dekodiert mit festen Grenzen (Maße vorher aus dem Kopf gelesen).
fn decode_limited(bytes: &[u8], format: image::ImageFormat, width: u32, height: u32) -> Option<image::DynamicImage> {
    let mut reader = image::ImageReader::with_format(Cursor::new(bytes), format);
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(width);
    limits.max_image_height = Some(height);
    limits.max_alloc = Some(96 * 1024 * 1024);
    reader.limits(limits);
    let image = reader.decode().ok()?;
    (image.width() == width && image.height() == height).then_some(image)
}

fn encode_png(image: &image::DynamicImage) -> Result<Vec<u8>> {
    let mut out = Cursor::new(Vec::new());
    image.write_to(&mut out, image::ImageFormat::Png).map_err(|e| Error::Internal(e.to_string()))?;
    Ok(out.into_inner())
}

fn image_format(format: ImageFormat) -> image::ImageFormat {
    match format {
        ImageFormat::Png => image::ImageFormat::Png,
        ImageFormat::Jpeg => image::ImageFormat::Jpeg,
        ImageFormat::Webp => image::ImageFormat::WebP,
    }
}

/// Ein eigenes Bild für den Editor: geprüft, verkleinert, als frisches PNG.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreparedImage {
    pub data_url: String,
    pub width: u32,
    pub height: u32,
}

pub fn prepare_upload(bytes: &[u8]) -> Result<PreparedImage> {
    let format = image_format(validate_image(bytes)?);
    let (width, height) =
        image::ImageReader::with_format(Cursor::new(bytes), format).into_dimensions().map_err(|_| broken_image())?;
    if width == 0 || height == 0 || width > MAX_UPLOAD_SIDE || height > MAX_UPLOAD_SIDE {
        return Err(broken_image());
    }
    let image = decode_limited(bytes, format, width, height).ok_or_else(broken_image)?;
    let image = if width.max(height) > UPLOAD_PREVIEW_SIDE {
        image.resize(UPLOAD_PREVIEW_SIDE, UPLOAD_PREVIEW_SIDE, image::imageops::FilterType::Triangle)
    } else {
        image
    };
    let image = image::DynamicImage::ImageRgba8(image.into_rgba8());
    let png = encode_png(&image)?;
    Ok(PreparedImage {
        data_url: format!("data:image/png;base64,{}", STANDARD.encode(png)),
        width: image.width(),
        height: image.height(),
    })
}

/// Wie [`prepare_upload`], für eine Datei aus dem nativen Dialog (Größe vor dem Lesen geprüft).
pub async fn prepare_upload_file(file: &Path) -> Result<PreparedImage> {
    let meta = tokio::fs::metadata(file).await.map_err(|e| Error::io(file, e))?;
    if !meta.is_file() || meta.len() > MAX_ICON_BYTES {
        return Err(Error::validation(crate::msg!("icon.imageTooLarge", "Das Bild darf höchstens 5 MB groß sein.")));
    }
    let bytes = tokio::fs::read(file).await.map_err(|e| Error::io(file, e))?;
    tokio::task::spawn_blocking(move || prepare_upload(&bytes)).await.map_err(|e| Error::Internal(e.to_string()))?
}

impl Launcher {
    /// Speichert ein im Editor gebautes Symbol (+ editierbare Quelle).
    pub async fn save_instance_icon(&self, id: &str, png: &[u8], source: Option<&str>) -> Result<Instance> {
        validate_composed(png)?;
        if let Some(text) = source {
            validate_source(text)?;
        }
        let updated = self.set_instance_icon_bytes(id, png).await?;
        if let Some(text) = source {
            let path = self.paths().instance_dir(&updated.id).join(SOURCE_FILE);
            fsutil::write_atomic(&path, text.as_bytes()).await?;
        }
        Ok(updated)
    }

    /// Editierbare Quelle des aktuellen Symbols (`None` = Bild ohne Editor-Daten).
    pub async fn instance_icon_source(&self, id: &str) -> Result<Option<String>> {
        let instance = self.instances().get(id).await?;
        if self.instance_icon_path(&instance).is_none() {
            return Ok(None);
        }
        let path = self.paths().instance_dir(&instance.id).join(SOURCE_FILE);
        let Ok(meta) = tokio::fs::metadata(&path).await else { return Ok(None) };
        if !meta.is_file() || meta.len() > MAX_SOURCE_BYTES as u64 {
            return Ok(None);
        }
        let Ok(text) = tokio::fs::read_to_string(&path).await else { return Ok(None) };
        Ok(validate_source(&text).is_ok().then_some(text))
    }

    /// Spielversionen mit geladenem Client (neueste zuerst).
    pub async fn mc_texture_versions(&self) -> Vec<String> {
        let paths = self.paths().clone();
        tokio::task::spawn_blocking(move || client_versions(&paths)).await.unwrap_or_default()
    }

    /// Texturen einer Version (ohne Angabe: neueste mit Client), bei Bedarf entpackt.
    pub async fn mc_textures(&self, version: Option<&str>) -> Result<TextureSet> {
        let paths = self.paths().clone();
        let wanted = version.map(str::to_owned);
        tokio::task::spawn_blocking(move || {
            let versions = client_versions(&paths);
            let version = match wanted {
                Some(v) if versions.contains(&v) => v,
                Some(_) => return Err(no_client()),
                None => versions.into_iter().next().ok_or_else(no_client)?,
            };
            let cache = texture_cache_dir(&paths);
            if read_index(&cache.join(&version)).is_none() {
                extract_textures(&paths.version_jar(&version), &cache, &version)?;
            }
            load_textures(&cache, &version)
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
    }
}

// --- Minecraft-Texturen ---------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Texture {
    /// `item/diamond_sword` oder `block/stone`.
    pub key: String,
    pub data_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TextureSet {
    pub version: String,
    pub textures: Vec<Texture>,
}

#[derive(Debug, Serialize, Deserialize)]
struct TextureIndex {
    format: u32,
    version: String,
    keys: Vec<String>,
}

pub fn texture_cache_dir(paths: &Paths) -> PathBuf {
    paths.meta_dir().join("mc-textures")
}

/// Versionen mit `versions/<id>/<id>.jar`, sortiert nach `releaseTime` (neueste zuerst).
pub fn client_versions(paths: &Paths) -> Vec<String> {
    let Ok(entries) = std::fs::read_dir(paths.versions_dir()) else { return Vec::new() };
    let mut found: Vec<(String, String)> = entries
        .filter_map(|entry| entry.ok())
        .filter_map(|entry| entry.file_name().to_str().map(str::to_owned))
        .filter(|id| crate::meta::is_safe_id(id))
        .filter(|id| std::fs::metadata(paths.version_jar(id)).is_ok_and(|m| m.is_file() && m.len() > 0))
        .map(|id| {
            let released = std::fs::read(paths.version_json(&id))
                .ok()
                .filter(|b| b.len() < 4 * 1024 * 1024)
                .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
                .and_then(|v| v.get("releaseTime").and_then(|t| t.as_str()).map(str::to_owned))
                .unwrap_or_default();
            (released, id)
        })
        .collect();
    found.sort_by(|a, b| b.cmp(a));
    found.into_iter().map(|(_, id)| id).collect()
}

/// `assets/minecraft/textures/item/diamond.png` → `item/diamond` (alte Ordner `items`/`blocks` auch).
fn texture_key(name: &str) -> Option<String> {
    let rest = name.strip_prefix(TEXTURE_PREFIX)?;
    let (dir, file) = rest.split_once('/')?;
    let kind = match dir {
        "item" | "items" => "item",
        "block" | "blocks" => "block",
        _ => return None,
    };
    let stem = file.strip_suffix(".png")?;
    let ok = !stem.is_empty()
        && stem.len() <= MAX_NAME_LEN
        && stem.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_');
    ok.then(|| format!("{kind}/{stem}"))
}

/// Erstes Bild eines Animationsstreifens, auf 16 bzw. 32 px gebracht. `None` = unbrauchbar.
fn normalize_texture(bytes: &[u8]) -> Option<Vec<u8>> {
    if sniff(bytes) != Some(ImageFormat::Png) {
        return None;
    }
    let (width, height) =
        image::ImageReader::with_format(Cursor::new(bytes), image::ImageFormat::Png).into_dimensions().ok()?;
    if width == 0 || width > MAX_TEXTURE_WIDTH || height < width || height > MAX_STRIP_HEIGHT {
        return None;
    }
    let image = decode_limited(bytes, image::ImageFormat::Png, width, height)?.into_rgba8();
    let frame = image::imageops::crop_imm(&image, 0, 0, width, width).to_image();
    let side = if width > 16 { 32 } else { 16 };
    let frame =
        if width == side { frame } else { image::imageops::resize(&frame, side, side, image::imageops::FilterType::Nearest) };
    if frame.pixels().all(|p| p.0[3] == 0) {
        return None;
    }
    encode_png(&image::DynamicImage::ImageRgba8(frame)).ok()
}

/// Entpackt die Texturen aus `jar` nach `<cache>/<version>/` (atomar über einen Zwischenordner).
pub fn extract_textures(jar: &Path, cache: &Path, version: &str) -> Result<usize> {
    if !crate::meta::is_safe_id(version) {
        return Err(no_client());
    }
    let file = std::fs::File::open(jar).map_err(|_| no_client())?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| no_client())?;
    let staging = cache.join(format!(".{}-{}", version.replace(' ', "_"), uuid::Uuid::new_v4().simple()));
    let result = (|| -> Result<Vec<String>> {
        let mut keys = BTreeSet::new();
        let mut total = 0u64;
        for i in 0..archive.len() {
            if keys.len() >= MAX_TEXTURES || total > MAX_TEXTURE_TOTAL {
                break;
            }
            let Ok(mut entry) = archive.by_index(i) else { continue };
            if !entry.is_file() || entry.size() > MAX_TEXTURE_ENTRY_BYTES {
                continue;
            }
            let Some(key) = texture_key(entry.name()) else { continue };
            if keys.contains(&key) {
                continue;
            }
            let mut bytes = Vec::new();
            // Angaben im Zip können lügen – nie mehr als die Grenze lesen.
            if (&mut entry).take(MAX_TEXTURE_ENTRY_BYTES + 1).read_to_end(&mut bytes).is_err()
                || bytes.len() as u64 > MAX_TEXTURE_ENTRY_BYTES
            {
                continue;
            }
            let Some(png) = normalize_texture(&bytes) else { continue };
            let dest = staging.join(format!("{key}.png"));
            if let Some(parent) = dest.parent() {
                std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
            }
            std::fs::write(&dest, &png).map_err(|e| Error::io(&dest, e))?;
            total += png.len() as u64;
            keys.insert(key);
        }
        if keys.is_empty() {
            return Err(no_client());
        }
        Ok(keys.into_iter().collect())
    })();
    let keys = match result {
        Ok(keys) => keys,
        Err(e) => {
            let _ = std::fs::remove_dir_all(&staging);
            return Err(e);
        }
    };
    let index = TextureIndex { format: CACHE_FORMAT, version: version.to_owned(), keys };
    let json = serde_json::to_vec(&index).map_err(|e| Error::Internal(e.to_string()))?;
    let count = index.keys.len();
    std::fs::write(staging.join(INDEX_FILE), json).map_err(|e| Error::io(&staging, e))?;
    let target = cache.join(version);
    let _ = std::fs::remove_dir_all(&target);
    if let Err(e) = std::fs::rename(&staging, &target) {
        let _ = std::fs::remove_dir_all(&staging);
        return Err(Error::io(&target, e));
    }
    Ok(count)
}

fn read_index(dir: &Path) -> Option<TextureIndex> {
    let bytes = std::fs::read(dir.join(INDEX_FILE)).ok().filter(|b| b.len() < 1024 * 1024)?;
    let index: TextureIndex = serde_json::from_slice(&bytes).ok()?;
    (index.format == CACHE_FORMAT).then_some(index)
}

/// Liest die zwischengespeicherten Texturen als Data-URLs (Schlüssel werden erneut geprüft).
pub fn load_textures(cache: &Path, version: &str) -> Result<TextureSet> {
    if !crate::meta::is_safe_id(version) {
        return Err(no_client());
    }
    let dir = cache.join(version);
    let index = read_index(&dir).ok_or_else(no_client)?;
    let textures = index
        .keys
        .iter()
        .take(MAX_TEXTURES)
        .filter(|key| texture_key(&format!("{TEXTURE_PREFIX}{key}.png")).as_deref() == Some(key.as_str()))
        .filter_map(|key| {
            let bytes = std::fs::read(dir.join(format!("{key}.png"))).ok()?;
            (bytes.len() as u64 <= MAX_TEXTURE_ENTRY_BYTES && sniff(&bytes) == Some(ImageFormat::Png))
                .then(|| Texture { key: key.clone(), data_url: format!("data:image/png;base64,{}", STANDARD.encode(bytes)) })
        })
        .collect();
    Ok(TextureSet { version: version.to_owned(), textures })
}

// --- Symbol im Modpack ------------------------------------------------------------

/// Namen, unter denen ein Pack sein Symbol im Wurzelordner trägt.
pub const PACK_ICON_NAMES: [&str; 3] = ["icon.png", "icon.jpg", "icon.webp"];

/// Liest das Symbol aus einem `.mrpack` (falls vorhanden und gültig).
pub fn read_pack_icon(pack: &Path) -> Option<Vec<u8>> {
    let file = std::fs::File::open(pack).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    for name in PACK_ICON_NAMES {
        let Ok(entry) = archive.by_name(name) else { continue };
        if entry.size() > MAX_ICON_BYTES {
            continue;
        }
        let mut bytes = Vec::new();
        if entry.take(MAX_ICON_BYTES + 1).read_to_end(&mut bytes).is_err() {
            continue;
        }
        if validate_image(&bytes).is_ok() {
            return Some(bytes);
        }
    }
    None
}

impl Launcher {
    /// Symbol aus dem Pack zum Instanz-Bild machen (Fehler nur im Log).
    pub(crate) async fn apply_pack_icon(&self, instance: Instance, pack: &Path) -> Instance {
        let path = pack.to_owned();
        let Ok(Some(bytes)) = tokio::task::spawn_blocking(move || read_pack_icon(&path)).await else { return instance };
        match self.set_instance_icon_bytes(&instance.id, &bytes).await {
            Ok(updated) => updated,
            Err(e) => {
                tracing::debug!("Pack-Symbol übersprungen: {e}");
                instance
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;
    use std::sync::Arc;

    use super::*;
    use crate::instance::{Loader, NewInstance};

    fn png(width: u32, height: u32, color: [u8; 4]) -> Vec<u8> {
        let img = image::RgbaImage::from_fn(width, height, |x, y| {
            if (x + y) % 3 == 0 { image::Rgba(color) } else { image::Rgba([0, 0, 0, 0]) }
        });
        encode_png(&image::DynamicImage::ImageRgba8(img)).unwrap()
    }

    fn write_zip(path: &Path, entries: &[(&str, &[u8])]) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        let opts = zip::write::SimpleFileOptions::default();
        for (name, bytes) in entries {
            zip.start_file(*name, opts).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn composed_icon_must_be_a_small_square_png() {
        assert_eq!(validate_composed(&png(128, 128, [200, 30, 30, 255])).unwrap(), 128);
        assert!(validate_composed(&png(16, 16, [1, 2, 3, 255])).is_ok());
        for bad in [png(128, 64, [1, 1, 1, 255]), png(8, 8, [1, 1, 1, 255]), png(600, 600, [1, 1, 1, 255])] {
            assert!(validate_composed(&bad).is_err());
        }
        assert!(validate_composed(b"<svg xmlns='http://www.w3.org/2000/svg'/>").is_err());
        assert!(validate_composed(&[0xFF, 0xD8, 0xFF, 0xE0]).is_err(), "nur PNG");
        // Kopf stimmt, Inhalt kaputt.
        let mut broken = png(32, 32, [9, 9, 9, 255]);
        broken.truncate(40);
        assert!(validate_composed(&broken).is_err());
        assert!(decode_base64(&"A".repeat(MAX_COMPOSED_BYTES * 2)).is_err());
        let data_url = format!("data:image/png;base64,{}", STANDARD.encode(png(32, 32, [1, 1, 1, 255])));
        assert!(validate_composed(&decode_base64(&data_url).unwrap()).is_ok());
    }

    #[test]
    fn source_needs_version_one() {
        assert!(validate_source(r#"{"v":1,"bg":{}}"#).is_ok());
        for bad in ["", "[]", r#"{"v":2}"#, r#"{"v":"1"}"#, "{", "null"] {
            assert!(validate_source(bad).is_err(), "{bad:?}");
        }
        let huge = format!(r#"{{"v":1,"x":"{}"}}"#, "a".repeat(MAX_SOURCE_BYTES));
        assert!(validate_source(&huge).is_err());
    }

    #[test]
    fn uploads_are_shrunk_and_reencoded() {
        let big = png(1200, 600, [10, 200, 10, 255]);
        let prepared = prepare_upload(&big).unwrap();
        assert_eq!((prepared.width, prepared.height), (512, 256));
        assert!(prepared.data_url.starts_with("data:image/png;base64,"));
        let small = prepare_upload(&png(40, 40, [1, 1, 1, 255])).unwrap();
        assert_eq!((small.width, small.height), (40, 40));
        assert!(prepare_upload(b"GIF89a......").is_err());
        assert!(prepare_upload(&png(5000, 1, [1, 1, 1, 255])).is_err());
    }

    #[test]
    fn texture_keys_are_allow_listed() {
        assert_eq!(texture_key("assets/minecraft/textures/item/diamond_sword.png").as_deref(), Some("item/diamond_sword"));
        assert_eq!(texture_key("assets/minecraft/textures/blocks/stone.png").as_deref(), Some("block/stone"));
        for bad in [
            "assets/minecraft/textures/item/../../x.png",
            "assets/minecraft/textures/item/sub/x.png",
            "assets/minecraft/textures/item/Upper.png",
            "assets/minecraft/textures/entity/creeper.png",
            "assets/minecraft/textures/item/x.png.mcmeta",
            "assets/minecraft/textures/item/.png",
            "data/minecraft/textures/item/x.png",
        ] {
            assert!(texture_key(bad).is_none(), "{bad:?}");
        }
    }

    #[test]
    fn extracts_textures_from_a_client_jar() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("1.21.1.jar");
        let item = png(16, 16, [40, 200, 220, 255]);
        let strip = png(16, 64, [220, 120, 20, 255]);
        let hd = png(64, 64, [20, 20, 220, 255]);
        let empty = encode_png(&image::DynamicImage::ImageRgba8(image::RgbaImage::new(16, 16))).unwrap();
        let wide = png(128, 128, [1, 1, 1, 255]);
        write_zip(&jar, &[
            ("assets/minecraft/textures/item/diamond.png", &item),
            ("assets/minecraft/textures/block/magma.png", &strip),
            ("assets/minecraft/textures/block/hd_block.png", &hd),
            ("assets/minecraft/textures/items/old_apple.png", &item),
            ("assets/minecraft/textures/item/blank.png", &empty),
            ("assets/minecraft/textures/item/huge.png", &wide),
            ("assets/minecraft/textures/item/fake.png", b"<svg/>"),
            ("assets/minecraft/textures/item/../evil.png", &item),
            ("assets/minecraft/textures/entity/pig.png", &item),
            ("net/minecraft/Main.class", b"\xca\xfe\xba\xbe"),
        ]);
        let cache = dir.path().join("cache");
        std::fs::create_dir_all(&cache).unwrap();
        assert_eq!(extract_textures(&jar, &cache, "1.21.1").unwrap(), 4);

        let set = load_textures(&cache, "1.21.1").unwrap();
        let keys: Vec<&str> = set.textures.iter().map(|t| t.key.as_str()).collect();
        assert_eq!(keys, ["block/hd_block", "block/magma", "item/diamond", "item/old_apple"]);
        // Animationsstreifen → erstes Bild, 64 px → 32 px.
        let decode = |key: &str| {
            let t = set.textures.iter().find(|t| t.key == key).unwrap();
            let bytes = STANDARD.decode(t.data_url.trim_start_matches("data:image/png;base64,")).unwrap();
            image::load_from_memory(&bytes).unwrap()
        };
        assert_eq!((decode("block/magma").width(), decode("block/magma").height()), (16, 16));
        assert_eq!(decode("block/hd_block").width(), 32);
        assert!(!cache.join("evil.png").exists() && !dir.path().join("evil.png").exists());
        // Keine Zwischenordner übrig.
        let leftovers = std::fs::read_dir(&cache).unwrap().filter(|e| e.as_ref().unwrap().file_name() != "1.21.1").count();
        assert_eq!(leftovers, 0);

        // Manipulierter Index: fremde Schlüssel werden nicht gelesen.
        std::fs::write(cache.join("1.21.1").join(INDEX_FILE), r#"{"format":1,"version":"1.21.1","keys":["../../secret","item/diamond"]}"#)
            .unwrap();
        assert_eq!(load_textures(&cache, "1.21.1").unwrap().textures.len(), 1);
        assert!(load_textures(&cache, "../x").is_err());

        // Jar ohne Texturen bzw. kein Zip.
        let bare = dir.path().join("bare.jar");
        write_zip(&bare, &[("net/x.class", b"x")]);
        assert!(extract_textures(&bare, &cache, "bare").is_err());
        std::fs::write(&bare, b"kein zip").unwrap();
        assert!(extract_textures(&bare, &cache, "bare").is_err());
        assert!(!cache.join("bare").exists());
    }

    #[test]
    fn finds_client_versions_newest_first() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        for (id, released, jar) in [
            ("1.8.9", "2015-12-03T09:24:39+00:00", true),
            ("1.21.1", "2024-08-08T12:24:45+00:00", true),
            ("fabric-loader-0.16.5-1.21.1", "2024-08-08T12:24:45+00:00", false),
        ] {
            let vdir = paths.version_dir(id);
            std::fs::create_dir_all(&vdir).unwrap();
            std::fs::write(paths.version_json(id), format!(r#"{{"releaseTime":"{released}"}}"#)).unwrap();
            if jar {
                std::fs::write(paths.version_jar(id), b"PK").unwrap();
            }
        }
        assert_eq!(client_versions(&paths), ["1.21.1", "1.8.9"]);
        assert!(client_versions(&Paths::new(dir.path().join("fehlt"))).is_empty());
    }

    #[test]
    fn pack_icon_is_read_from_the_root() {
        let dir = tempfile::tempdir().unwrap();
        let pack = dir.path().join("p.mrpack");
        let icon = png(64, 64, [1, 2, 3, 255]);
        write_zip(&pack, &[("modrinth.index.json", b"{}"), ("overrides/icon.png", b"x"), ("icon.png", &icon)]);
        assert_eq!(read_pack_icon(&pack).unwrap(), icon);
        write_zip(&pack, &[("icon.png", b"<svg/>"), ("icon.webp", b"RIFF\x10\0\0\0WEBPVP8 ")]);
        assert!(read_pack_icon(&pack).unwrap().starts_with(b"RIFF"));
        write_zip(&pack, &[("modrinth.index.json", b"{}")]);
        assert!(read_pack_icon(&pack).is_none());
    }

    #[tokio::test]
    async fn save_and_load_editor_icon() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
        let inst = launcher
            .instances()
            .create(NewInstance { name: "Symbol".into(), game_version: "1.21.1".into(), loader: Loader::vanilla() })
            .await
            .unwrap();
        assert_eq!(launcher.instance_icon_source(&inst.id).await.unwrap(), None);

        let icon = png(128, 128, [200, 30, 30, 255]);
        let source = r#"{"v":1,"layer":{"kind":"pixels"}}"#;
        assert!(launcher.save_instance_icon(&inst.id, &png(100, 50, [1, 1, 1, 255]), Some(source)).await.is_err());
        assert!(launcher.save_instance_icon(&inst.id, &icon, Some("{\"v\":9}")).await.is_err());
        assert!(launcher.save_instance_icon("../x", &icon, Some(source)).await.is_err());

        let saved = launcher.save_instance_icon(&inst.id, &icon, Some(source)).await.unwrap();
        let path = launcher.instance_icon_path(&saved).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), icon);
        assert_eq!(launcher.instance_icon_source(&inst.id).await.unwrap().as_deref(), Some(source));

        // Ein Bild von woanders ersetzt auch die Editor-Daten.
        let file = dir.path().join("pic.png");
        tokio::fs::write(&file, &icon).await.unwrap();
        launcher.set_instance_icon_from_file(&inst.id, &file).await.unwrap();
        assert_eq!(launcher.instance_icon_source(&inst.id).await.unwrap(), None);

        launcher.save_instance_icon(&inst.id, &icon, Some(source)).await.unwrap();
        launcher.remove_instance_icon(&inst.id).await.unwrap();
        assert_eq!(launcher.instance_icon_source(&inst.id).await.unwrap(), None);
        assert!(!launcher.paths().instance_dir(&inst.id).join(SOURCE_FILE).exists());

        // Eigenes Bild aus dem Dialog: Größe vor dem Lesen, Inhalt am Kopf.
        assert_eq!(prepare_upload_file(&file).await.unwrap().width, 128);
        tokio::fs::write(&file, vec![0x89; (MAX_ICON_BYTES + 1) as usize]).await.unwrap();
        assert!(prepare_upload_file(&file).await.is_err());
        assert!(prepare_upload_file(&dir.path().join("fehlt.png")).await.is_err());

        // Ohne Client gibt es keine Texturen.
        assert!(launcher.mc_textures(None).await.is_err());
        assert!(launcher.mc_texture_versions().await.is_empty());
    }

    #[tokio::test]
    async fn textures_through_the_launcher_are_cached() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
        let paths = launcher.paths().clone();
        std::fs::create_dir_all(paths.version_dir("1.21.1")).unwrap();
        write_zip(&paths.version_jar("1.21.1"), &[("assets/minecraft/textures/item/stick.png", &png(16, 16, [9, 9, 9, 255]))]);
        assert_eq!(launcher.mc_texture_versions().await, ["1.21.1"]);
        let set = launcher.mc_textures(None).await.unwrap();
        assert_eq!((set.version.as_str(), set.textures.len()), ("1.21.1", 1));
        // Zweiter Aufruf kommt aus dem Cache, auch wenn das Jar weg ist … solange die Version noch da ist.
        std::fs::write(paths.version_jar("1.21.1"), b"PK kaputt").unwrap();
        assert_eq!(launcher.mc_textures(Some("1.21.1")).await.unwrap().textures.len(), 1);
        assert!(launcher.mc_textures(Some("1.8.9")).await.is_err());
    }
}
