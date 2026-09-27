//! Text-Auswertung für den Absturz-Helfer: Log-Präfixe entfernen, Mod-Listen
//! aus Log und Crash-Report lesen, Ausnahmen und Stackzeilen finden und
//! Klassen/Mixin-Konfigurationen den Mods der Instanz zuordnen.

use super::{InstalledMod, ModRef};

/// So viele Log-Zeilen werden höchstens ausgewertet (das Ende zählt).
const MAX_LOG_LINES: usize = 20_000;
const MAX_REPORT_LINES: usize = 6_000;
const MAX_LINE_CHARS: usize = 4_000;
/// Länge angezeigter Zeilen.
const SHOWN_CHARS: usize = 300;
const MAX_EXCERPT: usize = 12;

/// Kürzt Text auf `max` Zeichen (ohne Steuerzeichen außer Tab).
pub(crate) fn clip(text: &str, max: usize) -> String {
    let mut out: String = text.chars().filter(|c| *c == '\t' || !c.is_control()).take(max + 1).collect();
    if out.chars().count() > max {
        out = out.chars().take(max.saturating_sub(1)).collect();
        out.push('…');
    }
    out.trim().to_owned()
}

/// Maskiert eine Zeile für die Anzeige: Pfade mit Benutzernamen, Tokens
/// (wie beim Log-Teilen) und den Spielernamen.
pub(crate) fn mask(line: &str) -> String {
    let mut out = crate::process::redact(line, &[]);
    for marker in ["Setting user: ", "--username, ", "--username "] {
        if let Some(at) = out.find(marker) {
            let start = at + marker.len();
            let end = out[start..].find([' ', ',', ']', ')']).map_or(out.len(), |i| start + i);
            if end > start {
                out.replace_range(start..end, "<player>");
            }
        }
    }
    clip(&out, SHOWN_CHARS)
}

/// `[12:00:00] [Render thread/ERROR]: text` bzw. mit drittem Block
/// (`[… /LOADING]`) → `text`. Andere Zeilen bleiben, wie sie sind.
pub(crate) fn strip_log_prefix(line: &str) -> &str {
    if !line.starts_with('[') {
        return line;
    }
    let mut rest = line;
    let mut groups = 0;
    while groups < 4 && rest.starts_with('[') {
        let Some(end) = rest.find(']') else { return line };
        rest = &rest[end + 1..];
        groups += 1;
        if let Some(r) = rest.strip_prefix(' ')
            && r.starts_with('[')
        {
            rest = r;
        }
    }
    match rest.strip_prefix(": ").or_else(|| rest.strip_prefix(':')) {
        Some(text) if groups >= 2 => text,
        _ => line,
    }
}

pub(crate) fn is_mod_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id.bytes().next().is_some_and(|b| b.is_ascii_alphanumeric())
        && id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.'))
}

/// IDs, die keine „echten“ Mods sind – nie als Schuldige nennen.
pub(crate) fn is_platform_id(id: &str) -> bool {
    matches!(
        id,
        "minecraft"
            | "java"
            | "fabricloader"
            | "fabric-loader"
            | "quilt_loader"
            | "forge"
            | "neoforge"
            | "fml"
            | "javafml"
            | "lowcodefml"
            | "mixin"
            | "mcp"
    )
}

/// Loader-IDs (für „falsche Loader-Version“).
pub(crate) fn is_loader_id(id: &str) -> bool {
    matches!(id, "fabricloader" | "fabric-loader" | "quilt_loader" | "forge" | "neoforge" | "fml" | "javafml")
}

/// Pakete, die zu Java, Minecraft, Loadern oder allgemeinen Bibliotheken
/// gehören – dort sucht man keinen Schuldigen.
const FRAMEWORK: &[&str] = &[
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "com.sun.",
    "net.minecraft.",
    "com.mojang.",
    "net.fabricmc.",
    "org.quiltmc.",
    "org.spongepowered.",
    "cpw.mods.",
    "net.minecraftforge.",
    "net.neoforged.",
    "org.lwjgl.",
    "io.netty.",
    "com.google.",
    "org.apache.",
    "it.unimi.",
    "org.objectweb.",
    "kotlin.",
    "kotlinx.",
    "scala.",
    "org.slf4j.",
    "com.electronwill.",
    "org.jetbrains.",
    "oshi.",
    "com.llamalad7.mixinextras.",
    "com.bawnorton.mixinsquared.",
    "me.fallenbreath.conditionalmixin.",
    "net.lenni0451.",
    "org.joml.",
    "com.github.benmanes.",
    "org.jline.",
    "joptsimple.",
    "Main.",
];

