//! Start auf Android/iOS: Statt einen `java`-Prozess zu starten, baut der Kern
//! eine [`GameLaunchSpec`] für die eingebettete JVM (Plugin `tauri-plugin-trs-game`).
//! Version, Libraries, Assets, Loader und Startargumente kommen aus derselben
//! Logik wie auf dem Desktop (`prepare` + `launch::build_command`); nur Java,
//! die LWJGL-Natives und desktop-spezifische JVM-Flags weichen ab.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde::{Deserialize, Serialize};

use crate::history::{HistoryEntry, HistoryKind};
use crate::instance::{Instance, LoaderKind};
use crate::launch::{self, JoinTarget, LaunchDirs, Session};
use crate::meta::version::VersionInfo;
use crate::prepare::{self, JavaChoice, Prepared, ProgressFn, Stage, StageProgress};
use crate::settings::Settings;
use crate::{Error, Join, Launcher, Result, boost, client_mod, depcheck, forge, fsutil, history, instance, servers, task};

/// Java-Hauptversionen der Engine (Runtimes zum Herunterladen).
pub const ENGINE_JAVA: [u32; 4] = [8, 17, 21, 25];

/// Startbeschreibung für die eingebettete JVM – Vertrag mit dem Plugin
/// (`tauri_plugin_trs_game::GameLaunchSpec`, gleiche JSON-Form).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GameLaunchSpec {
    pub game_dir: PathBuf,
    pub assets_dir: PathBuf,
    pub natives_dir: PathBuf,
    pub classpath: Vec<String>,
    pub main_class: String,
    pub jvm_args: Vec<String>,
    pub game_args: Vec<String>,
    pub java_major: u8,
    /// `auto` | `gl4es` | `mobileglues` | `zink`
    pub renderer: String,
    pub memory_mb: u32,
    pub extra_env: BTreeMap<String, String>,
    pub touch_profile: Option<String>,
    pub trs_client: bool,
    pub game_version: Option<String>,
    pub lwjgl_version: Option<String>,
}

/// Engine-Java für die Java-Version des Spiels (16 → 17, 22–24 → 25 …).
pub fn engine_java(required: u32) -> u8 {
    match required {
        0..=8 => 8,
        9..=17 => 17,
        18..=21 => 21,
        _ => 25,
    }
}

/// LWJGL-Version laut Version-JSON (`org.lwjgl:lwjgl:3.3.3`, alt `org.lwjgl.lwjgl:lwjgl:2.9.4…`).
pub fn lwjgl_version(version: &VersionInfo) -> Option<String> {
    version.libraries.iter().find_map(|lib| {
        let mut parts = lib.name.split(':');
        let (group, artifact, ver) = (parts.next()?, parts.next()?, parts.next()?);
        ((group == "org.lwjgl" || group == "org.lwjgl.lwjgl") && artifact == "lwjgl").then(|| ver.to_owned())
    })
}

/// LWJGL-Jars kommen aus dem Engine-Fork (GLFW-Stub, Android-Natives).
fn is_lwjgl_jar(path: &Path) -> bool {
    let parts: Vec<&str> = path.iter().filter_map(|p| p.to_str()).collect();
    parts.windows(2).any(|w| w[0] == "org" && w[1] == "lwjgl")
}

/// JVM-Flags, die die Engine selbst setzt oder die auf Mobilgeräten schaden
/// (Speicher vorab belegen, ZGC, große Seiten).
fn is_desktop_only_flag(arg: &str) -> bool {
    const PREFIXES: &[&str] = &[
        "-Xmx",
        "-Xms",
        "-Xss",
        "-Djava.library.path=",
        "-Djna.tmpdir=",
        "-Dorg.lwjgl.",
        "-XX:+AlwaysPreTouch",
        "-XX:+UseZGC",
        "-XX:+ZGenerational",
        "-XX:+UseLargePages",
        "-XX:+UseTransparentHugePages",
        "-XX:+UseNUMA",
        "-XX:ActiveProcessorCount",
        "-XstartOnFirstThread",
    ];
    PREFIXES.iter().any(|p| arg.starts_with(p))
}

