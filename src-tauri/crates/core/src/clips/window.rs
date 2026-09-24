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
}
