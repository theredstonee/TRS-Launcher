package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TRS-Umhang statt Mojang-/OptiFine-Umhang – nur wenn der Lookup für diesen Spieler einen TRS-Umhang
 * liefert (und seine Textur geladen ist). Die Elytra nimmt dieselbe Textur, wie bei Vanilla-Umhängen.
 * Bis 1.20.1 über die Textur-Getter, ab 1.20.2 über den PlayerSkin (ab 1.21.9 mit ClientAsset-Texturen).
 * require = 0: fehlt eine Stelle, bleibt einfach der Vanilla-Umhang.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class CapeTextureMixin {
	//? if >=1.21.11 {
	/*@Inject(method = "getSkin()Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$trsCape(CallbackInfoReturnable<net.minecraft.world.entity.player.PlayerSkin> cir) {
		Object tex = OnlineHooks.capeTexture((AbstractClientPlayer) (Object) this);
		net.minecraft.world.entity.player.PlayerSkin skin = cir.getReturnValue();
		if (tex == null || skin == null) return;
		net.minecraft.core.ClientAsset.ResourceTexture asset = new net.minecraft.core.ClientAsset.ResourceTexture(
				(net.minecraft.resources.Identifier) tex, (net.minecraft.resources.Identifier) tex);
		cir.setReturnValue(new net.minecraft.world.entity.player.PlayerSkin(skin.body(), asset, asset, skin.model(), skin.secure()));
	}
	*///?} elif >=1.21.9 {
	/*@Inject(method = "getSkin()Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$trsCape(CallbackInfoReturnable<net.minecraft.world.entity.player.PlayerSkin> cir) {
		Object tex = OnlineHooks.capeTexture((AbstractClientPlayer) (Object) this);
		net.minecraft.world.entity.player.PlayerSkin skin = cir.getReturnValue();
		if (tex == null || skin == null) return;
		net.minecraft.core.ClientAsset.ResourceTexture asset = new net.minecraft.core.ClientAsset.ResourceTexture(
				(net.minecraft.resources.ResourceLocation) tex, (net.minecraft.resources.ResourceLocation) tex);
		cir.setReturnValue(new net.minecraft.world.entity.player.PlayerSkin(skin.body(), asset, asset, skin.model(), skin.secure()));
	}
	*///?} elif >=1.20.2 {
	@Inject(method = "getSkin()Lnet/minecraft/client/resources/PlayerSkin;", at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$trsCape(CallbackInfoReturnable<net.minecraft.client.resources.PlayerSkin> cir) {
		Object tex = OnlineHooks.capeTexture((AbstractClientPlayer) (Object) this);
		net.minecraft.client.resources.PlayerSkin skin = cir.getReturnValue();
		if (tex == null || skin == null) return;
		net.minecraft.resources.ResourceLocation id = (net.minecraft.resources.ResourceLocation) tex;
		cir.setReturnValue(new net.minecraft.client.resources.PlayerSkin(skin.texture(), skin.textureUrl(), id, id,
				skin.model(), skin.secure()));
	}
	//?} else {
	/*@Inject(method = "getCloakTextureLocation()Lnet/minecraft/resources/ResourceLocation;", at = @At("HEAD"),
			cancellable = true, require = 0)
	private void trsclient$trsCape(CallbackInfoReturnable<net.minecraft.resources.ResourceLocation> cir) {
		Object tex = OnlineHooks.capeTexture((AbstractClientPlayer) (Object) this);
		if (tex != null) cir.setReturnValue((net.minecraft.resources.ResourceLocation) tex);
	}

	@Inject(method = "getElytraTextureLocation()Lnet/minecraft/resources/ResourceLocation;", at = @At("HEAD"),
			cancellable = true, require = 0)
	private void trsclient$trsElytra(CallbackInfoReturnable<net.minecraft.resources.ResourceLocation> cir) {
		Object tex = OnlineHooks.capeTexture((AbstractClientPlayer) (Object) this);
		if (tex != null) cir.setReturnValue((net.minecraft.resources.ResourceLocation) tex);
	}

	@Inject(method = "isCapeLoaded()Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$trsCapeLoaded(CallbackInfoReturnable<Boolean> cir) {
		if (OnlineHooks.capeTexture((AbstractClientPlayer) (Object) this) != null) cir.setReturnValue(true);
	}

	@Inject(method = "isElytraLoaded()Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$trsElytraLoaded(CallbackInfoReturnable<Boolean> cir) {
		if (OnlineHooks.capeTexture((AbstractClientPlayer) (Object) this) != null) cir.setReturnValue(true);
	}
	*///?}
}
