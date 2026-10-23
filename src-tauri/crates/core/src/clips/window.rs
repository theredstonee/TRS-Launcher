//! Findet das Fenster des Spiels: sichtbares Hauptfenster des Java-Prozesses
//! (oder eines seiner Kindprozesse, z. B. hinter einem Wrapper-Hook). Nur
//! dieses eine Fenster wird aufgenommen – nie der ganze Bildschirm.

use std::collections::HashSet;

/// Ein gefundenes Fenster.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GameWindow {
    pub hwnd: u64,
    pub pid: u32,
    /// Innenbereich (ohne Rahmen/Titelleiste) in Pixeln.
    pub width: u32,
    pub height: u32,
    /// Fensterklasse von GLFW (LWJGL 3) bzw. LWJGL 2.
    pub minecraft_class: bool,
}

/// Alle Prozesse im Baum unter `root` (einschließlich `root`).
#[cfg(windows)]
pub fn process_tree(root: u32) -> HashSet<u32> {
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, PROCESSENTRY32W, Process32FirstW, Process32NextW, TH32CS_SNAPPROCESS,
    };

    let mut tree = HashSet::from([root]);
    // SAFETY: Schnappschuss der Prozessliste; das Handle wird unten geschlossen.
    let Ok(snapshot) = (unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) }) else { return tree };
    let mut pairs = Vec::new();
    let mut entry = PROCESSENTRY32W { dwSize: std::mem::size_of::<PROCESSENTRY32W>() as u32, ..Default::default() };
    // SAFETY: `entry` ist korrekt initialisiert (dwSize gesetzt) und lebt während der Aufrufe.
    let mut ok = unsafe { Process32FirstW(snapshot, &raw mut entry) }.is_ok();
    while ok {
        pairs.push((entry.th32ProcessID, entry.th32ParentProcessID));
        // SAFETY: wie oben.
        ok = unsafe { Process32NextW(snapshot, &raw mut entry) }.is_ok();
    }
    // SAFETY: Handle aus CreateToolhelp32Snapshot, nur hier geschlossen.
    unsafe {
        let _ = CloseHandle(snapshot);
    }
    // Kinder, Enkel … (Schleife, bis nichts Neues dazukommt; PIDs können wiederverwendet werden,
    // deshalb nur Prozesse, deren Eltern schon im Baum sind).
    loop {
        let before = tree.len();
        for &(pid, parent) in &pairs {
            if pid != 0 && tree.contains(&parent) {
                tree.insert(pid);
            }
        }
        if tree.len() == before || tree.len() > 64 {
            break;
        }
    }
    tree
}

#[cfg(not(windows))]
pub fn process_tree(root: u32) -> HashSet<u32> {
    HashSet::from([root])
}

