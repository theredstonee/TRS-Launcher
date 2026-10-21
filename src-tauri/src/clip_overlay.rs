//! REC-Overlay: ein eigenes, immer obenauf liegendes, klick-durchlässiges und aus jeder
//! Aufnahme ausgeschlossenes Fenster, das den Aufnahme-Indikator („REC m:ss") über dem Spiel
//! zeigt. So landet „REC" nicht im Clip (den nimmt der Launcher per Windows Graphics Capture
//! genau vom Spielfenster auf). Blinken und die Zeit zählt die Oberfläche (`/clip-overlay`);
//! die Position führt der Launcher nach. Nur Windows – auf anderen Plattformen gibt es keine
//! Aufnahme, die Befehle sind dort No-Ops und das Fenster wird nie erstellt.

use tauri::{AppHandle, State};
#[cfg(windows)]
use tauri::Manager;

use crate::LauncherState;

/// Fenster-Label des Overlays (auch in der Fensterzustands-Sperrliste, siehe `lib.rs`).
#[cfg_attr(mobile, allow(dead_code))]
pub const LABEL: &str = "clip-overlay";

/// Overlay-Fenster holen oder beim ersten Mal anlegen: transparent, randlos, immer obenauf,
/// nicht in der Taskleiste, ohne Schatten, nicht fokussierbar/änderbar, versteckt – dazu
/// klick-durchlässig und aus jeder Aufnahme ausgeschlossen.
#[cfg(windows)]
fn ensure(app: &AppHandle) -> Option<tauri::WebviewWindow> {
    use tauri::{WebviewUrl, WebviewWindowBuilder};

    if let Some(window) = app.get_webview_window(LABEL) {
        return Some(window);
    }
    let window = WebviewWindowBuilder::new(app, LABEL, WebviewUrl::App("clip-overlay".into()))
        .title("TRS REC")
        .inner_size(320.0, 64.0)
        .decorations(false)
        .transparent(true)
        .always_on_top(true)
        .skip_taskbar(true)
        .shadow(false)
        .focused(false)
        .resizable(false)
        .maximizable(false)
        .minimizable(false)
        .closable(false)
        .visible(false)
        .build()
        .inspect_err(|e| log::warn!("REC-Overlay konnte nicht erstellt werden: {e}"))
        .ok()?;
    // Mausklicks gehen durch das Overlay hindurch (direkt ins Spiel).
    if let Err(e) = window.set_ignore_cursor_events(true) {
        log::warn!("REC-Overlay: Klick-Durchlass konnte nicht gesetzt werden: {e}");
    }
    // Aus jeder Fenster-/Bildschirm-Aufnahme ausschließen (WDA_EXCLUDEFROMCAPTURE) – so ist „REC"
    // nie im Clip, obwohl es auf dem Bildschirm sichtbar ist.
    match window.hwnd() {
        Ok(hwnd) => {
            if !trs_core::clips::window::exclude_from_capture(hwnd.0 as usize as u64) {
                log::warn!("REC-Overlay: Aufnahme-Ausschluss (DisplayAffinity) nicht gesetzt");
            }
        }
        Err(e) => log::warn!("REC-Overlay: HWND nicht verfügbar: {e}"),
    }
    Some(window)
}

/// Overlay deckungsgleich über den Client-Bereich des Spielfensters legen.
/// `Some(())` = platziert, `None` = kein (sichtbares) Spielfenster – dann besser verstecken.
#[cfg(windows)]
fn place(window: &tauri::WebviewWindow, launcher: &LauncherState, instance_id: &str) -> Option<()> {
    let pid = launcher.games().running().into_iter().find(|g| g.instance_id == instance_id)?.pid;
    let rect = trs_core::clips::window::find_screen_rect(pid)?;
    let _ = window.set_position(tauri::PhysicalPosition::new(rect.x, rect.y));
    let _ = window.set_size(tauri::PhysicalSize::new(rect.width.max(1), rect.height.max(1)));
    Some(())
}

/// Aufnahme hat begonnen: Overlay über das Spiel legen und zeigen.
#[tauri::command]
pub fn clip_overlay_show(app: AppHandle, launcher: State<'_, LauncherState>, instance_id: String) {
    #[cfg(windows)]
    if let Some(window) = ensure(&app) {
        if place(&window, &launcher, &instance_id).is_some() {
            let _ = window.show();
        } else {
            let _ = window.hide();
        }
    }
    #[cfg(not(windows))]
    let _ = (app, launcher, instance_id);
}

/// Während der Aufnahme (~500 ms-Takt): Position/Größe dem Spielfenster nachführen;
/// ist das Spielfenster weg oder minimiert, das Overlay verstecken.
#[tauri::command]
pub fn clip_overlay_track(app: AppHandle, launcher: State<'_, LauncherState>, instance_id: String) {
    #[cfg(windows)]
    if let Some(window) = app.get_webview_window(LABEL) {
        if place(&window, &launcher, &instance_id).is_some() {
            let _ = window.show();
        } else {
            let _ = window.hide();
        }
    }
    #[cfg(not(windows))]
    let _ = (app, launcher, instance_id);
}

/// Aufnahme beendet: Overlay verstecken (das Fenster bleibt für die nächste Aufnahme bestehen).
#[tauri::command]
pub fn clip_overlay_hide(app: AppHandle) {
    #[cfg(windows)]
    if let Some(window) = app.get_webview_window(LABEL) {
        let _ = window.hide();
    }
    #[cfg(not(windows))]
    let _ = app;
}
