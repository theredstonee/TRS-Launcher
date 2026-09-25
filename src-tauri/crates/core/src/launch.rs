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
    /// Prozesspriorität „Höher als normal“.
    pub high_priority: bool,
}

/// Zusätzliche JVM-Argumente vor die Hauptklasse setzen (nach denen des Nutzers –
/// eigene Angaben mit derselben Eigenschaft bleiben unangetastet).
pub fn insert_jvm_args(command: &mut Command, prepared: &Prepared, extra: Vec<String>) {
    let Ok(main) = main_class(&prepared.version) else { return };
    let Some(pos) = command.args.iter().position(|a| a == main) else { return };
    let extra: Vec<String> = extra
        .into_iter()
        .filter(|arg| {
            let key = arg.split('=').next().unwrap_or(arg);
            !command.args[..pos].iter().any(|a| a.split('=').next() == Some(key))
        })
        .collect();
    command.args.splice(pos..pos, extra);
}

/// `system_memory_mb`: eingebauter Arbeitsspeicher (`None` = unbekannt) – der
/// Heap wird so begrenzt, dass dem System noch 2 GB bleiben.
pub fn build_command(
    prepared: &Prepared,
    instance: &Instance,
    settings: &Settings,
    session: &Session,
    dirs: LaunchDirs<'_>,
    join: Option<&JoinTarget>,
    system_memory_mb: Option<u32>,
) -> Result<Command> {
    let (game_dir, assets_root, libraries_dir) = (dirs.game, dirs.assets, dirs.libraries);
    let version = &prepared.version;
    let quick_play = join.is_some() && supports_quick_play(version);
    let features = Features { quick_play_multiplayer: quick_play, ..session.features() };
    let resolution = instance.overrides.resolution.unwrap_or(settings.resolution);
    let user_jvm = split_args(instance.overrides.jvm_args.as_deref().unwrap_or(&settings.jvm_args));
    // Die tatsächlich benutzte Java zählt; die Metadaten nur, wenn sie sich nicht erkennen lässt.
    let java_major = prepared
        .java_major
        .or_else(|| version.java_version.as_ref().map(|j| j.major_version))
        .unwrap_or(8);
    let jvm = jvm_flags(
        &JvmOptions {
            // Eigene JVM-Argumente schalten die Abstimmung nicht mehr ab – sie gewinnen nur bei Überschneidungen.
            tuned: instance.overrides.performance_tuning.unwrap_or(settings.performance_tuning),
            java_major,
            max_mb: instance.overrides.max_memory_mb.unwrap_or(settings.max_memory_mb),
            min_mb: settings.min_memory_mb,
            system_mb: system_memory_mb,
        },
        &user_jvm,
    );

    let classpath = prepared
        .classpath
        .iter()
        .map(|p| p.display().to_string())
        .collect::<Vec<_>>()
        .join(crate::platform::CLASSPATH_SEPARATOR);

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
        ("classpath_separator", crate::platform::CLASSPATH_SEPARATOR.into()),
        ("classpath", classpath),
        ("launcher_name", LAUNCHER_NAME.into()),
        ("launcher_version", LAUNCHER_VERSION.into()),
        ("resolution_width", resolution.width.to_string()),
        ("resolution_height", resolution.height.to_string()),
        ("quickPlayMultiplayer", join.map(|j| j.address.clone()).unwrap_or_default()),
    ]);
    let fill = |template: &str| substitute(template, &vars);

    let mut args = jvm.heap;
    args.extend([
        "-Dfile.encoding=UTF-8".into(),
        "-Dstdout.encoding=UTF-8".into(),
        "-Dstderr.encoding=UTF-8".into(),
        // Zusätzlich zu Mojangs gepatchter Log-Konfiguration (Log4Shell).
        "-Dlog4j2.formatMsgNoLookups=true".into(),
    ]);

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

    // Launcher-Flags zuerst, eigene danach – bei gleichen Optionen gilt ohnehin das letzte.
    args.extend(jvm.tuning);
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

    Ok(Command { program: prepared.java.clone(), args, cwd: game_dir.to_owned(), env: Vec::new(), high_priority: false })
}

// --- JVM: Speicher und Garbage Collector -------------------------------------------

