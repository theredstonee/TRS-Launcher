package dev.theredstonee.trsclient.render;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.shield.ShieldPosition;
import dev.theredstonee.trsclient.core.shield.ShieldTransform;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

/**
 * Schild-Position (Modul {@code shieldPosition}, Logik in {@link ShieldPosition}): dünne Brücke zwischen den Mixins
 * am Hand-Renderer und der Rechnung in common. Nur die 1. Person – der Hand-Renderer zeichnet nichts anderes.
 *
 * <ul>
 *   <li>{@link #before}: direkt vor dem Zeichnen des Gegenstands – Haltung auf den Arm-Raum anwenden und eine Kopie
 *   des Stapels liefern (Vanillas Modell-Bedingung „blockt gerade“ vergleicht per Identität; mit der Kopie bleibt
 *   es beim normalen Modell, die Block-Haltung fährt ShieldPosition selbst weich an).</li>
 *   <li>Deckkraft beim Blocken: bis 1.21.8 über einen Puffer-Wrapper ({@code ShieldAlphaBuffers}), ab 1.21.9 über den
 *   Submit-Sammler ({@link #renderType}/{@link #tint}, solange {@link #drawAlpha} &lt; 1), in 1.14 über die GL-Farbe.</li>
 * </ul>
 * Jede Ausnahme wird abgefangen – im schlimmsten Fall sieht das Schild aus wie in Vanilla.
 */
public final class ShieldHooks {
	private static final ShieldTransform POSE = new ShieldTransform();
	/** Deckkraft der Teile, die gerade für das Schild übergeben werden (1 = unverändert). */
	private static float drawAlpha = 1f;
	private static boolean glActive;
	private static boolean failed;

	static {
		// Ab 26.1 sitzt das Schild der rechten Hand in Vanillas Modell etwas tiefer (Y 1,75 bzw. 3,25 statt 2 bzw. 5).
		//? if >=26.1 {
		/*ShieldPosition.setVanillaRightY(1.75, 3.25);
		*///?}
	}

	private ShieldHooks() {
	}

	/** Schild im Sinne des Moduls (auch Schilde anderer Mods, die ShieldItem erweitern)? */
	public static boolean isShield(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() instanceof ShieldItem;
	}

	private static ShieldPosition position() {
		TrsClient client = TrsClient.get();
		return client == null ? null : client.modules().shield;
	}

	/** Modul an und ein Schild in der Hand? */
	public static boolean applies(ItemStack stack) {
		ShieldPosition p = position();
		return p != null && p.active() && isShield(stack) && !failed;
	}

	/** Haupt- oder Nebenhand für die Seite, auf der gezeichnet wird. */
	private static int hand(LivingEntity entity, boolean leftArm) {
		boolean mainLeft = entity.getMainArm() == HumanoidArm.LEFT;
		return leftArm == mainLeft ? ShieldPosition.MAIN_HAND : ShieldPosition.OFF_HAND;
	}

	private static boolean blocking(LivingEntity entity, int hand) {
		InteractionHand used = hand == ShieldPosition.MAIN_HAND ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		return entity.isUsingItem() && entity.getUsedItemHand() == used && isShield(entity.getUseItem());
	}

	/**
	 * Vor dem Zeichnen des Gegenstands in der 1. Person: bei einem Schild die Haltung anwenden (auf {@code pose},
	 * in 1.14 auf die GL-Matrix, dann {@code pose} = null) und die Deckkraft vormerken.
	 *
	 * @return der Stapel, mit dem Minecraft das Modell wählt (bei einem Schild eine Kopie)
	 */
	public static ItemStack before(LivingEntity entity, ItemStack stack, boolean leftArm, Object pose) {
		drawAlpha = 1f;
		if (entity == null || !applies(stack)) return stack;
		try {
			ShieldPosition p = position();
			int hand = hand(entity, leftArm);
			p.transform(hand, leftArm, blocking(entity, hand), System.nanoTime(), POSE);
			apply(pose, POSE);
			drawAlpha = p.alpha(hand);
			return stack.copy();
		} catch (RuntimeException e) {
			fail(e);
			return stack;
		}
	}

	/** Wie {@link #before(LivingEntity, ItemStack, boolean, Object)}, aber mit bekannter Hand (26.3). */
	public static void before(LivingEntity entity, ItemStack stack, InteractionHand hand, Object pose) {
		if (entity == null) return;
		boolean mainLeft = entity.getMainArm() == HumanoidArm.LEFT;
		boolean leftArm = hand == InteractionHand.MAIN_HAND ? mainLeft : !mainLeft;
		before(entity, stack, leftArm, pose);
	}

	/** Nach dem Zeichnen: Deckkraft wieder aus. */
	public static void after() {
		drawAlpha = 1f;
	}

	/** Deckkraft der gerade übergebenen Schild-Teile (1 = unverändert). */
	public static float drawAlpha() {
		return drawAlpha;
	}

	/** Stapel für die Modellwahl (26.3: beim Auslesen des Zustands, Hand noch unbekannt). */
	public static ItemStack modelStack(ItemStack stack) {
		try {
			return applies(stack) ? stack.copy() : stack;
		} catch (RuntimeException e) {
			fail(e);
			return stack;
		}
	}

