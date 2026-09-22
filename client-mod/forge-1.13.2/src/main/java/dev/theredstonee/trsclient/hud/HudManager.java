package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.hud.HudLayout;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Hält alle HUD-Elemente und zeichnet sie an ihrer gespeicherten Position. */
public final class HudManager {
	private final Minecraft mc = Minecraft.getInstance();
	private final List<HudElement> elements;
	/** Wiederverwendeter Puffer für {@link #bounds}: x, y, Breite, Höhe (skaliert). */
	private final int[] box = new int[4];
	private final CrosshairRenderer crosshair;

	public HudManager(TrsModules modules) {
		this.crosshair = new CrosshairRenderer(modules);
		this.elements = Collections.unmodifiableList(Arrays.<HudElement>asList(
				new FpsHud(modules.fps),
				new CpsHud(modules.cps),
				new KeystrokesHud(modules.keystrokes, modules),
				new PingHud(modules.ping),
				new ArmorHud(modules.armor, modules),
				new InfoHuds.Effects(modules.effects),
				new InfoHuds.Coords(modules.coords, modules),
				new InfoHuds.Clock(modules.clock, modules),
				new InfoHuds.Memory(modules.memory),
				new InfoHuds.Server(modules.server),
				new InfoHuds.Packs(modules.packs),
				new InfoHuds.ToggleIndicator(modules.toggleSprint, "Sprinten", true),
				new InfoHuds.ToggleIndicator(modules.toggleSneak, "Schleichen", false)));
	}

	public CrosshairRenderer crosshair() {
		return crosshair;
	}

	public List<HudElement> elements() {
		return elements;
	}

	/** Aus RenderGameOverlayEvent.Post (jeden Frame), Größe in GUI-Pixeln. */
	public void render(int sw, int sh) {
		if (mc.gameSettings.hideGUI || mc.currentScreen instanceof HudEditorScreen) return;
		FontRenderer font = mc.fontRenderer;
		for (int i = 0, n = elements.size(); i < n; i++) {
			HudElement e = elements.get(i);
			if (e.module().isEnabled() && e.visible()) draw(font, e, sw, sh, false);
		}
	}

	/** Zeichnet ein Element an seiner Position (auch vom Editor genutzt). */
	public void draw(FontRenderer font, HudElement e, int sw, int sh, boolean preview) {
		int[] b = bounds(font, e, sw, sh, preview);
		float scale = e.module().scale.getFloat();
		GlStateManager.pushMatrix();
		GlStateManager.translatef(b[0], b[1], 0);
		GlStateManager.scalef(scale, scale, 1f);
		e.draw(font, preview);
		GlStateManager.popMatrix();
		GlStateManager.color4f(1f, 1f, 1f, 1f);
	}

	/**
	 * Berechnet die skalierte Box eines Elements. Achtung: gibt einen geteilten Puffer zurück,
	 * der beim nächsten Aufruf überschrieben wird.
	 */
	public int[] bounds(FontRenderer font, HudElement e, int sw, int sh, boolean preview) {
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
