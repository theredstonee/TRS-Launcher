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
	/** Auswahl-Optionen (z. B. Fadenkreuz-Form) als Options-ID. */
	public Map<String, String> choices = new LinkedHashMap<>();
	/** Tastenbelegungen als Vanilla-Tastenname (z. B. "key.keyboard.v"). */
	public Map<String, String> keys = new LinkedHashMap<>();

	/** Tiefe Kopie (für HUD-Profile). */
	public ModuleConfig copy() {
		ModuleConfig c = new ModuleConfig();
		c.enabled = enabled;
		c.anchor = anchor;
		c.offsetX = offsetX;
		c.offsetY = offsetY;
		normalized();
		c.flags.putAll(flags);
		c.numbers.putAll(numbers);
		c.colors.putAll(colors);
		c.choices.putAll(choices);
		c.keys.putAll(keys);
		return c;
	}

	/** Gson kann null-Maps liefern (z. B. {"flags": null}); hier auf leere Maps normalisieren. */
	public ModuleConfig normalized() {
		if (flags == null) flags = new LinkedHashMap<>();
		if (numbers == null) numbers = new LinkedHashMap<>();
		if (colors == null) colors = new LinkedHashMap<>();
		if (choices == null) choices = new LinkedHashMap<>();
		if (keys == null) keys = new LinkedHashMap<>();
		return this;
	}
}