pub(crate) fn is_framework_class(class: &str) -> bool {
    FRAMEWORK.iter().any(|p| class.starts_with(p)) || !class.contains('.')
}

/// Bekannte Pakete, falls die Datei selbst nicht zugeordnet werden kann
/// (z. B. eingebettete Bibliotheken).
const KNOWN_PACKAGES: &[(&str, &str)] = &[
    ("com.llamalad7.mixinextras", "mixinextras"),
    ("gg.essential", "essential"),
    ("dev.theredstonee.trsclient", "trsclient"),
    ("me.jellysquid.mods.sodium", "sodium"),
    ("net.caffeinemc.mods.sodium", "sodium"),
    ("me.jellysquid.mods.lithium", "lithium"),
    ("net.caffeinemc.mods.lithium", "lithium"),
    ("net.coderbot.iris", "iris"),
    ("net.irisshaders.iris", "iris"),
    ("net.optifine", "optifine"),
    ("optifine", "optifine"),
    ("me.modmuss50.optifabric", "optifabric"),
    ("xaero.common", "xaerominimap"),
    ("xaero.minimap", "xaerominimap"),
    ("xaero.map", "xaeroworldmap"),
    ("journeymap", "journeymap"),
    ("com.mamiyaotaru.voxelmap", "voxelmap"),
    ("mezz.jei", "jei"),
    ("me.shedaniel.clothconfig2", "cloth-config"),
    ("dev.tr7zw.entityculling", "entityculling"),
    ("com.terraformersmc.modmenu", "modmenu"),
];

/// Anzeigenamen, wenn die Mod nicht installiert ist.
const KNOWN_NAMES: &[(&str, &str)] = &[
    ("mixinextras", "MixinExtras"),
    ("essential", "Essential"),
    ("trsclient", "TRS Client"),
    ("sodium", "Sodium"),
    ("iris", "Iris"),
    ("optifine", "OptiFine"),
    ("optifabric", "OptiFabric"),
    ("fabric-api", "Fabric API"),
    ("cloth-config", "Cloth Config"),
    ("xaerominimap", "Xaero's Minimap"),
    ("journeymap", "JourneyMap"),
    ("voxelmap", "VoxelMap"),
    ("minecraft", "Minecraft"),
    ("fabricloader", "Fabric Loader"),
    ("forge", "Forge"),
    ("neoforge", "NeoForge"),
    ("java", "Java"),
];

pub(crate) fn known_name(id: &str) -> Option<&'static str> {
    KNOWN_NAMES.iter().find(|(k, _)| *k == id).map(|(_, v)| *v)
}

/// Eine Mod aus der Mod-Liste im Log bzw. Crash-Report.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ListedMod {
    pub id: String,
    pub name: Option<String>,
    pub version: Option<String>,
    /// Eingebettet in (Jar-in-Jar).
    pub parent: Option<String>,
}

/// Eine Stackzeile (`at a.b.C.m(C.java:1)`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Frame {
    pub line: String,
    pub class: String,
    pub method: String,
    /// (Neo)Forge nennt die Mod im Modul-Pfad: `TRANSFORMER/jei@15.2.0/…`.
    pub module_mod: Option<String>,
}

impl Frame {
    fn parse(line: &str) -> Option<Self> {
        let rest = line.trim_start().strip_prefix("at ")?;
        let target = rest.split('(').next()?.trim();
        let mut parts: Vec<&str> = target.split('/').collect();
        let last = parts.pop()?;
        let (class, method) = last.rsplit_once('.')?;
        let module_mod = parts.iter().filter_map(|p| p.split_once('@').map(|(id, _)| id)).find(|id| is_mod_id(id) && !is_platform_id(id)).map(str::to_owned);
        Some(Self { line: line.trim().to_owned(), class: class.to_owned(), method: method.to_owned(), module_mod })
    }

    /// Mixin-Handler mit Mod-ID im Namen (Fabric): `handler$zza000$sodium$onInit`.
    pub fn handler_mod(&self) -> Option<String> {
        handler_mod(&self.method)
    }

    /// Gehört nicht zu Java/Minecraft/Loader – oder ist ein Mixin-Handler einer Mod.
    pub fn is_own(&self) -> bool {
        self.module_mod.is_some() || self.handler_mod().is_some() || !is_framework_class(&self.class)
    }
}