/// Heap für Mobilgeräte: eingestellter Wert, aber höchstens die Hälfte des
/// Arbeitsspeichers (Android beendet sonst den Spielprozess) und mindestens 1 GB.
pub fn mobile_heap_mb(configured: u32, device_mb: Option<u32>) -> u32 {
    let cap = device_mb.map_or(configured, |total| total / 2);
    configured.min(cap).max(1024)
}

/// Desktop-Kommandozeile → Startbeschreibung (ohne Java-Pfad, `-cp` und LWJGL).
pub fn spec_from_command(
    prepared: &Prepared,
    command: launch::Command,
    instance: &Instance,
    memory_mb: u32,
    trs_client: bool,
) -> Result<GameLaunchSpec> {
    let version = &prepared.version;
    let main_class = version
        .main_class
        .clone()
        .ok_or_else(|| Error::launch(crate::msg!("launch.noMainClass", "Die Versions-Metadaten enthalten keine Hauptklasse.")))?;
    let pos = command
        .args
        .iter()
        .position(|a| *a == main_class)
        .ok_or_else(|| Error::launch(crate::msg!("launch.noMainClass", "Die Versions-Metadaten enthalten keine Hauptklasse.")))?;
    let (jvm_part, rest) = command.args.split_at(pos);
    let mut jvm_args = Vec::new();
    let mut skip_next = false;
    for arg in jvm_part {
        if skip_next {
            skip_next = false;
            continue;
        }
        if matches!(arg.as_str(), "-cp" | "-classpath" | "--class-path") {
            skip_next = true;
            continue;
        }
        if is_desktop_only_flag(arg) {
            continue;
        }
        jvm_args.push(arg.clone());
    }
    let classpath = prepared
        .classpath
        .iter()
        .filter(|p| !is_lwjgl_jar(p))
        .map(|p| p.display().to_string())
        .collect();
    let required = prepared.java_major.or_else(|| version.java_version.as_ref().map(|j| j.major_version)).unwrap_or(8);
    Ok(GameLaunchSpec {
        game_dir: command.cwd,
        assets_dir: PathBuf::new(),
        natives_dir: prepared.natives_dir.clone(),
        classpath,
        main_class,
        jvm_args,
        game_args: rest[1..].to_vec(),
        java_major: engine_java(required),
        renderer: "auto".into(),
        memory_mb,
        extra_env: BTreeMap::new(),
        touch_profile: None,
        trs_client,
        game_version: Some(instance.game_version.clone()),
        lwjgl_version: lwjgl_version(version),
    })
}

impl Launcher {
    /// Bereitet einen Start auf Android/iOS vor: Mods/TRS Client, Version,
    /// Libraries, Assets, Loader (Forge/NeoForge-Processors über `runner`) – und
    /// liefert die Startbeschreibung für die Engine. Startet selbst nichts.
    pub async fn prepare_mobile_launch(
        self: &Arc<Self>,
        instance_id: &str,
        join: Option<Join<'_>>,
        runner: Option<&dyn forge::ProcessorRunner>,
        on_progress: &ProgressFn,
    ) -> Result<GameLaunchSpec> {
        let instance = self.instances.get(instance_id).await?;
        {
            let mut preparing = self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if !preparing.insert(instance.id.clone()) {
                return Err(Error::launch(crate::msg!("launcher.alreadyStarting", "Diese Instanz wird bereits gestartet.")));
            }
        }
        let result = self.prepare_mobile_inner(&instance, join, runner, on_progress).await;
        self.preparing.lock().unwrap_or_else(std::sync::PoisonError::into_inner).remove(&instance.id);
        result
    }

