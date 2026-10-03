//! Spielstart auf Android/iOS: Der Kern baut die Startbeschreibung, das Plugin
//! `trs-game` startet die eingebettete JVM. Forge-/NeoForge-Processors laufen
//! über dieselbe Engine (kopflose JVM in eigenem Prozess).

use std::sync::Arc;

use tauri::{AppHandle, Runtime};
use tauri_plugin_trs_game::{JavaRunSpec, TrsGameExt};
use trs_core::Launcher;
use trs_core::forge::{ProcessorCall, ProcessorOutput, ProcessorRunner};
use trs_core::mobile_launch::engine_java;
use trs_core::prepare::ProgressFn;

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

/// Vorbereiten + Runtime + Start. Rückgabe 0 (es gibt keine PID); Status und Logs
/// kommen als `trs-game://state` / `trs-game://log`.
pub async fn launch<R: Runtime>(
    app: &AppHandle<R>,
    launcher: &Arc<Launcher>,
    id: &str,
    join: Option<trs_core::Join<'_>>,
    on_progress: &ProgressFn,
) -> trs_core::Result<u32> {
    let runner = EngineRunner { app: app.clone() };
    let spec = launcher.prepare_mobile_launch(id, join, Some(&runner), on_progress).await?;
    let engine = app.trs_game();
    engine.prepare_runtime(spec.java_major).await.map_err(engine_error)?;
    // Gleiche JSON-Form auf beiden Seiten (Vertrag GameLaunchSpec).
    let spec: tauri_plugin_trs_game::GameLaunchSpec = serde_json::to_value(&spec)
        .and_then(serde_json::from_value)
        .map_err(|e| {
            log::error!("Startbeschreibung passt nicht zum Plugin: {e}");
            trs_core::Error::launch(trs_core::msg!("game.engineFailed", "Die Spiel-Engine konnte nicht gestartet werden."))
        })?;
    let session = engine.launch(spec).map_err(engine_error)?;
    log::info!("Spiel gestartet (Sitzung {})", session.0);
    Ok(0)
}
