package dev.theredstonee.trsclient.core.touch;

/**
 * Was der Touch-Modus je Spiel-Tick braucht: die festen Tasten der Overlay-Knöpfe abfragen und die
 * Bildschirmtastatur führen. Jeder Baum ruft {@link #tick} aus seinem Client-Tick; ohne Touch-Modus kehrt es sofort
 * zurück.
 *
 * <p>Feste Tasten (nicht umbelegbar, nur im Touch-Modus, in der Steuerung nicht sichtbar) – das Overlay drückt sie
 * über {@code GameInput.sendKey} (GLFW-Codes; unter LWJGL 2 übersetzt die Engine sie):
 * <ul>
 *   <li>{@link #KEY_MENU} F13 (GLFW 302, LWJGL 2: 100) – TRS-Menü öffnen (Overlay-Aktion {@code trsMenu})</li>
 *   <li>{@link #KEY_EMOTE_WHEEL} F14 (GLFW 303, LWJGL 2: 101) – Emote-Rad öffnen (Overlay-Aktion
 *       {@code emoteWheel}); es bleibt offen, ein Finger wählt per Ziehen und Loslassen</li>
 *   <li>{@link #KEY_HUD_EDITOR} F15 (GLFW 304, LWJGL 2: 102) – HUD-Editor öffnen</li>
 * </ul>
 */
public final class TouchRuntime {
	public static final String KEY_MENU = "key.keyboard.f13";
	public static final String KEY_EMOTE_WHEEL = "key.keyboard.f14";
	public static final String KEY_HUD_EDITOR = "key.keyboard.f15";

	/** GLFW-Codes (für die Doku/Engine). */
	public static final int GLFW_MENU = 302;
	public static final int GLFW_EMOTE_WHEEL = 303;
	public static final int GLFW_HUD_EDITOR = 304;

	/** Was der Tick vom Spiel braucht. */
	public interface Platform {
		/** Ist die Taste gerade gedrückt ({@code "key.keyboard.f13"})? */
		boolean keyDown(String keyName);

		/** Ist gerade irgendein Bildschirm offen? */
		boolean screenOpen();

		/** Liegt eine Welt vor (Emote-Rad nur im Spiel)? */
		boolean inWorld();

		void openMenu();

		void openEmoteWheel();

		void openHudEditor();

		/** Fokussiertes Vanilla-Textfeld ({@link TouchKeyboard#FIELD_CHAT} …) oder null. */
		String vanillaField();

		/** GUI-Skalierung (Fensterpixel je GUI-Pixel). */
		double guiScale();
	}

	/** Aktionen der festen Tasten. */
	public enum Action {
		MENU, EMOTE_WHEEL, HUD_EDITOR
	}

	/** Flankenerkennung der festen Tasten (Drücken löst einmal aus). */
	static final class Edges {
		private final boolean[] down = new boolean[Action.values().length];

		/** @return die Aktion, deren Taste gerade gedrückt wurde (höchstens eine je Aufruf), sonst null */
		Action update(boolean menu, boolean wheel, boolean hud) {
			boolean[] now = {menu, wheel, hud};
			Action fired = null;
			for (int i = 0; i < now.length; i++) {
				// Gleichzeitig gedrückt: die erste gewinnt; erneut auslösbar erst nach dem Loslassen.
				if (now[i] && !down[i] && fired == null) fired = Action.values()[i];
				down[i] = now[i];
			}
			return fired;
		}
	}

	private static final Edges EDGES = new Edges();

	private TouchRuntime() {
	}

	/** Je Client-Tick (Spiel-Thread). */
	public static void tick(Platform p) {
		if (!TouchMode.enabled() || p == null) return;
		try {
			TouchMode.setGuiScale(p.guiScale());
			Action a = EDGES.update(p.keyDown(KEY_MENU), p.keyDown(KEY_EMOTE_WHEEL), p.keyDown(KEY_HUD_EDITOR));
			if (a != null && !p.screenOpen()) {
				switch (a) {
					case MENU:
						p.openMenu();
						break;
					case EMOTE_WHEEL:
						if (p.inWorld()) p.openEmoteWheel();
						break;
					case HUD_EDITOR:
						p.openHudEditor();
						break;
					default:
						break;
				}
			}
			TouchKeyboard.tick(p.vanillaField(), System.currentTimeMillis());
		} catch (RuntimeException e) {
			// Touch-Hilfen dürfen das Spiel nie stören.
		}
	}
}
