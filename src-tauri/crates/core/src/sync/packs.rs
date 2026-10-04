//! Ressourcenpakete beim Abgleich: jedes Paket nur einmal und nur in der
//! neuesten Fassung.
//!
//! - Gleicher Inhalt (SHA-1 der Zip bzw. aller Dateien eines Ordners) unter
//!   anderem Namen → nicht noch einmal kopieren.
//! - Andere Fassung desselben Pakets → nur die neuere bleibt, die ältere wandert
//!   in die Sicherung (nie löschen). Dasselbe Paket heißt: gleiches
//!   Modrinth-/CurseForge-Projekt (aus den `content.json` der Instanzen), sonst
//!   ein echtes Paket (`pack.mcmeta`) mit gleichem Namen ohne Versionsangaben.
//!   Nennt der Name eine Minecraft-Hauptversion (`1.8.9`, `1.21.4`), müssen die
//!   passen – ein Paket für 1.8 ersetzt nie eines für 1.21.
//! - Neuer = höhere Versionsangabe im Namen, sonst jüngere Änderungszeit.
//! - `options.txt` zeigt danach auf die Dateien, die geblieben sind.

use std::cell::OnceCell;
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use sha1::{Digest, Sha1};

use crate::content::{self, ContentKind};

/// Alter Name → Name, der stattdessen gilt.
pub(crate) type Renames = HashMap<String, String>;
/// Dateiname (klein) → Projekt-Schlüssel (`<modrinth-id>` bzw. `cf:<id>`).
pub(crate) type Known = HashMap<String, String>;

const MAX_INDEX_BYTES: u64 = 16 * 1024 * 1024;
/// Temp-Dateien von `copy_atomic` – nie als Paket zählen.
const TMP_MARK: &str = ".trs-sync-";
/// Wörter, die nur Versionsangaben einleiten.
const NOISE: [&str; 4] = ["mc", "v", "ver", "version"];
const PRE_RELEASE: [&str; 6] = ["pre", "rc", "beta", "alpha", "snapshot", "hotfix"];

struct Pack {
    name: String,
    path: PathBuf,
    dir: bool,
    size: u64,
    files: u64,
    mtime: Option<SystemTime>,
    /// `None`: kein Ressourcenpaket – nur nach Name und Inhalt abgleichen.
    ident: Option<Ident>,
    hash: OnceCell<Option<Vec<u8>>>,
}

struct Ident {
    project: Option<String>,
    name: Name,
}

/// Dateiname zerlegt.
#[derive(Debug, PartialEq, Eq)]
struct Name {
    /// Ohne Versionsangaben, Kopie-Zähler und Farbcodes, klein.
    base: String,
    /// Versionsangaben in Reihenfolge.
    version: Vec<Vec<u64>>,
    /// Genannte Minecraft-Hauptversionen (`1.21` aus `1.21.4`).
    lines: Vec<(u64, u64)>,
}

impl Name {
    /// Für dieselbe Minecraft-Hauptversion (oder keine genannt)?
    fn same_line(&self, other: &Self) -> bool {
        self.lines.is_empty() || other.lines.is_empty() || self.lines.iter().any(|l| other.lines.contains(l))
    }
}

impl Pack {
    fn read(path: PathBuf, name: String, known: &Known) -> Option<Self> {
        if name.contains(TMP_MARK) {
            return None;
        }
        let meta = std::fs::symlink_metadata(&path).ok()?;
        if meta.file_type().is_symlink() || !(meta.is_dir() || meta.is_file()) {
            return None;
        }
        let dir = meta.is_dir();
        let (size, files) = if dir { tree_stats(&path) } else { (meta.len(), 1) };
        let is_pack = if dir { path.join("pack.mcmeta").is_file() } else { is_zip_name(&name) && zip_has_mcmeta(&path) };
        let project = known.get(&name.to_lowercase()).cloned();
        let ident = (is_pack || project.is_some()).then(|| {
            Ident { project, name: split_name(stem(&name, dir)) }
        });
        Some(Self { name, path, dir, size, files, mtime: meta.modified().ok(), ident, hash: OnceCell::new() })
    }

    fn hash(&self) -> Option<&Vec<u8>> {
        self.hash
            .get_or_init(|| {
                let mut hasher = Sha1::new();
                if self.dir {
                    hash_dir(&self.path, "", &mut hasher).ok()?;
                } else {
                    hash_file(&self.path, &mut hasher).ok()?;
                }
                Some(hasher.finalize().to_vec())
            })
            .as_ref()
    }

