//! Standard-Skin eines Kontos, so wie Minecraft ihn vergibt.
//!
//! Seit 1.19.3 wählt der Client aus 18 Texturen (`slim/` und `wide/`, je neun
//! Namen) per `Math.floorMod(uuid.hashCode(), 18)`. Ältere Versionen kennen nur
//! Steve und Alex (`hashCode() & 1`). Die Dateien liegen im Client-Jar; wir
//! kopieren sie nicht ins Repo, sondern lesen sie aus einer installierten Version.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::SystemTime;

use crate::paths::Paths;
use crate::skins::{self, SkinVariant};

const MODERN_PREFIX: &str = "assets/minecraft/textures/entity/player/";
/// Reihenfolge wie `DefaultPlayerSkin.DEFAULT_SKINS`: erst alle schmalen, dann alle breiten, Namen alphabetisch.
const NAMES: [&str; 9] = [
    "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri",
];

const LEGACY_STEVE: &str = "assets/minecraft/textures/entity/steve.png";
const LEGACY_ALEX: &str = "assets/minecraft/textures/entity/alex.png";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Layout {
    /// 18 Texturen unter `player/slim` und `player/wide` (ab 1.19.3).
    Modern,
    /// Nur `steve.png` / `alex.png`.
    Legacy,
}

#[derive(Debug, Clone)]
struct Source {
    jar: PathBuf,
    layout: Layout,
}

/// Zuletzt gefundenes Jar, gültig solange sich der `versions`-Ordner nicht ändert
/// (neue oder gelöschte Version → neue Änderungszeit → neu suchen).
static SOURCE: Mutex<Option<(PathBuf, Option<SystemTime>, Source)>> = Mutex::new(None);
/// Zuletzt gelesene Textur (Jar, Eintrag, Änderungszeit des Jars) – spart das Öffnen des Client-Jars bei jedem Profil-Abruf.
#[allow(clippy::type_complexity)]
static PNG: Mutex<Option<(PathBuf, String, Option<SystemTime>, Vec<u8>)>> = Mutex::new(None);

/// Was die Vorschau zeigen soll: Modell immer, Textur nur wenn ein passendes Jar da ist.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DefaultSkin {
    pub variant: SkinVariant,
    pub png: Option<Vec<u8>>,
    /// Name des Standard-Skins (`steve`, `alex`, `ari`, …).
    pub name: String,
    /// Minecraft-Version, aus deren Jar die Textur stammt (`None` ohne installierte Version).
    pub version: Option<String>,
}

/// Standard-Skin für diese Konto-ID. `png` fehlt, wenn noch keine Minecraft-Version installiert ist.
pub async fn for_account(paths: &Paths, uuid: &str) -> DefaultSkin {
    let dir = paths.versions_dir();
    let uuid = uuid.to_owned();
    tokio::task::spawn_blocking(move || load(&dir, &uuid))
        .await
        .unwrap_or_else(|_| fallback())
}

fn load(versions_dir: &Path, uuid: &str) -> DefaultSkin {
    let Some(hash) = java_uuid_hash(uuid) else {
        return fallback();
    };
    let source = locate(versions_dir);
    let (variant, entry) = match source.as_ref().map(|s| s.layout) {
        Some(Layout::Legacy) => legacy(hash),
        _ => modern(hash),
    };
    let png = source.as_ref().and_then(|s| cached_png(&s.jar, &entry));
    let version = png.as_ref().and(source.as_ref()).and_then(|s| {
        s.jar.parent()?.file_name()?.to_str().map(str::to_owned)
    });
    DefaultSkin {
        variant,
        png,
        name: skin_name(&entry),
        version,
    }
}

fn fallback() -> DefaultSkin {
    DefaultSkin {
        variant: SkinVariant::Classic,
        png: None,
        name: "steve".to_owned(),
        version: None,
    }
}

/// `…/wide/ari.png` → `ari`.
fn skin_name(entry: &str) -> String {
    entry
        .rsplit('/')
        .next()
        .and_then(|f| f.strip_suffix(".png"))
        .unwrap_or("steve")
        .to_owned()
}

