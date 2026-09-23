//! Microsoft → Xbox Live → XSTS → Minecraft. Zwei Wege zum Microsoft-Token:
//! Auth-Code + PKCE über einen Loopback-Server (Standard) und Device-Code.

use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

use crate::{Error, Result};

/// Public Client (kein Secret) – die ID ist nicht geheim.
pub const CLIENT_ID: &str = "ac3d320e-d0a2-4910-8e3c-b425883984a9";

const SCOPE: &str = "XboxLive.signin offline_access";
// Xbox-Login gibt es nur für private Konten → fester `consumers`-Tenant.
const AUTHORIZE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const DEVICE_CODE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";

const XBL_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const MC_LOGIN_URL: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const MC_ENTITLEMENTS_URL: &str = "https://api.minecraftservices.com/entitlements/mcstore";
const MC_PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";

const BROWSER_LOGIN_TIMEOUT: Duration = Duration::from_secs(5 * 60);

/// So ist die Umleitungs-URI in der Azure-App eingetragen:
/// `http://localhost:28443/login`. Microsoft ignoriert bei `localhost` den
/// Port, der Pfad muss aber exakt stimmen.
const REDIRECT_PORT: u16 = 28443;
const REDIRECT_PATH: &str = "/login";

#[derive(Debug, Deserialize)]
pub struct MsTokens {
    pub access_token: String,
    pub refresh_token: String,
}

#[derive(Debug, Deserialize)]
struct OAuthError {
    error: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all(serialize = "camelCase"))]
pub struct DeviceCode {
    #[serde(skip_serializing)]
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    #[serde(skip_serializing)]
    pub interval: u64,
}

pub struct MinecraftSession {
    pub access_token: String,
    pub expires_at: DateTime<Utc>,
    pub uuid: String,
    pub name: String,
    pub skin_url: Option<String>,
    pub xuid: String,
}

async fn token_request(http: &reqwest::Client, form: &[(&str, &str)]) -> Result<std::result::Result<MsTokens, String>> {
    let response = http.post(TOKEN_URL).form(form).send().await?;
    if response.status().is_success() {
        return Ok(Ok(response.json().await?));
    }
    let err: OAuthError = response.json().await.map_err(|_| Error::auth(crate::msg!("auth.microsoftRejected", "Microsoft hat die Anmeldung abgelehnt.")))?;
    Ok(Err(err.error))
}

// --- Device-Code ---------------------------------------------------------

pub async fn device_code_start(http: &reqwest::Client) -> Result<DeviceCode> {
    let response = http
        .post(DEVICE_CODE_URL)
        .form(&[("client_id", CLIENT_ID), ("scope", SCOPE)])
        .send()
        .await?;
    if !response.status().is_success() {
        tracing::error!("devicecode fehlgeschlagen: {}", response.status());
        return Err(Error::auth(crate::msg!("auth.microsoftRejected", "Microsoft hat die Anmeldung abgelehnt.")));
    }
    Ok(response.json().await?)
}

pub async fn device_code_poll(http: &reqwest::Client, code: &DeviceCode) -> Result<MsTokens> {
    let mut interval = code.interval.clamp(1, 30);
    let deadline = tokio::time::Instant::now() + Duration::from_secs(code.expires_in.min(30 * 60));

    loop {
        tokio::time::sleep(Duration::from_secs(interval)).await;
        if tokio::time::Instant::now() >= deadline {
            return Err(Error::auth(crate::msg!("auth.deviceCodeExpired", "Der Anmeldecode ist abgelaufen – bitte erneut versuchen.")));
        }
        let form = [
            ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
            ("client_id", CLIENT_ID),
            ("device_code", code.device_code.as_str()),
        ];
        match token_request(http, &form).await? {
            Ok(tokens) => return Ok(tokens),
            Err(e) if e == "authorization_pending" => {}
            Err(e) if e == "slow_down" => interval += 5,
            Err(e) if e == "authorization_declined" => return Err(Error::Cancelled),
            Err(e) if e == "expired_token" => {
                return Err(Error::auth(crate::msg!("auth.deviceCodeExpired", "Der Anmeldecode ist abgelaufen – bitte erneut versuchen.")));
            }
            Err(e) => {
                tracing::error!("Device-Code-Token fehlgeschlagen: {e}");
                return Err(Error::auth(crate::msg!("auth.microsoftRejected", "Microsoft hat die Anmeldung abgelehnt.")));
            }
        }
    }
}

