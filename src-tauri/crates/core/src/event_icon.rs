//! Event-Symbole: Läuft für den Spieler ein Event mit eigenem Symbol (z. B. Halloween), zeigt die App
//! dieses Symbol – im Fenster und in der Taskleiste (App-Crate), auf den Verknüpfungen des Installers
//! (Windows: Desktop, Startmenü, angeheftete Taskleiste), in einer eigenen `.desktop`-Datei des Nutzers
//! (Linux) und als Startsymbol (Android/iOS, App-Crate). Endet das Event oder ist der Schalter
//! „Event-Symbole“ aus, kommt das normale Symbol zurück.
//!
//! Hier: Auswahl, gemerkter Zustand und die Verknüpfungen am PC. Alles idempotent – schon richtige
//! Verknüpfungen werden nicht neu geschrieben, neue werden nie angelegt.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::{Error, Result};

/// Events mit eigenem App-Symbol (Reihenfolge = Vorrang).
pub const EVENT_ICONS: [&str; 1] = ["halloween"];

/// Name des normalen Symbols für die nativen Teile (Android-Alias, iOS).
pub const DEFAULT_ICON: &str = "default";

/// Eigener Eintrag in der `.desktop`-Datei: das Symbol von vorher (Linux).
const ORIGINAL_KEY: &str = "X-TRS-Launcher-Original-Icon";

/// Welches Event-Symbol gelten soll: das erste laufende Event mit eigenem Symbol – außer der Schalter ist aus.
pub fn pick(events: &[String], enabled: bool) -> Option<&'static str> {
    if !enabled {
        return None;
    }
    EVENT_ICONS.into_iter().find(|id| events.iter().any(|e| e == id))
}

/// Name für die nativen Teile: Event-ID oder [`DEFAULT_ICON`].
pub fn native_name(icon: Option<&str>) -> &str {
    icon.unwrap_or(DEFAULT_ICON)
}

struct Images {
    ico: &'static [u8],
    png: &'static [u8],
}

fn images(id: &str) -> Option<Images> {
    match id {
        "halloween" => Some(Images {
            ico: include_bytes!("../../../icons/events/halloween/icon.ico"),
            png: include_bytes!("../../../icons/events/halloween/256x256.png"),
        }),
        _ => None,
    }
}