fn modified(path: &Path) -> Option<SystemTime> {
    std::fs::metadata(path).and_then(|m| m.modified()).ok()
}

fn cached_png(jar: &Path, entry: &str) -> Option<Vec<u8>> {
    let stamp = modified(jar);
    let mut guard = PNG.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((j, e, t, bytes)) = guard.as_ref()
        && j == jar
        && e == entry
        && *t == stamp
    {
        return Some(bytes.clone());
    }
    let bytes = read_png(jar, entry)?;
    *guard = Some((jar.to_owned(), entry.to_owned(), stamp, bytes.clone()));
    Some(bytes)
}

/// Index in die 18er-Liste (`Math.floorMod(hash, 18)`).
fn modern(hash: i32) -> (SkinVariant, String) {
    let index = floor_mod(hash, (NAMES.len() * 2) as i32);
    let slim = index < NAMES.len();
    let name = NAMES[index % NAMES.len()];
    let variant = if slim {
        SkinVariant::Slim
    } else {
        SkinVariant::Classic
    };
    let arm = if slim { "slim" } else { "wide" };
    (variant, format!("{MODERN_PREFIX}{arm}/{name}.png"))
}

/// Vor 1.19.3: ungerader Hash → Alex, gerader → Steve.
fn legacy(hash: i32) -> (SkinVariant, String) {
    if hash & 1 == 1 {
        (SkinVariant::Slim, LEGACY_ALEX.to_owned())
    } else {
        (SkinVariant::Classic, LEGACY_STEVE.to_owned())
    }
}

fn locate(versions_dir: &Path) -> Option<Source> {
    let stamp = modified(versions_dir);
    let mut guard = SOURCE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((dir, when, source)) = guard.as_ref()
        && dir == versions_dir
        && *when == stamp
        && source.jar.is_file()
    {
        return Some(source.clone());
    }
    let found = scan(versions_dir);
    *guard = found
        .as_ref()
        .map(|source| (versions_dir.to_owned(), stamp, source.clone()));
    found
}

fn scan(versions_dir: &Path) -> Option<Source> {
    let mut jars = Vec::new();
    let entries = std::fs::read_dir(versions_dir).ok()?;
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name) = name.to_str() else { continue };
        if !crate::meta::is_safe_id(name) {
            continue;
        }
        let jar = entry.path().join(format!("{name}.jar"));
        if jar.is_file() {
            jars.push((version_rank(name), jar));
        }
    }
    jars.sort_by(|a, b| match (a.0, b.0) {
        (Some(x), Some(y)) => y.cmp(&x),
        (Some(_), None) => std::cmp::Ordering::Less,
        (None, Some(_)) => std::cmp::Ordering::Greater,
        (None, None) => std::cmp::Ordering::Equal,
    });
    for (_, jar) in &jars {
        let probe = format!("{MODERN_PREFIX}wide/steve.png");
        if zip_contains(jar, &probe) {
            return Some(Source {
                jar: jar.clone(),
                layout: Layout::Modern,
            });
        }
    }
    for (_, jar) in &jars {
        if zip_contains(jar, LEGACY_STEVE) {
            return Some(Source {
                jar: jar.clone(),
                layout: Layout::Legacy,
            });
        }
    }
    None
}

/// `1.21` und `1.21.11` zählen, `24w14a` und Loader-Jars nicht (die kommen zuletzt dran).
fn version_rank(name: &str) -> Option<(u32, u32, u32)> {
    let head = name.split(['-', '+', ' ']).next().unwrap_or(name);
    let mut parts = head.split('.');
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    let patch = match parts.next() {
        Some(p) => p.parse().ok()?,
        None => 0,
    };
    parts.next().is_none().then_some((major, minor, patch))
}

