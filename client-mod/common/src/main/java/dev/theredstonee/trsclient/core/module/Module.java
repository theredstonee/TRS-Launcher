package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.i18n.I18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ein an-/ausschaltbares Feature des TRS Clients (versionsunabhängig). */
public class Module {
	private final String id;
	/** Englischer Name/Text – nur Rückfall, falls die Übersetzungsdatei fehlt. */
	private final String name;
	private final String description;
	/** Übersetzungsschlüssel "module.<id>" und "module.<id>.desc". */
	private final String nameKey;
	private final String descriptionKey;
	private final boolean defaultEnabled;
	private final List<Setting> settings = new ArrayList<>();
	private boolean enabled;
	/** Menü-Reiter; null = automatisch (HUD-Module → HUD, sonst Sonstiges). */
	private Category category;
	/** Symbol-ID (siehe core.ui.Icons); null = Symbol der Kategorie. */
	private String icon;
	/** Wird zusätzlich zu den HUD-Modulen in den Profilen gespeichert (z. B. Umhang-Physik, Farben). */
	private boolean profiled;

	public Module(String id, String name, String description, boolean defaultEnabled) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.nameKey = "module." + id;
		this.descriptionKey = "module." + id + ".desc";
		this.defaultEnabled = defaultEnabled;
		this.enabled = defaultEnabled;
	}

	/** Registriert eine Einstellung (Reihenfolge = Anzeige-Reihenfolge im Menü). */
	public <S extends Setting> S add(S setting) {
		setting.bind(id);
		settings.add(setting);
		return setting;
	}

	public String id() {
		return id;
	}

	/** Name in der aktiven Sprache. */
	public String name() {
		return I18n.trOr(nameKey, name);
	}

	/** Beschreibung in der aktiven Sprache. */
	public String description() {
		return I18n.trOr(descriptionKey, description);
	}

	/** Übersetzungsschlüssel des Namens ("module.<id>"). */
	public String nameKey() {
		return nameKey;
	}

	/** Übersetzungsschlüssel der Beschreibung ("module.<id>.desc"). */
	public String descriptionKey() {
		return descriptionKey;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public void toggle() {
		enabled = !enabled;
	}

	public List<Setting> settings() {
		return Collections.unmodifiableList(settings);
	}

	public boolean isHud() {
		return false;
	}

	/** Gehört der Zustand dieses Moduls zu den Profilen? (HUD-Module immer.) */
	public boolean inProfiles() {
		return profiled || isHud();
	}

	/** Speichert dieses Modul zusätzlich in den Profilen (verkettbar). */
	public Module profiled() {
		this.profiled = true;
		return this;
	}

	/** Reiter im Menü. */
	public Category category() {
		if (category != null) return category;
		return isHud() ? Category.HUD : Category.MISC;
	}

	/** Setzt den Menü-Reiter (verkettbar: {@code registry.register(new Module(...)).category(Category.PVP)}). */
	public Module category(Category category) {
		this.category = category;
		return this;
	}

	/** Symbol-ID für die Kachel im Menü (siehe {@code core.ui.Icons#ids()}). */
	public String icon() {
		return icon != null ? icon : category().icon();
	}

	/** Setzt das Symbol der Kachel (unbekannte IDs fallen auf das Kategorie-Symbol zurück). */
	public Module icon(String icon) {
		this.icon = icon;
		return this;
	}

	/**
	 * Treffer für die Menü-Suche (Name, Beschreibung, Einstellungen; ohne Groß-/Kleinschreibung) –
	 * in der aktiven Sprache und zusätzlich auf Englisch.
	 */
	public boolean matches(String query) {
		if (query == null) return true;
		String q = query.trim().toLowerCase(java.util.Locale.ROOT);
		if (q.isEmpty()) return true;
		if (contains(name(), q) || contains(name, q)) return true;
		if (contains(description(), q) || contains(description, q)) return true;
		if (contains(category().label(), q)) return true;
		for (Setting s : settings) {
			if (contains(s.label(), q) || contains(s.fallbackLabel(), q)) return true;
		}
		return false;
	}

	private static boolean contains(String text, String lowerQuery) {
		return text != null && text.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery);
	}

	/** Übernimmt den Zustand aus der Config; fehlende Werte → Standard. */
	public void read(ModuleConfig config) {
		enabled = config.enabled != null ? config.enabled : defaultEnabled;
		for (Setting s : settings) s.read(config);
	}

	/** Schreibt den aktuellen Zustand in eine neue Config. */
	public ModuleConfig write() {
		ModuleConfig config = new ModuleConfig();
		config.enabled = enabled;
		for (Setting s : settings) s.write(config);
		return config;
	}

	/** Alles auf Standard zurücksetzen. */
	public void reset() {
		enabled = defaultEnabled;
		for (Setting s : settings) s.reset();
	}
}
