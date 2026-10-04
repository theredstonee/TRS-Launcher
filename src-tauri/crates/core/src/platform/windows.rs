//! Windows-Teil von [`crate::platform`]: Prozess-Handles, Registry,
//! DPAPI, Papierkorb und der System-DNS-Resolver.

use std::path::{Path, PathBuf};

use windows::Win32::Foundation::{CloseHandle, FILETIME, HANDLE, WAIT_OBJECT_0};
use windows::Win32::System::Threading::{
    GetExitCodeProcess, GetProcessTimes, INFINITE, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE,
    PROCESS_TERMINATE, TerminateProcess, WaitForSingleObject,
};

use crate::{Error, Result};

const DETACHED_PROCESS: u32 = 0x0000_0008;
const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const ABOVE_NORMAL_PRIORITY_CLASS: u32 = 0x0000_8000;

// --- Prozesse ----------------------------------------------------------------------

/// Eigene Prozessgruppe ohne Konsole: Das Spiel überlebt den Launcher.
/// `high_priority`: Prioritätsklasse „Höher als normal“.
pub fn detach(cmd: &mut std::process::Command, high_priority: bool) {
    use std::os::windows::process::CommandExt;
    let priority = if high_priority { ABOVE_NORMAL_PRIORITY_CLASS } else { 0 };
    cmd.creation_flags(DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | priority);
}

/// Kein Konsolenfenster für Hilfsprozesse (Hooks, Forge-Processors).
pub fn hide_console(cmd: &mut tokio::process::Command) {
    cmd.creation_flags(CREATE_NO_WINDOW);
}

/// Befehl über die System-Shell (`cmd /D /S /C "…"`).
pub fn shell_command(command: &str) -> tokio::process::Command {
    let shell = std::env::var_os("ComSpec").filter(|s| !s.is_empty()).unwrap_or_else(|| "cmd.exe".into());
    let mut cmd = tokio::process::Command::new(shell);
    // `/S /C "…"`: cmd entfernt genau das äußere Anführungszeichenpaar und
    // übernimmt den Rest wörtlich.
    cmd.raw_arg(format!("/D /S /C \"{command}\""));
    hide_console(&mut cmd);
    cmd
}

/// Handle auf einen laufenden (auch fremd gestarteten) Prozess.
pub struct ProcessHandle(HANDLE);

// SAFETY: Ein Prozess-Handle ist ein Kernel-Objekt; es darf von beliebigen
// Threads benutzt werden. Geschlossen wird es genau einmal im Drop.
unsafe impl Send for ProcessHandle {}
unsafe impl Sync for ProcessHandle {}

impl ProcessHandle {
    pub fn open(pid: u32) -> Option<Self> {
        let access = PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE | PROCESS_TERMINATE;
        // SAFETY: reiner API-Aufruf; ein ungültiger PID liefert einen Fehler.
        unsafe { OpenProcess(access, false, pid) }.ok().map(Self)
    }

    /// Übernimmt ein frisch gestartetes Kind. Solange `child` lebt, existiert
    /// das Prozessobjekt sicher – erst öffnen, dann loslassen.
    pub fn from_child(child: std::process::Child) -> Option<Self> {
        let handle = Self::open(child.id());
        drop(child);
        handle
    }

    /// Startzeit als FILETIME-Wert – unterscheidet einen wiederverwendeten PID.
    pub fn creation_time(&self) -> Option<u64> {
        let (mut created, mut exited, mut kernel, mut user) =
            (FILETIME::default(), FILETIME::default(), FILETIME::default(), FILETIME::default());
        // SAFETY: gültiges Handle, alle Ausgabezeiger zeigen auf lokale Werte.
        unsafe { GetProcessTimes(self.0, &mut created, &mut exited, &mut kernel, &mut user) }.ok()?;
        Some((u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime))
    }

