//! Modrinth-Modpacks (`.mrpack`): legt aus einem Pack eine neue Instanz an.

use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::download::{self, Task};
use crate::instance::{Instance, Loader, LoaderKind, NewInstance};
use crate::modrinth::{self, CDN_PREFIX};
use crate::{Error, Launcher, Result, fsutil};

const MAX_INDEX_BYTES: u64 = 16 << 20;
const MAX_PACK_FILES: usize = 5000;
/// Laut Format-Spezifikation die einzigen erlaubten Download-Hosts.
const ALLOWED_HOSTS: [&str; 4] =
    ["https://cdn.modrinth.com/", "https://github.com/", "https://raw.githubusercontent.com/", "https://gitlab.com/"];

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum PackPhase {
    Pack,
    Files,
    Overrides,
}

#[derive(Debug, Clone, Copy, Serialize)]
pub struct PackProgress {
    pub phase: PackPhase,
    pub percent: f64,
}

pub type PackProgressFn = dyn Fn(PackProgress) + Send + Sync;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PackIndex {
    format_version: u32,
    game: String,
    name: String,
    #[serde(default)]
    files: Vec<PackFile>,
    dependencies: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PackFile {
    path: String,
    hashes: PackHashes,
    env: Option<PackEnv>,
    #[serde(default)]
    downloads: Vec<String>,
    file_size: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct PackHashes {
    sha1: String,
}

#[derive(Debug, Deserialize)]
struct PackEnv {
    client: Option<String>,
}

impl PackIndex {
    fn loader(&self) -> Result<Loader> {
        let pick = |key: &str, kind| self.dependencies.get(key).map(|v| Loader { kind, version: Some(v.clone()) });
        Ok(pick("fabric-loader", LoaderKind::Fabric)
            .or_else(|| pick("quilt-loader", LoaderKind::Quilt))
            .or_else(|| pick("neoforge", LoaderKind::NeoForge))
            .or_else(|| pick("forge", LoaderKind::Forge))
            .unwrap_or_else(Loader::vanilla))
    }

    fn game_version(&self) -> Result<&str> {
        self.dependencies
            .get("minecraft")
            .map(String::as_str)
            .ok_or_else(|| Error::validation("Das Modpack nennt keine Minecraft-Version."))
    }
}

/// Pfade im Pack sind relativ zum Spielordner und dürfen ihn nicht verlassen.
fn safe_relative(path: &str) -> Option<PathBuf> {
    let ok = !path.is_empty()
        && path.len() <= 260
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path.split('/').all(|seg| {
            !seg.is_empty() && seg != "." && seg != ".." && !seg.ends_with('.') && !seg.chars().any(char::is_control)
        });
    ok.then(|| path.split('/').collect())
}

fn read_index(pack: &Path) -> Result<PackIndex> {
    let file = std::fs::File::open(pack).map_err(|e| Error::io(pack, e))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| Error::validation("Das Modpack ist beschädigt."))?;
    let entry = archive
        .by_name("modrinth.index.json")
        .map_err(|_| Error::validation("Das ist kein gültiges Modrinth-Modpack."))?;
    if entry.size() > MAX_INDEX_BYTES {
        return Err(Error::validation("Das Modpack ist beschädigt."));
    }
    let mut text = String::new();
    entry
        .take(MAX_INDEX_BYTES)
        .read_to_string(&mut text)
        .map_err(|_| Error::validation("Das Modpack ist beschädigt."))?;
    let index: PackIndex =
        serde_json::from_str(&text).map_err(|e| Error::json("modrinth.index.json", e))?;
    if index.format_version != 1 || index.game != "minecraft" {
        return Err(Error::validation("Dieses Modpack-Format wird nicht unterstützt."));
    }
    if index.files.len() > MAX_PACK_FILES {
        return Err(Error::validation("Das Modpack enthält zu viele Dateien."));
    }
    Ok(index)
}

/// Entpackt `overrides/` und danach `client-overrides/` in den Spielordner.
fn extract_overrides(pack: &Path, game_dir: &Path) -> Result<()> {
    let file = std::fs::File::open(pack).map_err(|e| Error::io(pack, e))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| Error::validation("Das Modpack ist beschädigt."))?;

    for prefix in ["overrides/", "client-overrides/"] {
        for i in 0..archive.len() {
            let mut entry = archive.by_index(i).map_err(|_| Error::validation("Das Modpack ist beschädigt."))?;
            // `enclosed_name` verhindert Zip-Slip.
            let Some(name) = entry.enclosed_name() else { continue };
            let name = name.to_string_lossy().replace('\\', "/");
            let Some(rel) = name.strip_prefix(prefix).and_then(safe_relative) else { continue };
            if entry.is_dir() {
                continue;
            }
            let dest = game_dir.join(rel);
            if let Some(parent) = dest.parent() {
                std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
            }
            let mut out = std::fs::File::create(&dest).map_err(|e| Error::io(&dest, e))?;
            std::io::copy(&mut entry, &mut out).map_err(|e| Error::io(&dest, e))?;
        }
    }
    Ok(())
}

