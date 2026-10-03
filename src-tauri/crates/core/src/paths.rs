use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};

use crate::{Error, Result, fsutil};

/// Eigene Speicherorte einzelner Instanzen (`<id>` → Instanz-Ordner außerhalb von `instances/`).
pub const LOCATIONS_FILE: &str = "instance-locations.json";
const MAX_LOCATIONS_SIZE: u64 = 1024 * 1024;

/// Verzeichnisstruktur des Launchers.
///
/// ```text
/// <root>/
///   settings.json
///   meta/                 gecachte Manifeste & Version-JSONs
///   versions/<id>/        Client-Jars
///   libraries/            Maven-Layout, von allen Instanzen geteilt
///   assets/{indexes,objects}/
///   java/<component>/     von uns installierte Runtimes
///   accounts.json         Accounts (Tokens DPAPI-verschlüsselt)
///   client-mod/           TRS-Client-Updates (signiertes Manifest + <version>/*.jar)
///   shared-folders/<art>/ gemeinsame Ordner (Instanzen zeigen per Link darauf)
///   instances/<id>/
///     instance.json
///     minecraft/          Game-Directory (.minecraft-Äquivalent)
///   instance-locations.json  Instanzen an eigenem Ort (z. B. `D:\Minecraft\Modpack`)
/// ```
///
/// Alle Klone teilen sich die Liste der eigenen Speicherorte – [`Self::instance_dir`]
/// liefert für solche Instanzen deren Ordner, alle Aufrufer bleiben unverändert.
#[derive(Debug, Clone)]
pub struct Paths {
    root: PathBuf,
    locations: Arc<RwLock<BTreeMap<String, PathBuf>>>,
}

impl Paths {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        let root = root.into();
        let locations = load_locations(&root.join(LOCATIONS_FILE), &root);
        Self { root, locations: Arc::new(RwLock::new(locations)) }
    }

    pub async fn ensure(&self) -> Result<()> {
        for dir in [
            self.root.clone(),
            self.meta_dir(),
            self.versions_dir(),
            self.libraries_dir(),
            self.assets_dir(),
            self.java_dir(),
            self.instances_dir(),
        ] {
            fsutil::ensure_dir(&dir).await?;
        }
        Ok(())
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn settings_file(&self) -> PathBuf {
        self.root.join("settings.json")
    }

    pub fn meta_dir(&self) -> PathBuf {
        self.root.join("meta")
    }

    pub fn versions_dir(&self) -> PathBuf {
        self.root.join("versions")
    }

    pub fn libraries_dir(&self) -> PathBuf {
        self.root.join("libraries")
    }

    pub fn assets_dir(&self) -> PathBuf {
        self.root.join("assets")
    }

    pub fn java_dir(&self) -> PathBuf {
        self.root.join("java")
    }

    pub fn instances_dir(&self) -> PathBuf {
        self.root.join("instances")
    }

    /// `id` muss vorher mit [`crate::instance::validate_id`] geprüft sein.
    /// Instanzen mit eigenem Speicherort liegen dort statt unter `instances/`.
    pub fn instance_dir(&self, id: &str) -> PathBuf {
        self.instance_location(id).unwrap_or_else(|| self.default_instance_dir(id))
    }

    /// Ordner unter `instances/` – unabhängig von einem eigenen Speicherort.
    pub fn default_instance_dir(&self, id: &str) -> PathBuf {
        self.instances_dir().join(id)
    }

    /// Eigener Speicherort der Instanz (`None` = Standard).
    pub fn instance_location(&self, id: &str) -> Option<PathBuf> {
        self.locations.read().unwrap_or_else(std::sync::PoisonError::into_inner).get(id).cloned()
    }

    /// Alle Instanzen mit eigenem Speicherort.
    pub fn custom_locations(&self) -> Vec<(String, PathBuf)> {
        self.locations.read().unwrap_or_else(std::sync::PoisonError::into_inner).iter().map(|(k, v)| (k.clone(), v.clone())).collect()
    }

    /// Setzt (oder entfernt mit `None`) den eigenen Speicherort und speichert die Liste.
    /// `id`/`dir` prüft der Aufrufer (siehe [`crate::relocate`]).
    pub fn set_instance_location(&self, id: &str, dir: Option<PathBuf>) -> Result<()> {
        let mut map = self.locations.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut next = map.clone();
        match dir {
            Some(dir) => next.insert(id.to_owned(), dir),
            None => next.remove(id),
        };
        let file = self.root.join(LOCATIONS_FILE);
        if next.is_empty() {
            match std::fs::remove_file(&file) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return Err(Error::io(&file, e)),
            }
        } else {
            let bytes = serde_json::to_vec_pretty(&next).map_err(|e| Error::json(file.display().to_string(), e))?;
            fsutil::write_atomic_sync(&file, &bytes)?;
        }
        *map = next;
        Ok(())
    }

    pub fn instance_file(&self, id: &str) -> PathBuf {
        self.instance_dir(id).join("instance.json")
    }

    pub fn instance_game_dir(&self, id: &str) -> PathBuf {
        self.instance_dir(id).join("minecraft")
    }

    /// Gemeinsame Dateien für die Synchronisierung (siehe [`crate::sync`]).
    pub fn shared_dir(&self) -> PathBuf {
        self.root.join("shared")
    }

    /// Gemeinsame Ordner (Shader, Welten …), auf die Instanzen per Link zeigen
    /// (siehe [`crate::shared_folders`]).
    pub fn shared_folders_dir(&self) -> PathBuf {
        self.root.join("shared-folders")
    }

    pub fn shared_folder(&self, kind: crate::shared_folders::SharedFolder) -> PathBuf {
        self.shared_folders_dir().join(kind.key())
    }

    /// Aus dem Update-Kanal geladene TRS-Client-Versionen (siehe [`crate::client_mod_update`]).
    pub fn client_mod_cache_dir(&self) -> PathBuf {
        self.root.join("client-mod")
    }

    pub fn accounts_file(&self) -> PathBuf {
        self.root.join("accounts.json")
    }

    // Die folgenden IDs stammen aus Metadaten und müssen vorher geprüft sein
    // (siehe `meta::is_safe_id`).

    pub fn version_dir(&self, version_id: &str) -> PathBuf {
        self.versions_dir().join(version_id)
    }

    pub fn version_json(&self, version_id: &str) -> PathBuf {
        self.version_dir(version_id).join(format!("{version_id}.json"))
    }

    pub fn version_jar(&self, version_id: &str) -> PathBuf {
        self.version_dir(version_id).join(format!("{version_id}.jar"))
    }

    pub fn natives_dir(&self, version_id: &str) -> PathBuf {
        self.version_dir(version_id).join("natives")
    }

    pub fn asset_index(&self, index_id: &str) -> PathBuf {
        self.assets_dir().join("indexes").join(format!("{index_id}.json"))
    }

    pub fn asset_object(&self, hash: &str) -> PathBuf {
        self.assets_dir().join("objects").join(&hash[..2]).join(hash)
    }

    pub fn virtual_assets_dir(&self, index_id: &str) -> PathBuf {
        self.assets_dir().join("virtual").join(index_id)
    }

    pub fn log_config(&self, file_id: &str) -> PathBuf {
        self.assets_dir().join("log_configs").join(file_id)
    }
}

