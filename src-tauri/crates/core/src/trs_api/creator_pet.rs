//! Creator-Haustiere (API §35.13, Vertrag `creator-pets-contract.md`): Ein
//! Creator hat höchstens ein eigenes Haustier (Kosmetik mit slot `pet`). Der
//! Besitzer erstellt dafür selbst Einlösecodes über das normale Code-System –
//! mit einem Monatslimit je Stufe (die Summe `maxUses` zählt) – und kann sie
//! widerrufen. Creator ohne Haustier stellen einen Wunsch (Tierart,
//! Beschreibung, Farben, optional ein Referenzbild), das Team baut es und
//! ordnet es zu.
//!
//! Die Antworten werden tolerant gelesen: unbekannte Felder fallen weg, fehlende
//! optionale Felder sind `None`, und neben der Server-Form (`quota`, `requests`,
//! `reply`, `declined`, `hint`) gehen auch die Namen aus dem ersten Entwurf
//! (`limit`, `request`, `answer`, `rejected`, `codeHint`). Helfer mit dem Recht
//! „codes“ handeln wie bei den übrigen Creator-Routen über `?as=<uuid>` (§35.9).
//! Das Kartenbild lädt der Kern selbst und gibt es als Data-URL weiter (CSP bleibt eng).

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::chat::chat_text;
use super::creator::{acting, expiry_arg, id_arg, id_of, invalid, long_text, opt_u64, str_of, time, u64_of};
use super::{Body, Req, png, validate};
use crate::{Launcher, Result};

/// Zustände eines Wunsches in der Ansicht (`declined` vom Server wird zu `rejected`).
pub const PET_REQUEST_STATUSES: [&str; 4] = ["open", "in_progress", "done", "rejected"];
/// Codes je Erstellung und Einlösungen je Code.
pub const PET_CODE_COUNT: std::ops::RangeInclusive<u64> = 1..=20;
pub const PET_CODE_USES: std::ops::RangeInclusive<u64> = 1..=100;
/// Notiz an Codes, Tierart, Beschreibung und Farben des Wunsches.
pub const PET_NOTE_MAX: usize = 100;
pub const PET_SPECIES_MAX: usize = 60;
pub const PET_DESCRIPTION_MAX: usize = 1000;
pub const PET_COLORS_MAX: usize = 200;
pub const PET_IMAGE_MAX_BYTES: usize = 2 * 1024 * 1024;
/// Größtes Kartenbild (Vorschauen sind höchstens 512 px groß).
const MAX_CARD_BYTES: usize = 2 * 1024 * 1024;
/// Upload des Referenzbilds: der Server kodiert neu, das dauert etwas.
const IMAGE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);

// --- Ansichten ---------------------------------------------------------------------------

/// Das eigene Haustier. `card` = Kartenbild als Data-URL (vom Kern geladen).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorPet {
    pub cosmetic_id: String,
    pub name: String,
    pub card: Option<String>,
    /// So viele laufen beim Creator mit (1–4 nach Fans), falls bekannt.
    pub count: Option<u64>,
    /// `false` = Kosmetik gerade nicht verfügbar (Codes lassen sich nicht erstellen).
    pub available: bool,
    #[serde(skip)]
    pub card_url: Option<String>,
}

/// Monatslimit: `used` von `max` Einlösungen im Kalendermonat `month` (`YYYY-MM`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PetLimit {
    pub month: Option<String>,
    pub used: u64,
    pub max: u64,
    /// Beginn des nächsten Monats (ISO), falls der Server ihn nennt.
    pub resets_at: Option<String>,
}

impl PetLimit {
    /// Was in diesem Monat noch geht.
    pub fn remaining(&self) -> u64 {
        self.max.saturating_sub(self.used)
    }
}

/// Wunsch nach einem eigenen Haustier.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PetRequest {
    pub id: String,
    /// `open`, `in_progress`, `done` oder `rejected`.
    pub status: String,
    pub species: Option<String>,
    /// Beschreibung (bzw. der Wunschtext).
    pub text: String,
    pub colors: Option<String>,
    /// Ein Referenzbild hängt am Wunsch.
    pub has_image: bool,
    /// Antwort des Teams.
    pub answer: Option<String>,
    pub created_at: Option<String>,
}

