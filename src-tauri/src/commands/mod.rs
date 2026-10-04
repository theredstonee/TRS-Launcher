//! Tauri-Commands: dünne Schicht über `trs_core`. Validierung passiert im
//! Kern, damit sie unabhängig vom Frontend immer greift.

pub mod accounts;
pub mod app;
pub mod bisect;
pub mod clips;
pub mod content;
pub mod controls;
pub mod crash;
pub mod curseforge;
pub mod export;
pub mod extras;
pub mod files;
pub mod games;
pub mod hosting;
pub mod import;
pub mod icons;
pub mod instances;
pub mod logs;
pub mod meta;
pub mod moderation;
pub mod news;
pub mod packs;
pub mod presets;
pub mod relocate;
pub mod remote;
pub mod screenshots;
pub mod server_export;
pub mod servers;
pub mod settings;
pub mod skins;
pub mod social;
pub mod system;
pub mod tasks;
pub mod trs;
pub mod worlds;
