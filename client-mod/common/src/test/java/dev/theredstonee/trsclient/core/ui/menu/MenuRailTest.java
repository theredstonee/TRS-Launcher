package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.bugreport.BugReports;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.bugreport.BugReportPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Seitenleiste des TRS-Menüs bleibt bei jeder Fenstergröße vollständig erreichbar: passt sie nicht, wird sie
 * abgeschnitten und per Mausrad gescrollt – kein Eintrag ragt unten aus dem Menü.
 */
class MenuRailTest {
	@Test
	void everyRailEntryIsReachableInSmallWindows(@TempDir Path dir) {
		I18n.use("en");
		BugReports.init(dir.resolve("config"), "0.13.0", "1.21.1", "fabric");
		for (int[] size : new int[][]{{427, 240}, {320, 200}, {640, 360}, {960, 540}}) {
			FakeHost host = new FakeHost();
			ModMenu menu = new ModMenu(host);
			RecordingCanvas c = new RecordingCanvas();
			render(menu, c, size);
			// Unterkante des Menüfensters (wie ModMenu: bis 86 % der Höhe, 16 px Rand).
			int ph = Math.min(size[1] - 16, Math.max(Math.min(264, size[1] - 16), Math.min(470, Math.round(size[1] * 0.86f))));
			int bottom = (size[1] - ph) / 2 + ph;
			String label = I18n.tr("menu.bugReport");
			// Bis ganz nach unten scrollen (über der Leiste), der letzte Eintrag muss dann sichtbar im Fenster liegen.
			int[] seen = null;
			for (int i = 0; i < 40 && seen == null; i++) {
				c.clear();
				render(menu, c, size);
				seen = c.visible(label);
				if (seen == null || seen[1] + 8 > bottom) {
					seen = null;
					menu.mouseScrolled(c.railX(), (size[1]) / 2.0, -1);
				}
			}
			assertNotNull(seen, "„" + label + "“ nie sichtbar bei " + size[0] + "×" + size[1]);
			assertTrue(seen[1] + 8 <= bottom, "ragt heraus bei " + size[0] + "×" + size[1]);
			for (int[] t : c.allVisible()) assertTrue(t[1] <= bottom, "Text unter dem Fenster bei " + size[0] + "×" + size[1]);
			// Anklickbar: öffnet „Bug melden“.
			assertTrue(menu.mouseClicked(seen[0] + 2, seen[1] + 3, 0));
			c.clear();
			render(menu, c, size);
			assertNotNull(BugReportPage.current(), "Seite geöffnet bei " + size[0] + "×" + size[1]);
			menu.requestClose();
		}
	}