    pub fn is_alive(&self) -> bool {
        // SAFETY: gültiges Handle; Timeout 0 fragt nur den Zustand ab.
        (unsafe { WaitForSingleObject(self.0, 0) }) != WAIT_OBJECT_0
    }

    /// Blockiert bis zum Prozessende und liefert den Exit-Code.
    pub fn wait(&self) -> Option<i32> {
        // SAFETY: gültiges Handle.
        unsafe {
            WaitForSingleObject(self.0, INFINITE);
            let mut code = 0u32;
            GetExitCodeProcess(self.0, &mut code).ok()?;
            Some(code as i32)
        }
    }

    /// Windows kennt den Exit-Code auch wiedergefundener Prozesse.
    pub fn exit_code_known(&self) -> bool {
        true
    }

    pub fn terminate(&self) -> bool {
        // SAFETY: gültiges Handle mit PROCESS_TERMINATE.
        unsafe { TerminateProcess(self.0, 1) }.is_ok()
    }
}

impl Drop for ProcessHandle {
    fn drop(&mut self) {
        // SAFETY: Handle stammt aus OpenProcess und wird nur hier geschlossen.
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}

// --- Grafikkarte ---------------------------------------------------------------------

/// Unter Windows setzt [`crate::gpu`] die Grafikeinstellung in der Registry –
/// zusätzliche Umgebungsvariablen braucht es nicht.
pub fn dedicated_gpu_env(_program: &Path) -> Vec<(String, String)> {
    Vec::new()
}

// --- Microsoft Store: installierte Pakete --------------------------------------------------

/// Gibt es für den aktuellen Benutzer ein installiertes Store-Paket, dessen Vollname (`Name_Version_Arch__Herausgeber`)
/// `matches` erfüllt? Liest nur den Registry-Zweig der Paketverwaltung (HKCU) – ohne Adminrechte.
pub fn appx_package_installed(matches: impl Fn(&str) -> bool) -> bool {
    use windows::Win32::Foundation::ERROR_SUCCESS;
    use windows::Win32::System::Registry::{HKEY, HKEY_CURRENT_USER, KEY_READ, RegCloseKey, RegEnumKeyExW, RegOpenKeyExW};
    use windows::core::{HSTRING, PCWSTR, PWSTR};

    let path = HSTRING::from("Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\CurrentVersion\\AppModel\\Repository\\Packages");
    let mut key = HKEY::default();
    // SAFETY: Pfad ist nullterminiert, `key` wird unten wieder geschlossen.
    if unsafe { RegOpenKeyExW(HKEY_CURRENT_USER, PCWSTR(path.as_ptr()), None, KEY_READ, &mut key) } != ERROR_SUCCESS {
        return false;
    }
    let mut found = false;
    let mut index = 0u32;
    loop {
        let mut buf = [0u16; 256];
        let mut len = buf.len() as u32;
        // SAFETY: Puffer und Länge (in Zeichen) passen zusammen; die übrigen Ausgaben sind optional.
        let status = unsafe { RegEnumKeyExW(key, index, Some(PWSTR(buf.as_mut_ptr())), &mut len, None, None, None, None) };
        if status != ERROR_SUCCESS {
            break;
        }
        index += 1;
        if matches(&String::from_utf16_lossy(&buf[..(len as usize).min(buf.len())])) {
            found = true;
            break;
        }
    }
    // SAFETY: `key` wurde oben geöffnet.
    unsafe {
        let _ = RegCloseKey(key);
    }
    found
}

// --- Systemname ------------------------------------------------------------------------

fn read_version_string(name: &str) -> Option<String> {
    use windows::Win32::System::Registry::{HKEY_LOCAL_MACHINE, RRF_RT_REG_SZ, RegGetValueW};
    use windows::core::{HSTRING, PCWSTR};

    let key = HSTRING::from("SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion");
    let value = HSTRING::from(name);
    let mut buf = [0u16; 128];
    let mut len = (buf.len() * 2) as u32;
    // SAFETY: Puffer und Länge (in Bytes) passen zusammen; Strings sind nullterminiert.
    let status = unsafe {
        RegGetValueW(
            HKEY_LOCAL_MACHINE,
            PCWSTR(key.as_ptr()),
            PCWSTR(value.as_ptr()),
            RRF_RT_REG_SZ,
            None,
            Some(buf.as_mut_ptr().cast()),
            Some(&mut len),
        )
    };
    if status.is_err() {
        return None;
    }
    let chars = (len as usize / 2).min(buf.len());
    let text = String::from_utf16_lossy(&buf[..chars]);
    let text = text.trim_end_matches('\0').trim().to_owned();
    (!text.is_empty()).then_some(text)
}

/// z. B. „Windows 11 (24H2, Build 26100)“.
pub fn os_description() -> String {
    let build = read_version_string("CurrentBuild").and_then(|b| b.parse().ok());
    let display = read_version_string("DisplayVersion").filter(|v| v.len() <= 16 && v.chars().all(|c| c.is_ascii_alphanumeric()));
    crate::system::describe(build, display.as_deref())
}

// --- Arbeitsspeicher ------------------------------------------------------------------------

/// Eingebauter Arbeitsspeicher in MB (`GlobalMemoryStatusEx`).
pub fn total_memory_mb() -> Option<u32> {
    use windows::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
    let mut status = MEMORYSTATUSEX { dwLength: std::mem::size_of::<MEMORYSTATUSEX>() as u32, ..Default::default() };
    // SAFETY: `status` ist initialisiert und `dwLength` gesetzt, wie die API es verlangt.
    unsafe { GlobalMemoryStatusEx(&mut status) }.ok()?;
    u32::try_from(status.ullTotalPhys / (1024 * 1024)).ok().filter(|mb| *mb > 0)
}

/// Gerade freier Arbeitsspeicher in MB: das Kleinere aus freiem RAM und noch
/// zusagbarem Speicher (RAM + Auslagerungsdatei) – Java scheitert sonst mit 1455.
pub fn available_memory_mb() -> Option<u32> {
    use windows::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
    let mut status = MEMORYSTATUSEX { dwLength: std::mem::size_of::<MEMORYSTATUSEX>() as u32, ..Default::default() };
    // SAFETY: `status` ist initialisiert und `dwLength` gesetzt, wie die API es verlangt.
    unsafe { GlobalMemoryStatusEx(&mut status) }.ok()?;
    let free = status.ullAvailPhys.min(status.ullAvailPageFile);
    u32::try_from(free / (1024 * 1024)).ok()
}

// --- Papierkorb ----------------------------------------------------------------------------

/// Datei in den Windows-Papierkorb verschieben (`SHFileOperationW`).
pub fn move_to_trash(path: &Path) -> Result<()> {
    use std::os::windows::ffi::OsStrExt;

    use windows::Win32::UI::Shell::{
        FO_DELETE, FOF_ALLOWUNDO, FOF_NOCONFIRMATION, FOF_NOERRORUI, FOF_SILENT, SHFILEOPSTRUCTW, SHFileOperationW,
    };
    use windows::core::PCWSTR;

    // Der Pfad muss doppelt nullterminiert sein (Liste von Dateien).
    let mut wide: Vec<u16> = path.as_os_str().encode_wide().collect();
    if wide.contains(&0) {
        return Err(Error::validation(crate::msg!("screenshots.invalidPath", "Ungültiger Pfad")));
    }
    wide.push(0);
    wide.push(0);

    let mut op = SHFILEOPSTRUCTW {
        wFunc: FO_DELETE,
        pFrom: PCWSTR(wide.as_ptr()),
        fFlags: (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI).0 as u16,
        ..Default::default()
    };
    // SAFETY: `op` zeigt auf den doppelt nullterminierten Puffer oben, der
    // während des Aufrufs am Leben bleibt.
    let code = unsafe { SHFileOperationW(&raw mut op) };
    if code == 0 && !op.fAnyOperationsAborted.as_bool() {
        Ok(())
    } else {
        Err(Error::Internal(format!("SHFileOperation: {code}")))
    }
}

// --- Ordner-Links (gemeinsame Ordner) ----------------------------------------------------

/// Legt `link` als Junction auf den Ordner `target` an. Junctions brauchen –
/// anders als Symlinks – keine Adminrechte und keinen Entwicklermodus.
/// `target` muss ein lokaler, existierender Ordner sein.
pub fn create_dir_link(target: &Path, link: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;

    use windows::Win32::Foundation::GENERIC_WRITE;
    use windows::Win32::Storage::FileSystem::{
        CreateFileW, FILE_FLAG_BACKUP_SEMANTICS, FILE_FLAG_OPEN_REPARSE_POINT, FILE_SHARE_MODE, OPEN_EXISTING,
    };
    use windows::Win32::System::IO::DeviceIoControl;
    use windows::core::PCWSTR;

    // Aus winnt.h/winioctl.h – feste Werte der Windows-ABI.
    const IO_REPARSE_TAG_MOUNT_POINT: u32 = 0xA000_0003;
    const FSCTL_SET_REPARSE_POINT: u32 = 0x0009_00A4;

    let invalid = |text: &str| std::io::Error::new(std::io::ErrorKind::InvalidInput, text.to_owned());
    let target = std::fs::canonicalize(target)?;
    // Kanonisch heißt unter Windows `\\?\C:\…` – die Junction will `\??\C:\…`.
    let mut print: Vec<u16> = target.as_os_str().encode_wide().collect();
    let verbatim: Vec<u16> = r"\\?\".encode_utf16().collect();
    if print.starts_with(&verbatim) {
        print.drain(..verbatim.len());
    }
    let unc: Vec<u16> = r"UNC\".encode_utf16().collect();
    if print.starts_with(&unc) || print.starts_with(&[u16::from(b'\\'), u16::from(b'\\')]) || print.contains(&0) {
        return Err(invalid("junction target must be a local folder"));
    }
    while print.len() > 3 && print.last() == Some(&u16::from(b'\\')) {
        print.pop();
    }
    let substitute: Vec<u16> = r"\??\".encode_utf16().chain(print.iter().copied()).collect();

    let sub_bytes = substitute.len() * 2;
    let print_bytes = print.len() * 2;
    // 8 Bytes Kopf des Mount-Point-Puffers + beide Namen mit Nullzeichen.
    let data_len = 8 + sub_bytes + 2 + print_bytes + 2;
    let (Ok(data_len), Ok(sub_len), Ok(print_len)) =
        (u16::try_from(data_len), u16::try_from(sub_bytes), u16::try_from(print_bytes))
    else {
        return Err(invalid("junction target path too long"));
    };
    let mut buf: Vec<u8> = Vec::with_capacity(8 + usize::from(data_len));
    buf.extend(IO_REPARSE_TAG_MOUNT_POINT.to_le_bytes());
    buf.extend(data_len.to_le_bytes());
    buf.extend(0u16.to_le_bytes()); // Reserved
    buf.extend(0u16.to_le_bytes()); // SubstituteNameOffset
    buf.extend(sub_len.to_le_bytes());
    buf.extend((sub_len + 2).to_le_bytes()); // PrintNameOffset
    buf.extend(print_len.to_le_bytes());
    for unit in substitute.iter().chain(&[0]).chain(&print).chain(&[0]) {
        buf.extend(unit.to_le_bytes());
    }

    std::fs::create_dir(link)?;
    let wide: Vec<u16> = link.as_os_str().encode_wide().chain(Some(0)).collect();
    let result = (|| -> std::io::Result<()> {
        // SAFETY: `wide` ist nullterminiert und lebt während des Aufrufs.
        let handle = unsafe {
            CreateFileW(
                PCWSTR(wide.as_ptr()),
                GENERIC_WRITE.0,
                FILE_SHARE_MODE(0),
                None,
                OPEN_EXISTING,
                FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS,
                None,
            )
        }?;
        let mut returned = 0u32;
        // SAFETY: `buf` ist ein vollständiger REPARSE_DATA_BUFFER und lebt während des Aufrufs;
        // das Handle ist gültig und wird gleich danach geschlossen.
        let set = unsafe {
            DeviceIoControl(
                handle,
                FSCTL_SET_REPARSE_POINT,
                Some(buf.as_ptr().cast()),
                buf.len() as u32,
                None,
                0,
                Some(&raw mut returned),
                None,
            )
        };
        // SAFETY: Handle stammt aus CreateFileW und wird genau einmal geschlossen.
        unsafe {
            let _ = CloseHandle(handle);
        }
        set.map_err(std::io::Error::from)
    })();
    if result.is_err() {
        let _ = std::fs::remove_dir(link);
    }
    result
}

/// Entfernt nur den Link (Junction oder Symlink) – nie das, worauf er zeigt.
pub fn remove_dir_link(link: &Path) -> std::io::Result<()> {
    let meta = std::fs::symlink_metadata(link)?;
    if !meta.file_type().is_symlink() {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "not a link"));
    }
    // Junctions und Ordner-Symlinks sind Verzeichnis-Einträge, Datei-Symlinks Dateien.
    std::fs::remove_dir(link).or_else(|_| std::fs::remove_file(link))
}

