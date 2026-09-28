package dev.theredstonee.trsclient.core.notes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Notizbuch einer Welt: alle Notizen samt Grabsteinen, in Einfüge-Reihenfolge. */
public final class NoteBook {
	/** Höchstens so viele (nicht gelöschte) Notizen je Welt. */
	public static final int MAX_NOTES = 200;

	public final NoteWorld world;
	private final List<Note> notes = new ArrayList<Note>();
	boolean dirty;

	public NoteBook(NoteWorld world) {
		this.world = world;
	}

	/** Alle Einträge inkl. Grabsteine (nur lesen). */
	public List<Note> all() {
		return Collections.unmodifiableList(notes);
	}

	/** Nicht gelöschte Notizen, neueste Änderung zuerst. */
	public List<Note> live() {
		List<Note> out = new ArrayList<Note>();
		for (Note n : notes) if (!n.deleted) out.add(n);
		Collections.sort(out, NEWEST_FIRST);
		return out;
	}

	/** Treffer der Suche (neueste zuerst). */
	public List<Note> search(String query) {
		List<Note> out = new ArrayList<Note>();
		for (Note n : live()) if (NoteText.matches(n, query)) out.add(n);
		return out;
	}

	public int liveCount() {
		int c = 0;
		for (Note n : notes) if (!n.deleted) c++;
		return c;
	}

	/** Letzte Änderung einer Notiz (0 = keine). */
	public long lastUpdated() {
		long t = 0;
		for (Note n : notes) if (!n.deleted) t = Math.max(t, n.updated);
		return t;
	}

	public boolean canAdd() {
		return liveCount() < MAX_NOTES;
	}

	public Note byId(String id) {
		if (id == null) return null;
		for (Note n : notes) if (id.equals(n.id)) return n;
		return null;
	}

	/** Neue Notiz anlegen; null, wenn die Grenze erreicht ist. */
	public Note create(long now) {
		if (!canAdd()) return null;
		Note n = Note.create(now);
		notes.add(n);
		dirty = true;
		return n;
	}

	/** Eintrag übernehmen/ersetzen (Laden, Sync). */
	void put(Note n) {
		for (int i = 0; i < notes.size(); i++) {
			if (notes.get(i).id.equals(n.id)) {
				notes.set(i, n);
				dirty = true;
				return;
			}
		}
		notes.add(n);
		dirty = true;
	}

	/** Eintrag ganz entfernen (Notiz ist auf einem anderen PC in eine andere Welt gewandert). */
	void removeById(String id) {
		for (int i = notes.size() - 1; i >= 0; i--) {
			if (notes.get(i).id.equals(id)) {
				notes.remove(i);
				dirty = true;
			}
		}
	}

	/** Grabsteine älter als {@code before} entfernen. */
	int pruneTombstones(long before) {
		int removed = 0;
		for (int i = notes.size() - 1; i >= 0; i--) {
			Note n = notes.get(i);
			if (n.deleted && n.updated < before) {
				notes.remove(i);
				removed++;
			}
		}
		if (removed > 0) dirty = true;
		return removed;
	}

	public boolean isEmpty() {
		return notes.isEmpty();
	}

	static final Comparator<Note> NEWEST_FIRST = new Comparator<Note>() {
		@Override
		public int compare(Note a, Note b) {
			return a.updated == b.updated ? a.id.compareTo(b.id) : (a.updated > b.updated ? -1 : 1);
		}
	};
}
