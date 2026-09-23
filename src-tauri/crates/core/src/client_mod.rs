//! TRS Client: unser eigener In-Game-Mod (HUD, Zoom, Fullbright, Menü).
//! Der Launcher bringt die Jars samt `builds.json` mit und legt beim Start
//! automatisch den passenden Build in jede Instanz – inklusive benötigter
//! Abhängigkeiten wie Fabric API. Neuere Versionen kommen über den eigenen
//! Update-Kanal ([`crate::client_mod_update`]) auch ohne Launcher-Update.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::client_mod_update::{self, ClientModUpdater};
use crate::content::{self, ContentKind};
use crate::download;
use crate::history::{self, HistoryEntry, HistoryKind};
use crate::instance::{Instance, LoaderKind};
use crate::paths::Paths;
use crate::settings::{Accent, Theme, UiSettings};
use crate::{Error, Result, fsutil, modrinth};

/// So heißt die Datei im Mods-Ordner – fester Name, damit Updates sie ersetzen.
const INSTALLED_NAME: &str = "trsclient.jar";
const FABRIC_API_PROJECT: &str = "P7dR8mSH";
const MANIFEST: &str = "builds.json";
/// Zuletzt in die Instanz gelegte TRS-Client-Version (für den Verlauf).
const VERSION_MARKER: &str = "trsclient-version";
/// Farben des Launchers für das In-Game-Menü (relativ zum Config-Ordner der Instanz).
const THEME_FILE: &str = "trsclient/launcher-theme.json";

/// Ein Build aus dem Manifest (erzeugt von `scripts/publish-client-mod.mjs`
/// aus den `collectLauncherJars`-Ausgaben).
#[derive(Debug, Clone, Deserialize)]
pub struct Build {
    /// `fabric`, `forge`, `neoforge`
    pub loader: String,
    /// Alle exakten Spielversionen, die dieser Jar unterstützt.
    pub minecraft: Vec<String>,
    pub file: String,
    #[serde(default)]
    pub requires: Vec<String>,
    /// Pflicht im Update-Kanal, in der mitgelieferten Datei zur Kontrolle.
    #[serde(default)]
    pub sha256: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    /// Stammt aus dem Update-Kanal (nicht Teil der Datei).
    #[serde(skip)]
    pub from_channel: bool,
}

/// `builds.json` bzw. `client-mod.json`: `{ "version": "0.3.0", "builds": [...] }`.
/// Die alte Form (nur die Liste, ohne Version) wird weiter gelesen.
#[derive(Debug, Clone, Default)]
pub struct Manifest {
    /// Version des Mods (`mod_version` aus client-mod/gradle.properties); leer = unbekannt.
    pub version: String,
    pub builds: Vec<Build>,
}

/// Liest ein Manifest in neuer oder alter Form; Builds mit unsicheren
/// Dateinamen fallen heraus. `None`, wenn es gar nicht lesbar ist.
pub fn parse_manifest(bytes: &[u8]) -> Option<Manifest> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Raw {
        Current { version: String, builds: Vec<Build> },
        Legacy(Vec<Build>),
    }
    let (version, mut builds) = match serde_json::from_slice::<Raw>(bytes).ok()? {
        Raw::Current { version, builds } => (version, builds),
        Raw::Legacy(builds) => (String::new(), builds),
    };
    // Dateinamen stammen aus unserer eigenen Datei – trotzdem nur einfache Namen zulassen.
    builds.retain(|b| validate_build_file(&b.file).is_ok());
    Some(Manifest { version, builds })
}

/// Nur einfache Jar-Namen ohne Pfadanteile.
pub fn validate_build_file(file: &str) -> Result<()> {
    content::validate_file_name(ContentKind::Mod, file)
}

/// Das mitgelieferte Manifest aus dem Ressourcen-Ordner; kaputt oder fehlend = leer.
pub fn load_manifest(dir: &Path) -> Manifest {
    std::fs::read(dir.join(MANIFEST)).ok().and_then(|b| parse_manifest(&b)).unwrap_or_default()
}

/// Was der Launcher gerade vom TRS Client kennt: die mitgelieferten Builds und –
/// falls neuer – die aus dem Update-Kanal.
#[derive(Debug, Clone, Default)]
pub struct Catalog {
    bundled_dir: Option<PathBuf>,
    bundled: Manifest,
    channel: Option<Manifest>,
    /// Kanal-Builds zuerst, danach die mitgelieferten (Rückfall für Versionen,
    /// die der Kanal nicht oder nicht mehr hat).
    merged: Vec<Build>,
}

impl Catalog {
    pub async fn load(bundled_dir: Option<&Path>, updater: Option<&ClientModUpdater>) -> Self {
        let bundled = bundled_dir.map(load_manifest).unwrap_or_default();
        let channel = match updater {
            Some(u) => u.stored().await.filter(|c| client_mod_update::is_newer(&c.version, &bundled.version)),
            None => None,
        };
        let merged = channel
            .iter()
            .flat_map(|c| c.builds.iter().cloned().map(|b| Build { from_channel: true, ..b }))
            .chain(bundled.builds.iter().cloned())
            .collect();
        Self { bundled_dir: bundled_dir.map(Path::to_owned), bundled, channel, merged }
    }

