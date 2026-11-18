//! Welten des Partner-Servers: auflisten, aktiv setzen, löschen/zurücksetzen und herunterladen
//! (Vertrag: `docs/craftinghosting.md`, Abschnitt „Worlds“). Hochladen: [`super::craftinghosting_world`].
//!
//! Beim Herunterladen laufen die Dateien direkt von CraftingHosting in ein ZIP – nie über die
//! TRS API und nie mit dem TRS-Token.

use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::json;

use super::craftinghosting::BASE;
use super::craftinghosting_files::server_path;
use super::craftinghosting_world::{WorldProgress, partner_url};
use super::ops::encode_query;
use super::{Req, validate};
use crate::{Error, Launcher, Result, USER_AGENT};

pub const WORLD_NAME_MAX: usize = 32;
/// Höchstens so viele Pfade je `download-urls`.
const URL_BATCH: usize = 100;
/// Gleichzeitige Downloads.
const PARALLEL: usize = 4;
const FILES_MAX: usize = 100_000;
/// Größte einzelne Datei (Regionen sind wenige MB).
const FILE_MAX: u64 = 512 * 1024 * 1024;

/// Weltname: `^[A-Za-z0-9_-]{1,32}$`. Was sonst noch verboten ist (reservierte Ordner), entscheidet die API.
pub fn world_name(input: &str) -> Result<String> {
    let name = input.trim();
    if (1..=WORLD_NAME_MAX).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-')) {
        Ok(name.to_owned())
    } else {
        Err(Error::validation(crate::msg!(
            "craftinghosting.invalidWorldName",
            "Weltname: 1 bis 32 Zeichen aus A–Z, a–z, 0–9, _ und -."
        )))
    }
}

fn world_name_ok(name: &str) -> bool {
    world_name(name).is_ok_and(|n| n == name)
}

fn download_failed() -> Error {
    Error::TrsApi {
        kind: "trs_api",
        code: "download_failed".into(),
        msg: crate::msg!("craftinghosting.downloadFailed", "Die Welt konnte nicht heruntergeladen werden – bitte erneut versuchen."),
    }
}

// --- Ansichten --------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct WorldEntry {
    pub name: String,
    pub active: bool,
    /// z. B. `overworld`, `nether`, `the_end`.
    pub dimensions: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Worlds {
    /// `level-name` des Servers.
    pub active: String,
    /// Gibt es die aktive Welt schon (sonst entsteht sie beim nächsten Start)?
    pub exists: bool,
    pub worlds: Vec<WorldEntry>,
}

/// Ergebnis eines Downloads.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldDownload {
    pub file_name: String,
    pub files: usize,
    pub bytes: u64,
    /// Die API hat nicht alle Dateien gelistet.
    pub truncated: bool,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiWorld {
    name: String,
    active: bool,
    dimensions: Vec<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiWorlds {
    active: String,
    exists: bool,
    worlds: Vec<ApiWorld>,
}

fn dimension_ok(d: &str) -> bool {
    (1..=32).contains(&d.len()) && d.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'_' | b'-' | b':'))
}

