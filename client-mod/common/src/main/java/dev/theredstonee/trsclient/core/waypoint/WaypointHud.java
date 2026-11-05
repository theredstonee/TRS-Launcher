package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapLabels;
import dev.theredstonee.trsclient.core.map.MapSprites;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.render.Projection;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.waypoint.live.LiveHud;
import dev.theredstonee.trsclient.core.waypoint.live.LiveShare;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wegpunkte in der Welt, für alle Versionen gleich: Kästchen mit Größe nach Entfernung, Name und Entfernung (je nach
 * Einstellung beim Draufschauen weich eingeblendet), Lichtstrahl nur fürs aktuelle Ziel und ein Pfeil am Bildschirmrand,
 * wenn das Ziel außerhalb des Blickfelds liegt. Gezeichnet wird im 2D-HUD über die eigene Projektion ({@link Projection})
 * – keine versionsabhängige Welt-Renderei. Die Bäume liefern nur Kamera und Zeichenfläche.
 */
public final class WaypointHud {
	/** Abstand zur Bildmitte (GUI-Pixel), ab dem „Draufschauen“ zählt. */
	static final int LOOK_RADIUS = 28;
	/** Sekunden fürs Ein-/Ausblenden der Beschriftung. */
	private static final float FADE_SECONDS = 0.15f;
	private static final int BEAM_HEIGHT = 96;
	private static final int BEAM_STEPS = 16;
	/** Randabstand des Ziel-Pfeils. */
	private static final int EDGE_MARGIN = 26;
	private static final int EDGE_MARGIN_BOTTOM = 62;

	private final double[] point = new double[3];
	private final double[] top = new double[3];
	private final LiveHud friends = new LiveHud();
	private final MapLabels own = new MapLabels();
	/** Wegpunkt, auf den zuletzt geschaut wurde. */
	private Waypoint looked;
	/** Einblendung der Beschriftung je Wegpunkt (0..1). */
	private final Map<Waypoint, float[]> fades = new IdentityHashMap<Waypoint, float[]>();
	/** Beschriftung je Wegpunkt: nur neu gebaut, wenn sich Name, angezeigte Entfernung oder Sprache ändern. */
	private final Map<Waypoint, Object[]> labels = new IdentityHashMap<Waypoint, Object[]>();
	private long lastNanos;

	/** Größe des Kästchens nach Entfernung: nah 1,3 – ab rund 500 Blöcken 0,7. */
	public static float scaleFor(double distance) {
		double d = Math.max(8, Math.min(512, distance));
		double t = (Math.log(d) - Math.log(8)) / (Math.log(512) - Math.log(8));
		return (float) (1.3 - 0.6 * t);
	}

	/** Deckkraft nach Entfernung: ganz nah blendet das Kästchen aus, damit es nicht im Weg ist. */
	static int nearAlpha(double distance) {
		if (distance >= 4) return 255;
		if (distance <= 1.5) return 0;
		return (int) Math.round(255 * (distance - 1.5) / 2.5);
	}

	/** Soll die Beschriftung eingeblendet sein? */
	static boolean labelWanted(WaypointLabels mode, boolean target, boolean lookedAt) {
		if (mode == WaypointLabels.NEVER) return false;
		if (mode == WaypointLabels.ALWAYS || target) return true;
		return lookedAt;
	}

