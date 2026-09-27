//! Link-Protokoll `trs-launcher://` (Knopf „Im Launcher öffnen“ auf der Website).
//!
//! Nur `trs-launcher://pack/<Code>` wird angenommen ([`trs_core::pack_share::pack_code_from_link`]); der Launcher
//! öffnet dann den Dialog „Modpack per Code“ mit Vorschau – installiert wird erst nach einem Klick. Läuft der Launcher
//! schon, reicht das Single-Instance-Plugin den Link an das offene Fenster weiter.

use std::sync::Mutex;

use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_deep_link::DeepLinkExt;

/// Code aus einem Link beim Start, bis die Oberfläche bereit ist und ihn abholt.
#[derive(Default)]
pub struct PendingPackLink(Mutex<Option<String>>);

/// Links verarbeiten: gültigen Code merken, ans Fenster schicken, Fenster nach vorne holen.
fn handle(app: &AppHandle, urls: impl IntoIterator<Item = String>) {
    let Some(code) = urls.into_iter().find_map(|u| trs_core::pack_share::pack_code_from_link(&u)) else { return };
    log::info!("Link geöffnet: Modpack {code}");
    if let Some(state) = app.try_state::<PendingPackLink>() {
        *state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(code.clone());
    }
    let _ = app.emit("open-pack-link", code);
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// Im `setup`: Protokoll anmelden (wo nötig), Start-Link und spätere Links annehmen.
pub fn setup(app: &tauri::App) {
    app.manage(PendingPackLink::default());
    // Installer melden das Protokoll an; zusätzlich beim Start (nur für den eigenen Benutzer), damit es auch nach
    // Updates über ältere Installer, im AppImage und in Entwicklungs-Builds zum laufenden Programm zeigt.
    #[cfg(any(target_os = "linux", windows))]
    if let Err(e) = app.deep_link().register_all() {
        log::warn!("Link-Protokoll trs-launcher:// nicht angemeldet: {e}");
    }
    if let Ok(Some(urls)) = app.deep_link().get_current() {
        handle(app.handle(), urls.into_iter().map(|u| u.to_string()));
    }
    let handle_app = app.handle().clone();
    app.deep_link().on_open_url(move |event| {
        handle(&handle_app, event.urls().into_iter().map(|u| u.to_string()));
    });
}

/// Die Oberfläche holt einen beim Start mitgegebenen Link ab (einmalig).
#[tauri::command]
pub fn take_pending_pack_link(state: State<'_, PendingPackLink>) -> Option<String> {
    state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take()
}
