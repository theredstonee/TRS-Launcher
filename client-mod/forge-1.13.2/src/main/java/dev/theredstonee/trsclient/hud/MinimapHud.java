package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MinimapRenderer;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.map.MapBridge;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.BrandCanvas;
import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Minimap im HUD (Forge 1.13.2) – nur die Anbindung: Abtasten/Speichern/Zeichnen stecken in {@code core.map}.
 */
public final class MinimapHud extends HudElement {
	private final TrsModules modules;
	private final MapBridge bridge;
	private final MinimapRenderer renderer = new MinimapRenderer();
	private boolean drawing;

	/** Scissor für die gedrehte eckige Karte (GL-Scissor in GUI-Koordinaten des Bildschirms). */
	private final MinimapRenderer.Clip clip = new MinimapRenderer.Clip() {
		@Override
		public boolean begin(int x1, int y1, int x2, int y2) {
			if (!drawing) return false;
			float s = originScale;
			Brand.scissor(Math.round(originX + x1 * s), Math.round(originY + y1 * s), Math.round(originX + x2 * s),
					Math.round(originY + y2 * s));
			return true;
		}

		@Override
		public void end() {
			Brand.noScissor();
		}
	};

	public MinimapHud(TrsModules modules) {
		super(modules.minimap);
		this.modules = modules;
		this.bridge = new MapBridge(modules, FMLPaths.CONFIGDIR.get().resolve("trsclient-waypoints.json"));
	}

	@Override
	public boolean visible() {
		MapEngine e = MapEngine.get();
		return mc.world != null && mc.player != null && (e == null || e.fairPlay().minimapAllowed());
	}

	@Override
	public int width(FontRenderer font, boolean preview) {
		return renderer.width(modules);
	}

	@Override
	public int height(FontRenderer font, boolean preview) {
		return renderer.height(modules);
	}

	/** Einmal je Client-Tick: Todespunkt, Karte abtasten (Zeitbudget), Spieler/Kreaturen merken. */
	public void tick() {
		bridge.tickDeath();
		MapEngine e = MapEngine.get();
		if (e != null) e.tick(bridge);
	}

	@Override
	public void draw(FontRenderer font, boolean preview) {
		MapEngine e = MapEngine.get();
		if (e == null) return;
		double scale = mc.mainWindow.getGuiScaleFactor() * module.scale.getFloat();
		drawing = !preview;
		try {
			renderer.draw(BrandCanvas.of(font), e, preview, scale, preview ? null : clip);
		} finally {
			drawing = false;
		}
	}
}