/// Ab so viel Heap lohnt sich ZGC; darunter hat es zu wenig Luft und neigt zu
/// „Allocation Stalls“ (spürbare Hänger) – dann G1.
const ZGC_MIN_HEAP_MB: u32 = 12_288;
/// So viel Arbeitsspeicher bleibt mindestens für Windows und andere Programme.
const SYSTEM_RESERVE_MB: u32 = 2048;
/// Kleiner wird der Heap durch die Begrenzung nie.
const MIN_CAPPED_HEAP_MB: u32 = 1024;
const UNLOCK_EXPERIMENTAL: &str = "-XX:+UnlockExperimentalVMOptions";

/// Was in die Speicher- und GC-Flags einfließt.
#[derive(Debug, Clone, Copy)]
struct JvmOptions {
    /// FPS-Boost beim Start (abgestimmte Flags, Xms = Xmx).
    tuned: bool,
    /// Hauptversion der Java, die das Spiel startet.
    java_major: u32,
    /// Eingestellter Heap (Instanz oder global).
    max_mb: u32,
    /// Globaler Mindest-Heap (nur ohne Abstimmung).
    min_mb: u32,
    /// Eingebauter Arbeitsspeicher; `None` = unbekannt.
    system_mb: Option<u32>,
}

/// `heap` kommt vor, `tuning` nach den JVM-Argumenten der Version.
#[derive(Debug, Default)]
struct JvmFlags {
    heap: Vec<String>,
    tuning: Vec<String>,
}

/// Speicher- und GC-Flags des Launchers. Eigene JVM-Argumente gewinnen bei
/// Überschneidungen: eigenes `-Xmx`/`-Xms` ersetzt unseres, ein eigener
/// GC-Schalter ersetzt alle GC-Flags, und gleiche `-XX`-Optionen fallen bei uns weg.
fn jvm_flags(opts: &JvmOptions, user: &[String]) -> JvmFlags {
    let max_mb = cap_heap(opts.max_mb, opts.system_mb);
    let user_max = user_heap(user, "-Xmx", "MaxHeapSize");
    let user_min = user_heap(user, "-Xms", "InitialHeapSize");
    // Größe des Heaps, mit dem das Spiel wirklich läuft (eigenes -Xmx gewinnt).
    let heap_mb = match user_max {
        Some(parsed) => parsed,
        None => Some(max_mb),
    };

    let mut heap = Vec::new();
    if user_max.is_none() {
        heap.push(format!("-Xmx{max_mb}M"));
    }
    if user_min.is_none() {
        // Abgestimmt: gleich den ganzen Speicher holen (kein Nachwachsen im Spiel).
        // Sonst der Mindestwert – aber nie über dem Maximum (sonst startet Java nicht).
        let min = if opts.tuned { heap_mb } else { heap_mb.map(|h| opts.min_mb.min(h)) };
        if let Some(min) = min {
            heap.push(format!("-Xms{min}M"));
        }
    }

    let size = heap_mb.unwrap_or(max_mb);
    let mut flags = if opts.tuned { tuned_flags(opts.java_major, size) } else { performance_flags(opts.java_major, size) };

    let user_names: std::collections::HashSet<&str> = user.iter().filter_map(|a| xx_name(a)).collect();
    let own_collector = user.iter().any(|a| is_collector_flag(a));
    let no_experimental = user.iter().any(|a| a == "-XX:-UnlockExperimentalVMOptions");
    // Eigenes -Xmx größer, als der PC hergibt: den Speicher nicht vorab belegen (sonst lagert Windows aus).
    let too_big = matches!((heap_mb, opts.system_mb), (Some(h), Some(sys)) if h > sys.saturating_sub(SYSTEM_RESERVE_MB));
    flags.retain(|flag| {
        let Some(name) = xx_name(flag) else { return true };
        !(user_names.contains(name)
            || (own_collector && is_gc_specific(name))
            || (no_experimental && is_experimental(name, opts.java_major))
            || (too_big && name == "AlwaysPreTouch"))
    });
    // Experimentelle Optionen brauchen die Freischaltung – genau einmal, davor.
    if flags.iter().filter_map(|f| xx_name(f)).any(|n| is_experimental(n, opts.java_major)) {
        flags.insert(0, UNLOCK_EXPERIMENTAL.to_owned());
    }
    let mut seen = std::collections::HashSet::new();
    flags.retain(|f| seen.insert(f.clone()));
    JvmFlags { heap, tuning: flags }
}

