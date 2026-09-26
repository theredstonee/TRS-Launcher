package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cape.ClothMesh;
import dev.theredstonee.trsclient.core.cosmetic.Wearer;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
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

import java.util.UUID;

/**
 * TRS-Kopf-Kosmetik (Quietscheente) für Forge 1.8.9–1.12.2: eigene Ebene der Spieler-Renderer, zeichnet die
 * Vierecke aus {@code core.cosmetic} in den Kopf des (schon posierten) Spielermodells – wie Vanilla-Köpfe/Helme.
 */
public final class HatLayer implements LayerRenderer<AbstractClientPlayer> {
	private final RenderPlayer renderer;

	public HatLayer(RenderPlayer renderer) {
		this.renderer = renderer;
	}

	@Override
	public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
			float ageInTicks, float netHeadYaw, float headPitch, float scale) {
		OnlineFeatures<Object> features = LegacyOnline.features();
		if (features == null || player.isInvisible()) return;
		UUID id = player.getUniqueID();
		Object tex = features.hatTexture(id);
		if (tex == null) return;
		double dx = player.posX - player.prevPosX;
		double dz = player.posZ - player.prevPosZ;
		Wearer w = new Wearer().set(player.getEntityId(), (float) Math.sqrt(dx * dx + dz * dz),
				(float) (player.posY - player.prevPosY), player.onGround, player.isSneaking(), player.isSprinting(),
				player.isInWater(), player.rotationYawHead, player.rotationPitch, false, Mc.equipment(player, 0) != null);
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
