package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Farbe der Hitboxen (Modul "Hitboxen"). Die Boxen werden über renderLineBox gezeichnet;
 * die letzten vier Werte sind immer Rot/Grün/Blau/Deckkraft – unabhängig davon, ob die
 * Fassung mit AABB oder mit sechs Koordinaten aufgerufen wird.
 * Ab 1.21.9 gibt es den EntityRenderDispatcher so nicht mehr – dort ist der Mixin nicht eingetragen.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class HitboxColorMixin {
	@ModifyArgs(method = "renderHitbox",
			//? if >=1.21.4 {
			/*at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShapeRenderer;renderLineBox"),
			*///?} else
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox"),
			require = 1)
	private static void trsclient$hitboxColor(Args args) {
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