    /// Alle bekannten Builds, neueste Quelle zuerst.
    pub fn builds(&self) -> &[Build] {
        &self.merged
    }

    pub fn bundled_version(&self) -> Option<&str> {
        Some(self.bundled.version.as_str()).filter(|v| !v.is_empty())
    }

    /// Kanal-Version, sofern neuer als die mitgelieferte.
    pub fn channel_version(&self) -> Option<&str> {
        self.channel.as_ref().map(|c| c.version.as_str())
    }

    pub fn status(&self) -> ClientModStatus {
        ClientModStatus {
            bundled: self.bundled_version().map(str::to_owned),
            update: self.channel_version().map(str::to_owned),
        }
    }

    /// Jar-Datei für die Instanz: bevorzugt aus dem Kanal (lädt bei Bedarf),
    /// bei jedem Fehler aus dem Launcher-Paket.
    /// `installed`: die Kopie in der Instanz – ist sie schon genau dieser Kanal-Build,
    /// bleibt sie auch dann, wenn der Cache fehlt und gerade nichts ladbar ist.
    async fn resolve(
        &self,
        updater: Option<&ClientModUpdater>,
        kind: LoaderKind,
        game_version: &str,
        installed: &Path,
    ) -> Option<Resolved> {
        if let (Some(channel), Some(updater)) = (&self.channel, updater)
            && let Some(build) = build_for(&self.merged, kind, game_version).filter(|b| b.from_channel)
        {
            let resolved = |path| Resolved { path, version: channel.version.clone(), build: build.clone() };
            match updater.jar(channel, build).await {
                Ok(path) => return Some(resolved(path)),
                Err(e) => {
                    if let (Some(sha256), Some(size)) = (build.sha256.as_deref(), build.size)
                        && client_mod_update::file_matches(installed, sha256, size).await
                    {
                        return Some(resolved(installed.to_owned()));
                    }
                    tracing::warn!("TRS Client {} nicht ladbar, nehme den mitgelieferten: {e}", channel.version);
                }
            }
        }
        let dir = self.bundled_dir.as_deref()?;
        let build = build_for(&self.bundled.builds, kind, game_version)?;
        let path = dir.join(&build.file);
        if !path.is_file() {
            tracing::warn!("TRS Client fehlt im Launcher-Paket: {}", path.display());
            return None;
        }
        Some(Resolved { path, version: self.bundled.version.clone(), build: build.clone() })
    }
}

/// Für die Einstellungen: mitgelieferte Version und – falls neuer – die aus dem Kanal.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientModStatus {
    pub bundled: Option<String>,
    /// Neuere, geprüfte Version aus dem Update-Kanal (wird beim nächsten Start verwendet).
    pub update: Option<String>,
}

struct Resolved {
    path: PathBuf,
    version: String,
    build: Build,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Availability {
    Available,
    Unsupported,
    Disabled,
}

/// Builds aus `builds.json` im Ressourcen-Ordner; kaputt oder fehlend = leer.
pub fn load_builds(dir: &Path) -> Vec<Build> {
    load_manifest(dir).builds
}

fn loader_matches(build: &str, kind: LoaderKind) -> bool {
    match build {
        // Quilt lädt Fabric-Mods.
        "fabric" => matches!(kind, LoaderKind::Fabric | LoaderKind::Quilt),
        "forge" => kind == LoaderKind::Forge,
        "neoforge" => kind == LoaderKind::NeoForge,
        _ => false,
    }
}

pub fn build_for<'a>(builds: &'a [Build], kind: LoaderKind, game_version: &str) -> Option<&'a Build> {
    builds.iter().find(|b| loader_matches(&b.loader, kind) && b.minecraft.iter().any(|v| v == game_version))
}

/// Für welchen Loader es einen TRS-Client-Build für diese Version gibt
/// (bevorzugt Fabric) – für die TRS-Optimierung von Vanilla-Instanzen.
pub fn boost_loader(builds: &[Build], game_version: &str) -> Option<LoaderKind> {
    [LoaderKind::Fabric, LoaderKind::Forge, LoaderKind::NeoForge]
        .into_iter()
        .find(|&k| build_for(builds, k, game_version).is_some())
}

pub fn availability(builds: &[Build], instance: &Instance) -> Availability {
    if instance.overrides.trs_client == Some(false) {
        Availability::Disabled
    } else if build_for(builds, instance.loader.kind, &instance.game_version).is_some() {
        Availability::Available
    } else {
        Availability::Unsupported
    }
}

