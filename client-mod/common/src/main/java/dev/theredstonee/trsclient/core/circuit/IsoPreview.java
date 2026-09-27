package dev.theredstonee.trsclient.core.circuit;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Isometrische Vorschau einer Schaltung im Menü: drehbar (Ziehen oder Knöpfe), Schicht für Schicht (darüber
 * ausgeblendet, darunter blass). Rechtwinklige Projektion – jede Fläche ist ein exaktes Parallelogramm, gezeichnet
 * mit {@link Quads}. Flache Farben je Block + Pfeile für Richtung/Signalfluss.
 */
public final class IsoPreview {
	private static final float PITCH = (float) Math.toRadians(32);

	private float yaw = (float) Math.toRadians(35);
	private float targetYaw = yaw;
	private int layer = -1;

	private static final class Face {
		final float[] p = new float[8];
		float depth;
		int color;
	}

	private final List<Face> faces = new ArrayList<Face>();

	/** Um eine Vierteldrehung weiterdrehen (weich). */
	public void turn(int quarters) {
		targetYaw += quarters * (float) (Math.PI / 2);
	}

	/** Frei drehen (Ziehen mit der Maus), Bogenmaß. */
	public void drag(float radians) {
		yaw += radians;
		targetYaw = yaw;
	}

	public int layer() {
		return layer;
	}

	public void setLayer(int layer) {
		this.layer = layer;
	}

	/** Nächste/vorige Schicht, -1 = alle. */
	public void stepLayer(Circuit c, int delta) {
		int n = c.sizeY;
		int l = layer + delta;
		if (l < -1) l = n - 1;
		if (l >= n) l = -1;
		layer = l;
	}

	public void reset() {
		yaw = (float) Math.toRadians(35);
		targetYaw = yaw;
		layer = -1;
	}

	/** Zeichnet die Schaltung mittig in das Rechteck. */
	public void draw(Canvas c, Circuit circuit, boolean mirror, int x, int y, int w, int h, float dt) {
		yaw += (targetYaw - yaw) * Math.min(1f, dt * 12f);
		if (!c.images()) {
			drawFlat(c, circuit, x, y, w, h);
			return;
		}
		float cos = (float) Math.cos(yaw), sin = (float) Math.sin(yaw);
		float cp = (float) Math.cos(PITCH), sp = (float) Math.sin(PITCH);
		float hx = circuit.sizeX / 2f, hz = circuit.sizeZ / 2f, hy = circuit.sizeY / 2f;
		// Maßstab: Hülle der Schaltung ins Rechteck
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			float px = ((i & 1) != 0 ? circuit.sizeX : 0) - hx;
			float py = ((i & 2) != 0 ? circuit.sizeY : 0) - hy;
			float pz = ((i & 4) != 0 ? circuit.sizeZ : 0) - hz;
			float rx = px * cos - pz * sin;
			float rz = px * sin + pz * cos;
			float sx = rx;
			float sy = -(py * cp - rz * sp);
			minX = Math.min(minX, sx);
			maxX = Math.max(maxX, sx);
			minY = Math.min(minY, sy);
			maxY = Math.max(maxY, sy);
		}
		float scale = Math.min((w - 8) / Math.max(1f, maxX - minX), (h - 8) / Math.max(1f, maxY - minY));
		scale = Math.min(scale, 26f);
		float ox = x + w / 2f - (minX + maxX) / 2f * scale;
		float oy = y + h / 2f - (minY + maxY) / 2f * scale;