    fn version(&self) -> &[Vec<u64>] {
        self.ident.as_ref().map_or(&[][..], |i| i.name.version.as_slice())
    }
}

/// Genau gleicher Inhalt? Größe/Dateizahl vorab, gehasht wird nur bei Gleichstand.
fn identical(a: &Pack, b: &Pack) -> bool {
    a.dir == b.dir && a.size == b.size && a.files == b.files && a.hash().is_some() && a.hash() == b.hash()
}

/// Zwei Fassungen desselben Pakets?
fn same_pack(a: &Pack, b: &Pack) -> bool {
    let (Some(x), Some(y)) = (&a.ident, &b.ident) else { return false };
    let same = match (&x.project, &y.project) {
        (Some(p), Some(q)) => p == q,
        _ => !x.name.base.is_empty() && x.name.base == y.name.base,
    };
    same && x.name.same_line(&y.name)
}

/// Ist `a` neuer als `b`? Versionsangabe vor Änderungszeit.
fn newer(a: &Pack, b: &Pack) -> bool {
    let (va, vb) = (a.version(), b.version());
    if !va.is_empty() && !vb.is_empty() && va != vb {
        return va > vb;
    }
    a.mtime > b.mtime
}

fn split_name(stem: &str) -> Name {
    let lower = strip_noise(&stem.to_lowercase());
    let mut base: Vec<&str> = Vec::new();
    let mut version = Vec::new();
    let mut lines = Vec::new();
    for token in lower.split(|c: char| !(c.is_alphanumeric() || c == '.')) {
        let token = token.trim_matches('.');
        if token.is_empty() || NOISE.contains(&token) {
            continue;
        }
        match version_token(token) {
            Some((numbers, kind)) if !numbers.is_empty() => {
                if let Some(line) = mc_line(&numbers, kind) {
                    lines.push(line);
                }
                version.push(numbers);
            }
            Some(_) => {}
            None => base.push(token),
        }
    }
    Name { base: base.join(" "), version, lines }
}

/// Wie eine Versionsangabe eingeleitet wurde.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Prefix {
    None,
    /// `mc1.20.1`
    Minecraft,
    /// `v1.2`, `r5` – Version des Pakets selbst.
    Pack,
}

/// Minecraft-Hauptversion, wenn die Angabe danach aussieht (`1.x`, ab 2025 `26.1`).
fn mc_line(numbers: &[u64], prefix: Prefix) -> Option<(u64, u64)> {
    let (&major, &minor) = (numbers.first()?, numbers.get(1)?);
    let looks_like = (major == 1 && minor <= 30) || (25..=99).contains(&major);
    match prefix {
        Prefix::Minecraft => Some((major, minor)),
        Prefix::None if looks_like => Some((major, minor)),
        _ => None,
    }
}

/// `§x` und `(<zahl>)` raus.
fn strip_noise(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars();
    while let Some(c) = chars.next() {
        match c {
            '§' => {
                chars.next();
            }
            '(' => {
                let digits = chars.clone().take_while(char::is_ascii_digit).count();
                if digits > 0 && chars.clone().nth(digits) == Some(')') {
                    for _ in 0..=digits {
                        chars.next();
                    }
                    out.push(' ');
                } else {
                    out.push('(');
                }
            }
            _ => out.push(c),
        }
    }
    out
}

/// `1.21`, `1.21.x`, `v1.2`, `v2`, `r5`, `mc1.20.1` → Zahlen; `pre1`, `beta` →
/// leer (nur Rauschen). `32x` oder eine einzelne Zahl sind kein Versionsteil.
fn version_token(token: &str) -> Option<(Vec<u64>, Prefix)> {
    if PRE_RELEASE.iter().any(|p| token.strip_prefix(p).is_some_and(|r| r.chars().all(|c| c.is_ascii_digit()))) {
        return Some((Vec::new(), Prefix::None));
    }
    let digit_start = |r: &&str| r.starts_with(|c: char| c.is_ascii_digit());
    let (rest, prefix) = [("mc", Prefix::Minecraft), ("v", Prefix::Pack), ("r", Prefix::Pack)]
        .iter()
        .find_map(|(p, kind)| token.strip_prefix(p).filter(digit_start).map(|r| (r, *kind)))
        .unwrap_or((token, Prefix::None));
    if !digit_start(&rest) {
        return None;
    }
    let parts: Vec<&str> = rest.split('.').collect();
    if prefix == Prefix::None && parts.len() < 2 {
        return None;
    }
    let numbers = parts.into_iter().filter(|p| *p != "x").map(|p| p.parse::<u64>().ok()).collect::<Option<_>>()?;
    Some((numbers, prefix))
}

