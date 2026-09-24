package dev.theredstonee.trsclient.core.perf;

/**
 * Dynamische FPS: im Hintergrund, minimiert oder bei längerer Untätigkeit (AFK) wird die
 * Bildrate begrenzt und der Ton optional leiser. Reine Logik – der Loader meldet je Bild den
 * Fensterzustand und bekommt die Grenze zurück; das Schlafen übernimmt {@link FramePacer}.
 */
public final class DynamicFps {
	/** Warum gerade begrenzt wird. */
	public enum State {
		ACTIVE, UNFOCUSED, MINIMIZED, AFK
	}

	private long lastInput;
	private double lastMouseX = Double.NaN;
	private double lastMouseY = Double.NaN;
	private State state = State.ACTIVE;

	/**
	 * Eingaben des Spielers (Mausbewegung, gedrückte Tasten, offener Bildschirm) – je Bild einmal.
	 * @param anyKey irgendeine Taste/Maustaste gerade gedrückt
	 */
	public void input(long nowMillis, double mouseX, double mouseY, boolean anyKey) {
		if (lastInput == 0) lastInput = nowMillis;
		boolean moved = !Double.isNaN(lastMouseX) && (Math.abs(mouseX - lastMouseX) > 0.5 || Math.abs(mouseY - lastMouseY) > 0.5);
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		if (moved || anyKey) lastInput = nowMillis;
	}

	/** Eingabe von außen melden (z. B. Fenster bekommt den Fokus zurück). */
	public void touch(long nowMillis) {
		lastInput = nowMillis;
	}

	/** Zeit seit der letzten Eingabe. */
	public long idleMillis(long nowMillis) {
		return lastInput == 0 ? 0 : Math.max(0, nowMillis - lastInput);
	}

	/**
	 * Zustand für dieses Bild.
	 * @param afkMinutes Minuten ohne Eingabe bis AFK (≤ 0 = nie)
	 */
	public State update(long nowMillis, boolean focused, boolean minimized, double afkMinutes) {
		// Zurück im Fenster: AFK-Zeit neu starten, damit es nicht sofort wieder begrenzt.
		if (focused && !minimized && (state == State.UNFOCUSED || state == State.MINIMIZED)) touch(nowMillis);
		State next;
		if (minimized) next = State.MINIMIZED;
		else if (!focused) next = State.UNFOCUSED;
		else if (afkMinutes > 0 && idleMillis(nowMillis) >= (long) (afkMinutes * 60_000L)) next = State.AFK;
		else next = State.ACTIVE;
		state = next;
		return next;
	}

	public State state() {
		return state;
	}

	/**
	 * Bildrate für den Zustand (0 = unbegrenzt).
	 * @param afkFps 0 = AFK begrenzt nicht
	 */
	public static int limit(State state, int unfocusedFps, int minimizedFps, int afkFps) {
		switch (state) {
			case MINIMIZED:
				return clampFps(minimizedFps);
			case UNFOCUSED:
				return clampFps(unfocusedFps);
			case AFK:
				return afkFps <= 0 ? 0 : clampFps(afkFps);
			default:
				return 0;
		}
	}

	/** Lautstärke-Faktor (1 = unverändert): nur im Hintergrund/minimiert leiser. */
	public static float volume(State state, boolean quieter, double percent) {
		if (!quieter || (state != State.UNFOCUSED && state != State.MINIMIZED)) return 1f;
		return (float) Math.max(0, Math.min(1, percent / 100.0));
	}

	private static int clampFps(int fps) {
		return Math.max(1, Math.min(240, fps));
	}
}
