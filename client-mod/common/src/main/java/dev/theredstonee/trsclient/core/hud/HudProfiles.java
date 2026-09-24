package dev.theredstonee.trsclient.core.hud;

import dev.theredstonee.trsclient.core.config.ConfigPart;
import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HUD-Profile: mehrere gespeicherte HUD-Layouts (z. B. "PvP", "Bauen", "Aufnahme").
 * Ein Profil enthält den vollständigen Zustand aller HUD-Module (An/Aus, Position, Größe, Aussehen
 * und Modul-Einstellungen) sowie der Module mit {@link Module#profiled()} (Umhang-Physik, Farben).
 * Andere Module (Zoom, Fullbright, …) sind profilunabhängig.
 * Der Zustand des aktiven Profils steht in den Modulen selbst; beim Wechsel wird er ins Profil
 * zurückgeschrieben und das Ziel-Profil angewendet.
 */
public final class HudProfiles implements ConfigPart {
	public static final int MAX_NAME_LENGTH = 24;
	public static final int MAX_PROFILES = 16;
	public static final String DEFAULT_NAME = "Standard";

	private static final class Profile {
		String name;
		Map<String, ModuleConfig> modules;

		Profile(String name, Map<String, ModuleConfig> modules) {
			this.name = name;
			this.modules = modules;
		}
	}

	private final ModuleRegistry registry;
	private final List<Profile> profiles = new ArrayList<>();
	private int active;

	public HudProfiles(ModuleRegistry registry) {
		this.registry = registry;
		profiles.add(new Profile(DEFAULT_NAME, snapshot()));
		registry.addPart(this);
	}

	// --- Abfragen ---

	public int size() {
		return profiles.size();
	}

	public String name(int index) {
		return profiles.get(index).name;
	}

	public List<String> names() {
		List<String> out = new ArrayList<>();
		for (Profile p : profiles) out.add(p.name);
		return Collections.unmodifiableList(out);
	}

	public int activeIndex() {
		return active;
	}

	public String activeName() {
		return profiles.get(active).name;
	}

	public boolean canCreate() {
		return profiles.size() < MAX_PROFILES;
	}

	public boolean canDelete() {
		return profiles.size() > 1;
	}

	// --- Aktionen ---

	/** Wechselt zum Profil {@code index}; der aktuelle Zustand wird vorher im aktiven Profil gesichert. */
	public void switchTo(int index) {
		if (index < 0 || index >= profiles.size() || index == active) return;
		profiles.get(active).modules = snapshot();
		active = index;
		applyActive();
	}

	/** Nächstes Profil (rundherum); liefert den neuen Namen. */
	public String cycle() {
		switchTo((active + 1) % profiles.size());
		return activeName();
	}

	/**
	 * Legt ein neues Profil als Kopie des aktuellen Layouts an und aktiviert es.
	 * @return Fehlermeldung oder null bei Erfolg
	 */
	public String create(String name) {
		if (!canCreate()) return dev.theredstonee.trsclient.core.i18n.I18n.tr("profiles.error.max", MAX_PROFILES);
		String clean = clean(name);
		String error = validate(clean, -1);
		if (error != null) return error;
		profiles.get(active).modules = snapshot();
		profiles.add(new Profile(clean, snapshot()));
		active = profiles.size() - 1;
		return null;
	}

	/** @return Fehlermeldung oder null bei Erfolg */
	public String rename(int index, String name) {
		if (index < 0 || index >= profiles.size()) return dev.theredstonee.trsclient.core.i18n.I18n.tr("profiles.error.unknown");
		String clean = clean(name);
		String error = validate(clean, index);
		if (error != null) return error;
		profiles.get(index).name = clean;
		return null;
	}

	/** Löscht ein Profil (nie das letzte). Beim aktiven Profil wird das vorherige aktiviert. */
	public boolean delete(int index) {
		if (!canDelete() || index < 0 || index >= profiles.size()) return false;
		if (index == active) {
			profiles.remove(index);
			active = Math.max(0, index - 1);
			applyActive();
		} else {
			profiles.remove(index);
			if (index < active) active--;
		}
		return true;
	}

	/** Freier Vorschlagsname, z. B. "Profil 2". */
	public String suggestName() {
		for (int i = profiles.size() + 1; ; i++) {
			String candidate = dev.theredstonee.trsclient.core.i18n.I18n.tr("profiles.suggest", i);
			if (indexOf(candidate) < 0) return candidate;
		}
	}

	/** Prüft einen Namen; {@code ignoreIndex} = eigenes Profil beim Umbenennen. @return Fehler oder null */
	public String validate(String name, int ignoreIndex) {
		String clean = clean(name);
		if (clean.isEmpty()) return dev.theredstonee.trsclient.core.i18n.I18n.tr("profiles.error.empty");
		if (clean.length() > MAX_NAME_LENGTH) return dev.theredstonee.trsclient.core.i18n.I18n.tr("profiles.error.long", MAX_NAME_LENGTH);
		int existing = indexOf(clean);
		if (existing >= 0 && existing != ignoreIndex) return dev.theredstonee.trsclient.core.i18n.I18n.tr("profiles.error.taken");
		return null;
	}

	/** Entfernt Steuerzeichen und Leerraum am Rand. */
	public static String clean(String name) {
		if (name == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (!Character.isISOControl(c) && c != '§') sb.append(c);
		}
		return sb.toString().trim();
	}

	private int indexOf(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		for (int i = 0; i < profiles.size(); i++) {
			if (profiles.get(i).name.toLowerCase(Locale.ROOT).equals(key)) return i;
		}
		return -1;
	}

	/** Zustand aller HUD-Module als tiefe Kopie. */
	private Map<String, ModuleConfig> snapshot() {
		Map<String, ModuleConfig> map = new LinkedHashMap<>();
		for (Module m : registry.all()) {
			if (m.inProfiles()) map.put(m.id(), m.write());
		}
		return map;
	}

	/** Wendet das aktive Profil an. Module, die das Profil (noch) nicht kennt, bleiben unverändert. */
	private void applyActive() {
		Map<String, ModuleConfig> map = profiles.get(active).modules;
		for (Module m : registry.all()) {
			if (!m.inProfiles()) continue;
			ModuleConfig c = map.get(m.id());
			if (c != null) m.read(c.copy().normalized());
		}
	}

	// --- Config ---

	@Override
	public void read(TrsConfig config) {
		profiles.clear();
		active = 0;
		TrsConfig.HudProfiles stored = config.hudProfiles;
		if (stored != null && stored.profiles != null) {
			for (TrsConfig.Profile p : stored.profiles) {
				if (p == null || profiles.size() >= MAX_PROFILES) continue;
				String name = clean(p.name);
				if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH).trim();
				if (name.isEmpty() || indexOf(name) >= 0) name = suggestName();
				Map<String, ModuleConfig> modules = new LinkedHashMap<>();
				if (p.modules != null) {
					for (Map.Entry<String, ModuleConfig> e : p.modules.entrySet()) {
						if (e.getKey() != null && e.getValue() != null) modules.put(e.getKey(), e.getValue().normalized());
					}
				}
				profiles.add(new Profile(name, modules));
				if (stored.active != null && clean(p.name).equalsIgnoreCase(clean(stored.active))) active = profiles.size() - 1;
			}
		}
		if (profiles.isEmpty()) {
			// Version 1 oder leer: aktuelles Layout wird Profil "Standard".
			profiles.add(new Profile(DEFAULT_NAME, snapshot()));
			active = 0;
		}
		// Das aktive Profil entspricht dem Modulzustand (steht in "modules").
		profiles.get(active).modules = snapshot();
	}

	@Override
	public void write(TrsConfig config) {
		profiles.get(active).modules = snapshot();
		TrsConfig.HudProfiles out = new TrsConfig.HudProfiles();
		out.active = activeName();
		for (Profile p : profiles) {
			TrsConfig.Profile tp = new TrsConfig.Profile();
			tp.name = p.name;
			for (Map.Entry<String, ModuleConfig> e : p.modules.entrySet()) tp.modules.put(e.getKey(), e.getValue().copy());
			out.profiles.add(tp);
		}
		config.hudProfiles = out;
	}
}
