//! Clip als *Datei* in die Zwischenablage legen (zum Einfügen in Discord,
//! Explorer, Messenger …).
//!
//! - Windows: Format `CF_HDROP` (Dateiliste wie beim Kopieren im Explorer).
//! - Linux: `text/uri-list` über `wl-copy` (Wayland) bzw. `xclip` (X11) –
//!   nach bestem Bemühen; fehlt beides, gibt es eine Fehlermeldung.

use std::path::PathBuf;

use crate::{Error, Result};

fn failed() -> Error {
    Error::validation(crate::msg!("clips.clipboardFailed", "Der Clip konnte nicht in die Zwischenablage kopiert werden."))
}

/// Inhalt für `CF_HDROP`: `DROPFILES`-Kopf (20 Bytes, Unicode) + Pfade als
/// UTF-16, je mit NUL, am Ende ein weiteres NUL.
pub fn drop_files_payload(paths: &[PathBuf]) -> Vec<u8> {
    const HEADER: u32 = 20;
    let mut out = Vec::new();
    out.extend_from_slice(&HEADER.to_le_bytes()); // pFiles
    out.extend_from_slice(&0i32.to_le_bytes()); // pt.x
    out.extend_from_slice(&0i32.to_le_bytes()); // pt.y
    out.extend_from_slice(&0u32.to_le_bytes()); // fNC
    out.extend_from_slice(&1u32.to_le_bytes()); // fWide
    for path in paths {
        for unit in path.as_os_str().to_string_lossy().encode_utf16() {
            out.extend_from_slice(&unit.to_le_bytes());
        }
        out.extend_from_slice(&[0, 0]);
    }
    out.extend_from_slice(&[0, 0]);
    out
}

/// `file://`-Adressen, eine je Zeile (für `text/uri-list`).
pub fn uri_list(paths: &[PathBuf]) -> String {
    paths.iter().filter_map(|p| url::Url::from_file_path(p).ok()).map(|u| format!("{u}\r\n")).collect()
}

#[cfg(windows)]
pub fn copy_files(paths: &[PathBuf]) -> Result<()> {
    use windows::Win32::Foundation::{GlobalFree, HANDLE};
    use windows::Win32::System::DataExchange::{CloseClipboard, EmptyClipboard, OpenClipboard, SetClipboardData};
    use windows::Win32::System::Memory::{GMEM_MOVEABLE, GlobalAlloc, GlobalLock, GlobalUnlock};

    const CF_HDROP: u32 = 15;
    let payload = drop_files_payload(paths);
    // SAFETY: Speicher wird mit passender Größe angelegt, nur innerhalb von Lock/Unlock
    // beschrieben und gehört nach erfolgreichem SetClipboardData dem System.
    unsafe {
        let memory = GlobalAlloc(GMEM_MOVEABLE, payload.len()).map_err(|_| failed())?;
        let target = GlobalLock(memory);
        if target.is_null() {
            let _ = GlobalFree(Some(memory));
            return Err(failed());
        }
        std::ptr::copy_nonoverlapping(payload.as_ptr(), target.cast::<u8>(), payload.len());
        let _ = GlobalUnlock(memory);
        // Die Zwischenablage kann kurz von einem anderen Programm belegt sein.
        let mut opened = false;
        for _ in 0..10 {
            if OpenClipboard(None).is_ok() {
                opened = true;
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(25));
        }
        if !opened {
            let _ = GlobalFree(Some(memory));
            return Err(failed());
        }
        let _ = EmptyClipboard();
        let set = SetClipboardData(CF_HDROP, Some(HANDLE(memory.0)));
        let _ = CloseClipboard();
        if set.is_err() {
            let _ = GlobalFree(Some(memory));
            return Err(failed());
        }
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn copy_files(paths: &[PathBuf]) -> Result<()> {
    use std::io::Write;
    use std::process::{Command, Stdio};

    let list = uri_list(paths);
    let wayland = std::env::var_os("WAYLAND_DISPLAY").is_some();
    let tools: Vec<(&str, Vec<&str>)> = if wayland {
        vec![("wl-copy", vec!["--type", "text/uri-list"]), ("xclip", vec!["-selection", "clipboard", "-t", "text/uri-list", "-i"])]
    } else {
        vec![("xclip", vec!["-selection", "clipboard", "-t", "text/uri-list", "-i"])]
    };
    for (tool, args) in tools {
        let mut cmd = Command::new(tool);
        crate::platform::env::clean_std(&mut cmd);
        cmd.args(&args).stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null());
        let Ok(mut child) = cmd.spawn() else { continue };
        let written = child.stdin.take().is_some_and(|mut stdin| stdin.write_all(list.as_bytes()).is_ok());
        // Beide Programme bleiben im Hintergrund, bis etwas anderes kopiert wird – nicht warten.
        std::thread::spawn(move || {
            let _ = child.wait();
        });
        if written {
            return Ok(());
        }
    }
    Err(failed())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dateiliste_fuer_die_zwischenablage() {
        let payload = drop_files_payload(&[PathBuf::from("C:\\Clips\\a b.mp4")]);
        assert_eq!(&payload[..4], &20u32.to_le_bytes(), "Pfade beginnen nach dem Kopf");
        assert_eq!(&payload[16..20], &1u32.to_le_bytes(), "Unicode");
        let text: Vec<u16> = payload[20..].chunks(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
        let expected: Vec<u16> = "C:\\Clips\\a b.mp4".encode_utf16().chain([0, 0]).collect();
        assert_eq!(text, expected, "doppelt NUL-terminiert");
    }

    #[test]
    fn uri_liste_ist_kodiert() {
        #[cfg(windows)]
        let path = PathBuf::from("C:\\Clips\\a b.mp4");
        #[cfg(not(windows))]
        let path = PathBuf::from("/home/u/Clips/a b.mp4");
        let list = uri_list(&[path]);
        assert!(list.starts_with("file:///") && list.contains("a%20b.mp4") && list.ends_with("\r\n"), "{list}");
    }
}