/// Stand für den Reiter „Haustier“.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreatorPetState {
    pub pet: Option<CreatorPet>,
    pub limit: Option<PetLimit>,
    /// Der neueste Wunsch (leer für Helfer).
    pub request: Option<PetRequest>,
    /// Darf jetzt einen Wunsch stellen – `None`, wenn der Server es nicht sagt.
    pub can_request: Option<bool>,
}

/// Ein Haustier-Code. `code` (Klartext) gibt es nur direkt nach dem Erstellen, sonst `code_hint`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PetCode {
    pub id: String,
    pub code: Option<String>,
    pub code_hint: Option<String>,
    pub max_uses: u64,
    pub uses: u64,
    pub revoked: bool,
    pub expires_at: Option<String>,
    pub note: Option<String>,
    pub created_at: Option<String>,
}

// --- Rohdaten → Ansicht ------------------------------------------------------------------

/// Kosmetik-ID wie in der API (`[A-Za-z0-9_.-]{1,64}`).
fn cosmetic_id(input: &str) -> Option<String> {
    let t = input.trim();
    ((1..=64).contains(&t.len()) && t.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'.' | b'-'))).then(|| t.to_owned())
}

fn clean_pet(v: Option<&Value>) -> Option<CreatorPet> {
    let v = v.filter(|v| v.is_object())?;
    let cosmetic_id = str_of(v, "cosmeticId").and_then(|id| cosmetic_id(&id))?;
    let name = str_of(v, "name").map(|n| validate::text(n.trim(), 48)).filter(|n| !n.trim().is_empty());
    Some(CreatorPet {
        name: name.unwrap_or_else(|| cosmetic_id.clone()),
        cosmetic_id,
        card: None,
        count: opt_u64(v, "count").filter(|n| (1..=4).contains(n)),
        available: v.get("available").and_then(Value::as_bool).unwrap_or(true),
        card_url: str_of(v, "cardUrl").or_else(|| str_of(v, "card")).filter(|u| u.len() <= 500),
    })
}

fn month(v: Option<String>) -> Option<String> {
    v.filter(|m| m.len() == 7 && m.as_bytes()[4] == b'-' && m.bytes().enumerate().all(|(i, b)| i == 4 || b.is_ascii_digit()))
}

/// `quota { month, limit, used, remaining, resetsAt }` (Server) oder `limit { month, used, max }` (Entwurf).
fn clean_limit(v: &Value) -> Option<PetLimit> {
    let (q, max) = match v.get("quota").filter(|q| q.is_object()) {
        Some(q) => (q, opt_u64(q, "limit").or_else(|| opt_u64(q, "max"))?),
        None => {
            let l = v.get("limit").filter(|l| l.is_object())?;
            (l, opt_u64(l, "max")?)
        }
    };
    // `remaining` vom Server hat Vorrang (widerrufene Codes zählen dort nur mit ihren echten Einlösungen).
    let used = match opt_u64(q, "remaining") {
        Some(rest) => max.saturating_sub(rest.min(max)),
        None => u64_of(q, "used"),
    };
    Some(PetLimit { month: month(str_of(q, "month")), used, max, resets_at: time(str_of(q, "resetsAt")) })
}

fn status(raw: Option<String>) -> Option<String> {
    match raw.as_deref()? {
        "declined" => Some("rejected".into()),
        s if PET_REQUEST_STATUSES.contains(&s) => Some(s.to_owned()),
        _ => None,
    }
}

fn line(v: &Value, key: &str, max: usize) -> Option<String> {
    str_of(v, key).map(|t| validate::text(t.trim(), max)).filter(|t| !t.trim().is_empty())
}

pub(crate) fn clean_request(v: Option<&Value>) -> Option<PetRequest> {
    let v = v.filter(|v| v.is_object())?;
    let text = str_of(v, "description").or_else(|| str_of(v, "text")).map(|t| chat_text(&t, PET_DESCRIPTION_MAX)).unwrap_or_default();
    Some(PetRequest {
        id: id_of(v.get("id")?)?,
        status: status(str_of(v, "status"))?,
        species: line(v, "species", PET_SPECIES_MAX),
        text: text.trim().to_owned(),
        colors: line(v, "colors", PET_COLORS_MAX),
        has_image: v.get("image").is_some_and(Value::is_object),
        answer: str_of(v, "reply")
            .or_else(|| str_of(v, "answer"))
            .map(|a| chat_text(&a, 1000))
            .map(|a| a.trim().to_owned())
            .filter(|a| !a.is_empty()),
        created_at: time(str_of(v, "createdAt")),
    })
}

