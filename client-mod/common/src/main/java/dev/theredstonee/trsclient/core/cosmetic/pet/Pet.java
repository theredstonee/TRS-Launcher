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
	/** Zustände (Anhang D {@code PET_STATES}). */
	public static final int IDLE = 0, WALK = 1, SLIDE = 2, SWIM = 3, AIR = 4;
	static final String[] STATE_NAMES = { "idle", "walk", "slide", "swim", "air" };
	/** Überblenden zwischen zwei Zuständen (wie §7). */
	public static final long FADE_MS = 250L;

	public final UUID owner;
	/** Platz in der Formation (0…3). */
	public final int index;
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
	/** Nur {@link PetSim#visible}: Reihenfolge beim Zeichnen. */
	double sortKey;

	Pet(UUID owner, int index, long seed) {
		this.owner = owner;
		this.index = index;
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

	/** Treiber-Index der Animation eines Zustands ({@link CosmeticV2#DRIVER_IDLE} …). */
	public static int driver(int state) {
		switch (state) {
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