/// Bestes sichtbares Hauptfenster der Prozesse (Minecraft-Fensterklasse bevorzugt, dann größtes).
#[cfg(windows)]
pub fn find(pids: &HashSet<u32>) -> Option<GameWindow> {
    use windows::Win32::Foundation::{HWND, LPARAM, RECT};
    use windows::Win32::UI::WindowsAndMessaging::{
        EnumWindows, GW_OWNER, GetClassNameW, GetClientRect, GetWindow, GetWindowThreadProcessId, IsWindowVisible,
    };
    use windows::core::BOOL;

    struct Search<'a> {
        pids: &'a HashSet<u32>,
        found: Vec<GameWindow>,
    }

    unsafe extern "system" fn visit(hwnd: HWND, lparam: LPARAM) -> BOOL {
        // SAFETY: `lparam` ist der Zeiger auf `Search` aus `find`, der während EnumWindows lebt.
        let search = unsafe { &mut *(lparam.0 as *mut Search<'_>) };
        let mut pid = 0u32;
        // SAFETY: gültiges Fenster-Handle aus EnumWindows; `pid` ist ein gültiger Zeiger.
        unsafe { GetWindowThreadProcessId(hwnd, Some(&raw mut pid)) };
        if !search.pids.contains(&pid) {
            return true.into();
        }
        // SAFETY: nur lesende Abfragen auf einem gültigen Fenster-Handle.
        let visible = unsafe { IsWindowVisible(hwnd) }.as_bool();
        // Hauptfenster haben keinen Besitzer (Dialoge/Werkzeugfenster schon).
        let owned = unsafe { GetWindow(hwnd, GW_OWNER) }.is_ok_and(|h| !h.is_invalid());
        if !visible || owned {
            return true.into();
        }
        let mut rect = RECT::default();
        // SAFETY: wie oben; `rect` ist ein gültiger Zeiger.
        if unsafe { GetClientRect(hwnd, &raw mut rect) }.is_err() {
            return true.into();
        }
        let (width, height) = ((rect.right - rect.left).max(0) as u32, (rect.bottom - rect.top).max(0) as u32);
        let mut class = [0u16; 64];
        // SAFETY: Puffer mit fester Länge, die Funktion schreibt höchstens so viele Zeichen.
        let len = unsafe { GetClassNameW(hwnd, &mut class) }.max(0) as usize;
        let class = String::from_utf16_lossy(&class[..len.min(class.len())]);
        search.found.push(GameWindow {
            hwnd: hwnd.0 as usize as u64,
            pid,
            width,
            height,
            minecraft_class: class.starts_with("GLFW") || class == "LWJGL",
        });
        true.into()
    }

    let mut search = Search { pids, found: Vec::new() };
    // SAFETY: Rückruf und Zeiger bleiben während des (synchronen) Aufrufs gültig.
    unsafe {
        let _ = EnumWindows(Some(visit), LPARAM(&raw mut search as isize));
    }
    best(search.found)
}

#[cfg(not(windows))]
pub fn find(_pids: &HashSet<u32>) -> Option<GameWindow> {
    None
}

/// Auswahl: Minecraft-Klasse vor anderen, dann die größte Fläche; winzige Fenster (Ladebalken,
/// unsichtbare Hilfsfenster) zählen nicht.
#[cfg_attr(not(windows), allow(dead_code))]
fn best(mut found: Vec<GameWindow>) -> Option<GameWindow> {
    found.retain(|w| w.width >= 64 && w.height >= 64);
    found.sort_by_key(|w| (w.minecraft_class, u64::from(w.width) * u64::from(w.height)));
    found.pop()
}

/// Größe des Innenbereichs eines Fensters (z. B. nach Größenänderung), `None` = Fenster weg.
#[cfg(windows)]
pub fn client_size(hwnd: u64) -> Option<(u32, u32)> {
    use windows::Win32::Foundation::{HWND, RECT};
    use windows::Win32::UI::WindowsAndMessaging::{GetClientRect, IsWindow};

    let hwnd = HWND(hwnd as usize as *mut core::ffi::c_void);
    // SAFETY: IsWindow prüft beliebige Werte gefahrlos; danach nur lesende Abfrage.
    if !unsafe { IsWindow(Some(hwnd)) }.as_bool() {
        return None;
    }
    let mut rect = RECT::default();
    // SAFETY: gültiges Handle (oben geprüft), `rect` ist ein gültiger Zeiger.
    unsafe { GetClientRect(hwnd, &raw mut rect) }.ok()?;
    Some(((rect.right - rect.left).max(0) as u32, (rect.bottom - rect.top).max(0) as u32))
}

#[cfg(not(windows))]
pub fn client_size(_hwnd: u64) -> Option<(u32, u32)> {
    None
}

/// Client-Bereich eines Fensters in Bildschirm-Koordinaten (physische Pixel): Ursprung oben-links.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ScreenRect {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}

/// Lage des Innenbereichs eines Fensters auf dem Bildschirm (für ein darüberliegendes Overlay),
/// `None` = Fenster weg, (fast) nicht sichtbar oder nicht im Vordergrund. Nur das Overlay braucht das;
/// die Aufnahme nicht. Im Hintergrund (andere App vorn) läge das immer-obenauf-Overlay sonst über ihr.
#[cfg(windows)]
pub fn screen_rect(hwnd: u64) -> Option<ScreenRect> {
    use windows::Win32::Foundation::{HWND, POINT, RECT};
    use windows::Win32::Graphics::Gdi::ClientToScreen;
    use windows::Win32::UI::WindowsAndMessaging::{
        GA_ROOTOWNER, GetAncestor, GetClientRect, GetForegroundWindow, IsWindow, IsWindowVisible,
    };

    let hwnd = HWND(hwnd as usize as *mut core::ffi::c_void);
    // SAFETY: IsWindow/IsWindowVisible prüfen beliebige Werte gefahrlos; danach nur lesende Abfragen.
    if !unsafe { IsWindow(Some(hwnd)) }.as_bool() || !unsafe { IsWindowVisible(hwnd) }.as_bool() {
        return None;
    }
    // SAFETY: reine Abfragen; ein Null-Handle (kein Vordergrundfenster) ergibt einfach „nicht vorn“.
    let front = unsafe { GetForegroundWindow() };
    let root = |h: HWND| unsafe { GetAncestor(h, GA_ROOTOWNER) };
    if front.is_invalid() || (front != hwnd && root(front) != root(hwnd)) {
        return None;
    }
    let mut rect = RECT::default();
    // SAFETY: gültiges Handle (oben geprüft), `rect` ist ein gültiger Zeiger.
    unsafe { GetClientRect(hwnd, &raw mut rect) }.ok()?;
    let (width, height) = ((rect.right - rect.left).max(0) as u32, (rect.bottom - rect.top).max(0) as u32);
    if width < 16 || height < 16 {
        return None; // minimiert/zu klein
    }
    let mut origin = POINT { x: rect.left, y: rect.top };
    // SAFETY: gültiges Handle; `origin` ist ein gültiger Zeiger und wird in Bildschirm-Koords umgerechnet.
    if !unsafe { ClientToScreen(hwnd, &raw mut origin) }.as_bool() {
        return None;
    }
    Some(ScreenRect { x: origin.x, y: origin.y, width, height })
}

#[cfg(not(windows))]
pub fn screen_rect(_hwnd: u64) -> Option<ScreenRect> {
    None
}

/// Lage des Clip-Overlays (Größe `size`, physische Pixel) im Client-Bereich `client`: an der Stelle
/// des HUD-Elements „Clips“ – dieselbe Formel wie das HUD des TRS Client (`Anker × (Fläche −
/// Größe) + Versatz × Fläche`), also bei rechtem Anker rechtsbündig, unten verankert unten bündig.
/// Ohne Angabe oben links. Immer vollständig im Client-Bereich (zu große Overlays werden kleiner).
pub fn overlay_rect(client: ScreenRect, size: (u32, u32), place: Option<crate::link::HudPlace>) -> ScreenRect {
    let width = size.0.min(client.width).max(1);
    let height = size.1.min(client.height).max(1);
    let (dx, dy) = place.map_or((0, 0), |p| {
        let free_w = f64::from(client.width.saturating_sub(width));
        let free_h = f64::from(client.height.saturating_sub(height));
        let x = f64::from(p.anchor_x) / 1000.0 * free_w + f64::from(p.offset_x) / 1000.0 * f64::from(client.width);
        let y = f64::from(p.anchor_y) / 1000.0 * free_h + f64::from(p.offset_y) / 1000.0 * f64::from(client.height);
        #[allow(clippy::cast_possible_truncation)]
        let clamp = |v: f64, max: f64| v.round().clamp(0.0, max) as i32;
        (clamp(x, free_w), clamp(y, free_h))
    });
    ScreenRect { x: client.x.saturating_add(dx), y: client.y.saturating_add(dy), width, height }
}

/// Fenster des Spiels (ganzer Prozessbaum) + seinen Client-Bereich in Bildschirm-Koordinaten
/// suchen – für das REC-Overlay, das der Launcher über das Spiel legt.
pub fn find_screen_rect(pid: u32) -> Option<ScreenRect> {
    let tree = process_tree(pid);
    let win = find(&tree)?;
    screen_rect(win.hwnd)
}

/// Dieses (Overlay-)Fenster aus jeder Fenster-/Bildschirm-Aufnahme ausschließen
/// (`SetWindowDisplayAffinity` mit `WDA_EXCLUDEFROMCAPTURE`). So taucht „REC" nie im Clip auf.
/// `true` = gesetzt.
#[cfg(windows)]
pub fn exclude_from_capture(hwnd: u64) -> bool {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{SetWindowDisplayAffinity, WDA_EXCLUDEFROMCAPTURE};

    let hwnd = HWND(hwnd as usize as *mut core::ffi::c_void);
    // SAFETY: gültiges Fenster-Handle; die Funktion ändert nur die Anzeige-Affinität dieses Fensters.
    unsafe { SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE) }.is_ok()
}

