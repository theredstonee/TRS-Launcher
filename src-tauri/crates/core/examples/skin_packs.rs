//! Offizielle Skin-Pakete laden (wie die Skins-Seite) und auflisten.
//!
//! `cargo run -p trs-core --example skin_packs -- <cache-ordner> [--json]`
//! Der Cache landet unter `<cache-ordner>/skin-packs/`. Mit `--json` wird die
//! Liste samt Data-URLs ausgegeben (z. B. für eine UI-Attrappe).

#[tokio::main]
async fn main() {
    let mut args = std::env::args().skip(1);
    let Some(dir) = args.next() else {
        eprintln!("Aufruf: skin_packs <cache-ordner> [--json]");
        std::process::exit(2);
    };
    let json = args.next().as_deref() == Some("--json");
    let packs = trs_core::skin_packs::load_all(std::path::Path::new(&dir)).await;
    if json {
        println!("{}", serde_json::to_string(&packs).unwrap_or_default());
        return;
    }
    for pack in &packs {
        println!("{} ({}) – {} Skins", pack.name, pack.released, pack.skins.len());
        for skin in &pack.skins {
            println!("  {:<48} {:<28} {:?}", skin.id, skin.name, skin.variant);
        }
    }
    println!("{} Pakete", packs.len());
}