// --- Auth-Code + PKCE ----------------------------------------------------

fn random_token() -> String {
    // Zwei v4-UUIDs ≈ 244 Bit Zufall; 64 Zeichen aus [0-9a-f] sind ein gültiger PKCE-Verifier.
    format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple())
}

fn pkce_challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

const DONE_PAGE: &str = "<!doctype html><html lang=\"de\"><meta charset=\"utf-8\"><title>TRS Launcher</title>\
<body style=\"font-family:Segoe UI,sans-serif;background:#0b0c0f;color:#f2f4f8;display:grid;place-items:center;height:100vh;margin:0\">\
<div style=\"text-align:center\"><h1 style=\"font-size:20px\">Anmeldung abgeschlossen</h1>\
<p style=\"color:#7d8699\">Du kannst dieses Fenster schlie&szlig;en und zum TRS Launcher zur&uuml;ckkehren.</p></div>";

const FAILED_PAGE: &str = "<!doctype html><html lang=\"de\"><meta charset=\"utf-8\"><title>TRS Launcher</title>\
<body style=\"font-family:Segoe UI,sans-serif;background:#0b0c0f;color:#f2f4f8;display:grid;place-items:center;height:100vh;margin:0\">\
<div style=\"text-align:center\"><h1 style=\"font-size:20px\">Anmeldung fehlgeschlagen</h1>\
<p style=\"color:#7d8699\">Bitte im TRS Launcher erneut versuchen.</p></div>";

async fn respond(stream: &mut tokio::net::TcpStream, status: &str, body: &str) {
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\n\
         Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n\
         Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = stream.write_all(response.as_bytes()).await;
    let _ = stream.shutdown().await;
}

/// Öffnet den Browser (über `open_url`) und wartet auf den Redirect.
pub async fn browser_login(http: &reqwest::Client, open_url: &(dyn Fn(&str) + Sync)) -> Result<MsTokens> {
    // Nur Loopback – von außen ist der Port nicht erreichbar.
    let listener = match TcpListener::bind(("127.0.0.1", REDIRECT_PORT)).await {
        Ok(listener) => listener,
        // Port belegt (z. B. zweiter Launcher): irgendein freier tut es auch.
        Err(_) => TcpListener::bind(("127.0.0.1", 0))
            .await
            .map_err(|e| Error::Internal(format!("Loopback-Server: {e}")))?,
    };
    let port = listener.local_addr().map_err(|e| Error::Internal(e.to_string()))?.port();
    let redirect_uri = format!("http://localhost:{port}{REDIRECT_PATH}");

    let state = random_token();
    let verifier = random_token();
    let challenge = pkce_challenge(&verifier);

    let authorize = reqwest::Url::parse_with_params(
        AUTHORIZE_URL,
        &[
            ("client_id", CLIENT_ID),
            ("response_type", "code"),
            ("redirect_uri", &redirect_uri),
            ("response_mode", "query"),
            ("scope", SCOPE),
            ("state", &state),
            ("code_challenge", &challenge),
            ("code_challenge_method", "S256"),
            ("prompt", "select_account"),
        ],
    )
    .map_err(|e| Error::Internal(e.to_string()))?;
    open_url(authorize.as_str());

    let code = tokio::time::timeout(BROWSER_LOGIN_TIMEOUT, wait_for_redirect(&listener, &state))
        .await
        .map_err(|_| Error::auth(crate::msg!("auth.loginTimeout", "Zeitüberschreitung bei der Anmeldung – bitte erneut versuchen.")))??;

    let form = [
        ("grant_type", "authorization_code"),
        ("client_id", CLIENT_ID),
        ("code", code.as_str()),
        ("redirect_uri", redirect_uri.as_str()),
        ("code_verifier", verifier.as_str()),
        ("scope", SCOPE),
    ];
    token_request(http, &form).await?.map_err(|e| {
        tracing::error!("Code-Einlösung fehlgeschlagen: {e}");
        Error::auth(crate::msg!("auth.microsoftRejected", "Microsoft hat die Anmeldung abgelehnt."))
    })
}

