package dev.theredstonee.trsclient.core.cinematic;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.CinematicModules;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Kamera-Fahrten – versionsunabhängige Steuerung. Je Minecraft-Version liefert der {@link Host} nur: Weltschlüssel,
 * aktuelle Kamera, Aktionsleiste, HUD an/aus, Partikel und den Aufnahme-Knopf; die Bäume rufen die Tasten-Aktionen,
 * {@link #tick} einmal je Client-Tick und {@link #frame} einmal je Bild (Kamera/Sichtfeld).
 *
 * <p>Abgespielt wird nach der Wanduhr ({@link System#nanoTime()}): Position, Blick und Sichtfeld werden je Bild aus
 * der verstrichenen Zeit berechnet – unabhängig von Bildrate und Tick-Takt. Nur Spiel-/Render-Thread.
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
	}

	/** Vorschau: alle so viele Ticks die Bahn neu zeichnen. */
	static final int PREVIEW_TICKS = 10;
	/** Abstand der Vorschau-Punkte (Blöcke) und Höchstzahl je Durchgang. */
	static final double PREVIEW_STEP = 0.75;
	static final int PREVIEW_MAX = 160;
	/** Sichtweite der Vorschau (Blöcke). */
	static final double PREVIEW_RANGE = 96;

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
	/** Für Tests: feste Uhr (nanoTime). */
	private java.util.function.LongSupplier clock = System::nanoTime;

	public Cinematic(CinematicModules settings, CinematicStore store) {
		this.settings = settings;
		this.store = store;
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

	// --- Takt ---

	/**
	 * Einmal je Client-Tick: Ende der Fahrt erkennen, Welt-/Dimensionswechsel, Vorschau-Partikel.
	 *
	 * @param screenOpen ein Menü ist offen (z. B. Pause) – dann endet eine laufende Fahrt
	 */
	public void tick(Host host, boolean screenOpen) {
		String world = host.worldKey();
		if (world == null || world.isEmpty()) {
			if (playing) stop(host);
			key = "";
			return;
		}
		String now = CinematicStore.key(world, host.dimension());
		if (!now.equals(key)) {
			if (playing) stop(host);
			key = now;
		}
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
		return dx * dx + dy * dy + dz * dz <= PREVIEW_RANGE * PREVIEW_RANGE;
	}

	private double elapsed() {
		return (clock.getAsLong() - startNanos) / 1e9;
	}

	/**
	 * Kamera für dieses Bild oder null (keine Fahrt). Das Ergebnis wird wiederverwendet – sofort auslesen. Auch nach dem
	 * Ende (bis zum nächsten Tick) bleibt die Kamera auf dem letzten Keyframe stehen.
	 */
	public CameraPose frame() {
		if (!playing || path == null) return null;
		return path.pose(elapsed(), framePose);
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
