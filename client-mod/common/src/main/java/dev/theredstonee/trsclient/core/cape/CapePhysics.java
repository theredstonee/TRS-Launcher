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
	/** Teilschritte je Tick für grobe (ferne) Umhänge. */
	static final int COARSE_SUBSTEPS = 2;

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
		/** Gittergröße, mit der die Simulation angelegt wurde (Stil/Detailstufe geändert → neu). */
		int cols;
		int rows;
		double x;
		double y;
		double z;
		float yaw;
		boolean crouching;
		boolean seen;
	}

	/** Eigener Spieler zuerst, dann nach Entfernung. */
	private static final java.util.Comparator<Sample> ORDER = (a, b) -> {
		if (a.self != b.self) return a.self ? -1 : 1;
		return Double.compare(a.distanceSq, b.distanceSq);
	};
	private final Map<Integer, Body> bodies = new HashMap<>();
	private final List<Sample> wanted = new ArrayList<>();
	private final ClothSim.Motion motion = new ClothSim.Motion();
	private final ClothSim.Params params = new ClothSim.Params();
	private CapeSettings settings = new CapeSettings();
	private long ticks;
	private volatile long lastTickNanos = System.nanoTime();
	/** Vorschau im Menü: bis wann (nanoTime) sie als offen gilt, und wie lange sie schon läuft (Ticks). */
	private volatile long previewUntil;
	private int previewTicks;

	/**
	 * Ein Tick. {@code enabled}=false → alle Simulationen weg. {@code ownOnly} → nur der eigene Spieler.
	 */
	public void tick(List<Sample> samples, boolean enabled, boolean ownOnly, CapeSettings settings) {
		ticks++;
		lastTickNanos = System.nanoTime();
		if (previewing()) previewTicks++;
		else previewTicks = 0;
		if (!enabled) {
			bodies.clear();
			return;
		}
		this.settings = settings;
		settings.apply(params);
		double far = settings.farBlocks();
		double near = settings.nearBlocks();
		int maxFine = settings.maxFine();
		int maxTotal = settings.maxTotal();
		List<Sample> wanted = this.wanted;
		wanted.clear();
		for (Sample s : samples) {
			if (!s.hasCape || s.special) continue;
			if (ownOnly && !s.self) continue;
			if (s.distanceSq > far * far) continue;
			wanted.add(s);
		}
		// Eigener Spieler zuerst, dann nach Entfernung.
		if (wanted.size() > 1) Collections.sort(wanted, ORDER);
		for (Body b : bodies.values()) b.seen = false;
		int fine = 0;
		int total = 0;
		float time = (ticks % 72000) * ClothSim.TICK_SECONDS;
		for (Sample s : wanted) {
			if (total >= maxTotal) break;
			Body body = bodies.get(s.id);
			double limit = body != null && body.fine ? near + (NEAR_LEAVE - NEAR_BLOCKS) : near;
			boolean wantFine = (s.self || s.distanceSq <= limit * limit) && fine < maxFine;
			int cols = settings.cols(wantFine);
			int rows = settings.rows(wantFine);
			if (body == null || body.fine != wantFine || body.cols != cols || body.rows != rows) {
				body = new Body();
				body.fine = wantFine;
				body.cols = cols;
				body.rows = rows;
				body.sim = new ClothSim(cols, rows);
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
		// Jeder Spieler bekommt seine eigenen Böen.
		motion.phase = (s.id * 0.618034f % 1f) * 60f;
		if (s.self && previewWalking()) {
			// Vorschau im Menü: so tun, als ginge der Spieler vorwärts (≈ 0,22 Blöcke/Tick).
			motion.dz -= 0.22f * 16f;
			motion.legSwing = 0.6f;
		}
		// Grobe (ferne) Umhänge mit halb so vielen Teilschritten – aus der Entfernung sieht man keinen Unterschied.
		body.sim.tick(motion, params, body.fine || s.self ? ClothSim.SUBSTEPS : COARSE_SUBSTEPS);
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

	/** Vorschau im Menü ist offen (einmal je Bild aufrufen; gilt 300 ms). */
	public void preview() {
		previewUntil = System.nanoTime() + 300_000_000L;
	}

	public boolean previewing() {
		return previewUntil - System.nanoTime() > 0;
	}

	/**
	 * Läuft der eigene Umhang in der Vorschau gerade „gehend“? Die Vorschau wechselt alle 3 Sekunden
	 * zwischen Stehen und Gehen, damit Wind, Schwerkraft und Anhebung beide zu sehen sind.
	 */
	public boolean previewWalking() {
		return previewing() && (previewTicks / 60) % 2 == 1;
	}

	/** Aktuelle Einstellungen (Stil beim Zeichnen). */
	public CapeSettings settings() {
		return settings;
	}

	/** Umhang im Stufen-Stil zeichnen? */
	public boolean blocky() {
		return settings.blocky();
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
