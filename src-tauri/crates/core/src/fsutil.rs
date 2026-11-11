use std::path::Path;

use serde::{Serialize, de::DeserializeOwned};
use tokio::fs;

use crate::{Error, Result};

pub async fn ensure_dir(path: &Path) -> Result<()> {
    fs::create_dir_all(path).await.map_err(|e| Error::io(path, e))
}

/// Schreibt erst in eine Temp-Datei und benennt dann um, damit ein Absturz
/// mitten im Schreiben keine halbe Datei hinterlässt.
pub async fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        ensure_dir(parent).await?;
    }
    let tmp = path.with_extension(format!("tmp-{}", uuid::Uuid::new_v4().simple()));
    fs::write(&tmp, bytes).await.map_err(|e| Error::io(&tmp, e))?;
    if let Err(e) = fs::rename(&tmp, path).await {
        let _ = fs::remove_file(&tmp).await;
        return Err(Error::io(path, e));
    }
    Ok(())
}

/// Kopiert über eine Temp-Datei: Scheitert das Umbenennen (Datei gesperrt), bleibt das Ziel unverändert.
pub async fn copy_atomic(from: &Path, to: &Path) -> Result<()> {
    let tmp = to.with_extension(format!("tmp-{}", uuid::Uuid::new_v4().simple()));
    fs::copy(from, &tmp).await.map_err(|e| Error::io(&tmp, e))?;
    if let Err(e) = fs::rename(&tmp, to).await {
        let _ = fs::remove_file(&tmp).await;
        return Err(Error::io(to, e));
    }
    Ok(())
}

/// Beschädigte Datei als `<name>.corrupt-<unix-zeit>` beiseitelegen, damit der nächste Schreibvorgang sie nicht still überschreibt.
pub async fn set_aside_corrupt(path: &Path) {
    let Some(name) = path.file_name() else { return };
    let mut aside = name.to_os_string();
    aside.push(format!(".corrupt-{}", chrono::Utc::now().timestamp()));
    let aside = path.with_file_name(aside);
    match fs::rename(path, &aside).await {
        Ok(()) => tracing::warn!("Beschädigte Datei gesichert als {}", aside.display()),
        Err(e) => tracing::warn!("Beschädigte Datei {} konnte nicht gesichert werden: {e}", path.display()),
    }
}

/// Wie [`write_atomic`], blockierend (für Start und `spawn_blocking`).
pub fn write_atomic_sync(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
    }
    let tmp = path.with_extension(format!("tmp-{}", uuid::Uuid::new_v4().simple()));
    std::fs::write(&tmp, bytes).map_err(|e| Error::io(&tmp, e))?;
    if let Err(e) = std::fs::rename(&tmp, path) {
        let _ = std::fs::remove_file(&tmp);
        return Err(Error::io(path, e));
    }
    Ok(())
}

pub async fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    let bytes =
        serde_json::to_vec_pretty(value).map_err(|e| Error::json(path.display().to_string(), e))?;
    write_atomic(path, &bytes).await
}

/// `Ok(None)`, wenn die Datei nicht existiert.
pub async fn read_json<T: DeserializeOwned>(path: &Path) -> Result<Option<T>> {
    match fs::read(path).await {
        Ok(bytes) => serde_json::from_slice(&bytes)
            .map(Some)
            .map_err(|e| Error::json(path.display().to_string(), e)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(Error::io(path, e)),
    }
}
