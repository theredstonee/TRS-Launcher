package dev.theredstonee.trsclient.core.ui.notes;

import dev.theredstonee.trsclient.core.chat.ChatCoords;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.notes.Note;
import dev.theredstonee.trsclient.core.notes.NoteBook;
import dev.theredstonee.trsclient.core.notes.NoteLayout;
import dev.theredstonee.trsclient.core.notes.NotePanel;
import dev.theredstonee.trsclient.core.notes.NoteText;
import dev.theredstonee.trsclient.core.notes.NoteWorld;
import dev.theredstonee.trsclient.core.notes.Notes;
import dev.theredstonee.trsclient.core.notes.NotesModulePanel;
import dev.theredstonee.trsclient.core.notes.NotesStore;
import dev.theredstonee.trsclient.core.notes.NotesSync;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.social.ChatInput;
import dev.theredstonee.trsclient.core.ui.social.ChatLayout;

import java.util.List;

/**
 * Seite „Notizen“ im TRS-Menü: Liste der Notizen einer Welt mit Suche, Notiz lesen (Checklisten zum Abhaken,
 * Koordinaten als Links) oder bearbeiten, Liste anderer Welten mit Notizen. Immediate Mode wie das restliche Menü.
 */
public final class NotesPage {
	/** Was die Seite vom Menü braucht. */
	public interface Host {
		void click();

		/** Inhalt der Zwischenablage oder null. */
		String clipboard();

		/** Client-Einstellungen speichern (z. B. HUD-Modul eingeschaltet). */
		void save();
	}

	private enum View {
		LIST, NOTE, WORLDS
	}

	private static final int LH = 10;
	private static final int ROW_H = 26;
	/** So oft wird beim Tippen zwischengespeichert. */
	private static final long AUTOSAVE_MS = 10_000L;
	private static final long MESSAGE_MS = 4500L;
	private static final long CONFIRM_MS = 4000L;

	private final Host host;
	private final TextInput search = new TextInput(40);
	private final TextInput titleInput = new TextInput(Note.MAX_TITLE);
	private final ChatInput body = new ChatInput(Note.MAX_TEXT);

	private View view = View.LIST;
	/** Gewähltes Buch (null = Welt, in der der Spieler gerade ist). */
	private String bookKey;
	private String noteId;
	private boolean editing;
	private long lastCommit;
	private int listScroll;
	private int listMax;
	private int noteScroll;
	private int noteMax;
	private int worldsScroll;
	private int worldsMax;
	private final int[] scrollRect = new int[4];
	private String message;
	private boolean messageBad;
	private long messageUntil;
	private long confirmDeleteUntil;
	/** Offenes Koordinaten-Menü: x, y, z, Bildschirm-x, Bildschirm-y (null = zu). */
	private int[] popup;
	/** Letzter Umbruch im Editor (für Hoch/Runter und Klicks). */
	private List<int[]> editRows;
	private int editTextX;
	private int editTextY;
	private int editFirst;
	/** Bildlauf im Editor folgt dem Cursor (aus, sobald mit dem Mausrad gerollt wird). */
	private boolean followCursor = true;

	public NotesPage(Host host) {
		this.host = host;
	}

	// --- Öffnen/Schließen ---

	/** Seite betreten: Liste der aktuellen Welt. */
	public void opened() {
		commit();
		view = View.LIST;
		bookKey = null;
		noteId = null;
		editing = false;
		popup = null;
		listScroll = 0;
		search.clear();
		search.setFocused(false);
		titleInput.setFocused(false);
		body.setFocused(false);
	}

	/** Menü geschlossen / Seite verlassen: Änderungen sichern. */
	public void closed() {
		commit();
		popup = null;
		Notes n = Notes.get();
		if (n != null) n.save();
	}

	/** Direkt eine Notiz zeigen (Selbsttest). */
	public void showNote(String key, String id, boolean edit) {
		commit();
		bookKey = key;
		openNote(id, edit);
	}

	/** Selbsttest: Liste mit Suchbegriff zeigen. */
	public void testSearch(String query) {
		commit();
		view = View.LIST;
		search.setText(query);
		search.setFocused(query != null && !query.isEmpty());
		listScroll = 0;
	}

	/** Selbsttest: Liste der Welten mit Notizen zeigen. */
	public void testWorlds() {
		commit();
		view = View.WORLDS;
		worldsScroll = 0;
	}

	/** Selbsttest: Koordinaten-Menü an einer Stelle öffnen. */
	public void testPopup(int x, int y, int z, int screenX, int screenY) {
		popup = new int[] {x, y, z, screenX, screenY};
	}

	/** Gerade gezeigte Ansicht (Selbsttest): "list", "note" oder "worlds". */
	public String viewName() {
		return view.name().toLowerCase(java.util.Locale.ROOT);
	}

	private static Notes notes() {
		return Notes.get();
	}

	/** Gewähltes Buch (null = keine Welt und nichts gewählt). */
	private NoteBook book() {
		Notes n = notes();
		if (n == null) return null;
		if (bookKey != null) {
			NoteBook b = n.store().existing(bookKey);
			if (b != null) return b;
			bookKey = null;
		}
		return n.currentBook();
	}

	private boolean isCurrent(NoteBook b) {
		Notes n = notes();
		NoteWorld w = n == null ? null : n.currentWorld();
		return b != null && w != null && w.equals(b.world);
	}

