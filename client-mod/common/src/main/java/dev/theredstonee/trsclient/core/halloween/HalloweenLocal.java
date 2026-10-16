package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.config.ConfigPart;
import dev.theredstonee.trsclient.core.config.TrsConfig;

/**
 * Lokaler Halloween-Zustand in {@code trsclient.json} (Teil {@code halloween}, wird nicht synchronisiert): ob „Flackern
 * &amp; Rauschen“ im Client selbst umgeschaltet wurde (sonst gilt der Launcher) und welche Hinweise schon kamen.
 */
public final class HalloweenLocal implements ConfigPart {
	/** null = nie selbst umgeschaltet. */
	private Boolean flicker;
	private boolean scareInfoShown;
	private boolean flickerHintShown;
	private int revision;

	/**
	 * „Flackern &amp; Rauschen“ (Filmkorn + Blitz): eigene Wahl im Client, sonst der Launcher, sonst an (Vertrag:
	 * Standard an).
	 */
	public boolean flicker() {
		Boolean own = flicker;
		if (own != null) return own;
		Boolean launcher = Halloween.launcherFlicker();
		return launcher == null || launcher;
	}

	/** Im Client umgeschaltet (gilt ab jetzt statt des Launchers). */
	public void setFlicker(boolean on) {
		flicker = on;
		revision++;
	}

	/** Hat der Spieler „Flackern &amp; Rauschen“ hier schon selbst gewählt? */
	public boolean flickerChosen() {
		return flicker != null;
	}

	public boolean scareInfoShown() {
		return scareInfoShown;
	}

	public void markScareInfoShown() {
		if (scareInfoShown) return;
		scareInfoShown = true;
		revision++;
	}

	public boolean flickerHintShown() {
		return flickerHintShown;
	}

	public void markFlickerHintShown() {
		if (flickerHintShown) return;
		flickerHintShown = true;
		revision++;
	}

	/** Steigt mit jeder Änderung (Speichern auslösen). */
	public int revision() {
		return revision;
	}

	@Override
	public void read(TrsConfig config) {
		TrsConfig.HalloweenData d = config.halloween;
		flicker = d == null ? null : d.flicker;
		scareInfoShown = d != null && Boolean.TRUE.equals(d.scareInfoShown);
		flickerHintShown = d != null && Boolean.TRUE.equals(d.flickerHintShown);
		revision++;
	}

	@Override
	public void write(TrsConfig config) {
		if (flicker == null && !scareInfoShown && !flickerHintShown) {
			config.halloween = null;
			return;
		}
		TrsConfig.HalloweenData d = new TrsConfig.HalloweenData();
		d.flicker = flicker;
		d.scareInfoShown = scareInfoShown ? Boolean.TRUE : null;
		d.flickerHintShown = flickerHintShown ? Boolean.TRUE : null;
		config.halloween = d;
	}
}
