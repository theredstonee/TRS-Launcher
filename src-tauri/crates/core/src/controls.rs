//! Touch-Steuerung der mobilen App: Layouts der Bildschirm-Knöpfe.
//!
//! Ein Layout ist eine kleine JSON-Datei (Format Version 1, gleich auf
//! Android, iOS und im Launcher-Editor). Positionen und Größen sind Anteile
//! (0..1) der sicheren Bildschirmfläche, also ohne Notch/Ränder.
//!
//! Alle Layouts liegen in `<daten>/controls/<id>.json` – auch die drei
//! fertigen (PvP, Bauen, Redstone). Die schreibt der Launcher mit
//! `builtinRev`; wer sie ändert (Launcher oder Editor im Spiel), entfernt die
//! Markierung, dann bleiben sie bei Updates unangetastet. „Zurücksetzen“ holt
//! den Auslieferungsstand zurück. Das Overlay im Spiel liest nur die Datei.
//!
//! Teilen: als Datei (das Layout selbst) oder als Code
//! `TRSC1-` + Base64url(Deflate(JSON)).

use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::paths::Paths;
use crate::{Error, Result, fsutil};

pub const LAYOUT_VERSION: u32 = 1;
pub const MAX_BUTTONS: usize = 48;
/// Eigene Layouts (ohne die fertigen).
pub const MAX_LAYOUTS: usize = 50;
pub const MAX_NAME_CHARS: usize = 48;
pub const MAX_LABEL_CHARS: usize = 12;
pub const MAX_CHORD: usize = 3;
/// Kleinste Kantenlänge eines Knopfs (Anteil).
pub const MIN_SIZE: f64 = 0.02;
pub const MIN_OPACITY: f64 = 0.05;
pub const MIN_SENSITIVITY: f64 = 0.1;
pub const MAX_SENSITIVITY: f64 = 5.0;
/// Dateien und Codes sind klein – mehr wird nicht gelesen.
pub const MAX_IMPORT_BYTES: u64 = 64 * 1024;
/// Layout, wenn eine Instanz keins gewählt hat (oder es weg ist).
pub const DEFAULT_ID: &str = "pvp";
const CODE_PREFIX: &str = "TRSC1-";
const DIR_NAME: &str = "controls";
/// Spielraum für Rundungsfehler an den Rändern.
const EPS: f64 = 1e-6;

static WRITE_LOCK: Mutex<()> = Mutex::const_new(());

// --- Datenmodell -------------------------------------------------------------------

/// Wofür das Layout gedacht ist (Kategorie, auch bei eigenen Kopien).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Profile {
    Pvp,
    Build,
    Redstone,
    Custom,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Shape {
    #[default]
    Round,
    Rect,
}

/// Wann ein Knopf sichtbar ist: im Spiel (Maus gefangen), in Menüs oder immer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Show {
    Game,
    Menu,
    Always,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum JoystickMode {
    /// Laufen (W/A/S/D, ganz nach vorn = Sprinten).
    Wasd,
    /// Umsehen per Stick statt Wischen.
    Camera,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Special {
    /// Bildschirmtastatur ein/aus.
    Keyboard,
    /// Esc.
    Menu,
    /// TRS-Menü (rechte Umschalttaste).
    TrsMenu,
    /// Emote-Rad des TRS Clients (gedrückt halten).
    EmoteWheel,
    /// Chat öffnen + Tastatur.
    Chat,
    /// Fläche über der Hotbar: tippen wählt den Platz, wischen blättert.
    HotbarSwipe,
    /// Mausrad hoch/runter (Hotbar blättern).
    ScrollUp,
    ScrollDown,
}

/// Was ein Knopf auslöst. Tasten sind GLFW-Codes; `chord` = Tasten, die
/// vorher gedrückt und danach losgelassen werden (z. B. F3 für F3+G).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Action {
    Key {
        key: i32,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        chord: Vec<i32>,
    },
    Mouse {
        button: u8,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        chord: Vec<i32>,
    },
    /// Taste einrasten: einmal tippen = gedrückt, nochmal = los.
    Toggle { key: i32 },
    Joystick { mode: JoystickMode },
    Special { special: Special },
}

