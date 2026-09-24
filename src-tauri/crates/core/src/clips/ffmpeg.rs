//! FFmpeg für die Aufnahme – wird erst geladen, wenn Clips eingeschaltet werden.
//!
//! Feste Version (Gyan-Build 9.0.2 „essentials“, GPL-3.0, enthält `gfxcapture`,
//! NVENC/AMF/QSV/Media Foundation und libx264) vom dauerhaften GitHub-Spiegel
//! `GyanD/codexffmpeg`. ZIP und entpackte `ffmpeg.exe` werden gegen fest
//! eingetragene SHA-256-Werte geprüft; geladen wird nur über HTTPS.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, SystemTime};

use futures::StreamExt;
use serde::Serialize;
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;

use crate::paths::Paths;
use crate::{Error, Result, fsutil};

pub const VERSION: &str = "9.0.2";
const URL: &str = "https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip";
const ZIP_SHA256: &str = "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba";
pub const ZIP_SIZE: u64 = 114_768_076;
const EXE_ENTRY: &str = "ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe";
const EXE_SHA256: &str = "3256173f3f8bffd7df12227c68adf68025edb1832273a9530688a7bb1ed8edec";
const EXE_SIZE: u64 = 105_423_872;
const LICENSE_ENTRY: &str = "ffmpeg-9.0.2-essentials_build/LICENSE";
const DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(45 * 60);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FfmpegStatus {
    pub installed: bool,
    pub version: &'static str,
    /// Größe des Downloads (für die Anzeige vor dem Laden).
    pub download_bytes: u64,
}

pub struct Ffmpeg {
    dir: PathBuf,
    tools: PathBuf,
    /// Geprüfte Datei: (Größe, Änderungszeit) – dann nicht jedes Mal 100 MB hashen.
    verified: Mutex<Option<(u64, SystemTime)>>,
    install: tokio::sync::Mutex<()>,
}

impl Ffmpeg {
    pub fn new(paths: &Paths) -> Self {
        let tools = paths.root().join("tools");
        Self {
            dir: tools.join(format!("ffmpeg-{VERSION}")),
            tools,
            verified: Mutex::default(),
            install: tokio::sync::Mutex::default(),
        }
    }

    pub fn exe(&self) -> PathBuf {
        self.dir.join("ffmpeg.exe")
    }

    fn verified(&self) -> std::sync::MutexGuard<'_, Option<(u64, SystemTime)>> {
        self.verified.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Pfad zur geprüften `ffmpeg.exe` oder `None`.
    pub async fn ready(&self) -> Option<PathBuf> {
        let exe = self.exe();
        let meta = tokio::fs::metadata(&exe).await.ok()?;
        let stamp = (meta.len(), meta.modified().ok()?);
        if meta.len() != EXE_SIZE {
            return None;
        }
        if *self.verified() == Some(stamp) {
            return Some(exe);
        }
        let hash = crate::client_mod_update::sha256_of_file(&exe).await.ok()?;
        if !hash.eq_ignore_ascii_case(EXE_SHA256) {
            tracing::warn!("ffmpeg.exe hat eine falsche Prüfsumme – wird neu geladen");
            return None;
        }
        *self.verified() = Some(stamp);
        Some(exe)
    }

    pub async fn status(&self) -> FfmpegStatus {
        FfmpegStatus { installed: self.ready().await.is_some(), version: VERSION, download_bytes: ZIP_SIZE }
    }

    /// Lädt und prüft FFmpeg (einmal gleichzeitig). Läuft in einer Aufgabe mit
    /// Fortschritt, Pause und Abbruch.
    pub async fn install(&self) -> Result<PathBuf> {
        self.install_with(&|_| {}).await
    }

    /// Wie [`Self::install`], meldet den Download-Fortschritt in Prozent.
    pub async fn install_with(&self, on_progress: &(dyn Fn(f64) + Sync)) -> Result<PathBuf> {
        let _guard = self.install.lock().await;
        if let Some(exe) = self.ready().await {
            return Ok(exe);
        }
        fsutil::ensure_dir(&self.dir).await?;
        let zip = self.dir.join(format!("download.part-{}", uuid::Uuid::new_v4().simple()));
        let result = self.install_from(&zip, on_progress).await;
        let _ = tokio::fs::remove_file(&zip).await;
        let exe = result?;
        self.remove_old_versions().await;
        Ok(exe)
    }

    async fn install_from(&self, zip: &Path, on_progress: &(dyn Fn(f64) + Sync)) -> Result<PathBuf> {
        download(zip, on_progress).await?;
        let (dir, zip_path) = (self.dir.clone(), zip.to_owned());
        tokio::task::spawn_blocking(move || extract(&zip_path, &dir))
            .await
            .map_err(|e| Error::Internal(e.to_string()))??;
        *self.verified() = None;
        self.ready().await.ok_or_else(|| Error::download(URL, "ffmpeg.exe: Prüfsumme stimmt nicht"))
    }

