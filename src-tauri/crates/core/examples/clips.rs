//! Probelauf der Aufnahme ohne Spiel und ohne Launcher-Oberfläche.
//!
//! `cargo run -p trs-core --example clips -- <datenordner> <pid> [sekunden-bis-clip] [aufnahme-sekunden] [ton 0/1]`
//!
//! Nimmt das Hauptfenster des Prozesses `pid` auf, speichert nach der Wartezeit
//! einen Sofort-Clip und optional eine normale Aufnahme. FFmpeg wird bei Bedarf
//! in `<datenordner>/tools` geladen. Ausgabe: `<datenordner>/clips/demo/*.mp4`.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use trs_core::clips::settings::{ClipResolution, ClipSettings};
use trs_core::clips::{ClipEvent, ClipService, RunningGame};
use trs_core::paths::Paths;

#[tokio::main]
async fn main() {
    let mut args = std::env::args().skip(1);
    let data = PathBuf::from(args.next().expect("Datenordner"));
    let pid: u32 = args.next().expect("PID").parse().expect("PID");
    let wait: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(8);
    let record: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    let audio = args.next().is_none_or(|s| s == "1");

    let paths = Paths::new(&data);
    let link = Arc::new(trs_core::link::TrsLink::new(None));
    let service = ClipService::new(&paths, link.clone());
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<ClipEvent>();
    service.set_sink(Arc::new(move |e| {
        println!("Ereignis: {}", serde_json::to_string(&e).unwrap_or_default());
        let _ = tx.send(e);
    }));
    if !service.ffmpeg().status().await.installed {
        println!("Lade FFmpeg …");
        service.ffmpeg().install().await.expect("FFmpeg");
    }
    let settings = ClipSettings {
        enabled: true,
        buffer_seconds: 15,
        resolution: ClipResolution::Native,
        system_audio: audio,
        microphone: false,
        ..Default::default()
    };
    let game_dir = data.join("game");
    link.open_session("demo", false).await.expect("TRS-Link");
    service.prepare("demo", &game_dir, &settings).await;
    service.game_started(
        RunningGame { instance_id: "demo".into(), instance_name: "Demo".into(), pid, game_dir },
        &settings,
    );

    // Warten, bis der Puffer läuft.
    let started = std::time::Instant::now();
    loop {
        if service.states().iter().any(|s| s.buffer) {
            break;
        }
        assert!(started.elapsed() < Duration::from_secs(60), "Aufnahme startet nicht");
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    println!("Puffer läuft nach {:.1} s", started.elapsed().as_secs_f32());
    tokio::time::sleep(Duration::from_secs(wait)).await;
    service.command("demo", false).expect("Clip");
    wait_saved(&mut rx).await;
    if record > 0 {
        service.command("demo", true).expect("Aufnahme an");
        tokio::time::sleep(Duration::from_secs(record)).await;
        service.command("demo", true).expect("Aufnahme aus");
        wait_saved(&mut rx).await;
    }
    service.game_exited("demo");
    loop {
        match tokio::time::timeout(Duration::from_secs(15), rx.recv()).await {
            Ok(Some(ClipEvent::Ended { .. })) | Err(_) | Ok(None) => break,
            _ => {}
        }
    }
    for clip in trs_core::clips::library::list(&data.join("clips")) {
        println!("{} – {} Bytes, {:?} ms", clip.file_name, clip.size, clip.duration_ms);
    }
}

async fn wait_saved(rx: &mut tokio::sync::mpsc::UnboundedReceiver<ClipEvent>) {
    loop {
        match tokio::time::timeout(Duration::from_secs(60), rx.recv()).await {
            Ok(Some(ClipEvent::Saved { .. })) => return,
            Ok(Some(ClipEvent::Failed { code, .. })) => panic!("fehlgeschlagen: {code}"),
            Ok(Some(_)) => {}
            _ => panic!("kein Ergebnis"),
        }
    }
}
