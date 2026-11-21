//! Prüft das FPS-Paket über viele Versionen: frische Instanz, Presets wie beim
//! Anlegen in der App, Start bis zum Hauptmenü. Stürzt das Spiel wegen eines
//! Mixins ab, wird die schuldige Mod abgeschaltet und erneut gestartet.
//!
//! ```sh
//! TRS_MUTE=1 cargo run -p trs-core --example pack_matrix -- <datenordner> 1.20:fabric 1.21.1:neoforge …
//! ```
//!
//! `PM_KEEP=1` nimmt vorhandene Test-Instanzen, statt sie neu anzulegen.
//! Am Ende je Ziel eine Zeile `RESULT <version> <loader> <ok|crash|timeout> disabled=[…]`.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use trs_core::Launcher;
use trs_core::content::ContentKind;
use trs_core::instance::{Loader, LoaderKind, NewInstance};
use trs_core::launch::GameEvent;

const ATTEMPTS: usize = 4;
const READY: &[&str] = &["Sound engine started", "OpenAL initialized"];

enum Outcome {
    Ready,
    Exited { crashed: bool },
    Timeout,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let root = args.next().ok_or("Datenordner fehlt")?;
    let targets: Vec<String> = args.collect();
    let log: Arc<Mutex<Vec<String>>> = Arc::default();
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Outcome>();
    let (sink_log, sink_tx) = (log.clone(), tx.clone());
    let launcher = Arc::new(
        Launcher::init(
            root,
            Arc::new(move |event| match event {
                GameEvent::Logs { lines, .. } => {
                    let mut l = sink_log.lock().unwrap();
                    for line in lines {
                        if READY.iter().any(|r| line.message.contains(r)) {
                            let _ = sink_tx.send(Outcome::Ready);
                        }
                        l.push(line.message);
                    }
                }
                GameEvent::Exited { crashed, .. } => {
                    let _ = sink_tx.send(Outcome::Exited { crashed });
                }
                _ => {}
            }),
        )
        .await?,
    );
    if let Ok(dir) = std::env::var("TRS_CLIENT_MOD_DIR") {
        launcher.set_client_mod_dir(dir.into());
    }
    let builds = launcher.client_mod_builds().await;
    let mut results = Vec::new();

