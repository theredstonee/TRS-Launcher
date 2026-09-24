package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Zusätzlicher Bereich auf der Einstellungsseite eines Moduls, zwischen Beschreibung und
 * Einstellungen (z. B. FPS vorher/nachher, Leistungs-Check, „übernimmt Sodium“). Zeichnet wie das
 * restliche Menü im Immediate Mode und registriert seine Klickflächen in {@link Hits}.
 */
public interface ModulePanel {
	/**
	 * @param click Klick-Geräusch des Menüs (für eigene Knöpfe)
	 * @return y unter dem Bereich (= {@code y}, wenn nichts gezeichnet wurde)
	 */
	int draw(Canvas c, Hits hits, int x, int y, int w, int mouseX, int mouseY, Runnable click);

	/** Bereiche je Modul (gesetzt vom Code, der das Modul betreibt). */
	final class Registry {
		private static final Map<Module, ModulePanel> PANELS = new IdentityHashMap<Module, ModulePanel>();

		private Registry() {
		}

		public static synchronized void set(Module module, ModulePanel panel) {
			if (panel == null) PANELS.remove(module);
			else PANELS.put(module, panel);
		}

		public static synchronized ModulePanel of(Module module) {
			return PANELS.get(module);
		}
	}
}
