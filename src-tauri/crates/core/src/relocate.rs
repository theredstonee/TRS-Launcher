//! Umziehen: den ganzen Datenordner an einen anderen Ort und einzelne Instanzen
//! an einen eigenen Speicherort (z. B. `D:\Minecraft\Modpack`) und zurück.
//!
//! Ablauf immer: Ziel prüfen (leer oder neu, beschreibbar, kein Systemordner,
//! keine Überschneidung, genug Platz) → kopieren (Aufgabe, abbrechbar) → Kopie
//! prüfen (Anzahl, Größe, SHA-256 kleiner Dateien) → umschalten. Das Original
//! verschwindet erst danach – beim Datenordner sogar erst nach einer Bestätigung
//! und beim nächsten Start (siehe [`crate::data_location`]).

use std::path::{Component, Path, PathBuf};

use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::data_location::{self, DataLocation};
use crate::task::TaskControl;
use crate::{Error, LAUNCHER_NAME, Launcher, Result, fsutil};

const MAX_PATH_LEN: usize = 1024;
/// Dateien bis zu dieser Größe werden nach dem Kopieren per SHA-256 verglichen.
const VERIFY_HASH_MAX: u64 = 1024 * 1024;
/// Größere JSON-Dateien werden beim Pfad-Umschreiben übersprungen.
const MAX_REWRITE_JSON: u64 = 8 * 1024 * 1024;
const COPY_BUF: usize = 1024 * 1024;
/// Reserve über die reine Datengröße hinaus.
const FREE_MARGIN: u64 = 256 * 1024 * 1024;
/// Steht während eines Datenordner-Umzugs in `preparing` – keine Spielstarts mehr bis zum Neustart.
pub(crate) const DATA_MOVE_GUARD: &str = "\0data-move";
/// Puffer laufender Clip-Sitzungen – wird beim Start ohnehin geleert.
const SKIP_IN_DATA_DIR: &[&str] = &["cache/clip-buffer"];
/// Diese Dateien enthalten nur verschlüsselte Tokens – nie umschreiben.
const NEVER_REWRITE: &[&str] = &["accounts.json", "trs-api.json", "firewall.json"];

/// Zusammenfassung vor dem Umzug (Größe, freier Platz).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MovePlan {
    /// Endgültiger Zielordner (bei einem nicht leeren Ordner ein Unterordner darin).
    pub target: String,
    pub bytes: u64,
    pub files: u64,
    /// Verknüpfungen (Symlinks/Junctions) – werden beim Kopieren nicht mitgenommen.
    pub links: u64,
    pub free: Option<u64>,
    /// Gleiches Laufwerk: Instanzen werden dann nur umbenannt.
    pub same_volume: bool,
    pub enough_space: bool,
}

/// Wo eine Instanz liegt.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceLocation {
    pub path: String,
    pub custom: bool,
    pub default_path: String,
}

// --- Ziel prüfen --------------------------------------------------------------------

fn invalid() -> Error {
    Error::validation(crate::msg!("relocate.invalidPath", "Dieser Ordner kann nicht verwendet werden."))
}

/// Prüft den gewählten Ordner und liefert das endgültige Ziel. Ist er nicht leer
/// (oder ein Laufwerk), wird `sub` darin verwendet. `current`: was umzieht;
/// `forbidden`: Ordner, in denen das Ziel nicht liegen darf (und umgekehrt);
/// `protected`: Systemordner.
pub(crate) fn check_target(raw: &str, current: &Path, sub: &str, forbidden: &[PathBuf], protected: &[PathBuf]) -> Result<PathBuf> {
    let raw = raw.trim();
    if raw.is_empty() || raw.len() > MAX_PATH_LEN || raw.chars().any(char::is_control) {
        return Err(invalid());
    }
    if cfg!(windows) && (raw.starts_with("\\\\") || raw.starts_with("//")) {
        return Err(Error::validation(crate::msg!("relocate.network", "Netzwerkpfade werden nicht unterstützt.")));
    }
    let path = PathBuf::from(raw);
    if !path.is_absolute() {
        return Err(Error::validation(crate::msg!("relocate.notAbsolute", "Bitte einen vollständigen Ordnerpfad wählen.")));
    }
    if path.components().any(|c| matches!(c, Component::ParentDir | Component::CurDir)) {
        return Err(invalid());
    }
    let mut target = path;
    if target.parent().is_none() || is_non_empty_dir(&target) {
        target = target.join(sub);
    }
    match std::fs::metadata(&target) {
        Ok(meta) if !meta.is_dir() => {
            return Err(Error::validation(crate::msg!("relocate.notADirectory", "Am Ziel liegt eine Datei mit diesem Namen.")));
        }
        Ok(_) if is_non_empty_dir(&target) => {
            return Err(Error::validation(crate::msg!("relocate.notEmpty", "Der Zielordner ist nicht leer.")));
        }
        _ => {}
    }
    let t = normalize(&target);
    if protected.iter().map(|p| normalize(p)).any(|p| t.starts_with(&p)) || is_special_folder(&t) {
        return Err(Error::validation(crate::msg!("relocate.protected", "Systemordner können nicht verwendet werden.")));
    }
    let c = normalize(current);
    if t.starts_with(&c) || c.starts_with(&t) {
        return Err(Error::validation(crate::msg!("relocate.overlap", "Ziel und aktueller Ordner dürfen nicht ineinander liegen.")));
    }
    for f in forbidden.iter().map(|f| normalize(f)) {
        if t.starts_with(&f) || f.starts_with(&t) {
            return Err(Error::validation(crate::msg!(
                "relocate.insideLauncher",
                "Das Ziel darf nicht im Datenordner des Launchers oder in einer anderen Instanz liegen."
            )));
        }
    }
    Ok(target)
}

fn is_non_empty_dir(path: &Path) -> bool {
    std::fs::read_dir(path).is_ok_and(|mut d| d.next().is_some())
}

