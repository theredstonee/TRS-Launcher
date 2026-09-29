package dev.theredstonee.trsclient.core.ui.wardrobe;

import dev.theredstonee.trsclient.core.cosmetic.CosmeticMesh;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Images;
import dev.theredstonee.trsclient.core.skin.SkinModel;
import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.wardrobe.CosmeticCatalog;

/**
 * Kopf-Kosmetik auf der Garderoben-Figur ({@link SkinModel}, orthografisch): jede Fläche des Modells wird – wie die
 * Figur selbst – ein Textur-Parallelogramm über {@link Affine}, von hinten nach vorn sortiert und um den Kopf herum
 * gezeichnet ({@link SkinModel.HeadAttachment}). Gleiche Mathematik und Animation wie im Spiel und in der Studio-Werkbank
 * ({@link CosmeticV2Renderer}); Menüs können nicht additiv mischen, deshalb wird die Leucht-Schicht ins Bild
 * eingerechnet ({@code rgb·licht + leuchten}, Bild je Leucht-Frame) und die Höfe als weiche, normal gemischte Scheiben
 * gezeichnet. Nacht: nicht leuchtende Flächen auf 20 % (wie die Werkbank), {@code emissive} bleibt hell.
 *
 * <p>Spiegelungen (Rückseiten, gespiegelte UV) zeichnet der Canvas nicht überall – dafür gibt es eine waagerecht
 * gespiegelte Kopie der Textur. Nur Render-Thread.
 */
final class CosmeticPreview implements SkinModel.HeadAttachment {
	/** Licht nicht leuchtender Flächen bei Nacht (wie {@code applyLight} der Werkbank). */
	static final float NIGHT_LIGHT = 0.2f;
	private static final int MAX_QUADS = 512;

	private final WardrobeTextures textures;
	private final CosmeticV2Renderer renderer = new CosmeticV2Renderer();
	private final CosmeticMesh v1Mesh = new CosmeticMesh();

	private String id;
	private CosmeticCatalog.Preview preview;
	private boolean night;
	private long now;

	// Texturen (Arrays bleiben, Inhalt ändert sich je Leucht-Frame)
	private int[] lit;
	private int[] litMirror;
	private int[] emis;
	private int[] emisMirror;
	private int texW;
	private int texH;
	private int stamp = -1;
	private TextureRef tLit, tLitM, tEmis, tEmisM, tHalo;
	private int[] haloPixels;

	// Gesammelte Vierecke dieses Bildes
	private final float[] sx = new float[MAX_QUADS * 4];
	private final float[] sy = new float[MAX_QUADS * 4];
	private final float[] tu = new float[MAX_QUADS * 4];
	private final float[] tv = new float[MAX_QUADS * 4];
	private final float[] depth = new float[MAX_QUADS];
	private final byte[] emissive = new byte[MAX_QUADS];
	private final Integer[] order = new Integer[MAX_QUADS];
	private int quads;
	// Höfe
	private final float[] hx = new float[CosmeticV2.MAX_HALOS];
	private final float[] hy = new float[CosmeticV2.MAX_HALOS];
	private final float[] hd = new float[CosmeticV2.MAX_HALOS];
	private final float[] hs = new float[CosmeticV2.MAX_HALOS];
	private final int[] hc = new int[CosmeticV2.MAX_HALOS];
	private int halos;
	private final float[] tmp = new float[3];

	CosmeticPreview(WardrobeTextures textures) {
		this.textures = textures;
	}

	/** Was gezeigt wird (null = nichts). */
	void set(String id, CosmeticCatalog.Preview preview, boolean night) {
		if (id == null || preview == null) {
			this.id = null;
			this.preview = null;
			return;
		}
		if (!id.equals(this.id) || preview != this.preview) {
			this.id = id;
			this.preview = preview;
			stamp = -1;
			lit = litMirror = emis = emisMirror = null;
			tLit = tLitM = tEmis = tEmisM = null;
		}
		if (night != this.night) stamp = -1;
		this.night = night;
	}

	boolean active() {
		return preview != null;
	}

