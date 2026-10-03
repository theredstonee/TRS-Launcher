//! Welche Mod gehört auf den Server? Aus mehreren Quellen: Angaben im Jar
//! (`fabric.mod.json` `environment`, `quilt.mod.json` `minecraft.environment`,
//! `mods.toml` `clientSideOnly`/`displayTest`), Modrinth (`client_side`/`server_side`),
//! CurseForge (Umgebung „Client“/„Server“ der Datei) und eine Liste bekannter
//! Client-Mods. Was nichts verrät, bleibt „unbekannt“ und kommt mit.

use std::io::{Read, Seek};

use serde::{Deserialize, Serialize};

use crate::modcompat::meta::{self, MAX_ENTRY_BYTES};

/// Wo eine Mod läuft.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ModSide {
    /// Nur im Spiel (Grafik, Minimap, HUD) – bleibt beim Server weg.
    Client,
    /// Nur auf dem Server.
    Server,
    /// Auf beiden Seiten.
    Both,
    /// Keine Angaben – kommt vorsichtshalber mit.
    Unknown,
}

/// Woher die Einordnung stammt (für die Anzeige).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SideSource {
    /// Der TRS Client (immer nur im Spiel).
    Trs,
    /// Liste bekannter Client-Mods.
    List,
    /// Angaben im Jar.
    Jar,
    Modrinth,
    Curseforge,
    None,
}

/// Was das Jar über seine Seite sagt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JarSide {
    Client,
    Server,
    Both,
    /// Forge `displayTest = "IGNORE_ALL_VERSION"`: meist eine reine Client-Mod.
    LikelyClient,
}

/// Alles, was über eine Mod-Datei bekannt ist.
#[derive(Debug, Clone, Default)]
pub struct SideSignals {
    pub trs_client: bool,
    pub known_client: bool,
    pub jar: Option<JarSide>,
    /// Modrinth `(client_side, server_side)`: `required`/`optional`/`unsupported`/`unknown`.
    pub modrinth: Option<(String, String)>,
    /// CurseForge: Datei für Client/Server markiert.
    pub curseforge: Option<(bool, bool)>,
}

/// Mod-IDs und Modrinth-Slugs von Mods, die nur im Spiel laufen (Grafik, Karten, HUD, Zoom).
/// Bewusst nicht dabei: Mods, die auch auf Servern laufen (JourneyMap, Lithium, FerriteCore …).
pub const KNOWN_CLIENT_MODS: &[&str] = &[
    "sodium",
    "sodium-extra",
    "sodium_extra",
    "reeses-sodium-options",
    "reeses_sodium_options",
    "sodiumdynamiclights",
    "embeddium",
    "rubidium",
    "magnesium",
    "celeritas",
    "nvidium",
    "indium",
    "iris",
    "oculus",
    "optifine",
    "optifabric",
    "immediatelyfast",
    "entityculling",
    "moreculling",
    "cull-less-leaves",
    "cull_less_leaves",
    "enhancedblockentities",
    "dynamic_fps",
    "dynamic-fps",
    "dynamicfps",
    "fpsreducer",
    "continuity",
    "lambdynlights",
    "lambdynamiclights",
    "xaerominimap",
    "xaerominimapfair",
    "xaeroworldmap",
    "xaeros-minimap",
    "xaeros-world-map",
    "voxelmap",
    "modmenu",
    "betterf3",
    "zoomify",
    "ok_zoomer",
    "ok-zoomer",
    "okzoomer",
    "logical_zoom",
    "controlling",
    "notenoughanimations",
    "skinlayers3d",
    "3dskinlayers",
    "entity_model_features",
    "entity_texture_features",
    "entity-model-features",
    "entitytexturefeatures",
    "fabricskyboxes",
    "cit_resewn",
    "citresewn",
    "chat_heads",
    "chat-heads",
    "replaymod",
    "flashback",
    "drippyloadingscreen",
    "fancymenu",
    "legendarytooltips",
    "exordium",
    "betterthirdperson",
    "puzzle",
    "language-reload",
    "languagereload",
    "searchables",
    "particlerain",
    "visuality",
    "falling_leaves",
    "fallingleaves",
    "presencefootsteps",
    "sound_physics_remastered",
    "soundphysics",
    "ambientsounds",
];

