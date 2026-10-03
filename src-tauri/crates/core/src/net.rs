//! HTTP-Clients mit der TLS-Einrichtung der Plattform.
//!
//! Desktop: `native-tls` (SChannel bzw. OpenSSL) wie bisher. Android/iOS: rustls
//! mit ring. iOS prüft Zertifikate über den System-Vertrauensspeicher
//! (rustls-platform-verifier), Android gegen die Mozilla-Wurzeln – der
//! System-Verifier bräuchte dort eine eigene Java-Komponente.

/// Wie `reqwest::Client::builder()`, aber mit der TLS-Einrichtung der Plattform.
/// Alle Clients des Kerns entstehen hierüber.
pub fn client_builder() -> reqwest::ClientBuilder {
    #[cfg(any(target_os = "android", target_os = "ios"))]
    mobile::install_crypto_provider();
    platform_builder()
}

#[cfg(not(target_os = "android"))]
fn platform_builder() -> reqwest::ClientBuilder {
    reqwest::Client::builder()
}

#[cfg(target_os = "android")]
fn platform_builder() -> reqwest::ClientBuilder {
    reqwest::Client::builder().tls_certs_only(mobile::root_certificates())
}

#[cfg(any(target_os = "android", target_os = "ios"))]
mod mobile {
    /// rustls braucht einen Krypto-Anbieter für den ganzen Prozess (einmalig).
    pub(super) fn install_crypto_provider() {
        static ONCE: std::sync::Once = std::sync::Once::new();
        ONCE.call_once(|| {
            // Schon gesetzt (z. B. von einer anderen Bibliothek) ist auch gut.
            let _ = rustls::crypto::ring::default_provider().install_default();
        });
    }

    /// Mozilla-Wurzelzertifikate (webpki-root-certs), einmal eingelesen.
    #[cfg(target_os = "android")]
    pub(super) fn root_certificates() -> Vec<reqwest::Certificate> {
        static ROOTS: std::sync::OnceLock<Vec<reqwest::Certificate>> = std::sync::OnceLock::new();
        ROOTS
            .get_or_init(|| {
                webpki_root_certs::TLS_SERVER_ROOT_CERTS
                    .iter()
                    .filter_map(|der| reqwest::Certificate::from_der(der.as_ref()).ok())
                    .collect()
            })
            .clone()
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn builder_builds() {
        assert!(super::client_builder().user_agent(crate::USER_AGENT).build().is_ok());
    }
}
