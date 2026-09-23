//! Baut die Java-Kommandozeile und verwaltet laufende Spielprozesse.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use crate::instance::Instance;
use crate::meta::version::{Argument, Features, VersionInfo, rules_allow};
use crate::prepare::Prepared;
use crate::settings::Settings;
use crate::{Error, LAUNCHER_NAME, LAUNCHER_VERSION, Result};

/// Mit wem gespielt wird.
#[derive(Clone)]
pub struct Session {
    pub player_name: String,
    /// UUID ohne Bindestriche.
    pub uuid: String,
    pub access_token: String,
    pub xuid: String,
    /// Offizieller Demo-Modus des Spiels (nur Entwicklungs-Builds ohne Account).
    pub demo: bool,
}

impl Session {
    pub fn features(&self) -> Features {
        Features { demo_user: self.demo, custom_resolution: true, quick_play_multiplayer: false }
    }
}

/// Server, auf den nach dem Start direkt verbunden wird.
#[derive(Debug, Clone)]
pub struct JoinTarget {
    /// Wie eingegeben (`host` oder `host:port`) – für `--quickPlayMultiplayer`.
    pub address: String,
    /// SRV-aufgelöst – für das alte `--server/--port`, das selbst kein SRV kennt.
    pub host: String,
    pub port: u16,
}

/// Verzeichnisse, die in die Startargumente einfließen.
#[derive(Debug, Clone, Copy)]
pub struct LaunchDirs<'a> {
    pub game: &'a Path,
    pub assets: &'a Path,
    pub libraries: &'a Path,
}

#[derive(Debug)]
pub struct Command {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    /// Zusätzliche Umgebungsvariablen (Start-Hooks).
    pub env: Vec<(String, String)>,
}

