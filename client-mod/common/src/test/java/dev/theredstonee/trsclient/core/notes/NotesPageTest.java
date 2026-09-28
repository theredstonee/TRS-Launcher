package dev.theredstonee.trsclient.core.notes;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.notes.NotesPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Notiz-Seite im TRS-Menü ohne Spiel: alle Ansichten zeichnen, tippen, Checkliste per Enter, Grenzen. */
class NotesPageTest {
	/** Zeichenfläche, die nur Texte mitschreibt (6 px je Zeichen). */
	static final class FakeCanvas implements Canvas {
		final List<String> texts = new ArrayList<String>();

		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { }
		@Override public void text(String text, int x, int y, int argb, boolean shadow) { texts.add(text); }
		@Override public int textWidth(String text) { return text == null ? 0 : text.codePointCount(0, text.length()) * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public String clip(String text, int maxWidth) {
			int n = Math.max(0, maxWidth / 6);
			return text.length() <= n ? text : text.substring(0, n);
		}
		@Override public void flush() { }
		@Override public void scissor(int x1, int y1, int x2, int y2) { }
		@Override public void noScissor() { }
		@Override public void raise(float z) { }
		@Override public void push() { }
		@Override public void translate(float x, float y) { }
		@Override public void scale(float factor) { }
		@Override public void pop() { }

		boolean shows(String s) {
			for (String t : texts) if (t.contains(s)) return true;
			return false;
		}
	}

	private final TrsModules modules = new TrsModules();
	private final NotesStore store = new NotesStore(null);
	private final NoteWorld world = NoteWorld.fromWaypointKey("mp:play.example.net");
	private final Hits hits = new Hits();

	@AfterEach
	void reset() {
		I18n.use("en");
		Notes.forTest(new TrsModules(), new NotesStore(null));
	}

	private NotesPage page() {
		Notes.forTest(modules, store);
		return new NotesPage(new NotesPage.Host() {
			@Override
			public void click() {
			}

			@Override
			public String clipboard() {
				return "eingefügt";
			}

			@Override
			public void save() {
			}
		});
	}

	private FakeCanvas draw(NotesPage p) {
		FakeCanvas c = new FakeCanvas();
		hits.clear();
		p.draw(c, hits, 100, 40, 320, 210, 0, 0);
		return c;
	}

	@Test
	void allViewsDrawWithoutAWorld() {
		NotesPage p = page();
		p.opened();
		FakeCanvas c = draw(p);
		assertTrue(c.shows(I18n.tr("notes.noWorld")), "Texte: " + c.texts);
		p.testWorlds();
		assertTrue(draw(p).shows(I18n.tr("notes.worlds.none").split(" ")[0]));
		assertEquals("worlds", p.viewName());
	}

	@Test
	void readEditSearchAndChecklist() {
		NoteBook book = store.book(world);
		Note n = store.create(book, 1000);
		store.update(book, n, "Farm", "[ ] Eisen\n[x] Gold\nPortal x: 100, y: 64, z: -20 und ein sehr langer Satz, der umbrechen muss", 2000);
		store.pin(book, n);
		NotesPage p = page();
		p.showNote(world.key(), n.id, false);
		FakeCanvas c = draw(p);
		assertEquals("note", p.viewName());
		assertTrue(c.shows("Farm"));
		assertTrue(c.shows("x: 100, y: 64, z: -20"));
		p.testPopup(100, 64, -20, 150, 100);
		assertTrue(draw(p).shows(I18n.tr("notes.coord.tempWaypoint")));
		assertTrue(p.back());

		// Bearbeiten: am Ende tippen, Enter in einer Checkliste setzt ein neues Kästchen.
		p.showNote(world.key(), n.id, true);
		draw(p);
		for (char ch : " ok".toCharArray()) p.charTyped(ch);
		assertTrue(p.keyPressed(UiKey.UP));
		assertTrue(p.keyPressed(UiKey.DOWN));
		assertTrue(p.keyPressed(UiKey.END));
		assertTrue(p.keyPressed(UiKey.PASTE));
		draw(p);
		assertTrue(p.back()); // Bearbeiten beenden → speichern
		assertTrue(book.byId(n.id).text.endsWith(" okeingefügt"));

		Note list = store.create(book, 3000);
		store.update(book, list, "Liste", "[ ] eins", 3001);
		p.showNote(world.key(), list.id, true);
		draw(p);
		p.keyPressed(UiKey.END);
		p.keyPressed(UiKey.ENTER);
		for (char ch : "zwei".toCharArray()) p.charTyped(ch);
		p.back();
		assertEquals("[ ] eins\n[ ] zwei", book.byId(list.id).text);

		p.testSearch("gold");
		FakeCanvas s = draw(p);
		assertTrue(s.shows("Farm"));
		assertTrue(!s.shows("Liste"));
		p.testWorlds();
		assertTrue(draw(p).shows("play.example.net"));
	}

	@Test
	void textLimitStopsTyping() {
		NoteBook book = store.book(world);
		Note n = store.create(book, 1);
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < Note.MAX_TEXT; i++) big.append('a');
		store.update(book, n, "voll", big.toString(), 2);
		NotesPage p = page();
		p.showNote(world.key(), n.id, true);
		draw(p);
		p.charTyped('b');
		FakeCanvas c = draw(p);
		assertTrue(c.shows(I18n.tr("notes.limit.text", Note.MAX_TEXT)), "Hinweis auf die Grenze fehlt");
		p.back();
		assertEquals(Note.MAX_TEXT, Note.length(book.byId(n.id).text));
	}

	@Test
	void hudPanelShowsPinnedNoteOnlyInItsWorldButPreviewAlways() {
		NotePanel panel = new NotePanel(modules);
		Notes.forTest(modules, store);
		TextWidth tw = new TextWidth() {
			@Override
			public int width(String text) {
				return text.length() * 6;
			}
		};
		// Kein Spiel → keine Welt → im Spiel unsichtbar, im HUD-Editor ein Beispiel.
		assertTrue(!panel.visible());
		FakeCanvas c = new FakeCanvas();
		panel.draw(c, tw, true);
		assertTrue(c.shows(I18n.tr("notes.hud.sampleTitle")));
		assertTrue(panel.height(tw, true) > 20);
		assertEquals((int) modules.notes.hudWidth.get() + 10, panel.width(tw, true));
	}
}
