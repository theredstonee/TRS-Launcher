package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.cape.PngDecoder;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kartenfarben aus den Block-Texturen des gerade geladenen Resource Packs: je Block-Zustand die gemittelte Farbe
 * der Oberseiten-Textur (bei getönten Texturen wie Gras/Laub die Graustufe – die Karte multipliziert später mit
 * der Biom-Tönung). Dazu Größen von Entity-Texturen (für die Mob-Köpfe, siehe {@link MobHeads}).
 *
 * <p>Ablauf: Der Spiel-Thread fragt je Block-Zustand ({@link #lookup}); unbekannte Zustände löst die Version einmal
 * zu „Textur + getönt?“ auf ({@link #resolve}). Das Lesen und Mitteln der Textur läuft im Hintergrund-Thread
 * „TRS-MapColors“ – bis dahin gilt {@link #UNKNOWN} (die Karte nimmt die Vanilla-Kartenfarbe) und danach tastet
 * die Karte die Umgebung einmal neu ab ({@link #takeChanged}). Jede Textur wird nur einmal gelesen; ein
 * Resource-Reload ({@link #checkGeneration}) verwirft alles.
 */
public final class TexturePalette {
	/** Merker: Farbe ist die Graustufe einer getönten Textur – mit der Tönungsfarbe multiplizieren. */
	public static final int TINTED = 1 << 24;
	/** Noch unbekannt oder keine Textur → Vanilla-Kartenfarbe verwenden. */
	public static final int UNKNOWN = -1;
	/** {@link #lookup}: Zustand wurde noch nie aufgelöst → {@link #resolve} aufrufen. */
	public static final int UNRESOLVED = Integer.MIN_VALUE;
	/** Größte gelesene Textur (Bytes) – schützt vor riesigen Dateien in Resource Packs. */
	static final int MAX_BYTES = 4 << 20;

	/** Liest eine Datei aus den geladenen Ressourcen (Resource Packs zuerst). Muss aus einem Hintergrund-Thread gehen. */
	public interface Resources {
		/** @return Inhalt oder null, wenn es die Datei nicht gibt */
		byte[] read(String namespace, String path) throws IOException;
	}

	/** Auflösung eines Block-Zustands, der noch auf seine Textur wartet. */
	private static final class Pending {
		final String sprite;
		final boolean tinted;

		Pending(String sprite, boolean tinted) {
			this.sprite = sprite;
			this.tinted = tinted;
		}
	}

	private final boolean synchronous;
	private volatile Resources resources;
	private ThreadPoolExecutor worker;
	/** Spiel-Thread: Zustand → fertige Farbe (Integer) oder {@link Pending}. */
	private final Map<Object, Object> states = new IdentityHashMap<Object, Object>();
	/** Textur ("ns:pfad") → gemittelte Farbe (0xRRGGBB) oder {@link #UNKNOWN}. */
	private final ConcurrentHashMap<String, Integer> sprites = new ConcurrentHashMap<String, Integer>();
	private final ConcurrentHashMap<String, Boolean> queued = new ConcurrentHashMap<String, Boolean>();
	/** Bildgrößen ("ns:pfad" → {breite, höhe}; {0, 0} = fehlt). */
	private final ConcurrentHashMap<String, int[]> sizes = new ConcurrentHashMap<String, int[]>();
	private final AtomicInteger generation = new AtomicInteger();
	private final AtomicInteger pending = new AtomicInteger();
	private volatile boolean changed;
	private Object generationToken;

	/**
	 * @param synchronous true = alles sofort im aufrufenden Thread (Tests)
	 */
	public TexturePalette(boolean synchronous) {
		this.synchronous = synchronous;
	}

	public void setResources(Resources r) {
		resources = r;
	}

	/**
	 * Resource-Reload erkennen: {@code token} ist ein Objekt, das bei jedem Neuladen neu entsteht (z. B. das Modell
	 * von Stein). Anderes Objekt → alle Farben verwerfen.
	 *
	 * @return true, wenn verworfen wurde
	 */
	public boolean checkGeneration(Object token) {
		if (token == null || token == generationToken) return false;
		boolean first = generationToken == null;
		generationToken = token;
		if (first) return false;
		clear();
		return true;
	}

	/** Alles vergessen (Reload, Tests). */
	public void clear() {
		generation.incrementAndGet();
		states.clear();
		sprites.clear();
		queued.clear();
		sizes.clear();
		changed = true;
	}

	/** Zählt jedes Verwerfen (Resource-Reload) – Caches anderer Klassen vergleichen damit. */
	public int generation() {
		return generation.get();
	}

	/** Anzahl bekannter Texturfarben (Messung/Tests). */
	public int spriteCount() {
		return sprites.size();
	}

	/**
	 * Farbe eines Block-Zustands (Spiel-Thread): 0xRRGGBB, bei getönten Texturen zusätzlich {@link #TINTED};
	 * {@link #UNKNOWN} = (noch) keine; {@link #UNRESOLVED} = erst {@link #resolve} aufrufen.
	 */
	public int lookup(Object state) {
		Object v = states.get(state);
		if (v == null) return UNRESOLVED;
		if (v instanceof Integer) return (Integer) v;
		Pending p = (Pending) v;
		Integer c = sprites.get(p.sprite);
		if (c == null) return UNKNOWN;
		int color = c == UNKNOWN ? UNKNOWN : (c & MapColors.RGB) | (p.tinted ? TINTED : 0);
		states.put(state, color);
		return color;
	}

	/**
	 * Merkt die Textur eines Block-Zustands (Spiel-Thread, einmal je Zustand) und stößt das Mitteln an.
	 *
	 * @param sprite Textur als {@code "namespace:pfad"} ohne {@code textures/} und {@code .png}
	 *        (z. B. {@code minecraft:block/grass_block_top}) oder null = keine
	 * @return wie {@link #lookup}
	 */
	public int resolve(Object state, String sprite, boolean tinted) {
		if (sprite == null || sprite.isEmpty()) {
			states.put(state, UNKNOWN);
			return UNKNOWN;
		}
		states.put(state, new Pending(sprite, tinted));
		if (!sprites.containsKey(sprite)) request(sprite);
		return lookup(state);
	}

	/**
	 * Merkt eine schon bekannte Texturfarbe eines Block-Zustands (Versionen, die die Pixel der Textur direkt im
	 * Speicher haben – kein Lesen der Datei nötig). Spiel-Thread.
	 *
	 * @param rgb gemittelte Farbe (siehe {@link #average}) oder {@link #UNKNOWN}
	 * @return wie {@link #lookup}
	 */
	public int resolveColor(Object state, int rgb, boolean tinted) {
		int color = rgb == UNKNOWN ? UNKNOWN : (rgb & MapColors.RGB) | (tinted ? TINTED : 0);
		states.put(state, color);
		return color;
	}

	/** Liegen neue Farben vor, seit zuletzt gefragt wurde, und ist der Hintergrund fertig? (setzt zurück) */
	public boolean takeChanged() {
		if (!changed || pending.get() > 0) return false;
		changed = false;
		return true;
	}

	/** Wird im Hintergrund noch gerechnet? */
	public boolean busy() {
		return pending.get() > 0;
	}

	private void request(final String sprite) {
		if (queued.putIfAbsent(sprite, Boolean.TRUE) != null) return;
		final int gen = generation.get();
		submit(() -> {
			int color = UNKNOWN;
			try {
				byte[] data = readTexture(sprite);
				if (data != null) {
					PngDecoder.Image img = PngDecoder.decode(data);
					color = average(img.argb, img.width, img.height);
				}
			} catch (IOException | RuntimeException e) {
				color = UNKNOWN;
			}
			if (gen == generation.get()) {
				sprites.put(sprite, color);
				changed = true;
			}
		});
	}

	private byte[] readTexture(String sprite) throws IOException {
		Resources r = resources;
		if (r == null) return null;
		int colon = sprite.indexOf(':');
		String ns = colon < 0 ? "minecraft" : sprite.substring(0, colon);
		String path = colon < 0 ? sprite : sprite.substring(colon + 1);
		byte[] data = r.read(ns, "textures/" + path + ".png");
		return data == null || data.length > MAX_BYTES ? null : data;
	}

	/**
	 * Größe einer Textur-Datei ({@code location} = {@code "namespace:textures/…png"}); fehlt sie noch, wird sie im
	 * Hintergrund nachgesehen. Aufrufbar aus jedem Thread.
	 *
	 * @return {breite, höhe}, {0, 0} = gibt es nicht, null = wird noch nachgesehen
	 */
	public int[] imageSize(final String location) {
		int[] s = sizes.get(location);
		if (s != null) return s;
		if (queued.putIfAbsent("size:" + location, Boolean.TRUE) != null) return null;
		final int gen = generation.get();
		submit(() -> {
			int[] result = {0, 0};
			Resources r = resources;
			if (r != null) {
				int colon = location.indexOf(':');
				try {
					byte[] data = r.read(colon < 0 ? "minecraft" : location.substring(0, colon),
							colon < 0 ? location : location.substring(colon + 1));
					int[] wh = pngSize(data);
					if (wh != null) result = wh;
				} catch (IOException | RuntimeException e) {
					// fehlt
				}
			}
			if (gen == generation.get()) sizes.put(location, result);
		});
		return sizes.get(location);
	}

	/** Breite/Höhe aus dem PNG-Kopf (IHDR) oder null. */
	static int[] pngSize(byte[] png) {
		if (png == null || png.length < 24) return null;
		if ((png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return null;
		if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return null;
		int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
		int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
		if (w <= 0 || h <= 0 || w > 16384 || h > 65536) return null;
		return new int[] {w, h};
	}

	private void submit(Runnable task) {
		pending.incrementAndGet();
		Runnable wrapped = () -> {
			try {
				task.run();
			} finally {
				pending.decrementAndGet();
			}
		};
		if (synchronous) {
			wrapped.run();
			return;
		}
		synchronized (this) {
			if (worker == null) {
				worker = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
					Thread t = new Thread(r, "TRS-MapColors");
					t.setDaemon(true);
					t.setPriority(Thread.MIN_PRIORITY + 1);
					return t;
				});
				worker.allowCoreThreadTimeOut(true);
			}
		}
		try {
			worker.execute(wrapped);
		} catch (RuntimeException e) {
			pending.decrementAndGet();
		}
	}

	/**
	 * Mittlere Farbe einer Textur (0xRRGGBB) – nach Deckkraft gewichtet, durchsichtige Pixel zählen nicht. Bei
	 * animierten Texturen (Bilder untereinander, höher als breit) nur das erste Bild. {@link #UNKNOWN}, wenn alles
	 * durchsichtig ist.
	 */
	public static int average(int[] argb, int width, int height) {
		if (argb == null || width <= 0 || height <= 0) return UNKNOWN;
		int h = height > width && height % width == 0 ? width : height;
		long r = 0, g = 0, b = 0, weight = 0;
		for (int y = 0; y < h; y++) {
			int row = y * width;
			for (int x = 0; x < width; x++) {
				int c = argb[row + x];
				int a = c >>> 24;
				if (a < 16) continue;
				r += (long) ((c >> 16) & 0xFF) * a;
				g += (long) ((c >> 8) & 0xFF) * a;
				b += (long) (c & 0xFF) * a;
				weight += a;
			}
		}
		if (weight == 0) return UNKNOWN;
		return (int) (r / weight) << 16 | (int) (g / weight) << 8 | (int) (b / weight);
	}

	/**
	 * Getönte Texturfarbe: Graustufe × Tönung (je Kanal, wie im Spiel). {@code tint} = -1 → Graustufe unverändert.
	 */
	public static int tint(int gray, int tint) {
		gray &= MapColors.RGB;
		if (tint == -1) return gray;
		int r = ((gray >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
		int g = ((gray >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
		int b = (gray & 0xFF) * (tint & 0xFF) / 255;
		return (r << 16) | (g << 8) | b;
	}
}