/// Neuester Wunsch: `request` (Entwurf) oder der erste aus `requests` (Server, neueste zuerst).
fn latest_request(v: &Value) -> Option<PetRequest> {
    if let Some(r) = v.get("request").filter(|r| r.is_object()) {
        return clean_request(Some(r));
    }
    v.get("requests").and_then(Value::as_array)?.iter().take(20).find_map(|r| clean_request(Some(r)))
}

pub(crate) fn clean_state(v: &Value) -> Option<CreatorPetState> {
    v.is_object().then(|| CreatorPetState {
        pet: clean_pet(v.get("pet")),
        limit: clean_limit(v),
        request: latest_request(v),
        can_request: v.get("canRequest").and_then(Value::as_bool),
    })
}

/// Klartext-Code: groß, A–Z/0–9 mit Bindestrichen, 4–40 Zeichen.
fn plain_code(input: &str) -> Option<String> {
    let s = input.trim().to_ascii_uppercase();
    ((4..=40).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'-')).then_some(s)
}

/// Code-Hinweis (z. B. `C4HJ` oder `…C4HJN`): nur sichtbare Zeichen, höchstens 24.
fn hint(input: &str) -> Option<String> {
    let t = validate::text(input.trim(), 24);
    (!t.is_empty() && t.chars().all(|c| !c.is_control() && !c.is_whitespace())).then_some(t)
}

pub(crate) fn clean_code(v: &Value) -> Option<PetCode> {
    let code = str_of(v, "code").and_then(|c| plain_code(&c));
    let code_hint = str_of(v, "hint").or_else(|| str_of(v, "codeHint")).and_then(|h| hint(&h));
    if code.is_none() && code_hint.is_none() {
        return None;
    }
    Some(PetCode {
        id: id_of(v.get("id")?)?,
        code,
        code_hint,
        max_uses: u64_of(v, "maxUses").max(1),
        uses: u64_of(v, "uses"),
        revoked: v.get("revoked").and_then(Value::as_bool).unwrap_or(false) || v.get("revokedAt").is_some_and(|r| !r.is_null()),
        expires_at: time(str_of(v, "expiresAt")),
        note: str_of(v, "note").map(|n| validate::text(n.trim(), 200)).filter(|n| !n.is_empty()),
        created_at: time(str_of(v, "createdAt")),
    })
}

pub(crate) fn clean_codes(v: Option<&Value>) -> Vec<PetCode> {
    let mut codes: Vec<_> = v.and_then(Value::as_array).into_iter().flatten().take(1000).filter_map(clean_code).collect();
    // Neueste zuerst.
    codes.sort_by(|a, b| b.created_at.cmp(&a.created_at));
    codes
}

// --- Eingaben ----------------------------------------------------------------------------

/// Neue Haustier-Codes (Dashboard „Haustier“).
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NewPetCodes {
    pub count: u64,
    pub max_uses: u64,
    #[serde(default)]
    pub expires_at: Option<String>,
    #[serde(default)]
    pub note: Option<String>,
}

impl NewPetCodes {
    /// Summe der Einlösungen, die gegen das Monatslimit zählt.
    pub fn cost(&self) -> u64 {
        self.count.saturating_mul(self.max_uses)
    }

    pub(crate) fn body(&self) -> Result<Value> {
        if !PET_CODE_COUNT.contains(&self.count) {
            return Err(invalid(crate::msg!("creatorPet.countRange", "Anzahl: 1 bis {max} Codes auf einmal.", max = 20)));
        }
        if !PET_CODE_USES.contains(&self.max_uses) {
            return Err(invalid(crate::msg!("creatorPet.usesRange", "Einlösungen je Code: 1 bis {max}.", max = 100)));
        }
        let mut body = json!({ "count": self.count, "maxUses": self.max_uses });
        if let Some(at) = self.expires_at.as_deref().map(str::trim).filter(|a| !a.is_empty()) {
            body["expiresAt"] = json!(expiry_arg(at)?);
        }
        if let Some(note) = self.note.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
            body["note"] = json!(validate::plain_text(note, PET_NOTE_MAX, validate::TextField::Note)?);
        }
        Ok(body)
    }
}

