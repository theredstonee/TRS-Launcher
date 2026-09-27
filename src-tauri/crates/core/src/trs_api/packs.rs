//! Geteilte Modpacks – API-Seite (Vertrag `api/API.md` §27).
//!
//! Alles, was vom Server kommt, wird gesäubert: IDs und Codes nach festem Muster, Texte gekürzt und ohne
//! Steuerzeichen, Links nur zur eigenen API. Das Exportieren/Installieren selbst steht in
//! [`crate::pack_share`].

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::types::{UserRef, clean_user};
use super::{Body, Req, validate};
use crate::{Error, Launcher, Result};

/// Größte Pack-Datei, die hoch- oder heruntergeladen wird (Server: `PACK_MAX_MB` = 50).
pub const MAX_PACK_BYTES: usize = 50 * 1024 * 1024;
/// Hoch-/Herunterladen einer Pack-Datei darf dauern (50 MB bei langsamer Leitung).
const TRANSFER_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(15 * 60);
/// Laufzeiten, die der Server kennt.
pub const DURATIONS: [&str; 4] = ["1d", "7d", "30d", "forever"];

/// Pack-ID: 22 Zeichen base64url.
pub fn pack_id(input: &str) -> bool {
    input.len() == 22 && input.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

/// Crockford-Base32 ohne I, L, O, U.
const ALPHABET: &str = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/// Eingabe → `TRS-XXXX-XXXX` (Groß/klein egal, mit oder ohne `TRS-`/Striche/Leerzeichen, O→0, I/L→1).
pub fn normalize_code(input: &str) -> Option<String> {
    let mut s: String = input.trim().to_ascii_uppercase().chars().filter(|c| !matches!(c, ' ' | '-')).collect();
    if s.len() == 11 && s.starts_with("TRS") {
        s.drain(..3);
    }
    let s: String = s.chars().map(|c| match c { 'O' => '0', 'I' | 'L' => '1', c => c }).collect();
    (s.len() == 8 && s.chars().all(|c| ALPHABET.contains(c))).then(|| format!("TRS-{}-{}", &s[..4], &s[4..]))
}

fn invalid_code() -> Error {
    Error::validation(crate::msg!("packShare.invalidCode", "Das ist kein gültiger Modpack-Code (Form: TRS-XXXX-XXXX)."))
}

fn id_arg(id: &str) -> Result<&str> {
    if pack_id(id) { Ok(id) } else { Err(Error::validation(crate::msg!("packShare.notFound", "Dieses Modpack gibt es nicht (mehr)."))) }
}

// --- Ansichten -----------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackLoaderView {
    /// `vanilla`, `forge`, `neoforge`, `fabric` oder `quilt`.
    pub kind: String,
    pub version: Option<String>,
}

/// Ein geteiltes Pack (§27.1, gesäubert).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharedPack {
    pub id: String,
    /// `TRS-XXXX-XXXX`.
    pub code: String,
    /// `https://…/p/TRS-XXXX-XXXX`.
    pub url: String,
    pub name: String,
    pub summary: Option<String>,
    pub pack_version: String,
    pub revision: u32,
    pub mc_version: String,
    pub loader: PackLoaderView,
    pub modrinth_files: u32,
    /// Mod-Dateien im Pack, die nicht von Modrinth kommen.
    pub own_jars: u32,
    pub other_files: u32,
    pub bytes: u64,
    pub sha256: String,
    pub owner: UserRef,
    pub created_at: Option<String>,
    pub updated_at: Option<String>,
    /// `None` = läuft nicht ab.
    pub expires_at: Option<String>,
}

