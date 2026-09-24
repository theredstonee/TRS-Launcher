//! Windows-Firewall: Minecraft (Java) öffnet für LAN-Welten und manche Mods
//! einen Port – dann fragt Windows pro Java-Programm, ob es ins Netzwerk
//! darf. Der Launcher trägt die Freigabe für alle seine Java-Runtimes auf
//! einmal ein (eine einzige Admin-Abfrage), damit das nicht bei jeder Instanz
//! erneut kommt.
//!
//! Die Regeln legt der Launcher selbst über die Firewall-API von Windows an:
//! Er startet sich mit Admin-Rechten im Hilfsmodus ([`HELPER_FLAG`]), der nur
//! die Regeln einträgt und sich sofort beendet – ohne PowerShell oder Skripte.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use serde::{Deserialize, Serialize};

use crate::paths::Paths;
use crate::{Error, Result, fsutil};

const STATE_FILE: &str = "firewall.json";
#[cfg(windows)]
const RULE_PREFIX: &str = "TRS Launcher - Java";
/// Startet den Launcher im Firewall-Hilfsmodus statt der App.
pub const HELPER_FLAG: &str = "--trs-firewall-helper";
const MAX_HELPER_PROGRAMS: usize = 64;

/// Programm, das im Hilfsmodus gestartet wird (die Launcher-EXE). Ohne
/// gesetzten Pfad (z. B. im CLI-Beispiel) wird nichts eingetragen.
static HELPER_EXE: OnceLock<PathBuf> = OnceLock::new();