/// Pixel-Symbole (gleiche Liste in TS, Kotlin und Swift).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Icon {
    Attack,
    Use,
    Place,
    Break,
    Jump,
    Sneak,
    Sprint,
    Inventory,
    Chat,
    Keyboard,
    Menu,
    Trs,
    Emote,
    Pick,
    FlyUp,
    FlyDown,
    Drop,
    Perspective,
    Zoom,
    Debug,
    Redstone,
    Hotbar,
    Prev,
    Next,
    Swap,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Button {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<Icon>,
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
    #[serde(default = "default_opacity")]
    pub opacity: f64,
    #[serde(default)]
    pub shape: Shape,
    pub action: Action,
    /// Jede Taste/Maustaste einrastend statt nur solange gedrückt.
    #[serde(default, skip_serializing_if = "is_false")]
    pub toggle: bool,
    /// Der Finger auf dem Knopf dreht zusätzlich die Kamera.
    #[serde(default, skip_serializing_if = "is_false")]
    pub pass_through: bool,
    /// Fehlt = je nach Aktion (Menü/Tastatur/Chat immer, sonst nur im Spiel).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub show: Option<Show>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Gestures {
    /// Kurz tippen = angreifen (sonst = benutzen/setzen).
    pub tap_attack: bool,
    /// Halten = benutzen (sonst = abbauen).
    pub hold_use: bool,
    /// Auf der Hotbar-Fläche wischen blättert die Plätze.
    pub swipe_hotbar: bool,
    pub camera_sensitivity: f64,
    #[serde(default = "yes")]
    pub haptics: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Layout {
    pub version: u32,
    pub id: String,
    pub name: String,
    pub profile: Profile,
    /// Nur bei unveränderten fertigen Layouts (Auslieferungsstand).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub builtin_rev: Option<u32>,
    pub buttons: Vec<Button>,
    pub gestures: Gestures,
}

/// Ein Layout, wie es die Oberfläche bekommt.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredLayout {
    pub layout: Layout,
    /// Eins der fertigen Layouts (nicht löschbar, nur zurücksetzbar).
    pub builtin: bool,
    /// Fertiges Layout, aber geändert.
    pub modified: bool,
}

fn default_opacity() -> f64 {
    0.6
}

fn yes() -> bool {
    true
}

fn is_false(v: &bool) -> bool {
    !*v
}

// --- Fertige Layouts ------------------------------------------------------------------

/// Stand der mitgelieferten Layouts; höher = beim nächsten Start neu schreiben
/// (nur, wo der Nutzer nichts geändert hat).
pub const BUILTIN_REV: u32 = 1;

const BUILTINS: [(&str, &str); 3] = [
    ("pvp", include_str!("controls/pvp.json")),
    ("build", include_str!("controls/build.json")),
    ("redstone", include_str!("controls/redstone.json")),
];

pub fn is_builtin(id: &str) -> bool {
    BUILTINS.iter().any(|(b, _)| *b == id)
}

/// Auslieferungsstand eines fertigen Layouts.
pub fn builtin(id: &str) -> Option<Layout> {
    let (_, json) = BUILTINS.iter().find(|(b, _)| *b == id)?;
    serde_json::from_str(json).ok()
}

pub fn builtins() -> Vec<Layout> {
    BUILTINS.iter().filter_map(|(id, _)| builtin(id)).collect()
}

// --- Prüfung --------------------------------------------------------------------------

fn invalid(reason: &str) -> Error {
    Error::validation(crate::msg!("controls.invalidLayout", "Das Layout ist ungültig ({reason}).", reason = reason))
}

fn not_found() -> Error {
    Error::validation(crate::msg!("controls.notFound", "Dieses Layout gibt es nicht mehr."))
}

fn builtin_read_only() -> Error {
    Error::validation(crate::msg!(
        "controls.builtinReadOnly",
        "Fertige Layouts lassen sich nicht löschen – nur zurücksetzen."
    ))
}

fn too_many() -> Error {
    Error::validation(crate::msg!("controls.tooMany", "Mehr als {max} eigene Layouts gehen nicht.", max = MAX_LAYOUTS))
}

fn invalid_code() -> Error {
    Error::validation(crate::msg!("controls.invalidCode", "Das ist kein gültiger Steuerungs-Code."))
}

fn invalid_file() -> Error {
    Error::validation(crate::msg!("controls.invalidFile", "Das ist keine gültige Steuerungs-Datei."))
}

fn too_large() -> Error {
    Error::validation(crate::msg!("controls.tooLarge", "Die Steuerungs-Datei ist zu groß."))
}

fn newer_version() -> Error {
    Error::validation(crate::msg!(
        "controls.newerVersion",
        "Dieses Layout stammt aus einer neueren Launcher-Version."
    ))
}

/// Layout-IDs sind zugleich Dateinamen: klein, Ziffern, Bindestrich.
pub fn is_valid_id(id: &str) -> bool {
    let bytes = id.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 40
        && bytes[0].is_ascii_alphanumeric()
        && bytes.iter().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || *b == b'-')
}

fn is_valid_button_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 32 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

/// GLFW-Tastencodes (druckbare Zeichen bis Menü-Taste).
pub fn is_valid_key(key: i32) -> bool {
    (32..=348).contains(&key)
}