async fn wait_for_redirect(listener: &TcpListener, expected_state: &str) -> Result<String> {
    loop {
        let (mut stream, _) = listener.accept().await.map_err(|e| Error::Internal(e.to_string()))?;

        let mut buf = vec![0u8; 8192];
        let mut len = 0;
        // Nur die Request-Zeile interessiert; bei 8 KB ist Schluss.
        while len < buf.len() {
            match tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buf[len..])).await {
                Ok(Ok(n)) if n > 0 => {
                    len += n;
                    if buf[..len].windows(2).any(|w| w == b"\r\n") {
                        break;
                    }
                }
                _ => break,
            }
        }
        let head = String::from_utf8_lossy(&buf[..len]);
        let Some(target) = head.lines().next().and_then(|l| l.strip_prefix("GET ")).and_then(|l| l.split(' ').next())
        else {
            respond(&mut stream, "400 Bad Request", FAILED_PAGE).await;
            continue;
        };
        let Ok(url) = reqwest::Url::parse(&format!("http://localhost{target}")) else {
            respond(&mut stream, "400 Bad Request", FAILED_PAGE).await;
            continue;
        };
        // Browser fragen gern zusätzlich nach /favicon.ico.
        if url.path() != REDIRECT_PATH {
            respond(&mut stream, "404 Not Found", "").await;
            continue;
        }

        let param = |name: &str| url.query_pairs().find(|(k, _)| k == name).map(|(_, v)| v.into_owned());
        if param("state").as_deref() != Some(expected_state) {
            respond(&mut stream, "400 Bad Request", FAILED_PAGE).await;
            continue;
        }
        if let Some(error) = param("error") {
            respond(&mut stream, "200 OK", FAILED_PAGE).await;
            return if error == "access_denied" {
                Err(Error::Cancelled)
            } else {
                tracing::error!("Authorize-Fehler: {error}");
                Err(Error::auth(crate::msg!("auth.microsoftRejected", "Microsoft hat die Anmeldung abgelehnt.")))
            };
        }
        if let Some(code) = param("code") {
            respond(&mut stream, "200 OK", DONE_PAGE).await;
            return Ok(code);
        }
        respond(&mut stream, "400 Bad Request", FAILED_PAGE).await;
    }
}

// --- Refresh -------------------------------------------------------------

pub async fn refresh(http: &reqwest::Client, refresh_token: &str) -> Result<MsTokens> {
    let form = [
        ("grant_type", "refresh_token"),
        ("client_id", CLIENT_ID),
        ("refresh_token", refresh_token),
        ("scope", SCOPE),
    ];
    token_request(http, &form).await?.map_err(|e| {
        tracing::warn!("Token-Refresh fehlgeschlagen: {e}");
        Error::auth(crate::msg!("auth.sessionExpired", "Die Anmeldung ist abgelaufen – bitte den Account erneut anmelden."))
    })
}

// --- Xbox Live → Minecraft ----------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct XboxResponse {
    token: String,
    display_claims: DisplayClaims,
}

#[derive(Deserialize)]
struct DisplayClaims {
    xui: Vec<Xui>,
}

