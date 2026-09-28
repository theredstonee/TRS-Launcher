package dev.theredstonee.trsclient.core.map;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Weltkarte als PNG exportieren – ganz (alles Erkundete der Ebene) oder den sichtbaren Ausschnitt. Läuft im eigenen
 * Thread „TRS-Map-Export“, das Spiel hängt nie: Im Spiel-Thread wird nur kopiert, was gerade im Speicher liegt (das ist
 * neuer als die Platte), alles andere liest der Kartenthread von der Platte (so überschneiden sich Lesen und Speichern
 * nie). Das Bild wird Bereichszeile für Bereichszeile zusammengesetzt und direkt ins PNG geschrieben ({@link MapPng}).
 *
 * <p>Größe: ein Pixel je Block, solange die längere Seite in {@code maxSize} passt; sonst wird um eine Zweierpotenz
 * verkleinert (2, 4, … 128 Blöcke je Pixel). Ab 4 Blöcken je Pixel reichen die gespeicherten Übersichten (32×32 je
 * Bereich) – dann wird nur der Dateianfang entpackt und der Export ist auch bei riesigen Welten schnell.
 */
public final class MapExport {
	public enum State {
		RUNNING, DONE, FAILED, CANCELLED
	}

	/** Höchstens so viele Blöcke je Pixel (dann ein Pixel je Bereichs-Achtel). */
	static final int MAX_FACTOR = 128;
	/** Harte Grenze je Seite, egal was eingestellt ist. */
	static final int HARD_LIMIT = 32768;
	/** Wartezeit je Bereichszeile auf die Platte. */
	static final long READ_TIMEOUT_S = 60;

	/** Was genau exportiert wird: Block-Rechteck [bx0, bx1) × [bz0, bz1), {@code k} Blöcke je Pixel. */
	public static final class Plan {
		public final int bx0, bz0, bx1, bz1;
		public final int k;
		public final int width, height;

		Plan(int bx0, int bz0, int bx1, int bz1, int k) {
			this.bx0 = bx0;
			this.bz0 = bz0;
			this.bx1 = bx1;
			this.bz1 = bz1;
			this.k = k;
			this.width = (bx1 - bx0) / k;
			this.height = (bz1 - bz0) / k;
		}

		/** Reichen die Übersichten (ein Pixel je 4 Blöcke)? */
		public boolean summaries() {
			return k >= MapTextures.SUPER_BLOCKS_PER_TEXEL;
		}
	}

	/**
	 * Plan für ein Block-Rechteck: kleinste Zweierpotenz {@code k}, mit der die längere Seite in {@code maxSize} Pixel
	 * passt; Ränder auf Vielfache von {@code k} erweitert. null = leer oder selbst mit {@value #MAX_FACTOR} zu groß.
	 */
	public static Plan plan(int bx0, int bz0, int bx1, int bz1, int maxSize) {
		if (bx1 <= bx0 || bz1 <= bz0) return null;
		maxSize = Math.max(16, Math.min(HARD_LIMIT, maxSize));
		long longest = Math.max((long) bx1 - bx0, (long) bz1 - bz0);
		int k = 1;
		while (k < MAX_FACTOR && (longest + k - 1) / k > maxSize) k *= 2;
		int ax0 = Math.floorDiv(bx0, k) * k, az0 = Math.floorDiv(bz0, k) * k;
		int ax1 = -Math.floorDiv(-bx1, k) * k, az1 = -Math.floorDiv(-bz1, k) * k;
		Plan p = new Plan(ax0, az0, ax1, az1, k);
		if (p.width > HARD_LIMIT || p.height > HARD_LIMIT) return null;
		return p;
	}