    async fn prepare_mobile_inner(
        self: &Arc<Self>,
        instance: &Instance,
        join_request: Option<Join<'_>>,
        runner: Option<&dyn forge::ProcessorRunner>,
        on_progress: &ProgressFn,
    ) -> Result<GameLaunchSpec> {
        let join: Option<JoinTarget> = match join_request {
            Some(Join::Server(id)) => Some(servers::join_target(&self.servers.get(id).await?.address).await?),
            Some(Join::Address(address)) => Some(servers::join_target(address).await?),
            // Gehostete Welten brauchen den TRS-Link – auf Mobilgeräten noch nicht.
            Some(Join::World(_)) => {
                return Err(Error::launch(crate::msg!("game.worldJoinUnsupported", "Gehostete Welten gibt es auf diesem Gerät noch nicht.")));
            }
            None => None,
        };
        let session = match self.accounts.active_session().await? {
            Some(session) => session,
            None => crate::demo_session()?,
        };
        let settings = self.settings().await;

        let (client_mod_dir, catalog) = self.client_mod_catalog().await;
        let effective = boost::effective_instance(&self.http, &self.paths, catalog.builds(), instance).await;
        let mods_progress = |p: crate::presets::ApplyProgress| on_progress(crate::mods_stage(&p));
        if effective.loader.kind == LoaderKind::Fabric && instance.loader.kind != LoaderKind::Fabric {
            if boost::needs_performance(&self.paths, &effective).await {
                on_progress(StageProgress::begin(Stage::Mods));
            }
            boost::ensure_performance(&self.http, &self.paths, catalog.builds(), &effective, &mods_progress).await?;
        }
        let instance = &effective;
        let trs_enabled = self.trs.enabled().await;
        let updates = Some(&self.client_mod_updates);
        if let Err(e) =
            client_mod::sync(&self.http, &self.paths, client_mod_dir.as_deref(), updates, instance, &settings.ui, trs_enabled, &self.trs.active_events())
                .await
        {
            tracing::warn!("TRS Client konnte nicht eingerichtet werden: {e}");
        }
        match depcheck::ensure_before_launch(&self.http, &self.paths, catalog.builds(), instance, &mods_progress).await {
            Ok(_) => {}
            Err(Error::Cancelled) => return Err(Error::Cancelled),
            Err(e) => tracing::warn!("Abhängigkeiten konnten nicht geprüft werden: {e}"),
        }

        let prepared = prepare::prepare_with(
            &self.http,
            &self.paths,
            &settings,
            instance,
            &session.features(),
            false,
            JavaChoice::Embedded { runner },
            on_progress,
        )
        .await?;
        task::checkpoint().await?;
        on_progress(StageProgress::begin(Stage::Starting));

        let game_dir = self.paths.instance_game_dir(&instance.id);
        fsutil::ensure_dir(&game_dir).await?;
        let data_version = instance::client_data_version(&self.paths.version_jar(&instance.game_version)).await;
        if let Err(e) = instance::seed_game_options(&game_dir, data_version).await {
            tracing::warn!("Standard-Optionen konnten nicht geschrieben werden: {e}");
        }
        if let Err(e) = self.servers.sync_to_instance(&game_dir).await {
            tracing::warn!("servers.dat konnte nicht aktualisiert werden: {e}");
        }
        let assets_dir = self.paths.assets_dir();
        let command = launch::build_command(
            &prepared,
            instance,
            &settings,
            &session,
            LaunchDirs { game: &game_dir, assets: &assets_dir, libraries: &self.paths.libraries_dir() },
            join.as_ref(),
            None,
        )?;
        let trs_client = instance.overrides.trs_client != Some(false)
            && client_mod::build_for(catalog.builds(), instance.loader.kind, &instance.game_version).is_some();
        let configured = instance.overrides.max_memory_mb.unwrap_or(settings.max_memory_mb);
        let memory = mobile_heap_mb(configured, crate::platform::total_memory_mb());
        let mut spec = spec_from_command(&prepared, command, instance, memory, trs_client)?;
        spec.assets_dir = assets_dir;
        if trs_client && let Some(build) = client_mod::build_for(catalog.builds(), instance.loader.kind, &instance.game_version) {
            let extra = client_mod::bundled_jvm_args(&self.paths, build, instance).await;
            for arg in extra {
                let key = arg.split('=').next().unwrap_or(&arg).to_owned();
                if !spec.jvm_args.iter().any(|a| a.split('=').next() == Some(key.as_str())) {
                    spec.jvm_args.push(arg);
                }
            }
        }
        self.instances.touch_last_played(&instance.id).await?;
        history::record(&self.paths, &instance.id, HistoryEntry::new(HistoryKind::Launched)).await;
        Ok(spec)
    }
}

