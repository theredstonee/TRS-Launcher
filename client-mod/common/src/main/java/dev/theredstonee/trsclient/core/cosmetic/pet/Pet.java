package dev.theredstonee.trsclient.core.cosmetic.pet;

import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2;

import java.util.Random;
import java.util.UUID;

/**
 * Ein Begleiter am Boden (z. B. Pinguin) in der Welt: Position/Blickrichtung je Tick (mit dem Stand des vorigen Ticks
 * zum Überblenden zwischen zwei Ticks), Bewegungszustand für die Animation und die Merker der Simulation
 * ({@link PetSim}). Nur Spiel-/Render-Thread.
 */
public final class Pet {
	/** Zustände (Anhang D/E {@code PET_STATES}). */
	public static final int IDLE = 0, WALK = 1, SLIDE = 2, SWIM = 3, AIR = 4, SNEAK = 5, GRAB = 6, FLEE = 7, HANDSUP = 8;
	static final String[] STATE_NAMES = { "idle", "walk", "slide", "swim", "air", "sneak", "grab", "flee", "handsup" };
	/**
	 * Was das Modell kann ({@link #abilities}, Anhang E): nur dann wird das Verhalten ausgelöst. Ohne {@code sneak}
	 * schleicht er nicht (läuft normal), ohne {@code grab} schnappt er nie (und flieht dann auch nie), ohne {@code flee}
	 * flieht er im Lauf-Zustand, ohne {@code handsup} hebt er nie die Flossen.
	 */
	public static final int CAN_SNEAK = 1, CAN_GRAB = 2, CAN_FLEE = 4, CAN_HANDSUP = 8;
	/** Überblenden zwischen zwei Zuständen (wie §7). */
	public static final long FADE_MS = 250L;

	public final UUID owner;
	/** Platz in der Formation (0…{@link PetSim#MAX_PER_OWNER}−1). */
	public final int index;
	/**
	 * Welches Teil: 0 = Haupt-Teil des Besitzers ({@code cosmetics.pet}), 1…4 = Extra Nr. {@code kind − 1} des Gefolges
	 * ({@code cosmetics.pet.extras}).
	 */
	public final int kind;
	/** Größe (1 = normal, Kaiserpinguin 1,25): Modell, Kopffreiheit, Schwimmtiefe, Stufenhöhe und Abstand wachsen mit. */
	public final double scale;
	/** Aus {@link #scale}: Höhe (Kopffreiheit), Tiefe beim Schwimmen, Mindest-Wassertiefe, Stufe ohne Sprung, Radius. */
	final double height, swimDepth, swimMinDepth, step, radius;
	/** Eigener Zeitversatz der Animationen (Hash der Besitzer-UUID + Index), damit mehrere nicht im Gleichschritt laufen. */
	public final long offsetMs;
	final Random random;

	// Stand des aktuellen und des vorigen Ticks (Weltkoordinaten, Boden unter der Mitte; Gieren wie Minecraft)
	double x, y, z, yaw;
	double px, py, pz, pyaw;
	double vx, vy, vz;
	boolean onGround = true;
	boolean inWater;

	int state = IDLE;
	int prevState = IDLE;
	long stateSince;
	/** Ticks, die der Begleiter schon ohne Boden ist (AIR erst ab 2, gegen Flattern an Stufen). */
	int airTicks;

	// Überfall-Begleiter (Anhang E): was das Modell kann und die laufenden Verhaltens-Zähler (Ticks)
	int abilities;
	/** Bis zum nächsten Beute-Schnappen; Dauer des Schnappens / der Flucht; Fluchtrichtung (Welt, normiert). */
	int grabTimer = -1, grabTicks, fleeTicks;
	double fleeX, fleeZ;
	/** Wie lange er gerade angesehen wird; Hände oben noch so lange; Abklingzeit danach. */
	int lookTicks, handsupTicks, handsupCooldown;
	/** Blickziel beim Schnappen (Gieren) oder NaN. */
	double grabYaw = Double.NaN;

	// Verhalten
	int slideTicks;
	int slideCooldown;
	double slideX, slideZ;
	int idleTimer;
	/** Ziel beim Umherwatscheln (NaN = keins) und Blickziel beim Umschauen (NaN = keins). */
	double wanderX = Double.NaN, wanderZ = Double.NaN;
	int wanderTicks;
	double lookYaw = Double.NaN;
	int stuckTicks;
	int jumpCooldown;
	/** Ticks bis zum nächsten Versuch, zum Besitzer zu springen (kein Landeplatz beim Besitzer). */
	int teleportCooldown;
	/** Wie oft der Begleiter zum Besitzer gesetzt wurde (Selbsttests). */
	public int teleports;
	/** Nur {@link PetSim#visible}: Reihenfolge beim Zeichnen. */
	double sortKey;

	Pet(UUID owner, int index, long seed) {
		this(owner, index, seed, 0, 1.0);
	}