/// Liest die Speicherort-Liste; ungültige Einträge (ID, relativer Pfad, Pfad im
/// Datenordner) werden ignoriert, eine kaputte Datei ergibt eine leere Liste.
fn load_locations(file: &Path, root: &Path) -> BTreeMap<String, PathBuf> {
    let Ok(meta) = std::fs::metadata(file) else { return BTreeMap::new() };
    if !meta.is_file() || meta.len() > MAX_LOCATIONS_SIZE {
        return BTreeMap::new();
    }
    let map: BTreeMap<String, PathBuf> = match std::fs::read(file).map(|b| serde_json::from_slice(&b)) {
        Ok(Ok(map)) => map,
        _ => {
            tracing::warn!("{LOCATIONS_FILE} ist unlesbar – Instanzen an eigenem Ort fehlen in der Liste");
            return BTreeMap::new();
        }
    };
    map.into_iter()
        .filter(|(id, dir)| crate::instance::validate_id(id).is_ok() && dir.is_absolute() && !dir.starts_with(root))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn custom_instance_locations_are_shared_and_persisted() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("root");
        let elsewhere = dir.path().join("elsewhere").join("pack");
        let paths = Paths::new(&root);
        let clone = paths.clone();
        assert_eq!(paths.instance_dir("pack"), root.join("instances").join("pack"));

        paths.set_instance_location("pack", Some(elsewhere.clone())).unwrap();
        // Klone sehen die Änderung, Spielordner/instance.json folgen.
        assert_eq!(clone.instance_dir("pack"), elsewhere);
        assert_eq!(clone.instance_game_dir("pack"), elsewhere.join("minecraft"));
        assert_eq!(clone.instance_file("pack"), elsewhere.join("instance.json"));
        assert_eq!(clone.default_instance_dir("pack"), root.join("instances").join("pack"));

        // Neu geladen: gleiche Liste.
        assert_eq!(Paths::new(&root).custom_locations(), vec![("pack".to_owned(), elsewhere.clone())]);

        // Entfernen löscht die Datei, wenn nichts mehr übrig ist.
        paths.set_instance_location("pack", None).unwrap();
        assert!(!root.join(LOCATIONS_FILE).exists());
        assert_eq!(clone.instance_dir("pack"), root.join("instances").join("pack"));
    }

    #[test]
    fn invalid_location_entries_are_ignored() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("root");
        std::fs::create_dir_all(&root).unwrap();
        let outside = dir.path().join("ok");
        let json = serde_json::json!({
            "gut": outside,
            "../böse": dir.path().join("x"),
            "relativ": "nur/relativ",
            "drinnen": root.join("instances").join("drinnen"),
        });
        std::fs::write(root.join(LOCATIONS_FILE), json.to_string()).unwrap();
        assert_eq!(Paths::new(&root).custom_locations(), vec![("gut".to_owned(), outside)]);
        std::fs::write(root.join(LOCATIONS_FILE), b"kaputt").unwrap();
        assert!(Paths::new(&root).custom_locations().is_empty());
    }
}
