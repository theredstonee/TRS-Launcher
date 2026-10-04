//! Spielstart auf Android/iOS: Der Kern baut die Startbeschreibung, das Plugin
//! `trs-game` startet die eingebettete JVM. Forge-/NeoForge-Processors laufen
//! über dieselbe Engine (kopflose JVM in eigenem Prozess). Zustand und Logs der
//! Engine (`trs-game://state`/`trs-game://log`) landen im Spiele-Manager des
//! Kerns – damit gibt es `game-event`, Laufzeit, Spielzeit und Logs wie am PC.

use std::sync::{Arc, Mutex};

use tauri::{AppHandle, Listener, Runtime};
use tauri_plugin_trs_game::{GameLogEvent, GameState, GameStateEvent, JavaRunSpec, TrsGameExt};
use trs_core::Launcher;
use trs_core::forge::{ProcessorCall, ProcessorOutput, ProcessorRunner};
use trs_core::mobile_launch::{MobileLaunch, engine_java};
use trs_core::prepare::ProgressFn;

/// Start und Übernahme in den Spiele-Manager am Stück: Ereignisse der Engine warten,
/// bis die Sitzung eingetragen ist (sonst gingen frühe Zeilen verloren).
static ATTACH: Mutex<()> = Mutex::new(());

fn attach_lock() -> std::sync::MutexGuard<'static, ()> {
    ATTACH.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Engine-Ereignisse kommen auf fremden Threads (JNI/Swift) an: Der Kern (Spielzeit, Verlauf,
/// Absturz-Helfer) braucht dort die Tokio-Laufzeit der App.
fn in_runtime<T>(work: impl FnOnce() -> T) -> T {
    let handle = tauri::async_runtime::handle();
    let _enter = handle.inner().enter();
    work()
}

/// Plugin-Fehler → Launcher-Fehler mit Übersetzungs-Code (`errors.game.*`).
pub fn engine_error(err: tauri_plugin_trs_game::Error) -> trs_core::Error {
    use tauri_plugin_trs_game::Error as E;
    log::warn!("Spiel-Engine: {err}");
    let msg = match err {
        E::UnsupportedJava(_) | E::UnsupportedArch(_) | E::UnsupportedPlatform => {
            trs_core::msg!("game.unsupportedPlatform", "Dieses Gerät wird für das Spiel nicht unterstützt.")
        }
        E::Download(_) => trs_core::msg!("game.runtimeDownloadFailed", "Java für das Spiel konnte nicht geladen werden. Bitte Internetverbindung prüfen."),
        E::Checksum | E::Archive(_) => trs_core::msg!("game.runtimeBroken", "Die geladene Java-Laufzeit ist beschädigt. Bitte erneut versuchen."),
        E::RuntimeMissing(_) => trs_core::msg!("game.runtimeMissing", "Java für das Spiel fehlt noch. Bitte erneut starten."),
        E::EngineMissing => trs_core::msg!("game.engineMissing", "Diese Version der App enthält die Spiel-Engine nicht."),
        E::RestartRequired => trs_core::msg!("game.restartRequired", "Bitte die App neu starten, um wieder zu spielen."),
        E::NotEnoughMemory => trs_core::msg!("game.notEnoughMemory", "Zu wenig Arbeitsspeicher für das Spiel."),
        E::SdlUnsupported => trs_core::msg!("game.sdlUnsupported", "Minecraft 26.3 und neuer läuft auf iPhone und iPad noch nicht – wähle 26.2 oder älter."),
        _ => trs_core::msg!("game.engineFailed", "Die Spiel-Engine konnte nicht gestartet werden."),
    };
    trs_core::Error::launch(msg)
}

/// Processors über die Engine (Runtime vorher bereitstellen).
struct EngineRunner<R: Runtime> {
    app: AppHandle<R>,
}

impl<R: Runtime> ProcessorRunner for EngineRunner<R> {
    fn run(&self, call: ProcessorCall) -> futures_like::BoxFuture<'_, trs_core::Result<ProcessorOutput>> {
        Box::pin(async move {
            let engine = self.app.trs_game();
            let major = engine_java(call.java_major);
            engine.prepare_runtime(major).await.map_err(engine_error)?;
            let spec = JavaRunSpec {
                java_major: major,
                classpath: call.classpath.iter().map(|p| p.display().to_string()).collect(),
                main_class: call.main_class,
                args: call.args,
                jvm_args: Vec::new(),
                cwd: call.cwd,
                memory_mb: 1024,
            };
            let result = engine.run_java(spec).await.map_err(engine_error)?;
            Ok(ProcessorOutput { success: result.exit_code == 0, lines: result.log_tail })
        })
    }
}