	private Note note() {
		NoteBook b = book();
		Note n = b == null ? null : b.byId(noteId);
		return n == null || n.deleted ? null : n;
	}

	private void openNote(String id, boolean edit) {
		NoteBook b = book();
		Note n = b == null ? null : b.byId(id);
		if (n == null || n.deleted) return;
		noteId = id;
		view = View.NOTE;
		noteScroll = 0;
		popup = null;
		confirmDeleteUntil = 0;
		titleInput.setText(n.title);
		body.setText(n.text);
		body.setCursor(n.text.length());
		editing = edit;
		titleInput.setFocused(false);
		body.setFocused(edit);
		lastCommit = System.currentTimeMillis();
	}

	/** Eingaben in die Notiz übernehmen (nur wenn sich etwas geändert hat). */
	private void commit() {
		Notes n = notes();
		if (n == null || noteId == null || view != View.NOTE) return;
		NoteBook b = book();
		Note note = b == null ? null : b.byId(noteId);
		if (note == null || note.deleted) return;
		n.store().update(b, note, titleInput.text(), body.text(), System.currentTimeMillis());
		lastCommit = System.currentTimeMillis();
	}

	private void say(String text, boolean bad) {
		message = text;
		messageBad = bad;
		messageUntil = System.currentTimeMillis() + MESSAGE_MS;
	}

	// --- Zeichnen ---

