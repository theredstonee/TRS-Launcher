package dev.theredstonee.trsclient.core.cinematic;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.CinematicModules;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Kamera-Fahrten und freie Kamera – versionsunabhängige Steuerung. Je Minecraft-Version liefert der {@link Host} nur:
 * Weltschlüssel, aktuelle Kamera, Aktionsleiste, HUD an/aus, Partikel, Blöcke, Tasten der freien Kamera und den
 * Aufnahme-Knopf; die Bäume rufen die Tasten-Aktionen, {@link #tick} einmal je Client-Tick und {@link #frame(Host)}
 * je Bild (Kamera/Sichtfeld).
 *
 * <p>Abgespielt wird nach der Wanduhr ({@link System#nanoTime()}): Position, Blick und Sichtfeld werden je Bild aus
 * der verstrichenen Zeit berechnet – unabhängig von Bildrate und Tick-Takt. Ebenso bewegt sich die freie Kamera
 * ({@link Freecam}). Mit Fair Play ({@link CameraCollision}) halten beide an massiven, undurchsichtigen Blöcken an.
 * Nur Spiel-/Render-Thread.
 */
public final class Cinematic {
	/** Was die jeweilige Minecraft-Version beisteuert. */
	public interface Host {
		/** Welt/Server ({@code sp:…}/{@code mp:…}) oder leer/null außerhalb einer Welt. */
		String worldKey();

		/** Dimension (z. B. {@code minecraft:overworld}). */
		String dimension();

		/** Aktuelle Kamera (Position, Blick, Sichtfeld) in {@code out}; false = keine Welt. */
		boolean camera(CameraPose out);

		/** Text in der Aktionsleiste. */
		void actionBar(String text);

		/** Ist das HUD gerade ausgeblendet (F1)? */
		boolean hudHidden();

		void setHudHidden(boolean hidden);

		/** Ein Vorschau-Partikel ({@code keyframe}: Markierung eines Keyframes, sonst Bahn). */
		void particle(boolean keyframe, double x, double y, double z);

		/** Steht die Aufnahme über den TRS Launcher bereit? (Standard: über {@code core.clips.Clips}.) */
		default boolean recordingAvailable() {
			dev.theredstonee.trsclient.core.clips.ClipStatus s = dev.theredstonee.trsclient.core.clips.Clips.get().status();
			return s.connected && s.available;
		}

		default boolean recording() {
			return dev.theredstonee.trsclient.core.clips.Clips.get().status().recording;
		}

		default void toggleRecording() {
			dev.theredstonee.trsclient.core.clips.Clips.get().toggleRecording();
		}

		/**
		 * Ansicht für die Fahrt lösen ({@code true}, z. B. dritte Person: keine Hand, keine Bildschirm-Effekte der Figur
		 * wie „im Block“) bzw. wiederherstellen ({@code false}).
		 */
		default void detachView(boolean detached) {
		}

		/**
		 * Je Tick, solange die Ansicht gelöst ist: Zustand halten (z. B. nach Respawn eine neue Spielfigur wieder
		 * festhalten, Perspektive nicht in die erste Person zurück).
		 */
		default void holdView() {
		}

		/** Ist der Block eine Wand für die Kamera (massiv, undurchsichtig, kein Glas/Laub/Eis)? Standard: nein. */
		default boolean solid(int x, int y, int z) {
			return false;
		}

		/** Tasten der freien Kamera in {@code in} (nichts, solange ein Menü offen ist). */
		default void freecamInput(Freecam.Input in) {
		}

		/** Adresse des Servers (null = Einzelspieler). */
		default String serverAddress() {
			return null;
		}

		/** MOTD des Servers (für Fair-Play-Codes) oder null. */
		default String serverMotd() {
			return null;
		}
	}

	/** Mausrad in der freien Kamera: Faktor je Raste. */
	static final double SPEED_STEP = 1.25;

	/** Vorschau: alle so viele Ticks die Bahn neu zeichnen. */
	static final int PREVIEW_TICKS = 20;
	/** Abstand der Vorschau-Punkte (Blöcke) und Höchstzahl je Durchgang. */
	static final double PREVIEW_STEP = 0.75;
	static final int PREVIEW_MAX = 120;
	/** Sichtweite der Vorschau (Blöcke). */
	static final double PREVIEW_RANGE = 96;
	/** Keine Vorschau-Partikel direkt vor der Kamera (sie verdecken sonst das Bild). */
	static final double PREVIEW_MIN = 2.0;

	private static Cinematic instance;

	private final CinematicModules settings;
	private final CinematicStore store;
	private String key = "";
	private CameraPath path;
	/** Startzeit der laufenden Fahrt (nanoTime) – 0 = steht. */
	private long startNanos;
	private boolean playing;
	private boolean hudBefore;
	private boolean changedHud;
	private boolean startedRecording;
	private int previewCountdown;
	private final CameraPose scratch = new CameraPose();
	private final CameraPose framePose = new CameraPose();
	private final Freecam freecam = new Freecam();
	private final FreecamRules rules = new FreecamRules();
	private final Freecam.Input input = new Freecam.Input();
	/** Für Selbsttests: feste Tasten der freien Kamera statt der echten. */
	private Freecam.Input testInput;
	/** Ansicht gerade gelöst (Fahrt oder freie Kamera)? */
	private boolean detached;
	/** Fair Play beim Abspielen: zuletzt gezeigte (ggf. an einer Wand angehaltene) Position. */
	private final double[] playPos = new double[3];
	private boolean playPosSet;
	/** Welt-Abfrage für {@link CameraCollision} über den Host des aktuellen Bildes. */
	private Host blocksHost;
	private final CameraCollision.Blocks blocks = new CameraCollision.Blocks() {
		@Override
		public boolean solid(int x, int y, int z) {
			return blocksHost != null && blocksHost.solid(x, y, z);
		}
	};
	/** Für Tests: feste Uhr (nanoTime). */
	private java.util.function.LongSupplier clock = System::nanoTime;

	public Cinematic(CinematicModules settings, CinematicStore store) {
		this.settings = settings;
		this.store = store;
		// Server verlangt Fair Play (oder sperrt die freie Kamera): Schalter an und ausgegraut.
		settings.freecamFairPlay.lock(new java.util.function.BooleanSupplier() {
			@Override
			public boolean getAsBoolean() {
				return rules.fairForced();
			}
		}, true);
	}

	/** Beim Start des Clients: Datei {@code <configDir>/trsclient-cinematic.json} laden. */
	public static synchronized Cinematic init(CinematicModules settings, Path configDir) {
		if (instance == null) {
			CinematicStore store = new CinematicStore(configDir == null ? null : configDir.resolve("trsclient-cinematic.json"));
			store.load();
			instance = new Cinematic(settings, store);
		}
		return instance;
	}

	/** Die Fahrten des laufenden Spiels (null vor {@link #init}). */
	public static Cinematic get() {
		return instance;
	}

	void clock(java.util.function.LongSupplier clock) {
		this.clock = clock;
	}

	CinematicStore store() {
		return store;
	}

	// --- Tasten ---

	/** Keyframe an der aktuellen Kamera anhängen. */
	public void addKeyframe(Host host) {
		if (!settings.cinematic.isEnabled() || playing) return;
		if (!refreshKey(host) || !host.camera(scratch)) return;
		// In der freien Kamera: deren Lage (das Sichtfeld bleibt das gerade sichtbare).
		if (freecam.active()) {
			float fov = scratch.fov;
			freecam.pose(scratch).fov = fov;
		}
		if (store.add(key, Keyframe.of(scratch))) {
			host.actionBar(I18n.tr("cinematic.msg.added", store.get(key).size()));
			save();
		} else {
			host.actionBar(I18n.tr("cinematic.msg.full", CinematicStore.MAX_KEYFRAMES));
		}
		previewCountdown = 0;
	}

	/** Letzten Keyframe entfernen. */
	public void removeLast(Host host) {
		if (!settings.cinematic.isEnabled() || playing || !refreshKey(host)) return;
		if (store.removeLast(key)) {
			host.actionBar(I18n.tr("cinematic.msg.removed", store.get(key).size()));
			save();
		} else {
			host.actionBar(I18n.tr("cinematic.msg.empty"));
		}
	}

	/** Ganze Fahrt dieser Welt löschen. */
	public void clear(Host host) {
		if (!settings.cinematic.isEnabled() || !refreshKey(host)) return;
		if (playing) stop(host);
		if (store.clear(key)) {
			host.actionBar(I18n.tr("cinematic.msg.cleared"));
			save();
		} else {
			host.actionBar(I18n.tr("cinematic.msg.empty"));
		}
	}

	/** Abspielen bzw. anhalten. */
	public void togglePlay(Host host) {
		if (playing) {
			stop(host);
			return;
		}
		if (!settings.cinematic.isEnabled() || !refreshKey(host)) return;
		List<Keyframe> keys = store.get(key);
		CameraPath p = CameraPath.of(keys, settings.smoothing.get().path, settings.timing.get().path, settings.seconds.get(),
				settings.ease.get());
		if (p == null) {
			host.actionBar(I18n.tr("cinematic.msg.needTwo"));
			return;
		}
		path = p;
		playing = true;
		startNanos = clock.getAsLong();
		playPosSet = false;
		syncView(host);
		changedHud = false;
		if (settings.hideHud.get()) {
			hudBefore = host.hudHidden();
			if (!hudBefore) {
				host.setHudHidden(true);
				changedHud = true;
			}
		}
		startedRecording = false;
		if (settings.record.get()) {
			try {
				if (host.recordingAvailable() && !host.recording()) {
					host.toggleRecording();
					startedRecording = true;
				}
			} catch (RuntimeException ignored) {
				// Aufnahme ist ein Zusatz
			}
		}
	}

	/** Fahrt beenden (Taste, Ende erreicht, Menü geöffnet, Welt verlassen): Kamera, HUD und Aufnahme zurück. */
	public void stop(Host host) {
		if (!playing) return;
		playing = false;
		path = null;
		syncView(host);
		if (changedHud) {
			host.setHudHidden(hudBefore);
			changedHud = false;
		}
		if (startedRecording) {
			startedRecording = false;
			try {
				if (host.recording()) host.toggleRecording();
			} catch (RuntimeException ignored) {
				// Aufnahme ist ein Zusatz
			}
		}
		previewCountdown = PREVIEW_TICKS;
	}

	// --- Freie Kamera ---

	/** Freie Kamera an bzw. aus (Taste). */
	public void toggleFreecam(Host host) {
		if (freecam.active()) {
			exitFreecam(host);
			host.actionBar(I18n.tr("cinematic.msg.freecamOff"));
			return;
		}
		if (!settings.cinematic.isEnabled() || playing || !refreshKey(host)) return;
		updateRules(host);
		if (!rules.allowed()) {
			host.actionBar(I18n.tr("cinematic.msg.freecamBlocked"));
			return;
		}
		if (!host.camera(scratch)) return;
		blocksHost = host;
		freecam.enter(scratch, clock.getAsLong(), fairPlay() ? blocks : null);
		syncView(host);
		host.actionBar(I18n.tr("cinematic.msg.freecamOn", speedText()));
	}

	private void exitFreecam(Host host) {
		if (!freecam.active()) return;
		freecam.exit();
		syncView(host);
	}

	/** Ist die freie Kamera an? */
	public boolean freecam() {
		return freecam.active();
	}

	/** Zeigt die Kamera gerade nicht die Sicht der Figur (Fahrt oder freie Kamera)? */
	public boolean overridden() {
		return playing || freecam.active();
	}

	/**
	 * Mausbewegung (Rohwerte wie für {@code Entity#turn}): dreht die freie Kamera; während einer Fahrt wirkt sie gar
	 * nicht.
	 *
	 * @return true = verbraucht (die Spielfigur dreht sich nicht)
	 */
	public boolean turn(double yawDelta, double pitchDelta) {
		if (playing) return true;
		if (!freecam.active()) return false;
		freecam.turn(yawDelta, pitchDelta);
		return true;
	}

	/**
	 * Mausrad in der freien Kamera: Geschwindigkeit ändern.
	 *
	 * @return true = verbraucht (keine Hotbar)
	 */
	public boolean scroll(Host host, double amount) {
		if (!freecam.active() || playing || amount == 0) return false;
		double before = settings.freecamSpeed.get();
		double next = before * Math.pow(SPEED_STEP, Math.signum(amount));
		double step = settings.freecamSpeed.step();
		next = Math.round(next / step) * step;
		// Kleine Werte: mindestens ein Schritt, sonst bliebe das Rad wirkungslos.
		if (next == before) next = before + Math.signum(amount) * step;
		settings.freecamSpeed.set(next);
		host.actionBar(I18n.tr("cinematic.msg.speed", speedText()));
		return true;
	}

	/** Gilt Fair Play (eigene Einstellung oder vom Server erzwungen)? */
	public boolean fairPlay() {
		return settings.freecamFairPlay.get();
	}

	/** Server-Regeln der freien Kamera (für Anzeige/Tests). */
	public FreecamRules rules() {
		return rules;
	}

	/** Die freie Kamera selbst (für Selbsttests). */
	public Freecam freecamState() {
		return freecam;
	}

	/** Für Selbsttests: feste Tasten der freien Kamera (null = wieder die echten). */
	public void testInput(Freecam.Input in) {
		testInput = in;
	}

	/** Chat-Zeile vom Server: Fair-Play-Codes der freien Kamera. */
	public static void serverText(String text) {
		Cinematic c = instance;
		if (c != null) c.rules.onServerText(text);
	}

	private String speedText() {
		return settings.freecamSpeed.display();
	}

	private void updateRules(Host host) {
		String world = host.worldKey();
		boolean inWorld = world != null && !world.isEmpty();
		if (rules.update(inWorld, inWorld ? host.serverAddress() : null)) {
			try {
				rules.onServerText(host.serverMotd());
			} catch (RuntimeException ignored) {
				// MOTD ist ein Zusatz
			}
		}
	}

	/** Ansicht lösen, solange eine Fahrt läuft oder die freie Kamera an ist – sonst zurück. */
	private void syncView(Host host) {
		boolean want = playing || freecam.active();
		if (want == detached) return;
		detached = want;
		host.detachView(want);
	}

	// --- Takt ---

	/**
	 * Einmal je Client-Tick: Ende der Fahrt erkennen, Welt-/Dimensionswechsel, Vorschau-Partikel.
	 *
	 * @param screenOpen ein Menü ist offen (z. B. Pause) – dann endet eine laufende Fahrt
	 */
	public void tick(Host host, boolean screenOpen) {
		updateRules(host);
		String world = host.worldKey();
		if (world == null || world.isEmpty()) {
			if (playing) stop(host);
			exitFreecam(host);
			key = "";
			return;
		}
		String now = CinematicStore.key(world, host.dimension());
		if (!now.equals(key)) {
			if (playing) stop(host);
			exitFreecam(host);
			key = now;
		}
		if (freecam.active() && (!settings.cinematic.isEnabled() || !rules.allowed())) {
			boolean blocked = !rules.allowed();
			exitFreecam(host);
			if (blocked) host.actionBar(I18n.tr("cinematic.msg.freecamBlocked"));
		}
		if (detached) host.holdView();
		if (playing) {
			if (!settings.cinematic.isEnabled() || screenOpen || elapsed() > path.duration() + 0.05) stop(host);
			return;
		}
		if (!settings.cinematic.isEnabled() || !settings.preview.get()) return;
		if (--previewCountdown > 0) return;
		previewCountdown = PREVIEW_TICKS;
		preview(host);
	}

	/** Bahn und Keyframes als Partikel (nur in Sichtweite der Kamera). */
	private void preview(Host host) {
		List<Keyframe> keys = store.get(key);
		if (keys.isEmpty() || !host.camera(scratch)) return;
		for (Keyframe k : keys) {
			if (near(k.x, k.y, k.z)) host.particle(true, k.x, k.y, k.z);
		}
		CameraPath p = CameraPath.of(keys, settings.smoothing.get().path, settings.timing.get().path, settings.seconds.get(), false);
		if (p == null) return;
		for (double[] pt : p.sample(PREVIEW_STEP, PREVIEW_MAX)) {
			if (near(pt[0], pt[1], pt[2])) host.particle(false, pt[0], pt[1], pt[2]);
		}
	}

	private boolean near(double x, double y, double z) {
		double dx = x - scratch.x;
		double dy = y - scratch.y;
		double dz = z - scratch.z;
		double d = dx * dx + dy * dy + dz * dz;
		return d <= PREVIEW_RANGE * PREVIEW_RANGE && d >= PREVIEW_MIN * PREVIEW_MIN;
	}

	private double elapsed() {
		return (clock.getAsLong() - startNanos) / 1e9;
	}

	/** Wie {@link #frame(Host)}, aber ohne Welt (keine Wände) und ohne Tasten – für Tests. */
	public CameraPose frame() {
		return frame(null);
	}

	/**
	 * Kamera für dieses Bild oder null (weder Fahrt noch freie Kamera). Das Ergebnis wird wiederverwendet – sofort
	 * auslesen. Auch nach dem Ende (bis zum nächsten Tick) bleibt die Kamera auf dem letzten Keyframe stehen. Sichtfeld
	 * 0 = nicht ändern (freie Kamera: normales Sichtfeld inkl. Zoom).
	 *
	 * <p>Darf mehrmals je Bild aufgerufen werden (Kamera und Sichtfeld): die Bewegung richtet sich nach der Uhr.
	 */
	public CameraPose frame(Host host) {
		blocksHost = host;
		CameraCollision.Blocks walls = host != null && fairPlay() ? blocks : null;
		if (playing && path != null) {
			path.pose(elapsed(), framePose);
			if (walls != null) clampPlayback(walls);
			return framePose;
		}
		if (freecam.active()) {
			input.clear();
			if (testInput != null) input.set(testInput);
			else if (host != null) host.freecamInput(input);
			freecam.update(input, settings.freecamSpeed.get(), walls, clock.getAsLong());
			return freecam.pose(framePose);
		}
		return null;
	}

	/**
	 * Fair Play beim Abspielen: von der zuletzt gezeigten Position zur Bahn-Position fahren, an Wänden anhalten (die
	 * Kamera gleitet an ihnen entlang). Der Blick folgt weiter der Bahn.
	 */
	private void clampPlayback(CameraCollision.Blocks walls) {
		if (!playPosSet) {
			playPos[0] = framePose.x;
			playPos[1] = framePose.y;
			playPos[2] = framePose.z;
			CameraCollision.settle(playPos, walls);
			playPosSet = true;
		} else {
			CameraCollision.move(playPos, framePose.x - playPos[0], framePose.y - playPos[1], framePose.z - playPos[2], walls);
		}
		framePose.x = playPos[0];
		framePose.y = playPos[1];
		framePose.z = playPos[2];
	}

	/** Läuft gerade eine Fahrt? (Eingaben werden dann ignoriert.) */
	public boolean playing() {
		return playing;
	}

	/** Keyframes der aktuellen Welt (für Anzeige/Tests). */
	public int keyframes() {
		return key.isEmpty() ? 0 : store.get(key).size();
	}

	/** Dauer der laufenden Fahrt in Sekunden (0 = keine). */
	public double duration() {
		return path == null ? 0 : path.duration();
	}

	private boolean refreshKey(Host host) {
		String world = host.worldKey();
		if (world == null || world.isEmpty()) return false;
		key = CinematicStore.key(world, host.dimension());
		return true;
	}

	private void save() {
		try {
			store.saveIfDirty();
		} catch (IOException ignored) {
			// Beim nächsten Ändern erneut
		}
	}
}
