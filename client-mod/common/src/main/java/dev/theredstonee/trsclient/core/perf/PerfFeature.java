package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.i18n.I18n;

/**
 * Einzelne Leistungs-Funktionen des TRS Clients. Pro Minecraft-Version meldet der Loader, welche es
 * dort gibt ({@link PerfCompat#supported}); installierte Leistungs-Mods können einzelne übernehmen
 * ({@link PerfMod#takesOver}) – dann bleibt die TRS-Variante aus, damit nichts doppelt läuft.
 */
public enum PerfFeature {
	DYNAMIC_FPS("dynamicFps", "Dynamic FPS"),
	BACKGROUND_VOLUME("backgroundVolume", "Quieter in the background"),
	ENTITY_DISTANCE("entityDistance", "Entity distance"),
	ENTITY_OCCLUSION("entityOcclusion", "Hide entities behind walls"),
	BLOCK_ENTITY_DISTANCE("blockEntityDistance", "Block entity distance"),
	NAMETAG_DISTANCE("nameTagDistance", "Name tag distance"),
	ITEM_DISTANCE("itemDistance", "Dropped item distance"),
	FRAME_DISTANCE("frameDistance", "Item frame distance"),
	PARTICLE_LIMIT("particleLimit", "Particle limit"),
	PARTICLE_AMOUNT("particleAmount", "Particle amount"),
	PARTICLE_EXPLOSIONS("particleExplosions", "Explosion particles"),
	PARTICLE_RAIN("particleRain", "Rain splashes"),
	PARTICLE_SMOKE("particleSmoke", "Smoke particles"),
	SKY("sky", "Sky"),
	STARS("stars", "Stars"),
	FOG("fog", "Fog"),
	WEATHER("weather", "Rain and snow"),
	TEXTURE_ANIMATIONS("textureAnimations", "Texture animations");

	private final String id;
	private final String fallback;

	PerfFeature(String id, String fallback) {
		this.id = id;
		this.fallback = fallback;
	}

	public String id() {
		return id;
	}

	/** Übersetzungsschlüssel "perf.feature.<id>". */
	public String key() {
		return "perf.feature." + id;
	}

	/** Name in der aktiven Sprache. */
	public String label() {
		return I18n.trOr(key(), fallback);
	}
}
