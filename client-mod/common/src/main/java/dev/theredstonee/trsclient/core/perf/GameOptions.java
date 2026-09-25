package dev.theredstonee.trsclient.core.perf;

/**
 * Vanilla-Grafikoptionen, versionsneutral als ganze Zahlen. Jeder Loader setzt das für seine
 * Minecraft-Version um (Felder bis 1.18, OptionInstance ab 1.19, Grafik-Voreinstellung ab 1.21.11 …).
 * Geändert wird nur nach einem Klick des Spielers; {@link UndoLog} merkt sich den alten Wert.
 */
public interface GameOptions {
	/** Option gibt es in dieser Version nicht. */
	int NONE = Integer.MIN_VALUE;
	/** „Max. Bildrate“ auf diesem Wert = unbegrenzt (Vanilla seit 1.7, auch options.txt {@code maxFps:260}). */
	int UNLIMITED_FPS = 260;

	enum Opt {
		/** 0 aus, 1 an */
		VSYNC(0, 1),
		/** Sichtweite in Chunks */
		VIEW_DISTANCE(2, 32),
		/** Simulationsdistanz in Chunks (ab 1.18) */
		SIMULATION_DISTANCE(5, 32),
		/** 0 schnell, 1 schön, 2 fabelhaft (ab 1.21.11: Voreinstellung, 3 = benutzerdefiniert) */
		GRAPHICS(0, 3),
		/** 0 aus, 1 schnell, 2 schön */
		CLOUDS(0, 2),
		/** 0 alle, 1 verringert, 2 minimal */
		PARTICLES(0, 2),
		/** Mipmap-Stufen 0–4 */
		MIPMAP(0, 4),
		/** Biom-Übergang 0–7 (ab 1.13) */
		BIOME_BLEND(0, 7),
		/** Entity-Distanz-Skalierung in Prozent (ab 1.16) */
		ENTITY_DISTANCE(50, 500),
		/** Weiche Beleuchtung: 0 aus, 1 minimal, 2 maximal (ab 1.19.3 nur 0/2) */
		SMOOTH_LIGHTING(0, 2),
		/** 0 Fenster, 1 Vollbild (nur Anzeige) */
		FULLSCREEN(0, 1),
		/** Bildraten-Grenze („Max. Bildrate“) 10–260 in Zehnerschritten; {@link #UNLIMITED_FPS} = unbegrenzt */
		MAX_FPS(10, UNLIMITED_FPS);

		private final int min;
		private final int max;

		Opt(int min, int max) {
			this.min = min;
			this.max = max;
		}

		public int clamp(int v) {
			return Math.max(min, Math.min(max, v));
		}

		public String key() {
			return "perf.option." + name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** Aktueller Wert oder {@link #NONE}. */
	int get(Opt opt);

	/** Setzt und wendet an (Chunks neu bauen, VSync umschalten …); false = nicht möglich. */
	boolean set(Opt opt, int value);

	/** Weiche Beleuchtung kennt nur an/aus (ab 1.19.3). */
	boolean smoothLightingIsBoolean();

	/** options.txt schreiben. */
	void save();

	/** Name der Grafikkarte, mit der das Spiel läuft (GL_RENDERER bzw. Geräte-Name), "" = unbekannt. */
	String renderer();

	/** Hersteller der Grafikkarte, "" = unbekannt. */
	String vendor();
}
