package dev.theredstonee.trsclient.mixin;

// Diese Klasse gibt es erst ab 1.15 (in 1.14 fehlen ScreenEffectRenderer/RenderSystem);
// in 1.14 ist der Mixin auch nicht in trsclient.mixins.json eingetragen.
//? if >=1.15 {
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=1.21.4 {
/*import org.spongepowered.asm.mixin.injection.ModifyVariable;
*///?} else {
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
//?}

/**
 * Farbe der Block-Umrandung. Bis 1.21.3 bekommt die Zeichenmethode vier Farbanteile,
 * ab 1.21.4 eine einzelne ARGB-Zahl (ab 26.2 heißt die Methode submitHitOutline).
 * Die Stärke der Linien wird nicht hier, sondern im Renderer gesetzt (siehe BlockOutline).
 */
@Mixin(LevelRenderer.class)
public abstract class BlockOutlineMixin {
	//? if >=26.2 {
	/*@ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
	private int trsclient$outlineColor(int color) {
		return trsclient$color(color);
	}
	*///?} elif >=1.21.4 {
	/*@ModifyVariable(method = "renderHitOutline", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
	private int trsclient$outlineColor(int color) {
		return trsclient$color(color);
	}
	*///?} else {
	@ModifyArgs(method = "renderHitOutline",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderShape("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
					+ "Lnet/minecraft/world/phys/shapes/VoxelShape;DDDFFFF)V"), require = 1)
	private void trsclient$outlineColor(Args args) {
		TrsModules modules = trsclient$modules();
		if (modules == null || !modules.blockOutline.isEnabled()) return;
		int rgb = modules.blockOutlineColor.rgb();
		args.set(6, ((rgb >> 16) & 0xFF) / 255f);
		args.set(7, ((rgb >> 8) & 0xFF) / 255f);
		args.set(8, (rgb & 0xFF) / 255f);
		args.set(9, modules.blockOutlineOpacity.getInt() / 100f);
	}
	//?}

	//? if >=1.21.4 {
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
//?}
