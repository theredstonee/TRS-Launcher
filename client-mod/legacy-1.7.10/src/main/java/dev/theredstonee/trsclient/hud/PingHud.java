package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.net.PingPanel;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.ui.BrandCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

/**
 * Ping mit Jitter und Verlauf (Messung und Zeichnen in {@code core.net}: {@link PingPanel}). In Minecraft 1.7.10 kommt der
 * Wert vom Server (Spielerliste) – eigene Ping-Anfragen gibt es im Protokoll dieser Version nicht.
 */
public final class PingHud extends HudElement {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Minecraft.getMinecraft().fontRenderer.getStringWidth(text);
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
	public int width(FontRenderer font, boolean preview) {
		return panel.width(MEASURE, preview);
	}

	@Override
	public int height(FontRenderer font, boolean preview) {
		return panel.height(preview);
	}

	@Override
	public void draw(FontRenderer font, boolean preview) {
		panel.draw(BrandCanvas.of(font), MEASURE, preview);
	}
}
