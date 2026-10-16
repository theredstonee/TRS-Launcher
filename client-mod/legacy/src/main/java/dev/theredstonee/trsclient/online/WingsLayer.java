package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.cosmetic.v2.WingState;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;

/**
 * TRS-Flügel für Forge 1.8.9–1.12.2 (Slot {@code wings}, Format 2, {@code attach: "body"}): eigene Ebene der
 * Spieler-Renderer, zeichnet mit dem v2-Weg der Kopf-Kosmetik ({@link HatLayer#renderPart}) am Körper
 * ({@code bipedBody.postRender}) – folgt Körper-Drehung und Schleich-Neigung. Zustand idle/air/sneak mit Überblendung,
 * unsichtbar mit Elytra.
 */
public final class WingsLayer implements LayerRenderer<AbstractClientPlayer> {
	private final RenderPlayer renderer;
	private final HatLayer hats;

	public WingsLayer(RenderPlayer renderer, HatLayer hats) {
		this.renderer = renderer;
		this.hats = hats;
	}

	@Override
	public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
			float ageInTicks, float netHeadYaw, float headPitch, float scale) {
		OnlineFeatures<Object> features = LegacyOnline.features();
		if (features == null || player.isInvisible() || LegacyOnline.elytra(player)) return;
		try {
			//? if >=1.9 {
			/*boolean riding = player.isRiding();
			boolean flying = player.capabilities.isFlying || player.isElytraFlying();
			*///?} else {
			boolean riding = player.ridingEntity != null;
			boolean flying = player.capabilities.isFlying;
			//?}
			int state = WingState.classify(player.onGround, player.isSneaking(), flying, riding,
					player.isInWater() || player.isInLava(), player.isOnLadder());
			V2Hat<Object> wings = features.wingsV2(player.getUniqueID(), player == Mc.player(), false, state);
			if (wings != null) hats.renderPart(features, wings, player, renderer.getMainModel().bipedBody);
		} catch (RuntimeException e) {
			features.online().reportError(e);
		}
	}

	@Override
	public boolean shouldCombineTextures() {
		return false;
	}
}