/// Fenster-/Taskleisten-Symbol eines Events als RGBA (`daten, breite, höhe`).
pub fn window_rgba(id: &str) -> Option<(Vec<u8>, u32, u32)> {
    let img = image::load_from_memory_with_format(images(id)?.png, image::ImageFormat::Png).ok()?.into_rgba8();
    let (w, h) = img.dimensions();
    Some((img.into_raw(), w, h))
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct State {
    /// Zuletzt angewandtes Event-Symbol (`None` = normal).
    applied: Option<String>,
    /// Windows: Symbol der Verknüpfungen vor dem Event (`"pfad,index"`, leer = Symbol des Programms).
    originals: BTreeMap<String, String>,
}

/// Zustand der Event-Symbole im Datenordner (`event-icon.json`, Bilder in `event-icons/`).
pub struct EventIcons {
    root: PathBuf,
    lock: std::sync::Mutex<()>,
}

impl EventIcons {
    pub fn new(root: &Path) -> Self {
        Self { root: root.to_path_buf(), lock: std::sync::Mutex::new(()) }
    }

    fn state_file(&self) -> PathBuf {
        self.root.join("event-icon.json")
    }

    fn icon_dir(&self) -> PathBuf {
        self.root.join("event-icons")
    }

    fn load(&self) -> State {
        let mut state = std::fs::read(self.state_file())
            .ok()
            .and_then(|b| serde_json::from_slice::<State>(&b).ok())
            .unwrap_or_default();
        state.applied = state.applied.filter(|a| EVENT_ICONS.contains(&a.as_str()));
        state.originals.retain(|k, v| k.len() <= 1024 && v.len() <= 1024);
        state
    }

    fn save(&self, state: &State) -> Result<()> {
        let json = serde_json::to_vec_pretty(state).map_err(|e| Error::json("event-icon.json", e))?;
        crate::fsutil::write_atomic_sync(&self.state_file(), &json)
    }

    /// Zuletzt angewandtes Event-Symbol (für das Fenster gleich beim Start).
    pub fn applied(&self) -> Option<&'static str> {
        let applied = self.load().applied?;
        EVENT_ICONS.into_iter().find(|id| *id == applied)
    }

    /// Merkt das angewandte Symbol.
    pub fn set_applied(&self, icon: Option<&str>) -> Result<()> {
        let _guard = self.lock.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut state = self.load();
        let icon = icon.filter(|i| EVENT_ICONS.contains(i)).map(str::to_owned);
        if state.applied == icon {
            return Ok(());
        }
        state.applied = icon;
        self.save(&state)
    }

    /// Bilddatei des Event-Symbols im Datenordner – nur geschrieben, wenn sie fehlt oder anders ist.
    #[cfg_attr(any(target_os = "android", target_os = "ios", target_os = "macos"), allow(dead_code))]
    fn icon_file(&self, id: &str, ext: &str) -> Result<PathBuf> {
        let img = images(id).ok_or_else(|| Error::Internal(format!("Kein Event-Symbol für {id}")))?;
        let bytes = if ext == "ico" { img.ico } else { img.png };
        let path = self.icon_dir().join(format!("{id}.{ext}"));
        if std::fs::read(&path).ok().as_deref() != Some(bytes) {
            std::fs::create_dir_all(self.icon_dir()).map_err(|e| Error::io(self.icon_dir(), e))?;
            crate::fsutil::write_atomic_sync(&path, bytes)?;
        }
        Ok(path)
    }

    /// Verknüpfungen (Windows) bzw. eigene `.desktop`-Dateien (Linux) aufs Event-Symbol stellen
    /// oder zurück. `product` ist der Name der Verknüpfungen („TRS Launcher“). Blockiert (Shell/Dateien);
    /// liefert die Zahl der geänderten Verknüpfungen.
    pub fn apply_shortcuts(&self, icon: Option<&str>, product: &str) -> Result<usize> {
        let _guard = self.lock.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let icon = icon.filter(|i| EVENT_ICONS.contains(i));
        if product.is_empty() || product.len() > 64 || product.contains(['/', '\\', ':', '.']) {
            return Err(Error::Internal("Ungültiger Produktname".into()));
        }
        let exe = std::env::current_exe().map_err(|e| Error::Internal(e.to_string()))?;
        #[cfg(windows)]
        {
            let file = icon.map(|id| self.icon_file(id, "ico")).transpose()?;
            let mut state = self.load();
            let changed = windows_links::apply(&windows_links::candidates(product), &exe, file.as_deref(), &self.icon_dir(), &mut state.originals)?;
            self.save(&state)?;
            Ok(changed)
        }
        #[cfg(all(unix, not(any(target_os = "android", target_os = "ios", target_os = "macos"))))]
        {
            let _ = product;
            let file = icon.map(|id| self.icon_file(id, "png")).transpose()?;
            let mut exes = vec![exe];
            if let Some(appimage) = std::env::var_os("APPIMAGE") {
                exes.push(PathBuf::from(appimage));
            }
            Ok(linux_desktop::apply(&linux_desktop::applications_dir(), &exes, file.as_deref()))
        }
        #[cfg(not(any(windows, all(unix, not(any(target_os = "android", target_os = "ios", target_os = "macos"))))))]
        {
            let _ = (icon, product, exe);
            Ok(0)
        }
    }
}

/// Zeigt die Verknüpfung auf unser Programm? Debug-Builds erkennen auch das installierte
/// Programm am Dateinamen (zum Ausprobieren mit `tauri dev`).
#[cfg_attr(not(windows), allow(dead_code))]
fn same_exe(target: &Path, exe: &Path, loose: bool) -> bool {
    let norm = |p: &Path| {
        let p = std::fs::canonicalize(p).unwrap_or_else(|_| p.to_path_buf());
        p.to_string_lossy().replace('/', "\\").to_lowercase()
    };
    if norm(target) == norm(exe) {
        return true;
    }
    // Dateiname selbst abtrennen: `Path::file_name` kennt unter Linux keinen `\`-Trenner (Windows-Pfade aus .lnk).
    let name = |p: &Path| p.to_string_lossy().replace('/', "\\").rsplit('\\').next().unwrap_or("").to_lowercase();
    loose && !name(target).is_empty() && name(target) == name(exe)
}

/// Liegt die Symbol-Angabe (`"pfad,index"` oder Pfad) in unserem Ordner der Event-Symbole?
#[cfg_attr(not(windows), allow(dead_code))]
fn is_ours(icon: &str, icon_dir: &Path) -> bool {
    let path = icon.rsplit_once(',').map_or(icon, |(p, _)| p).trim().trim_matches('"');
    let dir = icon_dir.to_string_lossy().replace('/', "\\").to_lowercase();
    let path = path.replace('/', "\\").to_lowercase();
    !path.is_empty() && path.starts_with(&format!("{}\\", dir.trim_end_matches('\\')))
}

/// Symbol einer Verknüpfung lesen/schreiben (Windows: Shell; Tests: Attrappe).
#[cfg_attr(not(windows), allow(dead_code))]
trait LinkIcons {
    /// Aktuelles Symbol (`"pfad,index"`, leer = Symbol des Programms) – nur, wenn es die Verknüpfung
    /// gibt und sie auf unser Programm zeigt.
    fn read(&self, link: &Path) -> Option<String>;
    fn write(&self, link: &Path, icon: &str) -> std::io::Result<()>;
}

