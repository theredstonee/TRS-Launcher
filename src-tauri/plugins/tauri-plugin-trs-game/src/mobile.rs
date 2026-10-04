//! Brücke zur nativen Engine (Kotlin `TrsGamePlugin` / Swift `TrsGamePlugin`).
// iOS startet über `crate::ios` – der Android-Weg bleibt dort ungenutzt.
#![cfg_attr(target_os = "ios", allow(dead_code))]

use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::plugin::{PluginApi, PluginHandle};
use tauri::{AppHandle, Emitter, Runtime};

use crate::models::*;
use crate::Result;

#[cfg(target_os = "android")]
const PLUGIN_IDENTIFIER: &str = "dev.theredstonee.trs.game";

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_trs_game);

pub struct Engine<R: Runtime> {
    app: AppHandle<R>,
    handle: PluginHandle<R>,
}

impl<R: Runtime> Engine<R> {
    pub fn init<C: serde::de::DeserializeOwned>(app: &AppHandle<R>, api: PluginApi<R, C>) -> Result<Self> {
        #[cfg(target_os = "android")]
        let handle = api.register_android_plugin(PLUGIN_IDENTIFIER, "TrsGamePlugin")?;
        #[cfg(target_os = "ios")]
        let handle = api.register_ios_plugin(init_plugin_trs_game)?;
        Ok(Self { app: app.clone(), handle })
    }

    pub fn launch(&self, spec: &GameLaunchSpec, runtime: &RuntimeInfo) -> Result<SessionId> {
        // iOS: JIT, Speicher und JVM-Argumente in Rust (src/ios/), Engine im App-Prozess.
        #[cfg(target_os = "ios")]
        {
            crate::ios::tauri_bridge::launch(&self.app, &self.handle, spec, runtime)
        }
        #[cfg(not(target_os = "ios"))]
        {
            self.launch_engine(spec, runtime)
        }
    }

    fn launch_engine(&self, spec: &GameLaunchSpec, runtime: &RuntimeInfo) -> Result<SessionId> {
        let session = new_session_id();
        let renderer = spec.renderer.resolve(spec.game_version.as_deref());
        let app = self.app.clone();
        let on_event = Channel::new(move |body| {
            forward_event(&app, body);
            Ok(())
        });
        let payload = LaunchPayload {
            session: &session,
            spec,
            renderer,
            lwjgl: spec.engine_lwjgl(),
            lwjglx: spec.needs_lwjglx(),
            java_home: &runtime.home,
            on_event,
        };
        let _: serde_json::Value = self.handle.run_mobile_plugin("launch", payload)?;
        Ok(SessionId(session))
    }

    pub async fn run_java(&self, spec: &JavaRunSpec, runtime: &RuntimeInfo) -> Result<JavaRunResult> {
        let payload = JavaPayload { spec, java_home: &runtime.home };
        Ok(self.handle.run_mobile_plugin_async("runJava", payload).await?)
    }

    pub fn stop(&self, session: &SessionId) -> Result<()> {
        let _: serde_json::Value = self.handle.run_mobile_plugin("stop", StopPayload { session: &session.0 })?;
        Ok(())
    }

    pub async fn session_end(&self, session: &str, since_ms: i64) -> Result<SessionEnd> {
        Ok(self.handle.run_mobile_plugin_async("sessionEnd", SessionEndPayload { session, since_ms }).await?)
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LaunchPayload<'a> {
    session: &'a str,
    spec: &'a GameLaunchSpec,
    /// Aufgelöst (nie `auto`).
    renderer: Renderer,
    lwjgl: &'static str,
    lwjglx: bool,
    java_home: &'a Path,
    on_event: Channel<serde_json::Value>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct JavaPayload<'a> {
    spec: &'a JavaRunSpec,
    java_home: &'a Path,
}

#[derive(Serialize)]
struct StopPayload<'a> {
    session: &'a str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SessionEndPayload<'a> {
    session: &'a str,
    since_ms: i64,
}

/// Nachricht der Engine über den Kanal.
#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
enum EngineMessage {
    State(GameStateEvent),
    Log(GameLogEvent),
}

fn forward_event<R: Runtime>(app: &AppHandle<R>, body: InvokeResponseBody) {
    let InvokeResponseBody::Json(json) = body else { return };
    match serde_json::from_str::<EngineMessage>(&json) {
        Ok(EngineMessage::State(state)) => {
            let _ = app.emit(EVENT_STATE, state);
        }
        Ok(EngineMessage::Log(log)) => {
            let _ = app.emit(EVENT_LOG, log);
        }
        Err(e) => log::debug!("trs-game: unbekannte Engine-Nachricht: {e}"),
    }
}

fn new_session_id() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let millis = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
    format!("g{millis:x}-{}", COUNTER.fetch_add(1, Ordering::Relaxed))
}
