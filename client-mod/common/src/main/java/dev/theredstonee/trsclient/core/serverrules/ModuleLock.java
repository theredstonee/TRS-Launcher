package dev.theredstonee.trsclient.core.serverrules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regel des Servers für EIN Modul: {@code enabled} ({@code false} = gesperrt aus, {@code true} = erzwungen an,
 * {@code null} = der Spieler entscheidet) und Regeln für einzelne Einstellungen.
 */
public final class ModuleLock {
	public final String moduleId;
	/** Erzwungen an/aus; null = keine Sperre. */
	public final Boolean enabled;
	/** Regeln je Einstellungs-Schlüssel (Reihenfolge wie vom Server). */
	public final Map<String, SettingLock> settings;

	ModuleLock(String moduleId, Boolean enabled, Map<String, SettingLock> settings) {
		this.moduleId = moduleId;
		this.enabled = enabled;
		this.settings = Collections.unmodifiableMap(new LinkedHashMap<String, SettingLock>(settings));
	}

	/** Hat die Regel überhaupt eine Wirkung? */
	boolean empty() {
		return enabled == null && settings.isEmpty();
	}

	@Override
	public String toString() {
		return moduleId + "{enabled=" + enabled + ", settings=" + settings + "}";
	}
}