/// Verknüpfungen aufs Event-Symbol (`file`) stellen oder zurück (`None`). Merkt sich das alte Symbol.
#[cfg_attr(not(windows), allow(dead_code))]
fn sync_links(
    links: &dyn LinkIcons,
    paths: &[PathBuf],
    file: Option<&Path>,
    icon_dir: &Path,
    originals: &mut BTreeMap<String, String>,
) -> usize {
    let mut changed = 0;
    for link in paths {
        let Some(current) = links.read(link) else { continue };
        let key = link.to_string_lossy().into_owned();
        let target = match file {
            Some(file) => {
                let want = format!("{},0", file.display());
                if current.eq_ignore_ascii_case(&want) {
                    continue;
                }
                if !is_ours(&current, icon_dir) {
                    originals.insert(key.clone(), current);
                }
                want
            }
            None => {
                if !is_ours(&current, icon_dir) {
                    // Nie von uns geändert (oder vom Nutzer neu gesetzt): so lassen.
                    originals.remove(&key);
                    continue;
                }
                originals.get(&key).filter(|o| !is_ours(o, icon_dir)).cloned().unwrap_or_default()
            }
        };
        match links.write(link, &target) {
            Ok(()) => {
                changed += 1;
                if file.is_none() {
                    originals.remove(&key);
                }
            }
            Err(e) => tracing::warn!("Verknüpfung {} nicht geändert: {e}", link.display()),
        }
    }
    changed
}

#[cfg(windows)]
mod windows_links {
    use std::collections::BTreeMap;
    use std::path::{Path, PathBuf};

    use windows::Win32::Storage::FileSystem::WIN32_FIND_DATAW;
    use windows::Win32::System::Com::{
        CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize, IPersistFile,
        STGM_READ, STGM_READWRITE,
    };
    use windows::Win32::UI::Shell::{
        FOLDERID_Desktop, FOLDERID_Programs, FOLDERID_QuickLaunch, IShellLinkW, KF_FLAG_DEFAULT, SHCNE_ASSOCCHANGED, SHCNE_UPDATEITEM,
        SHCNF_IDLIST, SHCNF_PATHW, SHChangeNotify, SHGetKnownFolderPath, ShellLink,
    };
    use windows::core::{GUID, HSTRING, Interface, PCWSTR};

    use super::LinkIcons;
    use crate::{Error, Result};

    fn known_folder(id: &GUID) -> Option<PathBuf> {
        // SAFETY: Der zurückgegebene Puffer gehört uns und wird genau einmal freigegeben.
        unsafe {
            let raw = SHGetKnownFolderPath(id, KF_FLAG_DEFAULT, None).ok()?;
            let path = raw.to_string().ok();
            CoTaskMemFree(Some(raw.0 as *const _));
            path.map(PathBuf::from)
        }
    }

    /// Verknüpfungen, die der NSIS-Installer (pro Nutzer) anlegt – plus die angeheftete Taskleiste.
    pub(super) fn candidates(product: &str) -> Vec<PathBuf> {
        let name = format!("{product}.lnk");
        let mut out = Vec::new();
        if let Some(desktop) = known_folder(&FOLDERID_Desktop) {
            out.push(desktop.join(&name));
        }
        if let Some(programs) = known_folder(&FOLDERID_Programs) {
            out.push(programs.join(&name));
            out.push(programs.join(product).join(&name));
        }
        if let Some(quick) = known_folder(&FOLDERID_QuickLaunch) {
            out.push(quick.join("User Pinned").join("TaskBar").join(&name));
        }
        out
    }

    pub(super) struct ShellLinks {
        pub(super) exe: PathBuf,
        pub(super) loose: bool,
    }

    fn wide_to_string(buf: &[u16]) -> String {
        let len = buf.iter().position(|c| *c == 0).unwrap_or(buf.len());
        String::from_utf16_lossy(&buf[..len])
    }

    impl ShellLinks {
        fn open(link: &Path, write: bool) -> windows::core::Result<(IShellLinkW, IPersistFile)> {
            // SAFETY: COM ist im aufrufenden Thread initialisiert (siehe `apply`); Objekte zählen selbst.
            unsafe {
                let shell: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
                let file: IPersistFile = shell.cast()?;
                file.Load(&HSTRING::from(link.as_os_str()), if write { STGM_READWRITE } else { STGM_READ })?;
                Ok((shell, file))
            }
        }
    }

