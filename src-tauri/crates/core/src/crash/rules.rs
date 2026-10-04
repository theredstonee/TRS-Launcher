//! Die Regeln des Absturz-Helfers. Jede Regel liest den Text (und die
//! Mods der Instanz) und liefert Befunde mit Punktzahl – der höchste ist
//! die Hauptursache. Neue Regeln: Funktion schreiben, in [`run`] eintragen,
//! Fixture + Test in `tests.rs`, Texte in `app/locales/*.json`
//! (`crashHelper.kinds.<kind>[.<variant>]`).

use super::parse::{self, Frame, ModIndex, Text, is_loader_id, is_mod_id, is_platform_id};
use super::{CrashAction, CrashInput, CrashKind, Finding};
use crate::client_mod_update;
use crate::modcompat::version::SemVer;

/// Ab dieser TRS-Client-Version ist der Absturz mit Essential/MixinExtras 0.5 behoben
/// (@Redirect in `IntegratedServer#publishServer` → @Inject, Hotfix 0.9.1).
pub(crate) const TRS_ESSENTIAL_FIXED: &str = "0.9.1";

const SODIUM_LIKE: &[&str] = &["sodium", "embeddium", "rubidium", "magnesium"];
const MINIMAPS: &[&str] = &["xaerominimap", "xaerominimapfair", "journeymap", "voxelmap"];
const SHADER_MODS: &[&str] = &["iris", "oculus", "optifine"];

pub(super) fn run(input: &CrashInput<'_>, text: &Text, index: &ModIndex<'_>) -> Vec<Finding> {
    let mut out = Vec::new();
    known_issues(text, index, &mut out);
    duplicates(text, index, &mut out);
    requirements(text, index, &mut out);
    missing_dependencies(text, index, &mut out);
    wrong_java(input, text, index, &mut out);
    memory(input, text, &mut out);
    corrupt_files(text, index, &mut out);
    graphics(text, index, &mut out);
    mixin(text, index, &mut out);
    out
}

fn names(index: &ModIndex<'_>, ids: &[String]) -> String {
    ids.iter().map(|id| index.name(id)).collect::<Vec<_>>().join(", ")
}

fn disable(index: &ModIndex<'_>, ids: &[&str]) -> Option<CrashAction> {
    let files: Vec<String> = ids.iter().filter_map(|id| index.file_of(id)).collect();
    (!files.is_empty()).then_some(CrashAction::DisableMods { files })
}

fn has_mixin_failure(text: &Text) -> bool {
    text.has_any(&[
        "Mixin transformation of",
        "MixinTransformerError",
        "Mixin apply failed",
        "Mixin apply for mod",
        "MixinApplyError",
        "InvalidInjectionException",
        "InjectionError",
        "@Redirect conflict",
        "already redirected by",
        "MixinPrepareError",
        "InvalidMixinException",
        "Critical injection failure",
    ])
}

/// Mods, die im Stack/Mixin-Text auftauchen (für „wer ist beteiligt“).
fn mentioned_mods(text: &Text, index: &ModIndex<'_>) -> Vec<String> {
    let mut ids: Vec<String> = Vec::new();
    let add = |id: String, ids: &mut Vec<String>| {
        if is_mod_id(&id) && !is_platform_id(&id) && !ids.contains(&id) {
            ids.push(id);
        }
    };
    for line in &text.lines {
        for id in parse::from_mod(line) {
            add(id, &mut ids);
        }
        if line.contains("mixins") {
            for config in parse::mixin_configs(line) {
                if let Some(id) = index.by_mixin_config(&config) {
                    add(id, &mut ids);
                }
            }
        }
    }
    for frame in text.frames() {
        if let Some(id) = frame.handler_mod().or(frame.module_mod.clone()) {
            add(id, &mut ids);
        } else if !parse::is_framework_class(&frame.class) || frame.class.starts_with("com.llamalad7.mixinextras.") {
            // Java/Minecraft/Loader-Zeilen nicht zuordnen – nur MixinExtras als bekannte Bibliothek.
            if let Some(id) = index.by_class(&frame.class) {
                add(id, &mut ids);
            }
        }
    }
    ids
}

// --- Bekannte Kombi-Probleme ------------------------------------------------------

