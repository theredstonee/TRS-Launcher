use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tokio::fs;
use tokio::sync::Mutex;

use crate::hooks::{self, EnvVar, LaunchHooks};
use crate::paths::Paths;
use crate::settings::{self, Resolution};
use crate::sync::SyncItem;
use crate::{Error, Result, fsutil};

pub const MAX_NAME_LEN: usize = 64;
pub const MAX_GROUP_LEN: usize = 32;
const MAX_ID_LEN: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LoaderKind {
    Vanilla,
    Fabric,
    Quilt,
    Forge,
    NeoForge,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Loader {
    pub kind: LoaderKind,
    /// `None` bei Vanilla; bei Modloadern `None` = beim Installieren die
    /// neueste stabile Version auflösen.
    pub version: Option<String>,
}

impl Loader {
    pub fn vanilla() -> Self {
        Self { kind: LoaderKind::Vanilla, version: None }
    }

    pub fn validate(&self) -> Result<()> {
        match (&self.kind, &self.version) {
            (LoaderKind::Vanilla, Some(_)) => {
                Err(Error::validation(crate::msg!("instance.vanillaNoLoaderVersion", "Vanilla hat keine Loader-Version")))
            }
            (_, Some(v)) if !is_safe_version_string(v) => {
                Err(Error::validation(crate::msg!("instance.invalidLoaderVersion", "Loader-Version enthält ungültige Zeichen")))
            }
            _ => Ok(()),
        }
    }
}

/// Instanz-spezifische Überschreibungen der globalen Einstellungen.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct InstanceOverrides {
    pub max_memory_mb: Option<u32>,
    pub java_path: Option<String>,
    pub jvm_args: Option<String>,
    pub resolution: Option<Resolution>,
    /// TRS Client in dieser Instanz; `None` = an (Standard).
    pub trs_client: Option<bool>,
    /// TRS-Optimierung (nur Vanilla): Fabric + Performance-Mods; `None` = an.
    pub boost: Option<bool>,
    /// FPS-Boost beim Start (JVM-Abstimmung); `None` = globale Einstellung.
    pub performance_tuning: Option<bool>,
    /// Welche Modrinth-Versionen Updates und „neueste passende“ nehmen;
    /// `None` = nur stabile.
    pub update_channel: Option<UpdateChannel>,
    /// Vollbild beim Start; `None` = globale Einstellung.
    pub fullscreen: Option<bool>,
    /// Eigene Start-Hooks; `None` = globale Hooks.
    pub hooks: Option<LaunchHooks>,
    /// Eigene Umgebungsvariablen; `None` = globale.
    pub env: Option<Vec<EnvVar>>,
    /// Diese Dinge werden in dieser Instanz nicht synchronisiert.
    pub sync_separate: Vec<SyncItem>,
}

/// Update-Kanal für Inhalte (Modrinth-Versionstypen).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum UpdateChannel {
    #[default]
    Release,
    Beta,
    Alpha,
}

impl UpdateChannel {
    /// Modrinth-`version_type`s, die dieser Kanal zulässt.
    pub fn version_types(self) -> &'static [&'static str] {
        match self {
            Self::Release => &["release"],
            Self::Beta => &["release", "beta"],
            Self::Alpha => &["release", "beta", "alpha"],
        }
    }

    pub fn allows(self, version_type: &str) -> bool {
        self.version_types().contains(&version_type)
    }
}

impl InstanceOverrides {
    pub fn channel(&self) -> UpdateChannel {
        self.update_channel.unwrap_or_default()
    }

    /// Leere Hook-Befehle → `None`, doppelte Sync-Einträge raus.
    pub fn normalized(mut self) -> Self {
        self.hooks = self.hooks.map(LaunchHooks::normalized);
        self.env = self.env.map(hooks::normalize_env);
        let mut seen = Vec::new();
        self.sync_separate.retain(|i| {
            let new = !seen.contains(i);
            seen.push(*i);
            new
        });
        self
    }

