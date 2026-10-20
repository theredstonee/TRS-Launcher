//! Link-Protokoll `trs-launcher://` (Knöpfe auf der Website).
//!
//! Angenommen werden nur diese Formen, alle streng geprüft:
//! - `trs-launcher://pack/<Code>` ([`trs_core::pack_share::pack_code_from_link`]) → Dialog „Modpack per Code“ mit
//!   Vorschau – installiert wird erst nach einem Klick.
//! - `trs-launcher://web-login/<Token>` ([`trs_core::trs_api::web_login::web_login_token_from_link`]) → Dialog
//!   „Auf der Website anmelden“ – bestätigt wird erst nach einem Klick, nie automatisch.
//! - `trs-launcher://remote-pair/<Code>` ([`trs_core::trs_api::remote::pair_code_from_link`], QR-Code der
//!   PC-Fernbedienung) → am Handy die Seite „PC“ mit dem Code – gekoppelt wird erst nach einem Klick.
//! - `trs-launcher://notify/<Route>` (Tipp auf eine Push-Benachrichtigung, Route aus §33.5 wie `/chat/c…`) → die
//!   Oberfläche öffnet die passende Seite. Es passiert nichts außer Navigation.
//! - `trs-launcher://modpack/modrinth/<ID oder Slug>` bzw. `trs-launcher://modpack/curseforge/<Zahl>` (z. B. aus den
//!   empfohlenen Modpacks) → Projektseite im Launcher. Installiert wird erst nach einem Klick.
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

/// Route einer angetippten Push-Benachrichtigung beim Start, bis die Oberfläche sie abholt.
#[derive(Default)]
pub struct PendingPushTarget(Mutex<Option<String>>);

/// Modpack-Projekt (`modrinth/<ID>` bzw. `curseforge/<Zahl>`) beim Start, bis die Oberfläche es abholt.
#[derive(Default)]
pub struct PendingModpackLink(Mutex<Option<String>>);

enum Link {
    Pack(String),
    WebLogin(String),
    RemotePair(String),
    PushTarget(String),
    /// `modrinth/<ID>` bzw. `curseforge/<Zahl>`.
    Modpack(String),
}

/// `trs-launcher://modpack/modrinth/AANobbMI` → `modrinth/AANobbMI`; `…/curseforge/925200` → `curseforge/925200`.
/// Modrinth: ID oder Slug (`[A-Za-z0-9_-]`, bis 64 Zeichen), CurseForge: Zahl. Sonst nichts.
fn modpack_from_link(url: &str) -> Option<String> {
    use trs_core::featured::{FeaturedSource, is_project_id};
    let (scheme, rest) = url.split_once("://")?;
    if !scheme.eq_ignore_ascii_case(trs_core::pack_share::LINK_SCHEME) || url.len() > 200 {
        return None;
    }
    let rest = rest.strip_prefix("modpack/")?;
    let rest = rest.split(['?', '#']).next()?.trim_end_matches('/');
    let (platform, id) = rest.split_once('/')?;
    let source = match platform {
        "modrinth" => FeaturedSource::Modrinth,
        "curseforge" => FeaturedSource::Curseforge,
        _ => return None,
    };
    is_project_id(source, id).then(|| format!("{platform}/{id}"))
}

/// `trs-launcher://notify/chat/c123` → `/chat/c123` (nur Routen nach §33.5).
fn push_target_from_link(url: &str) -> Option<String> {
    let (scheme, rest) = url.split_once("://")?;
    if !scheme.eq_ignore_ascii_case(trs_core::pack_share::LINK_SCHEME) || url.len() > 300 {
        return None;
    }
    let rest = rest.strip_prefix("notify")?;
    let route = rest.split(['?', '#']).next()?.trim_end_matches('/');
    trs_core::trs_api::push::push_target(route).then(|| route.to_owned())
}

