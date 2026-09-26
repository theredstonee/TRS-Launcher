package dev.theredstonee.trsclient.online;

// Nur ab 1.15 (PoseStack/ModelPart); 1.14 zeichnet Spieler noch mit festen GL-Aufrufen.
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.theredstonee.trsclient.core.cape.ClothMesh;
import dev.theredstonee.trsclient.core.cosmetic.Wearer;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * TRS-Kopf-Kosmetik (Quietscheente) im Spiel: Textur holen, Kopf-Teil des Spielermodells ansetzen, die Vierecke aus
 * {@code core.cosmetic} ausgeben. Aufgerufen aus {@code HatLayerMixin} (am Umhang-Layer, der für jeden Spieler läuft).
 * Die Logik (Modell, Rig, Mesh) ist versionsunabhängig; hier nur die Render-Schnittstelle je Version.
 */
public final class HatHooks {
	private HatHooks() {
	}

	/** Spieler zu einer Entity-ID der Render-States (null = keiner). */
	public static AbstractClientPlayer player(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		net.minecraft.world.entity.Entity e = mc.level.getEntity(entityId);
		return e instanceof AbstractClientPlayer ? (AbstractClientPlayer) e : null;
	}

	/** Zustand des Trägers (je Aufruf neu – ab 1.21.9 wird erst nach dem Einsammeln gezeichnet). */
	static Wearer wearer(Player p) {
		double dx = p.getX() - p.xo;
		double dz = p.getZ() - p.zo;
		//? if >=1.20 {
		/*boolean ground = p.onGround();
		*///?} elif >=1.16 {
		boolean ground = p.isOnGround();
		//?} else
		/*boolean ground = p.onGround;*/
		//? if >=1.17 {
		/*float pitch = p.getXRot();
		*///?} else
		float pitch = p.xRot;
		return new Wearer().set(p.getId(), (float) Math.sqrt(dx * dx + dz * dz), (float) (p.getY() - p.yo), ground,
				p.isCrouching(), p.isSprinting(), p.isInWater(), p.yHeadRot, pitch, false,
				!p.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
	}

	//? if >=1.21.11 {
	/*public static void render(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			int entityId, ModelPart head) {
		OnlineFeatures<Object> features = OnlineHooks.features();
		AbstractClientPlayer p = player(entityId);
		if (features == null || p == null || p.isInvisible()) return;
		final UUID id = p.getUUID();
		Object tex = features.hatTexture(id);
		if (tex == null) return;
		final Wearer w = wearer(p);
		pose.pushPose();
		try {
			head.translateAndRotate(pose);
			collector.submitCustomGeometry(pose, net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(
					(net.minecraft.resources.Identifier) tex), (last, vc) -> features.emitHat(id, w, sink(vc, last, light)));
		} catch (RuntimeException e) {
			features.online().reportError(e);
		} finally {
			pose.popPose();
		}
	}
	*///?} elif >=1.21.9 {
	/*public static void render(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			int entityId, ModelPart head) {
		OnlineFeatures<Object> features = OnlineHooks.features();
		AbstractClientPlayer p = player(entityId);
		if (features == null || p == null || p.isInvisible()) return;
		final UUID id = p.getUUID();
		Object tex = features.hatTexture(id);
		if (tex == null) return;
		final Wearer w = wearer(p);
		pose.pushPose();
		try {
			head.translateAndRotate(pose);
			collector.submitCustomGeometry(pose, net.minecraft.client.renderer.RenderType.entitySolid(
					(net.minecraft.resources.ResourceLocation) tex), (last, vc) -> features.emitHat(id, w, sink(vc, last, light)));
		} catch (RuntimeException e) {
			features.online().reportError(e);
		} finally {
			pose.popPose();
		}
	}
	*///?} else {
	/** Bis 1.21.8: sofort zeichnen. */
	public static void render(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
			AbstractClientPlayer p, ModelPart head) {
		OnlineFeatures<Object> features = OnlineHooks.features();
		if (features == null || p == null || p.isInvisible()) return;
		UUID id = p.getUUID();
		Object tex = features.hatTexture(id);
		if (tex == null) return;
		pose.pushPose();
		try {
			VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entitySolid(
					(net.minecraft.resources.ResourceLocation) tex));
			head.translateAndRotate(pose);
			features.emitHat(id, wearer(p), sink(vc, pose.last(), light));
		} catch (RuntimeException e) {
			features.online().reportError(e);
		} finally {
			pose.popPose();
		}
	}
	//?}

	private static ClothMesh.QuadSink sink(VertexConsumer vc, PoseStack.Pose last, int light) {
		int overlay = OverlayTexture.NO_OVERLAY;
		return (x, y, z, u, v, nx, ny, nz) -> {
			//? if >=1.21 {
			/*vc.addVertex(last, x, y, z).setColor(-1).setUv(u, v).setOverlay(overlay).setLight(light)
					.setNormal(last, nx, ny, nz);
			*///?} elif >=1.20.5 {
			/*vc.vertex(last, x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(overlay).uv2(light)
					.normal(last, nx, ny, nz).endVertex();
			*///?} else {
			vc.vertex(last.pose(), x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(overlay).uv2(light)
					.normal(last.normal(), nx, ny, nz).endVertex();
			//?}
		};
	}
}
//?}