/// `handler$zza000$sodium$onInit` → `sodium`.
pub(crate) fn handler_mod(method: &str) -> Option<String> {
    let mut parts = method.split('$');
    let kind = parts.next()?;
    let hash = parts.next()?;
    let id = parts.next()?;
    parts.next()?;
    let hash_ok = hash.len() == 6 && hash.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit());
    (hash_ok && kind.bytes().all(|b| b.is_ascii_alphabetic()) && is_mod_id(id) && !is_platform_id(id)).then(|| id.to_owned())
}

/// Log + Crash-Report, zeilenweise und ohne Log-Präfixe.
pub(crate) struct Text {
    pub lines: Vec<String>,
    pub all: String,
    pub listed: Vec<ListedMod>,
}

fn normalized_lines(text: &str, max: usize, from_end: bool) -> Vec<String> {
    let lines: Vec<&str> = text.lines().collect();
    let slice = if from_end { &lines[lines.len().saturating_sub(max)..] } else { &lines[..lines.len().min(max)] };
    slice
        .iter()
        .map(|l| {
            let l = l.trim_start_matches('\u{feff}').trim_end();
            let l = strip_log_prefix(l);
            if l.len() > MAX_LINE_CHARS {
                let mut end = MAX_LINE_CHARS;
                while !l.is_char_boundary(end) {
                    end -= 1;
                }
                l[..end].to_owned()
            } else {
                l.to_owned()
            }
        })
        .collect()
}

impl Text {
    pub fn new(log: &str, report: Option<&str>) -> Self {
        let mut lines = report.map(|r| normalized_lines(r, MAX_REPORT_LINES, false)).unwrap_or_default();
        lines.extend(normalized_lines(log, MAX_LOG_LINES, true));
        let all = lines.join("\n");
        let mut listed = Vec::new();
        parse_fabric_log_list(&lines, &mut listed);
        parse_fabric_report_list(&lines, &mut listed);
        parse_forge_report_list(&lines, &mut listed);
        Self { lines, all, listed }
    }

    pub fn has(&self, needle: &str) -> bool {
        self.all.contains(needle)
    }

    pub fn has_any(&self, needles: &[&str]) -> bool {
        needles.iter().any(|n| self.all.contains(n))
    }

    /// Erste Zeile mit einem der Suchbegriffe.
    pub fn line_with(&self, needles: &[&str]) -> Option<&str> {
        self.lines.iter().map(String::as_str).find(|l| needles.iter().any(|n| l.contains(n)))
    }

    pub fn lines_with<'s>(&'s self, needles: &'s [&'s str]) -> impl Iterator<Item = &'s str> + 's {
        self.lines.iter().map(String::as_str).filter(move |l| needles.iter().any(|n| l.contains(n)))
    }

    fn cleaned(&self) -> impl Iterator<Item = (usize, &str)> {
        self.lines.iter().enumerate().map(|(i, l)| (i, l.trim()))
    }

    /// Zeilen, die eine Ausnahme einleiten („Caused by: …“, „java.lang.X: …“).
    fn exception_headers(&self) -> Vec<(usize, &str)> {
        self.cleaned().filter(|(_, l)| is_exception_header(l)).collect()
    }

    /// Hauptursache: das letzte „Caused by“, sonst die erste Ausnahme, sonst
    /// die Beschreibung des Crash-Reports.
    pub fn root_cause(&self) -> Option<&str> {
        let headers = self.exception_headers();
        if let Some((_, l)) = headers.iter().rev().find(|(_, l)| l.starts_with("Caused by: ")) {
            return Some(l);
        }
        if let Some((_, l)) = headers.first() {
            return Some(l);
        }
        self.cleaned().map(|(_, l)| l).find(|l| l.starts_with("Description: ") || l.starts_with("# Problematic frame"))
    }

    fn root_cause_index(&self) -> Option<usize> {
        let headers = self.exception_headers();
        headers.iter().rev().find(|(_, l)| l.starts_with("Caused by: ")).or(headers.first()).map(|(i, _)| *i)
    }

    pub fn frames(&self) -> impl Iterator<Item = Frame> + '_ {
        self.lines.iter().filter_map(|l| Frame::parse(l))
    }

    /// Erste „eigene“ Stackzeile der Hauptursache, sonst die erste im ganzen Text.
    pub fn first_own_frame(&self) -> Option<Frame> {
        if let Some(start) = self.root_cause_index() {
            let own = self.lines[start + 1..]
                .iter()
                .take_while(|l| {
                    let t = l.trim_start();
                    t.starts_with("at ") || t.starts_with("...") || t.is_empty()
                })
                .filter_map(|l| Frame::parse(l))
                .find(Frame::is_own);
            if own.is_some() {
                return own;
            }
        }
        self.frames().find(Frame::is_own)
    }

    /// Die wichtigsten Zeilen für „Details“ (maskiert).
    pub fn excerpt(&self) -> Vec<String> {
        const MARKERS: &[&str] = &[
            "Description: ",
            "Mixin transformation of",
            "@Redirect conflict",
            "Critical injection failure",
            "which is missing",
            "wrong version is present",
            "is incompatible with",
            "Missing or unsupported mandatory dependencies",
            "[MISSING]",
            "from mod files",
            "EXCEPTION_ACCESS_VIOLATION",
            "Problematic frame",
            "# C  [",
            "GLFW error",
            "Pixel format not accelerated",
            "Could not reserve enough space",
            "Error analyzing",
            "Suspected Mod",
        ];
        let mut out: Vec<String> = Vec::new();
        let push = |line: &str, out: &mut Vec<String>| {
            let masked = mask(line);
            if !masked.is_empty() && !out.contains(&masked) && out.len() < MAX_EXCERPT {
                out.push(masked);
            }
        };
        for (_, line) in self.cleaned() {
            if MARKERS.iter().any(|m| line.contains(m)) || is_exception_header(line) {
                push(line, &mut out);
            }
        }
        if let Some(frame) = self.first_own_frame() {
            push(&frame.line, &mut out);
        }
        out
    }
}

