package dev.theredstonee.trsclient.core.cosmetic.pet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Begleiter am Boden (Winter 2026: Pinguine) – reine Simulation ohne Minecraft-Klassen, 20 Ticks je Sekunde. Je
 * Besitzer bis zu {@link #MAX_PER_OWNER} Begleiter: folgen in einer Formation (etwa 1,5–3 Blöcke hinter dem Besitzer),
 * watscheln und schauen zufällig umher, rutschen manchmal auf dem Bauch (öfter auf Schnee/Eis und bergab, mit
 * Tempo-Schub), schwimmen an der Wasseroberfläche, springen 1-Block-Stufen hoch. Ist der Besitzer schnell (Boot,
 * Pferd, Elytra, Eis-Boot), halten sie mit: im Wasser schwimmen sie neben/hinter ihm her, an Land rutschen sie auf dem
 * Bauch hinterher (das ist ihr „schneller Gang“), beides mit Obergrenze. Zum Besitzer gesetzt werden sie nur als letzter
 * Ausweg: ab {@link #TELEPORT_DISTANCE} Blöcken Abstand oder wenn sie wirklich festhängen. Kollision über
 * {@link PetWorld}.
 *
 * <p>Ablauf je Client-Tick: {@link #begin}, {@link #owner} für jeden Spieler in der Welt mit Begleiter, {@link #end}.
 * Zeichnen: {@link #visible} (Entfernung, hinter der Kamera, Obergrenze gesamt) und {@link #alpha} zum Überblenden
 * zwischen zwei Ticks. Nur Spiel-/Render-Thread. Jeder Client rechnet selbst – die Begleiter anderer sehen bei jedem
 * Betrachter etwas anders aus, gehen aber immer dem Besitzer nach.
 */
public final class PetSim {
	public static final int MAX_PER_OWNER = 4;
	/** Weiter weg → der Begleiter wird zum Besitzer gesetzt. */
	public static final double TELEPORT_DISTANCE = 20.0;
	/** Folgen: Abstand zum Besitzer, ab dem nachgelaufen wird. */
	static final double FOLLOW_FAR = 3.0;
	static final double WALK_SPEED = 0.11, MAX_SPEED = 0.42, SWIM_SPEED = 0.16;
	/** Höchsttempo beim zufälligen Bauchrutschen (Blöcke je Tick). */
	static final double SLIDE_MAX = 0.55;
	/**
	 * Hinterherkommen bei schnellem Besitzer: ab {@link #CHASE_SLIDE} Blöcken je Tick rutschen sie an Land auf dem Bauch
	 * (bis {@link #CHASE_MAX}), im Wasser schwimmen sie bis {@link #SWIM_MAX} (Boot ≈ 0,4, Pferd bis ≈ 0,7).
	 */
	static final double CHASE_SLIDE = 0.3, CHASE_MAX = 1.1, SWIM_MAX = 0.8;
	/** Längster Einzelschritt beim Bewegen (schnelle Begleiter prüfen ihren Weg in Teilschritten, nicht durch Wände). */
	static final double MAX_STEP = 0.45;
	/** Weiter als so viele Blöcke in einem Tick: der Besitzer wurde versetzt (Teleport), kein echtes Tempo. */
	static final double OWNER_JUMP = 5.0;
	/** Höhenunterschiede: ohne Sprung (Stufe, Treppe) bzw. mit Sprung (ein Block). */
	static final double STEP = 0.6, JUMP_HEIGHT = 1.25;
	static final double JUMP_VELOCITY = 0.44, GRAVITY = 0.08, DRAG = 0.98;
	/** Höhe des Begleiters (Kopffreiheit), Tiefe des Ursprungs unter der Wasseroberfläche beim Schwimmen. */
	static final double HEIGHT = 0.8, SWIM_DEPTH = 0.3;
	/** Mindesttiefe des Wassers zum Schwimmen (flacher = waten). */
	static final double SWIM_MIN_DEPTH = 0.55;
	/** Wie lange ein Besitzer ohne Meldung behalten wird (Welt-/Sichtweiten-Wechsel). */
	static final int OWNER_TIMEOUT_TICKS = 100;

	/** Plätze der Formation: [seitlich (+ = links vom Besitzer), hinten] in Blöcken. */
	static final double[][] SLOTS = { { -0.9, 1.7 }, { 0.95, 1.9 }, { -0.25, 2.75 }, { 1.35, 2.9 } };

	/** Ergebnis von {@link #surface}: kein Boden in der Suchtiefe, Wand im Weg, Chunk unbekannt. */
	static final double NONE = Double.NaN, WALL = Double.POSITIVE_INFINITY, UNKNOWN = Double.NEGATIVE_INFINITY;

	static final class Owner {
		final UUID id;
		final List<Pet> pets = new ArrayList<Pet>(MAX_PER_OWNER);
		double x, y, z, lastX, lastZ;
		/** Waagerechtes Tempo (Blöcke je Tick, geglättet) – beim Reiten das des Fahrzeugs. */
		double speed;
		/** Steht/schwimmt der Besitzer (Boden oder Wasser bis 3 Blöcke unter ihm) – sonst fliegt er. */
		boolean grounded = true;
		/** Blickrichtung der Formation (Gieren); ändert sich nur, wenn der Besitzer läuft. */
		double heading;
		boolean hidden;
		boolean self;
		long seenTick;

		Owner(UUID id) {
			this.id = id;
		}
	}

	private final Map<UUID, Owner> owners = new HashMap<UUID, Owner>();
	private Object worldKey;
	private long tick;
	private long now;
	private long tickNanos;
	/** Nur Selbsttests: Zufall beim Rutschen erzwingen (1 = immer, 0 = nie, NaN = normal). */
	public static volatile double forceSlide = Double.NaN;
	/**
	 * Nur Selbsttests (Vergleich mit der Studio-Werkbank): {@code frozen} = Begleiter nicht bewegen, {@code forceState}
	 * = Zustand vorgeben ({@link Pet#IDLE} …, −1 = normal), {@code forceTimeMs} = feste Animationszeit ohne eigenen
	 * Versatz (−1 = Wanduhr).
	 */
	public static volatile boolean frozen;
	public static volatile int forceState = -1;
	public static volatile long forceTimeMs = -1;

	/** Nur Selbsttests: alle Begleiter eines Besitzers nebeneinander (je 0,9 Blöcke nach links) hinstellen. */
	public void debugPose(UUID owner, double x, double y, double z, double yawDeg) {
		Owner o = owners.get(owner);
		if (o == null) return;
		double h = Math.toRadians(yawDeg);
		for (Pet p : o.pets) {
			double side = p.index * 0.9;
			p.x = p.px = x + Math.cos(h) * side;
			p.z = p.pz = z + Math.sin(h) * side;
			p.y = p.py = y;
			p.yaw = p.pyaw = yawDeg;
		}
	}

	/** Beginn eines Ticks; ein anderer {@code worldKey} (neue Welt/Dimension) leert alles. */
	public void begin(Object worldKey, long nowMs) {
		if (worldKey != this.worldKey) {
			owners.clear();
			this.worldKey = worldKey;
		}
		tick++;
		now = nowMs;
		tickNanos = System.nanoTime();
	}

	/**
	 * Ein Besitzer in diesem Tick: {@code count} Begleiter (0 = keine mehr), Position (Füße; reitet er, die Unterseite
	 * des untersten Fahrzeugs – Boot, Pferd, Lore) und Gieren des Körpers.
	 * {@code hidden} = nicht zeichnen (unsichtbar, Zuschauer), weiter simulieren. {@code self} = eigener Spieler (hat
	 * beim Zeichnen Vorrang).
	 */
	public void owner(UUID id, int count, double x, double y, double z, float bodyYaw, boolean hidden, boolean self,
			PetWorld world) {
		if (id == null) return;
		count = Math.max(0, Math.min(MAX_PER_OWNER, count));
		Owner o = owners.get(id);
		if (count == 0) {
			if (o != null) owners.remove(id);
			return;
		}
		if (o == null) {
			o = new Owner(id);
			o.x = o.lastX = x;
			o.y = y;
			o.z = o.lastZ = z;
			o.heading = bodyYaw;
			owners.put(id, o);
		}
		o.lastX = o.x;
		o.lastZ = o.z;
		o.x = x;
		o.y = y;
		o.z = z;
		double step = Math.hypot(o.x - o.lastX, o.z - o.lastZ);
		if (step > OWNER_JUMP) step = 0; // Teleport des Besitzers, kein Tempo
		o.speed = o.speed * 0.5 + step * 0.5;
		if (step > 0.03) o.heading = Math.toDegrees(Math.atan2(-(o.x - o.lastX), o.z - o.lastZ));
		else if (o.pets.isEmpty()) o.heading = bodyYaw;
		o.hidden = hidden;
		o.self = self;
		o.seenTick = tick;
		o.grounded = finite(landing(world, x, z, y + 0.5, 3));
		while (o.pets.size() > count) o.pets.remove(o.pets.size() - 1);
		while (o.pets.size() < count) {
			int index = o.pets.size();
			Pet p = new Pet(id, index, seed(id, index));
			place(p, o, world);
			o.pets.add(p);
		}
		for (Pet p : o.pets) {
			try {
				tickPet(p, o, world);
			} catch (RuntimeException e) {
				place(p, o, world);
			}
		}
	}

	/** Ende des Ticks: Besitzer, die lange nicht gemeldet wurden, fallen weg. */
	public void end() {
		Iterator<Owner> it = owners.values().iterator();
		while (it.hasNext()) {
			if (tick - it.next().seenTick > OWNER_TIMEOUT_TICKS) it.remove();
		}
	}

	/** Alles vergessen (Modul aus, Welt verlassen). */
	public void clear() {
		owners.clear();
		worldKey = null;
	}

	/** Anteil des laufenden Ticks (0…1) zum Überblenden der Positionen, aus der Uhrzeit seit {@link #begin}. */
	public float alpha(long nanos) {
		if (tickNanos == 0) return 1f;
		double a = (nanos - tickNanos) / 50_000_000.0;
		return (float) Math.max(0, Math.min(1, a));
	}

	/** Begleiter eines Besitzers (nur lesen; leer, wenn keiner). */
	public List<Pet> pets(UUID owner) {
		Owner o = owners.get(owner);
		return o == null ? java.util.Collections.<Pet>emptyList() : java.util.Collections.unmodifiableList(o.pets);
	}

	/** Anzahl aller Begleiter (alle Besitzer). */
	public int size() {
		int n = 0;
		for (Owner o : owners.values()) n += o.pets.size();
		return n;
	}

	private final List<Pet> scratch = new ArrayList<Pet>();

	/**
	 * Zu zeichnende Begleiter dieses Bildes in {@code out} (höchstens {@code cap} und {@code out.length}): sichtbare
	 * Besitzer aus diesem Tick, höchstens {@code maxDistance} von der Kamera, nicht deutlich hinter ihr ({@code f*} =
	 * Blickrichtung, normiert). Eigene zuerst, dann nach Entfernung. Liefert die Anzahl.
	 */
	public int visible(double cx, double cy, double cz, double fx, double fy, double fz, double maxDistance, int cap,
			float alpha, Pet[] out) {
		scratch.clear();
		final double max2 = maxDistance * maxDistance;
		for (Owner o : owners.values()) {
			if (o.hidden || o.seenTick != tick) continue;
			for (Pet p : o.pets) {
				double dx = p.x(alpha) - cx, dy = p.y(alpha) + 0.4 - cy, dz = p.z(alpha) - cz;
				double d2 = dx * dx + dy * dy + dz * dz;
				if (d2 > max2) continue;
				// Hinter der Kamera (mit Rand für das Modell selbst)
				if (dx * fx + dy * fy + dz * fz < -1.5) continue;
				p.sortKey = (o.self ? 0 : 1e9) + d2;
				scratch.add(p);
			}
		}
		java.util.Collections.sort(scratch, BY_KEY);
		int n = Math.min(Math.min(cap, out.length), scratch.size());
		for (int i = 0; i < n; i++) out[i] = scratch.get(i);
		Arrays.fill(out, n, out.length, null);
		return n;
	}

	private static final Comparator<Pet> BY_KEY = new Comparator<Pet>() {
		@Override
		public int compare(Pet a, Pet b) {
			return Double.compare(a.sortKey, b.sortKey);
		}
	};

	// =====================================================================================================
	// Verhalten eines Begleiters
	// =====================================================================================================

	static long seed(UUID owner, int index) {
		return owner.getMostSignificantBits() * 31L + owner.getLeastSignificantBits() * 17L + index * 7919L;
	}

	/** Platz {@code index} der Formation hinter dem Besitzer (Weltkoordinaten x, z). */
	static void slot(Owner o, int index, double[] out) {
		double[] s = SLOTS[Math.max(0, Math.min(SLOTS.length - 1, index))];
		double h = Math.toRadians(o.heading);
		double fx = -Math.sin(h), fz = Math.cos(h);
		double lx = Math.cos(h), lz = Math.sin(h);
		// Schnell (Boot, Pferd): etwas weiter zurück, damit sie nicht im Fahrzeug stecken
		double back = s[1] * (1 + Math.min(0.5, o.speed * 1.2));
		out[0] = o.x + lx * s[0] - fx * back;
		out[1] = o.z + lz * s[0] - fz * back;
	}

	private final double[] tmp = new double[2];

	/** Neu beim Besitzer absetzen (erstes Erscheinen, Teleport, festgesteckt). */
	void place(Pet p, Owner o, PetWorld w) {
		slot(o, p.index, tmp);
		double sx = tmp[0], sz = tmp[1];
		// Boden oder – auf dem Meer – die Wasseroberfläche (nie auf den Meeresgrund)
		double g = landing(w, sx, sz, o.y + 1.0, PLACE_DEPTH);
		if (!finite(g) || !headroom(w, sx, sz, g)) {
			sx = o.x;
			sz = o.z;
			g = landing(w, sx, sz, o.y + 0.5, PLACE_DEPTH);
		}
		p.x = sx;
		p.z = sz;
		p.y = finite(g) ? g : o.y;
		p.onGround = finite(g);
		p.vx = p.vy = p.vz = 0;
		p.yaw = o.heading;
		p.px = p.x;
		p.py = p.y;
		p.pz = p.z;
		p.pyaw = p.yaw;
		p.slideTicks = 0;
		p.stuckTicks = 0;
		p.wanderX = p.wanderZ = Double.NaN;
		p.lookYaw = Double.NaN;
		p.state = Pet.IDLE;
		p.prevState = Pet.IDLE;
		p.stateSince = now - Pet.FADE_MS;
	}

	void tickPet(Pet p, Owner o, PetWorld w) {
		p.px = p.x;
		p.py = p.y;
		p.pz = p.z;
		p.pyaw = p.yaw;
		if (frozen) {
			// Selbsttest: stehen lassen, wo {@link #debugPose} sie hingestellt hat, Zustand vorgegeben
			int fs = forceState;
			if (fs >= 0 && fs != p.state) {
				p.state = p.prevState = fs;
				p.stateSince = now - Pet.FADE_MS;
			}
			return;
		}
		if (p.jumpCooldown > 0) p.jumpCooldown--;
		if (p.slideCooldown > 0) p.slideCooldown--;
		if (p.teleportCooldown > 0) p.teleportCooldown--;
		double dxo = o.x - p.x, dzo = o.z - p.z, dyo = o.y - p.y;
		double distOwner = Math.hypot(dxo, dzo);
		// Letzter Ausweg: zu weit weg. Höhe zählt nur, wenn der Besitzer steht/schwimmt (fliegt er hoch, bleiben sie unten
		// und laufen nach); ohne Landeplatz beim Besitzer (hoch über der Leere) bleiben sie, wo sie sind.
		boolean far = distOwner > TELEPORT_DISTANCE || o.grounded && Math.abs(dyo) > TELEPORT_DISTANCE;
		if (far && p.teleportCooldown == 0) {
			if (teleport(p, o, w)) return;
			p.teleportCooldown = 20;
		}
		// Umgebung
		int bx = floor(p.x), bz = floor(p.z);
		boolean water = (w.block(bx, floor(p.y + 0.2), bz) & PetWorld.WATER) != 0
				|| (w.block(bx, floor(p.y + SWIM_DEPTH + 0.05), bz) & PetWorld.WATER) != 0;
		double floorY = surface(w, p.x, p.z, p.y + 0.05, 6);
		double waterTop = water ? waterSurface(w, bx, floor(p.y + 0.2), bz) : Double.NaN;
		boolean deep = water && (!finite(floorY) || waterTop - floorY >= SWIM_MIN_DEPTH);
		p.inWater = deep;
		int under = w.block(bx, floor(p.y - 0.01), bz) | w.block(bx, floor(p.y + 0.01), bz);
		boolean slippery = (under & PetWorld.SLIPPERY) != 0;

		// Ziel und Tempo
		slot(o, p.index, tmp);
		double tx = tmp[0], tz = tmp[1];
		double dSlot = Math.hypot(tx - p.x, tz - p.z);
		boolean follow = dSlot > 1.2 || distOwner > FOLLOW_FAR;
		double speed = 0;
		double gx = Double.NaN, gz = Double.NaN;
		if (follow) {
			p.wanderX = p.wanderZ = Double.NaN;
			p.lookYaw = Double.NaN;
			gx = tx;
			gz = tz;
			speed = WALK_SPEED + 0.08 * Math.max(0, distOwner - FOLLOW_FAR);
			// Mit dem Besitzer mithalten (sein Tempo + Aufholen zum Platz), sonst fällt die Formation auseinander.
			if (distOwner > 2.0) speed = Math.max(speed, o.speed * 1.08 + 0.06 * dSlot);
			// Obergrenze je Gangart: schwimmen, auf dem Bauch rutschen (schneller Gang, siehe unten), sonst watscheln
			speed = Math.min(deep ? SWIM_MAX : CHASE_MAX, speed);
		} else {
			if (!Double.isNaN(p.wanderX)) {
				gx = p.wanderX;
				gz = p.wanderZ;
				speed = WALK_SPEED * 0.65;
				if (--p.wanderTicks <= 0 || Math.hypot(gx - p.x, gz - p.z) < 0.2) p.wanderX = p.wanderZ = Double.NaN;
			} else if (--p.idleTimer <= 0) {
				double r = p.random.nextDouble();
				if (r < 0.45) {
					p.lookYaw = p.yaw + (p.random.nextDouble() * 200 - 100);
				} else if (r < 0.82) {
					double a = p.random.nextDouble() * Math.PI * 2;
					double d = 0.5 + p.random.nextDouble() * 1.0;
					p.wanderX = tx + Math.cos(a) * d;
					p.wanderZ = tz + Math.sin(a) * d;
					p.wanderTicks = 80;
					p.lookYaw = Double.NaN;
				} else {
					p.lookYaw = Math.toDegrees(Math.atan2(-dxo, dzo));
				}
				p.idleTimer = 40 + p.random.nextInt(110);
			}
		}
		double dirX = 0, dirZ = 0;
		if (!Double.isNaN(gx)) {
			double dx = gx - p.x, dz = gz - p.z;
			double d = Math.hypot(dx, dz);
			if (d > 1e-4) {
				dirX = dx / d;
				dirZ = dz / d;
			}
			speed *= Math.min(1.0, d / 0.8);
			if (d < 0.12) speed = 0;
		}
		// Nicht aufeinander stehen: Begleiter desselben Besitzers schieben sich sanft auseinander.
		double sepX = 0, sepZ = 0;
		for (Pet q : o.pets) {
			if (q == p) continue;
			double ddx = p.x - q.x, ddz = p.z - q.z;
			double dd = Math.hypot(ddx, ddz);
			if (dd < 0.7 && dd > 1e-4) {
				sepX += ddx / dd * (0.7 - dd) * 0.12;
				sepZ += ddz / dd * (0.7 - dd) * 0.12;
			}
		}

		// Schneller Gang an Land: auf dem Bauch hinterherrutschen (ohne Zufall und ohne Pause zwischen zwei Rutschern)
		boolean chase = follow && !deep && !water && p.onGround && speed > CHASE_SLIDE;
		if (chase) {
			p.slideTicks = Math.max(p.slideTicks, 8);
			p.slideX = dirX * speed;
			p.slideZ = dirZ * speed;
		} else if (!deep) {
			speed = Math.min(MAX_SPEED, speed);
		}

		// Bauchrutschen
		boolean downhill = speed > 0 && downhill(w, p, dirX, dirZ);
		if (chase) {
			// Tempo kommt vom Hinterherkommen, nicht von der Reibung
			p.slideTicks--;
		} else if (p.slideTicks > 0) {
			p.slideTicks--;
			double friction = slippery ? 0.985 : 0.955;
			if (downhill) friction += 0.03;
			p.slideX *= friction;
			p.slideZ *= friction;
			// leicht zum Ziel lenken, Tempo bleibt
			double s = Math.hypot(p.slideX, p.slideZ);
			if (s > 1e-4 && (dirX != 0 || dirZ != 0)) {
				double nx = p.slideX / s * 0.94 + dirX * 0.06, nz = p.slideZ / s * 0.94 + dirZ * 0.06;
				double nl = Math.hypot(nx, nz);
				p.slideX = nx / nl * s;
				p.slideZ = nz / nl * s;
			}
			if (s < 0.06 || deep || !p.onGround && p.airTicks > 6) endSlide(p);
		} else if (!deep && !water && p.onGround && speed > 0.06 && p.slideCooldown == 0) {
			double chance = 0.0035;
			if (slippery) chance *= 5;
			if (downhill) chance *= 3;
			if (distOwner > 4) chance *= 2;
			double fs = forceSlide;
			if (!Double.isNaN(fs)) chance = fs;
			if (p.random.nextDouble() < chance) {
				p.slideTicks = 20 + p.random.nextInt(26);
				double s = clamp(speed * 1.7, 0.30, SLIDE_MAX);
				p.slideX = dirX * s;
				p.slideZ = dirZ * s;
			}
		}
		double mx, mz;
		if (p.slideTicks > 0) {
			mx = p.slideX;
			mz = p.slideZ;
		} else {
			mx = dirX * speed + sepX;
			mz = dirZ * speed + sepZ;
		}

		// Waagerecht bewegen (mit Stufen, Sprüngen, Wänden) – schnell in Teilschritten
		double beforeX = p.x, beforeZ = p.z;
		int parts = Math.max(1, (int) Math.ceil(Math.hypot(mx, mz) / MAX_STEP));
		boolean blocked = false;
		for (int i = 0; i < parts && !blocked; i++) blocked = move(p, w, mx / parts, mz / parts, deep);
		if (blocked && p.slideTicks > 0) endSlide(p);

		// Senkrecht
		if (deep) {
			double targetY = waterTop - SWIM_DEPTH;
			p.vy = clamp((targetY - p.y) * 0.25, -0.12, 0.12);
			p.y += p.vy;
			double g = surface(w, p.x, p.z, p.y + 0.3, 4);
			if (finite(g) && p.y < g) p.y = g;
			p.onGround = false;
			p.airTicks = 0;
		} else {
			fall(p, w);
		}

		// Festgesteckt (Wand, Klippe: will laufen, kommt aber nicht vom Fleck) → zum Besitzer. Nur echtes Feststecken
		// zählt: ein schnelles Ziel allein (Boot, Pferd) nicht – solange er vorankommt, läuft/schwimmt er nach.
		double moved = Math.hypot(p.x - beforeX, p.z - beforeZ);
		if (follow && distOwner > 6 && speed > 0.05 && moved < 0.02) {
			if (++p.stuckTicks > 60 && teleport(p, o, w)) return;
		} else {
			p.stuckTicks = 0;
		}

		// Zustand
		double hs = Math.hypot(p.x - p.px, p.z - p.pz);
		int st;
		if (deep) st = Pet.SWIM;
		else if (p.slideTicks > 0) st = Pet.SLIDE;
		else if (!p.onGround && p.airTicks >= 2) st = Pet.AIR;
		else if (hs > 0.012) st = Pet.WALK;
		else st = Pet.IDLE;
		p.setState(st, now);

		// Blickrichtung
		double want = Double.NaN;
		double rate = 9;
		if (p.slideTicks > 0 && Math.hypot(p.slideX, p.slideZ) > 1e-3) {
			want = Math.toDegrees(Math.atan2(-p.slideX, p.slideZ));
			rate = 14;
		} else if (hs > 0.012) {
			want = Math.toDegrees(Math.atan2(-(p.x - p.px), p.z - p.pz));
			rate = 20;
		} else if (!Double.isNaN(p.lookYaw)) {
			want = p.lookYaw;
		}
		if (!Double.isNaN(want)) {
			double d = Pet.wrap(want - p.yaw);
			p.yaw += clamp(d, -rate, rate);
			if (!Double.isNaN(p.lookYaw) && Math.abs(d) < 1) p.lookYaw = Double.NaN;
		}
		p.yaw = Pet.wrap(p.yaw);
	}

	private void endSlide(Pet p) {
		p.slideTicks = 0;
		p.slideCooldown = 60 + p.random.nextInt(100);
	}

	/**
	 * Waagerecht um ({@code mx}, {@code mz}) bewegen: kleine Stufen hoch/runter, Sprung über einen Block, an Wänden
	 * entlang gleiten. true = (teilweise) blockiert.
	 */
	boolean move(Pet p, PetWorld w, double mx, double mz, boolean swimming) {
		if (Math.abs(mx) < 1e-6 && Math.abs(mz) < 1e-6) return false;
		if (tryMove(p, w, mx, mz, swimming)) return false;
		// An der Wand entlang gleiten (erst x, sonst z)
		if (!(Math.abs(mx) > 1e-4 && tryMove(p, w, mx, 0, swimming)) && Math.abs(mz) > 1e-4) tryMove(p, w, 0, mz, swimming);
		return true;
	}

	private boolean tryMove(Pet p, PetWorld w, double mx, double mz, boolean swimming) {
		double nx = p.x + mx, nz = p.z + mz;
		double s = moveSurface(w, nx, nz, p.y);
		if (s == WALL || s == UNKNOWN) return false;
		if (finite(s) && s > p.y + STEP) {
			// Ein Block hoch: springen (am Boden oder im Wasser), solange noch zu tief stehen bleiben.
			if (s <= p.y + JUMP_HEIGHT + (swimming ? SWIM_DEPTH : 0) && (p.onGround || swimming) && p.jumpCooldown == 0) {
				p.vy = JUMP_VELOCITY;
				p.onGround = false;
				p.jumpCooldown = 8;
				if (swimming) p.y = Math.max(p.y, s - JUMP_HEIGHT + 0.05);
			}
			return false;
		}
		if (!finite(s)) {
			// Kein Boden in der Nähe: tiefes Wasser (Meer) – hinein/weiter schwimmen; tiefe Klippe/Leere – stehen bleiben.
			if (!waterAround(w, nx, nz, p.y)) return false;
			p.x = nx;
			p.z = nz;
			return true;
		}
		p.x = nx;
		p.z = nz;
		// Stufe hoch sofort, runter fallen (fall) – kleine Absätze am Boden direkt anschmiegen
		if (s > p.y) p.y = s;
		else if (p.onGround && !swimming && p.y - s <= STEP) p.y = s;
		return true;
	}

	/**
	 * Zum Besitzer setzen (Teleport, letzter Ausweg); false = kein Landeplatz beim Besitzer (er fliegt hoch über der
	 * Leere) – dann bleibt der Begleiter, wo er ist.
	 */
	private boolean teleport(Pet p, Owner o, PetWorld w) {
		if (!finite(landing(w, o.x, o.z, o.y + 1.0, PLACE_DEPTH))) return false;
		place(p, o, w);
		p.teleports++;
		return true;
	}

	/** Schwerkraft und Landen. */
	private void fall(Pet p, PetWorld w) {
		double g = surface(w, p.x, p.z, p.y + 0.05, 6);
		if (g == UNKNOWN) {
			// Chunk nicht geladen: einfach stehen bleiben
			p.vy = 0;
			p.onGround = true;
			p.airTicks = 0;
			return;
		}
		if (g == WALL) {
			// Im Block (z. B. gerade gesetzt): nach oben drücken, wenn es nicht zu hoch ist
			double up = surface(w, p.x, p.z, p.y + 1.05, 2);
			if (finite(up) && up - p.y <= 1.05) p.y = up;
			p.vy = 0;
			p.onGround = true;
			p.airTicks = 0;
			return;
		}
		if (p.onGround && finite(g) && p.y - g < 0.01 && p.vy <= 0) {
			p.y = g;
			p.vy = 0;
			p.airTicks = 0;
			return;
		}
		p.onGround = false;
		p.vy -= GRAVITY;
		p.vy *= DRAG;
		double ny = p.y + p.vy;
		if (p.vy > 0) {
			// Kopf stößt an?
			int hb = w.block(floor(p.x), floor(ny + HEIGHT), floor(p.z));
			if ((hb & PetWorld.SOLID) != 0 && floor(ny + HEIGHT) > floor(p.y + HEIGHT)) {
				p.vy = 0;
				ny = p.y;
			}
		}
		if (finite(g) && ny <= g) {
			p.y = g;
			p.vy = 0;
			p.onGround = true;
			p.airTicks = 0;
		} else {
			p.y = ny;
			p.airTicks++;
		}
	}

	/** Liegt der Boden 1,5 Blöcke voraus deutlich tiefer (bergab)? */
	private boolean downhill(PetWorld w, Pet p, double dx, double dz) {
		if (dx == 0 && dz == 0) return false;
		double ahead = surface(w, p.x + dx * 1.5, p.z + dz * 1.5, p.y + 0.3, 3);
		return finite(ahead) && ahead <= p.y - 0.4;
	}

	// =====================================================================================================
	// Welt-Abfragen
	// =====================================================================================================

	/**
	 * Oberkante des ersten Bodens in der Säule ({@code x}, {@code z}) auf oder unter {@code fromY}, höchstens
	 * {@code depth} Blöcke tiefer. {@link #WALL}, wenn zuerst ein Block kommt, der über {@code fromY} hinausragt (oder
	 * gefährlich ist), {@link #NONE} ohne Boden, {@link #UNKNOWN} bei nicht geladenem Chunk.
	 */
	static double surface(PetWorld w, double x, double z, double fromY, int depth) {
		int bx = floor(x), bz = floor(z);
		int start = floor(fromY);
		for (int by = start; by >= start - depth; by--) {
			int c = w.block(bx, by, bz);
			if ((c & PetWorld.UNLOADED) != 0) return UNKNOWN;
			if ((c & PetWorld.DANGER) != 0) return WALL;
			if ((c & PetWorld.SOLID) != 0) {
				double top = by + PetWorld.top(c);
				return top > fromY + 1e-4 ? WALL : top;
			}
		}
		return NONE;
	}

	/** Boden für einen Schritt nach ({@code x}, {@code z}) von der Höhe {@code y}: mit Sprunghöhe, sonst darunter durch. */
	static double moveSurface(PetWorld w, double x, double z, double y) {
		double s = surface(w, x, z, y + JUMP_HEIGHT, 8);
		if (s == UNKNOWN) return UNKNOWN;
		if (s != WALL && (!finite(s) || headroom(w, x, z, s))) return s;
		// Oben zu (Decke, Tunnel): auf Stufenhöhe darunter durch?
		double low = surface(w, x, z, y + STEP, 8);
		if (low == UNKNOWN) return UNKNOWN;
		if (low != WALL && finite(low) && headroom(w, x, z, low)) return low;
		return WALL;
	}

	/** Ist über dem Boden {@code s} genug Platz ({@link #HEIGHT})? */
	static boolean headroom(PetWorld w, double x, double z, double s) {
		int bx = floor(x), bz = floor(z);
		for (int by = floor(s + 0.01); by <= floor(s + HEIGHT); by++) {
			int c = w.block(bx, by, bz);
			if ((c & PetWorld.DANGER) != 0) return false;
			if ((c & PetWorld.SOLID) == 0) continue;
			if (by + PetWorld.top(c) <= s + 0.01) continue; // der Block, auf dem man steht (Stufe)
			return false;
		}
		return true;
	}

	/** Wie tief {@link #place} unter dem Besitzer nach Boden/Wasser sucht. */
	static final int PLACE_DEPTH = 48;

	/**
	 * Wo ein Begleiter in der Säule ({@code x}, {@code z}) ab {@code fromY} abwärts landet: Oberkante des ersten Bodens
	 * oder – kommt vorher tiefes Wasser – Schwimmhöhe an dessen Oberfläche. Sonst wie {@link #surface}.
	 */
	static double landing(PetWorld w, double x, double z, double fromY, int depth) {
		int bx = floor(x), bz = floor(z);
		int start = floor(fromY);
		for (int by = start; by >= start - depth; by--) {
			int c = w.block(bx, by, bz);
			if ((c & PetWorld.UNLOADED) != 0) return UNKNOWN;
			if ((c & PetWorld.DANGER) != 0) return WALL;
			if ((c & PetWorld.SOLID) != 0) {
				double top = by + PetWorld.top(c);
				return top > fromY + 1e-4 ? WALL : top;
			}
			if ((c & PetWorld.WATER) != 0) {
				double top = waterSurface(w, bx, by, bz);
				// Flaches Wasser: am Grund stehen (waten)
				int below = w.block(bx, by - 1, bz);
				if ((below & PetWorld.SOLID) != 0 && top - (by - 1 + PetWorld.top(below)) < SWIM_MIN_DEPTH) {
					return by - 1 + PetWorld.top(below);
				}
				return top - SWIM_DEPTH;
			}
		}
		return NONE;
	}

	/** Wasser in der Säule nahe der Höhe {@code y} (bis 2 darunter, knapp darüber) – dort kann man hinein schwimmen. */
	static boolean waterAround(PetWorld w, double x, double z, double y) {
		int bx = floor(x), bz = floor(z);
		for (int by = floor(y + SWIM_DEPTH + 0.05); by >= floor(y) - 2; by--) {
			int c = w.block(bx, by, bz);
			if ((c & PetWorld.WATER) != 0) return true;
			if ((c & (PetWorld.SOLID | PetWorld.DANGER | PetWorld.UNLOADED)) != 0) return false;
		}
		return false;
	}

	/** Wasseroberfläche über ({@code bx}, {@code by}, {@code bz}) (Quelle ≈ 0,9 über der Blockunterseite). */
	static double waterSurface(PetWorld w, int bx, int by, int bz) {
		int top = by;
		for (int i = 0; i < 6 && (w.block(bx, top + 1, bz) & PetWorld.WATER) != 0; i++) top++;
		return top + 0.9;
	}

	static boolean finite(double v) {
		return !Double.isNaN(v) && !Double.isInfinite(v);
	}

	static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}
}
