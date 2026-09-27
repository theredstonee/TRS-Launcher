//! Absturz-Helfer ausprobieren: analysiert eine Log-Datei bzw. einen
//! Crash-Report (nur lesend, ohne Instanz) und gibt das Ergebnis als JSON aus.
//!
//! `cargo run -p trs-core --example crash_scan -- <datei> [--memory <MB>] [--system <MB>]`
//! Crash-Reports (`---- Minecraft Crash Report ----`) werden als solche erkannt.

use trs_core::crash::{CrashInput, analyze};

fn main() {
    let mut args = std::env::args().skip(1);
    let Some(path) = args.next() else {
        eprintln!("Aufruf: crash_scan <datei> [--memory <MB>] [--system <MB>]");
        std::process::exit(2);
    };
    let (mut memory, mut system) = (None, None);
    while let Some(flag) = args.next() {
        let value = args.next().and_then(|v| v.parse::<u32>().ok());
        match flag.as_str() {
            "--memory" => memory = value,
            "--system" => system = value,
            _ => {}
        }
    }
    let bytes = std::fs::read(&path).unwrap_or_else(|e| {
        eprintln!("{path}: {e}");
        std::process::exit(1);
    });
    let text = String::from_utf8_lossy(&bytes);
    let is_report = text.trim_start().starts_with("---- Minecraft Crash Report");
    let input = CrashInput {
        log: if is_report { "" } else { &text },
        report: is_report.then_some(&*text),
        memory_mb: memory,
        system_memory_mb: system,
        ..Default::default()
    };
    println!("{}", serde_json::to_string_pretty(&analyze(&input)).unwrap_or_default());
}
