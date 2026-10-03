//! Link-Protokoll `trs-launcher://` (Knöpfe auf der Website).
//!
//! Angenommen werden nur diese Formen, alle streng geprüft:
//! - `trs-launcher://pack/<Code>` ([`trs_core::pack_share::pack_code_from_link`]) → Dialog „Modpack per Code“ mit
//!   Vorschau – installiert wird erst nach einem Klick.
//! - `trs-launcher://web-login/<Token>` ([`trs_core::trs_api::web_login::web_login_token_from_link`]) → Dialog
//!   „Auf der Website anmelden“ – bestätigt wird erst nach einem Klick, nie automatisch.
//! - `trs-launcher://remote-pair/<Code>` ([`trs_core::trs_api::remote::pair_code_from_link`], QR-Code der
//!   PC-Fernbedienung) → am Handy die Seite „PC“ mit dem Code – gekoppelt wird erst nach einem Klick.
//!
//! Läuft der Launcher schon, reicht das Single-Instance-Plugin den Link an das offene Fenster weiter.
//! Android/iOS: Das Schema steht im App-Manifest bzw. in der Info.plist (`plugins.deep-link.mobile`), das System
//! reicht Links an die laufende App weiter.

use std::sync::Mutex;

use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_deep_link::DeepLinkExt;

/// Code aus einem Link beim Start, bis die Oberfläche bereit ist und ihn abholt.
#[derive(Default)]
pub struct PendingPackLink(Mutex<Option<String>>);

/// Link-Token einer Website-Anmeldung beim Start, bis die Oberfläche ihn abholt.
#[derive(Default)]
pub struct PendingWebLogin(Mutex<Option<String>>);

/// Kopplungs-Code der Fernbedienung beim Start, bis die Oberfläche ihn abholt.
#[derive(Default)]
pub struct PendingRemotePair(Mutex<Option<String>>);

enum Link {
    Pack(String),
    WebLogin(String),
    RemotePair(String),
}

fn parse(url: &str) -> Option<Link> {
    if let Some(code) = trs_core::pack_share::pack_code_from_link(url) {
        return Some(Link::Pack(code));
    }
    if let Some(code) = trs_core::trs_api::remote::pair_code_from_link(url) {
        return Some(Link::RemotePair(code));
    }
    trs_core::trs_api::web_login::web_login_token_from_link(url).map(Link::WebLogin)
}

/// Links verarbeiten: gültigen Wert merken, ans Fenster schicken, Fenster nach vorne holen.
fn handle(app: &AppHandle, urls: impl IntoIterator<Item = String>) {
    let Some(link) = urls.into_iter().find_map(|u| parse(&u)) else { return };
    match link {
        Link::Pack(code) => {
            log::info!("Link geöffnet: Modpack {code}");
            if let Some(state) = app.try_state::<PendingPackLink>() {
                *state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(code.clone());
            }
            let _ = app.emit("open-pack-link", code);
        }
        Link::WebLogin(token) => {
            // Den Token nicht ins Log schreiben.
            log::info!("Link geöffnet: Anmeldung auf der Website");
            if let Some(state) = app.try_state::<PendingWebLogin>() {
                *state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(token.clone());
            }
            let _ = app.emit("open-web-login", token);
        }
        Link::RemotePair(code) => {
            log::info!("Link geöffnet: Fernbedienung koppeln");
            if let Some(state) = app.try_state::<PendingRemotePair>() {
                *state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(code.clone());
            }
            let _ = app.emit("open-remote-pair", code);
        }
    }
    if let Some(window) = app.get_webview_window("main") {
        #[cfg(desktop)]
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// Im `setup`: Protokoll anmelden (wo nötig), Start-Link und spätere Links annehmen.
pub fn setup(app: &tauri::App) {
    app.manage(PendingPackLink::default());
    app.manage(PendingWebLogin::default());
    app.manage(PendingRemotePair::default());
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

/// Wie [`take_pending_pack_link`], für `trs-launcher://web-login/<Token>`.
#[tauri::command]
pub fn take_pending_web_login(state: State<'_, PendingWebLogin>) -> Option<String> {
    state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take()
}

/// Wie [`take_pending_pack_link`], für `trs-launcher://remote-pair/<Code>`.
#[tauri::command]
pub fn take_pending_remote_pair(state: State<'_, PendingRemotePair>) -> Option<String> {
    state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take()
}
