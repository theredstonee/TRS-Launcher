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

	/** Höchstens so viele Zahlen je Bild – bei sehr viel Staub nur die nächsten. */
	static final int MAX_LABELS = 400;

	private final double[] point = new double[3];
	private final int[] bins = new int[34];

	/**
	 * @param showZero auch Staub ohne Signal ("0") beschriften
	 * @return Zahl der gezeichneten Zahlen
	 */
	public int draw(Canvas c, List<SignalCache.Entry> entries, double camX, double camY, double camZ,
			float yaw, float pitch, double fov, int width, int height, boolean showZero) {
		int drawn = 0;
		int n = entries.size();
		// Zu viel Staub: Entfernungs-Grenze (ganze Blöcke) so wählen, dass höchstens MAX_LABELS übrig bleiben.
		int limit = Integer.MAX_VALUE;
		if (n > MAX_LABELS) {
			java.util.Arrays.fill(bins, 0);
			int candidates = 0;
			for (int i = 0; i < n; i++) {
				SignalCache.Entry e = entries.get(i);
				if (!e.visible || (e.power <= 0 && !showZero)) continue;
				bins[distance(e, camX, camY, camZ)]++;
				candidates++;
			}
			if (candidates > MAX_LABELS) {
				int sum = 0;
				limit = -1;
				while (limit + 1 < bins.length && sum + bins[limit + 1] <= MAX_LABELS) sum += bins[++limit];
			}
		}
		for (int i = 0; i < n; i++) {
			SignalCache.Entry e = entries.get(i);
			if (!e.visible) continue;
			if (e.power <= 0 && !showZero) continue;
			if (limit != Integer.MAX_VALUE && distance(e, camX, camY, camZ) > limit) continue;
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

	/** Entfernung in ganzen Blöcken (0–33) für die Auswahl der nächsten Zahlen. */
	private static int distance(SignalCache.Entry e, double camX, double camY, double camZ) {
		double dx = e.x + 0.5 - camX, dy = e.y + 0.3 - camY, dz = e.z + 0.5 - camZ;
		return (int) Math.min(33, Math.sqrt(dx * dx + dy * dy + dz * dz));
	}
}
