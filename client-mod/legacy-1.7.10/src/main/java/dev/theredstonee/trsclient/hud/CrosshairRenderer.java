package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.List;

/**
 * Zeichnet das eigene Fadenkreuz. Die Geometrie kommt aus {@link Crosshair} (common) und
 * wird nur bei geänderten Einstellungen neu berechnet. 1.7.10 hat keine Angriffs-Abklingzeit
 * (kam mit 1.9) – die Einstellung ist hier ausgeblendet.
 */
public final class CrosshairRenderer {
	private static final int OUTLINE = 0xB0000000;

	private final TrsModules modules;
	private List<int[]> rects = Collections.emptyList();
	private List<int[]> outline = Collections.emptyList();
	private long key = Long.MIN_VALUE;

	public CrosshairRenderer(TrsModules modules) {
		this.modules = modules;
	}

	/** Soll das eigene statt des Vanilla-Fadenkreuzes gezeichnet werden? */
	public boolean replacesVanilla() {
		return modules.crosshair.isEnabled();
	}

	private void refresh() {
		int size = modules.crosshairSize.getInt();
		int gap = modules.crosshairGap.getInt();
		int thick = modules.crosshairThickness.getInt();
		long k = ((long) modules.crosshairShape.get().ordinal() << 24) | ((long) size << 16) | ((long) gap << 8) | thick;
		if (k == key) return;
		key = k;
		rects = Crosshair.rects(modules.crosshairShape.get(), size, gap, thick);
		outline = Crosshair.outline(rects);
	}

	/** Zeichnet das Fadenkreuz mit Mittelpunkt (cx, cy) – im Spiel und in der Vorschau des Editors. */
	public void draw(int cx, int cy) {
		refresh();
		if (modules.crosshairOutline.get()) {
			for (int[] r : outline) Brand.fill(cx + r[0], cy + r[1], cx + r[2], cy + r[3], OUTLINE);
		}
		int color = modules.crosshairColor.argb();
		for (int[] r : rects) Brand.fill(cx + r[0], cy + r[1], cx + r[2], cy + r[3], color);
	}

	/** Im HUD (statt des Vanilla-Fadenkreuzes, das per RenderGameOverlayEvent.Pre abgebrochen wird). */
	public void drawInGame(int sw, int sh) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || mc.gameSettings.hideGUI || mc.gameSettings.thirdPersonView != 0) return;
		draw(sw / 2, sh / 2);
	}
}
