//! TRS Client: unser eigener In-Game-Mod (HUD, Zoom, Fullbright, Menü).
//! Der Launcher bringt die Jars samt `builds.json` mit und legt beim Start
//! automatisch den passenden Build in jede Instanz – inklusive benötigter
//! Abhängigkeiten wie Fabric API.

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::content::{self, ContentKind};
use crate::download;
use crate::instance::{Instance, LoaderKind};
use crate::paths::Paths;
use crate::settings::{Accent, Theme, UiSettings};
use crate::{Error, Result, modrinth};

/// So heißt die Datei im Mods-Ordner – fester Name, damit Updates sie ersetzen.
const INSTALLED_NAME: &str = "trsclient.jar";
const FABRIC_API_PROJECT: &str = "P7dR8mSH";
const MANIFEST: &str = "builds.json";
/// Farben des Launchers für das In-Game-Menü (relativ zum Config-Ordner der Instanz).
const THEME_FILE: &str = "trsclient/launcher-theme.json";

/// Ein Eintrag aus `builds.json` (erzeugt vom Gradle-Task `collectLauncherJars`).
#[derive(Debug, Clone, Deserialize)]
pub struct Build {
    /// `fabric`, `forge`, `neoforge`
    pub loader: String,
    /// Alle exakten Spielversionen, die dieser Jar unterstützt.
    pub minecraft: Vec<String>,
    pub file: String,
    #[serde(default)]
    pub requires: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Availability {
    Available,
    Unsupported,
    Disabled,
}

/// Liest `builds.json` aus dem Ressourcen-Ordner; kaputt oder fehlend = leer.
pub fn load_builds(dir: &Path) -> Vec<Build> {
    std::fs::read(dir.join(MANIFEST))
        .ok()
        .and_then(|b| serde_json::from_slice::<Vec<Build>>(&b).ok())
        .unwrap_or_default()
        .into_iter()
        // Dateinamen stammen aus unserer eigenen Datei – trotzdem nur einfache Namen zulassen.
        .filter(|b| content::validate_file_name(ContentKind::Mod, &b.file).is_ok())
        .collect()
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
/// für die Version keinen Build mehr hat.
pub async fn sync(
    http: &reqwest::Client,
    paths: &Paths,
    bundled_dir: Option<&Path>,
    instance: &Instance,
    ui: &UiSettings,
) -> Result<()> {
    let Some(dir) = bundled_dir else { return Ok(()) };
    let builds = load_builds(dir);
    let mods = content::content_dir(paths, &instance.id, ContentKind::Mod);
    let target = mods.join(INSTALLED_NAME);
    let disabled_copy = mods.join(format!("{INSTALLED_NAME}.disabled"));

    let build = build_for(&builds, instance.loader.kind, &instance.game_version);
    let Some(build) = build.filter(|_| instance.overrides.trs_client != Some(false)) else {
        // Abgeschaltet oder nicht passend (z. B. nach Versionswechsel): alte Kopie weg.
        for file in [&target, &disabled_copy] {
            if file.is_file() {
                tokio::fs::remove_file(file).await.map_err(|e| Error::io(file, e))?;
            }
        }
        let theme = theme_path(paths, &instance.id);
        if theme.is_file() {
            let _ = tokio::fs::remove_file(&theme).await;
        }
        return Ok(());
    };

    let source = dir.join(&build.file);
    if !source.is_file() {
        tracing::warn!("TRS Client fehlt im Launcher-Paket: {}", source.display());
        return Ok(());
    }
    // Nur kopieren, wenn sich etwas geändert hat (Launcher-Update bringt neue Version).
    if !same_file(&source, &target).await {
        tokio::fs::create_dir_all(&mods).await.map_err(|e| Error::io(&mods, e))?;
        tokio::fs::copy(&source, &target).await.map_err(|e| Error::io(&target, e))?;
        let _ = tokio::fs::remove_file(&disabled_copy).await;
        tracing::info!("TRS Client ({}) in '{}' installiert", build.file, instance.id);
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

    /// Die mitgelieferte `builds.json`: jede Datei existiert, jede (Loader, Version)-Kombination
    /// ist eindeutig und kein Eintrag wurde wegen eines unsicheren Namens verworfen.
    #[test]
    fn bundled_manifest_is_consistent() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../resources/client-mod");
        let raw: Vec<Build> = serde_json::from_slice(&std::fs::read(dir.join(MANIFEST)).unwrap()).unwrap();
        let builds = load_builds(&dir);
        assert_eq!(builds.len(), raw.len(), "unsichere Dateinamen in builds.json");
        let mut seen = std::collections::HashSet::new();
        for b in &builds {
            assert!(matches!(b.loader.as_str(), "fabric" | "forge" | "neoforge"), "{}", b.loader);
            assert!(dir.join(&b.file).is_file(), "{} fehlt", b.file);
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
        sync(&http, &paths, Some(&res), &on, &ui).await.unwrap();
        assert_eq!(tokio::fs::read(mods.join(INSTALLED_NAME)).await.unwrap(), b"v1");
        assert!(theme_path(&paths, "test").is_file(), "Farben des Launchers liegen in der Instanz");

        tokio::fs::write(res.join("trsclient-fabric-1.21.jar"), b"v2").await.unwrap();
        sync(&http, &paths, Some(&res), &on, &ui).await.unwrap();
        assert_eq!(tokio::fs::read(mods.join(INSTALLED_NAME)).await.unwrap(), b"v2");

        // Versionswechsel auf eine Version ohne Build: alte Kopie verschwindet.
        let other = instance("1.20.4", LoaderKind::Fabric, None);
        sync(&http, &paths, Some(&res), &other, &ui).await.unwrap();
        assert!(!mods.join(INSTALLED_NAME).exists());

        sync(&http, &paths, Some(&res), &on, &ui).await.unwrap();
        let off = instance("1.21.1", LoaderKind::Fabric, Some(false));
        sync(&http, &paths, Some(&res), &off, &ui).await.unwrap();
        assert!(!mods.join(INSTALLED_NAME).exists());
        assert!(!theme_path(&paths, "test").exists(), "abgeschaltet: auch die Farbdatei ist weg");
    }
}
