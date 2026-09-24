package dev.theredstonee.trsclient.core.redstone;

import dev.theredstonee.trsclient.core.render.Projection;
import dev.theredstonee.trsclient.core.ui.Canvas;

import java.util.List;

/**
 * Zeichnet die Signalstärke über jedem Staub aus dem {@link SignalCache} – wie die Wegpunkte mit
 * der eigenen {@link Projection} ins 2D-HUD, damit es in allen Minecraft-Versionen gleich
 * funktioniert (keine versionsabhängige Welt-Renderei). Nah = volle Größe, weiter weg kleiner.
 */
public final class SignalOverlay {
	/** Ab dieser Tiefe (Blöcke) werden die Zahlen kleiner. */
	private static final double FULL_SIZE_DEPTH = 7.0;
	private static final float MIN_SCALE = 0.55f;

	private final double[] point = new double[3];

	/**
	 * @param showZero auch Staub ohne Signal ("0") beschriften
	 * @return Zahl der gezeichneten Zahlen
	 */
	public int draw(Canvas c, List<SignalCache.Entry> entries, double camX, double camY, double camZ,
			float yaw, float pitch, double fov, int width, int height, boolean showZero) {
		int drawn = 0;
		for (int i = 0, n = entries.size(); i < n; i++) {
			SignalCache.Entry e = entries.get(i);
			if (!e.visible) continue;
			if (e.power <= 0 && !showZero) continue;
			// knapp über dem Staub (der liegt flach auf dem Boden)
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height,
					e.x + 0.5, e.y + 0.3, e.z + 0.5, point)) continue;
			int x = (int) Math.round(point[0]);
			int y = (int) Math.round(point[1]);
			if (x < -16 || y < -16 || x > width + 16 || y > height + 16) continue;
			float scale = point[2] <= FULL_SIZE_DEPTH ? 1f : Math.max(MIN_SCALE, (float) (FULL_SIZE_DEPTH / point[2]));
			String s = SignalColors.digits(e.power);
			int tw = c.textWidth(s);
			c.push();
			c.translate(x, y);
			if (scale < 0.999f) c.scale(scale);
			c.text(s, -tw / 2, -4, SignalColors.color(e.power), true);
			c.pop();
			drawn++;
		}
		return drawn;
	}
}
