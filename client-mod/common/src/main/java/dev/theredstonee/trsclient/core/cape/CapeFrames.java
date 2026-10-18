package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.online.CapeInfo;

import java.io.IOException;

/**
 * Ein Umhang, zerlegt in seine Einzelbilder (je {@code 64·scale × 32·scale}, ARGB). Jedes Bild wird eine
 * eigene Textur – so passen die Vanilla-UVs des Umhangs unverändert, egal wie viele Bilder. Die Elytra bleibt immer
 * Vanilla (TRS-Umhänge werden nie als Elytra-Textur genutzt).
 *
 * <p>{@link #decode} schreibt beim Dekodieren direkt in die Einzelbilder (der ganze Streifen liegt nie am Stück im
 * Speicher) und kann dabei verkleinern ({@link #downscale}, Kastenfilter) – für das Textur-Budget von
 * {@link CapeTextures}.
 */
public final class CapeFrames {
	/** Höchstens so viele Bilder je Umhang (Plausibilitätsgrenze; neue Uploads haben höchstens 32). */
	public static final int MAX_FRAMES = 64;

	public final int width;
	public final int height;
	/**
	 * [Bild][Pixel], ARGB zeilenweise. {@link CapeTextures} setzt ein Bild nach dem Hochladen auf null (der Speicher ist
	 * dann sofort frei).
	 */
	public final int[][] frames;
	/** Verkleinerung gegenüber der Datei (1 = volle Qualität, 2 = halbe Kantenlänge …). */
	public final int downscale;
	/**
	 * Echter HD-Umhang (nicht aus einfarbigen k×k-Blöcken): wird weich gefiltert (linear + Mipmaps), Pixel-Art bleibt
	 * scharf – siehe {@link CapeFilter}. Beim Dekodieren bestimmt, solange alle Pixel noch da sind.
	 */
	public final boolean smooth;

	CapeFrames(int width, int height, int[][] frames) {
		this(width, height, frames, 1);
	}

	CapeFrames(int width, int height, int[][] frames, int downscale) {
		this.width = width;
		this.height = height;
		this.frames = frames;
		this.downscale = downscale;
		this.smooth = CapeFilter.looksSmooth(width, height, frames);
	}

	/**
	 * Teilt ein schon dekodiertes Bild in Bilder auf. Maßgeblich ist das Seitenverhältnis 2:1 je Bild; stimmt die
	 * Bildzahl der API nicht mit der Höhe überein, gewinnt die Höhe (höchstens {@link #MAX_FRAMES} Bilder).
	 */
	public static CapeFrames split(PngDecoder.Image image, CapeInfo info) throws IOException {
		int w = image.width;
		int frameH = w / 2;
		int count = frameCount(w, image.height, info == null ? 0 : info.frames, MAX_FRAMES);
		int[][] frames = new int[count][];
		for (int f = 0; f < count; f++) {
			int[] px = new int[w * frameH];
			System.arraycopy(image.argb, f * w * frameH, px, 0, w * frameH);
			frames[f] = px;
		}
		return new CapeFrames(w, frameH, frames);
	}

	/** Wie {@link #decode(byte[], int, int, int)} mit der Bildzahl der API und allen Bildern. */
	public static CapeFrames decode(byte[] png, CapeInfo info, int downscale) throws IOException {
		return decode(png, info == null ? 0 : info.frames, downscale, MAX_FRAMES);
	}

	/**
	 * Dekodiert eine Umhang-PNG direkt in Einzelbilder.
	 *
	 * @param expectedFrames Bildzahl laut API (0 = unbekannt); weicht die Datei ab, gewinnt die Datei, aber nie mehr Bilder
	 *                       als versprochen
	 * @param downscale      gewünschte Verkleinerung (1, 2, 4, 8, 16); wird so begrenzt, dass ein Bild mindestens 64×32
	 *                       bleibt
	 * @param maxFrames      höchstens so viele Bilder behalten (1 = nur die Vorschau, der Rest wird gar nicht entpackt)
	 */
	public static CapeFrames decode(byte[] png, int expectedFrames, int downscale, int maxFrames) throws IOException {
		FrameSink sink = new FrameSink(expectedFrames, downscale, maxFrames);
		PngDecoder.decode(png, PngDecoder.MAX_PIXELS, sink);
		return sink.finish();
	}

