//! Hintergrund-Aufgaben: Abbrechen, Pausieren, Byte-Stand als Event und der
//! Verlauf fertiger Aufgaben.
//!
//! Das Frontend vergibt jeder Aufgabe eine ID und reicht sie beim Start mit
//! (`taskId`). Der Byte-Stand kommt als Event `task-progress` – unabhängig
//! davon, ob die Seite, die die Aufgabe gestartet hat, noch offen ist.

use std::collections::HashMap;
use std::future::Future;
use std::sync::Mutex;
use std::time::Duration;

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State};
use trs_core::task::{TaskControl, drive};
use trs_core::task_history::{self, NewTaskRecord, TaskRecord};

use crate::LauncherState;
use crate::error::CommandResult;

const MAX_TASK_ID_LEN: usize = 128;
const REPORT_EVERY: Duration = Duration::from_millis(250);

/// Laufende Aufgaben mit ID (Abbrechen/Pausieren von außen).
#[derive(Default)]
pub struct TaskRegistry {
    tasks: Mutex<HashMap<String, TaskControl>>,
}

impl TaskRegistry {
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, TaskControl>> {
        self.tasks.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn get(&self, id: &str) -> Option<TaskControl> {
        self.lock().get(id).cloned()
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct TaskProgressEvent<'a> {
    task_id: &'a str,
    done_bytes: u64,
    total_bytes: u64,
    paused: bool,
}

fn valid_task_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_TASK_ID_LEN
        && id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b':' | b'.' | b'_' | b'-'))
}

/// Meldet die Aufgabe beim Verwerfen ab – auch wenn das Command-Future
/// vorzeitig fallen gelassen wird.
struct Registration<'a> {
    registry: &'a TaskRegistry,
    id: String,
}

impl Drop for Registration<'_> {
    fn drop(&mut self) {
        self.registry.lock().remove(&self.id);
    }
}

/// Führt `work` als Aufgabe `task_id` aus (ohne ID einfach so). Fehler beim
/// Senden ans Frontend werden ignoriert – die Aufgabe läuft immer zu Ende.
pub async fn tracked<T>(
    app: &AppHandle,
    task_id: Option<String>,
    work: impl Future<Output = trs_core::Result<T>>,
) -> trs_core::Result<T> {
    let Some(id) = task_id else { return work.await };
    if !valid_task_id(&id) {
        return Err(trs_core::Error::validation("Ungültige Aufgaben-ID"));
    }
    let registry = app.state::<TaskRegistry>();
    let control = TaskControl::new();
    {
        let mut tasks = registry.lock();
        if tasks.contains_key(&id) {
            return Err(trs_core::Error::validation("Diese Aufgabe läuft bereits."));
        }
        tasks.insert(id.clone(), control.clone());
    }
    let _registration = Registration { registry: registry.inner(), id: id.clone() };
    drive(control, REPORT_EVERY, work, |bytes, paused| {
        let event = TaskProgressEvent { task_id: &id, done_bytes: bytes.done, total_bytes: bytes.total, paused };
        if let Err(e) = app.emit("task-progress", event) {
            log::debug!("task-progress konnte nicht gesendet werden: {e}");
        }
    })
    .await
}

/// Bricht eine laufende Aufgabe ab. `false` = keine solche Aufgabe (mehr).
#[tauri::command]
pub fn cancel_task(registry: State<'_, TaskRegistry>, task_id: String) -> bool {
    match registry.get(&task_id) {
        Some(control) => {
            control.cancel();
            true
        }
        None => false,
    }
}

/// Pausiert eine Aufgabe (Downloads halten beim nächsten Stück an) oder setzt sie fort.
#[tauri::command]
pub fn pause_task(registry: State<'_, TaskRegistry>, task_id: String, paused: bool) -> bool {
    match registry.get(&task_id) {
        Some(control) => {
            control.set_paused(paused);
            true
        }
        None => false,
    }
}

#[tauri::command]
pub async fn task_history(launcher: State<'_, LauncherState>) -> CommandResult<Vec<TaskRecord>> {
    Ok(task_history::list(launcher.paths()).await)
}

#[tauri::command]
pub async fn record_task(launcher: State<'_, LauncherState>, record: NewTaskRecord) -> CommandResult<TaskRecord> {
    Ok(task_history::add(launcher.paths(), record).await?)
}

#[tauri::command]
pub async fn remove_task_record(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(task_history::remove(launcher.paths(), &id).await?)
}

#[tauri::command]
pub async fn clear_task_history(launcher: State<'_, LauncherState>) -> CommandResult<()> {
    Ok(task_history::clear(launcher.paths()).await?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn task_ids_are_restricted() {
        assert!(valid_task_id("modpack:AANobbMI"));
        assert!(valid_task_id("launch:my-pack_1.2"));
        for bad in ["", "a b", "../x", "x/y", "ä", &"x".repeat(MAX_TASK_ID_LEN + 1)] {
            assert!(!valid_task_id(bad), "{bad:?}");
        }
    }

    #[test]
    fn registration_unregisters_on_drop() {
        let registry = TaskRegistry::default();
        registry.lock().insert("a".into(), TaskControl::new());
        {
            let _r = Registration { registry: &registry, id: "a".into() };
            assert!(registry.get("a").is_some());
        }
        assert!(registry.get("a").is_none());
    }
}
