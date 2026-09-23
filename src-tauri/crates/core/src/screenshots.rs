//! Screenshots aller Instanzen an einem Ort: auflisten, Vorschaubilder
//! erzeugen, in den Papierkorb legen.
//!
//! Pfade bleiben im Kern: Das Webview arbeitet nur mit Instanz-ID und
//! Dateinamen, jede Datei wird einzeln fürs Asset-Protokoll freigegeben.

use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use serde::Serialize;

use crate::extras;
use crate::paths::Paths;
use crate::{Error, Launcher, Result, fsutil};

/// Breite der Vorschaubilder – reicht für die Kachelansicht.
const THUMB_WIDTH: u32 = 480;
/// Screenshots über dieser Größe werden nicht mehr verkleinert (Schutz vor
/// absurd großen Dateien).
const MAX_SOURCE_BYTES: u64 = 64 * 1024 * 1024;
/// So viele Bilder insgesamt.
const MAX_TOTAL: usize = 5000;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GalleryShot {
    pub instance_id: String,
    pub instance_name: String,
    pub file_name: String,
    pub size: u64,
    pub taken_at: Option<DateTime<Utc>>,
}

impl Launcher {
    /// Alle Screenshots über alle Instanzen, neueste zuerst.
    pub async fn all_screenshots(&self) -> Result<Vec<GalleryShot>> {
        let instances = self.instances().list().await?;
        let mut shots = Vec::new();
        for instance in instances {
            let list = match extras::list_screenshots(self.paths(), &instance.id).await {
                Ok(list) => list,
                Err(e) => {
                    tracing::debug!("Screenshots von '{}' übersprungen: {e}", instance.id);
                    continue;
                }
            };
            for shot in list {
                shots.push(GalleryShot {
                    instance_id: instance.id.clone(),
                    instance_name: instance.name.clone(),
                    file_name: shot.file_name,
                    size: shot.size,
                    taken_at: shot.taken_at,
                });
            }
        }
        shots.sort_by(|a, b| b.taken_at.cmp(&a.taken_at).then_with(|| a.file_name.cmp(&b.file_name)));
        shots.truncate(MAX_TOTAL);
        Ok(shots)
    }

    /// Pfad des Vorschaubilds; wird bei Bedarf erzeugt und zwischengespeichert.
    pub async fn screenshot_thumbnail(&self, instance_id: &str, file_name: &str) -> Result<PathBuf> {
        let source = extras::screenshot_path(self.paths(), instance_id, file_name)?;
        let meta = tokio::fs::metadata(&source).await.map_err(|e| Error::io(&source, e))?;
        if meta.len() > MAX_SOURCE_BYTES {
            return Ok(source);
        }
        let target = thumb_path(self.paths(), instance_id, file_name, &meta);
        if target.is_file() {
            return Ok(target);
        }
        fsutil::ensure_dir(target.parent().unwrap_or(&target)).await?;
        let (from, to) = (source.clone(), target.clone());
        tokio::task::spawn_blocking(move || write_thumbnail(&from, &to))
            .await
            .map_err(|e| Error::Internal(e.to_string()))??;
        Ok(target)
    }

    /// Legt einen Screenshot in den Papierkorb (falls möglich) und räumt das
    /// Vorschaubild weg.
    pub async fn trash_screenshot(&self, instance_id: &str, file_name: &str) -> Result<()> {
        let path = extras::screenshot_path(self.paths(), instance_id, file_name)?;
        let meta = tokio::fs::metadata(&path).await.ok();
        let to_trash = path.clone();
        let result = tokio::task::spawn_blocking(move || recycle(&to_trash))
            .await
            .map_err(|e| Error::Internal(e.to_string()))?;
        if result.is_err() {
            tracing::warn!("Papierkorb nicht verfügbar – lösche endgültig");
            tokio::fs::remove_file(&path).await.map_err(|e| Error::io(&path, e))?;
        }
        if let Some(meta) = meta {
            let _ = tokio::fs::remove_file(thumb_path(self.paths(), instance_id, file_name, &meta)).await;
        }
        Ok(())
    }

    /// Rohe Bilddaten eines Screenshots (für die Zwischenablage).
    pub async fn screenshot_rgba(&self, instance_id: &str, file_name: &str) -> Result<RgbaImage> {
        let path = extras::screenshot_path(self.paths(), instance_id, file_name)?;
        tokio::task::spawn_blocking(move || decode_rgba(&path)).await.map_err(|e| Error::Internal(e.to_string()))?
    }
}

