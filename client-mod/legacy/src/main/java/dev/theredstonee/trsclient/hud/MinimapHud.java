package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MinimapRenderer;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.map.MapBridge;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.gui.FontRenderer;

/**
 * Minimap im HUD (Forge 1.8.9–1.12.2) – nur die Anbindung: Abtasten/Speichern/Zeichnen stecken in
 * {@code core.map} und sind in allen Versionen gleich.
 */
public final class MinimapHud extends HudElement {
	private final TrsModules modules;
	private final MapBridge bridge = new MapBridge();
	private final MinimapRenderer renderer = new MinimapRenderer();
	private Gfx frame;

	/** Scissor für die gedrehte eckige Karte (GL-Scissor in GUI-Koordinaten des Bildschirms). */
	private final MinimapRenderer.Clip clip = new MinimapRenderer.Clip() {
		@Override
		public boolean begin(int x1, int y1, int x2, int y2) {
			if (frame == null) return false;
			float s = originScale;
			frame.scissor(Math.round(originX + x1 * s), Math.round(originY + y1 * s), Math.round(originX + x2 * s),
					Math.round(originY + y2 * s));
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

	@Override
	public boolean visible() {
		MapEngine e = MapEngine.get();
		return Mc.world() != null && Mc.player() != null && (e == null || e.fairPlay().minimapAllowed());
	}

	@Override
	public int width(FontRenderer font, boolean preview) {
		return renderer.width(modules);
	}

	@Override
	public int height(FontRenderer font, boolean preview) {
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
	public void draw(Gfx g, FontRenderer font, boolean preview) {
		MapEngine e = MapEngine.get();
		if (e == null) return;
		double scale = Mc.scaledResolution().getScaleFactor() * module.scale.getFloat();
		frame = preview ? null : g;
		try {
			renderer.draw(GfxCanvas.of(g, font), e, preview, scale, preview ? null : clip);
		} finally {
			frame = null;
		}
	}
}
