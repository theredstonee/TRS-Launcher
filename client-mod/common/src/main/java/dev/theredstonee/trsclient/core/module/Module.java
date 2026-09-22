package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ein an-/ausschaltbares Feature des TRS Clients (versionsunabhängig). */
public class Module {
	private final String id;
	private final String name;
	private final String description;
	private final boolean defaultEnabled;
	private final List<Setting> settings = new ArrayList<>();
	private boolean enabled;
	/** Menü-Reiter; null = automatisch (HUD-Module → HUD, sonst Sonstiges). */
	private Category category;
	/** Symbol-ID (siehe core.ui.Icons); null = Symbol der Kategorie. */
	private String icon;

	public Module(String id, String name, String description, boolean defaultEnabled) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.defaultEnabled = defaultEnabled;
		this.enabled = defaultEnabled;
	}

	/** Registriert eine Einstellung (Reihenfolge = Anzeige-Reihenfolge im Menü). */
	public <S extends Setting> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
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

	/** Treffer für die Menü-Suche (Name, Beschreibung, Einstellungen; ohne Groß-/Kleinschreibung). */
	public boolean matches(String query) {
		if (query == null) return true;
		String q = query.trim().toLowerCase(java.util.Locale.ROOT);
		if (q.isEmpty()) return true;
		if (name.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		if (description.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		if (category().label().toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		for (Setting s : settings) {
			if (s.label().toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		}
		return false;
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
