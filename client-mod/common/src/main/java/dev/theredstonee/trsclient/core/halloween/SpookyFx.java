package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.render.Projection;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Spuk-Effekte zu Halloween – reine Optik, Fair Play bleibt unberührt: Fledermäuse fliegen aus einem getroffenen Ziel
 * auf, beim Sprinten bleibt eine violette Geister-Spur zurück (Zahlen wie {@code fxFrame} im Entwurf: Fledermäuse
 * {@code vx ±25, vy −10…−28 px/s, Schwerkraft 4 px/s², Leben 1/0,8 s}; Spur alle 0,07 s, verblasst mit 0,9/s und steigt).
 *
 * <p>Die Teilchen liegen in Weltkoordinaten (Blöcke) und werden im 2D-HUD über die eigene {@link Projection} gezeichnet
 * – keine Welt-Renderei, in allen Versionen gleich. Ein Entwurfs-Pixel entspricht {@link #PX} Blöcken (Spielerfigur
 * 4 px breit ≈ 0,6 Blöcke).
 */
public final class SpookyFx {
	/** Blöcke je Entwurfs-Pixel. */
	static final double PX = 0.15;
	public static final int MAX_BATS = 64;
	public static final int MAX_GHOSTS = 48;
	/** Weiter weg wird nichts erzeugt oder gezeichnet. */
	public static final double MAX_DISTANCE = 48;
	/** Abstand der Spur-Abbilder. */
	static final long TRAIL_EVERY_MS = 70;
	static final int BATS_PER_HIT = 6;

	static final int BAT_BODY = 0xFF2A2240;
	static final int BAT_WING = 0xFFB9A2EA;
	static final int GHOST = 0xFFB9A2EA;

	/** Eine Fledermaus. */
	static final class Bat {
		double x, y, z, vx, vy, vz;
		float life = 1f;
		float phase;
	}

	/** Ein Abbild der Geister-Spur (Spielerfigur, violett). */
	static final class Ghost {
		double x, y, z;
		float alpha = 0.5f;
		/** Blickrichtung der Figur (Grad), damit sie quer zur Kamera richtig breit ist. */
		float yaw;
	}

	private static final SpookyFx INSTANCE = new SpookyFx(new Random());

	final List<Bat> bats = new ArrayList<Bat>();
	final List<Ghost> ghosts = new ArrayList<Ghost>();
	private final Map<Object, Long> lastTrail = new HashMap<Object, Long>();
	private final Random random;
	private final double[] p = new double[3];
	private long lastStep;
	private float clock;

	/** So weit hinter dem Spieler (Blöcke) erscheint ein Spur-Abbild. */
	static final double TRAIL_BEHIND = 1.5;

	SpookyFx(Random random) {
		this.random = random;
	}

	public static SpookyFx get() {
		return INSTANCE;
	}

	/** Fledermäuse an der Trefferstelle (Mitte des Ziels). */
	public synchronized void hit(double x, double y, double z) {
		for (int i = 0; i < BATS_PER_HIT; i++) {
			if (bats.size() >= MAX_BATS) bats.remove(0);
			Bat b = new Bat();
			b.x = x;
			b.y = y;
			b.z = z;
			double ang = random.nextDouble() * Math.PI * 2;
			double speed = (random.nextDouble() - 0.5) * 50 * PX;
			b.vx = Math.cos(ang) * speed;
			b.vz = Math.sin(ang) * speed;
			b.vy = (10 + random.nextDouble() * 18) * PX;
			b.phase = (float) (random.nextDouble() * 6);
			bats.add(b);
		}
	}

	/**
	 * Spur-Punkt eines sprintenden Spielers ({@code key} = UUID o. ä.; Füße bei x/y/z). Höchstens alle
	 * {@link #TRAIL_EVERY_MS} je Spieler ein neues Abbild.
	 */
	public synchronized void trail(Object key, double x, double y, double z, float yaw, long now) {
		Long last = lastTrail.get(key);
		if (last != null && now - last < TRAIL_EVERY_MS) return;
		lastTrail.put(key, now);
		if (lastTrail.size() > 128) lastTrail.clear();
		if (ghosts.size() >= MAX_GHOSTS) ghosts.remove(0);
		// Ein Stück HINTER dem Spieler absetzen (Blickrichtung MC: -sin(yaw), cos(yaw)) – direkt an den Füßen
		// erschien das Abbild in der Ich-Ansicht mitten im Bild.
		double rad = Math.toRadians(yaw);
		Ghost g = new Ghost();
		g.x = x + Math.sin(rad) * TRAIL_BEHIND;
		g.y = y;
		g.z = z - Math.cos(rad) * TRAIL_BEHIND;
		g.yaw = yaw;
		ghosts.add(g);
	}

