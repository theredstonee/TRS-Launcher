package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.util.HashMap;
import java.util.Map;

/**
 * Prozedural erzeugte, kantengeglättete Kartensymbole (Pfeil, Punkte, Raute, Grabstein, Freundes-Ring) und der
 * runde Redstone-Rahmen der Minimap. Erzeugt in Bildschirmauflösung (GUI-Größe × Skalierung) und verkleinert
 * gezeichnet – dadurch gestochen scharf, auch gedreht. Nur Render-Thread.
 */
public final class MapSprites {
	public static final int ARROW = 0;
	public static final int DOT = 1;
	public static final int DIAMOND = 2;
	public static final int GRAVE = 3;
	public static final int HALO = 4;
	public static final int BADGE = 5;

	private static final class Entry {
		final TextureRef ref;
		final int pixels;

		Entry(TextureRef ref, int pixels) {
			this.ref = ref;
			this.pixels = pixels;
		}
	}

	private final Map<String, Entry> cache = new HashMap<String, Entry>();
	/** Rahmen und Scheibe gibt es je nur einmal (eine Minimap); Schlüssel = Größe/Farben. */
	private Entry ring;
	private String ringKey;
	private Entry disc;
	private String discKey;

	/** Texturauflösung je GUI-Pixel (begrenzt, damit große Rahmen nicht riesig werden). */
	static int density(double scale, float sizeGui, int maxPixels) {
		double s = Math.max(1.0, Math.min(6.0, scale));
		int px = (int) Math.ceil(sizeGui * s);
		return Math.max(4, Math.min(maxPixels, px));
	}

	/**
	 * Zeichnet ein Symbol zentriert auf (cx, cy) mit Kantenlänge {@code size} (GUI-Pixel), gedreht um {@code rotation}
	 * (Bogenmaß) und eingefärbt mit {@code tint} (Füllung weiß → Farbe, Umriss bleibt dunkel).
	 */
	public boolean draw(Canvas c, int kind, float cx, float cy, float size, float rotation, int tint, double scale) {
		if (!c.images() || Textures.store() == null) return false;
		int px = density(scale, size, 128);
		Entry e = sprite(kind, px);
		if (e == null) return false;
		c.push();
		c.translate(cx, cy);
		if (rotation != 0f) c.rotate(rotation);
		float s = size / e.pixels;
		c.scale(s, s);
		c.translate(-e.pixels / 2f, -e.pixels / 2f);
		c.image(e.ref, 0, 0, e.pixels, e.pixels, tint);
		c.pop();
		return true;
	}

	private Entry sprite(int kind, int px) {
		String key = "s" + kind + "_" + px;
		Entry e = cache.get(key);
		if (e != null) return e;
		int[] argb = new int[px * px];
		for (int y = 0; y < px; y++) {
			for (int x = 0; x < px; x++) {
				argb[y * px + x] = spritePixel(kind, x, y, px);
			}
		}
		TextureRef ref = Textures.store().upload("map/sprite" + kind + "-" + px, px, px, argb);
		if (ref == null) return null;
		e = new Entry(ref, px);
		cache.put(key, e);
		return e;
	}

	/** 4×4-Überabtastung eines Symbol-Pixels. */
	static int spritePixel(int kind, int x, int y, int px) {
		float fill = 0f, edge = 0f, detail = 0f;
		for (int sy = 0; sy < 4; sy++) {
			for (int sx = 0; sx < 4; sx++) {
				float u = ((x + (sx + 0.5f) / 4f) / px) * 2f - 1f;
				float v = ((y + (sy + 0.5f) / 4f) / px) * 2f - 1f;
				int layer = shape(kind, u, v);
				if (layer == 1) edge += 1f / 16f;
				else if (layer == 2) fill += 1f / 16f;
				else if (layer == 3) detail += 1f / 16f;
			}
		}
		float a = fill + edge + detail;
		if (a <= 0f) return 0;
		int fillRgb = kind == GRAVE ? 0xC9C6BE : 0xFFFFFF;
		int edgeRgb = kind == HALO ? 0xFFFFFF : 0x121014;
		int detailRgb = 0x2A2628;
		float r = (((fillRgb >> 16) & 0xFF) * fill + ((edgeRgb >> 16) & 0xFF) * edge + ((detailRgb >> 16) & 0xFF) * detail) / a;
		float g = (((fillRgb >> 8) & 0xFF) * fill + ((edgeRgb >> 8) & 0xFF) * edge + ((detailRgb >> 8) & 0xFF) * detail) / a;
		float b = ((fillRgb & 0xFF) * fill + (edgeRgb & 0xFF) * edge + (detailRgb & 0xFF) * detail) / a;
		int alpha = Math.min(255, Math.round(a * 255));
		return (alpha << 24) | (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b);
	}

