package dev.theredstonee.trsclient.core.circuit;

import dev.theredstonee.trsclient.core.render.Projection;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Zeichnet eine eingeblendete Vorlage als Geisterblöcke – wie das Signal-Overlay mit der eigenen
 * {@link Projection} ins 2D-HUD, damit es in allen Minecraft-Versionen gleich aussieht: halbtransparente Flächen,
 * Kanten, Richtungspfeile und (nah) Blocknamen. Farben: richtig grün, falsch rot, fehlt grau, beim Platzieren in
 * der Akzentfarbe umrandet. Ohne freie Transformation im Canvas nur Markierungen + Text.
 */
public final class GhostPainter {
	static final int GREEN = 0xFF3BD160;
	static final int RED = 0xFFFF4545;
	static final int GREY = 0xFFD8D8D8;
	static final int PLACING = 0xFF5AB4FF;
	/** Weiter weg keine Beschriftungen/Pfeile. */
	private static final double LABEL_DISTANCE = 10.0;
	private static final int MAX_LABELS = 24;

	private static final int[][] FACE_CORNERS = {
			// je Fläche 4 Ecken als Index in corners (Bit 0 = x, Bit 1 = y, Bit 2 = z)
			{0, 1, 5, 4}, // unten  (y = min)
			{2, 6, 7, 3}, // oben   (y = max)
			{0, 2, 3, 1}, // Norden (z = min)
			{4, 5, 7, 6}, // Süden  (z = max)
			{0, 4, 6, 2}, // Westen (x = min)
			{1, 3, 7, 5}, // Osten  (x = max)
	};

	private static final class Face {
		final float[] xy = new float[8];
		double depth;
		int fill;
		int edge;
		float edgeWidth;
	}

	private final double[] point = new double[3];
	private final List<Face> faces = new ArrayList<Face>();
	private final List<Face> pool = new ArrayList<Face>();
	private final float[] screen = new float[16];
	private final boolean[] ok = new boolean[8];
	private final double[] depth = new double[8];