	/** Wird im HUD je Bild aufgerufen. {@code fov} senkrechtes Sichtfeld (Grad), {@code w}/{@code h} GUI-Größe. */
	public void draw(Canvas c, TrsModules m, List<Waypoint> list, Waypoint target, double camX, double camY, double camZ, float yaw,
			float pitch, double fov, int w, int h) {
		LiveShare live = LiveShare.get();
		boolean withFriends = live != null && live.active();
		if (list.isEmpty() && !withFriends) return;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.2f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;

		// Freunde zuerst – die eigenen Wegpunkte liegen darüber.
		if (withFriends) friends.draw(c, m, camX, camY, camZ, yaw, pitch, fov, w, h);
		if (fades.size() > 512) fades.clear();
		if (labels.size() > 512) labels.clear();

		double range = m.waypointRange.get();
		WaypointLabels mode = m.waypointLabels.get();
		boolean withDistance = m.waypointDistance.get();
		boolean targetDrawn = false;
		Waypoint nearest = null;
		int nearestOff = Integer.MAX_VALUE;
		for (int i = 0, n = list.size(); i < n; i++) {
			Waypoint wp = list.get(i);
			boolean isTarget = wp == target;
			double distance = wp.distanceTo(camX, camY, camZ);
			if (!isTarget && range > 0 && distance > range) continue;
			double wx = wp.x + 0.5, wy = wp.y + 0.5, wz = wp.z + 0.5;
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, w, h, wx, wy, wz, point)) continue;
			int x = (int) Math.round(point[0]);
			int y = (int) Math.round(point[1]);
			if (x < -40 || x > w + 40 || y < -40 || y > h + 40) continue;
			if (isTarget) {
				if (x >= 0 && x <= w && y >= 0 && y <= h) targetDrawn = true;
				if (m.waypointBeam.get()) beam(c, camX, camY, camZ, yaw, pitch, fov, w, h, wx, wy, wz, wp.color, distance);
			}
			int alpha = nearAlpha(distance);
			if (alpha == 0) continue;
			float k = scaleFor(distance);
			int dx = x - w / 2, dy = y - h / 2;
			int off = dx * dx + dy * dy;
			// Beim Draufschauen zählt nur der Wegpunkt, der der Bildmitte am nächsten liegt (Stand des letzten Bilds).
			if (off <= LOOK_RADIUS * LOOK_RADIUS && off < nearestOff) {
				nearestOff = off;
				nearest = wp;
			}
			float fade = fade(wp, labelWanted(mode, isTarget, wp == looked), dt);
			// Beim Draufschauen ist das Kästchen einen Tick größer.
			WaypointBadge.draw(c, wp, x, y, k + 0.2f * fade, alpha);
			if (fade > 0.03f) {
				String text = label(c, wp, distance, withDistance);
				int tw = labelWidth;
				int tx = x - tw / 2;
				int ty = y - Math.round((WaypointBadge.SIZE / 2f) * (k + 0.2f)) - 11;
				int textA = Math.round(fade * alpha);
				if (textA >= 16) {
					own.add(text, tx, ty, ColorMath.withAlpha(ColorMath.lerp(0xFF000000 | wp.color, 0xFFFFFFFF, 0.45f), textA),
							ColorMath.withAlpha(0xFF0E0D12, Math.round(fade * Math.min(alpha, 0xB0))), false,
							isTarget || wp == looked ? MapLabels.FORCE : MapLabels.OWN, false);
				}
				if (withFriends) friends.occupy(tx - 2, ty - 1, tx + tw + 2, ty + 9);
			}
		}
		looked = nearest;
		// Beschriftungen liegen über allen Kästchen und weichen einander aus.
		own.flush(c);
		if (withFriends) friends.flush(c);
		if (target != null && !targetDrawn && list.contains(target)) offscreenTarget(c, target, camX, camY, camZ, yaw, pitch, fov, w, h, withDistance);
	}

	private float fade(Waypoint wp, boolean wanted, float dt) {
		float[] f = fades.get(wp);
		if (f == null) {
			f = new float[]{wanted ? 1f : 0f};
			fades.put(wp, f);
			return f[0];
		}
		float step = dt / FADE_SECONDS;
		f[0] = wanted ? Math.min(1f, f[0] + step) : Math.max(0f, f[0] - step);
		return f[0];
	}

	private int labelWidth;

	private String label(Canvas c, Waypoint wp, double distance, boolean withDistance) {
		// Angezeigt wird „123 m“ bzw. „1,2 km“ – der Schlüssel ändert sich genau dann, wenn sich der Text ändert.
		long key = !withDistance ? -1 : (distance >= 1000 ? 1_000_000L + Math.round(distance / 100.0) : Math.round(distance));
		int gen = I18n.generation();
		Object[] cached = labels.get(wp);
		if (cached != null && (Long) cached[0] == key && cached[1] == wp.name && (Integer) cached[4] == gen) {
			labelWidth = (Integer) cached[3];
			return (String) cached[2];
		}
		String text = withDistance ? wp.name + "  " + Projection.distanceLabel(distance) : wp.name;
		labelWidth = c.textWidth(text);
		labels.put(wp, new Object[]{key, wp.name, text, labelWidth, gen});
		return text;
	}

	/** Pfeil, Kästchen und Entfernung am Rand, wenn das Ziel nicht im Bild ist. */
	private void offscreenTarget(Canvas c, Waypoint wp, double camX, double camY, double camZ, float yaw, float pitch, double fov, int w, int h,
			boolean withDistance) {
		double[] dir = Projection.screenDirection(camX, camY, camZ, yaw, pitch, wp.x + 0.5, wp.y + 0.5, wp.z + 0.5);
		// Unten bleibt die Schnellleiste frei.
		double hw = w / 2.0 - EDGE_MARGIN, hh = dir[1] > 0 ? h / 2.0 - EDGE_MARGIN_BOTTOM : h / 2.0 - EDGE_MARGIN;
		double scale = Math.min(hw / Math.max(1e-6, Math.abs(dir[0])), hh / Math.max(1e-6, Math.abs(dir[1])));
		float x = (float) (w / 2.0 + dir[0] * scale), y = (float) (h / 2.0 + dir[1] * scale);
		MapEngine e = MapEngine.get();
		if (e != null && c.images() && e.platform() != null) {
			float rot = (float) Math.atan2(dir[0], -dir[1]);
			float ax = (float) (x + dir[0] * 13), ay = (float) (y + dir[1] * 13);
			e.sprites().draw(c, MapSprites.ARROW, ax, ay, 11f, rot, 0xFF000000 | wp.color, e.platform().guiScale());
		}
		WaypointBadge.draw(c, wp, x, y, 1f, 255);
		if (withDistance) {
			String d = Projection.distanceLabel(wp.distanceTo(camX, camY, camZ));
			int tw = c.textWidth(d);
			int tx = Math.round(x) - tw / 2;
			int ty = y > h / 2f ? Math.round(y) - 18 : Math.round(y) + 9;
			c.fill(tx - 2, ty - 1, tx + tw + 2, ty + 9, 0xB00E0D12);
			c.text(d, tx, ty, 0xFFFFFFFF, false);
		}
	}

	/** Lichtstrahl als Kette kleiner Rechtecke (in der Projektion sind senkrechte Linien schräg); nach oben ausblendend. */
	private void beam(Canvas c, double camX, double camY, double camZ, float yaw, float pitch, double fov, int w, int h, double wx, double wy,
			double wz, int rgb, double distance) {
		int thick = distance < 32 ? 3 : 2;
		int lastX = Integer.MIN_VALUE;
		int lastY = 0;
		for (int step = 0; step <= BEAM_STEPS; step++) {
			double y = wy + (double) BEAM_HEIGHT * step / BEAM_STEPS;
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, w, h, wx, y, wz, top)) break;
			int px = (int) Math.round(top[0]);
			int py = (int) Math.round(top[1]);
			if (lastX != Integer.MIN_VALUE) {
				int alpha = 0xA0 - 0x90 * step / BEAM_STEPS;
				line(c, lastX, lastY, px, py, thick, ColorMath.withAlpha(0xFF000000 | rgb, alpha));
			}
			lastX = px;
			lastY = py;
		}
	}

	/**
	 * Dünne Linie aus Rechtecken. Fast senkrechte Stücke – der Normalfall – werden als ein einziges Rechteck gezeichnet,
	 * sonst in Schritten von 2 Pixeln.
	 */
	private static void line(Canvas c, int x1, int y1, int x2, int y2, int thick, int color) {
		int dx = x2 - x1;
		int dy = y2 - y1;
		if (Math.abs(dx) <= 2) {
			c.fill(x1 - thick / 2, Math.min(y1, y2), x1 - thick / 2 + thick, Math.max(y1, y2) + 1, color);
			return;
		}
		int steps = Math.max(Math.abs(dx), Math.abs(dy)) / 2;
		if (steps <= 0) {
			c.fill(x1, y1, x1 + thick, y1 + 1, color);
			return;
		}
		if (steps > 300) return; // sehr nah dran – lieber nichts als tausende Rechtecke
		for (int i = 0; i <= steps; i++) {
			int x = x1 + dx * i / steps;
			int y = y1 + dy * i / steps;
			c.fill(x, y, x + thick, y + 2, color);
		}
	}
}
