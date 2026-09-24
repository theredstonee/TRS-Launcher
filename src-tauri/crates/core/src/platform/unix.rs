//! Linux-Teil von [`crate::platform`] (auch für andere Unix-Systeme):
//! Prozessgruppen statt Job-Objekten, `/proc` statt Prozess-Handles,
//! XDG-Papierkorb, PRIME-Umgebungsvariablen für die GPU-Wahl und der
//! Secret Service (Schlüsselbund) für die Token-Verschlüsselung.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use crate::{Error, Result};

// --- Prozesse ----------------------------------------------------------------------

/// Eigene Prozessgruppe: Strg+C im Terminal des Launchers trifft das Spiel
/// nicht, und „Beenden“ erreicht auch Wrapper-Kinder (`gamemoderun`, …).
/// Kinder leben unter Linux ohnehin weiter, wenn der Launcher endet.
pub fn detach(cmd: &mut std::process::Command) {
    use std::os::unix::process::CommandExt;
    cmd.process_group(0);
}

/// Unter Linux gibt es keine Konsolenfenster.
pub fn hide_console(_cmd: &mut tokio::process::Command) {}

/// Befehl über die System-Shell (`/bin/sh -c "…"`).
pub fn shell_command(command: &str) -> tokio::process::Command {
    let mut cmd = tokio::process::Command::new("/bin/sh");
    cmd.arg("-c").arg(command);
    cmd
}

/// Zustand und Startzeit aus `/proc/<pid>/stat` (Startzeit in Ticks seit dem
/// Systemstart – unterscheidet einen wiederverwendeten PID).
fn proc_stat(pid: u32) -> Option<(char, u64)> {
    let text = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    // Der Programmname steht in Klammern und darf selbst Leerzeichen/Klammern enthalten.
    let rest = &text[text.rfind(')')? + 1..];
    let fields: Vec<&str> = rest.split_whitespace().collect();
    let state = fields.first()?.chars().next()?;
    // Feld 22 der Manpage = Index 19 nach dem Namen.
    let start = fields.get(19)?.parse().ok()?;
    Some((state, start))
}

/// Laufender Prozess. Eigene Kinder behalten ihr `Child` (für den Exit-Code),
/// wiedergefundene werden über `/proc` beobachtet.
pub struct ProcessHandle {
    pid: u32,
    start: Option<u64>,
    child: Mutex<Option<std::process::Child>>,
    own_child: bool,
}

impl ProcessHandle {
    pub fn open(pid: u32) -> Option<Self> {
        let (state, start) = proc_stat(pid)?;
        if state == 'Z' || pid == 0 {
            return None;
        }
        Some(Self { pid, start: Some(start), child: Mutex::new(None), own_child: false })
    }

    pub fn from_child(child: std::process::Child) -> Option<Self> {
        let pid = child.id();
        let start = proc_stat(pid).map(|(_, s)| s);
        Some(Self { pid, start, child: Mutex::new(Some(child)), own_child: true })
    }

    pub fn creation_time(&self) -> Option<u64> {
        self.start
    }

    pub fn is_alive(&self) -> bool {
        match proc_stat(self.pid) {
            Some((state, start)) => state != 'Z' && self.start.is_none_or(|s| s == start),
            None => false,
        }
    }

    /// Blockiert bis zum Prozessende. Den Exit-Code gibt es nur für eigene
    /// Kinder; wiedergefundene Prozesse (nach einem Launcher-Neustart) kann
    /// nur ihr Elternprozess abholen.
    pub fn wait(&self) -> Option<i32> {
        let child = self.child.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take();
        if let Some(mut child) = child {
            use std::os::unix::process::ExitStatusExt;
            let status = child.wait().ok()?;
            return status.code().or_else(|| status.signal().map(|s| 128 + s));
        }
        while self.is_alive() {
            std::thread::sleep(Duration::from_millis(500));
        }
        None
    }

    pub fn exit_code_known(&self) -> bool {
        self.own_child
    }

    /// Beendet die ganze Prozessgruppe: erst freundlich (SIGTERM, Java räumt
    /// auf), nach 5 s hart (SIGKILL).
    pub fn terminate(&self) -> bool {
        if !self.is_alive() {
            return false;
        }
        let Ok(pid) = libc::pid_t::try_from(self.pid) else { return false };
        // SAFETY: reine Systemaufrufe mit Zahlen; der PID wurde oben als
        // unser (noch lebender) Prozess bestätigt.
        let group = unsafe { libc::getpgid(pid) } == pid;
        let target = if group { -pid } else { pid };
        // SAFETY: wie oben.
        if unsafe { libc::kill(target, libc::SIGTERM) } != 0 {
            return false;
        }
        let (me_pid, start) = (self.pid, self.start);
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_secs(5));
            // Nur, wenn es noch derselbe Prozess ist.
            let same = proc_stat(me_pid).is_some_and(|(state, s)| state != 'Z' && start.is_none_or(|st| st == s));
            if same {
                // SAFETY: wie oben.
                unsafe { libc::kill(target, libc::SIGKILL) };
            }
        });
        true
    }
}