/// Absoluter Pfad mit aufgelösten Verknüpfungen (vorhandener Teil) – für Vergleiche.
/// Unter Windows ohne `\\?\`-Präfix und in Kleinbuchstaben.
pub(crate) fn normalize(path: &Path) -> PathBuf {
    let mut rest = Vec::new();
    let mut base = path;
    let resolved = loop {
        if let Ok(canonical) = std::fs::canonicalize(base) {
            break canonical;
        }
        match (base.parent(), base.file_name()) {
            (Some(parent), Some(name)) => {
                rest.push(name.to_owned());
                base = parent;
            }
            _ => break base.to_path_buf(),
        }
    };
    let mut out = resolved;
    for name in rest.into_iter().rev() {
        out.push(name);
    }
    if !cfg!(windows) {
        return out;
    }
    let text = out.to_string_lossy().replace('/', "\\");
    let text = if let Some(unc) = text.strip_prefix(r"\\?\UNC\") {
        format!(r"\\{unc}")
    } else {
        text.strip_prefix(r"\\?\").map(str::to_owned).unwrap_or(text)
    };
    PathBuf::from(text.to_lowercase())
}

/// Papierkorb und Systemdaten jedes Laufwerks (Windows).
fn is_special_folder(normalized: &Path) -> bool {
    normalized.components().nth(2).is_some_and(|c| {
        let name = c.as_os_str().to_string_lossy().to_lowercase();
        cfg!(windows) && (name == "$recycle.bin" || name == "system volume information")
    })
}

/// Systemordner, in die nie etwas umzieht.
pub(crate) fn protected_roots() -> Vec<PathBuf> {
    // Temp-Ordner werden aufgeräumt – nur nicht in Tests (dort liegt alles in Temp).
    let temp: &[&str] = if cfg!(test) { &[] } else if cfg!(windows) { &["TEMP", "TMP"] } else { &["TMPDIR"] };
    let from_env = |names: &[&str]| -> Vec<PathBuf> {
        names.iter().filter_map(std::env::var_os).filter(|v| !v.is_empty()).map(PathBuf::from).collect()
    };
    if cfg!(windows) {
        let mut roots = from_env(&["SystemRoot", "windir", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "ProgramData"]);
        roots.extend(from_env(temp));
        roots
    } else {
        let mut roots: Vec<PathBuf> = [
            "/bin", "/boot", "/dev", "/etc", "/lib", "/lib32", "/lib64", "/libx32", "/proc", "/run", "/sbin", "/sys",
            "/usr", "/var/lib", "/var/cache", "/var/log", "/snap",
        ]
        .iter()
        .map(PathBuf::from)
        .collect();
        if !cfg!(test) {
            roots.extend(["/tmp", "/var/tmp"].map(PathBuf::from));
        }
        roots.extend(from_env(temp));
        roots
    }
}

/// Legt das Ziel probeweise an, schreibt eine Datei und räumt wieder auf.
pub(crate) fn probe_writable(target: &Path) -> Result<()> {
    let not_writable = || Error::validation(crate::msg!("relocate.notWritable", "In diesen Ordner kann nicht geschrieben werden."));
    let mut created = Vec::new();
    for dir in target.ancestors() {
        if dir.exists() {
            break;
        }
        created.push(dir.to_path_buf());
    }
    let cleanup = |created: &[PathBuf]| {
        for dir in created {
            let _ = std::fs::remove_dir(dir);
        }
    };
    if std::fs::create_dir_all(target).is_err() {
        cleanup(&created);
        return Err(not_writable());
    }
    let probe = target.join(format!(".trs-write-test-{}", uuid::Uuid::new_v4().simple()));
    let ok = std::fs::write(&probe, b"ok").is_ok();
    let _ = std::fs::remove_file(&probe);
    cleanup(&created);
    if ok { Ok(()) } else { Err(not_writable()) }
}

/// Liegen beide Pfade auf demselben Laufwerk/Dateisystem?
pub(crate) fn same_volume(a: &Path, b: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let dev = |p: &Path| p.ancestors().find_map(|a| std::fs::metadata(a).ok()).map(|m| m.dev());
        dev(a).is_some() && dev(a) == dev(b)
    }
    #[cfg(not(unix))]
    {
        let prefix = |p: &Path| normalize(p).components().next().map(|c| c.as_os_str().to_owned());
        prefix(a).is_some() && prefix(a) == prefix(b)
    }
}

// --- Kopieren und prüfen -------------------------------------------------------------

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct TreeStats {
    pub files: u64,
    pub dirs: u64,
    pub bytes: u64,
    pub links: u64,
}

/// Zählt Dateien, Ordner und Bytes; `skip` bekommt relative Pfade (mit `/`).
pub(crate) fn scan(dir: &Path, skip: &dyn Fn(&str) -> bool) -> Result<TreeStats> {
    let mut stats = TreeStats::default();
    walk(dir, skip, &mut |_, kind, len| {
        match kind {
            Entry::File => {
                stats.files += 1;
                stats.bytes += len;
            }
            Entry::Dir => stats.dirs += 1,
            Entry::Link => stats.links += 1,
        }
        Ok(())
    })?;
    Ok(stats)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Entry {
    File,
    Dir,
    Link,
}

/// Läuft rekursiv durch `dir`, ohne Verknüpfungen zu folgen. Ordner kommen vor ihrem Inhalt.
fn walk(dir: &Path, skip: &dyn Fn(&str) -> bool, visit: &mut dyn FnMut(&str, Entry, u64) -> Result<()>) -> Result<()> {
    let mut stack = vec![String::new()];
    while let Some(rel) = stack.pop() {
        let abs = if rel.is_empty() { dir.to_path_buf() } else { dir.join(&rel) };
        let read = std::fs::read_dir(&abs).map_err(|e| Error::io(&abs, e))?;
        for entry in read {
            let entry = entry.map_err(|e| Error::io(&abs, e))?;
            let name = entry.file_name().to_string_lossy().into_owned();
            let child = if rel.is_empty() { name } else { format!("{rel}/{name}") };
            if skip(&child) {
                continue;
            }
            let kind = entry.file_type().map_err(|e| Error::io(entry.path(), e))?;
            if kind.is_symlink() {
                visit(&child, Entry::Link, 0)?;
            } else if kind.is_dir() {
                visit(&child, Entry::Dir, 0)?;
                stack.push(child);
            } else if kind.is_file() {
                let len = entry.metadata().map_err(|e| Error::io(entry.path(), e))?.len();
                visit(&child, Entry::File, len)?;
            }
        }
    }
    Ok(())
}

/// Eine kopierte Datei: Größe und – bei kleinen Dateien – SHA-256 der gelesenen Bytes.
#[derive(Debug, Clone)]
pub(crate) struct CopiedFile {
    rel: String,
    len: u64,
    hash: Option<[u8; 32]>,
}

#[derive(Debug, Default)]
pub(crate) struct Copied {
    files: Vec<CopiedFile>,
    bytes: u64,
    links: u64,
}

/// Abbruch und Byte-Stand für Kopieren und Prüfen.
pub(crate) struct Progress<'a> {
    pub control: Option<TaskControl>,
    pub on_bytes: &'a dyn Fn(u64),
}

impl Progress<'_> {
    fn cancelled(&self) -> bool {
        self.control.as_ref().is_some_and(TaskControl::is_cancelled)
    }

    fn check(&self) -> Result<()> {
        if self.cancelled() { Err(Error::Cancelled) } else { Ok(()) }
    }

    fn add(&self, bytes: u64) {
        if let Some(control) = &self.control {
            control.add_done(i64::try_from(bytes).unwrap_or(i64::MAX));
        }
        (self.on_bytes)(bytes);
    }
}

/// Kopiert `src` nach `dst` (Ordner darf fehlen oder leer sein). Verknüpfungen
/// werden übersprungen, Rechte (Linux: ausführbares Java!) und Zeitstempel bleiben.
pub(crate) fn copy_tree(src: &Path, dst: &Path, skip: &dyn Fn(&str) -> bool, progress: &Progress<'_>) -> Result<Copied> {
    std::fs::create_dir_all(dst).map_err(|e| Error::io(dst, e))?;
    let mut copied = Copied::default();
    walk(src, skip, &mut |rel, kind, _| {
        progress.check()?;
        match kind {
            Entry::Link => {
                tracing::warn!("Verknüpfung wird nicht mitkopiert: {rel}");
                copied.links += 1;
            }
            Entry::Dir => {
                let to = dst.join(rel);
                std::fs::create_dir_all(&to).map_err(|e| Error::io(&to, e))?;
            }
            Entry::File => {
                let file = copy_file(&src.join(rel), &dst.join(rel), progress)?;
                copied.bytes += file.0;
                copied.files.push(CopiedFile { rel: rel.to_owned(), len: file.0, hash: file.1 });
            }
        }
        Ok(())
    })?;
    Ok(copied)
}