fn is_zip_name(name: &str) -> bool {
    name.to_ascii_lowercase().ends_with(".zip")
}

fn stem(name: &str, dir: bool) -> &str {
    if !dir && is_zip_name(name) { &name[..name.len() - 4] } else { name }
}

fn zip_has_mcmeta(path: &Path) -> bool {
    let Ok(file) = std::fs::File::open(path) else { return false };
    zip::ZipArchive::new(std::io::BufReader::new(file)).is_ok_and(|mut zip| zip.by_name("pack.mcmeta").is_ok())
}

/// Gesamtgröße und Dateizahl eines Ordners (ohne Links).
fn tree_stats(dir: &Path) -> (u64, u64) {
    let Ok(entries) = std::fs::read_dir(dir) else { return (0, 0) };
    entries.flatten().fold((0, 0), |(size, files), entry| match entry.file_type() {
        Ok(kind) if kind.is_dir() => {
            let (s, f) = tree_stats(&entry.path());
            (size + s, files + f)
        }
        Ok(kind) if kind.is_file() => (size + entry.metadata().map_or(0, |m| m.len()), files + 1),
        _ => (size, files),
    })
}

fn hash_file(path: &Path, hasher: &mut Sha1) -> std::io::Result<()> {
    let mut file = std::fs::File::open(path)?;
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            return Ok(());
        }
        hasher.update(&buf[..n]);
    }
}

/// Ordner-Manifest: relative Pfade (sortiert), Größe und Inhalt jeder Datei.
fn hash_dir(dir: &Path, prefix: &str, hasher: &mut Sha1) -> std::io::Result<()> {
    let mut entries: Vec<_> = std::fs::read_dir(dir)?.collect::<std::io::Result<_>>()?;
    entries.sort_by_key(|e| e.file_name());
    for entry in entries {
        let kind = entry.file_type()?;
        let rel = format!("{prefix}{}", entry.file_name().to_string_lossy());
        if kind.is_dir() {
            hash_dir(&entry.path(), &format!("{rel}/"), hasher)?;
        } else if kind.is_file() {
            hasher.update(rel.as_bytes());
            hasher.update([0]);
            hasher.update(entry.metadata()?.len().to_le_bytes());
            hash_file(&entry.path(), hasher)?;
        }
    }
    Ok(())
}

fn scan(dir: &Path, known: &Known) -> std::io::Result<Vec<Pack>> {
    let mut packs = Vec::new();
    if !dir.is_dir() {
        return Ok(packs);
    }
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let Ok(name) = entry.file_name().into_string() else { continue };
        packs.extend(Pack::read(entry.path(), name, known));
    }
    Ok(packs)
}

/// Projekt-Schlüssel der Ressourcenpakete aus den `content.json` aller Instanzen
/// (dort merkt sich der Launcher Modrinth/CurseForge-Herkunft, auch aus dem
/// Hash-Abgleich). Kein Netzwerk. Widersprüchliche Namen fallen weg.
pub(crate) fn known_projects(instances_dir: &Path) -> Known {
    let mut found: HashMap<String, Option<String>> = HashMap::new();
    let Ok(entries) = std::fs::read_dir(instances_dir) else { return Known::new() };
    for entry in entries.flatten() {
        let file = entry.path().join("content.json");
        if !std::fs::metadata(&file).is_ok_and(|m| m.is_file() && m.len() <= MAX_INDEX_BYTES) {
            continue;
        }
        let Ok(bytes) = std::fs::read(&file) else { continue };
        let Ok(index) = serde_json::from_slice::<content::ContentIndex>(&bytes) else { continue };
        for (key, source) in &index.files {
            let Some((ContentKind::ResourcePack, file)) = content::split_key(key) else { continue };
            let name = file.strip_suffix(content::DISABLED_SUFFIX).unwrap_or(file).to_lowercase();
            let project = source.project_key();
            found
                .entry(name)
                .and_modify(|p| {
                    if p.as_deref() != Some(project.as_str()) {
                        *p = None;
                    }
                })
                .or_insert(Some(project));
        }
    }
    found.into_iter().filter_map(|(name, project)| Some((name, project?))).collect()
}

