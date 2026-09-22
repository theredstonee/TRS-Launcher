package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-Tick (Anfang/Ende von Minecraft#tick) – wie Fabrics START/END_CLIENT_TICK und unabhängig von
 * Forges Tick-Event (dessen API sich mit EventBus 7 geändert hat).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"), require = 1)
	private void trsclient$startTick(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null) client.onStartTick((Minecraft) (Object) this);
	}

	@Inject(method = "tick", at = @At("TAIL"), require = 1)
	private void trsclient$endTick(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null) client.onEndTick((Minecraft) (Object) this);
	}
}
