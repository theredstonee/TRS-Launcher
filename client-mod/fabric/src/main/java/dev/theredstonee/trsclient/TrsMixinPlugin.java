package dev.theredstonee.trsclient;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Lädt die Mixins, die nur der Selbsttest braucht (echte Maus-/Tastatur-Eingabe), ausschließlich
 * mit {@code -Dtrsclient.autotest=true}. Im normalen Spiel greifen sie nirgends ein – so kann
 * ein Test-Hilfsmittel nie den Spielstart gefährden.
 */
public final class TrsMixinPlugin implements IMixinConfigPlugin {
	private static final String[] TEST_ONLY = {"MouseHandlerAccessor", "KeyboardHandlerAccessor"};

	/**
	 * Welt-Details, die OptiFine (OptiFabric) bzw. Sodium Extra selbst mitbringen: deren Mixins bleiben
	 * dann ganz weg – keine zwei Eingriffe an derselben Stelle (das Menü zeigt „übernimmt …“).
	 */
	private static final String[] DETAIL_MIXINS = {"SkyMixin", "StarsMixin", "WeatherMixin", "FogMixin", "TextureAnimationMixin"};
	private static final String[] DETAIL_MODS = {"optifabric", "sodium-extra", "sodiumextra"};

	/** Benchmark „Vanilla“: nur die Bildzeit-Messung vor jedem Bild, sonst kein einziger TRS-Eingriff. */
	private static final boolean BENCH_VANILLA = Boolean.getBoolean("trsclient.bench.vanilla");

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (BENCH_VANILLA) return mixinClassName.endsWith(".FramePaceMixin");
		for (String name : TEST_ONLY) {
			if (mixinClassName.endsWith("." + name)) return Boolean.getBoolean("trsclient.autotest");
		}
		for (String name : DETAIL_MIXINS) {
			if (mixinClassName.endsWith("." + name)) return !anyLoaded(DETAIL_MODS);
		}
		return true;
	}

	private static boolean anyLoaded(String[] ids) {
		try {
			for (String id : ids) {
				if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(id)) return true;
			}
		} catch (RuntimeException | LinkageError e) {
			// Loader noch nicht bereit – dann lieber anwenden (require = 0, Laufzeitprüfung greift trotzdem).
		}
		return false;
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
