package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.cinematic.CameraPath;

/**
 * Kamera-Fahrten (TRS Client {@link NewSince#CINEMATIC}): Keyframes an der aktuellen Kamera setzen, die Fahrt abspielen
 * (Kamera löst sich von der Spielfigur, HUD aus, optional Aufnahme im TRS Launcher) – je Welt/Server gespeichert.
 * Dazu die freie Kamera (Freecam), aus der heraus man Keyframes setzen kann.
 *
 * <p>Logik in {@code core.cinematic}; die Bäume liefern nur Tasten, Kamera-/Sichtfeld-Hooks und Partikel.
 */
public final class CinematicModules {
	public final Module cinematic;
	/** Keyframe an der aktuellen Kamera setzen (Vanilla-Belegung, standardmäßig unbelegt). */
	public final KeySetting addKey;
	/** Fahrt abspielen / anhalten. */
	public final KeySetting playKey;
	/** Letzten Keyframe entfernen. */
	public final KeySetting removeKey;
	/** Alle Keyframes dieser Welt löschen. */
	public final KeySetting clearKey;
	/** Freie Kamera an/aus (standardmäßig unbelegt). */
	public final KeySetting freecamKey;
	/** Geschwindigkeit der freien Kamera (Blöcke je Sekunde; Mausrad ändert sie in der freien Kamera). */
	public final NumberSetting freecamSpeed;
	/**
	 * Fair Play: freie Kamera und Fahrten halten an massiven, undurchsichtigen Blöcken an (nur durch Durchsichtiges
	 * und Durchgänge). Der Server kann es erzwingen ({@code core.cinematic.FreecamRules}).
	 */
	public final BoolSetting freecamFairPlay;
	public final ChoiceSetting<Timing> timing;
	public final NumberSetting seconds;
	public final ChoiceSetting<Smoothing> smoothing;
	public final BoolSetting ease;
	public final BoolSetting hideHud;
	public final BoolSetting record;
	public final BoolSetting preview;

	/** Zeitplan der Fahrt. */
	public enum Timing implements ChoiceSetting.Option {
		PER_SEGMENT("Seconds per segment", CameraPath.Timing.PER_SEGMENT),
		TOTAL("Seconds for the whole path (even speed)", CameraPath.Timing.TOTAL);

		private final String label;
		public final CameraPath.Timing path;

		Timing(String label, CameraPath.Timing path) {
			this.label = label;
			this.path = path;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Kurve zwischen den Keyframes. */
	public enum Smoothing implements ChoiceSetting.Option {
		SMOOTH("Smooth (Catmull-Rom)", CameraPath.Smoothing.SMOOTH),
		LINEAR("Linear", CameraPath.Smoothing.LINEAR);

		private final String label;
		public final CameraPath.Smoothing path;

		Smoothing(String label, CameraPath.Smoothing path) {
			this.label = label;
			this.path = path;
		}

		@Override
		public String label() {
			return label;
		}
	}

	CinematicModules(ModuleRegistry registry) {
		cinematic = registry.register(new Module("cinematic", "Camera Paths",
				"Cinematic camera flights for videos and screenshots: set keyframes at your current camera (position, "
						+ "view and field of view) and play the path back smoothly. While it plays, the camera leaves your "
						+ "character, the HUD hides and the launcher can record. Paths are saved per world and server. "
						+ "Freecam lets the camera fly freely (WASD, space, sneak, scroll for speed) while your character "
						+ "stays put – set keyframes from there. Bind the keys under Controls → TRS Client.", true));
		addKey = cinematic.add(new KeySetting("addKey", "Add keyframe"));
		playKey = cinematic.add(new KeySetting("playKey", "Play / stop"));
		removeKey = cinematic.add(new KeySetting("removeKey", "Remove last keyframe"));
		clearKey = cinematic.add(new KeySetting("clearKey", "Clear path"));
		freecamKey = cinematic.add(new KeySetting("freecamKey", "Freecam on / off"));
		freecamSpeed = cinematic.add(new NumberSetting("freecamSpeed", "Freecam speed", 10, 0.5, 100, 0.5, "", " m/s"));
		freecamFairPlay = cinematic.add(new BoolSetting("freecamFairPlay",
				"Fair Play (the camera stops at solid blocks, also while a path plays)", true));
		timing = cinematic.add(new ChoiceSetting<>("timing", "Timing", Timing.class, Timing.PER_SEGMENT));
		seconds = cinematic.add(new NumberSetting("seconds", "Seconds", 5, 0.5, 120, 0.5, "", " s"));
		smoothing = cinematic.add(new ChoiceSetting<>("smoothing", "Curve", Smoothing.class, Smoothing.SMOOTH));
		ease = cinematic.add(new BoolSetting("ease", "Start and stop gently", true));
		hideHud = cinematic.add(new BoolSetting("hideHud", "Hide HUD while playing", true));
		record = cinematic.add(new BoolSetting("record", "Record with the TRS Launcher while playing", false));
		preview = cinematic.add(new BoolSetting("preview", "Show the path while editing", true));
		cinematic.icon("camera").category(Category.MISC);
	}
}
