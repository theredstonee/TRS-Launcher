//! Rauchtest für die CurseForge-Anbindung gegen die echte API (braucht einen
//! Build mit API-Schlüssel, siehe `build.rs`). Nie gegen echte Nutzerdaten
//! laufen lassen – immer einen eigenen Datenordner angeben:
//!
//! ```sh
//! cargo run -p trs-core --example curseforge -- <datenordner> search <text> [mc-version] [loader]
//! cargo run -p trs-core --example curseforge -- <datenordner> pack <projekt-id> [datei-id]
//! cargo run -p trs-core --example curseforge -- <datenordner> mod <instanz-id> <projekt-id>
//! cargo run -p trs-core --example curseforge -- <datenordner> updates <instanz-id>
//! cargo run -p trs-core --example curseforge -- <datenordner> adopt <instanz-id>
//! ```

use std::sync::Arc;

use trs_core::Launcher;
use trs_core::content::ContentKind;
use trs_core::launch::GameEvent;
use trs_core::modrinth::SearchParams;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let root = args.next().ok_or("Datenordner fehlt")?;
    let command = args.next().ok_or("Befehl fehlt")?;
    let launcher = Launcher::init(root, Arc::new(|_: GameEvent| {})).await?;
    println!("CurseForge verfügbar: {}", launcher.curseforge_status().available);
    let cf = launcher.curseforge()?;

    match command.as_str() {
        "search" => {
            let query = args.next().unwrap_or_default();
            let versions: Vec<String> = args.next().into_iter().collect();
            let loaders: Vec<String> = args.next().into_iter().collect();
            let params: SearchParams = serde_json::from_value(serde_json::json!({
                "query": query, "kind": "mod", "gameVersions": versions, "loaders": loaders, "limit": 8
            }))?;
            let result = cf.search(&params).await?;
            println!("{} Treffer (zeige {})", result.total_hits, result.hits.len());
            for hit in &result.hits {
                println!("  {:>8}  {:<40} von {:<16} {:>12} Downloads  {:?}", hit.project_id, hit.title, hit.author, hit.downloads, hit.categories);
            }
            println!("Kategorien: {}", cf.categories().await?.len());
        }
        "pack" => {
            let project = args.next().ok_or("Projekt-ID fehlt")?;
            let file = args.next();
            let outcome = launcher
                .install_curseforge_modpack(&project, file.as_deref(), &|p| {
                    println!("  {:?} {:.0} %", p.phase, p.percent);
                })
                .await?;
            println!("Instanz: {} ({} {:?})", outcome.instance.id, outcome.instance.game_version, outcome.instance.loader);
            for kind in ContentKind::ALL {
                let items = trs_core::content::list(launcher.paths(), &outcome.instance.id, kind).await?;
                let from_cf = items.iter().filter(|i| i.source.is_some()).count();
                println!("  {kind:?}: {} Dateien, {from_cf} mit Herkunft", items.len());
            }
            println!("Von Hand zu laden: {}", outcome.blocked.len());
            for b in &outcome.blocked {
                println!("  {} – {} ({})", b.title, b.file_name, b.url);
            }
        }
        "mod" => {
            let instance = args.next().ok_or("Instanz fehlt")?;
            let project = args.next().ok_or("Projekt-ID fehlt")?;
            let instance = launcher.instances().get(&instance).await?;
            let outcome = cf.install(launcher.paths(), &instance, &project, ContentKind::Mod, None).await?;
            println!("Installiert: {:?}", outcome.files);
            println!("Von Hand: {:?}", outcome.blocked.iter().map(|b| &b.file_name).collect::<Vec<_>>());
        }
        "updates" => {
            let instance = args.next().ok_or("Instanz fehlt")?;
            let instance = launcher.instances().get(&instance).await?;
            for u in cf.check_updates(launcher.paths(), &instance).await? {
                println!("  {} → {} ({})", u.file_name, u.version_number, u.version_id);
            }
        }
        "adopt" => {
            let instance = args.next().ok_or("Instanz fehlt")?;
            let r = trs_core::curseforge::adopt_downloads(launcher.paths(), &instance).await?;
            println!("Übernommen: {:?}, offen: {}, Ordner: {:?}", r.adopted, r.pending.len(), r.watch_folder);
        }
        other => return Err(format!("Unbekannter Befehl {other}").into()),
    }
    Ok(())
}