fn known_issues(text: &Text, index: &ModIndex<'_>, out: &mut Vec<Finding>) {
    // TRS Client ≤ 0.9.0 + Essential bzw. MixinExtras 0.5: @Redirect in IntegratedServer.
    if let Some(version) = index.version_of("trsclient")
        && client_mod_update::is_newer(TRS_ESSENTIAL_FIXED, &version)
    {
        let essential = index.find_present(|id| id == "essential" || id.starts_with("essential-"));
        let extras_05 = index.version_of("mixinextras").is_some_and(|v| v.starts_with("0.5."));
        let signature = text.has("FactoryRedirectWrapperMixinTransformer")
            || (has_mixin_failure(text) && (text.has("class_1132") || text.has("IntegratedServer")));
        if signature && (essential.is_some() || extras_05) {
            let mut f = Finding::new(CrashKind::KnownIssue, 100)
                .variant("trsclient_essential")
                .param("version", version)
                .param("fixed", TRS_ESSENTIAL_FIXED)
                .with_mods(["trsclient".to_owned()])
                .with_mods(essential.clone())
                .action(CrashAction::UpdateTrsClient);
            if index.present("mixinextras") {
                f = f.with_mods(["mixinextras".to_owned()]);
            }
            if let Some(line) = text.line_with(&["FactoryRedirectWrapperMixinTransformer", "Mixin transformation of"]) {
                f = f.evidence(line);
            }
            if let Some(action) = essential.as_deref().and_then(|e| disable(index, &[e])) {
                f = f.action(action);
            }
            out.push(f);
        }
    }

    // Sodium (bzw. Ableger) + OptiFine: laufen nie zusammen.
    let sodium = SODIUM_LIKE.iter().find(|id| index.present(id));
    let optifine: Vec<&str> = ["optifine", "optifabric"].into_iter().filter(|id| index.present(id)).collect();
    if let Some(sodium) = sodium
        && !optifine.is_empty()
    {
        let mut f = Finding::new(CrashKind::KnownIssue, 98)
            .variant("sodium_optifine")
            .param("name", index.name(sodium))
            .with_mods([(*sodium).to_owned()])
            .with_mods(optifine.iter().map(|s| (*s).to_owned()));
        if let Some(line) = text.line_with(&["optifine", "OptiFine", "optifabric"]) {
            f = f.evidence(line);
        }
        if let Some(action) = disable(index, &optifine) {
            f = f.action(action);
        }
        out.push(f);
    }

    // Iris braucht Sodium.
    if index.present("iris") && sodium.is_none() {
        let mut f = Finding::new(CrashKind::KnownIssue, 97)
            .variant("iris_without_sodium")
            .with_mods(["iris".to_owned()])
            .action(CrashAction::InstallDependencies { declarer: Some("iris".into()), dependencies: vec!["sodium".into()] });
        if let Some(line) = text.line_with(&["of sodium", "sodium, which is missing"]) {
            f = f.evidence(line);
        }
        out.push(f);
    }

    // Zwei Minimaps.
    let maps: Vec<String> = MINIMAPS.iter().filter(|id| index.present(id)).map(|s| (*s).to_owned()).collect();
    if maps.len() >= 2 {
        let mentioned = mentioned_mods(text, index);
        let involved = maps.iter().any(|m| mentioned.contains(m));
        let mut f = Finding::new(CrashKind::KnownIssue, if involved { 92 } else { 55 })
            .variant("two_minimaps")
            .param("names", names(index, &maps))
            .with_mods(maps.clone());
        for map in &maps {
            if let Some(action) = disable(index, &[map]) {
                f = f.action(action);
            }
        }
        out.push(f);
    }
}

// --- Doppelte Mods -------------------------------------------------------------------

/// Neueste Datei einer Gruppe: höchste Version, sonst neueste Änderungszeit.
fn newest<'m>(group: &[&'m crate::crash::InstalledMod]) -> &'m crate::crash::InstalledMod {
    let mut best = group[0];
    for m in &group[1..] {
        let newer = match (m.version.as_deref().and_then(SemVer::parse), best.version.as_deref().and_then(SemVer::parse)) {
            (Some(a), Some(b)) if a != b => a > b,
            _ => m.modified.unwrap_or(0) > best.modified.unwrap_or(0),
        };
        if newer {
            best = m;
        }
    }
    best
}

