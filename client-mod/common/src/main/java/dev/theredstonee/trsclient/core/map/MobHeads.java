package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Köpfe der Kreaturen für die Minimap: das Gesicht (Vorderseite des Kopf-Würfels) wird direkt aus der
 * Entity-Textur des geladenen Resource Packs geschnitten – so passen die Köpfe zu jedem Pack. Je Art eine Liste
 * möglicher Texturpfade (Minecraft hat sie über die Versionen umbenannt); genommen wird der erste, den es gibt.
 * Die Höhe der Textur (64×32 oder 64×64 …) kommt aus der Datei selbst, HD-Packs skalieren mit.
 *
 * <p>Arten ohne Eintrag (oder ohne gefundene Textur) zeigt die Karte als Symbol. Nur Render-/Spiel-Thread.
 */
public final class MobHeads {
	/** Ein möglicher Texturpfad und wo das Gesicht darin liegt (in Texeln bei der logischen Breite {@code texWidth}). */
	static final class Face {
		final String location;
		final int texWidth;
		final int u, v, w, h;
		/** Zweite Ebene (Hut) an dieser Stelle (u, v; gleiche Größe) oder -1. */
		final int hatU, hatV;

		Face(String path, int texWidth, int u, int v, int w, int h, int hatU, int hatV) {
			this.location = "minecraft:textures/entity/" + path;
			this.texWidth = texWidth;
			this.u = u;
			this.v = v;
			this.w = w;
			this.h = h;
			this.hatU = hatU;
			this.hatV = hatV;
		}
	}

	/** Fertig aufgelöster Kopf: Textur (logische Größe) + Gesichtsausschnitt. */
	public static final class Head {
		public final TextureRef texture;
		public final int u, v, w, h;
		public final int hatU, hatV;

		Head(TextureRef texture, Face f) {
			this.texture = texture;
			this.u = f.u;
			this.v = f.v;
			this.w = f.w;
			this.h = f.h;
			this.hatU = f.hatU;
			this.hatV = f.hatV;
		}
	}

	private static final Map<String, Face[]> FACES = new HashMap<String, Face[]>();
	/** Legacy-Namen (bis 1.10: EntityList-Zeichenketten; 1.11–1.12 alte IDs) → heutige ID. */
	private static final Map<String, String> LEGACY = new HashMap<String, String>();

