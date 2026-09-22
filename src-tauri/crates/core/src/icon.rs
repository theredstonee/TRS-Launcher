//! Bilder: Instanz-Bilder (vom Nutzer gewählt oder vom Modpack) und die
//! Prüfregeln für fremde Bild-Quellen.
//!
//! Bilder werden nie dekodiert, nur am Dateikopf erkannt und unverändert als
//! `instances/<id>/icon-<zufall>.<ext>` gespeichert. Der zufällige Name sorgt
//! dafür, dass das Webview nach einem Wechsel nicht das alte Bild aus dem Cache
//! zeigt.

use std::path::{Path, PathBuf};

use crate::history::{self, HistoryEntry, HistoryKind};
use crate::instance::Instance;
use crate::{Error, Launcher, Result, fsutil};

pub const MAX_ICON_BYTES: u64 = 5 * 1024 * 1024;
const MODRINTH_CDN: &str = "https://cdn.modrinth.com/";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImageFormat {
    Png,
    Jpeg,
    Webp,
}

impl ImageFormat {
    pub fn extension(self) -> &'static str {
        match self {
            Self::Png => "png",
            Self::Jpeg => "jpg",
            Self::Webp => "webp",
        }
    }
}

/// Erkennt PNG, JPEG und WebP am Dateikopf – die Endung zählt nicht.
pub fn sniff(bytes: &[u8]) -> Option<ImageFormat> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some(ImageFormat::Png)
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some(ImageFormat::Jpeg)
    } else if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some(ImageFormat::Webp)
    } else {
        None
    }
}

/// Prüft Größe und Format; liefert das erkannte Format.
pub fn validate_image(bytes: &[u8]) -> Result<ImageFormat> {
    if bytes.is_empty() || bytes.len() as u64 > MAX_ICON_BYTES {
        return Err(Error::validation("Das Bild darf höchstens 5 MB groß sein."));
    }
    sniff(bytes).ok_or_else(|| Error::validation("Nur PNG-, JPEG- und WebP-Bilder werden unterstützt."))
}

/// Bild-URLs aus Modrinth-Daten: nur Modrinths eigenes CDN, nur HTTPS, keine
/// Tricks mit Zugangsdaten, Backslashes oder Leerzeichen.
pub fn is_allowed_icon_url(url: &str) -> bool {
    url.len() <= 512
        && url.starts_with(MODRINTH_CDN)
        && url.len() > MODRINTH_CDN.len()
        && !url.chars().any(|c| c.is_whitespace() || c.is_control() || matches!(c, '\\' | '"' | '\'' | '<' | '>' | '@'))
}

/// Dateinamen, die wir selbst vergeben: `icon-<8 hex>.<png|jpg|webp>`.
pub fn is_icon_file_name(name: &str) -> bool {
    let Some(rest) = name.strip_prefix("icon-") else { return false };
    let Some((token, ext)) = rest.split_once('.') else { return false };
    token.len() == 8
        && token.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        && matches!(ext, "png" | "jpg" | "webp")
}

fn new_file_name(format: ImageFormat) -> String {
    format!("icon-{}.{}", &uuid::Uuid::new_v4().simple().to_string()[..8], format.extension())
}

impl Launcher {
    /// Absoluter Pfad des Instanz-Bilds, falls vorhanden.
    pub fn instance_icon_path(&self, instance: &Instance) -> Option<PathBuf> {
        let name = instance.icon.as_deref().filter(|n| is_icon_file_name(n))?;
        let path = self.paths().instance_dir(&instance.id).join(name);
        path.is_file().then_some(path)
    }

    /// Setzt ein neues Bild aus einer lokalen Datei (Pfad aus dem nativen Dialog).
    pub async fn set_instance_icon_from_file(&self, id: &str, file: &Path) -> Result<Instance> {
        let meta = tokio::fs::metadata(file).await.map_err(|e| Error::io(file, e))?;
        if !meta.is_file() || meta.len() > MAX_ICON_BYTES {
            return Err(Error::validation("Das Bild darf höchstens 5 MB groß sein."));
        }
        let bytes = tokio::fs::read(file).await.map_err(|e| Error::io(file, e))?;
        self.set_instance_icon_bytes(id, &bytes).await
    }

    /// Lädt ein Bild von Modrinths CDN (z. B. das Modpack-Icon).
    pub async fn set_instance_icon_from_url(&self, id: &str, url: &str) -> Result<Instance> {
        if !is_allowed_icon_url(url) {
            return Err(Error::validation("Bildquelle nicht erlaubt"));
        }
        let response = self.http().get(url).send().await?.error_for_status()?;
        if response.content_length().is_some_and(|len| len > MAX_ICON_BYTES) {
            return Err(Error::validation("Das Bild darf höchstens 5 MB groß sein."));
        }
        let bytes = response.bytes().await?;
        self.set_instance_icon_bytes(id, &bytes).await
    }

