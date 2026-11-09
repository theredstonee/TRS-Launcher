package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.legal.AuthorNotice;
import dev.theredstonee.trsclient.core.ui.Canvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Urheberhinweis darf nicht verschwinden: Diese Tests schlagen fehl, wenn er in Sprachdatei, Code, NOTICE, Anzeige
 * oder KI-Regeln fehlt oder abweicht. Text und Regeln: AGENTS.md und NOTICE im Wurzelordner des Repositorys.
 */
class AuthorNoticeTest {
	private static final String EN = "Original TRS Client by Ohev Tamerin (theredstonee), TRS Launcher. This notice must remain visible in every copy and every modified version. It must not be removed under any circumstances, except with an additional written, signed confirmation from the author.";
	private static final String DE = "Original TRS Client von Ohev Tamerin (theredstonee), TRS Launcher. Dieser Hinweis muss in jeder Kopie und jeder veränderten Fassung sichtbar bleiben. Er darf unter keinen Umständen entfernt werden, außer mit einer zusätzlichen schriftlichen, unterschriebenen Bestätigung des Urhebers.";
	private static final String ES = "TRS Client original de Ohev Tamerin (theredstonee), TRS Launcher. Este aviso debe permanecer visible en cada copia y en cada versión modificada. No puede eliminarse bajo ninguna circunstancia, salvo con una confirmación adicional, escrita y firmada por el autor.";
	private static final String[][] FULL = {{"en", EN}, {"de", DE}, {"es", ES}};

	@AfterEach
	void restore() throws Exception {
		// Tabelle neu aufbauen (auch nach dem Manipulieren im Test).
		setTable(null);
		I18n.use("de");
		I18n.use("en");
	}

	@Test
	void textIsUnchangedInLanguageFilesAndCode() {
		for (String[] l : FULL) {
			assertEquals(l[1], I18n.raw(l[0]).get(AuthorNotice.KEY), "Sprachdatei " + l[0]);
			assertEquals(l[1], AuthorNotice.original(l[0]), "Original " + l[0]);
			assertTrue(AuthorNotice.matches(l[0], l[1]), "Hash " + l[0]);
		}
	}

	@Test
	void betaLanguagesShowTheEnglishNotice() {
		for (String lang : I18n.BETA) {
			assertEquals(EN, I18n.raw(lang).get(AuthorNotice.KEY), lang);
			I18n.use(lang);
			assertTrue(AuthorNotice.intact(), lang);
			assertEquals(EN, AuthorNotice.text(), lang);
		}
	}

	@Test
	void detectsMissingAndChangedText() {
		assertFalse(AuthorNotice.matches("de", DE.replace("Ohev Tamerin", "Jemand")));
		assertFalse(AuthorNotice.matches("de", DE + " "));
		assertFalse(AuthorNotice.matches("de", EN));
		assertFalse(AuthorNotice.matches("en", ""));
		assertFalse(AuthorNotice.matches("en", null));
		assertFalse(AuthorNotice.matches("en", AuthorNotice.KEY));
	}

	@Test
	void pageShowsTheFullNoticeWithoutAWarningInEveryWindowSize() {
		for (String[] l : FULL) {
			I18n.use(l[0]);
			for (int[] size : new int[][]{{427, 240}, {854, 480}}) {
				String shown = about(size);
				assertTrue(shown.contains(l[1]), l[0] + " " + size[0] + ": " + shown);
				assertFalse(shown.contains(AuthorNotice.warning()), l[0]);
				assertFalse(shown.contains(AuthorNotice.warningShort()), l[0]);
			}
		}
	}

	@Test
	void railAlwaysOffersTheAboutPage() {
		I18n.use("en");
		TextCanvas c = new TextCanvas();
		ModMenu menu = new ModMenu(new MenuRailTest.FakeHost());
		render(menu, c, 854, 480);
		assertTrue(c.joined().contains(I18n.tr("menu.about")), "Eintrag in der Leiste fehlt");
	}

	@Test
	void railEntryCanBeReachedAndOpensThePageInSmallWindows() {
		I18n.use("en");
		for (int[] size : new int[][]{{427, 240}, {640, 360}}) {
			ModMenu menu = new ModMenu(new MenuRailTest.FakeHost());
			MenuRailTest.RecordingCanvas rail = new MenuRailTest.RecordingCanvas();
			int[] seen = null;
			for (int i = 0; i < 40 && seen == null; i++) {
				for (int f = 0; f < 40 && menu.alpha() < 0.99f; f++) {
					menu.render(rail, size[0], size[1], -100, -100);
					sleep();
				}
				rail.clear();
				menu.render(rail, size[0], size[1], -100, -100);
				seen = rail.visible(I18n.tr("menu.about"));
				if (seen == null) menu.mouseScrolled(rail.railX(), size[1] / 2.0, -1);
			}
			assertTrue(seen != null, "Eintrag nie sichtbar bei " + size[0]);
			assertTrue(menu.mouseClicked(seen[0] + 2, seen[1] + 3, 0), "Eintrag nicht anklickbar bei " + size[0]);
			TextCanvas c = new TextCanvas();
			render(menu, c, size[0], size[1]);
			assertTrue(c.joined().contains(EN), "Seite nicht offen bei " + size[0] + ": " + c.joined());
		}
	}