	/** Vor dem Zeichnen der Figur: Texturen für dieses Bild vorbereiten. false = noch nicht bereit. */
	boolean prepare(long now) {
		this.now = now;
		if (preview == null) return false;
		int frame;
		int[] base;
		int[] glow = null;
		if (preview.v2 != null) {
			CosmeticV2 m = preview.v2.model;
			texW = m.pixelWidth();
			texH = m.pixelHeight();
			int bf = CosmeticV2Renderer.frameAt(now, preview.v2.base.length, m.frameTimeMs);
			base = preview.v2.base[bf];
			int gf = 0;
			if (preview.v2.glow != null) {
				gf = CosmeticV2Renderer.frameAt(now, preview.v2.glow.length, m.glowFrameTimeMs);
				glow = preview.v2.glow[gf];
			}
			frame = bf * 64 + gf;
		} else {
			texW = preview.v1Width;
			texH = preview.v1Height;
			base = preview.v1Pixels;
			frame = 0;
		}
		int want = frame * 2 + (night ? 1 : 0);
		String key = "wardrobe/cos_" + id.replaceAll("[^a-z0-9_]", "");
		if (want != stamp || lit == null) {
			lit = V2Images.composite(base, glow, night ? NIGHT_LIGHT : 1f, lit);
			litMirror = V2Images.mirror(lit, texW, texH, litMirror);
			if (night) {
				emis = V2Images.composite(base, glow, 1f, emis);
				emisMirror = V2Images.mirror(emis, texW, texH, emisMirror);
			}
			stamp = want;
		}
		tLit = textures.get(key + "_l", lit, texW, texH, stamp);
		tLitM = textures.get(key + "_lm", litMirror, texW, texH, stamp);
		if (night) {
			tEmis = textures.get(key + "_e", emis, texW, texH, stamp);
			tEmisM = textures.get(key + "_em", emisMirror, texW, texH, stamp);
		} else {
			tEmis = tLit;
			tEmisM = tLitM;
		}
		if (preview.v2 != null && !preview.v2.model.halos.isEmpty()) {
			if (haloPixels == null) haloPixels = V2Images.haloAlpha();
			tHalo = textures.get("wardrobe/cos_halo", haloPixels, V2Images.HALO_SIZE, V2Images.HALO_SIZE, 0);
		}
		return tLit != null && tLitM != null && tEmis != null && tEmisM != null;
	}

	/** Höchster Punkt des Teils über dem Nacken (Skin-Pixel) – damit die Vorschau es ganz zeigt. */
	float top() {
		if (preview == null) return 8.5f;
		float top = 8.5f;
		if (preview.v2 != null) {
			for (CosmeticV2.Cube c : preview.v2.model.cubes) top = Math.max(top, c.y1 + c.inflate);
			for (CosmeticV2.Halo h : preview.v2.model.halos) top = Math.max(top, h.y + h.size / 2f);
		} else {
			for (dev.theredstonee.trsclient.core.cosmetic.CosmeticModel.Cube c : preview.v1.cubes) top = Math.max(top, c.y1 + 1f);
		}
		return top;
	}

	@Override
	public void draw(Canvas c, SkinModel.HeadView view, boolean front) {
		if (preview == null || tLit == null) return;
		if (!front) collect(view);
		for (int k = 0; k < quads; k++) {
			int q = order[k];
			if ((depth[q] >= view.depth) == front) quad(c, q);
		}
		for (int i = 0; i < halos; i++) {
			if ((hd[i] >= view.depth) == front) halo(c, i);
		}
	}

	// --- Sammeln ---