fn has_control(text: &str) -> bool {
    text.chars().any(char::is_control)
}

fn in_unit(v: f64) -> bool {
    v.is_finite() && (-EPS..=1.0 + EPS).contains(&v)
}

fn validate_chord(key: Option<i32>, chord: &[i32]) -> Result<()> {
    if chord.len() > MAX_CHORD {
        return Err(invalid("chord"));
    }
    for (i, k) in chord.iter().enumerate() {
        if !is_valid_key(*k) || chord[..i].contains(k) || Some(*k) == key {
            return Err(invalid("chord"));
        }
    }
    Ok(())
}

fn validate_button(b: &Button) -> Result<()> {
    if !is_valid_button_id(&b.id) {
        return Err(invalid("button id"));
    }
    if let Some(label) = &b.label
        && (label.trim().is_empty() || label.chars().count() > MAX_LABEL_CHARS || has_control(label))
    {
        return Err(invalid("label"));
    }
    if b.label.is_none() && b.icon.is_none() {
        return Err(invalid("label"));
    }
    let size_ok = |v: f64| v.is_finite() && (MIN_SIZE - EPS..=1.0 + EPS).contains(&v);
    if !in_unit(b.x) || !in_unit(b.y) || !size_ok(b.w) || !size_ok(b.h) || b.x + b.w > 1.0 + EPS || b.y + b.h > 1.0 + EPS {
        return Err(invalid("position"));
    }
    if !b.opacity.is_finite() || !(MIN_OPACITY - EPS..=1.0 + EPS).contains(&b.opacity) {
        return Err(invalid("opacity"));
    }
    match &b.action {
        Action::Key { key, chord } => {
            if !is_valid_key(*key) {
                return Err(invalid("key"));
            }
            validate_chord(Some(*key), chord)?;
        }
        Action::Mouse { button, chord } => {
            if *button > 7 {
                return Err(invalid("mouse"));
            }
            validate_chord(None, chord)?;
        }
        Action::Toggle { key } if !is_valid_key(*key) => return Err(invalid("key")),
        _ => {}
    }
    Ok(())
}

/// Prüft ein Layout vollständig (Format, Grenzen, eindeutige Knopf-IDs).
pub fn validate(layout: &Layout) -> Result<()> {
    if layout.version > LAYOUT_VERSION {
        return Err(newer_version());
    }
    if layout.version != LAYOUT_VERSION {
        return Err(invalid("version"));
    }
    if !is_valid_id(&layout.id) {
        return Err(invalid("id"));
    }
    let name_len = layout.name.trim().chars().count();
    if name_len == 0 || name_len > MAX_NAME_CHARS || has_control(&layout.name) {
        return Err(Error::validation(crate::msg!(
            "controls.nameLength",
            "Der Name muss zwischen 1 und {max} Zeichen lang sein.",
            max = MAX_NAME_CHARS
        )));
    }
    if layout.buttons.is_empty() || layout.buttons.len() > MAX_BUTTONS {
        return Err(Error::validation(crate::msg!(
            "controls.buttonCount",
            "Ein Layout braucht 1 bis {max} Knöpfe.",
            max = MAX_BUTTONS
        )));
    }
    for (i, b) in layout.buttons.iter().enumerate() {
        validate_button(b)?;
        if layout.buttons[..i].iter().any(|o| o.id == b.id) {
            return Err(invalid("duplicate button id"));
        }
    }
    let s = layout.gestures.camera_sensitivity;
    if !s.is_finite() || !(MIN_SENSITIVITY..=MAX_SENSITIVITY).contains(&s) {
        return Err(invalid("sensitivity"));
    }
    Ok(())
}

/// Liest ein Layout aus JSON (unbekannte Felder werden ignoriert).
pub fn parse(bytes: &[u8]) -> Result<Layout> {
    if bytes.len() as u64 > MAX_IMPORT_BYTES {
        return Err(too_large());
    }
    let layout: Layout = serde_json::from_slice(bytes).map_err(|_| invalid_file())?;
    validate(&layout)?;
    Ok(layout)
}

// --- Teilen als Code ------------------------------------------------------------------

/// Layout → kurzer Text zum Kopieren (ohne Auslieferungs-Markierung).
pub fn encode_code(layout: &Layout) -> Result<String> {
    let mut shared = layout.clone();
    shared.builtin_rev = None;
    let json = serde_json::to_vec(&shared).map_err(|e| Error::Internal(e.to_string()))?;
    let mut enc = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
    enc.write_all(&json).map_err(|e| Error::Internal(e.to_string()))?;
    let packed = enc.finish().map_err(|e| Error::Internal(e.to_string()))?;
    Ok(format!("{CODE_PREFIX}{}", URL_SAFE_NO_PAD.encode(packed)))
}

