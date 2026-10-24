package dev.theredstonee.trsclient.online;

// Nur ab 1.15 (PoseStack/ModelPart) – wie HatHooks.
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.cosmetic.v2.WingState;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/**
 * TRS-Flügel im Spiel (Slot {@code wings}, Format 2, {@code attach: "body"}): am Körper-Teil des bereits posierten
 * Spielermodells (folgt Körper-Drehung, Schleich-Neigung, Schwimmen), Zustand idle/air/sneak mit Überblendung, unsichtbar
 * mit Elytra. Gezeichnet wird mit demselben v2-Weg wie die Kopf-Kosmetik ({@link HatHooks#drawV2}). Aufgerufen aus
 * {@code WingsLayerMixin} (am Umhang-Layer, der für jeden Spieler läuft).
 */
public final class WingsHooks {
	private WingsHooks() {
	}

	/** Flügel dieses Spielers für dieses Bild (Elytra, Zustand) oder null. */
	static V2Hat<Object> wings(OnlineFeatures<Object> f, AbstractClientPlayer p) {
		boolean elytra = p.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
		if (elytra) return null;
		return f.wingsV2(p.getUUID(), p == Minecraft.getInstance().player, false, state(p));
	}

	/**
	 * Zustand des Spielers für Zustands-Animationen ({@code WingState} IDLE/AIR/SNEAK) – Flügel, aber auch Kopf-Kosmetik
	 * und Begleiter am Kopf (z. B. Weihnachtsmütze im Sprung).
	 */
	static int state(AbstractClientPlayer p) {
		//? if >=1.17 {
		/*boolean flying = p.getAbilities().flying || p.isFallFlying();
		*///?} else
		boolean flying = p.abilities.flying || p.isFallFlying();
		//? if >=1.20 {
		/*boolean ground = p.onGround();
		*///?} elif >=1.16 {
		boolean ground = p.isOnGround();
		//?} else
		/*boolean ground = p.onGround;*/
		//? if >=1.16 {
		boolean climbing = p.onClimbable();
		//?} else
		/*boolean climbing = p.onLadder();*/
		return WingState.classify(ground, p.isCrouching(), flying, p.isPassenger(), p.isInWater() || p.isInLava(), climbing);
	}

	//? if >=1.21.9 {
	/*public static void render(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			int entityId, ModelPart body) {
		OnlineFeatures<Object> features = OnlineHooks.features();
		AbstractClientPlayer p = HatHooks.player(entityId);
		if (features == null || p == null || p.isInvisible()) return;
		try {
			V2Hat<Object> wings = wings(features, p);
			if (wings != null) HatHooks.drawV2(features, wings, pose, collector, light, body);
		} catch (RuntimeException e) {
			features.online().reportError(e);
		}
	}
	*///?} else {
	public static void render(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
			AbstractClientPlayer p, ModelPart body) {
		OnlineFeatures<Object> features = OnlineHooks.features();
		if (features == null || p == null || p.isInvisible()) return;
		try {
			V2Hat<Object> wings = wings(features, p);
			if (wings != null) HatHooks.drawV2(features, wings, pose, buffers, light, body);
		} catch (RuntimeException e) {
			features.online().reportError(e);
		}
	}
	//?}
}
//?}
