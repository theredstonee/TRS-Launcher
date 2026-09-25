//! Trockenlauf des Launcher-Imports gegen die echten Ordner dieses PCs: zeigt,
//! was erkannt würde – importiert, kopiert und verändert nichts (fremde
//! Datenbanken werden als Kopie im Temp-Ordner gelesen). Immer einen eigenen,
//! leeren Datenordner angeben:
//!
//! ```sh
//! cargo run -p trs-core --example import_scan -- <datenordner>
//! ```

use std::sync::Arc;

use trs_core::Launcher;
use trs_core::launch::GameEvent;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = std::env::args().nth(1).ok_or("Datenordner fehlt")?;
    let launcher = Launcher::init(root, Arc::new(|_: GameEvent| {})).await?;
    let overview = launcher.import_overview().await?;
    println!("Erkannte Launcher:");
    for l in &overview.launchers {
        println!("  {:?}: {} Installation(en){}", l.source, l.instances, if l.supported { "" } else { " – nicht unterstützt" });
    }
    println!("Installationen:");
    for c in &overview.candidates {
        println!(
            "  [{:?}] {} – {} {:?} {} | Mods {} · Welten {} · RP {} · Shader {} · Optionen {} · Server {} · Herkunft {} · fehlt {} | {:?}{}",
            c.source,
            c.name,
            c.game_version,
            c.loader.kind,
            c.loader.version.as_deref().unwrap_or("-"),
            c.mod_count,
            c.world_count,
            c.resource_pack_count,
            c.shader_pack_count,
            c.has_options,
            c.has_servers,
            c.tracked_count,
            c.missing_count,
            c.notes,
            if c.version_guessed { " (Version geschätzt)" } else { "" },
        );
    }
    let skins = launcher.scan_launcher_skins().await?;
    println!("Skins: {} gefunden in {:?}; ohne Skin-Liste: {:?}", skins.candidates.len(), skins.found, skins.without_skins);
    Ok(())
}
