package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.clips.ClipPanel;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * Clips & Aufnahme im HUD (roter Punkt + Zeit, Puffer-Symbol, "Clip gespeichert"). Gezeichnet wird
 * versionsunabhängig in {@code core.clips.ClipPanel} – hier nur die Anbindung.
 */
public final class ClipHud extends HudElement {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Minecraft.getInstance().font.width(text);
		}
	};

	private final ClipPanel panel;

	public ClipHud(TrsModules modules) {
		super(modules.clips);
		this.panel = new ClipPanel(modules);
	}

	@Override
	public boolean visible() {
		return panel.visible();
	}

	@Override
	public int width(Font font, boolean preview) {
		return panel.width(MEASURE, preview);
	}

	@Override
	public int height(Font font, boolean preview) {
		return panel.height(preview);
	}

	@Override
	public void draw(Gfx g, Font font, boolean preview) {
		panel.draw(GfxCanvas.of(g, font), MEASURE, preview);
	}
}