#[derive(Deserialize)]
struct Xui {
    uhs: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct XstsError {
    x_err: u64,
}

#[derive(Deserialize)]
struct McLogin {
    access_token: String,
    expires_in: i64,
}

#[derive(Deserialize)]
struct Entitlements {
    #[serde(default)]
    items: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
struct Profile {
    id: String,
    name: String,
    #[serde(default)]
    skins: Vec<Skin>,
}

#[derive(Deserialize)]
struct Skin {
    state: String,
    url: String,
}

fn xsts_error_message(x_err: u64) -> crate::error::Msg {
    match x_err {
        2_148_916_227 => crate::msg!("auth.xboxBanned", "Dieses Xbox-Konto ist gesperrt."),
        2_148_916_233 => crate::msg!(
            "auth.xboxNoProfile",
            "Zu diesem Microsoft-Konto gibt es noch kein Xbox-Profil. Bitte einmal auf xbox.com anmelden und eines anlegen."
        ),
        2_148_916_235 => crate::msg!("auth.xboxRegionBlocked", "Xbox Live ist in deinem Land nicht verfügbar."),
        2_148_916_236 | 2_148_916_237 => {
            crate::msg!("auth.xboxAgeVerification", "Für dieses Konto ist eine Altersverifikation bei Xbox nötig.")
        }
        2_148_916_238 => crate::msg!(
            "auth.xboxChildAccount",
            "Dieses Konto gehört einem Kind und muss erst von einem Erwachsenen zu einer Microsoft-Familie hinzugefügt werden."
        ),
        _ => crate::msg!("auth.xboxFailed", "Die Anmeldung bei Xbox Live ist fehlgeschlagen."),
    }
}

pub async fn minecraft_login(http: &reqwest::Client, ms_access_token: &str) -> Result<MinecraftSession> {
    let xbl: XboxResponse = http
        .post(XBL_URL)
        .json(&serde_json::json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": format!("d={ms_access_token}"),
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT",
        }))
        .send()
        .await?
        .error_for_status()
        .map_err(|e| {
            tracing::error!("XBL fehlgeschlagen: {}", e.without_url());
            Error::auth(crate::msg!("auth.xboxFailed", "Die Anmeldung bei Xbox Live ist fehlgeschlagen."))
        })?
        .json()
        .await?;
    let uhs = xbl.display_claims.xui.first().map(|x| x.uhs.clone()).unwrap_or_default();

    let response = http
        .post(XSTS_URL)
        .json(&serde_json::json!({
            "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl.token] },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT",
        }))
        .send()
        .await?;
    if response.status() == reqwest::StatusCode::UNAUTHORIZED {
        let x_err = response.json::<XstsError>().await.map(|e| e.x_err).unwrap_or_default();
        tracing::warn!("XSTS abgelehnt: XErr {x_err}");
        return Err(Error::auth(xsts_error_message(x_err)));
    }
    let xsts: XboxResponse = response
        .error_for_status()
        .map_err(|_| Error::auth(crate::msg!("auth.xboxFailed", "Die Anmeldung bei Xbox Live ist fehlgeschlagen.")))?
        .json()
        .await?;

    let response = http
        .post(MC_LOGIN_URL)
        .json(&serde_json::json!({ "identityToken": format!("XBL3.0 x={uhs};{}", xsts.token) }))
        .send()
        .await?;
    // Neue App-Registrierungen müssen erst von Mojang freigegeben werden.
    if response.status() == reqwest::StatusCode::FORBIDDEN {
        return Err(Error::AuthNotApproved);
    }
    let mc: McLogin = response
        .error_for_status()
        .map_err(|e| {
            tracing::error!("login_with_xbox fehlgeschlagen: {}", e.without_url());
            Error::auth(crate::msg!("auth.minecraftServicesFailed", "Die Anmeldung bei den Minecraft-Diensten ist fehlgeschlagen."))
        })?
        .json()
        .await?;

    let entitlements: Entitlements = http
        .get(MC_ENTITLEMENTS_URL)
        .bearer_auth(&mc.access_token)
        .send()
        .await?
        .error_for_status()
        .map_err(|_| Error::auth(crate::msg!("auth.entitlementCheckFailed", "Der Spielbesitz konnte nicht geprüft werden.")))?
        .json()
        .await?;
    if entitlements.items.is_empty() {
        return Err(Error::auth(crate::msg!("auth.noJavaEdition", "Dieses Konto besitzt Minecraft: Java Edition nicht.")));
    }

