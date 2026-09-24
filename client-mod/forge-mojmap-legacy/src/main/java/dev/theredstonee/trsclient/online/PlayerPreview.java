package dev.theredstonee.trsclient.online;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
//? if >=1.19.3 {
/*import org.joml.Quaternionf;
*///?}
//? if >=1.20.2 {
/*import org.joml.Vector3f;
*///?}

/**
 * Live-Vorschau des eigenen Spielers im TRS-Menü (Seite „Umhang-Physik“): zeichnet den Spieler in ein
 * Rechteck, um die senkrechte Achse gedreht – der simulierte Umhang kommt dabei ganz normal über den
 * Umhang-Hook, zeigt also genau die eingestellte Physik.
 *
 * <p>Der Spieler wird für die Dauer des Zeichnens nach vorn ausgerichtet (Körper, Kopf, Blick) und danach
 * exakt zurückgesetzt; die Physik rechnet im Client-Tick und sieht davon nichts. Versionen:
 * eigene Kopie von {@code renderEntityInInventory} bis 1.19.3 (dort dreht die Methode den Spieler zur Maus),
 * {@code InventoryScreen.renderEntityInInventory} mit Drehung 1.19.4–1.21.10, ab 1.21.11 der Render-State
 * über {@code GuiGraphics#submitEntityRenderState} bzw. ab 26.1 {@code GuiGraphicsExtractor#entity}.
 */
public final class PlayerPreview {
	private PlayerPreview() {
	}

	/** Kann diese Version den Spieler im Menü zeichnen? (1.14 hat keine Umhang-Physik.) */
	public static boolean supported() {
		//? if >=1.15 {
		return true;
		//?} else {
		/*return false;
		*///?}
	}

	/**
	 * Zeichnet den eigenen Spieler.
	 *
	 * @param graphics   Zeichenobjekt des Bilds ({@code Gfx#raw()}: GuiGraphics, GuiGraphicsExtractor oder PoseStack)
	 * @param yawDegrees 0 = Blick zum Betrachter, 180 = Rücken
	 * @return false, wenn es gerade keinen Spieler gibt
	 */
	public static boolean draw(Object graphics, int x, int y, int w, int h, float yawDegrees) {
		Minecraft mc = Minecraft.getInstance();
		LivingEntity e = mc.player;
		if (e == null || w < 8 || h < 8 || !supported()) return false;
		float bbh = Math.max(0.5f, e.getBbHeight());
		float size = Math.min(h * 0.8f / bbh, w * 0.7f);
		int cx = x + w / 2;
		int cy = y + h / 2;
		int feet = Math.round(cy + size * bbh / 2f);
		float face = 180f + yawDegrees;
		float bodyO = e.yBodyRotO;
		float body = e.yBodyRot;
		float headO = e.yHeadRotO;
		float head = e.yHeadRot;
		float yRotO = e.yRotO;
		float xRotO = e.xRotO;
		float yRot = yRot(e);
		float xRot = xRot(e);
		e.yBodyRotO = face;
		e.yBodyRot = face;
		e.yHeadRotO = face;
		e.yHeadRot = face;
		e.yRotO = face;
		e.xRotO = 0f;
		setRot(e, face, 0f);
		try {
			render(graphics, e, x, y, w, h, cx, feet, size, bbh);
			return true;
		} finally {
			e.yBodyRotO = bodyO;
			e.yBodyRot = body;
			e.yHeadRotO = headO;
			e.yHeadRot = head;
			e.yRotO = yRotO;
			e.xRotO = xRotO;
			setRot(e, yRot, xRot);
		}
	}

	private static float yRot(LivingEntity e) {
		//? if >=1.17 {
		/*return e.getYRot();
		*///?} else {
		return e.yRot;
		//?}
	}

	private static float xRot(LivingEntity e) {
		//? if >=1.17 {
		/*return e.getXRot();
		*///?} else {
		return e.xRot;
		//?}
	}

	private static void setRot(LivingEntity e, float yRot, float xRot) {
		//? if >=1.17 {
		/*e.setYRot(yRot);
		e.setXRot(xRot);
		*///?} else {
		e.yRot = yRot;
		e.xRot = xRot;
		//?}
	}

