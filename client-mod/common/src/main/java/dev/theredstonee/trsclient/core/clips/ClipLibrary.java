package dev.theredstonee.trsclient.core.clips;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Gespeicherte Clips (MP4 aus dem Clip-Ordner des Launchers) und Bildschirmfotos ({@code screenshots/} der
 * Instanz) für den Bildschirm „Clips &amp; Bilder“. Das Durchsuchen läuft im Hintergrund; die Oberfläche liest
 * nur die zuletzt fertige {@link Listing}. Löschen nur nach Bestätigung und nur Dateien, die in einer der
 * beiden Listen stehen.
 */
public final class ClipLibrary {
	/** Höchstens so viele Einträge je Art (die neuesten). */
	static final int MAX_ENTRIES = 400;

	public enum Type {
		CLIP, SCREENSHOT
	}

	/** Eine Datei. */
	public static final class Entry {
		public final Path path;
		public final Type type;
		public final String name;
		public final long size;
		public final long modified;
		/** Nur Clips: Dauer in ms (−1 = unbekannt). */
		public final long durationMs;

		Entry(Path path, Type type, String name, long size, long modified, long durationMs) {
			this.path = path;
			this.type = type;
			this.name = name;
			this.size = size;
			this.modified = modified;
			this.durationMs = durationMs;
		}
	}

	/** Ergebnis eines Durchlaufs. */
	public static final class Listing {
		public final List<Entry> entries;
		/** Clip-Ordner (null = unbekannt, z. B. ohne TRS Launcher). */
		public final Path clipsDir;
		public final Path screenshotsDir;
		public final boolean scanned;

		Listing(List<Entry> entries, Path clipsDir, Path screenshotsDir, boolean scanned) {
			this.entries = Collections.unmodifiableList(entries);
			this.clipsDir = clipsDir;
			this.screenshotsDir = screenshotsDir;
			this.scanned = scanned;
		}

		public int count(Type type) {
			int n = 0;
			for (Entry e : entries) if (type == null || e.type == type) n++;
			return n;
		}
	}

	private final Path configDir;
	private final Path gameDir;
	private final Executor worker;
	private volatile Listing listing;
	private volatile boolean scanning;
	private volatile boolean again;

	public ClipLibrary(Path configDir, Path gameDir, Executor worker) {
		this.configDir = configDir;
		this.gameDir = gameDir;
		this.worker = worker;
		this.listing = new Listing(new ArrayList<Entry>(), null, screenshots(gameDir), false);
	}

	private static Path screenshots(Path gameDir) {
		return gameDir == null ? null : gameDir.resolve("screenshots");
	}

	public Listing listing() {
		return listing;
	}

	public boolean scanning() {
		return scanning;
	}

	/** Neu durchsuchen (im Hintergrund; läuft schon einer, folgt direkt danach ein weiterer). */
	public void refresh() {
		if (scanning) {
			again = true;
			return;
		}
		scanning = true;
		try {
			worker.execute(new Runnable() {
				@Override
				public void run() {
					try {
						do {
							again = false;
							listing = scan();
						} while (again);
					} finally {
						scanning = false;
					}
				}
			});
		} catch (RuntimeException e) {
			scanning = false;
		}
	}

	/** Blockierend: beide Ordner lesen. */
	Listing scan() {
		Path clips = ClipConfig.clipsDir(configDir, gameDir);
		Path shots = screenshots(gameDir);
		List<Entry> all = new ArrayList<>();
		all.addAll(read(clips, Type.CLIP));
		all.addAll(read(shots, Type.SCREENSHOT));
		sort(all);
		return new Listing(all, clips, shots, true);
	}

	static void sort(List<Entry> entries) {
		Collections.sort(entries, new Comparator<Entry>() {
			@Override
			public int compare(Entry a, Entry b) {
				if (a.modified != b.modified) return a.modified > b.modified ? -1 : 1;
				return a.name.compareTo(b.name);
			}
		});
	}