	/**
	 * @param layer    nur diese Schicht (-1 = alle)
	 * @param placing  Vorlage folgt gerade dem Blick
	 * @param opacity  0–1 Deckkraft der Flächen
	 * @return Zahl der gezeichneten Blöcke
	 */
	public int draw(Canvas c, CircuitCheck check, int layer, boolean placing, float opacity, boolean labels,
			boolean hideCorrect, CircuitTexts texts, double camX, double camY, double camZ, float yaw, float pitch,
			double fov, int width, int height) {
		Circuit circuit = check.circuit();
		Placement placement = check.placement();
		boolean free = c.images();
		pool.addAll(faces);
		faces.clear();
		Set<Long> shown = new HashSet<Long>();
		for (int i = 0; i < check.size(); i++) {
			Circuit.Cell cell = circuit.cells.get(i);
			if (layer >= 0 && cell.y != layer) continue;
			if (BlockLook.fullCube(cell.spec)) {
				int[] w = check.world(i);
				shown.add(pack(w[0], w[1], w[2]));
			}
		}
		int drawn = 0;
		List<int[]> labelCells = new ArrayList<int[]>();
		for (int i = 0; i < check.size(); i++) {
			Circuit.Cell cell = circuit.cells.get(i);
			if (layer >= 0 && cell.y != layer) continue;
			byte status = check.status(i);
			int[] w = check.world(i);
			double dx = w[0] + 0.5 - camX, dy = w[1] + 0.5 - camY, dz = w[2] + 0.5 - camZ;
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (dist > 96) continue;
			int base = statusColor(status, placing);
			boolean correct = status == CircuitCheck.CORRECT;
			int fillAlpha = (int) (opacity * (correct ? (hideCorrect ? 0 : 60) : status == CircuitCheck.MISSING ? 110 : 130));
			if (status == CircuitCheck.OPTIONAL) fillAlpha = (int) (opacity * 35);
			int fill = ColorMath.withAlpha(mix(base, BlockLook.color(cell.spec.def), 0.35f), fillAlpha);
			int edge = ColorMath.withAlpha(base, correct ? 170 : 255);
			float edgeWidth = dist < 8 ? 1.5f : dist < 20 ? 1.1f : 0.8f;
			float[] box = BlockLook.box(cell.spec, placement);
			boolean full = BlockLook.fullCube(cell.spec);
			if (!free) {
				// Nur Markierung in der Mitte
				if (Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height, w[0] + 0.5, w[1] + (box[1] + box[4]) / 2,
						w[2] + 0.5, point)) {
					int x = (int) point[0], y = (int) point[1];
					c.fill(x - 2, y - 2, x + 2, y + 2, edge);
					drawn++;
				}
			} else if (projectBox(w, box, camX, camY, camZ, yaw, pitch, fov, width, height)) {
				for (int f = 0; f < 6; f++) {
					if (!facesCamera(f, w, box, camX, camY, camZ)) continue;
					if (full && neighbourCovers(f, w, shown)) continue;
					int[] idx = FACE_CORNERS[f];
					if (!ok[idx[0]] || !ok[idx[1]] || !ok[idx[2]] || !ok[idx[3]]) continue;
					Face face = pool.isEmpty() ? new Face() : pool.remove(pool.size() - 1);
					double d = 0;
					for (int k = 0; k < 4; k++) {
						face.xy[k * 2] = screen[idx[k] * 2];
						face.xy[k * 2 + 1] = screen[idx[k] * 2 + 1];
						d += depth[idx[k]];
					}
					face.depth = d / 4;
					face.fill = fill;
					face.edge = edge;
					face.edgeWidth = edgeWidth;
					faces.add(face);
				}
				drawn++;
			}
			if (labels && !placing && dist <= LABEL_DISTANCE && status != CircuitCheck.CORRECT && status != CircuitCheck.OPTIONAL) {
				labelCells.add(new int[] {i, (int) (dist * 100)});
			}
		}
		// von hinten nach vorn
		Collections.sort(faces, new Comparator<Face>() {
			@Override
			public int compare(Face a, Face b) {
				return Double.compare(b.depth, a.depth);
			}
		});
		for (Face f : faces) {
			if ((f.fill >>> 24) > 2) Quads.quad(c, f.xy[0], f.xy[1], f.xy[2], f.xy[3], f.xy[4], f.xy[5], f.xy[6], f.xy[7], f.fill);
			for (int k = 0; k < 4; k++) {
				int n = (k + 1) % 4;
				Quads.line(c, f.xy[k * 2], f.xy[k * 2 + 1], f.xy[n * 2], f.xy[n * 2 + 1], f.edgeWidth, f.edge);
			}
		}
		if (free) arrows(c, check, layer, camX, camY, camZ, yaw, pitch, fov, width, height);
		if (!labelCells.isEmpty()) labels(c, check, labelCells, texts, camX, camY, camZ, yaw, pitch, fov, width, height);
		if (placing && free) bounds(c, check, camX, camY, camZ, yaw, pitch, fov, width, height);
		return drawn;
	}

	static int statusColor(byte status, boolean placing) {
		switch (status) {
			case CircuitCheck.CORRECT:
				return GREEN;
			case CircuitCheck.WRONG:
				return RED;
			case CircuitCheck.MISSING:
				return placing ? PLACING : GREY;
			default:
				return placing ? PLACING : GREY;
		}
	}

	private static int mix(int a, int b, float t) {
		return ColorMath.lerp(a, b, t);
	}

	private static long pack(int x, int y, int z) {
		return dev.theredstonee.trsclient.core.redstone.Dir.pack(x, y, z);
	}

	private static boolean neighbourCovers(int face, int[] w, Set<Long> shown) {
		int x = w[0], y = w[1], z = w[2];
		switch (face) {
			case 0: y--; break;
			case 1: y++; break;
			case 2: z--; break;
			case 3: z++; break;
			case 4: x--; break;
			default: x++; break;
		}
		return shown.contains(pack(x, y, z));
	}

	private static boolean facesCamera(int face, int[] w, float[] b, double camX, double camY, double camZ) {
		switch (face) {
			case 0: return camY < w[1] + b[1];
			case 1: return camY > w[1] + b[4];
			case 2: return camZ < w[2] + b[2];
			case 3: return camZ > w[2] + b[5];
			case 4: return camX < w[0] + b[0];
			default: return camX > w[0] + b[3];
		}
	}

	private boolean projectBox(int[] w, float[] b, double camX, double camY, double camZ, float yaw, float pitch,
			double fov, int width, int height) {
		boolean any = false;
		for (int i = 0; i < 8; i++) {
			double px = w[0] + ((i & 1) != 0 ? b[3] : b[0]);
			double py = w[1] + ((i & 2) != 0 ? b[4] : b[1]);
			double pz = w[2] + ((i & 4) != 0 ? b[5] : b[2]);
			ok[i] = Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height, px, py, pz, point);
			screen[i * 2] = (float) point[0];
			screen[i * 2 + 1] = (float) point[1];
			depth[i] = point[2];
			if (ok[i] && point[0] > -width && point[0] < width * 2 && point[1] > -height && point[1] < height * 2) any = true;
		}
		return any;
	}

	/** Pfeile (Signal-/Schieberichtung) auf den gerichteten Bauteilen. */
	private void arrows(Canvas c, CircuitCheck check, int layer, double camX, double camY, double camZ, float yaw, float pitch,
			double fov, int width, int height) {
		Circuit circuit = check.circuit();
		Placement placement = check.placement();
		double[] a = new double[3];
		double[] b = new double[3];
		for (int i = 0; i < check.size(); i++) {
			Circuit.Cell cell = circuit.cells.get(i);
			if (layer >= 0 && cell.y != layer) continue;
			String dir = BlockLook.arrow(cell.spec);
			if (dir == null) continue;
			int[] v = BlockLook.vector(placement.direction(dir));
			if (v == null || v[1] != 0) continue;
			int[] w = check.world(i);
			double dx = w[0] + 0.5 - camX, dy = w[1] + 0.5 - camY, dz = w[2] + 0.5 - camZ;
			if (dx * dx + dy * dy + dz * dz > LABEL_DISTANCE * LABEL_DISTANCE * 2.5) continue;
			float[] box = BlockLook.box(cell.spec, placement);
			double top = w[1] + box[4] + 0.01;
			double cx = w[0] + 0.5, cz = w[2] + 0.5;
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height, cx - v[0] * 0.3, top, cz - v[2] * 0.3, a)) continue;
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height, cx + v[0] * 0.3, top, cz + v[2] * 0.3, b)) continue;
			byte status = check.status(i);
			int col = status == CircuitCheck.WRONG && check.stateOnly(i) ? 0xFFFFE040 : 0xF0FFFFFF;
			float wdt = 1.5f;
			Quads.line(c, (float) a[0], (float) a[1], (float) b[0], (float) b[1], wdt, col);
			// Pfeilspitze
			double[] l = new double[3];
			double[] r = new double[3];
			double sx = -v[2] * 0.15, sz = v[0] * 0.15;
			double hx = cx + v[0] * 0.12, hz = cz + v[2] * 0.12;
			if (Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height, hx + sx, top, hz + sz, l)
					&& Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height, hx - sx, top, hz - sz, r)) {
				Quads.line(c, (float) l[0], (float) l[1], (float) b[0], (float) b[1], wdt, col);
				Quads.line(c, (float) r[0], (float) r[1], (float) b[0], (float) b[1], wdt, col);
			}
		}
	}

	/** Blocknamen neben fehlenden/falschen Blöcken (die nächsten zuerst). */
	private void labels(Canvas c, CircuitCheck check, List<int[]> cells, CircuitTexts texts, double camX, double camY,
			double camZ, float yaw, float pitch, double fov, int width, int height) {
		Collections.sort(cells, new Comparator<int[]>() {
			@Override
			public int compare(int[] a, int[] b) {
				return a[1] - b[1];
			}
		});
		Circuit circuit = check.circuit();
		int n = 0;
		List<int[]> taken = new ArrayList<int[]>();
		for (int[] e : cells) {
			if (n++ >= MAX_LABELS) break;
			int i = e[0];
			Circuit.Cell cell = circuit.cells.get(i);
			int[] w = check.world(i);
			float[] box = BlockLook.box(cell.spec, check.placement());
			if (!Projection.project(camX, camY, camZ, yaw, pitch, fov, width, height, w[0] + 0.5, w[1] + box[4] + 0.15, w[2] + 0.5,
					point)) continue;
			String text = texts.block(cell.spec.def);
			String detail = BlockLook.detail(cell.spec, texts);
			if (detail != null) text = text + " · " + detail;
			if (check.status(i) == CircuitCheck.WRONG && check.stateOnly(i)) text = text + " (!)";
			float scale = point[2] <= 4 ? 0.75f : Math.max(0.5f, (float) (3.0 / point[2]));
			int tw = c.textWidth(text);
			int x = (int) Math.round(point[0]);
			int y = (int) Math.round(point[1]);
			// Beschriftungen nicht übereinander (die nähere gewinnt)
			int hw = (int) ((tw / 2 + 2) * scale), top = (int) (y - 11 * scale), bottom = (int) (y - scale);
			boolean overlaps = false;
			for (int[] r : taken) {
				if (x - hw < r[2] && x + hw > r[0] && top < r[3] && bottom > r[1]) {
					overlaps = true;
					break;
				}
			}
			if (overlaps) continue;
			taken.add(new int[] {x - hw, top, x + hw, bottom});
			c.push();
			c.translate(x, y);
			c.scale(scale);
			c.fill(-tw / 2 - 2, -11, tw / 2 + 2, -1, 0x90000000);
			c.text(text, -tw / 2, -10, check.status(i) == CircuitCheck.WRONG ? 0xFFFF8080 : 0xFFFFFFFF, false);
			c.pop();
		}
	}

	/** Umriss der ganzen Vorlage beim Platzieren. */
	private void bounds(Canvas c, CircuitCheck check, double camX, double camY, double camZ, float yaw, float pitch,
			double fov, int width, int height) {
		Circuit circuit = check.circuit();
		Placement p = check.placement();
		int[] w = {p.x, p.y, p.z};
		float[] b = {0f, 0f, 0f, p.width(circuit), circuit.sizeY, p.depth(circuit)};
		if (!projectBox(w, b, camX, camY, camZ, yaw, pitch, fov, width, height)) return;
		int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		int col = ColorMath.withAlpha(PLACING, 200);
		for (int[] e : edges) {
			if (!ok[e[0]] || !ok[e[1]]) continue;
			Quads.line(c, screen[e[0] * 2], screen[e[0] * 2 + 1], screen[e[1] * 2], screen[e[1] * 2 + 1], 1f, col);
		}
	}
}
