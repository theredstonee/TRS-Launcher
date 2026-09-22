//! Bilder: Instanz-Bilder (vom Nutzer gewählt oder vom Modpack) und die
//! Prüfregeln für fremde Bild-Quellen.
//!
//! Bilder werden nie dekodiert, nur am Dateikopf erkannt und unverändert als
//! `instances/<id>/icon-<zufall>.<ext>` gespeichert. Der zufällige Name sorgt
//! dafür, dass das Webview nach einem Wechsel nicht das alte Bild aus dem Cache
//! zeigt.
//!
//! Banner (breites Titelbild einer Instanz) folgen denselben Regeln, liegen
//! als `instances/<id>/banner-<zufall>.<ext>` daneben und dürfen etwas größer
//! sein. Sie stehen nicht in `instance.json`: Die eine Datei mit festem
//! Namensmuster ist selbst die Quelle.

use std::path::{Path, PathBuf};

use crate::history::{self, HistoryEntry, HistoryKind};
use crate::instance::Instance;
use crate::{Error, Launcher, Result, fsutil};

pub const MAX_ICON_BYTES: u64 = 5 * 1024 * 1024;
/// Banner sind meist Screenshots in voller Auflösung.
pub const MAX_BANNER_BYTES: u64 = 10 * 1024 * 1024;
const BANNER_TOO_LARGE: &str = "Das Banner darf höchstens 10 MB groß sein.";
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
    is_own_image_name("icon-", name)
}

/// Banner-Dateinamen: `banner-<8 hex>.<png|jpg|webp>`.
pub fn is_banner_file_name(name: &str) -> bool {
    is_own_image_name("banner-", name)
}

fn is_own_image_name(prefix: &str, name: &str) -> bool {
    let Some(rest) = name.strip_prefix(prefix) else { return false };
    let Some((token, ext)) = rest.split_once('.') else { return false };
    token.len() == 8
        && token.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        && matches!(ext, "png" | "jpg" | "webp")
}

fn new_file_name(format: ImageFormat) -> String {
    new_named("icon-", format)
}

fn new_named(prefix: &str, format: ImageFormat) -> String {
    format!("{prefix}{}.{}", &uuid::Uuid::new_v4().simple().to_string()[..8], format.extension())
}

/// Wie [`validate_image`], nur mit der Größengrenze für Banner.
pub fn validate_banner(bytes: &[u8]) -> Result<ImageFormat> {
    if bytes.is_empty() || bytes.len() as u64 > MAX_BANNER_BYTES {
        return Err(Error::validation(BANNER_TOO_LARGE));
    }
    sniff(bytes).ok_or_else(|| Error::validation("Nur PNG-, JPEG- und WebP-Bilder werden unterstützt."))
}

/// Das Banner im Instanz-Ordner (bei mehreren – nur nach einem Wettlauf
/// möglich – das neueste).
pub fn find_banner(instance_dir: &Path) -> Option<PathBuf> {
    std::fs::read_dir(instance_dir)
        .ok()?
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.file_name().to_str().is_some_and(is_banner_file_name))
        .filter_map(|entry| {
            let meta = entry.metadata().ok().filter(|m| m.is_file())?;
            Some((meta.modified().ok(), entry.path()))
        })
        .max_by_key(|(modified, _)| *modified)
        .map(|(_, path)| path)
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

impl Launcher {
    /// Absoluter Pfad des Instanz-Banners, falls vorhanden.
    pub fn instance_banner_path(&self, instance: &Instance) -> Option<PathBuf> {
        find_banner(&self.paths().instance_dir(&instance.id))
    }

    /// Setzt ein neues Banner aus einer lokalen Datei (Pfad aus dem nativen Dialog).
    pub async fn set_instance_banner_from_file(&self, id: &str, file: &Path) -> Result<Instance> {
        let meta = tokio::fs::metadata(file).await.map_err(|e| Error::io(file, e))?;
        if !meta.is_file() || meta.len() > MAX_BANNER_BYTES {
            return Err(Error::validation(BANNER_TOO_LARGE));
        }
        let bytes = tokio::fs::read(file).await.map_err(|e| Error::io(file, e))?;
        self.set_instance_banner_bytes(id, &bytes).await
    }

    /// Nimmt einen Screenshot der Instanz als Banner (Dateiname wird geprüft).
    pub async fn set_instance_banner_from_screenshot(&self, id: &str, file_name: &str) -> Result<Instance> {
        let instance = self.instances().get(id).await?;
        let file = crate::extras::screenshot_path(self.paths(), &instance.id, file_name)?;
        self.set_instance_banner_from_file(&instance.id, &file).await
    }