fn duplicates(text: &Text, index: &ModIndex<'_>, out: &mut Vec<Finding>) {
    // Forge/NeoForge: „Mod ID: 'jei' from mod files: a.jar, b.jar“.
    let mut from_log: Vec<(String, Vec<String>)> = Vec::new();
    for line in text.lines_with(&["from mod files:"]) {
        let t = line.trim();
        let Some(rest) = t.strip_prefix("Mod ID: '") else { continue };
        let Some((id, rest)) = rest.split_once('\'') else { continue };
        let Some((_, files)) = rest.split_once("from mod files:") else { continue };
        if is_mod_id(id) {
            let files = files.split(',').map(|f| f.trim().to_owned()).filter(|f| f.to_ascii_lowercase().ends_with(".jar")).collect();
            from_log.push((id.to_owned(), files));
        }
    }
    let log_says = !from_log.is_empty() || text.has_any(&["Found duplicate mods", "DuplicateModsFoundException", "Duplicate mod", "duplicate mods"]);

    // Aus der Instanz: zwei aktivierte Dateien mit derselben Mod-ID.
    let mut groups: Vec<(String, Vec<&crate::crash::InstalledMod>)> = Vec::new();
    for m in index.enabled_installed() {
        let Some(id) = m.id.clone() else { continue };
        match groups.iter_mut().find(|(g, _)| *g == id) {
            Some((_, list)) => list.push(m),
            None => groups.push((id, vec![m])),
        }
    }
    for (id, files) in &from_log {
        if groups.iter().any(|(g, l)| g == id && l.len() >= 2) {
            continue;
        }
        let list: Vec<_> = files.iter().filter_map(|f| index.by_file(f)).filter(|m| m.enabled).collect();
        if list.len() < 2 {
            // Dateien nicht (mehr) in der Instanz: nur erklären, nichts anbieten.
            let mut f = Finding::new(CrashKind::DuplicateMod, 90).param("name", index.name(id)).param("files", files.join(", ")).with_mods([id.clone()]);
            if let Some(line) = text.line_with(&[&format!("Mod ID: '{id}' from mod files")]) {
                f = f.evidence(line);
            }
            out.push(f);
            continue;
        }
        groups.retain(|(g, _)| g != id);
        groups.push((id.clone(), list));
    }
    for (id, list) in groups.into_iter().filter(|(_, l)| l.len() >= 2) {
        let keep = newest(&list);
        let remove: Vec<String> = list.iter().filter(|m| m.file_name != keep.file_name).map(|m| m.file_name.clone()).collect();
        let mut f = Finding::new(CrashKind::DuplicateMod, if log_says { 90 } else { 70 })
            .param("name", index.name(&id))
            .param("files", list.iter().map(|m| m.file_name.as_str()).collect::<Vec<_>>().join(", "))
            .param("keep", keep.file_name.clone())
            .with_mods([id.clone()])
            .action(CrashAction::RemoveDuplicates { files: remove, keep: vec![keep.file_name.clone()] });
        if let Some(line) = text.line_with(&[&format!("Mod ID: '{id}' from mod files")]) {
            f = f.evidence(line);
        }
        out.push(f);
    }
}

// --- Versionen (Minecraft, Loader, Java, andere Mods) ------------------------------

/// `'Minecraft' (minecraft)`, `mod 'Iris' (iris)` oder `minecraft` → ID.
fn target_id(text: &str) -> Option<String> {
    let text = text.trim().trim_start_matches("mod ").trim();
    if let Some(open) = text.rfind('(')
        && let Some(close) = text[open..].find(')')
    {
        let id = &text[open + 1..open + close];
        return is_mod_id(id).then(|| id.to_owned());
    }
    let id = text.trim_matches(['\'', '"']);
    is_mod_id(id).then(|| id.to_owned())
}

/// `'Name' (id) rest` → (Name, id, rest).
fn mod_ref(text: &str) -> Option<(&str, &str, &str)> {
    let rest = text.strip_prefix('\'')?;
    let (name, rest) = rest.split_once("' (")?;
    let (id, rest) = rest.split_once(')')?;
    is_mod_id(id).then_some((name, id, rest.trim_start()))
}

struct Requirement {
    mod_id: String,
    mod_name: String,
    mod_version: Option<String>,
    target: String,
    required: String,
    present: Option<String>,
    line: String,
    /// Fabric (tauschbar über modcompat).
    fabric: bool,
}