    async fn remove_old_versions(&self) {
        let Ok(mut entries) = tokio::fs::read_dir(&self.tools).await else { return };
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with("ffmpeg-") && entry.path() != self.dir {
                let _ = tokio::fs::remove_dir_all(entry.path()).await;
            }
        }
    }
}

/// Eigener Client: nur HTTPS, höchstens 5 Weiterleitungen (GitHub → Objektspeicher).
fn client() -> Result<reqwest::Client> {
    Ok(reqwest::Client::builder()
        .user_agent(crate::USER_AGENT)
        .https_only(true)
        .redirect(reqwest::redirect::Policy::limited(5))
        .connect_timeout(Duration::from_secs(20))
        .read_timeout(Duration::from_secs(60))
        .build()?)
}

async fn download(target: &Path, on_progress: &(dyn Fn(f64) + Sync)) -> Result<()> {
    let response = client()?.get(URL).timeout(DOWNLOAD_TIMEOUT).send().await?.error_for_status()?;
    if response.content_length().is_some_and(|len| len != ZIP_SIZE) {
        return Err(Error::download(URL, "unerwartete Größe"));
    }
    let mut out = tokio::fs::File::create(target).await.map_err(|e| Error::io(target, e))?;
    let mut hasher = Sha256::new();
    let mut written = 0u64;
    let mut stream = response.bytes_stream();
    crate::task::add_total(ZIP_SIZE);
    loop {
        crate::task::checkpoint().await?;
        let Some(chunk) = stream.next().await else { break };
        let chunk = chunk?;
        written += chunk.len() as u64;
        crate::task::add_done(chunk.len() as i64);
        if written > ZIP_SIZE {
            return Err(Error::download(URL, "unerwartete Größe"));
        }
        hasher.update(&chunk);
        out.write_all(&chunk).await.map_err(|e| Error::io(target, e))?;
        on_progress(written as f64 * 100.0 / ZIP_SIZE as f64);
    }
    out.flush().await.map_err(|e| Error::io(target, e))?;
    drop(out);
    let hash: String = hasher.finalize().iter().map(|b| format!("{b:02x}")).collect();
    if written != ZIP_SIZE || hash != ZIP_SHA256 {
        return Err(Error::download(URL, "Prüfsumme stimmt nicht"));
    }
    Ok(())
}

/// Entpackt nur `ffmpeg.exe` und die Lizenz (feste Namen im Archiv, kein Zip-Slip möglich).
fn extract(zip: &Path, dir: &Path) -> Result<()> {
    let file = std::fs::File::open(zip).map_err(|e| Error::io(zip, e))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| Error::download(URL, format!("ZIP: {e}")))?;
    for (entry, name) in [(EXE_ENTRY, "ffmpeg.exe"), (LICENSE_ENTRY, "LICENSE.txt")] {
        let mut source = archive.by_name(entry).map_err(|e| Error::download(URL, format!("{entry}: {e}")))?;
        if entry == EXE_ENTRY && source.size() != EXE_SIZE {
            return Err(Error::download(URL, "ffmpeg.exe: unerwartete Größe"));
        }
        let target = dir.join(name);
        let tmp = dir.join(format!("{name}.part"));
        let mut out = std::fs::File::create(&tmp).map_err(|e| Error::io(&tmp, e))?;
        std::io::copy(&mut source, &mut out).map_err(|e| Error::io(&tmp, e))?;
        drop(out);
        std::fs::rename(&tmp, &target).map_err(|e| Error::io(&target, e))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nur_https_und_feste_pruefsummen() {
        assert!(URL.starts_with("https://github.com/GyanD/codexffmpeg/releases/download/"));
        assert!(URL.contains(VERSION));
        assert!(EXE_ENTRY.contains(VERSION));
        for hash in [ZIP_SHA256, EXE_SHA256] {
            assert_eq!(hash.len(), 64);
            assert!(hash.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)));
        }
    }

    #[tokio::test]
    async fn falsche_datei_gilt_nicht_als_installiert() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let ffmpeg = Ffmpeg::new(&paths);
        assert!(ffmpeg.ready().await.is_none());
        tokio::fs::create_dir_all(ffmpeg.exe().parent().unwrap()).await.unwrap();
        tokio::fs::write(ffmpeg.exe(), b"MZ nicht das echte ffmpeg").await.unwrap();
        assert!(ffmpeg.ready().await.is_none(), "falsche Größe/Prüfsumme");
        assert!(!ffmpeg.status().await.installed);
    }
}