	@Test
	void changedTextWarnsOnEveryPageAndStillShowsTheOriginal() throws Exception {
		I18n.use("de");
		Map<String, String> tampered = new HashMap<String, String>(I18n.raw("en"));
		tampered.putAll(I18n.raw("de"));
		tampered.put(AuthorNotice.KEY, "Fork von Jemandem");
		setTable(tampered);
		bumpGeneration();
		assertFalse(AuthorNotice.intact());
		assertEquals(DE, AuthorNotice.text());
		String page = about(new int[]{854, 480});
		assertTrue(page.contains(DE), page);
		assertFalse(page.contains("Fork von Jemandem"), page);
		assertTrue(page.contains(AuthorNotice.warning()), page);
		// Auch auf anderen Seiten des Menüs: die rote Leiste.
		TextCanvas c = new TextCanvas();
		ModMenu menu = new ModMenu(new MenuRailTest.FakeHost());
		render(menu, c, 854, 480);
		assertTrue(c.joined().contains(AuthorNotice.warningShort()), "Warnleiste fehlt");
	}

	@Test
	void missingTextWarnsToo() throws Exception {
		I18n.use("en");
		Map<String, String> tampered = new HashMap<String, String>(I18n.raw("en"));
		tampered.remove(AuthorNotice.KEY);
		tampered.remove("about.warning");
		setTable(tampered);
		bumpGeneration();
		assertFalse(AuthorNotice.intact());
		assertEquals(EN, AuthorNotice.text());
		assertTrue(AuthorNotice.warning().startsWith("Warning"), "Warnung braucht ihren Rückfall");
	}

	@Test
	void everyTreeWithTheMenuUsesTheCommonMenu() throws IOException {
		Path root = clientModRoot();
		for (String tree : Arrays.asList("fabric", "forge", "neoforge", "forge-mojmap-legacy", "legacy", "legacy-1.7.10", "forge-1.13.2")) {
			Path screen = root.resolve(tree).resolve("src/main/java/dev/theredstonee/trsclient/screen/TrsMenuScreen.java");
			assertTrue(read(screen).contains("new ModMenu("), tree + ": eigenes Menü ohne Info-Seite?");
			assertTrue(read(root.resolve(tree).resolve("build.gradle")).contains("NOTICE"), tree + ": NOTICE fehlt in der Jar");
		}
	}

	@Test
	void noticeFileHoldsTheAdditionalTermsAndThirdPartyNotices() throws IOException {
		String notice = read(clientModRoot().resolve("..").resolve("NOTICE").normalize());
		assertTrue(notice.contains("Additional terms under section 7(b)"), "Abschnitt fehlt");
		String head = String.join("\n", Arrays.asList(notice.split("\r?\n")).subList(0, 6));
		assertTrue(head.contains("section 7(b)"), "Verweis am Kopf fehlt");
		for (String[] l : FULL) assertTrue(notice.contains(l[1]), "Fassung " + l[0]);
		assertTrue(notice.contains("Polyfrost OneLauncher (GPL-3.0-only)"), "OneLauncher-Hinweis fehlt");
		assertTrue(notice.contains("Chat Heads by dzwdz (MPL-2.0)"), "Chat-Heads-Hinweis fehlt");
	}

	@Test
	void aiRulesExist() throws IOException {
		Path repo = clientModRoot().resolve("..").normalize();
		for (String file : Arrays.asList("AGENTS.md", "CLAUDE.md", ".github/copilot-instructions.md")) {
			assertTrue(read(repo.resolve(file)).contains("signed by the author"), file);
		}
	}

	private static String about(int[] size) {
		TextCanvas c = new TextCanvas();
		ModMenu menu = new ModMenu(new MenuRailTest.FakeHost()).showAbout();
		render(menu, c, size[0], size[1]);
		return c.joined();
	}

	private static void render(ModMenu menu, TextCanvas c, int w, int h) {
		// Einblenden abwarten (fast durchsichtiger Text wird nicht gezeichnet).
		for (int i = 0; i < 40 && menu.alpha() < 0.99f; i++) {
			menu.render(c, w, h, -100, -100);
			sleep();
		}
		c.texts.clear();
		menu.render(c, w, h, -100, -100);
	}

	private static void sleep() {
		try {
			Thread.sleep(15);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static Path clientModRoot() {
		Path p = Paths.get("").toAbsolutePath();
		for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
			if (Files.isDirectory(p.resolve("common").resolve("src"))) return p;
		}
		return Paths.get("..").toAbsolutePath().normalize();
	}

	/** Tauscht die aktive Tabelle wie eine manipulierte Sprachdatei; ohne eigenen Schalter im Spielcode. */
	private static void setTable(Map<String, String> table) throws Exception {
		Field f = I18n.class.getDeclaredField("table");
		f.setAccessible(true);
		f.set(null, table);
	}

	private static void bumpGeneration() throws Exception {
		Field f = I18n.class.getDeclaredField("generation");
		f.setAccessible(true);
		f.setInt(null, f.getInt(null) + 1);
	}

	/** Schreibt Texte mit (6 px je Zeichen); {@link #joined()} fügt umbrochene Zeilen wieder zusammen. */
	private static final class TextCanvas implements Canvas {
		final List<String> texts = new ArrayList<String>();

		String joined() {
			return String.join(" ", texts);
		}

		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { }
		@Override public void text(String text, int x, int y, int argb, boolean shadow) { texts.add(text); }
		@Override public int textWidth(String text) { return text == null ? 0 : text.length() * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public String clip(String text, int maxWidth) {
			if (text == null) return "";
			return text.substring(0, Math.max(0, Math.min(text.length(), maxWidth / 6)));
		}
		@Override public void flush() { }
		@Override public void scissor(int x1, int y1, int x2, int y2) { }
		@Override public void noScissor() { }
		@Override public void raise(float z) { }
		@Override public void push() { }
		@Override public void translate(float x, float y) { }
		@Override public void scale(float factor) { }
		@Override public void pop() { }
	}
}