// --- Java-Installationen -----------------------------------------------------------------

/// Ordner, in denen Java-Installationen (je ein Unterordner) liegen: Program
/// Files (Oracle, Adoptium, Microsoft, Zulu, …) und `~/.jdks`.
pub fn java_search_dirs() -> Vec<PathBuf> {
    const VENDORS: [&str; 10] = [
        "Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft", "Amazon Corretto", "Semeru",
        "Eclipse Foundation", "AdoptOpenJDK", "OpenJDK",
    ];
    let mut bases: Vec<PathBuf> = ["ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"]
        .iter()
        .filter_map(|v| std::env::var_os(v).map(PathBuf::from))
        .collect();
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        bases.push(PathBuf::from(local).join("Programs"));
    }
    let mut out: Vec<PathBuf> = bases.iter().flat_map(|base| VENDORS.iter().map(move |v| base.join(v))).collect();
    if let Some(profile) = std::env::var_os("USERPROFILE") {
        out.push(PathBuf::from(profile).join(".jdks"));
    }
    out
}

// --- DNS ---------------------------------------------------------------------------------------

/// SRV-Abfrage über den Windows-Resolver (`DnsQuery_W`), blockierend. Gewählt
/// wird wie im Spiel: kleinste Priorität, dann größtes Gewicht.
pub fn lookup_srv(name: &str) -> Option<(String, u16)> {
    match lookup_srv_records(name) {
        super::dns::SrvLookup::Found(records, _) => super::dns::pick_srv(&records).map(|r| (r.target.clone(), r.port)),
        _ => None,
    }
}