	static List<Entry> read(Path dir, Type type) {
		List<Entry> out = new ArrayList<>();
		if (dir == null || !Files.isDirectory(dir)) return out;
		String ext = type == Type.CLIP ? ".mp4" : ".png";
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path p : stream) {
				String name = p.getFileName().toString();
				if (!name.toLowerCase(Locale.ROOT).endsWith(ext) || name.startsWith(".")) continue;
				BasicFileAttributes attrs;
				try {
					attrs = Files.readAttributes(p, BasicFileAttributes.class);
				} catch (IOException e) {
					continue;
				}
				if (!attrs.isRegularFile()) continue;
				out.add(new Entry(p, type, name, attrs.size(), attrs.lastModifiedTime().toMillis(), -1));
			}
		} catch (IOException | RuntimeException e) {
			return out;
		}
		sort(out);
		if (out.size() > MAX_ENTRIES) out = new ArrayList<>(out.subList(0, MAX_ENTRIES));
		if (type == Type.CLIP) {
			for (int i = 0; i < out.size(); i++) {
				Entry e = out.get(i);
				out.set(i, new Entry(e.path, e.type, e.name, e.size, e.modified, mp4DurationMs(e.path)));
			}
		}
		return out;
	}

	/** Gehört die Datei zu den angezeigten Einträgen (und liegt im richtigen Ordner)? */
	boolean known(Entry e) {
		Listing l = listing;
		if (e == null) return false;
		boolean listed = false;
		for (Entry x : l.entries) {
			if (x.type == e.type && x.path.equals(e.path)) {
				listed = true;
				break;
			}
		}
		if (!listed) return false;
		Path dir = e.type == Type.CLIP ? l.clipsDir : l.screenshotsDir;
		return dir != null && e.path.getParent() != null && e.path.getParent().equals(dir);
	}

	/** Ergebnis von {@link #delete}. */
	public interface DeleteCallback {
		void done(boolean ok);
	}

	/** Datei endgültig löschen (nur bekannte Einträge; im Hintergrund), danach neu durchsuchen. */
	public void delete(final Entry e, final DeleteCallback callback) {
		if (!known(e)) {
			callback.done(false);
			return;
		}
		try {
			worker.execute(new Runnable() {
				@Override
				public void run() {
					boolean ok;
					try {
						ok = Files.deleteIfExists(e.path);
					} catch (IOException | RuntimeException ex) {
						ok = false;
					}
					callback.done(ok);
					refresh();
				}
			});
		} catch (RuntimeException ex) {
			callback.done(false);
		}
	}

	// --- MP4-Dauer (wie der Launcher: moov/mvhd lesen, mdat überspringen) ---

	static long mp4DurationMs(Path path) {
		try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
			long len = file.length();
			long pos = 0;
			while (pos + 8 <= len) {
				file.seek(pos);
				long size = readUInt(file);
				byte[] kind = new byte[4];
				file.readFully(kind);
				long header = 8;
				if (size == 1) {
					size = file.readLong();
					header = 16;
				} else if (size == 0) {
					size = len - pos;
				}
				if (size < header || size > len - pos) return -1;
				if (kind[0] == 'm' && kind[1] == 'o' && kind[2] == 'o' && kind[3] == 'v') {
					long body = size - header;
					if (body > 16L * 1024 * 1024) return -1;
					byte[] buf = new byte[(int) body];
					file.readFully(buf);
					return mvhd(buf);
				}
				pos += size;
			}
		} catch (IOException | RuntimeException e) {
			return -1;
		}
		return -1;
	}

	private static long readUInt(RandomAccessFile f) throws IOException {
		return f.readInt() & 0xFFFFFFFFL;
	}

	static long mvhd(byte[] moov) {
		int pos = 0;
		while (pos + 8 <= moov.length) {
			long size = u32(moov, pos);
			if (size < 8 || pos + size > moov.length) return -1;
			if (moov[pos + 4] == 'm' && moov[pos + 5] == 'v' && moov[pos + 6] == 'h' && moov[pos + 7] == 'd') {
				int b = pos + 8;
				int version = moov[b] & 0xFF;
				long timescale;
				long duration;
				if (version == 1) {
					if (b + 32 > moov.length) return -1;
					timescale = u32(moov, b + 20);
					duration = (u32(moov, b + 24) << 32) | u32(moov, b + 28);
				} else {
					if (b + 20 > moov.length) return -1;
					timescale = u32(moov, b + 12);
					duration = u32(moov, b + 16);
				}
				if (timescale == 0 || duration < 0) return -1;
				return duration * 1000 / timescale;
			}
			pos += (int) size;
		}
		return -1;
	}

	private static long u32(byte[] b, int i) {
		return ((b[i] & 0xFFL) << 24) | ((b[i + 1] & 0xFFL) << 16) | ((b[i + 2] & 0xFFL) << 8) | (b[i + 3] & 0xFFL);
	}
}
