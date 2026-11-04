//! Minispiele: Texturen aus einem schon geladenen Minecraft-Client lesen.
//!
//! Mojangs Grafiken werden nie mitgeliefert oder gespeichert. Die Spiele fragen beim Öffnen nur diese feste Liste ab,
//! gelesen wird sie aus `versions/<id>/<id>.jar` dieses Rechners. Fehlt eine passende Version, sagt der Launcher das,
//! lädt aber selbst nichts nach.

use std::io::Read;
use std::path::Path;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;

use crate::icon::{ImageFormat, sniff};
use crate::icon_editor::{Texture, TextureSet, client_versions};
use crate::{Error, Launcher, Result};

const PREFIX: &str = "assets/minecraft/textures/";
const MAX_TEXTURE_BYTES: u64 = 512 * 1024;

/// Pflicht-Texturen: Schlüssel und Pfade ab dem Textur-Ordner (neuere Pfade zuerst).
const REQUIRED: &[(&str, &[&str])] = &[
    ("stone", &["block/stone"]),
    ("cobblestone", &["block/cobblestone"]),
    ("bricks", &["block/bricks"]),
    ("copper_block", &["block/copper_block"]),
    ("oak_planks", &["block/oak_planks"]),
    ("obsidian", &["block/obsidian"]),
    ("quartz_block_side", &["block/quartz_block_side"]),
    ("redstone_block", &["block/redstone_block"]),
    ("tnt_side", &["block/tnt_side"]),
    ("tnt_top", &["block/tnt_top"]),
    ("redstone_lamp", &["block/redstone_lamp"]),
    ("redstone_lamp_on", &["block/redstone_lamp_on"]),
    ("piston_top", &["block/piston_top"]),
    ("iron_block", &["block/iron_block"]),
    ("dropper_front", &["block/dropper_front"]),
    ("deepslate_bricks", &["block/deepslate_bricks"]),
    ("polished_blackstone_bricks", &["block/polished_blackstone_bricks"]),
    ("deepslate_tiles", &["block/deepslate_tiles"]),
    ("redstone_dust_line0", &["block/redstone_dust_line0"]),
    ("lever", &["block/lever"]),
    ("deepslate", &["block/deepslate"]),
    ("netherrack", &["block/netherrack"]),
    ("nether_bricks", &["block/nether_bricks"]),
    ("magma", &["block/magma"]),
    ("lava_still", &["block/lava_still"]),
    ("coal_ore", &["block/coal_ore"]),
    ("iron_ore", &["block/iron_ore"]),
    ("redstone_ore", &["block/redstone_ore"]),
    ("deepslate_redstone_ore", &["block/deepslate_redstone_ore"]),
    ("diamond_ore", &["block/diamond_ore"]),
    ("oak_log", &["block/oak_log"]),
    ("rail", &["block/rail"]),
    ("powered_rail", &["block/powered_rail"]),
    ("powered_rail_on", &["block/powered_rail_on"]),
    ("torch", &["block/torch"]),
    ("redstone", &["item/redstone"]),
    ("creeper", &["entity/creeper/creeper"]),
    ("steve", &["entity/player/wide/steve", "entity/steve"]),
    ("minecart", &["entity/minecart", "entity/minecart/minecart"]),
    ("packed_ice", &["block/packed_ice"]),
    ("blue_ice", &["block/blue_ice"]),
    ("snow", &["block/snow"]),
    ("spruce_planks", &["block/spruce_planks"]),
    ("spruce_log", &["block/spruce_log"]),
    ("stripped_spruce_log", &["block/stripped_spruce_log"]),
    ("podzol_top", &["block/podzol_top"]),
];

/// Nur für den Fuchsbau-Look, den es erst ab 1.20 gibt: Fehlen sie, nimmt der Look Fichte.
const OPTIONAL: &[(&str, &[&str])] = &[
    ("cherry_planks", &["block/cherry_planks"]),
    ("cherry_log", &["block/cherry_log"]),
    ("cherry_leaves", &["block/cherry_leaves"]),
];

fn no_textures() -> Error {
    Error::validation(crate::msg!(
        "minigames.noTextures",
        "Für die Minispiele fehlen die Minecraft-Texturen. Starte einmal eine Instanz mit Minecraft 1.17 oder neuer."
    ))
}