/// `BoxFuture` ohne eigene `futures`-Abhängigkeit im App-Crate.
mod futures_like {
    pub type BoxFuture<'a, T> = std::pin::Pin<Box<dyn std::future::Future<Output = T> + Send + 'a>>;
}

/// Vorbereiten + Runtime + Start. Rückgabe 0 (es gibt keine PID im Launcher); das
/// Spiel erscheint über `game-event` als laufend.
pub async fn launch<R: Runtime>(
    app: &AppHandle<R>,
    launcher: &Arc<Launcher>,
    id: &str,
    join: Option<trs_core::Join<'_>>,
    options: trs_core::LaunchOptions,
    on_progress: &ProgressFn,
) -> trs_core::Result<u32> {
    let runner = EngineRunner { app: app.clone() };
    // Am Handy läuft immer nur ein Spiel – „noch einmal starten“ gibt es nicht.
    let MobileLaunch { spec, secrets } =
        launcher.prepare_mobile_launch(id, join, options.account_id.as_deref(), options.skip_mod_check, Some(&runner), on_progress).await?;
    let engine = app.trs_game();
    engine.prepare_runtime(spec.java_major).await.map_err(engine_error)?;
    // Gleiche JSON-Form auf beiden Seiten (Vertrag GameLaunchSpec).
    let spec: tauri_plugin_trs_game::GameLaunchSpec = serde_json::to_value(&spec)
        .and_then(serde_json::from_value)
        .map_err(|e| {
            log::error!("Startbeschreibung passt nicht zum Plugin: {e}");
            trs_core::Error::launch(trs_core::msg!("game.engineFailed", "Die Spiel-Engine konnte nicht gestartet werden."))
        })?;
    let _attach = attach_lock();
    let session = engine.launch(spec).map_err(engine_error)?;
    log::info!("Spiel gestartet (Sitzung {})", session.0);
    let stop_app = app.clone();
    let stop_session = session.clone();
    let stop: trs_core::process::StopFn = Arc::new(move || match stop_app.trs_game().stop(&stop_session) {
        Ok(()) => true,
        Err(e) => {
            log::warn!("Spiel konnte nicht beendet werden: {e}");
            false
        }
    });
    if let Err(e) = launcher.attach_engine_game(id, &session.0, secrets, stop.clone()) {
        stop();
        return Err(e);
    }
    Ok(0)
}

/// `trs-game://state` und `trs-game://log` an den Spiele-Manager weiterreichen (einmal beim Start).
pub fn forward_events<R: Runtime>(app: &AppHandle<R>, launcher: &Arc<Launcher>) {
    let logs = Arc::clone(launcher);
    app.listen(tauri_plugin_trs_game::EVENT_LOG, move |event| match serde_json::from_str::<GameLogEvent>(event.payload()) {
        Ok(log) => in_runtime(|| {
            let _attach = attach_lock();
            logs.games().engine_logs(&log.session, &log.lines);
        }),
        Err(e) => log::debug!("trs-game://log unlesbar: {e}"),
    });
    let states = Arc::clone(launcher);
    app.listen(tauri_plugin_trs_game::EVENT_STATE, move |event| match serde_json::from_str::<GameStateEvent>(event.payload()) {
        Ok(state) => in_runtime(|| {
            let _attach = attach_lock();
            apply_state(&states, state);
        }),
        Err(e) => log::debug!("trs-game://state unlesbar: {e}"),
    });
}