/// Heap so begrenzen, dass dem System [`SYSTEM_RESERVE_MB`] bleiben – mit
/// `AlwaysPreTouch` würde Windows sonst auslagern.
fn cap_heap(max_mb: u32, system_mb: Option<u32>) -> u32 {
    let Some(system) = system_mb else { return max_mb };
    let limit = system.saturating_sub(SYSTEM_RESERVE_MB).max(MIN_CAPPED_HEAP_MB);
    if max_mb > limit {
        tracing::info!(
            "Arbeitsspeicher für das Spiel von {max_mb} MB auf {limit} MB begrenzt (PC hat {system} MB, 2 GB bleiben frei)"
        );
        limit
    } else {
        max_mb
    }
}

/// Eigenes `-Xmx…`/`-XX:MaxHeapSize=…` (das letzte gilt, wie bei Java):
/// `None` = nicht gesetzt, `Some(None)` = gesetzt, aber nicht lesbar.
fn user_heap(user: &[String], short: &str, long: &str) -> Option<Option<u32>> {
    user.iter().rev().find_map(|arg| {
        let value = arg.strip_prefix(short).or_else(|| {
            arg.strip_prefix("-XX:").and_then(|rest| rest.strip_prefix(long)).and_then(|rest| rest.strip_prefix('='))
        })?;
        Some(parse_memory_mb(value))
    })
}

/// `4G`, `4096m`, `4194304k` oder Bytes → MB.
fn parse_memory_mb(value: &str) -> Option<u32> {
    let value = value.trim();
    let (digits, factor_kb): (&str, u64) = match value.chars().last()? {
        'k' | 'K' => (&value[..value.len() - 1], 1),
        'm' | 'M' => (&value[..value.len() - 1], 1024),
        'g' | 'G' => (&value[..value.len() - 1], 1024 * 1024),
        't' | 'T' => (&value[..value.len() - 1], 1024 * 1024 * 1024),
        c if c.is_ascii_digit() => {
            let bytes: u64 = value.parse().ok()?;
            return u32::try_from(bytes / (1024 * 1024)).ok();
        }
        _ => return None,
    };
    let kb = digits.parse::<u64>().ok()?.checked_mul(factor_kb)?;
    u32::try_from(kb / 1024).ok()
}

/// Name einer `-XX`-Option: `-XX:+UseG1GC` → `UseG1GC`, `-XX:SurvivorRatio=32` → `SurvivorRatio`.
fn xx_name(arg: &str) -> Option<&str> {
    let rest = arg.strip_prefix("-XX:")?;
    let rest = rest.strip_prefix(['+', '-']).unwrap_or(rest);
    let name = rest.split('=').next().unwrap_or(rest);
    (!name.is_empty()).then_some(name)
}

/// Gehört nur zu einem bestimmten Collector – fällt weg, wenn der Nutzer einen eigenen wählt.
fn is_gc_specific(name: &str) -> bool {
    name.starts_with("G1")
        || (name.starts_with("Use") && name.ends_with("GC"))
        || matches!(
            name,
            "ZGenerational"
                | "MaxGCPauseMillis"
                | "InitiatingHeapOccupancyPercent"
                | "SurvivorRatio"
                | "MaxTenuringThreshold"
                | "ParallelRefProcEnabled"
        )
}

/// Braucht `-XX:+UnlockExperimentalVMOptions`.
fn is_experimental(name: &str, java_major: u32) -> bool {
    matches!(name, "G1NewSizePercent" | "G1MaxNewSizePercent" | "G1MixedGCLiveThresholdPercent")
        // Kompakte Objekt-Header: in Java 24 experimentell, ab 25 regulär.
        || (name == "UseCompactObjectHeaders" && java_major == 24)
}

fn zgc_flags(java_major: u32) -> Vec<&'static str> {
    let mut flags = vec!["-XX:+UseZGC"];
    // Java 21–22: generationell nur auf Wunsch. Ab 23 ist das Standard und das
    // Flag wird als veraltet gemeldet, ab 24 hat es keine Wirkung mehr.
    if java_major < 23 {
        flags.push("-XX:+ZGenerational");
    }
    flags
}

