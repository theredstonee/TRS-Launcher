package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;

/**
 * Einstellungen der Umhang-Physik aus dem Menü (Stil, Wind, Bewegung, Schwerkraft, Anhebung, Steifheit,
 * Detailstufe) und ihre Umrechnung in Simulations-Parameter und Gittergrößen. Die Standardwerte ergeben
 * genau das bisherige Verhalten (glatter Stoff 10 × 16, Wellen, schwingende Bewegung).
 */
public final class CapeSettings {
	/** Aussehen: glatter Stoff oder Vanilla-artige Stufen (übereinanderliegende Streifen). */
	public enum Style implements ChoiceSetting.Option {
		SMOOTH("Smooth"),
		BLOCKY("Blocky");

		private final String label;

		Style(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Wind ohne eigene Bewegung: keiner, gleichmäßige Wellen oder Böen. */
	public enum Wind implements ChoiceSetting.Option {
		OFF("Off"),
		WAVES("Waves"),
		GUSTS("Gusts");

		private final String label;

		Wind(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Wie der Stoff der Bewegung des Körpers folgt. */
	public enum Movement implements ChoiceSetting.Option {
		/** Folgt dem Körper eng, fast wie ein Brett – nahe am Vanilla-Umhang. */
		VANILLA("Vanilla"),
		/** Freies Schwingen (bisheriges Verhalten). */
		SWINGING("Swinging"),
		/** Ruhig und träge, langsame weiche Wellen (wie in Minecraft Dungeons). */
		DUNGEONS("Dungeons");

		private final String label;

		Movement(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Detailstufe: Gittergröße, Reichweite und Anzahl simulierter Umhänge. */
	public enum Detail implements ChoiceSetting.Option {
		HIGH("High"),
		MEDIUM("Medium"),
		LOW("Low");

		private final String label;

		Detail(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public Style style = Style.SMOOTH;
	public Wind wind = Wind.WAVES;
	/** Windstärke 0–2 (Flattern, Wellen, Böen). */
	public float windStrength = 1f;
	public Movement movement = Movement.SWINGING;
	/** Schwerkraft 0,25–2. */
	public float gravity = 1f;
	/** Anhebung durch den Fahrtwind beim Laufen 0–2. */
	public float height = 1f;
	/** Biege-Steifheit 0–2. */
	public float stiffness = 1f;
	public Detail detail = Detail.HIGH;

	/** Überträgt die Einstellungen in die Parameter der Simulation. */
	public void apply(ClothSim.Params p) {
		p.windMode = wind == Wind.OFF ? ClothSim.WIND_OFF : (wind == Wind.GUSTS ? ClothSim.WIND_GUSTS : ClothSim.WIND_WAVES);
		p.wind = clamp(windStrength, 0f, 2f);
		p.gravity = clamp(gravity, 0.25f, 2f);
		p.lift = clamp(height, 0f, 2f);
		float stiff = clamp(stiffness, 0f, 2f);
		switch (movement) {
			case VANILLA:
				p.strength = 0.5f;
				p.turn = 0.6f;
				p.damping = 0.965f;
				p.waveSpeed = 1f;
				p.stiffness = stiff * 2.5f;
				break;
			case DUNGEONS:
				p.strength = 0.8f;
				p.turn = 0.5f;
				p.damping = 0.98f;
				p.waveSpeed = 0.45f;
				p.gravity *= 0.8f;
				p.lift *= 1.3f;
				p.stiffness = stiff;
				break;
			default:
				p.strength = 1f;
				p.turn = 1f;
				p.damping = 0.995f;
				p.waveSpeed = 1f;
				p.stiffness = stiff;
				break;
		}
	}

	/** Spalten des Gitters (Blockig: ein Streifen je Reihe). */
	public int cols(boolean fine) {
		if (style == Style.BLOCKY) return 1;
		switch (detail) {
			case LOW:
				return fine ? 4 : 3;
			case MEDIUM:
				return fine ? 6 : 4;
			default:
				return fine ? CapePhysics.FINE_COLS : CapePhysics.COARSE_COLS;
		}
	}

	/** Reihen des Gitters. */
	public int rows(boolean fine) {
		switch (detail) {
			case LOW:
				return fine ? 8 : 5;
			case MEDIUM:
				return fine ? 12 : 6;
			default:
				return fine ? CapePhysics.FINE_ROWS : CapePhysics.COARSE_ROWS;
		}
	}

	/** Bis zu dieser Entfernung (Blöcke) fein simulieren. */
	public double nearBlocks() {
		return detail == Detail.LOW ? 8 : (detail == Detail.MEDIUM ? 12 : CapePhysics.NEAR_BLOCKS);
	}

	/** Bis zu dieser Entfernung überhaupt simulieren. */
	public double farBlocks() {
		return detail == Detail.LOW ? 24 : (detail == Detail.MEDIUM ? 32 : CapePhysics.FAR_BLOCKS);
	}

	/** Höchstzahl fein simulierter Umhänge. */
	public int maxFine() {
		return detail == Detail.LOW ? 4 : (detail == Detail.MEDIUM ? 6 : CapePhysics.MAX_FINE);
	}

	/** Höchstzahl simulierter Umhänge insgesamt. */
	public int maxTotal() {
		return detail == Detail.LOW ? 10 : (detail == Detail.MEDIUM ? 16 : CapePhysics.MAX_TOTAL);
	}

	/** Stufen statt glatter Fläche zeichnen? */
	public boolean blocky() {
		return style == Style.BLOCKY;
	}

	private static float clamp(float v, float lo, float hi) {
		if (Float.isNaN(v)) return lo;
		return v < lo ? lo : (v > hi ? hi : v);
	}
}
