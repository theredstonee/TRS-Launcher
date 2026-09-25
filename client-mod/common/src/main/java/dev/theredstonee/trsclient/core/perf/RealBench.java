package dev.theredstonee.trsclient.core.perf;

import java.util.Locale;

/**
 * Realistischer FPS-Benchmark ({@code -PtrsAutotestOnly=realbench}): echte Welt mit festem Seed, Kamera-Pfad
 * über ein Dorf (Rundflug, Überflug, Umsehen) – in jedem Lauf exakt gleich, weil jede Position nur aus der
 * Tick-Nummer folgt ({@link #pose}). Ablauf: Einrichten → warten, bis der Boden geladen ist → Pfad einmal
 * ohne Messung (Chunks laden, JIT warm) → Pfad noch einmal mit Messung → Bericht.
 * Versionsneutral: der Loader liefert nur die kleinen Handgriffe über {@link Game}.
 */
public final class RealBench {
	/** Was der Loader für den Benchmark können muss. */
	public interface Game {
		/** Welt geladen und kein Ladebildschirm offen. */
		boolean ready();

		/** Server-Befehl ohne Schrägstrich. */
		void command(String command);

		/** Höhe der obersten Oberfläche (Blöcke/Flüssigkeit) oder {@link Integer#MIN_VALUE}, solange unbekannt. */
		int groundY(int x, int z);

		/** Kamera/Spieler setzen (fliegend, ohne eigene Bewegung). */
		void place(double x, double y, double z, float yaw, float pitch);

		/** Wo der Spieler gerade wirklich ist: {x, y, z} (zur Kontrolle, ob die Kamera hängt). */
		double[] position();

		/** Bildschirmfoto nach run/screenshots. */
		void shot(String name);

		void log(String line);

		/** Bildpuffer-Größe in Pixeln. */
		int width();

		int height();

		/** Zusammenfassung der relevanten Optionen (Sichtweite, VSync, Grafik …) für das Protokoll. */
		String setup();

		/** Profil-Aufnahme starten/stoppen (JFR); darf nichts tun. */
		void profile(boolean on);

		/** Fenster so groß wie möglich (maximiert auf dem Bildschirm, auf dem es gerade liegt); darf nichts tun. */
		void maximize();

		/** Fertig: Welt verlassen und Spiel beenden. */
		void finish();
	}

	/** Ticks je Pfad-Durchgang (60 s). */
	public static final int PATH_TICKS = 1200;
	/** Mindestabstand der Kamera über dem Gelände entlang des Pfads (Blöcke). */
	static final int CLEARANCE = 8;
	/** So weit (Ticks) vor und zurück zählt das Gelände für die Höhe – die Kamera steigt rechtzeitig und ruckelt nicht. */
	static final int LOOK = 40;
	/** Ab dieser Abweichung (Blöcke) zwischen Soll und Ist gilt die Kamera als hängend – Messung ungültig. */
	static final double STUCK = 1.5;

	/** Stillstand am Start, bis die Chunks um den Startpunkt gebaut sind. */
	static final int SETTLE_TICKS = 100;
	/** Standard-Messdauer (Ticks): der ganze Rundkurs in 20 s (Aufwärmen: ein Durchgang ohne Messung). */
	public static final int DEFAULT_LENGTH = 400;
	/** Längstens so lange auf den Boden warten, dann mit Meereshöhe weitermachen. */
	static final int GROUND_TIMEOUT = 400;

	private enum Phase {
		SETUP, GROUND, SETTLE, WARMUP, MEASURE, DONE
	}

	private final int cx;
	private final int cz;
	private final String label;
	private final FrameStats stats;
	private Phase phase = Phase.SETUP;
	private int t;
	private int groundY;
	private boolean finished;
	/** Ticks je Durchgang (Aufwärmen und Messen); der Pfad wird auf diese Dauer gestaucht. */
	private final int length;
	/** Kamerahöhe je Pfad-Tick (aus dem Gelände, einmal nach dem Laden bestimmt). */
	private double[] heights;
	private double[] last;
	private double maxDeviation;
	private int stuckTicks;

	/**
	 * @param cx,cz Mitte (Dorf) – feste Koordinaten je Version und Seed
	 * @param label Bezeichnung des Laufs im Protokoll (Variante, z. B. „vanilla“, „trs“, „trs+mods“)
	 */
	public RealBench(int cx, int cz, String label, FrameStats stats) {
		this(cx, cz, label, stats, DEFAULT_LENGTH);
	}