/// Eigenes Pack: dazu Laufzeit, Installationen, an wie viele Freunde geschickt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OwnPack {
    #[serde(flatten)]
    pub pack: SharedPack,
    pub duration: String,
    pub installs: u32,
    pub sent_to: u32,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PackLimits {
    pub active: u32,
    pub max_active: u32,
    pub uploads_today: u32,
    pub max_per_day: u32,
    pub max_bytes: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MyPacks {
    pub packs: Vec<OwnPack>,
    pub limits: PackLimits,
}

/// Ein Pack, das dir ein Freund geschickt hat.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InboxPack {
    pub pack: SharedPack,
    pub from: UserRef,
    pub sent_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SendResult {
    pub sent: Vec<UserRef>,
    pub skipped: Vec<String>,
}

// --- Rohformen -----------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct ApiLoader {
    #[serde(default)]
    kind: String,
    #[serde(default)]
    version: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ApiPack {
    id: String,
    code: String,
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    name: String,
    #[serde(default)]
    summary: Option<String>,
    #[serde(default)]
    pack_version: String,
    #[serde(default)]
    revision: u32,
    #[serde(default)]
    mc_version: String,
    loader: ApiLoader,
    #[serde(default)]
    modrinth_files: u32,
    #[serde(default)]
    own_jars: u32,
    #[serde(default)]
    other_files: u32,
    #[serde(default)]
    bytes: u64,
    #[serde(default)]
    sha256: String,
    owner: UserRef,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    updated_at: Option<String>,
    #[serde(default)]
    expires_at: Option<String>,
    // Nur eigene Packs:
    #[serde(default)]
    duration: Option<String>,
    #[serde(default)]
    installs: Option<u32>,
    #[serde(default)]
    sent_to: Option<u32>,
}

fn time(value: Option<String>) -> Option<String> {
    value.map(|t| validate::text(&t, 40)).filter(|t| !t.is_empty())
}

fn safe_version(v: &str) -> bool {
    !v.is_empty() && v.len() <= 64 && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | '+'))
}

impl ApiPack {
    pub(crate) fn cleaned(self, bases: &[&str], own: &str) -> Option<(SharedPack, Option<String>, u32, u32)> {
        if !pack_id(&self.id) {
            return None;
        }
        let code = normalize_code(&self.code)?;
        let path = format!("/p/{code}");
        let url = self
            .url
            .filter(|u| bases.iter().any(|b| b.starts_with("https://") && *u == format!("{b}{path}")))
            .unwrap_or_else(|| format!("{own}{path}"));
        let kind = match self.loader.kind.as_str() {
            k @ ("vanilla" | "forge" | "neoforge" | "fabric" | "quilt") => k.to_owned(),
            _ => return None,
        };
        if !safe_version(&self.mc_version) {
            return None;
        }
        let sha256 = if self.sha256.len() == 64 && self.sha256.bytes().all(|b| b.is_ascii_hexdigit()) {
            self.sha256.to_ascii_lowercase()
        } else {
            return None;
        };
        let name = validate::text(&self.name, 64);
        let pack = SharedPack {
            id: self.id,
            code,
            url,
            name: if name.is_empty() { "Modpack".into() } else { name },
            summary: self.summary.map(|s| validate::text(&s, 300)).filter(|s| !s.is_empty()),
            pack_version: validate::text(&self.pack_version, 64),
            revision: self.revision.max(1),
            mc_version: self.mc_version,
            loader: PackLoaderView { kind, version: self.loader.version.filter(|v| safe_version(v)) },
            modrinth_files: self.modrinth_files.min(100_000),
            own_jars: self.own_jars.min(100_000),
            other_files: self.other_files.min(100_000),
            bytes: self.bytes.min(u64::from(u32::MAX)),
            sha256,
            owner: clean_user(self.owner)?,
            created_at: time(self.created_at),
            updated_at: time(self.updated_at),
            expires_at: time(self.expires_at),
        };
        let duration = self.duration.filter(|d| DURATIONS.contains(&d.as_str()));
        Some((pack, duration, self.installs.unwrap_or(0).min(1_000_000), self.sent_to.unwrap_or(0).min(100_000)))
    }
}

#[derive(Debug, Deserialize)]
struct PackEnvelope {
    pack: ApiPack,
}