	private void collect(final SkinModel.HeadView view) {
		quads = 0;
		halos = 0;
		if (preview.v2 != null) {
			final CosmeticV2 m = preview.v2.model;
			final float scale = m.scale;
			renderer.pose(m, now, true);
			renderer.faces(m, -1, (p, uv, material, nx, ny, nz) -> {
				if (quads >= MAX_QUADS) return;
				int q = quads++;
				float d = 0f;
				for (int k = 0; k < 4; k++) {
					view.project(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], tmp, 0);
					sx[q * 4 + k] = tmp[0];
					sy[q * 4 + k] = tmp[1];
					d += tmp[2];
					tu[q * 4 + k] = uv[k * 2] * scale;
					tv[q * 4 + k] = uv[k * 2 + 1] * scale;
				}
				depth[q] = d / 4f;
				emissive[q] = (byte) (material == CosmeticV2.EMISSIVE ? 1 : 0);
			});
			double dx = view.towardViewer(0), dy = view.towardViewer(1), dz = view.towardViewer(2);
			renderer.halos(m, now, dx, dy, dz, true, (cx, cy, cz, half, rgb, intensity) -> {
				if (halos >= hx.length) return;
				view.project(cx, cy, cz, tmp, 0);
				int i = halos++;
				hx[i] = tmp[0];
				hy[i] = tmp[1];
				hd[i] = tmp[2];
				hs[i] = (float) (half * view.scale);
				int a = Math.round(Math.min(1f, intensity) * 255f);
				hc[i] = (a << 24) | (rgb & 0xFFFFFF);
			});
		} else {
			final float w = texW;
			final float h = texH;
			final int[] k = { 0 };
			v1Mesh.emit(preview.v1, null, now, false, (x, y, z, u, v, nx, ny, nz) -> {
				if (quads >= MAX_QUADS) return;
				int q = quads;
				int i = k[0]++;
				// ModelPart-Raum (Blöcke, y unten, vorne −z) → Kopf-Raum (Pixel)
				view.project(x * 16.0, -y * 16.0, -z * 16.0, tmp, 0);
				sx[q * 4 + i] = tmp[0];
				sy[q * 4 + i] = tmp[1];
				tu[q * 4 + i] = u * w;
				tv[q * 4 + i] = v * h;
				depth[q] += tmp[2];
				if (i == 0) depth[q] = tmp[2];
				if (i == 3) {
					depth[q] /= 4f;
					emissive[q] = 0;
					quads++;
					k[0] = 0;
				}
			});
		}
		for (int i = 0; i < quads; i++) order[i] = i;
		java.util.Arrays.sort(order, 0, quads, (a, b) -> Float.compare(depth[a], depth[b]));
	}

	// --- Zeichnen ---

	/** Ein Viereck als Textur-Parallelogramm (Abbildung aus drei Ecken und ihren UV). */
	private void quad(Canvas c, int q) {
		int o = q * 4;
		float e1x = sx[o + 1] - sx[o], e1y = sy[o + 1] - sy[o];
		float e3x = sx[o + 3] - sx[o], e3y = sy[o + 3] - sy[o];
		float d1u = tu[o + 1] - tu[o], d1v = tv[o + 1] - tv[o];
		float d3u = tu[o + 3] - tu[o], d3v = tv[o + 3] - tv[o];
		float det = d1u * d3v - d1v * d3u;
		if (Math.abs(det) < 1e-6f) return;
		// A · [d1 d3] = [e1 e3]  →  A = [e1 e3] · [d1 d3]⁻¹  (Bildschirm je Texel)
		float a00 = (e1x * d3v - e3x * d1v) / det;
		float a01 = (-e1x * d3u + e3x * d1u) / det;
		float a10 = (e1y * d3v - e3y * d1v) / det;
		float a11 = (-e1y * d3u + e3y * d1u) / det;
		float umin = Math.min(Math.min(tu[o], tu[o + 1]), Math.min(tu[o + 2], tu[o + 3]));
		float umax = Math.max(Math.max(tu[o], tu[o + 1]), Math.max(tu[o + 2], tu[o + 3]));
		float vmin = Math.min(Math.min(tv[o], tv[o + 1]), Math.min(tv[o + 2], tv[o + 3]));
		float vmax = Math.max(Math.max(tv[o], tv[o + 1]), Math.max(tv[o + 2], tv[o + 3]));
		int w = Math.round(umax - umin);
		int h = Math.round(vmax - vmin);
		if (w <= 0 || h <= 0) return;
		float screenDet = a00 * a11 - a01 * a10;
		if (Math.abs(screenDet) < 1e-5f) return; // hochkant
		boolean em = emissive[q] != 0;
		TextureRef tex;
		float ou;
		float originU;
		if (screenDet > 0) {
			tex = em ? tEmis : tLit;
			ou = umin;
			originU = umin;
		} else {
			// gespiegelt: aus der gespiegelten Kopie (u' = W − u), Abbildung mit umgedrehter u-Achse
			tex = em ? tEmisM : tLitM;
			ou = texW - umax;
			originU = umax;
			a00 = -a00;
			a10 = -a10;
		}
		float du = originU - tu[o];
		float dv = vmin - tv[o];
		// Ursprung: Bildschirmpunkt der Texel-Ecke (originU, vmin); mit der (ggf. gespiegelten) u-Achse
		float ex = sx[o] + (screenDet > 0 ? a00 : -a00) * du + a01 * dv;
		float ey = sy[o] + (screenDet > 0 ? a10 : -a10) * du + a11 * dv;
		c.push();
		if (Affine.apply(c, a00, a10, a01, a11, ex, ey)) c.image(tex, ou, vmin, w, h, 0xFFFFFFFF);
		c.pop();
	}

	private void halo(Canvas c, int i) {
		if (tHalo == null || hs[i] < 0.5f) return;
		float size = hs[i] * 2f;
		Affine.image(c, tHalo, hx[i] - hs[i], hy[i] - hs[i], size, size, 0, 0, V2Images.HALO_SIZE, V2Images.HALO_SIZE, hc[i]);
	}
}
