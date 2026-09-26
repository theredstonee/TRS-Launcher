package dev.theredstonee.trsclient.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.20.3 {
import dev.theredstonee.trsclient.core.hosting.share.HostingPack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;
//?}

/**
 * Welt-Hosting, Host (ab 1.20.3): Während ein angenommener TRS-Gast konfiguriert wird ({@link HostingPackConfigMixin}),
 * nennt der integrierte Server das geteilte Resource Pack – mit Platzhalter-Adresse, die der Gast auf seinen lokalen
 * Endpunkt umschreibt. Sonst (Host selbst, öffentlicher Link, nichts geteilt) bleibt alles Vanilla. Beim Erzeugen eines
 * Servers meldet der Mixin, dass es den Weg gibt ({@link HostingPack#markSupported}). Darunter leer und nicht in der
 * Mixin-Liste. Bewusst nur {@code @Inject} (kein {@code @Redirect}).
 */
@Mixin(MinecraftServer.class)
public abstract class HostingPackServerMixin {
	//? if >=1.20.3 {
	@Inject(method = "<init>*", at = @At("RETURN"), require = 0)
	private void trsclient$packSupported(CallbackInfo ci) {
		HostingPack.markSupported();
	}

	@Inject(method = "getServerResourcePack", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$pack(CallbackInfoReturnable<Optional<MinecraftServer.ServerResourcePackInfo>> cir) {
		Object[] offer = HostingPack.offer();
		if (offer != null) {
			cir.setReturnValue(Optional.of(new MinecraftServer.ServerResourcePackInfo((UUID) offer[0], (String) offer[1], (String) offer[2],
					false, null)));
		}
	}
	//?}
}