	public void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Notes n = notes();
		Theme t = Theme.get();
		if (n == null) {
			Paint.textCentered(c, I18n.tr("notes.unavailable"), x + w / 2, y + h / 2 - 4, t.textDim, false);
			return;
		}
		if (view == View.NOTE && note() == null) {
			view = View.LIST;
			editing = false;
		}
		if (editing && System.currentTimeMillis() - lastCommit > AUTOSAVE_MS) commit();
		int footerY = y + h - 9;
		int bodyH = h - 12;
		switch (view) {
			case NOTE:
				noteView(c, hits, x, y, w, bodyH, mx, my);
				break;
			case WORLDS:
				worldsView(c, hits, x, y, w, bodyH, mx, my);
				break;
			default:
				listView(c, hits, x, y, w, bodyH, mx, my);
				break;
		}
		footer(c, x, footerY, w);
		if (popup != null) popup(c, hits, x, y, w, h, mx, my);
	}

	private void footer(Canvas c, int x, int y, int w) {
		Theme t = Theme.get();
		long now = System.currentTimeMillis();
		String text;
		int color = t.textDim;
		if (message != null && now < messageUntil) {
			text = message;
			color = messageBad ? t.dustOn : t.text;
		} else if (view == View.NOTE && editing) {
			int used = Note.length(body.text());
			text = I18n.tr("notes.chars", used, Note.MAX_TEXT);
			if (used > Note.MAX_TEXT * 9 / 10) color = t.dustOn;
		} else {
			NotesSync sync = NotesSync.get();
			text = I18n.tr("notes.sync.label", NotesModulePanel.statusText(sync));
		}
		c.fill(x, y - 3, x + w, y - 2, ColorMath.withAlpha(t.border, 160));
		Paint.textClipped(c, text, x + 2, y, w - 4, color, false);
	}

	// --- Liste ---

	private void listView(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final Notes n = notes();
		final NoteBook book = book();
		// Kopf: Welt (Klick = andere Welten) und „Neue Notiz“.
		String newLabel = I18n.tr("notes.new");
		int nw = Math.min(w / 2, c.textWidth(newLabel) + 26);
		int nx = x + w - nw;
		boolean canNew = book != null;
		boolean newHover = canNew && inside(mx, my, nx, y, nw, 17);
		Paint.button(c, nx, y, nw, 17, "", canNew, newHover);
		Icons.draw(c, "plus", nx + 6, y + 4, 1, canNew ? (newHover ? t.dustOn : t.text) : t.textDim);
		Paint.textClipped(c, newLabel, nx + 18, y + 4 - (newHover ? 1 : 0), nw - 22, canNew ? t.text : t.textDim, false);
		if (canNew) {
			hits.add(nx, y, nw, 17, new Runnable() {
				@Override
				public void run() {
					host.click();
					createNote();
				}
			});
		}
		int ww = nw >= w - 40 ? w : w - nw - 6;
		String worldLabel = book == null ? I18n.tr("notes.noWorld") : book.world.label();
		String here = book != null && isCurrent(book) ? I18n.tr("notes.thisWorld") : book != null ? I18n.tr("notes.otherWorld") : null;
		boolean wHover = inside(mx, my, x, y, ww, 17);
		Redstone.stone(c, x, y, ww, 17, wHover ? t.surfaceHover : t.surface, t.border);
		Icons.draw(c, book != null && book.world.server() ? "globe" : "home", x + 5, y + 4, 1, t.dustOn);
		int hereW = here == null ? 0 : Math.min(ww / 3, c.textWidth(here) + 6);
		Paint.textClipped(c, worldLabel, x + 17, y + 4, ww - 30 - hereW, t.text, false);
		if (here != null) Paint.textRight(c, c.clip(here, hereW), x + ww - 16, y + 4, t.textDim, false);
		Icons.draw(c, "next", x + ww - 11, y + 4, 1, wHover ? t.dustOn : t.textDim);
		hits.add(x, y, ww, 17, new Runnable() {
			@Override
			public void run() {
				host.click();
				view = View.WORLDS;
				worldsScroll = 0;
				search.setFocused(false);
			}
		});

		// Suche
		int sy = y + 21;
		boolean sHover = inside(mx, my, x, sy, w, 16);
		Redstone.well(c, x, sy, w, 16, search.focused() ? t.accent : (sHover ? t.textDim : t.border));
		Icons.draw(c, "search", x + 5, sy + 4, 1, search.focused() ? t.dustOn : t.textDim);
		boolean hint = search.isEmpty() && !search.focused();
		Paint.textClipped(c, hint ? I18n.tr("notes.search") : search.text(), x + 16, sy + 4, w - 22, hint ? t.textDim : t.text, false);
		if (search.focused()) caret(c, x + 16 + c.textWidth(search.text()), sy + 4, x + w - 3);
		hits.add(x, sy, w, 16, new Runnable() {
			@Override
			public void run() {
				search.setFocused(true);
			}
		});

		int top = sy + 21;
		int listH = y + h - top;
		if (book == null) {
			int py = Paint.paragraph(c, I18n.tr("notes.joinWorld"), x + 2, top + 4, w - 4, LH, t.textDim);
			if (!n.store().booksWithNotes().isEmpty()) {
				String label = I18n.tr("notes.worlds.open");
				int bw = Math.min(w, c.textWidth(label) + 20);
				boolean hv = inside(mx, my, x, py + 4, bw, 17);
				Paint.button(c, x, py + 4, bw, 17, label, false, hv);
				hits.add(x, py + 4, bw, 17, new Runnable() {
					@Override
					public void run() {
						host.click();
						view = View.WORLDS;
					}
				});
			}
			return;
		}
		List<Note> list = book.search(search.text());
		if (list.isEmpty()) {
			String empty = search.text().trim().isEmpty() ? I18n.tr("notes.empty") : I18n.tr("notes.noMatch", search.text().trim());
			Paint.paragraph(c, empty, x + 2, top + 4, w - 4, LH, t.textDim);
			return;
		}
		int contentH = list.size() * ROW_H;
		listMax = Math.max(0, contentH - listH);
		listScroll = Math.max(0, Math.min(listScroll, listMax));
		setScrollRect(x, top, w, listH);
		Note pinned = n.store().pinned(book);
		c.scissor(x, top, x + w, top + listH);
		hits.clip(x, top, w, listH);
		int ry = top - listScroll;
		for (final Note note : list) {
			if (ry + ROW_H >= top && ry <= top + listH) {
				boolean hover = inside(mx, my, x, ry, w - 4, ROW_H - 3) && inside(mx, my, x, top, w, listH);
				Redstone.stone(c, x, ry, w - 4, ROW_H - 3, hover ? t.surfaceHover : t.surface, note == pinned ? ColorMath.lerp(t.border, t.accent, 0.7f) : t.border);
				int right = x + w - 10;
				if (note == pinned) {
					Icons.draw(c, "pin", right - 8, ry + 4, 1, t.dustOn);
					right -= 12;
				}
				int[] p = NoteText.progress(note.text);
				if (p[1] > 0) {
					String prog = p[0] + "/" + p[1];
					Paint.textRight(c, prog, right, ry + 4, p[0] == p[1] ? 0xFF55D86A : t.textDim, false);
					right -= c.textWidth(prog) + 6;
				}
				String title = note.displayTitle();
				Paint.textClipped(c, title == null ? I18n.tr("notes.untitled") : title, x + 6, ry + 4, right - x - 8,
						title == null ? t.textDim : t.text, false);
				String snippet = snippet(note);
				Paint.textClipped(c, snippet, x + 6, ry + 14, w - 20, ColorMath.withAlpha(t.textDim, 200), false);
				hits.add(x, ry, w - 4, ROW_H - 3, new Runnable() {
					@Override
					public void run() {
						host.click();
						search.setFocused(false);
						openNote(note.id, false);
					}
				});
			}
			ry += ROW_H;
		}
		hits.noClip();
		c.noScissor();
		if (listMax > 0) scrollbar(c, x + w - 2, top, listH, listScroll, listMax);
	}

	private static String snippet(Note note) {
		String first = note.title == null || note.title.trim().isEmpty() ? secondLine(note.text) : NoteText.firstLine(note.text);
		if (first.isEmpty()) return I18n.tr("notes.edited", ago(note.updated));
		return first;
	}

	/** Zweite nicht leere Zeile (die erste ist schon der Titel). */
	private static String secondLine(String text) {
		if (text == null) return "";
		boolean skipped = false;
		for (NoteText.Line l : NoteText.lines(text)) {
			String s = text.substring(l.contentStart, l.end).trim();
			if (s.isEmpty()) continue;
			if (!skipped) {
				skipped = true;
				continue;
			}
			return s;
		}
		return "";
	}

	private void createNote() {
		Notes n = notes();
		NoteBook book = book();
		if (n == null || book == null) return;
		if (!book.canAdd()) {
			say(I18n.tr("notes.limit.notes", NoteBook.MAX_NOTES), true);
			return;
		}
		Note note = n.store().create(book, System.currentTimeMillis());
		if (note == null) {
			say(I18n.tr("notes.limit.worlds", NotesStore.MAX_BOOKS), true);
			return;
		}
		if (bookKey == null) bookKey = book.world.key();
		search.clear();
		search.setFocused(false);
		openNote(note.id, true);
		titleInput.setFocused(true);
		body.setFocused(false);
	}

	// --- Andere Welten ---

	private void worldsView(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final Notes n = notes();
		boolean backHover = inside(mx, my, x, y, 18, 18);
		Paint.iconButton(c, x, y, 18, "back", backHover, false);
		hits.add(x, y, 18, 18, new Runnable() {
			@Override
			public void run() {
				host.click();
				view = View.LIST;
			}
		});
		Paint.textClipped(c, I18n.tr("notes.worlds.title"), x + 24, y + 5, w - 26, t.text, false);
		int top = y + 24;
		int listH = y + h - top;
		List<NoteBook> books = n.store().booksWithNotes();
		final NoteWorld current = n.currentWorld();
		boolean currentListed = false;
		for (NoteBook b : books) if (current != null && current.equals(b.world)) currentListed = true;
		int count = books.size() + (current != null && !currentListed ? 1 : 0);
		if (count == 0) {
			Paint.paragraph(c, I18n.tr("notes.worlds.none"), x + 2, top + 2, w - 4, LH, t.textDim);
			return;
		}
		int rowH = 20;
		worldsMax = Math.max(0, count * rowH - listH);
		worldsScroll = Math.max(0, Math.min(worldsScroll, worldsMax));
		setScrollRect(x, top, w, listH);
		c.scissor(x, top, x + w, top + listH);
		hits.clip(x, top, w, listH);
		int ry = top - worldsScroll;
		if (current != null && !currentListed) {
			worldRow(c, hits, x, ry, w, rowH, current.label(), current.server(), 0, true, mx, my, null);
			ry += rowH;
		}
		for (final NoteBook b : books) {
			boolean isCur = current != null && current.equals(b.world);
			worldRow(c, hits, x, ry, w, rowH, b.world.label(), b.world.server(), b.liveCount(), isCur, mx, my,
					isCur ? null : b.world.key());
			ry += rowH;
		}
		hits.noClip();
		c.noScissor();
		if (worldsMax > 0) scrollbar(c, x + w - 2, top, listH, worldsScroll, worldsMax);
	}

	private void worldRow(Canvas c, Hits hits, int x, int y, int w, int h, String label, boolean server, int count,
			boolean current, int mx, int my, final String key) {
		Theme t = Theme.get();
		boolean hover = inside(mx, my, x, y, w - 4, h - 2);
		Redstone.stone(c, x, y, w - 4, h - 2, hover ? t.surfaceHover : t.surface, current ? ColorMath.lerp(t.border, t.accent, 0.7f) : t.border);
		Icons.draw(c, server ? "globe" : "home", x + 5, y + 5, 1, current ? t.dustOn : t.textDim);
		String right = count > 0 ? I18n.tr("notes.worlds.count", count) : "";
		if (current) right = right.isEmpty() ? I18n.tr("notes.thisWorld") : right + " · " + I18n.tr("notes.thisWorld");
		int rw = c.textWidth(right);
		Paint.textRight(c, right, x + w - 10, y + 5, t.textDim, false);
		Paint.textClipped(c, label, x + 17, y + 5, w - 30 - rw, t.text, false);
		hits.add(x, y, w - 4, h - 2, new Runnable() {
			@Override
			public void run() {
				host.click();
				bookKey = key;
				view = View.LIST;
				listScroll = 0;
				search.clear();
			}
		});
	}

	// --- Notiz ---

	private void noteView(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final Notes n = notes();
		final NoteBook book = book();
		final Note note = note();
		if (book == null || note == null) return;
		// Kopf: Zurück | Titel | Anheften, Bearbeiten/Fertig, Löschen
		boolean backHover = inside(mx, my, x, y, 18, 18);
		Paint.iconButton(c, x, y, 18, "back", backHover, false);
		hits.add(x, y, 18, 18, new Runnable() {
			@Override
			public void run() {
				host.click();
				back();
			}
		});
		int bx = x + w - 18;
		final boolean confirm = System.currentTimeMillis() < confirmDeleteUntil;
		Paint.iconButton(c, bx, y, 18, "trash", inside(mx, my, bx, y, 18, 18), confirm);
		hits.add(bx, y, 18, 18, new Runnable() {
			@Override
			public void run() {
				host.click();
				if (!confirm) {
					confirmDeleteUntil = System.currentTimeMillis() + CONFIRM_MS;
					say(I18n.tr("notes.deleteConfirm"), true);
					return;
				}
				n.store().delete(book, note, System.currentTimeMillis());
				n.save();
				noteId = null;
				editing = false;
				view = View.LIST;
				say(I18n.tr("notes.deleted"), false);
			}
		});
		bx -= 21;
		Paint.iconButton(c, bx, y, 18, editing ? "check" : "pencil", inside(mx, my, bx, y, 18, 18), editing);
		hits.add(bx, y, 18, 18, new Runnable() {
			@Override
			public void run() {
				host.click();
				setEditing(!editing);
			}
		});
		bx -= 21;
		final boolean pinned = n.store().pinned(book) == note;
		Paint.iconButton(c, bx, y, 18, "pin", inside(mx, my, bx, y, 18, 18), pinned);
		hits.add(bx, y, 18, 18, new Runnable() {
			@Override
			public void run() {
				host.click();
				togglePin(book, note, pinned);
			}
		});
		int tx = x + 24;
		int tw = bx - 6 - tx;
		if (editing) {
			boolean focused = titleInput.focused();
			Redstone.well(c, tx, y + 1, tw, 16, focused ? t.accent : t.border);
			boolean empty = titleInput.isEmpty();
			Paint.textClipped(c, empty && !focused ? I18n.tr("notes.titleHint") : titleInput.text(), tx + 4, y + 5, tw - 8,
					empty && !focused ? t.textDim : t.text, false);
			if (focused) caret(c, tx + 4 + c.textWidth(titleInput.text().substring(0, Math.min(titleInput.cursor(), titleInput.text().length()))), y + 5, tx + tw - 3);
			hits.add(tx, y + 1, tw, 16, new Runnable() {
				@Override
				public void run() {
					titleInput.setFocused(true);
					body.setFocused(false);
				}
			});
		} else {
			String title = note.displayTitle();
			Paint.textClipped(c, title == null ? I18n.tr("notes.untitled") : title, tx, y + 5, tw, title == null ? t.textDim : t.text, false);
		}
		int top = y + 22;
		if (editing) top = toolbar(c, hits, x, top, w, mx, my, book);
		int bh = y + h - top;
		if (editing) editor(c, hits, x, top, w, bh, mx, my);
		else reader(c, hits, x, top, w, bh, mx, my, book, note);
	}

	private void togglePin(NoteBook book, Note note, boolean pinned) {
		Notes n = notes();
		if (n == null) return;
		commit();
		if (pinned) {
			n.store().pin(book, null);
			say(I18n.tr("notes.unpinned"), false);
		} else {
			n.store().pin(book, note);
			if (!n.modules().notes.pinnedNote.isEnabled()) {
				n.modules().notes.pinnedNote.setEnabled(true);
				host.save();
			}
			say(I18n.tr(isCurrent(book) ? "notes.pinned" : "notes.pinnedOther"), false);
		}
		n.save();
	}

	private void setEditing(boolean on) {
		if (!on) {
			commit();
			noteScroll = 0;
			editing = false;
			titleInput.setFocused(false);
			body.setFocused(false);
			return;
		}
		Note note = note();
		if (note == null) return;
		titleInput.setText(note.title);
		body.setText(note.text);
		body.setCursor(note.text.length());
		noteScroll = 0;
		followCursor = true;
		editing = true;
		body.setFocused(true);
		titleInput.setFocused(false);
		lastCommit = System.currentTimeMillis();
	}

	/** Werkzeugleiste im Bearbeiten: Checkliste, Meine Position. */
	private int toolbar(Canvas c, Hits hits, int x, int y, int w, int mx, int my, final NoteBook book) {
		Theme t = Theme.get();
		String check = I18n.tr("notes.tool.checklist");
		String pos = I18n.tr("notes.tool.position");
		int cw = Math.min(w / 2 - 3, c.textWidth(check) + 24);
		boolean ch = inside(mx, my, x, y, cw, 16);
		Paint.button(c, x, y, cw, 16, "", false, ch);
		NotePanel.box(c, x + 6, y + 4, false, ch ? t.dustOn : t.text);
		Paint.textClipped(c, check, x + 17, y + 4 - (ch ? 1 : 0), cw - 20, t.text, false);
		hits.add(x, y, cw, 16, new Runnable() {
			@Override
			public void run() {
				host.click();
				Object[] r = NoteText.toggleChecklistLine(body.text(), body.cursor());
				body.setText((String) r[0]);
				body.setCursor(((Integer) r[1]).intValue());
				body.setFocused(true);
				titleInput.setFocused(false);
			}
		});
		final boolean here = isCurrent(book);
		int px = x + cw + 6;
		int pw = Math.min(x + w - px, c.textWidth(pos) + 24);
		boolean ph = here && inside(mx, my, px, y, pw, 16);
		Paint.button(c, px, y, pw, 16, "", false, ph);
		Icons.draw(c, "pin", px + 6, y + 4, 1, here ? (ph ? t.dustOn : t.text) : t.textDim);
		Paint.textClipped(c, pos, px + 17, y + 4 - (ph ? 1 : 0), pw - 20, here ? t.text : t.textDim, false);
		hits.add(px, y, pw, 16, new Runnable() {
			@Override
			public void run() {
				host.click();
				insertPosition(here);
			}
		});
		return y + 20;
	}

	private void insertPosition(boolean here) {
		Notes n = notes();
		int[] p = n == null ? null : n.position();
		if (!here || p == null) {
			say(I18n.tr(p == null ? "notes.coord.noWorld" : "notes.tool.positionOther"), true);
			return;
		}
		String s = NoteText.position(p[0], p[1], p[2]);
		String text = body.text();
		int cur = body.cursor();
		// Ein Leerzeichen davor, wenn direkt an ein Wort angehängt würde.
		if (cur > 0 && cur <= text.length() && !Character.isWhitespace(text.charAt(cur - 1))) s = " " + s;
		if (!body.insert(s)) {
			say(I18n.tr("notes.limit.text", Note.MAX_TEXT), true);
			return;
		}
		body.setFocused(true);
		titleInput.setFocused(false);
	}

	/** Mehrzeiliger Editor mit Cursor, Bildlauf zum Cursor und Klick setzt den Cursor. */
	private void editor(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final String text = body.text();
		final Canvas measure = c;
		List<int[]> rows = ChatLayout.wrap(new ChatLayout.Measure() {
			@Override
			public int width(String s) {
				return measure.textWidth(s);
			}
		}, text, w - 14);
		if (rows.isEmpty()) rows.add(new int[] {0, 0});
		editRows = rows;
		int visible = Math.max(1, (h - 8) / LH);
		int cursorRow = rowOf(rows, body.cursor());
		// Bildlauf folgt dem Cursor.
		int first = Math.max(0, Math.min(noteScroll, rows.size() - visible));
		if (body.focused() && followCursor) {
			if (cursorRow < first) first = cursorRow;
			if (cursorRow >= first + visible) first = cursorRow - visible + 1;
		}
		noteScroll = first;
		noteMax = Math.max(0, rows.size() - visible);
		setScrollRect(x, y, w, h);
		editFirst = first;
		editTextX = x + 5;
		editTextY = y + 4;
		Redstone.well(c, x, y, w, h, body.focused() ? t.accent : t.border);
		if (text.isEmpty() && !body.focused()) {
			Paint.paragraph(c, I18n.tr("notes.bodyHint"), x + 5, y + 4, w - 12, LH, ColorMath.withAlpha(t.textDim, 170));
		}
		c.scissor(x + 1, y + 1, x + w - 1, y + h - 1);
		int ty = y + 4;
		boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
		for (int i = first; i < rows.size() && i < first + visible; i++) {
			int[] r = rows.get(i);
			c.text(text.substring(r[0], r[1]), x + 5, ty, t.text, false);
			if (body.focused() && i == cursorRow && blink) {
				int col = Math.max(r[0], Math.min(body.cursor(), r[1]));
				int cx = x + 5 + c.textWidth(text.substring(r[0], col));
				c.fill(cx, ty - 1, cx + 1, ty + 9, t.dustOn);
			}
			ty += LH;
		}
		c.noScissor();
		if (noteMax > 0) scrollbar(c, x + w - 3, y + 2, h - 4, noteScroll, noteMax);
		final int rx = x, ry = y, rw = w, rh = h;
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				body.setFocused(true);
				titleInput.setFocused(false);
			}
		});
		// Klick-Position → Cursor (über die Maus bei Klick: in mouseClicked gesetzt).
		pendingEditorRect[0] = rx;
		pendingEditorRect[1] = ry;
		pendingEditorRect[2] = rw;
		pendingEditorRect[3] = rh;
		lastMeasure = c;
	}

	private final int[] pendingEditorRect = new int[4];
	private Canvas lastMeasure;

	/** Klick in den Editor: Cursor an die Stelle setzen (vom Menü vor den Klickflächen aufgerufen). */
	public void mouseClicked(double mx, double my) {
		if (!editing || view != View.NOTE || editRows == null || lastMeasure == null || popup != null) return;
		if (!inside(mx, my, pendingEditorRect[0], pendingEditorRect[1], pendingEditorRect[2], pendingEditorRect[3])) return;
		int row = editFirst + (int) Math.floor((my - editTextY) / LH);
		if (row < 0) row = 0;
		if (row >= editRows.size()) {
			body.setCursor(body.text().length());
			return;
		}
		int[] r = editRows.get(row);
		String text = body.text();
		int pos = r[0];
		double target = mx - editTextX;
		while (pos < r[1]) {
			int next = text.offsetByCodePoints(pos, 1);
			int wNext = lastMeasure.textWidth(text.substring(r[0], next));
			int wPrev = lastMeasure.textWidth(text.substring(r[0], pos));
			if (target < (wPrev + wNext) / 2.0) break;
			pos = next;
		}
		body.setCursor(pos);
		followCursor = true;
	}

	private static int rowOf(List<int[]> rows, int cursor) {
		int row = rows.size() - 1;
		for (int i = 0; i < rows.size(); i++) {
			int[] r = rows.get(i);
			if (cursor >= r[0] && cursor <= r[1]) {
				// Am Zeilenende eines umbrochenen Absatzes: nächste Zeile, wenn sie direkt anschließt.
				if (cursor == r[1] && i + 1 < rows.size() && rows.get(i + 1)[0] == cursor) continue;
				return i;
			}
			if (cursor < r[0]) return Math.max(0, i - 1);
		}
		return Math.max(0, row);
	}

	/** Lesen: Checklisten zum Abhaken, Koordinaten als Links, Klick in den Text = bearbeiten. */
	private void reader(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my, final NoteBook book, final Note note) {
		Theme t = Theme.get();
		final String text = note.text;
		final Canvas measure = c;
		List<NoteLayout.Row> rows = NoteLayout.layout(text, w - 14, new ChatLayout.Measure() {
			@Override
			public int width(String s) {
				return measure.textWidth(s);
			}
		});
		Redstone.well(c, x, y, w, h, t.border);
		setScrollRect(x, y, w, h);
		if (text.trim().isEmpty()) {
			Paint.paragraph(c, I18n.tr("notes.emptyNote"), x + 5, y + 4, w - 12, LH, t.textDim);
		}
		int contentH = rows.size() * LH + 8;
		noteMax = Math.max(0, contentH - h);
		noteScroll = Math.max(0, Math.min(noteScroll, noteMax));
		// Klick in freie Fläche: bearbeiten (zuerst registriert = liegt unter Kästchen und Links).
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				host.click();
				setEditing(true);
			}
		});
		c.scissor(x + 1, y + 1, x + w - 1, y + h - 1);
		hits.clip(x + 1, y + 1, w - 2, h - 2);
		int ty = y + 4 - noteScroll;
		int linkColor = ColorMath.lerp(t.accent, 0xFFFFFFFF, 0.25f);
		for (final NoteLayout.Row r : rows) {
			if (ty + LH < y || ty > y + h) {
				ty += LH;
				continue;
			}
			int tx = x + 5;
			if (r.check >= 0) {
				boolean bh = inside(mx, my, tx - 2, ty - 2, 11, 11);
				NotePanel.box(c, tx, ty, r.check == 1, bh ? t.dustOn : t.text);
				hits.add(tx - 2, ty - 2, r.indent + 2, 11, new Runnable() {
					@Override
					public void run() {
						host.click();
						toggleLine(book, note, r.line);
					}
				});
			}
			int color = r.checkedItem ? ColorMath.withAlpha(t.textDim, 220) : t.text;
			int px = tx + r.indent;
			int pos = r.start;
			for (final ChatCoords.Hit hit : r.coords) {
				if (hit.start > pos) {
					String s = text.substring(pos, hit.start);
					c.text(s, px, ty, color, false);
					px += c.textWidth(s);
				}
				String link = text.substring(hit.start, hit.end);
				int lw = c.textWidth(link);
				boolean lh = inside(mx, my, px, ty - 1, lw, LH);
				c.text(link, px, ty, lh ? t.dustOn : linkColor, false);
				c.fill(px, ty + 8, px + lw, ty + 9, ColorMath.withAlpha(lh ? t.dustOn : linkColor, 160));
				final int ax = px, ay = ty;
				hits.add(px, ty - 1, lw, LH, new Runnable() {
					@Override
					public void run() {
						host.click();
						popup = new int[] {hit.x, hit.y, hit.z, ax, ay + LH};
					}
				});
				px += lw;
				pos = hit.end;
			}
			if (pos < r.end) c.text(text.substring(pos, r.end), px, ty, color, false);
			ty += LH;
		}
		hits.noClip();
		c.noScissor();
		if (noteMax > 0) scrollbar(c, x + w - 3, y + 2, h - 4, noteScroll, noteMax);
	}

	private void toggleLine(NoteBook book, Note note, int line) {
		Notes n = notes();
		if (n == null) return;
		String text = NoteText.toggle(note.text, line);
		n.store().update(book, note, note.title, text, System.currentTimeMillis());
		body.setText(note.text);
		titleInput.setText(note.title);
	}

	// --- Koordinaten-Menü ---

	private void popup(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final int px = popup[0], py = popup[1], pz = popup[2];
		String[] labels = {I18n.tr("notes.coord.showMap"), I18n.tr("notes.coord.tempWaypoint"), I18n.tr("notes.coord.saveWaypoint")};
		String head = NoteText.position(px, py, pz);
		int bw = c.textWidth(head) + 12;
		for (String l : labels) bw = Math.max(bw, c.textWidth(l) + 16);
		bw = Math.min(bw, w);
		int bh = 14 + labels.length * 17 + 4;
		int ox = Math.max(x, Math.min(popup[3], x + w - bw));
		int oy = popup[4] + bh > y + h ? Math.max(y, popup[4] - LH - bh - 2) : popup[4];
		// Klick daneben schließt (liegt über allem außer den Knöpfen des Menüs).
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				popup = null;
			}
		});
		c.push();
		c.raise(20f);
		Redstone.glow(c, ox, oy, bw, bh, t.glow, 0.3f);
		Redstone.stone(c, ox, oy, bw, bh, t.surfaceHigh, ColorMath.lerp(t.border, t.accent, 0.6f));
		Paint.textClipped(c, head, ox + 6, oy + 4, bw - 12, t.textDim, false);
		int by = oy + 14;
		for (int i = 0; i < labels.length; i++) {
			final int action = i;
			boolean hv = inside(mx, my, ox + 3, by, bw - 6, 16);
			Paint.button(c, ox + 3, by, bw - 6, 16, c.clip(labels[i], bw - 14), i == 0, hv);
			hits.add(ox + 3, by, bw - 6, 16, new Runnable() {
				@Override
				public void run() {
					host.click();
					coordAction(action, px, py, pz);
				}
			});
			by += 17;
		}
		c.pop();
	}

	private void coordAction(int action, int x, int y, int z) {
		Notes n = notes();
		NoteBook book = book();
		Note note = note();
		popup = null;
		if (n == null || book == null) return;
		commit();
		n.save();
		String name = note == null ? null : note.displayTitle();
		Notes.Outcome o;
		if (action == 0) o = n.showOnMap(book.world, name, x, z);
		else if (action == 1) o = n.temporaryWaypoint(book.world, name, x, y, z);
		else o = n.saveWaypoint(book.world, name, x, y, z);
		if (o.message != null) say(o.message, !o.ok);
	}

	// --- Eingaben ---

	/** Esc: Menü/Eingabe/Ansicht zurück; false = Seite verlassen. */
	public boolean back() {
		if (popup != null) {
			popup = null;
			return true;
		}
		if (search.focused()) {
			if (!search.isEmpty()) search.clear();
			else search.setFocused(false);
			return true;
		}
		if (view == View.NOTE) {
			if (editing) {
				setEditing(false);
				return true;
			}
			commit();
			view = View.LIST;
			noteId = null;
			return true;
		}
		if (view == View.WORLDS) {
			view = View.LIST;
			return true;
		}
		return false;
	}

	public boolean keyPressed(UiKey key) {
		if (key == UiKey.ESCAPE) return back();
		if (view == View.LIST && search.focused()) {
			if (key == UiKey.ENTER) {
				search.setFocused(false);
				return true;
			}
			if (search.key(key)) {
				listScroll = 0;
				return true;
			}
			return false;
		}
		if (view != View.NOTE || !editing) return false;
		followCursor = true;
		if (titleInput.focused()) {
			if (key == UiKey.ENTER || key == UiKey.TAB || key == UiKey.DOWN) {
				titleInput.setFocused(false);
				body.setFocused(true);
				return true;
			}
			if (key == UiKey.PASTE) {
				String clip = host.clipboard();
				if (clip != null) {
					String cleaned = Note.clean(clip, Note.MAX_TITLE, false);
					for (int i = 0; i < cleaned.length(); i++) titleInput.type(cleaned.charAt(i));
				}
				return true;
			}
			return titleInput.key(key);
		}
		if (body.focused()) {
			switch (key) {
				case ENTER: {
					Object[] r = NoteText.enterInChecklist(body.text(), body.cursor());
					if (r != null) {
						String next = (String) r[0];
						if (Note.length(next) > Note.MAX_TEXT) {
							say(I18n.tr("notes.limit.text", Note.MAX_TEXT), true);
							return true;
						}
						body.setText(next);
						body.setCursor(((Integer) r[1]).intValue());
					} else if (!body.insert("\n")) {
						say(I18n.tr("notes.limit.text", Note.MAX_TEXT), true);
					}
					return true;
				}
				case TAB:
					body.setFocused(false);
					titleInput.setFocused(true);
					return true;
				case UP:
				case DOWN:
					moveVertical(key == UiKey.UP ? -1 : 1);
					return true;
				case PASTE: {
					String clip = host.clipboard();
					if (clip != null && Note.length(clip) > body.room()) say(I18n.tr("notes.limit.text", Note.MAX_TEXT), true);
					body.key(UiKey.PASTE, clip);
					return true;
				}
				default:
					return body.key(key, null);
			}
		}
		return false;
	}

	/** Cursor eine Anzeigezeile hoch/runter (gleiche Spalte in Zeichen). */
	private void moveVertical(int dir) {
		List<int[]> rows = editRows;
		if (rows == null || rows.isEmpty()) return;
		int row = rowOf(rows, body.cursor());
		int target = row + dir;
		if (target < 0) {
			body.setCursor(0);
			return;
		}
		if (target >= rows.size()) {
			body.setCursor(body.text().length());
			return;
		}
		int col = body.cursor() - rows.get(row)[0];
		int[] r = rows.get(target);
		body.setCursor(Math.min(r[1], r[0] + Math.max(0, col)));
	}

	public boolean charTyped(char ch) {
		if (view == View.NOTE && editing) {
			followCursor = true;
			if (titleInput.focused()) return titleInput.type(ch);
			if (body.focused()) {
				if (!body.type(ch) && body.room() <= 0) say(I18n.tr("notes.limit.text", Note.MAX_TEXT), true);
				return true;
			}
			return false;
		}
		if (view == View.LIST) {
			if (!search.focused()) {
				if (!TextInput.allowed(ch) || ch == ' ') return false;
				search.setFocused(true);
			}
			boolean typed = search.type(ch);
			if (typed) listScroll = 0;
			return typed;
		}
		return false;
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!inside(mx, my, scrollRect[0], scrollRect[1], scrollRect[2], scrollRect[3])) return false;
		int dir = (int) Math.signum(amount);
		switch (view) {
			case NOTE:
				if (editing) {
					noteScroll = Math.max(0, Math.min(noteMax, noteScroll - dir * 3));
					followCursor = false;
				} else {
					noteScroll = Math.max(0, Math.min(noteMax, noteScroll - dir * LH * 3));
				}
				return true;
			case WORLDS:
				worldsScroll = Math.max(0, Math.min(worldsMax, worldsScroll - dir * 20));
				return true;
			default:
				listScroll = Math.max(0, Math.min(listMax, listScroll - dir * ROW_H));
				return true;
		}
	}

	// --- Hilfen ---

	private void setScrollRect(int x, int y, int w, int h) {
		scrollRect[0] = x;
		scrollRect[1] = y;
		scrollRect[2] = w;
		scrollRect[3] = h;
	}

	private static void caret(Canvas c, int x, int y, int maxX) {
		if ((System.currentTimeMillis() / 500) % 2 != 0) return;
		int cx = Math.min(x, maxX);
		c.fill(cx, y, cx + 1, y + 8, Theme.get().dustOn);
	}

	private static void scrollbar(Canvas c, int x, int y, int h, int scroll, int max) {
		Theme t = Theme.get();
		int barH = Math.max(12, h * h / (h + max));
		int barY = y + (h - barH) * scroll / Math.max(1, max);
		c.fill(x, y, x + 2, y + h, t.dustOff);
		c.fill(x, barY, x + 2, barY + barH, t.dustOn);
	}

	/** „vor 5 Min.“ usw. */
	static String ago(long at) {
		long m = Math.max(0, (System.currentTimeMillis() - at) / 60_000L);
		if (m < 1) return I18n.tr("notes.ago.now");
		if (m < 60) return I18n.tr("notes.ago.minutes", m);
		long hrs = m / 60;
		if (hrs < 48) return I18n.tr("notes.ago.hours", hrs);
		return I18n.tr("notes.ago.days", hrs / 24);
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