/// Höchstens so viele Einträge je Antwort werden gelesen.
const MAX_DNS_RECORDS: usize = 32;

/// Eine Abfrage über `DnsQuery_W`; `read` wertet jeden Eintrag vom Typ `kind`
/// aus (`Some((ttl, wert))`). `Err(true)` = sicher kein Eintrag, `Err(false)` = unbekannt.
fn dns_query<T>(
    name: &str,
    kind: windows::Win32::NetworkManagement::Dns::DNS_TYPE,
    read: impl Fn(&windows::Win32::NetworkManagement::Dns::DNS_RECORDW) -> Option<T>,
) -> std::result::Result<(Vec<T>, u32), bool> {
    use windows::Win32::Foundation::{DNS_ERROR_RCODE_NAME_ERROR, DNS_INFO_NO_RECORDS};
    use windows::Win32::NetworkManagement::Dns::{DNS_QUERY_STANDARD, DNS_RECORDW, DnsFree, DnsFreeRecordList, DnsQuery_W};
    use windows::core::HSTRING;

    let mut records: *mut DNS_RECORDW = std::ptr::null_mut();
    // SAFETY: `records` wird von der API befüllt und unten mit DnsFree
    // freigegeben; gelesen wird nur, solange die Liste lebt.
    unsafe {
        // Die Bindings deklarieren den Ausgabeparameter als ANSI-Variante; bei
        // DnsQuery_W liegen dort tatsächlich die (gleich aufgebauten) W-Records.
        let out = (&raw mut records).cast();
        let status = DnsQuery_W(&HSTRING::from(name), kind, DNS_QUERY_STANDARD, None, out, None);
        if status.is_err() {
            if !records.is_null() {
                DnsFree(Some(records.cast()), DnsFreeRecordList);
            }
            let definite = status == DNS_ERROR_RCODE_NAME_ERROR || status.0 == DNS_INFO_NO_RECORDS as u32;
            return Err(definite);
        }
        if records.is_null() {
            return Err(true);
        }
        let mut found = Vec::new();
        let mut ttl = u32::MAX;
        let mut current = records;
        while !current.is_null() && found.len() < MAX_DNS_RECORDS {
            let record = &*current;
            if record.wType == kind.0
                && let Some(value) = read(record)
            {
                ttl = ttl.min(record.dwTtl);
                found.push(value);
            }
            current = record.pNext;
        }
        DnsFree(Some(records.cast()), DnsFreeRecordList);
        if found.is_empty() { Err(true) } else { Ok((found, ttl)) }
    }
}