fn apply_state(launcher: &Arc<Launcher>, event: GameStateEvent) {
    match event.state {
        // Läuft schon seit `attach_engine_game` als gestartet.
        GameState::Starting | GameState::Running => {}
        GameState::Exited | GameState::Crashed => {
            let crashed = event.state == GameState::Crashed;
            launcher.games().engine_exited(&event.session, event.exit_code, crashed, &event.log_tail);
        }
    }
}

/// iOS: Das Spielende beendet die App. Die Engine schreibt das Ende nach
/// `Documents/trs-last-session.json` – beim nächsten Start Spielzeit und Verlauf nachtragen.
#[cfg(target_os = "ios")]
pub fn finish_last_session<R: Runtime>(app: &AppHandle<R>, launcher: &Arc<Launcher>) {
    use tauri::Manager;
    use tauri_plugin_trs_game::ios::session::{LAST_SESSION_FILE, take_last_session};
    let Ok(docs) = app.path().document_dir() else { return };
    // Zeitpunkt des Endes: Die Engine schreibt die Datei beim Beenden.
    let ended_at = std::fs::metadata(docs.join(LAST_SESSION_FILE))
        .and_then(|m| m.modified())
        .map(chrono::DateTime::<chrono::Utc>::from)
        .unwrap_or_else(|_| chrono::Utc::now());
    if let Some(event) = take_last_session(&docs) {
        let crashed = event.state == GameState::Crashed;
        in_runtime(|| launcher.engine_session_ended_offline(&event.session, ended_at, event.exit_code, crashed, &event.log_tail));
    } else {
        // Keine Endmeldung: übrig gebliebene Sitzungen vergessen.
        let _ = launcher.games().take_engine_records();
    }
}

/// Android: Wird der Launcher-Prozess beendet, während das Spiel läuft (z. B. vom System bei
/// wenig Speicher), erreicht ihn das Spielende nie. Beim nächsten Start nachfragen: Läuft das Spiel
/// noch, später erneut prüfen; sonst Ende mit Androids Grund und dem Log-Ende nachtragen.
#[cfg(target_os = "android")]
pub fn finish_android_sessions<R: Runtime>(app: &AppHandle<R>, launcher: &Arc<Launcher>) {
    let records = launcher.games().take_engine_records();
    if records.is_empty() {
        return;
    }
    let app = app.clone();
    let launcher = Arc::clone(launcher);
    // Eigener Thread: wartet ggf. lange (Spiel läuft weiter), blockiert keine Laufzeit.
    std::thread::spawn(move || {
        let mut pending = records;
        // Fehler (Plugin noch nicht bereit) begrenzt wiederholen.
        let mut errors = 0;
        std::thread::sleep(std::time::Duration::from_secs(3));
        loop {
            let mut running = Vec::new();
            for record in pending {
                let end = tauri::async_runtime::block_on(app.trs_game().session_end(&record.session, record.started_at.timestamp_millis()));
                match end {
                    Ok(end) if end.running => running.push(record),
                    Ok(end) => {
                        let ended_at = end.ended_at_ms.and_then(chrono::DateTime::from_timestamp_millis).unwrap_or_else(chrono::Utc::now);
                        let exit_code = Some(if end.crashed { -1 } else { 0 });
                        let session = record.session.clone();
                        launcher.games().keep_engine_record(record);
                        in_runtime(|| launcher.engine_session_ended_offline(&session, ended_at, exit_code, end.crashed, &end.log_tail));
                    }
                    Err(e) => {
                        log::warn!("Ende der Spielsitzung {} unbekannt: {e}", record.session);
                        errors += 1;
                        if errors < 10 {
                            running.push(record);
                        }
                    }
                }
            }
            if running.is_empty() {
                break;
            }
            for record in &running {
                launcher.games().keep_engine_record(record.clone());
            }
            std::thread::sleep(std::time::Duration::from_secs(5));
            pending = launcher.games().take_engine_records();
        }
    });
}
