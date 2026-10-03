//! Start auf iOS: Gerät prüfen → Heap → JIT → Argumente → Swift zeigt das Spiel und startet
//! die JVM. Ohne Tauri-Typen, damit es auf jedem Rechner getestet werden kann; die Brücke zu
//! Swift steckt hinter [`Bridge`] (echt: `tauri_bridge`).

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use super::args::{self, BuildInput, EngineLaunch, EngineLayout};
use super::jit::{self, JitHelp, JitVerdict};
use super::memory;
use super::probe::DeviceProbe;
use super::session::{EngineEvent, Output, SessionTracker};
use crate::models::{GameLaunchSpec, GameLogEvent, GameStateEvent, RuntimeInfo, SessionId};
use crate::{Error, Result};

/// Rückkanal der Swift-Engine.
pub type EngineEvents = Box<dyn Fn(EngineEvent) + Send + Sync>;

/// Aufrufe ins Swift-Plugin (blockierend, nie auf dem Haupt-Thread aufrufen).
pub trait Bridge {
    fn probe(&self) -> Result<DeviceProbe>;
    /// Zeigt das Spiel (bzw. erst die JIT-Hilfe) und startet die JVM. Kehrt zurück, sobald
    /// Swift den Start angenommen hat; der Rest kommt über `events`.
    fn launch(&self, launch: &EngineLaunch, events: EngineEvents) -> Result<()>;
}

/// Was nach außen gemeldet wird (die Tauri-Schicht macht `trs-game://…`-Events daraus).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Emitted {
    State(GameStateEvent),
    Log(GameLogEvent),
}

pub type Emit = Arc<dyn Fn(Emitted) + Send + Sync>;

/// Eine JVM pro Prozess: sie lässt sich nicht entladen oder neu starten.
pub struct LaunchGuard(AtomicBool);

impl LaunchGuard {
    pub const fn new() -> Self {
        Self(AtomicBool::new(false))
    }
    pub fn used(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}

impl Default for LaunchGuard {
    fn default() -> Self {
        Self::new()
    }
}

/// Der Wächter des laufenden Prozesses.
pub static PROCESS_GUARD: LaunchGuard = LaunchGuard::new();

/// Startet das Spiel. Die Laufzeit muss vorher bereitstehen (`prepare_runtime`).
pub fn launch<B: Bridge>(bridge: &B, guard: &'static LaunchGuard, spec: &GameLaunchSpec, runtime: &RuntimeInfo, emit: Emit) -> Result<SessionId> {
    if guard.used() {
        return Err(Error::RestartRequired);
    }
    let probe = bridge.probe()?;
    if !probe.engine_installed {
        return Err(Error::EngineMissing);
    }
    if probe.engine_used {
        return Err(Error::RestartRequired);
    }
    let heap = memory::plan_heap(&probe.memory, &probe.entitlements, spec.memory_mb, runtime.java_major)?;
    if heap.low {
        log::warn!("Wenig Speicher für das Spiel: -Xmx{}M ({:?})", heap.xmx_mb, heap.limited_by);
    }
    let (wait_for_jit, jit_help) = match jit::evaluate(&probe.jit) {
        JitVerdict::Ready => (false, JitHelp::Generic),
        JitVerdict::NeedsJit { help } => (true, help),
    };
    let layout = EngineLayout::scan(Path::new(&probe.bundle_path));
    let session = new_session_id();
    let java_home = runtime.home.to_string_lossy().into_owned();
    let request = args::build(BuildInput {
        spec,
        probe: &probe,
        layout: &layout,
        java_home: &java_home,
        java_major: runtime.java_major,
        heap,
        session: &session,
        wait_for_jit,
        jit_help,
    })?;

    if guard.0.swap(true, Ordering::SeqCst) {
        return Err(Error::RestartRequired);
    }
    let (tracker, first) = SessionTracker::new(&session, wait_for_jit);
    forward(&emit, first);
    let tracker = Mutex::new(tracker);
    let home = runtime.home.clone();
    let sink = Arc::clone(&emit);
    let events: EngineEvents = Box::new(move |event| {
        match &event {
            // JVM lief nie: neuer Versuch erlaubt.
            EngineEvent::Cancelled => guard.0.store(false, Ordering::SeqCst),
            EngineEvent::Failed { reason } => {
                guard.0.store(false, Ordering::SeqCst);
                // libjli ließ sich nicht laden: Laufzeit beim nächsten Start neu holen.
                if reason == "runtimeBroken" {
                    let _ = std::fs::remove_dir_all(&home);
                }
            }
            _ => {}
        }
        let out = tracker.lock().ok().and_then(|mut t| t.apply(event));
        if let Some(out) = out {
            forward(&sink, out);
        }
    });
    if let Err(e) = bridge.launch(&request, events) {
        // Swift hat nichts gestartet: ein neuer Versuch ist erlaubt.
        guard.0.store(false, Ordering::SeqCst);
        return Err(e);
    }
    Ok(SessionId(session))
}

fn forward(emit: &Emit, out: Output) {
    match out {
        Output::State(s) => emit(Emitted::State(s)),
        Output::Log(l) => emit(Emitted::Log(l)),
    }
}

fn new_session_id() -> String {
    let ms = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
    format!("ios-{ms}")
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::*;
    use crate::ios::probe::{JitProbe, MemoryProbe, ScreenProbe};
    use crate::models::GameState;

    #[derive(Default)]
    struct FakeBridge {
        probe: DeviceProbe,
        fail_launch: bool,
        sent: Mutex<Vec<EngineLaunch>>,
        events: Mutex<Option<EngineEvents>>,
    }

    impl Bridge for FakeBridge {
        fn probe(&self) -> Result<DeviceProbe> {
            Ok(self.probe.clone())
        }
        fn launch(&self, launch: &EngineLaunch, events: EngineEvents) -> Result<()> {
            if self.fail_launch {
                return Err(Error::Engine("test".into()));
            }
            self.sent.lock().unwrap().push(launch.clone());
            *self.events.lock().unwrap() = Some(events);
            Ok(())
        }
    }

    fn probe(jit: bool) -> DeviceProbe {
        DeviceProbe {
            engine_installed: true,
            jit: JitProbe { enabled: jit, ..Default::default() },
            memory: MemoryProbe { physical_mb: 6144, available_mb: 3500, max_contiguous_mb: 0 },
            os_version: "18.0".into(),
            screen: ScreenProbe { width_px: 2556, height_px: 1179, scale: 3.0, max_fps: 60 },
            bundle_path: "/var/containers/Bundle/TRS.app".into(),
            home_dir: "/var/mobile/Documents".into(),
            time_zone: "UTC".into(),
            ..Default::default()
        }
    }

    fn spec() -> GameLaunchSpec {
        GameLaunchSpec {
            game_dir: "/data/instances/demo".into(),
            assets_dir: "/data/assets".into(),
            classpath: vec!["/data/versions/1.21.1/1.21.1.jar".into()],
            main_class: "net.minecraft.client.main.Main".into(),
            java_major: 21,
            memory_mb: 2048,
            ..Default::default()
        }
    }

    fn runtime(home: PathBuf) -> RuntimeInfo {
        RuntimeInfo { java_major: 21, home, version: Some("21.0.8".into()), arch: "aarch64".into() }
    }

    fn guard() -> &'static LaunchGuard {
        Box::leak(Box::new(LaunchGuard::new()))
    }