    pub fn validate(&self) -> Result<()> {
        if let Some(hooks) = &self.hooks {
            hooks.validate()?;
        }
        if let Some(env) = &self.env {
            hooks::validate_env(env)?;
        }
        if self.sync_separate.len() > SyncItem::ALL.len() {
            return Err(Error::validation(crate::msg!("instance.invalidSyncSettings", "Ungültige Synchronisierungs-Einstellungen")));
        }
        if let Some(mb) = self.max_memory_mb {
            settings::validate_memory(mb)?;
        }
        if let Some(p) = &self.java_path {
            settings::validate_java_path(p)?;
        }
        if let Some(a) = &self.jvm_args {
            settings::validate_jvm_args(a)?;
        }
        if let Some(r) = &self.resolution {
            r.validate()?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Instance {
    pub id: String,
    pub name: String,
    pub game_version: String,
    pub loader: Loader,
    pub created_at: DateTime<Utc>,
    pub last_played: Option<DateTime<Utc>>,
    #[serde(default)]
    pub total_play_seconds: u64,
    #[serde(default)]
    pub overrides: InstanceOverrides,
    /// Dateiname des Instanz-Bilds im Instanz-Ordner (siehe [`crate::icon`]).
    #[serde(default)]
    pub icon: Option<String>,
    /// Eigene Gruppe in der Bibliothek (`None` = keine).
    #[serde(default)]
    pub group: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewInstance {
    pub name: String,
    pub game_version: String,
    pub loader: Loader,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInstance {
    pub name: String,
    pub overrides: InstanceOverrides,
}

/// Instanzen liegen als `instances/<id>/instance.json` auf der Platte – so
/// bleibt jeder Instanz-Ordner für sich kopier- und sicherbar.
pub struct InstanceStore {
    paths: Paths,
    /// Serialisiert Schreibzugriffe (ID-Vergabe, Update, Löschen).
    write_lock: Mutex<()>,
}

impl InstanceStore {
    pub fn new(paths: Paths) -> Self {
        Self { paths, write_lock: Mutex::new(()) }
    }

    pub async fn list(&self) -> Result<Vec<Instance>> {
        let dir = self.paths.instances_dir();
        let mut entries = fs::read_dir(&dir).await.map_err(|e| Error::io(&dir, e))?;
        let mut out = Vec::new();

        while let Some(entry) = entries.next_entry().await.map_err(|e| Error::io(&dir, e))? {
            let Some(id) = entry.file_name().to_str().map(str::to_owned) else { continue };
            if validate_id(&id).is_err() {
                continue;
            }
            match fsutil::read_json::<Instance>(&self.paths.instance_file(&id)).await {
                // Der Ordnername ist maßgeblich, nicht die ID in der Datei.
                Ok(Some(inst)) => out.push(Instance { id, ..inst }),
                Ok(None) => {}
                Err(e) => tracing::warn!("Instanz '{id}' übersprungen: {e}"),
            }
        }

        out.sort_by(|a, b| {
            b.last_played
                .cmp(&a.last_played)
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        Ok(out)
    }

    pub async fn get(&self, id: &str) -> Result<Instance> {
        validate_id(id)?;
        fsutil::read_json::<Instance>(&self.paths.instance_file(id))
            .await?
            .map(|inst| Instance { id: id.to_owned(), ..inst })
            .ok_or_else(|| Error::InstanceNotFound(id.to_owned()))
    }

    pub async fn create(&self, new: NewInstance) -> Result<Instance> {
        let name = validate_name(&new.name)?;
        if !is_safe_version_string(&new.game_version) {
            return Err(Error::validation(crate::msg!("instance.invalidGameVersion", "Minecraft-Version enthält ungültige Zeichen")));
        }
        new.loader.validate()?;

        let _guard = self.write_lock.lock().await;
        let id = self.unique_id(&name).await;

        let instance = Instance {
            id: id.clone(),
            name,
            game_version: new.game_version,
            loader: new.loader,
            created_at: Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            overrides: InstanceOverrides::default(),
            icon: None,
            group: None,
        };

        fsutil::ensure_dir(&self.paths.instance_game_dir(&id)).await?;
        fsutil::write_json(&self.paths.instance_file(&id), &instance).await?;
        Ok(instance)
    }

    /// Setzt den Dateinamen des Instanz-Bilds (Datei legt [`crate::icon`] an).
    pub async fn set_icon(&self, id: &str, icon: Option<String>) -> Result<Instance> {
        if icon.as_deref().is_some_and(|n| !crate::icon::is_icon_file_name(n)) {
            return Err(Error::validation(crate::msg!("instance.invalidImageName", "Ungültiger Bildname")));
        }
        let _guard = self.write_lock.lock().await;
        let mut instance = self.get(id).await?;
        instance.icon = icon;
        fsutil::write_json(&self.paths.instance_file(id), &instance).await?;
        Ok(instance)
    }

    /// Wechselt Minecraft-Version und/oder Modloader. Prüfen, ob die Version
    /// existiert und die Instanz nicht läuft, muss der Aufrufer.
    pub async fn set_version(&self, id: &str, game_version: &str, loader: Loader) -> Result<Instance> {
        if !is_safe_version_string(game_version) {
            return Err(Error::validation(crate::msg!("instance.invalidGameVersion", "Minecraft-Version enthält ungültige Zeichen")));
        }
        loader.validate()?;
        let _guard = self.write_lock.lock().await;
        let mut instance = self.get(id).await?;
        instance.game_version = game_version.to_owned();
        instance.loader = loader;
        fsutil::write_json(&self.paths.instance_file(id), &instance).await?;
        Ok(instance)
    }

    /// Setzt die eigene Gruppe (`None` oder leer = keine Gruppe).
    pub async fn set_group(&self, id: &str, group: Option<&str>) -> Result<Instance> {
        let group = validate_group(group)?;
        let _guard = self.write_lock.lock().await;
        let mut instance = self.get(id).await?;
        instance.group = group;
        fsutil::write_json(&self.paths.instance_file(id), &instance).await?;
        Ok(instance)
    }

    pub async fn update(&self, id: &str, update: UpdateInstance) -> Result<Instance> {
        let name = validate_name(&update.name)?;
        let overrides = update.overrides.normalized();
        overrides.validate()?;

        let _guard = self.write_lock.lock().await;
        let mut instance = self.get(id).await?;
        instance.name = name;
        instance.overrides = overrides;
        fsutil::write_json(&self.paths.instance_file(id), &instance).await?;
        Ok(instance)
    }

    pub async fn touch_last_played(&self, id: &str) -> Result<()> {
        let _guard = self.write_lock.lock().await;
        let mut instance = self.get(id).await?;
        instance.last_played = Some(Utc::now());
        fsutil::write_json(&self.paths.instance_file(id), &instance).await
    }

    pub async fn add_play_time(&self, id: &str, seconds: u64) -> Result<()> {
        let _guard = self.write_lock.lock().await;
        let mut instance = self.get(id).await?;
        instance.total_play_seconds = instance.total_play_seconds.saturating_add(seconds);
        fsutil::write_json(&self.paths.instance_file(id), &instance).await
    }

    /// Löscht die Instanz inklusive Welten, Mods und Screenshots.
    pub async fn delete(&self, id: &str) -> Result<()> {
        let _guard = self.write_lock.lock().await;
        self.get(id).await?;
        let dir = self.paths.instance_dir(id);
        fs::remove_dir_all(&dir).await.map_err(|e| Error::io(&dir, e))
    }

    async fn unique_id(&self, name: &str) -> String {
        let base = slugify(name);
        let mut candidate = base.clone();
        let mut n = 2u32;
        while fs::try_exists(self.paths.instance_dir(&candidate)).await.unwrap_or(true) {
            let suffix = format!("-{n}");
            let keep = MAX_ID_LEN - suffix.len();
            candidate = format!("{}{suffix}", &base[..base.len().min(keep)]);
            n += 1;
        }
        candidate
    }
}

/// IDs werden zu Ordnernamen – deshalb strikt `[a-z0-9-]`, kein führender
/// Bindestrich, keine Windows-Gerätenamen.
pub fn validate_id(id: &str) -> Result<()> {
    let ok = !id.is_empty()
        && id.len() <= MAX_ID_LEN
        && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        && !id.starts_with('-')
        && !id.ends_with('-')
        && !is_reserved_windows_name(id);
    if ok { Ok(()) } else { Err(Error::validation(crate::msg!("instance.invalidId", "Ungültige Instanz-ID"))) }
}

fn validate_name(name: &str) -> Result<String> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > MAX_NAME_LEN {
        return Err(Error::validation(crate::msg!(
            "instance.nameLength",
            "Der Name muss zwischen 1 und {max} Zeichen lang sein",
            max = MAX_NAME_LEN
        )));
    }
    if name.chars().any(char::is_control) {
        return Err(Error::validation(crate::msg!("instance.nameInvalidChars", "Der Name enthält ungültige Zeichen")));
    }
    Ok(name.to_owned())
}

/// Gruppennamen: getrimmt, 1–32 Zeichen, keine Steuerzeichen; leer = keine Gruppe.
pub fn validate_group(group: Option<&str>) -> Result<Option<String>> {
    let Some(group) = group.map(str::trim).filter(|g| !g.is_empty()) else { return Ok(None) };
    if group.chars().count() > MAX_GROUP_LEN || group.chars().any(char::is_control) {
        return Err(Error::validation(crate::msg!(
            "instance.groupInvalid",
            "Gruppennamen: höchstens {max} Zeichen, keine Sonderzeichen",
            max = MAX_GROUP_LEN
        )));
    }
    Ok(Some(group.to_owned()))
}

/// Versions-IDs landen später in Pfaden (`versions/<id>/`) und URLs.
fn is_safe_version_string(v: &str) -> bool {
    !v.is_empty()
        && v.len() <= 64
        && v.trim() == v
        && !v.contains("..")
        && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+' | ' '))
}

fn slugify(name: &str) -> String {
    let mut slug = String::new();
    for c in name.chars() {
        let mapped = match c {
            'ä' | 'Ä' => "ae",
            'ö' | 'Ö' => "oe",
            'ü' | 'Ü' => "ue",
            'ß' => "ss",
            c if c.is_ascii_alphanumeric() => {
                slug.push(c.to_ascii_lowercase());
                continue;
            }
            _ => "-",
        };
        if mapped != "-" || !slug.ends_with('-') {
            slug.push_str(mapped);
        }
    }
    let mut slug: String = slug.trim_matches('-').chars().take(MAX_ID_LEN - 8).collect();
    slug = slug.trim_matches('-').to_owned();
    if slug.is_empty() || is_reserved_windows_name(&slug) {
        slug = format!("instanz-{}", &uuid::Uuid::new_v4().simple().to_string()[..8]);
    }
    slug
}

fn is_reserved_windows_name(s: &str) -> bool {
    matches!(s, "con" | "prn" | "aux" | "nul")
        || (s.len() == 4
            && (s.starts_with("com") || s.starts_with("lpt"))
            && s.as_bytes()[3].is_ascii_digit())
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn store() -> (tempfile::TempDir, InstanceStore) {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        paths.ensure().await.unwrap();
        (dir, InstanceStore::new(paths))
    }

    fn new_instance(name: &str) -> NewInstance {
        NewInstance { name: name.into(), game_version: "1.21.1".into(), loader: Loader::vanilla() }
    }

    #[test]
    fn slugs() {
        assert_eq!(slugify("Mein Überlebens-Pack!"), "mein-ueberlebens-pack");
        assert!(slugify("???").starts_with("instanz-"));
        assert!(slugify("CON").starts_with("instanz-"));
        assert!(validate_id(&slugify(&"x".repeat(200))).is_ok());
    }

    #[test]
    fn id_validation_blocks_traversal() {
        for bad in ["", "..", "../x", "a/b", "a\\b", "A", "-a", "a-", "nul", "com1", "a.b"] {
            assert!(validate_id(bad).is_err(), "{bad:?} hätte abgelehnt werden müssen");
        }
        assert!(validate_id("survival-2").is_ok());
    }

    #[test]
    fn loader_validation() {
        assert!(Loader { kind: LoaderKind::Vanilla, version: Some("1".into()) }.validate().is_err());
        assert!(Loader { kind: LoaderKind::Fabric, version: None }.validate().is_ok());
        assert!(Loader { kind: LoaderKind::Forge, version: Some("47.4.0".into()) }.validate().is_ok());
        assert!(Loader { kind: LoaderKind::Forge, version: Some("../evil".into()) }.validate().is_err());
    }

    #[tokio::test]
    async fn crud() {
        let (_dir, store) = store().await;

        let a = store.create(new_instance("Survival")).await.unwrap();
        let b = store.create(new_instance("Survival")).await.unwrap();
        assert_eq!(a.id, "survival");
        assert_eq!(b.id, "survival-2");
        assert_eq!(store.list().await.unwrap().len(), 2);

        let updated = store
            .update(
                &a.id,
                UpdateInstance {
                    name: "  Hardcore ".into(),
                    overrides: InstanceOverrides { max_memory_mb: Some(8192), ..Default::default() },
                },
            )
            .await
            .unwrap();
        assert_eq!(updated.name, "Hardcore");
        assert_eq!(store.get(&a.id).await.unwrap().overrides.max_memory_mb, Some(8192));

        store.touch_last_played(&b.id).await.unwrap();
        assert_eq!(store.list().await.unwrap()[0].id, b.id);
        store.add_play_time(&b.id, 90).await.unwrap();
        store.add_play_time(&b.id, 30).await.unwrap();
        assert_eq!(store.get(&b.id).await.unwrap().total_play_seconds, 120);

        store.delete(&a.id).await.unwrap();
        assert!(matches!(store.get(&a.id).await, Err(Error::InstanceNotFound(_))));
        assert!(matches!(store.delete("../..").await, Err(Error::Validation(_))));
    }

    #[tokio::test]
    async fn rejects_bad_input() {
        let (_dir, store) = store().await;
        assert!(store.create(new_instance("   ")).await.is_err());
        assert!(store.create(new_instance(&"x".repeat(65))).await.is_err());
        let mut bad = new_instance("ok");
        bad.game_version = "../../etc".into();
        assert!(store.create(bad).await.is_err());
    }

    #[test]
    fn group_validation() {
        assert_eq!(validate_group(None).unwrap(), None);
        assert_eq!(validate_group(Some("   ")).unwrap(), None);
        assert_eq!(validate_group(Some(" PvP ")).unwrap().as_deref(), Some("PvP"));
        assert!(validate_group(Some(&"g".repeat(33))).is_err());
        assert!(validate_group(Some("a\nb")).is_err());
    }

    #[tokio::test]
    async fn groups_and_new_overrides_roundtrip() {
        let (_dir, store) = store().await;
        let a = store.create(new_instance("Gruppiert")).await.unwrap();
        assert_eq!(store.set_group(&a.id, Some("Modpacks")).await.unwrap().group.as_deref(), Some("Modpacks"));
        assert_eq!(store.set_group(&a.id, Some("")).await.unwrap().group, None);

        let overrides = InstanceOverrides {
            update_channel: Some(UpdateChannel::Beta),
            fullscreen: Some(true),
            hooks: Some(LaunchHooks { pre_launch: Some("  ".into()), wrapper: Some("w".into()), ..Default::default() }),
            sync_separate: vec![SyncItem::Options, SyncItem::Options, SyncItem::Hotbar],
            ..Default::default()
        };
        let updated = store.update(&a.id, UpdateInstance { name: "Gruppiert".into(), overrides }).await.unwrap();
        assert_eq!(updated.overrides.sync_separate, [SyncItem::Options, SyncItem::Hotbar]);
        assert_eq!(updated.overrides.hooks.as_ref().unwrap().pre_launch, None);
        assert_eq!(store.get(&a.id).await.unwrap().overrides.channel(), UpdateChannel::Beta);

        let bad = InstanceOverrides {
            hooks: Some(LaunchHooks { post_exit: Some("a\nb".into()), ..Default::default() }),
            ..Default::default()
        };
        assert!(store.update(&a.id, UpdateInstance { name: "x".into(), overrides: bad }).await.is_err());
    }

    #[test]
    fn channels() {
        assert!(UpdateChannel::Release.allows("release") && !UpdateChannel::Release.allows("beta"));
        assert!(UpdateChannel::Beta.allows("beta") && !UpdateChannel::Beta.allows("alpha"));
        assert!(UpdateChannel::Alpha.allows("alpha"));
        assert_eq!(serde_json::to_value(UpdateChannel::Beta).unwrap(), "beta");
        // Alte instance.json ohne neue Felder lädt weiter.
        let old: InstanceOverrides = serde_json::from_str(r#"{"maxMemoryMb":4096}"#).unwrap();
        assert_eq!(old.channel(), UpdateChannel::Release);
    }

    #[test]
    fn serializes_camel_case() {
        let json = serde_json::to_value(Loader { kind: LoaderKind::NeoForge, version: None }).unwrap();
        assert_eq!(json["kind"], "neoforge");
    }
}