#[derive(Debug, Deserialize)]
struct PacksEnvelope {
    #[serde(default)]
    packs: Vec<ApiPack>,
    #[serde(default)]
    limits: PackLimits,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiInbox {
    pack: ApiPack,
    from: UserRef,
    #[serde(default)]
    sent_at: Option<String>,
}

#[derive(Debug, Deserialize)]
struct InboxEnvelope {
    #[serde(default)]
    packs: Vec<ApiInbox>,
}

#[derive(Debug, Deserialize)]
struct ApiSend {
    #[serde(default)]
    sent: Vec<UserRef>,
    #[serde(default)]
    skipped: Vec<String>,
}

/// Eigene Texte für die Fehler rund ums Teilen.
fn pack_error(e: Error) -> Error {
    let Error::TrsApi { kind, code, msg } = e else { return e };
    let msg = match code.as_str() {
        "pack_limit" => crate::msg!("packShare.limit", "Du teilst schon 10 Modpacks – lösche zuerst eins unter „Meine Modpacks“."),
        "pack_daily_limit" => crate::msg!("packShare.dailyLimit", "Heute hast du schon 30 Modpacks hochgeladen – morgen geht es weiter."),
        "storage_full" => crate::msg!("packShare.storageFull", "Der TRS-Server hat gerade keinen Platz – bitte später erneut versuchen."),
        "pack_not_found" => crate::msg!("packShare.notFound", "Dieses Modpack gibt es nicht (mehr)."),
        "pack_unchanged" => crate::msg!("packShare.unchanged", "Seit der letzten Version hat sich nichts geändert."),
        "invalid_pack" => crate::msg!("packShare.invalidPack", "Der Server hat das Modpack abgelehnt – es ist kein gültiges Modrinth-Pack."),
        "payload_too_large" => crate::msg!("packShare.tooLarge", "Das Modpack ist größer als 50 MB – wähle weniger Ordner aus (z. B. ohne Resource Packs)."),
        "no_recipients" => crate::msg!("packShare.noRecipients", "Modpacks kannst du nur an deine Freunde schicken."),
        _ => msg,
    };
    let kind = if matches!(code.as_str(), "storage_full" | "pack_daily_limit") { "trs_api" } else { kind };
    Error::TrsApi { kind, code, msg }
}

impl Launcher {
    fn pack_bases(&self) -> (Vec<&str>, &str) {
        (self.trs.trusted_bases(), self.trs.base())
    }

    fn own_pack(&self, raw: ApiPack) -> Result<OwnPack> {
        let (bases, own) = self.pack_bases();
        let (pack, duration, installs, sent_to) = raw.cleaned(&bases, own).ok_or_else(super::bad_response)?;
        Ok(OwnPack { pack, duration: duration.unwrap_or_else(|| "7d".into()), installs, sent_to })
    }

    fn shared_pack(&self, raw: ApiPack) -> Option<SharedPack> {
        let (bases, own) = self.pack_bases();
        raw.cleaned(&bases, own).map(|(p, ..)| p)
    }

    /// Fertige `.mrpack`-Datei hochladen (neues Pack).
    pub(crate) async fn trs_pack_upload(&self, bytes: Vec<u8>, duration: &str) -> Result<OwnPack> {
        if !DURATIONS.contains(&duration) {
            return Err(Error::validation(crate::msg!("packShare.invalidDuration", "Ungültige Laufzeit.")));
        }
        let req = Req::with(
            reqwest::Method::POST,
            format!("/v1/packs?duration={duration}"),
            Body::Image("application/x-modrinth-modpack+zip", bytes),
            super::MAX_JSON_BYTES,
        )
        .timeout(TRANSFER_TIMEOUT);
        let env: PackEnvelope = self.trs_get(req).await.map_err(pack_error)?;
        self.own_pack(env.pack)
    }

    /// Neue Version eines eigenen Packs (gleicher Code).
    pub(crate) async fn trs_pack_upload_version(&self, id: &str, bytes: Vec<u8>) -> Result<OwnPack> {
        let id = id_arg(id)?;
        let req = Req::with(
            reqwest::Method::PUT,
            format!("/v1/packs/{id}/file"),
            Body::Image("application/x-modrinth-modpack+zip", bytes),
            super::MAX_JSON_BYTES,
        )
        .timeout(TRANSFER_TIMEOUT);
        let env: PackEnvelope = self.trs_get(req).await.map_err(pack_error)?;
        self.own_pack(env.pack)
    }

    pub async fn trs_pack_set_duration(&self, id: &str, duration: &str) -> Result<OwnPack> {
        let id = id_arg(id)?;
        if !DURATIONS.contains(&duration) {
            return Err(Error::validation(crate::msg!("packShare.invalidDuration", "Ungültige Laufzeit.")));
        }
        let env: PackEnvelope = self.trs_get(Req::patch(format!("/v1/packs/{id}"), json!({ "duration": duration }))).await.map_err(pack_error)?;
        self.own_pack(env.pack)
    }

    pub async fn trs_pack_delete(&self, id: &str) -> Result<()> {
        let id = id_arg(id)?;
        self.trs_do(Req::delete(format!("/v1/packs/{id}"))).await.map_err(pack_error)
    }

