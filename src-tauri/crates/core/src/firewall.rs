//! Windows-Firewall: Minecraft (Java) öffnet für LAN-Welten und manche Mods
//! einen Port – dann fragt Windows pro Java-Programm, ob es ins Netzwerk
//! darf. Der Launcher trägt die Freigabe für alle seine Java-Runtimes auf
//! einmal ein (eine einzige Admin-Abfrage), damit das nicht bei jeder Instanz
//! erneut kommt.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const STATE_FILE: &str = "firewall.json";
const RULE_PREFIX: &str = "TRS Launcher - Java";

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct State {
    /// Programme, für die bereits eine Regel angelegt wurde.
    #[serde(default)]
    allowed: Vec<PathBuf>,
    /// Beim automatischen Nachfragen abgelehnt – nicht bei jedem Start erneut fragen.
    #[serde(default)]
    declined: Vec<PathBuf>,
}

/// Alle Java-Programme unserer Runtimes (`java/<component>/bin/java[w].exe`).
pub fn runtime_programs(paths: &Paths) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(paths.java_dir()) else { return Vec::new() };
    let mut out = Vec::new();
    for entry in entries.flatten() {
        for exe in ["javaw.exe", "java.exe"] {
            let program = entry.path().join("bin").join(exe);
            if program.is_file() {
                out.push(program);
            }
        }
    }
    out.sort();
    out
}

/// Programme, für die noch keine Regel eingetragen ist.
pub async fn missing(paths: &Paths) -> Vec<PathBuf> {
    let state: State = fsutil::read_json(&paths.root().join(STATE_FILE)).await.ok().flatten().unwrap_or_default();
    runtime_programs(paths).into_iter().filter(|p| !state.allowed.contains(p)).collect()
}

/// Wie [`missing`], aber ohne Programme, bei denen die automatische Abfrage
/// schon einmal abgelehnt wurde.
pub async fn missing_for_auto(paths: &Paths) -> Vec<PathBuf> {
    let state: State = fsutil::read_json(&paths.root().join(STATE_FILE)).await.ok().flatten().unwrap_or_default();
    missing(paths).await.into_iter().filter(|p| !state.declined.contains(p)).collect()
}

/// Merkt sich, dass die automatische Abfrage abgelehnt wurde.
pub async fn remember_declined(paths: &Paths, programs: &[PathBuf]) -> Result<()> {
    let file = paths.root().join(STATE_FILE);
    let mut state: State = fsutil::read_json(&file).await.ok().flatten().unwrap_or_default();
    for p in programs {
        if !state.declined.contains(p) {
            state.declined.push(p.clone());
        }
    }
    fsutil::write_json(&file, &state).await
}

/// PowerShell-String in einfachen Anführungszeichen (dort ist nur `'` besonders).
fn ps_quote(text: &str) -> String {
    format!("'{}'", text.replace('\'', "''"))
}

fn rule_name(program: &Path) -> String {
    // z. B. "TRS Launcher - Java (java-runtime-delta, javaw.exe)"
    let component = program
        .parent()
        .and_then(Path::parent)
        .and_then(Path::file_name)
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let exe = program.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    format!("{RULE_PREFIX} ({component}, {exe})")
}

/// Skript, das alte Regeln gleichen Namens ersetzt und neue anlegt.
fn build_script(programs: &[PathBuf]) -> String {
    let mut script = String::from("$ErrorActionPreference = 'Stop'\n");
    for program in programs {
        let name = ps_quote(&rule_name(program));
        let path = ps_quote(&program.display().to_string());
        script.push_str(&format!(
            "Get-NetFirewallRule -DisplayName {name} -ErrorAction SilentlyContinue | Remove-NetFirewallRule\n\
             New-NetFirewallRule -DisplayName {name} -Program {path} -Direction Inbound -Action Allow \
             -Protocol TCP -Profile Any | Out-Null\n\
             New-NetFirewallRule -DisplayName {name} -Program {path} -Direction Inbound -Action Allow \
             -Protocol UDP -Profile Any | Out-Null\n"
        ));
    }
    script
}

