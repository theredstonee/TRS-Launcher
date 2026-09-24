package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.compat.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import org.lwjgl.opengl.GL11;

/**
 * Live-Vorschau des eigenen Spielers im TRS-Menü (Seite „Umhang-Physik“) unter Forge 1.8.9–1.12.2:
 * eigene Kopie von {@code GuiInventory.drawEntityOnScreen}, aber mit freier Drehung um die senkrechte Achse
 * statt Blick zur Maus. Die echte Körperdrehung bleibt, gedreht wird die GL-Matrix; Kopf und Blick werden
 * nur für das Zeichnen geradeaus gestellt und danach zurückgesetzt. Der Umhang kommt wie im Spiel über
 * {@link ClothCapeLayer} (simuliert) bzw. den Vanilla-Umhang.
 */
public final class PlayerPreview {
	private PlayerPreview() {
	}

	/**
	 * @param yawDegrees 0 = Blick zum Betrachter, 180 = Rücken
	 * @return false ohne Spieler
	 */
	public static boolean draw(int x, int y, int w, int h, float yawDegrees) {
		EntityPlayerSP e = Mc.player();
		if (e == null || w < 8 || h < 8) return false;
		float bbh = Math.max(0.5f, e.height);
		float size = Math.min(h * 0.8f / bbh, w * 0.7f);
		float cx = x + w / 2f;
		float feet = y + h / 2f + size * bbh / 2f;

		float body = e.renderYawOffset;
		float bodyO = e.prevRenderYawOffset;
		float yaw = e.rotationYaw;
		float yawO = e.prevRotationYaw;
		float pitch = e.rotationPitch;
		float pitchO = e.prevRotationPitch;
		float head = e.rotationYawHead;
		float headO = e.prevRotationYawHead;
		boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.enableDepth();
		GlStateManager.enableColorMaterial();
		GlStateManager.pushMatrix();
		try {
			GlStateManager.translate(cx, feet, 50.0F);
			GlStateManager.scale(-size, size, size);
			GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
			GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
			RenderHelper.enableStandardItemLighting();
			GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
			// Sieht aus wie renderYawOffset = yawDegrees; das Licht bleibt dabei fest zur Ansicht.
			GlStateManager.rotate(body - yawDegrees, 0.0F, 1.0F, 0.0F);
			e.prevRenderYawOffset = body;
			e.rotationYaw = body;
			e.prevRotationYaw = body;
			e.rotationYawHead = body;
			e.prevRotationYawHead = body;
			e.rotationPitch = 0.0F;
			e.prevRotationPitch = 0.0F;
			RenderManager rm = Minecraft.getMinecraft().getRenderManager();
			rm.setPlayerViewY(180.0F);
			rm.setRenderShadow(false);
			try {
				//? if >=1.12 {
				/*rm.renderEntity(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
				*///?} elif >=1.9 {
				/*rm.doRenderEntity(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
				*///?} else {
				rm.renderEntityWithPosYaw(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
				//?}
			} finally {
				rm.setRenderShadow(true);
			}
		} finally {
			e.renderYawOffset = body;
			e.prevRenderYawOffset = bodyO;
			e.rotationYaw = yaw;
			e.prevRotationYaw = yawO;
			e.rotationPitch = pitch;
			e.prevRotationPitch = pitchO;
			e.rotationYawHead = head;
			e.prevRotationYawHead = headO;
			GlStateManager.popMatrix();
			RenderHelper.disableStandardItemLighting();
			GlStateManager.disableRescaleNormal();
			GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
			GlStateManager.disableTexture2D();
			GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
			if (!depth) GlStateManager.disableDepth();
		}
		return true;
	}
}