fn parse_requirement(line: &str) -> Option<Requirement> {
    let t = line.trim().trim_start_matches(['-', '\t', ' ']).trim();
    // Fabric: Mod 'X' (x) 1.0 requires version 1.21 of 'Minecraft' (minecraft), but only the wrong version is present: 1.21.11!
    if let Some(rest) = t.strip_prefix("Mod ")
        && let Some((name, id, rest)) = mod_ref(rest)
        && let Some((version, rest)) = rest.split_once(' ')
        && let Some(rest) = rest.strip_prefix("requires ")
        && let Some((req, present)) = rest.split_once(", but only the wrong version is present: ")
        && let Some((required, target)) = req.rsplit_once(" of ")
        && let Some(target) = target_id(target)
    {
        return Some(Requirement {
            mod_id: id.to_owned(),
            mod_name: name.to_owned(),
            mod_version: Some(version.to_owned()),
            target,
            required: required.trim_start_matches("version ").to_owned(),
            present: Some(present.trim_end_matches(['!', '.']).to_owned()),
            line: t.to_owned(),
            fabric: true,
        });
    }
    // (Neo)Forge: Mod ID: 'minecraft', Requested by: 'jei', Expected range: '[1.20,1.20.2)', Actual version: '1.21.1'
    if let Some(rest) = t.strip_prefix("Mod ID: '")
        && let Some((target, rest)) = rest.split_once('\'')
        && let Some((_, by)) = rest.split_once("Requested by: '")
        && let Some((by, rest)) = by.split_once('\'')
        && let Some((_, range)) = rest.split_once("Expected range: '")
        && let Some((range, rest)) = range.split_once('\'')
        && let Some((_, actual)) = rest.split_once("Actual version: '")
        && let Some((actual, _)) = actual.split_once('\'')
        && actual != "[MISSING]"
        && is_mod_id(target)
        && is_mod_id(by)
    {
        return Some(Requirement {
            mod_id: by.to_owned(),
            mod_name: by.to_owned(),
            mod_version: None,
            target: target.to_owned(),
            required: range.to_owned(),
            present: Some(actual.to_owned()),
            line: t.to_owned(),
            fabric: false,
        });
    }
    // Fabric: „is incompatible with …“ / „Replace mod … compatible with: …“
    let c = crate::process::parse_mod_conflict(t)?;
    Some(Requirement {
        mod_id: c.mod_id,
        mod_name: c.mod_name,
        mod_version: Some(c.mod_version),
        target: c.other_id,
        required: String::new(),
        present: c.other_version,
        line: t.to_owned(),
        fabric: true,
    })
}

fn first_number(text: &str) -> Option<u32> {
    let digits: String = text.chars().skip_while(|c| !c.is_ascii_digit()).take_while(char::is_ascii_digit).collect();
    digits.parse().ok()
}

fn requirements(text: &Text, index: &ModIndex<'_>, out: &mut Vec<Finding>) {
    let mut seen: Vec<(String, String)> = Vec::new();
    // Erst die genauen Angaben, dann Fabrics Lösungsvorschläge („Replace mod …“).
    let detailed = text.lines_with(&["wrong version is present", "is incompatible with", "Expected range:"]);
    for line in detailed.chain(text.lines_with(&["Replace mod "])) {
        let Some(r) = parse_requirement(line) else { continue };
        if seen.contains(&(r.mod_id.clone(), r.target.clone())) {
            continue;
        }
        seen.push((r.mod_id.clone(), r.target.clone()));
        let name = if r.mod_name == r.mod_id { index.name(&r.mod_id) } else { r.mod_name.clone() };
        let present = r.present.clone().unwrap_or_default();
        let finding = if r.target == "minecraft" {
            Finding::new(CrashKind::WrongGameVersion, 88)
                .param("name", name)
                .param("required", r.required.clone())
                .param("present", present)
                .with_mods([r.mod_id.clone()])
        } else if is_loader_id(&r.target) {
            Finding::new(CrashKind::WrongLoaderVersion, 87)
                .param("name", name)
                .param("loader", index.name(&r.target))
                .param("required", r.required.clone())
                .param("present", present)
                .with_mods([r.mod_id.clone()])
        } else if r.target == "java" {
            let mut f = Finding::new(CrashKind::WrongJava, 80).variant("mod_requires").param("name", name).param("present", present).with_mods([r.mod_id.clone()]);
            if let Some(major) = first_number(&r.required) {
                f = f.param("required", major.to_string()).action(CrashAction::SwitchJava { major: Some(major) });
            }
            f.evidence(&r.line)
        } else if is_platform_id(&r.target) {
            continue;
        } else {
            let other = index.name(&r.target);
            let mut f = Finding::new(CrashKind::IncompatibleMod, 85)
                .param("name", r.mod_version.as_ref().map_or(name.clone(), |v| format!("{name} {v}")))
                .param("other", if present.is_empty() { other } else { format!("{other} {present}") })
                .with_mods([r.mod_id.clone(), r.target.clone()]);
            if r.fabric {
                f = f.action(CrashAction::FixConflict { mod_id: r.mod_id.clone() });
            }
            f
        };
        let mut finding = finding.evidence(&r.line);
        if finding.kind != CrashKind::WrongJava
            && let Some(action) = disable(index, &[&r.mod_id])
        {
            finding = finding.action(action);
        }
        out.push(finding);
    }
}