	/** @param length Ticks je Durchgang (20 = 1 s); der Rundkurs bleibt derselbe, nur schneller oder langsamer. */
	public RealBench(int cx, int cz, String label, FrameStats stats, int length) {
		this.length = Math.max(100, length);
		this.cx = cx;
		this.cz = cz;
		this.label = label == null || label.isEmpty() ? "?" : label;
		this.stats = stats;
	}

	/** Ein Client-Tick; false = fertig (Spiel wurde beendet). */
	public boolean tick(Game g, long nanoNow) {
		if (finished) return false;
		if (!g.ready()) return true;
		switch (phase) {
			case SETUP:
				g.maximize();
				for (String rule : new String[]{"doDaylightCycle", "advance_time", "doWeatherCycle", "advance_weather", "doMobSpawning",
						"spawn_mobs", "sendCommandFeedback", "send_command_feedback"}) {
					g.command("gamerule " + rule + " false");
				}
				g.command("time set 1000");
				g.command("weather clear");
				// Kreativ-Flug wie ein Spieler (Hand und Hotbar sichtbar); die Kamera fährt über dem Gelände
				// ({@link #smoothHeights}), die Abweichung Soll/Ist wird geprüft.
				g.command("gamemode creative @a");
				g.command(String.format(Locale.ROOT, "tp @p %d 200 %d", cx, cz));
				phase = Phase.GROUND;
				t = 0;
				return true;
			case GROUND: {
				int y = g.groundY(cx, cz);
				t++;
				if (y == Integer.MIN_VALUE || y <= 0) {
					g.place(cx + 0.5, 200, cz + 0.5, 0, 90);
					if (t < GROUND_TIMEOUT) return true;
					y = 64;
					g.log("[RealBench] Boden nicht gefunden – nehme Höhe 64");
				}
				groundY = y;
				g.log(String.format(Locale.ROOT, "[RealBench] Setup \"%s\": Mitte %d %d %d, %dx%d, %s", label, cx, groundY, cz, g.width(),
						g.height(), g.setup()));
				phase = Phase.SETTLE;
				t = 0;
				return true;
			}
			case SETTLE:
				// Über der Mitte schweben, bis die Chunks ringsum da sind; dann die Höhen entlang des Pfads festlegen.
				g.place(cx + 0.5, groundY + 20, cz + 0.5, 0, 30);
				if (++t >= SETTLE_TICKS) {
					heights = heights(g);
					phase = Phase.WARMUP;
					t = 0;
				}
				return true;
			case WARMUP:
				apply(g, t);
				if (t == length / 4) g.shot("realbench-" + safe(label) + "-orbit");
				if (++t >= length) {
					phase = Phase.MEASURE;
					t = 0;
					apply(g, 0);
					g.profile(true);
					stats.start(nanoNow);
				}
				return true;
			case MEASURE:
				check(g);
				if (t % (length / 8) == 0) {
					double[] at = g.position();
					g.log(String.format(Locale.ROOT, "[RealBench] Tick %d: Kamera %.1f %.1f %.1f", t, at[0], at[1], at[2]));
				}
				apply(g, t);
				if (++t >= length) {
					stats.stop(nanoNow);
					g.profile(false);
					g.log(String.format(Locale.ROOT, "[RealBench] Pfad \"%s\": größte Abweichung %.2f Blöcke, %d Ticks hängend – %s", label,
							maxDeviation, stuckTicks, stuckTicks == 0 ? "gültig" : "UNGÜLTIG"));
					g.log(report(label + (stuckTicks == 0 ? "" : " UNGÜLTIG"), stats));
					phase = Phase.DONE;
					t = 0;
				}
				return true;
			default:
				if (++t < 20) return true;
				finished = true;
				g.finish();
				return false;
		}
	}

	private void apply(Game g, int tick) {
		int pathTick = (int) ((long) tick * PATH_TICKS / length);
		double[] p = pose(pathTick, cx + 0.5, groundY, cz + 0.5);
		if (heights != null) p[1] = heights[((pathTick % PATH_TICKS) + PATH_TICKS) % PATH_TICKS];
		g.place(p[0], p[1], p[2], (float) p[3], (float) p[4]);
		last = p;
	}

	/** Ist die Kamera dort, wo sie im letzten Tick hingesetzt wurde? */
	private void check(Game g) {
		if (last == null) return;
		double[] at = g.position();
		double d = Math.sqrt(sq(at[0] - last[0]) + sq(at[1] - last[1]) + sq(at[2] - last[2]));
		if (d > maxDeviation) maxDeviation = d;
		if (d > STUCK) stuckTicks++;
	}

	private static double sq(double v) {
		return v * v;
	}

