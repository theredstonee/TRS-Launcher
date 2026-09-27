//! Modpacks teilen (§27): per Code/Link/an Freunde teilen, per Code installieren, Updates übernehmen.

use std::sync::Arc;

use tauri::ipc::Channel;
use tauri::{AppHandle, State};
use trs_core::modpack::PackProgress;
use trs_core::modpack_export::ExportProgress;
use trs_core::pack_share::{PackCodePreview, PackLinkView, PackUpdateInfo, PackUpdateResult, SharePackOptions, SharePackOutcome};
use trs_core::trs_api::packs::{InboxPack, MyPacks, OwnPack, SendResult, SharedPack};

use crate::LauncherState;
use crate::commands::instances::{InstanceView, view};
use crate::commands::tasks::tracked;
use crate::error::CommandResult;

/// Instanz packen und teilen; `update` = neue Version des schon geteilten Packs (gleicher Code).
#[tauri::command]
pub async fn share_pack(
    launcher: State<'_, LauncherState>,
    id: String,
    options: SharePackOptions,
    update: bool,
    on_progress: Channel<ExportProgress>,
) -> CommandResult<SharePackOutcome> {
    let progress: trs_core::modpack_export::ExportProgressFn = Arc::new(move |p| {
        let _ = on_progress.send(p);
    });
    Ok(launcher.share_pack(&id, &options, update, &progress).await?)
}

#[tauri::command]
pub async fn packs_mine(launcher: State<'_, LauncherState>) -> CommandResult<MyPacks> {
    Ok(launcher.trs_packs_mine().await?)
}

#[tauri::command]
pub async fn pack_set_duration(launcher: State<'_, LauncherState>, id: String, duration: String) -> CommandResult<OwnPack> {
    Ok(launcher.trs_pack_set_duration(&id, &duration).await?)
}

#[tauri::command]
pub async fn pack_delete(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.pack_delete(&id).await?)
}

#[tauri::command]
pub async fn pack_send(launcher: State<'_, LauncherState>, id: String, friends: Vec<String>) -> CommandResult<SendResult> {
    Ok(launcher.trs_pack_send(&id, &friends).await?)
}

#[tauri::command]
pub async fn pack_inbox(launcher: State<'_, LauncherState>) -> CommandResult<Vec<InboxPack>> {
    Ok(launcher.trs_pack_inbox().await?)
}

#[tauri::command]
pub async fn pack_inbox_dismiss(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.trs_pack_inbox_dismiss(&id).await?)
}

/// Pack zu einem Code (nur Daten der API, ohne Download).
#[tauri::command]
pub async fn pack_by_code(launcher: State<'_, LauncherState>, code: String) -> CommandResult<SharedPack> {
    Ok(launcher.trs_pack_by_code(&code).await?)
}

/// Pack zu einem Code laden und prüfen (für „mit/ohne TRS Client“); bleibt für die Installation liegen.
#[tauri::command]
pub async fn preview_pack_code(launcher: State<'_, LauncherState>, code: String) -> CommandResult<PackCodePreview> {
    Ok(launcher.preview_pack_code(&code).await?)
}

#[tauri::command]
pub async fn install_pack_code(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    code: String,
    trs_client: Option<bool>,
    on_progress: Channel<PackProgress>,
    task_id: Option<String>,
) -> CommandResult<InstanceView> {
    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = launcher.install_pack_code(&code, trs_client, &report);
    let instance = tracked(&app, task_id, work).await?;
    Ok(view(&app, &launcher, instance))
}

#[tauri::command]
pub async fn pack_links(launcher: State<'_, LauncherState>) -> CommandResult<Vec<PackLinkView>> {
    Ok(launcher.pack_links().await?)
}

#[tauri::command]
pub async fn pack_unlink(launcher: State<'_, LauncherState>, id: String) -> CommandResult<()> {
    Ok(launcher.pack_unlink(&id).await?)
}

#[tauri::command]
pub async fn pack_updates(launcher: State<'_, LauncherState>) -> CommandResult<Vec<PackUpdateInfo>> {
    Ok(launcher.pack_updates().await?)
}

#[tauri::command]
pub async fn update_pack_instance(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    id: String,
    on_progress: Channel<PackProgress>,
    task_id: Option<String>,
) -> CommandResult<PackUpdateResult> {
    let report = move |progress| {
        let _ = on_progress.send(progress);
    };
    let work = launcher.update_pack_instance(&id, &report);
    Ok(tracked(&app, task_id, work).await?)
}
