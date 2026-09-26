package dev.theredstonee.trsclient.core.hosting.share;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host-Seite des Datei-Kanals ({@link FileChannel}): liefert genau die freigegebenen Dateien (Mods „direkt vom Host“,
 * Resource Pack) an angenommene Gäste. Zugriff nur über den SHA-256 aus der Freigabeliste – nie über Pfade.
 *
 * <p>Grenzen je Gast: 2 Kanäle gleichzeitig, 60 Anfragen je 10 Minuten, insgesamt höchstens
 * {@code 3 × freigegebene Größe + 64 MB}; Tempo über das Relay 1,5 MB/s, direkt 8 MB/s je Kanal. Ein Kanal ohne Anfrage
 * schließt nach 60 s. Ändert sich eine Datei nach der Freigabe (Größe/Zeit/Hash), wird sie nicht mehr geliefert.
 */
public final class FileServer {
	static final int MAX_CHANNELS_PER_GUEST = 2;
	static final int MAX_REQUESTS = 60;
	static final long REQUEST_WINDOW_MS = 10 * 60_000L;
	static final long IDLE_MS = 60_000L;
	static final long RELAY_RATE = 1_500_000L;
	static final long DIRECT_RATE = 8_000_000L;
	static final long MAX_PENDING = 512 * 1024;

	/** Wer darf (Spiel-Zustand): angenommen, nicht gesperrt, Teilen an. */
	public interface Access {
		boolean allowed(String guestUuid);
	}

	/** Eine freigegebene Datei. */
	public static final class Entry {
		public final int kind;
		public final Path path;
		public final long size;
		public final long modified;
		public final String sha256;

		public Entry(int kind, Path path, long size, long modified, String sha256) {
			this.kind = kind;
			this.path = path;
			this.size = size;
			this.modified = modified;
			this.sha256 = sha256;
		}
	}

	private final Access access;
	private volatile Map<String, Entry> files = Collections.emptyMap();
	private volatile long sharedBytes;
	private final Map<String, Guest> guests = new ConcurrentHashMap<String, Guest>();
	private final AtomicInteger threads = new AtomicInteger();
	/** Für Tests: Tempo ohne Grenze. */
	volatile boolean unthrottled;
	/** Protokoll (nie Pfade/Hashes vollständig). */
	public volatile java.util.function.Consumer<String> log;

	public FileServer(Access access) {
		this.access = access;
	}

	private static final class Guest {
		int open;
		final ArrayDeque<Long> requests = new ArrayDeque<Long>();
		long bytes;
	}

	/** Freigabeliste ersetzen (leer = nichts teilen). Schon laufende Übertragungen prüfen beim nächsten Block neu. */
	public void share(Map<String, Entry> allow) {
		Map<String, Entry> copy = new HashMap<String, Entry>(allow);
		long total = 0;
		for (Entry e : copy.values()) total += e.size;
		files = Collections.unmodifiableMap(copy);
		sharedBytes = total;
	}

	public void stop() {
		files = Collections.emptyMap();
		sharedBytes = 0;
		guests.clear();
	}

	public boolean sharing() {
		return !files.isEmpty();
	}

