package dev.theredstonee.trsclient.core.notes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notizen auf der Platte: {@code config/trsclient/notes/<16 hex>.json} je Welt (klein, atomar geschrieben) und
 * {@code config/trsclient/notes/state.json} (angeheftete Notiz je Welt – bleibt auf diesem PC).
 *
 * <p>Nur aus dem Spiel-Thread benutzen; der Sync bekommt Kopien ({@link #snapshot}) und liefert Ergebnisse, die hier
 * im Spiel-Thread eingespielt werden ({@link #applyRemote}).
 */
public final class NotesStore {
	/** Höchstens so viele Welten mit Notizen. */
	public static final int MAX_BOOKS = 1000;
	/** Grabsteine bleiben so lange (dann weiß jeder PC, der sich in der Zeit meldet, von der Löschung). */
	public static final long TOMBSTONE_TTL_MS = 90L * 24 * 60 * 60 * 1000;
	static final String STATE = "state.json";
	static final String SYNC_STATE = "sync-state.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Datei eines Buchs (Gson-DTO). */
	static final class BookFile {
		int format = 1;
		String key;
		String name;
		List<Note> notes = new ArrayList<Note>();
	}

	/** Zustand dieses PCs (Gson-DTO). */
	static final class StateFile {
		Map<String, String> pins = new LinkedHashMap<String, String>();
	}

	private final Path dir;
	private final Map<String, NoteBook> books = new LinkedHashMap<String, NoteBook>();
	private StateFile state = new StateFile();
	private boolean stateDirty;
	/** Zählt lokale Änderungen (Sync: „seit dem letzten Abgleich geändert?“). */
	private long revision;
	private long changedAt;

	public NotesStore(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	// --- Laden/Speichern ---

	/** Lädt alle Bücher (kaputte Dateien werden übersprungen). */
	public NotesStore load(long now) {
		books.clear();
		state = new StateFile();
		if (dir == null || !Files.isDirectory(dir)) return this;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path p : files) {
				String name = p.getFileName().toString();
				if (name.equals(STATE) || name.equals(SYNC_STATE)) continue;
				BookFile f = read(p, BookFile.class);
				if (f == null) continue;
				NoteWorld w = NoteWorld.fromKey(f.key, f.name);
				if (w == null || books.containsKey(w.key()) || books.size() >= MAX_BOOKS) continue;
				NoteBook book = new NoteBook(w);
				if (f.notes != null) {
					for (Note n : f.notes) {
						Note ok = n == null ? null : n.normalized();
						if (ok != null && book.byId(ok.id) == null) book.put(ok);
					}
				}
				book.pruneTombstones(now - TOMBSTONE_TTL_MS);
				book.dirty = false;
				books.put(w.key(), book);
			}
		} catch (IOException | RuntimeException e) {
			// Ordner nicht lesbar: mit dem Gelesenen weitermachen.
		}
		StateFile s = dir == null ? null : read(dir.resolve(STATE), StateFile.class);
		if (s != null && s.pins != null) state = s;
		if (state.pins == null) state.pins = new LinkedHashMap<String, String>();
		return this;
	}

	private static <T> T read(Path p, Class<T> type) {
		if (!Files.isRegularFile(p)) return null;
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			return GSON.fromJson(r, type);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Schreibt geänderte Bücher und den Zustand (atomar). */
	public void save() throws IOException {
		if (dir == null) return;
		for (NoteBook b : books.values()) {
			if (!b.dirty) continue;
			Path file = dir.resolve(b.world.fileName());
			if (b.isEmpty()) {
				Files.deleteIfExists(file);
			} else {
				BookFile f = new BookFile();
				f.key = b.world.key();
				f.name = b.world.name.isEmpty() ? null : b.world.name;
				f.notes = new ArrayList<Note>(b.all());
				write(file, f);
			}
			b.dirty = false;
		}
		if (stateDirty) {
			write(dir.resolve(STATE), state);
			stateDirty = false;
		}
	}

	/** Speichern, Fehler schlucken (Rückgabe: Fehlermeldung oder null). */
	public String saveQuietly() {
		try {
			save();
			return null;
		} catch (IOException | RuntimeException e) {
			return e.toString();
		}
	}

	public boolean dirty() {
		if (stateDirty) return true;
		for (NoteBook b : books.values()) if (b.dirty) return true;
		return false;
	}

	static void write(Path file, Object data) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) Files.createDirectories(parent);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(data, w);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// --- Bücher ---

	/** Buch einer Welt (legt es im Speicher an; auf die Platte kommt es erst mit der ersten Notiz). */
	public NoteBook book(NoteWorld world) {
		if (world == null) return null;
		NoteBook b = books.get(world.key());
		if (b == null) {
			b = new NoteBook(world);
			books.put(world.key(), b);
		}
		return b;
	}

	/** Vorhandenes Buch zum Schlüssel oder null. */
	public NoteBook existing(String key) {
		return key == null ? null : books.get(key);
	}

	/** Welten mit Notizen, zuletzt bearbeitete zuerst. */
	public List<NoteBook> booksWithNotes() {
		List<NoteBook> out = new ArrayList<NoteBook>();
		for (NoteBook b : books.values()) if (b.liveCount() > 0) out.add(b);
		Collections.sort(out, new Comparator<NoteBook>() {
			@Override
			public int compare(NoteBook a, NoteBook b) {
				long x = a.lastUpdated(), y = b.lastUpdated();
				return x == y ? a.world.label().compareToIgnoreCase(b.world.label()) : (x > y ? -1 : 1);
			}
		});
		return out;
	}

	/** Darf in dieser Welt noch ein Buch angelegt werden (Grenze der Welten)? */
	public boolean canUseBook(NoteBook book) {
		if (book == null) return false;
		if (!book.isEmpty()) return true;
		int used = 0;
		for (NoteBook b : books.values()) if (!b.isEmpty()) used++;
		return used < MAX_BOOKS;
	}

	// --- Ändern (Oberfläche) ---

	/** Neue Notiz; null = Grenze erreicht. */
	public Note create(NoteBook book, long now) {
		if (book == null || !canUseBook(book)) return null;
		Note n = book.create(now);
		if (n != null) changed(now);
		return n;
	}

	/** Titel/Text setzen (nur wenn sich etwas ändert). Rückgabe: geändert. */
	public boolean update(NoteBook book, Note note, String title, String text, long now) {
		if (book == null || note == null || note.deleted) return false;
		String t = Note.clean(title, Note.MAX_TITLE, false);
		String x = Note.clean(text, Note.MAX_TEXT, true);
		if (t.equals(note.title) && x.equals(note.text)) return false;
		note.title = t;
		note.text = x;
		note.updated = Math.max(now, note.updated + 1);
		book.dirty = true;
		changed(now);
		return true;
	}

	/** Löschen (Grabstein) – löst auch das Anheften. */
	public void delete(NoteBook book, Note note, long now) {
		if (book == null || note == null || note.deleted) return;
		note.tombstone(now);
		book.dirty = true;
		if (note.id.equals(state.pins.get(book.world.key()))) {
			state.pins.remove(book.world.key());
			stateDirty = true;
		}
		changed(now);
	}

	private void changed(long now) {
		revision++;
		changedAt = now;
	}

	public long revision() {
		return revision;
	}

	public long changedAt() {
		return changedAt;
	}

	// --- Anheften (HUD) ---

	/** Angeheftete Notiz der Welt oder null. */
	public Note pinned(NoteBook book) {
		if (book == null) return null;
		Note n = book.byId(state.pins.get(book.world.key()));
		return n == null || n.deleted ? null : n;
	}

	/** Notiz anheften ({@code note} null = lösen). */
	public void pin(NoteBook book, Note note) {
		if (book == null) return;
		if (note == null) {
			if (state.pins.remove(book.world.key()) != null) stateDirty = true;
			return;
		}
		String old = state.pins.put(book.world.key(), note.id);
		if (!note.id.equals(old)) stateDirty = true;
	}

	// --- Sync ---

	/** Eine Notiz mit ihrer Welt (Kopie). */
	public static final class Entry {
		public final NoteWorld world;
		public final Note note;

		public Entry(NoteWorld world, Note note) {
			this.world = world;
			this.note = note;
		}
	}

	/** Kopien aller Einträge (inkl. Grabsteine) für den Sync-Thread. */
	public List<Entry> snapshot() {
		List<Entry> out = new ArrayList<Entry>();
		for (NoteBook b : books.values()) {
			for (Note n : b.all()) out.add(new Entry(b.world, n.copy()));
		}
		return out;
	}

	/** Lokale Fassung einer Notiz (egal welche Welt) oder null. */
	public Entry find(String id) {
		for (NoteBook b : books.values()) {
			Note n = b.byId(id);
			if (n != null) return new Entry(b.world, n);
		}
		return null;
	}

	/**
	 * Notiz ganz entfernen (ohne Grabstein), wenn sie noch den Stand {@code updated} hat – anderswo gelöscht und auf
	 * dem Server schon vergessen ({@code reset}). Rückgabe: entfernt.
	 */
	public boolean dropIfUnchanged(String id, long updated) {
		for (NoteBook b : books.values()) {
			Note n = b.byId(id);
			if (n == null) continue;
			if (n.updated != updated) return false;
			b.removeById(id);
			if (id.equals(state.pins.get(b.world.key()))) {
				state.pins.remove(b.world.key());
				stateDirty = true;
			}
			return true;
		}
		return false;
	}

	/**
	 * Stand des Kontos einspielen, wenn er neuer ist (letzter Schreiber gewinnt je Notiz). Rückgabe: übernommen.
	 * Wechselt eine Notiz die Welt (auf einem anderen PC verschoben), zieht sie mit.
	 */
	public boolean applyRemote(NoteWorld world, Note remote) {
		Note r = remote == null ? null : remote.copy().normalized();
		if (world == null || r == null) return false;
		Entry local = find(r.id);
		if (local != null && local.note.updated > r.updated) return false;
		if (local != null && local.note.updated == r.updated && local.note.sameContent(r) && local.world.equals(world)) return false;
		if (local == null && r.deleted) return false;
		if (local != null && !local.world.equals(world)) {
			NoteBook old = books.get(local.world.key());
			if (old != null) {
				old.removeById(r.id);
			}
		}
		NoteBook book = book(world);
		book.put(r);
		if (r.deleted && r.id.equals(state.pins.get(world.key()))) {
			state.pins.remove(world.key());
			stateDirty = true;
		}
		return true;
	}
}