/// Sorgt vor dem Start dafür, dass der TRS Client (und seine Abhängigkeiten)
/// in der Instanz liegt – bzw. entfernt ihn, wenn er abgeschaltet wurde oder
/// für die Version keinen Build mehr hat. Quelle ist die neueste geprüfte
/// Version: Update-Kanal (falls neuer und ladbar), sonst das Launcher-Paket.
pub async fn sync(
    http: &reqwest::Client,
    paths: &Paths,
    bundled_dir: Option<&Path>,
    updater: Option<&ClientModUpdater>,
    instance: &Instance,
    ui: &UiSettings,
) -> Result<()> {
    let catalog = Catalog::load(bundled_dir, updater).await;
    // Weder Launcher-Paket noch Kanal bekannt: nichts anfassen.
    if bundled_dir.is_none() && catalog.channel.is_none() {
        return Ok(());
    }
    let mods = content::content_dir(paths, &instance.id, ContentKind::Mod);
    let target = mods.join(INSTALLED_NAME);
    let disabled_copy = mods.join(format!("{INSTALLED_NAME}.disabled"));

    let build = build_for(catalog.builds(), instance.loader.kind, &instance.game_version);
    if build.is_none() || instance.overrides.trs_client == Some(false) {
        // Abgeschaltet oder nicht passend (z. B. nach Versionswechsel): alte Kopie weg.
        for file in [&target, &disabled_copy] {
            if file.is_file() {
                tokio::fs::remove_file(file).await.map_err(|e| Error::io(file, e))?;
            }
        }
        for file in [theme_path(paths, &instance.id), paths.instance_dir(&instance.id).join(VERSION_MARKER)] {
            if file.is_file() {
                let _ = tokio::fs::remove_file(&file).await;
            }
        }
        return Ok(());
    }

    // Es gibt einen Build, aber keine Datei (Kanal-Jar nicht ladbar und nichts
    // mitgeliefert): Instanz so lassen, wie sie ist.
    let Some(Resolved { path: source, version, build }) =
        catalog.resolve(updater, instance.loader.kind, &instance.game_version, &target).await
    else {
        return Ok(());
    };
    // In der Mod-Liste deaktiviert (trsclient.jar.disabled): Das bleibt so – nur die
    // deaktivierte Kopie wird aktuell gehalten, damit ein späteres Einschalten passt.
    if disabled_copy.is_file() && !target.exists() {
        if !same_file(&source, &disabled_copy).await {
            tokio::fs::copy(&source, &disabled_copy).await.map_err(|e| Error::io(&disabled_copy, e))?;
        }
        return Ok(());
    }
    // Nur kopieren, wenn sich etwas geändert hat (neue Version aus Kanal oder Launcher-Update).
    let marker = paths.instance_dir(&instance.id).join(VERSION_MARKER);
    if !same_file(&source, &target).await {
        let replaced = target.is_file();
        tokio::fs::create_dir_all(&mods).await.map_err(|e| Error::io(&mods, e))?;
        tokio::fs::copy(&source, &target).await.map_err(|e| Error::io(&target, e))?;
        let _ = tokio::fs::remove_file(&disabled_copy).await;
        let label = if version.is_empty() { build.file.clone() } else { format!("{} {version}", build.file) };
        tracing::info!("TRS Client ({label}) in '{}' installiert", instance.id);
        let previous = tokio::fs::read_to_string(&marker).await.ok().map(|v| v.trim().to_owned());
        if replaced && !version.is_empty() && previous.as_deref() != Some(version.as_str()) {
            // Im Verlauf der Instanz sichtbar: "TRS Client aktualisiert 0.2.0 → 0.3.0".
            let mut entry = HistoryEntry::new(HistoryKind::ModUpdated).subject("TRS Client").to(&version);
            if let Some(previous) = previous.filter(|p| client_mod_update::is_valid_version(p)) {
                if client_mod_update::is_newer(&previous, &version) {
                    entry = entry.detail("downgrade");
                }
                entry = entry.from(previous);
            }
            history::record(paths, &instance.id, entry).await;
        }
    }
    if !version.is_empty() && tokio::fs::read_to_string(&marker).await.ok().as_deref() != Some(version.as_str()) {
        let _ = fsutil::write_atomic(&marker, version.as_bytes()).await;
    }

    // Der Mod übernimmt Thema und Akzentfarbe des Launchers.
    if let Err(e) = write_theme(paths, &instance.id, ui).await {
        tracing::warn!("Farben für den TRS Client konnten nicht geschrieben werden: {e}");
    }

    if build.requires.iter().any(|r| r == "fabric-api") {
        ensure_fabric_api(http, paths, instance).await;
    }
    Ok(())
}

/// Datei mit den Farben des Launchers in der Instanz.
fn theme_path(paths: &Paths, instance_id: &str) -> std::path::PathBuf {
    paths.instance_game_dir(instance_id).join("config").join(THEME_FILE)
}

/// Farben, die der Mod liest: Thema, Akzent-Name und der dazugehörige Farbwert.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LauncherTheme {
    version: u32,
    theme: &'static str,
    accent: &'static str,
    /// "#RRGGBB" – passend zum Akzent-Namen, damit der Mod nichts raten muss.
    accent_color: &'static str,
}

