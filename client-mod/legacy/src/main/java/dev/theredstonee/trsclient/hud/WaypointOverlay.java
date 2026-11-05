package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.WaypointHud;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.Entity;

/**
 * Wegpunkte in der Welt: Das Zeichnen steckt in {@link WaypointHud}, hier nur Kamera und Zeichenfläche dieser Version
 * ({@code RenderGameOverlayEvent.Post}, keine Welt-Renderei, kein Mixin).
 *
 * <p>In der Verfolger-Perspektive (F5) steht die Kamera hinter dem Spieler; die Markierungen sitzen dann ein Stück daneben.
 */
public final class WaypointOverlay {
	private final TrsModules modules;
	private final WaypointHud hud = new WaypointHud();

	public WaypointOverlay(TrsModules modules) {
		this.modules = modules;
	}

	public void render(Gfx g, FontRenderer font, float partialTicks) {
		if (!modules.waypoints.isEnabled()) return;
		if (Mc.player() == null || Mc.world() == null) return;
		Entity view = Mc.viewEntity();
		if (view == null) return;
		double camX = Mc.lerp(view.lastTickPosX, view.posX, partialTicks);
		double camY = Mc.lerp(view.lastTickPosY, view.posY, partialTicks) + view.getEyeHeight();
		double camZ = Mc.lerp(view.lastTickPosZ, view.posZ, partialTicks);
		float yaw;
		float pitch;
		if (TrsClient.get().pvp().freelook().active()) {
			yaw = TrsClient.get().pvp().freelook().yaw();
			pitch = TrsClient.get().pvp().freelook().pitch();
		} else {
			yaw = (float) Mc.lerp(view.prevRotationYaw, view.rotationYaw, partialTicks);
			pitch = (float) Mc.lerp(view.prevRotationPitch, view.rotationPitch, partialTicks);
		}
		hud.draw(GfxCanvas.of(g, font), modules, TrsClient.get().waypoints().visible(), TrsClient.get().waypoints().target(), camX, camY, camZ, yaw, pitch,
				TrsClient.get().worldFov(), g.width(), g.height());
	}
}
