package dev.theredstonee.trsclient.core.map;

/**
 * Auto-Zoom der Minimap: schnell unterwegs (Sprinten, Reiten, Boot, Elytra) weiter heraus, drinnen/in Höhlen
 * näher heran – immer ausgehend vom eingestellten Zoom und nur in ganzen Stufen der Leiter {@link #LADDER}
 * (Bildschirmpixel je Block → scharfe Karte, sobald der Übergang fertig ist). Stufenwechsel mit Hysterese
 * (hinaus zügig, zurück erst nach einer Pause), der Übergang wird je Bild weich überblendet.
 *
 * <p>{@link #tick} je Client-Tick (Spiel-Thread), {@link #frame} je Bild (Render-Thread) – reine Mathematik.
 */
public final class AutoZoom {
	/** Zoomstufen (Bildschirmpixel je Block). */
	static final float[] LADDER = {0.5f, 1f, 2f, 3f, 4f, 6f};
	/** Geschwindigkeit (Blöcke je Tick, waagerecht), ab der eine bzw. zwei Stufen herausgezoomt wird. */
	static final double FAST = 0.3, VERY_FAST = 0.65;
	/** So viele Ticks muss ein höherer Wunsch anliegen, bevor herausgezoomt wird (kurze Sprünge zählen nicht). */
	static final int OUT_TICKS = 4;
	/** So viele Ticks muss ein niedrigerer Wunsch anliegen, bevor wieder hineingezoomt wird. */
	static final int IN_TICKS = 30;
	/** Zeitkonstanten des Übergangs (s): hinaus zügig, hinein gemächlich. */
	static final float TAU_OUT = 0.35f, TAU_IN = 0.7f;

	private int speedSteps;
	private int wantedSpeed;
	private int wantedTicks;
	private int indoorSteps;
	private int indoorWanted;
	private int indoorTicks;
	private double smoothSpeed;
	/** Aktueller Wert in log2(Bildschirmpixel je Block); NaN = noch keiner. */
	private float current = Float.NaN;

	/**
	 * Je Tick: Tempo und Umgebung auswerten.
	 *
	 * @param speed waagerechte Geschwindigkeit (Blöcke je Tick)
	 * @param movement Merker aus {@link MapPlatform#movement()}
	 * @param indoor drinnen/unter Tage/in der Höhlenansicht
	 * @param bySpeed Auto-Zoom nach Tempo an?
	 * @param byIndoor Auto-Zoom drinnen an?
	 */
	public void tick(double speed, int movement, boolean indoor, boolean bySpeed, boolean byIndoor) {
		smoothSpeed += (speed - smoothSpeed) * 0.35;
		int want = bySpeed ? speedWish(smoothSpeed, movement) : 0;
		if (want > speedSteps) {
			if (want == wantedSpeed) wantedTicks++;
			else {
				wantedSpeed = want;
				wantedTicks = 1;
			}
			if (wantedTicks >= OUT_TICKS || !bySpeed) speedSteps = want;
		} else if (want < speedSteps) {
			if (want == wantedSpeed) wantedTicks++;
			else {
				wantedSpeed = want;
				wantedTicks = 1;
			}
			if (wantedTicks >= IN_TICKS || !bySpeed) speedSteps = want;
		} else {
			wantedSpeed = want;
			wantedTicks = 0;
		}
		int in = byIndoor && indoor ? 1 : 0;
		if (in != indoorSteps) {
			if (in == indoorWanted) indoorTicks++;
			else {
				indoorWanted = in;
				indoorTicks = 1;
			}
			// Hinein (drinnen) nach kurzer Zeit, hinaus (wieder draußen) ebenfalls ohne langes Warten.
			if (indoorTicks >= (in > indoorSteps ? 10 : 6) || !byIndoor) indoorSteps = in;
		} else {
			indoorWanted = in;
			indoorTicks = 0;
		}
	}

	/** Gewünschte Stufen heraus für Tempo und Bewegungsart (0..2). */
	static int speedWish(double speed, int movement) {
		int steps = speed >= VERY_FAST ? 2 : (speed >= FAST ? 1 : 0);
		if ((movement & MapPlatform.GLIDING) != 0 && speed > 0.4) steps = Math.max(steps, 2);
		if ((movement & (MapPlatform.RIDING | MapPlatform.BOAT)) != 0 && speed > 0.12) steps = Math.max(steps, 1);
		if ((movement & MapPlatform.SPRINTING) != 0 && speed > 0.2) steps = Math.max(steps, 1);
		return steps;
	}

	/** Stufen heraus (Tempo). */
	public int speedSteps() {
		return speedSteps;
	}

	/** Stufen hinein (drinnen). */
	public int indoorSteps() {
		return indoorSteps;
	}

	/** Zielzoom (Bildschirmpixel je Block) für den eingestellten Grundzoom {@code basePx}. */
	public float target(float basePx) {
		return step(basePx, indoorSteps - speedSteps);
	}

	/** Grundzoom um {@code steps} Stufen der Leiter verschoben (+ = näher), an den Enden begrenzt. */
	static float step(float basePx, int steps) {
		int base = nearest(basePx);
		if (steps == 0) return basePx;
		int i = Math.max(0, Math.min(LADDER.length - 1, base + steps));
		return LADDER[i];
	}

	static int nearest(float px) {
		int best = 0;
		double bestD = Double.MAX_VALUE;
		double l = Math.log(Math.max(0.01f, px));
		for (int i = 0; i < LADDER.length; i++) {
			double d = Math.abs(Math.log(LADDER[i]) - l);
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	/**
	 * Je Bild: weich überblendeter Zoom (Bildschirmpixel je Block) Richtung {@link #target}.
	 *
	 * @param dt Sekunden seit dem letzten Bild
	 */
	public float frame(float basePx, float dt) {
		float goal = (float) (Math.log(target(basePx)) / Math.log(2));
		if (Float.isNaN(current) || dt <= 0f) {
			if (Float.isNaN(current)) current = goal;
		} else {
			float tau = goal < current ? TAU_OUT : TAU_IN;
			current += (goal - current) * (1f - (float) Math.exp(-dt / tau));
			if (Math.abs(goal - current) < 0.002f) current = goal;
		}
		return (float) Math.pow(2, current);
	}

	/** Neu anfangen (Weltwechsel). */
	public void reset() {
		speedSteps = 0;
		wantedSpeed = 0;
		wantedTicks = 0;
		indoorSteps = 0;
		indoorWanted = 0;
		indoorTicks = 0;
		smoothSpeed = 0;
		current = Float.NaN;
	}
}
