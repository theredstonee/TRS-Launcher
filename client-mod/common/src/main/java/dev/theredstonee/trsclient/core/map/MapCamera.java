package dev.theredstonee.trsclient.core.map;

/**
 * Kamera der Weltkarte – reine Mathematik ohne Minecraft (Tests): Mitte in Blöcken, Zoom in GUI-Pixeln je Block,
 * weiches Zoomen zu einem Bildschirmpunkt (der Block unter dem Mauszeiger bleibt während der ganzen Animation unter
 * dem Zeiger) und Schwung nach dem Ziehen (Geschwindigkeit der letzten Mausbewegung, klingt exponentiell ab).
 *
 * <p>Bildschirmpunkte werden relativ zur Kartenmitte angegeben ({@code ox}/{@code oy} = Abstand zur Mitte in GUI-Pixeln),
 * damit die Kamera die Fenstergröße nicht kennen muss.
 */
public final class MapCamera {
	/** Zeitkonstante des Zoomens (s) – etwa so flott wie bisher. */
	static final float ZOOM_TAU = 0.075f;
	/** Abklingen des Schwungs (1/s): v(t) = v0·e^(−F·t), Gleitweg = v0/F. */
	static final double FRICTION = 5.5;
	/** Darunter (GUI-Pixel/s) endet der Schwung. */
	static final double MIN_SPEED = 25;
	/** Obergrenze (GUI-Pixel/s) – ein Ruck am Ende einer Bewegung schießt die Karte nicht ins Nirgendwo. */
	static final double MAX_SPEED = 3500;
	/** Aus diesem Zeitfenster (ns) vor dem Loslassen wird die Geschwindigkeit bestimmt. */
	static final long SAMPLE_WINDOW_NS = 90_000_000L;
	/** Stand die Maus vor dem Loslassen länger still, gibt es keinen Schwung. */
	static final long STILL_NS = 60_000_000L;

	private float minScale = 0.01f;
	private float maxScale = 16f;

	private double centerX;
	private double centerZ;
	private float scale = 1f;
	private float target = 1f;

	private boolean anchored;
	private double anchorX, anchorZ;
	private double anchorOx, anchorOy;

	private boolean dragging;
	private double vx, vy;
	// Ringpuffer der letzten Mauspositionen beim Ziehen (GUI-Pixel, ns).
	private static final int SAMPLES = 16;
	private final double[] sx = new double[SAMPLES];
	private final double[] sy = new double[SAMPLES];
	private final long[] st = new long[SAMPLES];
	private int sampleCount;
	private int sampleHead;
	private double lastX, lastY;

	/** Grenzen des Zooms (GUI-Pixel je Block). */
	public void setLimits(float min, float max) {
		minScale = min;
		maxScale = Math.max(min, max);
		scale = clamp(scale);
		target = clamp(target);
	}

	public float clamp(float s) {
		if (Float.isNaN(s)) return minScale;
		return Math.max(minScale, Math.min(maxScale, s));
	}

	public double centerX() {
		return centerX;
	}

	public double centerZ() {
		return centerZ;
	}

	/** Aktueller (animierter) Zoom in GUI-Pixeln je Block. */
	public float scale() {
		return scale;
	}

	/** Zielzoom, auf den die Animation zuläuft. */
	public float targetScale() {
		return target;
	}

	public boolean dragging() {
		return dragging;
	}

	/** Läuft gerade eine Zoom-Animation oder gleitet die Karte? */
	public boolean moving() {
		return scale != target || vx != 0 || vy != 0;
	}

	public double velocityX() {
		return vx;
	}

	public double velocityY() {
		return vy;
	}

	/** Mitte setzen (z. B. Spieler folgen) – beendet Schwung und Zoom-Anker. */
	public void center(double x, double z) {
		centerX = x;
		centerZ = z;
		anchored = false;
		vx = vy = 0;
	}

	/** Mitte setzen, ohne Schwung/Anker anzufassen (Spieler folgen während einer Zoom-Animation um die Mitte). */
	public void follow(double x, double z) {
		centerX = x;
		centerZ = z;
	}

	/** Sofort auf diesen Zoom (ohne Animation). */
	public void setScaleNow(float s) {
		scale = target = clamp(s);
		anchored = false;
	}

	/** Weltkoordinate unter einem Bildschirmpunkt (Abstand zur Mitte). */
	public double worldX(double ox) {
		return centerX + ox / scale;
	}

	public double worldZ(double oy) {
		return centerZ + oy / scale;
	}

	/** Bildschirmabstand zur Mitte eines Weltpunkts. */
	public double screenX(double wx) {
		return (wx - centerX) * scale;
	}

	public double screenY(double wz) {
		return (wz - centerZ) * scale;
	}