	/** Weiterrechnen (jedes Bild; {@code now} in ms). */
	public synchronized void step(long now) {
		float dt = lastStep == 0 ? 0f : Math.min(0.1f, (now - lastStep) / 1000f);
		lastStep = now;
		step(dt);
	}

	synchronized void step(float dt) {
		if (dt <= 0) return;
		clock += dt;
		for (int i = bats.size() - 1; i >= 0; i--) {
			Bat b = bats.get(i);
			b.x += b.vx * dt;
			b.y += b.vy * dt;
			b.z += b.vz * dt;
			b.vy -= 4 * PX * dt;
			b.life -= dt * 0.8f;
			if (b.life <= 0) bats.remove(i);
		}
		for (int i = ghosts.size() - 1; i >= 0; i--) {
			Ghost g = ghosts.get(i);
			g.alpha -= dt * 0.9f;
			g.y += 2 * PX * dt;
			if (g.alpha <= 0) ghosts.remove(i);
		}
	}

	/** Ist gerade etwas zu sehen? */
	public synchronized boolean any() {
		return !bats.isEmpty() || !ghosts.isEmpty();
	}

	public synchronized int batCount() {
		return bats.size();
	}

	public synchronized int ghostCount() {
		return ghosts.size();
	}

	/** Alles weg (Weltwechsel). */
	public synchronized void clear() {
		bats.clear();
		ghosts.clear();
		lastTrail.clear();
	}

	/**
	 * Zeichnet alle Teilchen ins HUD. {@code fov} = senkrechtes Sichtfeld in Grad (inkl. Zoom).
	 */
	public synchronized void draw(Canvas c, double camX, double camY, double camZ, float yaw, float pitch, double fov,
			int width, int height) {
		if (bats.isEmpty() && ghosts.isEmpty()) return;
		double tan = Math.tan(Math.toRadians(fov) / 2.0);
		if (tan <= 0) return;
		// Spur zuerst (hinter den Fledermäusen), ältere zuerst.
		for (int i = 0; i < ghosts.size(); i++) {
			Ghost g = ghosts.get(i);
			if (dist2(g.x - camX, g.y - camY, g.z - camZ) > MAX_DISTANCE * MAX_DISTANCE) continue;
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height, g.x, g.y, g.z, p)) continue;
			double unit = height / 2.0 / (p[2] * tan) * PX;
			if (unit < 0.25) continue;
			figure(c, p[0], p[1], unit, ColorMath.fade(GHOST, Math.max(0f, g.alpha * 0.6f)), g.yaw - yaw);
		}
		for (int i = 0; i < bats.size(); i++) {
			Bat b = bats.get(i);
			if (dist2(b.x - camX, b.y - camY, b.z - camZ) > MAX_DISTANCE * MAX_DISTANCE) continue;
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height, b.x, b.y, b.z, p)) continue;
			int u = (int) Math.max(1, Math.round(height / 2.0 / (p[2] * tan) * PX));
			if (u > 12) u = 12;
			int x = (int) Math.round(p[0]) - u;
			int y = (int) Math.round(p[1]);
			float a = Math.max(0f, Math.min(1f, b.life));
			boolean up = Math.sin(clock * 20 + b.phase) > 0;
			c.fill(x + u, y, x + 2 * u, y + u, ColorMath.fade(BAT_BODY, a));
			int wing = ColorMath.fade(BAT_WING, a);
			if (up) {
				c.fill(x, y - u, x + u, y, wing);
				c.fill(x + 2 * u, y - u, x + 3 * u, y, wing);
			} else {
				c.fill(x, y, x + u, y + u, wing);
				c.fill(x + 2 * u, y, x + 3 * u, y + u, wing);
			}
		}
	}

	/**
	 * Spielerfigur wie {@code figure()} im Entwurf (Kopf 4×4, Körper 4×6, Beine 4×6 Entwurfs-Pixel) mit den Füßen bei
	 * (fx, fy); seitlich gesehen schmaler.
	 */
	static void figure(Canvas c, double fx, double fy, double unit, int argb, float relYaw) {
		double side = Math.abs(Math.sin(Math.toRadians(relYaw)));
		double w = unit * (4 - 1.5 * side);
		int x0 = (int) Math.round(fx - w / 2);
		int x1 = (int) Math.round(fx + w / 2);
		int legs = (int) Math.round(fy - unit * 6);
		int body = (int) Math.round(fy - unit * 12);
		int head = (int) Math.round(fy - unit * 16);
		if (x1 <= x0) x1 = x0 + 1;
		c.fill(x0, legs, x1, (int) Math.round(fy), argb);
		c.fill(x0, body, x1, legs, argb);
		c.fill(x0, head, x1, body, argb);
	}

	private static double dist2(double dx, double dy, double dz) {
		return dx * dx + dy * dy + dz * dz;
	}
}