// --- Grafikkarte ----------------------------------------------------------------------

/// Anzahl der Grafikkarten laut `/sys/class/drm` (`card0`, `card1`, …).
fn gpu_count() -> usize {
    std::fs::read_dir("/sys/class/drm")
        .map(|entries| {
            entries
                .flatten()
                .filter(|e| {
                    let name = e.file_name();
                    let name = name.to_string_lossy();
                    name.strip_prefix("card").is_some_and(|n| !n.is_empty() && n.chars().all(|c| c.is_ascii_digit()))
                })
                .count()
        })
        .unwrap_or(0)
}

/// PRIME-Render-Offload: Bei zwei Grafikchips rechnet die dedizierte GPU.
/// NVIDIA-Treiber brauchen eigene Variablen, Mesa (AMD/Intel) `DRI_PRIME`.
/// Mit nur einer GPU ändert sich nichts.
pub fn dedicated_gpu_env(_program: &Path) -> Vec<(String, String)> {
    let nvidia = Path::new("/proc/driver/nvidia/version").exists();
    gpu_env_for(gpu_count(), nvidia)
}

fn gpu_env_for(gpus: usize, nvidia: bool) -> Vec<(String, String)> {
    let pairs: &[(&str, &str)] = match (gpus, nvidia) {
        (0 | 1, _) => &[],
        (_, true) => &[
            ("__NV_PRIME_RENDER_OFFLOAD", "1"),
            ("__GLX_VENDOR_LIBRARY_NAME", "nvidia"),
            ("__VK_LAYER_NV_optimus", "NVIDIA_only"),
        ],
        (_, false) => &[("DRI_PRIME", "1")],
    };
    pairs.iter().map(|(k, v)| ((*k).to_owned(), (*v).to_owned())).collect()
}

// --- Systemname -------------------------------------------------------------------------

/// `PRETTY_NAME` aus einer os-release-Datei.
fn pretty_name(os_release: &str) -> Option<String> {
    let value = os_release.lines().find_map(|l| l.trim().strip_prefix("PRETTY_NAME="))?;
    let value = value.trim().trim_matches(['"', '\'']).trim();
    let ok = !value.is_empty() && value.len() <= 64 && value.chars().all(|c| !c.is_control());
    ok.then(|| value.to_owned())
}

/// z. B. „Arch Linux (Kernel 6.10.2-arch1-1)“. Im Flatpak zählt das
/// Host-System (`/run/host/os-release`), nicht die Laufzeit.
pub fn os_description() -> String {
    let name = ["/run/host/os-release", "/etc/os-release", "/usr/lib/os-release"]
        .iter()
        .find_map(|p| std::fs::read_to_string(p).ok().as_deref().and_then(pretty_name))
        .unwrap_or_else(|| "Linux".to_owned());
    let kernel = std::fs::read_to_string("/proc/sys/kernel/osrelease").ok().map(|k| k.trim().to_owned());
    match kernel.filter(|k| !k.is_empty() && k.len() <= 64 && k.chars().all(|c| c.is_ascii_graphic())) {
        Some(k) => format!("{name} (Kernel {k})"),
        None => name,
    }
}

// --- Papierkorb (freedesktop.org Trash) ------------------------------------------------------

fn data_home() -> Option<PathBuf> {
    std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share")))
}