impl ApiWorlds {
    fn cleaned(self) -> Worlds {
        let active = if world_name_ok(&self.active) { self.active } else { "world".into() };
        let worlds = self
            .worlds
            .into_iter()
            .filter(|w| world_name_ok(&w.name))
            .map(|w| WorldEntry {
                active: w.active || w.name == active,
                dimensions: w.dimensions.into_iter().filter(|d| dimension_ok(d)).take(8).collect(),
                name: w.name,
            })
            .take(200)
            .collect();
        Worlds { active, exists: self.exists, worlds }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiWorldFile {
    path: String,
    size: Option<f64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiWorldFiles {
    files: Vec<ApiWorldFile>,
    truncated: bool,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiUrl {
    path: String,
    url: String,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiUrls {
    urls: Vec<ApiUrl>,
}

#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn size(v: Option<f64>) -> u64 {
    v.filter(|n| n.is_finite() && *n >= 0.0).map_or(0, |n| n as u64)
}

/// Datei gehört zur Welt: erster Ordner ist `<name>`, `<name>_nether` oder `<name>_the_end`.
fn belongs_to(path: &str, name: &str) -> bool {
    let first = path.trim_start_matches('/').split('/').next().unwrap_or_default();
    path.matches('/').count() >= 2 && [name.to_owned(), format!("{name}_nether"), format!("{name}_the_end")].iter().any(|n| n == first)
}

/// Löscht die halbe Datei, falls der Download nicht durchläuft.
struct Partial(Option<PathBuf>);

impl Drop for Partial {
    fn drop(&mut self) {
        if let Some(p) = self.0.take() {
            let _ = std::fs::remove_file(p);
        }
    }
}

/// Vorschlag für den Dateinamen: `<server>-<welt>-<datum>.zip` (nur sichere Zeichen).
pub fn download_file_name(server: &str, world: &str, date: &str) -> String {
    let clean = |s: &str| -> String {
        let out: String = s.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '-' | '_') { c } else { '-' }).collect();
        out.trim_matches('-').chars().take(40).collect()
    };
    let server = clean(server);
    let parts: Vec<String> = [server, clean(world), clean(date)].into_iter().filter(|s| !s.is_empty()).collect();
    format!("{}.zip", if parts.is_empty() { "world".to_owned() } else { parts.join("-") })
}

async fn fetch(client: &reqwest::Client, url: reqwest::Url, expected: u64) -> Result<Vec<u8>> {
    let mut response = client.get(url).send().await.map_err(|e| {
        tracing::warn!("Welt-Download von CraftingHosting fehlgeschlagen: {e}");
        download_failed()
    })?;
    if !response.status().is_success() {
        tracing::warn!("Welt-Download von CraftingHosting abgelehnt: HTTP {}", response.status());
        return Err(download_failed());
    }
    let mut data = Vec::with_capacity(usize::try_from(expected.min(64 * 1024 * 1024)).unwrap_or(0));
    while let Some(chunk) = crate::task::or_cancel(response.chunk()).await?.map_err(|_| download_failed())? {
        if data.len() as u64 + chunk.len() as u64 > FILE_MAX {
            return Err(download_failed());
        }
        data.extend_from_slice(&chunk);
    }
    Ok(data)
}

// --- Operationen ------------------------------------------------------------------------

impl Launcher {
    async fn ch_worlds(&self, req: Req) -> Result<Worlds> {
        let (_, bytes) = self.ch_send(req).await?;
        let raw: ApiWorlds = super::parse(&bytes, "craftinghosting/worlds")?;
        Ok(raw.cleaned())
    }

    pub async fn craftinghosting_worlds(&self) -> Result<Worlds> {
        self.ch_worlds(Req::get(format!("{BASE}/server/worlds"))).await
    }

    /// Aktive Welt setzen (`level-name`); fehlt sie, entsteht sie beim nächsten Start.
    pub async fn craftinghosting_set_active_world(&self, name: &str) -> Result<Worlds> {
        let name = world_name(name)?;
        self.ch_worlds(Req::patch(format!("{BASE}/server/worlds"), json!({ "active": name }))).await
    }

    /// Welt mit allen Dimensionen löschen; bei der aktiven heißt das „zurücksetzen“.
    pub async fn craftinghosting_delete_world(&self, name: &str) -> Result<Worlds> {
        let name = world_name(name)?;
        self.ch_worlds(Req::delete(format!("{BASE}/server/worlds?name={}", encode_query(&name))).timeout(Duration::from_secs(120)))
            .await
    }

    /// Welt als ZIP nach `dest` (Einträge wie `world/level.dat`). Abbrechen über die laufende Aufgabe.
    pub async fn craftinghosting_download_world(
        &self,
        name: &str,
        dest: &Path,
        on_progress: impl Fn(WorldProgress) + Send + Sync + 'static,
    ) -> Result<WorldDownload> {
        let name = world_name(name)?;
        on_progress(WorldProgress { phase: "list", percent: 0 });
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/server/world/files?name={}", encode_query(&name)))).await?;
        let raw: ApiWorldFiles = super::parse(&bytes, "craftinghosting/world/files")?;
        let mut files: Vec<(String, u64)> = Vec::new();
        for f in raw.files.into_iter().take(FILES_MAX) {
            if let Ok(path) = server_path(&f.path)
                && belongs_to(&path, &name)
                && !path.ends_with("/session.lock")
                && !files.iter().any(|(p, _)| *p == path)
            {
                files.push((path, size(f.size)));
            }
        }
        if files.is_empty() {
            return Err(super::api_error(404, "file_not_found", None));
        }
        let total: u64 = files.iter().map(|(_, s)| *s).sum::<u64>().max(1);

        let part = dest.with_extension("zip.part");
        let mut guard = Partial(Some(part.clone()));
        let file = std::fs::File::create(&part).map_err(|e| Error::io(&part, e))?;
        let mut zip = zip::ZipWriter::new(std::io::BufWriter::new(file));
        let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated).large_file(true);

        // Eigener Client ohne TRS-Token, ohne Weiterleitungen.
        let client = crate::net::client_builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(10 * 60))
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        let on_progress: Arc<dyn Fn(WorldProgress) + Send + Sync> = Arc::new(on_progress);
        let mut done = 0u64;
        let mut last = 0u8;
        let mut written = 0usize;
        for batch in files.chunks(URL_BATCH) {
            crate::task::checkpoint().await?;
            let paths: Vec<&str> = batch.iter().map(|(p, _)| p.as_str()).collect();
            let (_, bytes) = self.ch_send(Req::post(format!("{BASE}/server/files/download-urls"), json!({ "paths": paths }))).await?;
            let urls: ApiUrls = super::parse(&bytes, "craftinghosting/files/download-urls")?;
            let mut jobs = Vec::with_capacity(batch.len());
            for (path, expected) in batch {
                let url = urls
                    .urls
                    .iter()
                    .find(|u| u.path == *path)
                    .and_then(|u| partner_url(&u.url, cfg!(test)))
                    .ok_or_else(|| {
                        tracing::warn!("CraftingHosting-Download-Adresse fehlt oder abgelehnt");
                        super::bad_response()
                    })?;
                jobs.push((path.clone(), *expected, url));
            }
            let client = &client;
            let mut downloads = futures::stream::iter(jobs)
                .map(|(path, expected, url)| async move { fetch(client, url, expected).await.map(|data| (path, data)) })
                .buffer_unordered(PARALLEL);
            while let Some(item) = downloads.next().await {
                let (path, data) = item?;
                let entry = validate::text(path.trim_start_matches('/'), 512);
                zip.start_file(entry, options).map_err(|e| Error::Internal(e.to_string()))?;
                zip.write_all(&data).map_err(|e| Error::io(&part, e))?;
                written += 1;
                done += data.len() as u64;
                let percent = u8::try_from((done * 100 / total).min(99)).unwrap_or(99);
                if percent != last {
                    last = percent;
                    on_progress(WorldProgress { phase: "download", percent });
                }
            }
        }
        zip.finish().map_err(|e| Error::Internal(e.to_string()))?.flush().map_err(|e| Error::io(&part, e))?;
        std::fs::rename(&part, dest).map_err(|e| Error::io(dest, e))?;
        guard.0 = None;
        on_progress(WorldProgress { phase: "download", percent: 100 });
        Ok(WorldDownload {
            file_name: dest.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
            files: written,
            bytes: done,
            truncated: raw.truncated,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names() {
        assert_eq!(world_name(" world ").unwrap(), "world");
        assert!(world_name("Skyblock_2-a").is_ok());
        for bad in ["", "a b", "../x", "welt!", "grün", &"a".repeat(33)] {
            assert!(world_name(bad).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn world_files() {
        assert!(belongs_to("/world/level.dat", "world"));
        assert!(belongs_to("/world_nether/DIM-1/region/r.0.0.mca", "world"));
        assert!(belongs_to("/world_the_end/DIM1/x", "world"));
        assert!(!belongs_to("/world2/level.dat", "world"));
        assert!(!belongs_to("/server.properties", "world"));
        assert!(!belongs_to("/world", "world"));
    }

    #[test]
    fn file_names() {
        assert_eq!(download_file_name("Meine Welt", "world", "2026-11-18"), "Meine-Welt-world-2026-11-18.zip");
        assert_eq!(download_file_name("../..", "x", ""), "x.zip");
        assert_eq!(download_file_name("", "", ""), "world.zip");
    }
}
