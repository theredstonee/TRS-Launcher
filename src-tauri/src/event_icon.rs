//! Event-Symbole (z. B. Halloween): Die Oberfläche meldet die laufenden Events (`event_icon_sync`, beim
//! Start und bei `events_changed`); hier wird daraus das App-Symbol – Fenster/Taskleiste, Verknüpfungen
//! am PC (siehe `trs_core::event_icon`), Startsymbol unter Android (activity-alias) und iOS (alternatives
//! App-Symbol). Der Schalter „Event-Symbole“ (Einstellungen → Aussehen) gilt überall.

use std::sync::Arc;

use tauri::{AppHandle, State};
use trs_core::event_icon::{EventIcons, pick};

use crate::LauncherState;
use crate::error::CommandResult;

/// Mehr Events schickt die Oberfläche nie.
const MAX_EVENTS: usize = 64;

pub struct EventIconState {
    icons: Arc<EventIcons>,
    /// In dieser Sitzung schon angewandt (`None` = noch nicht geprüft) – gleiche Meldungen tun nichts.
    session: tauri::async_runtime::Mutex<Option<Option<&'static str>>>,
}

impl EventIconState {
    pub fn new(launcher: &LauncherState) -> Self {
        Self { icons: Arc::new(EventIcons::new(launcher.paths().root())), session: tauri::async_runtime::Mutex::new(None) }
    }

    /// Beim Start: das zuletzt angewandte Event-Symbol gleich ins Fenster (bevor die Oberfläche fragt).
    pub fn restore_window(&self, app: &AppHandle) {
        if let Some(icon) = self.icons.applied() {
            set_window_icon(app, Some(icon));
        }
    }
}

/// Laufende Events melden; liefert das jetzt geltende Event-Symbol (`null` = normal).
#[tauri::command]
pub async fn event_icon_sync(
    app: AppHandle,
    launcher: State<'_, LauncherState>,
    state: State<'_, EventIconState>,
    events: Vec<String>,
) -> CommandResult<Option<String>> {
    if events.len() > MAX_EVENTS {
        return Err(trs_core::Error::validation(trs_core::msg!("trsEvents.invalidEvent", "Ungültiges Event.")).into());
    }
    let events: Vec<String> = events.into_iter().filter(|e| trs_core::trs_api::events::event_id(e)).collect();
    let want = pick(&events, launcher.settings().await.ui.event_icons);
    let mut session = state.session.lock().await;
    if *session == Some(want) {
        return Ok(want.map(str::to_owned));
    }
    set_window_icon(&app, want);
    apply_native(&app, &state.icons, want).await;
    if let Err(e) = state.icons.set_applied(want) {
        log::warn!("Event-Symbol nicht gemerkt: {e}");
    }
    *session = Some(want);
    Ok(want.map(str::to_owned))
}

/// Fenster und Taskleiste (Windows/Linux). Ohne Event: das Standard-Symbol der App.
#[cfg(desktop)]
fn set_window_icon(app: &AppHandle, icon: Option<&str>) {
    use tauri::Manager;

    let Some(window) = app.get_webview_window("main") else { return };
    let image = match icon.and_then(trs_core::event_icon::window_rgba) {
        Some((rgba, w, h)) => Some(tauri::image::Image::new_owned(rgba, w, h)),
        None => app.default_window_icon().cloned().map(tauri::image::Image::to_owned),
    };
    if let Some(image) = image
        && let Err(e) = window.set_icon(image)
    {
        log::warn!("Fenstersymbol nicht gesetzt: {e}");
    }
}

#[cfg(mobile)]
fn set_window_icon(_app: &AppHandle, _icon: Option<&str>) {}

/// Dauerhafte Symbole: Verknüpfungen (PC) bzw. Startsymbol (Android/iOS). Fehler nur ins Log.
async fn apply_native(app: &AppHandle, icons: &Arc<EventIcons>, icon: Option<&'static str>) {
    let name = trs_core::event_icon::native_name(icon);
    #[cfg(desktop)]
    {
        let product = app.package_info().name.clone();
        let icons = Arc::clone(icons);
        let result = tauri::async_runtime::spawn_blocking(move || icons.apply_shortcuts(icon, &product)).await;
        match result {
            Ok(Ok(0)) => {}
            Ok(Ok(n)) => log::info!("Event-Symbol {name}: {n} Verknüpfung(en) umgestellt"),
            Ok(Err(e)) => log::warn!("Verknüpfungen nicht umgestellt: {e}"),
            Err(e) => log::warn!("Verknüpfungen nicht umgestellt: {e}"),
        }
    }
    #[cfg(target_os = "android")]
    {
        let _ = icons;
        match crate::mobile::set_launcher_icon(app, name).await {
            Ok(true) => log::info!("Startsymbol: {name}"),
            Ok(false) => {}
            Err(e) => log::warn!("Startsymbol nicht gewechselt: {e}"),
        }
    }
    #[cfg(target_os = "ios")]
    {
        use tauri_plugin_trs_push::TrsPushExt;
        let _ = icons;
        let handle = app.clone();
        let owned = name.to_owned();
        match tauri::async_runtime::spawn_blocking(move || handle.trs_push().set_app_icon(&owned)).await {
            Ok(Ok(true)) => log::info!("App-Symbol: {name}"),
            Ok(Ok(false)) => {}
            Ok(Err(e)) => log::warn!("App-Symbol nicht gewechselt: {e}"),
            Err(e) => log::warn!("App-Symbol nicht gewechselt: {e}"),
        }
    }
}
