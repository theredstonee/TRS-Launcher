package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.redstone.RedstonePanels;
import dev.theredstonee.trsclient.core.redstone.RedstoneTools;
import dev.theredstonee.trsclient.core.redstone.SignalOverlay;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.ui.BrandCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.Entity;

/**
 * Redstone-Anzeigen im HUD (Forge 1.13.2): Signalstärke, Takt-Messer und das Welt-Overlay über dem
 * Staub. Gezeichnet wird versionsunabhängig in {@code core.redstone} über {@link BrandCanvas}.
 */
public final class RedstoneHuds {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Minecraft.getInstance().fontRenderer.getStringWidth(text);
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

	/** Zahlen über dem Redstone-Staub (eigene Projektion ins HUD – ohne Welt-Renderei). */
	public static final class Overlay {
		private final TrsModules modules;
		private final RedstoneTools tools;
		private final SignalOverlay painter = new SignalOverlay();

		public Overlay(TrsModules modules, RedstoneTools tools) {
			this.modules = modules;
			this.tools = tools;
		}

		public void render(FontRenderer font, int sw, int sh, float partialTicks) {
			if (!modules.redstoneOverlay.isEnabled() || tools.cache().size() == 0) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.world == null) return;
			Entity view = mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : mc.player;
			double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
			double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks + view.getEyeHeight();
			double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
			float yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partialTicks;
			float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partialTicks;
			painter.draw(BrandCanvas.of(font), tools.cache().entries(), camX, camY, camZ, yaw, pitch,
					TrsClient.get().worldFov(), sw, sh, modules.redstoneOverlayZero.get());
		}
	}
}