/// `java.lang.IllegalStateException: …`, `Caused by: …`, `Exception in thread …`.
pub(crate) fn is_exception_header(line: &str) -> bool {
    if line.starts_with("Exception in thread ") {
        return true;
    }
    let rest = line.strip_prefix("Caused by: ").unwrap_or(line);
    let name = rest.split([':', ' ']).next().unwrap_or("");
    if !name.contains('.') || name.starts_with('.') || name.ends_with('.') {
        return false;
    }
    let simple = name.rsplit(['.', '$']).next().unwrap_or("");
    let is_type = simple.ends_with("Exception") || simple.ends_with("Error") || simple.ends_with("Throwable");
    is_type && name.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'$' | b'_'))
}

/// „id version“ bzw. „id: Name version“.
fn split_id_version(text: &str) -> Option<(String, Option<String>)> {
    let mut parts = text.split_whitespace();
    let id = parts.next()?.trim_end_matches(':');
    is_mod_id(id).then(|| (id.to_owned(), parts.next_back().map(str::to_owned)))
}

/// Fabric-Log: `Loading 65 mods:` gefolgt von `\t- id version` und
/// eingebetteten `\t   |-- id version` / `\t   \-- id version`.
fn parse_fabric_log_list(lines: &[String], out: &mut Vec<ListedMod>) {
    let Some(start) = lines.iter().position(|l| {
        let t = l.trim();
        t.starts_with("Loading ") && t.ends_with(" mods:")
    }) else {
        return;
    };
    let mut top: Option<String> = None;
    for line in &lines[start + 1..] {
        if !line.starts_with(['\t', ' ']) {
            break;
        }
        let t = line.trim();
        if let Some(entry) = t.strip_prefix("- ") {
            if let Some((id, version)) = split_id_version(entry) {
                top = Some(id.clone());
                out.push(ListedMod { id, name: None, version, parent: None });
            }
        } else if t.starts_with('|') || t.starts_with('\\') {
            let entry = t.trim_start_matches(['|', '\\', '-', ' ']);
            if let Some((id, version)) = split_id_version(entry) {
                out.push(ListedMod { id, name: None, version, parent: top.clone() });
            }
        } else {
            break;
        }
    }
}

/// Fabric-Crash-Report: `Fabric Mods:` – zwei Tabs = Mod, mehr = eingebettet;
/// Zeilen `id: Name Version`.
fn parse_fabric_report_list(lines: &[String], out: &mut Vec<ListedMod>) {
    let Some(start) = lines.iter().position(|l| l.trim() == "Fabric Mods:" || l.trim() == "Quilt Mods:") else { return };
    let mut top: Option<String> = None;
    for line in &lines[start + 1..] {
        let tabs = line.bytes().take_while(|b| *b == b'\t').count();
        if tabs < 2 || line.trim().is_empty() {
            break;
        }
        let Some((id, rest)) = line.trim().split_once(": ") else { continue };
        if !is_mod_id(id) {
            continue;
        }
        let (name, version) = match rest.trim().rsplit_once(' ') {
            Some((name, version)) => (Some(name.to_owned()), Some(version.to_owned())),
            None => (Some(rest.trim().to_owned()), None),
        };
        let parent = if tabs == 2 {
            top = Some(id.to_owned());
            None
        } else {
            top.clone()
        };
        out.push(ListedMod { id: id.to_owned(), name, version, parent });
    }
}

