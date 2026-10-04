//! Brücke zum nativen Teil (Kotlin `PushPlugin` / Swift `TrsPushPlugin`).

use serde::Serialize;
use serde::de::DeserializeOwned;
use tauri::Runtime;
use tauri::plugin::{PluginApi, PluginHandle};

use crate::{Error, Result};

#[cfg(target_os = "android")]
const PLUGIN_IDENTIFIER: &str = "dev.theredstonee.trs.push";

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_trs_push);

pub struct Native<R: Runtime>(PluginHandle<R>);

impl<R: Runtime> Native<R> {
    pub fn init<C: DeserializeOwned>(api: PluginApi<R, C>) -> std::result::Result<Self, Box<dyn std::error::Error>> {
        #[cfg(target_os = "android")]
        let handle = api.register_android_plugin(PLUGIN_IDENTIFIER, "PushPlugin")?;
        #[cfg(target_os = "ios")]
        let handle = api.register_ios_plugin(init_plugin_trs_push)?;
        Ok(Self(handle))
    }

    /// Blockiert bis zur Antwort – aus Rust nur außerhalb der Async-Runtime aufrufen (`spawn_blocking`).
    pub fn call<T: DeserializeOwned, A: Serialize>(&self, command: &str, args: A) -> Result<T> {
        self.0.run_mobile_plugin(command, args).map_err(|e| match e {
            tauri::plugin::mobile::PluginInvokeError::InvokeRejected(r) => Error(r.code.or(r.message).unwrap_or_else(|| "rejected".into())),
            other => Error(other.to_string()),
        })
    }
}