    async fn set_instance_icon_bytes(&self, id: &str, bytes: &[u8]) -> Result<Instance> {
        let format = validate_image(bytes)?;
        let instance = self.instances().get(id).await?;
        let dir = self.paths().instance_dir(&instance.id);
        let name = new_file_name(format);
        fsutil::write_atomic(&dir.join(&name), bytes).await?;

        let updated = self.instances().set_icon(&instance.id, Some(name)).await?;
        remove_old_icons(&dir, updated.icon.as_deref()).await;
        history::record(self.paths(), &updated.id, HistoryEntry::new(HistoryKind::IconChanged)).await;
        Ok(updated)
    }

    pub async fn remove_instance_icon(&self, id: &str) -> Result<Instance> {
        let instance = self.instances().get(id).await?;
        let updated = self.instances().set_icon(&instance.id, None).await?;
        remove_old_icons(&self.paths().instance_dir(&updated.id), None).await;
        Ok(updated)
    }
}

/// Entfernt alle eigenen Bilddateien außer `keep`.
async fn remove_old_icons(dir: &Path, keep: Option<&str>) {
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else { return };
    while let Ok(Some(entry)) = entries.next_entry().await {
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
        if is_icon_file_name(&name) && Some(name.as_str()) != keep {
            let _ = tokio::fs::remove_file(entry.path()).await;
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::instance::{Loader, NewInstance};

    const PNG: &[u8] = b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR";

    #[test]
    fn sniffs_magic_bytes() {
        assert_eq!(sniff(PNG), Some(ImageFormat::Png));
        assert_eq!(sniff(&[0xFF, 0xD8, 0xFF, 0xE0, 0, 0]), Some(ImageFormat::Jpeg));
        assert_eq!(sniff(b"RIFF\x10\0\0\0WEBPVP8 "), Some(ImageFormat::Webp));
        // Endung egal, Inhalt zählt: GIF, SVG, HTML und Kaputtes werden abgelehnt.
        for bad in [&b"GIF89a....."[..], b"<svg xmlns=", b"<html>", b"RIFF\0\0\0\0WAVE", b"\x89PN", b""] {
            assert!(sniff(bad).is_none(), "{bad:?}");
        }
        assert!(validate_image(b"").is_err());
        assert!(validate_image(&vec![0xFF; (MAX_ICON_BYTES + 1) as usize]).is_err());
        assert_eq!(validate_image(PNG).unwrap().extension(), "png");
    }

    #[test]
    fn icon_url_allow_list() {
        assert!(is_allowed_icon_url("https://cdn.modrinth.com/data/AANobbMI/icon.png"));
        for bad in [
            "http://cdn.modrinth.com/data/x/icon.png",
            "https://cdn.modrinth.com.evil.example/icon.png",
            "https://evil.example/https://cdn.modrinth.com/",
            "https://cdn.modrinth.com/",
            "https://cdn.modrinth.com/a b.png",
            "https://cdn.modrinth.com/x\\..\\y.png",
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
        ] {
            assert!(!is_allowed_icon_url(bad), "{bad:?}");
        }
    }

    #[test]
    fn icon_file_names() {
        assert!(is_icon_file_name("icon-0a1b2c3d.png"));
        assert!(is_icon_file_name(&new_file_name(ImageFormat::Webp)));
        for bad in ["icon.png", "icon-0a1b2c3d.gif", "icon-../../x.png", "icon-0A1B2C3D.png", "icon-0a1b2c3d.png.exe"] {
            assert!(!is_icon_file_name(bad), "{bad:?}");
        }
    }

    #[tokio::test]
    async fn set_and_remove_icon() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
        let inst = launcher
            .instances()
            .create(NewInstance { name: "Bild".into(), game_version: "1.21.1".into(), loader: Loader::vanilla() })
            .await
            .unwrap();

        let file = dir.path().join("fake.png");
        tokio::fs::write(&file, b"<svg></svg>").await.unwrap();
        assert!(launcher.set_instance_icon_from_file(&inst.id, &file).await.is_err());

        tokio::fs::write(&file, PNG).await.unwrap();
        let first = launcher.set_instance_icon_from_file(&inst.id, &file).await.unwrap();
        let first_path = launcher.instance_icon_path(&first).unwrap();
        let second = launcher.set_instance_icon_from_file(&inst.id, &file).await.unwrap();
        assert_ne!(first.icon, second.icon);
        assert!(!first_path.exists(), "altes Bild wird aufgeräumt");

        let cleared = launcher.remove_instance_icon(&inst.id).await.unwrap();
        assert!(cleared.icon.is_none() && launcher.instance_icon_path(&cleared).is_none());
        assert!(launcher.set_instance_icon_from_url(&inst.id, "https://evil.example/x.png").await.is_err());
    }
}