/// Ergänzt `to` um die Pakete aus `from` – ohne Doppelte und ohne ältere
/// Fassungen. Danach bleibt in `to` je Paket nur die neueste Fassung (außer
/// `to` ist selbst ein Link, etwa ein geteilter Ordner). Liefert, welcher Name
/// jetzt für welchen gilt.
pub(crate) fn merge(from: &Path, to: &Path, backup_dir: &Path, known: &Known) -> std::io::Result<Renames> {
    let cleanup = !std::fs::symlink_metadata(to).is_ok_and(|m| m.file_type().is_symlink());
    std::fs::create_dir_all(to)?;
    let mut dst = scan(to, known)?;
    let mut renames = Renames::new();
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        if kind.is_symlink() {
            continue;
        }
        let target = to.join(entry.file_name());
        let Ok(name) = entry.file_name().into_string() else {
            // Kein UTF-8: nur ergänzen, wie bisher.
            if std::fs::symlink_metadata(&target).is_err() {
                copy_entry(&entry.path(), &target, kind.is_dir())?;
            }
            continue;
        };
        let Some(src) = Pack::read(entry.path(), name, known) else { continue };
        if std::fs::symlink_metadata(&target).is_ok() {
            // Gleicher Name: Dateien nur ersetzen, wenn die Quelle neuer ist.
            if !src.dir
                && target.is_file()
                && !super::same_file(&src.path, &target)
                && src.mtime > super::modified(&target)
            {
                super::backup(&target, backup_dir)?;
                super::copy_atomic(&src.path, &target)?;
                if let Some(i) = dst.iter().position(|d| d.name.eq_ignore_ascii_case(&src.name)) {
                    let old = &dst[i];
                    if let Some(fresh) = Pack::read(old.path.clone(), old.name.clone(), known) {
                        dst[i] = fresh;
                    }
                }
            }
            continue;
        }
        if let Some(same) = dst.iter().find(|d| identical(&src, d)) {
            renames.insert(src.name, same.name.clone());
            continue;
        }
        let best = dst.iter().filter(|d| same_pack(&src, d)).reduce(|a, b| if newer(b, a) { b } else { a });
        if let Some(best) = best
            && !newer(&src, best)
        {
            renames.insert(src.name, best.name.clone());
            continue;
        }
        copy_entry(&src.path, &target, src.dir)?;
        dst.extend(Pack::read(target, src.name, known));
    }
    if cleanup {
        keep_newest(&mut dst, backup_dir, &mut renames)?;
    }
    Ok(resolve(renames))
}

/// Je Paket bleibt nur die neueste Fassung; die übrigen wandern in die Sicherung.
fn keep_newest(dst: &mut Vec<Pack>, backup_dir: &Path, renames: &mut Renames) -> std::io::Result<()> {
    let mut i = 0;
    while i < dst.len() {
        let group: Vec<usize> = (0..dst.len()).filter(|&j| j == i || same_pack(&dst[i], &dst[j])).collect();
        if group.len() < 2 {
            i += 1;
            continue;
        }
        let winner = group.iter().copied().fold(i, |a, b| if newer(&dst[b], &dst[a]) { b } else { a });
        let mut losers: Vec<usize> = group.into_iter().filter(|&j| j != winner).collect();
        for &j in &losers {
            move_to_backup(&dst[j].path, backup_dir)?;
            renames.insert(dst[j].name.clone(), dst[winner].name.clone());
        }
        losers.sort_unstable();
        for j in losers.into_iter().rev() {
            dst.remove(j);
        }
    }
    Ok(())
}

/// Ketten auflösen (a → b → c wird a → c).
fn resolve(renames: Renames) -> Renames {
    renames
        .iter()
        .map(|(from, to)| {
            let mut to = to;
            for _ in 0..16 {
                match renames.get(to) {
                    Some(next) if next != from => to = next,
                    _ => break,
                }
            }
            (from.clone(), to.clone())
        })
        .filter(|(from, to)| from != to)
        .collect()
}

fn copy_entry(from: &Path, to: &Path, dir: bool) -> std::io::Result<()> {
    if dir { super::copy_dir(from, to) } else { super::copy_atomic(from, to) }
}

