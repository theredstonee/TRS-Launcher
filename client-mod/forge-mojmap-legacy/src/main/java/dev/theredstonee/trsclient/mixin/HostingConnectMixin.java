package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.hosting.netty.TrsConnect;
import dev.theredstonee.trsclient.hosting.HostingMarker;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Welt-Hosting, Gast: Verbindet Minecraft zu einer Marker-Adresse ({@link TrsConnect#MARKER_IP}), nimmt der
 * Bootstrap statt des TCP-Kanals den {@code TrsChannel} (P2P/Relay-Strom). Alle anderen Verbindungen bleiben
 * unverändert. Die Zieladresse kommt per HEAD-Inject (ThreadLocal) zum Kanal-Tausch.
 */
@Mixin(Connection.class)
public abstract class HostingConnectMixin implements HostingMarker {
	//? if >=1.21.11 {
	/*@Inject(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;", at = @At("HEAD"))
	private static void trsclient$target(java.net.InetSocketAddress address, net.minecraft.server.network.EventLoopGroupHolder holder,
			Connection connection, CallbackInfoReturnable<io.netty.channel.ChannelFuture> cir) {
		TrsConnect.target(address);
	}

	@ModifyArg(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
	private static Class<?> trsclient$channel(Class<?> original) {
		return TrsConnect.channelForTarget(original);
	}
	*///?} elif >=1.20.1 {
	/*@Inject(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;", at = @At("HEAD"))
	private static void trsclient$target(java.net.InetSocketAddress address, boolean epoll, Connection connection,
			CallbackInfoReturnable<io.netty.channel.ChannelFuture> cir) {
		TrsConnect.target(address);
	}

	@ModifyArg(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
	private static Class<?> trsclient$channel(Class<?> original) {
		return TrsConnect.channelForTarget(original);
	}
	*///?} elif >=1.17 {
	/*@Inject(method = "connectToServer(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;", at = @At("HEAD"))
	private static void trsclient$target(java.net.InetSocketAddress address, boolean epoll, CallbackInfoReturnable<Connection> cir) {
		TrsConnect.target(address);
	}

	@ModifyArg(method = "connectToServer(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
	private static Class<?> trsclient$channel(Class<?> original) {
		return TrsConnect.channelForTarget(original);
	}
	*///?} else {
	@Inject(method = "connectToServer(Ljava/net/InetAddress;IZ)Lnet/minecraft/network/Connection;", at = @At("HEAD"))
	private static void trsclient$target(java.net.InetAddress address, int port, boolean epoll, CallbackInfoReturnable<Connection> cir) {
		TrsConnect.target(new java.net.InetSocketAddress(address, port));
	}

	@ModifyArg(method = "connectToServer(Ljava/net/InetAddress;IZ)Lnet/minecraft/network/Connection;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
	private static Class<?> trsclient$channel(Class<?> original) {
		return TrsConnect.channelForTarget(original);
	}
	//?}
}