/// (Neo)Forge-Crash-Report: `Mod List:` mit `datei.jar |Name |id |version |…`.
fn parse_forge_report_list(lines: &[String], out: &mut Vec<ListedMod>) {
    let Some(start) = lines.iter().position(|l| l.trim() == "Mod List:") else { return };
    for line in &lines[start + 1..] {
        if !line.contains('|') {
            break;
        }
        let cells: Vec<&str> = line.split('|').map(str::trim).collect();
        if cells.len() < 4 || !is_mod_id(cells[2]) {
            continue;
        }
        out.push(ListedMod {
            id: cells[2].to_owned(),
            name: Some(cells[1].to_owned()).filter(|n| !n.is_empty()),
            version: Some(cells[3].to_owned()).filter(|v| !v.is_empty()),
            parent: None,
        });
    }
}

/// Mixin-Konfigurationen in einer Zeile (`sodium.mixins.json`, `mixins.jei.json`).
pub(crate) fn mixin_configs(line: &str) -> Vec<String> {
    line.split(|c: char| !(c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-')))
        .filter(|t| t.ends_with(".json") && t.contains("mixins"))
        .map(|t| t.trim_start_matches('.').to_owned())
        .collect()
}

/// `… from mod sodium]` → `sodium` (alle Vorkommen).
pub(crate) fn from_mod(line: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut rest = line;
    while let Some(at) = rest.find("from mod ") {
        rest = &rest[at + 9..];
        let id: String = rest.chars().take_while(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.')).collect();
        // `from mod betterchat->@Redirect…`: Pfeil gehört nicht zur ID.
        let id = id.trim_end_matches(['.', '-', '_']).to_owned();
        if is_mod_id(&id) && !is_platform_id(&id) && !out.contains(&id) {
            out.push(id);
        }
    }
    out
}

/// Ordnet IDs, Klassen, Dateien und Mixin-Konfigurationen den Mods zu.
pub(crate) struct ModIndex<'a> {
    installed: &'a [InstalledMod],
    listed: Vec<ListedMod>,
}

/// OptiFine hat kein `fabric.mod.json` – am Dateinamen erkennen.
fn guessed_id(m: &InstalledMod) -> Option<String> {
    let lower = m.file_name.to_ascii_lowercase();
    (m.id.is_none() && lower.contains("optifine")).then(|| "optifine".to_owned())
}

impl<'a> ModIndex<'a> {
    pub fn new(installed: &'a [InstalledMod], text: &Text) -> Self {
        Self { installed, listed: text.listed.clone() }
    }

    fn ids_of(m: &InstalledMod) -> Vec<String> {
        let mut ids: Vec<String> = m.ids().map(str::to_owned).collect();
        ids.extend(guessed_id(m));
        ids
    }

    /// Installierte Mod mit dieser ID (aktivierte bevorzugt).
    pub fn installed(&self, id: &str) -> Option<&'a InstalledMod> {
        let has = |m: &&InstalledMod| Self::ids_of(m).iter().any(|i| i == id);
        self.installed.iter().filter(|m| m.enabled).find(has).or_else(|| self.installed.iter().find(has))
    }

    pub fn enabled_installed(&self) -> impl Iterator<Item = &'a InstalledMod> {
        self.installed.iter().filter(|m| m.enabled)
    }

    pub fn listed(&self, id: &str) -> Option<&ListedMod> {
        self.listed.iter().find(|l| l.id == id)
    }

    /// Ist die Mod im Spiel (aktiviert installiert, eingebettet oder laut Log geladen)?
    pub fn present(&self, id: &str) -> bool {
        self.installed(id).is_some_and(|m| m.enabled)
            || self.listed(id).is_some()
            || self.installed.iter().any(|m| m.enabled && m.nested.iter().any(|n| n == id))
    }

    /// Erste vorhandene ID, auf die `pred` passt.
    pub fn find_present(&self, pred: impl Fn(&str) -> bool) -> Option<String> {
        let installed = self.enabled_installed().flat_map(Self::ids_of);
        let listed = self.listed.iter().map(|l| l.id.clone());
        installed.chain(listed).find(|id| pred(id))
    }

    pub fn version_of(&self, id: &str) -> Option<String> {
        self.installed(id)
            .filter(|m| m.enabled)
            .and_then(|m| m.version.clone())
            .or_else(|| self.listed(id).and_then(|l| l.version.clone()))
    }

    /// Aktivierte Datei der Mod im Mods-Ordner.
    pub fn file_of(&self, id: &str) -> Option<String> {
        self.installed(id).filter(|m| m.enabled).map(|m| m.file_name.clone())
    }

    pub fn by_file(&self, file: &str) -> Option<&'a InstalledMod> {
        self.installed.iter().find(|m| m.file_name.eq_ignore_ascii_case(file))
    }

    /// Haupt-ID einer installierten Datei (oder der Dateiname).
    pub fn id_of(m: &InstalledMod) -> String {
        m.id.clone().or_else(|| guessed_id(m)).unwrap_or_else(|| m.file_name.clone())
    }

    /// Klasse → Mod: erst die Pakete der installierten Jars (eindeutig), dann bekannte Pakete.
    pub fn by_class(&self, class: &str) -> Option<String> {
        let class = class.replace('/', ".");
        let mut best: Option<(usize, &InstalledMod)> = None;
        let mut ambiguous = false;
        for m in self.installed {
            for p in &m.packages {
                if class.len() > p.len() && class.starts_with(p.as_str()) && class.as_bytes()[p.len()] == b'.' {
                    match best {
                        Some((len, other)) if len == p.len() && other.file_name != m.file_name => ambiguous = true,
                        Some((len, _)) if len >= p.len() => {}
                        _ => {
                            best = Some((p.len(), m));
                            ambiguous = false;
                        }
                    }
                }
            }
        }
        if let Some((_, m)) = best.filter(|_| !ambiguous) {
            return Some(Self::id_of(m));
        }
        KNOWN_PACKAGES
            .iter()
            .filter(|(p, _)| class.starts_with(p) && class.as_bytes().get(p.len()) == Some(&b'.'))
            .max_by_key(|(p, _)| p.len())
            .map(|(_, id)| (*id).to_owned())
    }

    /// Mixin-Konfiguration → Mod (aus dem Jar, sonst aus dem Namen).
    pub fn by_mixin_config(&self, config: &str) -> Option<String> {
        if let Some(m) = self.installed.iter().find(|m| m.mixin_configs.iter().any(|c| c == config)) {
            return Some(Self::id_of(m));
        }
        let stem = config.trim_end_matches(".json").replace(".mixins", "").replace("mixins.", "");
        let stem = stem.split(['.', '-', '_']).next().unwrap_or("");
        (!stem.is_empty() && (self.present(stem) || self.installed(stem).is_some())).then(|| stem.to_owned())
    }

    /// Anzeige-Infos einer Mod.
    pub fn resolve(&self, id: &str) -> ModRef {
        let listed = self.listed(id);
        let fallback_name = || listed.and_then(|l| l.name.clone()).or_else(|| known_name(id).map(str::to_owned)).unwrap_or_else(|| id.to_owned());
        if let Some(m) = self.installed(id) {
            return ModRef {
                id: id.to_owned(),
                name: m.name.clone().filter(|n| !n.is_empty()).unwrap_or_else(fallback_name),
                version: m.version.clone().or_else(|| listed.and_then(|l| l.version.clone())),
                file: Some(m.file_name.clone()),
                enabled: m.enabled,
                icon_url: m.icon_url.clone(),
                bundled_in: None,
            };
        }
        let host = self
            .installed
            .iter()
            .find(|m| m.enabled && m.nested.iter().any(|n| n == id))
            .map(|m| m.name.clone().unwrap_or_else(|| m.file_name.clone()))
            .or_else(|| {
                let parent = listed?.parent.as_deref()?;
                Some(self.resolve(parent).name)
            });
        ModRef {
            id: id.to_owned(),
            name: fallback_name(),
            version: listed.and_then(|l| l.version.clone()),
            file: None,
            enabled: true,
            icon_url: None,
            bundled_in: host,
        }
    }

    /// Anzeigename (für Übersetzungs-Parameter).
    pub fn name(&self, id: &str) -> String {
        self.resolve(id).name
    }
}