fn copy_file(from: &Path, to: &Path, progress: &Progress<'_>) -> Result<(u64, Option<[u8; 32]>)> {
    use std::io::{Read, Write};
    let mut input = std::fs::File::open(from).map_err(|e| Error::io(from, e))?;
    let meta = input.metadata().map_err(|e| Error::io(from, e))?;
    let mut hasher = (meta.len() <= VERIFY_HASH_MAX).then(Sha256::new);
    let mut output = std::fs::File::create(to).map_err(|e| Error::io(to, e))?;
    let mut buf = vec![0u8; COPY_BUF.min(usize::try_from(meta.len()).unwrap_or(COPY_BUF).max(8 * 1024))];
    let mut total = 0u64;
    loop {
        progress.check()?;
        let n = input.read(&mut buf).map_err(|e| Error::io(from, e))?;
        if n == 0 {
            break;
        }
        output.write_all(&buf[..n]).map_err(|e| Error::io(to, e))?;
        if let Some(h) = hasher.as_mut() {
            h.update(&buf[..n]);
        }
        total += n as u64;
        progress.add(n as u64);
    }
    output.flush().map_err(|e| Error::io(to, e))?;
    if let Ok(modified) = meta.modified() {
        let _ = output.set_modified(modified);
    }
    drop(output);
    // Rechte übernehmen (Linux: Java muss ausführbar bleiben). Schreibschutz erst zuletzt.
    let _ = std::fs::set_permissions(to, meta.permissions());
    // Größer geworden, während wir lasen (z. B. ein Log)? Dann gilt der Hash nicht.
    let hash = hasher.filter(|_| total <= VERIFY_HASH_MAX).map(|h| h.finalize().into());
    Ok((total, hash))
}

/// Prüft die Kopie: gleiche Anzahl und Größe, jede Datei gleich lang, kleine
/// Dateien mit gleichem SHA-256 wie beim Lesen.
pub(crate) fn verify_copy(dst: &Path, copied: &Copied, progress: &Progress<'_>) -> Result<()> {
    let failed = || Error::validation(crate::msg!("relocate.verifyFailed", "Die Kopie ist unvollständig – es wurde nichts umgestellt."));
    let stats = scan(dst, &|_| false)?;
    if stats.files != copied.files.len() as u64 || stats.bytes != copied.bytes {
        tracing::warn!("Kopie prüfen: {} Dateien/{} Bytes statt {}/{}", stats.files, stats.bytes, copied.files.len(), copied.bytes);
        return Err(failed());
    }
    for file in &copied.files {
        progress.check()?;
        let path = dst.join(&file.rel);
        let len = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(u64::MAX);
        if len != file.len {
            tracing::warn!("Kopie prüfen: Größe von {} weicht ab", file.rel);
            return Err(failed());
        }
        if let Some(expected) = file.hash {
            let bytes = std::fs::read(&path).map_err(|e| Error::io(&path, e))?;
            let actual: [u8; 32] = Sha256::digest(&bytes).into();
            if actual != expected {
                tracing::warn!("Kopie prüfen: Inhalt von {} weicht ab", file.rel);
                return Err(failed());
            }
        }
    }
    Ok(())
}

/// Entfernt eine fehlgeschlagene Kopie. `created`: der Zielordner war neu (sonst bleibt er leer stehen).
fn discard_copy(dst: &Path, created: bool) {
    if created {
        let _ = std::fs::remove_dir_all(dst);
        return;
    }
    let Ok(read) = std::fs::read_dir(dst) else { return };
    for entry in read.flatten() {
        let path = entry.path();
        let _ = if entry.file_type().is_ok_and(|t| t.is_dir()) { std::fs::remove_dir_all(&path) } else { std::fs::remove_file(&path) };
    }
}

// --- Gespeicherte Pfade umschreiben --------------------------------------------------

fn is_sep(c: char) -> bool {
    c == '/' || (cfg!(windows) && c == '\\')
}

fn same_char(a: char, b: char) -> bool {
    if cfg!(windows) { (is_sep(a) && is_sep(b)) || a.to_lowercase().eq(b.to_lowercase()) } else { a == b }
}

/// Ersetzt `old` (als ganzer Pfadanfang, z. B. in `-javaagent:C:\…\agent.jar`) durch `new`.
/// Windows: ohne Groß-/Kleinschreibung, `\` und `/` gleich. `None` = nichts gefunden.
pub(crate) fn rewrite_path_text(text: &str, old: &Path, new: &Path) -> Option<String> {
    let old_text = old.to_string_lossy();
    let old_text = old_text.trim_end_matches(['/', '\\']);
    let new_text = new.to_string_lossy();
    let new_text = new_text.trim_end_matches(['/', '\\']);
    let needle: Vec<char> = old_text.chars().collect();
    if needle.len() < 2 {
        return None;
    }
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut changed = false;
    let mut i = 0;
    while i < chars.len() {
        let end = i + needle.len();
        let starts_clean = i == 0 || !(chars[i - 1].is_alphanumeric() || is_sep(chars[i - 1]) || matches!(chars[i - 1], '.' | '_' | '-'));
        let ends_clean = end == chars.len() || chars.get(end).is_some_and(|c| is_sep(*c));
        if end <= chars.len() && starts_clean && ends_clean && chars[i..end].iter().zip(&needle).all(|(a, b)| same_char(*a, *b)) {
            out.push_str(new_text);
            i = end;
            changed = true;
        } else {
            out.push(chars[i]);
            i += 1;
        }
    }
    changed.then_some(out)
}

fn rewrite_value(value: &mut serde_json::Value, old: &Path, new: &Path) -> bool {
    match value {
        serde_json::Value::String(s) => match rewrite_path_text(s, old, new) {
            Some(replaced) => {
                *s = replaced;
                true
            }
            None => false,
        },
        serde_json::Value::Array(items) => items.iter_mut().fold(false, |changed, v| rewrite_value(v, old, new) | changed),
        serde_json::Value::Object(map) => map.values_mut().fold(false, |changed, v| rewrite_value(v, old, new) | changed),
        _ => false,
    }
}

/// Schreibt alle Pfade unter `old` in einer JSON-Datei auf `new` um. `true` = geändert.
pub(crate) fn rewrite_json_file(file: &Path, old: &Path, new: &Path) -> bool {
    let Ok(meta) = std::fs::metadata(file) else { return false };
    if !meta.is_file() || meta.len() > MAX_REWRITE_JSON {
        return false;
    }
    let Some(mut value) = std::fs::read(file).ok().and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok()) else {
        return false;
    };
    if !rewrite_value(&mut value, old, new) {
        return false;
    }
    match serde_json::to_vec_pretty(&value).map_err(|e| Error::json(file.display().to_string(), e)).and_then(|b| fsutil::write_atomic_sync(file, &b)) {
        Ok(()) => true,
        Err(e) => {
            tracing::warn!("Pfade in {} nicht umgeschrieben: {e}", file.display());
            false
        }
    }
}