/// Alle SRV-Einträge samt TTL (blockierend).
pub fn lookup_srv_records(name: &str) -> super::dns::SrvLookup {
    use windows::Win32::NetworkManagement::Dns::DNS_TYPE_SRV;
    let result = dns_query(name, DNS_TYPE_SRV, |record| {
        // SAFETY: Eintrag vom Typ SRV – die Union enthält SRV-Daten.
        let srv = unsafe { record.Data.SRV };
        if srv.pNameTarget.is_null() {
            return None;
        }
        // SAFETY: nullterminierter String der API, lebt bis DnsFree.
        let target = unsafe { srv.pNameTarget.to_string() }.ok()?;
        Some(super::dns::SrvRecord { priority: srv.wPriority, weight: srv.wWeight, port: srv.wPort, target })
    });
    match result {
        Ok((records, ttl)) => super::dns::SrvLookup::Found(records, ttl),
        Err(true) => super::dns::SrvLookup::None,
        Err(false) => super::dns::SrvLookup::Unknown,
    }
}

/// A- (`v6 = false`) bzw. AAAA-Adressen samt TTL (blockierend).
pub fn lookup_addrs(host: &str, v6: bool) -> super::dns::AddrLookup {
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
    use windows::Win32::NetworkManagement::Dns::{DNS_TYPE_A, DNS_TYPE_AAAA};
    let result = if v6 {
        dns_query(host, DNS_TYPE_AAAA, |record| {
            // SAFETY: Eintrag vom Typ AAAA – 16 Bytes in Netzwerk-Reihenfolge.
            let bytes = unsafe { record.Data.AAAA.Ip6Address.IP6Byte };
            Some(IpAddr::V6(Ipv6Addr::from(bytes)))
        })
    } else {
        dns_query(host, DNS_TYPE_A, |record| {
            // SAFETY: Eintrag vom Typ A – die Adresse liegt in Netzwerk-Reihenfolge im Speicher.
            let raw = unsafe { record.Data.A.IpAddress };
            Some(IpAddr::V4(Ipv4Addr::from(raw.to_ne_bytes())))
        })
    };
    match result {
        Ok((ips, ttl)) => super::dns::AddrLookup::Found(ips, ttl),
        Err(true) => super::dns::AddrLookup::None,
        Err(false) => super::dns::AddrLookup::Unknown,
    }
}

