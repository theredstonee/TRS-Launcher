package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.net.NetHooks;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Netzwerk-Optimierung + Ping: Sobald eine Client-Verbindung steht (vor dem ersten Paket), hängt der TRS Client seine
 * beiden Beobachter-Handler ein (siehe {@code core.net.NetBoost}). Server-Verbindungen (Einzelspieler-Server, Hosting)
 * bleiben unberührt.
 */
@Mixin(Connection.class)
public abstract class NetConnectionMixin {
	@Shadow
	@Final
	private PacketFlow receiving;

	@Inject(method = "channelActive(Lio/netty/channel/ChannelHandlerContext;)V", at = @At("TAIL"), remap = false, require = 0)
	private void trsclient$netActive(ChannelHandlerContext ctx, CallbackInfo ci) {
		if (receiving == PacketFlow.CLIENTBOUND) NetHooks.channelActive(ctx.channel());
	}
}