/// Java 24+: kleinere Objekt-Header, spürbar weniger RAM-Verbrauch.
fn compact_headers(java_major: u32) -> Option<&'static str> {
    (java_major >= 24).then_some("-XX:+UseCompactObjectHeaders")
}

/// Voreinstellungen ohne FPS-Boost: G1 mit kurzen Pausen; ab Java 21 und
/// großem Heap das generationelle ZGC.
fn performance_flags(java_major: u32, max_mb: u32) -> Vec<String> {
    let zgc = java_major >= 21 && max_mb >= ZGC_MIN_HEAP_MB;
    let mut flags: Vec<&str> = if zgc {
        zgc_flags(java_major)
    } else {
        vec![
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=50",
            "-XX:G1NewSizePercent=20",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapRegionSize=32M",
        ]
    };
    // Kompakte Objekt-Header nur mit G1 – mit ZGC sind sie nicht in jeder Java-Version erlaubt.
    if !zgc {
        flags.extend(compact_headers(java_major));
    }
    flags.into_iter().map(str::to_owned).collect()
}

/// FPS-Boost: abgestimmte Flags je Java-Version und Speicher.
/// - Ab Java 21 und mindestens 12 GB Heap: generationelles ZGC.
/// - Sonst G1 mit client-tauglichen Werten: junge Generation groß genug für
///   den ständigen Kurzzeit-Müll des Spiels, Pausenziel unter einem Frame bei
///   27 FPS, frühe gemischte Sammlungen statt seltener großer.
///
/// Alles mit `-XX:+AlwaysPreTouch` – zusammen mit Xms = Xmx liegt der Speicher
/// von Anfang an bereit. Nur Flags, die es von Java 8 bis 25 gibt (keine der
/// in Java 21 entfernten wie `G1ConcRSHotCardLimit`).
fn tuned_flags(java_major: u32, max_mb: u32) -> Vec<String> {
    let zgc = java_major >= 21 && max_mb >= ZGC_MIN_HEAP_MB;
    let mut flags: Vec<&str> = if zgc {
        zgc_flags(java_major)
    } else {
        vec![
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=37",
            "-XX:G1NewSizePercent=23",
            "-XX:G1HeapRegionSize=16M",
            "-XX:G1ReservePercent=20",
            "-XX:SurvivorRatio=32",
            "-XX:MaxTenuringThreshold=1",
            "-XX:InitiatingHeapOccupancyPercent=10",
            "-XX:G1MixedGCCountTarget=3",
            "-XX:G1HeapWastePercent=20",
            "-XX:+PerfDisableSharedMem",
        ]
    };
    flags.extend(["-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch"]);
    // Kompakte Objekt-Header nur mit G1 – mit ZGC sind sie nicht in jeder Java-Version erlaubt.
    if !zgc {
        flags.extend(compact_headers(java_major));
    }
    if java_major >= 25 {
        flags.push("-XX:+UseStringDeduplication");
    }
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
            java_major: Some(21),
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
        build_command(p, inst, &Settings::default(), s, dirs(), None, None).unwrap().args
    }

    fn build_joining(p: &Prepared) -> Vec<String> {
        let join = JoinTarget { address: "play.cooltiers.de".into(), host: "srv.cooltiers.de".into(), port: 25577 };
        build_command(p, &instance(), &Settings::default(), &session(), dirs(), Some(&join), None).unwrap().args
    }

    fn has(flags: &[String], f: &str) -> bool {
        flags.iter().any(|x| x == f)
    }

    fn count(flags: &[String], f: &str) -> usize {
        flags.iter().filter(|x| *x == f).count()
    }

    fn opts(tuned: bool, java_major: u32, max_mb: u32) -> JvmOptions {
        JvmOptions { tuned, java_major, max_mb, min_mb: 512, system_mb: None }
    }

    fn user(args: &str) -> Vec<String> {
        split_args(args)
    }

    /// Alles, was eine Option doppelt setzt (`-XX:`-Name oder `-Xmx`/`-Xms`).
    fn duplicates(flags: &[String]) -> Vec<String> {
        let mut seen = std::collections::HashSet::new();
        flags
            .iter()
            .map(|f| xx_name(f).map_or_else(|| f.chars().take(4).collect(), str::to_owned))
            .filter(|n| !seen.insert(n.clone()))
            .collect()
    }

    #[test]
    fn gc_defaults_without_tuning() {
        let g1 = performance_flags(8, 4096);
        assert!(has(&g1, "-XX:+UseG1GC") && !has(&g1, "-XX:+AlwaysPreTouch"));
        // ZGC erst ab 12 GB – vorher G1, auch mit Java 21.
        assert!(has(&performance_flags(21, 8192), "-XX:+UseG1GC"));
        let zgc = performance_flags(21, 12_288);
        assert!(has(&zgc, "-XX:+UseZGC") && has(&zgc, "-XX:+ZGenerational"));
        let j25 = performance_flags(25, 16_384);
        assert!(has(&j25, "-XX:+UseZGC") && !has(&j25, "-XX:+ZGenerational"));
        // Kompakte Header nur mit G1.
        assert!(!has(&j25, "-XX:+UseCompactObjectHeaders"));
        assert!(has(&performance_flags(25, 8192), "-XX:+UseCompactObjectHeaders"));
        // Über jvm_flags: Freischaltung höchstens einmal, nichts doppelt.
        for (java, mb) in [(8, 2048), (17, 4096), (21, 8192), (24, 4096), (24, 16_384), (25, 4096)] {
            let flags = jvm_flags(&opts(false, java, mb), &[]).tuning;
            assert!(duplicates(&flags).is_empty(), "{java}/{mb}: {flags:?}");
            assert!(count(&flags, UNLOCK_EXPERIMENTAL) <= 1);
        }
        let j24 = jvm_flags(&opts(false, 24, 4096), &[]).tuning;
        assert_eq!(j24[0], UNLOCK_EXPERIMENTAL);
    }

    #[test]
    fn tuned_flags_per_java_and_memory() {
        // G1 mit den Client-Werten – auch mit Java 21 und 8 GB.
        for (java, mb) in [(8, 4096), (17, 8192), (21, 4096), (21, 8192), (21, 12_287)] {
            let g1 = tuned_flags(java, mb);
            for f in [
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=37",
                "-XX:G1NewSizePercent=23",
                "-XX:G1HeapRegionSize=16M",
                "-XX:G1ReservePercent=20",
                "-XX:SurvivorRatio=32",
                "-XX:MaxTenuringThreshold=1",
                "-XX:InitiatingHeapOccupancyPercent=10",
                "-XX:G1MixedGCCountTarget=3",
                "-XX:G1HeapWastePercent=20",
                "-XX:+AlwaysPreTouch",
                "-XX:+DisableExplicitGC",
                "-XX:+PerfDisableSharedMem",
            ] {
                assert!(has(&g1, f), "{java}/{mb}: {f} fehlt");
            }
            assert!(!has(&g1, "-XX:+UseZGC"));
        }
        // In Java 21 entfernte Flags tauchen nirgends auf.
        for java in [8, 17, 21, 25] {
            for mb in [2048, 16_384] {
                let joined = tuned_flags(java, mb).join(" ");
                assert!(!joined.contains("G1ConcRSHotCardLimit") && !joined.contains("G1ConcRefinementServiceIntervalMillis"));
            }
        }
        // Java 21–22 ab 12 GB: generationelles ZGC, ohne G1-Flags.
        let j21 = tuned_flags(21, 12_288);
        assert!(has(&j21, "-XX:+UseZGC") && has(&j21, "-XX:+ZGenerational") && !has(&j21, "-XX:+UseG1GC"));
        assert!(has(&j21, "-XX:+DisableExplicitGC") && has(&j21, "-XX:+AlwaysPreTouch"));
        assert!(!j21.iter().any(|f| f.contains("G1")));
        // Ab 23 ohne ZGenerational (Standard, als veraltet gemeldet); kompakte Header nur mit G1.
        assert!(!has(&tuned_flags(23, 16_384), "-XX:+ZGenerational"));
        let j24 = tuned_flags(24, 16_384);
        assert!(!has(&j24, "-XX:+ZGenerational") && !has(&j24, "-XX:+UseCompactObjectHeaders"));
        assert!(has(&tuned_flags(24, 8192), "-XX:+UseCompactObjectHeaders"));
        // Java 8 hat kein ZGC – egal wie viel Speicher.
        assert!(has(&tuned_flags(8, 32_768), "-XX:+UseG1GC"));
        // Java 25: dazu String-Deduplizierung.
        assert!(has(&tuned_flags(25, 8192), "-XX:+UseStringDeduplication"));

        // Freischaltung: bei G1 (G1NewSizePercent) und Java 24 (Header) genau einmal, ganz vorne.
        for (java, mb) in [(8, 4096), (21, 4096), (24, 2048), (25, 4096)] {
            let flags = jvm_flags(&opts(true, java, mb), &[]).tuning;
            assert!(duplicates(&flags).is_empty(), "{java}/{mb}: {flags:?}");
            assert_eq!(count(&flags, UNLOCK_EXPERIMENTAL), 1, "{java}/{mb}");
            assert_eq!(flags[0], UNLOCK_EXPERIMENTAL);
        }
        // ZGC (auch Java 24): keine kompakten Header, nichts Experimentelles – keine Freischaltung.
        let z24 = jvm_flags(&opts(true, 24, 16_384), &[]).tuning;
        assert!(has(&z24, "-XX:+UseZGC") && !has(&z24, UNLOCK_EXPERIMENTAL));
        // ZGC mit Java 25: nichts Experimentelles – keine Freischaltung.
        let z25 = jvm_flags(&opts(true, 25, 16_384), &[]).tuning;
        assert!(has(&z25, "-XX:+UseZGC") && !has(&z25, UNLOCK_EXPERIMENTAL));
    }

    #[test]
    fn heap_follows_settings_and_user_args() {
        // Abgestimmt: Xms = Xmx.
        let f = jvm_flags(&opts(true, 21, 6144), &[]);
        assert_eq!(f.heap, ["-Xmx6144M", "-Xms6144M"]);
        // Ohne Abstimmung: Mindestwert aus den Einstellungen.
        assert_eq!(jvm_flags(&opts(false, 21, 6144), &[]).heap, ["-Xmx6144M", "-Xms512M"]);

        // Eigenes -Xmx gewinnt; Xms folgt ihm (abgestimmt) bzw. bleibt darunter.
        let f = jvm_flags(&opts(true, 21, 4096), &user("-Xmx8G"));
        assert_eq!(f.heap, ["-Xms8192M"]);
        let f = jvm_flags(&opts(false, 21, 4096), &user("-Xmx256m"));
        assert_eq!(f.heap, ["-Xms256M"], "Xms darf nie über Xmx liegen");
        // Eigenes -Xms bleibt, unser Xmx kommt dazu.
        assert_eq!(jvm_flags(&opts(true, 21, 4096), &user("-Xms1G")).heap, ["-Xmx4096M"]);
        // Unlesbares -Xmx: dann lieber gar kein Xms setzen.
        assert!(jvm_flags(&opts(true, 21, 4096), &user("-Xmxviel")).heap.is_empty());
        // Das eigene -Xmx entscheidet auch über den Collector.
        assert!(has(&jvm_flags(&opts(true, 21, 4096), &user("-Xmx16g")).tuning, "-XX:+UseZGC"));
        assert!(has(&jvm_flags(&opts(true, 21, 16_384), &user("-XX:MaxHeapSize=4g")).tuning, "-XX:+UseG1GC"));

        assert_eq!(parse_memory_mb("4G"), Some(4096));
        assert_eq!(parse_memory_mb("4096m"), Some(4096));
        assert_eq!(parse_memory_mb("4194304k"), Some(4096));
        assert_eq!(parse_memory_mb("4294967296"), Some(4096));
        assert_eq!(parse_memory_mb("1t"), Some(1_048_576));
        assert_eq!(parse_memory_mb("x"), None);
        assert_eq!(parse_memory_mb(""), None);
        assert_eq!(parse_memory_mb("99999999999999999999g"), None);
    }

    #[test]
    fn heap_is_capped_to_the_pc() {
        // 16 GB PC: 20 GB eingestellt → 14 GB (2 GB bleiben frei).
        let f = jvm_flags(&JvmOptions { system_mb: Some(16_384), ..opts(true, 21, 20_480) }, &[]);
        assert_eq!(f.heap, ["-Xmx14336M", "-Xms14336M"]);
        assert!(has(&f.tuning, "-XX:+AlwaysPreTouch"));
        // Passt: bleibt.
        assert_eq!(cap_heap(6144, Some(16_384)), 6144);
        assert_eq!(cap_heap(6144, None), 6144);
        // Winziger PC: nie unter 1 GB.
        assert_eq!(cap_heap(4096, Some(2048)), 1024);
        // Eigenes -Xmx größer als der PC: bleibt, aber ohne Vorab-Belegung.
        let f = jvm_flags(&JvmOptions { system_mb: Some(8192), ..opts(true, 21, 4096) }, &user("-Xmx12G"));
        assert!(!has(&f.tuning, "-XX:+AlwaysPreTouch"));
    }

    #[test]
    fn user_flags_win_on_overlap() {
        // Eigener GC-Schalter: keine GC-Flags vom Launcher, allgemeine bleiben.
        let f = jvm_flags(&opts(true, 21, 4096), &user("-XX:+UseShenandoahGC")).tuning;
        assert!(!f.iter().any(|x| xx_name(x).is_some_and(is_gc_specific)), "{f:?}");
        assert!(has(&f, "-XX:+AlwaysPreTouch") && has(&f, "-XX:+DisableExplicitGC"));
        assert!(!has(&f, UNLOCK_EXPERIMENTAL), "ohne G1NewSizePercent keine Freischaltung");
        let f = jvm_flags(&opts(false, 21, 16_384), &user("-XX:+UseG1GC")).tuning;
        assert!(!has(&f, "-XX:+UseZGC") && !has(&f, "-XX:+ZGenerational"));

        // Gleiche Option: Nutzerwert gewinnt, unsere fällt weg (auch als -XX:-…).
        let f = jvm_flags(&opts(true, 21, 4096), &user("-XX:MaxGCPauseMillis=100 -XX:-AlwaysPreTouch")).tuning;
        assert!(!f.iter().any(|x| x.starts_with("-XX:MaxGCPauseMillis")) && !has(&f, "-XX:+AlwaysPreTouch"));
        assert!(has(&f, "-XX:+UseG1GC"));
        // Freischaltung ausdrücklich aus: keine experimentellen Flags.
        let f = jvm_flags(&opts(true, 21, 4096), &user("-XX:-UnlockExperimentalVMOptions")).tuning;
        assert!(!f.iter().any(|x| x.starts_with("-XX:G1NewSizePercent")) && !has(&f, UNLOCK_EXPERIMENTAL));
        // Eigene Freischaltung: unsere bleibt vorne (Reihenfolge), aber nur einmal.
        let f = jvm_flags(&opts(true, 21, 4096), &user("-XX:+UnlockExperimentalVMOptions")).tuning;
        assert_eq!(count(&f, UNLOCK_EXPERIMENTAL), 1);

        assert_eq!(xx_name("-XX:+UseG1GC"), Some("UseG1GC"));
        assert_eq!(xx_name("-XX:SurvivorRatio=8"), Some("SurvivorRatio"));
        assert_eq!(xx_name("-Xmx4G"), None);
    }

    #[test]
    fn tuning_stays_on_with_own_args() {
        // Standard (an): Xms = Xmx, abgestimmte Flags.
        let args = build(&prepared(MODERN), &instance(), &session());
        assert!(has(&args, "-Xms4096M") && has(&args, "-XX:+AlwaysPreTouch"));

        // Pro Instanz aus: schlichte Voreinstellungen, Xms aus den Einstellungen.
        let mut off = instance();
        off.overrides.performance_tuning = Some(false);
        let args = build(&prepared(MODERN), &off, &session());
        assert!(has(&args, "-Xms512M") && !has(&args, "-XX:+AlwaysPreTouch"));
        assert!(has(&args, "-XX:+UseG1GC"));

        // Eigene JVM-Argumente: Abstimmung bleibt an, eigene Werte kommen dazu.
        let mut own = instance();
        own.overrides.jvm_args = Some("-Dfoo=bar -Xmx6G".into());
        let args = build(&prepared(MODERN), &own, &session());
        assert!(has(&args, "-Xms6144M") && !has(&args, "-Xmx4096M") && has(&args, "-XX:+AlwaysPreTouch"));
        assert!(has(&args, "-Dfoo=bar") && has(&args, "-Xmx6G"));
        let xx: Vec<String> = args.iter().filter(|a| a.starts_with("-XX:")).cloned().collect();
        assert!(duplicates(&xx).is_empty(), "{xx:?}");

        // Eigener Collector bleibt allein.
        let mut gc = instance();
        gc.overrides.jvm_args = Some("-XX:+UseShenandoahGC".into());
        let args = build(&prepared(MODERN), &gc, &session());
        assert!(has(&args, "-XX:+UseShenandoahGC") && !has(&args, "-XX:+UseG1GC") && !has(&args, "-XX:+UseZGC"));
    }

    #[test]
    fn java_version_comes_from_the_runtime() {
        // Metadaten sagen Java 21, gestartet wird mit Java 25 → Flags für 25.
        let mut p = prepared(
            r#"{"id":"1.21.1","mainClass":"M","javaVersion":{"component":"java-runtime-delta","majorVersion":21},
                "arguments":{"jvm":["-cp","${classpath}"],"game":["--username","${auth_player_name}"]}}"#,
        );
        p.java_major = Some(25);
        let mut inst = instance();
        inst.overrides.max_memory_mb = Some(16_384);
        let args = build(&p, &inst, &session());
        assert!(has(&args, "-XX:+UseZGC") && !has(&args, "-XX:+ZGenerational"));
        // Nicht erkennbar: Metadaten (21) → mit ZGenerational.
        p.java_major = None;
        let args = build(&p, &inst, &session());
        assert!(has(&args, "-XX:+UseZGC") && has(&args, "-XX:+ZGenerational"));
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
        // Regeln mit `os.name` gelten nur auf dem passenden System.
        assert_eq!(args.contains(&"-XX:HeapDumpPath=x.heapdump".to_owned()), cfg!(windows));
        assert!(!joined.contains("XstartOnFirstThread"));
        assert!(args.contains(&r"-Djava.library.path=C:\natives".to_owned()));
        let classpath = format!(r"C:\l\a.jar{}C:\v\client.jar", crate::platform::CLASSPATH_SEPARATOR);
        assert!(args.contains(&classpath));
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
        let args = build_command(&prepared(MODERN), &instance(), &settings, &session(), dirs(), None, None).unwrap().args;
        assert!(args.contains(&"--fullscreen".to_owned()));
        inst.overrides.fullscreen = Some(false);
        let args = build_command(&prepared(MODERN), &inst, &settings, &session(), dirs(), None, None).unwrap().args;
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
    fn extra_jvm_args_go_before_the_main_class_unless_the_user_set_them() {
        let p = prepared(r#"{"id":"1.21.1","mainClass":"net.minecraft.client.main.Main",
            "arguments":{"game":["--username","${auth_player_name}"],"jvm":["-cp","${classpath}"]}}"#);
        let mut cmd = build_command(&p, &instance(), &Settings::default(), &session(), dirs(), None, None).unwrap();
        insert_jvm_args(&mut cmd, &p, vec!["-Dfabric.debug.disableModIds=lithium".into()]);
        let main = cmd.args.iter().position(|a| a == "net.minecraft.client.main.Main").unwrap();
        assert_eq!(cmd.args[main - 1], "-Dfabric.debug.disableModIds=lithium");
        // Schon vorhanden (z. B. eigene JVM-Argumente): nicht doppelt.
        insert_jvm_args(&mut cmd, &p, vec!["-Dfabric.debug.disableModIds=other".into()]);
        assert_eq!(cmd.args.iter().filter(|a| a.starts_with("-Dfabric.debug.disableModIds")).count(), 1);
    }

    #[test]
    fn arg_splitting() {
        assert_eq!(split_args(r#"  -a  "-b c"  -d"e f"  "#), ["-a", "-b c", "-de f"]);
        assert!(split_args("   ").is_empty());
        assert_eq!(split_args(r#""""#), [""]);
    }
}