/// Prozent-Kodierung für `Path=` in `.trashinfo` (RFC 2396, `/` bleibt).
fn trash_path_encode(path: &Path) -> String {
    use std::os::unix::ffi::OsStrExt;
    let mut out = String::new();
    for &b in path.as_os_str().as_bytes() {
        if b.is_ascii_alphanumeric() || b"/-_.~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Verschiebt eine Datei in den Papierkorb des Benutzers
/// (`~/.local/share/Trash`), so dass Dateimanager sie wiederherstellen können.
pub fn move_to_trash(path: &Path) -> Result<()> {
    let trash = data_home().ok_or_else(|| Error::Internal("Kein Benutzerordner".into()))?.join("Trash");
    move_to_trash_in(path, &trash)
}

fn move_to_trash_in(path: &Path, trash: &Path) -> Result<()> {
    use std::io::Write;

    let absolute = std::fs::canonicalize(path).map_err(|e| Error::io(path, e))?;
    let name = absolute.file_name().ok_or_else(|| Error::Internal("Kein Dateiname".into()))?.to_owned();
    let (files, info) = (trash.join("files"), trash.join("info"));
    for dir in [&files, &info] {
        std::fs::create_dir_all(dir).map_err(|e| Error::io(dir, e))?;
    }
    let date = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S");
    let content = format!("[Trash Info]\nPath={}\nDeletionDate={date}\n", trash_path_encode(&absolute));
    for n in 1..1000 {
        let entry = if n == 1 {
            name.clone()
        } else {
            let mut s = name.clone();
            s.push(format!(".{n}"));
            s
        };
        let mut info_name = entry.clone();
        info_name.push(".trashinfo");
        let info_file = info.join(&info_name);
        // Die .trashinfo-Datei reserviert den Namen (create_new = atomar).
        let Ok(mut file) = std::fs::OpenOptions::new().write(true).create_new(true).open(&info_file) else { continue };
        if files.join(&entry).exists() {
            drop(file);
            let _ = std::fs::remove_file(&info_file);
            continue;
        }
        file.write_all(content.as_bytes()).map_err(|e| Error::io(&info_file, e))?;
        let target = files.join(&entry);
        let moved = std::fs::rename(&absolute, &target).or_else(|_| {
            // Anderes Dateisystem: kopieren und dann löschen.
            std::fs::copy(&absolute, &target).and_then(|_| std::fs::remove_file(&absolute))
        });
        return moved.map_err(|e| {
            let _ = std::fs::remove_file(&info_file);
            Error::io(path, e)
        });
    }
    Err(Error::Internal("Papierkorb: kein freier Name".into()))
}

// --- Java-Installationen ------------------------------------------------------------------

/// Ordner, in denen Java-Installationen (je ein Unterordner) liegen:
/// Paketverwaltung (`/usr/lib/jvm`), `/opt`, SDKMAN, `~/.jdks` (IntelliJ).
pub fn java_search_dirs() -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = ["/usr/lib/jvm", "/usr/lib64/jvm", "/usr/java", "/opt/java", "/opt/jdk", "/opt"]
        .iter()
        .map(PathBuf::from)
        .collect();
    if let Some(home) = std::env::var_os("HOME").map(PathBuf::from) {
        out.push(home.join(".jdks"));
        out.push(home.join(".sdkman/candidates/java"));
        out.push(home.join(".local/share/jdks"));
    }
    out
}

// --- DNS ------------------------------------------------------------------------------------

pub fn lookup_srv(name: &str) -> Option<(String, u16)> {
    super::dns::lookup_srv(name)
}

// --- Token-Verschlüsselung --------------------------------------------------------------------

pub mod secret {
    //! Tokens werden mit ChaCha20-Poly1305 verschlüsselt. Der Schlüssel liegt
    //! im Schlüsselbund des Systems (Secret Service: GNOME Keyring, KWallet,
    //! KeePassXC …). Gibt es keinen, landet er in `<daten>/.token-key` mit
    //! Rechten 0600 – dann schützen nur die Dateirechte, und die Oberfläche
    //! weist darauf hin.
    //!
    //! Beim Entschlüsseln werden alle bekannten Schlüssel probiert: War der
    //! Schlüsselbund bei einem Start gesperrt, bleiben die damit (per Datei)
    //! verschlüsselten Tokens trotzdem lesbar.

    use std::path::Path;
    use std::sync::OnceLock;

    use chacha20poly1305::aead::{Aead, KeyInit, Payload};
    use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};

    const SERVICE: &str = "TRS-Launcher";
    const USER: &str = "token-key";
    const KEY_FILE: &str = ".token-key";
    const AAD: &[u8] = b"TRS-Launcher/accounts/v1";
    const NONCE_LEN: usize = 12;

    struct Keys {
        /// Zum Verschlüsseln (der erste) und zum Entschlüsseln (alle).
        keys: Vec<[u8; 32]>,
        protection: &'static str,
    }

    static KEYS: OnceLock<Keys> = OnceLock::new();

    /// `keyring` (Schlüsselbund), `file` (nur Dateirechte) oder `none`
    /// (noch nicht eingerichtet).
    pub fn protection() -> &'static str {
        KEYS.get().map_or("none", |k| k.protection)
    }

    fn random_key() -> Option<[u8; 32]> {
        let mut key = [0u8; 32];
        getrandom::fill(&mut key).ok()?;
        Some(key)
    }

    fn encode(key: &[u8; 32]) -> String {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.encode(key)
    }

    fn decode(text: &str) -> Option<[u8; 32]> {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.decode(text.trim()).ok()?.try_into().ok()
    }

    fn read_file_key(file: &Path) -> Option<[u8; 32]> {
        decode(&std::fs::read_to_string(file).ok()?)
    }

    fn write_file_key(file: &Path, key: &[u8; 32]) -> std::io::Result<()> {
        use std::io::Write;
        use std::os::unix::fs::OpenOptionsExt;
        if let Some(dir) = file.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let tmp = file.with_extension("tmp");
        let _ = std::fs::remove_file(&tmp);
        let mut out = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp)?;
        out.write_all(encode(key).as_bytes())?;
        out.sync_all()?;
        std::fs::rename(&tmp, file)
    }

    /// Schlüsselbund-Zugriff. `Err` = kein Secret Service erreichbar.
    fn keyring_entry() -> Result<keyring_core::Entry, String> {
        use keyring_core::api::CredentialStoreApi;
        let store = zbus_secret_service_keyring_store::Store::new().map_err(|e| e.to_string())?;
        let label = std::collections::HashMap::from([("label", "TRS Launcher – Anmeldeschlüssel")]);
        store.build(SERVICE, USER, Some(&label)).map_err(|e| e.to_string())
    }

    enum Keyring {
        Found([u8; 32]),
        Empty(keyring_core::Entry),
        Unavailable,
    }

    fn read_keyring() -> Keyring {
        // Tests dürfen den echten Schlüsselbund des Benutzers nie anfassen.
        if cfg!(test) || std::env::var_os("TRS_LAUNCHER_NO_KEYRING").is_some() {
            return Keyring::Unavailable;
        }
        let entry = match keyring_entry() {
            Ok(e) => e,
            Err(e) => {
                tracing::info!("Kein Schlüsselbund (Secret Service) erreichbar: {e}");
                return Keyring::Unavailable;
            }
        };
        match entry.get_password() {
            Ok(text) => match decode(&text) {
                Some(key) => Keyring::Found(key),
                None => {
                    tracing::warn!("Schlüssel im Schlüsselbund ist unlesbar – wird ersetzt");
                    Keyring::Empty(entry)
                }
            },
            Err(keyring_core::Error::NoEntry) => Keyring::Empty(entry),
            Err(e) => {
                tracing::warn!("Schlüsselbund nicht lesbar: {e}");
                Keyring::Unavailable
            }
        }
    }

    /// Lädt oder erzeugt den Schlüssel. Blockiert (D-Bus, evtl. Entsperr-Dialog
    /// des Schlüsselbunds) – nur beim Start in `spawn_blocking` aufrufen.
    pub fn init(root: &Path) {
        if KEYS.get().is_some() {
            return;
        }
        let file = root.join(KEY_FILE);
        let file_key = read_file_key(&file);
        let keys = match read_keyring() {
            Keyring::Found(key) => {
                let mut keys = vec![key];
                keys.extend(file_key.filter(|f| *f != key));
                Keys { keys, protection: "keyring" }
            }
            Keyring::Empty(entry) => {
                // Vorhandenen Datei-Schlüssel in den Schlüsselbund übernehmen, sonst neu.
                let key = file_key.or_else(random_key);
                match key.map(|k| (k, entry.set_password(&encode(&k)))) {
                    Some((k, Ok(()))) => {
                        if file_key.is_some() && std::fs::remove_file(&file).is_ok() {
                            tracing::info!("Anmeldeschlüssel aus der Datei in den Schlüsselbund verschoben");
                        }
                        Keys { keys: vec![k], protection: "keyring" }
                    }
                    Some((k, Err(e))) => {
                        tracing::warn!("Schlüsselbund nicht beschreibbar ({e}) – Schlüssel liegt in einer Datei");
                        file_fallback(&file, Some(k))
                    }
                    None => file_fallback(&file, None),
                }
            }
            Keyring::Unavailable => file_fallback(&file, file_key),
        };
        let _ = KEYS.set(keys);
    }

    fn file_fallback(file: &Path, key: Option<[u8; 32]>) -> Keys {
        let existing = read_file_key(file);
        let Some(key) = existing.or(key).or_else(random_key) else {
            return Keys { keys: Vec::new(), protection: "none" };
        };
        if existing != Some(key)
            && let Err(e) = write_file_key(file, &key)
        {
            tracing::error!("Anmeldeschlüssel konnte nicht gespeichert werden: {e}");
        }
        Keys { keys: vec![key], protection: "file" }
    }

    fn keys() -> Option<&'static Keys> {
        #[cfg(test)]
        {
            // Tests laufen ohne Launcher::init – fester Schlüssel nur im Speicher.
            if KEYS.get().is_none() {
                let _ = KEYS.set(Keys { keys: vec![[7u8; 32]], protection: "file" });
            }
        }
        KEYS.get().filter(|k| !k.keys.is_empty())
    }

    pub fn encrypt(plain: &[u8]) -> Result<Vec<u8>, String> {
        let keys = keys().ok_or("Kein Anmeldeschlüssel eingerichtet")?;
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&keys.keys[0]));
        let mut nonce = [0u8; NONCE_LEN];
        getrandom::fill(&mut nonce).map_err(|e| e.to_string())?;
        let sealed = cipher
            .encrypt(Nonce::from_slice(&nonce), Payload { msg: plain, aad: AAD })
            .map_err(|_| "Verschlüsselung fehlgeschlagen".to_owned())?;
        Ok([nonce.as_slice(), &sealed].concat())
    }

    pub fn decrypt(data: &[u8]) -> Option<Vec<u8>> {
        if data.len() <= NONCE_LEN {
            return None;
        }
        let (nonce, sealed) = data.split_at(NONCE_LEN);
        keys()?.keys.iter().find_map(|key| {
            ChaCha20Poly1305::new(Key::from_slice(key))
                .decrypt(Nonce::from_slice(nonce), Payload { msg: sealed, aad: AAD })
                .ok()
        })
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn key_file_is_private_and_roundtrips() {
            use std::os::unix::fs::PermissionsExt;
            let dir = tempfile::tempdir().unwrap();
            let file = dir.path().join("sub").join(KEY_FILE);
            let first = file_fallback(&file, None);
            assert_eq!(first.protection, "file");
            let mode = std::fs::metadata(&file).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600);
            // Zweiter Start liest denselben Schlüssel.
            let second = file_fallback(&file, None);
            assert_eq!(first.keys, second.keys);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gpu_env_depends_on_hardware() {
        assert!(gpu_env_for(1, true).is_empty());
        assert_eq!(gpu_env_for(2, false), vec![("DRI_PRIME".to_owned(), "1".to_owned())]);
        assert!(gpu_env_for(2, true).iter().any(|(k, _)| k == "__NV_PRIME_RENDER_OFFLOAD"));
    }

    #[test]
    fn os_release_names() {
        assert_eq!(pretty_name("NAME=\"Arch Linux\"\nPRETTY_NAME=\"Arch Linux\"\n").as_deref(), Some("Arch Linux"));
        assert_eq!(pretty_name("PRETTY_NAME='Ubuntu 24.04.1 LTS'").as_deref(), Some("Ubuntu 24.04.1 LTS"));
        assert_eq!(pretty_name("ID=x"), None);
        assert!(os_description().len() > 3);
    }

    #[test]
    fn trash_moves_file_and_writes_info() {
        let dir = tempfile::tempdir().unwrap();
        let trash = dir.path().join("Trash");
        for _ in 0..2 {
            let shot = dir.path().join("2024 shot #1.png");
            std::fs::write(&shot, b"png").unwrap();
            move_to_trash_in(&shot, &trash).unwrap();
            assert!(!shot.exists());
        }
        assert!(trash.join("files/2024 shot #1.png").is_file());
        assert!(trash.join("files/2024 shot #1.png.2").is_file(), "gleicher Name bekommt ein Suffix");
        let info = std::fs::read_to_string(trash.join("info/2024 shot #1.png.trashinfo")).unwrap();
        assert!(info.starts_with("[Trash Info]\nPath=/"));
        assert!(info.contains("2024%20shot%20%231.png"), "{info}");
        assert!(info.contains("DeletionDate="));
    }

    #[test]
    fn own_child_is_tracked_and_terminated() {
        let mut cmd = std::process::Command::new("/bin/sh");
        cmd.arg("-c").arg("sleep 30");
        detach(&mut cmd);
        let handle = ProcessHandle::from_child(cmd.spawn().unwrap()).unwrap();
        assert!(handle.is_alive());
        assert!(handle.creation_time().is_some());
        let reopened = ProcessHandle::open(handle.pid).unwrap();
        assert_eq!(reopened.creation_time(), handle.creation_time());
        assert!(!reopened.exit_code_known());
        assert!(handle.terminate());
        assert_eq!(handle.wait(), Some(128 + libc::SIGTERM));
        assert!(!handle.is_alive());
        assert!(!reopened.is_alive());
    }
}