	/** Bildzahl eines Streifens {@code width × height} – prüft Breite (64…64·MAX_SCALE, Vielfaches von 64) und Höhe. */
	static int frameCount(int width, int height, int expectedFrames, int maxFrames) throws IOException {
		int frameH = width / 2;
		if (width < 64 || width > 64 * CapeInfo.MAX_SCALE || width % 64 != 0 || frameH == 0) {
			throw new IOException("Umhang-Breite " + width);
		}
		if (height % frameH != 0) throw new IOException("Umhang-Höhe " + height);
		int count = height / frameH;
		if (count < 1 || count > MAX_FRAMES) throw new IOException("Bildzahl " + count);
		if (expectedFrames > 0 && expectedFrames != count) {
			// Die API verspricht etwas anderes als die Datei – der Datei glauben, aber nie mehr Bilder als geliefert.
			count = Math.min(count, expectedFrames);
		}
		return Math.max(1, Math.min(count, maxFrames));
	}

	/** Verkleinerung auf eine Zweierpotenz begrenzen, die ein Bild mindestens 64 Pixel breit lässt. */
	static int clampDownscale(int width, int downscale) {
		int k = 1;
		while (k * 2 <= downscale && width / (k * 2) >= 64) k *= 2;
		return k;
	}

	/** Speicher (Bytes, RGBA) aller Bilder dieses Umhangs – so viel belegt er auch als Textur (weich: samt Mipmaps). */
	public long bytes() {
		long perFrame = (long) width * height * 4 + (smooth ? CapeFilter.mipBytes(width, height) : 0);
		return perFrame * frames.length;
	}

	/** Geschätzter Speicher eines Umhangs laut API (Faktor, Bilder) bei Verkleinerung {@code downscale}. */
	public static long bytes(int scale, int frames, int downscale) {
		int k = clampDownscale(64 * Math.max(1, scale), downscale);
		long w = 64L * Math.max(1, scale) / k;
		long h = 32L * Math.max(1, scale) / k;
		long count = Math.max(1, Math.min(frames, MAX_FRAMES));
		// Die Datei darf nicht größer sein als die Pixel-Grenze des Dekoders – mehr kann also nie ankommen.
		long maxCount = Math.max(1, PngDecoder.MAX_PIXELS / (64L * scale * 32L * scale));
		return w * h * 4 * Math.min(count, maxCount);
	}

	public int count() {
		return frames.length;
	}

	/**
	 * Verkleinert ein Bild um {@code k} (Kastenfilter, gewichtet mit der Deckkraft – durchsichtige Pixel färben die
	 * Ränder nicht dunkel).
	 */
	static int[] downscale(int[] px, int w, int h, int k) {
		if (k <= 1) return px;
		int ow = w / k;
		int oh = h / k;
		int[] out = new int[ow * oh];
		int[] a = new int[ow];
		int[] r = new int[ow];
		int[] g = new int[ow];
		int[] b = new int[ow];
		for (int oy = 0; oy < oh; oy++) {
			for (int y = oy * k; y < oy * k + k; y++) accumulate(px, y * w, ow, k, a, r, g, b);
			emit(out, oy * ow, ow, k, a, r, g, b);
		}
		return out;
	}

