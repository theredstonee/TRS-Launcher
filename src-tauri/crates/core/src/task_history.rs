//! Verlauf fertiger Hintergrund-Aufgaben („Fertig“ im Aufgaben-Panel):
//! Modpacks, neue/duplizierte/importierte Instanzen, Java, Exporte …
//!
//! Liegt als `task-history.json` im Datenverzeichnis, neueste zuerst,
//! höchstens [`MAX_RECORDS`] Einträge. Reine Komfort-Information – die
//! Aufgabe selbst hängt nie davon ab.

use std::path::PathBuf;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::instance::validate_id;
use crate::paths::Paths;
use crate::{Error, Result, fsutil};

pub const MAX_RECORDS: usize = 50;
const MAX_TITLE: usize = 120;
const MAX_DETAIL: usize = 200;
const MAX_ICON_URL: usize = 512;
const ICON_PREFIX: &str = "https://cdn.modrinth.com/";

static WRITE_LOCK: Mutex<()> = Mutex::const_new(());

/// Art der Aufgabe – das Frontend leitet daraus Beschriftung und Symbol ab.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum TaskKind {
    Modpack,
    ModpackFile,
    Content,
    ContentUpdate,
    PerformancePack,
    Java,
    Import,
    Export,
    Create,
    Duplicate,
    Repair,
    Reinstall,
    VersionChange,
    Launch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TaskOutcome {
    Done,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskRecord {
    pub id: String,
    pub kind: TaskKind,
    pub title: String,
    pub outcome: TaskOutcome,
    pub finished_at: DateTime<Utc>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub instance_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon_url: Option<String>,
    /// Kurzer Zusatz, z. B. die Fehlermeldung.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
    /// Geladene Bytes, falls die Aufgabe etwas heruntergeladen hat.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bytes: Option<u64>,
}

/// Was das Frontend meldet; ID und Zeit vergibt der Kern.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewTaskRecord {
    pub kind: TaskKind,
    pub title: String,
    pub outcome: TaskOutcome,
    #[serde(default)]
    pub instance_id: Option<String>,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub detail: Option<String>,
    #[serde(default)]
    pub bytes: Option<u64>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct HistoryFile {
    #[serde(default)]
    records: Vec<TaskRecord>,
}

fn file(paths: &Paths) -> PathBuf {
    paths.root().join("task-history.json")
}

fn clean(text: &str, max: usize) -> Option<String> {
    let text: String = text.trim().chars().filter(|c| !c.is_control()).take(max).collect();
    (!text.is_empty()).then_some(text)
}

fn validate(new: NewTaskRecord) -> Result<TaskRecord> {
    let title = clean(&new.title, MAX_TITLE).ok_or_else(|| Error::validation("Aufgabe ohne Namen"))?;
    if let Some(id) = &new.instance_id {
        validate_id(id)?;
    }
    // Nur Modrinths CDN – dasselbe, was `ModIcon` anzeigt.
    let icon_url = new.icon_url.filter(|u| {
        u.len() <= MAX_ICON_URL && u.starts_with(ICON_PREFIX) && !u.chars().any(|c| c.is_control() || c.is_whitespace())
    });
    Ok(TaskRecord {
        id: uuid::Uuid::new_v4().simple().to_string(),
        kind: new.kind,
        title,
        outcome: new.outcome,
        finished_at: Utc::now(),
        instance_id: new.instance_id,
        icon_url,
        detail: new.detail.as_deref().and_then(|d| clean(d, MAX_DETAIL)),
        bytes: new.bytes,
    })
}

async fn read(paths: &Paths) -> HistoryFile {
    fsutil::read_json(&file(paths)).await.ok().flatten().unwrap_or_default()
}

/// Neueste zuerst.
pub async fn list(paths: &Paths) -> Vec<TaskRecord> {
    let mut records = read(paths).await.records;
    records.truncate(MAX_RECORDS);
    records
}

pub async fn add(paths: &Paths, new: NewTaskRecord) -> Result<TaskRecord> {
    let record = validate(new)?;
    let _guard = WRITE_LOCK.lock().await;
    let mut history = read(paths).await;
    history.records.insert(0, record.clone());
    history.records.truncate(MAX_RECORDS);
    fsutil::write_json(&file(paths), &history).await?;
    Ok(record)
}

pub async fn remove(paths: &Paths, id: &str) -> Result<()> {
    if id.is_empty() || id.len() > 64 || !id.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(Error::validation("Ungültiger Eintrag"));
    }
    let _guard = WRITE_LOCK.lock().await;
    let mut history = read(paths).await;
    history.records.retain(|r| r.id != id);
    fsutil::write_json(&file(paths), &history).await
}

pub async fn clear(paths: &Paths) -> Result<()> {
    let _guard = WRITE_LOCK.lock().await;
    fsutil::write_json(&file(paths), &HistoryFile::default()).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn new(title: &str) -> NewTaskRecord {
        NewTaskRecord {
            kind: TaskKind::Modpack,
            title: title.into(),
            outcome: TaskOutcome::Done,
            instance_id: None,
            icon_url: None,
            detail: None,
            bytes: None,
        }
    }

    #[tokio::test]
    async fn keeps_newest_first_and_caps() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        for i in 0..(MAX_RECORDS + 5) {
            add(&paths, new(&format!("Pack {i}"))).await.unwrap();
        }
        let list = list(&paths).await;
        assert_eq!(list.len(), MAX_RECORDS);
        assert_eq!(list[0].title, format!("Pack {}", MAX_RECORDS + 4));

        remove(&paths, &list[0].id).await.unwrap();
        assert_eq!(super::list(&paths).await.len(), MAX_RECORDS - 1);
        assert!(remove(&paths, "../x").await.is_err());
        clear(&paths).await.unwrap();
        assert!(super::list(&paths).await.is_empty());
    }

    #[test]
    fn validates_input() {
        assert!(validate(new("   ")).is_err());
        assert!(validate(NewTaskRecord { instance_id: Some("../evil".into()), ..new("x") }).is_err());

        let r = validate(NewTaskRecord {
            icon_url: Some("https://evil.example/a.png".into()),
            detail: Some(format!("  {}\n", "x".repeat(500))),
            ..new("  Fabulously\u{7} Optimized ")
        })
        .unwrap();
        assert_eq!(r.title, "Fabulously Optimized");
        assert!(r.icon_url.is_none(), "nur Modrinths CDN");
        assert_eq!(r.detail.unwrap().len(), MAX_DETAIL);

        let r = validate(NewTaskRecord { icon_url: Some("https://cdn.modrinth.com/data/x/icon.png".into()), ..new("x") }).unwrap();
        assert!(r.icon_url.is_some());

        let json = serde_json::to_value(&r).unwrap();
        assert_eq!(json["kind"], "modpack");
        assert_eq!(json["outcome"], "done");
        let parsed: NewTaskRecord =
            serde_json::from_value(serde_json::json!({ "kind": "version-change", "title": "a", "outcome": "failed" })).unwrap();
        assert_eq!(parsed.kind, TaskKind::VersionChange);
    }
}
