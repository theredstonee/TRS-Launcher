package dev.theredstonee.trsclient.core.screenshot.edit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.List;

/**
 * Rastert den Editor-Stand – für die Vorschau (verkleinert) und für das Speichern (volle Größe) mit demselben Code,
 * damit die Vorschau dem Ergebnis entspricht. Formen über Java2D (nur {@code BufferedImage}, funktioniert auch
 * „headless“), Drehen/Zuschneiden/Verpixeln direkt auf den Pixeln. Nie im Render-Thread aufrufen (große Bilder).
 */
public final class EditRender {
	private EditRender() {
	}

	/** Bild um {@code turns} Vierteldrehungen im Uhrzeigersinn drehen (neues Feld; Maße bei ungeraden Drehungen getauscht). */
	public static int[] rotate(int[] src, int w, int h, int turns) {
		int t = ((turns % 4) + 4) % 4;
		if (t == 0) return src.clone();
		int[] out = new int[w * h];
		if (t == 2) {
			for (int i = 0, n = w * h; i < n; i++) out[n - 1 - i] = src[i];
			return out;
		}
		// Ziel hat Breite h und Höhe w.
		for (int y = 0; y < h; y++) {
			int row = y * w;
			for (int x = 0; x < w; x++) {
				int nx, ny;
				if (t == 1) {
					nx = h - 1 - y;
					ny = x;
				} else {
					nx = y;
					ny = w - 1 - x;
				}
				out[ny * h + nx] = src[row + x];
			}
		}
		return out;
	}

	/** Ausschnitt kopieren. */
	public static int[] crop(int[] src, int w, int h, int x, int y, int cw, int ch) {
		int[] out = new int[cw * ch];
		for (int row = 0; row < ch; row++) System.arraycopy(src, (y + row) * w + x, out, row * cw, cw);
		return out;
	}