fn json_files(dir: &Path) -> Vec<PathBuf> {
    let Ok(read) = std::fs::read_dir(dir) else { return Vec::new() };
    read.flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x.eq_ignore_ascii_case("json")))
        .collect()
}

/// Nach dem Umzug: gespeicherte absolute Pfade unter dem alten Datenordner
/// (Java in Einstellungen und Instanzen, Clip-Ordner, Hooks, Absturzberichte …)
/// auf den neuen umstellen. `instance_dirs`: Instanzen an eigenem Ort (dort in-place).
/// Firewall: Regeln hängen am Java-Pfad – alte Freigaben vergessen, damit sie neu eingetragen werden.
pub(crate) fn rewrite_data_files(new_root: &Path, old_root: &Path, instance_dirs: &[PathBuf]) -> usize {
    let mut files: Vec<PathBuf> = json_files(new_root)
        .into_iter()
        .filter(|p| p.file_name().is_some_and(|n| !NEVER_REWRITE.iter().any(|x| n.eq_ignore_ascii_case(x))))
        .collect();
    let mut dirs: Vec<PathBuf> = std::fs::read_dir(new_root.join("instances"))
        .map(|r| r.flatten().filter(|e| e.file_type().is_ok_and(|t| t.is_dir())).map(|e| e.path()).collect())
        .unwrap_or_default();
    dirs.extend(instance_dirs.iter().cloned());
    for dir in dirs {
        files.extend(json_files(&dir));
        files.extend(json_files(&dir.join("crashes")));
    }
    let changed = files.iter().filter(|f| rewrite_json_file(f, old_root, new_root)).count();
    forget_firewall_rules(new_root, old_root);
    changed
}

fn forget_firewall_rules(root: &Path, old_root: &Path) {
    let file = root.join("firewall.json");
    let Some(mut value) = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok()) else { return };
    let Some(map) = value.as_object_mut() else { return };
    let mut changed = false;
    if let Some(serde_json::Value::Array(allowed)) = map.get_mut("allowed") {
        let before = allowed.len();
        allowed.retain(|p| p.as_str().is_none_or(|s| rewrite_path_text(s, old_root, root).is_none()));
        changed |= allowed.len() != before;
    }
    if let Some(declined) = map.get_mut("declined") {
        changed |= rewrite_value(declined, old_root, root);
    }
    if changed && let Ok(bytes) = serde_json::to_vec_pretty(&value) {
        let _ = fsutil::write_atomic_sync(&file, &bytes);
    }
}

// --- Launcher ------------------------------------------------------------------------

fn skip_in_data_dir(rel: &str) -> bool {
    SKIP_IN_DATA_DIR.iter().any(|s| rel.eq_ignore_ascii_case(s))
}

fn not_enough_space() -> Error {
    Error::validation(crate::msg!("relocate.notEnoughSpace", "Auf dem Ziel-Laufwerk ist nicht genug Platz frei."))
}

pub(crate) fn data_moving() -> Error {
    Error::launch(crate::msg!("relocate.dataMoving", "Der Datenordner wurde verschoben – bitte den Launcher neu starten."))
}

fn plan_for(source: &Path, target: &Path, stats: TreeStats) -> MovePlan {
    let free = crate::clips::library::free_space(target);
    let same = same_volume(source, target);
    MovePlan {
        target: target.display().to_string(),
        bytes: stats.bytes,
        files: stats.files,
        links: stats.links,
        free,
        same_volume: same,
        enough_space: free.is_none_or(|f| f >= stats.bytes.saturating_add(FREE_MARGIN)),
    }
}

async fn blocking<T: Send + 'static>(work: impl FnOnce() -> Result<T> + Send + 'static) -> Result<T> {
    tokio::task::spawn_blocking(work).await.map_err(|e| Error::Internal(e.to_string()))?
}

