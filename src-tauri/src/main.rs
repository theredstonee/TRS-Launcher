// Kein Konsolenfenster in Release-Builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    trs_launcher_lib::run()
}
