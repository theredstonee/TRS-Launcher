//! Mods, die ein Mixin-Fehler eindeutig als Verursacher nennt – die Mod mit dem
//! kaputten Mixin, nicht die Mods, in die es greifen wollte.

use super::InstalledMod;
use super::parse::{self, ModIndex, Text};

const NEEDLES: &[&str] = &["Mixin apply for mod ", "Mixin apply failed", "failed injection check", "Critical injection failure"];

fn id_after<'a>(line: &'a str, marker: &str) -> Option<&'a str> {
    let rest = &line[line.find(marker)? + marker.len()..];
    let end = rest.find(|c: char| !(c.is_ascii_alphanumeric() || matches!(c, '_' | '-'))).unwrap_or(rest.len());
    Some(&rest[..end])
}

/// Dateinamen eingeschalteter Mods, deren Mixin beim Laden scheitert.
/// `@Redirect`-Konflikte zwischen zwei Mods bleiben außen vor (wer schuld ist, ist offen).
pub fn mixin_culprits(log: &str, report: Option<&str>, installed: &[InstalledMod]) -> Vec<String> {
    let text = Text::new(log, report);
    let index = ModIndex::new(installed, &text);
    let mut ids: Vec<String> = Vec::new();
    for line in text.lines_with(NEEDLES) {
        if line.contains("@Redirect conflict") || line.contains("already redirected by") {
            continue;
        }
        let mut found: Vec<String> = id_after(line, "Mixin apply for mod ").filter(|id| parse::is_mod_id(id)).map(str::to_owned).into_iter().collect();
        if found.is_empty() {
            found = parse::from_mod(line);
        }
        if found.is_empty() {
            found = parse::mixin_configs(line).iter().filter_map(|c| index.by_mixin_config(c)).collect();
        }
        for id in found {
            if !parse::is_platform_id(&id) && !ids.contains(&id) {
                ids.push(id);
            }
        }
    }
    let mut files: Vec<String> = Vec::new();
    for file in ids.iter().filter_map(|id| index.file_of(id)) {
        if !files.contains(&file) {
            files.push(file);
        }
    }
    files
}

#[cfg(test)]
mod tests {
    use super::*;

    fn installed(file: &str, id: &str, config: &str) -> InstalledMod {
        InstalledMod {
            file_name: file.into(),
            enabled: true,
            id: Some(id.into()),
            provides: Vec::new(),
            nested: Vec::new(),
            name: None,
            version: None,
            icon_url: None,
            packages: Vec::new(),
            mixin_configs: vec![config.into()],
            modified: None,
        }
    }

    #[test]
    fn names_the_mod_with_the_broken_mixin() {
        let mods = [
            installed("moreculling-1.20.4-0.21.0.jar", "moreculling", "moreculling.mixins.json"),
            installed("sodium-fabric-mc1.20-0.4.10+build.27.jar", "sodium", "sodium.mixins.json"),
            installed("modernfix-fabric-5.0.2+mc1.20.jar", "modernfix", "modernfix-common.mixins.json"),
        ];
        // Aus dem Fehlerbericht (MC 1.20, More Culling 0.21 mit Sodium 0.4.10).
        let log = "[WARN] Method overwrite conflict for method_21740 in modernfix-common.mixins.json:perf.BiomeMixin from mod modernfix, previously written by x. Skipping method.\n\
[ERROR] Mixin apply for mod moreculling failed moreculling.mixins.json:compat.ItemRenderer_sodiumMixin from mod moreculling -> net.minecraft.class_918: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException Critical injection failure: @Redirect annotation on moreculling$modifyDirections$Sodium could not find any targets matching '@MixinSquared:Handler[mixin='me.jellysquid.mods.sodium.mixin.features.render.model.item.ItemRendererMixin', name='renderModelFast', prefix='null']' in net/minecraft/class_918.";
        assert_eq!(mixin_culprits(log, None, &mods), ["moreculling-1.20.4-0.21.0.jar"]);
        // Forge nennt nur die Konfiguration.
        let forge = "Mixin apply failed sodium.mixins.json:core.MinecraftMixin -> net.minecraft.client.Minecraft: InvalidInjectionException";
        assert_eq!(mixin_culprits(forge, None, &mods), ["sodium-fabric-mc1.20-0.4.10+build.27.jar"]);
        assert!(mixin_culprits("@Redirect conflict. Skipping moreculling.mixins.json:X from mod moreculling->@Redirect::a already redirected by sodium.mixins.json:Y from mod sodium", None, &mods).is_empty());
        assert!(mixin_culprits("Loading 70 mods", None, &mods).is_empty());
    }
}
