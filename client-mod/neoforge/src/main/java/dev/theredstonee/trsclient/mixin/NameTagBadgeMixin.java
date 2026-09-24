package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.online.OnlineHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <1.21.2 {
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
//?}
//? if >=1.15 && <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
//?}

/**
 * TRS-Abzeichen vor dem Namensschild über dem Kopf. Ab 1.21.2 steht der Name im Render-State
 * ({@code nameTag}, gesetzt in extractRenderState), davor wird renderNameTag einmal mit dem erweiterten Namen
 * erneut aufgerufen (Vanilla-Aufruf abgebrochen). Zeigt nur "nutzt TRS" – keine weiteren Infos.
 */
//? if >=1.21.9 {
/*@Mixin(net.minecraft.client.renderer.entity.player.AvatarRenderer.class)
*///?} else
@Mixin(net.minecraft.client.renderer.entity.player.PlayerRenderer.class)
public abstract class NameTagBadgeMixin {
	//? if >=1.21.9 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void trsclient$badge(net.minecraft.world.entity.Avatar entity,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float partial, CallbackInfo ci) {
		if (state.nameTag != null && OnlineHooks.badge(entity.getUUID(), false)) {
			state.nameTag = OnlineHooks.badged(entity.getUUID(), state.nameTag);
		}
	}
	*///?} elif >=1.21.2 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void trsclient$badge(net.minecraft.client.player.AbstractClientPlayer entity,
			net.minecraft.client.renderer.entity.state.PlayerRenderState state, float partial, CallbackInfo ci) {
		if (state.nameTag != null && OnlineHooks.badge(entity.getUUID(), false)) {
			state.nameTag = OnlineHooks.badged(entity.getUUID(), state.nameTag);
		}
	}
	*///?} elif >=1.20.5 {
	@Unique
	private static boolean trsclient$inBadge;

	@Shadow
	protected abstract void renderNameTag(AbstractClientPlayer player, Component name, PoseStack pose,
			MultiBufferSource buffers, int light, float partial);

	@Inject(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$badge(AbstractClientPlayer player, Component name, PoseStack pose, MultiBufferSource buffers,
			int light, float partial, CallbackInfo ci) {
		if (trsclient$inBadge || !OnlineHooks.badge(player.getUUID(), false)) return;
		trsclient$inBadge = true;
		try {
			renderNameTag(player, OnlineHooks.badged(player.getUUID(), name), pose, buffers, light, partial);
		} finally {
			trsclient$inBadge = false;
		}
		ci.cancel();
	}
	//?} elif >=1.16 {
	/*@Unique
	private static boolean trsclient$inBadge;

	@Shadow
	protected abstract void renderNameTag(AbstractClientPlayer player, Component name, PoseStack pose,
			MultiBufferSource buffers, int light);

	@Inject(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$badge(AbstractClientPlayer player, Component name, PoseStack pose, MultiBufferSource buffers,
			int light, CallbackInfo ci) {
		if (trsclient$inBadge || !OnlineHooks.badge(player.getUUID(), false)) return;
		trsclient$inBadge = true;
		try {
			renderNameTag(player, OnlineHooks.badged(player.getUUID(), name), pose, buffers, light);
		} finally {
			trsclient$inBadge = false;
		}
		ci.cancel();
	}
	*///?} elif >=1.15 {
	/*@Unique
	private static boolean trsclient$inBadge;

	@Shadow
	protected abstract void renderNameTag(AbstractClientPlayer player, String name, PoseStack pose,
			MultiBufferSource buffers, int light);

	@Inject(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Ljava/lang/String;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$badge(AbstractClientPlayer player, String name, PoseStack pose, MultiBufferSource buffers,
			int light, CallbackInfo ci) {
		if (trsclient$inBadge || !OnlineHooks.badge(player.getUUID(), false)) return;
		trsclient$inBadge = true;
		try {
			renderNameTag(player, OnlineHooks.badged(name), pose, buffers, light);
		} finally {
			trsclient$inBadge = false;
		}
		ci.cancel();
	}
	*///?} else {
	/*@Unique
	private static boolean trsclient$inBadge;

	@Shadow
	protected abstract void renderNameTags(AbstractClientPlayer player, double x, double y, double z, String name,
			double distance);

	@Inject(method = "renderNameTags(Lnet/minecraft/client/player/AbstractClientPlayer;DDDLjava/lang/String;D)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$badge(AbstractClientPlayer player, double x, double y, double z, String name, double distance,
			CallbackInfo ci) {
		if (trsclient$inBadge || !OnlineHooks.badge(player.getUUID(), false)) return;
		trsclient$inBadge = true;
		try {
			renderNameTags(player, x, y, z, OnlineHooks.badged(name), distance);
		} finally {
			trsclient$inBadge = false;
		}
		ci.cancel();
	}
	*///?}
}
