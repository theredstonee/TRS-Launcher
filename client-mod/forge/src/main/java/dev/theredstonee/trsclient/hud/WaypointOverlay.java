package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.render.Projection;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.List;

/**
 * Zeichnet die Wegpunkte in die Welt: Lichtsäule, Markierung und Name mit Entfernung.
 * Gerechnet wird mit der eigenen Projektion ({@link Projection}) – dadurch reicht zum Zeichnen
 * das normale 2D-HUD und es gibt keine versionsabhängige Welt-Renderei.
 */
public final class WaypointOverlay {
	/** Höhe der Lichtsäule in Blöcken. */
	private static final int BEAM_HEIGHT = 48;
	private static final int BEAM_STEPS = 12;

	private final TrsModules modules;
	private final double[] point = new double[3];
	private final double[] top = new double[3];

	public WaypointOverlay(TrsModules modules) {
		this.modules = modules;
	}

	public void render(Gfx g, Font font) {
		if (!modules.waypoints.isEnabled()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		List<Waypoint> list = TrsClient.get().waypoints().visible();
		if (list.isEmpty()) return;

		double camX = Mc.cameraX();
		double camY = Mc.cameraY();
		double camZ = Mc.cameraZ();
		float yaw = Mc.cameraYaw();
		float pitch = Mc.cameraPitch();
		double fov = TrsClient.get().worldFov();
		int w = g.width();
		int h = g.height();
		double range = modules.waypointRange.get();

		for (int i = 0, n = list.size(); i < n; i++) {
			Waypoint waypoint = list.get(i);
			double distance = waypoint.distanceTo(camX, camY, camZ);
			if (range > 0 && distance > range) continue;
			double wx = waypoint.x + 0.5;
			double wy = waypoint.y + 0.5;
			double wz = waypoint.z + 0.5;
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, w, h, wx, wy, wz, point)) continue;
			int x = (int) Math.round(point[0]);
			int y = (int) Math.round(point[1]);
			if (x < -40 || x > w + 40 || y < -40 || y > h + 40) continue;
			int color = 0xFF000000 | waypoint.color;

			if (modules.waypointBeam.get()) drawBeam(g, camX, camY, camZ, yaw, pitch, fov, w, h, wx, wy, wz, color);

			// Markierung (kleine Raute) + Name
			g.fill(x - 2, y - 2, x + 3, y + 3, color);
			g.outline(x - 3, y - 3, 7, 7, 0xC0000000);
			String label = label(waypoint, distance, font);
			int tw = labelWidth;
			int tx = x - tw / 2;
			int ty = y - 14;
			g.fill(tx - 2, ty - 2, tx + tw + 2, ty + 9, Brand.HUD_BG);
			g.text(font, label, tx, ty, color, false);
		}
	}

	/** Beschriftung je Wegpunkt: nur neu gebaut, wenn sich Name, angezeigte Entfernung oder Sprache ändern. */
	private final java.util.IdentityHashMap<Waypoint, Object[]> labels = new java.util.IdentityHashMap<>();
	private int labelWidth;

	private String label(Waypoint waypoint, double distance, Font font) {
		boolean withDistance = modules.waypointDistance.get();
		// Angezeigt wird „123 m“ bzw. „1,2 km“ – der Schlüssel ändert sich genau dann, wenn sich der Text ändert.
		long key = !withDistance ? -1 : (distance >= 1000 ? 1_000_000L + Math.round(distance / 100.0) : Math.round(distance));
		int gen = dev.theredstonee.trsclient.core.i18n.I18n.generation();
		Object[] cached = labels.get(waypoint);
		if (cached != null && (Long) cached[0] == key && cached[1] == waypoint.name && (Integer) cached[4] == gen) {
			labelWidth = (Integer) cached[3];
			return (String) cached[2];
		}
		String text = withDistance ? waypoint.name + "  " + Projection.distanceLabel(distance) : waypoint.name;
		labelWidth = font.width(text);
		if (labels.size() > 256) labels.clear();
		labels.put(waypoint, new Object[]{key, waypoint.name, text, labelWidth, gen});
		return text;
	}

	/** Lichtsäule als Kette kleiner Rechtecke (in der Projektion sind senkrechte Linien schräg). */
	private void drawBeam(Gfx g, double camX, double camY, double camZ, float yaw, float pitch, double fov,
			int w, int h, double wx, double wy, double wz, int color) {
		int lastX = Integer.MIN_VALUE;
		int lastY = 0;
		int beamColor = (color & 0xFFFFFF) | 0x90000000;
		for (int step = 0; step <= BEAM_STEPS; step++) {
			double y = wy + (double) BEAM_HEIGHT * step / BEAM_STEPS;
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, w, h, wx, y, wz, top)) break;
			int px = (int) Math.round(top[0]);
			int py = (int) Math.round(top[1]);
			if (lastX != Integer.MIN_VALUE) {
				line(g, lastX, lastY, px, py, beamColor);
			}
			lastX = px;
			lastY = py;
		}
	}

	/** Dünne Linie aus Rechtecken (zwei Punkte, immer 2 Pixel breit). */
	private static void line(Gfx g, int x1, int y1, int x2, int y2, int color) {
		int dx = Math.abs(x2 - x1);
		int dy = Math.abs(y2 - y1);
		int steps = Math.max(dx, dy);
		if (steps <= 0) {
			g.fill(x1, y1, x1 + 2, y1 + 1, color);
			return;
		}
		if (steps > 400) return; // sehr nah dran – nicht zeichnen statt tausende Rechtecke
		for (int i = 0; i <= steps; i++) {
			int x = x1 + (x2 - x1) * i / steps;
			int y = y1 + (y2 - y1) * i / steps;
			g.fill(x, y, x + 2, y + 1, color);
		}
	}
}