/// Modrinth-Projekt-IDs bekannter Client-Mods (falls kein Slug geladen wurde).
pub const KNOWN_CLIENT_PROJECTS: &[&str] = &[
    "AANobbMI", // Sodium
    "sk9rgfiA", // Embeddium
    "YL57xq9U", // Iris
    "5ZwdcRci", // ImmediatelyFast
    "NNAgCjsB", // Entity Culling
    "51shyZVL", // More Culling
    "LQ3K71Q1", // Dynamic FPS
    "1bokaNcj", // Xaero's Minimap
    "NcUtCpym", // Xaero's World Map
    "mOgUt4GM", // Mod Menu
    "SfMw2IZN", // Nvidium
    "Nv2fQJo5", // Replay Mod
    "4das1Fjq", // Flashback
];

/// Ist das eine bekannte Client-Mod? `ids` sind Mod-IDs aus dem Jar und Modrinth-Slug/-ID.
pub fn is_known_client(ids: &[String]) -> bool {
    ids.iter().any(|id| {
        let lower = id.to_ascii_lowercase();
        KNOWN_CLIENT_MODS.contains(&lower.as_str()) || KNOWN_CLIENT_PROJECTS.contains(&id.as_str())
    })
}

/// Ist das der TRS Client (wird vom Launcher selbst verwaltet, nie auf dem Server)?
pub fn is_trs_client_file(file_name: &str) -> bool {
    let lower = file_name.to_ascii_lowercase();
    lower == "trsclient.jar" || lower.starts_with("trsclient-") && lower.ends_with(".jar")
}

fn modrinth_known(value: &str) -> bool {
    matches!(value, "required" | "optional" | "unsupported")
}

/// Fasst die Angaben zusammen. Reihenfolge: sichere Client-Hinweise, dann
/// Server, dann „beide Seiten“, zuletzt schwache Client-Hinweise.
pub fn classify(signals: &SideSignals) -> (ModSide, SideSource) {
    if signals.trs_client {
        return (ModSide::Client, SideSource::Trs);
    }
    if signals.known_client {
        return (ModSide::Client, SideSource::List);
    }
    let modrinth = signals.modrinth.as_ref().map(|(c, s)| (c.as_str(), s.as_str()));
    if let Some((_, "unsupported")) = modrinth {
        return (ModSide::Client, SideSource::Modrinth);
    }
    if signals.jar == Some(JarSide::Client) {
        return (ModSide::Client, SideSource::Jar);
    }
    if let Some(("unsupported", server)) = modrinth
        && modrinth_known(server)
    {
        return (ModSide::Server, SideSource::Modrinth);
    }
    if signals.jar == Some(JarSide::Server) {
        return (ModSide::Server, SideSource::Jar);
    }
    if signals.curseforge == Some((false, true)) {
        return (ModSide::Server, SideSource::Curseforge);
    }
    if let Some((client, server)) = modrinth
        && modrinth_known(client)
        && modrinth_known(server)
    {
        return (ModSide::Both, SideSource::Modrinth);
    }
    if signals.jar == Some(JarSide::Both) {
        return (ModSide::Both, SideSource::Jar);
    }
    if signals.curseforge == Some((true, true)) {
        return (ModSide::Both, SideSource::Curseforge);
    }
    if signals.jar == Some(JarSide::LikelyClient) {
        return (ModSide::Client, SideSource::Jar);
    }
    if signals.curseforge == Some((true, false)) {
        return (ModSide::Client, SideSource::Curseforge);
    }
    (ModSide::Unknown, SideSource::None)
}

