package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.i18n.I18n;

/**
 * Eine einstellbare Option eines Moduls. Der aktuelle Wert liegt als einfaches Feld
 * in der Unterklasse, damit das Rendern ohne Map-Zugriffe/Boxing auskommt.
 */
// Unterklassen: BoolSetting (Schalter), NumberSetting (Schieberegler), ColorSetting (Farbwähler),
// ChoiceSetting (Auswahlliste), KeySetting (Tastenbelegung) – das Menü kennt genau diese Typen.
public abstract class Setting {
	private final String key;
	/** Englischer Name – nur Rückfall, falls die Übersetzungsdatei fehlt. */
	private final String label;
	/** Übersetzungsschlüssel ("setting.<modul>.<key>"); gesetzt von {@link Module#add}. */
	private String labelKey;
	/** Platzhalter {0} im Namen (z. B. "Text 1"), sonst null. */
	private Object labelArg;
	private String cachedLabel;
	private int cachedGeneration = -1;

	protected Setting(String key, String label) {
		this.key = key;
		this.label = label;
	}

	/** Eigener Übersetzungsschlüssel (z. B. gemeinsame HUD-Einstellungen); vor {@link Module#add} setzen. */
	public Setting i18n(String translationKey) {
		this.labelKey = translationKey;
		return this;
	}

	/** Eigener Übersetzungsschlüssel mit Platzhalter {0}; vor {@link Module#add} setzen. */
	public Setting i18n(String translationKey, Object arg) {
		this.labelKey = translationKey;
		this.labelArg = arg;
		return this;
	}

	/** Hängt die Einstellung an ihr Modul (Standard-Schlüssel "setting.<modul>.<key>"). */
	void bind(String moduleId) {
		if (labelKey == null) labelKey = "setting." + moduleId + "." + key;
		onBound();
	}

	/** Für Unterklassen mit weiteren Texten (Optionen, Platzhalter). */
	protected void onBound() {
	}

	/** Übersetzungsschlüssel des Namens (null, solange die Einstellung keinem Modul gehört). */
	public String labelKey() {
		return labelKey;
	}

	/** Schlüssel in der Config. */
	public String key() {
		return key;
	}

	/** Anzeigename in der aktiven Sprache (zwischengespeichert – keine Allokation je Bild). */
	public String label() {
		if (labelKey == null) return fallbackLabel();
		int gen = I18n.generation();
		if (cachedLabel == null || cachedGeneration != gen) {
			String pattern = I18n.trOr(labelKey, label);
			cachedLabel = labelArg == null ? pattern : I18n.format(pattern, labelArg);
			cachedGeneration = gen;
		}
		return cachedLabel;
	}

	/** Englischer Name aus dem Code (die Suche findet Module auch auf Englisch). */
	public String fallbackLabel() {
		return labelArg == null ? label : I18n.format(label, labelArg);
	}

	/** Übernimmt den Wert aus der Config (oder den Standard). */
	public abstract void read(ModuleConfig config);

	/** Schreibt den aktuellen Wert in die Config. */
	public abstract void write(ModuleConfig config);

	/** Setzt auf den Standardwert zurück. */
	public abstract void reset();
}