/// Wunsch nach einem eigenen Haustier: Tierart, Beschreibung, Farben + optional Referenzbild (PNG/JPG als Base64).
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NewPetRequest {
    pub species: String,
    pub description: String,
    #[serde(default)]
    pub colors: Option<String>,
    #[serde(default)]
    pub image: Option<String>,
}

/// Bildart aus den ersten Bytes (nicht aus Dateiname oder Angabe des Webviews).
pub fn image_kind(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some("image/jpeg")
    } else {
        None
    }
}

fn image_too_large() -> crate::Error {
    invalid(crate::msg!("creatorPet.imageTooLarge", "Das Bild ist zu groß (höchstens 2 MB)."))
}

fn invalid_image() -> crate::Error {
    invalid(crate::msg!("creatorPet.invalidImage", "Das Referenzbild muss ein PNG- oder JPG-Bild sein."))
}

/// Geprüftes Referenzbild: MIME-Typ und Bytes.
pub type PetImage = (&'static str, Vec<u8>);

/// Base64 (ohne oder mit `data:`-Kopf) → geprüftes Bild: PNG oder JPG, höchstens 2 MB.
pub fn decode_image(encoded: &str) -> Result<PetImage> {
    let raw = encoded.trim();
    let raw = match raw.strip_prefix("data:") {
        Some(rest) => rest.split_once(";base64,").map(|(_, data)| data).ok_or_else(invalid_image)?,
        None => raw,
    };
    if raw.len() > PET_IMAGE_MAX_BYTES.div_ceil(3) * 4 + 4 {
        return Err(image_too_large());
    }
    let bytes = STANDARD.decode(raw).map_err(|_| invalid_image())?;
    if bytes.len() > PET_IMAGE_MAX_BYTES {
        return Err(image_too_large());
    }
    let kind = image_kind(&bytes).ok_or_else(invalid_image)?;
    Ok((kind, bytes))
}

fn species_arg(input: &str) -> Result<String> {
    let t = input.trim();
    if t.is_empty() || t.chars().count() > PET_SPECIES_MAX {
        return Err(invalid(crate::msg!("creatorPet.speciesLength", "Tierart: 1 bis {max} Zeichen.", max = PET_SPECIES_MAX)));
    }
    validate::plain_text(t, PET_SPECIES_MAX, validate::TextField::Comment)
}

impl NewPetRequest {
    /// JSON für den Wunsch und – getrennt – das geprüfte Bild (geht danach als eigener Upload raus).
    pub(crate) fn parts(&self) -> Result<(Value, Option<PetImage>)> {
        let mut body = json!({ "species": species_arg(&self.species)?, "description": long_text(&self.description, PET_DESCRIPTION_MAX)? });
        if let Some(colors) = self.colors.as_deref().map(str::trim).filter(|c| !c.is_empty()) {
            body["colors"] = json!(validate::plain_text(colors, PET_COLORS_MAX, validate::TextField::Comment)?);
        }
        let image = match self.image.as_deref().map(str::trim).filter(|i| !i.is_empty()) {
            Some(i) => Some(decode_image(i)?),
            None => None,
        };
        Ok((body, image))
    }
}

// --- Anfragen ----------------------------------------------------------------------------

impl Launcher {
    /// Kartenbild der eigenen API laden (mit Token – Creator-Haustiere sind oft nicht öffentlich).
    async fn pet_card(&self, pet: &mut CreatorPet) {
        let Some(path) = pet.card_url.as_deref().and_then(|u| self.trs.own_path(u)).map(str::to_owned) else { return };
        let token = self.creator_token().await;
        if let Some(bytes) = self.trs.own_png(&path, token.as_deref(), MAX_CARD_BYTES).await
            && png::size(&bytes).is_some()
        {
            pet.card = Some(format!("data:image/png;base64,{}", STANDARD.encode(&bytes)));
        }
    }

    /// Eigenes Haustier, Monatslimit und Wunsch (`GET /v1/creator/pet`).
    pub async fn creator_pet(&self, as_creator: Option<&str>) -> Result<CreatorPetState> {
        let raw: Value = self.trs_get(Req::get(acting("/v1/creator/pet".into(), as_creator)?)).await?;
        let mut state = clean_state(&raw).ok_or_else(super::bad_response)?;
        if let Some(pet) = state.pet.as_mut() {
            self.pet_card(pet).await;
        }
        Ok(state)
    }

    /// Alle Haustier-Codes (ohne Klartext).
    pub async fn creator_pet_codes(&self, as_creator: Option<&str>) -> Result<Vec<PetCode>> {
        let raw: Value = self.trs_get(Req::get(acting("/v1/creator/pet/codes".into(), as_creator)?)).await?;
        Ok(clean_codes(raw.get("codes")))
    }

    /// Codes erstellen – der Klartext steht **nur** in dieser Antwort.
    pub async fn creator_create_pet_codes(&self, new: &NewPetCodes, as_creator: Option<&str>) -> Result<Vec<PetCode>> {
        let raw: Value = self.trs_get(Req::post(acting("/v1/creator/pet/codes".into(), as_creator)?, new.body()?)).await?;
        Ok(clean_codes(raw.get("codes")))
    }

    /// Code widerrufen (bleibt in der Liste, lässt sich nicht mehr einlösen).
    pub async fn creator_revoke_pet_code(&self, id: &str, as_creator: Option<&str>) -> Result<()> {
        let id = id_arg(id)?;
        self.trs_do(Req::delete(acting(format!("/v1/creator/pet/codes/{id}"), as_creator)?)).await
    }

    /// Wunsch nach einem eigenen Haustier (der Creator selbst oder das Team im Team-Modus), danach ggf. das Referenzbild.
    pub async fn creator_request_pet(&self, request: &NewPetRequest, as_creator: Option<&str>) -> Result<PetRequest> {
        let (body, image) = request.parts()?;
        let raw: Value = self.trs_get(Req::post(acting("/v1/creator/pet/requests".into(), as_creator)?, body)).await?;
        let created = latest_request(&raw).ok_or_else(super::bad_response)?;
        match image {
            Some((mime, bytes)) => self.upload_pet_image(&created.id, mime, bytes, as_creator).await,
            None => Ok(created),
        }
    }

    /// Referenzbild an einen offenen Wunsch hängen bzw. ersetzen.
    pub async fn creator_pet_request_image(&self, id: &str, image: &str, as_creator: Option<&str>) -> Result<PetRequest> {
        let id = id_arg(id)?.to_owned();
        let (mime, bytes) = decode_image(image)?;
        self.upload_pet_image(&id, mime, bytes, as_creator).await
    }

    async fn upload_pet_image(&self, id: &str, mime: &'static str, bytes: Vec<u8>, as_creator: Option<&str>) -> Result<PetRequest> {
        let path = acting(format!("/v1/creator/pet/requests/{id}/image"), as_creator)?;
        let req = Req::with(reqwest::Method::PUT, path, Body::Image(mime, bytes), 1024 * 1024).timeout(IMAGE_TIMEOUT);
        let raw: Value = self.trs_get(req).await?;
        latest_request(&raw).ok_or_else(super::bad_response)
    }

    /// Offenen Wunsch zurückziehen.
    pub async fn creator_withdraw_pet_request(&self, id: &str, as_creator: Option<&str>) -> Result<()> {
        let id = id_arg(id)?;
        self.trs_do(Req::delete(acting(format!("/v1/creator/pet/requests/{id}"), as_creator)?)).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn state_is_read_tolerantly() {
        // Form des Servers.
        let raw = json!({
            "pet": { "cosmeticId": "penguin", "name": "Pinguin", "card": "https://trs-launcher.theredstonee.de/v1/cosmetics/penguin/card.png?v=ab12",
                     "count": 3, "available": true, "assignedAt": "2026-10-25T00:00:00.000Z" },
            "quota": { "month": "2026-10", "limit": 200, "used": 40, "remaining": 170, "resetsAt": "2026-11-01T00:00:00.000Z" },
            "codes": [], "requests": [], "canRequest": false, "unknown": true
        });
        let s = clean_state(&raw).unwrap();
        let pet = s.pet.unwrap();
        assert_eq!((pet.cosmetic_id.as_str(), pet.name.as_str(), pet.count, pet.available), ("penguin", "Pinguin", Some(3), true));
        assert!(pet.card_url.unwrap().ends_with("card.png?v=ab12"));
        let limit = s.limit.unwrap();
        assert_eq!((limit.month.as_deref(), limit.used, limit.max, limit.remaining()), (Some("2026-10"), 30, 200, 170), "remaining hat Vorrang");
        assert_eq!(limit.resets_at.as_deref(), Some("2026-11-01T00:00:00.000Z"));
        assert_eq!((s.request, s.can_request), (None, Some(false)));

        // Form aus dem Entwurf.
        let s = clean_state(&json!({ "pet": { "cosmeticId": "fox", "cardUrl": "/v1/cosmetics/fox/card.png" }, "limit": { "month": "2026-10", "used": 3, "max": 10 },
            "request": { "id": 2, "status": "open", "text": "Fuchs" } })).unwrap();
        assert_eq!(s.pet.as_ref().unwrap().name, "fox", "ohne Namen: die ID");
        assert_eq!(s.limit.unwrap().remaining(), 7);
        assert_eq!(s.request.unwrap().text, "Fuchs");
        assert_eq!(s.can_request, None);

        // Fehlt alles Optionale, bleibt ein leerer Stand; kaputte Werte fallen weg.
        let s = clean_state(&json!({ "pet": { "cosmeticId": "../x" }, "quota": { "month": "Oktober", "used": 3 } })).unwrap();
        assert!(s.pet.is_none() && s.limit.is_none() && s.request.is_none());
        assert_eq!(clean_state(&json!({ "limit": { "month": "2026-1x", "max": 10 } })).unwrap().limit.unwrap().month, None);
        assert!(clean_state(&json!([])).is_none());
    }

    #[test]
    fn requests_are_normalized() {
        let raw = json!({ "requests": [
            { "id": 5, "status": "declined", "species": "Fuchs", "description": "Mit Schal\nund Mütze", "colors": "", "image": { "url": "/v1/creator-pet-images/x.jpg" },
              "reply": "Zu ähnlich zum Pinguin", "createdAt": "2026-10-25T10:00:00.000Z" },
            { "id": 4, "status": "done", "species": "Katze", "description": "alt" }
        ] });
        let r = latest_request(&raw).unwrap();
        assert_eq!((r.id.as_str(), r.status.as_str(), r.species.as_deref(), r.colors.as_deref()), ("5", "rejected", Some("Fuchs"), None));
        assert!(r.has_image && r.text.contains("Schal"));
        assert_eq!(r.answer.as_deref(), Some("Zu ähnlich zum Pinguin"));
        let r = clean_request(Some(&json!({ "id": 1, "status": "in_progress", "text": "x", "answer": "  " }))).unwrap();
        assert_eq!((r.status.as_str(), r.answer, r.has_image), ("in_progress", None, false));
        assert!(clean_request(Some(&json!({ "id": 1, "status": "maybe", "text": "x" }))).is_none());
        assert!(clean_request(Some(&json!({ "id": "../1", "status": "open" }))).is_none());
        // Kaputter erster Eintrag → der nächste gilt.
        assert_eq!(latest_request(&json!({ "requests": [{ "id": "../x", "status": "open" }, { "id": 2, "status": "open" }] })).unwrap().id, "2");
    }

    #[test]
    fn pet_events_arrive_as_creator_updates() {
        use super::super::creator::decode_live;
        use super::super::live::LiveEvent;
        for kind in ["pet_assigned", "pet_removed", "pet_request_answered"] {
            let data = json!({ "kind": kind, "reason": "Fertig – viel Spaß!" }).to_string();
            match decode_live("creator_updated", &data) {
                Some(LiveEvent::CreatorUpdated { kind: k, reason, .. }) => {
                    assert_eq!(k, kind);
                    assert_eq!(reason.as_deref(), Some("Fertig – viel Spaß!"));
                }
                other => panic!("{kind}: {other:?}"),
            }
        }
        assert!(decode_live("creator_updated", &json!({ "kind": "pet_unknown" }).to_string()).is_none());
    }

    #[test]
    fn codes_are_cleaned() {
        let codes = clean_codes(Some(&json!([
            { "id": 1, "hint": "C4HJ", "maxUses": 10, "uses": 4, "revokedAt": null, "expiresAt": null, "note": "Stream", "createdAt": "2026-10-20T00:00:00.000Z",
              "createdBy": { "uuid": "x", "name": "y" } },
            { "id": 2, "code": "7k3qf-m2xpa-9rtvb-c4hjn", "hint": "C4HJ", "maxUses": 5, "uses": 0, "createdAt": "2026-10-25T00:00:00.000Z" },
            { "id": 3, "codeHint": "AB12", "maxUses": 1, "revokedAt": "2026-10-21T00:00:00.000Z" },
            { "id": 6, "codeHint": "ZZ99", "maxUses": 1, "revoked": true },
            { "id": 4, "code": "<script>", "maxUses": 1 },
            { "id": "../5", "hint": "X" }
        ])));
        assert_eq!(codes.iter().map(|c| c.id.as_str()).collect::<Vec<_>>(), ["2", "1", "3", "6"], "neueste zuerst, kaputte fallen weg");
        assert_eq!(codes[0].code.as_deref(), Some("7K3QF-M2XPA-9RTVB-C4HJN"));
        assert!(!codes[1].revoked && codes[2].revoked && codes[3].revoked);
        assert_eq!(codes[1].note.as_deref(), Some("Stream"));
    }

    #[test]
    fn new_codes_are_checked() {
        let ok = NewPetCodes { count: 3, max_uses: 10, expires_at: None, note: Some("  Stream  ".into()) };
        assert_eq!(ok.cost(), 30);
        assert_eq!(ok.body().unwrap(), json!({ "count": 3, "maxUses": 10, "note": "Stream" }));
        let code = |count, max_uses| NewPetCodes { count, max_uses, expires_at: None, note: None }.body().unwrap_err().message_code();
        assert_eq!(code(0, 1), "creatorPet.countRange");
        assert_eq!(code(21, 1), "creatorPet.countRange");
        assert_eq!(code(1, 101), "creatorPet.usesRange");
        let past = NewPetCodes { count: 1, max_uses: 1, expires_at: Some("2020-01-01T00:00:00Z".into()), note: None };
        assert_eq!(past.body().unwrap_err().message_code(), "creator.expiryPast");
        let future = NewPetCodes { count: 1, max_uses: 1, expires_at: Some("2099-01-01T00:00:00Z".into()), note: None };
        assert_eq!(future.body().unwrap()["expiresAt"], "2099-01-01T00:00:00.000Z");
        let long = NewPetCodes { count: 1, max_uses: 1, expires_at: None, note: Some("x".repeat(101)) };
        assert!(long.body().is_err());
    }

    #[test]
    fn request_images_are_png_or_jpeg_up_to_2_mb() {
        let png_bytes = b"\x89PNG\r\n\x1a\nrest".to_vec();
        let jpg = [0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3].to_vec();
        assert_eq!(decode_image(&STANDARD.encode(&png_bytes)).unwrap(), ("image/png", png_bytes.clone()));
        assert_eq!(decode_image(&format!("data:image/jpeg;base64,{}", STANDARD.encode(&jpg))).unwrap(), ("image/jpeg", jpg.clone()));
        assert_eq!(decode_image(&STANDARD.encode(b"GIF89a....")).unwrap_err().message_code(), "creatorPet.invalidImage");
        assert_eq!(decode_image("%%%").unwrap_err().message_code(), "creatorPet.invalidImage");
        let mut big = png_bytes.clone();
        big.resize(PET_IMAGE_MAX_BYTES + 1, 0);
        assert_eq!(decode_image(&STANDARD.encode(&big)).unwrap_err().message_code(), "creatorPet.imageTooLarge");

        let wish = |species: &str, description: &str, colors: Option<&str>| NewPetRequest {
            species: species.into(),
            description: description.into(),
            colors: colors.map(Into::into),
            image: None,
        };
        let (body, image) = NewPetRequest { image: Some(STANDARD.encode(&jpg)), ..wish(" Fuchs ", " Mit Schal\nund Mütze ", Some("  ")) }.parts().unwrap();
        assert_eq!(body, json!({ "species": "Fuchs", "description": "Mit Schal\nund Mütze" }), "leere Farben fallen weg");
        assert_eq!(image.unwrap().0, "image/jpeg");
        assert_eq!(wish("Fuchs", "x", Some("Orange, Weiß")).parts().unwrap().0["colors"], "Orange, Weiß");
        assert_eq!(wish("  ", "x", None).parts().unwrap_err().message_code(), "creatorPet.speciesLength");
        assert_eq!(wish(&"F".repeat(61), "x", None).parts().unwrap_err().message_code(), "creatorPet.speciesLength");
        assert_eq!(wish("Fuchs", "   ", None).parts().unwrap_err().message_code(), "creator.textLength");
        assert_eq!(wish("Fuchs", &"x".repeat(1001), None).parts().unwrap_err().message_code(), "creator.textLength");
    }
}
