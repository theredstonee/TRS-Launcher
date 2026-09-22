package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Zeichnet das eigene Fadenkreuz. Die Geometrie kommt aus {@link Crosshair} (common) und
 * wird nur bei geänderten Einstellungen neu berechnet.
 */
public final class CrosshairRenderer {
	private static final int OUTLINE = 0xB0000000;

	private final TrsModules modules;
	private List<int[]> rects = List.of();
	private List<int[]> outline = List.of();
	private long key = Long.MIN_VALUE;

	public CrosshairRenderer(TrsModules modules) {
		this.modules = modules;
	}

	/** Soll das eigene statt des Vanilla-Fadenkreuzes gezeichnet werden? */
	public boolean replacesVanilla() {
		return modules.crosshair.isEnabled();
	}

	/** Im Spiel sichtbar: Ego-Perspektive, kein Zuschauer, HUD nicht ausgeblendet. */
	public boolean visibleInGame() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && !mc.player.isSpectator() && !Mc.hudHidden()
				&& mc.options.getCameraType().isFirstPerson();
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
	public void draw(Gfx g, int cx, int cy) {
		refresh();
		if (modules.crosshairOutline.get()) {
			for (int[] r : outline) g.fill(cx + r[0], cy + r[1], cx + r[2], cy + r[3], OUTLINE);
		}
		int color = modules.crosshairColor.argb();
		for (int[] r : rects) g.fill(cx + r[0], cy + r[1], cx + r[2], cy + r[3], color);
	}

	/** Im HUD: Fadenkreuz in der Bildschirmmitte plus Abklingzeit-Balken des Angriffs. */
	public void drawInGame(Gfx g) {
		if (!visibleInGame()) return;
		int cx = g.width() / 2;
		int cy = g.height() / 2;
		draw(g, cx, cy);
		Minecraft mc = Minecraft.getInstance();
		if (modules.crosshairAttack.get() && mc.options.attackIndicator().get() == AttackIndicatorStatus.CROSSHAIR) {
			float scale = mc.player.getAttackStrengthScale(0.0F);
			if (scale < 1.0F) {
				int y = cy + 9 + modules.crosshairSize.getInt();
				int w = 16;
				g.fill(cx - w / 2, y, cx + w / 2, y + 2, 0x80000000);
				g.fill(cx - w / 2, y, cx - w / 2 + (int) (w * scale), y + 2, modules.crosshairColor.argb());
			}
		}
	}
}