/// Bild als RGBA-Puffer – genau das, was die Zwischenablage braucht.
pub struct RgbaImage {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
}

fn decode_rgba(path: &Path) -> Result<RgbaImage> {
    let meta = std::fs::metadata(path).map_err(|e| Error::io(path, e))?;
    if meta.len() > MAX_SOURCE_BYTES {
        return Err(Error::validation(crate::msg!(
            "screenshots.tooLargeForClipboard",
            "Das Bild ist zu groß für die Zwischenablage."
        )));
    }
    let image = image::ImageReader::open(path)
        .map_err(|e| Error::io(path, e))?
        .with_guessed_format()
        .map_err(|e| Error::io(path, e))?
        .decode()
        .map_err(|_| Error::validation(crate::msg!(
            "screenshots.imageUnreadable",
            "Das Bild konnte nicht gelesen werden."
        )))?
        .to_rgba8();
    Ok(RgbaImage { width: image.width(), height: image.height(), pixels: image.into_raw() })
}

/// Vorschau-Dateiname: Instanz + Datei + Änderungszeit/Größe, damit ein neuer
/// Screenshot mit gleichem Namen nicht das alte Bild zeigt.
fn thumb_path(paths: &Paths, instance_id: &str, file_name: &str, meta: &std::fs::Metadata) -> PathBuf {
    use sha1::Digest;
    let stamp = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or_default();
    let digest = sha1::Sha1::digest(format!("{instance_id}|{file_name}|{stamp}|{}", meta.len()).as_bytes());
    let hex: String = digest.iter().take(10).map(|b| format!("{b:02x}")).collect();
    paths.root().join("cache").join("thumbs").join(format!("{hex}.jpg"))
}

fn write_thumbnail(source: &Path, target: &Path) -> Result<()> {
    let image = image::ImageReader::open(source)
        .map_err(|e| Error::io(source, e))?
        .with_guessed_format()
        .map_err(|e| Error::io(source, e))?
        .decode()
        .map_err(|_| Error::validation(crate::msg!(
            "screenshots.screenshotUnreadable",
            "Der Screenshot konnte nicht gelesen werden."
        )))?;
    let thumb = if image.width() > THUMB_WIDTH {
        image.thumbnail(THUMB_WIDTH, u32::MAX)
    } else {
        image
    };
    let tmp = target.with_extension(format!("part-{}", uuid::Uuid::new_v4().simple()));
    let mut out = std::io::BufWriter::new(std::fs::File::create(&tmp).map_err(|e| Error::io(&tmp, e))?);
    thumb
        .to_rgb8()
        .write_with_encoder(image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 82))
        .map_err(|_| Error::validation(crate::msg!(
            "screenshots.thumbnailWriteFailed",
            "Das Vorschaubild konnte nicht geschrieben werden."
        )))?;
    drop(out);
    std::fs::rename(&tmp, target).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        Error::io(target, e)
    })
}

/// Datei in den Windows-Papierkorb verschieben (`SHFileOperationW`).
#[cfg(windows)]
fn recycle(path: &Path) -> Result<()> {
    use std::os::windows::ffi::OsStrExt;

    use windows::Win32::UI::Shell::{
        FO_DELETE, FOF_ALLOWUNDO, FOF_NOCONFIRMATION, FOF_NOERRORUI, FOF_SILENT, SHFILEOPSTRUCTW, SHFileOperationW,
    };
    use windows::core::PCWSTR;

    // Der Pfad muss doppelt nullterminiert sein (Liste von Dateien).
    let mut wide: Vec<u16> = path.as_os_str().encode_wide().collect();
    if wide.contains(&0) {
        return Err(Error::validation(crate::msg!("screenshots.invalidPath", "Ungültiger Pfad")));
    }
    wide.push(0);
    wide.push(0);

    let mut op = SHFILEOPSTRUCTW {
        wFunc: FO_DELETE,
        pFrom: PCWSTR(wide.as_ptr()),
        fFlags: (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI).0 as u16,
        ..Default::default()
    };
    // SAFETY: `op` zeigt auf den doppelt nullterminierten Puffer oben, der
    // während des Aufrufs am Leben bleibt.
    let code = unsafe { SHFileOperationW(&raw mut op) };
    if code == 0 && !op.fAnyOperationsAborted.as_bool() {
        Ok(())
    } else {
        Err(Error::Internal(format!("SHFileOperation: {code}")))
    }
}