    let response = http.get(MC_PROFILE_URL).bearer_auth(&mc.access_token).send().await?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Err(Error::auth(crate::msg!("auth.noProfile", "Für dieses Konto gibt es noch kein Minecraft-Profil. Bitte einmal im offiziellen Launcher anmelden und einen Spielernamen festlegen.")));
    }
    let profile: Profile = response
        .error_for_status()
        .map_err(|_| Error::auth(crate::msg!("auth.profileLoadFailed", "Das Minecraft-Profil konnte nicht geladen werden.")))?
        .json()
        .await?;

    let valid_uuid = profile.id.len() == 32 && profile.id.bytes().all(|b| b.is_ascii_hexdigit());
    if !valid_uuid {
        return Err(Error::auth(crate::msg!("auth.profileInvalid", "Das Minecraft-Profil enthält ungültige Daten.")));
    }

    Ok(MinecraftSession {
        xuid: xuid_from_jwt(&mc.access_token).unwrap_or_default(),
        expires_at: Utc::now() + chrono::Duration::seconds(mc.expires_in.clamp(60, 7 * 86_400)),
        access_token: mc.access_token,
        uuid: profile.id.to_ascii_lowercase(),
        name: profile.name,
        skin_url: profile
            .skins
            .into_iter()
            .find(|s| s.state == "ACTIVE")
            .and_then(|s| safe_skin_url(&s.url)),
    })
}

/// Mojang liefert `http://textures.minecraft.net/...`; nur dieser Host, nur HTTPS.
fn safe_skin_url(url: &str) -> Option<String> {
    let rest = url.strip_prefix("http://").or_else(|| url.strip_prefix("https://"))?;
    let (host, path) = rest.split_once('/')?;
    let path_ok = path.chars().all(|c| c.is_ascii_alphanumeric() || c == '/');
    (host == "textures.minecraft.net" && path_ok).then(|| format!("https://{host}/{path}"))
}

/// Die XUID steht als Claim im Minecraft-Token (für `--xuid`). Nur gelesen,
/// nicht verifiziert – das Token kommt direkt von Mojang über TLS.
fn xuid_from_jwt(token: &str) -> Option<String> {
    let payload = token.split('.').nth(1)?;
    let bytes = URL_SAFE_NO_PAD.decode(payload.trim_end_matches('=')).ok()?;
    let json: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    let xuid = json.get("xuid")?.as_str()?;
    xuid.bytes().all(|b| b.is_ascii_digit()).then(|| xuid.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkce_matches_rfc7636_vector() {
        assert_eq!(
            pkce_challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
        let v = random_token();
        assert_eq!(v.len(), 64);
        assert_ne!(v, random_token());
    }

    #[test]
    fn skin_urls() {
        assert_eq!(
            safe_skin_url("http://textures.minecraft.net/texture/abc123").as_deref(),
            Some("https://textures.minecraft.net/texture/abc123")
        );
        assert!(safe_skin_url("https://evil.example/texture/abc").is_none());
        assert!(safe_skin_url("javascript:alert(1)").is_none());
        assert!(safe_skin_url("https://textures.minecraft.net/texture/a?x=<script>").is_none());
    }

    #[test]
    fn reads_xuid_claim() {
        let payload = URL_SAFE_NO_PAD.encode(br#"{"xuid":"2535412345678901","sub":"x"}"#);
        assert_eq!(xuid_from_jwt(&format!("h.{payload}.s")).as_deref(), Some("2535412345678901"));
        assert!(xuid_from_jwt("kein-jwt").is_none());
    }

    #[tokio::test]
    async fn redirect_server_validates_state_and_returns_code() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();

        let client = tokio::spawn(async move {
            let http = reqwest::Client::new();
            let base = format!("http://127.0.0.1:{port}");
            // Falscher State und Favicon dürfen den Login nicht beenden.
            assert_eq!(http.get(format!("{base}/login?code=evil&state=falsch")).send().await.unwrap().status(), 400);
            assert_eq!(http.get(format!("{base}/favicon.ico")).send().await.unwrap().status(), 404);
            let ok = http.get(format!("{base}/login?code=M.C5_abc%2Bdef&state=richtig")).send().await.unwrap();
            assert_eq!(ok.status(), 200);
            assert!(ok.text().await.unwrap().contains("Anmeldung abgeschlossen"));
        });

        let code = wait_for_redirect(&listener, "richtig").await.unwrap();
        assert_eq!(code, "M.C5_abc+def");
        client.await.unwrap();
    }

    #[tokio::test]
    async fn redirect_server_maps_denied_to_cancelled() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let _ = reqwest::get(format!("http://127.0.0.1:{port}/login?error=access_denied&state=s")).await;
        });
        assert!(matches!(wait_for_redirect(&listener, "s").await, Err(Error::Cancelled)));
    }
}
