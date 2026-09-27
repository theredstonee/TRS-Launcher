//! „Mit TRS Client“ oder „Ohne“? Die Frage beim Installieren bzw. Importieren
//! eines Modpacks oder einer Instanz mit Mods.
//!
//! Der Kern entscheidet hier drei Dinge:
//! - ob die Frage überhaupt passt (Vanilla ohne Modloader: nein – dort bleibt
//!   alles wie bisher),
//! - ob es für Loader + Version einen TRS-Client-Build gibt (sonst ausgegraut),
//! - was vorausgewählt ist: „Mit“, außer das Pack bringt Mods mit, die sich mit
//!   dem TRS Client beißen (andere Client-Mods, eigene Minimap/HUD). Zoom- und
//!   Freelook-Mods sind nur ein Hinweis – die TRS-Module dafür lassen sich im
//!   Spiel einzeln abschalten und stören sich höchstens über die Tastenbelegung.
//!
//! Erkannt wird über Modrinth-/CurseForge-Projekt-IDs (Packs nennen sie bzw.
//! ihre Download-Links) und über Dateinamen (Import aus anderen Launchern).

use serde::{Deserialize, Serialize};

use crate::client_mod::{self, Build};
use crate::instance::{Loader, LoaderKind};

/// Globale Einstellung: bei Modpacks fragen oder immer gleich entscheiden.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ModpackTrsPolicy {
    /// Im Installations-/Import-Dialog fragen (Standard).
    #[default]
    Ask,
    /// Immer mit TRS Client (sofern es einen Build gibt).
    Always,
    /// Immer ohne TRS Client.
    Never,
}

/// Unbekannter Wert (z. B. aus einer neueren Version) → wieder „fragen“.
pub(crate) fn lenient_policy<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> std::result::Result<ModpackTrsPolicy, D::Error> {
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(serde_json::from_value(value).unwrap_or_default())
}

/// Wie stark sich eine Mod mit dem TRS Client beißt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum ConflictKind {
    /// Ein anderer Client (Menüs, Kosmetik, Konten) – beide zusammen stören sich.
    ClientMod,
    /// Eigene Minimap/Weltkarte – doppelt auf dem Bildschirm.
    Minimap,
    /// Eigene HUD-Anzeigen (Rüstung, Tasten, Infozeilen).
    Hud,
    /// Zoom bzw. Freelook – nur ein Hinweis (Tastenbelegung).
    Zoom,
}

impl ConflictKind {
    /// Starke Überschneidung → „Ohne“ vorausgewählt.
    pub fn is_strong(self) -> bool {
        !matches!(self, Self::Zoom)
    }
}

/// Eine erkannte Mod aus der Liste bekannter Überschneidungen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Conflict {
    pub name: &'static str,
    pub kind: ConflictKind,
}

/// Was über eine Datei des Packs bekannt ist (alles optional).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ModHint {
    pub file_name: Option<String>,
    pub modrinth: Option<String>,
    pub curseforge: Option<u64>,
}

impl ModHint {
    pub fn file(name: impl Into<String>) -> Self {
        Self { file_name: Some(name.into()), ..Self::default() }
    }

    /// Aus einem Eintrag einer `.mrpack`: Pfad + Download-Links
    /// (`https://cdn.modrinth.com/data/<projekt>/versions/…`).
    pub fn from_pack_file(path: &str, downloads: &[String]) -> Self {
        let file_name = path.rsplit('/').next().filter(|n| !n.is_empty()).map(str::to_owned);
        let modrinth = downloads.iter().find_map(|u| modrinth_project_of(u));
        Self { file_name, modrinth, curseforge: None }
    }
}

/// Projekt-ID aus einem Modrinth-CDN-Link.
fn modrinth_project_of(url: &str) -> Option<String> {
    let rest = url.strip_prefix("https://cdn.modrinth.com/data/")?;
    let id = rest.split('/').next()?;
    crate::modrinth::is_safe_project_id(id).then(|| id.to_owned())
}