fn zip_contains(jar: &Path, name: &str) -> bool {
    let Ok(file) = std::fs::File::open(jar) else {
        return false;
    };
    let Ok(mut archive) = zip::ZipArchive::new(file) else {
        return false;
    };
    archive.by_name(name).is_ok()
}

fn read_png(jar: &Path, name: &str) -> Option<Vec<u8>> {
    let file = std::fs::File::open(jar).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let entry = archive.by_name(name).ok()?;
    if entry.size() > skins::MAX_SKIN_BYTES as u64 {
        return None;
    }
    let mut buf = Vec::new();
    entry
        .take(skins::MAX_SKIN_BYTES as u64)
        .read_to_end(&mut buf)
        .ok()?;
    let (width, height) = skins::png_size(&buf)?;
    (width == 64 && (height == 64 || height == 32)).then_some(buf)
}

/// `UUID.hashCode()`: `(int)(hilo >> 32) ^ (int) hilo` mit `hilo = most ^ least`.
fn java_uuid_hash(uuid: &str) -> Option<i32> {
    let hex: String = uuid.chars().filter(|c| *c != '-').collect();
    if hex.len() != 32 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let most = u64::from_str_radix(&hex[..16], 16).ok()? as i64;
    let least = u64::from_str_radix(&hex[16..], 16).ok()? as i64;
    let hilo = most ^ least;
    Some(((hilo >> 32) as i32) ^ (hilo as i32))
}

