package dev.theredstonee.trsclient.core.i18n;

import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.Setting;
import dev.theredstonee.trsclient.core.module.TextSetting;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Paint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Passen die Texte in die Kacheln und Leisten des Menüs? Gemessen mit den Zeichenbreiten der
 * Minecraft-Standardschrift (inkl. 1 px Abstand). Voll übersetzte Sprachen müssen passen,
 * Beta-Sprachen werden nur gemeldet – dort kürzt das Menü mit "…".
 */
class TextFitTest {
	/** Kachel in normaler Breite (TILE_MIN_W 116): Name 116 - 16 - 12 (Zahnrad) Pixel, zwei Zeilen. */
	private static final int TILE_NAME_W = 116 - 16 - 12;
	/**
	 * Leiste im schmalen Fenster (854×480 mit GUI-Größe 2 – der Minecraft-Standard): railW 96 - PAD 10 - 6,
	 * Text ab x + 19 mit 3 px Rand; die Fußzeile ("10 von 34 an") ab x + 14.
	 */
	private static final int RAIL_TEXT_W = 96 - 10 - 6 - 22;
	private static final int RAIL_FOOTER_W = 96 - 10 - 6 - 14;
	/** Seitenleiste des HUD-Editors (PANEL_W 174 - 16): Schalter-Zeilen w - 34, Regler/Farben w - 90. */
	private static final int EDITOR_BOOL_W = 174 - 16 - 34;
	private static final int EDITOR_CONTROL_W = 174 - 16 - 90;
	/** Einstellungsseite im schmalen Fenster (Inhalt 295 - 10 px, Regler/Farbe/Auswahl w - 90). */
	private static final int SETTING_LABEL_W = 295 - 10 - 90;
	/** Lampen-Knöpfe des Startbildschirms: halbe Breite (BTN_W 200) minus Rand. */
	private static final int LAMP_HALF_W = (200 - 6) / 2 - 10;
	private static final int LAMP_FULL_W = 200 - 10;

	@AfterEach
	void english() {
		I18n.use("en");
	}

	@Test
	void moduleNamesFitTheTilesAndRailLabelsFit() {
		Canvas c = new McFontCanvas();
		TrsModules modules = new TrsModules();
		List<String> problems = new ArrayList<String>();
		for (String lang : I18n.LANGUAGES) {
			I18n.use(lang);
			for (Module m : modules.registry.all()) {
				List<String> lines = Paint.wrap(c, m.name(), TILE_NAME_W);
				boolean fits = lines.size() <= 2;
				for (String line : lines) fits &= c.textWidth(line) <= TILE_NAME_W;
				if (!fits) problems.add(lang + " Kachel: " + m.name() + " " + lines);
			}
			for (String key : new String[]{"menu.all", "menu.hudEditor", "menu.profiles", "menu.packs",
					"category.hud", "category.pvp", "category.chat", "category.world", "category.misc"}) {
				String s = I18n.tr(key);
				if (c.textWidth(s) > RAIL_TEXT_W) problems.add(lang + " Leiste: " + s + " (" + c.textWidth(s) + " px)");
			}
			String footer = I18n.tr("menu.activeCount", 19, 34);
			if (c.textWidth(footer) > RAIL_FOOTER_W) problems.add(lang + " Leiste: " + footer + " (" + c.textWidth(footer) + " px)");
			HudModule hud = modules.fps;
			for (Setting s : new Setting[]{hud.textShadow, hud.textColor, hud.scale, hud.backgroundOpacity}) {
				int max = s instanceof BoolSetting ? EDITOR_BOOL_W : EDITOR_CONTROL_W;
				if (c.textWidth(s.label()) > max) problems.add(lang + " HUD-Editor: " + s.label() + " (" + c.textWidth(s.label()) + " px)");
			}
			for (Module m : modules.registry.all()) {
				for (Setting s : m.settings()) {
					if (!(s instanceof BoolSetting) && !(s instanceof TextSetting) && c.textWidth(s.label()) > SETTING_LABEL_W) {
						problems.add(lang + " Einstellung: " + s.label() + " (" + c.textWidth(s.label()) + " px)");
					}
				}
			}
			for (String key : new String[]{"title.singleplayer", "title.multiplayer", "title.options", "title.trsMenu",
					"title.mods", "title.quit"}) {
				String s = I18n.tr(key);
				// Einzel-/Mehrspieler sind volle Zeilen, der Rest halbe.
				int max = key.endsWith("player") ? LAMP_FULL_W : LAMP_HALF_W;
				if (c.textWidth(s) > max) problems.add(lang + " Startbildschirm: " + s + " (" + c.textWidth(s) + " px)");
			}
		}
		List<String> hard = new ArrayList<String>();
		for (String p : problems) {
			System.out.println("Passt nicht ganz: " + p);
			String lang = p.substring(0, p.indexOf(' '));
			if (!I18n.BETA.contains(lang)) hard.add(p);
		}
		assertTrue(hard.isEmpty(), "Texte zu breit: " + hard);
	}

	@Test
	void longHyphenatedWordsBreakAtTheHyphen() {
		Canvas c = new McFontCanvas();
		List<String> lines = Paint.wrap(c, "TRS-Online-Funktionen", TILE_NAME_W);
		assertEquals(2, lines.size(), lines.toString());
		assertEquals("TRS-Online-", lines.get(0));
		assertEquals("Funktionen", lines.get(1));
		lines = Paint.wrap(c, "Kein Schadens-Wackeln", TILE_NAME_W);
		assertEquals(2, lines.size(), lines.toString());
		assertEquals("Kein Schadens-", lines.get(0));
		assertEquals("Kein Schadens-Wackeln", Paint.join(lines, 0));
	}

	/** Breiten der Minecraft-ASCII-Schrift (Glyphe + 1 px Abstand). */
	static final class McFontCanvas implements Canvas {
		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { }
		@Override public void text(String text, int x, int y, int argb, boolean shadow) { }
		@Override public int lineHeight() { return 9; }
		@Override public void flush() { }
		@Override public void scissor(int x1, int y1, int x2, int y2) { }
		@Override public void noScissor() { }
		@Override public void raise(float z) { }
		@Override public void push() { }
		@Override public void translate(float x, float y) { }
		@Override public void scale(float factor) { }
		@Override public void pop() { }

		@Override
		public int textWidth(String text) {
			int w = 0;
			for (int i = 0; i < text.length(); i++) w += width(text.charAt(i));
			return w;
		}

		@Override
		public String clip(String text, int maxWidth) {
			int w = 0;
			for (int i = 0; i < text.length(); i++) {
				w += width(text.charAt(i));
				if (w > maxWidth) return text.substring(0, i);
			}
			return text;
		}

		static int width(char ch) {
			switch (ch) {
				case 'i': case '!': case ',': case '.': case ':': case ';': case '|': case '\'': case 'í': case 'ì': case 'ı':
					return 2;
				case 'l': case '`':
					return 3;
				case 't': case 'I': case '[': case ']': case 'İ':
					return 4;
				case ' ':
					return 4;
				case 'f': case 'k': case '(': case ')': case '{': case '}': case '"': case '*': case '<': case '>':
					return 5;
				case '@': case '~': case '…': case '–':
					return 7;
				default:
					return 6;
			}
		}
	}
}