/// Prozent aus kopierten Bytes (0–95; der Rest ist Prüfen und Umstellen).
fn percent_reporter(total: u64, on_progress: impl Fn(u8) + Send + Sync + 'static) -> impl Fn(u64) + Send + 'static {
    let done = std::sync::atomic::AtomicU64::new(0);
    let last = std::sync::atomic::AtomicU8::new(0);
    move |bytes| {
        let now = done.fetch_add(bytes, std::sync::atomic::Ordering::Relaxed) + bytes;
        let percent = u8::try_from((now.min(total) * 95).checked_div(total).unwrap_or(95)).unwrap_or(95);
        if percent != last.swap(percent, std::sync::atomic::Ordering::Relaxed) {
            on_progress(percent);
        }
    }
}

impl Launcher {
    fn other_instance_dirs(&self, except: Option<&str>) -> Vec<PathBuf> {
        self.paths.custom_locations().into_iter().filter(|(id, _)| Some(id.as_str()) != except).map(|(_, dir)| dir).collect()
    }

    fn data_move_running(&self) -> bool {
        self.is_preparing(DATA_MOVE_GUARD)
    }

    /// Zusammenfassung vor dem Umzug des Datenordners.
    pub async fn data_move_plan(&self, location: &DataLocation, target: &str) -> Result<MovePlan> {
        if !location.movable() {
            return Err(Error::validation(crate::msg!(
                "relocate.notMovable",
                "Im portablen Modus lässt sich der Datenordner nicht verschieben."
            )));
        }
        let old = self.paths.root().to_path_buf();
        let target = check_target(target, &old, LAUNCHER_NAME, &self.other_instance_dirs(None), &protected_roots())?;
        let probe = target.clone();
        blocking(move || probe_writable(&probe)).await?;
        let source = old.clone();
        let stats = blocking(move || scan(&source, &skip_in_data_dir)).await?;
        Ok(plan_for(&old, &target, stats))
    }

    /// Kopiert den Datenordner nach `target`, prüft die Kopie, stellt gespeicherte
    /// Pfade um und schaltet den Zeiger um. Danach startet nichts mehr bis zum Neustart.
    /// Liefert den neuen Ordner.
    pub async fn move_data_dir(&self, location: &DataLocation, target: &str, on_progress: impl Fn(u8) + Send + Sync + 'static) -> Result<String> {
        if self.data_move_running() {
            return Err(data_moving());
        }
        if self.anything_active() {
            return Err(Error::launch(crate::msg!("launcher.stopAllGamesFirst", "Bitte erst alle laufenden Spiele beenden.")));
        }
        let plan = self.data_move_plan(location, target).await?;
        if !plan.enough_space {
            return Err(not_enough_space());
        }
        self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner).insert(DATA_MOVE_GUARD.to_owned());
        let result = self.copy_data_dir(location, PathBuf::from(&plan.target), plan.bytes, on_progress).await;
        if result.is_err() {
            self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner).remove(DATA_MOVE_GUARD);
        }
        result
    }

    async fn copy_data_dir(&self, location: &DataLocation, target: PathBuf, total: u64, on_progress: impl Fn(u8) + Send + Sync + 'static) -> Result<String> {
        let old = self.paths.root().to_path_buf();
        let instance_dirs = self.other_instance_dirs(None);
        let control = crate::task::current();
        if let Some(c) = &control {
            c.add_total(total);
        }
        let location = location.clone();
        let report = std::sync::Arc::new(on_progress);
        let reporter = report.clone();
        blocking(move || {
            let created = !target.exists();
            let percent = percent_reporter(total, move |p| reporter(p));
            let progress = Progress { control, on_bytes: &percent };
            let copied = copy_tree(&old, &target, &skip_in_data_dir, &progress).and_then(|copied| {
                verify_copy(&target, &copied, &progress)?;
                Ok(copied)
            });
            let copied = match copied {
                Ok(c) => c,
                Err(e) => {
                    discard_copy(&target, created);
                    return Err(e);
                }
            };
            let rewritten = rewrite_data_files(&target, &old, &instance_dirs);
            // Marke im alten Ordner: Nur damit darf er später gelöscht werden.
            let marker = old.join(data_location::MOVED_MARKER);
            fsutil::write_atomic_sync(&marker, target.display().to_string().as_bytes())?;
            if let Err(e) = data_location::switch_root(&location, &target) {
                let _ = std::fs::remove_file(&marker);
                discard_copy(&target, created);
                return Err(e);
            }
            tracing::info!(
                "Datenordner kopiert: {} Dateien, {} Bytes, {} Dateien mit Pfaden angepasst → {}",
                copied.files.len(),
                copied.bytes,
                rewritten,
                target.display()
            );
            Ok(target.display().to_string())
        })
        .await
        .inspect(|_| report(100))
    }

    /// Nach dem Umzug: alten Ordner beim nächsten Start löschen (`true`) oder behalten.
    pub fn confirm_data_move(&self, location: &DataLocation, delete_old: bool) -> Result<()> {
        let old = self.paths.root();
        if !self.data_move_running() || !old.join(data_location::MOVED_MARKER).is_file() {
            return Err(Error::validation(crate::msg!("relocate.noMove", "Es wurde nichts verschoben.")));
        }
        data_location::schedule_delete(location, delete_old.then_some(old))
    }

    /// Wo die Instanz liegt.
    pub async fn instance_location(&self, id: &str) -> Result<InstanceLocation> {
        let instance = self.instances.get(id).await?;
        Ok(InstanceLocation {
            path: self.paths.instance_dir(&instance.id).display().to_string(),
            custom: self.paths.instance_location(&instance.id).is_some(),
            default_path: self.paths.default_instance_dir(&instance.id).display().to_string(),
        })
    }

    /// Ziel einer Instanz prüfen: `None` = zurück an den Standardort.
    async fn instance_target(&self, id: &str, target: Option<&str>) -> Result<(PathBuf, PathBuf)> {
        let instance = self.instances.get(id).await?;
        let current = self.paths.instance_dir(&instance.id);
        let dest = match target {
            Some(raw) => {
                let mut forbidden = self.other_instance_dirs(Some(&instance.id));
                forbidden.push(self.paths.root().to_path_buf());
                check_target(raw, &current, &instance.id, &forbidden, &protected_roots())?
            }
            None => {
                if self.paths.instance_location(&instance.id).is_none() {
                    return Err(Error::validation(crate::msg!("relocate.alreadyDefault", "Die Instanz liegt bereits am Standardort.")));
                }
                let dest = self.paths.default_instance_dir(&instance.id);
                if is_non_empty_dir(&dest) {
                    return Err(Error::validation(crate::msg!("relocate.notEmpty", "Der Zielordner ist nicht leer.")));
                }
                dest
            }
        };
        let probe = dest.clone();
        blocking(move || probe_writable(&probe)).await?;
        Ok((current, dest))
    }

    /// Zusammenfassung vor dem Verschieben einer Instanz.
    pub async fn instance_move_plan(&self, id: &str, target: Option<&str>) -> Result<MovePlan> {
        let (current, dest) = self.instance_target(id, target).await?;
        let source = current.clone();
        let stats = blocking(move || scan(&source, &|_| false)).await?;
        let mut plan = plan_for(&current, &dest, stats);
        // Gleiches Laufwerk: nur umbenennen, kein Platz nötig.
        plan.enough_space |= plan.same_volume;
        Ok(plan)
    }

    /// Verschiebt die Instanz (gleiches Laufwerk: umbenennen, sonst kopieren +
    /// prüfen) und löscht danach den alten Ordner.
    pub async fn move_instance(&self, id: &str, target: Option<&str>, on_progress: impl Fn(u8) + Send + Sync + 'static) -> Result<InstanceLocation> {
        let instance = self.instances.get(id).await?;
        if self.data_move_running() {
            return Err(data_moving());
        }
        if self.games.is_running(&instance.id) {
            return Err(Error::launch(crate::msg!("launcher.instanceRunningStopFirst", "Die Instanz läuft gerade – bitte erst beenden.")));
        }
        {
            let mut preparing = self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if !preparing.insert(instance.id.clone()) {
                return Err(Error::launch(crate::msg!("launcher.instanceRunningStopFirst", "Die Instanz läuft gerade – bitte erst beenden.")));
            }
        }
        let result = self.move_instance_inner(&instance.id, target, on_progress).await;
        self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner).remove(&instance.id);
        result?;
        self.instance_location(&instance.id).await
    }

    async fn move_instance_inner(&self, id: &str, target: Option<&str>, on_progress: impl Fn(u8) + Send + Sync + 'static) -> Result<()> {
        let plan = self.instance_move_plan(id, target).await?;
        if !plan.enough_space {
            return Err(not_enough_space());
        }
        let current = self.paths.instance_dir(id);
        let dest = PathBuf::from(&plan.target);
        let control = crate::task::current();
        if let Some(c) = &control {
            c.add_total(plan.bytes);
        }
        let to_default = target.is_none();
        let (from, to) = (current.clone(), dest.clone());
        let total = plan.bytes;
        let same_volume = plan.same_volume;
        let report = std::sync::Arc::new(on_progress);
        let reporter = report.clone();
        let copied = blocking(move || {
            let created = !to.exists();
            // Gleiches Laufwerk: umbenennen (sofort, nichts zu prüfen).
            if same_volume {
                if !created {
                    let _ = std::fs::remove_dir(&to);
                }
                if let Some(parent) = to.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                match std::fs::rename(&from, &to) {
                    Ok(()) => return Ok(false),
                    Err(e) => tracing::info!("Umbenennen nicht möglich ({e}) – kopiere"),
                }
            }
            let created = !to.exists();
            let percent = percent_reporter(total, move |p| reporter(p));
            let progress = Progress { control, on_bytes: &percent };
            let result = copy_tree(&from, &to, &|_| false, &progress).and_then(|copied| verify_copy(&to, &copied, &progress));
            if let Err(e) = result {
                discard_copy(&to, created);
                return Err(e);
            }
            Ok(true)
        })
        .await?;
        // Umschalten, danach das Original löschen.
        let switched = self.paths.set_instance_location(id, (!to_default).then(|| dest.clone()));
        if let Err(e) = switched {
            if copied {
                let to = dest.clone();
                let _ = blocking(move || {
                    discard_copy(&to, true);
                    Ok(())
                })
                .await;
            } else {
                // Umbenannt: zurück an den alten Ort.
                let _ = std::fs::rename(&dest, &current);
            }
            return Err(e);
        }
        if copied {
            let old = current.clone();
            if let Err(e) = blocking(move || std::fs::remove_dir_all(&old).map_err(|e| Error::io(&old, e))).await {
                tracing::warn!("Alter Instanz-Ordner konnte nicht ganz gelöscht werden: {e}");
            }
        }
        report(100);
        tracing::info!("Instanz '{id}' verschoben: {} → {}", current.display(), dest.display());
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(path: &Path, bytes: &[u8]) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, bytes).unwrap();
    }

    fn quiet() -> Progress<'static> {
        Progress { control: None, on_bytes: &|_| {} }
    }

    #[test]
    fn target_must_be_empty_absolute_and_outside() {
        let dir = tempfile::tempdir().unwrap();
        let current = dir.path().join("current");
        std::fs::create_dir_all(current.join("instances")).unwrap();
        let full = dir.path().join("full");
        write(&full.join("x.txt"), b"x");
        let target = |raw: &Path| check_target(&raw.display().to_string(), &current, "TRS-Launcher", &[], &[]);

        // Neu oder leer: genau dieser Ordner.
        assert_eq!(target(&dir.path().join("neu")).unwrap(), dir.path().join("neu"));
        std::fs::create_dir_all(dir.path().join("leer")).unwrap();
        assert_eq!(target(&dir.path().join("leer")).unwrap(), dir.path().join("leer"));
        // Nicht leer: Unterordner darin.
        assert_eq!(target(&full).unwrap(), full.join("TRS-Launcher"));
        write(&full.join("TRS-Launcher").join("a"), b"a");
        assert_eq!(target(&full).unwrap_err().to_user().code, "relocate.notEmpty");
        // Datei statt Ordner.
        write(&dir.path().join("datei"), b"x");
        std::fs::create_dir_all(dir.path().join("hat-datei")).unwrap();
        write(&dir.path().join("hat-datei").join("TRS-Launcher"), b"x");
        assert_eq!(target(&dir.path().join("hat-datei")).unwrap_err().to_user().code, "relocate.notADirectory");

        // Ineinander verschachtelt.
        assert_eq!(target(&current.join("unter")).unwrap_err().to_user().code, "relocate.overlap");
        assert_eq!(target(&current).unwrap_err().to_user().code, "relocate.overlap");
        // Ungültig.
        for raw in ["", "relativ/ordner", "a\nb"] {
            assert!(check_target(raw, &current, "x", &[], &[]).is_err(), "{raw:?}");
        }
        let dotted = format!("{}/a/../b", dir.path().display());
        assert_eq!(check_target(&dotted, &current, "x", &[], &[]).unwrap_err().to_user().code, "relocate.invalidPath");
        if cfg!(windows) {
            assert_eq!(check_target(r"\\server\share\x", &current, "x", &[], &[]).unwrap_err().to_user().code, "relocate.network");
        }
        // Systemordner und verbotene Bereiche.
        let system = dir.path().join("system");
        let err = check_target(&system.join("x").display().to_string(), &current, "x", &[], std::slice::from_ref(&system)).unwrap_err();
        assert_eq!(err.to_user().code, "relocate.protected");
        let data = dir.path().join("data");
        let err = check_target(&data.join("i").display().to_string(), &current, "x", std::slice::from_ref(&data), &[]).unwrap_err();
        assert_eq!(err.to_user().code, "relocate.insideLauncher");
    }

    #[test]
    fn probes_writability_without_leaving_traces() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("a").join("b").join("c");
        probe_writable(&target).unwrap();
        assert!(!dir.path().join("a").exists(), "angelegte Ordner wieder weg");
        std::fs::create_dir_all(&target).unwrap();
        probe_writable(&target).unwrap();
        assert_eq!(std::fs::read_dir(&target).unwrap().count(), 0);
    }

    #[test]
    fn copies_verifies_and_skips() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("src");
        write(&src.join("settings.json"), b"{}");
        write(&src.join("instances/a/minecraft/options.txt"), b"maxFps:260");
        write(&src.join("big.bin"), &vec![7u8; 3 * 1024 * 1024]);
        write(&src.join("cache/clip-buffer/seg.ts"), b"temp");
        std::fs::create_dir_all(src.join("leer")).unwrap();

        let stats = scan(&src, &skip_in_data_dir).unwrap();
        assert_eq!((stats.files, stats.bytes), (3, 2 + 10 + 3 * 1024 * 1024));

        let seen = std::sync::atomic::AtomicU64::new(0);
        let count = |n: u64| {
            seen.fetch_add(n, std::sync::atomic::Ordering::Relaxed);
        };
        let progress = Progress { control: None, on_bytes: &count };
        let dst = dir.path().join("dst");
        let copied = copy_tree(&src, &dst, &skip_in_data_dir, &progress).unwrap();
        assert_eq!(seen.into_inner(), stats.bytes);
        assert_eq!(copied.files.len(), 3);
        assert!(copied.files.iter().any(|f| f.rel == "settings.json" && f.hash.is_some()));
        assert!(copied.files.iter().any(|f| f.rel == "big.bin" && f.hash.is_none()), "große Dateien nur per Größe");
        assert!(dst.join("leer").is_dir());
        assert!(!dst.join("cache/clip-buffer").exists());
        assert_eq!(std::fs::read(dst.join("instances/a/minecraft/options.txt")).unwrap(), b"maxFps:260");
        verify_copy(&dst, &copied, &quiet()).unwrap();

        // Kaputte Kopie: gleicher Inhalt-Länge, anderer Inhalt → erkannt.
        std::fs::write(dst.join("settings.json"), b"[]").unwrap();
        assert_eq!(verify_copy(&dst, &copied, &quiet()).unwrap_err().to_user().code, "relocate.verifyFailed");
        std::fs::write(dst.join("settings.json"), b"{}").unwrap();
        // Fehlende oder zusätzliche Datei → erkannt.
        std::fs::write(dst.join("extra.txt"), b"x").unwrap();
        assert!(verify_copy(&dst, &copied, &quiet()).is_err());
        std::fs::remove_file(dst.join("extra.txt")).unwrap();
        std::fs::remove_file(dst.join("big.bin")).unwrap();
        assert!(verify_copy(&dst, &copied, &quiet()).is_err());

        discard_copy(&dst, false);
        assert!(dst.is_dir() && std::fs::read_dir(&dst).unwrap().next().is_none());
    }

    #[test]
    fn copy_can_be_cancelled() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("src");
        write(&src.join("a.txt"), b"a");
        let control = TaskControl::new();
        control.cancel();
        let progress = Progress { control: Some(control), on_bytes: &|_| {} };
        let err = copy_tree(&src, &dir.path().join("dst"), &|_| false, &progress).unwrap_err();
        assert!(matches!(err, Error::Cancelled));
    }

    #[cfg(unix)]
    #[test]
    fn keeps_permissions_and_skips_links() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("src");
        let java = src.join("java/x/bin/java");
        write(&java, b"#!");
        std::fs::set_permissions(&java, std::fs::Permissions::from_mode(0o755)).unwrap();
        std::os::unix::fs::symlink(dir.path(), src.join("link")).unwrap();
        let dst = dir.path().join("dst");
        let copied = copy_tree(&src, &dst, &|_| false, &quiet()).unwrap();
        assert_eq!(copied.links, 1);
        assert!(!dst.join("link").exists());
        assert_eq!(std::fs::metadata(dst.join("java/x/bin/java")).unwrap().permissions().mode() & 0o777, 0o755);
    }

    #[test]
    fn rewrites_paths_only_at_path_boundaries() {
        let (old, new) = if cfg!(windows) {
            (PathBuf::from(r"C:\Users\A\AppData\Roaming\TRS-Launcher"), PathBuf::from(r"D:\Spiele\TRS"))
        } else {
            (PathBuf::from("/home/a/.local/share/TRS-Launcher"), PathBuf::from("/mnt/spiele/TRS"))
        };
        let o = old.display().to_string();
        let n = new.display().to_string();
        let sep = std::path::MAIN_SEPARATOR;
        assert_eq!(rewrite_path_text(&o, &old, &new).as_deref(), Some(n.as_str()));
        assert_eq!(
            rewrite_path_text(&format!("{o}{sep}java{sep}bin{sep}java"), &old, &new),
            Some(format!("{n}{sep}java{sep}bin{sep}java"))
        );
        assert_eq!(
            rewrite_path_text(&format!("-Xmx2G -javaagent:{o}{sep}agent.jar"), &old, &new),
            Some(format!("-Xmx2G -javaagent:{n}{sep}agent.jar"))
        );
        // Nur ganze Pfade: „TRS-Launcher2“ oder ein längerer Pfad bleiben unverändert.
        assert_eq!(rewrite_path_text(&format!("{o}2{sep}x"), &old, &new), None);
        assert_eq!(rewrite_path_text(&format!("{sep}vorne{o}"), &old, &new), None);
        assert_eq!(rewrite_path_text("nichts", &old, &new), None);
        if cfg!(windows) {
            assert_eq!(
                rewrite_path_text(r"c:/users/a/appdata/roaming/trs-launcher/java/x.exe", &old, &new).as_deref(),
                Some(r"D:\Spiele\TRS/java/x.exe")
            );
        }
    }

    #[test]
    fn rewrites_stored_paths_and_forgets_firewall_rules() {
        let dir = tempfile::tempdir().unwrap();
        let old = dir.path().join("old");
        let new = dir.path().join("new");
        let custom = dir.path().join("custom-pack");
        let java = old.join("java").join("rt").join("bin").join("javaw.exe");
        let other_java = dir.path().join("jdk").join("bin").join("java.exe");
        let settings = serde_json::json!({
            "javaPath": java,
            "java": { "java21": java, "java17": other_java },
            "jvmArgs": format!("-javaagent:{}", old.join("agent.jar").display()),
            "clips": { "folder": old.join("clips") },
            "maxMemoryMb": 4096,
        });
        write(&new.join("settings.json"), settings.to_string().as_bytes());
        let instance = serde_json::json!({ "id": "a", "overrides": { "javaPath": java } });
        write(&new.join("instances/a/instance.json"), instance.to_string().as_bytes());
        write(&new.join("instances/a/crashes/1.json"), serde_json::json!({ "log": old.join("x.log") }).to_string().as_bytes());
        write(&custom.join("instance.json"), instance.to_string().as_bytes());
        // Tokens nie anfassen.
        let accounts = serde_json::json!({ "note": old.join("x") }).to_string();
        write(&new.join("accounts.json"), accounts.as_bytes());
        let fw = serde_json::json!({ "allowed": [java, other_java], "declined": [java] });
        write(&new.join("firewall.json"), fw.to_string().as_bytes());

        let changed = rewrite_data_files(&new, &old, std::slice::from_ref(&custom));
        assert_eq!(changed, 4);
        let new_java = new.join("java").join("rt").join("bin").join("javaw.exe");
        let read = |p: &Path| serde_json::from_slice::<serde_json::Value>(&std::fs::read(p).unwrap()).unwrap();
        let s = read(&new.join("settings.json"));
        assert_eq!(s["javaPath"], serde_json::json!(new_java));
        assert_eq!(s["java"]["java21"], serde_json::json!(new_java));
        assert_eq!(s["java"]["java17"], serde_json::json!(other_java), "fremdes Java bleibt");
        assert_eq!(s["jvmArgs"], format!("-javaagent:{}", new.join("agent.jar").display()));
        assert_eq!(s["clips"]["folder"], serde_json::json!(new.join("clips")));
        assert_eq!(s["maxMemoryMb"], 4096);
        assert_eq!(read(&new.join("instances/a/instance.json"))["overrides"]["javaPath"], serde_json::json!(new_java));
        assert_eq!(read(&custom.join("instance.json"))["overrides"]["javaPath"], serde_json::json!(new_java));
        assert_eq!(read(&new.join("instances/a/crashes/1.json"))["log"], serde_json::json!(new.join("x.log")));
        assert_eq!(std::fs::read_to_string(new.join("accounts.json")).unwrap(), accounts);
        let fw = read(&new.join("firewall.json"));
        assert_eq!(fw["allowed"], serde_json::json!([other_java]), "Regel muss für den neuen Pfad neu eingetragen werden");
        assert_eq!(fw["declined"], serde_json::json!([new_java]));
    }

    #[tokio::test]
    async fn instance_moves_out_and_back_and_survives_a_missing_drive() {
        use crate::instance::{Loader, NewInstance};
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("root");
        let launcher = Launcher::init(&root, std::sync::Arc::new(|_| {})).await.unwrap();
        let inst = launcher
            .instances()
            .create(NewInstance { name: "Pack".into(), game_version: "1.21.1".into(), loader: Loader::vanilla() })
            .await
            .unwrap();
        let game = launcher.paths().instance_game_dir(&inst.id);
        write(&game.join("saves/welt/level.dat"), b"welt");

        // Nicht leerer Ordner: die Instanz landet in einem Unterordner.
        let games = dir.path().join("Spiele");
        write(&games.join("anderes.txt"), b"x");
        let plan = launcher.instance_move_plan(&inst.id, Some(&games.display().to_string())).await.unwrap();
        assert_eq!(PathBuf::from(&plan.target), games.join(&inst.id));
        assert!(plan.same_volume && plan.enough_space && plan.files >= 2);
        // Im Datenordner: verboten.
        let inside = root.join("eigene");
        let err = launcher.instance_move_plan(&inst.id, Some(&inside.display().to_string())).await.unwrap_err();
        assert_eq!(err.to_user().code, "relocate.insideLauncher");

        let location = launcher.move_instance(&inst.id, Some(&games.display().to_string()), |_| {}).await.unwrap();
        assert!(location.custom);
        let moved = games.join(&inst.id);
        assert_eq!(PathBuf::from(&location.path), moved);
        assert!(!root.join("instances").join(&inst.id).exists(), "alter Ordner weg");
        assert_eq!(std::fs::read(moved.join("minecraft/saves/welt/level.dat")).unwrap(), b"welt");
        // Bibliothek und Pfade folgen dem neuen Ort.
        assert_eq!(launcher.instances().list().await.unwrap().len(), 1);
        assert_eq!(launcher.paths().instance_game_dir(&inst.id), moved.join("minecraft"));
        // Neue Instanz mit gleichem Namen bekommt eine andere ID.
        let twin = launcher
            .instances()
            .create(NewInstance { name: "Pack".into(), game_version: "1.21.1".into(), loader: Loader::vanilla() })
            .await
            .unwrap();
        assert_ne!(twin.id, inst.id);

        // Laufwerk weg: nicht in der Liste, aber als „nicht verfügbar“ – die Liste bricht nicht.
        let unplugged = dir.path().join("abgezogen");
        std::fs::rename(&moved, &unplugged).unwrap();
        assert_eq!(launcher.instances().list().await.unwrap().len(), 1);
        let missing = launcher.instances().unavailable().await;
        assert_eq!(missing.len(), 1);
        assert_eq!(missing[0].id, inst.id);
        std::fs::rename(&unplugged, &moved).unwrap();
        assert!(launcher.instances().unavailable().await.is_empty());

        // Zurück an den Standardort.
        let location = launcher.move_instance(&inst.id, None, |_| {}).await.unwrap();
        assert!(!location.custom);
        assert!(!moved.exists());
        assert!(games.join("anderes.txt").exists(), "fremde Dateien bleiben");
        assert_eq!(std::fs::read(root.join("instances").join(&inst.id).join("minecraft/saves/welt/level.dat")).unwrap(), b"welt");
        let err = launcher.instance_move_plan(&inst.id, None).await.unwrap_err();
        assert_eq!(err.to_user().code, "relocate.alreadyDefault");

        // Löschen an eigenem Ort: nur der Instanz-Ordner, Eintrag weg.
        launcher.move_instance(&inst.id, Some(&games.display().to_string()), |_| {}).await.unwrap();
        launcher.delete_instance(&inst.id).await.unwrap();
        assert!(!moved.exists());
        assert!(games.join("anderes.txt").exists());
        assert!(launcher.paths().custom_locations().is_empty());

        // Nicht erreichbare Instanz aus der Liste entfernen.
        launcher.move_instance(&twin.id, Some(&games.display().to_string()), |_| {}).await.unwrap();
        std::fs::remove_dir_all(games.join(&twin.id)).unwrap();
        launcher.instances().forget_unavailable(&twin.id).await.unwrap();
        assert!(launcher.paths().custom_locations().is_empty());
        assert!(launcher.instances().list().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn data_dir_move_copies_switches_and_blocks_launches() {
        let dir = tempfile::tempdir().unwrap();
        let app_data = dir.path().join("appdata");
        std::fs::create_dir_all(&app_data).unwrap();
        let location = data_location::resolve(None, None, &app_data);
        let launcher = Launcher::init(&location.root, std::sync::Arc::new(|_| {})).await.unwrap();
        let java = location.root.join("java").join("rt").join("bin").join("javaw.exe");
        write(&java, b"java");
        let settings = serde_json::json!({ "javaPath": java });
        write(&location.root.join("settings.json"), settings.to_string().as_bytes());

        // Ziel im alten Ordner: verboten. Portabel: nicht verschiebbar.
        let err = launcher.data_move_plan(&location, &location.root.join("x").display().to_string()).await.unwrap_err();
        assert_eq!(err.to_user().code, "relocate.overlap");
        let portable = DataLocation { source: data_location::RootSource::Portable, ..location.clone() };
        assert_eq!(launcher.data_move_plan(&portable, "C:/x").await.unwrap_err().to_user().code, "relocate.notMovable");

        let target = dir.path().join("neu");
        let plan = launcher.data_move_plan(&location, &target.display().to_string()).await.unwrap();
        assert!(plan.files >= 2);
        let percents = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let seen = percents.clone();
        let new_root = launcher
            .move_data_dir(&location, &target.display().to_string(), move |p| seen.lock().unwrap().push(p))
            .await
            .unwrap();
        assert_eq!(PathBuf::from(&new_root), target);
        assert_eq!(percents.lock().unwrap().last(), Some(&100));
        // Zeiger umgestellt, Pfade angepasst, alter Ordner unverändert mit Marke.
        let next = data_location::resolve(None, None, &app_data);
        assert_eq!((next.source, next.root.clone()), (data_location::RootSource::Custom, target.clone()));
        let s: serde_json::Value = serde_json::from_slice(&std::fs::read(target.join("settings.json")).unwrap()).unwrap();
        assert_eq!(s["javaPath"], serde_json::json!(target.join("java").join("rt").join("bin").join("javaw.exe")));
        assert!(location.root.join("settings.json").exists());
        assert!(location.root.join(data_location::MOVED_MARKER).exists());

        // Bis zum Neustart: kein zweiter Umzug, keine Instanz-Umzüge.
        let again = launcher.move_data_dir(&location, &dir.path().join("noch").display().to_string(), |_| {}).await;
        assert_eq!(again.unwrap_err().to_user().code, "relocate.dataMoving");

        // Bestätigung „alten Ordner löschen“ → beim nächsten Start weg.
        launcher.confirm_data_move(&location, true).unwrap();
        data_location::finish_pending_delete(&next);
        assert!(!location.root.exists());
        assert!(target.join("settings.json").exists());
    }

    /// Anmeldungen überleben den Umzug: Die Verschlüsselung hängt am Benutzerkonto
    /// (Windows) bzw. am Schlüsselbund/`.token-key` im Datenordner (Linux) – nie am Pfad.
    #[test]
    fn encrypted_accounts_survive_the_move() {
        let dir = tempfile::tempdir().unwrap();
        let old = dir.path().join("old");
        std::fs::create_dir_all(&old).unwrap();
        crate::auth::crypto::init(&old);
        let token = crate::auth::crypto::protect("M.refresh-token").unwrap();
        write(&old.join("accounts.json"), serde_json::json!({ "accounts": [{ "refresh": token }] }).to_string().as_bytes());
        let new = dir.path().join("new");
        let copied = copy_tree(&old, &new, &|_| false, &quiet()).unwrap();
        verify_copy(&new, &copied, &quiet()).unwrap();
        rewrite_data_files(&new, &old, &[]);
        std::fs::remove_dir_all(&old).unwrap();
        let stored = serde_json::from_slice::<serde_json::Value>(&std::fs::read(new.join("accounts.json")).unwrap()).unwrap();
        let token = stored["accounts"][0]["refresh"].as_str().unwrap();
        assert_eq!(crate::auth::crypto::unprotect(token).unwrap(), "M.refresh-token");
    }
}