fn missing_dependencies(text: &Text, index: &ModIndex<'_>, out: &mut Vec<Finding>) {
    let triggered = text.has("requires") && (text.has("which is missing") || text.has("but it is missing"))
        || text.has("Missing or unsupported mandatory dependencies")
        || text.has("Could not find required mod")
        || text.has("[MISSING]");
    if !triggered {
        return;
    }
    let Some(missing) = crate::process::parse_missing_dependency(&text.all) else { return };
    let deps: Vec<String> = missing.dependencies.into_iter().filter(|d| !is_platform_id(d)).collect();
    if deps.is_empty() {
        return;
    }
    // Schon als bekanntes Problem mit derselben Lösung erfasst (Iris ohne Sodium)?
    let covered = out.iter().any(|f| {
        f.actions.iter().any(|a| matches!(a, CrashAction::InstallDependencies { dependencies, .. } if deps.iter().all(|d| dependencies.contains(d))))
    });
    if covered {
        return;
    }
    let mut f = Finding::new(CrashKind::MissingDependency, 84).param("deps", names(index, &deps));
    if let Some(declarer) = &missing.mod_id {
        f = f.param("name", missing.mod_name.clone().unwrap_or_else(|| index.name(declarer))).with_mods([declarer.clone()]);
    }
    if let Some(line) = text.line_with(&["which is missing", "[MISSING]", "Could not find required mod"]) {
        f = f.evidence(line);
    }
    out.push(f.action(CrashAction::InstallDependencies { declarer: missing.mod_id, dependencies: deps }));
}

// --- Java -----------------------------------------------------------------------------

/// `(class file version 65.0)` → 21.
fn class_file_java(text: &str, marker: &str) -> Option<u32> {
    let (_, rest) = text.split_once(marker)?;
    let n = first_number(rest)?;
    (45..=100).contains(&n).then(|| n - 44)
}

fn wrong_java(input: &CrashInput<'_>, text: &Text, index: &ModIndex<'_>, out: &mut Vec<Finding>) {
    let current = text
        .line_with(&["Java Version: "])
        .and_then(|l| l.split_once("Java Version: "))
        .and_then(|(_, v)| first_number(v))
        .map(|n| if n == 1 { 8 } else { n });
    if let Some(line) = text.line_with(&["UnsupportedClassVersionError", "compiled by a more recent version of the Java Runtime"]) {
        let required = class_file_java(line, "class file version ");
        let found = class_file_java(line, "only recognizes class file versions up to ").or(current);
        let class = line
            .split_once("UnsupportedClassVersionError: ")
            .map_or(line, |(_, r)| r)
            .split_whitespace()
            .next()
            .unwrap_or("");
        let mut f = Finding::new(CrashKind::WrongJava, 80).variant("too_old").evidence(line);
        if let Some(id) = index.by_class(class).filter(|id| !is_platform_id(id)) {
            f = f.param("name", index.name(&id)).with_mods([id]);
        }
        if let Some(found) = found {
            f = f.param("present", found.to_string());
        }
        if let Some(required) = required {
            f = f.param("required", required.to_string()).action(CrashAction::SwitchJava { major: Some(required) });
        } else if input.custom_java {
            f = f.action(CrashAction::SwitchJava { major: None });
        }
        out.push(f);
        return;
    }
    // Java zu neu für alte Versionen: ASM kennt das Klassenformat nicht bzw.
    // LaunchWrapper (Forge ≤ 1.12) erwartet den URLClassLoader von Java 8.
    let url_loader = text.line_with(&["cannot be cast to class java.net.URLClassLoader", "cannot be cast to java.net.URLClassLoader"]);
    let asm = text.line_with(&["Unsupported class file major version"]);
    if let Some(line) = url_loader.or(asm) {
        let mut f = Finding::new(CrashKind::WrongJava, 80).variant("too_new").evidence(line);
        if let Some(found) = current.or_else(|| asm.and_then(|l| class_file_java(l, "major version "))) {
            f = f.param("present", found.to_string());
        }
        if url_loader.is_some() {
            f = f.param("required", "8").action(CrashAction::SwitchJava { major: Some(8) });
        } else if input.custom_java {
            f = f.action(CrashAction::SwitchJava { major: None });
        }
        out.push(f);
    }
}