/// `Math.floorMod` für einen positiven Teiler.
fn floor_mod(x: i32, y: i32) -> usize {
    let mut r = x.checked_rem(y).unwrap_or(0);
    if (x ^ y) < 0 && r != 0 {
        r += y;
    }
    r as usize
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(mark: u8) -> Vec<u8> {
        let mut img = image::RgbaImage::new(64, 64);
        img.put_pixel(0, 0, image::Rgba([mark, 0, 0, 255]));
        let mut cursor = std::io::Cursor::new(Vec::new());
        img.write_to(&mut cursor, image::ImageFormat::Png).unwrap();
        cursor.into_inner()
    }

    fn write_jar(path: &Path, files: &[(&str, &[u8])]) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let file = std::fs::File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let opts = zip::write::SimpleFileOptions::default();
        for (name, bytes) in files {
            zip.start_file(*name, opts).unwrap();
            std::io::Write::write_all(&mut zip, bytes).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn nil_uuid_is_slim_alex_and_dashes_do_not_matter() {
        let (variant, path) =
            modern(java_uuid_hash("00000000-0000-0000-0000-000000000000").unwrap());
        assert_eq!(variant, SkinVariant::Slim);
        assert_eq!(path, format!("{MODERN_PREFIX}slim/alex.png"));
        assert_eq!(
            java_uuid_hash("00000000000000000000000000000000"),
            java_uuid_hash("00000000-0000-0000-0000-000000000000")
        );
    }

    #[test]
    fn hash_one_is_the_second_skin_and_negative_hashes_stay_in_range() {
        let (variant, path) = modern(1);
        assert_eq!(
            (variant, path.as_str()),
            (
                SkinVariant::Slim,
                format!("{MODERN_PREFIX}slim/ari.png").as_str()
            )
        );
        // i32::MIN % 18 == -2, floorMod hebt das auf 16 → wide/sunny (Index 16).
        let (variant, path) = modern(i32::MIN);
        assert_eq!(variant, SkinVariant::Classic);
        assert_eq!(path, format!("{MODERN_PREFIX}wide/sunny.png"));
        assert!(floor_mod(-1, 18) < 18);
    }

    #[test]
    fn legacy_odd_hash_is_alex() {
        assert_eq!(legacy(1).0, SkinVariant::Slim);
        assert_eq!(legacy(1).1, LEGACY_ALEX);
        assert_eq!(legacy(2).0, SkinVariant::Classic);
        assert_eq!(legacy(2).1, LEGACY_STEVE);
        assert!(java_uuid_hash("not-a-uuid").is_none());
    }

    #[test]
    fn newest_client_jar_wins_over_an_older_steve_only_jar() {
        let root = tempfile::tempdir().unwrap();
        let versions = root.path().join("versions");
        let old = versions.join("1.16.5").join("1.16.5.jar");
        let new = versions.join("1.21.1").join("1.21.1.jar");
        let steve = png(1);
        let ari = png(2);
        write_jar(&old, &[(LEGACY_STEVE, steve.as_slice())]);
        write_jar(
            &new,
            &[(&format!("{MODERN_PREFIX}slim/ari.png"), ari.as_slice())],
        );
        // Der Suchlauf prüft wide/steve.png. Die liegt hier nicht – also fällt er auf das alte Jar zurück,
        // solange kein modernes Jar die Sonde hat. Zweites Jar mit der Sonde muss gewinnen.
        let probe = png(3);
        let newer = versions.join("1.21.11").join("1.21.11.jar");
        write_jar(
            &newer,
            &[
                (&format!("{MODERN_PREFIX}wide/steve.png"), probe.as_slice()),
                (&format!("{MODERN_PREFIX}slim/ari.png"), ari.as_slice()),
            ],
        );
        let skin = load(&versions, "00000000-0000-0000-0000-000000000001");
        assert_eq!(skin.variant, SkinVariant::Slim);
        let bytes = skin.png.expect("textur");
        assert_eq!(&bytes, &ari);
        assert_ne!(&bytes, &steve);
    }

    #[test]
    fn without_a_modern_jar_the_legacy_texture_is_used() {
        let root = tempfile::tempdir().unwrap();
        let versions = root.path().join("versions");
        let alex = png(9);
        write_jar(
            &versions.join("1.16.5").join("1.16.5.jar"),
            &[
                (LEGACY_STEVE, png(1).as_slice()),
                (LEGACY_ALEX, alex.as_slice()),
            ],
        );
        // Hash 1 ist ungerade → Alex.
        let skin = load(&versions, "00000000-0000-0000-0000-000000000001");
        assert_eq!(skin.variant, SkinVariant::Slim);
        assert_eq!(skin.png.as_deref(), Some(alex.as_slice()));
    }

    #[test]
    fn a_newly_installed_version_replaces_the_cached_legacy_jar() {
        let root = tempfile::tempdir().unwrap();
        let versions = root.path().join("versions");
        write_jar(
            &versions.join("1.8.9").join("1.8.9.jar"),
            &[(LEGACY_STEVE, png(1).as_slice()), (LEGACY_ALEX, png(2).as_slice())],
        );
        let uuid = "00000000-0000-0000-0000-000000000002";
        let first = load(&versions, uuid);
        assert_eq!(first.version.as_deref(), Some("1.8.9"));
        assert_eq!(first.name, "steve");
        // Neue Version kommt dazu → Ordner ändert sich → das moderne Jar wird gefunden.
        std::thread::sleep(std::time::Duration::from_millis(20));
        let efe = png(7);
        write_jar(
            &versions.join("1.21.11").join("1.21.11.jar"),
            &[
                (&format!("{MODERN_PREFIX}wide/steve.png"), png(3).as_slice()),
                (&format!("{MODERN_PREFIX}slim/efe.png"), efe.as_slice()),
            ],
        );
        let second = load(&versions, uuid);
        assert_eq!(second.version.as_deref(), Some("1.21.11"));
        assert_eq!(second.name, "efe");
        assert_eq!(second.png.as_deref(), Some(efe.as_slice()));
    }

    #[test]
    fn missing_install_still_picks_a_model() {
        let root = tempfile::tempdir().unwrap();
        let skin = load(
            &root.path().join("versions"),
            "00000000-0000-0000-0000-000000000000",
        );
        assert_eq!(skin.variant, SkinVariant::Slim);
        assert!(skin.png.is_none());
        assert_eq!(version_rank("1.21"), Some((1, 21, 0)));
        assert_eq!(version_rank("1.21.11"), Some((1, 21, 11)));
        assert_eq!(version_rank("24w14a"), None);
    }
}
