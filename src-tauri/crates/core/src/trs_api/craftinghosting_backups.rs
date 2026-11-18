//! Version wechseln und Backups des Partner-Servers (Vertrag: `docs/craftinghosting.md`, „Feature switches“).
//! Beides schaltet die TRS API frei; bis dahin antwortet sie `501 feature_unavailable`.

use std::io::Write;
use std::path::Path;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::craftinghosting::{BASE, Server};
use super::craftinghosting_world::{WorldProgress, partner_url};
use super::{Req, validate};
use crate::{Error, Launcher, Result, USER_AGENT};

const BACKUPS_MAX: usize = 200;
/// Größtes Backup, das der Launcher lädt.
const BACKUP_MAX_BYTES: u64 = 20 * 1024 * 1024 * 1024;

fn invalid_backup() -> Error {
    Error::validation(crate::msg!("craftinghosting.invalidBackup", "Dieses Backup gibt es so nicht."))
}

fn backup_id(id: &str) -> Result<&str> {
    let ok = (1..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'));
    if ok { Ok(id) } else { Err(invalid_backup()) }
}

fn download_failed() -> Error {
    Error::TrsApi {
        kind: "trs_api",
        code: "download_failed".into(),
        msg: crate::msg!("craftinghosting.downloadFailed", "Die Welt konnte nicht heruntergeladen werden – bitte erneut versuchen."),
    }
}

/// Neuer Servertyp/neue Version.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionChange {
    pub server_type: String,
    pub version: String,
    #[serde(default = "yes")]
    pub keep_world: bool,
}

fn yes() -> bool {
    true
}

