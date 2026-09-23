package dev.theredstonee.trsclient.mixin;

// Diese Klasse gibt es erst ab 1.15 (in 1.14 fehlen ScreenEffectRenderer/RenderSystem);
// in 1.14 ist der Mixin auch nicht in trsclient.mixins.json eingetragen.
//? if >=1.15 {
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Farbe der Hitboxen (Modul "Hitboxen"). Die Boxen werden über renderLineBox gezeichnet;
 * die letzten vier Werte sind immer Rot/Grün/Blau/Deckkraft – unabhängig davon, ob die
 * Fassung mit AABB oder mit sechs Koordinaten aufgerufen wird.
 * Ab 1.21.9 zeichnet ein eigener Renderer die Hitboxen – dort ist der Mixin nicht eingetragen.
 *
 * Die Ziele brauchen die vollständige Signatur: renderLineBox gibt es in mehreren
 * Fassungen, und ohne Signatur schreibt Loom den Namen für das fertige Spiel nicht um
 * (dann findet Mixin im echten Spiel nichts). require = 0: Eine fehlende Stelle
 * kostet nur die Farbe, nie den Spielstart.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class HitboxColorMixin {
	@ModifyArgs(method = "renderHitbox",
			//? if >=1.21.2 {
			/*at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShapeRenderer;renderLineBox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDDDDFFFF)V"),
			*///?} else
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDDDDFFFF)V"),
			require = 0)
	//? if >=1.17 {
	private static void trsclient$hitboxColor(Args args) {
	//?} else
	/*private void trsclient$hitboxColor(Args args) {*/
		trsclient$recolor(args);
	}

	// 1.17 bis 1.21.4 zeichnen die Hauptbox zusätzlich über die AABB-Fassung.
	//? if >=1.17 && <1.21.5 {
	@ModifyArgs(method = "renderHitbox",
			//? if >=1.21.2 {
			/*at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShapeRenderer;renderLineBox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/phys/AABB;FFFF)V"),
			*///?} else
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/phys/AABB;FFFF)V"),
			require = 0)
	private static void trsclient$hitboxColorBox(Args args) {
		trsclient$recolor(args);
	}
	//?}

	@Unique
	private static void trsclient$recolor(Args args) {
		TrsClient client = TrsClient.get();
		if (client == null) return;
		TrsModules modules = client.modules();
		if (!modules.hitboxes.isEnabled()) return;
		int size = args.size();
		if (size < 4) return;
		int rgb = modules.hitboxColor.rgb();
		args.set(size - 4, ((rgb >> 16) & 0xFF) / 255f);
		args.set(size - 3, ((rgb >> 8) & 0xFF) / 255f);
		args.set(size - 2, (rgb & 0xFF) / 255f);
	}
}
//?}
