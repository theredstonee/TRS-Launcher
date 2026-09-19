//! Tauri-Commands: dünne Schicht über `trs_core`. Validierung passiert im
//! Kern, damit sie unabhängig vom Frontend immer greift.

pub mod app;
pub mod instances;
pub mod meta;
pub mod settings;