    impl LinkIcons for ShellLinks {
        fn read(&self, link: &Path) -> Option<String> {
            if !link.is_file() {
                return None;
            }
            let (shell, _file) = Self::open(link, false).ok()?;
            let mut target = vec![0u16; 1024];
            let mut icon = vec![0u16; 1024];
            let mut index = 0i32;
            // SAFETY: Puffer gehören uns, die Länge gibt die windows-Crate mit.
            unsafe {
                let mut find = WIN32_FIND_DATAW::default();
                shell.GetPath(&mut target, &mut find, 0).ok()?;
                shell.GetIconLocation(&mut icon, &mut index).ok()?;
            }
            let target = wide_to_string(&target);
            if target.is_empty() || !super::same_exe(Path::new(&target), &self.exe, self.loose) {
                return None;
            }
            let icon = wide_to_string(&icon);
            Some(if icon.is_empty() { String::new() } else { format!("{icon},{index}") })
        }

        fn write(&self, link: &Path, icon: &str) -> std::io::Result<()> {
            let (path, index) = match icon.rsplit_once(',') {
                Some((p, i)) => (p, i.trim().parse::<i32>().unwrap_or(0)),
                None => (icon, 0),
            };
            let (shell, file) = Self::open(link, true).map_err(std::io::Error::other)?;
            let wide = HSTRING::from(link.as_os_str());
            // SAFETY: wie oben; Speichern in die geladene Datei (Ziel und Argumente bleiben).
            unsafe {
                shell.SetIconLocation(&HSTRING::from(path), index).map_err(std::io::Error::other)?;
                file.Save(PCWSTR::null(), true).map_err(std::io::Error::other)?;
                SHChangeNotify(SHCNE_UPDATEITEM, SHCNF_PATHW, Some(wide.as_ptr().cast()), None);
            }
            Ok(())
        }
    }

    /// In einem eigenen Thread mit COM (STA) – wie es die Shell erwartet.
    pub(super) fn apply(
        paths: &[PathBuf],
        exe: &Path,
        file: Option<&Path>,
        icon_dir: &Path,
        originals: &mut BTreeMap<String, String>,
    ) -> Result<usize> {
        let links = ShellLinks { exe: exe.to_path_buf(), loose: cfg!(debug_assertions) };
        std::thread::scope(|s| {
            s.spawn(|| {
                // SAFETY: Initialisierung und Freigabe im selben, eigenen Thread.
                let init = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };
                let changed = super::sync_links(&links, paths, file, icon_dir, originals);
                if changed > 0 {
                    // Symbol-Zwischenspeicher von Explorer/Taskleiste auffrischen.
                    // SAFETY: ohne Zeiger.
                    unsafe { SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, None, None) };
                }
                if init.is_ok() {
                    // SAFETY: passend zum erfolgreichen CoInitializeEx oben.
                    unsafe { CoUninitialize() };
                }
                changed
            })
            .join()
            .map_err(|_| Error::Internal("Verknüpfungen: Thread abgebrochen".into()))
        })
    }

    #[cfg(test)]
    pub(super) fn create_link(link: &Path, target: &Path, args: &str) -> windows::core::Result<()> {
        // SAFETY: Test – COM im Test-Thread, Objekte zählen selbst.
        unsafe {
            let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
            let shell: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
            shell.SetPath(&HSTRING::from(target.as_os_str()))?;
            shell.SetArguments(&HSTRING::from(args))?;
            let file: IPersistFile = shell.cast()?;
            file.Save(&HSTRING::from(link.as_os_str()), true)
        }
    }

    #[cfg(test)]
    pub(super) fn link_args(link: &Path) -> String {
        let (shell, _file) = ShellLinks::open(link, false).unwrap();
        let mut buf = vec![0u16; 1024];
        // SAFETY: Test, eigener Puffer.
        unsafe { shell.GetArguments(&mut buf).unwrap() };
        wide_to_string(&buf)
    }
}

#[cfg_attr(not(all(unix, not(any(target_os = "android", target_os = "ios", target_os = "macos")))), allow(dead_code))]
mod linux_desktop {
    use std::path::{Path, PathBuf};

    use super::ORIGINAL_KEY;

    /// `$XDG_DATA_HOME/applications`, sonst `~/.local/share/applications` – nie die systemweiten Ordner.
    pub(super) fn applications_dir() -> PathBuf {
        std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local").join("share")))
            .unwrap_or_default()
            .join("applications")
    }

