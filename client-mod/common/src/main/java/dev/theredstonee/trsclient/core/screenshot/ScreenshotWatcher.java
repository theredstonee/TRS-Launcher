package dev.theredstonee.trsclient.core.screenshot;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Erkennt neue Bildschirmfotos im Ordner {@code screenshots/} – unabhängig davon, wer sie aufgenommen hat (Vanilla
 * F2, Essential, andere Mods) und ob eine Chatzeile kam. Nur Dateien mit Vanilla-Namen, die nach dem Spielstart
 * entstanden sind, und erst, wenn sie fertig geschrieben sind (Größe stabil und PNG-Ende {@code IEND} vorhanden).
 *
 * <p>Kosten: je Aufruf von {@link #poll} ein {@code stat} des Ordners; gelistet wird nur, wenn sich der Ordner
 * geändert hat, ein Kandidat wartet oder {@link #FULL_SCAN_MS} vergangen sind. Nicht threadsicher – ein Thread ruft
 * {@link #poll}, andere nur {@link #announce} (synchronisiert).
 */
public final class ScreenshotWatcher {
	static final long FULL_SCAN_MS = 10_000L;
	/** So lange darf eine Datei „älter“ als der Spielstart sein (Uhr-/Dateisystem-Ungenauigkeit). */
	static final long START_SLACK_MS = 2_000L;
	/** Unfertige Kandidaten gibt der Wächter nach dieser Zeit auf. */
	static final long GIVE_UP_MS = 20_000L;

	private final Path dir;
	private final long startMillis;
	private final Set<String> known = new HashSet<>();
	/** Name → zuletzt gesehene Größe (−1 = noch nie). */
	private final Map<String, long[]> pending = new LinkedHashMap<>();
	private long lastDirModified = Long.MIN_VALUE;
	private long lastFullScan;
	private boolean seeded;

	public ScreenshotWatcher(Path dir, long startMillis) {
		this.dir = dir;
		this.startMillis = startMillis;
	}

	public Path dir() {
		return dir;
	}

	/** Schon gemeldet (oder beim Start vorhanden)? Markiert den Namen als bekannt; true = war neu. */
	public synchronized boolean announce(String name) {
		if (name == null) return false;
		pending.remove(name);
		return known.add(name);
	}

	private synchronized boolean isKnown(String name) {
		return known.contains(name);
	}

	/** Neue, fertige Bildschirmfotos seit dem letzten Aufruf (bereits als bekannt markiert). */
	public List<Path> poll(long now) {
		List<Path> out = new ArrayList<>();
		if (dir == null) return out;
		try {
			if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
				seeded = true;
				return out;
			}
			long mod = Files.getLastModifiedTime(dir).toMillis();
			boolean changed = mod != lastDirModified;
			boolean list = !seeded || changed || !pending.isEmpty() || now - lastFullScan >= FULL_SCAN_MS;
			if (!list) return out;
			lastDirModified = mod;
			lastFullScan = now;
			scan(now, out);
			seeded = true;
		} catch (IOException | RuntimeException e) {
			// Ordner gerade nicht lesbar – beim nächsten Mal wieder.
		}
		return out;
	}

	private void scan(long now, List<Path> out) throws IOException {
		Set<String> seen = new HashSet<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path p : stream) {
				String name = p.getFileName().toString();
				if (!ScreenshotNames.vanilla(name) || isKnown(name)) continue;
				seen.add(name);
				BasicFileAttributes a;
				try {
					a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				} catch (IOException e) {
					continue;
				}
				if (!a.isRegularFile()) {
					announce(name);
					continue;
				}
				long created = Math.max(a.lastModifiedTime().toMillis(), a.creationTime().toMillis());
				if (!seeded || created < startMillis - START_SLACK_MS) {
					// Alte Datei (vor dem Spielstart) – nie melden.
					announce(name);
					continue;
				}
				long[] state = pending.get(name);
				if (state == null) {
					pending.put(name, new long[]{a.size(), now});
					continue;
				}
				boolean stable = a.size() > 0 && a.size() == state[0];
				state[0] = a.size();
				if (stable && complete(p, a.size())) {
					if (announce(name)) out.add(p);
				} else if (now - state[1] > GIVE_UP_MS) {
					announce(name);
				}
			}
		}
		// Verschwundene Kandidaten vergessen.
		pending.keySet().retainAll(seen);
	}

	/** Endet die Datei mit dem PNG-Block {@code IEND}? (Minecraft schreibt direkt in die Zieldatei.) */
	static boolean complete(Path p, long size) {
		if (size < 20) return false;
		try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
			byte[] tail = new byte[12];
			f.seek(size - 12);
			f.readFully(tail);
			return tail[4] == 'I' && tail[5] == 'E' && tail[6] == 'N' && tail[7] == 'D';
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}
}