/// Code → geprüftes Layout. Leerzeichen/Zeilenumbrüche (aus Chats) stören nicht.
pub fn decode_code(code: &str) -> Result<Layout> {
    let compact: String = code.chars().filter(|c| !c.is_whitespace()).collect();
    if compact.len() as u64 > MAX_IMPORT_BYTES {
        return Err(too_large());
    }
    let body = compact.strip_prefix(CODE_PREFIX).ok_or_else(invalid_code)?;
    let packed = URL_SAFE_NO_PAD.decode(body).map_err(|_| invalid_code())?;
    let mut json = Vec::new();
    // Höchstens MAX+1 Bytes entpacken: schützt vor „Zip-Bomben“.
    flate2::read::DeflateDecoder::new(packed.as_slice())
        .take(MAX_IMPORT_BYTES + 1)
        .read_to_end(&mut json)
        .map_err(|_| invalid_code())?;
    if json.len() as u64 > MAX_IMPORT_BYTES {
        return Err(too_large());
    }
    let layout: Layout = serde_json::from_slice(&json).map_err(|_| invalid_code())?;
    validate(&layout)?;
    Ok(layout)
}

// --- Speicher ---------------------------------------------------------------------------

/// `<daten>/controls` – hier liest auch das Overlay im Spiel.
pub fn dir(paths: &Paths) -> PathBuf {
    paths.root().join(DIR_NAME)
}

/// Datei eines Layouts (die ID ist geprüft, also ein sicherer Dateiname).
pub fn layout_file(paths: &Paths, id: &str) -> Result<PathBuf> {
    if !is_valid_id(id) {
        return Err(invalid("id"));
    }
    Ok(dir(paths).join(format!("{id}.json")))
}

async fn read_layout(path: &Path) -> Option<Layout> {
    let meta = tokio::fs::metadata(path).await.ok()?;
    if meta.len() > MAX_IMPORT_BYTES {
        return None;
    }
    let bytes = tokio::fs::read(path).await.ok()?;
    parse(&bytes).ok()
}

/// Schreibt die fertigen Layouts, wo sie fehlen, kaputt oder veraltet (und
/// unverändert) sind. Geänderte bleiben, wie sie sind.
pub async fn ensure_builtins(paths: &Paths) -> Result<()> {
    for layout in builtins() {
        let file = layout_file(paths, &layout.id)?;
        let stale = match read_layout(&file).await {
            None => true,
            Some(current) => current.builtin_rev.is_some_and(|rev| rev < BUILTIN_REV),
        };
        if stale {
            fsutil::write_json(&file, &layout).await?;
        }
    }
    Ok(())
}

fn stored(layout: Layout) -> StoredLayout {
    let builtin = is_builtin(&layout.id);
    StoredLayout { modified: builtin && layout.builtin_rev.is_none(), builtin, layout }
}

async fn read_all(paths: &Paths) -> Result<Vec<Layout>> {
    let dir = dir(paths);
    let mut out = Vec::new();
    let mut entries = match tokio::fs::read_dir(&dir).await {
        Ok(e) => e,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(out),
        Err(e) => return Err(Error::io(&dir, e)),
    };
    let mut seen = 0usize;
    while let Some(entry) = entries.next_entry().await.map_err(|e| Error::io(&dir, e))? {
        seen += 1;
        // Gegen volle Ordner: mehr als nötig wird gar nicht erst angesehen.
        if seen > (MAX_LAYOUTS + BUILTINS.len()) * 4 {
            break;
        }
        let path = entry.path();
        let Some(stem) = path.file_stem().and_then(|s| s.to_str()) else { continue };
        if path.extension().and_then(|e| e.to_str()) != Some("json") || !is_valid_id(stem) {
            continue;
        }
        // Nur, wenn ID und Dateiname zusammenpassen (sonst fremde/umbenannte Datei).
        if let Some(layout) = read_layout(&path).await.filter(|l| l.id == stem) {
            out.push(layout);
        }
    }
    Ok(out)
}

