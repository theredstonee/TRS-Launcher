package dev.theredstonee.trsclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.20.3 {
import dev.theredstonee.trsclient.core.hosting.share.HostingPack;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URL;
//?}

/**
 * Welt-Hosting, Gast (ab 1.20.3): Die Platzhalter-Adresse des Host-Packs ({@code http://trs-pack.invalid/<sha1>.zip})
 * wird auf den lokalen, nur für diese Verbindung gültigen Endpunkt umgeschrieben (127.0.0.1, zufälliger Port + Token).
 * Alle anderen Adressen prüft Minecraft wie immer. Die Frage „Server-Resource-Pack verwenden?“ bleibt Vanilla.
 */
//? if >=1.20.3 {
@Mixin(ClientCommonPacketListenerImpl.class)
//?} else
/*@Mixin(net.minecraft.client.Minecraft.class)*/
public abstract class HostingPackClientMixin {
	//? if >=1.20.3 {
	@Inject(method = "parseResourcePackUrl", at = @At("HEAD"), cancellable = true, require = 0)
	private static void trsclient$localPack(String url, CallbackInfoReturnable<URL> cir) {
		String local = HostingPack.rewrite(url);
		if (local == null) return;
		try {
			cir.setReturnValue(new URL(local));
		} catch (java.net.MalformedURLException ignored) {
			// dann eben Vanilla (schlägt fehl)
		}
	}
	//?}
}