// --- Token-Verschlüsselung (DPAPI) -------------------------------------------------------------

pub mod secret {
    //! Tokens über die Windows-DPAPI: Die Daten sind an das Windows-Benutzerkonto
    //! gebunden – eine kopierte `accounts.json` ist auf einem anderen Rechner
    //! oder unter einem anderen Benutzer wertlos.

    use std::path::Path;

    use windows::Win32::Foundation::{HLOCAL, LocalFree};
    use windows::Win32::Security::Cryptography::{
        CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
    };
    use windows::core::PCWSTR;

    /// Zusätzliche Entropie: Andere Programme desselben Benutzers können die
    /// Blobs nicht einfach mit einem nackten `CryptUnprotectData` öffnen.
    const ENTROPY: &[u8] = b"TRS-Launcher/accounts/v1";

    /// Wie die Tokens geschützt sind (für die Anzeige).
    pub fn protection() -> &'static str {
        "dpapi"
    }

    /// DPAPI braucht keinen eigenen Schlüssel.
    pub fn init(_root: &Path) {}

    fn blob(data: &[u8]) -> CRYPT_INTEGER_BLOB {
        CRYPT_INTEGER_BLOB { cbData: data.len() as u32, pbData: data.as_ptr().cast_mut() }
    }

    /// Übernimmt den von der DPAPI allokierten Puffer und gibt ihn frei.
    ///
    /// # Safety
    /// `out` muss von `CryptProtectData`/`CryptUnprotectData` befüllt worden sein.
    unsafe fn take(out: CRYPT_INTEGER_BLOB) -> Vec<u8> {
        if out.pbData.is_null() {
            return Vec::new();
        }
        // SAFETY: Die DPAPI garantiert `cbData` gültige Bytes ab `pbData`; der
        // Puffer stammt aus LocalAlloc und gehört nach dem Aufruf uns.
        unsafe {
            let bytes = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
            LocalFree(Some(HLOCAL(out.pbData.cast())));
            bytes
        }
    }

    pub fn encrypt(plain: &[u8]) -> Result<Vec<u8>, String> {
        let input = blob(plain);
        let entropy = blob(ENTROPY);
        let mut out = CRYPT_INTEGER_BLOB::default();
        // SAFETY: Alle Zeiger verweisen auf lebende Puffer; `out` wird von der API befüllt.
        unsafe {
            CryptProtectData(&input, PCWSTR::null(), Some(&entropy), None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut out)
                .map_err(|e| format!("DPAPI-Verschlüsselung fehlgeschlagen: {e}"))?;
            Ok(take(out))
        }
    }

    pub fn decrypt(cipher: &[u8]) -> Option<Vec<u8>> {
        let input = blob(cipher);
        let entropy = blob(ENTROPY);
        let mut out = CRYPT_INTEGER_BLOB::default();
        // SAFETY: wie oben.
        unsafe {
            CryptUnprotectData(&input, None, Some(&entropy), None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut out).ok()?;
            Some(take(out))
        }
    }
}

/// Läuft gerade eine Vollbild-Anwendung (Spiel im Vollbild, Präsentation)
/// oder hat der Nutzer „Nicht stören“/Fokus-Assistent an? Dann bleiben
/// Benachrichtigungen des Launchers still (wie bei Windows selbst).
pub fn quiet_hours() -> bool {
    use windows::Win32::UI::Shell::{
        QUNS_BUSY, QUNS_PRESENTATION_MODE, QUNS_QUIET_TIME, QUNS_RUNNING_D3D_FULL_SCREEN, SHQueryUserNotificationState,
    };
    // SAFETY: reine Abfrage ohne Zeiger-Argumente außer dem Ergebnis.
    match unsafe { SHQueryUserNotificationState() } {
        Ok(state) => matches!(state, QUNS_BUSY | QUNS_RUNNING_D3D_FULL_SCREEN | QUNS_PRESENTATION_MODE | QUNS_QUIET_TIME),
        Err(_) => false,
    }
}
