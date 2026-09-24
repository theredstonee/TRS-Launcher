package dev.theredstonee.trsclient.core.clips;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Verbindung zum TRS Launcher (nur 127.0.0.1, Port + Einmal-Token aus {@link ClipConfig}).
 *
 * <p>Ein Daemon-Thread verbindet sich, meldet sich mit dem Token an und liest Statuszeilen; das Spiel
 * liest nur {@link #status()} und {@link #pollNotice()} und schickt Tastendrücke über {@link #press}.
 * Ohne Launcher-Datei passiert nichts außer einem Hinweis beim Tastendruck.
 */
public final class ClipLink {
	/** Befehle an den Launcher. */
	public static final String CLIP = "clip";
	public static final String RECORD = "record";

	private static final int MAX_LINE = 2048;
	private static final int CONNECT_TIMEOUT_MS = 2000;
	/** Der Launcher schickt alle 5 s eine Statuszeile – bleibt sie länger aus, ist er weg. */
	private static final int READ_TIMEOUT_MS = 15000;
	/** Ein vorgemerkter Tastendruck verfällt, wenn die Verbindung nicht zustande kommt. */
	private static final long PENDING_MS = 4000;
	/** Diagnose nur im Autotest (stdout landet im Spiel-Log). */
	private static final boolean DEBUG = Boolean.getBoolean("trsclient.autotest");

	private static void debug(String message) {
		if (DEBUG) System.out.println("[TRS Clips] " + message);
	}

	private final Path configDir;
	private final Object wake = new Object();
	private final ConcurrentLinkedQueue<ClipNotice> notices = new ConcurrentLinkedQueue<ClipNotice>();
	private volatile ClipStatus status = ClipStatus.OFFLINE;
	private volatile ClipConfig config = ClipConfig.MISSING;
	private volatile OutputStream out;
	private volatile String pending;
	private volatile long pendingSince;
	private volatile boolean running;
	private Thread thread;

	public ClipLink(Path configDir) {
		this.configDir = configDir;
	}

	/** Startet den Netz-Thread (einmalig). */
	public synchronized void start() {
		if (thread != null) return;
		running = true;
		config = ClipConfig.load(configDir);
		thread = new Thread(new Runnable() {
			@Override
			public void run() {
				loop();
			}
		}, "TRS-Clips");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		thread.start();
	}

	public synchronized void stop() {
		running = false;
		closeQuietly();
		synchronized (wake) {
			wake.notifyAll();
		}
	}

	public ClipStatus status() {
		return status;
	}

	/** Nächste Meldung für den Spieler oder null (Spiel-Thread). */
	public ClipNotice pollNotice() {
		return notices.poll();
	}

	/**
	 * Tastendruck (Spiel-Thread). Verbunden → sofort senden; sonst Datei neu lesen und je nach Lage
	 * vormerken (Verbindung wird aufgebaut) oder einen Hinweis zeigen.
	 */
	public void press(String command) {
		OutputStream o = out;
		debug("Taste " + command + ": verbunden=" + status.connected + " out=" + (o != null));
		if (o != null && status.connected && send(o, "{\"type\":\"" + command + "\"}")) return;
		ClipConfig fresh = ClipConfig.load(configDir);
		config = fresh;
		if (fresh.kind == ClipConfig.Kind.MISSING) {
			notices.add(ClipNotice.hint("noLauncher"));
			return;
		}
		if (fresh.kind == ClipConfig.Kind.DISABLED) {
			notices.add(ClipNotice.hint("disabled"));
			return;
		}
		pending = command;
		pendingSince = System.currentTimeMillis();
		synchronized (wake) {
			wake.notifyAll();
		}
	}

	/** Spiel-Thread: abgelaufene Vormerkung → Hinweis "Launcher nicht erreichbar". */
	public void tick(long now) {
		String p = pending;
		if (p != null && now - pendingSince > PENDING_MS) {
			pending = null;
			notices.add(ClipNotice.hint("unreachable"));
		}
	}

	private boolean send(OutputStream o, String line) {
		try {
			byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
			synchronized (this) {
				o.write(bytes);
				o.flush();
			}
			return true;
		} catch (IOException e) {
			debug("Senden fehlgeschlagen: " + e);
			closeQuietly();
			return false;
		}
	}

	private volatile Socket socket;

	private void closeQuietly() {
		Socket s = socket;
		socket = null;
		out = null;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
		}
	}

	private void sleep(long ms) {
		synchronized (wake) {
			if (!running) return;
			try {
				wake.wait(ms);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				running = false;
			}
		}
	}

	private void loop() {
		int failures = 0;
		long deniedModified = -1;
		while (running) {
			ClipConfig cfg = ClipConfig.load(configDir);
			config = cfg;
			if (cfg.kind != ClipConfig.Kind.ENABLED) {
				status = ClipStatus.OFFLINE;
				sleep(cfg.kind == ClipConfig.Kind.MISSING ? 10000 : 5000);
				continue;
			}
			// Abgewiesen (altes Token)? Erst wieder versuchen, wenn der Launcher die Datei neu schreibt.
			if (deniedModified == cfg.modified) {
				status = ClipStatus.OFFLINE;
				sleep(3000);
				continue;
			}
			Session result = session(cfg);
			debug("Verbindung beendet: " + result);
			status = ClipStatus.OFFLINE;
			if (result == Session.DENIED) {
				deniedModified = cfg.modified;
				failures = 0;
				continue;
			}
			failures = result == Session.OK ? 0 : Math.min(failures + 1, 4);
			long[] backoff = {1000, 2000, 5000, 10000, 10000};
			sleep(backoff[failures]);
		}
		closeQuietly();
	}

	private enum Session {
		/** Verbindung lief und wurde beendet (Spiel/Launcher). */
		OK,
		FAILED,
		/** Token abgelehnt. */
		DENIED
	}

	/** DTO einer Zeile vom Launcher (Gson 2.2.4-tauglich). */
	static final class Line {
		String type;
		Boolean available;
		String reason;
		Boolean buffer;
		Boolean recording;
		Long recordingMs;
		Integer clipSeconds;
		String kind;
		Integer seconds;
		String code;
	}

	private Session session(ClipConfig cfg) {
		Socket s = new Socket();
		boolean greeted = false;
		try {
			s.setTcpNoDelay(true);
			s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), cfg.port), CONNECT_TIMEOUT_MS);
			s.setSoTimeout(READ_TIMEOUT_MS);
			socket = s;
			OutputStream o = s.getOutputStream();
			if (!send(o, "{\"type\":\"hello\",\"v\":1,\"token\":\"" + cfg.token + "\"}")) return Session.FAILED;
			InputStream in = new java.io.BufferedInputStream(s.getInputStream());
			Gson gson = new Gson();
			boolean wasRecording = false;
			while (running) {
				String raw = readLine(in);
				if (raw == null) return greeted ? Session.OK : Session.FAILED;
				Line line;
				try {
					line = gson.fromJson(raw, Line.class);
				} catch (RuntimeException e) {
					continue;
				}
				if (line == null || line.type == null) continue;
				if ("denied".equals(line.type)) return Session.DENIED;
				if ("state".equals(line.type)) {
					boolean recording = Boolean.TRUE.equals(line.recording);
					long since = recording ? System.currentTimeMillis() - clampMs(line.recordingMs) : 0;
					status = new ClipStatus(true, Boolean.TRUE.equals(line.available), line.reason,
							Boolean.TRUE.equals(line.buffer), recording, since,
							line.clipSeconds == null ? 0 : Math.max(0, Math.min(3600, line.clipSeconds)));
					if (recording && !wasRecording && greeted) notices.add(new ClipNotice(ClipNotice.Type.RECORDING_STARTED, 0, null));
					wasRecording = recording;
					if (!greeted) {
						greeted = true;
						out = o;
						String p = pending;
						pending = null;
						if (p != null && System.currentTimeMillis() - pendingSince <= PENDING_MS) {
							send(o, "{\"type\":\"" + p + "\"}");
						}
					}
				} else if ("saved".equals(line.type)) {
					int seconds = line.seconds == null ? 0 : Math.max(0, line.seconds);
					notices.add(new ClipNotice("recording".equals(line.kind) ? ClipNotice.Type.RECORDING_SAVED
							: ClipNotice.Type.CLIP_SAVED, seconds, null));
				} else if ("failed".equals(line.type)) {
					notices.add(new ClipNotice(ClipNotice.Type.FAILED, 0, safeCode(line.code)));
				}
			}
			return Session.OK;
		} catch (SocketTimeoutException e) {
			return greeted ? Session.OK : Session.FAILED;
		} catch (IOException | RuntimeException e) {
			return greeted ? Session.OK : Session.FAILED;
		} finally {
			out = null;
			if (socket == s) socket = null;
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
		}
	}

	private static long clampMs(Long ms) {
		if (ms == null) return 0;
		return Math.max(0, Math.min(ms, 24L * 3600 * 1000));
	}

	/** Nur bekannte Fehlercodes durchlassen (sonst "error"). */
	static String safeCode(String code) {
		if (code == null) return "error";
		String[] known = {"disabled", "starting", "noWindow", "ffmpeg", "noFrames", "busy", "error"};
		for (String k : known) {
			if (k.equals(code)) return k;
		}
		return "error";
	}

	/** Eine Zeile (UTF-8) lesen; zu lang → Verbindung beenden. */
	static String readLine(InputStream in) throws IOException {
		byte[] buf = new byte[256];
		int n = 0;
		while (true) {
			int b = in.read();
			if (b < 0) return null;
			if (b == '\n') return new String(buf, 0, n, StandardCharsets.UTF_8);
			if (n >= MAX_LINE) throw new IOException("Zeile zu lang");
			if (n == buf.length) {
				byte[] bigger = new byte[buf.length * 2];
				System.arraycopy(buf, 0, bigger, 0, n);
				buf = bigger;
			}
			buf[n++] = (byte) b;
		}
	}
}
