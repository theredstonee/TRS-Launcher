package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MinimapRenderer;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.map.MapBridge;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.gui.Font;

/**
 * Minimap im HUD – nur die Anbindung: Abtasten/Speichern/Zeichnen stecken in {@code core.map}
 * ({@link MapEngine}, {@link MinimapRenderer}) und sind in allen Versionen gleich.
 */
public final class MinimapHud extends HudElement {
	private final TrsModules modules;
	private final MapBridge bridge = new MapBridge();
	private final MinimapRenderer renderer = new MinimapRenderer();
	private Gfx frame;

	/** Scissor für die gedrehte eckige Karte: ab 1.21.6 im lokalen System, davor in GUI-Koordinaten. */
	private final MinimapRenderer.Clip clip = new MinimapRenderer.Clip() {
		@Override
		public boolean begin(int x1, int y1, int x2, int y2) {
			if (frame == null) return false;
			//? if >=1.21.6 {
			/*frame.scissor(x1, y1, x2, y2);
			*///?} else {
			float s = originScale;
			frame.scissor(Math.round(originX + x1 * s), Math.round(originY + y1 * s), Math.round(originX + x2 * s),
					Math.round(originY + y2 * s));
			//?}
			return true;
		}

		@Override
		public void end() {
			if (frame != null) frame.noScissor();
		}
	};

	public MinimapHud(HudModule module, TrsModules modules) {
		super(module);
		this.modules = modules;
	}

	public MapBridge bridge() {
		return bridge;
	}

	@Override
	public boolean visible() {
		MapEngine e = MapEngine.get();
		return mc.level != null && mc.player != null && (e == null || e.fairPlay().minimapAllowed());
	}

	@Override
	public int width(Font font, boolean preview) {
		return renderer.width(modules);
	}

	@Override
	public int height(Font font, boolean preview) {
		return renderer.height(modules);
	}

	/** Einmal je Client-Tick: Karte abtasten (Zeitbudget), Spieler/Kreaturen merken. */
	public void tick() {
		MapEngine e = MapEngine.get();
		if (e != null) e.tick(bridge);
	}

	public void onWorldChange() {
		// Die Karte erkennt Welt-/Dimensionswechsel selbst.
	}

	@Override
	public void draw(Gfx g, Font font, boolean preview) {
		MapEngine e = MapEngine.get();
		if (e == null) return;
		double scale = Mc.window().getGuiScale() * module.scale.getFloat();
		frame = preview ? null : g;
		try {
			renderer.draw(GfxCanvas.of(g, font), e, preview, scale, preview ? null : clip);
		} finally {
			frame = null;
		}
	}
}
