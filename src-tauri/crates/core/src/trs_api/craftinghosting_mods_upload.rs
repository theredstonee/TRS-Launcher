//! Mods einer Instanz 1:1 auf den Partner-Server: die Jar-Dateien aus `mods/` gehen unverändert über
//! Upload-Links der TRS API direkt zu CraftingHosting. Vorher räumt die API die alten Jars weg.

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::Req;
use super::craftinghosting::BASE;
use super::craftinghosting_world::{WorldProgress, partner_url};
use crate::{Error, Launcher, Result, USER_AGENT};

/// Höchstens so viele Bytes je Upload-Anfrage (eine größere Datei geht allein).
const BATCH_BYTES: u64 = 48 * 1024 * 1024;
const MAX_FILES: usize = 500;
const MAX_FILE_BYTES: u64 = 256 * 1024 * 1024;
const MAX_TOTAL_BYTES: u64 = 2 * 1024 * 1024 * 1024;

/// Ergebnis: hochgeladene Dateinamen.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModsUploaded {
    pub files: Vec<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiModsUpload {
    url: String,
}

fn upload_failed() -> Error {
    Error::TrsApi {
        kind: "trs_api",
        code: "upload_failed".into(),
        msg: crate::msg!("craftinghosting.modsUploadFailed", "Die Mods konnten nicht hochgeladen werden – bitte erneut versuchen."),
    }
}

fn plain_name(name: &str) -> bool {
    !name.is_empty() && name.len() <= 200 && !name.contains(['/', '\\', '\0']) && !name.starts_with('.')
}

/// Aktive Jars direkt in `mods/` (keine Unterordner, keine `.disabled`, keine Symlinks), nach Namen sortiert.
pub(crate) fn instance_jars(dir: &Path) -> Result<Vec<(String, PathBuf, u64)>> {
    let mut out = Vec::new();
    let entries = match std::fs::read_dir(dir) {
        Ok(e) => e,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(out),
        Err(e) => return Err(Error::io(dir, e)),
    };
    for entry in entries.flatten() {
        let Ok(meta) = entry.path().symlink_metadata() else { continue };
        if !meta.is_file() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        if !name.to_ascii_lowercase().ends_with(".jar") || !plain_name(&name) || meta.len() > MAX_FILE_BYTES {
            continue;
        }
        out.push((name, entry.path(), meta.len()));
    }
    out.sort_by_key(|a| a.0.to_lowercase());
    out.truncate(MAX_FILES);
    Ok(out)
}

/// Pakete bis `BATCH_BYTES`; eine größere Datei bildet ein eigenes Paket.
pub(crate) fn batches<T: Clone>(files: &[(String, T, u64)]) -> Vec<Vec<(String, T, u64)>> {
    let mut out: Vec<Vec<(String, T, u64)>> = Vec::new();
    let mut size = 0u64;
    for f in files {
        if out.is_empty() || (size + f.2 > BATCH_BYTES && !out.last().is_some_and(Vec::is_empty)) {
            out.push(Vec::new());
            size = 0;
        }
        out.last_mut().expect("eben angelegt").push(f.clone());
        size += f.2;
    }
    out
}

