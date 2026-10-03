//! Baut aus der `GameLaunchSpec` die JVM-Argumente und Umgebung für die iOS-Engine.
//! Die festen Teile entsprechen `launchJVM` aus Amethyst-iOS (`JavaLauncher.m`).

use std::collections::BTreeMap;
use std::path::Path;

use serde::Serialize;

use super::jit::JitHelp;
use super::memory::HeapPlan;
use super::probe::DeviceProbe;
use crate::{Error, Result};
use crate::models::{GameLaunchSpec, Renderer};

/// Startklasse in `trs-boot.jar`: lädt den Spiel-Klassenpfad und ruft `main` auf.
pub const BOOT_CLASS: &str = "dev.theredstonee.trs.ios.TrsBoot";
pub const ENGINE_LIB: &str = "libtrsengine.dylib";

/// Bibliotheken, die die Engine selbst mitbringt (LWJGL-Fork) oder die auf iOS nicht laufen.
const SKIPPED_LIBRARIES: [&str; 4] = ["/org/lwjgl/", "/com/mojang/text2speech/", "/net/java/dev/jna/platform/", "/tv/twitch/"];
/// Umgebungsvariablen, die das Profil nicht überschreiben darf.
const RESERVED_ENV: [&str; 9] = ["HOME", "PATH", "JAVA_HOME", "BUNDLE_PATH", "POJAV_HOME", "POJAV_GAME_DIR", "POJAV_RENDERER", "TMPDIR", "JIT_FLAGS"];

/// Ordner in der `.app` (gefüllt von `scripts/ios/package-ipa.sh`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EngineLayout {
    /// Pfad der `.app` (iOS-Pfad mit `/`).
    pub bundle: String,
    /// Jars aus `libs_caciocavallo` (Java 8) bzw. `libs_caciocavallo17`.
    pub cacio8: Vec<String>,
    pub cacio17: Vec<String>,
}

impl EngineLayout {
    pub fn scan(bundle: &Path) -> Self {
        Self { bundle: bundle.to_string_lossy().into_owned(), cacio8: jars(&bundle.join("libs_caciocavallo")), cacio17: jars(&bundle.join("libs_caciocavallo17")) }
    }
    pub fn frameworks(&self) -> String {
        format!("{}/Frameworks", self.bundle)
    }
    pub fn libs(&self) -> String {
        format!("{}/libs", self.bundle)
    }
}

fn jars(dir: &Path) -> Vec<String> {
    let mut out: Vec<String> = std::fs::read_dir(dir)
        .map(|rd| {
            rd.filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| p.extension().is_some_and(|x| x == "jar"))
                .map(|p| p.to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default();
    out.sort();
    out
}

/// Was das Swift-Plugin zum Start bekommt.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineLaunch {
    pub session: String,
    pub java_home: String,
    pub java_major: u8,
    /// Vollständige Argumentliste für `JLI_Launch` (argv[0] = `<javaHome>/bin/java`).
    pub argv: Vec<String>,
    pub env: BTreeMap<String, String>,
    pub game_dir: String,
    pub xmx_mb: u32,
    pub window_width: u32,
    pub window_height: u32,
    /// `POJAV_RENDERER`: `auto` oder Bibliotheksname.
    pub renderer: String,
    /// Zink rendert über OSMesa in einen einfachen `CALayer`, sonst `CAMetalLayer`.
    pub metal_layer: bool,
    pub wait_for_jit: bool,
    pub jit_help: JitHelp,
    /// Touch-Layout (`GameLaunchSpec.touchProfile`) und sein Ordner – für das Overlay.
    pub touch_profile: Option<String>,
    pub controls_dir: Option<String>,
}

/// `auto` bleibt auf iOS `auto`: Amethysts eigene Wahl (Start mit ANGLE, dann je nach
/// GL-Version des Fensters gl4es oder MobileGlues) statt `Renderer::resolve`.
pub fn renderer_lib(renderer: Renderer) -> &'static str {
    match renderer {
        Renderer::Auto => "auto",
        Renderer::Gl4es => "libgl4es_114.dylib",
        Renderer::Mobileglues => "libmobileglues.dylib",
        Renderer::Zink => "libOSMesa.8.dylib",
    }
}