	/** 0 = außen, 1 = Umriss, 2 = Füllung, 3 = Innenzeichnung. u, v in [-1, 1]. */
	static int shape(int kind, float u, float v) {
		switch (kind) {
			case ARROW: {
				// Pfeil nach oben mit Kerbe; Umriss = etwas größerer Pfeil.
				if (inArrow(u, v, 0.62f)) return 2;
				if (inArrow(u, v, 0.95f)) return 1;
				return 0;
			}
			case DOT: {
				float d = u * u + v * v;
				if (d <= 0.55f * 0.55f) return 2;
				if (d <= 0.9f * 0.9f) return 1;
				return 0;
			}
			case DIAMOND: {
				float d = Math.abs(u) + Math.abs(v);
				if (d <= 0.6f) return 2;
				if (d <= 0.95f) return 1;
				return 0;
			}
			case GRAVE: {
				// Grabstein: oben rund, unten gerade, mit eingraviertem Kreuz.
				boolean outer = tomb(u, v, 0.95f);
				if (!outer) return 0;
				if (!tomb(u, v, 0.72f)) return 1;
				boolean cross = (Math.abs(u) <= 0.09f && v >= -0.45f && v <= 0.35f) || (Math.abs(v + 0.12f) <= 0.09f && Math.abs(u) <= 0.3f);
				return cross ? 3 : 2;
			}
			case HALO: {
				float d = (float) Math.sqrt(u * u + v * v);
				if (d >= 0.78f && d <= 0.98f) return 2;
				if (d >= 0.7f && d < 0.78f) return 1;
				return 0;
			}
			case BADGE: {
				float d = u * u + v * v;
				if (d <= 0.8f * 0.8f) return 2;
				if (d <= 0.97f * 0.97f) return 1;
				return 0;
			}
			default:
				return 0;
		}
	}

	private static boolean inArrow(float u, float v, float s) {
		// Dreieck (0,-1)…(±0.72,0.8) mit Kerbe bei (0,0.4), skaliert um s.
		float x = u / s, y = v / s;
		if (y < -1f || y > 0.8f) return false;
		float half = 0.72f * (y + 1f) / 1.8f;
		if (Math.abs(x) > half) return false;
		// Kerbe: unterhalb der Linie von (±0.72, 0.8) nach (0, 0.4) ist außen.
		float notch = 0.4f + (0.8f - 0.4f) * Math.abs(x) / 0.72f;
		return y <= notch;
	}

	private static boolean tomb(float u, float v, float s) {
		float x = u / s, y = v / s;
		if (y > 0.85f || Math.abs(x) > 0.62f) return false;
		if (y >= -0.25f) return true;
		float dy = y + 0.25f;
		return x * x + dy * dy <= 0.62f * 0.62f;
	}

	// --- Runder Rahmen ---

	/** Breite des Randes außerhalb des Kartenradius (GUI-Pixel). */
	public static final float RING_OUT = 3f;
	/**
	 * Deckender Rand innerhalb des Kartenradius (Staublinie + Stein) – verdeckt die Streifenkanten. Wächst mit der
	 * Größe, damit große Karten nicht zu viele Streifen brauchen (Anzahl ≈ Radius / Rand).
	 */
	public static float ringIn(float radius) {
		return Math.max(3f, radius / 18f);
	}

	/**
	 * Runder Redstone-Rahmen für eine Karte mit Radius {@code radius} (GUI-Pixel): Steinring mit Licht von links
	 * oben, rote Staublinie innen und ein schwaches Leuchten zur Karte hin. Textur deckt [−R−RING_OUT, R+RING_OUT]².
	 */
	public boolean drawRing(Canvas c, float cx, float cy, float radius, double scale, int alpha) {
		if (!c.images() || Textures.store() == null) return false;
		Theme t = Theme.get();
		float outer = radius + RING_OUT;
		int px = density(scale, outer * 2f, 1024);
		String key = "ring_" + px + "_" + Math.round(radius * 4) + "_" + t.dustOn + "_" + t.bevelLight;
		if (!key.equals(ringKey) || ring == null) {
			Entry made = makeRing(px, radius, outer, t);
			if (made == null) return false;
			ring = made;
			ringKey = key;
		}
		Entry e = ring;
		c.push();
		c.translate(cx - outer, cy - outer);
		float s = outer * 2f / e.pixels;
		c.scale(s, s);
		c.image(e.ref, 0, 0, e.pixels, e.pixels, (alpha << 24) | 0xFFFFFF);
		c.pop();
		return true;
	}

