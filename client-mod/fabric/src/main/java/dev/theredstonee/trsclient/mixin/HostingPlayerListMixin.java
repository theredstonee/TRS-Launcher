package dev.theredstonee.trsclient.mixin;

import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.9 {
/*import dev.theredstonee.trsclient.hosting.HostingHooks;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Welt-Hosting ab 1.21.9: die Spielergrenze fragt der Server über {@code PlayerList#getMaxPlayers} – während des
 * Hostings gilt die Grenze der Welt (≤ 10). Darunter leer und nicht in der Mixin-Liste ({@link HostingPlayerListAccessor}).
 */
@Mixin(PlayerList.class)
public abstract class HostingPlayerListMixin {
	//? if >=1.21.9 {
	/*@Inject(method = "getMaxPlayers()I", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$max(CallbackInfoReturnable<Integer> cir) {
		int max = HostingHooks.maxPlayers();
		if (max > 0) cir.setReturnValue(max);
	}
	*///?}
}
