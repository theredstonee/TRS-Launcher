package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cosmetic.pet.PetDraw;
import dev.theredstonee.trsclient.core.cosmetic.pet.PetWorld;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Hat;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Math;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;
//? if >=1.9 {
/*import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
*///?} else {
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
//?}

import java.nio.FloatBuffer;
import java.util.ArrayList;

/**
 * Begleiter am Boden (Winter 2026: Pinguine) für Forge 1.8.9–1.12.2: einmal je Client-Tick die Spieler an die
 * Simulation melden ({@code core.cosmetic.pet.PetSim}), einmal je Bild in {@code RenderWorldLastEvent} alle sichtbaren
 * Begleiter per GL zeichnen – mit Licht am Standort (Lightmap), Entity-Licht, Entfernungs-Grenze und Obergrenze aus
 * {@code OnlineFeatures#petFrame}. Die Ecken rechnet {@link PetDraw#wrap} in die Welt um (Ursprung = Render-Position des
 * Kamera-Wesens), die Kamera kommt aus der GL-Matrix. Auch der eigene Begleiter ist sichtbar (auch in der Ich-Perspektive).
 */
public final class LegacyPets {
	private static final Blocks BLOCKS = new Blocks();
	private final FloatBuffer matrix = GLAllocation.createDirectFloatBuffer(16);
	private final float[] m = new float[16];
	private final double[] cam = new double[3];

	/** Einmal je Client-Tick (aus {@code LegacyOnline.tick}). */
	static void tick(OnlineFeatures<Object> f) {
		World world = Mc.world();
		EntityPlayer self = Mc.player();
		if (!f.petsBegin(self == null ? null : world)) return;
		BLOCKS.world = world;
		for (EntityPlayer p : new ArrayList<EntityPlayer>(world.playerEntities)) {
			boolean hidden = p.isInvisible() || p.isSpectator();
			f.petOwner(p.getUniqueID(), p == self, p.posX, p.posY, p.posZ, p.renderYawOffset, hidden, BLOCKS);
		}
		f.petsEnd();
	}

	@SubscribeEvent
	public void onRenderLast(RenderWorldLastEvent event) {
		OnlineFeatures<Object> f = LegacyOnline.features();
		if (f == null || Mc.world() == null) return;
		try {
			render(f);
		} catch (RuntimeException e) {
			f.online().reportError(e);
		}
	}