/// Trägt die Freigabe für die angegebenen Programme ein. Öffnet genau eine
/// Windows-Admin-Abfrage (UAC). `Error::Cancelled`, wenn sie abgelehnt wird.
pub async fn allow(paths: &Paths, programs: Vec<PathBuf>) -> Result<usize> {
    if programs.is_empty() {
        return Ok(0);
    }
    // Nur unsere eigenen Runtimes – nie beliebige Programme freigeben.
    let java_dir = paths.java_dir();
    if programs.iter().any(|p| !p.starts_with(&java_dir) || !p.is_file()) {
        return Err(Error::validation("Nur Java-Versionen des Launchers können freigegeben werden."));
    }

    // Als -EncodedCommand übergeben statt als Datei: Eine Skriptdatei im
    // Temp-Ordner könnte ein anderes Programm vor der Admin-Ausführung tauschen.
    let encoded = encode_command(&build_script(&programs));
    let code = tokio::task::spawn_blocking(move || run_elevated_powershell(&encoded))
        .await
        .map_err(|e| Error::Internal(e.to_string()))?;

    match code? {
        0 => {}
        code => {
            tracing::warn!("Firewall-Skript endete mit Code {code}");
            return Err(Error::launch("Die Firewall-Freigabe konnte nicht eingetragen werden."));
        }
    }

    let state_file = paths.root().join(STATE_FILE);
    let mut state: State = fsutil::read_json(&state_file).await.ok().flatten().unwrap_or_default();
    for program in &programs {
        if !state.allowed.contains(program) {
            state.allowed.push(program.clone());
        }
    }
    state.declined.retain(|p| !programs.contains(p));
    fsutil::write_json(&state_file, &state).await?;
    Ok(programs.len())
}

/// PowerShell erwartet für -EncodedCommand Base64 über UTF-16LE.
fn encode_command(script: &str) -> String {
    use base64::Engine;
    let bytes: Vec<u8> = script.encode_utf16().flat_map(u16::to_le_bytes).collect();
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

/// Startet PowerShell mit dem kodierten Skript und Admin-Rechten (UAC) und wartet.
fn run_elevated_powershell(encoded: &str) -> Result<u32> {
    use windows::Win32::Foundation::{CloseHandle, ERROR_CANCELLED, GetLastError};
    use windows::Win32::System::Threading::{GetExitCodeProcess, INFINITE, WaitForSingleObject};
    use windows::Win32::UI::Shell::{SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, ShellExecuteExW};
    use windows::Win32::UI::WindowsAndMessaging::SW_HIDE;
    use windows::core::{HSTRING, w};

    let params = HSTRING::from(format!(
        "-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand {encoded}"
    ));
    let mut info = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS,
        lpVerb: w!("runas"),
        lpFile: w!("powershell.exe"),
        lpParameters: windows::core::PCWSTR(params.as_ptr()),
        nShow: SW_HIDE.0,
        ..Default::default()
    };
    // SAFETY: `info` ist vollständig initialisiert; alle Strings leben bis zum Ende der Funktion.
    unsafe {
        if ShellExecuteExW(&mut info).is_err() {
            if GetLastError() == ERROR_CANCELLED {
                return Err(Error::Cancelled);
            }
            return Err(Error::launch("Die Admin-Abfrage konnte nicht geöffnet werden."));
        }
        if info.hProcess.is_invalid() {
            return Err(Error::launch("Die Firewall-Freigabe wurde nicht gestartet."));
        }
        WaitForSingleObject(info.hProcess, INFINITE);
        let mut code = 1u32;
        let _ = GetExitCodeProcess(info.hProcess, &mut code);
        let _ = CloseHandle(info.hProcess);
        Ok(code)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quoting_and_names() {
        assert_eq!(ps_quote("C:\\Users\\O'Neil\\x"), "'C:\\Users\\O''Neil\\x'");
        let p = PathBuf::from(r"C:\Users\A\AppData\Roaming\TRS-Launcher\java\java-runtime-delta\bin\javaw.exe");
        assert_eq!(rule_name(&p), "TRS Launcher - Java (java-runtime-delta, javaw.exe)");
        let script = build_script(&[p]);
        assert!(script.contains("-Profile Any"));
        assert!(script.contains("Remove-NetFirewallRule"));
        assert_eq!(script.matches("New-NetFirewallRule").count(), 2);
        // "ä" → UTF-16LE e4 00
        assert_eq!(encode_command("ä"), "5AA=");
    }

    #[tokio::test]
    async fn finds_runtimes_and_rejects_foreign_programs() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        let bin = paths.java_dir().join("java-runtime-delta").join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(bin.join("javaw.exe"), b"x").unwrap();
        std::fs::write(bin.join("java.exe"), b"x").unwrap();

        assert_eq!(runtime_programs(&paths).len(), 2);
        assert_eq!(missing(&paths).await.len(), 2);

        let foreign = dir.path().join("evil.exe");
        std::fs::write(&foreign, b"x").unwrap();
        assert!(matches!(allow(&paths, vec![foreign]).await, Err(Error::Validation(_))));
        assert_eq!(allow(&paths, vec![]).await.unwrap(), 0);
    }
}