impl Launcher {
    /// Alle aktiven Mod-Dateien der Instanz unverändert auf den Server (Server muss aus sein).
    /// Ersetzt die vorhandenen Jars; TRS Server Rules bleibt.
    pub async fn craftinghosting_upload_mods(&self, instance_id: &str, on_progress: impl Fn(WorldProgress) + Send + Sync) -> Result<ModsUploaded> {
        crate::instance::validate_id(instance_id)?;
        let dir = self.paths().instance_game_dir(instance_id).join("mods");
        let jars = tokio::task::spawn_blocking(move || instance_jars(&dir)).await.map_err(|e| Error::Internal(e.to_string()))??;
        let total: u64 = jars.iter().map(|j| j.2).sum();
        if total > MAX_TOTAL_BYTES {
            return Err(Error::validation(crate::msg!("craftinghosting.modsTooLarge", "Die Mods dieser Instanz sind zu groß für den Server.")));
        }
        let client = crate::net::client_builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(30 * 60))
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        on_progress(WorldProgress { phase: "upload", percent: 0 });
        let mut sent = 0u64;
        let mut done = Vec::new();
        let groups = batches(&jars);
        // Ohne Dateien trotzdem einmal aufräumen, damit keine alten Mods zurückbleiben.
        let rounds = groups.len().max(1);
        for i in 0..rounds {
            let body = json!({ "replace": i == 0 });
            let (_, bytes) = self.ch_send(Req::post(format!("{BASE}/server/mods/upload"), body)).await?;
            let Some(group) = groups.get(i) else { break };
            let target: ApiModsUpload = super::parse(&bytes, "craftinghosting/mods/upload")?;
            let url = partner_url(&target.url, cfg!(test)).ok_or_else(|| {
                tracing::warn!("CraftingHosting-Upload-Adresse abgelehnt");
                super::bad_response()
            })?;
            let mut form = reqwest::multipart::Form::new();
            for (name, path, _) in group {
                let data = tokio::fs::read(path).await.map_err(|e| Error::io(path, e))?;
                let part = reqwest::multipart::Part::bytes(data)
                    .file_name(name.clone())
                    .mime_str("application/java-archive")
                    .map_err(|_| upload_failed())?;
                form = form.part("files", part);
            }
            let response = client.post(url).multipart(form).send().await.map_err(|e| {
                tracing::warn!("Mod-Upload zu CraftingHosting fehlgeschlagen: {e}");
                upload_failed()
            })?;
            if !response.status().is_success() {
                tracing::warn!("Mod-Upload zu CraftingHosting abgelehnt: HTTP {}", response.status());
                return Err(upload_failed());
            }
            sent += group.iter().map(|g| g.2).sum::<u64>();
            done.extend(group.iter().map(|g| g.0.clone()));
            let percent = u8::try_from((sent * 100 / total.max(1)).min(100)).unwrap_or(100);
            on_progress(WorldProgress { phase: "upload", percent });
        }
        on_progress(WorldProgress { phase: "upload", percent: 100 });
        Ok(ModsUploaded { files: done })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picks_active_jars_only() {
        let dir = tempfile::tempdir().unwrap();
        let mods = dir.path();
        std::fs::write(mods.join("b-mod.jar"), b"bb").unwrap();
        std::fs::write(mods.join("A-mod.JAR"), b"a").unwrap();
        std::fs::write(mods.join("off.jar.disabled"), b"x").unwrap();
        std::fs::write(mods.join("notes.txt"), b"x").unwrap();
        std::fs::write(mods.join(".hidden.jar"), b"x").unwrap();
        std::fs::create_dir(mods.join("sub.jar")).unwrap();
        let names: Vec<_> = instance_jars(mods).unwrap().into_iter().map(|j| (j.0, j.2)).collect();
        assert_eq!(names, vec![("A-mod.JAR".to_owned(), 1), ("b-mod.jar".to_owned(), 2)]);
        assert!(instance_jars(&mods.join("missing")).unwrap().is_empty());
    }

    #[test]
    fn splits_into_batches() {
        let mb = 1024 * 1024;
        let files = vec![("a".to_owned(), (), 30 * mb), ("b".to_owned(), (), 20 * mb), ("big".to_owned(), (), 100 * mb), ("c".to_owned(), (), mb)];
        let names: Vec<Vec<String>> = batches(&files).into_iter().map(|b| b.into_iter().map(|f| f.0).collect()).collect();
        assert_eq!(names, vec![vec!["a"], vec!["b"], vec!["big"], vec!["c"]]);
        let small = vec![("a".to_owned(), (), mb), ("b".to_owned(), (), mb)];
        assert_eq!(batches(&small).len(), 1);
        assert!(batches::<()>(&[]).is_empty());
    }
}
