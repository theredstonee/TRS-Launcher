package dev.theredstonee.trsclient.compat;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
//? if >=1.9 {
/*import net.minecraft.util.math.AxisAlignedBB;
*///?} else
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import org.lwjgl.opengl.GL11;

/**
 * Eigener Rahmen um den anvisierten Block (Farbe und Stärke einstellbar). Das Vanilla-Ereignis
 * {@code DrawBlockHighlightEvent} wird abgebrochen und der Kasten selbst gezeichnet – mit
 * {@code GL11.GL_LINES} im Immediate-Mode, das sieht in 1.8.9 bis 1.12.2 gleich aus und
 * braucht keinen der drei Tessellator-Umbauten dieser Jahre.
 *
 * <p>Versionsunterschiede: die Ereignisfelder werden ab 1.9 zu Gettern, {@code MovingObjectPosition}
 * heißt ab 1.9 {@code RayTraceResult}, und die Auswahl-Box kommt ab 1.9 vom {@code IBlockState}
 * statt vom {@code Block} (dort vorher mit {@code setBlockBoundsBasedOnState}).
 */
public final class BlockOutline {
	/** Wie weit der Rahmen über den Block hinausragt (wie Vanilla). */
	private static final double EXPAND = 0.002;

	private BlockOutline() {
	}

	/**
	 * Zeichnet den Rahmen.
	 *
	 * @return true, wenn gezeichnet wurde (dann das Ereignis abbrechen)
	 */
	public static boolean draw(DrawBlockHighlightEvent event, int rgb, float alpha, float lineWidth) {
		//? if >=1.9 {
		/*if (event.getSubID() != 0) return false;
		net.minecraft.util.math.RayTraceResult target = event.getTarget();
		if (target == null || target.typeOfHit != net.minecraft.util.math.RayTraceResult.Type.BLOCK) return false;
		net.minecraft.util.math.BlockPos pos = target.getBlockPos();
		float partialTicks = event.getPartialTicks();
		EntityPlayer player = event.getPlayer();
		*///?} else {
		if (event.subID != 0) return false;
		net.minecraft.util.MovingObjectPosition target = event.target;
		if (target == null || target.typeOfHit != net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK) return false;
		net.minecraft.util.BlockPos pos = target.getBlockPos();
		float partialTicks = event.partialTicks;
		EntityPlayer player = event.player;
		//?}
		World world = Mc.world();
		if (world == null || player == null || pos == null || world.isAirBlock(pos)) return false;
		IBlockState state = world.getBlockState(pos);
		if (state == null) return false;

		AxisAlignedBB box;
		//? if >=1.9 {
		/*box = state.getSelectedBoundingBox(world, pos);
		*///?} else {
		state.getBlock().setBlockBoundsBasedOnState(world, pos);
		box = state.getBlock().getSelectedBoundingBox(world, pos);
		//?}
		if (box == null) return false;

		double camX = Mc.lerp(player.lastTickPosX, player.posX, partialTicks);
		double camY = Mc.lerp(player.lastTickPosY, player.posY, partialTicks);
		double camZ = Mc.lerp(player.lastTickPosZ, player.posZ, partialTicks);
		double x1 = box.minX - EXPAND - camX;
		double y1 = box.minY - EXPAND - camY;
		double z1 = box.minZ - EXPAND - camZ;
		double x2 = box.maxX + EXPAND - camX;
		double y2 = box.maxY + EXPAND - camY;
		double z2 = box.maxZ + EXPAND - camZ;

		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
		GlStateManager.color(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha);
		GL11.glLineWidth(Math.max(1.0F, lineWidth));
		GlStateManager.disableTexture2D();
		GlStateManager.depthMask(false);
		box(x1, y1, z1, x2, y2, z2);
		GlStateManager.depthMask(true);
		GlStateManager.enableTexture2D();
		GlStateManager.disableBlend();
		GlStateManager.color(1f, 1f, 1f, 1f);
		GL11.glLineWidth(1.0F);
		return true;
	}

	/** Die zwölf Kanten des Kastens. */
	private static void box(double x1, double y1, double z1, double x2, double y2, double z2) {
		GL11.glBegin(GL11.GL_LINES);
		edge(x1, y1, z1, x2, y1, z1);
		edge(x2, y1, z1, x2, y1, z2);
		edge(x2, y1, z2, x1, y1, z2);
		edge(x1, y1, z2, x1, y1, z1);
		edge(x1, y2, z1, x2, y2, z1);
		edge(x2, y2, z1, x2, y2, z2);
		edge(x2, y2, z2, x1, y2, z2);
		edge(x1, y2, z2, x1, y2, z1);
		edge(x1, y1, z1, x1, y2, z1);
		edge(x2, y1, z1, x2, y2, z1);
		edge(x2, y1, z2, x2, y2, z2);
		edge(x1, y1, z2, x1, y2, z2);
		GL11.glEnd();
	}

	private static void edge(double x1, double y1, double z1, double x2, double y2, double z2) {
		GL11.glVertex3d(x1, y1, z1);
		GL11.glVertex3d(x2, y2, z2);
	}
}