struct Known {
    name: &'static str,
    kind: ConflictKind,
    modrinth: &'static [&'static str],
    curseforge: &'static [u64],
    /// Dateinamen in Wörtern (klein, ohne Trennzeichen), z. B. `["xaeros", "minimap"]`.
    files: &'static [&'static [&'static str]],
}

/// Bekannte Überschneidungen. IDs am 2026-09-27 über die Modrinth- bzw.
/// CurseForge-API geprüft.
const KNOWN: &[Known] = &[
    Known {
        name: "Essential",
        kind: ConflictKind::ClientMod,
        modrinth: &["k2ZPuTBm"],
        curseforge: &[546_670],
        files: &[&["essential"]],
    },
    Known { name: "Feather", kind: ConflictKind::ClientMod, modrinth: &[], curseforge: &[], files: &[&["feather"]] },
    Known {
        name: "NoRisk Client",
        kind: ConflictKind::ClientMod,
        modrinth: &[],
        curseforge: &[],
        files: &[&["noriskclient"], &["norisk", "client"]],
    },
    Known { name: "LabyMod", kind: ConflictKind::ClientMod, modrinth: &[], curseforge: &[], files: &[&["labymod"]] },
    Known {
        name: "Xaero's Minimap",
        kind: ConflictKind::Minimap,
        modrinth: &["1bokaNcj"],
        curseforge: &[263_420],
        files: &[&["xaeros", "minimap"], &["xaerominimap"], &["xaeros", "minimap", "fp"]],
    },
    Known {
        name: "Xaero's World Map",
        kind: ConflictKind::Minimap,
        modrinth: &["NcUtCpym"],
        curseforge: &[317_780],
        files: &[&["xaeros", "worldmap"], &["xaeros", "world", "map"], &["xaeroworldmap"]],
    },
    Known {
        name: "JourneyMap",
        kind: ConflictKind::Minimap,
        modrinth: &["lfHFW1mp"],
        curseforge: &[32_274],
        files: &[&["journeymap"]],
    },
    Known {
        name: "VoxelMap",
        kind: ConflictKind::Minimap,
        modrinth: &["wkzK5379"],
        curseforge: &[],
        files: &[&["voxelmap"], &["voxelmap", "updated"]],
    },
    Known {
        name: "FTB Chunks",
        kind: ConflictKind::Minimap,
        modrinth: &[],
        curseforge: &[314_906, 472_657],
        files: &[&["ftb", "chunks"], &["ftbchunks"]],
    },
    Known {
        name: "MiniHUD",
        kind: ConflictKind::Hud,
        modrinth: &["UMxybHE8"],
        curseforge: &[244_260],
        files: &[&["minihud"]],
    },
    Known {
        name: "Armor HUD",
        kind: ConflictKind::Hud,
        modrinth: &["AghHBZC5"],
        curseforge: &[1_321_716],
        files: &[&["armor", "hud"], &["armorhud"]],
    },
    Known { name: "Keystrokes", kind: ConflictKind::Hud, modrinth: &[], curseforge: &[], files: &[&["keystrokes"]] },
    Known {
        name: "Ok Zoomer",
        kind: ConflictKind::Zoom,
        modrinth: &["aXf2OSFU"],
        curseforge: &[354_047],
        files: &[&["okzoomer"], &["ok", "zoomer"]],
    },
    Known {
        name: "Zoomify",
        kind: ConflictKind::Zoom,
        modrinth: &["w7ThoJFB"],
        curseforge: &[574_741],
        files: &[&["zoomify"]],
    },
    Known {
        name: "Logical Zoom",
        kind: ConflictKind::Zoom,
        modrinth: &["8bOImuGU"],
        curseforge: &[],
        files: &[&["logical", "zoom"], &["logicalzoom"]],
    },
    Known {
        name: "Freelook",
        kind: ConflictKind::Zoom,
        modrinth: &["g4pR0Fmy"],
        curseforge: &[298_112],
        files: &[&["freelook"]],
    },
    Known {
        name: "Perspective Mod Redux",
        kind: ConflictKind::Zoom,
        modrinth: &["GwyFsKOX"],
        curseforge: &[280_647],
        files: &[&["perspective", "mod", "redux"], &["perspectivemod"]],
    },
];

