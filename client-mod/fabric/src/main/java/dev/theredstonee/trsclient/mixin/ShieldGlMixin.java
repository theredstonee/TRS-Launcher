package dev.theredstonee.trsclient.mixin;

// Schild-Position in 1.14: Deckkraft beim Blocken über die GL-Farbe. Minecraft setzt die Farbe unmittelbar vor
// EntityBlockRenderer#renderByItem auf Weiß – danach (HEAD) stellt ShieldHooks#glBegin die Deckkraft ein, solange das
// Schild der 1. Person gezeichnet wird, und RETURN setzt sie zurück. Nur in der Mixin-Liste für 1.14.
//? if <1.15 {
/*import dev.theredstonee.trsclient.render.ShieldHooks;
import net.minecraft.client.renderer.EntityBlockRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityBlockRenderer.class)
public abstract class ShieldGlMixin {
	@Inject(method = "renderByItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), require = 1)
	private void trsclient$shieldAlpha(ItemStack stack, CallbackInfo ci) {
		ShieldHooks.glBegin();
	}

	@Inject(method = "renderByItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"), require = 1)
	private void trsclient$shieldAlphaEnd(ItemStack stack, CallbackInfo ci) {
		ShieldHooks.glEnd();
	}
}
*///?}
