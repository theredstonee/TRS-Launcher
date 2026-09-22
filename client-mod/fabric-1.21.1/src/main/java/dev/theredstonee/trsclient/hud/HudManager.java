package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.hud.HudLayout;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Hält alle HUD-Elemente und zeichnet sie an ihrer gespeicherten Position. */
public final class HudManager {
	private final Minecraft mc = Minecraft.getInstance();
	private final List<HudElement> elements;
	/** Wiederverwendeter Puffer für {@link #bounds}: x, y, Breite, Höhe (skaliert). */
	private final int[] box = new int[4];

	public HudManager(TrsModules modules) {
		this.elements = List.of(
				new FpsHud(modules.fps),
				new CpsHud(modules.cps),
				new KeystrokesHud(modules.keystrokes, modules),
				new PingHud(modules.ping));
	}

	public List<HudElement> elements() {
		return elements;
	}

	/** HUD-Callback (jeden Frame). */
	public void render(GuiGraphics g, DeltaTracker delta) {
		if (mc.options.hideGui || mc.screen instanceof HudEditorScreen) return;
		Font font = mc.font;
		int sw = g.guiWidth();
		int sh = g.guiHeight();
		for (int i = 0, n = elements.size(); i < n; i++) {
			HudElement e = elements.get(i);
			if (e.module().isEnabled() && e.visible()) draw(g, font, e, sw, sh, false);
		}
	}

	/** Zeichnet ein Element an seiner Position (auch vom Editor genutzt). */
	public void draw(GuiGraphics g, Font font, HudElement e, int sw, int sh, boolean preview) {
		int[] b = bounds(font, e, sw, sh, preview);
		float scale = e.module().scale.getFloat();
		g.pose().pushPose();
		g.pose().translate(b[0], b[1], 0);
		g.pose().scale(scale, scale, 1f);
		e.draw(g, font, preview);
		g.pose().popPose();
	}

	/**
	 * Berechnet die skalierte Box eines Elements. Achtung: gibt einen geteilten Puffer zurück,
	 * der beim nächsten Aufruf überschrieben wird.
	 */
	public int[] bounds(Font font, HudElement e, int sw, int sh, boolean preview) {
		float scale = e.module().scale.getFloat();
		int w = (int) Math.ceil(e.width(font, preview) * scale);
		int h = (int) Math.ceil(e.height(font, preview) * scale);
		box[0] = HudLayout.resolveX(e.module().position(), w, sw);
		box[1] = HudLayout.resolveY(e.module().position(), h, sh);
		box[2] = w;
		box[3] = h;
		return box;
	}
}
