//! Umziehen: Datenordner verschieben (mit Neustart) und Instanzen an einen
//! eigenen Speicherort. Die eigentliche Arbeit macht [`trs_core::relocate`].

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use trs_core::data_location::DataLocation;
use trs_core::instance::UnavailableInstance;
use trs_core::relocate::{InstanceLocation, MovePlan};

use crate::LauncherState;
use crate::commands::tasks::tracked;
use crate::dialog_text::{self, DialogText};
use crate::error::CommandResult;

/// Ordner im nativen Dialog wählen. `None` = abgebrochen. Geprüft wird erst im Plan.
#[tauri::command]
pub async fn pick_target_folder(app: AppHandle, launcher: State<'_, LauncherState>) -> CommandResult<Option<String>> {
    let lang = dialog_text::language(&launcher).await;
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog().file().set_title(DialogText::PickTargetFolder.text(lang)).blocking_pick_folder()
    })
    .await
    .ok()
    .flatten()
    .and_then(|p| p.into_path().ok());
    Ok(picked.map(|p| p.display().to_string()))
}

/// Zusammenfassung (Ziel, Größe, freier Platz) vor dem Umzug des Datenordners.
#[tauri::command]
pub async fn data_move_plan(launcher: State<'_, LauncherState>, location: State<'_, DataLocation>, target: String) -> CommandResult<MovePlan> {
    Ok(launcher.data_move_plan(&location, &target).await?)
}

/// Kopiert und prüft den Datenordner und stellt auf ihn um (wirksam nach dem Neustart).
#[tauri::command]
pub async fn move_data_dir(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    location: State<'_, DataLocation>,
    target: String,
    on_progress: Channel<u8>,
    task_id: Option<String>,
) -> CommandResult<String> {
    let work = launcher.move_data_dir(&location, &target, move |p| {
        let _ = on_progress.send(p);
    });
    Ok(tracked(&app, task_id, work).await?)
}

/// Nach dem Umzug: alten Ordner beim nächsten Start löschen oder behalten.
#[tauri::command]
pub fn confirm_data_move(launcher: State<'_, LauncherState>, location: State<'_, DataLocation>, delete_old: bool) -> CommandResult<()> {
    Ok(launcher.confirm_data_move(&location, delete_old)?)
}

#[tauri::command]
pub async fn instance_location(launcher: State<'_, LauncherState>, id: String) -> CommandResult<InstanceLocation> {
    Ok(launcher.instance_location(&id).await?)
}

/// `target`: `None` = zurück an den Standardort.
#[tauri::command]
pub async fn instance_move_plan(launcher: State<'_, LauncherState>, id: String, target: Option<String>) -> CommandResult<MovePlan> {
    Ok(launcher.instance_move_plan(&id, target.as_deref()).await?)
}

#[tauri::command]
pub async fn move_instance(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    target: Option<String>,
    on_progress: Channel<u8>,
    task_id: Option<String>,
) -> CommandResult<InstanceLocation> {
    let work = launcher.move_instance(&id, target.as_deref(), move |p| {
        let _ = on_progress.send(p);
    });
    Ok(tracked(&app, task_id, work).await?)
}

/// Instanzen an eigenem Ort, deren Ordner gerade fehlt (Laufwerk getrennt).
#[tauri::command]
pub async fn unavailable_instances(launcher: State<'_, LauncherState>) -> CommandResult<Vec<UnavailableInstance>> {
    Ok(launcher.instances().unavailable().await)
}

/// Entfernt eine nicht erreichbare Instanz aus der Liste – ihr Ordner bleibt unberührt.
#[tauri::command]
pub async fn forget_unavailable_instance(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.instances().forget_unavailable(&id).await?)
}
