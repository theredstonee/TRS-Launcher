package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cape.ClothMesh;
import dev.theredstonee.trsclient.core.cosmetic.Wearer;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Math;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
//? if >=1.12 {
/*import net.minecraft.client.renderer.BufferBuilder;
*///?} elif >=1.9 {
/*import net.minecraft.client.renderer.VertexBuffer;
*///?} else
import net.minecraft.client.renderer.WorldRenderer;

import java.nio.FloatBuffer;
import java.util.UUID;

/**
 * TRS-Kopf-Kosmetik für Forge 1.8.9–1.12.2: eigene Ebene der Spieler-Renderer, zeichnet die Vierecke aus
 * {@code core.cosmetic} in den Kopf des (schon posierten) Spielermodells – wie Vanilla-Köpfe/Helme.
 *
 * <p>Format 2 (Studio-Modelle) per GL: Grundmodell mit Alpha-Test (Alpha &lt; 0,5 unsichtbar) und Entity-Licht,
 * {@code emissive} voll hell (Lightmap 240/240, ohne GL-Licht), durchscheinende Flächen gemischt ohne Tiefe, Leucht-
 * Schicht und Höfe additiv {@code GL_ONE, GL_ONE} voll hell mit kleinem Polygon-Versatz – wie Vanillas Spinnenaugen.
 * Kopf-Kosmetik bleibt immer sichtbar: mit Helm sitzt v2 auf dem Helm (siehe {@code OnlineFeatures#hatV2}).
 */
public final class HatLayer implements LayerRenderer<AbstractClientPlayer> {
	private final RenderPlayer renderer;
	private final FloatBuffer matrix = GLAllocation.createDirectFloatBuffer(16);

	public HatLayer(RenderPlayer renderer) {
		this.renderer = renderer;
	}

