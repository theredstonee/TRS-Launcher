package dev.theredstonee.trsclient.mixin;

//? if >=1.15 {
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Farbe der Hitboxen (Modul "Hitboxen"). Die Trefferbox wird über {@code renderLineBox} gezeichnet;
 * die letzten vier Argumente sind immer Rot/Grün/Blau/Deckkraft. Bis 1.16.5 nimmt die Methode
 * sechs Koordinaten, ab 1.17 eine AABB – deshalb zwei Ziele (Forge braucht die volle Signatur,
 * weil jede Überladung einen eigenen SRG-Namen hat). Die rote Augenlinie bleibt rot.
 * Ab 1.17 ist {@code renderHitbox} statisch, davor eine Instanzmethode.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class HitboxColorMixin {
	//? if >=1.17 {
	/*@ModifyArgs(method = "renderHitbox",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
					+ "Lnet/minecraft/world/phys/AABB;FFFF)V"), require = 1)
	private static void trsclient$hitboxColor(Args args) {
	*///?} else {
	@ModifyArgs(method = "renderHitbox",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox("
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
					+ "DDDDDDFFFF)V"), require = 1)
	private void trsclient$hitboxColor(Args args) {
	//?}
		TrsClient client = TrsClient.get();
		if (client == null) return;
		TrsModules modules = client.modules();
		if (!modules.hitboxes.isEnabled()) return;
		int size = args.size();
		if (size < 4) return;
		int rgb = modules.hitboxColor.rgb();
		// Die letzten vier Werte sind Rot/Grün/Blau/Deckkraft – die Deckkraft bleibt.
		args.set(size - 4, ((rgb >> 16) & 0xFF) / 255f);
		args.set(size - 3, ((rgb >> 8) & 0xFF) / 255f);
		args.set(size - 2, (rgb & 0xFF) / 255f);
	}
}
//?} else {
/*/^* Minecraft 1.14.4 zeichnet die Hitboxen noch ohne PoseStack (und Forge dort kein Mixin) – leer. ^/
public final class HitboxColorMixin {
	private HitboxColorMixin() {
	}
}
*///?}