pub fn set_helper_exe(exe: PathBuf) {
    let _ = HELPER_EXE.set(exe);
}

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
/// Unter Linux gibt es keine Programm-Freigaben – dort immer leer.
pub fn runtime_programs(paths: &Paths) -> Vec<PathBuf> {
    if !cfg!(windows) {
        return Vec::new();
    }
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

#[cfg(windows)]
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

/// Trägt die Freigabe für die angegebenen Programme ein. Öffnet genau eine
/// Windows-Admin-Abfrage (UAC). `Error::Cancelled`, wenn sie abgelehnt wird.
pub async fn allow(paths: &Paths, programs: Vec<PathBuf>) -> Result<usize> {
    if programs.is_empty() {
        return Ok(0);
    }
    // Nur unsere eigenen Runtimes – nie beliebige Programme freigeben.
    let java_dir = paths.java_dir();
    if programs.len() > MAX_HELPER_PROGRAMS || programs.iter().any(|p| !p.starts_with(&java_dir) || !helper_accepts(p)) {
        return Err(Error::validation(crate::msg!("firewall.onlyLauncherJava", "Nur Java-Versionen des Launchers können freigegeben werden.")));
    }
    let Some(exe) = HELPER_EXE.get().cloned() else { return Ok(0) };

    let params = helper_params(&programs);
    let code = tokio::task::spawn_blocking(move || run_elevated_helper(&exe, &params))
        .await
        .map_err(|e| Error::Internal(e.to_string()))?;

    match code? {
        0 => {}
        code => {
            tracing::warn!("Firewall-Hilfsmodus endete mit Code {code}");
            return Err(Error::launch(crate::msg!("firewall.ruleFailed", "Die Firewall-Freigabe konnte nicht eingetragen werden.")));
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

/// Kommandozeile für den Hilfsmodus: jeder Pfad in Anführungszeichen
/// (Windows-Pfade können selbst keine Anführungszeichen enthalten).
fn helper_params(programs: &[PathBuf]) -> String {
    let mut params = String::from(HELPER_FLAG);
    for program in programs {
        params.push_str(&format!(" \"{}\"", program.display()));
    }
    params
}

#[cfg(not(windows))]
fn run_elevated_helper(_exe: &Path, _params: &str) -> Result<u32> {
    Err(Error::validation(crate::msg!("firewall.onlyLauncherJava", "Nur Java-Versionen des Launchers können freigegeben werden.")))
}

/// Startet die Launcher-EXE mit Admin-Rechten (UAC) im Hilfsmodus und wartet.
#[cfg(windows)]
fn run_elevated_helper(exe: &Path, params: &str) -> Result<u32> {
    use windows::Win32::Foundation::{CloseHandle, ERROR_CANCELLED, GetLastError};
    use windows::Win32::System::Threading::{GetExitCodeProcess, INFINITE, WaitForSingleObject};
    use windows::Win32::UI::Shell::{SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, ShellExecuteExW};
    use windows::Win32::UI::WindowsAndMessaging::SW_HIDE;
    use windows::core::{HSTRING, PCWSTR, w};

    let file = HSTRING::from(exe.as_os_str());
    let params = HSTRING::from(params);
    let mut info = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS,
        lpVerb: w!("runas"),
        lpFile: PCWSTR(file.as_ptr()),
        lpParameters: PCWSTR(params.as_ptr()),
        nShow: SW_HIDE.0,
        ..Default::default()
    };
    // SAFETY: `info` ist vollständig initialisiert; alle Strings leben bis zum Ende der Funktion.
    unsafe {
        if ShellExecuteExW(&mut info).is_err() {
            if GetLastError() == ERROR_CANCELLED {
                return Err(Error::Cancelled);
            }
            return Err(Error::launch(crate::msg!("firewall.elevationFailed", "Die Admin-Abfrage konnte nicht geöffnet werden.")));
        }
        if info.hProcess.is_invalid() {
            return Err(Error::launch(crate::msg!("firewall.helperNotStarted", "Die Firewall-Freigabe wurde nicht gestartet.")));
        }
        WaitForSingleObject(info.hProcess, INFINITE);
        let mut code = 1u32;
        let _ = GetExitCodeProcess(info.hProcess, &mut code);
        let _ = CloseHandle(info.hProcess);
        Ok(code)
    }
}

/// Nur echte Java-Programme einer Runtime (`…\bin\java[w].exe`). Der
/// Hilfsmodus läuft mit Admin-Rechten und prüft seine Eingaben deshalb selbst.
fn helper_accepts(program: &Path) -> bool {
    let exe_ok = program
        .file_name()
        .and_then(|n| n.to_str())
        .is_some_and(|n| n.eq_ignore_ascii_case("java.exe") || n.eq_ignore_ascii_case("javaw.exe"));
    let bin_ok = program
        .parent()
        .and_then(Path::file_name)
        .and_then(|n| n.to_str())
        .is_some_and(|n| n.eq_ignore_ascii_case("bin"));
    program.is_absolute() && exe_ok && bin_ok && program.is_file()
}

/// Wird ganz am Anfang von `main` aufgerufen: Läuft der Prozess im
/// Hilfsmodus, trägt er die Regeln ein und liefert den Exit-Code.
pub fn helper_main_if_requested() -> Option<i32> {
    if !cfg!(windows) {
        return None;
    }
    let mut args = std::env::args_os().skip(1);
    if args.next()? != HELPER_FLAG {
        return None;
    }
    let programs: Vec<PathBuf> = args.map(PathBuf::from).collect();
    if programs.is_empty() || programs.len() > MAX_HELPER_PROGRAMS || !programs.iter().all(|p| helper_accepts(p)) {
        return Some(2);
    }
    Some(match add_rules(&programs) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("Firewall-Regeln konnten nicht eingetragen werden: {e}");
            1
        }
    })
}

#[cfg(not(windows))]
fn add_rules(_programs: &[PathBuf]) -> std::io::Result<()> {
    Err(std::io::Error::other("Firewall-Regeln gibt es nur unter Windows"))
}

/// Legt je Programm eine Regel für TCP und UDP an (alte gleichen Namens werden ersetzt).
#[cfg(windows)]
fn add_rules(programs: &[PathBuf]) -> windows::core::Result<()> {
    use windows::Win32::Foundation::VARIANT_TRUE;
    use windows::Win32::NetworkManagement::WindowsFirewall::{
        INetFwPolicy2, INetFwRule, NET_FW_ACTION_ALLOW, NET_FW_PROFILE2_ALL, NET_FW_RULE_DIR_IN, NetFwPolicy2, NetFwRule,
    };
    use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, CoCreateInstance, CoInitializeEx};
    use windows::core::BSTR;

    const TCP: i32 = 6;
    const UDP: i32 = 17;

    // SAFETY: COM-Aufrufe im eigenen, kurzlebigen Hilfsprozess; die windows-Crate
    // gibt alle Objekte über Referenzzählung frei.
    unsafe {
        CoInitializeEx(None, COINIT_APARTMENTTHREADED).ok()?;
        let policy: INetFwPolicy2 = CoCreateInstance(&NetFwPolicy2, None, CLSCTX_INPROC_SERVER)?;
        let rules = policy.Rules()?;
        for program in programs {
            let name = BSTR::from(rule_name(program));
            // Remove löscht je Aufruf eine Regel dieses Namens.
            for _ in 0..8 {
                if rules.Item(&name).is_err() {
                    break;
                }
                rules.Remove(&name)?;
            }
            let application = BSTR::from(program.display().to_string());
            for protocol in [TCP, UDP] {
                let rule: INetFwRule = CoCreateInstance(&NetFwRule, None, CLSCTX_INPROC_SERVER)?;
                rule.SetName(&name)?;
                rule.SetApplicationName(&application)?;
                rule.SetProtocol(protocol)?;
                rule.SetDirection(NET_FW_RULE_DIR_IN)?;
                rule.SetAction(NET_FW_ACTION_ALLOW)?;
                rule.SetProfiles(NET_FW_PROFILE2_ALL.0)?;
                rule.SetEnabled(VARIANT_TRUE)?;
                rules.Add(&rule)?;
            }
        }
    }
    Ok(())
}