	private static void render(ModMenu menu, RecordingCanvas c, int[] size) {
		// Einblenden abwarten (fast durchsichtiger Text wird nicht gezeichnet).
		for (int i = 0; i < 40 && menu.alpha() < 0.99f; i++) {
			menu.render(c, size[0], size[1], -100, -100);
			try {
				Thread.sleep(15);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		c.clear();
		menu.render(c, size[0], size[1], -100, -100);
	}

	/** Zeichnet nichts, merkt sich aber, welcher Text wo sichtbar (nicht weggeschnitten) gezeichnet wurde. */
	static final class RecordingCanvas implements Canvas {
		private final List<Object[]> texts = new ArrayList<Object[]>();
		private int[] scissor;
		private int minTextX = Integer.MAX_VALUE;

		void clear() {
			texts.clear();
		}

		int railX() {
			// Leiste = links im Fenster: Text der Einträge beginnt dort.
			int x = Integer.MAX_VALUE;
			for (Object[] t : texts) {
				if ("All".equals(t[0])) x = Math.min(x, (Integer) t[1]);
			}
			return x == Integer.MAX_VALUE ? 30 : x;
		}

		int[] visible(String s) {
			// Leisten-Texte werden auf die Breite gekürzt (Testschrift ist breiter als die echte) → Anfang reicht.
			for (Object[] t : texts) {
				String shown = (String) t[0];
				if (shown.endsWith("…")) shown = shown.substring(0, shown.length() - 1);
				if (s.equals(shown) || (shown.length() >= 4 && s.startsWith(shown))) return new int[]{(Integer) t[1], (Integer) t[2]};
			}
			return null;
		}

		List<int[]> allVisible() {
			List<int[]> out = new ArrayList<int[]>();
			for (Object[] t : texts) out.add(new int[]{(Integer) t[1], (Integer) t[2]});
			return out;
		}

		@Override
		public void fill(int x1, int y1, int x2, int y2, int argb) {
		}

		@Override
		public void text(String text, int x, int y, int argb, boolean shadow) {
			if (scissor != null && (y + 8 <= scissor[1] || y >= scissor[3] || x >= scissor[2])) return;
			minTextX = Math.min(minTextX, x);
			texts.add(new Object[]{text, x, y});
		}

		@Override
		public int textWidth(String text) {
			return text == null ? 0 : text.length() * 6;
		}

		@Override
		public int lineHeight() {
			return 9;
		}

		@Override
		public String clip(String text, int maxWidth) {
			if (text == null) return "";
			int n = Math.max(0, Math.min(text.length(), maxWidth / 6));
			return text.substring(0, n);
		}

		@Override
		public void flush() {
		}

		@Override
		public void scissor(int x1, int y1, int x2, int y2) {
			scissor = new int[]{x1, y1, x2, y2};
		}

		@Override
		public void noScissor() {
			scissor = null;
		}

		@Override
		public void raise(float z) {
		}

		@Override
		public void push() {
		}

		@Override
		public void translate(float x, float y) {
		}

		@Override
		public void scale(float factor) {
		}

		@Override
		public void pop() {
		}
	}

	/** Host mit allen Leisten-Einträgen (Packs, Konten, Garderobe, Sozial, Clips). */
	static final class FakeHost implements MenuHost {
		private final TrsModules modules = new TrsModules();

		@Override
		public TrsModules modules() {
			return modules;
		}

		@Override
		public void playClick() {
		}

		@Override
		public void closeScreen() {
		}

		@Override
		public void openHudEditor() {
		}

		@Override
		public void openMenu() {
		}

		@Override
		public void openPacks() {
		}

		@Override
		public boolean hasPacks() {
			return true;
		}

		@Override
		public boolean hasAccounts() {
			return true;
		}

		@Override
		public boolean hasWardrobe() {
			return true;
		}

		@Override
		public boolean hasFriends() {
			return true;
		}

		@Override
		public boolean hasClips() {
			return true;
		}

		@Override
		public boolean supports(Module module) {
			return true;
		}

		@Override
		public List<MenuAction> actions(Module module) {
			return Collections.emptyList();
		}

		@Override
		public void save() {
		}

		@Override
		public boolean shiftDown() {
			return false;
		}

		@Override
		public String keyLabel(String keyName) {
			return "?";
		}

		@Override
		public String keyNameOf(int rawKey) {
			return null;
		}

		@Override
		public String menuKeyLabel() {
			return "Right Shift";
		}

		@Override
		public String profileKeyLabel() {
			return "";
		}

		@Override
		public List<HudItem> hudItems() {
			return Collections.emptyList();
		}

		@Override
		public boolean inWorld() {
			return false;
		}
	}

	@Test
	void largeWindowsNeedNoScrolling(@TempDir Path dir) {
		I18n.use("en");
		BugReports.init(dir.resolve("config"), "0.13.0", "1.21.1", "fabric");
		ModMenu menu = new ModMenu(new FakeHost());
		RecordingCanvas c = new RecordingCanvas();
		render(menu, c, new int[]{960, 540});
		assertNotNull(c.visible(I18n.tr("menu.bugReport")), "ohne Scrollen sichtbar");
		assertNotNull(c.visible(I18n.tr("menu.all")));
	}
}
