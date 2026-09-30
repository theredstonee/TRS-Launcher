package dev.theredstonee.trsclient.online;

// Nur ab 1.15 (PoseStack/ModelPart); 1.14 zeichnet Spieler noch mit festen GL-Aufrufen.
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.theredstonee.trsclient.core.cape.ClothMesh;
import dev.theredstonee.trsclient.core.cosmetic.Wearer;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Math;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * TRS-Kopf-Kosmetik im Spiel: Textur holen, Kopf-Teil des Spielermodells ansetzen, die Vierecke aus
 * {@code core.cosmetic} ausgeben. Aufgerufen aus {@code HatLayerMixin} (am Umhang-Layer, der für jeden Spieler läuft).
 * Die Logik (Modell, Rig, Mesh, v2-Renderer) ist versionsunabhängig; hier nur die Render-Schnittstelle je Version.
 *
 * <p>Format 1 (Quietscheente): ein Durchgang mit {@code entitySolid}. Format 2 (Studio-Modelle): Grundmodell
 * {@code entityCutoutNoCull} (26.x: {@code entityCutout}, dort ohne Culling) mit Weltlicht bzw. voll hell für
 * {@code emissive}, {@code entityTranslucent} für durchscheinende Flächen, die Leucht-Schicht und die Höfe additiv und
 * voll hell über {@code eyes} (bis 1.21.4) bzw. {@code energySwirl} (ab 1.21.5 – dort mischt {@code eyes} nicht mehr
 * additiv). Kopf-Kosmetik bleibt immer sichtbar: mit Helm sitzt v2 auf dem Helm (siehe {@code OnlineFeatures#hatV2}).
 */
public final class HatHooks {
	/** Volle Helligkeit (Himmels- und Blocklicht 15), wie {@code LightTexture.FULL_BRIGHT}. */
	private static final int FULL_BRIGHT = 0xF000F0;

	private HatHooks() {
	}

	/** Spieler zu einer Entity-ID der Render-States (null = keiner). */
	public static AbstractClientPlayer player(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		net.minecraft.world.entity.Entity e = mc.level.getEntity(entityId);
		return e instanceof AbstractClientPlayer ? (AbstractClientPlayer) e : null;
	}

	/** Trägt der Spieler etwas auf dem Kopf (Helm, Kürbis, Kopf)? */
	static boolean helmet(Player p) {
		return !p.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
	}

	/** Zustand des Trägers (je Aufruf neu – ab 1.21.9 wird erst nach dem Einsammeln gezeichnet). */
	static Wearer wearer(Player p) {
		double dx = p.getX() - p.xo;
		double dz = p.getZ() - p.zo;
		//? if >=1.20 {
		boolean ground = p.onGround();
		//?} elif >=1.16 {
		/*boolean ground = p.isOnGround();
		*///?} else
		/*boolean ground = p.onGround;*/
		//? if >=1.17 {
		float pitch = p.getXRot();
		//?} else
		/*float pitch = p.xRot;*/
		return new Wearer().set(p.getId(), (float) Math.sqrt(dx * dx + dz * dz), (float) (p.getY() - p.yo), ground,
				p.isCrouching(), p.isSprinting(), p.isInWater(), p.yHeadRot, pitch, false, helmet(p));
	}

	//? if >=1.21.11 {
	/*public static void render(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			int entityId, ModelPart head) {
		OnlineFeatures<Object> features = OnlineHooks.features();
		AbstractClientPlayer p = player(entityId);
		if (features == null || p == null || p.isInvisible()) return;
		final UUID id = p.getUUID();
		Object tex = features.hatTexture(id);
		if (tex == null) {
			renderV2(features, p, pose, collector, light, head);
			return;
		}
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
		if (tex == null) {
			renderV2(features, p, pose, collector, light, head);
			return;
		}
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
	// Bis 1.21.8: sofort zeichnen.
	public static void render(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
			AbstractClientPlayer p, ModelPart head) {
		OnlineFeatures<Object> features = OnlineHooks.features();
		if (features == null || p == null || p.isInvisible()) return;
		UUID id = p.getUUID();
		Object tex = features.hatTexture(id);
		if (tex == null) {
			renderV2(features, p, pose, buffers, light, head);
			return;
		}
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

	// --- Format 2 ---

	//? if >=1.21.9 {
	/*private static void renderV2(final OnlineFeatures<Object> f, AbstractClientPlayer p, PoseStack pose,
			net.minecraft.client.renderer.SubmitNodeCollector collector, final int light, ModelPart head) {
		final V2Hat<Object> hat = f.hatV2(p.getUUID(), helmet(p));
		if (hat == null) return;
		pose.pushPose();
		try {
			head.translateAndRotate(pose);
			// Kamera im Kopf-Raum: für die Höfe und die Entfernungs-Stufe der HD-Texturen (gegen Flimmern)
			final double[] cam = eye(pose.last());
			final int lod = hat.level(cam, fov(), screenHeight());
			final double[] eye = hat.has(CosmeticV2Renderer.PASS_HALO) ? cam : null;
			if (hat.has(CosmeticV2Renderer.PASS_CUTOUT) || hat.has(CosmeticV2Renderer.PASS_EMISSIVE)) {
				collector.submitCustomGeometry(pose, cutout(hat.base(lod)), (last, vc) -> {
					f.emitV2(hat, CosmeticV2Renderer.PASS_CUTOUT, null, v2sink(vc, last, light));
					f.emitV2(hat, CosmeticV2Renderer.PASS_EMISSIVE, null, v2sink(vc, last, FULL_BRIGHT));
				});
			}
			if (hat.has(CosmeticV2Renderer.PASS_TRANSLUCENT)) {
				collector.submitCustomGeometry(pose, translucent(hat.base(lod)),
						(last, vc) -> f.emitV2(hat, CosmeticV2Renderer.PASS_TRANSLUCENT, null, v2sink(vc, last, light)));
			}
			if (hat.has(CosmeticV2Renderer.PASS_GLOW)) {
				collector.submitCustomGeometry(pose, additive(hat.glow(lod)),
						(last, vc) -> f.emitV2(hat, CosmeticV2Renderer.PASS_GLOW, null, additiveSink(vc, last)));
			}
			if (eye != null) {
				collector.submitCustomGeometry(pose, additive(hat.halo),
						(last, vc) -> f.emitV2(hat, CosmeticV2Renderer.PASS_HALO, eye, additiveSink(vc, last)));
			}
		} catch (RuntimeException e) {
			f.online().reportError(e);
		} finally {
			pose.popPose();
		}
	}
	*///?} else {
	private static void renderV2(OnlineFeatures<Object> f, AbstractClientPlayer p, PoseStack pose,
			net.minecraft.client.renderer.MultiBufferSource buffers, int light, ModelPart head) {
		V2Hat<Object> hat = f.hatV2(p.getUUID(), helmet(p));
		if (hat == null) return;
		pose.pushPose();
		try {
			head.translateAndRotate(pose);
			PoseStack.Pose last = pose.last();
			// Kamera im Kopf-Raum: für die Höfe und die Entfernungs-Stufe der HD-Texturen (gegen Flimmern)
			double[] eye = eye(last);
			int lod = hat.level(eye, fov(), screenHeight());
			if (hat.has(CosmeticV2Renderer.PASS_CUTOUT) || hat.has(CosmeticV2Renderer.PASS_EMISSIVE)) {
				VertexConsumer vc = buffers.getBuffer(cutout(hat.base(lod)));
				f.emitV2(hat, CosmeticV2Renderer.PASS_CUTOUT, null, v2sink(vc, last, light));
				f.emitV2(hat, CosmeticV2Renderer.PASS_EMISSIVE, null, v2sink(vc, last, FULL_BRIGHT));
			}
			if (hat.has(CosmeticV2Renderer.PASS_TRANSLUCENT)) {
				f.emitV2(hat, CosmeticV2Renderer.PASS_TRANSLUCENT, null, v2sink(buffers.getBuffer(translucent(hat.base(lod))), last, light));
			}
			if (hat.has(CosmeticV2Renderer.PASS_GLOW)) {
				f.emitV2(hat, CosmeticV2Renderer.PASS_GLOW, null, additiveSink(buffers.getBuffer(additive(hat.glow(lod))), last));
			}
			if (hat.has(CosmeticV2Renderer.PASS_HALO)) {
				if (eye != null) {
					f.emitV2(hat, CosmeticV2Renderer.PASS_HALO, eye, additiveSink(buffers.getBuffer(additive(hat.halo)), last));
				}
			}
		} catch (RuntimeException e) {
			f.online().reportError(e);
		} finally {
			pose.popPose();
		}
	}
	//?}

	// Render-Typen: Grundmodell ohne Rückseiten-Culling mit Alpha-Test, durchscheinend.
	//? if >=26.1 {
	/*private static net.minecraft.client.renderer.rendertype.RenderType cutout(Object tex) {
		return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout((net.minecraft.resources.Identifier) tex);
	}

	private static net.minecraft.client.renderer.rendertype.RenderType translucent(Object tex) {
		return net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent((net.minecraft.resources.Identifier) tex);
	}

	*///?} elif >=1.21.11 {
	/*private static net.minecraft.client.renderer.rendertype.RenderType cutout(Object tex) {
		return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull((net.minecraft.resources.Identifier) tex);
	}

	private static net.minecraft.client.renderer.rendertype.RenderType translucent(Object tex) {
		return net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent((net.minecraft.resources.Identifier) tex);
	}

	*///?} else {
	private static net.minecraft.client.renderer.RenderType cutout(Object tex) {
		return net.minecraft.client.renderer.RenderType.entityCutoutNoCull((net.minecraft.resources.ResourceLocation) tex);
	}

	private static net.minecraft.client.renderer.RenderType translucent(Object tex) {
		return net.minecraft.client.renderer.RenderType.entityTranslucent((net.minecraft.resources.ResourceLocation) tex);
	}

	//?}

	// Additiv und voll hell: bis 1.21.4 „eyes“ (ONE/ONE, mit Rückseiten-Culling → beidseitig ausgeben); ab 1.21.5 mischt
	// „eyes“ normal (TRANSLUCENT) – dort „energy_swirl“ (ADDITIVE, EMISSIVE, ohne Culling, Textur-Versatz 0).
	//? if >=1.21.11 {
	/*private static net.minecraft.client.renderer.rendertype.RenderType additive(Object tex) {
		return net.minecraft.client.renderer.rendertype.RenderTypes.energySwirl((net.minecraft.resources.Identifier) tex, 0f, 0f);
	}

	private static CosmeticV2Renderer.VertexSink additiveSink(VertexConsumer vc, PoseStack.Pose last) {
		return v2sink(vc, last, FULL_BRIGHT);
	}
	*///?} elif >=1.21.5 {
	/*private static net.minecraft.client.renderer.RenderType additive(Object tex) {
		return net.minecraft.client.renderer.RenderType.energySwirl((net.minecraft.resources.ResourceLocation) tex, 0f, 0f);
	}

	private static CosmeticV2Renderer.VertexSink additiveSink(VertexConsumer vc, PoseStack.Pose last) {
		return v2sink(vc, last, FULL_BRIGHT);
	}
	*///?} else {
	private static net.minecraft.client.renderer.RenderType additive(Object tex) {
		return net.minecraft.client.renderer.RenderType.eyes((net.minecraft.resources.ResourceLocation) tex);
	}

	private static CosmeticV2Renderer.VertexSink additiveSink(VertexConsumer vc, PoseStack.Pose last) {
		return CosmeticV2Renderer.bothSides(v2sink(vc, last, FULL_BRIGHT));
	}
	//?}

	/** Senkrechtes Sichtfeld der Welt (mit Zoom) – für die Entfernungs-Stufe der HD-Texturen. */
	private static double fov() {
		return dev.theredstonee.trsclient.TrsClient.get().worldFov();
	}

	/** Bildhöhe in Pixeln – für die Entfernungs-Stufe der HD-Texturen. */
	private static int screenHeight() {
		return dev.theredstonee.trsclient.compat.Mc.window().getHeight();
	}

	/** Kamera im Anhängepunkt-Raum des Kopfes (die Pose bildet auf kamerazentrierte Koordinaten ab). */
	private static double[] eye(PoseStack.Pose last) {
		float[] m = new float[16];
		//? if >=1.19.3 {
		last.pose().get(m);
		//?} else {
		/*java.nio.FloatBuffer buf = java.nio.FloatBuffer.allocate(16);
		last.pose().store(buf);
		buf.get(m);
		*///?}
		double[] out = new double[3];
		return V2Math.eyeInAttachSpace(m, out) ? out : null;
	}

	private static CosmeticV2Renderer.VertexSink v2sink(final VertexConsumer vc, final PoseStack.Pose last, final int light) {
		final int overlay = OverlayTexture.NO_OVERLAY;
		return (x, y, z, u, v, nx, ny, nz, argb) -> {
			//? if >=1.21 {
			vc.addVertex(last, x, y, z).setColor(argb).setUv(u, v).setOverlay(overlay).setLight(light)
					.setNormal(last, nx, ny, nz);
			//?} elif >=1.20.5 {
			/*vc.vertex(last, x, y, z).color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
					.uv(u, v).overlayCoords(overlay).uv2(light).normal(last, nx, ny, nz).endVertex();
			*///?} else {
			/*vc.vertex(last.pose(), x, y, z).color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
					.uv(u, v).overlayCoords(overlay).uv2(light).normal(last.normal(), nx, ny, nz).endVertex();
			*///?}
		};
	}

	private static ClothMesh.QuadSink sink(VertexConsumer vc, PoseStack.Pose last, int light) {
		int overlay = OverlayTexture.NO_OVERLAY;
		return (x, y, z, u, v, nx, ny, nz) -> {
			//? if >=1.21 {
			vc.addVertex(last, x, y, z).setColor(-1).setUv(u, v).setOverlay(overlay).setLight(light)
					.setNormal(last, nx, ny, nz);
			//?} elif >=1.20.5 {
			/*vc.vertex(last, x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(overlay).uv2(light)
					.normal(last, nx, ny, nz).endVertex();
			*///?} else {
			/*vc.vertex(last.pose(), x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(overlay).uv2(light)
					.normal(last.normal(), nx, ny, nz).endVertex();
			*///?}
		};
	}
}
//?}