	private void render(OnlineFeatures<Object> f) {
		// Kamera aus der GL-Matrix: Ursprung = Render-Position (Füße des Kamera-Wesens), die Kamera sitzt in Augenhöhe
		// bzw. in der 3. Person dahinter.
		matrix.clear();
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrix);
		matrix.get(m);
		if (!V2Math.eyeInAttachSpace(m, cam)) return;
		// eyeInAttachSpace liefert Pixel im Anhängepunkt-Raum (×16, y/z gespiegelt) – zurück in Blöcke der Welt
		cam[0] = cam[0] / 16.0;
		cam[1] = -cam[1] / 16.0;
		cam[2] = -cam[2] / 16.0;
		double fx = -m[2], fy = -m[6], fz = -m[10];
		double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
		if (fl < 1e-6) return;
		float yaw = (float) Math.toDegrees(Math.atan2(-fx, fz));
		float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -fy / fl))));
		double rx = TileEntityRendererDispatcher.staticPlayerX;
		double ry = TileEntityRendererDispatcher.staticPlayerY;
		double rz = TileEntityRendererDispatcher.staticPlayerZ;
		BLOCKS.world = Mc.world();
		int n = f.petFrame(BLOCKS, rx + cam[0], ry + cam[1], rz + cam[2], yaw, pitch);
		if (n == 0) return;
		Minecraft mc = Minecraft.getMinecraft();
		float lastX = OpenGlHelper.lastBrightnessX;
		float lastY = OpenGlHelper.lastBrightnessY;
		mc.entityRenderer.enableLightmap();
		RenderHelper.enableStandardItemLighting();
		GlStateManager.pushMatrix();
		GlStateManager.disableCull();
		GlStateManager.enableDepth();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		try {
			for (int i = 0; i < n; i++) {
				PetDraw<Object> d = f.petDraw(i);
				d.shift(cam[0], cam[1], cam[2]);
				draw(mc, f, d);
			}
		} finally {
			GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
			GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
			GlStateManager.enableCull();
			GlStateManager.popMatrix();
			RenderHelper.disableStandardItemLighting();
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastX, lastY);
			mc.entityRenderer.disableLightmap();
		}
	}

	/** Ein Begleiter: Grund (Weltlicht am Standort), emissive voll hell, durchscheinend, Leucht-Schicht + Höfe additiv. */
	private static void draw(Minecraft mc, OnlineFeatures<Object> f, final PetDraw<Object> d) {
		V2Hat<Object> hat = d.hat;
		float lx = d.light & 0xFFFF;
		float ly = (d.light >>> 16) & 0xFFFF;
		int lod = hat.level(d.eye, dev.theredstonee.trsclient.TrsClient.get().worldFov(), mc.displayHeight);
		java.util.function.UnaryOperator<CosmeticV2Renderer.VertexSink> wrap = d::wrap;
		OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lx, ly);
		GlStateManager.enableAlpha();
		GlStateManager.alphaFunc(GL11.GL_GREATER, 0.5F);
		mc.getTextureManager().bindTexture((ResourceLocation) hat.base(lod));
		if (hat.has(CosmeticV2Renderer.PASS_CUTOUT)) HatLayer.drawV2(f, hat, CosmeticV2Renderer.PASS_CUTOUT, null, wrap);
		if (hat.has(CosmeticV2Renderer.PASS_EMISSIVE)) {
			GlStateManager.disableLighting();
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
			HatLayer.drawV2(f, hat, CosmeticV2Renderer.PASS_EMISSIVE, null, wrap);
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lx, ly);
			GlStateManager.enableLighting();
		}
		if (hat.has(CosmeticV2Renderer.PASS_TRANSLUCENT)) {
			GlStateManager.enableBlend();
			GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			GlStateManager.alphaFunc(GL11.GL_GREATER, 0.004F);
			GlStateManager.depthMask(false);
			HatLayer.drawV2(f, hat, CosmeticV2Renderer.PASS_TRANSLUCENT, null, wrap);
			GlStateManager.depthMask(true);
			GlStateManager.disableBlend();
		}
		boolean glow = hat.has(CosmeticV2Renderer.PASS_GLOW);
		boolean halos = hat.has(CosmeticV2Renderer.PASS_HALO);
		if (glow || halos) {
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
					mc.getTextureManager().bindTexture((ResourceLocation) hat.glow(lod));
					HatLayer.drawV2(f, hat, CosmeticV2Renderer.PASS_GLOW, null, wrap);
				}
				if (halos) {
					mc.getTextureManager().bindTexture((ResourceLocation) hat.halo);
					HatLayer.drawV2(f, hat, CosmeticV2Renderer.PASS_HALO, d.eye, wrap);
				}
			} finally {
				GlStateManager.doPolygonOffset(0.0F, 0.0F);
				GlStateManager.disablePolygonOffset();
				GlStateManager.enableLighting();
				GlStateManager.depthMask(true);
				GlStateManager.disableBlend();
				GlStateManager.enableAlpha();
			}
		}
	}

	/** Block-Abfragen der Simulation ({@link PetWorld}) über Material und Kollisions-Box. */
	static final class Blocks implements PetWorld {
		World world;

		@Override
		public int block(int x, int y, int z) {
			World w = world;
			if (w == null) return UNLOADED;
			BlockPos pos = new BlockPos(x, y, z);
			if (!w.isBlockLoaded(pos)) return UNLOADED;
			IBlockState s = w.getBlockState(pos);
			if (s == null) return 0;
			Block b = s.getBlock();
			int flags = 0;
			AxisAlignedBB box;
			//? if >=1.9 {
			/*Material mat = s.getMaterial();
			if (mat == Material.AIR) return 0;
			if (mat == Material.WATER) flags |= WATER;
			if (mat == Material.LAVA || mat == Material.FIRE || mat == Material.CACTUS) flags |= DANGER;
			if (mat == Material.ICE || mat == Material.PACKED_ICE || mat == Material.SNOW || mat == Material.CRAFTED_SNOW) flags |= SLIPPERY;
			box = s.getCollisionBoundingBox(w, pos);
			double top = box == null ? -1 : box.maxY;
			*///?} else {
			Material mat = b.getMaterial();
			if (mat == Material.air) return 0;
			if (mat == Material.water) flags |= WATER;
			if (mat == Material.lava || mat == Material.fire || mat == Material.cactus) flags |= DANGER;
			if (mat == Material.ice || mat == Material.packedIce || mat == Material.snow || mat == Material.craftedSnow) flags |= SLIPPERY;
			b.setBlockBoundsBasedOnState(w, pos);
			box = b.getCollisionBoundingBox(w, pos, s);
			double top = box == null ? -1 : box.maxY - y;
			//?}
			if (box == null || top < 0) return flags;
			return PetWorld.code(flags | SOLID, top);
		}

		@Override
		public int light(int x, int y, int z) {
			World w = world;
			if (w == null) return PetWorld.packLight(15, 15);
			return w.getCombinedLight(new BlockPos(x, y, z), 0);
		}
	}
}
