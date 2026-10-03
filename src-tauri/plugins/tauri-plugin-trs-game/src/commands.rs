use tauri::{AppHandle, Runtime, command};

use crate::{GameLaunchSpec, Result, RuntimeInfo, SessionId, TrsGameExt};

#[command]
pub(crate) async fn prepare_runtime<R: Runtime>(app: AppHandle<R>, java_major: u8) -> Result<RuntimeInfo> {
    app.trs_game().prepare_runtime(java_major).await
}

#[command]
pub(crate) async fn launch<R: Runtime>(app: AppHandle<R>, spec: GameLaunchSpec) -> Result<SessionId> {
    app.trs_game().launch(spec)
}

#[command]
pub(crate) fn runtimes<R: Runtime>(app: AppHandle<R>) -> Vec<RuntimeInfo> {
    app.trs_game().runtimes()
}
