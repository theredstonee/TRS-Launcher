package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.hosting.HostingHooks;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Welt-Hosting, Host: {@code IntegratedServer#publishServer} ganz normal laufen lassen (gilt dann als veröffentlicht,
 * pausiert nicht mehr, Rechte/Spielmodus wie bei Vanilla-LAN) – aber OHNE TCP-Port und OHNE LAN-Rundruf, solange
 * TRS es aufruft ({@link HostingHooks#quiet()}). Gäste kommen ausschließlich über eigene Netty-Kanäle
 * ({@code core.hosting.netty.ServerAttach}). Normales „Im LAN öffnen“ bleibt unverändert.
 */
@Mixin(IntegratedServer.class)
public abstract class HostingPublishMixin {
	@Redirect(method = "publishServer", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"))
	private void trsclient$bind(ServerConnectionListener listener, InetAddress address, int port) throws IOException {
		if (HostingHooks.quiet()) {
			HostingHooks.suppressed();
			return;
		}
		listener.startTcpServerListener(address, port);
	}

	@Redirect(method = "publishServer", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/server/LanServerPinger;start()V"))
	private void trsclient$ping(LanServerPinger pinger) {
		if (!HostingHooks.quiet()) pinger.start();
	}
}