// --- Arbeitsspeicher ---------------------------------------------------------------

fn round_512(mb: u32) -> u32 {
    mb / 512 * 512
}

/// Eingestellter Heap: Instanz, sonst aus dem Crash-Report (`up to … (4096 MiB)`) bzw. `-Xmx`.
fn heap_mb(input: &CrashInput<'_>, text: &Text) -> Option<u32> {
    input.memory_mb.or_else(|| {
        let line = text.line_with(&["Memory: "])?;
        let (_, rest) = line.rsplit_once("up to ")?;
        let (_, mib) = rest.split_once('(')?;
        first_number(mib)
    })
}

fn memory(input: &CrashInput<'_>, text: &Text, out: &mut Vec<Finding>) {
    let reserve = text.line_with(&[
        "Could not reserve enough space",
        "There is insufficient memory for the Java Runtime Environment",
        "Native memory allocation (mmap) failed",
        "Native memory allocation (malloc) failed",
        // Android-Engine: vom System wegen Speichermangel beendet (CrashInfo.kt).
        "[TRS] Spielprozess beendet: zu wenig Arbeitsspeicher",
    ]);
    let current = heap_mb(input, text);
    if let Some(line) = reserve {
        let mut f = Finding::new(CrashKind::OutOfMemory, 76).variant("reserve").evidence(line);
        if let Some(from) = current {
            f = f.param("current", from.to_string());
            // Mehr, als der PC hergibt: auf höchstens die Hälfte des RAMs senken.
            if let Some(system) = input.system_memory_mb {
                let to = round_512((system / 2).clamp(1024, 8192));
                if to < from {
                    f = f.param("suggested", to.to_string()).action(CrashAction::SetMemory { from_mb: from, to_mb: to });
                }
            }
        }
        out.push(f);
        return;
    }
    let Some(line) = text.line_with(&["java.lang.OutOfMemoryError"]) else { return };
    let mut f = Finding::new(CrashKind::OutOfMemory, 75).variant("heap").evidence(line);
    if let Some(from) = current {
        f = f.param("current", from.to_string());
        let cap = input.system_memory_mb.map_or(from + 2048, |s| round_512(s.saturating_sub(2048)));
        let to = round_512((from + 2048).max(from * 3 / 2)).min(cap).min(16_384);
        if to > from {
            f = f.param("suggested", to.to_string()).action(CrashAction::SetMemory { from_mb: from, to_mb: to });
        } else {
            f = f.variant("heap_max");
        }
    }
    out.push(f);
}

// --- Beschädigte Dateien ------------------------------------------------------------

/// `…\mods\journeymap.jar]` → `journeymap.jar`.
fn mod_file_in(line: &str) -> Option<String> {
    let lower = line.to_ascii_lowercase();
    let at = lower.find("mods\\").or_else(|| lower.find("mods/"))? + 5;
    let name: String = line[at..].chars().take_while(|c| !matches!(c, ']' | ':' | '"' | '\'' | ')' | '\\' | '/')).collect();
    let name = name.trim();
    (name.to_ascii_lowercase().ends_with(".jar") || name.to_ascii_lowercase().ends_with(".jar.disabled")).then(|| name.trim_end_matches(".disabled").to_owned())
}

fn corrupt_files(text: &Text, index: &ModIndex<'_>, out: &mut Vec<Finding>) {
    const ZIP: &[&str] = &[
        "java.util.zip.ZipException",
        "invalid CEN header",
        "zip END header not found",
        "invalid LOC header",
        "Invalid or corrupt jarfile",
        "ClassFormatError: Truncated class file",
        "Incompatible magic value",
        "Unexpected end of ZLIB input stream",
        "error in opening zip file",
    ];
    if let Some(line) = text.line_with(ZIP) {
        let file = text
            .lines_with(&["mods\\", "mods/"])
            .filter(|l| ZIP.iter().any(|z| l.contains(z)) || l.contains("Error analyzing") || l.contains("Failed to read"))
            .find_map(mod_file_in)
            .or_else(|| mod_file_in(line));
        let mut f = Finding::new(CrashKind::CorruptFiles, 70).evidence(line);
        match file {
            Some(file) => {
                let id = index.by_file(&file).map(ModIndex::id_of);
                f = f.variant("mod_file").param("file", file.clone());
                if let Some(id) = id {
                    f = f.with_mods([id]);
                }
                if index.by_file(&file).is_some_and(|m| m.enabled) {
                    f = f.action(CrashAction::DisableMods { files: vec![file] });
                }
            }
            None => f = f.variant("game_files").action(CrashAction::Repair),
        }
        out.push(f);
        return;
    }
    let assets = text.lines.iter().find(|l| {
        (l.contains("FileNotFoundException") || l.contains("NoSuchFileException")) && (l.contains("assets") || l.contains("indexes"))
            || l.contains("Missing asset index")
            || l.contains("Unable to load asset index")
    });
    if let Some(line) = assets {
        out.push(Finding::new(CrashKind::CorruptFiles, 70).variant("assets").evidence(line).action(CrashAction::Repair));
    }
}