	static {
		// Menschenähnliche Köpfe 8×8×8 an (0,0) → Gesicht (8,8); Zombies mit Hut-Ebene (40,8).
		face("zombie", f("zombie/zombie.png", 64, 8, 8, 8, 8, 40, 8));
		face("husk", f("zombie/husk.png", 64, 8, 8, 8, 8, 40, 8));
		face("drowned", f("zombie/drowned.png", 64, 8, 8, 8, 8, 40, 8));
		face("zombie_villager", f("zombie_villager/zombie_villager.png", 64, 8, 8, 8, 10, -1, -1),
				f("zombie_villager.png", 64, 8, 8, 8, 10, -1, -1));
		face("skeleton", f("skeleton/skeleton.png", 64, 8, 8, 8, 8, -1, -1));
		face("stray", f("skeleton/stray.png", 64, 8, 8, 8, 8, -1, -1));
		face("wither_skeleton", f("skeleton/wither_skeleton.png", 64, 8, 8, 8, 8, -1, -1));
		face("creeper", f("creeper/creeper.png", 64, 8, 8, 8, 8, -1, -1));
		face("enderman", f("enderman/enderman.png", 64, 8, 8, 8, 8, -1, -1));
		face("slime", f("slime/slime.png", 64, 8, 8, 8, 8, -1, -1));
		face("blaze", f("blaze.png", 64, 8, 8, 8, 8, -1, -1));
		face("snow_golem", f("snow_golem.png", 64, 8, 8, 8, 8, -1, -1), f("snowman.png", 64, 8, 8, 8, 8, -1, -1));
		// Spinnen: Kopf 8×8×8 an (32,4).
		face("spider", f("spider/spider.png", 64, 40, 12, 8, 8, -1, -1));
		face("cave_spider", f("spider/cave_spider.png", 64, 40, 12, 8, 8, -1, -1));
		// Ghast: Körper 16×16×16.
		face("ghast", f("ghast/ghast.png", 64, 16, 16, 16, 16, -1, -1));
		// Dorfbewohner-Köpfe 8×10×8.
		face("villager", f("villager/villager.png", 64, 8, 8, 8, 10, -1, -1));
		face("wandering_trader", f("wandering_trader.png", 64, 8, 8, 8, 10, -1, -1));
		face("witch", f("witch.png", 64, 8, 8, 8, 10, -1, -1));
		face("pillager", f("illager/pillager.png", 64, 8, 8, 8, 10, -1, -1));
		face("vindicator", f("illager/vindicator.png", 64, 8, 8, 8, 10, -1, -1));
		face("evoker", f("illager/evoker.png", 64, 8, 8, 8, 10, -1, -1));
		face("illusioner", f("illager/illusioner.png", 64, 8, 8, 8, 10, -1, -1));
		face("iron_golem", f("iron_golem/iron_golem.png", 128, 8, 8, 8, 10, -1, -1), f("iron_golem.png", 128, 8, 8, 8, 10, -1, -1));
		// Piglins: Kopf 10×8×8; alter Zombie-Pigman mit Zombie-Kopf.
		face("piglin", f("piglin/piglin.png", 64, 8, 8, 10, 8, -1, -1));
		face("piglin_brute", f("piglin/piglin_brute.png", 64, 8, 8, 10, 8, -1, -1));
		face("zombified_piglin", f("piglin/zombified_piglin.png", 64, 8, 8, 10, 8, -1, -1),
				f("zombie_pigman.png", 64, 8, 8, 8, 8, 40, 8));
		// Tiere.
		face("cow", f("cow/temperate_cow.png", 64, 6, 6, 8, 8, -1, -1), f("cow/cow.png", 64, 6, 6, 8, 8, -1, -1));
		face("mooshroom", f("cow/red_mooshroom.png", 64, 6, 6, 8, 8, -1, -1), f("cow/mooshroom.png", 64, 6, 6, 8, 8, -1, -1));
		face("pig", f("pig/temperate_pig.png", 64, 8, 8, 8, 8, -1, -1), f("pig/pig.png", 64, 8, 8, 8, 8, -1, -1));
		face("sheep", f("sheep/sheep.png", 64, 8, 8, 6, 6, -1, -1));
		face("chicken", f("chicken/temperate_chicken.png", 64, 3, 3, 4, 6, -1, -1), f("chicken.png", 64, 3, 3, 4, 6, -1, -1));
		face("wolf", f("wolf/wolf.png", 64, 4, 4, 6, 6, -1, -1));
		face("cat", f("cat/tabby.png", 64, 5, 5, 5, 4, -1, -1), f("cat/ocelot.png", 64, 5, 5, 5, 4, -1, -1));
		face("ocelot", f("cat/ocelot.png", 64, 5, 5, 5, 4, -1, -1));
		face("fox", f("fox/fox.png", 48, 7, 11, 8, 6, -1, -1));
		face("polar_bear", f("bear/polarbear.png", 128, 7, 7, 7, 7, -1, -1));
		face("squid", f("squid/squid.png", 64, 12, 12, 12, 16, -1, -1), f("squid.png", 64, 12, 12, 12, 16, -1, -1));
		face("glow_squid", f("squid/glow_squid.png", 64, 12, 12, 12, 16, -1, -1));

		LEGACY.put("pig_zombie", "zombified_piglin");
		LEGACY.put("zombie_pigman", "zombified_piglin");
		LEGACY.put("villager_golem", "iron_golem");
		LEGACY.put("snow_man", "snow_golem");
		LEGACY.put("snowman", "snow_golem");
		LEGACY.put("mushroom_cow", "mooshroom");
		LEGACY.put("ozelot", "ocelot");
		LEGACY.put("lava_slime", "magma_cube");
		LEGACY.put("entity_horse", "horse");
		LEGACY.put("wither_boss", "wither");
		LEGACY.put("ender_dragon", "ender_dragon");
		LEGACY.put("illusion_illager", "illusioner");
		LEGACY.put("evocation_illager", "evoker");
		LEGACY.put("vindication_illager", "vindicator");
	}

