//! Brücke zum Swift-Plugin (`ios/Sources/TrsGame/TrsGamePlugin.swift`). Nur auf iOS gebaut.
//! Swift-Befehle: `probe` (Gerätedaten) und `launch` ({request, events}).

use std::sync::Arc;

use serde::Serialize;
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::plugin::PluginHandle;
use tauri::plugin::mobile::PluginInvokeError;
use tauri::{AppHandle, Emitter, Runtime};

use super::args::EngineLaunch;
use super::engine::{self, Bridge, Emit, Emitted, EngineEvents};
use super::probe::DeviceProbe;
use super::session::EngineEvent;
use crate::models::{EVENT_LOG, EVENT_STATE, GameLaunchSpec, RuntimeInfo, SessionId};
use crate::{Error, Result};

/// Von `mobile::Engine::launch` auf iOS aufgerufen.
pub fn launch<R: Runtime>(app: &AppHandle<R>, handle: &PluginHandle<R>, spec: &GameLaunchSpec, runtime: &RuntimeInfo) -> Result<SessionId> {
    let app = app.clone();
    let emit: Emit = Arc::new(move |event| {
        let sent = match event {
            Emitted::State(s) => app.emit(EVENT_STATE, s),
            Emitted::Log(l) => app.emit(EVENT_LOG, l),
        };
        if let Err(e) = sent {
            log::warn!("trs-game-Event nicht gesendet: {e}");
        }
    });
    engine::launch(&TauriBridge(handle.clone()), &engine::PROCESS_GUARD, spec, runtime, emit)
}

pub struct TauriBridge<R: Runtime>(pub PluginHandle<R>);

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LaunchArgs<'a> {
    request: &'a EngineLaunch,
    events: Channel<serde_json::Value>,
}

/// Swift lehnt mit Code ab (`engineMissing`, `restartRequired`).
fn map_rejection(e: PluginInvokeError) -> Error {
    let code = match &e {
        PluginInvokeError::InvokeRejected(r) => r.code.clone(),
        _ => None,
    };
    match code.as_deref() {
        Some("engineMissing") => Error::EngineMissing,
        Some("restartRequired") => Error::RestartRequired,
        _ => Error::PluginInvoke(e),
    }
}

impl<R: Runtime> Bridge for TauriBridge<R> {
    fn probe(&self) -> Result<DeviceProbe> {
        self.0.run_mobile_plugin::<DeviceProbe>("probe", ()).map_err(map_rejection)
    }

    fn launch(&self, launch: &EngineLaunch, events: EngineEvents) -> Result<()> {
        let channel = Channel::new(move |body| {
            let parsed = match body {
                InvokeResponseBody::Json(json) => serde_json::from_str::<EngineEvent>(&json),
                InvokeResponseBody::Raw(bytes) => serde_json::from_slice::<EngineEvent>(&bytes),
            };
            match parsed {
                Ok(event) => events(event),
                Err(e) => log::warn!("Unbekanntes Engine-Ereignis: {e}"),
            }
            Ok(())
        });
        self.0
            .run_mobile_plugin::<serde_json::Value>("launch", LaunchArgs { request: launch, events: channel })
            .map(|_| ())
            .map_err(map_rejection)
    }
}
