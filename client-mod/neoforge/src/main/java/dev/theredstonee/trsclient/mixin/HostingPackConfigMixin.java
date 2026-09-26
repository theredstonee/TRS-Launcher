package dev.theredstonee.trsclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.20.3 {
import dev.theredstonee.trsclient.core.hosting.share.HostingPack;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/**
 * Welt-Hosting, Host (ab 1.20.3): Merkt sich während {@code startConfiguration}, welcher Spieler gerade konfiguriert
 * wird – nur dann darf {@link HostingPackServerMixin} ihm das geteilte Pack nennen. Darunter nicht in der Mixin-Liste.
 */
//? if >=1.20.3 {
@Mixin(ServerConfigurationPacketListenerImpl.class)
//?} else
/*@Mixin(net.minecraft.server.MinecraftServer.class)*/
public abstract class HostingPackConfigMixin {
	//? if >=1.20.3 {
	@Shadow
	protected abstract com.mojang.authlib.GameProfile playerProfile();

	@Inject(method = "startConfiguration", at = @At("HEAD"), require = 0)
	private void trsclient$enter(CallbackInfo ci) {
		com.mojang.authlib.GameProfile p = playerProfile();
		//? if >=1.21.9 {
		/*HostingPack.enter(p == null ? null : p.name());
		*///?} else
		HostingPack.enter(p == null ? null : p.getName());
	}

	@Inject(method = "startConfiguration", at = @At("RETURN"), require = 0)
	private void trsclient$exit(CallbackInfo ci) {
		HostingPack.exit();
	}
	//?}
}
