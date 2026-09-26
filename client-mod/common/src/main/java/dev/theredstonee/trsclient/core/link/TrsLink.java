package dev.theredstonee.trsclient.core.link;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Die gesicherte Verbindung zum TRS Launcher („TRS Link“): nur 127.0.0.1, ein Daemon-Thread.
 *
 * <p>Protokoll 2 (siehe {@link LinkTarget}, {@link LinkCrypto}): {@code hello} mit Zufallswert →
 * {@code challenge} mit Beweis des Launchers (wird geprüft, bevor irgendetwas anderes gesendet wird) →
 * {@code auth} mit Beweis des Spiels. Danach Statuszeilen der Clips, Tastendrücke und Anfragen
 * ({@code req}/{@code res}) für Konten. Protokoll 1 (alte Launcher): {@code hello} mit Token aus clips.json,
 * nur Clips.
 *
 * <p>Das Spiel liest nur {@link #status()}; Zeilen gehen an {@link Listener} (im Netz-Thread).
 */
public final class TrsLink {
	/** Größte Zeile vom Launcher (Kontenliste, versiegeltes Token). */
	static final int MAX_LINE = 64 * 1024;
	private static final int CONNECT_TIMEOUT_MS = 2000;
	/** Warten auf Zeilen in kleinen Schritten, damit Anfragen pünktlich ablaufen. */
	private static final int POLL_MS = 1000;
	/** Der Launcher schickt alle 5 s eine Statuszeile – bleibt sie länger aus, ist er weg. */
	private static final long IDLE_LIMIT_MS = 15000;
	private static final long HANDSHAKE_MS = 5000;
	private static final boolean DEBUG = Boolean.getBoolean("trsclient.autotest");

	private static void debug(String message) {
		if (DEBUG) System.out.println("[TRS Link] " + message);
	}

	/** Empfänger der Zeilen (Netz-Thread – schnell bleiben, nie blockieren). */
	public interface Listener {
		/** Angemeldet; {@code accounts} = der Launcher kann Konten liefern (Protokoll 2). */
		void onConnected(TrsLink link, boolean accounts);

		/** Eine Zeile nach der Anmeldung (außer Antworten auf Anfragen). */
		void onLine(TrsLink link, Line line);

		void onDisconnected(TrsLink link);
	}

	/** Rückruf einer Anfrage (Netz-Thread). */
	public interface Callback {
		void done(Line response);

		/** Fehlercode ({@code offline}, {@code timeout} oder der des Launchers). */
		void failed(String code);
	}

	/** Zustand für das Spiel. */
	public static final class Status {
		public final boolean connected;
		public final int protocol;
		public final boolean accounts;
		/** Der Launcher kann Clips auf Wunsch des Spiels einschalten ({@code clips.enable}, ab Launcher 0.6.1). */
		public final boolean clipsEnable;
		/** Alle Merkmale aus dem {@code challenge} des Launchers (leer bei Protokoll 1). */
		private final java.util.Set<String> features;

		Status(boolean connected, int protocol, boolean accounts) {
			this(connected, protocol, accounts, false, null);
		}

		Status(boolean connected, int protocol, boolean accounts, boolean clipsEnable) {
			this(connected, protocol, accounts, clipsEnable, null);
		}

		Status(boolean connected, int protocol, boolean accounts, boolean clipsEnable, java.util.Collection<String> features) {
			this.connected = connected;
			this.protocol = protocol;
			this.accounts = accounts;
			this.clipsEnable = clipsEnable;
			this.features = features == null ? Collections.<String>emptySet()
					: Collections.unmodifiableSet(new java.util.HashSet<String>(features));
		}

		/** Kann der verbundene Launcher das ({@link #FEATURE_CLIPS_PREVIEW} …)? */
		public boolean has(String feature) {
			return connected && protocol >= 2 && features.contains(feature);
		}
	}

	/** Merkmal des Launchers: Clips per Anfrage einschalten. */
	/** Launcher → Spiel: Beitritt zu einer gehosteten Welt (docs/hosting-link.md). */
	public static final String FEATURE_HOSTING_JOIN = "hosting.join";
	/** Was DIESES Spiel kann – geht in der {@code auth}-Zeile mit (Launcher schickt sonst keinen Welt-Beitritt). */
	static final String GAME_FEATURES_JSON = "[\"" + FEATURE_HOSTING_JOIN + "\"]";

	public static final String FEATURE_CLIPS_ENABLE = "clips.enable";
	/** Merkmal des Launchers: kleine Vorschau-Animation eines Clips ({@code clips.preview}). */
	public static final String FEATURE_CLIPS_PREVIEW = "clips.preview";
	/** Merkmal des Launchers: Clip im Player des Launchers öffnen ({@code clips.open}). */
	public static final String FEATURE_CLIPS_OPEN = "clips.open";

	static final Status OFFLINE = new Status(false, 0, false);

	/** DTO einer Zeile vom Launcher (Gson 2.2.4-tauglich: nur Felder, geboxte Typen). */
	public static final class Line {
		public String type;
		// Anmeldung
		public String nonce;
		public String proof;
		public List<String> features;
		// Clips
		public Boolean available;
		public String reason;
		public Boolean buffer;
		public Boolean recording;
		public Long recordingMs;
		public Integer clipSeconds;
		/** Download-Fortschritt von FFmpeg in Prozent (nur bei {@code reason = "ffmpeg"}). */
		public Integer progress;
		/** Würde/wird Systemton aufgenommen? (null = unbekannt, älterer Launcher) */
		public Boolean audio;
		/** Würde/wird das Mikrofon aufgenommen? */
		public Boolean mic;
		public String kind;
		public Integer seconds;
		public String code;
		// Anfragen
		public Long id;
		public Boolean ok;
		public String error;
		public List<AccountDto> accounts;
		public AccountDto account;
		public SessionDto session;
		/** Antwort auf {@code clips.preview}. */
		public PreviewDto preview;
		/** Push {@code hostingJoin} / Antwort auf {@code hosting.join}: gehostete Welt beitreten (oder null). */
		public JoinDto join;
	}

	/** Vorschau-Leiste eines Clips: PNG-Raster, das der Launcher in seinem Cache ablegt. */
	/** Welt-Beitritt vom Launcher (docs/hosting-link.md §2) – ungeprüft, der Empfänger prüft jedes Feld. */
	public static final class JoinDto {
		public String roomId;
		public String code;
		public String name;
		public UserDto host;
		public String mcVersion;
		public String loader;
	}

	public static final class UserDto {
		public String uuid;
		public String name;
	}

	public static final class PreviewDto {
		public String path;
		public Integer frames;
		public Integer cols;
		public Integer rows;
		public Integer frameWidth;
		public Integer frameHeight;
		public Integer intervalMs;
		public Long durationMs;
	}

	public static final class AccountDto {
		public String id;
		public String name;
		public String skinUrl;
		public Boolean active;
	}

	public static final class SessionDto {
		public String id;
		public String name;
		public String xuid;
		public String token;
	}

	private final Path configDir;
	private final LinkTarget env;
	private final Object wake = new Object();
	private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
	private final Map<Long, Pending> pending = new ConcurrentHashMap<Long, Pending>();
	private final AtomicInteger nextId = new AtomicInteger(1);
	private volatile Status status = OFFLINE;
	private volatile OutputStream out;
	private volatile Socket socket;
	private volatile byte[] sealKey;
	private volatile boolean running;
	private volatile LinkTarget lastTarget = LinkTarget.MISSING;
	private Thread thread;

	private static final class Pending {
		final Callback callback;
		final long deadline;

		Pending(Callback callback, long deadline) {
			this.callback = callback;
			this.deadline = deadline;
		}
	}

	/**
	 * @param envValue Wert von {@code TRS_CLIENT_LINK} (null = keiner)
	 */
	public TrsLink(Path configDir, String envValue) {
		this.configDir = configDir;
		this.env = LinkTarget.parseEnv(envValue);
	}

	private static volatile TrsLink shared;

	/** Die gemeinsame Verbindung des Spiels (Clips + Konten); beim Start einmal anlegen. */
	public static synchronized TrsLink initShared(Path configDir) {
		if (shared == null) {
			String value = null;
			try {
				value = System.getenv(LinkTarget.ENV);
			} catch (SecurityException ignored) {
				// ohne Umgebung: nur Protokoll 1
			}
			shared = new TrsLink(configDir, value);
			shared.start();
		}
		return shared;
	}

	/** Die gemeinsame Verbindung oder null (vor {@link #initShared}). */
	public static TrsLink shared() {
		return shared;
	}

	/** Nur für Tests. */
	public static synchronized void setShared(TrsLink link) {
		shared = link;
	}

	/** Hat der Launcher dieses Spiel mit Protokoll 2 gestartet (Konten möglich)? */
	public boolean launchedByLauncher() {
		return env != null;
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
		if (status.connected) listener.onConnected(this, status.accounts);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	public Status status() {
		return status;
	}

	/** Aktuelles Ziel laut Umgebung/Datei (liest die Datei neu). */
	public LinkTarget target() {
		return LinkTarget.resolve(env, configDir, false);
	}

	/** Startet den Netz-Thread (einmalig). */
	public synchronized void start() {
		if (thread != null) return;
		running = true;
		thread = new Thread(new Runnable() {
			@Override
			public void run() {
				loop();
			}
		}, "TRS-Link");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		thread.start();
	}

	public synchronized void stop() {
		running = false;
		closeQuietly();
		wakeUp();
	}

	/** Sofort (neu) verbinden, statt die Wartezeit abzuwarten. */
	public void wakeUp() {
		synchronized (wake) {
			wake.notifyAll();
		}
	}

	/** Zeile senden (ohne Zeilenumbruch); false = nicht verbunden oder Fehler. */
	public boolean send(String json) {
		OutputStream o = out;
		return o != null && status.connected && write(o, json);
	}

	/**
	 * Anfrage an den Launcher (nur Protokoll 2). Der Rückruf kommt genau einmal im Netz-Thread – bzw. sofort,
	 * wenn nicht verbunden.
	 */
	public void request(String op, Map<String, String> args, long timeoutMs, Callback callback) {
		Status s = status;
		if (!s.connected || s.protocol < 2) {
			callback.failed("offline");
			return;
		}
		long id = nextId.getAndIncrement() & 0x7FFFFFFFL;
		if (id == 0) id = nextId.getAndIncrement();
		JsonObject o = new JsonObject();
		o.add("type", new JsonPrimitive("req"));
		o.add("id", new JsonPrimitive(id));
		o.add("op", new JsonPrimitive(op));
		if (args != null) {
			for (Map.Entry<String, String> e : args.entrySet()) o.add(e.getKey(), new JsonPrimitive(e.getValue()));
		}
		pending.put(id, new Pending(callback, System.currentTimeMillis() + timeoutMs));
		if (!send(o.toString())) {
			Pending p = pending.remove(id);
			if (p != null) p.callback.failed("offline");
		}
	}

	/** Entsiegelt ein Token dieser Verbindung (null = ungültig oder keine Protokoll-2-Verbindung). */
	public byte[] unseal(String sealed) {
		byte[] key = sealKey;
		return key == null ? null : LinkCrypto.unseal(key, sealed);
	}

	private boolean write(OutputStream o, String line) {
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

	private enum Result {
		/** Verbindung lief und wurde beendet (Spiel/Launcher). */
		OK,
		FAILED,
		/** Port nicht erreichbar (Launcher neu gestartet → anderer Port). */
		REFUSED,
		/** Abgelehnt (altes Token, unbekannte Sitzung, Beweis falsch). */
		DENIED
	}

	private void loop() {
		int failures = 0;
		long deniedModified = -1;
		boolean preferFile = false;
		while (running) {
			LinkTarget target = LinkTarget.resolve(env, configDir, preferFile);
			lastTarget = target;
			if (target.kind == LinkTarget.Kind.MISSING || target.kind == LinkTarget.Kind.DISABLED) {
				setStatus(OFFLINE);
				sleep(target.kind == LinkTarget.Kind.MISSING ? 10000 : 5000);
				continue;
			}
			// Abgewiesen? Erst wieder versuchen, wenn der Launcher die Datei neu schreibt (neues Token/neuer Port).
			if (deniedModified == target.modified) {
				setStatus(OFFLINE);
				sleep(target.kind == LinkTarget.Kind.V2 ? 15000 : 3000);
				if (target.kind == LinkTarget.Kind.V2) deniedModified = -1;
				continue;
			}
			Result result = session(target);
			debug("Verbindung beendet: " + result);
			setStatus(OFFLINE);
			failPending("offline");
			if (result == Result.DENIED) {
				deniedModified = target.modified;
				failures = 0;
				continue;
			}
			// Port aus der Umgebung geht nicht (Launcher neu gestartet)? Dann abwechselnd den aus clips.json.
			if (target.kind == LinkTarget.Kind.V2 && result != Result.OK) preferFile = !preferFile;
			failures = result == Result.OK ? 0 : Math.min(failures + 1, 4);
			long[] backoff = {1000, 2000, 5000, 10000, 10000};
			sleep(backoff[failures]);
		}
		closeQuietly();
	}

	private void setStatus(Status next) {
		Status before = status;
		status = next;
		if (before.connected && !next.connected) {
			for (Listener l : listeners) {
				try {
					l.onDisconnected(this);
				} catch (RuntimeException ignored) {
					// ein Empfänger darf die Verbindung nicht stören
				}
			}
		} else if (!before.connected && next.connected) {
			for (Listener l : listeners) {
				try {
					l.onConnected(this, next.accounts);
				} catch (RuntimeException ignored) {
					// dito
				}
			}
		}
	}

	private void failPending(String code) {
		if (pending.isEmpty()) return;
		List<Pending> failed = new ArrayList<Pending>();
		for (Iterator<Map.Entry<Long, Pending>> it = pending.entrySet().iterator(); it.hasNext();) {
			failed.add(it.next().getValue());
			it.remove();
		}
		for (Pending p : failed) {
			try {
				p.callback.failed(code);
			} catch (RuntimeException ignored) {
				// dito
			}
		}
	}

	private void expirePending(long now) {
		for (Iterator<Map.Entry<Long, Pending>> it = pending.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Long, Pending> e = it.next();
			if (e.getValue().deadline <= now) {
				it.remove();
				try {
					e.getValue().callback.failed("timeout");
				} catch (RuntimeException ignored) {
					// dito
				}
			}
		}
	}

	private Result session(LinkTarget target) {
		Socket s = new Socket();
		boolean greeted = false;
		sealKey = null;
		partial.reset();
		try {
			s.setTcpNoDelay(true);
			try {
				s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), target.port), CONNECT_TIMEOUT_MS);
			} catch (IOException e) {
				return Result.REFUSED;
			}
			s.setSoTimeout(POLL_MS);
			socket = s;
			OutputStream o = s.getOutputStream();
			InputStream in = new java.io.BufferedInputStream(s.getInputStream());
			Gson gson = new Gson();
			boolean accounts = false;
			boolean clipsEnable = false;
			List<String> features = null;
			String gameNonce = null;
			if (target.kind == LinkTarget.Kind.V2) {
				gameNonce = LinkCrypto.randomHex(16);
				if (!write(o, "{\"type\":\"hello\",\"v\":2,\"sid\":\"" + target.sid + "\",\"nonce\":\"" + gameNonce + "\"}")) {
					return Result.FAILED;
				}
				Line challenge = readHandshake(in, gson);
				if (challenge == null) return Result.FAILED;
				if ("denied".equals(challenge.type)) return Result.DENIED;
				if (!"challenge".equals(challenge.type) || !LinkCrypto.isHex(challenge.nonce, 32)) return Result.FAILED;
				String expected = LinkCrypto.launcherProof(target.key, target.sid, gameNonce, challenge.nonce);
				// Erst den Launcher prüfen – einem fremden Programm auf dem Port wird nichts verraten.
				if (!LinkCrypto.equalsConstantTime(expected, challenge.proof)) {
					debug("Launcher-Beweis falsch – Verbindung getrennt");
					return Result.DENIED;
				}
				String proof = LinkCrypto.gameProof(target.key, target.sid, gameNonce, challenge.nonce);
				if (!write(o, "{\"type\":\"auth\",\"proof\":\"" + proof + "\",\"features\":" + GAME_FEATURES_JSON + "}")) {
					return Result.FAILED;
				}
				sealKey = LinkCrypto.sealKey(target.key, gameNonce, challenge.nonce);
				accounts = challenge.features != null && challenge.features.contains("accounts");
				clipsEnable = challenge.features != null && challenge.features.contains(FEATURE_CLIPS_ENABLE);
				features = challenge.features;
			} else {
				if (!write(o, "{\"type\":\"hello\",\"v\":1,\"token\":\"" + target.token + "\"}")) return Result.FAILED;
			}
			long lastLine = System.currentTimeMillis();
			while (running) {
				String raw;
				try {
					raw = readLine(in);
				} catch (SocketTimeoutException e) {
					long now = System.currentTimeMillis();
					expirePending(now);
					if (now - lastLine > (greeted ? IDLE_LIMIT_MS : HANDSHAKE_MS)) return greeted ? Result.OK : Result.FAILED;
					continue;
				}
				if (raw == null) return greeted ? Result.OK : Result.FAILED;
				lastLine = System.currentTimeMillis();
				Line line;
				try {
					line = gson.fromJson(raw, Line.class);
				} catch (RuntimeException e) {
					continue;
				}
				if (line == null || line.type == null) continue;
				if ("denied".equals(line.type)) return Result.DENIED;
				if (!greeted) {
					if (!"state".equals(line.type)) continue;
					greeted = true;
					out = o;
					setStatus(new Status(true, target.kind == LinkTarget.Kind.V2 ? 2 : 1, accounts, clipsEnable, features));
				}
				if ("res".equals(line.type)) {
					Pending p = line.id == null ? null : pending.remove(line.id);
					if (p != null) {
						try {
							if (Boolean.TRUE.equals(line.ok)) p.callback.done(line);
							else p.callback.failed(safeError(line.error));
						} catch (RuntimeException ignored) {
							// dito
						}
					}
					continue;
				}
				for (Listener l : listeners) {
					try {
						l.onLine(this, line);
					} catch (RuntimeException ignored) {
						// dito
					}
				}
			}
			return Result.OK;
		} catch (IOException | RuntimeException e) {
			return greeted ? Result.OK : Result.FAILED;
		} finally {
			out = null;
			sealKey = null;
			if (socket == s) socket = null;
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
		}
	}

	/** Erste Antwort während der Anmeldung (mit Zeitlimit). */
	private Line readHandshake(InputStream in, Gson gson) throws IOException {
		long until = System.currentTimeMillis() + HANDSHAKE_MS;
		while (System.currentTimeMillis() < until) {
			String raw;
			try {
				raw = readLine(in);
			} catch (SocketTimeoutException e) {
				continue;
			}
			if (raw == null) return null;
			try {
				Line line = gson.fromJson(raw, Line.class);
				if (line != null && line.type != null) return line;
			} catch (RuntimeException ignored) {
				// weiter warten
			}
		}
		return null;
	}

	private static final List<String> ERRORS = Collections.unmodifiableList(java.util.Arrays.asList(
			"rate_limited", "unknown_account", "not_allowed", "busy", "cancelled", "auth_failed", "offline",
			"unknown_op", "timeout", "unsupported", "unknown_clip", "no_ffmpeg", "error"));

	/** Nur bekannte Fehlercodes durchlassen. */
	public static String safeError(String code) {
		return code != null && ERRORS.contains(code) ? code : "error";
	}

	/** Eine Zeile (UTF-8) lesen; zu lang → Verbindung beenden. Teilzeilen bleiben bei Zeitüberschreitung erhalten. */
	private final java.io.ByteArrayOutputStream partial = new java.io.ByteArrayOutputStream();

	private String readLine(InputStream in) throws IOException {
		while (true) {
			int b = in.read();
			if (b < 0) {
				partial.reset();
				return null;
			}
			if (b == '\n') {
				String line = new String(partial.toByteArray(), StandardCharsets.UTF_8);
				partial.reset();
				return line;
			}
			if (partial.size() >= MAX_LINE) {
				partial.reset();
				throw new IOException("Zeile zu lang");
			}
			partial.write(b);
		}
	}

	/** Für Clips: zuletzt aufgelöstes Ziel (ohne Datei neu zu lesen). */
	public LinkTarget lastTarget() {
		return lastTarget;
	}
}