impl VersionChange {
    fn body(&self) -> Result<serde_json::Value> {
        let type_ok = (1..=32).contains(&self.server_type.len())
            && self.server_type.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'_' | b'-'));
        let version_ok = (1..=32).contains(&self.version.len())
            && self.version.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'));
        if !type_ok || !version_ok {
            return Err(Error::validation(crate::msg!("craftinghosting.invalidType", "Bitte Servertyp und Version wählen.")));
        }
        Ok(json!({ "serverType": self.server_type, "version": self.version, "keepWorld": self.keep_world }))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Backup {
    pub id: String,
    pub name: String,
    pub size: u64,
    pub created_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Backups {
    pub backups: Vec<Backup>,
    /// Höchstzahl der Backups (0 = unbekannt).
    pub max: u32,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ApiBackup {
    id: String,
    name: String,
    size: Option<f64>,
    created_at: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiBackups {
    backups: Vec<ApiBackup>,
    max: Option<f64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApiUrl {
    url: String,
}

#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn amount(v: Option<f64>) -> u64 {
    v.filter(|n| n.is_finite() && *n >= 0.0).map_or(0, |n| n as u64)
}

impl ApiBackups {
    fn cleaned(self) -> Backups {
        let backups = self
            .backups
            .into_iter()
            .filter(|b| backup_id(&b.id).is_ok())
            .map(|b| {
                let name = validate::text(b.name.trim(), 100);
                Backup {
                    name: if name.is_empty() { b.id.clone() } else { name },
                    id: b.id,
                    size: amount(b.size),
                    created_at: b.created_at.map(|t| validate::text(t.trim(), 40)).filter(|t| !t.is_empty()),
                }
            })
            .take(BACKUPS_MAX)
            .collect();
        Backups { backups, max: u32::try_from(amount(self.max).min(1000)).unwrap_or(0) }
    }
}

/// Vorschlag für den Dateinamen eines Backups.
pub fn backup_file_name(server: &str, backup: &str) -> String {
    let clean = |s: &str| -> String {
        let out: String = s.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '-' | '_') { c } else { '-' }).collect();
        out.trim_matches('-').chars().take(40).collect()
    };
    let parts: Vec<String> = [clean(server), clean(backup)].into_iter().filter(|s| !s.is_empty()).collect();
    format!("{}.zip", if parts.is_empty() { "backup".to_owned() } else { parts.join("-") })
}

impl Launcher {
    /// Servertyp/Version wechseln; danach startet der Server neu (Oberfläche fragt wie nach dem Start nach).
    pub async fn craftinghosting_change_version(&self, change: &VersionChange) -> Result<Server> {
        let body = change.body()?;
        self.ch_required(Req::post(format!("{BASE}/server/version"), body).timeout(Duration::from_secs(120))).await
    }

    pub async fn craftinghosting_backups(&self) -> Result<Backups> {
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/server/backups"))).await?;
        let raw: ApiBackups = super::parse(&bytes, "craftinghosting/backups")?;
        Ok(raw.cleaned())
    }

    pub async fn craftinghosting_create_backup(&self) -> Result<()> {
        self.ch_send(Req::post_empty(format!("{BASE}/server/backups")).timeout(Duration::from_secs(120))).await.map(|_| ())
    }

    /// Backup einspielen (Server muss aus sein).
    pub async fn craftinghosting_restore_backup(&self, id: &str) -> Result<()> {
        let id = backup_id(id)?;
        self.ch_send(Req::post_empty(format!("{BASE}/server/backups/{id}/restore")).timeout(Duration::from_secs(180))).await.map(|_| ())
    }

    pub async fn craftinghosting_delete_backup(&self, id: &str) -> Result<()> {
        let id = backup_id(id)?;
        self.ch_send(Req::delete(format!("{BASE}/server/backups/{id}"))).await.map(|_| ())
    }

    /// Backup direkt von CraftingHosting nach `dest` laden (nur `https://*.craftinghosting.free`, ohne TRS-Token).
    pub async fn craftinghosting_download_backup(
        &self,
        id: &str,
        dest: &Path,
        on_progress: impl Fn(WorldProgress) + Send + Sync + 'static,
    ) -> Result<u64> {
        let id = backup_id(id)?;
        let (_, bytes) = self.ch_send(Req::get(format!("{BASE}/server/backups/{id}/download"))).await?;
        let raw: ApiUrl = super::parse(&bytes, "craftinghosting/backups/download")?;
        let url = partner_url(&raw.url, cfg!(test)).ok_or_else(|| {
            tracing::warn!("CraftingHosting-Backup-Adresse abgelehnt");
            super::bad_response()
        })?;
        let client = crate::net::client_builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(60 * 60))
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        let mut response = client.get(url).send().await.map_err(|_| download_failed())?;
        if !response.status().is_success() {
            tracing::warn!("Backup-Download abgelehnt: HTTP {}", response.status());
            return Err(download_failed());
        }
        let total = response.content_length().unwrap_or(0);
        let part = dest.with_extension("zip.part");
        let mut file = std::io::BufWriter::new(std::fs::File::create(&part).map_err(|e| Error::io(&part, e))?);
        let result: Result<u64> = async {
            let mut done = 0u64;
            let mut last = 0u8;
            while let Some(chunk) = crate::task::or_cancel(response.chunk()).await?.map_err(|_| download_failed())? {
                done += chunk.len() as u64;
                if done > BACKUP_MAX_BYTES {
                    return Err(download_failed());
                }
                file.write_all(&chunk).map_err(|e| Error::io(&part, e))?;
                if let Some(share) = (done * 100).checked_div(total) {
                    let percent = u8::try_from(share.min(99)).unwrap_or(99);
                    if percent != last {
                        last = percent;
                        on_progress(WorldProgress { phase: "download", percent });
                    }
                }
            }
            file.flush().map_err(|e| Error::io(&part, e))?;
            Ok(done)
        }
        .await;
        drop(file);
        match result {
            Ok(done) => {
                std::fs::rename(&part, dest).map_err(|e| Error::io(dest, e))?;
                on_progress(WorldProgress { phase: "download", percent: 100 });
                Ok(done)
            }
            Err(e) => {
                let _ = std::fs::remove_file(&part);
                Err(e)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_and_names() {
        assert!(backup_id("abc_1-2").is_ok());
        for bad in ["", "a/b", "../x", "a b", &"x".repeat(65)] {
            assert!(backup_id(bad).is_err(), "{bad:?}");
        }
        assert_eq!(backup_file_name("Meine Welt", "2026-11-18 Abend"), "Meine-Welt-2026-11-18-Abend.zip");
        assert_eq!(backup_file_name("", ""), "backup.zip");
    }

    #[test]
    fn version_change_body() {
        let ok = VersionChange { server_type: "paper".into(), version: "1.21.11".into(), keep_world: true };
        assert_eq!(ok.body().unwrap(), json!({ "serverType": "paper", "version": "1.21.11", "keepWorld": true }));
        let bad = VersionChange { server_type: "Paper!".into(), version: "1".into(), keep_world: false };
        assert!(bad.body().is_err());
    }
}