/// Verschiebt in die Sicherung (freier Name, nichts wird überschrieben); klappt
/// das Umbenennen nicht (anderes Laufwerk), erst kopieren, dann die Quelle entfernen.
fn move_to_backup(path: &Path, backup_dir: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(backup_dir)?;
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("pack");
    let target = backup_dir.join(crate::instance_files::free_name(backup_dir, name));
    if std::fs::rename(path, &target).is_ok() {
        return Ok(());
    }
    let dir = path.is_dir();
    copy_entry(path, &target, dir)?;
    if dir { std::fs::remove_dir_all(path) } else { std::fs::remove_file(path) }
}

// --- options.txt ------------------------------------------------------------------------

/// Stellt `resourcePacks`/`incompatibleResourcePacks` auf die Dateien in
/// `packs_dir` um: ersetzte Pakete zeigen auf ihren Nachfolger (Reihenfolge
/// bleibt), Verweise auf fehlende Dateien fliegen raus. Kennungen wie
/// `vanilla` oder Mod-Pakete bleiben unangetastet.
pub(crate) fn rewrite_options(options: &Path, packs_dir: &Path, renames: &Renames) -> std::io::Result<()> {
    let Ok(text) = std::fs::read_to_string(options) else { return Ok(()) };
    let present = present_names(packs_dir);
    let mut changed = false;
    let lines: Vec<String> = text
        .split('\n')
        .map(|line| match rewrite_line(line, packs_dir, &present, renames) {
            Some(new) => {
                changed = true;
                new
            }
            None => line.to_owned(),
        })
        .collect();
    if !changed {
        return Ok(());
    }
    let tmp = options.with_extension(format!("trs-sync-{}", uuid::Uuid::new_v4().simple()));
    std::fs::write(&tmp, lines.join("\n"))?;
    std::fs::rename(&tmp, options).inspect_err(|_| {
        let _ = std::fs::remove_file(&tmp);
    })
}

/// Dateiname und zerlegter Name.
type Present = Vec<(String, Name)>;

fn present_names(dir: &Path) -> Present {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    entries
        .flatten()
        .filter_map(|entry| {
            let name = entry.file_name().into_string().ok().filter(|n| !n.contains(TMP_MARK))?;
            let dir = entry.file_type().ok()?.is_dir();
            let parsed = split_name(stem(&name, dir));
            Some((name, parsed))
        })
        .collect()
}

fn rewrite_line(line: &str, packs_dir: &Path, present: &Present, renames: &Renames) -> Option<String> {
    let (body, cr) = match line.strip_suffix('\r') {
        Some(body) => (body, "\r"),
        None => (line, ""),
    };
    let (key, value) = body.split_once(':')?;
    if key != "resourcePacks" && key != "incompatibleResourcePacks" {
        return None;
    }
    let list: Vec<String> = serde_json::from_str(value).ok()?;
    let mut out: Vec<String> = Vec::with_capacity(list.len());
    for entry in &list {
        if let Some(next) = resolve_entry(entry, packs_dir, present, renames)
            && !out.contains(&next)
        {
            out.push(next);
        }
    }
    if out == list {
        return None;
    }
    Some(format!("{key}:{}{cr}", serde_json::to_string(&out).ok()?))
}

fn resolve_entry(entry: &str, packs_dir: &Path, present: &Present, renames: &Renames) -> Option<String> {
    let (prefix, name) = match entry.strip_prefix("file/") {
        Some(name) => ("file/", name),
        None => ("", entry),
    };
    // Vor 1.13 stehen Dateinamen ohne „file/“ drin; Kennungen wie „vanilla“ bleiben.
    let file_ref = !prefix.is_empty() || renames.contains_key(name) || is_zip_name(name);
    if !file_ref || name.is_empty() || name.contains(['/', '\\']) {
        return Some(entry.to_owned());
    }
    if let Some(to) = renames.get(name)
        && packs_dir.join(to).exists()
    {
        return Some(format!("{prefix}{to}"));
    }
    if packs_dir.join(name).exists() {
        return Some(entry.to_owned());
    }
    // Gleiches Paket in anderer Fassung da? Dann die neueste nehmen.
    let wanted = split_name(stem(name, !is_zip_name(name)));
    if wanted.base.is_empty() {
        return None;
    }
    present
        .iter()
        .filter(|(_, n)| n.base == wanted.base && n.same_line(&wanted))
        .max_by(|a, b| a.1.version.cmp(&b.1.version))
        .map(|(n, _)| format!("{prefix}{n}"))
}