	/** Datei-Kanal eines Gasts bedienen (eigener Thread). Die Magie ist schon gelesen. */
	public void serve(final PeerStream stream, final String guestUuid) {
		if (guestUuid == null || files.isEmpty() || !access.allowed(guestUuid)) {
			byte[] e = FileChannel.error("not_allowed");
			stream.write(e, 0, e.length);
			stream.close("files not allowed");
			return;
		}
		final Guest g = guest(guestUuid);
		synchronized (g) {
			if (g.open >= MAX_CHANNELS_PER_GUEST) {
				byte[] e = FileChannel.error("busy");
				stream.write(e, 0, e.length);
				stream.close("busy");
				return;
			}
			g.open++;
		}
		final FileChannel.Input in = new FileChannel.Input();
		in.timeout(IDLE_MS);
		stream.start(in);
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					loop(stream, in, guestUuid, g);
				} finally {
					synchronized (g) {
						g.open--;
					}
					stream.close("files done");
				}
			}
		}, "TRS-Dateien-" + threads.incrementAndGet());
		t.setDaemon(true);
		t.start();
	}

	private Guest guest(String uuid) {
		Guest g = guests.get(uuid);
		if (g == null) {
			Guest fresh = new Guest();
			g = guests.putIfAbsent(uuid, fresh);
			if (g == null) g = fresh;
		}
		return g;
	}

	private void loop(PeerStream stream, FileChannel.Input in, String guestUuid, Guest g) {
		try {
			while (stream.isOpen()) {
				FileChannel.Frame f = FileChannel.read(in, FileChannel.MAX_REQUEST);
				if (f.type == FileChannel.BYE) return;
				if (f.type != FileChannel.GET || f.payload.length != 33) {
					send(stream, FileChannel.error("bad_request"));
					return;
				}
				int kind = f.payload[0];
				byte[] hash = new byte[32];
				System.arraycopy(f.payload, 1, hash, 0, 32);
				String sha = ModScan.hex(hash);
				if (!access.allowed(guestUuid)) {
					send(stream, FileChannel.error("not_allowed"));
					return;
				}
				Entry e = files.get(sha);
				if (e == null || e.kind != kind) {
					send(stream, FileChannel.error("not_shared"));
					continue;
				}
				long now = System.currentTimeMillis();
				synchronized (g) {
					while (!g.requests.isEmpty() && now - g.requests.peekFirst() > REQUEST_WINDOW_MS) g.requests.pollFirst();
					if (g.requests.size() >= MAX_REQUESTS || g.bytes + e.size > 3 * sharedBytes + 64L * 1024 * 1024) {
						send(stream, FileChannel.error("rate_limited"));
						continue;
					}
					g.requests.addLast(now);
					g.bytes += e.size;
				}
				if (!transfer(stream, e, guestUuid)) return;
			}
		} catch (InterruptedIOException ex) {
			// untätig
		} catch (IOException | RuntimeException ex) {
			log("Datei-Kanal: " + ex.getClass().getSimpleName());
		}
	}

	/** Eine Datei schicken; false = Kanal beenden. */
	private boolean transfer(PeerStream stream, Entry e, String guestUuid) throws IOException {
		BasicFileAttributes a;
		try {
			a = Files.readAttributes(e.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (IOException ex) {
			return send(stream, FileChannel.error("not_found"));
		}
		if (!a.isRegularFile() || a.size() != e.size || a.lastModifiedTime().toMillis() != e.modified) {
			return send(stream, FileChannel.error("changed"));
		}
		if (!send(stream, FileChannel.frame(FileChannel.FILE, FileChannel.u64(e.size)))) return false;
		long rate = unthrottled ? Long.MAX_VALUE : stream.path() == PeerStream.Path.DIRECT ? DIRECT_RATE : RELAY_RATE;
		MessageDigest sha = ModScan.digest("SHA-256");
		long start = System.currentTimeMillis();
		long sent = 0;
		byte[] buf = new byte[FileChannel.CHUNK];
		try (InputStream in = Files.newInputStream(e.path)) {
			int n;
			while (sent < e.size && (n = in.read(buf, 0, (int) Math.min(buf.length, e.size - sent))) > 0) {
				if (!files.containsKey(e.sha256) || !access.allowed(guestUuid)) {
					// Teilen beendet oder Gast entfernt: sofort aufhören.
					send(stream, FileChannel.error("not_allowed"));
					return false;
				}
				sha.update(buf, 0, n);
				if (!send(stream, FileChannel.frame(FileChannel.DATA, buf, 0, n))) return false;
				sent += n;
				if (rate != Long.MAX_VALUE) {
					long due = start + sent * 1000L / rate;
					long wait = due - System.currentTimeMillis();
					if (wait > 0) sleep(Math.min(wait, 1000L));
				}
			}
		}
		String got = ModScan.hex(sha.digest());
		if (sent != e.size || !got.equals(e.sha256)) {
			// Datei hat sich während des Lesens geändert – der Gast verwirft sie ohnehin (eigene Prüfung).
			return send(stream, FileChannel.error("changed"));
		}
		log("Datei-Kanal: " + e.size / 1024 + " KB an Gast gesendet (" + (e.kind == FileChannel.KIND_PACK ? "Pack" : "Mod") + ")");
		return send(stream, FileChannel.frame(FileChannel.END, FileChannel.unhex(got)));
	}

	private static boolean send(PeerStream s, byte[] frame) throws InterruptedIOException {
		return FileChannel.writeBlocking(s, frame, MAX_PENDING, 30_000L);
	}

	private static void sleep(long ms) throws InterruptedIOException {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
	}

	private void log(String m) {
		java.util.function.Consumer<String> l = log;
		if (l != null) l.accept(m);
	}
}
