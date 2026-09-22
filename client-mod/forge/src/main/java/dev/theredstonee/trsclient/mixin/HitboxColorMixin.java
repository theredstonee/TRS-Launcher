package dev.theredstonee.trsclient.mixin;

// Ab 1.21.9 zeichnet ein eigener Renderer die Hitboxen (EntityRenderDispatcher#renderHitbox gibt es
// nicht mehr) – für diese Versionen ist der Mixin nicht in trsclient.mixins.json eingetragen.
//? if <1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
//? if >=1.21.3 {
/*import net.minecraft.client.renderer.ShapeRenderer;
*///?} else
import net.minecraft.client.renderer.LevelRenderer;
//? if <1.21.5 {
import net.minecraft.world.phys.AABB;
//?}

/**
 * Farbe der Hitboxen (Modul "Hitboxen"). Die Boxen werden über renderLineBox gezeichnet; der Aufruf
 * wird umgeleitet und mit den eingestellten Farbanteilen wiederholt. Die Hilfsmethode liegt bis
 * 1.21.1 im LevelRenderer, danach im ShapeRenderer; ab 1.21.5 gibt es nur noch die Fassung mit
 * sechs Koordinaten statt einer AABB.
 *
 * <p>Bewusst @Redirect statt @ModifyArgs: Forges Klassenlader findet die von @ModifyArgs erzeugte
 * Hilfsklasse {@code org.spongepowered.asm.synthetic.args.Args$1} nicht (Absturz beim Start).
 * Die Zielbeschreibung steht vollständig da, damit sie der Mixin-Annotation-Processor für die
 * SRG-Versionen (1.20 – 1.20.4) übersetzen kann.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class HitboxColorMixin {
	//? if >=1.21.5 {
	/*@Redirect(method = "renderHitbox",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShapeRenderer;renderLineBox("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDDDDFFFF)V"),
			require = 1)
	private static void trsclient$hitboxColor(PoseStack pose, VertexConsumer consumer, double x1, double y1, double z1,
			double x2, double y2, double z2, float red, float green, float blue, float alpha) {
		ShapeRenderer.renderLineBox(pose, consumer, x1, y1, z1, x2, y2, z2,
				trsclient$part(red, 16), trsclient$part(green, 8), trsclient$part(blue, 0), alpha);
	}
	*///?} elif >=1.21.3 {
	/*@Redirect(method = "renderHitbox",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShapeRenderer;renderLineBox("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
					+ "Lnet/minecraft/world/phys/AABB;FFFF)V"),
			require = 1)
	private static void trsclient$hitboxColor(PoseStack pose, VertexConsumer consumer, AABB box,
			float red, float green, float blue, float alpha) {
		ShapeRenderer.renderLineBox(pose, consumer, box,
				trsclient$part(red, 16), trsclient$part(green, 8), trsclient$part(blue, 0), alpha);
	}
	*///?} else {
	@Redirect(method = "renderHitbox",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
					+ "Lnet/minecraft/world/phys/AABB;FFFF)V"),
			require = 1)
	private static void trsclient$hitboxColor(PoseStack pose, VertexConsumer consumer, AABB box,
			float red, float green, float blue, float alpha) {
		LevelRenderer.renderLineBox(pose, consumer, box,
				trsclient$part(red, 16), trsclient$part(green, 8), trsclient$part(blue, 0), alpha);
	}
	//?}

	/** Farbanteil (Bit-Verschiebung 16/8/0) der eingestellten Farbe, sonst der Vanilla-Wert. */
	private static float trsclient$part(float original, int shift) {
		TrsClient client = TrsClient.get();
		if (client == null) return original;
		TrsModules modules = client.modules();
		if (!modules.hitboxes.isEnabled()) return original;
		return ((modules.hitboxColor.rgb() >> shift) & 0xFF) / 255f;
	}
}
//?}
