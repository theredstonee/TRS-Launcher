package dev.theredstonee.trsclient.core.input;

import dev.theredstonee.trsclient.core.i18n.I18n;

/** Was die Toggle-Sprint/-Schleichen-Anzeige im HUD zeigt, z. B. „[Sprinten (umgeschaltet)]“. */
public enum ToggleStatus {
	NONE(null, ""),
	SPRINT_TOGGLED("hud.status.sprintToggled", "Sprinting (Toggled)"),
	SPRINT_HELD("hud.status.sprintHeld", "Sprinting (Key held)"),
	FLY_BOOST("hud.status.flyBoost", "Flying (Boost)"),
	SNEAK_TOGGLED("hud.status.sneakToggled", "Sneaking (Toggled)"),
	SNEAK_HELD("hud.status.sneakHeld", "Sneaking (Key held)");

	private final String key;
	private final String fallback;
	private String cached;
	private int cachedGeneration = -1;

	ToggleStatus(String key, String fallback) {
		this.key = key;
		this.fallback = fallback;
	}

	/** Übersetzungsschlüssel (null bei {@link #NONE}). */
	public String key() {
		return key;
	}

	/** Anzeigetext in eckigen Klammern (zwischengespeichert je Sprache); leer bei {@link #NONE}. */
	public String text() {
		if (key == null) return "";
		int gen = I18n.generation();
		if (cached == null || cachedGeneration != gen) {
			cached = "[" + I18n.trOr(key, fallback) + "]";
			cachedGeneration = gen;
		}
		return cached;
	}
}
