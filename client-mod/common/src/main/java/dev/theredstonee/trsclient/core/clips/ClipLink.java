package dev.theredstonee.trsclient.core.clips;

import dev.theredstonee.trsclient.core.link.LinkTarget;
import dev.theredstonee.trsclient.core.link.TrsLink;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Clips über die Verbindung zum TRS Launcher ({@link TrsLink}, nur 127.0.0.1).
 *
 * <p>Der Netz-Thread liefert Statuszeilen; das Spiel liest nur {@link #status()} und {@link #pollNotice()} und
 * schickt Tastendrücke über {@link #press}. Jeder Tastendruck bekommt eine sichtbare Antwort: gespeichert,
 * Fehlergrund, „Clips sind aus“ (bei neuen Launchern mit Angebot: dieselbe Taste noch einmal schaltet sie ein),
 * „nur mit dem TRS Launcher“ oder „Launcher nicht erreichbar“.
 */
public final class ClipLink implements TrsLink.Listener {
	/** Befehle an den Launcher. */
	public static final String CLIP = "clip";
	public static final String RECORD = "record";
	/** Anfrage „Clips einschalten“ (Launcher ab 0.6.1, Merkmal {@link TrsLink#FEATURE_CLIPS_ENABLE}). */
	public static final String OP_ENABLE = "clips.enable";

	/** Ein vorgemerkter Tastendruck verfällt, wenn die Verbindung nicht zustande kommt. */
	private static final long PENDING_MS = 4000;
	/** So lange gilt „noch einmal drücken = Clips einschalten“. */
	static final long OFFER_MS = 8000;
	private static final long ENABLE_TIMEOUT_MS = 10000;
	/** Nach dem Einschalten höchstens so lange auf den laufenden Puffer warten (FFmpeg-Download inklusive). */
	private static final long AWAIT_READY_MS = 5 * 60 * 1000;
	/** Diagnose nur im Autotest (stdout landet im Spiel-Log). */
	private static final boolean DEBUG = Boolean.getBoolean("trsclient.autotest");

	private static void debug(String message) {
		if (DEBUG) System.out.println("[TRS Clips] " + message);
	}

	private final TrsLink link;
	private final boolean ownsLink;
	private final ConcurrentLinkedQueue<ClipNotice> notices = new ConcurrentLinkedQueue<ClipNotice>();
	private volatile ClipStatus status = ClipStatus.OFFLINE;
	private volatile String pending;
	private volatile long pendingSince;
	private volatile boolean wasRecording;
	/** Angebot „noch einmal drücken“ gilt bis (Wanduhr ms), 0 = keins. */
	private volatile long offerUntil;
	/** Zuletzt gedrückt: Aufnahme-Taste? (für den Tastennamen im Angebot) */
	private volatile boolean lastRecordKey;
	private volatile boolean enabling;
	/** Nach dem Einschalten: seit wann auf den laufenden Puffer gewartet wird (0 = nicht). */
	private volatile long awaitingSince;
	private volatile int lastProgressStep = -1;

	/** Eigene Verbindung (Tests, Launcher-Protokoll 1 aus clips.json). */
	public ClipLink(Path configDir) {
		this(new TrsLink(configDir, null), true);
	}

	/** Auf einer gemeinsamen Verbindung (Clips + Konten). */
	public ClipLink(TrsLink link) {
		this(link, false);
	}

	private ClipLink(TrsLink link, boolean ownsLink) {
		this.link = link;
		this.ownsLink = ownsLink;
		link.addListener(this);
	}

	/** Startet den Netz-Thread (einmalig; bei einer gemeinsamen Verbindung läuft er schon). */
	public synchronized void start() {
		link.start();
	}

	public synchronized void stop() {
		link.removeListener(this);
		if (ownsLink) link.stop();
	}

	public ClipStatus status() {
		return status;
	}

	/** Nächste Meldung für den Spieler oder null (Spiel-Thread). */
	public ClipNotice pollNotice() {
		return notices.poll();
	}

	/**
	 * Ohne Verbindung der Grund als Hinweis-Code – aus dem zuletzt vom Netz-Thread aufgelösten Ziel (keine
	 * Dateizugriffe im Render-Thread).
	 */
	public String offlineCode() {
		LinkTarget target = link.lastTarget();
		if (target == null || target.kind == LinkTarget.Kind.MISSING) return "noLauncher";
		if (target.kind == LinkTarget.Kind.DISABLED) return target.clipsEnabled ? "noLauncher" : "disabled";
		return "unreachable";
	}

	/** Wartet gerade ein „noch einmal drücken = einschalten“? */
	public boolean offerActive(long now) {
		return offerUntil != 0 && now <= offerUntil && status.offersEnable();
	}

	private java.util.concurrent.ExecutorService presses;

	private void inBackground(final Runnable task) {
		java.util.concurrent.ExecutorService ex;
		synchronized (this) {
			if (presses == null) {
				presses = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "TRS-Clips-Taste");
					t.setDaemon(true);
					return t;
				});
			}
			ex = presses;
		}
		try {
			ex.execute(() -> {
				try {
					task.run();
				} catch (RuntimeException e) {
					notices.add(ClipNotice.hint("unreachable"));
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			notices.add(ClipNotice.hint("unreachable"));
		}
	}

	/**
	 * Tastendruck aus dem Spiel-Thread: Senden (Socket) und ggf. Lesen der Launcher-Datei laufen im Thread
	 * „TRS-Clips-Taste“ – das Spiel wartet nie auf Netz oder Festplatte. Rückmeldungen kommen wie immer über
	 * {@link #pollNotice()}.
	 */
	public void pressInBackground(final String command) {
		inBackground(() -> press(command));
	}

	/** „Jetzt einschalten“ (Knopf im Clips-Bildschirm) – ohne das Spiel zu blockieren. */
	public void enableInBackground() {
		inBackground(this::enable);
	}

	/**
	 * Tastendruck (blockierend – im Spiel {@link #pressInBackground} benutzen). Verbunden → sofort senden bzw. bei
	 * ausgeschalteten Clips das Einschalten anbieten; sonst Ziel neu lesen und je nach Lage vormerken (Verbindung
	 * wird aufgebaut) oder einen Hinweis zeigen.
	 */
	public void press(String command) {
		long now = System.currentTimeMillis();
		ClipStatus st = status;
		boolean record = RECORD.equals(command);
		lastRecordKey = record;
		debug("Taste " + command + ": verbunden=" + st.connected + " Grund=" + st.reason);
		if (st.connected) {
			if (st.disabled()) {
				disabledPress(st, record, now);
				return;
			}
			if (link.send("{\"type\":\"" + command + "\"}")) return;
		}
		LinkTarget target = link.target();
		if (target.kind == LinkTarget.Kind.MISSING) {
			notices.add(ClipNotice.hint("noLauncher"));
			return;
		}
		if (target.kind == LinkTarget.Kind.DISABLED) {
			// Alter Launcher mit Clips aus – oder ein neuer Launcher, aber das Spiel lief nicht über ihn.
			notices.add(ClipNotice.hint(target.clipsEnabled ? "noLauncher" : "disabled"));
			return;
		}
		pending = command;
		pendingSince = now;
		link.wakeUp();
	}

	/** Clips sind aus: Angebot zeigen bzw. beim zweiten Druck einschalten (nur neue Launcher). */
	private void disabledPress(ClipStatus st, boolean record, long now) {
		if (!st.canEnable) {
			// Launcher ohne „clips.enable“: nur der Weg über die Einstellungen.
			notices.add(ClipNotice.hint("disabled"));
			return;
		}
		if (enabling) {
			notices.add(ClipNotice.hint("wait"));
			return;
		}
		if (offerUntil != 0 && now <= offerUntil) {
			enable();
			return;
		}
		offer(st, record, now);
	}

	private void offer(ClipStatus st, boolean record, long now) {
		offerUntil = now + OFFER_MS;
		notices.add(ClipNotice.offer(record, st.audio, st.mic));
	}

	/**
	 * Clips im Launcher einschalten (blockierend kurz: nur das Senden). Antwort und Fortschritt kommen als Meldungen.
	 */
	public void enable() {
		offerUntil = 0;
		TrsLink.Status ls = link.status();
		if (!ls.connected) {
			notices.add(ClipNotice.hint(link.target().kind == LinkTarget.Kind.MISSING ? "noLauncher" : "unreachable"));
			return;
		}
		if (!ls.clipsEnable) {
			notices.add(ClipNotice.hint("disabled"));
			return;
		}
		synchronized (this) {
			if (enabling) return;
			enabling = true;
		}
		debug("Clips einschalten angefragt");
		link.request(OP_ENABLE, null, ENABLE_TIMEOUT_MS, new TrsLink.Callback() {
			@Override
			public void done(TrsLink.Line response) {
				enabling = false;
				ClipStatus s = status;
				if (s.buffer) {
					notices.add(ClipNotice.ready(s.clipSeconds));
					return;
				}
				awaitingSince = System.currentTimeMillis();
				lastProgressStep = -1;
				notices.add(new ClipNotice(ClipNotice.Type.ENABLED, 0, null));
			}

			@Override
			public void failed(String code) {
				enabling = false;
				debug("Clips einschalten fehlgeschlagen: " + code);
				if ("unsupported".equals(code)) notices.add(ClipNotice.hint("unsupported"));
				else if ("rate_limited".equals(code) || "busy".equals(code)) notices.add(ClipNotice.hint("wait"));
				else if ("offline".equals(code) || "timeout".equals(code)) notices.add(ClipNotice.hint("unreachable"));
				else notices.add(ClipNotice.hint("enableFailed"));
			}
		});
	}

	/** Spiel-Thread: abgelaufene Vormerkung → Hinweis "Launcher nicht erreichbar". */
	public void tick(long now) {
		String p = pending;
		if (p != null && now - pendingSince > PENDING_MS) {
			pending = null;
			notices.add(ClipNotice.hint("unreachable"));
		}
		if (offerUntil != 0 && now > offerUntil) offerUntil = 0;
		long since = awaitingSince;
		if (since != 0 && now - since > AWAIT_READY_MS) awaitingSince = 0;
	}

	@Override
	public void onConnected(TrsLink l, boolean accounts) {
		// Status kommt mit der ersten Zeile (onLine); vorgemerkten Tastendruck jetzt senden.
		String p = pending;
		pending = null;
		if (p != null && System.currentTimeMillis() - pendingSince <= PENDING_MS) {
			l.send("{\"type\":\"" + p + "\"}");
		}
	}

	@Override
	public void onDisconnected(TrsLink l) {
		status = ClipStatus.OFFLINE;
		wasRecording = false;
		offerUntil = 0;
		enabling = false;
	}

	@Override
	public void onLine(TrsLink l, TrsLink.Line line) {
		if ("state".equals(line.type)) {
			onState(l, line);
		} else if ("saved".equals(line.type)) {
			int seconds = line.seconds == null ? 0 : Math.max(0, line.seconds);
			notices.add(new ClipNotice("recording".equals(line.kind) ? ClipNotice.Type.RECORDING_SAVED
					: ClipNotice.Type.CLIP_SAVED, seconds, null));
		} else if ("failed".equals(line.type)) {
			String code = safeCode(line.code);
			ClipStatus s = status;
			if ("disabled".equals(code) && s.canEnable) {
				// Taste kam an, bevor die Mod „aus“ kannte – jetzt das Einschalten anbieten.
				offer(s, lastRecordKey, System.currentTimeMillis());
			} else {
				notices.add(ClipNotice.failed(code, s.progress));
			}
		}
	}

	private void onState(TrsLink l, TrsLink.Line line) {
		boolean recording = Boolean.TRUE.equals(line.recording);
		long since = recording ? System.currentTimeMillis() - clampMs(line.recordingMs) : 0;
		boolean first = !status.connected;
		int progress = line.progress == null ? -1 : Math.max(0, Math.min(100, line.progress));
		ClipStatus s = new ClipStatus(true, Boolean.TRUE.equals(line.available), safeReason(line.reason),
				Boolean.TRUE.equals(line.buffer), recording, since,
				line.clipSeconds == null ? 0 : Math.max(0, Math.min(3600, line.clipSeconds)),
				progress, line.audio == null || line.audio, Boolean.TRUE.equals(line.mic), l.status().clipsEnable);
		status = s;
		if (recording && !wasRecording && !first) notices.add(new ClipNotice(ClipNotice.Type.RECORDING_STARTED, 0, null));
		wasRecording = recording;
		if (awaitingSince != 0) awaitReady(s);
	}

	/** Nach dem Einschalten: Fortschritt melden und „bereit“, sobald der Puffer läuft. */
	private void awaitReady(ClipStatus s) {
		if (s.buffer) {
			awaitingSince = 0;
			notices.add(ClipNotice.ready(s.clipSeconds));
		} else if ("ffmpeg".equals(s.reason)) {
			int step = s.progress < 0 ? 0 : s.progress / 10 + 1;
			if (step != lastProgressStep) {
				lastProgressStep = step;
				notices.add(ClipNotice.preparing(s.progress));
			}
		} else if ("ffmpegFailed".equals(s.reason) || "encoder".equals(s.reason) || "error".equals(s.reason)) {
			awaitingSince = 0;
			notices.add(ClipNotice.failed(s.reason, -1));
		}
	}

	private static long clampMs(Long ms) {
		if (ms == null) return 0;
		return Math.max(0, Math.min(ms, 24L * 3600 * 1000));
	}

	private static final String[] KNOWN = {"disabled", "starting", "noWindow", "ffmpeg", "ffmpegFailed", "encoder",
			"unsupported", "noFrames", "busy", "error"};

	/** Nur bekannte Fehlercodes durchlassen (sonst "error"). */
	static String safeCode(String code) {
		if (code == null) return "error";
		for (String k : KNOWN) {
			if (k.equals(code)) return k;
		}
		return "error";
	}

	/** Grund aus der Statuszeile: bekannt, null (läuft) oder "error". */
	static String safeReason(String reason) {
		return reason == null ? null : safeCode(reason);
	}
}