	/** Kamerahöhe je Tick: nie unter {@link #CLEARANCE} über dem Gelände im Umkreis von {@link #LOOK} Ticks. */
	private double[] heights(Game g) {
		double[] ground = new double[PATH_TICKS];
		for (int i = 0; i < PATH_TICKS; i++) {
			double[] p = pose(i, cx + 0.5, groundY, cz + 0.5);
			int y = g.groundY((int) Math.floor(p[0]), (int) Math.floor(p[2]));
			ground[i] = y == Integer.MIN_VALUE ? groundY : y;
		}
		return smoothHeights(ground, groundY);
	}

	/** Höhen aus dem Boden entlang des Pfads (rein, testbar). */
	static double[] smoothHeights(double[] ground, double centerGround) {
		int n = ground.length;
		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			double base = pose(i, 0, centerGround, 0)[1];
			double top = Double.NEGATIVE_INFINITY;
			for (int k = -LOOK; k <= LOOK; k++) top = Math.max(top, ground[((i + k) % n + n) % n]);
			out[i] = Math.max(base, top + CLEARANCE);
		}
		// Steigung begrenzen (höchstens 0,5 Blöcke je Tick): früher steigen, langsamer sinken – nie unter dem Mindestabstand.
		for (int round = 0; round < 3; round++) {
			for (int i = 0; i < 2 * n; i++) {
				int k = i % n;
				out[k] = Math.max(out[k], out[(k + n - 1) % n] - MAX_SLOPE);
			}
			for (int i = 2 * n - 1; i >= 0; i--) {
				int k = i % n;
				out[k] = Math.max(out[k], out[(k + 1) % n] - MAX_SLOPE);
			}
		}
		return out;
	}

	/** Größte Höhenänderung der Kamera je Tick (Blöcke). */
	static final double MAX_SLOPE = 0.5;

	/**
	 * Kamera für Tick {@code tick} des Pfads: {x, y, z, yaw, pitch} – ein geschlossener Rundkurs ohne Sprünge.
	 * 0–599 Rundflug (Radius 36, Blick zur Mitte), 600–899 Überflug von Ost nach West durch die Mitte (Blick nach vorn),
	 * 900–999 zurück zur Mitte, 1000–1099 dort einmal rundum sehen, 1100–1199 zurück zum Startpunkt des Rundflugs.
	 * Die Höhe hier ist nur die Mindesthöhe über der Mitte; über Hügeln hebt {@link #smoothHeights} die Kamera an.
	 * Yaw wie Minecraft: 0 = Süden (+Z), 90 = Westen (−X); Pitch positiv = nach unten.
	 */
	public static double[] pose(int tick, double cx, double groundY, double cz) {
		int t = ((tick % PATH_TICKS) + PATH_TICKS) % PATH_TICKS;
		if (t < 600) {
			double a = 2 * Math.PI * t / 600.0;
			double x = cx + 36 * Math.cos(a);
			double z = cz + 36 * Math.sin(a);
			return new double[]{x, groundY + 12, z, yaw(cx - x, cz - z), 20};
		}
		if (t < 900) {
			double f = (t - 600) / 300.0;
			return new double[]{cx + 36 - 72 * f, groundY + 12, cz, 90, 12};
		}
		if (t < 1000) {
			double f = (t - 900) / 100.0;
			return new double[]{cx - 36 + 36 * f, groundY + 12 + 8 * f, cz, wrap(90 + 180 * f), 12 + 13 * f};
		}
		if (t < 1100) {
			double f = (t - 1000) / 100.0;
			return new double[]{cx, groundY + 20, cz, wrap(270 + 360 * f), 25 + 10 * Math.sin(2 * Math.PI * f)};
		}
		double f = (t - 1100) / 100.0;
		return new double[]{cx + 36 * f, groundY + 20 - 8 * f, cz, wrap(270 + 180 * f), 25 - 5 * f};
	}

	/** Blickrichtung (dx, dz) → Minecraft-Yaw. */
	static double yaw(double dx, double dz) {
		return wrap(Math.toDegrees(Math.atan2(-dx, dz)));
	}

	private static double wrap(double deg) {
		double d = deg % 360;
		if (d >= 180) d -= 360;
		if (d < -180) d += 360;
		return d;
	}

	/** Protokollzeile (von der Auswertung per Muster gelesen). */
	public static String report(String label, FrameStats stats) {
		return String.format(Locale.ROOT,
				"[RealBench] Ergebnis \"%s\": Ø %.1f FPS | 1%%-Low %.1f FPS | 0,1%%-Low %.1f FPS | %d Bilder in %.1f s | längstes Bild %.1f ms",
				label, stats.averageFps(), stats.lowFps(0.01), stats.lowFps(0.001), stats.frames(), stats.seconds(), stats.worstMillis());
	}

	private static String safe(String s) {
		return s.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