    /// Erstes Wort von `Exec=` (ohne `env VAR=…`, Anführungszeichen entfernt).
    fn exec_program(content: &str) -> Option<String> {
        let exec = entry_lines(content).find_map(|l| key_value(l).filter(|(k, _)| *k == "Exec").map(|(_, v)| v.to_owned()))?;
        let mut tokens = Vec::new();
        let mut cur = String::new();
        let mut quoted = false;
        let mut chars = exec.chars();
        while let Some(c) = chars.next() {
            match c {
                '"' => quoted = !quoted,
                '\\' if quoted => {
                    if let Some(n) = chars.next() {
                        cur.push(n);
                    }
                }
                ' ' | '\t' if !quoted => {
                    if !cur.is_empty() {
                        tokens.push(std::mem::take(&mut cur));
                    }
                }
                c => cur.push(c),
            }
        }
        if !cur.is_empty() {
            tokens.push(cur);
        }
        let mut it = tokens.into_iter().peekable();
        if it.peek().map(String::as_str) == Some("env") {
            it.next();
            while it.peek().is_some_and(|t| t.contains('=') && !t.starts_with('/')) {
                it.next();
            }
        }
        it.next()
    }

    /// Startet diese `.desktop`-Datei unser Programm?
    pub(super) fn launches(content: &str, exes: &[PathBuf]) -> bool {
        let Some(program) = exec_program(content) else { return false };
        let program = Path::new(&program);
        exes.iter().any(|exe| {
            program == exe || std::fs::canonicalize(program).ok().zip(std::fs::canonicalize(exe).ok()).is_some_and(|(a, b)| a == b)
        })
    }

    fn key_value(line: &str) -> Option<(&str, &str)> {
        let (k, v) = line.split_once('=')?;
        Some((k.trim(), v.trim()))
    }

    /// Zeilen der Gruppe `[Desktop Entry]`.
    fn entry_lines(content: &str) -> impl Iterator<Item = &str> {
        let mut inside = false;
        content.lines().filter(move |l| {
            let t = l.trim();
            if t.starts_with('[') {
                inside = t == "[Desktop Entry]";
                return false;
            }
            inside
        })
    }

    /// Neuer Inhalt mit Event-Symbol (`Some(pfad)`) bzw. dem alten Symbol (`None`); `None`, wenn nichts zu tun ist.
    /// Das alte Symbol steht dabei in der Datei selbst ([`ORIGINAL_KEY`]).
    pub(super) fn with_icon(content: &str, icon: Option<&str>) -> Option<String> {
        let lines: Vec<&str> = content.lines().collect();
        let start = lines.iter().position(|l| l.trim() == "[Desktop Entry]")?;
        let end = lines[start + 1..].iter().position(|l| l.trim().starts_with('[')).map_or(lines.len(), |i| start + 1 + i);
        let find = |key: &str| (start + 1..end).find(|&i| key_value(lines[i]).is_some_and(|(k, _)| k == key));
        let icon_line = find("Icon");
        let orig_line = find(ORIGINAL_KEY);
        let current = icon_line.and_then(|i| key_value(lines[i])).map_or("", |(_, v)| v);
        let mut out: Vec<String> = lines.iter().map(|l| (*l).to_owned()).collect();
        match icon {
            Some(path) => {
                if current == path {
                    return None;
                }
                let mut insert_at = end;
                if let Some(i) = icon_line {
                    out[i] = format!("Icon={path}");
                } else {
                    out.insert(insert_at, format!("Icon={path}"));
                    insert_at += 1;
                }
                if orig_line.is_none() {
                    out.insert(insert_at, format!("{ORIGINAL_KEY}={current}"));
                }
            }
            None => {
                let orig = key_value(lines[orig_line?]).map_or("", |(_, v)| v).to_owned();
                // Erst die hintere Zeile entfernen, damit die Indizes stimmen.
                let mut remove = vec![orig_line?];
                match icon_line {
                    Some(i) if orig.is_empty() => remove.push(i),
                    Some(i) => out[i] = format!("Icon={orig}"),
                    None if !orig.is_empty() => out.insert(end, format!("Icon={orig}")),
                    None => {}
                }
                remove.sort_unstable_by(|a, b| b.cmp(a));
                for i in remove {
                    if i < out.len() {
                        out.remove(i);
                    }
                }
            }
        }
        let mut text = out.join("\n");
        if content.ends_with('\n') {
            text.push('\n');
        }
        Some(text)
    }

