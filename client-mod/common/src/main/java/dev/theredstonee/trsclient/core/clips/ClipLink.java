package dev.theredstonee.trsclient.core.clips;

import dev.theredstonee.trsclient.core.link.LinkTarget;
import dev.theredstonee.trsclient.core.link.TrsLink;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Clips über die Verbindung zum TRS Launcher ({@link TrsLink}, nur 127.0.0.1).
 *
 * <p>Der Netz-Thread liefert Statuszeilen; das Spiel liest nur {@link #status()} und {@link #pollNotice()} und
 * schickt Tastendrücke über {@link #press}. Ohne Launcher passiert nichts außer einem Hinweis beim Tastendruck.
 */
public final class ClipLink implements TrsLink.Listener {
	/** Befehle an den Launcher. */
	public static final String CLIP = "clip";
	public static final String RECORD = "record";

	/** Ein vorgemerkter Tastendruck verfällt, wenn die Verbindung nicht zustande kommt. */
	private static final long PENDING_MS = 4000;
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

	private java.util.concurrent.ExecutorService presses;

	/**
	 * Tastendruck aus dem Spiel-Thread: Senden (Socket) und ggf. Lesen der Launcher-Datei laufen im Thread
	 * „TRS-Clips-Taste“ – das Spiel wartet nie auf Netz oder Festplatte. Rückmeldungen kommen wie immer über
	 * {@link #pollNotice()}.
	 */
	public void pressInBackground(final String command) {
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
					press(command);
				} catch (RuntimeException e) {
					notices.add(ClipNotice.hint("unreachable"));
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			notices.add(ClipNotice.hint("unreachable"));
		}
	}

	/**
	 * Tastendruck (blockierend – im Spiel {@link #pressInBackground} benutzen). Verbunden → sofort senden; sonst
	 * Ziel neu lesen und je nach Lage vormerken (Verbindung wird aufgebaut) oder einen Hinweis zeigen.
	 */
	public void press(String command) {
		debug("Taste " + command + ": verbunden=" + status.connected);
		if (status.connected && link.send("{\"type\":\"" + command + "\"}")) return;
		LinkTarget target = link.target();
		if (target.kind == LinkTarget.Kind.MISSING) {
			notices.add(ClipNotice.hint("noLauncher"));
			return;
		}
		if (target.kind == LinkTarget.Kind.DISABLED || !target.clipsEnabled) {
			notices.add(ClipNotice.hint("disabled"));
			return;
		}
		pending = command;
		pendingSince = System.currentTimeMillis();
		link.wakeUp();
	}

	/** Spiel-Thread: abgelaufene Vormerkung → Hinweis "Launcher nicht erreichbar". */
	public void tick(long now) {
		String p = pending;
		if (p != null && now - pendingSince > PENDING_MS) {
			pending = null;
			notices.add(ClipNotice.hint("unreachable"));
		}
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
	}

	@Override
	public void onLine(TrsLink l, TrsLink.Line line) {
		if ("state".equals(line.type)) {
			boolean recording = Boolean.TRUE.equals(line.recording);
			long since = recording ? System.currentTimeMillis() - clampMs(line.recordingMs) : 0;
			boolean first = !status.connected;
			status = new ClipStatus(true, Boolean.TRUE.equals(line.available), line.reason,
					Boolean.TRUE.equals(line.buffer), recording, since,
					line.clipSeconds == null ? 0 : Math.max(0, Math.min(3600, line.clipSeconds)));
			if (recording && !wasRecording && !first) notices.add(new ClipNotice(ClipNotice.Type.RECORDING_STARTED, 0, null));
			wasRecording = recording;
		} else if ("saved".equals(line.type)) {
			int seconds = line.seconds == null ? 0 : Math.max(0, line.seconds);
			notices.add(new ClipNotice("recording".equals(line.kind) ? ClipNotice.Type.RECORDING_SAVED
					: ClipNotice.Type.CLIP_SAVED, seconds, null));
		} else if ("failed".equals(line.type)) {
			notices.add(new ClipNotice(ClipNotice.Type.FAILED, 0, safeCode(line.code)));
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
}