/// Spielauflösung: Querformat, gerade Zahlen (wie Amethyst bei 100 %).
pub fn window_size(probe: &DeviceProbe) -> (u32, u32) {
    let (a, b) = (probe.screen.width_px, probe.screen.height_px);
    let (w, h) = (a.max(b), a.min(b));
    (w - w % 2, h - h % 2)
}

fn check_text(value: &str, what: &'static str) -> Result<()> {
    if value.contains('\0') || value.len() > 32 * 1024 {
        return Err(Error::InvalidSpec(what));
    }
    Ok(())
}

fn check_abs_path(value: &str, what: &'static str) -> Result<()> {
    check_text(value, what)?;
    let absolute = value.starts_with('/') || Path::new(value).is_absolute();
    if !absolute || value.split(['/', '\\']).any(|c| c == "..") {
        return Err(Error::InvalidSpec(what));
    }
    Ok(())
}

fn valid_class(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 256
        && !name.starts_with('.')
        && !name.ends_with('.')
        && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '$'))
}

fn valid_env_key(key: &str) -> bool {
    let mut chars = key.chars();
    matches!(chars.next(), Some(c) if c.is_ascii_uppercase() || c == '_')
        && key.len() <= 64
        && chars.all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == '_')
}

fn reserved_env(key: &str) -> bool {
    RESERVED_ENV.contains(&key) || key.starts_with("DYLD_") || key.starts_with("LD_") || key.starts_with("TRS_")
}

/// Klassenpfad ohne LWJGL & Co.; Einträge müssen absolut sein und dürfen kein `:` enthalten.
pub fn game_classpath(spec: &GameLaunchSpec) -> Result<Vec<String>> {
    let mut out = Vec::with_capacity(spec.classpath.len());
    for entry in &spec.classpath {
        check_abs_path(entry, "classpath")?;
        if entry.contains(':') {
            return Err(Error::InvalidSpec("classpath"));
        }
        let unified = entry.replace('\\', "/");
        if SKIPPED_LIBRARIES.iter().any(|s| unified.contains(s)) {
            continue;
        }
        out.push(entry.clone());
    }
    if out.is_empty() {
        return Err(Error::InvalidSpec("classpath"));
    }
    Ok(out)
}

/// JVM-Argumente aus dem Profil ohne das, was die Engine selbst setzt.
pub fn filter_jvm_args(args: &[String]) -> Result<Vec<String>> {
    let mut out = Vec::with_capacity(args.len());
    let mut skip_next = false;
    for arg in args {
        check_text(arg, "jvmArgs")?;
        if std::mem::take(&mut skip_next) {
            continue;
        }
        if arg == "-cp" || arg == "-classpath" || arg == "--class-path" {
            skip_next = true;
            continue;
        }
        let dropped = ["-Xmx", "-Xms", "-Djava.library.path=", "-Dorg.lwjgl.librarypath=", "-Djava.system.class.loader="]
            .iter()
            .any(|p| arg.starts_with(p))
            || matches!(arg.as_str(), "-d32" | "-d64" | "-XstartOnFirstThread" | "-server" | "-client");
        if !dropped {
            out.push(arg.clone());
        }
    }
    Ok(out)
}

pub struct BuildInput<'a> {
    pub spec: &'a GameLaunchSpec,
    pub probe: &'a DeviceProbe,
    pub layout: &'a EngineLayout,
    pub java_home: &'a str,
    pub java_major: u8,
    pub heap: HeapPlan,
    pub session: &'a str,
    pub wait_for_jit: bool,
    pub jit_help: JitHelp,
}

