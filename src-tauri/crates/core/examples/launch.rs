//! Rauchtest für die Start-Pipeline ohne UI:
//!
//! ```sh
//! cargo run -p trs-core --example launch -- <datenordner> <version> [vanilla|fabric|quilt] [sekunden]
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
        _ => LoaderKind::Vanilla,
    };
    let seconds: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(25);

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

    let name = format!("smoke-{version}-{kind:?}").to_lowercase();
    let instance = match launcher.instances().list().await?.into_iter().find(|i| i.name == name) {
        Some(i) => i,
        None => {
            launcher
                .create_instance(NewInstance {
                    name,
                    game_version: version,
                    loader: Loader { kind, version: None },
                })
                .await?
        }
    };

    let last = AtomicU64::new(u64::MAX);
    launcher
        .launch(&instance.id, &move |p| {
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
