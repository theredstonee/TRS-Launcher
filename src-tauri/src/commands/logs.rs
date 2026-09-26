//! Tab „Logs“: ältere Log-Dateien und Absturzberichte lesen und teilen, dazu
//! der QR-Code für geteilte Links.

use serde::Serialize;
use tauri::State;
use trs_core::logfiles::{self, LogSource, LogText};

use crate::LauncherState;
use crate::error::CommandResult;

#[tauri::command]
pub async fn list_log_sources(launcher: State<'_, LauncherState>, id: String) -> CommandResult<Vec<LogSource>> {
    let instance = launcher.instances().get(&id).await?;
    let paths = launcher.paths().clone();
    let result = tauri::async_runtime::spawn_blocking(move || logfiles::list_sources(&paths, &instance.id))
        .await
        .map_err(|e| trs_core::Error::Internal(e.to_string()))?;
    Ok(result?)
}

#[tauri::command]
pub async fn read_log_source(launcher: State<'_, LauncherState>, id: String, source: String) -> CommandResult<LogText> {
    let instance = launcher.instances().get(&id).await?;
    Ok(logfiles::read_source(launcher.paths(), &instance.id, &source).await?)
}

/// Lädt die gewählte Log-Datei geschwärzt auf mclo.gs hoch und liefert den Link.
#[tauri::command]
pub async fn share_log_source(launcher: State<'_, LauncherState>, id: String, source: String) -> CommandResult<String> {
    Ok(launcher.share_log_source(&id, &source).await?)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QrMatrix {
    /// Module je Seite (ohne Ruhezone).
    size: usize,
    /// Zeilenweise, `1` = dunkel.
    modules: Vec<u8>,
}

/// QR-Code für einen HTTPS-Link (z. B. den geteilten Log). Das Webview
/// zeichnet die Matrix selbst als SVG – kein HTML aus Rust.
#[tauri::command]
pub fn qr_code(text: String) -> CommandResult<QrMatrix> {
    if !text.starts_with("https://") || text.len() > 512 || text.chars().any(char::is_control) {
        return Err(trs_core::Error::validation(trs_core::msg!("logs.qrInvalid", "Für diesen Link gibt es keinen QR-Code.")).into());
    }
    let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M)
        .map_err(|_| trs_core::Error::validation(trs_core::msg!("logs.qrInvalid", "Für diesen Link gibt es keinen QR-Code.")))?;
    let modules = code.to_colors().into_iter().map(|c| u8::from(c == qrcode::Color::Dark)).collect();
    Ok(QrMatrix { size: code.width(), modules })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn qr_only_for_https_links() {
        let qr = qr_code("https://mclo.gs/AbC1234".into()).ok().unwrap();
        assert_eq!(qr.modules.len(), qr.size * qr.size);
        assert!(qr.size >= 21);
        // Finder-Muster oben links: erste Zeile beginnt mit 7 dunklen Modulen.
        assert_eq!(&qr.modules[..7], &[1, 1, 1, 1, 1, 1, 1]);
        assert!(qr_code("javascript:alert(1)".into()).is_err());
        assert!(qr_code(format!("https://{}", "a".repeat(600))).is_err());
    }
}