#[cfg(not(windows))]
fn recycle(_path: &Path) -> Result<()> {
    Err(Error::Internal("Papierkorb nur unter Windows".into()))
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::instance::{Loader, NewInstance};

    fn png_bytes(width: u32, height: u32) -> Vec<u8> {
        let buffer = image::RgbImage::from_fn(width, height, |x, y| image::Rgb([(x % 256) as u8, (y % 256) as u8, 128]));
        let mut out = std::io::Cursor::new(Vec::new());
        image::DynamicImage::ImageRgb8(buffer).write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    }

    async fn launcher_with_shots() -> (tempfile::TempDir, Launcher, Vec<String>) {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
        let mut ids = Vec::new();
        for (name, version) in [("Alpha", "1.21.1"), ("Beta", "1.20.1")] {
            let instance = launcher
                .instances()
                .create(NewInstance { name: name.into(), game_version: version.into(), loader: Loader::vanilla() })
                .await
                .unwrap();
            let shots = launcher.paths().instance_game_dir(&instance.id).join("screenshots");
            tokio::fs::create_dir_all(&shots).await.unwrap();
            tokio::fs::write(shots.join(format!("{name}-1.png")), png_bytes(640, 360)).await.unwrap();
            tokio::fs::write(shots.join("notiz.txt"), b"kein Bild").await.unwrap();
            ids.push(instance.id);
        }
        (dir, launcher, ids)
    }

    #[tokio::test]
    async fn lists_screenshots_of_every_instance() {
        let (_dir, launcher, ids) = launcher_with_shots().await;
        let shots = launcher.all_screenshots().await.unwrap();
        assert_eq!(shots.len(), 2, "nur PNGs, aus beiden Instanzen");
        assert!(shots.iter().all(|s| ids.contains(&s.instance_id)));
        assert!(shots.iter().any(|s| s.instance_name == "Alpha"));
        assert!(shots.iter().all(|s| s.size > 0));
    }

    #[tokio::test]
    async fn creates_and_reuses_thumbnails() {
        let (_dir, launcher, ids) = launcher_with_shots().await;
        let thumb = launcher.screenshot_thumbnail(&ids[0], "Alpha-1.png").await.unwrap();
        assert!(thumb.is_file());
        let decoded = image::ImageReader::open(&thumb).unwrap().with_guessed_format().unwrap().decode().unwrap();
        assert_eq!(decoded.width(), THUMB_WIDTH, "auf Kachelbreite verkleinert");

        // Zweiter Aufruf nimmt die Datei aus dem Cache (gleicher Pfad).
        let again = launcher.screenshot_thumbnail(&ids[0], "Alpha-1.png").await.unwrap();
        assert_eq!(thumb, again);

        // Fremde Dateinamen werden abgewiesen.
        assert!(launcher.screenshot_thumbnail(&ids[0], "..\\..\\instance.json").await.is_err());
        assert!(launcher.screenshot_thumbnail(&ids[0], "gibt-es-nicht.png").await.is_err());
    }

    #[tokio::test]
    async fn reads_pixels_for_the_clipboard() {
        let (_dir, launcher, ids) = launcher_with_shots().await;
        let image = launcher.screenshot_rgba(&ids[0], "Alpha-1.png").await.unwrap();
        assert_eq!((image.width, image.height), (640, 360));
        assert_eq!(image.pixels.len(), 640 * 360 * 4);
    }

    #[tokio::test]
    async fn deleting_removes_file_and_thumbnail() {
        let (_dir, launcher, ids) = launcher_with_shots().await;
        let thumb = launcher.screenshot_thumbnail(&ids[0], "Alpha-1.png").await.unwrap();
        launcher.trash_screenshot(&ids[0], "Alpha-1.png").await.unwrap();
        assert!(!thumb.exists());
        assert!(launcher.all_screenshots().await.unwrap().iter().all(|s| s.file_name != "Alpha-1.png"));
        assert!(launcher.trash_screenshot(&ids[0], "Alpha-1.png").await.is_err(), "zweimal löschen geht nicht");
    }
}
