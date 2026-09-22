package dev.theredstonee.trsclient.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gespeicherter Zustand eines Moduls (Gson-DTO). Alle Felder sind optional –
 * fehlende Werte werden beim Laden durch die Standardwerte des Moduls ersetzt.
 */
public final class ModuleConfig {
	public Boolean enabled;
	public String anchor;
	public Double offsetX;
	public Double offsetY;
	public Map<String, Boolean> flags = new LinkedHashMap<>();
	public Map<String, Double> numbers = new LinkedHashMap<>();
	/** Farben als "#RRGGBB". */
	public Map<String, String> colors = new LinkedHashMap<>();

	/** Gson kann null-Maps liefern (z. B. {"flags": null}); hier auf leere Maps normalisieren. */
	public ModuleConfig normalized() {
		if (flags == null) flags = new LinkedHashMap<>();
		if (numbers == null) numbers = new LinkedHashMap<>();
		if (colors == null) colors = new LinkedHashMap<>();
		return this;
	}
}
