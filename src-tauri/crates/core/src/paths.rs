use std::path::{Path, PathBuf};

use crate::{Result, fsutil};

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
///   instances/<id>/
///     instance.json
///     minecraft/          Game-Directory (.minecraft-Äquivalent)
/// ```
#[derive(Debug, Clone)]
pub struct Paths {
    root: PathBuf,
}

impl Paths {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
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
    pub fn instance_dir(&self, id: &str) -> PathBuf {
        self.instances_dir().join(id)
    }

    pub fn instance_file(&self, id: &str) -> PathBuf {
        self.instance_dir(id).join("instance.json")
    }

    pub fn instance_game_dir(&self, id: &str) -> PathBuf {
        self.instance_dir(id).join("minecraft")
    }
}