	private static void apply(Object raw, ShieldTransform t) {
		if (t.isIdentity()) return;
		//? if <1.15 {
		/*com.mojang.blaze3d.platform.GlStateManager.translatef(t.x, t.y, t.z);
		com.mojang.blaze3d.platform.GlStateManager.rotatef(t.angleDegrees(), t.axisX(), t.axisY(), t.axisZ());
		com.mojang.blaze3d.platform.GlStateManager.scalef(t.scale, t.scale, t.scale);
		*///?} elif >=26.3 {
		/*com.mojang.blaze3d.vertex.PoseStack pose = (com.mojang.blaze3d.vertex.PoseStack) raw;
		pose.translate(t.x, t.y, t.z);
		pose.rotate(new org.joml.Quaternionf(t.qx, t.qy, t.qz, t.qw));
		pose.scale(t.scale, t.scale, t.scale);
		*///?} elif >=1.19.3 {
		/*com.mojang.blaze3d.vertex.PoseStack pose = (com.mojang.blaze3d.vertex.PoseStack) raw;
		pose.translate(t.x, t.y, t.z);
		pose.mulPose(new org.joml.Quaternionf(t.qx, t.qy, t.qz, t.qw));
		pose.scale(t.scale, t.scale, t.scale);
		*///?} else {
		com.mojang.blaze3d.vertex.PoseStack pose = (com.mojang.blaze3d.vertex.PoseStack) raw;
		pose.translate(t.x, t.y, t.z);
		pose.mulPose(new com.mojang.math.Quaternion(t.qx, t.qy, t.qz, t.qw));
		pose.scale(t.scale, t.scale, t.scale);
		//?}
	}

	//? if >=1.15 && <1.21.9 {
	// Bis 1.21.8: Puffer für das Schild mit Deckkraft (sonst unverändert).
	public static net.minecraft.client.renderer.MultiBufferSource buffers(LivingEntity entity, ItemStack stack, boolean leftArm,
			net.minecraft.client.renderer.MultiBufferSource buffers) {
		if (entity == null || !applies(stack)) return buffers;
		try {
			float alpha = position().alpha(hand(entity, leftArm));
			return alpha < 1f ? new ShieldAlphaBuffers(buffers, alpha) : buffers;
		} catch (RuntimeException e) {
			fail(e);
			return buffers;
		}
	}
	//?}

	//? if >=1.21.11 {
	/*// Ab 1.21.9: deckende Schild-Textur beim Übergeben durch die durchsichtige Variante ersetzen.
	public static net.minecraft.client.renderer.rendertype.RenderType renderType(net.minecraft.client.renderer.rendertype.RenderType type) {
		if (drawAlpha >= 1f) return type;
		net.minecraft.resources.Identifier sheet = net.minecraft.client.renderer.Sheets.SHIELD_SHEET;
		if (type == net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(sheet)) {
			return net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(sheet);
		}
		return glintType(type, sheet);
	}
	*///?} elif >=1.21.9 {
	/*public static net.minecraft.client.renderer.RenderType renderType(net.minecraft.client.renderer.RenderType type) {
		if (drawAlpha >= 1f) return type;
		net.minecraft.resources.ResourceLocation sheet = net.minecraft.client.renderer.Sheets.SHIELD_SHEET;
		if (type == net.minecraft.client.renderer.RenderType.entitySolid(sheet)) {
			return net.minecraft.client.renderer.RenderType.entityTranslucent(sheet);
		}
		return type;
	}
	*///?}

	//? if >=26.3 {
	/*// 26.3: verzaubertes Schild ohne Muster kommt als „entity_solid_glint“ – durchsichtig ohne Glanz.
	private static net.minecraft.client.renderer.rendertype.RenderType glintType(net.minecraft.client.renderer.rendertype.RenderType type,
			net.minecraft.resources.Identifier sheet) {
		if (type == net.minecraft.client.renderer.rendertype.RenderTypes.entitySolidGlint(sheet)) {
			return net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(sheet);
		}
		return type;
	}
	*///?} elif >=1.21.11 {
	/*private static net.minecraft.client.renderer.rendertype.RenderType glintType(net.minecraft.client.renderer.rendertype.RenderType type,
			net.minecraft.resources.Identifier sheet) {
		return type;
	}
	*///?}

	/** Ab 1.21.9: Farbe (ARGB) der übergebenen Schild-Teile mit der Deckkraft multiplizieren. */
	public static int tint(int argb) {
		if (drawAlpha >= 1f) return argb;
		int a = Math.round(((argb >>> 24) & 0xFF) * drawAlpha);
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** 1.14: vor dem Zeichnen des Schildmodells die GL-Deckkraft setzen. */
	public static void glBegin() {
		glActive = false;
		if (drawAlpha >= 1f) return;
		glActive = true;
		//? if <1.15 {
		/*com.mojang.blaze3d.platform.GlStateManager.enableBlend();
		com.mojang.blaze3d.platform.GlStateManager.blendFuncSeparate(770, 771, 1, 0);
		com.mojang.blaze3d.platform.GlStateManager.color4f(1f, 1f, 1f, drawAlpha);
		*///?}
	}

	/** 1.14: danach wieder deckend. */
	public static void glEnd() {
		if (!glActive) return;
		glActive = false;
		//? if <1.15 {
		/*com.mojang.blaze3d.platform.GlStateManager.color4f(1f, 1f, 1f, 1f);
		com.mojang.blaze3d.platform.GlStateManager.disableBlend();
		*///?}
	}

	private static void fail(RuntimeException e) {
		if (failed) return;
		failed = true;
		drawAlpha = 1f;
		TrsClient.LOGGER.warn("Schild-Position abgeschaltet (Fehler beim Zeichnen): {}", e.toString());
	}
}
