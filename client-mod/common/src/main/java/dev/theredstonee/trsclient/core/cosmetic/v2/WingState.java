package dev.theredstonee.trsclient.core.cosmetic.v2;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Zustand der Flügel je Spieler (Vertrag „Wings“): {@link #IDLE} am Boden, {@link #AIR} ohne Boden (Sprung, Fall,
 * Kreativflug), {@link #SNEAK} beim Schleichen (Flügel eng angelegt). Wechselt der Zustand, wird {@link #FADE_MS} lang
 * vom alten in den neuen überblendet. Fehlt dem Modell die Animation des Zustands, spielt der Renderer {@code idle}.
 *
 * <p>Nur aus dem Spiel-/Render-Thread benutzen.
 */
public final class WingState {
	public static final int IDLE = 0, AIR = 1, SNEAK = 2;
	/** Überblendung zwischen zwei Zuständen. */
	public static final long FADE_MS = 250L;
	/** Einträge, die so lange nicht gefragt wurden, fallen weg. */
	static final long IDLE_MS = 60_000L;

	/** Nur Selbsttests: Zustand für alle erzwingen (-1 = aus). */
	public static volatile int forced = -1;

	/** Ergebnis für ein Bild: Treiber (Index wie {@code CosmeticV2#driverIndex}) jetzt/vorher und Überblendung 0..1. */
	public static final class Snapshot {
		public final int state;
		public final int driver;
		public final int prevDriver;
		public final float blend;

		Snapshot(int state, int driver, int prevDriver, float blend) {
			this.state = state;
			this.driver = driver;
			this.prevDriver = prevDriver;
			this.blend = blend;
		}
	}

	private static final class Track {
		int state;
		int prev;
		long since;
		long seen;
		/** Seit wann ohne Boden gemeldet (0 = nicht). */
		long airFrom;
	}

	private final Map<Object, Track> tracks = new HashMap<Object, Track>();
	private long lastCleanup;

	/** In die Luft erst nach so langer Zeit ohne Boden (Treppenstufen/Stufen sollen nicht flattern). */
	static final long AIR_DELAY_MS = 100L;

	/**
	 * Zustand aus der Bewegung (FORMAT.md der Flügel): {@link #SNEAK} beim Schleichen (nicht im Flug), {@link #AIR} ohne
	 * Boden oder im Flug – nicht beim Reiten, in Wasser/Lava, an Leiter/Ranke –, sonst {@link #IDLE}.
	 */
	public static int classify(boolean onGround, boolean sneaking, boolean flying, boolean riding, boolean inLiquid,
			boolean climbing) {
		if (sneaking && !flying) return SNEAK;
		if ((!onGround || flying) && !riding && !inLiquid && !climbing) return AIR;
		return IDLE;
	}

	/** Treiber-Index eines Zustands ({@code idle} 0, {@code sneak} 2, {@code air} 4 – siehe Format §7). */
	public static int driver(int state) {
		switch (state) {
			case AIR:
				return 4;
			case SNEAK:
				return 2;
			default:
				return 0;
		}
	}

	/** Neuen Zustand von {@code key} (Spieler) melden und den Stand für dieses Bild holen. */
	public Snapshot update(Object key, int state, long now) {
		int f = forced;
		if (f >= 0) state = f;
		Track t = tracks.get(key);
		if (state == AIR && f < 0) {
			// Erst nach AIR_DELAY_MS in die Luft (kurze Lücken über Stufen zählen nicht).
			if (t != null && t.state != AIR) {
				if (t.airFrom == 0) t.airFrom = now;
				if (now - t.airFrom < AIR_DELAY_MS) state = t.state;
			}
		} else if (t != null) {
			t.airFrom = 0;
		}
		if (t == null) {
			t = new Track();
			t.state = state;
			t.prev = state;
			t.since = now - FADE_MS;
			tracks.put(key, t);
		} else if (t.state != state) {
			// Mitten in einer Überblendung zurück: vom überwiegend sichtbaren Zustand aus weiterblenden.
			float w = weight(t, now);
			t.prev = w >= 0.5f ? t.state : t.prev;
			t.state = state;
			t.since = now;
		}
		t.seen = now;
		if (now - lastCleanup > 10_000L) cleanup(now);
		return new Snapshot(t.state, driver(t.state), driver(t.prev), weight(t, now));
	}

	static float weight(Track t, long now) {
		long dt = now - t.since;
		if (dt >= FADE_MS || t.prev == t.state) return 1f;
		if (dt <= 0) return 0f;
		float u = dt / (float) FADE_MS;
		return u * u * (3 - 2 * u);
	}

	private void cleanup(long now) {
		lastCleanup = now;
		Iterator<Track> it = tracks.values().iterator();
		while (it.hasNext()) {
			if (now - it.next().seen > IDLE_MS) it.remove();
		}
	}

	int size() {
		return tracks.size();
	}
}
