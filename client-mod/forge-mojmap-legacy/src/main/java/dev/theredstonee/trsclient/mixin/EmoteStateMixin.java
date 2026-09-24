package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.online.EmoteHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ab 1.21.2 kennt das Modell nur noch den Render-State: beim Befüllen ({@code extractRenderState}) wird der State
 * dem Spieler zugeordnet, damit {@link EmoteModelMixin} dessen Emote findet. Nur in der Mixin-Liste ab 1.21.2
 * (davor ist die Klasse leer und wird nicht geladen).
 */
//? if >=1.21.9 {
/*@Mixin(net.minecraft.client.renderer.entity.player.AvatarRenderer.class)
*///?} else {
@Mixin(net.minecraft.client.renderer.entity.player.PlayerRenderer.class)
//?}
public abstract class EmoteStateMixin {
	//? if >=1.21.9 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void trsclient$emoteState(net.minecraft.world.entity.Avatar entity,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float partial, CallbackInfo ci) {
		EmoteHooks.extracted(state, entity.getUUID());
	}
	*///?} elif >=1.21.2 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void trsclient$emoteState(net.minecraft.client.player.AbstractClientPlayer entity,
			net.minecraft.client.renderer.entity.state.PlayerRenderState state, float partial, CallbackInfo ci) {
		EmoteHooks.extracted(state, entity.getUUID());
	}
	*///?}
}
