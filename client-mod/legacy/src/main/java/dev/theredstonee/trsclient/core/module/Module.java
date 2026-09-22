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
