package dev.theredstonee.trsclient.core.shield;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;

/**
 * Vorlagen der Schild-Haltung. Die Zahlen sind eigene Werte des TRS Clients (Versatz zur Vanilla-Haltung, siehe
 * {@link ShieldPose}); „Eigene“ behält, was der Spieler an den Reglern eingestellt hat.
 */
public enum ShieldPreset implements ChoiceSetting.Option {
	/** Seitlich und etwas tiefer, beim Blocken flach am Rand – der Standard beim Einschalten. */
	SIDE("Side",
			new ShieldPose(0.12, -0.08, 0.0, 0, -12, 0, 0.85),
			new ShieldPose(0.22, -0.16, 0.04, 8, -18, -4, 0.85)),
	/** Weit unten, beim Blocken nur knapp über dem Bildrand. */
	LOW("Low",
			new ShieldPose(0.04, -0.20, 0.0, 0, 0, 0, 0.90),
			new ShieldPose(0.10, -0.30, 0.0, 12, -6, 0, 0.90)),
	/** Alles neutral – wie ohne Modul, aber mit weichem Übergang ins Blocken. */
	VANILLA("Vanilla", ShieldPose.NEUTRAL, ShieldPose.NEUTRAL),
	/** Die eigenen Reglerwerte. */
	CUSTOM("Custom", null, null);

	private final String label;
	private final ShieldPose normal;
	private final ShieldPose blocking;

	ShieldPreset(String label, ShieldPose normal, ShieldPose blocking) {
		this.label = label;
		this.normal = normal;
		this.blocking = blocking;
	}

	@Override
	public String label() {
		return label;
	}

	/** Haltung ohne Blocken (null bei {@link #CUSTOM}). */
	public ShieldPose normal() {
		return normal;
	}

	/** Haltung beim Blocken (null bei {@link #CUSTOM}). */
	public ShieldPose blocking() {
		return blocking;
	}

	/** Feste Werte (alles außer „Eigene“)? */
	public boolean fixed() {
		return normal != null;
	}
}
