package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Bilder des Chats als Texturen: Laden (Anhänge mit dem Token des Mods, Server-Symbole), Dekodieren im Hintergrund mit
 * Pixel-Grenzen (PNG über den eigenen Dekoder, JPEG über ImageIO mit Unterabtastung), Verkleinern auf die gewünschte
 * Kantenlänge und höchstens {@link #UPLOADS_PER_FRAME} Uploads je Bild im Render-Thread. LRU mit festen Plätzen.
 */
public final class ChatImages {
	public static final int UPLOADS_PER_FRAME = 2;
	/** Größte Kante vor dem Dekodieren (API: ≤ 8192) und größte Pixelzahl (24 MP). */
	static final int MAX_SIDE = 8192;
	static final long MAX_PIXELS = 24L * 1000 * 1000;

	/** Zustand eines Bildes. */
	private static final class Slot {
		TextureRef ref;
		int width;
		int height;
		boolean failed;
		boolean loading;
	}

	private static final class Decoded {
		final String key;
		final int width;
		final int height;
		final int[] argb;

		Decoded(String key, int width, int height, int[] argb) {
			this.key = key;
			this.width = width;
			this.height = height;
			this.argb = argb;
		}
	}

	private final Executor worker;
	private final int capacity;
	private final LinkedHashMap<String, Slot> cache = new LinkedHashMap<String, Slot>(64, 0.75f, true);
	private final ConcurrentLinkedQueue<Decoded> done = new ConcurrentLinkedQueue<Decoded>();
	private static int counter;
	private final String prefix;
	private int uploads;

	public ChatImages(Executor worker, int capacity) {
		this.worker = worker;
		this.capacity = Math.max(4, capacity);
		synchronized (ChatImages.class) {
			this.prefix = "trs_chat/" + (counter++) + "_";
		}
	}

	/**
	 * Textur für {@code key} oder null (lädt noch / ging nicht). Beim ersten Aufruf wird {@code source} im
	 * Hintergrund gelesen und auf höchstens {@code maxSide} Pixel verkleinert. Render-Thread.
	 */
	public TextureRef get(String key, Callable<byte[]> source, int maxSide) {
		Slot s = cache.get(key);
		if (s != null) return s.ref;
		if (Textures.store() == null) return null;
		s = new Slot();
		s.loading = true;
		cache.put(key, s);
		trim();
		schedule(key, source, maxSide);
		return null;
	}

	/** Liegt ein Ergebnis vor (Bild oder Fehler)? */
	public boolean settled(String key) {
		Slot s = cache.get(key);
		return s != null && !s.loading;
	}

	public boolean failed(String key) {
		Slot s = cache.get(key);
		return s != null && s.failed;
	}

	/** Seitenverhältnis des geladenen Bildes (0 = unbekannt). */
	public float aspect(String key) {
		Slot s = cache.get(key);
		return s == null || s.height == 0 ? 0f : (float) s.width / s.height;
	}

	private void schedule(final String key, final Callable<byte[]> source, final int maxSide) {
		try {
			worker.execute(new Runnable() {
				@Override
				public void run() {
					Decoded d;
					try {
						byte[] bytes = source.call();
						Image img = decode(bytes, maxSide);
						d = new Decoded(key, img.width, img.height, img.argb);
					} catch (Exception | OutOfMemoryError e) {
						d = new Decoded(key, 0, 0, null);
					}
					done.add(d);
				}
			});
		} catch (RuntimeException e) {
			done.add(new Decoded(key, 0, 0, null));
		}
	}

	/** Einmal je Bild (Render-Thread): fertige Bilder hochladen. */
	public void frame() {
		Textures.Store store = Textures.store();
		for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
			Decoded d = done.poll();
			if (d == null) return;
			Slot s = cache.get(d.key);
			if (s == null) continue;
			s.loading = false;
			if (d.argb == null || store == null) {
				s.failed = true;
				continue;
			}
			TextureRef ref = store.upload(prefix + (uploads++), d.width, d.height, d.argb);
			if (ref == null) {
				s.failed = true;
			} else {
				s.ref = ref;
				s.width = d.width;
				s.height = d.height;
			}
		}
	}

	private void trim() {
		Textures.Store store = Textures.store();
		Iterator<Map.Entry<String, Slot>> it = cache.entrySet().iterator();
		while (cache.size() > capacity && it.hasNext()) {
			Slot s = it.next().getValue();
			if (s.loading) continue;
			if (s.ref != null && store != null) store.release(s.ref);
			it.remove();
		}
	}

	/** Alle Texturen freigeben. Render-Thread. */
	public void releaseAll() {
		Textures.Store store = Textures.store();
		for (Slot s : cache.values()) {
			if (s.ref != null && store != null) store.release(s.ref);
		}
		cache.clear();
		done.clear();
	}

	// --- Dekodieren (Hintergrund) ---

	/** Dekodiertes Bild (ARGB). */
	public static final class Image {
		public final int width;
		public final int height;
		public final int[] argb;

		public Image(int width, int height, int[] argb) {
			this.width = width;
			this.height = height;
			this.argb = argb;
		}
	}

	static boolean isPng(byte[] b) {
		return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
	}

	static boolean isJpeg(byte[] b) {
		return b != null && b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
	}

	/** PNG oder JPEG dekodieren und auf höchstens {@code maxSide} verkleinern. */
	public static Image decode(byte[] bytes, int maxSide) throws IOException {
		Image img;
		if (isPng(bytes)) {
			PngDecoder.Image p = PngDecoder.decode(bytes);
			img = new Image(p.width, p.height, p.argb);
		} else if (isJpeg(bytes)) {
			img = jpeg(bytes, maxSide);
		} else {
			throw new IOException("unbekanntes Bildformat");
		}
		float scale = Math.min(1f, maxSide / (float) Math.max(img.width, img.height));
		if (scale >= 1f) return img;
		int w = Math.max(1, Math.round(img.width * scale));
		int h = Math.max(1, Math.round(img.height * scale));
		return new Image(w, h, downscale(img.argb, img.width, img.height, w, h));
	}

	/** JPEG über ImageIO: Größe vorab prüfen, große Bilder schon beim Lesen unterabtasten (spart Speicher). */
	static Image jpeg(byte[] bytes, int maxSide) throws IOException {
		ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));
		if (in == null) throw new IOException("kein Eingabestrom");
		try {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) throw new IOException("kein JPEG-Leser");
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				int w = reader.getWidth(0);
				int h = reader.getHeight(0);
				if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
					throw new IOException("Bild zu groß");
				}
				ImageReadParam param = reader.getDefaultReadParam();
				int sub = Math.max(1, Math.max(w, h) / Math.max(1, maxSide * 2));
				if (sub > 1) param.setSourceSubsampling(sub, sub, 0, 0);
				BufferedImage bi = reader.read(0, param);
				int bw = bi.getWidth();
				int bh = bi.getHeight();
				int[] argb = bi.getRGB(0, 0, bw, bh, null, 0, bw);
				for (int i = 0; i < argb.length; i++) argb[i] |= 0xFF000000;
				return new Image(bw, bh, argb);
			} finally {
				reader.dispose();
			}
		} finally {
			in.close();
		}
	}

	/** Flächenmittel mit Alpha (vormultipliziert gemittelt). */
	static int[] downscale(int[] src, int sw, int sh, int tw, int th) {
		int[] out = new int[tw * th];
		for (int y = 0; y < th; y++) {
			int y0 = y * sh / th;
			int y1 = Math.max(y0 + 1, (y + 1) * sh / th);
			for (int x = 0; x < tw; x++) {
				int x0 = x * sw / tw;
				int x1 = Math.max(x0 + 1, (x + 1) * sw / tw);
				long a = 0, r = 0, g = 0, b = 0;
				int n = 0;
				int stepY = Math.max(1, (y1 - y0) / 4);
				int stepX = Math.max(1, (x1 - x0) / 4);
				for (int yy = y0; yy < y1; yy += stepY) {
					int row = yy * sw;
					for (int xx = x0; xx < x1; xx += stepX) {
						int p = src[row + xx];
						int pa = (p >>> 24) & 0xFF;
						a += pa;
						r += ((p >> 16) & 0xFF) * pa;
						g += ((p >> 8) & 0xFF) * pa;
						b += (p & 0xFF) * pa;
						n++;
					}
				}
				int oa = (int) (a / n);
				if (a == 0) {
					out[y * tw + x] = 0;
				} else {
					out[y * tw + x] = oa << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
				}
			}
		}
		return out;
	}

	// --- Hochladen vorbereiten (Hintergrund) ---

	/** Bild für den Upload: Bytes + MIME. */
	public static final class Upload {
		public final byte[] bytes;
		public final String mime;

		public Upload(byte[] bytes, String mime) {
			this.bytes = bytes;
			this.mime = mime;
		}
	}

	/**
	 * Bildschirmfoto für den Upload vorbereiten: PNG/JPEG bis 5 MiB unverändert (der Server kodiert ohnehin neu und
	 * entfernt Metadaten), größere werden auf höchstens 2048 px verkleinert und als JPEG neu kodiert.
	 */
	public static Upload prepare(byte[] file) throws IOException {
		if (file == null || file.length == 0) throw new IOException("leer");
		boolean png = isPng(file);
		if (!png && !isJpeg(file)) throw new IOException("unbekanntes Bildformat");
		if (file.length <= ChatApi.MAX_UPLOAD_BYTES) return new Upload(file, png ? "image/png" : "image/jpeg");
		Image img = decode(file, 2048);
		BufferedImage bi = new BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB);
		bi.setRGB(0, 0, img.width, img.height, img.argb, 0, img.width);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) throw new IOException("kein JPEG-Schreiber");
		javax.imageio.ImageWriter writer = writers.next();
		try {
			javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(0.9f);
			javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out);
			try {
				writer.setOutput(ios);
				writer.write(null, new javax.imageio.IIOImage(bi, null, null), param);
			} finally {
				ios.close();
			}
		} finally {
			writer.dispose();
		}
		byte[] jpeg = out.toByteArray();
		if (jpeg.length > ChatApi.MAX_UPLOAD_BYTES) throw new IOException("Bild zu groß");
		return new Upload(jpeg, "image/jpeg");
	}
}