    for target in &targets {
        let (version, loader) = target.split_once(':').unwrap_or((target, "fabric"));
        let kind = match loader {
            "quilt" => LoaderKind::Quilt,
            "forge" => LoaderKind::Forge,
            "neoforge" => LoaderKind::NeoForge,
            _ => LoaderKind::Fabric,
        };
        let name = format!("pm-{version}-{loader}");
        println!("\n===== {name}");
        let existing = launcher.instances().list().await?.into_iter().find(|i| i.name == name);
        let instance = match existing {
            Some(i) if std::env::var_os("PM_KEEP").is_some() => i,
            other => {
                if let Some(old) = other {
                    launcher.delete_instance(&old.id).await?;
                }
                let inst = launcher
                    .create_instance(NewInstance { name: name.clone(), game_version: version.to_owned(), loader: Loader { kind, version: None } })
                    .await?;
                let mut presets = vec!["trs-fps-boost".to_owned()];
                if matches!(kind, LoaderKind::Fabric | LoaderKind::Quilt) {
                    presets.push("trs-nvidium".to_owned());
                }
                match trs_core::presets::apply(launcher.http(), launcher.paths(), &inst, &builds, None, &presets, &|_| {}).await {
                    Ok(report) => {
                        for item in &report.items {
                            println!("   {:<22} {:?} {}", item.title, item.status, item.version_number.clone().unwrap_or_default());
                        }
                    }
                    Err(e) => println!("   Presets fehlgeschlagen: {e}"),
                }
                inst
            }
        };
        mute(&launcher.paths().instance_game_dir(&instance.id))?;

        let mut disabled: Vec<String> = Vec::new();
        let mut verdict = "crash";
        for attempt in 1..=ATTEMPTS {
            log.lock().unwrap().clear();
            while rx.try_recv().is_ok() {}
            let before = enabled_mods(&launcher, &instance.id).await;
            let started = std::time::SystemTime::now();
            if let Err(e) = launcher.launch(&instance.id, None, &|_| {}).await {
                println!("   Start fehlgeschlagen: {e}");
                verdict = "launch_error";
                break;
            }
            let outcome = tokio::time::timeout(Duration::from_secs(300), rx.recv()).await.ok().flatten().unwrap_or(Outcome::Timeout);
            match outcome {
                Outcome::Ready => {
                    // Kurz weiterlaufen lassen: späte Mixins (Titelbildschirm) greifen erst jetzt.
                    let late = tokio::time::timeout(Duration::from_secs(8), rx.recv()).await;
                    if let Ok(Some(Outcome::Exited { crashed: true })) = late {
                    } else {
                        launcher.games().kill(&instance.id);
                        let _ = tokio::time::timeout(Duration::from_secs(30), rx.recv()).await;
                        verdict = "ok";
                        break;
                    }
                }
                Outcome::Timeout => {
                    launcher.games().kill(&instance.id);
                    let _ = tokio::time::timeout(Duration::from_secs(30), rx.recv()).await;
                    verdict = "timeout";
                    break;
                }
                Outcome::Exited { .. } => {}
            }
            // Der Launcher schaltet schuldige Paket-Mods selbst ab (Absturz-Analyse im Hintergrund).
            tokio::time::sleep(Duration::from_secs(4)).await;
            let after = enabled_mods(&launcher, &instance.id).await;
            let healed: Vec<String> = before.iter().filter(|f| !after.contains(f)).cloned().collect();
            if !healed.is_empty() {
                println!("   Versuch {attempt}: Absturz, vom Launcher abgeschaltet: {healed:?}");
                disabled.extend(healed);
                continue;
            }
            let text = log.lock().unwrap().join("\n");
            let report = newest_report(&launcher.paths().instance_game_dir(&instance.id), started);
            let installed = trs_core::crash::installed_mod_list(launcher.paths(), &instance.id).await;
            let culprits = trs_core::crash::mixin_culprits(&text, report.as_deref(), &installed);
            println!("   Versuch {attempt}: Absturz, schuldig: {culprits:?}");
            if culprits.is_empty() {
                let all = format!("{text}\n{}", report.unwrap_or_default());
                for line in all.lines().filter(|l| l.contains("Exception") || l.contains("Error") || l.contains("Caused by")).take(12) {
                    println!("      | {}", line.chars().take(300).collect::<String>());
                }
                break;
            }
            for file in culprits {
                trs_core::content::set_enabled(launcher.paths(), &instance.id, ContentKind::Mod, &file, false).await?;
                disabled.push(file);
            }
        }
        let line = format!("RESULT {version} {loader} {verdict} disabled={disabled:?}");
        println!("{line}");
        results.push(line);
    }
    println!("\n===== Zusammenfassung");
    for r in results {
        println!("{r}");
    }
    Ok(())
}

async fn enabled_mods(launcher: &Launcher, instance_id: &str) -> Vec<String> {
    trs_core::content::list(launcher.paths(), instance_id, ContentKind::Mod)
        .await
        .map(|items| items.into_iter().filter(|i| i.enabled).map(|i| i.file_name).collect())
        .unwrap_or_default()
}

fn mute(game_dir: &std::path::Path) -> std::io::Result<()> {
    std::fs::create_dir_all(game_dir)?;
    let options = game_dir.join("options.txt");
    let mut lines: Vec<String> =
        std::fs::read_to_string(&options).unwrap_or_default().lines().filter(|l| !l.starts_with("soundCategory_master:")).map(str::to_owned).collect();
    lines.push("soundCategory_master:0.0".into());
    std::fs::write(&options, lines.join("\n") + "\n")
}

fn newest_report(game_dir: &std::path::Path, since: std::time::SystemTime) -> Option<String> {
    let newest = std::fs::read_dir(game_dir.join("crash-reports"))
        .ok()?
        .flatten()
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .filter(|(m, _)| *m >= since)
        .max()?;
    std::fs::read_to_string(newest.1).ok()
}