/// Wörter, die nach dem Mod-Namen in Dateinamen stehen dürfen (Loader, Präfixe
/// von Versionen). Alles andere ist eine andere Mod („essential-commands“).
const NAME_SUFFIXES: &[&str] =
    &["fabric", "forge", "neoforge", "quilt", "mc", "v", "universal", "client", "all", "release", "beta", "alpha"];

/// Dateiname in kleine Wörter zerlegt; die Endung zählt nicht mit.
fn words(file_name: &str) -> Vec<String> {
    let lower = file_name.to_ascii_lowercase();
    let stem = [".jar.disabled", ".jar", ".disabled", ".zip"]
        .iter()
        .find_map(|ext| lower.strip_suffix(ext))
        .unwrap_or(&lower);
    stem.replace('\'', "")
        .split(|c: char| !c.is_ascii_alphanumeric())
        .filter(|w| !w.is_empty())
        .map(str::to_owned)
        .collect()
}

fn file_matches(words: &[String], pattern: &[&str]) -> bool {
    if words.len() < pattern.len() || !words.iter().zip(pattern).all(|(w, p)| w == p) {
        return false;
    }
    match words.get(pattern.len()) {
        None => true,
        Some(next) => next.starts_with(|c: char| c.is_ascii_digit()) || NAME_SUFFIXES.contains(&next.as_str()),
    }
}

fn matches(known: &Known, hint: &ModHint) -> bool {
    if hint.modrinth.as_deref().is_some_and(|id| known.modrinth.contains(&id)) {
        return true;
    }
    if hint.curseforge.is_some_and(|id| known.curseforge.contains(&id)) {
        return true;
    }
    hint.file_name.as_deref().is_some_and(|name| {
        let words = words(name);
        known.files.iter().any(|p| file_matches(&words, p))
    })
}

/// Welche bekannten Überschneidungen im Pack stecken (jede Mod einmal, starke zuerst).
pub fn detect(hints: &[ModHint]) -> Vec<Conflict> {
    let mut found: Vec<Conflict> = KNOWN
        .iter()
        .filter(|k| hints.iter().any(|h| matches(k, h)))
        .map(|k| Conflict { name: k.name, kind: k.kind })
        .collect();
    found.sort_by_key(|c| !c.kind.is_strong());
    found
}

/// Warum „Mit TRS Client“ ausgegraut ist.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum Unsupported {
    /// Für diesen Loader + diese Version gibt es keinen TRS-Client-Build.
    NoBuild { loader: LoaderKind, game_version: String },
}

/// Was der Dialog zur Frage „Mit oder ohne TRS Client“ anzeigt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrsOffer {
    /// `false` = keine Frage (Vanilla ohne Modloader bleibt wie bisher).
    pub applies: bool,
    /// Es gibt einen passenden Build.
    pub supported: bool,
    pub unsupported: Option<Unsupported>,
    /// Vorauswahl: `true` = „Mit TRS Client“.
    pub recommended: bool,
    pub conflicts: Vec<Conflict>,
    /// Globale Einstellung – bei „immer …“ fragt das Frontend nicht.
    pub policy: ModpackTrsPolicy,
}

impl TrsOffer {
    /// Muss der Dialog fragen?
    pub fn asks(&self) -> bool {
        self.applies && self.supported && self.policy == ModpackTrsPolicy::Ask
    }
}

/// Angebot für ein Pack bzw. eine Instanz mit diesem Loader, dieser Version und diesen Dateien.
pub fn offer(builds: &[Build], loader: &Loader, game_version: &str, hints: &[ModHint], policy: ModpackTrsPolicy) -> TrsOffer {
    let applies = loader.kind != LoaderKind::Vanilla;
    let supported = applies && client_mod::build_for(builds, loader.kind, game_version).is_some();
    let conflicts = if applies { detect(hints) } else { Vec::new() };
    let recommended = supported && !conflicts.iter().any(|c| c.kind.is_strong());
    let unsupported = (applies && !supported)
        .then(|| Unsupported::NoBuild { loader: loader.kind, game_version: game_version.to_owned() });
    TrsOffer { applies, supported, unsupported, recommended, conflicts, policy }
}

