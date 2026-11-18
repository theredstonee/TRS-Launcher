//! Welt einer Instanz auf den Partner-Server: packen (`world/` oben im ZIP), direkt zu
//! CraftingHosting hochladen (kurzlebige URL von der TRS API) und einspielen lassen.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::Req;
use super::craftinghosting::{BASE, Server};
use crate::{Error, Launcher, Result, USER_AGENT};

/// Nur Upload-Adressen auf diesen Hosts werden angenommen.
pub const UPLOAD_HOST_SUFFIX: &str = ".craftinghosting.free";
/// Dateiname des Archivs (Upload und Import).
pub const WORLD_FILE: &str = "trs-world.zip";
const CHUNK: usize = 256 * 1024;

/// Fortschritt: `zip`, `upload`, `import` mit Prozent.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct WorldProgress {
    pub phase: &'static str,
    pub percent: u8,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiUploadUrl {
    url: String,
}

/// Upload-Adresse prüfen: HTTPS, Host `*.craftinghosting.free`, keine Zugangsdaten.
/// `allow_local` nur für Tests (Attrappe auf `127.0.0.1`).
pub(crate) fn upload_url(input: &str, allow_local: bool) -> Option<reqwest::Url> {
    let url = reqwest::Url::parse(input.trim()).ok()?;
    if !url.username().is_empty() || url.password().is_some() || input.len() > 2048 {
        return None;
    }
    let host = url.host_str()?.to_ascii_lowercase();
    let partner = url.scheme() == "https" && host.ends_with(UPLOAD_HOST_SUFFIX) && host.len() > UPLOAD_HOST_SUFFIX.len();
    let local = allow_local && url.scheme() == "http" && host == "127.0.0.1";
    (partner || local).then_some(url)
}

fn upload_failed() -> Error {
    Error::TrsApi {
        kind: "trs_api",
        code: "upload_failed".into(),
        msg: crate::msg!("craftinghosting.uploadFailed", "Die Welt konnte nicht hochgeladen werden – bitte erneut versuchen."),
    }
}

fn server_running() -> Error {
    Error::validation(crate::msg!("craftinghosting.stopFirst", "Stoppe den Server, bevor du eine Welt einspielst."))
}

/// Löscht das Archiv, egal wie es ausgeht.
struct TempFile(PathBuf);

impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

async fn upload(url: reqwest::Url, file: &Path, on_progress: Arc<dyn Fn(WorldProgress) + Send + Sync>) -> Result<()> {
    let len = tokio::fs::metadata(file).await.map_err(|e| Error::io(file, e))?.len();
    let input = tokio::fs::File::open(file).await.map_err(|e| Error::io(file, e))?;
    let progress = Arc::clone(&on_progress);
    let stream = futures::stream::unfold((input, 0u64, 0u8), move |(mut input, sent, last)| {
        let progress = Arc::clone(&progress);
        async move {
            use tokio::io::AsyncReadExt;
            let mut buf = vec![0u8; CHUNK];
            match input.read(&mut buf).await {
                Ok(0) => None,
                Ok(n) => {
                    buf.truncate(n);
                    let sent = sent + n as u64;
                    let percent = u8::try_from((sent * 100 / len.max(1)).min(99)).unwrap_or(99);
                    if percent != last {
                        progress(WorldProgress { phase: "upload", percent });
                    }
                    Some((Ok::<_, std::io::Error>(buf), (input, sent, percent)))
                }
                Err(e) => Some((Err(e), (input, sent, last))),
            }
        }
    });
    let part = reqwest::multipart::Part::stream_with_length(reqwest::Body::wrap_stream(stream), len)
        .file_name(WORLD_FILE)
        .mime_str("application/zip")
        .map_err(|_| upload_failed())?;
    let form = reqwest::multipart::Form::new().part("files", part);
    // Eigener Client: kein Gesamt-Timeout von 60 s, keine Weiterleitungen auf fremde Hosts.
    let client = crate::net::client_builder()
        .user_agent(USER_AGENT)
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(60 * 60))
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let response = client.post(url).multipart(form).send().await.map_err(|e| {
        tracing::warn!("Welt-Upload zu CraftingHosting fehlgeschlagen: {e}");
        upload_failed()
    })?;
    if !response.status().is_success() {
        tracing::warn!("Welt-Upload zu CraftingHosting abgelehnt: HTTP {}", response.status());
        return Err(upload_failed());
    }
    on_progress(WorldProgress { phase: "upload", percent: 100 });
    Ok(())
}

impl Launcher {
    /// Welt `saves/<folder>` einer Instanz auf den Server spielen. Der Server muss aus sein;
    /// die alte Welt (mit Nether und Ende) ersetzt die API.
    pub async fn craftinghosting_upload_world(
        &self,
        instance_id: &str,
        folder: &str,
        on_progress: impl Fn(WorldProgress) + Send + Sync + 'static,
    ) -> Result<Server> {
        let dir = crate::worlds::world_dir(self.paths(), instance_id, folder)?;
        if !dir.join("level.dat").is_file() {
            return Err(Error::validation(crate::msg!("worlds.notFound", "Diese Welt gibt es nicht mehr.")));
        }
        let server = self.craftinghosting_server().await?.ok_or_else(|| super::api_error(404, "no_server", None))?;
        if !matches!(server.state.as_str(), "offline" | "failed") {
            return Err(server_running());
        }
        let on_progress: Arc<dyn Fn(WorldProgress) + Send + Sync> = Arc::new(on_progress);

        let tmp = self.paths().root().join("tmp");
        tokio::fs::create_dir_all(&tmp).await.map_err(|e| Error::io(&tmp, e))?;
        let archive = TempFile(tmp.join(format!("craftinghosting-world-{}.zip", std::process::id())));
        let dest = archive.0.clone();
        let zip_progress = Arc::clone(&on_progress);
        tokio::task::spawn_blocking(move || {
            crate::worlds::write_zip(&dir, "world", &dest, &|percent| zip_progress(WorldProgress { phase: "zip", percent }), &|| false)
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))??;

        let (_, bytes) = self.ch_send(Req::post_empty(format!("{BASE}/server/world/upload"))).await?;
        let target: ApiUploadUrl = super::parse(&bytes, "craftinghosting/world/upload")?;
        let url = upload_url(&target.url, cfg!(test)).ok_or_else(|| {
            tracing::warn!("CraftingHosting-Upload-Adresse abgelehnt");
            super::bad_response()
        })?;
        upload(url, &archive.0, Arc::clone(&on_progress)).await?;
        drop(archive);

        on_progress(WorldProgress { phase: "import", percent: 0 });
        let req = Req::post(format!("{BASE}/server/world/import"), json!({ "file": WORLD_FILE })).timeout(Duration::from_secs(180));
        let server = self.ch_required(req).await?;
        on_progress(WorldProgress { phase: "import", percent: 100 });
        Ok(server)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upload_urls() {
        assert!(upload_url("https://node1.craftinghosting.free/upload?token=abc", false).is_some());
        assert!(upload_url("https://craftinghosting.free/upload", false).is_none());
        assert!(upload_url("http://node1.craftinghosting.free/upload", false).is_none());
        assert!(upload_url("https://evil.com/?x=.craftinghosting.free", false).is_none());
        assert!(upload_url("https://node1.craftinghosting.free.evil.com/", false).is_none());
        assert!(upload_url("https://user:pw@node1.craftinghosting.free/", false).is_none());
        assert!(upload_url("http://127.0.0.1:1234/upload", false).is_none());
        assert!(upload_url("http://127.0.0.1:1234/upload", true).is_some());
    }
}
