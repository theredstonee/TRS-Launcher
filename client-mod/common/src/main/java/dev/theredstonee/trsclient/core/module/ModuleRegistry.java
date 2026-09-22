package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ConfigPart;
import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.config.TrsConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Geordnete Liste aller Module + Übertragung von/zur Config. */
public final class ModuleRegistry {
	private final List<Module> modules = new ArrayList<>();
	private final List<ConfigPart> parts = new ArrayList<>();

	public <M extends Module> M register(M module) {
		for (Module m : modules) {
			if (m.id().equals(module.id())) throw new IllegalArgumentException("Doppelte Modul-ID: " + module.id());
		}
		modules.add(module);
		return module;
	}

	/** Weiterer Config-Teil (z. B. HUD-Profile), wird nach den Modulen gelesen/geschrieben. */
	public void addPart(ConfigPart part) {
		parts.add(part);
	}

	public List<Module> all() {
		return Collections.unmodifiableList(modules);
	}

	public Module byId(String id) {
		for (Module m : modules) {
			if (m.id().equals(id)) return m;
		}
		return null;
	}

	/** Übernimmt Werte aus der Config; unbekannte Einträge werden ignoriert, fehlende → Standard. */
	public void apply(TrsConfig config) {
		for (Module m : modules) {
			ModuleConfig mc = config.modules == null ? null : config.modules.get(m.id());
			m.read(mc == null ? new ModuleConfig() : mc.normalized());
		}
		for (ConfigPart p : parts) p.read(config);
	}

	/** Erzeugt eine Config aus dem aktuellen Zustand. */
	public TrsConfig capture() {
		TrsConfig config = new TrsConfig();
		for (Module m : modules) config.modules.put(m.id(), m.write());
		for (ConfigPart p : parts) p.write(config);
		return config;
	}
}
