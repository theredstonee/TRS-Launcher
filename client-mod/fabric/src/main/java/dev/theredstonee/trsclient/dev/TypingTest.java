package dev.theredstonee.trsclient.dev;

import com.sun.jna.Function;
import com.sun.jna.Pointer;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.menu.ModMenu;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Selbsttest Tippen im TRS-Menü ({@code -PtrsAutotestOnly=typing}): öffnet das Menü, klickt ins Suchfeld und tippt
 * über das Betriebssystem – Windows-Nachrichten {@code WM_CHAR}/{@code WM_KEYDOWN} an das Spielfenster, also derselbe
 * Weg wie echte Tasten (GLFW bzw. ab 26.3 SDL → Minecrafts KeyboardHandler → Bildschirm). Geprüft: Text kommt an,
 * Rücktaste, Esc leert, Enter nimmt den Fokus, Tippen ohne Fokus landet in der Suche. Ergebnis im Log
 * ({@code [Autotest] Tippen: OK} bzw. {@code FEHLER}), Screenshots trsclient-&lt;mc&gt;-typing-*.png.
 * <p>
 * SDL (26.3) liefert Tasten nur an das Fenster mit Tastaturfokus. Hat das Spielfenster ihn nicht (anderes Fenster
 * vorn), geht der Test über Minecrafts eigenen Eingang ({@code KeyboardHandler#textInput}) – aber nur, wenn SDL die
 * Texteingabe an hat; sonst hätte SDL die Zeichen verworfen und der Test zählt sie als verloren.
 */
public final class TypingTest {
	private static final int WM_KEYDOWN = 0x0100;
	private static final int WM_KEYUP = 0x0101;
	private static final int WM_CHAR = 0x0102;
	private static final int VK_BACK = 0x08;
	private static final int VK_RETURN = 0x0D;
	private static final int VK_ESCAPE = 0x1B;

	private int phase;
	private int wait;
	private final List<String> errors = new ArrayList<>();
	/** "os" = Windows-Nachrichten, "handler" = Minecrafts Eingang (nur SDL ohne Fokus), "none" = nicht möglich. */
	private String mode = "none";
	private long hwnd;

	public static void install() {
		TypingTest test = new TypingTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		Screen screen = Mc.screen();
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || screen == null) return;
				mc.options.pauseOnLostFocus = false;
				// Kein Fokus-Klau (der Nutzer arbeitet evtl. gerade in einem anderen Fenster): ohne Fokus nimmt GLFW die
				// Nachrichten trotzdem, SDL nicht – dann Minecrafts Eingang (siehe Klassenkommentar).
				hwnd = hwnd();
				Mc.setScreen(new TrsMenuScreen(screen));
				phase++;
				wait = 20;
				return;
			case 1: {
				ModMenu menu = menu();
				if (menu == null) {
					errors.add("TRS-Menü nicht offen");
					finish(mc);
					return;
				}
				// Echter Klick ins Suchfeld (Mitte des Feldes aus dem letzten Frame).
				int[] box = menu.testSearchBox();
				double x = box[0] + box[2] / 2.0;
				double y = box[1] + box[3] / 2.0;
				UiScreen ui = ((TrsMenuScreen) Mc.screen()).ui();
				ui.inputClick(x, y, 0);
				ui.inputRelease(x, y, 0);
				check(menu.testSearchFocused(), "Klick fokussiert das Suchfeld");
				mode = chooseMode();
				log("Eingabeweg: " + mode + " (Fenster " + Long.toHexString(hwnd) + ", " + backend() + ")");
				phase++;
				wait = 5;
				return;
			}
			case 2:
				type("Zoom");
				phase++;
				wait = 10;
				return;
			case 3:
				expect("Zoom", "Tippen ins Suchfeld");
				shot(mc, "search");
				key(VK_BACK, 0x0E, "key.keyboard.backspace");
				phase++;
				wait = 10;
				return;
			case 4:
				expect("Zoo", "Rücktaste");
				key(VK_ESCAPE, 0x01, "key.keyboard.escape");
				phase++;
				wait = 10;
				return;
			case 5:
				expect("", "Esc leert die Suche");
				key(VK_RETURN, 0x1C, "key.keyboard.enter");
				phase++;
				wait = 10;
				return;
			case 6: {
				ModMenu menu = menu();
				check(menu != null && !menu.testSearchFocused(), "Enter nimmt den Fokus");
				type("fps");
				phase++;
				wait = 10;
				return;
			}
			case 7: {
				expect("fps", "Tippen ohne Fokus landet in der Suche");
				ModMenu menu = menu();
				check(menu != null && menu.testSearchFocused(), "Suchfeld wieder fokussiert");
				shot(mc, "typeahead");
				finish(mc);
				return;
			}
			default:
		}
	}

	private void finish(Minecraft mc) {
		if (errors.isEmpty()) log("Tippen: OK (" + mode + ", " + backend() + ")");
		else log("Tippen: FEHLER (" + mode + ", " + backend() + "): " + String.join("; ", errors));
		phase = 99;
		Mc.setScreen(null);
		mc.stop();
	}

	private static ModMenu menu() {
		Screen s = Mc.screen();
		if (!(s instanceof TrsMenuScreen)) return null;
		UiScreen ui = ((TrsMenuScreen) s).ui();
		return ui instanceof ModMenu ? (ModMenu) ui : null;
	}

	private void expect(String want, String what) {
		ModMenu menu = menu();
		String got = menu == null ? null : menu.testSearchText();
		log(what + ": \"" + got + "\" (erwartet \"" + want + "\")");
		if (!want.equals(got)) errors.add(what + ": \"" + got + "\" statt \"" + want + "\"");
	}

	private void check(boolean ok, String what) {
		log(what + ": " + (ok ? "ja" : "NEIN"));
		if (!ok) errors.add(what);
	}

	// --- Eingabe ---

	private String chooseMode() {
		if (hwnd == 0) return sdl() ? "handler" : "none";
		//? if >=26.3 {
		/*if (org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardFocus() != handle()) return "handler";
		*///?}
		return "os";
	}

	/** Zeichen tippen (je Zeichen ein WM_CHAR wie nach TranslateMessage). */
	private void type(String text) {
		if ("os".equals(mode)) {
			for (int i = 0; i < text.length(); i++) {
				user32Int("PostMessageW", new Pointer(hwnd), WM_CHAR, (long) text.charAt(i), 1L);
			}
			return;
		}
		if ("handler".equals(mode)) {
			//? if >=26.3 {
			/*// Wie SDL: ohne aktive Texteingabe kommt kein SDL_EVENT_TEXT_INPUT.
			if (!org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(handle())) {
				log("SDL-Texteingabe aus – \"" + text + "\" geht verloren (wie beim Spieler)");
				return;
			}
			Mc.mc().keyboardHandler.textInput(handle(), text);
			*///?}
			return;
		}
		errors.add("keine Eingabe möglich (kein Windows-Fenster)");
	}

	/** Taste drücken und loslassen (WM_KEYDOWN/WM_KEYUP mit Scancode). */
	private void key(int vk, int scancode, String keyName) {
		if ("os".equals(mode)) {
			long down = 1L | ((long) scancode << 16);
			long up = down | (1L << 30) | (1L << 31);
			user32Int("PostMessageW", new Pointer(hwnd), WM_KEYDOWN, (long) vk, down);
			user32Int("PostMessageW", new Pointer(hwnd), WM_KEYUP, (long) vk, up);
			return;
		}
		if ("handler".equals(mode)) {
			//? if >=26.3 {
			/*int code = dev.theredstonee.trsclient.compat.Keys.code(keyName);
			Mc.mc().keyboardHandler.keyPress(handle(), 1, new net.minecraft.client.input.KeyEvent(code, 0, 0));
			Mc.mc().keyboardHandler.keyPress(handle(), 0, new net.minecraft.client.input.KeyEvent(code, 0, 0));
			*///?}
		}
	}

	// --- Fenster ---

	private static long handle() {
		//? if >=1.21.9 {
		/*return Mc.window().handle();
		*///?} else
		return Mc.window().getWindow();
	}

	private static boolean sdl() {
		//? if >=26.3 {
		/*return true;
		*///?} else
		return false;
	}

	private static String backend() {
		return sdl() ? "SDL" : "GLFW";
	}

	/** HWND des Spielfensters (0 außerhalb von Windows). */
	private static long hwnd() {
		if (!System.getProperty("os.name", "").startsWith("Windows")) return 0;
		try {
			//? if >=26.3 {
			/*return org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty(org.lwjgl.sdl.SDLVideo.SDL_GetWindowProperties(handle()),
					org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
			*///?} else
			return org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle());
		} catch (Throwable t) {
			log("Fenster-Handle: " + t);
			return 0;
		}
	}

	private static int user32Int(String name, Object... args) {
		return Function.getFunction("user32", name, Function.ALT_CONVENTION).invokeInt(args);
	}

	private static void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-typing-" + name);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] {}", text);
	}
}