	/**
	 * Zoomt weich auf {@code next}, so dass der Weltpunkt unter ({@code ox}, {@code oy}) dort bleibt. Mehrere Aufrufe
	 * während einer Animation setzen den Anker neu (vom aktuellen Zwischenstand aus) – kein Springen.
	 */
	public void zoomTo(float next, double ox, double oy) {
		next = clamp(next);
		anchorX = worldX(ox);
		anchorZ = worldZ(oy);
		anchorOx = ox;
		anchorOy = oy;
		anchored = true;
		vx = vy = 0;
		target = next;
	}

	/** Zoom um einen Faktor (&gt;1 = näher) zum Punkt ({@code ox}, {@code oy}). */
	public void zoomBy(double factor, double ox, double oy) {
		if (!(factor > 0)) return;
		zoomTo((float) (target * factor), ox, oy);
	}

	/** Faktor für {@code notches} Mausrad-Rasten (auch Bruchteile vom Touchpad): 1,25 je Raste. */
	public static double wheelFactor(double notches) {
		return Math.pow(1.25, notches);
	}

	/**
	 * Animation um {@code dt} Sekunden weiterrechnen: Zoom (logarithmisch, gleich schnell rein wie raus) und Schwung.
	 */
	public void update(float dt) {
		if (dt <= 0) return;
		if (scale != target) {
			double k = 1 - Math.exp(-dt / ZOOM_TAU);
			double ls = Math.log(scale), lt = Math.log(target);
			double l = ls + (lt - ls) * k;
			scale = Math.abs(l - lt) < 0.002 ? target : (float) Math.exp(l);
		}
		if (anchored) {
			centerX = anchorX - anchorOx / scale;
			centerZ = anchorZ - anchorOy / scale;
			if (scale == target) anchored = false;
		}
		if (!dragging && (vx != 0 || vy != 0)) {
			// Genauer Weg über dt (bildratenunabhängig): ∫v0·e^(−F·t) dt = v0·(1−e^(−F·dt))/F.
			double decay = Math.exp(-FRICTION * dt);
			double f = (1 - decay) / FRICTION;
			centerX -= vx * f / scale;
			centerZ -= vy * f / scale;
			vx *= decay;
			vy *= decay;
			if (Math.hypot(vx, vy) < MIN_SPEED) vx = vy = 0;
		}
	}

	/** Ziehen beginnt bei ({@code x}, {@code y}) (GUI-Pixel) zur Zeit {@code nanos}. */
	public void beginDrag(double x, double y, long nanos) {
		dragging = true;
		anchored = false;
		target = scale;
		vx = vy = 0;
		sampleCount = 0;
		sampleHead = 0;
		lastX = x;
		lastY = y;
		sample(x, y, nanos);
	}

	/** Maus beim Ziehen bewegt: Karte folgt 1:1. */
	public void dragTo(double x, double y, long nanos) {
		if (!dragging) return;
		double dx = x - lastX, dy = y - lastY;
		centerX -= dx / scale;
		centerZ -= dy / scale;
		lastX = x;
		lastY = y;
		sample(x, y, nanos);
	}

	/**
	 * Loslassen: mit {@code inertia} gleitet die Karte mit der Geschwindigkeit der letzten ~90 ms weiter
	 * (nicht, wenn die Maus vorher stillstand).
	 */
	public void endDrag(long nanos, boolean inertia) {
		if (!dragging) return;
		dragging = false;
		vx = vy = 0;
		if (!inertia || sampleCount < 2) return;
		int newest = (sampleHead - 1 + SAMPLES) % SAMPLES;
		if (nanos - st[newest] > STILL_NS) return;
		int oldest = newest;
		for (int i = 1; i < sampleCount; i++) {
			int idx = (newest - i + SAMPLES) % SAMPLES;
			if (st[newest] - st[idx] > SAMPLE_WINDOW_NS) break;
			oldest = idx;
		}
		long span = st[newest] - st[oldest];
		if (oldest == newest || span <= 0) return;
		double sec = span / 1e9;
		double ux = (sx[newest] - sx[oldest]) / sec, uy = (sy[newest] - sy[oldest]) / sec;
		double speed = Math.hypot(ux, uy);
		if (speed < MIN_SPEED * 2) return;
		if (speed > MAX_SPEED) {
			ux *= MAX_SPEED / speed;
			uy *= MAX_SPEED / speed;
		}
		vx = ux;
		vy = uy;
	}

	/** Schwung sofort anhalten (Klick, Zoom, „Zentrieren“). */
	public void stop() {
		vx = vy = 0;
	}

	/** Gesamter restlicher Gleitweg in GUI-Pixeln (für Tests): |v|/F. */
	public double remainingGlide() {
		return Math.hypot(vx, vy) / FRICTION;
	}

	private void sample(double x, double y, long nanos) {
		sx[sampleHead] = x;
		sy[sampleHead] = y;
		st[sampleHead] = nanos;
		sampleHead = (sampleHead + 1) % SAMPLES;
		if (sampleCount < SAMPLES) sampleCount++;
	}
}