#[cfg(not(windows))]
pub fn exclude_from_capture(_hwnd: u64) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    fn w(hwnd: u64, width: u32, height: u32, mc: bool) -> GameWindow {
        GameWindow { hwnd, pid: 1, width, height, minecraft_class: mc }
    }

    #[test]
    fn minecraft_fenster_wird_bevorzugt() {
        let found = vec![w(1, 1920, 1080, false), w(2, 854, 480, true), w(3, 10, 10, true)];
        assert_eq!(best(found).unwrap().hwnd, 2);
        let found = vec![w(1, 200, 100, false), w(4, 800, 600, false)];
        assert_eq!(best(found).unwrap().hwnd, 4);
        assert!(best(vec![w(5, 32, 32, true)]).is_none());
    }

    #[test]
    fn eigener_prozessbaum_enthaelt_mich() {
        let me = std::process::id();
        assert!(process_tree(me).contains(&me));
    }

    #[test]
    fn overlay_liegt_an_der_stelle_des_clips_elements() {
        use crate::link::HudPlace;
        let client = ScreenRect { x: -1600, y: 167, width: 1280, height: 720 };
        let place = |ax, ay, ox, oy| Some(HudPlace { anchor_x: ax, anchor_y: ay, offset_x: ox, offset_y: oy });
        // Ohne Angabe: oben links.
        assert_eq!(overlay_rect(client, (440, 100), None), ScreenRect { x: -1600, y: 167, width: 440, height: 100 });
        // Standard des TRS Client: rechts, Mitte, 20 % nach oben → rechtsbündig, (720−100)/2 − 144 = 166.
        let r = overlay_rect(client, (440, 100), place(1000, 500, 0, -200));
        assert_eq!((r.x - client.x, r.y - client.y, r.width), (840, 166, 440));
        // Unten links mit Versatz nach innen.
        let r = overlay_rect(client, (440, 100), place(0, 1000, 10, -10));
        assert_eq!((r.x - client.x, r.y - client.y), (13, 613));
        // Versatz über den Rand hinaus → geklemmt, nie außerhalb des Client-Bereichs.
        let r = overlay_rect(client, (440, 100), place(1000, 0, 500, -500));
        assert_eq!((r.x - client.x, r.y - client.y), (840, 0));
        let r = overlay_rect(client, (440, 100), place(0, 1000, -1000, 1000));
        assert_eq!((r.x - client.x, r.y - client.y), (0, 620));
        // Kleines Spielfenster: Overlay schrumpft auf den Client-Bereich.
        let tiny = ScreenRect { x: 0, y: 0, width: 300, height: 80 };
        assert_eq!(overlay_rect(tiny, (440, 100), place(1000, 1000, 0, 0)), ScreenRect { x: 0, y: 0, width: 300, height: 80 });
    }

    #[test]
    fn kein_spielfenster_ohne_passenden_prozess() {
        // Eine PID, die (praktisch) kein sichtbares Hauptfenster hat → kein Overlay-Rechteck.
        assert!(find_screen_rect(0xFFFF_FFF0).is_none());
    }
}
