package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.WaypointHud;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/** Wegpunkte in der Welt: Das Zeichnen steckt in {@link WaypointHud}, hier nur Kamera und Zeichenfläche dieser Version. */
public final class WaypointOverlay {
	private final TrsModules modules;
	private final WaypointHud hud = new WaypointHud();

	public WaypointOverlay(TrsModules modules) {
		this.modules = modules;
	}

	public void render(Gfx g, Font font) {
		if (!modules.waypoints.isEnabled()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		hud.draw(GfxCanvas.of(g, font), modules, TrsClient.get().waypoints().visible(), TrsClient.get().waypoints().target(), Mc.cameraX(), Mc.cameraY(),
				Mc.cameraZ(), Mc.cameraYaw(), Mc.cameraPitch(), TrsClient.get().worldFov(), g.width(), g.height());
	}
}