    pub async fn trs_packs_mine(&self) -> Result<MyPacks> {
        let env: PacksEnvelope = self.trs_get(Req::get("/v1/me/packs")).await.map_err(pack_error)?;
        let packs = env.packs.into_iter().take(200).filter_map(|p| self.own_pack(p).ok()).collect();
        let l = env.limits;
        let cap = |v: u32| v.min(10_000);
        Ok(MyPacks {
            packs,
            limits: PackLimits {
                active: cap(l.active),
                max_active: cap(l.max_active),
                uploads_today: cap(l.uploads_today),
                max_per_day: cap(l.max_per_day),
                max_bytes: l.max_bytes.min(MAX_PACK_BYTES as u64 * 20),
            },
        })
    }

    /// Pack zu einem Code (für die Vorschau vor der Installation).
    pub async fn trs_pack_by_code(&self, code: &str) -> Result<SharedPack> {
        let code = normalize_code(code).ok_or_else(invalid_code)?;
        let env: PackEnvelope = self.trs_get(Req::get(format!("/v1/packs/code/{code}"))).await.map_err(pack_error)?;
        self.shared_pack(env.pack).ok_or_else(super::bad_response)
    }

    /// Die `.mrpack`-Datei zu einem Code.
    pub(crate) async fn trs_pack_download(&self, code: &str) -> Result<Vec<u8>> {
        let code = normalize_code(code).ok_or_else(invalid_code)?;
        let req = Req::with(reqwest::Method::GET, format!("/v1/packs/code/{code}/file"), Body::Empty, MAX_PACK_BYTES + 1024)
            .timeout(TRANSFER_TIMEOUT);
        self.trs_raw(req).await.map_err(pack_error)
    }

    /// Aktueller Stand vieler Packs (Update-Prüfung). Unbekannte/abgelaufene fehlen.
    pub(crate) async fn trs_packs_lookup(&self, codes: &[String]) -> Result<Vec<SharedPack>> {
        let codes: Vec<String> = codes.iter().filter_map(|c| normalize_code(c)).take(100).collect();
        if codes.is_empty() {
            return Ok(Vec::new());
        }
        let env: PacksEnvelope = self.trs_get(Req::post("/v1/packs/lookup", json!({ "codes": codes }))).await.map_err(pack_error)?;
        Ok(env.packs.into_iter().take(100).filter_map(|p| self.shared_pack(p)).collect())
    }

    pub async fn trs_pack_send(&self, id: &str, friends: &[String]) -> Result<SendResult> {
        let id = id_arg(id)?;
        let to: Vec<String> = friends.iter().filter_map(|u| validate::uuid(u)).take(20).collect();
        if to.is_empty() {
            return Err(Error::validation(crate::msg!("packShare.noRecipients", "Modpacks kannst du nur an deine Freunde schicken.")));
        }
        let r: ApiSend = self.trs_get(Req::post(format!("/v1/packs/{id}/send"), json!({ "to": to }))).await.map_err(pack_error)?;
        Ok(SendResult {
            sent: r.sent.into_iter().filter_map(clean_user).take(20).collect(),
            skipped: r.skipped.iter().filter_map(|u| validate::uuid(u)).take(20).collect(),
        })
    }

    /// Packs, die Freunde dir geschickt haben.
    pub async fn trs_pack_inbox(&self) -> Result<Vec<InboxPack>> {
        let env: InboxEnvelope = self.trs_get(Req::get("/v1/me/pack-inbox")).await.map_err(pack_error)?;
        Ok(env
            .packs
            .into_iter()
            .take(200)
            .filter_map(|e| Some(InboxPack { pack: self.shared_pack(e.pack)?, from: clean_user(e.from)?, sent_at: time(e.sent_at) }))
            .collect())
    }