fn read_texture(archive: &mut zip::ZipArchive<std::fs::File>, candidates: &[&str]) -> Option<Vec<u8>> {
    for path in candidates {
        let Ok(entry) = archive.by_name(&format!("{PREFIX}{path}.png")) else { continue };
        if entry.size() > MAX_TEXTURE_BYTES {
            continue;
        }
        let mut bytes = Vec::new();
        if entry.take(MAX_TEXTURE_BYTES).read_to_end(&mut bytes).is_ok() && sniff(&bytes) == Some(ImageFormat::Png) {
            return Some(bytes);
        }
    }
    None
}

fn texture(key: &str, bytes: &[u8]) -> Texture {
    Texture { key: key.to_owned(), data_url: format!("data:image/png;base64,{}", STANDARD.encode(bytes)) }
}

/// Liest alle Texturen aus einer Jar. `None`, wenn eine Pflicht-Textur fehlt (Version zu alt oder Datei beschädigt).
fn read_jar(jar: &Path, version: &str) -> Option<TextureSet> {
    let file = std::fs::File::open(jar).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let mut textures = Vec::with_capacity(REQUIRED.len() + OPTIONAL.len());
    for (key, candidates) in REQUIRED {
        textures.push(texture(key, &read_texture(&mut archive, candidates)?));
    }
    for (key, candidates) in OPTIONAL {
        if let Some(bytes) = read_texture(&mut archive, candidates) {
            textures.push(texture(key, &bytes));
        }
    }
    Some(TextureSet { version: version.to_owned(), textures })
}

impl Launcher {
    /// Texturen für die Minispiele aus der neuesten geladenen Version, die alle Pflicht-Texturen hat.
    pub async fn minigame_textures(&self) -> Result<TextureSet> {
        let paths = self.paths().clone();
        tokio::task::spawn_blocking(move || {
            client_versions(&paths).into_iter().find_map(|version| read_jar(&paths.version_jar(&version), &version)).ok_or_else(no_textures)
        })
        .await
        .map_err(|e| Error::Internal(e.to_string()))?
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    /// Kleinstes gültiges PNG-Kennzeichen reicht hier: Die Prüfung liest nur die Signatur.
    const PNG: &[u8] = &[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0];

    fn write_jar(path: &Path, skip: &[&str]) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        for (key, candidates) in REQUIRED.iter().chain(OPTIONAL) {
            if skip.contains(key) {
                continue;
            }
            zip.start_file(format!("{PREFIX}{}.png", candidates[0]), zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(PNG).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn reads_every_texture_as_data_url() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("c.jar");
        write_jar(&jar, &[]);
        let set = read_jar(&jar, "1.21.11").unwrap();
        assert_eq!(set.textures.len(), REQUIRED.len() + OPTIONAL.len());
        assert!(set.textures.iter().all(|t| t.data_url.starts_with("data:image/png;base64,")));
    }

    #[test]
    fn optional_textures_may_be_missing() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("c.jar");
        write_jar(&jar, &["cherry_planks", "cherry_log", "cherry_leaves"]);
        assert_eq!(read_jar(&jar, "1.17.1").unwrap().textures.len(), REQUIRED.len());
    }

    #[test]
    fn missing_required_texture_rejects_the_jar() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("c.jar");
        write_jar(&jar, &["deepslate"]);
        assert!(read_jar(&jar, "1.16.5").is_none());
    }

    #[test]
    fn falls_back_to_the_older_path() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("c.jar");
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&jar).unwrap());
        zip.start_file(format!("{PREFIX}entity/steve.png"), zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(PNG).unwrap();
        zip.finish().unwrap();
        let mut archive = zip::ZipArchive::new(std::fs::File::open(&jar).unwrap()).unwrap();
        assert!(read_texture(&mut archive, &["entity/player/wide/steve", "entity/steve"]).is_some());
    }

    #[test]
    fn broken_jar_is_not_a_panic() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("c.jar");
        std::fs::write(&jar, b"kein zip").unwrap();
        assert!(read_jar(&jar, "1.21.11").is_none());
    }
}
