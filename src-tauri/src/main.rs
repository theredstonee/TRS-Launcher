// Kein Konsolenfenster in Release-Builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // Mit Admin-Rechten gestarteter Firewall-Hilfsmodus: nur Regeln eintragen, keine App.
    if let Some(code) = trs_core::firewall::helper_main_if_requested() {
        std::process::exit(code);
    }
    #[cfg(target_os = "linux")]
    linux_webkit_workarounds();
    trs_launcher_lib::run()
}

/// WebKitGTK zeigt mit dem proprietären NVIDIA-Treiber (v. a. unter Wayland)
/// und ohne GPU-Render-Knoten (VMs, Container, Software-Rendering) oft nur ein
/// leeres Fenster, solange der DMA-BUF-Renderer aktiv ist. Wer die Variable
/// selbst setzt, behält seinen Wert.
#[cfg(target_os = "linux")]
fn linux_webkit_workarounds() {
    let nvidia = std::path::Path::new("/proc/driver/nvidia/version").exists();
    let render_node = std::fs::read_dir("/dev/dri")
        .map(|entries| entries.flatten().any(|e| e.file_name().to_string_lossy().starts_with("renderD")))
        .unwrap_or(false);
    if (nvidia || !render_node) && std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        // SAFETY: ganz am Anfang von `main`, bevor irgendein weiterer Thread läuft.
        unsafe { std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1") };
    }
}