/// Schreibt `config/trsclient/launcher-theme.json` in die Instanz (nur bekannte, feste Werte).
pub async fn write_theme(paths: &Paths, instance_id: &str, ui: &UiSettings) -> Result<()> {
    let file = theme_path(paths, instance_id);
    let dir = file.parent().expect("Elternordner");
    tokio::fs::create_dir_all(dir).await.map_err(|e| Error::io(dir, e))?;
    let theme = LauncherTheme {
        version: 1,
        theme: theme_name(ui.theme),
        accent: accent_name(ui.accent),
        accent_color: accent_color(ui.accent),
    };
    let json = serde_json::to_string_pretty(&theme).map_err(|e| Error::json("launcher-theme.json", e))?;
    tokio::fs::write(&file, json).await.map_err(|e| Error::io(&file, e))
}

/// Name des Themas für den Mod. "System" löst der Launcher auf – im Spiel gilt dann Dunkel.
fn theme_name(theme: Theme) -> &'static str {
    match theme {
        Theme::Dark | Theme::System => "dark",
        Theme::Oled => "oled",
        Theme::Light => "light",
    }
}

fn accent_name(accent: Accent) -> &'static str {
    match accent {
        Accent::Redstone => "redstone",
        Accent::Lamp => "lamp",
        Accent::Emerald => "emerald",
        Accent::Lapis => "lapis",
        Accent::Amethyst => "amethyst",
    }
}

/// Dieselben Farbwerte wie in der Oberfläche (app/assets/css/main.css, `--color-redstone-500`).
fn accent_color(accent: Accent) -> &'static str {
    match accent {
        Accent::Redstone => "#E0281E",
        Accent::Lamp => "#E0900C",
        Accent::Emerald => "#17A34A",
        Accent::Lapis => "#3563E9",
        Accent::Amethyst => "#9B4DDF",
    }
}

async fn same_file(a: &Path, b: &Path) -> bool {
    let (Ok(ma), Ok(mb)) = (tokio::fs::metadata(a).await, tokio::fs::metadata(b).await) else { return false };
    if ma.len() != mb.len() {
        return false;
    }
    match (download::sha1_of_file(a).await, download::sha1_of_file(b).await) {
        (Ok(x), Ok(y)) => x == y,
        _ => false,
    }
}

/// Fabric API nachinstallieren, falls sie fehlt. Offline oder bei Fehlern
/// nur warnen – das Spiel meldet eine fehlende Abhängigkeit dann selbst.
async fn ensure_fabric_api(http: &reqwest::Client, paths: &Paths, instance: &Instance) {
    let installed = content::installed_project_ids(paths, &instance.id).await.unwrap_or_default();
    if installed.iter().any(|id| id == FABRIC_API_PROJECT) || has_fabric_api_file(paths, instance).await {
        return;
    }
    match modrinth::install(http, paths, instance, FABRIC_API_PROJECT, ContentKind::Mod, None).await {
        Ok(_) => tracing::info!("Fabric API für den TRS Client in '{}' installiert", instance.id),
        Err(e) => tracing::warn!("Fabric API konnte nicht installiert werden: {e}"),
    }
}