pub fn build_command(
    prepared: &Prepared,
    instance: &Instance,
    settings: &Settings,
    session: &Session,
    dirs: LaunchDirs<'_>,
    join: Option<&JoinTarget>,
) -> Result<Command> {
    let (game_dir, assets_root, libraries_dir) = (dirs.game, dirs.assets, dirs.libraries);
    let version = &prepared.version;
    let quick_play = join.is_some() && supports_quick_play(version);
    let features = Features { quick_play_multiplayer: quick_play, ..session.features() };
    let resolution = instance.overrides.resolution.unwrap_or(settings.resolution);
    let max_mb = instance.overrides.max_memory_mb.unwrap_or(settings.max_memory_mb);
    let min_mb = settings.min_memory_mb.min(max_mb);

    let classpath = prepared
        .classpath
        .iter()
        .map(|p| p.display().to_string())
        .collect::<Vec<_>>()
        .join(";");

    let vars: HashMap<&str, String> = HashMap::from([
        ("auth_player_name", session.player_name.clone()),
        ("auth_uuid", session.uuid.clone()),
        ("auth_access_token", session.access_token.clone()),
        ("auth_session", format!("token:{}:{}", session.access_token, session.uuid)),
        ("auth_xuid", session.xuid.clone()),
        ("clientid", String::new()),
        ("user_type", "msa".into()),
        ("user_properties", "{}".into()),
        ("version_name", version.id.clone()),
        ("version_type", version.kind.clone().unwrap_or_else(|| "release".into())),
        ("game_directory", game_dir.display().to_string()),
        ("assets_root", assets_root.display().to_string()),
        ("game_assets", prepared.game_assets.display().to_string()),
        ("assets_index_name", version.assets.clone().unwrap_or_else(|| "legacy".into())),
        ("natives_directory", prepared.natives_dir.display().to_string()),
        ("library_directory", libraries_dir.display().to_string()),
        ("classpath_separator", ";".into()),
        ("classpath", classpath),
        ("launcher_name", LAUNCHER_NAME.into()),
        ("launcher_version", LAUNCHER_VERSION.into()),
        ("resolution_width", resolution.width.to_string()),
        ("resolution_height", resolution.height.to_string()),
        ("quickPlayMultiplayer", join.map(|j| j.address.clone()).unwrap_or_default()),
    ]);
    let fill = |template: &str| substitute(template, &vars);

    let mut args = vec![
        format!("-Xms{min_mb}M"),
        format!("-Xmx{max_mb}M"),
        "-Dfile.encoding=UTF-8".into(),
        "-Dstdout.encoding=UTF-8".into(),
        "-Dstderr.encoding=UTF-8".into(),
        // Zusätzlich zu Mojangs gepatchter Log-Konfiguration (Log4Shell).
        "-Dlog4j2.formatMsgNoLookups=true".into(),
    ];

    match &version.arguments {
        Some(a) if !a.jvm.is_empty() => args.extend(expand(&a.jvm, &features).map(&fill)),
        // Vor 1.13 gibt es keine JVM-Argumente im JSON.
        _ => args.extend(
            [
                "-Djava.library.path=${natives_directory}",
                "-Dminecraft.launcher.brand=${launcher_name}",
                "-Dminecraft.launcher.version=${launcher_version}",
                "-cp",
                "${classpath}",
            ]
            .map(fill),
        ),
    }

    if let (Some(path), Some(cfg)) =
        (&prepared.log_config, version.logging.as_ref().and_then(|l| l.client.as_ref()))
    {
        args.push(cfg.argument.replace("${path}", &path.display().to_string()));
    }

    let user_jvm = split_args(instance.overrides.jvm_args.as_deref().unwrap_or(&settings.jvm_args));
    // Eigene GC-Wahl des Nutzers hat Vorrang vor unseren Voreinstellungen.
    if !user_jvm.iter().any(|a| is_collector_flag(a)) {
        let java_major = version.java_version.as_ref().map_or(8, |j| j.major_version);
        args.extend(performance_flags(java_major, max_mb));
    }
    args.extend(user_jvm);

    args.push(main_class(version)?.to_owned());

    match (&version.arguments, &version.minecraft_arguments) {
        (Some(a), _) if !a.game.is_empty() => args.extend(expand(&a.game, &features).map(&fill)),
        (_, Some(legacy)) => {
            // Erst trennen, dann ersetzen – Werte mit Leerzeichen bleiben ein Argument.
            args.extend(legacy.split_whitespace().map(fill));
            args.extend(["--width".into(), resolution.width.to_string()]);
            args.extend(["--height".into(), resolution.height.to_string()]);
            if session.demo {
                args.push("--demo".into());
            }
        }
        _ => return Err(Error::launch(crate::msg!("launch.noArguments", "Die Versions-Metadaten enthalten keine Startargumente."))),
    }

    if instance.overrides.fullscreen.unwrap_or(settings.fullscreen) {
        args.push("--fullscreen".into());
    }

    if let Some(join) = join.filter(|_| !quick_play) {
        args.extend(["--server".into(), join.host.clone(), "--port".into(), join.port.to_string()]);
    }

    Ok(Command { program: prepared.java.clone(), args, cwd: game_dir.to_owned(), env: Vec::new() })
}

/// GC-Voreinstellungen (angelehnt an OneLauncher `arguments.rs` `performance_flags`):
/// G1 mit kurzen Pausen; ab Java 21 und großem Heap das generationelle ZGC.
fn performance_flags(java_major: u32, max_mb: u32) -> Vec<String> {
    let mut flags: Vec<&str> = if java_major >= 21 && max_mb >= 8192 {
        vec!["-XX:+UseZGC", "-XX:+ZGenerational"]
    } else {
        vec![
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=50",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:G1NewSizePercent=20",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapRegionSize=32M",
        ]
    };
    // Java 24+: kleinere Objekt-Header, spürbar weniger RAM-Verbrauch.
    if java_major >= 24 {
        flags.extend(["-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders"]);
    }
    // ZGenerational ist ab Java 23 Standard und wird dort als veraltet gemeldet.
    if java_major >= 23 {
        flags.retain(|f| *f != "-XX:+ZGenerational");
    }
    flags.dedup();
    flags.into_iter().map(str::to_owned).collect()
}