fn download_tasks(index: &PackIndex, game_dir: &Path) -> Result<Vec<Task>> {
    let mut tasks = Vec::new();
    for file in &index.files {
        if file.env.as_ref().and_then(|e| e.client.as_deref()) == Some("unsupported") {
            continue;
        }
        let rel = safe_relative(&file.path)
            .ok_or_else(|| Error::validation("Das Modpack enthält einen ungültigen Dateipfad."))?;
        let url = file
            .downloads
            .iter()
            .find(|u| ALLOWED_HOSTS.iter().any(|host| u.starts_with(host)))
            .ok_or_else(|| Error::validation("Das Modpack verweist auf eine nicht erlaubte Download-Quelle."))?;
        let sha1_ok = file.hashes.sha1.len() == 40 && file.hashes.sha1.bytes().all(|b| b.is_ascii_hexdigit());
        if !sha1_ok {
            return Err(Error::validation("Das Modpack enthält eine ungültige Prüfsumme."));
        }
        tasks.push(Task {
            url: url.clone(),
            path: game_dir.join(rel),
            sha1: Some(file.hashes.sha1.clone()),
            size: file.file_size,
        });
    }
    Ok(tasks)
}

impl Launcher {
    /// Lädt ein Modpack von Modrinth und legt daraus eine neue Instanz an.
    pub async fn install_modpack(
        &self,
        project_id: &str,
        version_id: Option<&str>,
        on_progress: &PackProgressFn,
    ) -> Result<Instance> {
        if !modrinth::is_safe_project_id(project_id) {
            return Err(Error::validation("Ungültige Projekt-ID"));
        }
        let http = self.http();
        on_progress(PackProgress { phase: PackPhase::Pack, percent: 0.0 });

        let version = match version_id {
            Some(id) => modrinth::version_by_id(http, id).await?,
            None => {
                let versions: Vec<modrinth::Version> = http
                    .get(format!("{}/project/{project_id}/version", modrinth::API))
                    .send()
                    .await?
                    .error_for_status()?
                    .json()
                    .await?;
                versions.into_iter().next().ok_or_else(|| Error::validation("Dieses Modpack hat keine Version."))?
            }
        };
        let file = version
            .files
            .iter()
            .find(|f| f.primary && f.filename.ends_with(".mrpack"))
            .or_else(|| version.files.iter().find(|f| f.filename.ends_with(".mrpack")))
            .ok_or_else(|| Error::validation("Diese Version enthält kein Modpack."))?;
        if !file.url.starts_with(CDN_PREFIX) {
            return Err(Error::download(&file.url, "Download liegt nicht auf Modrinths CDN"));
        }

        let pack_path = self.paths().meta_dir().join(format!("pack-{}.mrpack", uuid::Uuid::new_v4().simple()));
        let pack_task =
            Task { url: file.url.clone(), path: pack_path.clone(), sha1: Some(file.hashes.sha1.clone()), size: Some(file.size) };
        let result = self.install_pack_file(&pack_task, on_progress).await;
        let _ = tokio::fs::remove_file(&pack_path).await;
        result
    }

