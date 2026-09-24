//! Verlauf fertiger Hintergrund-Aufgaben („Fertig“ im Aufgaben-Panel):
//! Modpacks, neue/duplizierte/importierte Instanzen, Java, Exporte …
//!
//! Liegt als `task-history.json` im Datenverzeichnis, neueste zuerst,
//! höchstens [`MAX_RECORDS`] Einträge. Reine Komfort-Information – die
//! Aufgabe selbst hängt nie davon ab.

use std::collections::BTreeMap;
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
const MAX_TEXT_KEY: usize = 100;
const MAX_TEXT_PARAMS: usize = 8;
const MAX_PARAM_NAME: usize = 32;
const MAX_PARAM_VALUE: usize = 200;
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
    /// FFmpeg für Clips & Aufnahme geladen.
    Ffmpeg,
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
    /// Übersetzbare Fassung von `title` (Schlüssel + Parameter).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title_ref: Option<TextRef>,
    /// Übersetzbare Fassung von `detail`, z. B. `errors.<code>` eines Fehlers.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail_ref: Option<TextRef>,
}

/// Verweis auf einen Text der Oberfläche: Schlüssel aus `app/locales/*.json`
/// (z. B. `tasks.title.modpack` oder `errors.upload.tooLarge`) und Parameter.
/// So bleibt der Verlauf auch nach einem Sprachwechsel lesbar; `title` bzw.
/// `detail` sind der Text in der Sprache von damals.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TextRef {
    pub key: String,
    #[serde(default, deserialize_with = "text_params", skip_serializing_if = "BTreeMap::is_empty")]
    pub params: BTreeMap<String, String>,
}

/// Parameter dürfen Texte oder Zahlen sein; alles andere wird verworfen.
fn text_params<'de, D: serde::Deserializer<'de>>(d: D) -> std::result::Result<BTreeMap<String, String>, D::Error> {
    let raw = BTreeMap::<String, serde_json::Value>::deserialize(d)?;
    Ok(raw
        .into_iter()
        .filter_map(|(k, v)| match v {
            serde_json::Value::String(s) => Some((k, s)),
            serde_json::Value::Number(n) => Some((k, n.to_string())),
            _ => None,
        })
        .collect())
}

impl TextRef {
    /// Nur harmlose Schlüssel und kurze Parameter ohne Steuerzeichen; sonst `None`
    /// (reine Komfort-Information – dann bleibt es beim gespeicherten Text).
    fn validated(self) -> Option<Self> {
        let key_ok = !self.key.is_empty()
            && self.key.len() <= MAX_TEXT_KEY
            && self.key.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
            && !self.key.starts_with('.')
            && !self.key.ends_with('.');
        let names_ok = self.params.len() <= MAX_TEXT_PARAMS
            && self.params.keys().all(|k| {
                !k.is_empty() && k.len() <= MAX_PARAM_NAME && k.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
            });
        if !key_ok || !names_ok {
            return None;
        }
        let params = self
            .params
            .into_iter()
            .map(|(k, v)| {
                let v: String = v.chars().filter(|c| !c.is_control()).take(MAX_PARAM_VALUE).collect();
                (k, v)
            })
            .collect();
        Some(Self { key: self.key, params })
    }
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
    #[serde(default)]
    pub title_ref: Option<TextRef>,
    #[serde(default)]
    pub detail_ref: Option<TextRef>,
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
    let title = clean(&new.title, MAX_TITLE).ok_or_else(|| Error::validation(crate::msg!(
        "taskHistory.untitled",
        "Aufgabe ohne Namen"
    )))?;
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
        title_ref: new.title_ref.and_then(TextRef::validated),
        detail_ref: new.detail_ref.and_then(TextRef::validated),
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
        return Err(Error::validation(crate::msg!("taskHistory.invalidEntry", "Ungültiger Eintrag")));
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
            title_ref: None,
            detail_ref: None,
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
        assert!(parsed.title_ref.is_none());
    }

    #[test]
    fn keeps_valid_text_refs_only() {
        let parsed: NewTaskRecord = serde_json::from_value(serde_json::json!({
            "kind": "modpack", "title": "Modpack „X“ installiert", "outcome": "done",
            "titleRef": { "key": "tasks.title.modpack", "params": { "name": "X", "count": 3, "bad": null } },
            "detailRef": { "key": "../evil" }
        }))
        .unwrap();
        let r = validate(parsed).unwrap();
        let title = r.title_ref.as_ref().unwrap();
        assert_eq!(title.key, "tasks.title.modpack");
        assert_eq!(title.params.get("count").map(String::as_str), Some("3"));
        assert!(!title.params.contains_key("bad"));
        assert!(r.detail_ref.is_none(), "ungültiger Schlüssel fällt weg");

        let json = serde_json::to_value(&r).unwrap();
        assert_eq!(json["titleRef"]["params"]["name"], "X");
        assert!(json.get("detailRef").is_none());

        let err = validate(new(" ")).unwrap_err();
        assert_eq!(err.message_code(), "taskHistory.untitled");
    }
}