	private static Face f(String path, int texWidth, int u, int v, int w, int h, int hatU, int hatV) {
		return new Face(path, texWidth, u, v, w, h, hatU, hatV);
	}

	private static void face(String id, Face... faces) {
		FACES.put(id, faces);
	}

	private final Map<String, Object> cache = new HashMap<String, Object>();
	private static final Object NONE = new Object();
	private int generation = -1;

	/**
	 * Heutige Kurz-ID ohne Namensraum: {@code minecraft:zombie} → {@code zombie}, Legacy {@code PigZombie} →
	 * {@code zombified_piglin}, {@code EntityHorse} → {@code horse}. null/leer → "".
	 */
	public static String normalize(String kind) {
		if (kind == null || kind.isEmpty()) return "";
		String k = kind;
		int colon = k.indexOf(':');
		if (colon >= 0) {
			if (!k.startsWith("minecraft:")) return k.toLowerCase(Locale.ROOT);
			k = k.substring(colon + 1);
		}
		// CamelCase → snake_case (Legacy-Namen wie "CaveSpider").
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < k.length(); i++) {
			char ch = k.charAt(i);
			if (Character.isUpperCase(ch)) {
				if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
				sb.append(Character.toLowerCase(ch));
			} else {
				sb.append(ch);
			}
		}
		String id = sb.toString();
		String mapped = LEGACY.get(id);
		return mapped != null ? mapped : id;
	}

	/** Gibt es für diese Art einen Kopf-Eintrag? */
	public static boolean known(String kind) {
		return FACES.containsKey(normalize(kind));
	}

	/**
	 * Kopf einer Art oder null (keine Textur bekannt/vorhanden → Symbol zeichnen). Die Texturgröße wird beim ersten
	 * Mal im Hintergrund nachgesehen – bis dahin null.
	 *
	 * @param paletteGeneration ändert sich bei Resource-Reload (Cache verwerfen)
	 */
	public Head head(String kind, TexturePalette palette, int paletteGeneration) {
		if (kind == null || palette == null) return null;
		if (paletteGeneration != generation) {
			cache.clear();
			generation = paletteGeneration;
		}
		Object cached = cache.get(kind);
		if (cached == NONE) return null;
		if (cached != null) return (Head) cached;
		Face[] faces = FACES.get(normalize(kind));
		if (faces == null) {
			cache.put(kind, NONE);
			return null;
		}
		for (Face f : faces) {
			int[] size = palette.imageSize(f.location);
			if (size == null) return null; // wird noch nachgesehen
			if (size[0] <= 0) continue;
			Textures.Store store = Textures.store();
			if (store == null) return null;
			int texHeight = logicalHeight(f.texWidth, size[0], size[1]);
			TextureRef ref = store.game(f.location, f.texWidth, texHeight);
			if (ref == null) continue;
			Head h = new Head(ref, f);
			cache.put(kind, h);
			return h;
		}
		cache.put(kind, NONE);
		return null;
	}

	/** Logische Höhe einer Textur: gleiche Seitenverhältnisse wie die Datei (HD-Packs), bei logischer Breite {@code texWidth}. */
	static int logicalHeight(int texWidth, int pixelWidth, int pixelHeight) {
		if (pixelWidth <= 0 || pixelHeight <= 0) return texWidth;
		return Math.max(1, Math.round(texWidth * (float) pixelHeight / pixelWidth));
	}
}