    pub async fn trs_pack_inbox_dismiss(&self, id: &str) -> Result<()> {
        let id = id_arg(id)?;
        self.trs_do(Req::delete(format!("/v1/me/pack-inbox/{id}"))).await.map_err(pack_error)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_normalized() {
        assert_eq!(normalize_code("TRS-AB12-CD34").as_deref(), Some("TRS-AB12-CD34"));
        assert_eq!(normalize_code(" trs ab12 cd34 ").as_deref(), Some("TRS-AB12-CD34"));
        assert_eq!(normalize_code("ab12cd34").as_deref(), Some("TRS-AB12-CD34"));
        assert_eq!(normalize_code("Ab1O-CDl4").as_deref(), Some("TRS-AB10-CD14"));
        assert_eq!(normalize_code("AB12CD3"), None);
        assert_eq!(normalize_code("AB12CD3U"), None);
        assert_eq!(normalize_code("../../etc"), None);
        assert_eq!(normalize_code("TRS-ÄB12-CD34"), None);
    }

    fn raw(over: serde_json::Value) -> ApiPack {
        let mut v = json!({
            "id": "Qm9vLWJhei1xdXV4LTEyMw", "code": "TRS-7K2M-Q9XA", "url": "https://trs-launcher.theredstonee.de/p/TRS-7K2M-Q9XA",
            "name": "Redstone\u{0007} Pack", "summary": "Tech", "packVersion": "1.0.0", "revision": 2, "mcVersion": "1.21.1",
            "loader": { "kind": "fabric", "version": "0.16.9" }, "modrinthFiles": 3, "ownJars": 1, "otherFiles": 4, "bytes": 1000,
            "sha256": "A".repeat(64), "owner": { "uuid": "0123456789abcdef0123456789abcdef", "name": "Alex" },
            "expiresAt": null, "duration": "forever", "installs": 5, "sentTo": 2
        });
        if let (Some(obj), Some(extra)) = (v.as_object_mut(), over.as_object()) {
            for (k, val) in extra {
                obj.insert(k.clone(), val.clone());
            }
        }
        serde_json::from_value(v).unwrap()
    }

    #[test]
    fn packs_are_cleaned() {
        let bases = ["https://trs-launcher.theredstonee.de"];
        let own = "https://trs-launcher.theredstonee.de";
        let (p, duration, installs, sent) = raw(json!({})).cleaned(&bases, own).unwrap();
        assert_eq!(p.name, "Redstone Pack");
        assert_eq!(p.sha256, "a".repeat(64));
        assert_eq!((duration.as_deref(), installs, sent), (Some("forever"), 5, 2));
        assert_eq!(p.expires_at, None);
        // Fremde Links werden ersetzt, kaputte Felder verwerfen das Pack.
        let (p, ..) = raw(json!({ "url": "https://evil.example/p/TRS-7K2M-Q9XA" })).cleaned(&bases, own).unwrap();
        assert_eq!(p.url, "https://trs-launcher.theredstonee.de/p/TRS-7K2M-Q9XA");
        assert!(raw(json!({ "id": "../x" })).cleaned(&bases, own).is_none());
        assert!(raw(json!({ "loader": { "kind": "evil" } })).cleaned(&bases, own).is_none());
        assert!(raw(json!({ "mcVersion": "1.21 && rm" })).cleaned(&bases, own).is_none());
        assert!(raw(json!({ "sha256": "xyz" })).cleaned(&bases, own).is_none());
        let (p, ..) = raw(json!({ "loader": { "kind": "forge", "version": "../../x" } })).cleaned(&bases, own).unwrap();
        assert_eq!(p.loader.version, None);
    }

    #[test]
    fn live_events_are_decoded() {
        use super::super::live::{LiveEvent, decode};
        let removed = decode("pack_removed", r#"{"type":"pack_removed","packId":"Qm9vLWJhei1xdXV4LTEyMw"}"#).unwrap();
        assert_eq!(removed, LiveEvent::PackRemoved { pack_id: "Qm9vLWJhei1xdXV4LTEyMw".into() });
        assert!(decode("pack_removed", r#"{"packId":"../x"}"#).is_none());
        let pack = json!({
            "id": "Qm9vLWJhei1xdXV4LTEyMw", "code": "TRS-7K2M-Q9XA", "name": "P", "revision": 2, "mcVersion": "1.21.1",
            "loader": { "kind": "fabric" }, "sha256": "a".repeat(64), "owner": { "uuid": "0123456789abcdef0123456789abcdef", "name": "Alex" }
        });
        let shared = decode("pack_shared", &json!({ "pack": pack, "from": { "uuid": "0123456789abcdef0123456789abcdef", "name": "Alex" } }).to_string()).unwrap();
        let out = serde_json::to_value(&shared).unwrap();
        assert_eq!(out["type"], "pack_shared");
        assert_eq!(out["pack"]["code"], "TRS-7K2M-Q9XA");
        assert_eq!(out["pack"]["url"], "https://trs-launcher.theredstonee.de/p/TRS-7K2M-Q9XA");
        assert!(decode("pack_updated", r#"{"pack":{"id":"x"}}"#).is_none());
    }
}
