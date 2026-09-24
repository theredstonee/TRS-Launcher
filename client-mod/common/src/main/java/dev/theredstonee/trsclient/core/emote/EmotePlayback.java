package dev.theredstonee.trsclient.core.emote;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Laufende Emotes je Spieler (eigener und andere TRS-Spieler): Start, vorzeitiges Ende bei Bewegung
 * (waagerecht &gt; {@link #MOVE_LIMIT} Blöcke/Tick, wie die API es vorgibt) oder Schleichen, Abfrage der Pose.
 * Nur aus dem Spiel-/Render-Thread benutzen.
 */
public final class EmotePlayback {
	/** Waagerechte Bewegung pro Tick, ab der ein Emote endet (API.md §12). */
	public static final double MOVE_LIMIT = 0.05;
	/** So lange nach dem Start zählt Bewegung nicht (Ausrollen nach dem Loslassen der Lauftasten). */
	public static final long GRACE_MS = 250;
	/** Ausblenden nach einem Abbruch. */
	public static final long STOP_FADE_MS = 200;
	/** Obergrenze gleichzeitig verfolgter Spieler (Schutz vor Ereignis-Fluten). */
	static final int MAX_ACTIVE = 256;

	/** Position eines Spielers in diesem Tick (für den Bewegungs-Abbruch). */
	public static final class Mover {
		public final UUID uuid;
		public final double x;
		public final double z;
		public final boolean crouching;
		public final boolean self;

		public Mover(UUID uuid, double x, double z, boolean crouching, boolean self) {
			this.uuid = uuid;
			this.x = x;
			this.z = z;
			this.crouching = crouching;
			this.self = self;
		}
	}

	private static final class Active {
		final EmoteDef def;
		final long start;
		final int duration;
		long stopAt = -1;
		boolean hasPos;
		double lastX;
		double lastZ;

		Active(EmoteDef def, long start, int duration) {
			this.def = def;
			this.start = start;
			this.duration = duration;
		}

		boolean finished(long now) {
			return now - start >= duration || (stopAt >= 0 && now - stopAt >= STOP_FADE_MS);
		}
	}

	private final Map<UUID, Active> active = new HashMap<>();

	/** Startet (oder ersetzt) das Emote eines Spielers. {@code durationMs} ≤ 0 → Standarddauer. */
	public void start(UUID uuid, EmoteDef def, int durationMs, long now) {
		if (uuid == null || def == null) return;
		if (!active.containsKey(uuid) && active.size() >= MAX_ACTIVE) return;
		int duration = durationMs > 0 ? durationMs : def.durationMs();
		active.put(uuid, new Active(def, now, duration));
	}

	/** Beendet das Emote vorzeitig (kurz ausblenden). */
	public void stop(UUID uuid, long now) {
		Active a = uuid == null ? null : active.get(uuid);
		if (a != null && a.stopAt < 0) a.stopAt = now;
	}

	/** Einmal pro Tick: Bewegung prüfen, fertige Emotes entfernen. */
	public void tick(long now, List<Mover> movers) {
		if (active.isEmpty()) return;
		if (movers != null) {
			for (Mover m : movers) {
				Active a = active.get(m.uuid);
				if (a == null || a.stopAt >= 0) continue;
				boolean moved = false;
				if (a.hasPos) {
					double dx = m.x - a.lastX;
					double dz = m.z - a.lastZ;
					moved = dx * dx + dz * dz > MOVE_LIMIT * MOVE_LIMIT;
				}
				a.hasPos = true;
				a.lastX = m.x;
				a.lastZ = m.z;
				if (now - a.start >= GRACE_MS && (moved || m.crouching)) a.stopAt = now;
			}
		}
		Iterator<Active> it = active.values().iterator();
		while (it.hasNext()) {
			if (it.next().finished(now)) it.remove();
		}
	}

	/**
	 * Pose des Spielers zur Zeit {@code now} nach {@code frame}; Rückgabe = Deckkraft (0 = kein Emote, dann ist
	 * {@code frame} unverändert).
	 */
	public float sample(UUID uuid, long now, float[] frame) {
		Active a = uuid == null ? null : active.get(uuid);
		if (a == null) return 0f;
		float elapsed = now - a.start;
		float w = EmoteDef.weight(elapsed, a.duration);
		if (a.stopAt >= 0) w = Math.min(w, EmoteDef.smooth(1f - (now - a.stopAt) / (float) STOP_FADE_MS));
		if (w <= 0f) return 0f;
		a.def.sample(elapsed, frame);
		return w;
	}

	/** Das laufende Emote (auch während des Ausblendens) oder null. */
	public EmoteDef current(UUID uuid) {
		Active a = uuid == null ? null : active.get(uuid);
		return a == null ? null : a.def;
	}

	/** Läuft ein Emote (nicht abgebrochen, nicht abgelaufen)? */
	public boolean playing(UUID uuid, long now) {
		Active a = uuid == null ? null : active.get(uuid);
		return a != null && a.stopAt < 0 && now - a.start < a.duration;
	}

	public int size() {
		return active.size();
	}

	public void clear() {
		active.clear();
	}

	/** Alle Emotes außer dem von {@code keep} verwerfen (null = alle). */
	public void retainOnly(UUID keep) {
		if (active.isEmpty()) return;
		active.keySet().removeIf(u -> !u.equals(keep));
	}
}
