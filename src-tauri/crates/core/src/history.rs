//! Verlauf einer Instanz: was wann passiert ist (erstellt, gestartet, Mods
//! installiert, Version gewechselt …). Liegt als `instances/<id>/history.json`,
//! neueste Einträge zuerst, höchstens [`MAX_ENTRIES`].
//!
//! Der Verlauf ist reine Komfort-Information: Fehler beim Schreiben werden nur
//! geloggt und brechen nie die eigentliche Aktion ab.

use std::path::PathBuf;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::instance::validate_id;
use crate::paths::Paths;
use crate::{Result, fsutil};

pub const MAX_ENTRIES: usize = 500;
const MAX_TEXT: usize = 120;

/// Serialisiert alle Schreibzugriffe – Einträge kommen aus vielen Tasks.
static WRITE_LOCK: Mutex<()> = Mutex::const_new(());

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HistoryKind {
    Created,
    Imported,
    Launched,
    Stopped,
    Crashed,
    ModInstalled,
    ModUpdated,
    ModRemoved,
    ModEnabled,
    ModDisabled,
    VersionSwitched,
    Repaired,
    IconChanged,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryEntry {
    pub at: DateTime<Utc>,
    pub kind: HistoryKind,
    /// Worum es geht – z. B. Mod-Name oder Import-Quelle.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subject: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub from: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub to: Option<String>,
    /// Zusatz, z. B. die Absturz-Diagnose (`out_of_memory`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seconds: Option<u64>,
}

impl HistoryEntry {
    pub fn new(kind: HistoryKind) -> Self {
        Self { at: Utc::now(), kind, subject: None, from: None, to: None, detail: None, seconds: None }
    }

    pub fn subject(mut self, text: impl AsRef<str>) -> Self {
        self.subject = clean(text.as_ref());
        self
    }

    pub fn from(mut self, text: impl AsRef<str>) -> Self {
        self.from = clean(text.as_ref());
        self
    }

    pub fn to(mut self, text: impl AsRef<str>) -> Self {
        self.to = clean(text.as_ref());
        self
    }

    pub fn detail(mut self, text: impl AsRef<str>) -> Self {
        self.detail = clean(text.as_ref());
        self
    }

    pub fn seconds(mut self, seconds: u64) -> Self {
        self.seconds = Some(seconds);
        self
    }
}

fn clean(text: &str) -> Option<String> {
    let text: String = text.trim().chars().filter(|c| !c.is_control()).take(MAX_TEXT).collect();
    (!text.is_empty()).then_some(text)
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct HistoryFile {
    #[serde(default)]
    entries: Vec<HistoryEntry>,
}

fn file(paths: &Paths, instance_id: &str) -> PathBuf {
    paths.instance_dir(instance_id).join("history.json")
}

/// Neuester Eintrag nach vorn, Liste auf [`MAX_ENTRIES`] kappen.
fn push_capped(entries: &mut Vec<HistoryEntry>, entry: HistoryEntry) {
    entries.insert(0, entry);
    entries.truncate(MAX_ENTRIES);
}

async fn try_record(paths: &Paths, instance_id: &str, entry: HistoryEntry) -> Result<()> {
    validate_id(instance_id)?;
    // Instanz gelöscht (oder nie angelegt)? Dann keinen verwaisten Ordner erzeugen.
    if !paths.instance_file(instance_id).is_file() {
        return Ok(());
    }
    let _guard = WRITE_LOCK.lock().await;
    let path = file(paths, instance_id);
    let mut history: HistoryFile = fsutil::read_json(&path).await.ok().flatten().unwrap_or_default();
    push_capped(&mut history.entries, entry);
    fsutil::write_json(&path, &history).await
}

/// Schreibt einen Eintrag; Fehler landen nur im Log.
pub async fn record(paths: &Paths, instance_id: &str, entry: HistoryEntry) {
    if let Err(e) = try_record(paths, instance_id, entry).await {
        tracing::warn!("Verlauf für '{instance_id}' konnte nicht gespeichert werden: {e}");
    }
}

/// Wie [`record`], aber ohne zu warten (für synchrone Rückrufe).
pub fn record_detached(paths: &Paths, instance_id: &str, entry: HistoryEntry) {
    let Ok(handle) = tokio::runtime::Handle::try_current() else { return };
    let (paths, id) = (paths.clone(), instance_id.to_owned());
    handle.spawn(async move { record(&paths, &id, entry).await });
}

pub async fn list(paths: &Paths, instance_id: &str) -> Result<Vec<HistoryEntry>> {
    validate_id(instance_id)?;
    let history: HistoryFile = fsutil::read_json(&file(paths, instance_id)).await.ok().flatten().unwrap_or_default();
    let mut entries = history.entries;
    entries.truncate(MAX_ENTRIES);
    Ok(entries)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn caps_and_orders_newest_first() {
        let mut entries = Vec::new();
        for i in 0..(MAX_ENTRIES + 25) {
            push_capped(&mut entries, HistoryEntry::new(HistoryKind::Launched).seconds(i as u64));
        }
        assert_eq!(entries.len(), MAX_ENTRIES);
        assert_eq!(entries[0].seconds, Some((MAX_ENTRIES + 24) as u64));
        assert_eq!(entries.last().unwrap().seconds, Some(25));
    }

    #[test]
    fn cleans_text() {
        let e = HistoryEntry::new(HistoryKind::ModInstalled).subject("  Sodium\n\u{7}  ").detail("   ");
        assert_eq!(e.subject.as_deref(), Some("Sodium"));
        assert!(e.detail.is_none());
        assert_eq!(HistoryEntry::new(HistoryKind::Created).subject("x".repeat(500)).subject.unwrap().len(), MAX_TEXT);
    }

    #[tokio::test]
    async fn records_only_for_existing_instances() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        record(&paths, "fehlt", HistoryEntry::new(HistoryKind::Launched)).await;
        assert!(!paths.instance_dir("fehlt").exists());

        fsutil::write_json(&paths.instance_file("da"), &serde_json::json!({})).await.unwrap();
        record(&paths, "da", HistoryEntry::new(HistoryKind::Created)).await;
        record(&paths, "da", HistoryEntry::new(HistoryKind::VersionSwitched).from("1.21.1").to("1.21.4")).await;
        let list = list(&paths, "da").await.unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].kind, HistoryKind::VersionSwitched);
        assert!(super::list(&paths, "../x").await.is_err());

        let json = serde_json::to_value(&list[0]).unwrap();
        assert_eq!(json["kind"], "version_switched");
        assert!(json.get("subject").is_none());
    }
}
