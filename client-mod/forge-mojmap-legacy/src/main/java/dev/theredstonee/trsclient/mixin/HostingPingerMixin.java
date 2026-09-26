package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.hosting.HostingHooks;
import net.minecraft.client.server.LanServerPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Welt-Hosting, Host: kein LAN-Rundruf, wenn TRS die Welt still veröffentlicht. Der Pinger wird in
 * {@code publishServer} angelegt (dort gilt {@link HostingHooks#quiet()}), läuft aber in seinem eigenen Thread –
 * deshalb merkt er sich beim Anlegen, ob er still bleiben soll, und {@code run()} endet dann sofort.
 */
@Mixin(LanServerPinger.class)
public abstract class HostingPingerMixin {
	@Unique
	private boolean trsclient$quiet;

	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void trsclient$created(CallbackInfo ci) {
		trsclient$quiet = HostingHooks.quiet();
	}

	@Inject(method = "run", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$run(CallbackInfo ci) {
		if (trsclient$quiet) ci.cancel();
	}
}
