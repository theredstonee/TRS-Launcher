package dev.theredstonee.trsclient.core.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * „NEU“-Markierungen im TRS-Menü: Module und Einstellungen, die ein Client-Update gebracht hat, tragen ein
 * „NEU“-Schild, bis man sie einmal geöffnet hat.
 *
 * <p>Neu ist ein Eintrag, wenn seine Version ({@link NewSince}) neuer ist als der {@link #baseline() Stand} und er
 * noch nicht geöffnet wurde. Der Stand ist bei einer Neuinstallation die neueste bekannte Version (nichts ist neu –
 * neue Spieler sehen stattdessen die Einführung) und nach einem Update von einer Version ohne diese Funktion
 * {@link NewSince#LEGACY_BASELINE}. Stand und geöffnete Einträge werden über den {@code client}-Sync mit dem
 * TRS-Konto abgeglichen.
 *
 * <p>Öffnet man eine Modulseite, verliert die Kachel ihr Schild sofort; die Schilder der Einstellungszeilen bleiben
 * sichtbar, bis man die Seite wieder verlässt ({@link #closed()}).
 */
public final class NewMarkers {
	/** Mehr gemerkte Einträge braucht es nie (alles Ältere als der Stand fällt ohnehin weg). */
	public static final int MAX_SEEN = 512;

	private String baseline;
	private final Set<String> seen = new LinkedHashSet<String>();
	/** Gerade geöffnete Einstellungen – ihr Schild bleibt bis {@link #closed()} sichtbar. */
	private final Set<String> lingering = new HashSet<String>();
	private int revision;

	public NewMarkers(String baseline) {
		this.baseline = NewSince.valid(baseline) ? baseline : NewSince.latest();
	}

	/** Stand: nur Einträge, die neuer sind, können „NEU“ sein. */
	public String baseline() {
		return baseline;
	}

	/** Zähler, der bei jeder Änderung steigt (für „speichern nötig?“). */
	public int revision() {
		return revision;
	}

	/** Bereits geöffnete neue Einträge (Kopie, in Einfüge-Reihenfolge). */
	public List<String> seen() {
		return new ArrayList<String>(seen);
	}

	/** Setzt Stand und geöffnete Einträge (Laden, Sync). Unbekannte/alte Einträge fallen weg. */
	public void set(String newBaseline, Collection<String> seenIds) {
		if (NewSince.valid(newBaseline)) baseline = newBaseline;
		seen.clear();
		if (seenIds != null) {
			for (String id : seenIds) {
				if (id != null && id.length() <= 96 && seen.size() < MAX_SEEN) seen.add(id);
			}
		}
		prune();
		revision++;
	}

	/** Entfernt Einträge, die ohnehin nicht (mehr) neu wären. */
	private void prune() {
		java.util.Iterator<String> it = seen.iterator();
		while (it.hasNext()) {
			String since = NewSince.of(it.next());
			// Unbekannte Einträge (neuere Mod-Version auf einem anderen PC) bleiben – dort werden sie gebraucht.
			if (since != null && NewSince.compare(since, baseline) <= 0) it.remove();
		}
	}

	/** Ist {@code id} ({@code "modul"} oder {@code "modul.schlüssel"}) neu und ungesehen? */
	public boolean isNew(String id) {
		String since = NewSince.of(id);
		return since != null && NewSince.compare(since, baseline) > 0 && !seen.contains(id);
	}

	public static String id(Module m) {
		return m.id();
	}

	public static String id(Module m, Setting s) {
		return m.id() + "." + s.key();
	}

	/** Das Modul selbst ist neu (Kachel-Schild). */
	public boolean isNew(Module m) {
		return isNew(id(m));
	}

	/** Modul oder eine seiner Einstellungen ist neu (Schild auf der Kachel). */
	public boolean hasNew(Module m) {
		if (isNew(m)) return true;
		List<Setting> settings = m.settings();
		for (int i = 0; i < settings.size(); i++) {
			if (isNew(id(m, settings.get(i)))) return true;
		}
		return false;
	}

	/** Einstellungszeile mit Schild (auch, solange die Seite nach dem Öffnen noch offen ist). */
	public boolean isNew(Module m, Setting s) {
		String id = id(m, s);
		return lingering.contains(id) || isNew(id);
	}

	/** Gibt es in {@code modules} (z. B. einer Kategorie) etwas Neues? */
	public boolean anyNew(Iterable<Module> modules) {
		for (Module m : modules) {
			if (hasNew(m)) return true;
		}
		return false;
	}

	/** Modulseite geöffnet: Modul und seine Einstellungen gelten als gesehen. */
	public void opened(Module m) {
		boolean changed = mark(id(m));
		List<Setting> settings = m.settings();
		for (int i = 0; i < settings.size(); i++) {
			String id = id(m, settings.get(i));
			if (isNew(id)) {
				lingering.add(id);
				changed |= mark(id);
			}
		}
		if (changed) revision++;
	}

	/** Seite verlassen: die Schilder der Einstellungen verschwinden. */
	public void closed() {
		lingering.clear();
	}

	private boolean mark(String id) {
		if (!isNew(id) || seen.size() >= MAX_SEEN) return false;
		return seen.add(id);
	}

	/**
	 * Zusammenführen mit dem Stand des TRS-Kontos: geöffnet ist, was irgendwo geöffnet wurde; der Stand des Kontos
	 * gilt, sobald es einen hat (der erste PC legt ihn fest).
	 *
	 * @return true, wenn sich hier etwas geändert hat
	 */
	public boolean merge(String remoteBaseline, Collection<String> remoteSeen) {
		String before = baseline + "|" + seen;
		Set<String> union = new LinkedHashSet<String>(seen);
		if (remoteSeen != null) union.addAll(remoteSeen);
		set(NewSince.valid(remoteBaseline) ? remoteBaseline : baseline, union);
		return !before.equals(baseline + "|" + seen);
	}
}