    /// Eigene `.desktop`-Dateien des Nutzers, die unser Programm starten, umstellen.
    pub(super) fn apply(dir: &Path, exes: &[PathBuf], icon: Option<&Path>) -> usize {
        let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
        let mut changed = 0;
        for entry in entries.flatten().take(2000) {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("desktop") {
                continue;
            }
            // Nur echte, kleine Dateien (keine Links ins System).
            match std::fs::symlink_metadata(&path) {
                Ok(m) if m.is_file() && m.len() <= 64 * 1024 => {}
                _ => continue,
            }
            let Ok(content) = std::fs::read_to_string(&path) else { continue };
            if !launches(&content, exes) {
                continue;
            }
            let icon = icon.map(|p| p.to_string_lossy().into_owned());
            let Some(next) = with_icon(&content, icon.as_deref()) else { continue };
            match crate::fsutil::write_atomic_sync(&path, next.as_bytes()) {
                Ok(()) => changed += 1,
                Err(e) => tracing::warn!("{} nicht geändert: {e}", path.display()),
            }
        }
        changed
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::collections::HashMap;

    #[test]
    fn pick_respects_switch_and_known_events() {
        let ev = |l: &[&str]| l.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>();
        assert_eq!(pick(&ev(&["halloween"]), true), Some("halloween"));
        assert_eq!(pick(&ev(&["xmas", "halloween"]), true), Some("halloween"));
        assert_eq!(pick(&ev(&["halloween"]), false), None, "Schalter aus");
        assert_eq!(pick(&ev(&["xmas"]), true), None, "Event ohne eigenes Symbol");
        assert_eq!(pick(&[], true), None);
        assert_eq!(native_name(pick(&ev(&["halloween"]), true)), "halloween");
        assert_eq!(native_name(None), DEFAULT_ICON);
    }

    #[test]
    fn embedded_images_decode() {
        let (rgba, w, h) = window_rgba("halloween").unwrap();
        assert_eq!((w, h), (256, 256));
        assert_eq!(rgba.len(), 256 * 256 * 4);
        assert!(window_rgba("xmas").is_none());
        let img = images("halloween").unwrap();
        assert_eq!(&img.ico[..4], &[0, 0, 1, 0], "ICO-Kopf");
    }

    #[test]
    fn state_is_remembered_and_cleaned() {
        let dir = tempfile::tempdir().unwrap();
        let icons = EventIcons::new(dir.path());
        assert_eq!(icons.applied(), None);
        icons.set_applied(Some("halloween")).unwrap();
        assert_eq!(icons.applied(), Some("halloween"));
        assert_eq!(EventIcons::new(dir.path()).applied(), Some("halloween"), "überlebt den Neustart");
        icons.set_applied(Some("nope")).unwrap();
        assert_eq!(icons.applied(), None, "unbekanntes Event = normal");
        std::fs::write(dir.path().join("event-icon.json"), r#"{"applied":"../evil"}"#).unwrap();
        assert_eq!(icons.applied(), None);
        std::fs::write(dir.path().join("event-icon.json"), "kaputt").unwrap();
        assert_eq!(icons.applied(), None);
    }

    #[test]
    fn icon_file_is_written_once() {
        let dir = tempfile::tempdir().unwrap();
        let icons = EventIcons::new(dir.path());
        let ico = icons.icon_file("halloween", "ico").unwrap();
        assert!(ico.starts_with(dir.path().join("event-icons")));
        let before = std::fs::metadata(&ico).unwrap().modified().unwrap();
        std::thread::sleep(std::time::Duration::from_millis(20));
        icons.icon_file("halloween", "ico").unwrap();
        assert_eq!(std::fs::metadata(&ico).unwrap().modified().unwrap(), before, "nicht neu geschrieben");
        assert!(icons.icon_file("xmas", "ico").is_err());
    }

    /// Attrappe: Verknüpfungen als Tabelle, nur „unsere“ sind lesbar; zählt Schreibvorgänge.
    struct FakeLinks {
        icons: RefCell<HashMap<PathBuf, String>>,
        writes: RefCell<usize>,
    }

    impl LinkIcons for FakeLinks {
        fn read(&self, link: &Path) -> Option<String> {
            self.icons.borrow().get(link).cloned()
        }
        fn write(&self, link: &Path, icon: &str) -> std::io::Result<()> {
            *self.writes.borrow_mut() += 1;
            self.icons.borrow_mut().insert(link.to_path_buf(), icon.to_owned());
            Ok(())
        }
    }

    #[test]
    fn links_switch_once_and_restore_the_original() {
        let dir = Path::new("C:\\Users\\x\\AppData\\Roaming\\TRS\\event-icons");
        let file = dir.join("halloween.ico");
        let desktop = PathBuf::from("C:\\Users\\x\\Desktop\\TRS Launcher.lnk");
        let start = PathBuf::from("C:\\Start\\TRS Launcher.lnk");
        let missing = PathBuf::from("C:\\Nirgends\\TRS Launcher.lnk");
        let links = FakeLinks {
            icons: RefCell::new(HashMap::from([(desktop.clone(), String::new()), (start.clone(), "D:\\eigen.ico,3".into())])),
            writes: RefCell::new(0),
        };
        let paths = [desktop.clone(), start.clone(), missing.clone()];
        let mut originals = BTreeMap::new();

        assert_eq!(sync_links(&links, &paths, Some(&file), dir, &mut originals), 2);
        let want = format!("{},0", file.display());
        assert_eq!(links.read(&desktop).as_deref(), Some(want.as_str()));
        assert!(links.read(&missing).is_none(), "keine neuen Verknüpfungen");
        assert_eq!(originals.get(start.to_str().unwrap()).map(String::as_str), Some("D:\\eigen.ico,3"));

        // Idempotent: zweiter Lauf schreibt nichts.
        assert_eq!(sync_links(&links, &paths, Some(&file), dir, &mut originals), 0);
        assert_eq!(*links.writes.borrow(), 2);

        // Event vorbei: altes Symbol zurück (leer = Symbol des Programms), Merker weg.
        assert_eq!(sync_links(&links, &paths, None, dir, &mut originals), 2);
        assert_eq!(links.read(&desktop).as_deref(), Some(""));
        assert_eq!(links.read(&start).as_deref(), Some("D:\\eigen.ico,3"));
        assert!(originals.is_empty());
        assert_eq!(sync_links(&links, &paths, None, dir, &mut originals), 0, "nichts mehr zu tun");
    }

    #[test]
    fn restore_leaves_icons_the_user_changed() {
        let dir = Path::new("C:\\data\\event-icons");
        let link = PathBuf::from("C:\\Desktop\\TRS Launcher.lnk");
        let links = FakeLinks { icons: RefCell::new(HashMap::from([(link.clone(), "C:\\mine.ico,0".into())])), writes: RefCell::new(0) };
        let mut originals = BTreeMap::from([(link.to_string_lossy().into_owned(), String::new())]);
        assert_eq!(sync_links(&links, std::slice::from_ref(&link), None, dir, &mut originals), 0);
        assert_eq!(links.read(&link).as_deref(), Some("C:\\mine.ico,0"));
        assert!(originals.is_empty());
        // Merker verloren, Symbol noch von uns: zurück aufs Programm-Symbol.
        links.icons.borrow_mut().insert(link.clone(), "c:/data/event-icons/halloween.ico,0".into());
        assert_eq!(sync_links(&links, std::slice::from_ref(&link), None, dir, &mut originals), 1);
        assert_eq!(links.read(&link).as_deref(), Some(""));
    }

    #[test]
    fn ours_and_same_exe() {
        let dir = Path::new("C:\\Data\\event-icons");
        assert!(is_ours("c:\\data\\EVENT-ICONS\\halloween.ico,0", dir));
        assert!(!is_ours("C:\\Data\\event-icons-x\\a.ico,0", dir));
        assert!(!is_ours("", dir));
        assert!(same_exe(Path::new("C:\\A\\trs-launcher.exe"), Path::new("c:/a/TRS-launcher.exe"), false));
        assert!(!same_exe(Path::new("C:\\B\\trs-launcher.exe"), Path::new("C:\\A\\trs-launcher.exe"), false));
        assert!(same_exe(Path::new("C:\\B\\trs-launcher.exe"), Path::new("C:\\A\\trs-launcher.exe"), true), "Debug: Dateiname");
        assert!(!same_exe(Path::new("C:\\B\\other.exe"), Path::new("C:\\A\\trs-launcher.exe"), true));
    }

    #[test]
    fn desktop_file_switches_and_restores() {
        use linux_desktop::with_icon;
        let orig = "[Desktop Entry]\nName=TRS Launcher\nExec=\"/home/u/Apps/TRS Launcher.AppImage\" %U\nIcon=appimagekit_abc_trs\nType=Application\n\n[Desktop Action New]\nIcon=other\n";
        let on = with_icon(orig, Some("/home/u/.local/share/trs/event-icons/halloween.png")).unwrap();
        assert!(on.contains("\nIcon=/home/u/.local/share/trs/event-icons/halloween.png\n"));
        assert!(on.contains(&format!("\n{ORIGINAL_KEY}=appimagekit_abc_trs\n")));
        assert!(on.contains("[Desktop Action New]\nIcon=other\n"), "andere Gruppen bleiben");
        assert!(with_icon(&on, Some("/home/u/.local/share/trs/event-icons/halloween.png")).is_none(), "idempotent");
        let off = with_icon(&on, None).unwrap();
        assert_eq!(off, orig);
        assert!(with_icon(orig, None).is_none(), "nie von uns geändert");
        // Ohne Icon-Zeile: hinzufügen und beim Zurückstellen wieder entfernen.
        let bare = "[Desktop Entry]\nExec=/opt/trs\n";
        let on = with_icon(bare, Some("/x.png")).unwrap();
        assert_eq!(with_icon(&on, None).unwrap(), bare);
        assert!(with_icon("Name=kein Eintrag\n", Some("/x.png")).is_none());
    }

    #[test]
    fn desktop_file_must_launch_us() {
        use linux_desktop::launches;
        let exes = [PathBuf::from("/home/u/Apps/TRS Launcher.AppImage")];
        assert!(launches("[Desktop Entry]\nExec=\"/home/u/Apps/TRS Launcher.AppImage\" %U\n", &exes));
        assert!(launches("[Desktop Entry]\nExec=env APPIMAGELAUNCHER_DISABLE=1 \"/home/u/Apps/TRS Launcher.AppImage\"\n", &exes));
        assert!(!launches("[Desktop Entry]\nExec=/usr/bin/firefox %u\n", &exes));
        assert!(!launches("[Desktop Action x]\nExec=\"/home/u/Apps/TRS Launcher.AppImage\"\n", &exes));
    }

    #[test]
    fn desktop_dir_apply_only_touches_matching_files() {
        let dir = tempfile::tempdir().unwrap();
        let exe = dir.path().join("trs-launcher");
        std::fs::write(&exe, b"x").unwrap();
        let ours = dir.path().join("trs.desktop");
        let other = dir.path().join("other.desktop");
        std::fs::write(&ours, format!("[Desktop Entry]\nExec={} %U\nIcon=trs\n", exe.display())).unwrap();
        std::fs::write(&other, "[Desktop Entry]\nExec=/usr/bin/other\nIcon=other\n").unwrap();
        let png = dir.path().join("event-icons").join("halloween.png");
        let exes = [exe.clone()];
        assert_eq!(linux_desktop::apply(dir.path(), &exes, Some(&png)), 1);
        assert_eq!(linux_desktop::apply(dir.path(), &exes, Some(&png)), 0, "schon richtig");
        assert!(std::fs::read_to_string(&ours).unwrap().contains(&format!("Icon={}", png.display())));
        assert_eq!(std::fs::read_to_string(&other).unwrap(), "[Desktop Entry]\nExec=/usr/bin/other\nIcon=other\n");
        assert_eq!(linux_desktop::apply(dir.path(), &exes, None), 1);
        assert_eq!(std::fs::read_to_string(&ours).unwrap(), format!("[Desktop Entry]\nExec={} %U\nIcon=trs\n", exe.display()));
        assert_eq!(linux_desktop::apply(&dir.path().join("fehlt"), &exes, None), 0);
    }

    #[cfg(windows)]
    #[test]
    fn real_shortcut_keeps_target_and_arguments() {
        let dir = tempfile::tempdir().unwrap();
        let exe = dir.path().join("trs-launcher.exe");
        std::fs::write(&exe, b"MZ").unwrap();
        let link = dir.path().join("TRS Launcher.lnk");
        let stranger = dir.path().join("Fremd.lnk");
        windows_links::create_link(&link, &exe, "--flag \"a b\"").unwrap();
        windows_links::create_link(&stranger, &dir.path().join("other.exe"), "").unwrap();
        let icons = EventIcons::new(&dir.path().join("data"));
        let ico = icons.icon_file("halloween", "ico").unwrap();
        let mut originals = BTreeMap::new();
        let paths = [link.clone(), stranger.clone(), dir.path().join("fehlt.lnk")];
        let shell = windows_links::ShellLinks { exe: exe.clone(), loose: false };

        let changed = windows_links::apply(&paths, &exe, Some(&ico), &icons.icon_dir(), &mut originals).unwrap();
        assert_eq!(changed, 1, "nur unsere Verknüpfung");
        let read = || {
            std::thread::scope(|s| {
                s.spawn(|| {
                    // SAFETY: Test – COM im eigenen Thread.
                    unsafe {
                        let _ = windows::Win32::System::Com::CoInitializeEx(None, windows::Win32::System::Com::COINIT_APARTMENTTHREADED);
                    }
                    (shell.read(&link), windows_links::link_args(&link), shell.read(&stranger))
                })
                .join()
                .unwrap()
            })
        };
        let (icon, args, stranger_icon) = read();
        assert_eq!(icon, Some(format!("{},0", ico.display())));
        assert_eq!(args, "--flag \"a b\"");
        assert_eq!(stranger_icon, None, "fremde Verknüpfung nicht lesbar = nicht angefasst");
        assert_eq!(originals.get(link.to_str().unwrap()).map(String::as_str), Some(""));

        assert_eq!(windows_links::apply(&paths, &exe, Some(&ico), &icons.icon_dir(), &mut originals).unwrap(), 0, "idempotent");
        assert_eq!(windows_links::apply(&paths, &exe, None, &icons.icon_dir(), &mut originals).unwrap(), 1);
        let (icon, args, _) = read();
        assert_eq!(icon.as_deref(), Some(""));
        assert_eq!(args, "--flag \"a b\"");
        assert!(originals.is_empty());
    }
}