/// Was in `overrides.trsClient` der neuen Instanz landet.
///
/// - Vanilla (keine Frage): `None` – wie bisher.
/// - Kein Build: `Some(false)` – ausdrücklich ohne; später in den
///   Instanz-Einstellungen einschaltbar, falls es einen Build gibt.
/// - „immer mit/ohne“: das, egal was angefragt wurde.
/// - „fragen“: die Wahl aus dem Dialog, ohne Wahl die Vorauswahl.
pub fn decide(offer: &TrsOffer, requested: Option<bool>) -> Option<bool> {
    if !offer.applies {
        return None;
    }
    if !offer.supported {
        return Some(false);
    }
    Some(match offer.policy {
        ModpackTrsPolicy::Always => true,
        ModpackTrsPolicy::Never => false,
        ModpackTrsPolicy::Ask => requested.unwrap_or(offer.recommended),
    })
}

#[cfg(test)]
mod flow_tests;

#[cfg(test)]
mod tests {
    use super::*;

    fn builds() -> Vec<Build> {
        client_mod::parse_manifest(
            br#"{"version":"0.10.0","builds":[
                {"loader":"fabric","minecraft":["1.21.1","1.21.11"],"file":"trsclient-fabric-1.21.1.jar"},
                {"loader":"forge","minecraft":["1.8.9"],"file":"trsclient-forge-1.8.9.jar"}]}"#,
        )
        .unwrap()
        .builds
    }

    fn fabric() -> Loader {
        Loader { kind: LoaderKind::Fabric, version: None }
    }

    #[test]
    fn plain_pack_recommends_the_client() {
        let hints = [ModHint::file("sodium-fabric-0.6.0+mc1.21.1.jar"), ModHint::file("lithium-fabric-0.15.0.jar")];
        let o = offer(&builds(), &fabric(), "1.21.1", &hints, ModpackTrsPolicy::Ask);
        assert!(o.applies && o.supported && o.recommended && o.asks());
        assert!(o.conflicts.is_empty());
        assert_eq!(decide(&o, None), Some(true));
        assert_eq!(decide(&o, Some(false)), Some(false), "die Wahl im Dialog gewinnt");
    }

    #[test]
    fn quilt_uses_the_fabric_build() {
        let o = offer(&builds(), &Loader { kind: LoaderKind::Quilt, version: None }, "1.21.11", &[], ModpackTrsPolicy::Ask);
        assert!(o.supported);
    }

    #[test]
    fn strong_conflicts_preselect_without() {
        for hint in [
            ModHint::file("Essential (fabric_1.21.1).jar"),
            ModHint::file("Xaeros_Minimap_24.6.1_Fabric_1.21.jar"),
            ModHint::file("Xaeros_Minimap_FP_24.6.1_Fabric_1.21.jar"),
            ModHint::file("journeymap-fabric-1.21.1-6.0.0-beta.jar"),
            ModHint::file("minihud-fabric-1.21.1-0.32.0.jar.disabled"),
            ModHint::file("ftb-chunks-neoforge-2101.1.1.jar"),
            ModHint { modrinth: Some("k2ZPuTBm".into()), ..ModHint::default() },
            ModHint { curseforge: Some(263_420), ..ModHint::default() },
            ModHint::from_pack_file("mods/whatever.jar", &["https://cdn.modrinth.com/data/lfHFW1mp/versions/abc/x.jar".into()]),
        ] {
            let o = offer(&builds(), &fabric(), "1.21.1", std::slice::from_ref(&hint), ModpackTrsPolicy::Ask);
            assert!(!o.recommended, "{hint:?}");
            assert!(o.supported && o.asks(), "{hint:?}: trotzdem wählbar");
            assert_eq!(o.conflicts.len(), 1, "{hint:?}");
            assert_eq!(decide(&o, None), Some(false), "{hint:?}");
            assert_eq!(decide(&o, Some(true)), Some(true), "{hint:?}: bewusst mit");
        }
    }

    #[test]
    fn zoom_mods_are_only_a_hint() {
        let hints = [ModHint::file("zoomify-2.14.2+1.21.jar"), ModHint { curseforge: Some(354_047), ..ModHint::default() }];
        let o = offer(&builds(), &fabric(), "1.21.1", &hints, ModpackTrsPolicy::Ask);
        assert!(o.recommended);
        assert_eq!(o.conflicts.iter().map(|c| c.name).collect::<Vec<_>>(), ["Ok Zoomer", "Zoomify"]);
        // Starke Überschneidungen stehen vorn.
        let mixed = [ModHint::file("zoomify-2.14.jar"), ModHint::file("voxelmap-1.21.1-1.15.2.jar")];
        let o = offer(&builds(), &fabric(), "1.21.1", &mixed, ModpackTrsPolicy::Ask);
        assert!(!o.recommended);
        assert_eq!(o.conflicts[0].kind, ConflictKind::Minimap);
    }

    #[test]
    fn similar_names_are_not_mistaken() {
        for name in [
            "essential-commands-0.35.jar",
            "essentials-x.jar",
            "journeymap-api-2.0.jar",
            "xaeros-minimap-compat-addon.jar",
            "betterf3-11.0.jar",
            "zoomifyextra.jar",
            "feathers-0.1.jar",
        ] {
            assert!(detect(&[ModHint::file(name)]).is_empty(), "{name}");
        }
        assert!(detect(&[ModHint::from_pack_file("mods/x.jar", &["https://evil.example/data/k2ZPuTBm/x.jar".into()])]).is_empty());
    }

    #[test]
    fn unsupported_versions_are_greyed_out() {
        let o = offer(&builds(), &fabric(), "1.12.2", &[], ModpackTrsPolicy::Ask);
        assert!(o.applies && !o.supported && !o.recommended && !o.asks());
        assert_eq!(o.unsupported, Some(Unsupported::NoBuild { loader: LoaderKind::Fabric, game_version: "1.12.2".into() }));
        assert_eq!(decide(&o, Some(true)), Some(false), "ohne Build gibt es kein „Mit“");
        let neo = offer(&builds(), &Loader { kind: LoaderKind::NeoForge, version: None }, "1.21.1", &[], ModpackTrsPolicy::Always);
        assert_eq!(decide(&neo, None), Some(false));
    }

    #[test]
    fn vanilla_stays_as_before() {
        let o = offer(&builds(), &Loader::vanilla(), "1.21.1", &[ModHint::file("essential.jar")], ModpackTrsPolicy::Never);
        assert!(!o.applies && !o.asks());
        assert!(o.conflicts.is_empty());
        assert_eq!(decide(&o, Some(false)), None);
    }

    #[test]
    fn policy_overrides_the_question() {
        let hints = [ModHint::file("Essential-fabric_1-21-1.jar")];
        let always = offer(&builds(), &fabric(), "1.21.1", &hints, ModpackTrsPolicy::Always);
        assert!(!always.asks());
        assert_eq!(decide(&always, Some(false)), Some(true));
        let never = offer(&builds(), &fabric(), "1.21.1", &[], ModpackTrsPolicy::Never);
        assert_eq!(decide(&never, Some(true)), Some(false));
    }

    #[test]
    fn policy_reads_unknown_values_leniently() {
        #[derive(Deserialize)]
        struct S {
            #[serde(deserialize_with = "lenient_policy")]
            p: ModpackTrsPolicy,
        }
        let read = |json: &str| serde_json::from_str::<S>(json).unwrap().p;
        assert_eq!(read(r#"{"p":"always"}"#), ModpackTrsPolicy::Always);
        assert_eq!(read(r#"{"p":"never"}"#), ModpackTrsPolicy::Never);
        assert_eq!(read(r#"{"p":"sometimes"}"#), ModpackTrsPolicy::Ask);
    }
}