pub fn build(input: BuildInput<'_>) -> Result<EngineLaunch> {
    let BuildInput { spec, probe, layout, java_home, java_major, heap, session, wait_for_jit, jit_help } = input;
    // Minecraft 26.3+ öffnet sein Fenster über SDL3 – Amethyst-iOS kennt nur GLFW (docs/mobile.md).
    if spec.uses_sdl {
        return Err(Error::SdlUnsupported);
    }
    let game_dir = spec.game_dir.to_string_lossy().into_owned();
    check_abs_path(&game_dir, "gameDir")?;
    check_abs_path(java_home, "javaHome")?;
    check_abs_path(&probe.home_dir, "homeDir")?;
    if !valid_class(&spec.main_class) {
        return Err(Error::InvalidSpec("mainClass"));
    }
    for arg in &spec.game_args {
        check_text(arg, "gameArgs")?;
    }
    let classpath = game_classpath(spec)?;
    let extra_jvm = filter_jvm_args(&spec.jvm_args)?;
    let (width, height) = window_size(probe);
    if width == 0 || height == 0 {
        return Err(Error::Engine("Bildschirmgröße unbekannt".into()));
    }
    let renderer = renderer_lib(spec.renderer);
    let java8 = java_major <= 8;
    let frameworks = layout.frameworks();
    let libs = layout.libs();
    let tz = if probe.time_zone.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '_' | '-' | '+')) && !probe.time_zone.is_empty() {
        probe.time_zone.as_str()
    } else {
        "UTC"
    };

    let mut a: Vec<String> = Vec::with_capacity(96 + extra_jvm.len() + spec.game_args.len());
    a.push(format!("{java_home}/bin/java"));
    a.push("-XstartOnFirstThread".into());
    a.push("-Djava.system.class.loader=net.kdt.pojavlaunch.PojavClassLoader".into());
    a.push(format!("-Xms{}M", heap.xmx_mb.min(128)));
    a.push(format!("-Xmx{}M", heap.xmx_mb));
    a.push(format!("-Djava.library.path={frameworks}"));
    a.push(format!("-Duser.dir={game_dir}"));
    a.push(format!("-Duser.home={}", probe.home_dir));
    a.push(format!("-Duser.timezone={tz}"));
    a.push(format!("-DUIScreen.maximumFramesPerSecond={}", probe.screen.max_fps.clamp(30, 240)));
    a.push("-Dorg.lwjgl.glfw.checkThread0=false".into());
    a.push("-Dorg.lwjgl.system.allocator=system".into());
    a.push("-Dlog4j2.formatMsgNoLookups=true".into());
    // Wie Amethyst: bei "auto" erst ANGLE, das GLFW-Fenster wählt dann je nach GL-Version.
    let gl_lib = if renderer == "auto" { "libtinygl4angle.dylib" } else { renderer };
    a.push(format!("-Dorg.lwjgl.opengl.libname={gl_lib}"));
    if spec.engine_lwjgl() == "3.4.1" {
        // Minecraft 26.2 lädt SPIRV-Cross beim Start; die App bringt es mit (wie Amethyst-iOS).
        a.push("-Dorg.lwjgl.spvc.libname=libspirv-cross-c-shared.0.dylib".into());
    }
    a.push(format!("-javaagent:{libs}/patchjna_agent.jar="));
    a.push("-XX:+UnlockExperimentalVMOptions".into());
    a.push("-XX:+DisablePrimordialThreadGuardPages".into());
    if probe.os_major() >= 26 {
        a.push("-XX:+MirrorMappedCodeCache".into());
    }
    a.push("-Dfml.earlyprogresswindow=false".into());
    // Caciocavallo (AWT ohne Fenster-System).
    a.push("-Djava.awt.headless=false".into());
    a.push("-Dcacio.font.fontmanager=sun.awt.X11FontManager".into());
    a.push("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler".into());
    a.push(format!("-Dcacio.managed.screensize={width}x{height}"));
    a.push("-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel".into());
    if java8 {
        a.push("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit".into());
        a.push("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment".into());
    } else {
        a.push("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit".into());
        a.push("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment".into());
        for export in [
            "java.desktop/java.awt",
            "java.desktop/java.awt.peer",
            "java.desktop/sun.awt.image",
            "java.desktop/sun.java2d",
            "java.desktop/java.awt.dnd.peer",
            "java.desktop/sun.awt",
            "java.desktop/sun.awt.event",
            "java.desktop/sun.awt.datatransfer",
            "java.desktop/sun.font",
            "java.base/sun.security.action",
            // Amethyst: Übergang für Forge 1.17+ ohne eigenen Starter.
            "cpw.mods.bootstraplauncher/cpw.mods.bootstraplauncher",
        ] {
            a.push(format!("--add-exports={export}=ALL-UNNAMED"));
        }
        for open in ["java.base/java.util", "java.desktop/java.awt", "java.desktop/sun.font", "java.desktop/sun.java2d", "java.base/java.lang.reflect", "java.base/java.net"] {
            a.push(format!("--add-opens={open}=ALL-UNNAMED"));
        }
    }
    let cacio = if java8 { &layout.cacio8 } else { &layout.cacio17 };
    if !cacio.is_empty() {
        a.push(format!("-Xbootclasspath/{}:{}", if java8 { "p" } else { "a" }, cacio.join(":")));
    }
    // Ohne extended-virtual-addressing schlägt der komprimierte Klassenbereich fehl.
    a.push("-XX:-UseCompressedClassPointers".into());
    // Sodium bricht sonst wegen der mitgebrachten LWJGL ab (26.x will 3.4.3). Vor den eigenen
    // Argumenten, damit ein eigener Wert ihn noch überschreiben kann.
    a.push("-Dsodium.checks.issue2561=false".into());
    a.extend(extra_jvm);
    // Vertrag: das liest der TRS Client.
    let [l, t, r, b] = probe.safe_insets_px;
    a.push("-Dtrs.mobile=ios".into());
    a.push("-Dtrs.touch=true".into());
    a.push("-Dtrs.overlay.version=1".into());
    a.push(format!("-Dtrs.safeInsets={l},{t},{r},{b}"));
    a.push(format!("-Dtrs.boot.classpath={}", classpath.join(":")));
    a.push("-cp".into());
    a.push(format!("{libs}/*"));
    a.push(BOOT_CLASS.into());
    a.push(spec.main_class.clone());
    a.extend(spec.game_args.iter().cloned());

    let mut env = BTreeMap::new();
    for (key, value) in &spec.extra_env {
        if !valid_env_key(key) || reserved_env(key) {
            return Err(Error::InvalidSpec("extraEnv"));
        }
        check_text(value, "extraEnv")?;
        env.insert(key.clone(), value.clone());
    }
    env.insert("BUNDLE_PATH".into(), layout.bundle.clone());
    env.insert("POJAV_HOME".into(), probe.home_dir.clone());
    env.insert("POJAV_GAME_DIR".into(), game_dir.clone());
    env.insert("POJAV_RENDERER".into(), renderer.into());
    env.insert("TRS_ENGINE_LIB".into(), format!("{frameworks}/{ENGINE_LIB}"));

    let controls_dir = match &spec.controls_dir {
        Some(dir) => {
            let dir = dir.display().to_string();
            check_abs_path(&dir, "controlsDir")?;
            Some(dir)
        }
        None => None,
    };

    Ok(EngineLaunch {
        session: session.into(),
        java_home: java_home.into(),
        java_major,
        argv: a,
        env,
        game_dir,
        xmx_mb: heap.xmx_mb,
        window_width: width,
        window_height: height,
        renderer: renderer.into(),
        metal_layer: spec.renderer != Renderer::Zink,
        wait_for_jit,
        jit_help,
        touch_profile: spec.touch_profile.clone(),
        controls_dir,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ios::memory::HeapLimit;
    use crate::ios::probe::ScreenProbe;

    fn probe() -> DeviceProbe {
        DeviceProbe {
            engine_installed: true,
            os_version: "18.2".into(),
            screen: ScreenProbe { width_px: 1179, height_px: 2556, scale: 3.0, max_fps: 120 },
            safe_insets_px: [177, 0, 177, 63],
            bundle_path: "/app/TRS.app".into(),
            home_dir: "/home/Documents".into(),
            time_zone: "Europe/Berlin".into(),
            ..Default::default()
        }
    }

    fn spec() -> GameLaunchSpec {
        GameLaunchSpec {
            game_dir: "/data/instances/demo".into(),
            assets_dir: "/data/assets".into(),
            memory_mb: 2048,
            classpath: vec![
                "/data/libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar".into(),
                "/data/libraries/com/mojang/brigadier/1.2.9/brigadier-1.2.9.jar".into(),
                "/data/versions/1.21.1/1.21.1.jar".into(),
            ],
            main_class: "net.minecraft.client.main.Main".into(),
            jvm_args: vec!["-Xmx8G".into(), "-cp".into(), "/x.jar".into(), "-Djava.library.path=/natives".into(), "-Dfoo=bar".into()],
            game_args: vec!["--username".into(), "Steve".into()],
            java_major: 21,
            ..Default::default()
        }
    }

    fn layout() -> EngineLayout {
        EngineLayout { bundle: "/app/TRS.app".into(), cacio8: vec!["/app/TRS.app/libs_caciocavallo/a.jar".into()], cacio17: vec!["/app/TRS.app/libs_caciocavallo17/b.jar".into()] }
    }

    fn input<'a>(spec: &'a GameLaunchSpec, probe: &'a DeviceProbe, layout: &'a EngineLayout, major: u8) -> BuildInput<'a> {
        BuildInput {
            spec,
            probe,
            layout,
            java_home: "/data/runtimes/ios-aarch64/java-21",
            java_major: major,
            heap: HeapPlan { xmx_mb: 2048, limited_by: HeapLimit::Auto, low: false },
            session: "ios-1",
            wait_for_jit: false,
            jit_help: JitHelp::Generic,
        }
    }

    #[test]
    fn builds_full_command_line() {
        let (spec, probe, layout) = (spec(), probe(), layout());
        let launch = build(input(&spec, &probe, &layout, 21)).unwrap();
        let argv = &launch.argv;
        assert_eq!(argv[0], "/data/runtimes/ios-aarch64/java-21/bin/java");
        assert!(argv.contains(&"-Xmx2048M".to_string()));
        assert!(argv.contains(&"-Xms128M".to_string()));
        assert!(!argv.iter().any(|a| a == "-Xmx8G" || a == "/x.jar" || a.starts_with("-Djava.library.path=/natives")));
        assert!(argv.contains(&"-Dfoo=bar".to_string()));
        assert!(argv.contains(&"-Djava.library.path=/app/TRS.app/Frameworks".to_string()));
        assert!(argv.contains(&"-Dcacio.managed.screensize=2556x1178".to_string()));
        assert!(argv.contains(&"-Dtrs.mobile=ios".to_string()));
        assert!(argv.contains(&"-Dtrs.touch=true".to_string()));
        assert!(argv.contains(&"-Dtrs.overlay.version=1".to_string()));
        assert!(argv.contains(&"-Dtrs.safeInsets=177,0,177,63".to_string()));
        assert!(argv.contains(&"-Xbootclasspath/a:/app/TRS.app/libs_caciocavallo17/b.jar".to_string()));
        assert!(!argv.iter().any(|a| a.contains("MirrorMappedCodeCache")));
        let cp = argv.iter().find(|a| a.starts_with("-Dtrs.boot.classpath=")).unwrap();
        assert!(!cp.contains("lwjgl"));
        assert!(cp.contains("brigadier") && cp.ends_with("1.21.1.jar"));
        // Ende: -cp <libs>/* Boot Hauptklasse Spielargumente
        let n = argv.len();
        assert_eq!(&argv[n - 6..], ["-cp", "/app/TRS.app/libs/*", BOOT_CLASS, "net.minecraft.client.main.Main", "--username", "Steve"]);
        // Profil-Argumente stehen vor den Vertragswerten (die gewinnen).
        let foo = argv.iter().position(|a| a == "-Dfoo=bar").unwrap();
        let touch = argv.iter().position(|a| a == "-Dtrs.touch=true").unwrap();
        assert!(foo < touch);
        assert_eq!(launch.env["TRS_ENGINE_LIB"], "/app/TRS.app/Frameworks/libtrsengine.dylib");
        assert_eq!(launch.env["POJAV_RENDERER"], "auto");
        assert!(argv.contains(&"-Dorg.lwjgl.opengl.libname=libtinygl4angle.dylib".to_string()));
        assert!(launch.metal_layer);
    }

    #[test]
    fn java8_uses_old_cacio_and_prepend() {
        let (mut spec, probe, layout) = (spec(), probe(), layout());
        spec.renderer = Renderer::Gl4es;
        let launch = build(input(&spec, &probe, &layout, 8)).unwrap();
        assert!(launch.argv.contains(&"-Xbootclasspath/p:/app/TRS.app/libs_caciocavallo/a.jar".to_string()));
        assert!(launch.argv.contains(&"-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit".to_string()));
        assert!(!launch.argv.iter().any(|a| a.starts_with("--add-exports")));
        assert!(launch.argv.contains(&"-Dorg.lwjgl.opengl.libname=libgl4es_114.dylib".to_string()));
    }

    #[test]
    fn minecraft_26_needs_spvc_and_rejects_sdl() {
        let (mut spec, probe, layout) = (spec(), probe(), layout());
        spec.lwjgl_version = Some("3.4.1".into());
        let launch = build(input(&spec, &probe, &layout, 25)).unwrap();
        assert!(launch.argv.contains(&"-Dorg.lwjgl.spvc.libname=libspirv-cross-c-shared.0.dylib".to_string()));
        spec.lwjgl_version = Some("3.4.3".into());
        spec.uses_sdl = true;
        assert!(matches!(build(input(&spec, &probe, &layout, 25)), Err(Error::SdlUnsupported)));
    }

    #[test]
    fn ios26_and_zink() {
        let (mut spec, mut probe, layout) = (spec(), probe(), layout());
        probe.os_version = "26.1".into();
        spec.renderer = Renderer::Zink;
        let launch = build(input(&spec, &probe, &layout, 21)).unwrap();
        assert!(launch.argv.contains(&"-XX:+MirrorMappedCodeCache".to_string()));
        assert!(!launch.metal_layer);
        assert_eq!(launch.env["POJAV_RENDERER"], "libOSMesa.8.dylib");
    }

    #[test]
    fn rejects_bad_input() {
        let (probe, layout) = (probe(), layout());
        let mut s = spec();
        s.main_class = "net.minecraft;rm".into();
        assert!(matches!(build(input(&s, &probe, &layout, 21)), Err(Error::InvalidSpec("mainClass"))));
        let mut s = spec();
        s.game_dir = "relative/dir".into();
        assert!(matches!(build(input(&s, &probe, &layout, 21)), Err(Error::InvalidSpec("gameDir"))));
        let mut s = spec();
        s.classpath.push("/a/../../etc.jar".into());
        assert!(matches!(build(input(&s, &probe, &layout, 21)), Err(Error::InvalidSpec("classpath"))));
        let mut s = spec();
        s.classpath = vec!["/x/org/lwjgl/lwjgl.jar".into()];
        assert!(matches!(build(input(&s, &probe, &layout, 21)), Err(Error::InvalidSpec("classpath"))));
        let mut s = spec();
        s.extra_env.insert("DYLD_INSERT_LIBRARIES".into(), "/x".into());
        assert!(matches!(build(input(&s, &probe, &layout, 21)), Err(Error::InvalidSpec("extraEnv"))));
        let mut s = spec();
        s.extra_env.insert("lower".into(), "x".into());
        assert!(matches!(build(input(&s, &probe, &layout, 21)), Err(Error::InvalidSpec("extraEnv"))));
        let mut s = spec();
        s.game_args.push("a\0b".into());
        assert!(matches!(build(input(&s, &probe, &layout, 21)), Err(Error::InvalidSpec("gameArgs"))));
    }

    #[test]
    fn extra_env_passes_through() {
        let (mut s, probe, layout) = (spec(), probe(), layout());
        s.extra_env.insert("LIBGL_MIPMAP".into(), "3".into());
        let launch = build(input(&s, &probe, &layout, 21)).unwrap();
        assert_eq!(launch.env["LIBGL_MIPMAP"], "3");
    }

    #[test]
    fn filter_drops_classpath_pair() {
        let args: Vec<String> = ["-classpath", "/a", "-Xss2M", "-d64", "-server"].iter().map(|s| s.to_string()).collect();
        assert_eq!(filter_jvm_args(&args).unwrap(), vec!["-Xss2M".to_string()]);
    }

    #[test]
    fn scan_reads_cacio_jars() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("libs_caciocavallo17")).unwrap();
        std::fs::write(dir.path().join("libs_caciocavallo17/z.jar"), b"").unwrap();
        std::fs::write(dir.path().join("libs_caciocavallo17/a.jar"), b"").unwrap();
        std::fs::write(dir.path().join("libs_caciocavallo17/readme.txt"), b"").unwrap();
        let layout = EngineLayout::scan(dir.path());
        assert!(layout.cacio8.is_empty());
        assert_eq!(layout.cacio17.len(), 2);
        assert!(layout.cacio17[0].ends_with("a.jar"));
    }
}
