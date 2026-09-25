package dev.theredstonee.trsclient.core.intro;

import dev.theredstonee.trsclient.core.module.TrsModules;

/**
 * Wahl des Config-Modus der Optimierungs-Mods im Einführungsschritt „Leistung“ (und später im TRS-Menü):
 * {@link #PRETTY} („Schön“ – nur Leistungs-Schalter ohne sichtbaren Optik-Verlust) oder {@link #MAX}
 * („Max FPS“ – aggressiv).
 *
 * <p>Schmale Schnittstelle zum Verdrahten: Die eigentliche Umsetzung (Configs von Sodium, ImmediatelyFast … schreiben)
 * liefert das Leistungs-Paket in {@code core.perf} und setzt sie mit {@link #install(FpsModeChooser)}. Bis dahin
 * speichert die {@link Default}-Umsetzung nur den gewählten Modus in der Client-Config ({@link ClientState#fpsMode()});
 * der Modus gehört zum {@code client}-Sync-Dokument (Abschnitt {@code prefs}).
 */
public interface FpsModeChooser {
	String PRETTY = "pretty";
	String MAX = "max";

	/** Aktueller Modus ({@link #PRETTY}/{@link #MAX}) oder null, wenn nie gewählt. */
	String current(TrsModules modules);

	/** Modus wählen und anwenden (nur nach einem Klick des Spielers). */
	void apply(TrsModules modules, String mode);

	/** Gibt es in dieser Version überhaupt Optimierungs-Mods zum Einstellen? (false = Schritt zeigt nur den Hinweis.) */
	boolean available();

	/** Nur speichern (Platzhalter, bis core.perf die echte Umsetzung setzt). */
	final class Default implements FpsModeChooser {
		@Override
		public String current(TrsModules modules) {
			String m = modules.clientState.fpsMode();
			return PRETTY.equals(m) || MAX.equals(m) ? m : null;
		}

		@Override
		public void apply(TrsModules modules, String mode) {
			if (PRETTY.equals(mode) || MAX.equals(mode)) modules.clientState.setFpsMode(mode);
		}

		@Override
		public boolean available() {
			return true;
		}
	}

	/** Die aktive Umsetzung. */
	final class Holder {
		private static volatile FpsModeChooser chooser = new Default();

		private Holder() {
		}
	}

	/** Setzt die Umsetzung (null = zurück zum Platzhalter). */
	static void install(FpsModeChooser chooser) {
		Holder.chooser = chooser != null ? chooser : new Default();
	}

	static FpsModeChooser get() {
		return Holder.chooser;
	}
}
