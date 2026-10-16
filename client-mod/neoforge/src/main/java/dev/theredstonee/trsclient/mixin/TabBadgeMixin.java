package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.online.FanHooks;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * TRS-Abzeichen, Creator-Stern und Platz fürs Fan-Symbol vor dem Namen in der Tabliste ({@code FanHooks#decorate}).
 * Die Breite rechnet Vanilla mit dem geänderten Namen – das Layout passt sich an; das Symbol zeichnet
 * {@code FanGuiMixin}.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class TabBadgeMixin {
	@Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$badge(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		//? if >=1.21.9 {
		/*UUID id = info.getProfile().id();
		*///?} else
		UUID id = info.getProfile().getId();
		Component name = cir.getReturnValue();
		Component decorated = FanHooks.decorate(id, name, true);
		if (decorated != name) cir.setReturnValue(decorated);
	}
}
