package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.ui.Canvas;

/**
 * Ein HUD-Element aus Sicht des Editors: Modul, unskalierte Größe der Vorschau und das
 * Zeichnen bei (0, 0). Die Umrechnung von Position und Größe macht der Editor.
 */
public interface HudItem {
	HudModule module();

	/** Unskalierte Breite der Editor-Vorschau. */
	int width();

	/** Unskalierte Höhe der Editor-Vorschau. */
	int height();

	/** Zeichnet die Vorschau bei (0, 0) – der Editor hat bereits verschoben und skaliert. */
	void draw(Canvas canvas);
}