    async fn install_pack_file(&self, pack_task: &Task, on_progress: &PackProgressFn) -> Result<Instance> {
        download::fetch_all(self.http(), vec![pack_task.clone()], 1, &|p| {
            on_progress(PackProgress { phase: PackPhase::Pack, percent: p.percent() });
        })
        .await?;

        let pack_path = pack_task.path.clone();
        let index = {
            let path = pack_path.clone();
            tokio::task::spawn_blocking(move || read_index(&path)).await.map_err(|e| Error::Internal(e.to_string()))??
        };

        let instance = self
            .create_instance(NewInstance {
                name: index.name.chars().filter(|c| !c.is_control()).take(64).collect(),
                game_version: index.game_version()?.to_owned(),
                loader: index.loader()?,
            })
            .await?;

        let game_dir = self.paths().instance_game_dir(&instance.id);
        let files = async {
            let tasks = download_tasks(&index, &game_dir)?;
            let concurrency = usize::from(self.settings().await.concurrent_downloads);
            download::fetch_all(self.http(), tasks, concurrency, &|p| {
                on_progress(PackProgress { phase: PackPhase::Files, percent: p.percent() });
            })
            .await?;

            on_progress(PackProgress { phase: PackPhase::Overrides, percent: 0.0 });
            let (pack, dir) = (pack_path.clone(), game_dir.clone());
            tokio::task::spawn_blocking(move || extract_overrides(&pack, &dir))
                .await
                .map_err(|e| Error::Internal(e.to_string()))??;
            on_progress(PackProgress { phase: PackPhase::Overrides, percent: 100.0 });
            Ok::<_, Error>(())
        }
        .await;

        // Halb installierte Packs nicht herumliegen lassen.
        if let Err(e) = files {
            let _ = self.instances().delete(&instance.id).await;
            return Err(e);
        }
        fsutil::ensure_dir(&game_dir).await?;
        Ok(instance)
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    const INDEX: &str = r#"{
        "formatVersion": 1, "game": "minecraft", "versionId": "1.0", "name": "Testpack",
        "dependencies": { "minecraft": "1.21.1", "fabric-loader": "0.16.10" },
        "files": [
          { "path": "mods/a.jar", "hashes": { "sha1": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "sha512": "x" },
            "env": { "client": "required", "server": "required" },
            "downloads": ["https://evil.example/a.jar", "https://cdn.modrinth.com/data/x/versions/y/a.jar"], "fileSize": 10 },
          { "path": "mods/server-only.jar", "hashes": { "sha1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "sha512": "x" },
            "env": { "client": "unsupported", "server": "required" },
            "downloads": ["https://cdn.modrinth.com/data/x/versions/y/s.jar"], "fileSize": 10 }
        ] }"#;

    fn write_pack(path: &Path, index: &str) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        let opts = zip::write::SimpleFileOptions::default();
        zip.start_file("modrinth.index.json", opts).unwrap();
        zip.write_all(index.as_bytes()).unwrap();
        zip.start_file("overrides/config/a.toml", opts).unwrap();
        zip.write_all(b"base").unwrap();
        zip.start_file("overrides/options.txt", opts).unwrap();
        zip.write_all(b"base").unwrap();
        zip.start_file("client-overrides/options.txt", opts).unwrap();
        zip.write_all(b"client").unwrap();
        zip.start_file("server-overrides/server.properties", opts).unwrap();
        zip.write_all(b"nope").unwrap();
        zip.finish().unwrap();
    }

    #[test]
    fn reads_index_and_builds_tasks() {
        let dir = tempfile::tempdir().unwrap();
        let pack = dir.path().join("p.mrpack");
        write_pack(&pack, INDEX);

        let index = read_index(&pack).unwrap();
        assert_eq!(index.game_version().unwrap(), "1.21.1");
        assert_eq!(index.loader().unwrap(), Loader { kind: LoaderKind::Fabric, version: Some("0.16.10".into()) });

        let tasks = download_tasks(&index, dir.path()).unwrap();
        assert_eq!(tasks.len(), 1, "server-only Dateien werden übersprungen");
        assert!(tasks[0].url.starts_with("https://cdn.modrinth.com/"), "nicht erlaubter Host wird ignoriert");
        assert!(tasks[0].path.ends_with("mods/a.jar") || tasks[0].path.ends_with("mods\\a.jar"));
    }

    #[test]
    fn overrides_are_layered_and_server_files_skipped() {
        let dir = tempfile::tempdir().unwrap();
        let pack = dir.path().join("p.mrpack");
        write_pack(&pack, INDEX);
        let game = dir.path().join("game");
        extract_overrides(&pack, &game).unwrap();

        assert_eq!(std::fs::read_to_string(game.join("config/a.toml")).unwrap(), "base");
        assert_eq!(std::fs::read_to_string(game.join("options.txt")).unwrap(), "client");
        assert!(!game.join("server.properties").exists());
    }

    #[test]
    fn rejects_bad_paths_and_hosts() {
        for bad in ["../x.jar", "/abs.jar", "mods/../../x", "c:/x", "mods\\x.jar", "mods//x", "mods/x."] {
            assert!(safe_relative(bad).is_none(), "{bad:?}");
        }
        assert!(safe_relative("config/sub/a.toml").is_some());

        let dir = tempfile::tempdir().unwrap();
        let evil = INDEX.replace("mods/a.jar", "../../evil.jar");
        let index: PackIndex = serde_json::from_str(&evil).unwrap();
        assert!(download_tasks(&index, dir.path()).is_err());

        let only_evil = INDEX.replace("https://cdn.modrinth.com/data/x/versions/y/a.jar", "https://evil.example/b.jar");
        let index: PackIndex = serde_json::from_str(&only_evil).unwrap();
        assert!(download_tasks(&index, dir.path()).is_err());
    }

    #[test]
    fn rejects_foreign_formats() {
        let dir = tempfile::tempdir().unwrap();
        let pack = dir.path().join("p.mrpack");
        write_pack(&pack, &INDEX.replace("\"formatVersion\": 1", "\"formatVersion\": 2"));
        assert!(read_index(&pack).is_err());
    }
}