// --- Geteilte Ordner --------------------------------------------------------------------

/// Inhalt eines Ordners, gegen den neue Einträge auf gleichen Inhalt geprüft werden.
pub(crate) struct Pool(Vec<Pack>);

impl Pool {
    pub(crate) fn scan(dir: &Path) -> Self {
        Self(scan(dir, &Known::new()).unwrap_or_default())
    }

    /// Name eines Eintrags mit genau diesem Inhalt (egal, wie er heißt).
    pub(crate) fn identical(&self, path: &Path) -> Option<String> {
        let name = path.file_name()?.to_str()?.to_owned();
        let pack = Pack::read(path.to_path_buf(), name, &Known::new())?;
        self.0.iter().find(|p| identical(&pack, p)).map(|p| p.name.clone())
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use std::fs;
    use std::io::Write;
    use std::time::Duration;

    use super::*;

    pub(crate) fn write_pack(path: &Path, description: &str) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let mut zip = zip::ZipWriter::new(fs::File::create(path).unwrap());
        zip.start_file("pack.mcmeta", zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(format!(r#"{{"pack":{{"pack_format":34,"description":"{description}"}}}}"#).as_bytes()).unwrap();
        zip.finish().unwrap();
    }

    fn age(path: &Path, secs: u64) {
        let time = SystemTime::now() - Duration::from_secs(secs);
        fs::File::options().write(true).open(path).unwrap().set_modified(time).unwrap();
    }

    fn names(dir: &Path) -> Vec<String> {
        let mut names: Vec<String> =
            fs::read_dir(dir).unwrap().map(|e| e.unwrap().file_name().into_string().unwrap()).collect();
        names.sort();
        names
    }

    #[test]
    fn names_without_versions() {
        let base = |s: &str| split_name(s).base;
        assert_eq!(base("Faithful 32x - 1.21"), "faithful 32x");
        assert_eq!(base("Faithful 32x - 1.21.4"), "faithful 32x");
        assert_eq!(base("Faithful 64x - 1.21.4"), "faithful 64x");
        assert_eq!(base("Fresh-Animations_v1.9.2"), "fresh animations");
        assert_eq!(base("Stay True (1)"), "stay true");
        assert_eq!(base("§6Bare Bones MC1.20.1-pre2"), "bare bones");
        assert_eq!(base("Pack 1.20.x"), "pack");
        assert_eq!(base("1.21"), "");
        assert_eq!(split_name("Faithful 32x - 1.21.4").version, [vec![1, 21, 4]]);
        assert_eq!(split_name("x v2 r3").version, [vec![2], vec![3]]);
        assert!(split_name("Pack 2").version.is_empty());
        // Minecraft-Hauptversionen: 1.21 und 1.21.4 passen zusammen, 1.8.9 nicht.
        assert_eq!(split_name("Faithful 32x - 1.21.4").lines, [(1, 21)]);
        assert_eq!(split_name("Pack 1.20-1.21 mc26.1").lines, [(1, 20), (1, 21), (26, 1)]);
        assert!(split_name("Fresh Animations v1.9.2").lines.is_empty());
        assert!(split_name("Faithful 1.21").same_line(&split_name("Faithful 1.21.4")));
        assert!(!split_name("Faithful 1.8.9").same_line(&split_name("Faithful 1.21.4")));
        assert!(split_name("Faithful").same_line(&split_name("Faithful 1.21.4")));
    }

    #[test]
    fn identical_content_under_another_name_is_skipped() {
        let root = tempfile::tempdir().unwrap();
        let (from, to, backup) = (root.path().join("from"), root.path().join("to"), root.path().join("backup"));
        write_pack(&from.join("Copy of Pack.zip"), "p");
        fs::create_dir_all(&to).unwrap();
        fs::copy(from.join("Copy of Pack.zip"), to.join("Pack.zip")).unwrap();
        // Ordner-Pakete genauso.
        fs::create_dir_all(from.join("Folder A/assets")).unwrap();
        fs::write(from.join("Folder A/pack.mcmeta"), "{}").unwrap();
        fs::write(from.join("Folder A/assets/x.png"), "x").unwrap();
        fs::create_dir_all(to.join("Other/assets")).unwrap();
        fs::write(to.join("Other/pack.mcmeta"), "{}").unwrap();
        fs::write(to.join("Other/assets/x.png"), "x").unwrap();

        let renames = merge(&from, &to, &backup, &Known::new()).unwrap();
        assert_eq!(names(&to), ["Other", "Pack.zip"]);
        assert_eq!(renames["Copy of Pack.zip"], "Pack.zip");
        assert_eq!(renames["Folder A"], "Other");
        assert!(!backup.exists());
    }

    #[test]
    fn newer_version_replaces_older_and_older_is_skipped() {
        let root = tempfile::tempdir().unwrap();
        let (from, to, backup) = (root.path().join("from"), root.path().join("to"), root.path().join("backup"));
        write_pack(&from.join("Faithful 32x - 1.21.4.zip"), "new");
        write_pack(&to.join("Faithful 32x - 1.21.zip"), "old");
        // Ältere Fassung ist jünger gespeichert – die Versionsangabe zählt.
        age(&from.join("Faithful 32x - 1.21.4.zip"), 3600);
        let renames = merge(&from, &to, &backup, &Known::new()).unwrap();
        assert_eq!(names(&to), ["Faithful 32x - 1.21.4.zip"]);
        assert_eq!(names(&backup), ["Faithful 32x - 1.21.zip"]);
        assert_eq!(renames["Faithful 32x - 1.21.zip"], "Faithful 32x - 1.21.4.zip");

        // Ein Paket für eine andere Minecraft-Hauptversion bleibt daneben.
        write_pack(&from.join("Faithful 32x - 1.8.9.zip"), "pvp");
        merge(&from, &to, &backup, &Known::new()).unwrap();
        assert_eq!(names(&to), ["Faithful 32x - 1.21.4.zip", "Faithful 32x - 1.8.9.zip"]);
        fs::remove_file(from.join("Faithful 32x - 1.8.9.zip")).unwrap();
        fs::remove_file(to.join("Faithful 32x - 1.8.9.zip")).unwrap();

        // Andersherum: die ältere kommt gar nicht erst rein.
        let renames = merge(&backup, &to, &root.path().join("backup2"), &Known::new()).unwrap();
        assert_eq!(names(&to), ["Faithful 32x - 1.21.4.zip"]);
        assert_eq!(renames["Faithful 32x - 1.21.zip"], "Faithful 32x - 1.21.4.zip");
        assert!(!root.path().join("backup2").exists());
    }

    #[test]
    fn without_versions_the_newer_file_wins_and_backups_never_overwrite() {
        let root = tempfile::tempdir().unwrap();
        let (from, to, backup) = (root.path().join("from"), root.path().join("to"), root.path().join("backup"));
        write_pack(&to.join("Stay True.zip"), "old");
        write_pack(&to.join("Stay True (1).zip"), "older");
        age(&to.join("Stay True.zip"), 600);
        age(&to.join("Stay True (1).zip"), 1200);
        write_pack(&backup.join("Stay True.zip"), "earlier backup");
        write_pack(&from.join("stay_true.zip"), "new");
        merge(&from, &to, &backup, &Known::new()).unwrap();
        assert_eq!(names(&to), ["stay_true.zip"]);
        assert_eq!(names(&backup), ["Stay True (1).zip", "Stay True (2).zip", "Stay True.zip"]);
        // Nicht-Pakete (kein pack.mcmeta) werden nie als Fassungen behandelt.
        fs::write(from.join("notes 1.0.zip"), "a").unwrap();
        fs::write(to.join("notes 2.0.zip"), "b").unwrap();
        merge(&from, &to, &backup, &Known::new()).unwrap();
        assert!(to.join("notes 1.0.zip").is_file() && to.join("notes 2.0.zip").is_file());
    }

    #[test]
    fn project_ids_group_differently_named_packs() {
        let root = tempfile::tempdir().unwrap();
        let (from, to, backup) = (root.path().join("from"), root.path().join("to"), root.path().join("backup"));
        write_pack(&from.join("FA-32x-1.21.4.zip"), "new");
        write_pack(&to.join("Faithful 1.21.zip"), "old");
        // Gleicher Name ohne Version, aber verschiedene Projekte → beide bleiben.
        write_pack(&from.join("Clear Glass 1.2.zip"), "a");
        write_pack(&to.join("Clear Glass 1.1.zip"), "b");
        let known: Known = [
            ("fa-32x-1.21.4.zip", "faithful"),
            ("faithful 1.21.zip", "faithful"),
            ("clear glass 1.2.zip", "glass-a"),
            ("clear glass 1.1.zip", "cf:42"),
        ]
        .into_iter()
        .map(|(a, b)| (a.to_owned(), b.to_owned()))
        .collect();
        merge(&from, &to, &backup, &known).unwrap();
        assert_eq!(names(&to), ["Clear Glass 1.1.zip", "Clear Glass 1.2.zip", "FA-32x-1.21.4.zip"]);
        assert_eq!(names(&backup), ["Faithful 1.21.zip"]);
    }

    #[test]
    fn folder_packs_keep_the_newest_version() {
        let root = tempfile::tempdir().unwrap();
        let (from, to, backup) = (root.path().join("from"), root.path().join("to"), root.path().join("backup"));
        for (dir, side) in [("Pack v2", &from), ("Pack v1", &to)] {
            fs::create_dir_all(side.join(dir)).unwrap();
            fs::write(side.join(dir).join("pack.mcmeta"), dir).unwrap();
        }
        let renames = merge(&from, &to, &backup, &Known::new()).unwrap();
        assert_eq!(names(&to), ["Pack v2"]);
        assert_eq!(read_text(&backup.join("Pack v1/pack.mcmeta")), "Pack v1");
        assert_eq!(renames["Pack v1"], "Pack v2");
    }

    fn read_text(path: &Path) -> String {
        fs::read_to_string(path).unwrap()
    }

    #[test]
    fn options_point_to_the_remaining_files() {
        let root = tempfile::tempdir().unwrap();
        let packs = root.path().join("resourcepacks");
        write_pack(&packs.join("Faithful 32x - 1.21.4.zip"), "f");
        write_pack(&packs.join("B.zip"), "b");
        fs::create_dir_all(packs.join("Folder")).unwrap();
        let options = root.path().join("options.txt");
        fs::write(
            &options,
            "fov:0.5\r\nresourcePacks:[\"vanilla\",\"file/Faithful 32x - 1.21.zip\",\"file/A.zip\",\"file/Gone.zip\",\"file/Folder\",\"fabric\",\"file/B.zip\"]\r\nincompatibleResourcePacks:[\"file/Faithful 32x - 1.21.zip\"]\r\nlang:de_de\r\n",
        )
        .unwrap();
        let renames: Renames = [("A.zip".to_owned(), "B.zip".to_owned())].into_iter().collect();
        rewrite_options(&options, &packs, &renames).unwrap();
        assert_eq!(
            read_text(&options),
            "fov:0.5\r\nresourcePacks:[\"vanilla\",\"file/Faithful 32x - 1.21.4.zip\",\"file/B.zip\",\"file/Folder\",\"fabric\"]\r\nincompatibleResourcePacks:[\"file/Faithful 32x - 1.21.4.zip\"]\r\nlang:de_de\r\n"
        );
        // Alte Versionen (vor 1.13) ohne „file/“.
        fs::write(&options, "resourcePacks:[\"A.zip\",\"Gone.zip\",\"Folder\"]\n").unwrap();
        rewrite_options(&options, &packs, &renames).unwrap();
        assert_eq!(read_text(&options), "resourcePacks:[\"B.zip\",\"Folder\"]\n");
        // Nichts zu tun → Datei bleibt unberührt.
        rewrite_options(&options, &packs, &Renames::new()).unwrap();
        assert_eq!(read_text(&options), "resourcePacks:[\"B.zip\",\"Folder\"]\n");
    }

    #[test]
    fn known_projects_come_from_all_instances() {
        let root = tempfile::tempdir().unwrap();
        let index = |project: &str, file: &str| {
            serde_json::json!({ "files": { format!("resourcepacks/{file}"): { "projectId": project, "versionId": "v" } } })
                .to_string()
        };
        fs::create_dir_all(root.path().join("a")).unwrap();
        fs::create_dir_all(root.path().join("b")).unwrap();
        fs::create_dir_all(root.path().join("c")).unwrap();
        fs::write(root.path().join("a/content.json"), index("p1", "Pack.zip")).unwrap();
        fs::write(root.path().join("b/content.json"), index("p2", "Other.zip.disabled")).unwrap();
        fs::write(root.path().join("c/content.json"), index("p3", "pack.zip")).unwrap();
        let known = known_projects(root.path());
        assert_eq!(known.get("other.zip").map(String::as_str), Some("p2"));
        // Widerspruch (p1 vs. p3) → unbekannt.
        assert!(!known.contains_key("pack.zip"));
    }
}
