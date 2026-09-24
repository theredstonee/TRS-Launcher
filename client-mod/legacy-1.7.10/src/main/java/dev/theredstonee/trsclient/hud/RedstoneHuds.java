package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.redstone.RedstonePanels;
import dev.theredstonee.trsclient.core.redstone.RedstoneTools;
import dev.theredstonee.trsclient.core.redstone.SignalOverlay;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.ui.BrandCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.opengl.GL11;

/**
 * Redstone-Anzeigen im HUD (Minecraft 1.7.10): Signalstärke, Takt-Messer und das Welt-Overlay über dem
 * Staub. Gezeichnet wird versionsunabhängig in {@code core.redstone} über {@link BrandCanvas}.
 */
public final class RedstoneHuds {
	private static final TextWidth MEASURE = new TextWidth() {
		@Override
		public int width(String text) {
			return Minecraft.getMinecraft().fontRenderer.getStringWidth(text);
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
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.thePlayer == null || mc.theWorld == null) return;
			EntityLivingBase view = mc.renderViewEntity != null ? mc.renderViewEntity : mc.thePlayer;
			double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
			// 1.7.10: posY des eigenen Spielers liegt schon auf Augenhöhe
			double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks
					+ (view == mc.thePlayer ? 0 : view.getEyeHeight());
			double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
			float yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partialTicks;
			float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partialTicks;
			// Während des Bildes steht in fovSetting schon das gezoomte Sichtfeld (siehe TrsClient).
			double fov = mc.gameSettings.fovSetting;
			painter.draw(BrandCanvas.of(font), tools.cache().entries(), camX, camY, camZ, yaw, pitch, fov, sw, sh,
					modules.redstoneOverlayZero.get());
			GL11.glColor4f(1f, 1f, 1f, 1f);
		}
	}
}
