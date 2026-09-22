package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import net.minecraft.client.Minecraft;
import net.minecraft.world.GameType;

import java.util.Collections;
import java.util.List;

/**
 * Zeichnet das eigene Fadenkreuz. Die Geometrie kommt aus {@link Crosshair} (common) und
 * wird nur bei geänderten Einstellungen neu berechnet.
 */
public final class CrosshairRenderer {
	private static final int OUTLINE = 0xB0000000;
	/** GameSettings.attackIndicator: 1 = am Fadenkreuz. */
	private static final int ATTACK_INDICATOR_CROSSHAIR = 1;

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

	/**
	 * Im HUD (statt des Vanilla-Fadenkreuzes, das per RenderGameOverlayEvent.Pre abgebrochen wird) –
	 * inklusive Abklingzeit-Balken des Angriffs, den Vanilla sonst am Fadenkreuz zeichnet.
	 */
	public void drawInGame(int sw, int sh) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameSettings.hideGUI || mc.gameSettings.thirdPersonView != 0) return;
		if (mc.playerController != null && mc.playerController.getCurrentGameType() == GameType.SPECTATOR) return;
		int cx = sw / 2;
		int cy = sh / 2;
		draw(cx, cy);
		if (modules.crosshairAttack.get() && mc.gameSettings.attackIndicator == ATTACK_INDICATOR_CROSSHAIR) {
			float scale = mc.player.getCooledAttackStrength(0.0F);
			if (scale < 1.0F) {
				int y = cy + 9 + modules.crosshairSize.getInt();
				int w = 16;
				Brand.fill(cx - w / 2, y, cx + w / 2, y + 2, 0x80000000);
				Brand.fill(cx - w / 2, y, cx - w / 2 + (int) (w * scale), y + 2, modules.crosshairColor.argb());
			}
		}
	}
}
