// Kein Konsolenfenster in Release-Builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // Mit Admin-Rechten gestarteter Firewall-Hilfsmodus: nur Regeln eintragen, keine App.
    if let Some(code) = trs_core::firewall::helper_main_if_requested() {
        std::process::exit(code);
    }
    trs_launcher_lib::run()
}
