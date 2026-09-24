package dev.theredstonee.trsclient.core.camera;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;

/**
 * Freelook: eigene Kamera-Blickwinkel, die sich bei gehaltener (oder umgeschalteter) Taste
 * unabhängig von der Spielfigur drehen. Mausbewegung wird wie bei Minecraft skaliert
 * (0,15° je Einheit), der Neigungswinkel bleibt wie bei Vanilla zwischen -90° und 90°.
 * <p>
 * Fair gegenüber Servern: die Spielfigur dreht sich währenddessen nicht – der Server bekommt
 * genau die Blickrichtung, die er ohne Freelook auch bekäme.
 */
public final class FreelookState {
	private static final float SENSITIVITY = 0.15F;
	public static final float MAX_PITCH = 90.0F;

	/** Aus welcher Richtung die Kamera während des Freelooks auf die Spielfigur schaut. */
	public enum Perspective implements ChoiceSetting.Option {
		/** Dritte Person von hinten (Kamera-Modus 1). */
		BACK("Behind", 1),
		/** Dritte Person von vorne (Kamera-Modus 2). */
		FRONT("In front", 2);

		private final String label;
		private final int cameraMode;

		Perspective(String label, int cameraMode) {
			this.label = label;
			this.cameraMode = cameraMode;
		}

		@Override
		public String label() {
			return label;
		}

		/** Kamera-Modus wie in Minecrafts Optionen: 0 = Ego, 1 = hinten, 2 = vorne. */
		public int cameraMode() {
			return cameraMode;
		}
	}

	private boolean active;
	private float yaw;
	private float pitch;
	/** Umschalt-Modus: eingeschaltet? */
	private boolean toggled;
	private boolean lastKey;
	/** Taste gedrückt, aber auf diesem Server gesperrt → einmal Hinweis zeigen. */
	private boolean blockedNotice;

	/**
	 * Tasten-Logik (einmal pro Tick).
	 *
	 * @param keyDown      Freelook-Taste gedrückt
	 * @param toggleMode   Umschalten statt Halten
	 * @param inputAllowed Tastendrücke zählen (Spielfigur da, kein Menü offen)
	 * @param enabled      Modul an und eine Spielfigur vorhanden
	 * @param blockedHere  aktueller Server steht in der Sperrliste
	 * @return ob Freelook jetzt aktiv sein soll
	 */
	public boolean wanted(boolean keyDown, boolean toggleMode, boolean inputAllowed, boolean enabled, boolean blockedHere) {
		boolean pressed = keyDown && !lastKey && inputAllowed;
		lastKey = keyDown;
		if (pressed && enabled && blockedHere) blockedNotice = true;
		boolean allowed = enabled && !blockedHere;
		if (!allowed || !toggleMode) {
			toggled = false;
		} else if (pressed) {
			toggled = !toggled;
		}
		if (!allowed) return false;
		return toggleMode ? toggled : keyDown && inputAllowed;
	}

	/** Einmaliger Hinweis „auf diesem Server deaktiviert“ (danach false). */
	public boolean consumeBlockedNotice() {
		boolean n = blockedNotice;
		blockedNotice = false;
		return n;
	}

	/** Startet mit dem aktuellen Blick der Spielfigur. */
	public void start(float playerYaw, float playerPitch) {
		active = true;
		yaw = playerYaw;
		pitch = clampPitch(playerPitch);
	}

	public void stop() {
		active = false;
		toggled = false;
	}

	/** Mausbewegung (wie {@code Entity#turn(yRot, xRot)}). */
	public void turn(double yawDelta, double pitchDelta) {
		yaw = wrapNear(yaw + (float) yawDelta * SENSITIVITY);
		pitch = clampPitch(pitch + (float) pitchDelta * SENSITIVITY);
	}

	public boolean active() {
		return active;
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	/** Neigung wie bei Vanilla begrenzen (NaN → 0). */
	public static float clampPitch(float p) {
		if (Float.isNaN(p)) return 0.0F;
		return Math.max(-MAX_PITCH, Math.min(MAX_PITCH, p));
	}

	/**
	 * Hält den Gierwinkel in einem vernünftigen Zahlenbereich (sehr viele Umdrehungen würden die
	 * float-Genauigkeit fressen), ohne die Richtung zu ändern.
	 */
	static float wrapNear(float yaw) {
		if (Float.isNaN(yaw) || Float.isInfinite(yaw)) return 0.0F;
		if (yaw > 36000.0F || yaw < -36000.0F) {
			yaw = yaw % 360.0F;
		}
		return yaw;
	}
}