/// Auch von Hand hinzugefügte Fabric API zählt (z. B. aus einem Import).
async fn has_fabric_api_file(paths: &Paths, instance: &Instance) -> bool {
    content::list(paths, &instance.id, ContentKind::Mod)
        .await
        .map(|items| {
            items.iter().any(|i| {
                i.enabled
                    && (i.title.as_deref() == Some("Fabric API") || i.file_name.to_ascii_lowercase().starts_with("fabric-api"))
            })
        })
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use chrono::Utc;

    use super::*;
    use crate::instance::{InstanceOverrides, Loader};

    fn instance(version: &str, kind: LoaderKind, enabled: Option<bool>) -> Instance {
        Instance {
            id: "test".into(),
            name: "Test".into(),
            game_version: version.into(),
            loader: Loader { kind, version: None },
            created_at: Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            icon: None,
            group: None,
            overrides: InstanceOverrides { trs_client: enabled, ..Default::default() },
        }
    }

    const MANIFEST_JSON: &str = r#"[
        {"loader":"fabric","minecraft":["1.21","1.21.1"],"file":"trsclient-fabric-1.21.jar","requires":["fabric-api"]},
        {"loader":"forge","minecraft":["1.8.9"],"file":"trsclient-forge-1.8.9.jar","requires":[]},
        {"loader":"fabric","minecraft":["1.20.1"],"file":"../boese.jar"}
    ]"#;

    fn bundled(dir: &Path) -> Vec<Build> {
        std::fs::write(dir.join(MANIFEST), MANIFEST_JSON).unwrap();
        load_builds(dir)
    }

    #[test]
    fn manifest_matching() {
        let dir = tempfile::tempdir().unwrap();
        let builds = bundled(dir.path());
        assert_eq!(builds.len(), 2, "unsichere Dateinamen werden verworfen");
        assert_eq!(availability(&builds, &instance("1.21", LoaderKind::Fabric, None)), Availability::Available);
        assert_eq!(availability(&builds, &instance("1.21.1", LoaderKind::Quilt, None)), Availability::Available);
        assert_eq!(availability(&builds, &instance("1.8.9", LoaderKind::Forge, None)), Availability::Available);
        assert_eq!(availability(&builds, &instance("1.8.9", LoaderKind::Fabric, None)), Availability::Unsupported);
        assert_eq!(availability(&builds, &instance("1.21.1", LoaderKind::Fabric, Some(false))), Availability::Disabled);
        assert_eq!(boost_loader(&builds, "1.21.1"), Some(LoaderKind::Fabric));
        assert_eq!(boost_loader(&builds, "1.8.9"), Some(LoaderKind::Forge));
        assert_eq!(boost_loader(&builds, "1.5.2"), None);
        assert!(load_builds(&dir.path().join("fehlt")).is_empty());
    }

    /// In der Mod-Liste deaktiviert = bleibt deaktiviert, wird aber aktuell gehalten.
    #[tokio::test]
    async fn disabled_in_mod_list_stays_disabled() {
        let bundle = tempfile::tempdir().unwrap();
        bundled(bundle.path());
        std::fs::write(bundle.path().join("trsclient-forge-1.8.9.jar"), b"neu").unwrap();
        let root = tempfile::tempdir().unwrap();
        let paths = Paths::new(root.path());
        let inst = instance("1.8.9", LoaderKind::Forge, None);
        let http = reqwest::Client::new();
        let ui = UiSettings::default();
        let mods = content::content_dir(&paths, &inst.id, ContentKind::Mod);

        sync(&http, &paths, Some(bundle.path()), None, &inst, &ui).await.unwrap();
        assert_eq!(std::fs::read(mods.join(INSTALLED_NAME)).unwrap(), b"neu");

        // Nutzer schaltet ihn in der Mod-Liste aus, danach kommt ein Launcher-Update.
        std::fs::rename(mods.join(INSTALLED_NAME), mods.join("trsclient.jar.disabled")).unwrap();
        std::fs::write(bundle.path().join("trsclient-forge-1.8.9.jar"), b"neuer").unwrap();
        sync(&http, &paths, Some(bundle.path()), None, &inst, &ui).await.unwrap();
        assert!(!mods.join(INSTALLED_NAME).exists(), "darf nicht wieder eingeschaltet werden");
        assert_eq!(std::fs::read(mods.join("trsclient.jar.disabled")).unwrap(), b"neuer");
    }

    /// Die mitgelieferte `builds.json`: jede Datei existiert, jede (Loader, Version)-Kombination
    /// ist eindeutig und kein Eintrag wurde wegen eines unsicheren Namens verworfen.
    #[test]
    fn bundled_manifest_is_consistent() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../resources/client-mod");
        let raw: serde_json::Value = serde_json::from_slice(&std::fs::read(dir.join(MANIFEST)).unwrap()).unwrap();
        let manifest = load_manifest(&dir);
        let builds = manifest.builds;
        assert_eq!(builds.len(), raw["builds"].as_array().unwrap().len(), "unsichere Dateinamen in builds.json");
        assert!(client_mod_update::is_valid_version(&manifest.version), "Version: {}", manifest.version);
        let mut seen = std::collections::HashSet::new();
        for b in &builds {
            assert!(matches!(b.loader.as_str(), "fabric" | "forge" | "neoforge"), "{}", b.loader);
            assert!(dir.join(&b.file).is_file(), "{} fehlt", b.file);
            let data = std::fs::read(dir.join(&b.file)).unwrap();
            assert_eq!(b.size, Some(data.len() as u64), "{}: Größe", b.file);
            assert_eq!(b.sha256.as_deref(), Some(client_mod_update::tests::sha256_hex(&data).as_str()), "{}: SHA-256", b.file);
            assert!(!b.minecraft.is_empty(), "{}", b.file);
            for v in &b.minecraft {
                assert!(seen.insert((b.loader.clone(), v.clone())), "doppelt: {} {v}", b.loader);
            }
        }
    }

    /// Die mitgelieferte builds.json deckt jede Fabric-Version von 1.14.4 bis 26.3 ab,
    /// und jede genannte Jar liegt tatsächlich im Ressourcen-Ordner.
    #[test]
    fn bundled_manifest_covers_all_fabric_versions() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../resources/client-mod");
        let builds = load_builds(&dir);
        let versions = [
            "1.14.4", "1.15.2", "1.16.2", "1.16.3", "1.16.4", "1.16.5", "1.17", "1.17.1", "1.18", "1.18.1", "1.18.2",
            "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20.1", "1.20.6", "1.21", "1.21.1", "1.21.11", "26.1",
            "26.3",
        ];
        for v in versions {
            let build = build_for(&builds, LoaderKind::Fabric, v).unwrap_or_else(|| panic!("kein Fabric-Build für {v}"));
            assert!(dir.join(&build.file).is_file(), "{} fehlt", build.file);
            assert_eq!(boost_loader(&builds, v), Some(LoaderKind::Fabric));
        }
    }

    /// Fabric-Jars laufen im echten Spiel mit Intermediary-Namen (`net/minecraft/class_…`).
    /// Bleibt ein Mojang-Name stehen (etwa ein Mixin-Ziel ohne vollständige Signatur, das
    /// Loom nicht umschreibt), findet Mixin nichts und das Spiel stürzt beim Start ab –
    /// im Entwicklungsmodus fällt das nicht auf, deshalb prüft es dieser Test.
    #[test]
    fn bundled_fabric_jars_contain_no_mojang_names() {
        use std::io::Read;
        const MOJANG: &[&str] = &[
            "net/minecraft/client/renderer/",
            "net/minecraft/client/gui/",
            "net/minecraft/client/player/",
            "net/minecraft/client/multiplayer/",
            "net/minecraft/world/",
            "net/minecraft/network/",
            "net/minecraft/server/",
            "net/minecraft/core/",
            "net/minecraft/util/",
            "net/minecraft/resources/",
            "net/minecraft/sounds/",
        ];
        // Klassen, die auch in Intermediary ihren Namen behalten.
        const KEEPS_NAME: &[&str] = &["net/minecraft/server/MinecraftServer", "net/minecraft/server/Main"];
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../resources/client-mod");
        let mut problems = Vec::new();
        // Ab 26.1 liefert Mojang das Spiel unverschleiert aus – dort sind Mojang-Namen richtig.
        let obfuscated = |b: &&Build| b.minecraft.iter().all(|v| v.starts_with("1."));
        for build in load_builds(&dir).iter().filter(|b| b.loader == "fabric").filter(obfuscated) {
            let file = std::fs::File::open(dir.join(&build.file)).unwrap();
            let mut zip = zip::ZipArchive::new(file).unwrap();
            for i in 0..zip.len() {
                let mut entry = zip.by_index(i).unwrap();
                if !entry.name().ends_with(".class") {
                    continue;
                }
                let name = entry.name().to_owned();
                let mut bytes = Vec::new();
                entry.read_to_end(&mut bytes).unwrap();
                for needle in MOJANG {
                    for (at, _) in bytes.windows(needle.len()).enumerate().filter(|(_, w)| *w == needle.as_bytes()) {
                        let class: String = bytes[at..]
                            .iter()
                            .take_while(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'_' | b'$'))
                            .map(|&b| b as char)
                            .collect();
                        if !KEEPS_NAME.contains(&class.as_str()) {
                            problems.push(format!("{}: {name} enthält {class}", build.file));
                        }
                    }
                }
            }
        }
        assert!(problems.is_empty(), "nicht umgeschriebene Mojang-Namen:\n{}", problems.join("\n"));
    }

    #[tokio::test]
    async fn writes_launcher_colours_for_the_mod() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("root"));
        let mut ui = UiSettings { accent: Accent::Emerald, theme: Theme::Oled, ..UiSettings::default() };
        write_theme(&paths, "test", &ui).await.unwrap();

        let file = paths.instance_game_dir("test").join("config/trsclient/launcher-theme.json");
        let json: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
        assert_eq!(json["version"], 1);
        assert_eq!(json["theme"], "oled");
        assert_eq!(json["accent"], "emerald");
        assert_eq!(json["accentColor"], "#17A34A");

        // "System" gibt es im Spiel nicht – dort gilt das dunkle Thema.
        ui.theme = Theme::System;
        ui.accent = Accent::Redstone;
        write_theme(&paths, "test", &ui).await.unwrap();
        let json: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
        assert_eq!(json["theme"], "dark");
        assert_eq!(json["accentColor"], "#E0281E");
    }

    #[test]
    fn every_accent_has_a_colour() {
        for accent in [Accent::Redstone, Accent::Lamp, Accent::Emerald, Accent::Lapis, Accent::Amethyst] {
            let hex = accent_color(accent);
            assert!(hex.len() == 7 && hex.starts_with('#'), "{accent:?} → {hex}");
            assert!(hex[1..].chars().all(|c| c.is_ascii_hexdigit()), "{accent:?} → {hex}");
            assert!(!accent_name(accent).is_empty());
        }
    }

    #[tokio::test]
    async fn installs_updates_and_removes_jar() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("root"));
        paths.ensure().await.unwrap();
        let res = dir.path().join("bundled");
        tokio::fs::create_dir_all(&res).await.unwrap();
        bundled(&res);
        tokio::fs::write(res.join("trsclient-fabric-1.21.jar"), b"v1").await.unwrap();

        // Offline-Client: die Fabric-API-Nachinstallation schlägt fehl, darf aber nichts blockieren.
        let http = reqwest::Client::builder().proxy(reqwest::Proxy::all("http://127.0.0.1:9").unwrap()).build().unwrap();
        let mods = content::content_dir(&paths, "test", ContentKind::Mod);
        let ui = UiSettings::default();

        let on = instance("1.21.1", LoaderKind::Fabric, None);
        sync(&http, &paths, Some(&res), None, &on, &ui).await.unwrap();
        assert_eq!(tokio::fs::read(mods.join(INSTALLED_NAME)).await.unwrap(), b"v1");
        assert!(theme_path(&paths, "test").is_file(), "Farben des Launchers liegen in der Instanz");

        tokio::fs::write(res.join("trsclient-fabric-1.21.jar"), b"v2").await.unwrap();
        sync(&http, &paths, Some(&res), None, &on, &ui).await.unwrap();
        assert_eq!(tokio::fs::read(mods.join(INSTALLED_NAME)).await.unwrap(), b"v2");

        // Versionswechsel auf eine Version ohne Build: alte Kopie verschwindet.
        let other = instance("1.20.4", LoaderKind::Fabric, None);
        sync(&http, &paths, Some(&res), None, &other, &ui).await.unwrap();
        assert!(!mods.join(INSTALLED_NAME).exists());

        sync(&http, &paths, Some(&res), None, &on, &ui).await.unwrap();
        let off = instance("1.21.1", LoaderKind::Fabric, Some(false));
        sync(&http, &paths, Some(&res), None, &off, &ui).await.unwrap();
        assert!(!mods.join(INSTALLED_NAME).exists());
        assert!(!theme_path(&paths, "test").exists(), "abgeschaltet: auch die Farbdatei ist weg");
    }

    // --- Update-Kanal ---------------------------------------------------------

    use crate::client_mod_update::tests::{TestKey, TestServer, publish};

    /// Mitgeliefertes Manifest im neuen Format mit einem Fabric-Build (1.21/1.21.1).
    fn bundle_version(dir: &Path, version: &str, jar: &[u8]) {
        let manifest = serde_json::json!({
            "version": version,
            "builds": [{"loader":"fabric","minecraft":["1.21","1.21.1"],"file":"trsclient-fabric-1.21.jar","requires":["fabric-api"]}]
        });
        std::fs::create_dir_all(dir).unwrap();
        std::fs::write(dir.join(MANIFEST), manifest.to_string()).unwrap();
        std::fs::write(dir.join("trsclient-fabric-1.21.jar"), jar).unwrap();
    }

    fn offline_http() -> reqwest::Client {
        reqwest::Client::builder().proxy(reqwest::Proxy::all("http://127.0.0.1:9").unwrap()).build().unwrap()
    }

    #[test]
    fn reads_new_manifest_format() {
        let dir = tempfile::tempdir().unwrap();
        bundle_version(dir.path(), "0.2.0", b"x");
        let manifest = load_manifest(dir.path());
        assert_eq!(manifest.version, "0.2.0");
        assert_eq!(manifest.builds.len(), 1);
        // Alte Liste ohne Version: Version unbekannt, Builds wie gehabt.
        let legacy = parse_manifest(MANIFEST_JSON.as_bytes()).unwrap();
        assert_eq!((legacy.version.as_str(), legacy.builds.len()), ("", 2));
        assert!(parse_manifest(b"{kaputt").is_none());
    }

    #[tokio::test]
    async fn newer_channel_client_wins_until_the_launcher_brings_a_newer_one() {
        let key = TestKey::generate();
        let (server, url) = TestServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("root"));
        paths.ensure().await.unwrap();
        let res = dir.path().join("bundled");
        bundle_version(&res, "0.2.0", b"v2");
        let updater = ClientModUpdater::for_tests(paths.client_mod_cache_dir(), &url, &key.public);
        let (http, ui) = (offline_http(), UiSettings::default());
        let inst = instance("1.21.1", LoaderKind::Fabric, None);
        let jar = content::content_dir(&paths, "test", ContentKind::Mod).join(INSTALLED_NAME);
        // Der Verlauf schreibt nur für angelegte Instanzen.
        std::fs::create_dir_all(paths.instance_dir("test")).unwrap();
        std::fs::write(paths.instance_file("test"), "{}").unwrap();

        // Noch nichts im Kanal: mitgelieferte Version.
        assert_eq!(updater.check_now(Some("0.2.0")).await, client_mod_update::CheckOutcome::Failed);
        sync(&http, &paths, Some(&res), Some(&updater), &inst, &ui).await.unwrap();
        assert_eq!(std::fs::read(&jar).unwrap(), b"v2");

        // Neuere Version im Kanal: wird geladen, geprüft und installiert.
        publish(&server, &key, "0.3.0", b"v3");
        updater.check_now(Some("0.2.0")).await;
        let catalog = Catalog::load(Some(&res), Some(&updater)).await;
        assert_eq!(catalog.status(), ClientModStatus { bundled: Some("0.2.0".into()), update: Some("0.3.0".into()) });
        sync(&http, &paths, Some(&res), Some(&updater), &inst, &ui).await.unwrap();
        assert_eq!(std::fs::read(&jar).unwrap(), b"v3");
        let entry = history::list(&paths, "test").await.unwrap().into_iter().next().unwrap();
        assert_eq!(entry.kind, HistoryKind::ModUpdated);
        assert_eq!((entry.from.as_deref(), entry.to.as_deref()), (Some("0.2.0"), Some("0.3.0")));

        // Zweiter Start: nichts Neues zu laden, nichts Neues im Verlauf.
        sync(&http, &paths, Some(&res), Some(&updater), &inst, &ui).await.unwrap();
        assert_eq!(server.hits("trsclient-fabric-1.21.jar"), 1);
        assert_eq!(history::list(&paths, "test").await.unwrap().len(), 1);

        // Launcher-Update bringt 0.4.0 mit: das ist neuer als der Kanal.
        bundle_version(&res, "0.4.0", b"v4");
        let catalog = Catalog::load(Some(&res), Some(&updater)).await;
        assert_eq!(catalog.status(), ClientModStatus { bundled: Some("0.4.0".into()), update: None });
        sync(&http, &paths, Some(&res), Some(&updater), &inst, &ui).await.unwrap();
        assert_eq!(std::fs::read(&jar).unwrap(), b"v4");
        updater.cleanup(Some("0.4.0")).await;
        assert!(!paths.client_mod_cache_dir().join("0.3.0").exists());
    }

    #[tokio::test]
    async fn channel_problems_fall_back_to_the_bundled_client() {
        let key = TestKey::generate();
        let (server, url) = TestServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("root"));
        paths.ensure().await.unwrap();
        let res = dir.path().join("bundled");
        bundle_version(&res, "0.2.0", b"v2");
        let updater = ClientModUpdater::for_tests(paths.client_mod_cache_dir(), &url, &key.public);
        let (http, ui) = (offline_http(), UiSettings::default());
        let inst = instance("1.21.1", LoaderKind::Fabric, None);
        let mods = content::content_dir(&paths, "test", ContentKind::Mod);

        // Signiertes Manifest, aber der Jar auf dem Server passt nicht zur Prüfsumme.
        publish(&server, &key, "0.3.0", b"v3");
        updater.check_now(Some("0.2.0")).await;
        server.put("trsclient-fabric-1.21.jar", b"v3-manipuliert".to_vec());
        sync(&http, &paths, Some(&res), Some(&updater), &inst, &ui).await.unwrap();
        assert_eq!(std::fs::read(mods.join(INSTALLED_NAME)).unwrap(), b"v2", "Rückfall auf die mitgelieferte Version");

        // In der Mod-Liste deaktiviert: bleibt aus, die deaktivierte Kopie bekommt das Update.
        std::fs::rename(mods.join(INSTALLED_NAME), mods.join("trsclient.jar.disabled")).unwrap();
        server.put("trsclient-fabric-1.21.jar", b"v3".to_vec());
        sync(&http, &paths, Some(&res), Some(&updater), &inst, &ui).await.unwrap();
        assert!(!mods.join(INSTALLED_NAME).exists(), "darf nicht wieder eingeschaltet werden");
        assert_eq!(std::fs::read(mods.join("trsclient.jar.disabled")).unwrap(), b"v3");
        std::fs::rename(mods.join("trsclient.jar.disabled"), mods.join(INSTALLED_NAME)).unwrap();

        // Offline: geprüftes Manifest + Jar aus dem Cache reichen.
        let offline = ClientModUpdater::for_tests(paths.client_mod_cache_dir(), "http://127.0.0.1:9/", &key.public);
        assert_eq!(offline.check_now(Some("0.2.0")).await, client_mod_update::CheckOutcome::Failed);
        sync(&http, &paths, Some(&res), Some(&offline), &inst, &ui).await.unwrap();
        assert_eq!(std::fs::read(mods.join(INSTALLED_NAME)).unwrap(), b"v3");

        // Offline und Cache weg: die installierte Kopie ist genau dieser Build und bleibt.
        std::fs::remove_dir_all(paths.client_mod_cache_dir().join("0.3.0")).unwrap();
        sync(&http, &paths, Some(&res), Some(&offline), &inst, &ui).await.unwrap();
        assert_eq!(std::fs::read(mods.join(INSTALLED_NAME)).unwrap(), b"v3");

        // Offline, kein Cache und keine passende Kopie: mitgelieferte Version.
        std::fs::remove_file(mods.join(INSTALLED_NAME)).unwrap();
        sync(&http, &paths, Some(&res), Some(&offline), &inst, &ui).await.unwrap();
        assert_eq!(std::fs::read(mods.join(INSTALLED_NAME)).unwrap(), b"v2");

        // Keine Quelle hat einen Build für die Version: Kopie wird entfernt.
        let other = instance("1.8.9", LoaderKind::Forge, None);
        sync(&http, &paths, Some(&res), Some(&offline), &other, &ui).await.unwrap();
        assert!(!mods.join(INSTALLED_NAME).exists(), "kein Build für 1.8.9: entfernt");
    }
}
