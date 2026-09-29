package dev.theredstonee.trsclient.mixin;

// TRS-Kopf-Kosmetik in 1.14: Spieler werden dort noch mit festen GL-Aufrufen gezeichnet – eigener Weg über den
// Tesselator, eingehängt am Umhang-Layer (läuft für jeden Spieler). Nur in der Mixin-Liste für 1.14.
//? if <1.15 {
/*import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import dev.theredstonee.trsclient.core.cosmetic.Wearer;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Math;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(CapeLayer.class)
public abstract class HatGlMixin {
	@Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFFFFFF)V", at = @At("HEAD"), require = 0)
	private void trsclient$hat(AbstractClientPlayer p, float limbSwing, float limbSwingAmount, float partial, float age,
			float headYaw, float headPitch, float scale, CallbackInfo ci) {
		OnlineFeatures<Object> features = OnlineHooks.features();
		if (features == null || p.isInvisible()) return;
		UUID id = p.getUUID();
		boolean helmet = !p.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
		@SuppressWarnings("unchecked")
		RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> self =
				(RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>) (Object) this;
		Object tex = features.hatTexture(id);
		if (tex == null) {
			V2Hat<Object> hat = features.hatV2(id, helmet);
			if (hat != null) renderV2(features, hat, p, self);
			return;
		}
		double dx = p.x - p.xo;
		double dz = p.z - p.zo;
		Wearer w = new Wearer().set(p.getId(), (float) Math.sqrt(dx * dx + dz * dz), (float) (p.y - p.yo), p.onGround,
				p.isSneaking(), p.isSprinting(), p.isInWater(), p.yHeadRot, p.xRot, false, helmet);
		GlStateManager.color4f(1f, 1f, 1f, 1f);
		self.bindTexture((ResourceLocation) tex);
		GlStateManager.pushMatrix();
		GlStateManager.disableCull();
		try {
			if (p.isSneaking()) GlStateManager.translatef(0f, 0.2f, 0f);
			self.getParentModel().head.translateTo(0.0625f);
			Tesselator tesselator = Tesselator.getInstance();
			BufferBuilder buffer = tesselator.getBuilder();
			buffer.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_NORMAL);
			try {
				features.emitHat(id, w, (x, y, z, u, v, nx, ny, nz) -> buffer.vertex(x, y, z).uv(u, v).normal(nx, ny, nz).endVertex());
			} finally {
				tesselator.end();
			}
		} catch (RuntimeException e) {
			features.online().reportError(e);
		} finally {
			GlStateManager.enableCull();
			GlStateManager.popMatrix();
		}
	}

	// Format 2 über GL: Grundmodell mit Alpha-Test (Welt-/Entity-Licht), emissive voll hell (Lightmap 240, ohne
	// GL-Licht), durchscheinend gemischt ohne Tiefe, Leucht-Schicht und Höfe additiv ONE/ONE voll hell – wie Vanillas
	// Spinnenaugen. (Keine Javadoc-Kommentare hier: der ganze Block ist außerhalb von 1.14 auskommentiert.)
	private static void renderV2(OnlineFeatures<Object> f, V2Hat<Object> hat, AbstractClientPlayer p,
			RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> self) {
		GlStateManager.pushMatrix();
		GlStateManager.disableCull();
		GlStateManager.color4f(1f, 1f, 1f, 1f);
		try {
			if (p.isSneaking()) GlStateManager.translatef(0f, 0.2f, 0f);
			self.getParentModel().head.translateTo(0.0625f);
			double[] eye = null;
			if (hat.has(CosmeticV2Renderer.PASS_HALO)) {
				java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(16);
				GlStateManager.getMatrix(GL11.GL_MODELVIEW_MATRIX, buf);
				float[] m = new float[16];
				buf.get(m);
				double[] out = new double[3];
				if (V2Math.eyeInAttachSpace(m, out)) eye = out;
			}
			GlStateManager.enableAlphaTest();
			GlStateManager.alphaFunc(GL11.GL_GREATER, 0.5f);
			self.bindTexture((ResourceLocation) hat.base);
			if (hat.has(CosmeticV2Renderer.PASS_CUTOUT)) draw(f, hat, CosmeticV2Renderer.PASS_CUTOUT, null);
			if (hat.has(CosmeticV2Renderer.PASS_EMISSIVE)) {
				GlStateManager.disableLighting();
				GLX.glMultiTexCoord2f(GLX.GL_TEXTURE1, 240f, 240f);
				draw(f, hat, CosmeticV2Renderer.PASS_EMISSIVE, null);
				lightmap(p);
				GlStateManager.enableLighting();
			}
			if (hat.has(CosmeticV2Renderer.PASS_TRANSLUCENT)) {
				GlStateManager.enableBlend();
				GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
				GlStateManager.alphaFunc(GL11.GL_GREATER, 0.004f);
				GlStateManager.depthMask(false);
				draw(f, hat, CosmeticV2Renderer.PASS_TRANSLUCENT, null);
				GlStateManager.depthMask(true);
				GlStateManager.disableBlend();
			}
			boolean glow = hat.has(CosmeticV2Renderer.PASS_GLOW);
			if (glow || eye != null) {
				GlStateManager.enableBlend();
				GlStateManager.disableAlphaTest();
				GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
				GlStateManager.depthMask(false);
				GlStateManager.disableLighting();
				GLX.glMultiTexCoord2f(GLX.GL_TEXTURE1, 240f, 240f);
				Minecraft.getInstance().gameRenderer.resetFogColor(true);
				GlStateManager.enablePolygonOffset();
				GlStateManager.polygonOffset(-1f, -2f);
				try {
					if (glow) {
						self.bindTexture((ResourceLocation) hat.glow);
						draw(f, hat, CosmeticV2Renderer.PASS_GLOW, null);
					}
					if (eye != null) {
						self.bindTexture((ResourceLocation) hat.halo);
						draw(f, hat, CosmeticV2Renderer.PASS_HALO, eye);
					}
				} finally {
					GlStateManager.polygonOffset(0f, 0f);
					GlStateManager.disablePolygonOffset();
					Minecraft.getInstance().gameRenderer.resetFogColor(false);
					lightmap(p);
					GlStateManager.enableLighting();
					GlStateManager.depthMask(true);
					GlStateManager.disableBlend();
					GlStateManager.enableAlphaTest();
				}
			}
		} catch (RuntimeException e) {
			f.online().reportError(e);
		} finally {
			GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
			GlStateManager.color4f(1f, 1f, 1f, 1f);
			GlStateManager.enableCull();
			GlStateManager.popMatrix();
		}
	}

	private static void lightmap(AbstractClientPlayer p) {
		int light = p.getLightColor();
		GLX.glMultiTexCoord2f(GLX.GL_TEXTURE1, (float) (light % 65536), (float) (light / 65536));
	}

	private static void draw(OnlineFeatures<Object> f, V2Hat<Object> hat, int pass, double[] eye) {
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.getBuilder();
		buffer.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
		try {
			f.emitV2(hat, pass, eye, (x, y, z, u, v, nx, ny, nz, argb) -> buffer.vertex(x, y, z).uv(u, v)
					.color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF).normal(nx, ny, nz).endVertex());
		} finally {
			tesselator.end();
		}
	}
}
*///?}