	/** Dunkle Kreisscheibe als Kartenhintergrund (unerkundet). */
	public boolean drawDisc(Canvas c, float cx, float cy, float radius, double scale, int argb) {
		if (!c.images() || Textures.store() == null) return false;
		int px = density(scale, radius * 2f, 1024);
		String key = "disc_" + px;
		if (!key.equals(discKey) || disc == null) {
			int[] img = new int[px * px];
			float r = px / 2f;
			for (int y = 0; y < px; y++) {
				for (int x = 0; x < px; x++) {
					float cov = 0f;
					for (int s = 0; s < 4; s++) {
						float dx = x + 0.25f + (s & 1) * 0.5f - r;
						float dy = y + 0.25f + (s >> 1) * 0.5f - r;
						if (dx * dx + dy * dy <= r * r) cov += 0.25f;
					}
					img[y * px + x] = cov <= 0f ? 0 : (Math.round(cov * 255) << 24) | 0xFFFFFF;
				}
			}
			TextureRef ref = Textures.store().upload("map/disc", px, px, img);
			if (ref == null) return false;
			disc = new Entry(ref, px);
			discKey = key;
		}
		Entry e = disc;
		c.push();
		c.translate(cx - radius, cy - radius);
		float s = radius * 2f / e.pixels;
		c.scale(s, s);
		c.image(e.ref, 0, 0, e.pixels, e.pixels, argb);
		c.pop();
		return true;
	}

	private Entry makeRing(int px, float radius, float outer, Theme t) {
		int[] img = new int[px * px];
		float k = px / (outer * 2f); // Texturpixel je GUI-Pixel
		float center = px / 2f;
		int stoneLight = ColorMath.lerp(0xFF6E6A70, t.bevelLight, 0.35f);
		int stoneDark = ColorMath.lerp(0xFF232126, t.bevelDark, 0.35f);
		int dust = t.dustOn;
		int dustDark = ColorMath.scaleRgb(t.dustOn, 0.55f);
		for (int y = 0; y < px; y++) {
			for (int x = 0; x < px; x++) {
				float a = 0f, r = 0f, g = 0f, b = 0f;
				for (int s = 0; s < 4; s++) {
					float dx = (x + 0.25f + (s & 1) * 0.5f - center) / k;
					float dy = (y + 0.25f + (s >> 1) * 0.5f - center) / k;
					float d = (float) Math.sqrt(dx * dx + dy * dy);
					int col = ringColor(d, dx, dy, radius, stoneLight, stoneDark, dust, dustDark);
					float ca = (col >>> 24) / 255f;
					a += ca * 0.25f;
					r += ((col >> 16) & 0xFF) * ca * 0.25f;
					g += ((col >> 8) & 0xFF) * ca * 0.25f;
					b += (col & 0xFF) * ca * 0.25f;
				}
				if (a <= 0.002f) continue;
				img[y * px + x] = (Math.round(a * 255) << 24) | (Math.round(r / a) << 16) | (Math.round(g / a) << 8) | Math.round(b / a);
			}
		}
		// Gleicher Name: gleiche Größe = in place ersetzt, sonst gibt der Speicher die alte Textur frei.
		TextureRef ref = Textures.store().upload("map/ring", px, px, img);
		return ref == null ? null : new Entry(ref, px);
	}

	/** Farbe des Rahmens im Abstand d vom Mittelpunkt (0 = durchsichtig). */
	static int ringColor(float d, float dx, float dy, float radius, int stoneLight, int stoneDark, int dust, int dustDark) {
		float inner = radius - ringIn(radius);
		if (d > radius + RING_OUT || d < inner - 2.5f) return 0;
		if (d < inner) {
			// Leuchten zur Karte hin.
			float f = 1f - (inner - d) / 2.5f;
			return ColorMath.withAlpha(dust, Math.round(f * f * 0x60));
		}
		if (d < inner + 1.6f) {
			// Staublinie mit hellerer Mitte.
			float m = 1f - Math.abs(d - (inner + 0.8f)) / 0.8f;
			return 0xFF000000 | (ColorMath.lerp(dustDark, dust, 0.55f + 0.45f * m) & 0xFFFFFF);
		}
		if (d > radius + RING_OUT - 0.9f) return 0xFF0B0A0D;
		if (d < inner + 2.2f) return 0xFF151317;
		// Stein: Licht von links oben.
		float nx = dx / Math.max(0.001f, d), ny = dy / Math.max(0.001f, d);
		float light = 0.5f - 0.5f * (nx * 0.7071f + ny * 0.7071f);
		return 0xFF000000 | (ColorMath.lerp(stoneDark, stoneLight, light) & 0xFFFFFF);
	}

	/** Alle Texturen freigeben (Weltwechsel/Größenwechsel unnötig – nur beim Beenden). */
	public void clear() {
		if (Textures.store() != null) {
			for (Entry e : cache.values()) Textures.store().release(e.ref);
			if (ring != null) Textures.store().release(ring.ref);
			if (disc != null) Textures.store().release(disc.ref);
		}
		cache.clear();
		ring = null;
		disc = null;
		ringKey = null;
		discKey = null;
	}
}
