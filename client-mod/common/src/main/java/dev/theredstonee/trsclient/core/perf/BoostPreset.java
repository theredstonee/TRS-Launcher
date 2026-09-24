package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.i18n.I18n;

/**
 * Ein-Klick-Stufen des FPS-Boosts. Setzt die Leistungs-Module auf feste Werte und senkt
 * Vanilla-Optionen, die bremsen (erhöht nie etwas). Alles landet vorher in {@link UndoLog}.
 */
public enum BoostPreset {
	/** Sanft: kaum sichtbare Unterschiede. */
	LOW(30, 30, 96, 64, 48, 48, 4000, 100, false, false, false, false),
	/** Ausgewogen. */
	MEDIUM(15, 30, 64, 48, 32, 32, 2000, 75, false, true, false, false),
	/** Maximale Bildrate, weniger Details. */
	HIGH(10, 15, 48, 32, 24, 24, 1000, 50, true, true, true, true);

	final int unfocusedFps;
	final int afkFps;
	final int entities;
	final int blockEntities;
	final int nameTags;
	final int items;
	final int particleLimit;
	final int particleAmount;
	final boolean noExplosions;
	final boolean noRain;
	final boolean noSmoke;
	/** Welt-Details: Sterne, Wetter und Textur-Animationen aus. */
	final boolean details;

	BoostPreset(int unfocusedFps, int afkFps, int entities, int blockEntities, int nameTags, int items, int particleLimit,
			int particleAmount, boolean noExplosions, boolean noRain, boolean noSmoke, boolean details) {
		this.unfocusedFps = unfocusedFps;
		this.afkFps = afkFps;
		this.entities = entities;
		this.blockEntities = blockEntities;
		this.nameTags = nameTags;
		this.items = items;
		this.particleLimit = particleLimit;
		this.particleAmount = particleAmount;
		this.noExplosions = noExplosions;
		this.noRain = noRain;
		this.noSmoke = noSmoke;
		this.details = details;
	}

	public String label() {
		return I18n.trOr("perf.boost." + name().toLowerCase(java.util.Locale.ROOT), name());
	}

	/** Neue Werte der Vanilla-Optionen (nur Senkungen; {@link GameOptions#NONE} = unverändert). */
	public int target(GameOptions.Opt opt, int current, int view, boolean smoothIsBoolean) {
		if (current == GameOptions.NONE) return GameOptions.NONE;
		int t;
		switch (opt) {
			case VSYNC:
				t = 0;
				break;
			case VIEW_DISTANCE:
				t = this == HIGH ? 12 : (this == MEDIUM ? 16 : current);
				break;
			case SIMULATION_DISTANCE:
				t = Math.max(5, view - 2);
				break;
			case CLOUDS:
				t = this == HIGH ? 0 : 1;
				break;
			case MIPMAP:
				t = this == HIGH ? 1 : 2;
				break;
			case GRAPHICS:
				// Benutzerdefinierte Voreinstellung (ab 1.21.11) bleibt, wie sie ist.
				if (this == LOW || current == 3) return GameOptions.NONE;
				t = 0;
				break;
			case PARTICLES:
				if (this == LOW) return GameOptions.NONE;
				// Höhere Zahl = weniger Partikel.
				return current >= (this == HIGH ? 2 : 1) ? GameOptions.NONE : (this == HIGH ? 2 : 1);
			case BIOME_BLEND:
				if (this == LOW) return GameOptions.NONE;
				t = this == HIGH ? 1 : 2;
				break;
			case ENTITY_DISTANCE:
				if (this == LOW) return GameOptions.NONE;
				t = this == HIGH ? 75 : 100;
				break;
			case SMOOTH_LIGHTING:
				if (this == LOW) return GameOptions.NONE;
				if (this == MEDIUM) {
					if (smoothIsBoolean) return GameOptions.NONE;
					t = 1;
				} else {
					t = 0;
				}
				break;
			default:
				return GameOptions.NONE;
		}
		return t < current ? t : GameOptions.NONE;
	}
}