fn parse(url: &str) -> Option<Link> {
    if let Some(code) = trs_core::pack_share::pack_code_from_link(url) {
        return Some(Link::Pack(code));
    }
    if let Some(code) = trs_core::trs_api::remote::pair_code_from_link(url) {
        return Some(Link::RemotePair(code));
    }
    if let Some(target) = push_target_from_link(url) {
        return Some(Link::PushTarget(target));
    }
    if let Some(project) = modpack_from_link(url) {
        return Some(Link::Modpack(project));
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
        Link::PushTarget(target) => {
            log::info!("Link geöffnet: Benachrichtigung");
            if let Some(state) = app.try_state::<PendingPushTarget>() {
                *state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(target.clone());
            }
            let _ = app.emit("open-push-target", target);
        }
        Link::Modpack(project) => {
            log::info!("Link geöffnet: Modpack {project}");
            if let Some(state) = app.try_state::<PendingModpackLink>() {
                *state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(project.clone());
            }
            let _ = app.emit("open-modpack-link", project);
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
    app.manage(PendingPushTarget::default());
    app.manage(PendingModpackLink::default());
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

/// Wie [`take_pending_pack_link`], für `trs-launcher://notify/<Route>`.
#[tauri::command]
pub fn take_pending_push_target(state: State<'_, PendingPushTarget>) -> Option<String> {
    state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take()
}

/// Wie [`take_pending_pack_link`], für `trs-launcher://modpack/<Plattform>/<ID>`.
#[tauri::command]
pub fn take_pending_modpack_link(state: State<'_, PendingModpackLink>) -> Option<String> {
    state.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_links_carry_only_known_routes() {
        assert_eq!(push_target_from_link("trs-launcher://notify/chat/c0123456789abcdef0123").as_deref(), Some("/chat/c0123456789abcdef0123"));
        assert_eq!(push_target_from_link("TRS-LAUNCHER://notify/friends/requests/").as_deref(), Some("/friends/requests"));
        assert_eq!(push_target_from_link("trs-launcher://notify/issues/42?x=1").as_deref(), Some("/issues/42"));
        assert!(push_target_from_link("trs-launcher://notify/").is_none());
        assert!(push_target_from_link("trs-launcher://notify/../etc").is_none());
        assert!(push_target_from_link("trs-launcher://notifyx/chat").is_none());
        assert!(push_target_from_link("https://notify/chat").is_none());
        assert!(matches!(parse("trs-launcher://notify/worlds/r1/requests"), Some(Link::PushTarget(t)) if t == "/worlds/r1/requests"));
    }

    #[test]
    fn modpack_links_open_only_valid_projects() {
        assert_eq!(modpack_from_link("trs-launcher://modpack/modrinth/AANobbMI").as_deref(), Some("modrinth/AANobbMI"));
        assert_eq!(modpack_from_link("TRS-LAUNCHER://modpack/modrinth/fabulously-optimized/?x=1").as_deref(), Some("modrinth/fabulously-optimized"));
        assert_eq!(modpack_from_link("trs-launcher://modpack/curseforge/925200").as_deref(), Some("curseforge/925200"));
        assert!(modpack_from_link("trs-launcher://modpack/curseforge/abc").is_none());
        assert!(modpack_from_link("trs-launcher://modpack/curseforge/0123").is_none());
        assert!(modpack_from_link("trs-launcher://modpack/modrinth/../etc").is_none());
        assert!(modpack_from_link("trs-launcher://modpack/modrinth/a/b").is_none());
        assert!(modpack_from_link("trs-launcher://modpack/modrinth/").is_none());
        assert!(modpack_from_link("trs-launcher://modpack/github/x").is_none());
        assert!(modpack_from_link(&format!("trs-launcher://modpack/modrinth/{}", "a".repeat(65))).is_none());
        assert!(modpack_from_link("https://modpack/modrinth/AANobbMI").is_none());
        assert!(matches!(parse("trs-launcher://modpack/curseforge/925200"), Some(Link::Modpack(p)) if p == "curseforge/925200"));
        // Codes geteilter Modpacks bleiben beim alten Weg.
        assert!(!matches!(parse("trs-launcher://pack/ABCD-EFGH"), Some(Link::Modpack(_))));
    }
}
