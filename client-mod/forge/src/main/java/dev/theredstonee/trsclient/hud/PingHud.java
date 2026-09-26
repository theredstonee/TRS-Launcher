package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.net.PingPanel;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * Ping mit Jitter und Verlauf (Messung und Zeichnen in {@code core.net}: {@link PingPanel}); im Einzelspieler
 * ausgeblendet.
 */
public final class PingHud extends HudElement {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Minecraft.getInstance().font.width(text);
		}
	};
	private final PingPanel panel;

	public PingHud(TrsModules modules) {
		super(modules.ping);
		this.panel = new PingPanel(modules);
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