/// Alle Layouts: erst die fertigen (PvP, Bauen, Redstone), dann die eigenen nach Name.
pub async fn list(paths: &Paths) -> Result<Vec<StoredLayout>> {
    let _guard = WRITE_LOCK.lock().await;
    ensure_builtins(paths).await?;
    let mut all = read_all(paths).await?;
    let rank = |l: &Layout| BUILTINS.iter().position(|(b, _)| *b == l.id).unwrap_or(BUILTINS.len());
    all.sort_by(|a, b| rank(a).cmp(&rank(b)).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    Ok(all.into_iter().map(stored).collect())
}

pub async fn get(paths: &Paths, id: &str) -> Result<StoredLayout> {
    let file = layout_file(paths, id)?;
    if let Some(layout) = read_layout(&file).await {
        return Ok(stored(layout));
    }
    builtin(id).map(stored).ok_or_else(not_found)
}

fn custom_count(all: &[Layout]) -> usize {
    all.iter().filter(|l| !is_builtin(&l.id)).count()
}

fn new_id() -> String {
    format!("custom-{}", &uuid::Uuid::new_v4().simple().to_string()[..10])
}

/// Gleicher Name schon vergeben? Dann „Name (2)“, „Name (3)“ …
fn unique_name(name: &str, taken: &[&str]) -> String {
    let name = name.trim();
    if !taken.contains(&name) {
        return name.to_owned();
    }
    (2..=MAX_LAYOUTS + BUILTINS.len() + 1)
        .map(|n| {
            let suffix = format!(" ({n})");
            let base: String = name.chars().take(MAX_NAME_CHARS - suffix.chars().count()).collect();
            format!("{}{suffix}", base.trim_end())
        })
        .find(|candidate| !taken.contains(&candidate.as_str()))
        .unwrap_or_else(|| name.to_owned())
}

/// Speichert ein Layout. Fertige Layouts gelten danach als „geändert“
/// (Kategorie bleibt), eigene müssen schon existieren oder es ist Platz frei.
pub async fn save(paths: &Paths, mut layout: Layout) -> Result<StoredLayout> {
    layout.name = layout.name.trim().to_owned();
    layout.builtin_rev = None;
    // Bei fertigen Layouts bleibt die Kategorie; sonst entscheidet nur die ID.
    if let Some(original) = builtin(&layout.id) {
        layout.profile = original.profile;
    }
    validate(&layout)?;
    let _guard = WRITE_LOCK.lock().await;
    let file = layout_file(paths, &layout.id)?;
    if !is_builtin(&layout.id) && read_layout(&file).await.is_none() {
        let all = read_all(paths).await?;
        if custom_count(&all) >= MAX_LAYOUTS {
            return Err(too_many());
        }
    }
    fsutil::write_json(&file, &layout).await?;
    Ok(stored(layout))
}

/// Neues eigenes Layout aus einem vorhandenen (Kopie) oder aus PvP.
pub async fn duplicate(paths: &Paths, from: &str, name: &str) -> Result<StoredLayout> {
    let source = get(paths, from).await?.layout;
    add_new(paths, source, Some(name)).await
}

/// Legt `layout` mit neuer ID und freiem Namen an.
async fn add_new(paths: &Paths, mut layout: Layout, name: Option<&str>) -> Result<StoredLayout> {
    let _guard = WRITE_LOCK.lock().await;
    let all = read_all(paths).await?;
    if custom_count(&all) >= MAX_LAYOUTS {
        return Err(too_many());
    }
    let taken: Vec<&str> = all.iter().map(|l| l.name.as_str()).collect();
    let wanted = name.map(str::trim).filter(|n| !n.is_empty()).unwrap_or(&layout.name).to_owned();
    layout.name = unique_name(&wanted, &taken);
    layout.id = new_id();
    layout.builtin_rev = None;
    validate(&layout)?;
    fsutil::write_json(&layout_file(paths, &layout.id)?, &layout).await?;
    Ok(stored(layout))
}

/// Löscht ein eigenes Layout. Fertige lassen sich nur zurücksetzen.
pub async fn delete(paths: &Paths, id: &str) -> Result<()> {
    if is_builtin(id) {
        return Err(builtin_read_only());
    }
    let file = layout_file(paths, id)?;
    let _guard = WRITE_LOCK.lock().await;
    match tokio::fs::remove_file(&file).await {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Err(not_found()),
        Err(e) => Err(Error::io(&file, e)),
    }
}

/// Fertiges Layout auf den Auslieferungsstand zurücksetzen.
pub async fn reset(paths: &Paths, id: &str) -> Result<StoredLayout> {
    let layout = builtin(id).ok_or_else(not_found)?;
    let _guard = WRITE_LOCK.lock().await;
    fsutil::write_json(&layout_file(paths, id)?, &layout).await?;
    Ok(stored(layout))
}

pub async fn export_code(paths: &Paths, id: &str) -> Result<String> {
    encode_code(&get(paths, id).await?.layout)
}

/// Code einfügen → neues eigenes Layout.
pub async fn import_code(paths: &Paths, code: &str) -> Result<StoredLayout> {
    let layout = decode_code(code)?;
    add_new(paths, layout, None).await
}

/// Inhalt + Dateiname für „Als Datei speichern“.
pub async fn export_file(paths: &Paths, id: &str) -> Result<(String, Vec<u8>)> {
    let mut layout = get(paths, id).await?.layout;
    layout.builtin_rev = None;
    let bytes = serde_json::to_vec_pretty(&layout).map_err(|e| Error::Internal(e.to_string()))?;
    Ok((export_file_name(&layout.name), bytes))
}

fn export_file_name(name: &str) -> String {
    let safe: String = name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '-' })
        .collect();
    let safe = safe.trim_matches('-');
    format!("trs-controls-{}.json", if safe.is_empty() { "layout" } else { safe })
}

