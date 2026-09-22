package dev.theredstonee.trsclient.feature;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.world.World;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;

/**
 * Eigener Rahmen um den anvisierten Block (Farbe und Stärke einstellbar). Das Vanilla-Ereignis
 * {@code DrawBlockHighlightEvent} wird abgebrochen und der Kasten selbst gezeichnet.
 *
 * <p>1.13.2 zeichnet die Auswahl nicht mehr als {@code AxisAlignedBB}, sondern als {@code VoxelShape};
 * dafür gibt es die statische Hilfe {@code WorldRenderer.drawShape(...)} (das ist genau das, was
 * {@code WorldRenderer.drawSelectionBox} intern benutzt – Tessellator/BufferBuilder mit GL_LINES).
 * Damit braucht es hier keinen Immediate-Mode ({@code GL11.glBegin}) wie in der 1.8.9-Fassung.
 */
public final class BlockOutline {
	private BlockOutline() {
	}

	/**
	 * Zeichnet den Rahmen.
	 *
	 * @return true, wenn gezeichnet wurde (dann das Ereignis abbrechen)
	 */
	public static boolean draw(DrawBlockHighlightEvent event, int rgb, float alpha, float lineWidth) {
		if (event.getSubID() != 0) return false;
		RayTraceResult target = event.getTarget();
		if (target == null || target.type != RayTraceResult.Type.BLOCK) return false;
		BlockPos pos = target.getBlockPos();
		EntityPlayer player = event.getPlayer();
		World world = Minecraft.getInstance().world;
		if (world == null || player == null || pos == null || world.isAirBlock(pos)) return false;
		IBlockState state = world.getBlockState(pos);
		if (state == null) return false;
		VoxelShape shape = state.getShape(world, pos);
		if (shape == null || shape.isEmpty()) return false;

		float partialTicks = event.getPartialTicks();
		double camX = lerp(player.lastTickPosX, player.posX, partialTicks);
		double camY = lerp(player.lastTickPosY, player.posY, partialTicks);
		double camZ = lerp(player.lastTickPosZ, player.posZ, partialTicks);

		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(770, 771, 1, 0);
		GlStateManager.lineWidth(Math.max(1.0F, lineWidth));
		GlStateManager.disableTexture2D();
		GlStateManager.depthMask(false);
		WorldRenderer.drawShape(shape, pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ,
				((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha);
		GlStateManager.depthMask(true);
		GlStateManager.enableTexture2D();
		GlStateManager.disableBlend();
		GlStateManager.lineWidth(1.0F);
		return true;
	}

	/** Zwischenwert einer Position für das aktuelle Teilbild. */
	private static double lerp(double last, double now, float partialTicks) {
		return last + (now - last) * partialTicks;
	}
}
