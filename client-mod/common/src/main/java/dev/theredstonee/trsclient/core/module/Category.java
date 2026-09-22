package dev.theredstonee.trsclient.core.module;

/** Reiter im TRS-Menü. Jedes Modul gehört zu genau einer Kategorie. */
public enum Category {
	HUD("HUD", "hud"),
	PVP("PvP", "sword"),
	CHAT("Chat", "chat"),
	WORLD("Welt", "globe"),
	MISC("Sonstiges", "gear");

	private final String label;
	private final String icon;

	Category(String label, String icon) {
		this.label = label;
		this.icon = icon;
	}

	/** Deutscher Anzeigename des Reiters. */
	public String label() {
		return label;
	}

	/** Standard-Symbol für Module ohne eigenes Symbol (siehe {@code core.ui.Icons}). */
	public String icon() {
		return icon;
	}
}
