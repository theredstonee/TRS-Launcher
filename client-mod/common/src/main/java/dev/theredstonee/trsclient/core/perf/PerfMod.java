package dev.theredstonee.trsclient.core.perf;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Bekannte Leistungs-Mods. Ist einer installiert, übernimmt er die genannten Funktionen: die
 * TRS-Variante bleibt dann aus (keine doppelten Eingriffe an derselben Stelle), das Menü zeigt
 * „übernimmt &lt;Mod&gt;“. Mods ohne übernommene Funktion werden nur als „erkannt“ gelistet.
 * Die IDs sind die Mod-IDs der Loader (Fabric, Forge, NeoForge); OptiFine wird zusätzlich an
 * seinen Klassen erkannt (siehe {@link PerfCompat}).
 */
public enum PerfMod {
	SODIUM("Sodium", new String[]{"sodium"}, EnumSet.of(PerfFeature.ENTITY_OCCLUSION)),
	EMBEDDIUM("Embeddium", new String[]{"embeddium"}, EnumSet.of(PerfFeature.ENTITY_OCCLUSION)),
	RUBIDIUM("Rubidium", new String[]{"rubidium"}, EnumSet.of(PerfFeature.ENTITY_OCCLUSION)),
	OPTIFINE("OptiFine", new String[]{"optifine", "optifabric"}, EnumSet.of(PerfFeature.SKY, PerfFeature.STARS,
			PerfFeature.FOG, PerfFeature.WEATHER, PerfFeature.TEXTURE_ANIMATIONS, PerfFeature.PARTICLE_EXPLOSIONS,
			PerfFeature.PARTICLE_RAIN, PerfFeature.PARTICLE_SMOKE)),
	SODIUM_EXTRA("Sodium Extra", new String[]{"sodium-extra", "sodiumextra", "embeddium_extra", "rubidium_extra"},
			EnumSet.of(PerfFeature.SKY, PerfFeature.STARS, PerfFeature.FOG, PerfFeature.WEATHER,
					PerfFeature.TEXTURE_ANIMATIONS, PerfFeature.PARTICLE_EXPLOSIONS, PerfFeature.PARTICLE_RAIN,
					PerfFeature.PARTICLE_SMOKE)),
	ENTITY_CULLING("EntityCulling", new String[]{"entityculling"}, EnumSet.of(PerfFeature.ENTITY_OCCLUSION)),
	DYNAMIC_FPS("Dynamic FPS", new String[]{"dynamic_fps", "dynamicfps"},
			EnumSet.of(PerfFeature.DYNAMIC_FPS, PerfFeature.BACKGROUND_VOLUME)),
	MORE_CULLING("MoreCulling", new String[]{"moreculling"}, EnumSet.of(PerfFeature.FRAME_DISTANCE)),
	PATCHER("Patcher", new String[]{"patcher"}, EnumSet.of(PerfFeature.DYNAMIC_FPS, PerfFeature.ENTITY_DISTANCE,
			PerfFeature.BLOCK_ENTITY_DISTANCE, PerfFeature.ENTITY_OCCLUSION, PerfFeature.PARTICLE_LIMIT)),
	IMMEDIATELY_FAST("ImmediatelyFast", new String[]{"immediatelyfast"}, EnumSet.noneOf(PerfFeature.class)),
	NVIDIUM("Nvidium", new String[]{"nvidium"}, EnumSet.noneOf(PerfFeature.class)),
	BAD_OPTIMIZATIONS("BadOptimizations", new String[]{"badoptimizations"}, EnumSet.noneOf(PerfFeature.class)),
	MODERNFIX("ModernFix", new String[]{"modernfix"}, EnumSet.noneOf(PerfFeature.class)),
	FERRITECORE("FerriteCore", new String[]{"ferritecore"}, EnumSet.noneOf(PerfFeature.class)),
	LITHIUM("Lithium", new String[]{"lithium"}, EnumSet.noneOf(PerfFeature.class)),
	VINTAGEFIX("VintageFix", new String[]{"vintagefix"}, EnumSet.noneOf(PerfFeature.class)),
	VANILLAFIX("VanillaFix", new String[]{"vanillafix"}, EnumSet.noneOf(PerfFeature.class)),
	FOAMFIX("FoamFix", new String[]{"foamfix"}, EnumSet.noneOf(PerfFeature.class));

	private final String displayName;
	private final String[] ids;
	private final Set<PerfFeature> takesOver;

	PerfMod(String displayName, String[] ids, EnumSet<PerfFeature> takesOver) {
		this.displayName = displayName;
		this.ids = ids;
		this.takesOver = Collections.unmodifiableSet(takesOver);
	}

	/** Name für das Menü (Eigenname, wird nicht übersetzt). */
	public String displayName() {
		return displayName;
	}

	/** Mod-IDs, unter denen die Loader ihn kennen. */
	public String[] ids() {
		return ids.clone();
	}

	/** Funktionen, die dieser Mod selbst mitbringt – dort bleibt TRS aus. */
	public Set<PerfFeature> takesOver() {
		return takesOver;
	}

	/** Ersetzt den Chunk-Renderer (Sodium & Co.) – zählt für den Hinweis „Sodium fehlt“. */
	public boolean isRenderer() {
		return this == SODIUM || this == EMBEDDIUM || this == RUBIDIUM || this == OPTIFINE;
	}
}