	/** Eine Quellzeile (ab {@code off}, {@code ow·k} Pixel) in die Summen je Zielpixel. */
	private static void accumulate(int[] px, int off, int ow, int k, int[] a, int[] r, int[] g, int[] b) {
		for (int o = 0; o < ow; o++) {
			int sa = 0;
			int sr = 0;
			int sg = 0;
			int sb = 0;
			for (int i = off + o * k, e = i + k; i < e; i++) {
				int c = px[i];
				int al = c >>> 24;
				sa += al;
				sr += ((c >> 16) & 0xFF) * al;
				sg += ((c >> 8) & 0xFF) * al;
				sb += (c & 0xFF) * al;
			}
			a[o] += sa;
			r[o] += sr;
			g[o] += sg;
			b[o] += sb;
		}
	}

	/** Summen → Zielzeile (ab {@code off}), Summen danach auf 0. */
	private static void emit(int[] out, int off, int ow, int k, int[] a, int[] r, int[] g, int[] b) {
		int n = k * k;
		for (int o = 0; o < ow; o++) {
			int sa = a[o];
			int c = 0;
			if (sa > 0) {
				int al = (sa + n / 2) / n;
				c = (al << 24) | (((r[o] + sa / 2) / sa) << 16) | (((g[o] + sa / 2) / sa) << 8) | ((b[o] + sa / 2) / sa);
			}
			out[off + o] = c;
			a[o] = 0;
			r[o] = 0;
			g[o] = 0;
			b[o] = 0;
		}
	}

	/**
	 * Schreibt die Zeilen des Dekoders direkt in die Einzelbilder. Ohne Interlacing (der Normalfall) wird dabei sofort
	 * verkleinert – es liegt höchstens eine Quellzeile zusätzlich im Speicher. Mit Interlacing (selten) entstehen erst die
	 * Bilder in voller Größe, dann wird verkleinert.
	 */
	private static final class FrameSink implements PngDecoder.Sink {
		private final int expectedFrames;
		private final int wantDownscale;
		private final int maxFrames;
		private int width;
		private int frameH;
		private int count;
		private int k;
		private boolean interlaced;
		private int[][] frames;
		private int[] a;
		private int[] r;
		private int[] g;
		private int[] b;

		FrameSink(int expectedFrames, int downscale, int maxFrames) {
			this.expectedFrames = expectedFrames;
			this.wantDownscale = Math.max(1, downscale);
			this.maxFrames = Math.max(1, maxFrames);
		}

		@Override
		public void begin(int w, int h, boolean interlaced) throws IOException {
			count = frameCount(w, h, expectedFrames, maxFrames);
			width = w;
			frameH = w / 2;
			k = clampDownscale(w, wantDownscale);
			this.interlaced = interlaced;
			frames = new int[count][];
			if (!interlaced && k > 1) {
				int ow = w / k;
				a = new int[ow];
				r = new int[ow];
				g = new int[ow];
				b = new int[ow];
			}
		}

		@Override
		public boolean row(int y, int x0, int dx, int[] px, int n) {
			int f = y / frameH;
			if (f >= count) return interlaced; // ohne Interlacing: alles Gebrauchte ist da
			int ry = y - f * frameH;
			if (interlaced || k == 1) {
				int[] frame = frames[f];
				if (frame == null) frame = frames[f] = new int[width * frameH];
				int base = ry * width + x0;
				if (dx == 1) {
					System.arraycopy(px, 0, frame, base, n);
				} else {
					for (int i = 0; i < n; i++) frame[base + i * dx] = px[i];
				}
				return true;
			}
			int ow = width / k;
			accumulate(px, 0, ow, k, a, r, g, b);
			if (ry % k == k - 1) {
				int[] frame = frames[f];
				if (frame == null) frame = frames[f] = new int[ow * (frameH / k)];
				emit(frame, (ry / k) * ow, ow, k, a, r, g, b);
			}
			return true;
		}

		CapeFrames finish() throws IOException {
			if (frames == null) throw new IOException("Umhang leer");
			for (int f = 0; f < count; f++) {
				if (frames[f] == null) throw new IOException("Umhang-Bild " + f + " fehlt");
				if (interlaced && k > 1) frames[f] = downscale(frames[f], width, frameH, k);
			}
			return new CapeFrames(width / k, frameH / k, frames, k);
		}
	}
}