		Set<Long> full = new HashSet<Long>();
		for (Circuit.Cell cell : circuit.cells) {
			if (layer >= 0 && cell.y > layer) continue;
			if (BlockLook.fullCube(cell.spec)) full.add(pack(mx(circuit, cell.x, mirror), cell.y, cell.z));
		}
		faces.clear();
		for (Circuit.Cell cell : circuit.cells) {
			if (layer >= 0 && cell.y > layer) continue;
			int cx = mx(circuit, cell.x, mirror);
			float[] b = mirrorBox(BlockLook.box(cell.spec, null), mirror);
			boolean isFull = BlockLook.fullCube(cell.spec);
			int base = BlockLook.color(cell.spec.def);
			boolean faded = layer >= 0 && cell.y < layer;
			for (int f = 1; f < 6; f++) { // ohne Unterseite
				float nx = f == 4 ? -1 : f == 5 ? 1 : 0;
				float nz = f == 2 ? -1 : f == 3 ? 1 : 0;
				float ny = f == 1 ? 1 : 0;
				float toViewer = (nx * sin + nz * cos) * cp + ny * sp;
				if (toViewer <= 0.001f) continue;
				if (isFull && covered(full, cx, cell.y, cell.z, f)) continue;
				Face face = new Face();
				float[][] q = corners(f, b);
				float depth = 0;
				for (int k = 0; k < 4; k++) {
					float px = cx + q[k][0] - hx, py = cell.y + q[k][1] - hy, pz = cell.z + q[k][2] - hz;
					float rx = px * cos - pz * sin;
					float rz = px * sin + pz * cos;
					face.p[k * 2] = ox + rx * scale;
					face.p[k * 2 + 1] = oy - (py * cp - rz * sp) * scale;
					depth += rz * cp + py * sp;
				}
				face.depth = depth / 4;
				float light = f == 1 ? 1f : 0.62f + 0.25f * Math.max(0f, nx * 0.6f + nz * -0.8f) + 0.12f * Math.abs(nx);
				int col = ColorMath.scaleRgb(base, Math.min(1f, light));
				if (faded) col = ColorMath.withAlpha(ColorMath.lerp(col, 0xFF202020, 0.35f), 110);
				face.color = col;
				faces.add(face);
			}
		}
		Collections.sort(faces, new Comparator<Face>() {
			@Override
			public int compare(Face a, Face b) {
				return Float.compare(a.depth, b.depth);
			}
		});
		for (Face f : faces) Quads.quad(c, f.p[0], f.p[1], f.p[2], f.p[3], f.p[4], f.p[5], f.p[6], f.p[7], f.color);
		// Pfeile auf den Oberseiten
		for (Circuit.Cell cell : circuit.cells) {
			if (layer >= 0 && cell.y != layer && !(layer < 0)) continue;
			String dir = BlockLook.arrow(cell.spec);
			if (dir == null) continue;
			if (mirror) dir = mirrorDir(dir);
			int[] v = BlockLook.vector(dir);
			if (v == null || v[1] != 0) continue;
			int cx = mx(circuit, cell.x, mirror);
			float top = BlockLook.box(cell.spec, null)[4] + 0.02f;
			float[] a = screen(cx + 0.5f - v[0] * 0.3f - hx, cell.y + top - hy, cell.z + 0.5f - v[2] * 0.3f - hz, cos, sin, cp, sp, ox, oy, scale);
			float[] e = screen(cx + 0.5f + v[0] * 0.32f - hx, cell.y + top - hy, cell.z + 0.5f + v[2] * 0.32f - hz, cos, sin, cp, sp, ox, oy, scale);
			float[] l = screen(cx + 0.5f + v[0] * 0.08f - v[2] * 0.18f - hx, cell.y + top - hy, cell.z + 0.5f + v[2] * 0.08f + v[0] * 0.18f - hz, cos, sin, cp, sp, ox, oy, scale);
			float[] r = screen(cx + 0.5f + v[0] * 0.08f + v[2] * 0.18f - hx, cell.y + top - hy, cell.z + 0.5f + v[2] * 0.08f - v[0] * 0.18f - hz, cos, sin, cp, sp, ox, oy, scale);
			float wdt = Math.max(1f, scale / 12f);
			int col = 0xE0FFFFFF;
			Quads.line(c, a[0], a[1], e[0], e[1], wdt, col);
			Quads.line(c, l[0], l[1], e[0], e[1], wdt, col);
			Quads.line(c, r[0], r[1], e[0], e[1], wdt, col);
		}
	}

	private static float[] screen(float px, float py, float pz, float cos, float sin, float cp, float sp, float ox, float oy, float scale) {
		float rx = px * cos - pz * sin;
		float rz = px * sin + pz * cos;
		return new float[] {ox + rx * scale, oy - (py * cp - rz * sp) * scale};
	}

	/** Ohne freie Transformation (sehr alte Canvas): Draufsicht der gewählten bzw. obersten Schicht als Raster. */
	private void drawFlat(Canvas c, Circuit circuit, int x, int y, int w, int h) {
		int cell = Math.max(2, Math.min((w - 4) / circuit.sizeX, (h - 4) / circuit.sizeZ));
		int ox = x + (w - cell * circuit.sizeX) / 2;
		int oy = y + (h - cell * circuit.sizeZ) / 2;
		for (Circuit.Cell cl : circuit.cells) {
			if (layer >= 0 && cl.y != layer) continue;
			int col = BlockLook.color(cl.spec.def);
			c.fill(ox + cl.x * cell, oy + cl.z * cell, ox + (cl.x + 1) * cell - 1, oy + (cl.z + 1) * cell - 1,
					ColorMath.scaleRgb(col, 0.6f + 0.4f * (cl.y + 1f) / circuit.sizeY));
		}
	}

	private static int mx(Circuit c, int x, boolean mirror) {
		return mirror ? c.sizeX - 1 - x : x;
	}

	private static float[] mirrorBox(float[] b, boolean mirror) {
		if (!mirror) return b;
		return new float[] {1f - b[3], b[1], b[2], 1f - b[0], b[4], b[5]};
	}

	private static String mirrorDir(String d) {
		if ("east".equals(d)) return "west";
		if ("west".equals(d)) return "east";
		return d;
	}

	private static long pack(int x, int y, int z) {
		return dev.theredstonee.trsclient.core.redstone.Dir.pack(x, y, z);
	}

	private static boolean covered(Set<Long> full, int x, int y, int z, int face) {
		switch (face) {
			case 1: return full.contains(pack(x, y + 1, z));
			case 2: return full.contains(pack(x, y, z - 1));
			case 3: return full.contains(pack(x, y, z + 1));
			case 4: return full.contains(pack(x - 1, y, z));
			default: return full.contains(pack(x + 1, y, z));
		}
	}

	/** Ecken einer Fläche des Kastens (rundherum). */
	private static float[][] corners(int face, float[] b) {
		float x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
		switch (face) {
			case 1: return new float[][] {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
			case 2: return new float[][] {{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}};
			case 3: return new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
			case 4: return new float[][] {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
			default: return new float[][] {{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}};
		}
	}
}