	/**
	 * Mittel eines Rechtecks aus einem ARGB-Bild (Zeilenlänge {@code stride}): Farbe nach Deckkraft gewichtet,
	 * Deckkraft = Anteil erkundeter Fläche (Ränder werden weich, Unerkundetes bleibt durchsichtig).
	 */
	public static int average(int[] src, int stride, int x, int y, int w, int h) {
		if (w == 1 && h == 1) return src[y * stride + x];
		long r = 0, g = 0, b = 0, a = 0;
		for (int yy = y; yy < y + h; yy++) {
			int row = yy * stride;
			for (int xx = x; xx < x + w; xx++) {
				int c = src[row + xx];
				int al = c >>> 24;
				if (al == 0) continue;
				a += al;
				r += (long) (c >> 16 & 0xFF) * al;
				g += (long) (c >> 8 & 0xFF) * al;
				b += (long) (c & 0xFF) * al;
			}
		}
		if (a == 0) return 0;
		int n = w * h;
		int alpha = (int) Math.min(255, (a + n / 2) / n);
		if (alpha == 0) return 0;
		return alpha << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
	}

	/** Bereiche, die ein Block-Rechteck berührt, als Schlüssel-Grenzen {rx0, rz0, rx1, rz1} (einschließlich). */
	static int[] regionBounds(Plan p) {
		return new int[]{p.bx0 >> MapRegion.SHIFT, p.bz0 >> MapRegion.SHIFT, (p.bx1 - 1) >> MapRegion.SHIFT, (p.bz1 - 1) >> MapRegion.SHIFT};
	}

	/** Block-Rechteck aller Bereiche {bx0, bz0, bx1, bz1} oder null (keine). */
	static int[] boundsOf(Set<Long> keys) {
		if (keys.isEmpty()) return null;
		int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (long k : keys) {
			int rx = MapRegion.keyX(k), rz = MapRegion.keyZ(k);
			x0 = Math.min(x0, rx);
			z0 = Math.min(z0, rz);
			x1 = Math.max(x1, rx);
			z1 = Math.max(z1, rz);
		}
		return new int[]{x0 * MapRegion.SIZE, z0 * MapRegion.SIZE, (x1 + 1) * MapRegion.SIZE, (z1 + 1) * MapRegion.SIZE};
	}

