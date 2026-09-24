//! Baut den CurseForge-API-Schlüssel ein, ohne dass er je im Quelltext oder
//! im Repository steht.
//!
//! Quelle (in dieser Reihenfolge): Umgebungsvariable `CURSEFORGE_API_KEY`
//! (GitHub-Secret im Release-Build), sonst die Datei
//! `%USERPROFILE%\.tauri\curseforge-api-key` (lokale Builds). Fehlt beides,
//! bleibt der Schlüssel leer und der Launcher blendet CurseForge aus.
//!
//! Der Schlüssel landet als Datei in `OUT_DIR` (eingelesen mit
//! `include_str!`) statt als `cargo:rustc-env` – so taucht er auch in
//! `cargo build -vv` nicht in der Ausgabe auf.

use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=CURSEFORGE_API_KEY");

    let key_file = std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(|home| PathBuf::from(home).join(".tauri").join("curseforge-api-key"));
    if let Some(file) = &key_file {
        // Nur beobachten, was es gibt – ein fehlender Pfad ließe Cargo sonst bei jedem Build neu bauen.
        if file.is_file() {
            println!("cargo:rerun-if-changed={}", file.display());
        } else if let Some(dir) = file.parent().filter(|d| d.is_dir()) {
            println!("cargo:rerun-if-changed={}", dir.display());
        }
    }

    let from_env = std::env::var("CURSEFORGE_API_KEY").ok().map(|k| k.trim().to_owned()).filter(|k| !k.is_empty());
    let key = from_env
        .or_else(|| key_file.and_then(|f| std::fs::read_to_string(f).ok()).map(|k| k.trim().to_owned()))
        .filter(|k| is_plausible(k))
        .unwrap_or_default();
    if key.is_empty() {
        println!("cargo:warning=Kein CurseForge-API-Schlüssel gefunden – CurseForge bleibt in diesem Build ausgeschaltet.");
    }

    let out = PathBuf::from(std::env::var_os("OUT_DIR").expect("OUT_DIR"));
    std::fs::write(out.join("curseforge_api_key.txt"), key).expect("Schlüsseldatei schreiben");
}

/// Nur sichtbare ASCII-Zeichen ohne Leerraum – alles andere ist kein Schlüssel.
fn is_plausible(key: &str) -> bool {
    (16..=256).contains(&key.len()) && key.bytes().all(|b| b.is_ascii_graphic())
}
