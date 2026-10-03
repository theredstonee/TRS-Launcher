// Befehle, die das Frontend direkt aufrufen darf (der Kern nutzt die Rust-API).
const COMMANDS: &[&str] = &["prepare_runtime", "launch", "runtimes"];

fn main() {
    tauri_plugin::Builder::new(COMMANDS).android_path("android").ios_path("ios").build();
}
