//! Ältere Logs einer Instanz für den Log-Tab: `logs/*.log`, `logs/*.log.gz`,
//! `crash-reports/*.txt` und die vom Launcher mitgeschriebene Ausgabe.
//! Quellen werden nur über ihre ID (`logs/<name>` …) angesprochen – der Name
//! wird geprüft, Pfade kommen nie aus dem Webview.

use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use serde::Serialize;

use crate::instance::validate_id;
use crate::paths::Paths;
use crate::{Error, Launcher, Result, process};

/// So viel Text geht höchstens ans Webview (bei größeren Logs: das Ende).
pub const MAX_READ_BYTES: u64 = 48 << 20;
/// Entpackt höchstens so viel aus einer `.log.gz`.
const MAX_GZ_UNPACKED: u64 = 512 << 20;
const MAX_SOURCES: usize = 300;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum LogKind {
    /// `logs/*.log(.gz)` des Spiels.
    Game,
    /// `crash-reports/*.txt`
    Crash,
    /// Vom Launcher mitgeschriebene Ausgabe (`launcher-logs/`).
    Launcher,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct LogSource {
    /// `logs/<name>`, `crash-reports/<name>` oder `launcher/<name>`.
    pub id: String,
    pub kind: LogKind,
    pub name: String,
    pub size: u64,
    pub modified: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogText {
    pub text: String,
    /// Der Anfang fehlt, weil die Datei größer als [`MAX_READ_BYTES`] ist.
    pub truncated: bool,
}

fn plain(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 200
        && !name.starts_with('.')
        && !name.chars().any(|c| c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|'))
}

fn allowed(kind: LogKind, name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    plain(name)
        && match kind {
            LogKind::Game => lower.ends_with(".log") || lower.ends_with(".log.gz"),
            LogKind::Crash => lower.ends_with(".txt"),
            LogKind::Launcher => lower.ends_with(".log"),
        }
}

fn dir_for(paths: &Paths, instance_id: &str, kind: LogKind) -> PathBuf {
    match kind {
        LogKind::Game => paths.instance_game_dir(instance_id).join("logs"),
        LogKind::Crash => paths.instance_game_dir(instance_id).join("crash-reports"),
        LogKind::Launcher => paths.instance_dir(instance_id).join("launcher-logs"),
    }
}

fn prefix(kind: LogKind) -> &'static str {
    match kind {
        LogKind::Game => "logs",
        LogKind::Crash => "crash-reports",
        LogKind::Launcher => "launcher",
    }
}

fn invalid() -> Error {
    Error::validation(crate::msg!("logs.sourceGone", "Diese Log-Datei gibt es nicht mehr."))
}

/// Quelle-ID → geprüfter Pfad (normale Datei, kein Symlink).
pub fn source_path(paths: &Paths, instance_id: &str, id: &str) -> Result<PathBuf> {
    validate_id(instance_id)?;
    let (head, name) = id.split_once('/').ok_or_else(invalid)?;
    let kind = [LogKind::Game, LogKind::Crash, LogKind::Launcher].into_iter().find(|k| prefix(*k) == head).ok_or_else(invalid)?;
    if !allowed(kind, name) {
        return Err(invalid());
    }
    let path = dir_for(paths, instance_id, kind).join(name);
    match std::fs::symlink_metadata(&path) {
        Ok(meta) if meta.is_file() => Ok(path),
        _ => Err(invalid()),
    }
}

/// Alle Log-Quellen, neueste zuerst.
pub fn list_sources(paths: &Paths, instance_id: &str) -> Result<Vec<LogSource>> {
    validate_id(instance_id)?;
    let mut out = Vec::new();
    for kind in [LogKind::Game, LogKind::Crash, LogKind::Launcher] {
        let dir = dir_for(paths, instance_id, kind);
        let Ok(read) = std::fs::read_dir(&dir) else { continue };
        for entry in read.flatten() {
            let Ok(file_type) = entry.file_type() else { continue };
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
            if !file_type.is_file() || !allowed(kind, &name) {
                continue;
            }
            let Ok(meta) = entry.metadata() else { continue };
            out.push(LogSource {
                id: format!("{}/{name}", prefix(kind)),
                kind,
                name,
                size: meta.len(),
                modified: meta.modified().ok().map(DateTime::<Utc>::from),
            });
        }
    }
    out.sort_by(|a, b| b.modified.cmp(&a.modified).then_with(|| a.id.cmp(&b.id)));
    out.truncate(MAX_SOURCES);
    Ok(out)
}

/// Letzte `max` Bytes eines Puffers, an einer Zeilengrenze beginnend.
fn tail(bytes: Vec<u8>, max: u64) -> (Vec<u8>, bool) {
    let max = max as usize;
    if bytes.len() <= max {
        return (bytes, false);
    }
    let mut start = bytes.len() - max;
    if let Some(nl) = bytes[start..].iter().position(|&b| b == b'\n') {
        start += nl + 1;
    }
    (bytes[start..].to_vec(), true)
}

fn read_capped(path: &Path) -> Result<(Vec<u8>, bool)> {
    let is_gz = path.to_string_lossy().to_ascii_lowercase().ends_with(".gz");
    let mut file = std::fs::File::open(path).map_err(|e| Error::io(path, e))?;
    if is_gz {
        let mut out = Vec::new();
        flate2::read::GzDecoder::new(file)
            .take(MAX_GZ_UNPACKED)
            .read_to_end(&mut out)
            .map_err(|_| Error::validation(crate::msg!("logs.unreadable", "Diese Log-Datei lässt sich nicht lesen.")))?;
        return Ok(tail(out, MAX_READ_BYTES));
    }
    let len = file.metadata().map_err(|e| Error::io(path, e))?.len();
    let mut cut = false;
    if len > MAX_READ_BYTES {
        file.seek(SeekFrom::Start(len - MAX_READ_BYTES)).map_err(|e| Error::io(path, e))?;
        cut = true;
    }
    let mut out = Vec::new();
    file.read_to_end(&mut out).map_err(|e| Error::io(path, e))?;
    // Angeschnittene erste Zeile weglassen.
    if cut && let Some(nl) = out.iter().position(|&b| b == b'\n') {
        out.drain(..=nl);
    }
    Ok((out, cut))
}

pub async fn read_source(paths: &Paths, instance_id: &str, id: &str) -> Result<LogText> {
    let path = source_path(paths, instance_id, id)?;
    tokio::task::spawn_blocking(move || {
        let (bytes, truncated) = read_capped(&path)?;
        Ok(LogText { text: String::from_utf8_lossy(&bytes).into_owned(), truncated })
    })
    .await
    .map_err(|e| Error::Internal(e.to_string()))?
}

impl Launcher {
    /// Lädt eine ältere Log-Datei bzw. einen Absturzbericht geschwärzt auf
    /// mclo.gs hoch (gleiche Schwärzung wie beim aktuellen Log).
    pub async fn share_log_source(&self, instance_id: &str, source: &str) -> Result<String> {
        if !self.settings().await.allow_log_upload {
            return Err(Error::validation(crate::msg!("launcher.logUploadDisabled", "Log-Upload ist in den Datenschutz-Einstellungen ausgeschaltet.")));
        }
        let instance = self.instances().get(instance_id).await?;
        let text = read_source(self.paths(), &instance.id, source).await?.text;
        let secrets = self.accounts().active_session().await.ok().flatten().map(|s| vec![s.access_token]).unwrap_or_default();
        process::share_log(self.http(), &process::redact(&text, &secrets)).await
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    fn gz(text: &str) -> Vec<u8> {
        let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        enc.write_all(text.as_bytes()).unwrap();
        enc.finish().unwrap()
    }

    #[tokio::test]
    async fn lists_and_reads_sources() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        let game = paths.instance_game_dir("test");
        std::fs::create_dir_all(game.join("logs")).unwrap();
        std::fs::create_dir_all(game.join("crash-reports")).unwrap();
        std::fs::create_dir_all(paths.instance_dir("test").join("launcher-logs")).unwrap();
        std::fs::write(game.join("logs/latest.log"), "[12:00:00] [main/INFO]: Hallo\n").unwrap();
        std::fs::write(game.join("logs/2026-09-25-1.log.gz"), gz("[11:00:00] [main/WARN]: Alt\n")).unwrap();
        std::fs::write(game.join("logs/notiz.txt"), "x").unwrap();
        std::fs::write(game.join("crash-reports/crash-2026-09-25_11.00.00-client.txt"), "---- Minecraft Crash Report ----\n").unwrap();
        std::fs::write(paths.instance_dir("test").join("launcher-logs/launcher-stdout.log"), "stdout\n").unwrap();

        let sources = list_sources(&paths, "test").unwrap();
        let ids: Vec<_> = sources.iter().map(|s| s.id.as_str()).collect();
        assert_eq!(sources.len(), 4, "{ids:?}");
        assert!(ids.contains(&"logs/2026-09-25-1.log.gz"));
        assert!(ids.contains(&"crash-reports/crash-2026-09-25_11.00.00-client.txt"));
        assert!(ids.contains(&"launcher/launcher-stdout.log"));
        assert!(!ids.iter().any(|i| i.ends_with("notiz.txt")));

        let old = read_source(&paths, "test", "logs/2026-09-25-1.log.gz").await.unwrap();
        assert_eq!(old.text, "[11:00:00] [main/WARN]: Alt\n");
        assert!(!old.truncated);
        assert!(read_source(&paths, "test", "crash-reports/crash-2026-09-25_11.00.00-client.txt").await.unwrap().text.contains("Crash Report"));

        for bad in ["logs/../../instance.json", "logs/..", "logs/notiz.txt", "saves/level.dat", "logs", "", "launcher/../x.log", "logs/fehlt.log", "logs\\latest.log"] {
            assert!(source_path(&paths, "test", bad).is_err(), "{bad:?}");
        }
        assert!(source_path(&paths, "../test", "logs/latest.log").is_err());
        assert!(list_sources(&paths, "leer").unwrap().is_empty());
    }

    #[test]
    fn tail_starts_at_line_boundary() {
        let (out, cut) = tail(b"eins\nzwei\ndrei\n".to_vec(), 8);
        assert!(cut);
        assert_eq!(out, b"drei\n");
        let (out, cut) = tail(b"kurz\n".to_vec(), 100);
        assert!(!cut);
        assert_eq!(out, b"kurz\n");
    }
}
