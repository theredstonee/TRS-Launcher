//! Rauchtest für die Start-Pipeline ohne UI:
//!
//! ```sh
//! cargo run -p trs-core --example launch -- <datenordner> <version> \
//!     [vanilla|fabric|quilt|forge|neoforge] [sekunden] [loader-version]
//! ```
//!
//! Lädt alles herunter, startet das Spiel, gibt Logs aus und beendet es nach
//! `sekunden` (Standard 25) wieder.

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use trs_core::Launcher;
use trs_core::instance::{Loader, LoaderKind, NewInstance};
use trs_core::launch::GameEvent;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let root = args.next().ok_or("Datenordner fehlt")?;
    let version = args.next().ok_or("Version fehlt")?;
    let kind = match args.next().as_deref() {
        Some("fabric") => LoaderKind::Fabric,
        Some("quilt") => LoaderKind::Quilt,
        Some("forge") => LoaderKind::Forge,
        Some("neoforge") => LoaderKind::NeoForge,
        _ => LoaderKind::Vanilla,
    };
    let seconds: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(25);
    // Optional eine feste Loader-Version, z. B. `14.23.5.2847` (altes Installer-Format).
    let loader_version = args.next();
    if std::env::var_os("TRS_LOG").is_some() {
        install_stderr_logger();
    }

    let (exit_tx, mut exit_rx) = tokio::sync::mpsc::unbounded_channel();
    let launcher = Arc::new(
        Launcher::init(
            root,
            Arc::new(move |event| match event {
                GameEvent::Started { pid, .. } => println!("== gestartet, PID {pid}"),
                GameEvent::Logs { lines, .. } => {
                    for l in lines {
                        println!("[{:?}] {}", l.level, l.message.lines().next().unwrap_or_default());
                    }
                }
                GameEvent::Exited { exit_code, crashed, play_seconds, .. } => {
                    println!("== beendet: code={exit_code:?} crashed={crashed} spielzeit={play_seconds}s");
                    let _ = exit_tx.send(());
                }
            }),
        )
        .await?,
    );

    let pinned = loader_version.as_deref().map(|v| format!("-{v}")).unwrap_or_default();
    // Optional: mitgelieferte TRS-Client-Builds wie in der App verwenden.
    if let Ok(dir) = std::env::var("TRS_CLIENT_MOD_DIR") {
        launcher.set_client_mod_dir(dir.into());
    }

    let name = format!("smoke-{version}-{kind:?}{pinned}").to_lowercase();
    let instance = match launcher.instances().list().await?.into_iter().find(|i| i.name == name) {
        Some(i) => i,
        None => {
            launcher
                .create_instance(NewInstance {
                    name,
                    game_version: version,
                    loader: Loader { kind, version: loader_version },
                })
                .await?
        }
    };

    let last = AtomicU64::new(u64::MAX);
    launcher
        .launch(&instance.id, None, &move |p| {
            // Nur bei Änderung in 10-%-Schritten ausgeben.
            let bucket = (p.stage as u64) * 1000 + (p.percent as u64 / 10);
            if last.swap(bucket, Ordering::Relaxed) != bucket {
                println!("-- {:?}: {:.0} % ({}/{} Dateien)", p.stage, p.percent, p.done_files, p.total_files);
            }
        })
        .await?;

    tokio::select! {
        _ = exit_rx.recv() => {}
        () = tokio::time::sleep(Duration::from_secs(seconds)) => {
            println!("== {seconds}s um, beende Spiel");
            launcher.games().kill(&instance.id);
            let _ = exit_rx.recv().await;
        }
    }
    // Dem on_exit-Task Zeit geben, die Spielzeit zu speichern.
    tokio::time::sleep(Duration::from_millis(300)).await;
    let inst = launcher.instances().get(&instance.id).await?;
    println!("== gespeicherte Spielzeit: {}s", inst.total_play_seconds);
    Ok(())
}

/// Mit `TRS_LOG=1` landen die `tracing`-Meldungen des Kerns (z. B. die Ausgabe
/// der Forge-Processors) auf stderr – ohne zusätzliche Abhängigkeit.
fn install_stderr_logger() {
    use tracing::field::{Field, Visit};
    use tracing::span::{Attributes, Id, Record};
    use tracing::{Event, Metadata, Subscriber};

    struct Message(String);
    impl Visit for Message {
        fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
            if field.name() == "message" {
                self.0 = format!("{value:?}");
            }
        }
    }

    struct Stderr;
    impl Subscriber for Stderr {
        fn enabled(&self, metadata: &Metadata<'_>) -> bool {
            metadata.target().starts_with("trs_core") && *metadata.level() <= tracing::Level::DEBUG
        }
        fn new_span(&self, _: &Attributes<'_>) -> Id {
            Id::from_u64(1)
        }
        fn record(&self, _: &Id, _: &Record<'_>) {}
        fn record_follows_from(&self, _: &Id, _: &Id) {}
        fn event(&self, event: &Event<'_>) {
            let mut message = Message(String::new());
            event.record(&mut message);
            eprintln!("~~ {} {}", event.metadata().level(), message.0);
        }
        fn enter(&self, _: &Id) {}
        fn exit(&self, _: &Id) {}
    }

    let _ = tracing::subscriber::set_global_default(Stderr);
}