// --- Grafiktreiber -----------------------------------------------------------------

fn graphics(text: &Text, index: &ModIndex<'_>, out: &mut Vec<Finding>) {
    let native = text.has("EXCEPTION_ACCESS_VIOLATION") || text.has("SIGSEGV");
    // Treiber-DLL im „Problematic frame“ (`# C  [atio6axx.dll+0x1a8f3e]`).
    let frames: Vec<String> = if native {
        text.lines.iter().filter(|l| l.contains("C  [") || l.contains("Problematic frame")).map(|l| l.to_ascii_lowercase()).collect()
    } else {
        Vec::new()
    };
    let vendor = |needles: &[&str]| frames.iter().any(|l| needles.iter().any(|n| l.contains(n)));
    const AMD: &[&str] = &["atio6axx", "atioglxx", "amdxx", "atig6pxx", "amdvlk"];
    const INTEL: &[&str] = &["ig9icd", "ig7icd", "ig75icd", "ig8icd", "ig11icd", "igxelpicd", "igdumdim"];
    const NVIDIA: &[&str] = &["nvoglv", "nvwgf2um", "nvd3dum"];
    // Zeile mit der DLL (Groß-/Kleinschreibung egal), sonst „Problematic frame“.
    let dll_line = |needles: &[&str]| {
        text.lines
            .iter()
            .map(String::as_str)
            .find(|l| needles.iter().any(|n| l.to_ascii_lowercase().contains(n)))
            .or_else(|| text.line_with(&["Problematic frame"]))
    };
    let (variant, line) = if vendor(AMD) {
        ("amd", dll_line(AMD))
    } else if vendor(INTEL) {
        ("intel", dll_line(INTEL))
    } else if vendor(NVIDIA) {
        ("nvidia", dll_line(NVIDIA))
    } else if let Some(line) = text.line_with(&[
        "GLFW error 65542",
        "The driver does not appear to support OpenGL",
        "Pixel format not accelerated",
        "No OpenGL context found",
    ]) {
        ("no_opengl", Some(line))
    } else if let Some(line) = text.line_with(&[
        "GLFW error 65543",
        "WGL_ARB_create_context_profile is unavailable",
        "OpenGL 3.2 is required",
        "requires OpenGL",
        "OpenGL version is too old",
        "Requested OpenGL version",
    ]) {
        ("opengl_too_old", Some(line))
    } else {
        return;
    };
    let mut f = Finding::new(CrashKind::GraphicsDriver, 65).variant(variant);
    if let Some(line) = line {
        f = f.evidence(line);
    }
    if let Some(card) = text.line_with(&["Graphics card #0 name: ", "GL Renderer: ", "Backend API: "]).and_then(|l| l.split_once(": ")).map(|(_, v)| v.trim())
        && !card.is_empty()
    {
        f = f.param("card", card);
    }
    let shaders: Vec<&str> = SHADER_MODS.iter().copied().filter(|id| index.installed(id).is_some_and(|m| m.enabled)).collect();
    if !shaders.is_empty() {
        f = f.param("shaders", shaders.iter().map(|s| index.name(s)).collect::<Vec<_>>().join(", ")).with_mods(shaders.iter().map(|s| (*s).to_owned()));
        if let Some(action) = disable(index, &shaders) {
            f = f.action(action);
        }
    }
    out.push(f);
}

// --- Mixin ------------------------------------------------------------------------------

