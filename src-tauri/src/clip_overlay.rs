//! Clip-Overlay: ein eigenes, immer obenauf liegendes, klick-durchlässiges und aus jeder
//! Aufnahme ausgeschlossenes Fenster über dem Spiel. Es zeigt den Aufnahme-Indikator
//! („REC m:ss"), die Puffer-Anzeige („○ Puffer 30 s") und kurze Clip-Meldungen („Clip
//! gespeichert") – so landet nichts davon im Clip (den nimmt der Launcher per Windows Graphics
//! Capture genau vom Spielfenster auf). Inhalt, Blinken und Zeit macht die Oberfläche
//! (`/clip-overlay`); die Position führt der Launcher nach. Das Fenster ist nur so groß wie
//! sein Inhalt (oben links im Client-Bereich), weil es jetzt die ganze Spielsitzung über
//! sichtbar sein kann. Nur Windows – auf anderen Plattformen gibt es keine Aufnahme, die
//! Befehle sind dort No-Ops und das Fenster wird nie erstellt.

use tauri::AppHandle;
#[cfg(windows)]
use tauri::Manager;

#[cfg(windows)]
use crate::LauncherState;

/// Fenster-Label des Overlays (auch in der Fensterzustands-Sperrliste, siehe `lib.rs`).
#[cfg_attr(mobile, allow(dead_code))]
pub const LABEL: &str = "clip-overlay";

/// Größe des Overlays in logischen Pixeln (Pille + bis zu zwei Zeilen Meldung); wird mit dem
/// DPI-Faktor des Fensters umgerechnet und auf den Client-Bereich des Spiels begrenzt.
#[cfg(windows)]
const SIZE: (f64, f64) = (440.0, 100.0);

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
        .title("TRS Clips")
        .inner_size(SIZE.0, SIZE.1)
        .decorations(false)
        .transparent(true)
        .always_on_top(true)
        .skip_taskbar(true)
        .shadow(false)
        .focused(false)
        // Nie aktivierbar: Zeigen/Nachführen nimmt dem Spiel nie den Fokus.
        .focusable(false)
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

/// Lage des Overlays (physische Pixel): oben links im Client-Bereich des Spiels, so groß wie der
/// Inhalt (`SIZE` × DPI-Faktor), höchstens so groß wie der Client-Bereich.
#[cfg(windows)]
fn overlay_rect(rect: trs_core::clips::window::ScreenRect, scale: f64) -> (i32, i32, u32, u32) {
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let scaled = |logical: f64| (logical * scale.clamp(0.5, 8.0)).round().max(1.0) as u32;
    (rect.x, rect.y, scaled(SIZE.0).min(rect.width).max(1), scaled(SIZE.1).min(rect.height).max(1))
}

/// Overlay oben links über den Client-Bereich des Spielfensters legen und zeigen. Nur echte
/// Änderungen gehen an das Fenster (der 500-ms-Takt läuft die ganze Spielsitzung).
/// `Some(())` = platziert, `None` = kein (sichtbares) Spielfenster – dann besser verstecken.
#[cfg(windows)]
fn place(window: &tauri::WebviewWindow, launcher: &LauncherState, instance_id: &str) -> Option<()> {
    let pid = launcher.games().running().into_iter().find(|g| g.instance_id == instance_id)?.pid;
    let rect = trs_core::clips::window::find_screen_rect(pid)?;
    let (x, y, width, height) = overlay_rect(rect, window.scale_factor().unwrap_or(1.0));
    if window.outer_position().ok() != Some(tauri::PhysicalPosition::new(x, y)) {
        let _ = window.set_position(tauri::PhysicalPosition::new(x, y));
    }
    if window.inner_size().ok() != Some(tauri::PhysicalSize::new(width, height)) {
        let _ = window.set_size(tauri::PhysicalSize::new(width, height));
    }
    if !window.is_visible().unwrap_or(false) {
        let _ = window.show();
    }
    Some(())
}

/// Für welches Spiel das Overlay gerade zeigen soll (`None` = versteckt). Die Befehle laufen
/// synchron und damit in Aufrufreihenfolge auf dem Hauptthread und setzen nur diesen Wunsch; die
/// eigentliche Arbeit (Fenster anlegen, Spielfenster suchen) läuft in [`apply`] außerhalb davon.
#[cfg(windows)]
static WANTED: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

#[cfg(windows)]
fn wanted() -> Option<String> {
    WANTED.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone()
}

#[cfg(windows)]
fn set_wanted(instance_id: Option<String>) {
    *WANTED.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = instance_id;
}

/// Wunsch umsetzen – nie auf dem Hauptthread: Ein Webview-Fenster in einem synchronen Befehl
/// (also auf dem Hauptthread) anzulegen, blockiert unter Windows die ganze Oberfläche (WebView2
/// wartet auf den Hauptthread, der auf das Fenster wartet). Auch die Suche nach dem Spielfenster
/// (Prozess-Schnappschuss) soll die Oberfläche nicht im 500-ms-Takt bremsen.
#[cfg(windows)]
fn apply(app: AppHandle) {
    // Nacheinander (nie zwei Fenster gleichzeitig anlegen); der Hauptthread nimmt diese Sperre nie.
    static APPLY: std::sync::Mutex<()> = std::sync::Mutex::new(());
    tauri::async_runtime::spawn_blocking(move || {
        let _serial = APPLY.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let Some(instance_id) = wanted() else {
            if let Some(window) = app.get_webview_window(LABEL) {
                let _ = window.hide();
            }
            return;
        };
        let Some(window) = ensure(&app) else { return };
        if place(&window, &app.state::<LauncherState>(), &instance_id).is_none() {
            let _ = window.hide();
        }
        // Inzwischen versteckt worden (Befehl lief, während hier gezeigt wurde)? Dann gilt das.
        if wanted().is_none() {
            let _ = window.hide();
        }
    });
}

/// Overlay zeigen (Aufnahme, Puffer-Anzeige oder Meldung für dieses Spiel).
#[tauri::command]
pub fn clip_overlay_show(app: AppHandle, instance_id: String) {
    #[cfg(windows)]
    {
        set_wanted(Some(instance_id));
        apply(app);
    }
    #[cfg(not(windows))]
    let _ = (app, instance_id);
}

/// Solange das Overlay etwas zeigt (~500-ms-Takt): Position/Größe dem Spielfenster nachführen;
/// ist das Spielfenster weg oder minimiert, das Overlay verstecken.
#[tauri::command]
pub fn clip_overlay_track(app: AppHandle, instance_id: String) {
    #[cfg(windows)]
    if wanted().as_deref() == Some(instance_id.as_str()) {
        apply(app);
    }
    #[cfg(not(windows))]
    let _ = (app, instance_id);
}

/// Nichts mehr zu zeigen: Overlay verstecken (das Fenster bleibt für später bestehen).
#[tauri::command]
pub fn clip_overlay_hide(app: AppHandle) {
    #[cfg(windows)]
    {
        set_wanted(None);
        if let Some(window) = app.get_webview_window(LABEL) {
            let _ = window.hide();
        }
    }
    #[cfg(not(windows))]
    let _ = app;
}