/// Datei öffnen → neues eigenes Layout (höchstens [`MAX_IMPORT_BYTES`]).
pub async fn import_file(paths: &Paths, file: &Path) -> Result<StoredLayout> {
    let size = tokio::fs::metadata(file).await.map_err(|e| Error::io(file, e))?.len();
    if size > MAX_IMPORT_BYTES {
        return Err(too_large());
    }
    let bytes = tokio::fs::read(file).await.map_err(|e| Error::io(file, e))?;
    let layout = parse(&bytes)?;
    add_new(paths, layout, None).await
}

/// Welches Layout beim Start gilt: das der Instanz, wenn es (noch) da ist,
/// sonst PvP. Stellt sicher, dass die Datei für das Overlay existiert.
pub async fn resolve(paths: &Paths, wanted: Option<&str>) -> Result<String> {
    {
        let _guard = WRITE_LOCK.lock().await;
        ensure_builtins(paths).await?;
    }
    if let Some(id) = wanted.filter(|id| is_valid_id(id))
        && read_layout(&layout_file(paths, id)?).await.is_some()
    {
        return Ok(id.to_owned());
    }
    Ok(DEFAULT_ID.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn paths() -> (tempfile::TempDir, Paths) {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path());
        (dir, paths)
    }

    fn profile_id(profile: Profile) -> &'static str {
        match profile {
            Profile::Pvp => "pvp",
            Profile::Build => "build",
            Profile::Redstone => "redstone",
            Profile::Custom => "custom",
        }
    }

    fn rects_overlap(a: &Button, b: &Button) -> bool {
        a.x < b.x + b.w - EPS && b.x < a.x + a.w - EPS && a.y < b.y + b.h - EPS && b.y < a.y + a.h - EPS
    }

    #[test]
    fn builtins_are_valid_and_tidy() {
        let all = builtins();
        assert_eq!(all.len(), 3);
        for layout in &all {
            validate(layout).unwrap();
            assert_eq!(layout.builtin_rev, Some(BUILTIN_REV), "{}", layout.id);
            assert_eq!(profile_id(layout.profile), layout.id);
            // Keine Knöpfe übereinander – sonst verdeckt einer den anderen.
            for (i, a) in layout.buttons.iter().enumerate() {
                for b in &layout.buttons[i + 1..] {
                    assert!(!rects_overlap(a, b), "{}: {} überlappt {}", layout.id, a.id, b.id);
                }
            }
            // Jedes Layout kann laufen, ins Menü und schreiben.
            let has = |f: &dyn Fn(&Action) -> bool| layout.buttons.iter().any(|b| f(&b.action));
            assert!(has(&|a| matches!(a, Action::Joystick { mode: JoystickMode::Wasd })));
            assert!(has(&|a| matches!(a, Action::Special { special: Special::Menu })));
            assert!(has(&|a| matches!(a, Action::Special { special: Special::Keyboard })));
            assert!(has(&|a| matches!(a, Action::Special { special: Special::HotbarSwipe })));
        }
        let redstone = builtin("redstone").unwrap();
        let keys: Vec<i32> = redstone
            .buttons
            .iter()
            .filter_map(|b| match &b.action {
                Action::Key { key, .. } => Some(*key),
                _ => None,
            })
            .collect();
        // F6 (Redstone-Overlay), F8 (alte Versionen) und F3.
        assert!(keys.contains(&295) && keys.contains(&297) && keys.contains(&292));
        assert!(!builtin("pvp").unwrap().gestures.hold_use || builtin("pvp").unwrap().gestures.tap_attack);
        assert!(!builtin("build").unwrap().gestures.tap_attack);
    }

    #[test]
    fn validation_rejects_bad_layouts() {
        let good = builtin("pvp").unwrap();
        let bad = |f: &dyn Fn(&mut Layout)| {
            let mut l = good.clone();
            f(&mut l);
            validate(&l).is_err()
        };
        assert!(bad(&|l| l.version = 2));
        assert!(bad(&|l| l.version = 0));
        assert!(bad(&|l| l.id = "../x".into()));
        assert!(bad(&|l| l.id = "Groß".into()));
        assert!(bad(&|l| l.name = " ".into()));
        assert!(bad(&|l| l.name = "x".repeat(MAX_NAME_CHARS + 1)));
        assert!(bad(&|l| l.buttons.clear()));
        assert!(bad(&|l| l.buttons[0].x = 0.95));
        assert!(bad(&|l| l.buttons[0].w = f64::NAN));
        assert!(bad(&|l| l.buttons[0].h = 0.001));
        assert!(bad(&|l| l.buttons[0].opacity = 0.0));
        assert!(bad(&|l| l.buttons[1].id = l.buttons[0].id.clone()));
        assert!(bad(&|l| l.buttons[1].id = "a b".into()));
        assert!(bad(&|l| l.buttons[1].label = Some("x".repeat(13))));
        assert!(bad(&|l| {
            l.buttons[1].label = None;
            l.buttons[1].icon = None;
        }));
        assert!(bad(&|l| l.buttons[1].action = Action::Key { key: 5, chord: vec![] }));
        assert!(bad(&|l| l.buttons[1].action = Action::Key { key: 71, chord: vec![71] }));
        assert!(bad(&|l| l.buttons[1].action = Action::Key { key: 71, chord: vec![292, 292] }));
        assert!(bad(&|l| l.buttons[1].action = Action::Mouse { button: 9, chord: vec![] }));
        assert!(bad(&|l| l.gestures.camera_sensitivity = 0.0));
        assert!(bad(&|l| l.gestures.camera_sensitivity = 99.0));
        let mut many = good.clone();
        many.buttons = (0..=MAX_BUTTONS)
            .map(|i| Button { id: format!("b{i}"), ..good.buttons[1].clone() })
            .collect();
        assert!(validate(&many).is_err());
        // Neuere Version: eigener Hinweis.
        let mut newer = good.clone();
        newer.version = 2;
        assert!(matches!(validate(&newer), Err(Error::Validation(m)) if m.code == "controls.newerVersion"));
    }

    #[test]
    fn parse_ignores_unknown_fields_and_fills_defaults() {
        let json = r#"{"version":1,"id":"x","name":"X","profile":"custom","future":true,
            "buttons":[{"id":"a","label":"A","x":0,"y":0,"w":0.1,"h":0.1,"action":{"type":"key","key":65},"extra":1}],
            "gestures":{"tapAttack":true,"holdUse":false,"swipeHotbar":false,"cameraSensitivity":1}}"#;
        let l = parse(json.as_bytes()).unwrap();
        assert_eq!(l.buttons[0].opacity, 0.6);
        assert_eq!(l.buttons[0].shape, Shape::Round);
        assert!(l.gestures.haptics);
        // Unbekannter Aktionstyp = ungültig, nicht stillschweigend etwas anderes.
        assert!(parse(json.replace("\"key\",\"key\":65", "\"warp\"").as_bytes()).is_err());
        assert!(parse(b"not json").is_err());
        assert!(parse(&vec![b' '; MAX_IMPORT_BYTES as usize + 1]).is_err());
    }

    #[test]
    fn code_round_trip() {
        let pvp = builtin("pvp").unwrap();
        let code = encode_code(&pvp).unwrap();
        assert!(code.starts_with(CODE_PREFIX));
        // Kurz genug für einen Chat.
        assert!(code.len() < 2500, "{}", code.len());
        let back = decode_code(&format!("  {}\n{} ", &code[..20], &code[20..])).unwrap();
        assert_eq!(back.buttons, pvp.buttons);
        assert_eq!(back.builtin_rev, None);
        assert!(decode_code("TRSC1-!!!").is_err());
        assert!(decode_code("hello").is_err());
        assert!(decode_code(&format!("{CODE_PREFIX}{}", URL_SAFE_NO_PAD.encode(b"xx"))).is_err());
        // Entpackt riesig: wird abgelehnt, ohne alles zu entpacken.
        let mut enc = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
        enc.write_all(&vec![b'a'; 10 * MAX_IMPORT_BYTES as usize]).unwrap();
        let bomb = format!("{CODE_PREFIX}{}", URL_SAFE_NO_PAD.encode(enc.finish().unwrap()));
        assert!(decode_code(&bomb).is_err());
    }

    #[test]
    fn names_and_ids() {
        assert_eq!(unique_name("A", &["B"]), "A");
        assert_eq!(unique_name("A", &["A", "A (2)"]), "A (3)");
        assert!(is_valid_id("custom-0a1b2c3d4e") && is_valid_id(&new_id()));
        assert!(!is_valid_id("") && !is_valid_id("-x") && !is_valid_id("a.json") && !is_valid_id(&"a".repeat(41)));
        assert_eq!(export_file_name("Mein PvP!"), "trs-controls-Mein-PvP.json");
        assert_eq!(export_file_name("ÄÖ"), "trs-controls-layout.json");
    }

    #[tokio::test]
    async fn store_round_trip() {
        let (_dir, paths) = paths();
        let listed = list(&paths).await.unwrap();
        let ids: Vec<&str> = listed.iter().map(|s| s.layout.id.as_str()).collect();
        assert_eq!(ids, ["pvp", "build", "redstone"]);
        assert!(listed.iter().all(|s| s.builtin && !s.modified));
        // Dateien liegen für das Overlay bereit.
        assert!(dir(&paths).join("redstone.json").exists());

        // Fertiges Layout ändern → „geändert“, bleibt auch über ensure_builtins.
        let mut pvp = listed[0].layout.clone();
        pvp.buttons[0].x = 0.05;
        pvp.profile = Profile::Custom;
        let saved = save(&paths, pvp).await.unwrap();
        assert!(saved.modified && saved.layout.profile == Profile::Pvp);
        ensure_builtins(&paths).await.unwrap();
        assert_eq!(get(&paths, "pvp").await.unwrap().layout.buttons[0].x, 0.05);
        assert!(delete(&paths, "pvp").await.is_err());
        let reset_back = reset(&paths, "pvp").await.unwrap();
        assert!(!reset_back.modified && reset_back.layout.buttons[0].x == 0.03);

        // Kopie, umbenennen, löschen.
        let copy = duplicate(&paths, "redstone", "Mein Redstone").await.unwrap();
        assert!(copy.layout.id.starts_with("custom-") && !copy.builtin);
        assert_eq!(copy.layout.profile, Profile::Redstone);
        let again = duplicate(&paths, "redstone", "Mein Redstone").await.unwrap();
        assert_eq!(again.layout.name, "Mein Redstone (2)");
        let mut renamed = copy.layout.clone();
        renamed.name = "Neu".into();
        assert_eq!(save(&paths, renamed).await.unwrap().layout.name, "Neu");
        assert_eq!(list(&paths).await.unwrap().len(), 5);
        delete(&paths, &copy.layout.id).await.unwrap();
        assert!(delete(&paths, &copy.layout.id).await.is_err());
        assert!(get(&paths, "custom-unknown").await.is_err());

        // Code und Datei.
        let code = export_code(&paths, "build").await.unwrap();
        let imported = import_code(&paths, &code).await.unwrap();
        assert!(!imported.builtin && imported.layout.name == "Build (2)");
        let (name, bytes) = export_file(&paths, &imported.layout.id).await.unwrap();
        assert!(name.ends_with(".json"));
        let file = paths.root().join("share.json");
        std::fs::write(&file, &bytes).unwrap();
        let from_file = import_file(&paths, &file).await.unwrap();
        assert_ne!(from_file.layout.id, imported.layout.id);

        // Fremde Dateien im Ordner stören nicht.
        std::fs::write(dir(&paths).join("kaputt.json"), b"{").unwrap();
        std::fs::write(dir(&paths).join("notes.txt"), b"x").unwrap();
        let mut other = builtin("pvp").unwrap();
        other.id = "falsch".into();
        std::fs::write(dir(&paths).join("anders.json"), serde_json::to_vec(&other).unwrap()).unwrap();
        assert_eq!(list(&paths).await.unwrap().len(), 6);
    }

    #[tokio::test]
    async fn stale_builtins_are_refreshed_and_resolve_falls_back() {
        let (_dir, paths) = paths();
        let mut old = builtin("build").unwrap();
        old.builtin_rev = Some(0);
        old.name = "Alt".into();
        fsutil::write_json(&layout_file(&paths, "build").unwrap(), &old).await.unwrap();
        std::fs::write(layout_file(&paths, "pvp").unwrap(), b"kaputt").unwrap();
        ensure_builtins(&paths).await.unwrap();
        assert_eq!(get(&paths, "build").await.unwrap().layout.name, "Build");
        assert!(get(&paths, "pvp").await.is_ok());

        assert_eq!(resolve(&paths, None).await.unwrap(), DEFAULT_ID);
        assert_eq!(resolve(&paths, Some("redstone")).await.unwrap(), "redstone");
        assert_eq!(resolve(&paths, Some("custom-weg")).await.unwrap(), DEFAULT_ID);
        assert_eq!(resolve(&paths, Some("../../etc")).await.unwrap(), DEFAULT_ID);
    }

    #[tokio::test]
    async fn custom_limit() {
        let (_dir, paths) = paths();
        for i in 0..MAX_LAYOUTS {
            duplicate(&paths, "pvp", &format!("L{i}")).await.unwrap();
        }
        assert!(duplicate(&paths, "pvp", "zu viel").await.is_err());
        let mut fresh = builtin("pvp").unwrap();
        fresh.id = "custom-neu".into();
        assert!(save(&paths, fresh).await.is_err());
    }
}