	//? if >=26.1 {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		net.minecraft.client.renderer.entity.state.EntityRenderState state = state(e);
		((net.minecraft.client.gui.GuiGraphicsExtractor) graphics).entity(state, size, new Vector3f(0f, bbh / 2f, 0f),
				new Quaternionf().rotateZ((float) Math.PI), null, x, y, x + w, y + h);
	}
	*///?} elif >=1.21.11 {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		net.minecraft.client.renderer.entity.state.EntityRenderState state = state(e);
		state.lightCoords = 15728880;
		((net.minecraft.client.gui.GuiGraphics) graphics).submitEntityRenderState(state, size,
				new Vector3f(0f, bbh / 2f, 0f), new Quaternionf().rotateZ((float) Math.PI), null, x, y, x + w, y + h);
	}
	*///?} elif >=1.21.6 {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(
				(net.minecraft.client.gui.GuiGraphics) graphics, x, y, x + w, y + h, size, new Vector3f(0f, bbh / 2f, 0f),
				new Quaternionf().rotateZ((float) Math.PI), null, e);
	}
	*///?} elif >=1.20.5 {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(
				(net.minecraft.client.gui.GuiGraphics) graphics, cx, feet - size * bbh / 2f, size,
				new Vector3f(0f, bbh / 2f, 0f), new Quaternionf().rotateZ((float) Math.PI), null, e);
	}
	*///?} elif >=1.20.2 {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(
				(net.minecraft.client.gui.GuiGraphics) graphics, cx, feet - size * bbh / 2f, Math.round(size),
				new Vector3f(0f, bbh / 2f, 0f), new Quaternionf().rotateZ((float) Math.PI), null, e);
	}
	*///?} elif >=1.20 {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(
				(net.minecraft.client.gui.GuiGraphics) graphics, cx, feet, Math.round(size),
				new Quaternionf().rotateZ((float) Math.PI), null, e);
	}
	*///?} elif >=1.19.4 {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(
				(com.mojang.blaze3d.vertex.PoseStack) graphics, cx, feet, Math.round(size),
				new Quaternionf().rotateZ((float) Math.PI), null, e);
	}
	*///?} elif >=1.19.3 {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		com.mojang.blaze3d.vertex.PoseStack modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
		modelView.pushPose();
		modelView.translate((float) cx, (float) feet, 1050.0F);
		modelView.scale(1.0F, 1.0F, -1.0F);
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
		com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
		pose.translate(0.0F, 0.0F, 1000.0F);
		pose.scale(size, size, size);
		pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));
		com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();
		drawEntity(e, pose, new Quaternionf());
		modelView.popPose();
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
		com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
	}

	private static void drawEntity(LivingEntity e, com.mojang.blaze3d.vertex.PoseStack pose, Quaternionf camera) {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		dispatcher.overrideCameraOrientation(camera);
		dispatcher.setRenderShadow(false);
		net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		com.mojang.blaze3d.systems.RenderSystem.runAsFancy(() ->
				dispatcher.render(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, pose, buffers, 15728880));
		buffers.endBatch();
		dispatcher.setRenderShadow(true);
	}
	*///?} elif >=1.17 {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		com.mojang.blaze3d.vertex.PoseStack modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
		modelView.pushPose();
		modelView.translate(cx, feet, 1050.0D);
		modelView.scale(1.0F, 1.0F, -1.0F);
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
		com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
		pose.translate(0.0D, 0.0D, 1000.0D);
		pose.scale(size, size, size);
		pose.mulPose(com.mojang.math.Vector3f.ZP.rotationDegrees(180.0F));
		com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();
		drawEntity(e, pose);
		modelView.popPose();
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
		com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
	}

	private static void drawEntity(LivingEntity e, com.mojang.blaze3d.vertex.PoseStack pose) {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		dispatcher.overrideCameraOrientation(com.mojang.math.Vector3f.XP.rotationDegrees(0.0F));
		dispatcher.setRenderShadow(false);
		net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		com.mojang.blaze3d.systems.RenderSystem.runAsFancy(() ->
				dispatcher.render(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, pose, buffers, 15728880));
		buffers.endBatch();
		dispatcher.setRenderShadow(true);
	}
	*///?} elif >=1.15 {
	private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
		com.mojang.blaze3d.systems.RenderSystem.pushMatrix();
		com.mojang.blaze3d.systems.RenderSystem.translatef((float) cx, (float) feet, 1050.0F);
		com.mojang.blaze3d.systems.RenderSystem.scalef(1.0F, 1.0F, -1.0F);
		com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
		pose.translate(0.0D, 0.0D, 1000.0D);
		pose.scale(size, size, size);
		pose.mulPose(com.mojang.math.Vector3f.ZP.rotationDegrees(180.0F));
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		dispatcher.overrideCameraOrientation(com.mojang.math.Vector3f.XP.rotationDegrees(0.0F));
		dispatcher.setRenderShadow(false);
		net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		Runnable draw = () -> dispatcher.render(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, pose, buffers, 15728880);
		if (!fancy(draw)) draw.run();
		buffers.endBatch();
		dispatcher.setRenderShadow(true);
		com.mojang.blaze3d.systems.RenderSystem.popMatrix();
	}
	//?} else {
	/*private static void render(Object graphics, LivingEntity e, int x, int y, int w, int h, int cx, int feet, float size,
			float bbh) {
	}
	*///?}

	/** 1.16 zeichnet Spieler im Menü „fancy“ (sonst fehlen mit Fast-Grafik transparente Teile). */
	//? if >=1.16 && <1.17 {
	private static boolean fancy(Runnable draw) {
		com.mojang.blaze3d.systems.RenderSystem.runAsFancy(draw);
		return true;
	}
	//?} else {
	/*private static boolean fancy(Runnable draw) {
		return false;
	}
	*///?}

	//? if >=1.21.11 {
	/*private static net.minecraft.client.renderer.entity.state.EntityRenderState state(LivingEntity e) {
		net.minecraft.client.renderer.entity.EntityRenderer renderer =
				Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e);
		net.minecraft.client.renderer.entity.state.EntityRenderState state = renderer.createRenderState(e, 1.0F);
		state.shadowPieces.clear();
		state.outlineColor = 0;
		if (state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState) {
			net.minecraft.client.renderer.entity.state.LivingEntityRenderState living =
					(net.minecraft.client.renderer.entity.state.LivingEntityRenderState) state;
			living.bodyRot = e.yBodyRot;
			living.yRot = 0.0F;
			living.xRot = 0.0F;
		}
		return state;
	}
	*///?}
}
