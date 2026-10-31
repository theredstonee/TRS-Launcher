package dev.theredstonee.trsclient.core.waypoint.live;

import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.map.MinimapRenderer;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.render.Projection;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;

import java.util.List;

/**
 * Wegpunkte und Köpfe der Freunde im HUD (in der Welt, über die eigene Projektion – wie die eigenen Wegpunkte, aber
 * gedimmt, ohne Lichtsäule, mit goldenem Rand). Köpfe auch weit weg; steht der Freund als Spieler in Sichtweite, zeigt
 * ihn das Spiel selbst – dann nichts zeichnen. Für alle Versionen gleich ({@link Canvas}).
 */
public final class LiveHud {
	private final double[] point = new double[3];

	/**
	 * @param fov senkrechtes Sichtfeld (Grad), {@code w}/{@code h} GUI-Größe
	 */
	public void draw(Canvas c, TrsModules m, double camX, double camY, double camZ, float yaw, float pitch, double fov, int w, int h) {
		LiveShare live = LiveShare.get();
		MapEngine e = MapEngine.get();
		if (live == null || e == null || !live.active() || e.platform() == null) return;
		MapPlatform p = e.platform();
		String dim = WaypointShare.canonicalDimension(p.dimension());
		if (dim == null) return;
		double range = m.waypointRange.get();
		if (m.waypointFriendWaypoints.get()) {
			List<FriendWaypoint> list = live.friendWaypoints(dim);
			for (int i = 0, n = list.size(); i < n; i++) {
				FriendWaypoint fw = list.get(i);
				double distance = fw.distanceTo(camX, camY, camZ);
				if (range > 0 && distance > range) continue;
				if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, w, h, fw.x + 0.5, fw.y + 0.5, fw.z + 0.5, point)) continue;
				int x = (int) Math.round(point[0]), y = (int) Math.round(point[1]);
				if (x < -40 || x > w + 40 || y < -40 || y > h + 40) continue;
				int color = 0xC0000000 | fw.color;
				// Markierung: kleine Raute mit goldenem Rand (eigene: voll, mit Lichtsäule).
				c.fill(x - 3, y - 3, x + 4, y + 4, ColorMath.withAlpha(FriendFaces.FRIEND, 0xB0));
				c.fill(x - 2, y - 2, x + 3, y + 3, color);
				Owner o = live.owner(fw.owner);
				String label = fw.name + " · " + (o == null ? "?" : o.name());
				if (m.waypointDistance.get()) label = label + "  " + Projection.distanceLabel(distance);
				int tw = c.textWidth(label);
				int tx = x - tw / 2, ty = y - 14;
				c.fill(tx - 2, ty - 2, tx + tw + 2, ty + 9, 0x70000000);
				c.text(label, tx, ty, ColorMath.lerp(0xD0000000 | fw.color, 0xD0FFFFFF, 0.35f), false);
			}
		}
		if (m.waypointFriendPositions.get()) {
			long now = System.currentTimeMillis();
			for (Owner o : live.owners()) {
				FriendPosition fp = o.position();
				if (o.hidden() || fp == null || !fp.dimension.equals(dim)) continue;
				int alpha = MinimapRenderer.positionAlpha(fp, now);
				if (alpha == 0 || p.playerLoaded(Uuids.toUuid(o.uuid))) continue;
				if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, w, h, fp.x, fp.y + 2.2, fp.z, point)) continue;
				int x = (int) Math.round(point[0]), y = (int) Math.round(point[1]);
				if (x < -20 || x > w + 20 || y < -20 || y > h + 20) continue;
				FriendFaces.head(c, o.uuid, o.name(), x, y, 10f, alpha);
				double dx = fp.x - camX, dy = fp.y - camY, dz = fp.z - camZ;
				String label = o.name() + "  " + Projection.distanceLabel(Math.sqrt(dx * dx + dy * dy + dz * dz));
				int tw = c.textWidth(label);
				c.fill(x - tw / 2 - 2, y + 7, x + tw / 2 + 2, y + 17, ColorMath.withAlpha(0xFF000000, Math.min(0x80, alpha)));
				c.text(label, x - tw / 2, y + 8, ColorMath.withAlpha(FriendFaces.FRIEND, alpha), false);
			}
		}
	}
}
