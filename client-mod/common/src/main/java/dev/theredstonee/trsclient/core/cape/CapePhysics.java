package dev.theredstonee.trsclient.core.cape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Verwaltet die Umhang-Simulationen aller Spieler: je Spieler eine {@link ClothSim}, gefüttert aus dem
 * Client-Tick, mit Detailstufen nach Entfernung und einer festen Obergrenze an Rechenaufwand.
 *
 * <ul>
 *   <li>nah (≤ {@link #NEAR_BLOCKS}): feines Gitter 10 × 16, höchstens {@link #MAX_FINE} Spieler</li>
 *   <li>mittel (≤ {@link #FAR_BLOCKS}): grobes Gitter 5 × 8, insgesamt höchstens {@link #MAX_TOTAL} Spieler</li>
 *   <li>weiter weg oder über der Grenze: keine Simulation → Vanilla-Umhang (starr)</li>
 * </ul>
 * Nur Spiel-Thread.
 */
public final class CapePhysics {
	public static final double NEAR_BLOCKS = 16;
	public static final double FAR_BLOCKS = 40;
	/** Abstand, ab dem ein feiner Umhang grob wird (Hysterese gegen Flackern). */
	static final double NEAR_LEAVE = 20;
	public static final int MAX_FINE = 8;
	public static final int MAX_TOTAL = 24;
	static final int FINE_COLS = 10;
	static final int FINE_ROWS = 16;
	static final int COARSE_COLS = 5;
	static final int COARSE_ROWS = 8;

	/** Zustand eines Spielers in diesem Tick (vom Loader ausgefüllt). */
	public static final class Sample {
		public int id;
		/** Position in Blöcken (Welt). */
		public double x;
		public double y;
		public double z;
		/** Körper-Yaw in Grad (Minecraft). */
		public float bodyYaw;
		public boolean crouching;
		/** Wie weit die Beine gerade schwingen (0–1, aus der Lauf-Animation). */
		public float limbSwing;
		/** Eigener Spieler? */
		public boolean self;
		/** Entfernung² zur Kamera in Blöcken². */
		public double distanceSq;
		/** Hat überhaupt einen Umhang, der gezeichnet würde? */
		public boolean hasCape;
		/** Schwimmt/fliegt mit Elytra/schläft – dann keine Physik (Vanilla). */
		public boolean special;
	}

	private static final class Body {
		ClothSim sim;
		boolean fine;
		double x;
		double y;
		double z;
		float yaw;
		boolean crouching;
		boolean seen;
	}

	private final Map<Integer, Body> bodies = new HashMap<>();
	private final ClothSim.Motion motion = new ClothSim.Motion();
	private final ClothSim.Params params = new ClothSim.Params();
	private long ticks;
	private volatile long lastTickNanos = System.nanoTime();

	/**
	 * Ein Tick. {@code enabled}=false → alle Simulationen weg. {@code ownOnly} → nur der eigene Spieler.
	 */
	public void tick(List<Sample> samples, boolean enabled, boolean ownOnly, float strength, float wind) {
		ticks++;
		lastTickNanos = System.nanoTime();
		if (!enabled) {
			bodies.clear();
			return;
		}
		params.strength = strength;
		params.wind = wind;
		List<Sample> wanted = new ArrayList<>();
		for (Sample s : samples) {
			if (!s.hasCape || s.special) continue;
			if (ownOnly && !s.self) continue;
			if (s.distanceSq > FAR_BLOCKS * FAR_BLOCKS) continue;
			wanted.add(s);
		}
		// Eigener Spieler zuerst, dann nach Entfernung.
		Collections.sort(wanted, (a, b) -> {
			if (a.self != b.self) return a.self ? -1 : 1;
			return Double.compare(a.distanceSq, b.distanceSq);
		});
		for (Body b : bodies.values()) b.seen = false;
		int fine = 0;
		int total = 0;
		float time = (ticks % 72000) * ClothSim.TICK_SECONDS;
		for (Sample s : wanted) {
			if (total >= MAX_TOTAL) break;
			Body body = bodies.get(s.id);
			double limit = body != null && body.fine ? NEAR_LEAVE : NEAR_BLOCKS;
			boolean wantFine = (s.self || s.distanceSq <= limit * limit) && fine < MAX_FINE;
			if (body == null || body.fine != wantFine) {
				body = new Body();
				body.fine = wantFine;
				body.sim = wantFine ? new ClothSim(FINE_COLS, FINE_ROWS) : new ClothSim(COARSE_COLS, COARSE_ROWS);
				body.x = s.x;
				body.y = s.y;
				body.z = s.z;
				body.yaw = s.bodyYaw;
				body.crouching = s.crouching;
				body.sim.reset(s.crouching ? 0.5f : 0f);
				bodies.put(s.id, body);
			}
			body.seen = true;
			if (wantFine) fine++;
			total++;
			step(body, s, time);
		}
		Iterator<Body> it = bodies.values().iterator();
		while (it.hasNext()) {
			if (!it.next().seen) it.remove();
		}
	}

	private void step(Body body, Sample s, float time) {
		double dxw = (s.x - body.x) * 16.0;
		double dyw = (s.y - body.y) * 16.0;
		double dzw = (s.z - body.z) * 16.0;
		double yaw = Math.toRadians(s.bodyYaw);
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		// Körper-System: links = (cos, 0, sin), unten = (0, -1, 0), hinten = (sin, 0, -cos).
		motion.dx = (float) (dxw * cos + dzw * sin);
		motion.dy = (float) -dyw;
		motion.dz = (float) (dxw * sin - dzw * cos);
		float dYaw = (float) Math.toRadians(wrapDegrees(s.bodyYaw - body.yaw));
		motion.dYaw = dYaw;
		motion.tilt = s.crouching ? 0.5f : 0f;
		// Beinschwung: aus der Lauf-Animation, sonst aus dem Tempo (0,22 Blöcke/Tick ≈ Gehen).
		float swing = s.limbSwing > 0f ? s.limbSwing
				: (float) (Math.sqrt(dxw * dxw + dzw * dzw) / 16.0 / 0.22);
		motion.legSwing = Math.max(0f, Math.min(1f, swing)) * 0.6f;
		motion.time = time;
		body.sim.tick(motion, params);
		if (!body.sim.healthy()) body.sim.reset(motion.tilt);
		body.x = s.x;
		body.y = s.y;
		body.z = s.z;
		body.yaw = s.bodyYaw;
		body.crouching = s.crouching;
	}

	/**
	 * Anteil des laufenden Ticks (0–1) für das Interpolieren beim Zeichnen – aus der Zeit seit dem letzten
	 * Physik-Tick, damit kein versionsabhängiger Partial-Tick nötig ist.
	 */
	public float partial() {
		float p = (System.nanoTime() - lastTickNanos) / 50_000_000f;
		return p < 0f ? 0f : (p > 1f ? 1f : p);
	}

	/** Simulation eines Spielers (null = keine → Vanilla zeichnen). */
	public ClothSim sim(int id) {
		Body b = bodies.get(id);
		return b == null ? null : b.sim;
	}

	public int active() {
		return bodies.size();
	}

	public void clear() {
		bodies.clear();
	}

	static float wrapDegrees(float deg) {
		float d = deg % 360f;
		if (d >= 180f) d -= 360f;
		if (d < -180f) d += 360f;
		return d;
	}
}