    fn sink() -> (Emit, Arc<Mutex<Vec<Emitted>>>) {
        let seen = Arc::new(Mutex::new(Vec::new()));
        let inner = Arc::clone(&seen);
        (Arc::new(move |e| inner.lock().unwrap().push(e)), seen)
    }

    #[test]
    fn launch_flow_with_jit_wait() {
        let bridge = FakeBridge { probe: probe(false), ..Default::default() };
        let (emit, seen) = sink();
        let g = guard();
        let session = launch(&bridge, g, &spec(), &runtime("/data/runtimes/jre-21".into()), emit).unwrap();
        let sent = bridge.sent.lock().unwrap().clone();
        assert_eq!(sent.len(), 1);
        assert!(sent[0].wait_for_jit);
        assert_eq!(sent[0].jit_help, JitHelp::Generic);
        assert!(sent[0].argv.contains(&"-Xmx2048M".to_string()));
        assert_eq!(sent[0].java_home, "/data/runtimes/jre-21");
        let events = bridge.events.lock().unwrap().take().unwrap();
        events(EngineEvent::JitWaiting);
        events(EngineEvent::JitReady);
        events(EngineEvent::Running);
        events(EngineEvent::Log { line: "hallo".into() });
        events(EngineEvent::Exited { code: 0, log_tail: None });
        let seen = seen.lock().unwrap();
        let states: Vec<GameState> = seen
            .iter()
            .filter_map(|e| match e {
                Emitted::State(s) if s.session == session.0 => Some(s.state),
                _ => None,
            })
            .collect();
        assert_eq!(states, vec![GameState::Starting, GameState::Running, GameState::Exited]);
        assert!(seen.iter().any(|e| matches!(e, Emitted::Log(l) if l.lines == ["hallo"])));
        // Zweiter Start im selben Prozess geht nicht.
        assert!(matches!(launch(&bridge, g, &spec(), &runtime("/r".into()), sink().0), Err(Error::RestartRequired)));
    }

    #[test]
    fn refuses_without_engine_or_when_used() {
        let mut p = probe(true);
        p.engine_installed = false;
        let bridge = FakeBridge { probe: p.clone(), ..Default::default() };
        assert!(matches!(launch(&bridge, guard(), &spec(), &runtime("/r".into()), sink().0), Err(Error::EngineMissing)));
        p.engine_installed = true;
        p.engine_used = true;
        let bridge = FakeBridge { probe: p, ..Default::default() };
        assert!(matches!(launch(&bridge, guard(), &spec(), &runtime("/r".into()), sink().0), Err(Error::RestartRequired)));
    }

    #[test]
    fn not_enough_memory_stops_early() {
        let mut p = probe(true);
        p.memory = MemoryProbe { physical_mb: 2048, available_mb: 700, max_contiguous_mb: 0 };
        let bridge = FakeBridge { probe: p, ..Default::default() };
        assert!(matches!(launch(&bridge, guard(), &spec(), &runtime("/r".into()), sink().0), Err(Error::NotEnoughMemory)));
        assert!(bridge.sent.lock().unwrap().is_empty());
    }

    #[test]
    fn failed_swift_launch_allows_retry() {
        let bridge = FakeBridge { probe: probe(true), fail_launch: true, ..Default::default() };
        let g = guard();
        assert!(launch(&bridge, g, &spec(), &runtime("/r".into()), sink().0).is_err());
        assert!(!g.used());
    }

    #[test]
    fn failure_events_allow_retry_and_drop_broken_runtime() {
        let dir = tempfile::tempdir().unwrap();
        let home = dir.path().join("jre-21");
        std::fs::create_dir_all(home.join("lib")).unwrap();
        let bridge = FakeBridge { probe: probe(true), ..Default::default() };
        let g = guard();
        launch(&bridge, g, &spec(), &runtime(home.clone()), sink().0).unwrap();
        assert!(g.used());
        let events = bridge.events.lock().unwrap().take().unwrap();
        events(EngineEvent::Failed { reason: "runtimeBroken".into() });
        assert!(!g.used());
        assert!(!home.exists());
    }
}
