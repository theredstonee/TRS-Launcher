package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.hosting.HostingHooks;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

/**
 * Welt-Hosting, Host: {@code IntegratedServer#publishServer} läuft ganz normal (gilt dann als veröffentlicht, pausiert
 * nicht mehr, Rechte/Spielmodus wie bei Vanilla-LAN) – aber OHNE TCP-Port, solange TRS es aufruft
 * ({@link HostingHooks#quiet()}). Gäste kommen ausschließlich über eigene Netty-Kanäle
 * ({@code core.hosting.netty.ServerAttach}). Normales „Im LAN öffnen“ bleibt unverändert.
 *
 * <p>Bewusst ein {@code @Inject} am Ziel statt {@code @Redirect} in {@code publishServer}: Der Redirect stürzte mit
 * älteren MixinExtras (0.5.0, z. B. mit Essential) schon beim Laden von {@code IntegratedServer} ab.
 * Den LAN-Rundruf unterdrückt {@link HostingPingerMixin}.
 */
@Mixin(ServerConnectionListener.class)
public abstract class HostingPublishMixin {
	@Inject(method = "startTcpServerListener", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$bind(InetAddress address, int port, CallbackInfo ci) {
		if (HostingHooks.quiet()) {
			HostingHooks.suppressed();
			ci.cancel();
		}
	}
}
