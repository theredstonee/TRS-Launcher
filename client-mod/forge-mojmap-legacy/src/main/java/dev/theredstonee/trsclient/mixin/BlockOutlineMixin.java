package dev.theredstonee.trsclient.mixin;

//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Farbe der Block-Umrandung: ersetzt die vier Farbanteile, mit denen {@code renderHitOutline}
 * die Umrisse zeichnet. Signatur und Aufruf sind von 1.15.2 bis 1.19.4 identisch.
 * Die Linienstärke macht {@link LineWidthMixin}.
 *
 * <p>Bewusst {@code @Redirect} statt {@code @ModifyArgs}: Für {@code @ModifyArgs} erzeugt Mixin
 * zur Laufzeit eine Klasse in {@code org.spongepowered.asm.synthetic.args}, die der Modul-Classloader
 * von Forge ab 1.17 nicht laden kann (das Spiel stürzt beim Start ab).
 * (Minecraft 1.14.4 zeichnet die Umrandung noch ohne PoseStack – dort gibt es das Modul nicht.)
 */
@Mixin(LevelRenderer.class)
public abstract class BlockOutlineMixin {
	/** Vanillas eigene (private) Zeichenmethode – wird mit geänderten Farben wieder aufgerufen. */
	@Shadow
	private static void renderShape(PoseStack pose, VertexConsumer consumer, VoxelShape shape,
			double x, double y, double z, float red, float green, float blue, float alpha) {
		throw new AssertionError("Mixin-Shadow");
	}

	@Redirect(method = "renderHitOutline",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderShape("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
					+ "Lnet/minecraft/world/phys/shapes/VoxelShape;DDDFFFF)V"), require = 1)
	private void trsclient$outlineColor(PoseStack pose, VertexConsumer consumer, VoxelShape shape,
			double x, double y, double z, float red, float green, float blue, float alpha) {
		TrsClient client = TrsClient.get();
		TrsModules modules = client == null ? null : client.modules();
		if (modules != null && modules.blockOutline.isEnabled()) {
			int rgb = modules.blockOutlineColor.rgb();
			red = ((rgb >> 16) & 0xFF) / 255f;
			green = ((rgb >> 8) & 0xFF) / 255f;
			blue = (rgb & 0xFF) / 255f;
			alpha = modules.blockOutlineOpacity.getInt() / 100f;
		}
		renderShape(pose, consumer, shape, x, y, z, red, green, blue, alpha);
	}
}
//?} else {
/*/^* Minecraft 1.14.4 kennt renderShape(PoseStack, …) noch nicht (und Forge dort kein Mixin) – leer. ^/
public final class BlockOutlineMixin {
	private BlockOutlineMixin() {
	}
}
*///?}
