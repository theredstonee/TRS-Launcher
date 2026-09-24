package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.core.cape.ClothMesh;
import dev.theredstonee.trsclient.core.cape.ClothSim;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
//? if >=1.12 {
/*import net.minecraft.client.renderer.BufferBuilder;
*///?} elif >=1.9 {
/*import net.minecraft.client.renderer.VertexBuffer;
*///?} else
import net.minecraft.client.renderer.WorldRenderer;

/**
 * Ersatz für {@link LayerCape} (Forge 1.8.9–1.12.2): zeichnet den simulierten Umhang als Stoff-Gitter mit der
 * Textur, die Vanilla nehmen würde. Gibt es keine Simulation (Modul aus, zu weit weg, Elytra …), zeichnet der
 * ursprüngliche {@link LayerCape} wie immer.
 */
public final class ClothCapeLayer implements LayerRenderer<AbstractClientPlayer> {
	private final RenderPlayer renderer;
	private final LayerCape vanilla;
	private final ClothMesh mesh = new ClothMesh();

	public ClothCapeLayer(RenderPlayer renderer, LayerCape vanilla) {
		this.renderer = renderer;
		this.vanilla = vanilla;
	}

	@Override
	public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
			float ageInTicks, float netHeadYaw, float headPitch, float scale) {
		ClothSim sim = LegacyOnline.sim(player.getEntityId());
		ResourceLocation texture = sim == null ? null : player.getLocationCape();
		if (sim == null || texture == null || !player.hasPlayerInfo() || player.isInvisible()
				|| !player.isWearing(EnumPlayerModelParts.CAPE) || LegacyOnline.elytra(player)) {
			vanilla.doRenderLayer(player, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale);
			return;
		}
		float partial = LegacyOnline.features().physics().partial();
		final float zOff = (LegacyOnline.chestArmor(player) ? 3.1f : 2f) / 16f;
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		renderer.bindTexture(texture);
		GlStateManager.pushMatrix();
		try {
			draw(player, sim, partial, zOff);
		} catch (RuntimeException e) {
			// Nie das Spiel abstürzen lassen – dann eben kein Umhang in diesem Bild.
			LegacyOnline.features().online().reportError(e);
		} finally {
			GlStateManager.popMatrix();
		}
	}

	private void draw(AbstractClientPlayer player, ClothSim sim, float partial, final float zOff) {
		// Schleichen: Vanilla senkt beim Zeichnen des Modells alles um 0,2 und neigt den Oberkörper.
		if (player.isSneaking()) GlStateManager.translate(0.0F, 0.2F, 0.0F);
		renderer.getMainModel().bipedBody.postRender(0.0625F);
		Tessellator tessellator = Tessellator.getInstance();
		//? if >=1.12 {
		/*final BufferBuilder buffer = tessellator.getBuffer();
		*///?} elif >=1.9 {
		/*final VertexBuffer buffer = tessellator.getBuffer();
		*///?} else
		final WorldRenderer buffer = tessellator.getWorldRenderer();
		buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
		try {
			mesh.emit(sim, partial, LegacyOnline.features().physics().blocky(), new ClothMesh.QuadSink() {
				@Override
				public void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
					buffer.pos(x, y, z + zOff).tex(u, v).normal(nx, ny, nz).endVertex();
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