	Pet(UUID owner, int index, long seed, int kind, double scale) {
		this.owner = owner;
		this.index = index;
		this.kind = kind;
		this.scale = scale > 0 && !Double.isInfinite(scale) ? scale : 1.0;
		double s = this.scale;
		this.height = PetSim.HEIGHT * s;
		this.swimDepth = PetSim.SWIM_DEPTH * s;
		this.swimMinDepth = PetSim.SWIM_MIN_DEPTH * Math.max(1.0, s);
		// Kleine Begleiter schaffen trotzdem Stufen (0,5), große gehen höhere Absätze ohne Sprung hoch.
		this.step = Math.max(0.55, Math.min(0.95, PetSim.STEP * s));
		this.radius = PetSim.RADIUS * s;
		this.random = new Random(seed);
		this.offsetMs = Math.floorMod(seed * 2654435761L, 60_000L);
		this.idleTimer = 20 + random.nextInt(60);
	}

	/** Position zwischen zwei Ticks ({@code alpha} 0 = voriger Tick, 1 = aktueller). */
	public double x(float alpha) {
		return px + (x - px) * alpha;
	}

	public double y(float alpha) {
		return py + (y - py) * alpha;
	}

	public double z(float alpha) {
		return pz + (z - pz) * alpha;
	}

	/** Gieren zwischen zwei Ticks (kürzester Weg). */
	public double yaw(float alpha) {
		double d = wrap(yaw - pyaw);
		return pyaw + d * alpha;
	}

	public int state() {
		return state;
	}

	/** Zustandsname ({@code idle}, {@code walk}, {@code slide}, {@code swim}, {@code air}). */
	public String stateName() {
		return STATE_NAMES[state];
	}

	/** Name eines Zustands ({@link #IDLE} …). */
	public static String stateNameOf(int state) {
		return STATE_NAMES[Math.max(0, Math.min(STATE_NAMES.length - 1, state))];
	}

	/** Treiber-Index der Animation eines Zustands ({@link CosmeticV2#DRIVER_IDLE} …). */
	public static int driver(int state) {
		switch (state) {
			case SNEAK:
				return CosmeticV2.DRIVER_SNEAK;
			case GRAB:
				return CosmeticV2.DRIVER_GRAB;
			case FLEE:
				return CosmeticV2.DRIVER_FLEE;
			case HANDSUP:
				return CosmeticV2.DRIVER_HANDSUP;
			case WALK:
				return CosmeticV2.DRIVER_WALK;
			case SLIDE:
				return CosmeticV2.DRIVER_SLIDE;
			case SWIM:
				return CosmeticV2.DRIVER_SWIM;
			case AIR:
				return CosmeticV2.DRIVER_AIR;
			default:
				return CosmeticV2.DRIVER_IDLE;
		}
	}

	/** Überblendung 0…1 vom vorigen in den aktuellen Zustand zur Wanduhr {@code now} (geglättet wie §7). */
	public float blend(long now) {
		if (prevState == state) return 1f;
		long dt = now - stateSince;
		if (dt >= FADE_MS) return 1f;
		if (dt <= 0) return 0f;
		float u = dt / (float) FADE_MS;
		return u * u * (3 - 2 * u);
	}

	public int prevState() {
		return prevState;
	}

	/** Was das Modell kann ({@link #CAN_SNEAK} …), zuletzt gemeldet. */
	public int abilities() {
		return abilities;
	}

	/** Wanduhr-Zeit (ms, wie {@link PetSim#begin}), seit der der aktuelle Zustand läuft. */
	public long stateSince() {
		return stateSince;
	}

	/** Spielt dieser Zustand seine Animation einmal ab Zustandsbeginn statt nach der Uhr ({@link #GRAB})? */
	public static boolean fromStateStart(int state) {
		return state == GRAB;
	}

	/**
	 * Fähigkeiten aus den Treibern eines Modells (Anhang E): {@link #CAN_SNEAK} … je nachdem, ob das Modell Animationen
	 * mit {@code sneak}/{@code grab}/{@code flee}/{@code handsup} hat.
	 */
	public static int abilitiesOf(CosmeticV2 m) {
		if (m == null) return 0;
		int a = 0;
		if (m.hasDriver(CosmeticV2.DRIVER_SNEAK)) a |= CAN_SNEAK;
		if (m.hasDriver(CosmeticV2.DRIVER_GRAB)) a |= CAN_GRAB;
		if (m.hasDriver(CosmeticV2.DRIVER_FLEE)) a |= CAN_FLEE;
		if (m.hasDriver(CosmeticV2.DRIVER_HANDSUP)) a |= CAN_HANDSUP;
		return a;
	}

	void setState(int s, long now) {
		if (s == state) return;
		// Mitten im Überblenden: vom überwiegend sichtbaren Zustand weiterblenden.
		prevState = blend(now) >= 0.5f ? state : prevState;
		state = s;
		stateSince = now;
	}

	static double wrap(double deg) {
		deg %= 360.0;
		if (deg >= 180.0) deg -= 360.0;
		if (deg < -180.0) deg += 360.0;
		return deg;
	}
}
