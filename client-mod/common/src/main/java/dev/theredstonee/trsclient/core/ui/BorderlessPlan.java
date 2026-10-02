package dev.theredstonee.trsclient.core.ui;

/**
 * Was der Vollbild-Haken diesen Tick tun soll. Reine Entscheidung, ohne GLFW: die Loader setzen sie um.
 * Einrandloses Fenster meldet keinen Monitor, darum ist „schon randlos“ ein eigenes Bit.
 */
public final class BorderlessPlan {
	public enum Step {
		/** Fenstermodus: Lage merken, nichts umschalten. */
		REMEMBER,
		/** Modul an, Vollbild an, noch exklusiv: auf randlos umstellen. */
		ENTER,
		/** Vollbild aus, aber noch randlos: vorige Lage und Rahmen zurück. */
		RESTORE_WINDOW,
		/** Modul aus, während randloses Vollbild: zurück zum exklusiven Vollbild. */
		TO_EXCLUSIVE,
		/** Nichts zu tun (schon im gewünschten Zustand). */
		NONE
	}

	private BorderlessPlan() {
	}

	public static Step step(boolean moduleOn, boolean fullscreenOn, boolean exclusiveNow, boolean borderlessNow) {
		if (!fullscreenOn && !borderlessNow) return Step.REMEMBER;
		if (moduleOn && fullscreenOn && exclusiveNow && !borderlessNow) return Step.ENTER;
		if (borderlessNow && !fullscreenOn) return Step.RESTORE_WINDOW;
		if (borderlessNow && fullscreenOn && !moduleOn) return Step.TO_EXCLUSIVE;
		return Step.NONE;
	}
}