fn is_collector_flag(arg: &str) -> bool {
    matches!(
        arg,
        "-XX:+UseG1GC" | "-XX:+UseZGC" | "-XX:+UseShenandoahGC" | "-XX:+UseParallelGC" | "-XX:+UseSerialGC" | "-XX:+UseEpsilonGC"
    ) || arg.starts_with("-XX:+UseConcMarkSweepGC")
}

fn supports_quick_play(version: &VersionInfo) -> bool {
    version.arguments.as_ref().is_some_and(|a| {
        a.game.iter().any(|arg| match arg {
            Argument::Conditional { rules, .. } => rules
                .iter()
                .any(|r| r.features.as_ref().is_some_and(|f| f.contains_key("is_quick_play_multiplayer"))),
            Argument::Plain(_) => false,
        })
    })
}

fn main_class(version: &VersionInfo) -> Result<&str> {
    version
        .main_class
        .as_deref()
        .ok_or_else(|| Error::launch(crate::msg!("launch.noMainClass", "Die Versions-Metadaten enthalten keine Hauptklasse.")))
}

fn expand<'a>(args: &'a [Argument], features: &'a Features) -> impl Iterator<Item = &'a str> {
    args.iter().flat_map(move |arg| -> Box<dyn Iterator<Item = &'a str> + 'a> {
        match arg {
            Argument::Plain(s) => Box::new(std::iter::once(s.as_str())),
            Argument::Conditional { rules, value } if rules_allow(rules, features) => Box::new(value.iter()),
            Argument::Conditional { .. } => Box::new(std::iter::empty()),
        }
    })
}

