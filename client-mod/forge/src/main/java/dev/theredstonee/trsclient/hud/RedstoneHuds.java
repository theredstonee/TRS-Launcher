package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.redstone.RedstonePanels;
import dev.theredstonee.trsclient.core.redstone.RedstoneTools;
import dev.theredstonee.trsclient.core.redstone.SignalOverlay;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * Redstone-Anzeigen im HUD: Signalstärke, Takt-Messer und das Welt-Overlay über dem Staub.
 * Gezeichnet wird versionsunabhängig in {@code core.redstone} – hier nur die Anbindung.
 */
public final class RedstoneHuds {
	/** Textbreite mit der Schrift des Spiels (einmal angelegt, kein Objekt je Frame). */
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Minecraft.getInstance().font.width(text);
		}
	};

	private RedstoneHuds() {
	}

	/** Signalstärke des angeschauten Bauteils. */
	public static final class Signal extends HudElement {
		private final RedstonePanels.Signal panel;

		public Signal(TrsModules modules, RedstoneTools tools) {
			super(modules.redstoneSignal);
			this.panel = new RedstonePanels.Signal(modules, tools);
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

	/** Takt-Messer mit Oszilloskop. */
	public static final class Clock extends HudElement {
		private final RedstonePanels.Clock panel;

		public Clock(TrsModules modules, RedstoneTools tools) {
			super(modules.redstoneClock);
			this.panel = new RedstonePanels.Clock(modules, tools);
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

	/** Zahlen über dem Redstone-Staub (eigene Projektion ins HUD, wie die Wegpunkte). */
	public static final class Overlay {
		private final TrsModules modules;
		private final RedstoneTools tools;
		private final SignalOverlay painter = new SignalOverlay();

		public Overlay(TrsModules modules, RedstoneTools tools) {
			this.modules = modules;
			this.tools = tools;
		}

		public void render(Gfx g, Font font) {
			if (!modules.redstoneOverlay.isEnabled() || tools.cache().size() == 0) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null) return;
			painter.draw(GfxCanvas.of(g, font), tools.cache().entries(), Mc.cameraX(), Mc.cameraY(), Mc.cameraZ(),
					Mc.cameraYaw(), Mc.cameraPitch(), TrsClient.get().worldFov(), g.width(), g.height(),
					modules.redstoneOverlayZero.get());
		}
	}
}