	/** Flächenmittel auf {@code tw}×{@code th} (deckend). */
	public static int[] downscale(int[] src, int sw, int sh, int tw, int th) {
		if (tw == sw && th == sh) {
			int[] out = src.clone();
			for (int i = 0; i < out.length; i++) out[i] |= 0xFF000000;
			return out;
		}
		int[] out = new int[tw * th];
		for (int y = 0; y < th; y++) {
			int y0 = (int) ((long) y * sh / th);
			int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / th));
			for (int x = 0; x < tw; x++) {
				int x0 = (int) ((long) x * sw / tw);
				int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / tw));
				long r = 0, g = 0, b = 0;
				int n = 0;
				int stepY = Math.max(1, (y1 - y0) / 4);
				int stepX = Math.max(1, (x1 - x0) / 4);
				for (int yy = y0; yy < y1; yy += stepY) {
					int row = yy * sw;
					for (int xx = x0; xx < x1; xx += stepX) {
						int p = src[row + xx];
						r += (p >> 16) & 0xFF;
						g += (p >> 8) & 0xFF;
						b += p & 0xFF;
						n++;
					}
				}
				out[y * tw + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
			}
		}
		return out;
	}

	/**
	 * Zeichnungen auf ein (gedrehtes) Bild rastern – verändert {@code pixels}. {@code scale} = Pufferpixel je Bildpixel
	 * (1 beim Speichern, &lt; 1 in der Vorschau).
	 */
	public static void draw(int[] pixels, int w, int h, List<Shape> shapes, float scale) {
		if (shapes.isEmpty()) return;
		BufferedImage img = wrap(pixels, w, h);
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			for (Shape s : shapes) {
				if (s instanceof Shape.Pixelate) {
					Shape.Pixelate p = (Shape.Pixelate) s;
					pixelate(pixels, w, h, Math.round(p.x1 * scale), Math.round(p.y1 * scale), Math.round(p.x2 * scale),
							Math.round(p.y2 * scale), Math.max(2, Math.round(p.size * scale)));
					continue;
				}
				Graphics2D sg = (Graphics2D) g.create();
				try {
					sg.scale(scale, scale);
					sg.setColor(new Color(s.color, false));
					shape(sg, s);
				} catch (RuntimeException e) {
					// eine kaputte Form (z. B. Schrift fehlt) verdirbt nicht das ganze Bild
				} finally {
					sg.dispose();
				}
			}
		} finally {
			g.dispose();
		}
	}

	/** Puffer als {@code BufferedImage} ohne Kopie (RGB, Alpha wird ignoriert). */
	static BufferedImage wrap(int[] pixels, int w, int h) {
		DataBufferInt db = new DataBufferInt(pixels, w * h);
		int[] masks = {0xFF0000, 0xFF00, 0xFF};
		WritableRaster raster = Raster.createPackedRaster(db, w, h, w, masks, null);
		return new BufferedImage(new DirectColorModel(24, masks[0], masks[1], masks[2]), raster, false, null);
	}

	private static void shape(Graphics2D g, Shape s) {
		float sw = s.size;
		switch (s.kind) {
			case ARROW: {
				Shape.Arrow a = (Shape.Arrow) s;
				double dx = a.x2 - a.x1, dy = a.y2 - a.y1;
				double len = Math.sqrt(dx * dx + dy * dy);
				if (len < 0.5) return;
				double ux = dx / len, uy = dy / len;
				double head = Math.min(len, Math.max(sw * 4.2, 10));
				double half = head * 0.55;
				double bx = a.x2 - ux * head, by = a.y2 - uy * head;
				g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				Path2D.Double line = new Path2D.Double();
				line.moveTo(a.x1, a.y1);
				line.lineTo(a.x2 - ux * head * 0.6, a.y2 - uy * head * 0.6);
				g.draw(line);
				Path2D.Double tip = new Path2D.Double();
				tip.moveTo(a.x2, a.y2);
				tip.lineTo(bx - uy * half, by + ux * half);
				tip.lineTo(bx + uy * half, by - ux * half);
				tip.closePath();
				g.fill(tip);
				g.setStroke(new BasicStroke(Math.max(1f, sw * 0.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(tip);
				return;
			}
			case RECT: {
				Shape.Rect r = (Shape.Rect) s;
				g.setStroke(new BasicStroke(sw, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
				g.draw(new java.awt.geom.Rectangle2D.Float(r.x1, r.y1, r.x2 - r.x1, r.y2 - r.y1));
				return;
			}
			case PEN: {
				Shape.Pen p = (Shape.Pen) s;
				if (p.count() == 0) return;
				g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				if (p.count() == 1) {
					g.fill(new java.awt.geom.Ellipse2D.Float(p.x(0) - sw / 2, p.y(0) - sw / 2, sw, sw));
					return;
				}
				Path2D.Float path = new Path2D.Float();
				path.moveTo(p.x(0), p.y(0));
				for (int i = 1; i < p.count(); i++) path.lineTo(p.x(i), p.y(i));
				g.draw(path);
				return;
			}
			case TEXT: {
				Shape.Text t = (Shape.Text) s;
				if (t.text.trim().isEmpty()) return;
				Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, Math.round(t.size)));
				g.setFont(font);
				FontMetrics fm = g.getFontMetrics();
				g.rotate(t.quarterTurns * Math.PI / 2, t.x, t.y);
				float base = t.y + fm.getAscent();
				// Dunkler Schatten, damit Text auf hellem und dunklem Grund lesbar bleibt.
				float off = Math.max(1f, t.size / 14f);
				Color fill = g.getColor();
				g.setColor(new Color(0, 0, 0, 150));
				g.drawString(t.text, t.x + off, base + off);
				g.setColor(fill);
				g.drawString(t.text, t.x, base);
				return;
			}
			default:
		}
	}

	/** Bereich (x1,y1)–(x2,y2) in Blöcken von {@code block} Pixeln mitteln. */
	static void pixelate(int[] px, int w, int h, int x1, int y1, int x2, int y2, int block) {
		int ax = Math.max(0, Math.min(x1, x2)), bx = Math.min(w, Math.max(x1, x2));
		int ay = Math.max(0, Math.min(y1, y2)), by = Math.min(h, Math.max(y1, y2));
		if (bx <= ax || by <= ay) return;
		for (int y = ay; y < by; y += block) {
			int ye = Math.min(by, y + block);
			for (int x = ax; x < bx; x += block) {
				int xe = Math.min(bx, x + block);
				long r = 0, g = 0, b = 0;
				int n = 0;
				for (int yy = y; yy < ye; yy++) {
					int row = yy * w;
					for (int xx = x; xx < xe; xx++) {
						int p = px[row + xx];
						r += (p >> 16) & 0xFF;
						g += (p >> 8) & 0xFF;
						b += p & 0xFF;
						n++;
					}
				}
				int c = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
				for (int yy = y; yy < ye; yy++) {
					int row = yy * w;
					for (int xx = x; xx < xe; xx++) px[row + xx] = c;
				}
			}
		}
	}

	/** Ergebnis in voller Größe: drehen, zeichnen, zuschneiden, zuletzt der Filter „Rauschen“. */
	public static int[] export(int[] base, EditState s) {
		int[] rotated = rotate(base, s.baseW, s.baseH, s.rotation);
		int w = s.width(), h = s.height();
		draw(rotated, w, h, s.shapes, 1f);
		int[] out = s.cropped() ? crop(rotated, w, h, s.cropX, s.cropY, s.cropW, s.cropH) : rotated;
		if (s.grain) grain(out, s.cropW, s.cropH, 0, 0, s.cropW, s.cropH, grainCell(s.cropW, s.cropH));
		return out;
	}

	/** Seed des Korns – fest, damit Vorschau und Speichern dasselbe Muster zeigen. */
	static final int GRAIN_SEED = 0x7A110E3;
	/** Stärke des Korns (± Helligkeit, 0–255). */
	static final int GRAIN_AMOUNT = 26;
	/** Stärke der Vignette am Rand (0–1). */
	static final float GRAIN_VIGNETTE = 0.55f;

	/** Korngröße in Bildpixeln für ein Ergebnis von {@code w}×{@code h} (wie das Menü-Korn: ~1/480 der langen Seite). */
	public static float grainCell(int w, int h) {
		return Math.max(1f, Math.max(w, h) / 480f);
	}

	/**
	 * Halloween-Filter „Rauschen“ – dasselbe Bild wie „Flackern &amp; Rauschen“ in den Menüs ({@code HorrorLayer}):
	 * graues Filmkorn, dunkle Vignette und ein kühler Mondnacht-Ton (Lila/Silber). Wirkt auf den Bereich
	 * ({@code rx, ry, rw, rh}) – die Vignette sitzt in dessen Mitte; {@code cell} = Korngröße in Pufferpixeln. Das
	 * Muster hängt nur von der Kornzelle ab, nicht von der Auflösung – die Vorschau (verkleinert) sieht aus wie das
	 * Ergebnis.
	 */
	public static void grain(int[] px, int w, int h, int rx, int ry, int rw, int rh, float cell) {
		int ax = Math.max(0, rx), ay = Math.max(0, ry);
		int bx = Math.min(w, rx + rw), by = Math.min(h, ry + rh);
		if (bx <= ax || by <= ay || rw <= 0 || rh <= 0) return;
		float c = Math.max(0.25f, cell);
		double cx = rx + rw / 2.0, cy = ry + rh / 2.0;
		double hx = rw / 2.0, hy = rh / 2.0;
		for (int y = ay; y < by; y++) {
			int row = y * w;
			int gy = (int) Math.floor((y - ry) / c);
			double ny = (y + 0.5 - cy) / hy;
			for (int x = ax; x < bx; x++) {
				int p = px[row + x];
				int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
				// Mondnacht-Ton: etwas entsättigt, Schatten Richtung Lila, Lichter Richtung Silber.
				int lum = (r * 77 + g * 151 + b * 28) >> 8;
				r = (r * 3 + lum * 7 / 8) >> 2;
				g = (g * 3 + lum * 13 / 16) >> 2;
				b = (b * 3 + Math.min(255, lum + 18)) >> 2;
				// Filmkorn: je Kornzelle ein fester Zufallswert (auf hellem Grund etwas schwächer).
				int gx = (int) Math.floor((x - rx) / c);
				int n = (hash(gx, gy) & 0xFF) - 128;
				int d = n * GRAIN_AMOUNT / 128 * (320 - lum / 2) / 320;
				r += d;
				g += d;
				b += d;
				// Vignette: ab der halben Strecke zum Rand dunkler, in den Ecken am stärksten.
				double nx = (x + 0.5 - cx) / hx;
				double e = Math.sqrt(nx * nx + ny * ny) / Math.sqrt(2);
				if (e > 0.35) {
					double k = (e - 0.35) / 0.65;
					double f = 1 - GRAIN_VIGNETTE * k * k;
					r = (int) (r * f);
					g = (int) (g * f);
					b = (int) (b * f);
				}
				px[row + x] = 0xFF000000 | clamp255(r) << 16 | clamp255(g) << 8 | clamp255(b);
			}
		}
	}

	private static int clamp255(int v) {
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	/** Fester Zufallswert je Kornzelle (Integer-Hash, gleichmäßig verteilt). */
	static int hash(int x, int y) {
		int v = x * 0x27D4EB2D ^ y * 0x165667B1 ^ GRAIN_SEED;
		v ^= v >>> 15;
		v *= 0x2C1B3C6D;
		v ^= v >>> 12;
		v *= 0x297A2D39;
		v ^= v >>> 15;
		return v;
	}
}