/// Kommt die Mod ab Werk mit? Client-Mods nicht, alles andere schon.
pub fn included_by_default(side: ModSide) -> bool {
    side != ModSide::Client
}

/// CurseForge führt die Umgebung als „Client“/„Server“ in `gameVersions`.
pub fn curseforge_env(game_versions: &[String]) -> Option<(bool, bool)> {
    let client = game_versions.iter().any(|v| v.eq_ignore_ascii_case("client"));
    let server = game_versions.iter().any(|v| v.eq_ignore_ascii_case("server"));
    (client || server).then_some((client, server))
}

/// Was das Jar über sich sagt: Seite, Mod-IDs und Anzeigename.
#[derive(Debug, Clone, Default)]
pub struct JarFacts {
    pub side: Option<JarSide>,
    pub ids: Vec<String>,
    pub name: Option<String>,
}

pub fn fabric_side(text: &str) -> Option<JarSide> {
    let json = meta::lenient_json(text)?;
    match json.get("environment")?.as_str()? {
        "client" => Some(JarSide::Client),
        "server" => Some(JarSide::Server),
        "*" => Some(JarSide::Both),
        _ => None,
    }
}

pub fn quilt_side(text: &str) -> Option<JarSide> {
    let json = meta::lenient_json(text)?;
    match json.get("minecraft")?.get("environment")?.as_str()? {
        "client" => Some(JarSide::Client),
        "dedicated_server" => Some(JarSide::Server),
        "*" => Some(JarSide::Both),
        _ => None,
    }
}

/// (Neo)Forge: `clientSideOnly = true` ist eindeutig; `displayTest = "IGNORE_ALL_VERSION"`
/// nutzen fast nur reine Client-Mods; `IGNORE_SERVER_VERSION` heißt „nur Server“.
pub fn toml_side(text: &str) -> Option<JarSide> {
    let table = text.parse::<toml::Table>().ok()?;
    if table.get("clientSideOnly").and_then(toml::Value::as_bool) == Some(true) {
        return Some(JarSide::Client);
    }
    let tests: Vec<String> = table
        .get("mods")
        .and_then(|m| m.as_array())
        .into_iter()
        .flatten()
        .filter_map(|m| m.get("displayTest").and_then(|d| d.as_str()))
        .map(str::to_ascii_uppercase)
        .collect();
    if tests.iter().any(|t| t == "IGNORE_SERVER_VERSION") {
        return Some(JarSide::Server);
    }
    if !tests.is_empty() && tests.iter().all(|t| t == "IGNORE_ALL_VERSION") {
        return Some(JarSide::LikelyClient);
    }
    None
}

/// Liest Seite, IDs und Namen aus einem Mod-Jar. Kaputte Jars liefern leere Angaben.
pub fn read_jar_facts<R: Read + Seek>(reader: R) -> JarFacts {
    let Ok(mut archive) = zip::ZipArchive::new(reader) else { return JarFacts::default() };
    let mut read = |name: &str| -> Option<String> {
        let entry = archive.by_name(name).ok()?;
        if entry.size() > MAX_ENTRY_BYTES {
            return None;
        }
        let mut bytes = Vec::new();
        entry.take(MAX_ENTRY_BYTES).read_to_end(&mut bytes).ok()?;
        Some(String::from_utf8_lossy(&bytes).into_owned())
    };
    let side = if let Some(text) = read(meta::FABRIC) {
        fabric_side(&text)
    } else if let Some(text) = read(meta::QUILT) {
        quilt_side(&text)
    } else {
        read(meta::NEOFORGE_TOML).or_else(|| read(meta::FORGE_TOML)).and_then(|t| toml_side(&t))
    };
    let infos = meta::from_entries(&mut read);
    let name = infos.first().map(|m| m.display_name().to_owned()).filter(|n| !n.is_empty());
    JarFacts { side, ids: infos.into_iter().map(|m| m.id).collect(), name }
}