    async fn set_instance_banner_bytes(&self, id: &str, bytes: &[u8]) -> Result<Instance> {
        let format = validate_banner(bytes)?;
        let instance = self.instances().get(id).await?;
        let dir = self.paths().instance_dir(&instance.id);
        let name = new_named("banner-", format);
        fsutil::write_atomic(&dir.join(&name), bytes).await?;
        remove_old_images(&dir, is_banner_file_name, Some(&name)).await;
        history::record(self.paths(), &instance.id, HistoryEntry::new(HistoryKind::IconChanged).detail("banner")).await;
        Ok(instance)
    }

    pub async fn remove_instance_banner(&self, id: &str) -> Result<Instance> {
        let instance = self.instances().get(id).await?;
        remove_old_images(&self.paths().instance_dir(&instance.id), is_banner_file_name, None).await;
        Ok(instance)
    }
}

/// Entfernt alle eigenen Bilddateien außer `keep`.
async fn remove_old_icons(dir: &Path, keep: Option<&str>) {
    remove_old_images(dir, is_icon_file_name, keep).await;
}

async fn remove_old_images(dir: &Path, own: fn(&str) -> bool, keep: Option<&str>) {
    let Ok(mut entries) = tokio::fs::read_dir(dir).await else { return };
    while let Ok(Some(entry)) = entries.next_entry().await {
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
        if own(&name) && Some(name.as_str()) != keep {
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

    #[test]
    fn banner_file_names_and_size() {
        assert!(is_banner_file_name("banner-0a1b2c3d.jpg"));
        assert!(is_banner_file_name(&new_named("banner-", ImageFormat::Png)));
        for bad in ["banner.png", "icon-0a1b2c3d.png", "banner-../../x.png", "banner-0a1b2c3d.svg", "banner-0a1b2c3d.png.exe"] {
            assert!(!is_banner_file_name(bad), "{bad:?}");
        }
        assert!(!is_icon_file_name("banner-0a1b2c3d.png"));
        let mut big = PNG.to_vec();
        big.resize((MAX_ICON_BYTES + 1) as usize, 0);
        assert!(validate_image(&big).is_err() && validate_banner(&big).is_ok(), "Banner dürfen größer sein");
        big.resize((MAX_BANNER_BYTES + 1) as usize, 0);
        assert!(validate_banner(&big).is_err());
        assert!(validate_banner(b"<svg></svg>").is_err());
    }

    #[tokio::test]
    async fn set_and_remove_banner() {
        let dir = tempfile::tempdir().unwrap();
        let launcher = Launcher::init(dir.path(), Arc::new(|_| {})).await.unwrap();
        let inst = launcher
            .instances()
            .create(NewInstance { name: "Banner".into(), game_version: "1.21.1".into(), loader: Loader::vanilla() })
            .await
            .unwrap();
        assert!(launcher.instance_banner_path(&inst).is_none());

        let file = dir.path().join("fake.jpg");
        tokio::fs::write(&file, b"GIF89a....").await.unwrap();
        assert!(launcher.set_instance_banner_from_file(&inst.id, &file).await.is_err());

        tokio::fs::write(&file, PNG).await.unwrap();
        launcher.set_instance_banner_from_file(&inst.id, &file).await.unwrap();
        let first = launcher.instance_banner_path(&inst).unwrap();
        launcher.set_instance_banner_from_file(&inst.id, &file).await.unwrap();
        let second = launcher.instance_banner_path(&inst).unwrap();
        assert_ne!(first, second);
        assert!(!first.exists(), "altes Banner wird aufgeräumt");
        assert!(second.extension().is_some_and(|e| e == "png"));
        // Instanz-Bild und Banner kommen sich nicht in die Quere.
        let with_icon = launcher.set_instance_icon_from_file(&inst.id, &file).await.unwrap();
        assert!(launcher.instance_icon_path(&with_icon).is_some() && second.exists());

        // Screenshots: nur echte Dateien aus dem Screenshot-Ordner.
        assert!(launcher.set_instance_banner_from_screenshot(&inst.id, "../instance.json").await.is_err());
        assert!(launcher.set_instance_banner_from_screenshot(&inst.id, "fehlt.png").await.is_err());
        let shots = crate::extras::screenshots_dir(launcher.paths(), &inst.id);
        tokio::fs::create_dir_all(&shots).await.unwrap();
        tokio::fs::write(shots.join("2026-09-22_12.00.00.png"), PNG).await.unwrap();
        launcher.set_instance_banner_from_screenshot(&inst.id, "2026-09-22_12.00.00.png").await.unwrap();
        assert!(!second.exists() && launcher.instance_banner_path(&inst).is_some());

        launcher.remove_instance_banner(&inst.id).await.unwrap();
        assert!(launcher.instance_banner_path(&inst).is_none());
        assert!(launcher.instance_icon_path(&with_icon).is_some(), "Entfernen lässt das Bild stehen");
        assert!(launcher.remove_instance_banner("../x").await.is_err());
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