	/** Dateiname {@code trs-map_<welt>_<dimension>[_y<höhe>]_<datum>.png} (nur harmlose Zeichen). */
	public static String fileName(String worldKey, String dimension, MapLayer layer, Date when) {
		String world = worldKey == null ? "" : worldKey;
		if (world.startsWith("sp:") || world.startsWith("mp:")) world = world.substring(3);
		String dim = dimension == null ? "" : dimension;
		int colon = dim.indexOf(':');
		if (colon >= 0) dim = dim.substring(colon + 1);
		StringBuilder sb = new StringBuilder("trs-map_").append(clean(world, "world")).append('_').append(clean(dim, "dim"));
		// Höhlenschicht: Unterkante des Bands (Bezugshöhe = Band-Oberkante + 3).
		if (layer != null && layer.cave()) sb.append("_y").append(layer.caveRef - 3 - MapEngine.CAVE_BAND);
		sb.append('_').append(new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.ROOT).format(when)).append(".png");
		return sb.toString();
	}

	static String clean(String raw, String fallback) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < raw.length() && sb.length() < 32; i++) {
			char c = raw.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
			if (ok) sb.append(c);
			else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
		}
		while (sb.length() > 0 && (sb.charAt(sb.length() - 1) == '_' || sb.charAt(sb.length() - 1) == '.')) sb.setLength(sb.length() - 1);
		return sb.length() == 0 ? fallback : sb.toString();
	}

	/** Spielordner aus dem Kartenordner ({@code <spiel>/config/trsclient/maps}). */
	public static Path gameDir(MapDisk disk) {
		if (disk == null || disk.root() == null) return Paths.get("").toAbsolutePath();
		Path p = disk.root().toAbsolutePath();
		for (int i = 0; i < 3 && p.getParent() != null; i++) p = p.getParent();
		return p;
	}

	/** Freier Dateiname (bei gleicher Sekunde _2, _3 …). */
	static Path unique(Path dir, String name) {
		Path f = dir.resolve(name);
		int n = 2;
		while (Files.exists(f) && n < 100) {
			f = dir.resolve(name.substring(0, name.length() - 4) + "_" + n++ + ".png");
		}
		return f;
	}

	// --- laufender Export ---

	/** Momentaufnahme eines Bereichs aus dem Speicher (Spiel-Thread → Export-Thread). */
	private static final class Snap {
		final int[] pixels;
		final short[] heights;
		final int[] summary;

		Snap(int[] pixels, short[] heights, int[] summary) {
			this.pixels = pixels;
			this.heights = heights;
			this.summary = summary;
		}
	}

	private static volatile MapExport current;

	private final Plan plan;
	private final Set<Long> keys;
	private final Map<Long, Snap> snaps;
	private final MapDisk disk;
	private final Path dir;
	private final int caveRef;
	private final Path file;
	private final Consumer<MapExport> onFinish;
	private volatile State state = State.RUNNING;
	private volatile float progress;
	private volatile String error;
	private volatile boolean cancel;
	private volatile long millis;

	private MapExport(Plan plan, Set<Long> keys, Map<Long, Snap> snaps, MapDisk disk, Path dir, int caveRef, Path file,
			Consumer<MapExport> onFinish) {
		this.plan = plan;
		this.keys = keys;
		this.snaps = snaps;
		this.disk = disk;
		this.dir = dir;
		this.caveRef = caveRef;
		this.file = file;
		this.onFinish = onFinish;
	}

	/** Laufender oder zuletzt beendeter Export (null = noch keiner). */
	public static MapExport current() {
		return current;
	}

	/** Läuft gerade ein Export? */
	public static boolean running() {
		MapExport e = current;
		return e != null && e.state == State.RUNNING;
	}

	/** Ergebnis beim Start. */
	public enum StartResult {
		STARTED, RUNNING, EMPTY, TOO_BIG
	}

	/**
	 * Startet einen Export (nur Spiel-Thread).
	 *
	 * @param rect     {bx0, bz0, bx1, bz1} = sichtbarer Ausschnitt, null = alles Erkundete
	 * @param outDir   Zielordner (meist {@code screenshots})
	 * @param onFinish im Spiel-Thread nach dem Ende (über {@link MapDisk#runOnGameThread}; ohne Platte sofort im Export-Thread)
	 */
	public static StartResult start(MapLayer layer, String worldKey, String dimension, int[] rect, int maxSize, Path outDir,
			MapDisk disk, boolean background, Consumer<MapExport> onFinish) {
		if (running()) return StartResult.RUNNING;
		if (layer == null) return StartResult.EMPTY;
		Set<Long> all = layer.knownKeys();
		int[] bounds = rect != null ? rect : boundsOf(all);
		if (bounds == null) return StartResult.EMPTY;
		Plan plan = plan(bounds[0], bounds[1], bounds[2], bounds[3], maxSize);
		if (plan == null) return StartResult.TOO_BIG;
		int[] rb = regionBounds(plan);
		Set<Long> keys = new HashSet<Long>();
		for (long k : all) {
			int rx = MapRegion.keyX(k), rz = MapRegion.keyZ(k);
			if (rx >= rb[0] && rx <= rb[2] && rz >= rb[1] && rz <= rb[3]) keys.add(k);
		}
		if (keys.isEmpty()) return StartResult.EMPTY;
		// Was ungespeichert im Speicher liegt, ist neuer als die Platte: jetzt (Spiel-Thread) kopieren. Gespeicherte
		// Bereiche liest der Export selbst von der Platte (spart Speicher und Zeit im Spiel-Thread).
		boolean fromDisk = disk != null && layer.dir() != null;
		Map<Long, Snap> snaps = new HashMap<Long, Snap>();
		for (MapRegion r : layer.loaded()) {
			if (!keys.contains(r.key()) || (fromDisk && !r.dirty()) || !r.anyKnown()) continue;
			int[] summary = r.summary != null && r.summaryVersion == r.version ? r.summary.clone() : null;
			snaps.put(r.key(), new Snap(r.pixels.clone(), r.heights.clone(), summary));
		}
		Path file = unique(outDir, fileName(worldKey, dimension, layer, new Date()));
		final MapExport job = new MapExport(plan, keys, snaps, disk, layer.dir(), layer.caveRef, file, onFinish);
		current = job;
		if (!background) {
			job.run();
			return StartResult.STARTED;
		}
		Thread t = new Thread(job::run, "TRS-Map-Export");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		t.start();
		return StartResult.STARTED;
	}

	public State state() {
		return state;
	}

	/** Fortschritt 0..1. */
	public float progress() {
		return progress;
	}

	public Path file() {
		return file;
	}

	public String error() {
		return error;
	}

	public Plan plan() {
		return plan;
	}

	/** Dauer in ms (nach dem Ende). */
	public long millis() {
		return millis;
	}

	/** Abbrechen (die halbe Datei wird gelöscht). */
	public void cancel() {
		cancel = true;
	}

	private void run() {
		long t0 = System.currentTimeMillis();
		try {
			write();
			state = cancel ? State.CANCELLED : State.DONE;
		} catch (Exception | OutOfMemoryError e) {
			error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			state = State.FAILED;
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
		} finally {
			millis = System.currentTimeMillis() - t0;
			snaps.clear();
		}
		if (onFinish != null) {
			if (disk != null) disk.runOnGameThread(() -> onFinish.accept(this));
			else onFinish.accept(this);
		}
	}

	private void write() throws IOException, InterruptedException {
		Plan p = plan;
		int[] rb = regionBounds(p);
		int k = p.k;
		int perRegion = MapRegion.SIZE / k;
		int[] band = new int[p.width * perRegion];
		int[] composed = new int[MapRegion.AREA];
		Map<Long, MapRegion> north = new HashMap<Long, MapRegion>();
		try (MapPng png = new MapPng(file, p.width, p.height)) {
			for (int rz = rb[1]; rz <= rb[3]; rz++) {
				if (cancel) return;
				int z0 = Math.max(p.bz0, rz * MapRegion.SIZE), z1 = Math.min(p.bz1, (rz + 1) * MapRegion.SIZE);
				int outRows = (z1 - z0) / k;
				java.util.Arrays.fill(band, 0, outRows * p.width, 0);
				Map<Long, byte[]> files = read(rz, rb[0], rb[2]);
				Map<Long, MapRegion> row = new HashMap<Long, MapRegion>();
				for (int rx = rb[0]; rx <= rb[2]; rx++) {
					long key = MapRegion.key(rx, rz);
					if (!keys.contains(key)) continue;
					int x0 = Math.max(p.bx0, rx * MapRegion.SIZE), x1 = Math.min(p.bx1, (rx + 1) * MapRegion.SIZE);
					int outCols = (x1 - x0) / k;
					int outX = (x0 - p.bx0) / k;
					int lx = x0 - rx * MapRegion.SIZE, lz = z0 - rz * MapRegion.SIZE;
					if (p.summaries()) {
						int[] s = summary(key, files.get(key), composed);
						if (s == null) continue;
						int f = k / MapTextures.SUPER_BLOCKS_PER_TEXEL;
						int sx = lx / MapTextures.SUPER_BLOCKS_PER_TEXEL, sz = lz / MapTextures.SUPER_BLOCKS_PER_TEXEL;
						for (int oy = 0; oy < outRows; oy++) {
							for (int ox = 0; ox < outCols; ox++) {
								band[oy * p.width + outX + ox] = average(s, MapRegion.SUMMARY, sx + ox * f, sz + oy * f, f, f);
							}
						}
					} else {
						MapRegion r = region(key, files.get(key));
						if (r == null) continue;
						row.put(key, r);
						compose(r, north.get(MapRegion.key(rx, rz - 1)), row.get(MapRegion.key(rx - 1, rz)), composed);
						for (int oy = 0; oy < outRows; oy++) {
							for (int ox = 0; ox < outCols; ox++) {
								band[oy * p.width + outX + ox] = average(composed, MapRegion.SIZE, lx + ox * k, lz + oy * k, k, k);
							}
						}
					}
				}
				north = row;
				for (int oy = 0; oy < outRows; oy++) png.writeRow(band, oy * p.width);
				progress = (rz - rb[1] + 1) / (float) (rb[3] - rb[1] + 1);
			}
			png.finish();
		}
	}

	/** Liest die Dateien einer Bereichszeile, die nicht im Speicher lagen (im Kartenthread, nacheinander). */
	private Map<Long, byte[]> read(int rz, int rx0, int rx1) throws InterruptedException {
		Map<Long, byte[]> out = new HashMap<Long, byte[]>();
		if (disk == null || dir == null) return out;
		List<Long> want = new ArrayList<Long>();
		List<CompletableFuture<byte[]>> futures = new ArrayList<CompletableFuture<byte[]>>();
		for (int rx = rx0; rx <= rx1; rx++) {
			long key = MapRegion.key(rx, rz);
			if (!keys.contains(key) || snaps.containsKey(key)) continue;
			want.add(key);
			futures.add(disk.readRaw(MapDisk.regionFile(dir, rx, rz)));
		}
		for (int i = 0; i < want.size(); i++) {
			try {
				byte[] data = futures.get(i).get(READ_TIMEOUT_S, TimeUnit.SECONDS);
				if (data != null) out.put(want.get(i), data);
			} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
				// Bereich fehlt dann im Bild
			}
		}
		return out;
	}

	private int[] summary(long key, byte[] data, int[] scratch) {
		Snap s = snaps.get(key);
		if (s != null) {
			if (s.summary != null) return s.summary;
			MapRegion r = new MapRegion(MapRegion.keyX(key), MapRegion.keyZ(key));
			System.arraycopy(s.pixels, 0, r.pixels, 0, MapRegion.AREA);
			System.arraycopy(s.heights, 0, r.heights, 0, MapRegion.AREA);
			compose(r, null, null, scratch);
			return MapCompose.summaryOf(scratch);
		}
		if (data == null) return null;
		try {
			int[] sum = RegionCodec.readSummary(new ByteArrayInputStream(data));
			if (sum != null) return sum;
			// Alte Datei ohne Übersicht: ganz lesen.
			MapRegion r = region(key, data);
			if (r == null) return null;
			compose(r, null, null, scratch);
			return MapCompose.summaryOf(scratch);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private MapRegion region(long key, byte[] data) {
		Snap s = snaps.get(key);
		MapRegion r = new MapRegion(MapRegion.keyX(key), MapRegion.keyZ(key));
		if (s != null) {
			System.arraycopy(s.pixels, 0, r.pixels, 0, MapRegion.AREA);
			System.arraycopy(s.heights, 0, r.heights, 0, MapRegion.AREA);
			return r;
		}
		if (data == null) return null;
		try {
			RegionCodec.Decoded d = RegionCodec.decode(new ByteArrayInputStream(data));
			System.arraycopy(d.pixels, 0, r.pixels, 0, MapRegion.AREA);
			System.arraycopy(d.heights, 0, r.heights, 0, MapRegion.AREA);
			return r;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Schattiert einen Bereich mit seinen Nachbarn im Norden/Westen (Relief über die Bereichsgrenze hinweg). */
	private void compose(MapRegion r, MapRegion northOf, MapRegion westOf, int[] out) {
		MapLayer tmp = new MapLayer("export", -1, caveRef, null, null);
		if (northOf != null) copyInto(tmp, northOf);
		if (westOf != null) copyInto(tmp, westOf);
		MapCompose.compose(tmp, r, out);
	}

	private static void copyInto(MapLayer tmp, MapRegion src) {
		MapRegion dst = tmp.forWrite(src.rx, src.rz, 0);
		System.arraycopy(src.pixels, 0, dst.pixels, 0, MapRegion.AREA);
		System.arraycopy(src.heights, 0, dst.heights, 0, MapRegion.AREA);
	}
}