/// Ein paar bekannte Intermediary-Namen, damit „class_1132“ lesbar wird.
fn class_label(class: &str) -> String {
    const NAMES: &[(&str, &str)] = &[
        ("class_1132", "IntegratedServer"),
        ("class_310", "MinecraftClient"),
        ("class_442", "TitleScreen"),
        ("class_761", "WorldRenderer"),
        ("class_757", "GameRenderer"),
        ("class_329", "InGameHud"),
        ("class_338", "ChatHud"),
        ("class_437", "Screen"),
        ("class_746", "ClientPlayerEntity"),
        ("class_1937", "World"),
        ("class_3218", "ServerWorld"),
        ("class_2338", "BlockPos"),
    ];
    let simple = class.rsplit(['.', '/']).next().unwrap_or(class);
    match NAMES.iter().find(|(k, _)| *k == simple) {
        Some((_, name)) => format!("{name} ({simple})"),
        None => simple.to_owned(),
    }
}

fn mixin(text: &Text, index: &ModIndex<'_>, out: &mut Vec<Finding>) {
    if !has_mixin_failure(text) {
        return;
    }
    let redirect = text.line_with(&["@Redirect conflict", "already redirected by"]);
    let transform = text.line_with(&["Mixin transformation of"]);
    let variant = if redirect.is_some() {
        "redirect_conflict"
    } else if transform.is_some() {
        "transform"
    } else {
        "apply"
    };
    let mut f = Finding::new(CrashKind::MixinConflict, 60).variant(variant);
    if let Some(target) = transform.and_then(|l| l.split_once("Mixin transformation of ")).and_then(|(_, r)| r.split_whitespace().next()) {
        f = f.param("target", class_label(target));
    }
    let mut ids: Vec<String> = Vec::new();
    // Beim @Redirect-Konflikt: genau die beiden genannten Mods.
    if let Some(line) = redirect {
        ids.extend(parse::from_mod(line));
        for config in parse::mixin_configs(line) {
            if let Some(id) = index.by_mixin_config(&config)
                && !ids.contains(&id)
            {
                ids.push(id);
            }
        }
        f = f.evidence(line);
    }
    for id in mentioned_mods(text, index) {
        if !ids.contains(&id) {
            ids.push(id);
        }
    }
    ids.retain(|id| !is_platform_id(id));
    ids.truncate(5);
    if let Some(line) = transform.or_else(|| text.line_with(&["Mixin apply", "Critical injection failure", "InvalidInjectionException", "MixinTransformerError"])) {
        f = f.evidence(line);
    }
    if !ids.is_empty() {
        f = f.param("names", names(index, &ids));
    }
    for id in ids.iter().take(3) {
        if let Some(action) = disable(index, &[id]) {
            f = f.action(action);
        }
    }
    out.push(f.with_mods(ids));
}

// --- Rückfall ------------------------------------------------------------------------

/// „Unbekannter Fehler“: wichtigste Zeilen + vermutlich beteiligte Mod.
pub(super) fn unknown(text: &Text, index: &ModIndex<'_>, frame: Option<&Frame>) -> Finding {
    let mut f = Finding::new(CrashKind::Unknown, 10);
    let mut ids: Vec<String> = Vec::new();
    if let Some(frame) = frame
        && let Some(id) = frame.handler_mod().or(frame.module_mod.clone()).or_else(|| index.by_class(&frame.class))
    {
        ids.push(id);
    }
    // (Neo)Forge nennt verdächtige Mods im Crash-Report.
    let mut suspects = false;
    for line in &text.lines {
        let t = line.trim();
        if t.starts_with("Suspected Mod") {
            suspects = true;
        } else if suspects && !(t.contains('(') && t.contains(')')) {
            suspects = false;
        }
        if suspects
            && let Some(id) = target_id(t.split(", Version").next().unwrap_or(t))
            && !is_platform_id(&id)
            && !ids.contains(&id)
        {
            ids.push(id);
        }
    }
    ids.retain(|id| !is_platform_id(id));
    ids.truncate(3);
    if let Some(cause) = text.root_cause() {
        f = f.evidence(cause);
    }
    if let Some(frame) = frame {
        f = f.evidence(&frame.line);
    }
    // Android-Engine: Ende-Bericht (Grund, Signal, oberste Stack-Zeilen) zeigen.
    let engine = ["[TRS] Spielprozess beendet", "[TRS] signal ", "[TRS] Abbruch", "[TRS]   #0"];
    for line in text.lines.iter().filter(|l| engine.iter().any(|m| l.trim_start().starts_with(m.trim_start()))).take(6) {
        f = f.evidence(line);
    }
    if let Some(first) = ids.first() {
        f = f.variant("suspect").param("name", index.name(first));
        if let Some(action) = disable(index, &[first]) {
            f = f.action(action);
        }
    }
    f.with_mods(ids)
}
