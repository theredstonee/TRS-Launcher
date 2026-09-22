package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.ui.Brand;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Einzeiliges Text-Element (FPS, CPS, Ping). Text und Breite werden nur neu
 * berechnet, wenn sich der angezeigte Wert ändert – keine Allokationen pro Frame.
 */
public abstract class TextHudElement extends HudElement {
	private static final int PAD_X = 5;
	private static final int PAD_Y = 4;

	private String text = "";
	private int textWidth = -1;
	private long cachedKey = Long.MIN_VALUE;

	protected TextHudElement(HudModule module) {
		super(module);
	}

	/** Aktueller Wert als Schlüssel; bei gleichem Schlüssel bleibt der Text erhalten. */
	protected abstract long valueKey(boolean preview);

	/** Baut den Text zum Schlüssel (nur bei Änderung aufgerufen). */
	protected abstract String format(long key);

	private void refresh(Font font, boolean preview) {
		long key = valueKey(preview);
		if (key != cachedKey || textWidth < 0) {
			cachedKey = key;
			text = format(key);
			textWidth = font.width(text);
		}
	}

	@Override
	public int width(Font font, boolean preview) {
		refresh(font, preview);
		return textWidth + PAD_X * 2;
	}

	@Override
	public int height(Font font, boolean preview) {
		return 8 + PAD_Y * 2;
	}

	@Override
	public void draw(GuiGraphics g, Font font, boolean preview) {
		refresh(font, preview);
		boolean bg = module.background.get();
		if (bg) g.fill(0, 0, textWidth + PAD_X * 2, 8 + PAD_Y * 2, Brand.HUD_BG);
		g.drawString(font, text, PAD_X, PAD_Y, textColor(), !bg);
	}
}
