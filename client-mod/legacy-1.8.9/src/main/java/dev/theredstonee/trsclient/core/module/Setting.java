package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;

/**
 * Eine einstellbare Option eines Moduls. Der aktuelle Wert liegt als einfaches Feld
 * in der Unterklasse, damit das Rendern ohne Map-Zugriffe/Boxing auskommt.
 * <p>
 * Legacy-Kopie aus {@code client-mod/common}: dort {@code sealed}, hier ohne (Java 8).
 */
public abstract class Setting {
	private final String key;
	private final String label;

	protected Setting(String key, String label) {
		this.key = key;
		this.label = label;
	}

	/** Schlüssel in der Config. */
	public String key() {
		return key;
	}

	/** Anzeigename (deutsch). */
	public String label() {
		return label;
	}

	/** Übernimmt den Wert aus der Config (oder den Standard). */
	public abstract void read(ModuleConfig config);

	/** Schreibt den aktuellen Wert in die Config. */
	public abstract void write(ModuleConfig config);

	/** Setzt auf den Standardwert zurück. */
	public abstract void reset();
}
