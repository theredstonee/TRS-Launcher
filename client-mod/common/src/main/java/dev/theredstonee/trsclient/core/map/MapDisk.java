package dev.theredstonee.trsclient.core.map;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Kartenspeicher auf der Platte: {@code config/trsclient/maps/<welt>/<dimension>/<ebene>/r.<x>.<z>.trsm}.
 * Lesen/Schreiben läuft im Hintergrund-Thread „TRS-Map“; Ergebnisse kommen über {@link #drain()} zurück in den
 * Spiel-Thread. Die Gesamtgröße wird begrenzt – die am längsten nicht mehr geänderten Dateien fliegen zuerst.
 * Nichts davon verlässt den Rechner.
 */
public final class MapDisk {
	private static final String SUFFIX = ".trsm";

	private final Path root;
	private final ThreadPoolExecutor worker;
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<Runnable>();
	private final boolean synchronous;

	/**
	 * @param root Wurzel ({@code config/trsclient/maps})
	 * @param synchronous true = alles sofort im aufrufenden Thread (Tests)
	 */
	public MapDisk(Path root, boolean synchronous) {
		this.root = root;
		this.synchronous = synchronous;
		if (synchronous) {
			worker = null;
		} else {
			worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
				Thread t = new Thread(r, "TRS-Map");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY + 1);
				return t;
			});
			worker.allowCoreThreadTimeOut(true);
		}
	}

	public Path root() {
		return root;
	}

	/** Ordner einer Ebene (Welt/Server + Dimension + Ebene). */
	public Path layerDir(String worldKey, String dimension, String layer) {
		return root.resolve(safeName(worldKey)).resolve(safeName(dimension)).resolve(safeName(layer));
	}

	/**
	 * Dateiname aus beliebigem Text: nur {@code [a-z0-9._-]}, höchstens 40 Zeichen, dazu ein kurzer Prüfwert des
	 * Originals (verschiedene Server mit ähnlichem Namen kollidieren nicht).
	 */
	public static String safeName(String raw) {
		if (raw == null || raw.isEmpty()) raw = "unknown";
		StringBuilder sb = new StringBuilder();
		String lower = raw.toLowerCase(Locale.ROOT);
		for (int i = 0; i < lower.length() && sb.length() < 40; i++) {
			char c = lower.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
			sb.append(ok ? c : '_');
		}
		String name = sb.toString();
		if (name.startsWith(".")) name = "_" + name.substring(1);
		return name + "-" + shortHash(raw);
	}

	static String shortHash(String raw) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 4; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i] & 0xFF));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(raw.hashCode());
		}
	}

	public static Path regionFile(Path dir, int rx, int rz) {
		return dir.resolve("r." + rx + "." + rz + SUFFIX);
	}

	/** Bereichsschlüssel aus einem Dateinamen, {@link Long#MIN_VALUE} wenn kein Kartenbereich. */
	public static long parseRegionFile(String name) {
		if (!name.startsWith("r.") || !name.endsWith(SUFFIX)) return Long.MIN_VALUE;
		String mid = name.substring(2, name.length() - SUFFIX.length());
		int dot = mid.indexOf('.');
		if (dot <= 0 || dot == mid.length() - 1) return Long.MIN_VALUE;
		try {
			int rx = Integer.parseInt(mid.substring(0, dot));
			int rz = Integer.parseInt(mid.substring(dot + 1));
			return MapRegion.key(rx, rz);
		} catch (NumberFormatException e) {
			return Long.MIN_VALUE;
		}
	}

	/** Alle gespeicherten Bereiche einer Ebene (Schlüssel). */
	public void listRegions(final Path dir, final Consumer<long[]> done) {
		submit(() -> {
			List<Long> keys = new ArrayList<Long>();
			if (Files.isDirectory(dir)) {
				try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "r.*" + SUFFIX)) {
					for (Path f : files) {
						long k = parseRegionFile(f.getFileName().toString());
						if (k != Long.MIN_VALUE) keys.add(k);
					}
				} catch (IOException e) {
					// leer lassen
				}
			}
			final long[] out = new long[keys.size()];
			for (int i = 0; i < out.length; i++) out[i] = keys.get(i);
			post(() -> done.accept(out));
		});
	}

	/** Lädt einen Bereich vollständig (null = fehlt/beschädigt). */
	public void loadRegion(final Path file, final Consumer<RegionCodec.Decoded> done) {
		submit(() -> {
			RegionCodec.Decoded d = null;
			if (Files.isRegularFile(file)) {
				try (InputStream in = Files.newInputStream(file)) {
					d = RegionCodec.decode(in);
				} catch (IOException | RuntimeException e) {
					d = null;
				}
			}
			final RegionCodec.Decoded result = d;
			post(() -> done.accept(result));
		});
	}

	/** Lädt nur die Übersicht eines Bereichs (null = fehlt). */
	public void loadSummary(final Path file, final Consumer<int[]> done) {
		submit(() -> {
			int[] s = null;
			if (Files.isRegularFile(file)) {
				try (InputStream in = Files.newInputStream(file)) {
					s = RegionCodec.readSummary(in);
				} catch (IOException | RuntimeException e) {
					s = null;
				}
			}
			final int[] result = s;
			post(() -> done.accept(result));
		});
	}

	/** Speichert einen Bereich (die Arrays müssen Kopien sein – sie werden im Hintergrund gelesen). */
	public void save(final Path file, final int rx, final int rz, final int[] pixels, final short[] heights, final int[] summary) {
		submit(() -> {
			try {
				byte[] data = RegionCodec.encode(rx, rz, pixels, heights, summary);
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				Files.write(tmp, data);
				try {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException | RuntimeException e) {
				// nächster Versuch beim nächsten Speichern
			}
		});
	}

	/**
	 * Hält die Gesamtgröße unter {@code maxBytes}: löscht die am längsten nicht geänderten Bereiche (nicht die in
	 * {@code keep} – die aktuell offenen Ebenen), bis 90 % der Grenze erreicht sind.
	 */
	public void enforceLimit(final long maxBytes, final Collection<Path> keep) {
		final List<Path> protectedDirs = new ArrayList<Path>(keep);
		submit(() -> trim(root, maxBytes, protectedDirs));
	}

	/** Größe aller Kartendateien (Bytes, blockiert – nur Hintergrund/Tests). */
	static long totalSize(Path root) {
		long total = 0;
		for (FileInfo f : scan(root)) total += f.size;
		return total;
	}

	static int trim(Path root, long maxBytes, List<Path> keep) {
		List<FileInfo> files = scan(root);
		long total = 0;
		for (FileInfo f : files) total += f.size;
		if (total <= maxBytes) return 0;
		Collections.sort(files, new Comparator<FileInfo>() {
			@Override
			public int compare(FileInfo a, FileInfo b) {
				return Long.compare(a.modified, b.modified);
			}
		});
		long target = maxBytes / 10 * 9;
		int deleted = 0;
		for (FileInfo f : files) {
			if (total <= target) break;
			if (isInside(f.path, keep)) continue;
			try {
				Files.deleteIfExists(f.path);
				total -= f.size;
				deleted++;
			} catch (IOException e) {
				// weiter mit der nächsten
			}
		}
		return deleted;
	}

	private static boolean isInside(Path file, List<Path> dirs) {
		for (Path d : dirs) {
			if (d != null && file.getParent() != null && file.getParent().equals(d)) return true;
		}
		return false;
	}

	private static final class FileInfo {
		final Path path;
		final long size;
		final long modified;

		FileInfo(Path path, long size, long modified) {
			this.path = path;
			this.size = size;
			this.modified = modified;
		}
	}

	private static List<FileInfo> scan(Path root) {
		final List<FileInfo> out = new ArrayList<FileInfo>();
		if (!Files.isDirectory(root)) return out;
		try (Stream<Path> walk = Files.walk(root, 4)) {
			walk.forEach(p -> {
				if (!p.getFileName().toString().endsWith(SUFFIX)) return;
				try {
					BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
					if (a.isRegularFile()) out.add(new FileInfo(p, a.size(), a.lastModifiedTime().toMillis()));
				} catch (IOException e) {
					// überspringen
				}
			});
		} catch (IOException | RuntimeException e) {
			// was gefunden wurde, reicht
		}
		return out;
	}

	private void submit(Runnable task) {
		if (synchronous) {
			task.run();
			return;
		}
		try {
			worker.execute(task);
		} catch (RuntimeException e) {
			// abgelehnt (Beenden) – dann eben nicht
		}
	}

	private void post(Runnable result) {
		if (synchronous) {
			result.run();
			return;
		}
		results.add(result);
	}

	/** Ergebnisse im Spiel-Thread ausführen (je Tick/Bild aufrufen). */
	public int drain() {
		int n = 0;
		Runnable r;
		while (n < 64 && (r = results.poll()) != null) {
			try {
				r.run();
			} catch (RuntimeException e) {
				// ein kaputtes Ergebnis darf die Karte nicht anhalten
			}
			n++;
		}
		return n;
	}

	/** Anzahl wartender Hintergrund-Aufträge. */
	public int pending() {
		return worker == null ? 0 : worker.getQueue().size() + worker.getActiveCount();
	}

	/** Beim Beenden: kurz warten, bis alles geschrieben ist. */
	public void flush(long timeoutMs) {
		if (worker == null) return;
		long end = System.currentTimeMillis() + timeoutMs;
		while (pending() > 0 && System.currentTimeMillis() < end) {
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
