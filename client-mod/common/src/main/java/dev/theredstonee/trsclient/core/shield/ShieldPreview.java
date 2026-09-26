package dev.theredstonee.trsclient.core.shield;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Live-Vorschau auf der Einstellungsseite: zwei kleine Bildschirme („Normal“ und „Blocken“) mit Fadenkreuz, dem
 * Umriss des Schilds in der Nebenhand (links) so, wie Minecraft ihn in der 1. Person zeichnet (Sichtfeld 70°), dazu
 * blass die Vanilla-Haltung zum Vergleich und wie viel des Bildes das Schild verdeckt. Reine Mathematik – läuft in
 * jeder Version gleich und folgt jedem Regler sofort.
 */
public final class ShieldPreview implements ModulePanel {
	/** Sichtfeld der Hand in der 1. Person (Minecraft nimmt dafür immer 70°, unabhängig vom eingestellten FOV). */
	static final double HAND_FOV = 70;
	private static final double TAN = Math.tan(Math.toRadians(HAND_FOV / 2));

	private final ShieldPosition position;

	public ShieldPreview(ShieldPosition position) {
		this.position = position;
	}

	@Override
	public boolean pinned() {
		return true;
	}

	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mx, int my, Runnable click) {
		position.sync();
		Theme t = Theme.get();
		int gap = 6;
		int bw = Math.max(40, (w - gap) / 2);
		int bh = Math.max(34, Math.min(96, bw * 9 / 16));
		double aspect = bw / (double) bh;
		ShieldPose normal = position.normalPose();
		ShieldPose blocking = position.blockingPose();
		boolean transparent = position.transparentWanted();
		for (int i = 0; i < 2; i++) {
			boolean block = i == 1;
			int bx = x + i * (bw + gap);
			Redstone.well(c, bx, y, bw, bh, t.border);
			c.fill(bx + 1, y + 1, bx + bw - 1, y + bh - 1, ColorMath.withAlpha(t.deep, 235));
			double[][] ghost = silhouette(block ? ShieldPosition.vanillaDelta(true) : ShieldMath.identity(), true, aspect);
			double[][] shape = silhouette(ShieldPosition.offset(block ? blocking : normal, true, block), true, aspect);
			fillPolygon(c, ghost, bx + 1, y + 1, bw - 2, bh - 2, ColorMath.withAlpha(t.textDim, 70));
			int alpha = block && transparent ? Math.round(255 * position.opacityFraction()) : 230;
			fillPolygon(c, shape, bx + 1, y + 1, bw - 2, bh - 2, ColorMath.withAlpha(t.dustOn, Math.max(40, alpha)));
			// Fadenkreuz
			int cx = bx + bw / 2, cy = y + bh / 2;
			c.fill(cx - 3, cy, cx + 4, cy + 1, t.text);
			c.fill(cx, cy - 3, cx + 1, cy + 4, t.text);
			String label = I18n.tr(block ? "shield.preview.blocking" : "shield.preview.normal");
			String covered = I18n.tr("shield.preview.covered", String.format(Locale.ROOT, "%.0f", coverage(shape) * 100));
			Paint.textClipped(c, label, bx + 3, y + 3, bw - 6, t.text, true);
			Paint.textRight(c, covered, bx + bw - 3, y + bh - 11, t.textDim, true);
		}
		return y + bh + 6;
	}

	// --- Mathematik (paketweit sichtbar für Tests) ---

	/**
	 * Umriss der Schildplatte auf dem Bildschirm (Normalkoordinaten −1..1, y nach oben) als konvexe Hülle.
	 *
	 * @param offset Versatz im Arm-Raum (siehe {@link ShieldPosition#offset}); gezeichnet wird immer das normale Modell
	 */
	static double[][] silhouette(double[] offset, boolean leftArm, double aspect) {
		double side = leftArm ? -1 : 1;
		double[] m = ShieldMath.mul(ShieldMath.translation(side * 0.56, -0.52, -0.72), offset,
				ShieldPosition.vanillaDisplay(leftArm, false), ShieldMath.translation(-0.5, -0.5, -0.5), ShieldMath.scale(1, -1, -1));
		List<double[]> pts = new ArrayList<double[]>();
		for (int ix = 0; ix < 2; ix++) {
			for (int iy = 0; iy < 2; iy++) {
				for (int iz = 0; iz < 2; iz++) {
					double[] p = ShieldMath.apply(m, (ix == 0 ? -6 : 6) / 16.0, (iy == 0 ? -11 : 11) / 16.0, (iz == 0 ? -2 : -1) / 16.0);
					double depth = Math.max(0.02, -p[2]);
					pts.add(new double[]{p[0] / depth / TAN / aspect, p[1] / depth / TAN});
				}
			}
		}
		return hull(pts);
	}

	/** Anteil des Bildschirms (0..1), den der Umriss verdeckt. */
	static double coverage(double[][] polygon) {
		double[][] clipped = clip(polygon);
		return Math.min(1, Math.abs(area(clipped)) / 4.0);
	}

	/** Konvexe Hülle (Andrew), gegen den Uhrzeigersinn. */
	static double[][] hull(List<double[]> input) {
		double[][] p = input.toArray(new double[0][]);
		Arrays.sort(p, new Comparator<double[]>() {
			@Override
			public int compare(double[] a, double[] b) {
				return a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]);
			}
		});
		if (p.length < 3) return p;
		double[][] h = new double[p.length * 2][];
		int k = 0;
		for (double[] pt : p) {
			while (k >= 2 && cross(h[k - 2], h[k - 1], pt) <= 0) k--;
			h[k++] = pt;
		}
		for (int i = p.length - 2, low = k + 1; i >= 0; i--) {
			while (k >= low && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
			h[k++] = p[i];
		}
		return Arrays.copyOf(h, Math.max(0, k - 1));
	}

	private static double cross(double[] o, double[] a, double[] b) {
		return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
	}

	static double area(double[][] poly) {
		double s = 0;
		for (int i = 0; i < poly.length; i++) {
			double[] a = poly[i], b = poly[(i + 1) % poly.length];
			s += a[0] * b[1] - b[0] * a[1];
		}
		return s / 2;
	}

	/** Polygon auf das Bildquadrat −1..1 zuschneiden (Sutherland-Hodgman). */
	static double[][] clip(double[][] poly) {
		List<double[]> out = new ArrayList<double[]>(Arrays.asList(poly));
		for (int edge = 0; edge < 4 && !out.isEmpty(); edge++) {
			List<double[]> in = out;
			out = new ArrayList<double[]>();
			for (int i = 0; i < in.size(); i++) {
				double[] a = in.get(i), b = in.get((i + 1) % in.size());
				boolean ina = inside(a, edge), inb = inside(b, edge);
				if (ina && inb) {
					out.add(b);
				} else if (ina) {
					out.add(intersect(a, b, edge));
				} else if (inb) {
					out.add(intersect(a, b, edge));
					out.add(b);
				}
			}
		}
		return out.toArray(new double[0][]);
	}

	private static boolean inside(double[] p, int edge) {
		switch (edge) {
			case 0: return p[0] >= -1;
			case 1: return p[0] <= 1;
			case 2: return p[1] >= -1;
			default: return p[1] <= 1;
		}
	}

	private static double[] intersect(double[] a, double[] b, int edge) {
		double bound = edge == 0 || edge == 2 ? -1 : 1;
		int axis = edge < 2 ? 0 : 1;
		double f = (bound - a[axis]) / (b[axis] - a[axis]);
		return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
	}

	/** Konvexes Polygon (Normalkoordinaten) zeilenweise in das Rechteck x, y, w, h füllen. */
	private static void fillPolygon(Canvas c, double[][] poly, int x, int y, int w, int h, int argb) {
		if (poly.length < 3) return;
		double[][] px = new double[poly.length][];
		for (int i = 0; i < poly.length; i++) {
			px[i] = new double[]{x + (poly[i][0] + 1) / 2 * w, y + (1 - poly[i][1]) / 2 * h};
		}
		for (int row = y; row < y + h; row++) {
			double yc = row + 0.5, minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
			for (int i = 0; i < px.length; i++) {
				double[] a = px[i], b = px[(i + 1) % px.length];
				if ((a[1] <= yc && b[1] > yc) || (b[1] <= yc && a[1] > yc)) {
					double xi = a[0] + (yc - a[1]) / (b[1] - a[1]) * (b[0] - a[0]);
					minX = Math.min(minX, xi);
					maxX = Math.max(maxX, xi);
				}
			}
			if (maxX < minX) continue;
			int x1 = (int) Math.max(x, Math.round(minX)), x2 = (int) Math.min(x + w, Math.round(maxX));
			if (x2 > x1) c.fill(x1, row, x2, row + 1, argb);
		}
	}
}