/// Session ohne Token für Tests (nicht öffentlich nutzbar).
#[cfg(test)]
fn test_session() -> Session {
    Session { player_name: "Steve".into(), uuid: "0".repeat(32), access_token: "token".into(), xuid: String::new(), demo: false }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::meta::version::Library;

    fn lib(name: &str) -> Library {
        serde_json::from_value(serde_json::json!({ "name": name })).unwrap()
    }

    fn version(libs: &[&str]) -> VersionInfo {
        let mut v: VersionInfo = serde_json::from_value(serde_json::json!({
            "id": "1.21.4",
            "mainClass": "net.minecraft.client.main.Main",
            "javaVersion": { "component": "java-runtime-delta", "majorVersion": 21 },
            "arguments": {
                "game": ["--username", "${auth_player_name}", "--accessToken", "${auth_access_token}", "--gameDir", "${game_directory}"],
                "jvm": ["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"]
            }
        }))
        .unwrap();
        v.libraries = libs.iter().map(|n| lib(n)).collect();
        v
    }

    #[test]
    fn java_mapping() {
        assert_eq!(engine_java(8), 8);
        assert_eq!(engine_java(16), 17);
        assert_eq!(engine_java(17), 17);
        assert_eq!(engine_java(21), 21);
        assert_eq!(engine_java(25), 25);
    }

    #[test]
    fn detects_lwjgl() {
        assert_eq!(lwjgl_version(&version(&["com.mojang:blocklist:1.0.10", "org.lwjgl:lwjgl:3.3.3"])).as_deref(), Some("3.3.3"));
        assert_eq!(
            lwjgl_version(&version(&["org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209"])).as_deref(),
            Some("2.9.4-nightly-20150209")
        );
        assert!(lwjgl_version(&version(&["org.lwjgl:lwjgl-glfw:3.3.3"])).is_none());
    }

    #[test]
    fn heap_is_capped_for_phones() {
        assert_eq!(mobile_heap_mb(4096, Some(6000)), 3000);
        assert_eq!(mobile_heap_mb(2048, Some(12_000)), 2048);
        assert_eq!(mobile_heap_mb(4096, Some(1500)), 1024);
        assert_eq!(mobile_heap_mb(4096, None), 4096);
    }

    #[test]
    fn spec_strips_classpath_lwjgl_and_desktop_flags() {
        let dir = tempfile::tempdir().unwrap();
        let libs = dir.path().join("libraries");
        let prepared = Prepared {
            version: version(&["org.lwjgl:lwjgl:3.3.3"]),
            java: PathBuf::new(),
            java_major: Some(21),
            classpath: vec![
                libs.join("org").join("lwjgl").join("lwjgl").join("3.3.3").join("lwjgl-3.3.3.jar"),
                libs.join("com").join("mojang").join("brigadier.jar"),
                dir.path().join("versions").join("1.21.4").join("1.21.4.jar"),
            ],
            natives_dir: dir.path().join("natives"),
            game_assets: dir.path().join("assets"),
            log_config: None,
        };
        let instance = Instance {
            id: "abc".into(),
            name: "Test".into(),
            game_version: "1.21.4".into(),
            loader: crate::instance::Loader::vanilla(),
            created_at: chrono::Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            icon: None,
            group: None,
            overrides: crate::instance::InstanceOverrides::default(),
        };
        let settings = Settings::default();
        let game = dir.path().join("game");
        let assets = dir.path().join("assets");
        let command = launch::build_command(
            &prepared,
            &instance,
            &settings,
            &test_session(),
            LaunchDirs { game: &game, assets: &assets, libraries: &libs },
            None,
            Some(8192),
        )
        .unwrap();
        let spec = spec_from_command(&prepared, command, &instance, 2048, false).unwrap();
        assert_eq!(spec.main_class, "net.minecraft.client.main.Main");
        assert_eq!(spec.classpath.len(), 2);
        assert!(spec.classpath.iter().all(|p| !p.contains("lwjgl")));
        assert!(!spec.jvm_args.iter().any(|a| a == "-cp" || a.starts_with("-Xmx") || a.starts_with("-Djava.library.path")));
        assert!(!spec.jvm_args.iter().any(|a| a.contains("AlwaysPreTouch") || a.contains("UseZGC")));
        assert_eq!(spec.game_args[0], "--username");
        assert_eq!(spec.java_major, 21);
        assert_eq!(spec.lwjgl_version.as_deref(), Some("3.3.3"));
        assert_eq!(spec.game_dir, game);
        let json = serde_json::to_value(&spec).unwrap();
        assert!(json.get("gameDir").is_some() && json.get("lwjglVersion").is_some());
    }
}