fn substitute(template: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = String::with_capacity(template.len());
    let mut rest = template;
    while let Some(start) = rest.find("${") {
        out.push_str(&rest[..start]);
        match rest[start + 2..].find('}') {
            Some(len) => {
                let key = &rest[start + 2..start + 2 + len];
                match vars.get(key) {
                    Some(value) => out.push_str(value),
                    None => out.push_str(&rest[start..start + 3 + len]),
                }
                rest = &rest[start + 3 + len..];
            }
            None => {
                out.push_str(&rest[start..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

/// Trennt an Leerzeichen, doppelte Anführungszeichen halten zusammen.
pub(crate) fn split_args(input: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut quoted = false;
    let mut has_token = false;
    for c in input.chars() {
        match c {
            '"' => {
                quoted = !quoted;
                has_token = true;
            }
            c if c.is_whitespace() && !quoted => {
                if has_token {
                    out.push(std::mem::take(&mut current));
                    has_token = false;
                }
            }
            c => {
                current.push(c);
                has_token = true;
            }
        }
    }
    if has_token {
        out.push(current);
    }
    out
}

// Prozessverwaltung liegt in `crate::process`.
pub use crate::process::{EventSink, GameEvent, GameManager, OnExit, RunningGame};

#[cfg(test)]
mod tests {
    use chrono::Utc;

    use super::*;
    use crate::instance::{InstanceOverrides, Loader};
    use crate::settings::Resolution;

    fn session() -> Session {
        Session {
            player_name: "Steve".into(),
            uuid: "00000000000000000000000000000001".into(),
            access_token: "geheimes-token-123".into(),
            xuid: "42".into(),
            demo: false,
        }
    }

    fn instance() -> Instance {
        Instance {
            id: "test".into(),
            name: "Test".into(),
            game_version: "1.21.1".into(),
            loader: Loader::vanilla(),
            created_at: Utc::now(),
            last_played: None,
            total_play_seconds: 0,
            icon: None,
            group: None,
            overrides: InstanceOverrides::default(),
        }
    }

    fn prepared(version_json: &str) -> Prepared {
        Prepared {
            version: serde_json::from_str(version_json).unwrap(),
            java: PathBuf::from(r"C:\java\bin\javaw.exe"),
            classpath: vec![PathBuf::from(r"C:\l\a.jar"), PathBuf::from(r"C:\v\client.jar")],
            natives_dir: PathBuf::from(r"C:\natives"),
            game_assets: PathBuf::from(r"C:\assets\virtual\legacy"),
            log_config: Some(PathBuf::from(r"C:\assets\log_configs\client.xml")),
        }
    }

    fn dirs() -> LaunchDirs<'static> {
        LaunchDirs { game: Path::new(r"C:\game dir"), assets: Path::new(r"C:\assets"), libraries: Path::new(r"C:\l") }
    }

    fn build(p: &Prepared, inst: &Instance, s: &Session) -> Vec<String> {
        build_command(p, inst, &Settings::default(), s, dirs(), None).unwrap().args
    }

    fn build_joining(p: &Prepared) -> Vec<String> {
        let join = JoinTarget { address: "play.cooltiers.de".into(), host: "srv.cooltiers.de".into(), port: 25577 };
        build_command(p, &instance(), &Settings::default(), &session(), dirs(), Some(&join)).unwrap().args
    }

    #[test]
    fn gc_defaults_respect_java_and_user_choice() {
        assert!(performance_flags(8, 4096).contains(&"-XX:+UseG1GC".to_owned()));
        let zgc = performance_flags(21, 8192);
        assert!(zgc.contains(&"-XX:+UseZGC".to_owned()) && zgc.contains(&"-XX:+ZGenerational".to_owned()));
        let j25 = performance_flags(25, 16384);
        assert!(!j25.contains(&"-XX:+ZGenerational".to_owned()));
        assert!(j25.contains(&"-XX:+UseCompactObjectHeaders".to_owned()));

        let mut inst = instance();
        inst.overrides.jvm_args = Some("-XX:+UseShenandoahGC".into());
        let args = build(&prepared(MODERN), &inst, &session());
        assert!(args.contains(&"-XX:+UseShenandoahGC".to_owned()));
        assert!(!args.iter().any(|a| a == "-XX:+UseG1GC" || a == "-XX:+UseZGC"));
    }

    #[test]
    fn quick_join_modern_and_legacy() {
        let modern = prepared(
            r#"{"id":"1.21.1","mainClass":"M","arguments":{"jvm":["-cp","${classpath}"],"game":["--username","${auth_player_name}",
                {"rules":[{"action":"allow","features":{"is_quick_play_multiplayer":true}}],
                 "value":["--quickPlayMultiplayer","${quickPlayMultiplayer}"]}]}}"#,
        );
        let args = build_joining(&modern).join(" ");
        assert!(args.ends_with("--quickPlayMultiplayer play.cooltiers.de"));
        assert!(!args.contains("--server"));
        assert!(!build(&modern, &instance(), &session()).join(" ").contains("quickPlay"));

        let legacy = prepared(r#"{"id":"1.8.9","mainClass":"M","minecraftArguments":"--username ${auth_player_name}"}"#);
        assert!(build_joining(&legacy).join(" ").ends_with("--server srv.cooltiers.de --port 25577"));
    }

    const MODERN: &str = r#"{
        "id":"1.21.1","type":"release","assets":"17","mainClass":"net.minecraft.client.main.Main",
        "logging":{"client":{"argument":"-Dlog4j.configurationFile=${path}","type":"log4j2-xml",
            "file":{"id":"client-1.12.xml","sha1":"x","size":1,"url":"https://x"}}},
        "arguments":{
          "jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
                 {"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-XX:HeapDumpPath=x.heapdump"},
                 "-Djava.library.path=${natives_directory}","-cp","${classpath}"],
          "game":["--username","${auth_player_name}","--gameDir","${game_directory}","--accessToken","${auth_access_token}",
                 {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},
                 {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],
                  "value":["--width","${resolution_width}","--height","${resolution_height}"]},
                 {"rules":[{"action":"allow","features":{"has_quick_plays_support":true}}],
                  "value":["--quickPlayPath","${quickPlayPath}"]}]}}"#;

    #[test]
    fn modern_arguments() {
        let args = build(&prepared(MODERN), &instance(), &session());
        let joined = args.join(" | ");

        assert!(args.contains(&"-Xmx4096M".to_owned()));
        assert!(args.contains(&"-XX:HeapDumpPath=x.heapdump".to_owned()));
        assert!(!joined.contains("XstartOnFirstThread"));
        assert!(args.contains(&r"-Djava.library.path=C:\natives".to_owned()));
        assert!(args.contains(&r"C:\l\a.jar;C:\v\client.jar".to_owned()));
        assert!(args.contains(&r"-Dlog4j.configurationFile=C:\assets\log_configs\client.xml".to_owned()));
        // Pfad mit Leerzeichen bleibt EIN Argument.
        assert!(args.contains(&r"C:\game dir".to_owned()));
        assert!(!joined.contains("--demo") && !joined.contains("quickPlay"));
        assert!(joined.ends_with("--width | 1280 | --height | 720"));

        // JVM-Argumente vor der Hauptklasse, Spielargumente danach.
        let main = args.iter().position(|a| a == "net.minecraft.client.main.Main").unwrap();
        assert!(args.iter().position(|a| a == "-cp").unwrap() < main);
        assert!(args.iter().position(|a| a == "--username").unwrap() > main);
    }

    #[test]
    fn demo_and_overrides() {
        let mut inst = instance();
        inst.overrides = InstanceOverrides {
            max_memory_mb: Some(8192),
            jvm_args: Some(r#"-XX:+UseG1GC "-Dfoo=a b""#.into()),
            resolution: Some(Resolution { width: 1920, height: 1080 }),
            ..Default::default()
        };
        let args = build(&prepared(MODERN), &inst, &Session { demo: true, ..session() });
        assert!(args.contains(&"-Xmx8192M".to_owned()));
        assert!(args.contains(&"-Dfoo=a b".to_owned()));
        assert!(args.contains(&"--demo".to_owned()));
        assert!(args.contains(&"1920".to_owned()));
        assert!(!args.contains(&"--fullscreen".to_owned()));
    }

    #[test]
    fn fullscreen_from_instance_or_settings() {
        let mut inst = instance();
        inst.overrides.fullscreen = Some(true);
        assert!(build(&prepared(MODERN), &inst, &session()).ends_with(&["--fullscreen".to_owned()]));

        let settings = Settings { fullscreen: true, ..Default::default() };
        let args = build_command(&prepared(MODERN), &instance(), &settings, &session(), dirs(), None).unwrap().args;
        assert!(args.contains(&"--fullscreen".to_owned()));
        inst.overrides.fullscreen = Some(false);
        let args = build_command(&prepared(MODERN), &inst, &settings, &session(), dirs(), None).unwrap().args;
        assert!(!args.contains(&"--fullscreen".to_owned()));
    }

    #[test]
    fn legacy_arguments() {
        let p = prepared(
            r#"{"id":"1.6.4","assets":"legacy","mainClass":"net.minecraft.client.main.Main",
                "minecraftArguments":"--username ${auth_player_name} --session ${auth_session} --gameDir ${game_directory} --assetsDir ${game_assets}"}"#,
        );
        let args = build(&p, &instance(), &session());
        assert!(args.contains(&r"-Djava.library.path=C:\natives".to_owned()));
        assert!(args.contains(&"token:geheimes-token-123:00000000000000000000000000000001".to_owned()));
        assert!(args.contains(&r"C:\assets\virtual\legacy".to_owned()));
        assert!(args.contains(&r"C:\game dir".to_owned()));
        assert!(args.contains(&"--width".to_owned()));
    }

    #[test]
    fn substitution_keeps_unknown_placeholders() {
        let vars = HashMap::from([("a", "1".to_owned())]);
        assert_eq!(substitute("x${a}y${b}z${a", &vars), "x1y${b}z${a");
    }

    #[test]
    fn arg_splitting() {
        assert_eq!(split_args(r#"  -a  "-b c"  -d"e f"  "#), ["-a", "-b c", "-de f"]);
        assert!(split_args("   ").is_empty());
        assert_eq!(split_args(r#""""#), [""]);
    }
}
