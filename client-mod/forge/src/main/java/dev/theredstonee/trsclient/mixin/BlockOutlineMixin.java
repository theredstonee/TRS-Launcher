package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=1.21.3 {
/*import org.spongepowered.asm.mixin.injection.ModifyVariable;
*///?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Redirect;
//?}

/**
 * Farbe der Block-Umrandung. Bis 1.21.1 bekommt die Zeichenmethode vier Farbanteile (der Aufruf
 * wird umgeleitet und mit den eigenen Werten wiederholt – @ModifyArgs geht unter Forge nicht,
 * dessen Klassenlader findet die dafür erzeugte Args-Hilfsklasse nicht), ab 1.21.3 eine einzelne
 * ARGB-Zahl (ab 26.2 heißt die Methode submitHitOutline). Die Stärke der Linien wird nicht hier,
 * sondern über RenderSystem#lineWidth gesetzt (siehe {@code LineWidthMixin}).
 */
@Mixin(LevelRenderer.class)
public abstract class BlockOutlineMixin {
	//? if >=26.2 {
	/*@ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
	private int trsclient$outlineColor(int color) {
		return trsclient$color(color);
	}
	*///?} elif >=1.21.3 {
	/*@ModifyVariable(method = "renderHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
	private int trsclient$outlineColor(int color) {
		return trsclient$color(color);
	}
	*///?} else {
	/** Die (private) Zeichenmethode des Renderers, um sie mit eigenen Farben erneut aufzurufen. */
	@Shadow
	private static void renderShape(PoseStack pose, VertexConsumer consumer, VoxelShape shape,
			double x, double y, double z, float red, float green, float blue, float alpha) {
		throw new AssertionError("Mixin-Platzhalter");
	}

	@Redirect(method = "renderHitOutline",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderShape("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
					+ "Lnet/minecraft/world/phys/shapes/VoxelShape;DDDFFFF)V"), require = 1)
	private void trsclient$outlineColor(PoseStack pose, VertexConsumer consumer, VoxelShape shape,
			double x, double y, double z, float red, float green, float blue, float alpha) {
		TrsModules modules = trsclient$modules();
		if (modules == null || !modules.blockOutline.isEnabled()) {
			renderShape(pose, consumer, shape, x, y, z, red, green, blue, alpha);
			return;
		}
		int rgb = modules.blockOutlineColor.rgb();
		renderShape(pose, consumer, shape, x, y, z, ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f,
				(rgb & 0xFF) / 255f, modules.blockOutlineOpacity.getInt() / 100f);
	}
	//?}

	//? if >=1.21.3 {
	/*/^* Farbe als ARGB ersetzen, wenn das Modul an ist. *^/
	private static int trsclient$color(int original) {
		TrsModules modules = trsclient$modules();
		if (modules == null || !modules.blockOutline.isEnabled()) return original;
		int alpha = Math.round(modules.blockOutlineOpacity.getInt() * 2.55f);
		return (alpha << 24) | modules.blockOutlineColor.rgb();
	}
	*///?}

	private static TrsModules trsclient$modules() {
		TrsClient client = TrsClient.get();
		return client == null ? null : client.modules();
	}
}
