package dev.theredstonee.trsclient.core.notes;

import dev.theredstonee.trsclient.core.i18n.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemeinsamer Teil des Selbsttests {@code -PtrsAutotestOnly=notes} (Fabric, Legacy-Forge): legt Beispiel-Notizen in der
 * aktuellen Welt an, heftet eine an und räumt am Ende wieder auf. Nur für Autotests.
 */
public final class NotesSelfTest {
	private final List<String> created = new ArrayList<String>();
	private NoteWorld world;
	private Note main;
	private String pinnedBefore;

	/** Beispiel-Notizen anlegen (Koordinaten 20 Blöcke vor dem Spieler); false = keine Welt. */
	public boolean seed() {
		Notes n = Notes.get();
		if (n == null) return false;
		world = n.currentWorld();
		int[] p = n.position();
		if (world == null || p == null) return false;
		NotesStore store = n.store();
		NoteBook book = store.book(world);
		Note old = store.pinned(book);
		pinnedBefore = old == null ? null : old.id;
		long now = System.currentTimeMillis();
		main = store.create(book, now);
		if (main == null) return false;
		store.update(book, main, I18n.tr("notes.hud.sampleTitle"),
				"[x] " + I18n.tr("notes.tool.checklist") + " 1\n[ ] " + I18n.tr("notes.tool.checklist") + " 2\n"
						+ NoteText.position(p[0] + 20, p[1], p[2]) + "\n\n" + I18n.tr("notes.bodyHint"), now + 1);
		created.add(main.id);
		Note second = store.create(book, now + 2);
		store.update(book, second, "Redstone", "[ ] Piston door\n[ ] Item sorter\nx: 100, y: 64, z: -20", now + 3);
		created.add(second.id);
		Note third = store.create(book, now + 4);
		store.update(book, third, "", "Villager trades: mending 12 emeralds", now + 5);
		created.add(third.id);
		store.pin(book, main);
		n.modules().notes.pinnedNote.setEnabled(true);
		n.save();
		return true;
	}

	public NoteWorld world() {
		return world;
	}

	public Note main() {
		return main;
	}

	/** Koordinaten der ersten Notiz {x, y, z}. */
	public int[] target() {
		Notes n = Notes.get();
		int[] p = n == null ? null : n.position();
		return p == null ? new int[] {0, 64, 0} : new int[] {p[0] + 20, p[1], p[2]};
	}

	/** Zusammenfassung fürs Log. */
	public String summary() {
		Notes n = Notes.get();
		if (n == null || world == null) return "keine Welt";
		NoteBook b = n.store().existing(world.key());
		Note pinned = n.store().pinned(b);
		return "Welt " + world.key() + " (" + world.label() + "), " + (b == null ? 0 : b.liveCount()) + " Notizen, angeheftet "
				+ (pinned == null ? "-" : pinned.displayTitle()) + ", Checkliste " + NoteText.progress(main == null ? "" : main.text)[0]
				+ "/" + NoteText.progress(main == null ? "" : main.text)[1] + ", vorübergehender Wegpunkt "
				+ (n.temporary() == null ? "-" : n.temporary().name + " @ " + n.temporary().x + "," + n.temporary().z);
	}

	/** Angelegte Notizen löschen (Grabsteine bleiben) und die alte Anheftung zurück. */
	public void cleanup() {
		Notes n = Notes.get();
		if (n == null || world == null) return;
		NoteBook book = n.store().existing(world.key());
		long now = System.currentTimeMillis();
		for (String id : created) {
			Note note = book == null ? null : book.byId(id);
			if (note != null) n.store().delete(book, note, now);
		}
		if (book != null && pinnedBefore != null) n.store().pin(book, book.byId(pinnedBefore));
		n.save();
	}
}