	@Override
	public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
			float ageInTicks, float netHeadYaw, float headPitch, float scale) {
		OnlineFeatures<Object> features = LegacyOnline.features();
		if (features == null || player.isInvisible()) return;
		UUID id = player.getUniqueID();
		boolean helmet = Mc.equipment(player, 0) != null;
		Object tex = features.hatTexture(id);
		if (tex == null) {
			V2Hat<Object> hat = features.hatV2(id, helmet);
			if (hat != null) renderV2(features, hat, player);
			return;
		}
		double dx = player.posX - player.prevPosX;
		double dz = player.posZ - player.prevPosZ;
		Wearer w = new Wearer().set(player.getEntityId(), (float) Math.sqrt(dx * dx + dz * dz),
				(float) (player.posY - player.prevPosY), player.onGround, player.isSneaking(), player.isSprinting(),
				player.isInWater(), player.rotationYawHead, player.rotationPitch, false, helmet);
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		renderer.bindTexture((ResourceLocation) tex);
		GlStateManager.pushMatrix();
		GlStateManager.disableCull();
		try {
			// Schleichen: Vanilla senkt beim Zeichnen des Modells alles um 0,2 (wie beim Helm-Kopf).
			if (player.isSneaking()) GlStateManager.translate(0.0F, 0.2F, 0.0F);
			renderer.getMainModel().bipedHead.postRender(0.0625F);
			draw(features, id, w);
		} catch (RuntimeException e) {
			// Nie das Spiel abstürzen lassen – dann eben keine Ente in diesem Bild.
			features.online().reportError(e);
		} finally {
			GlStateManager.enableCull();
			GlStateManager.popMatrix();
		}
	}

	private void renderV2(OnlineFeatures<Object> f, V2Hat<Object> hat, AbstractClientPlayer player) {
		float lastX = OpenGlHelper.lastBrightnessX;
		float lastY = OpenGlHelper.lastBrightnessY;
		GlStateManager.pushMatrix();
		GlStateManager.disableCull();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		try {
			if (player.isSneaking()) GlStateManager.translate(0.0F, 0.2F, 0.0F);
			renderer.getMainModel().bipedHead.postRender(0.0625F);
			// Kamera im Kopf-Raum: für die Höfe und die Entfernungs-Stufe der HD-Texturen (gegen Flimmern)
			double[] cam = null;
			{
				matrix.clear();
				GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrix);
				float[] m = new float[16];
				matrix.get(m);
				double[] out = new double[3];
				if (V2Math.eyeInAttachSpace(m, out)) cam = out;
			}
			double[] eye = hat.has(CosmeticV2Renderer.PASS_HALO) ? cam : null;
			int lod = hat.level(cam, dev.theredstonee.trsclient.TrsClient.get().worldFov(), net.minecraft.client.Minecraft.getMinecraft().displayHeight);
			GlStateManager.enableAlpha();
			GlStateManager.alphaFunc(GL11.GL_GREATER, 0.5F);
			renderer.bindTexture((ResourceLocation) hat.base(lod));
			if (hat.has(CosmeticV2Renderer.PASS_CUTOUT)) drawV2(f, hat, CosmeticV2Renderer.PASS_CUTOUT, null);
			if (hat.has(CosmeticV2Renderer.PASS_EMISSIVE)) {
				GlStateManager.disableLighting();
				OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
				drawV2(f, hat, CosmeticV2Renderer.PASS_EMISSIVE, null);
				OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastX, lastY);
				GlStateManager.enableLighting();
			}
			if (hat.has(CosmeticV2Renderer.PASS_TRANSLUCENT)) {
				GlStateManager.enableBlend();
				GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
				GlStateManager.alphaFunc(GL11.GL_GREATER, 0.004F);
				GlStateManager.depthMask(false);
				drawV2(f, hat, CosmeticV2Renderer.PASS_TRANSLUCENT, null);
				GlStateManager.depthMask(true);
				GlStateManager.disableBlend();
			}
			boolean glow = hat.has(CosmeticV2Renderer.PASS_GLOW);
			if (glow || eye != null) {
				GlStateManager.enableBlend();
				GlStateManager.disableAlpha();
				GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
				GlStateManager.depthMask(false);
				GlStateManager.disableLighting();
				OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
				GlStateManager.enablePolygonOffset();
				GlStateManager.doPolygonOffset(-1.0F, -2.0F);
				try {
					if (glow) {
						renderer.bindTexture((ResourceLocation) hat.glow(lod));
						drawV2(f, hat, CosmeticV2Renderer.PASS_GLOW, null);
					}
					if (eye != null) {
						renderer.bindTexture((ResourceLocation) hat.halo);
						drawV2(f, hat, CosmeticV2Renderer.PASS_HALO, eye);
					}
				} finally {
					GlStateManager.doPolygonOffset(0.0F, 0.0F);
					GlStateManager.disablePolygonOffset();
					OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastX, lastY);
					GlStateManager.enableLighting();
					GlStateManager.depthMask(true);
					GlStateManager.disableBlend();
					GlStateManager.enableAlpha();
				}
			}
		} catch (RuntimeException e) {
			f.online().reportError(e);
		} finally {
			GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
			GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
			GlStateManager.enableCull();
			GlStateManager.popMatrix();
		}
	}

	private static void drawV2(OnlineFeatures<Object> f, V2Hat<Object> hat, int pass, double[] eye) {
		Tessellator tessellator = Tessellator.getInstance();
		//? if >=1.12 {
		/*final BufferBuilder buffer = tessellator.getBuffer();
		*///?} elif >=1.9 {
		/*final VertexBuffer buffer = tessellator.getBuffer();
		*///?} else
		final WorldRenderer buffer = tessellator.getWorldRenderer();
		buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
		try {
			f.emitV2(hat, pass, eye, new CosmeticV2Renderer.VertexSink() {
				@Override
				public void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int argb) {
					buffer.pos(x, y, z).tex(u, v).color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
							.normal(nx, ny, nz).endVertex();
				}
			});
		} finally {
			// Immer abschließen – ein offener Puffer ließe das nächste begin() abstürzen.
			tessellator.draw();
		}
	}

	private static void draw(OnlineFeatures<Object> features, UUID id, Wearer w) {
		Tessellator tessellator = Tessellator.getInstance();
		//? if >=1.12 {
		/*final BufferBuilder buffer = tessellator.getBuffer();
		*///?} elif >=1.9 {
		/*final VertexBuffer buffer = tessellator.getBuffer();
		*///?} else
		final WorldRenderer buffer = tessellator.getWorldRenderer();
		buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
		try {
			features.emitHat(id, w, new ClothMesh.QuadSink() {
				@Override
				public void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
					buffer.pos(x, y, z).tex(u, v).normal(nx, ny, nz).endVertex();
				}
			});
		} finally {
			// Immer abschließen – ein offener Puffer ließe das nächste begin() abstürzen.
			tessellator.draw();
		}
	}

	@Override
	public boolean shouldCombineTextures() {
		return false;
	}
}
