//! iOS: Minecraft Java im App-Prozess über die Amethyst-iOS-Engine (Sideload + JIT).
//!
//! Ablauf ([`engine::launch`]): Gerät prüfen (Swift `probe`) → Heap planen → JIT-Urteil →
//! JVM-Argumente bauen → Swift `launch` zeigt das Spiel (bzw. erst die JIT-Hilfe) und startet
//! die JVM. Die Java-Laufzeit kommt wie auf Android aus [`crate::runtime`] (`IOS_RUNTIMES`).
//! Alles außer `tauri_bridge` ist plattformunabhängig und wird überall getestet.

pub mod args;
pub mod engine;
pub mod jit;
pub mod memory;
pub mod probe;
pub mod session;
#[cfg(target_os = "ios")]
pub mod tauri_bridge;