#[cfg(all(test, not(windows)))]
mod tests_unix {
    use super::*;

    #[tokio::test]
    async fn nothing_to_allow_outside_windows() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        let bin = paths.java_dir().join("java-runtime-delta").join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(bin.join("java"), b"x").unwrap();
        assert!(runtime_programs(&paths).is_empty());
        assert!(missing_for_auto(&paths).await.is_empty());
        assert_eq!(allow(&paths, vec![]).await.unwrap(), 0);
        assert_eq!(helper_main_if_requested(), None);
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;

    #[test]
    fn names_and_helper_params() {
        let p = PathBuf::from(r"C:\Users\A B\AppData\Roaming\TRS-Launcher\java\java-runtime-delta\bin\javaw.exe");
        assert_eq!(rule_name(&p), "TRS Launcher - Java (java-runtime-delta, javaw.exe)");
        assert_eq!(
            helper_params(&[p]),
            r#"--trs-firewall-helper "C:\Users\A B\AppData\Roaming\TRS-Launcher\java\java-runtime-delta\bin\javaw.exe""#
        );
    }

    #[test]
    fn helper_only_accepts_java_in_bin() {
        let dir = tempfile::tempdir().unwrap();
        let bin = dir.path().join("rt").join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(bin.join("javaw.exe"), b"x").unwrap();
        std::fs::write(bin.join("cmd.exe"), b"x").unwrap();
        std::fs::write(dir.path().join("java.exe"), b"x").unwrap();
        assert!(helper_accepts(&bin.join("javaw.exe")));
        assert!(!helper_accepts(&bin.join("cmd.exe")));
        assert!(!helper_accepts(&bin.join("java.exe")), "existiert nicht");
        assert!(!helper_accepts(&dir.path().join("java.exe")), "nicht in bin");
        assert!(!helper_accepts(Path::new(r"bin\javaw.exe")), "relativ");
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
        // Ohne Hilfs-EXE (CLI/Tests) wird nichts eingetragen.
        assert_eq!(allow(&paths, runtime_programs(&paths)).await.unwrap(), 0);
    }
}
