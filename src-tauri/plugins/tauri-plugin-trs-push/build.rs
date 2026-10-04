// Keine Befehle für das Webview: Nur der Rust-Kern ruft das Plugin auf (Verteiler, Adresse, Hinweise).
const COMMANDS: &[&str] = &[];

fn main() {
    tauri_plugin::Builder::new(COMMANDS).android_path("android").ios_path("ios").build();
}
