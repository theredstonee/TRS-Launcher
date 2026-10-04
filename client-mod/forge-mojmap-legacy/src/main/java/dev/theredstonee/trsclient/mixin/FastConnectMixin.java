package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.connect.FastConnect;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Schnell verbinden: Vor dem Netty-Aufbau der Client-Verbindung fährt {@link FastConnect} das Adressen-Rennen
 * (nur im Thread des Verbinden-Bildschirms, nur bei mehreren Adressen). Netty bekommt dann die Kanalklasse, die die
 * fertige Verbindung übernimmt, und die Gewinner-Adresse (gleicher Name – der Handshake bleibt unverändert).
 */
@Mixin(Connection.class)
public abstract class FastConnectMixin {
	//? if >=1.21.11 {
	/*@Inject(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;", at = @At("HEAD"))
	private static void trsclient$fastConnect(java.net.InetSocketAddress address, net.minecraft.server.network.EventLoopGroupHolder holder,
			Connection connection, CallbackInfoReturnable<io.netty.channel.ChannelFuture> cir) {
		FastConnect.beforeConnect(address);
	}

	@ModifyArg(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
	private static Class<?> trsclient$fastChannel(Class<?> original) {
		return FastConnect.channelClass(original);
	}

	@ModifyArg(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;", remap = false))
	private static java.net.InetAddress trsclient$fastTarget(java.net.InetAddress original) {
		return FastConnect.connectAddress(original);
	}

	@Inject(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;", at = @At("RETURN"))
	private static void trsclient$fastDone(java.net.InetSocketAddress address, net.minecraft.server.network.EventLoopGroupHolder holder,
			Connection connection, CallbackInfoReturnable<io.netty.channel.ChannelFuture> cir) {
		FastConnect.afterConnect();
	}
	*///?} elif >=1.20.1 {
	/*@Inject(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;", at = @At("HEAD"))
	private static void trsclient$fastConnect(java.net.InetSocketAddress address, boolean epoll, Connection connection,
			CallbackInfoReturnable<io.netty.channel.ChannelFuture> cir) {
		FastConnect.beforeConnect(address);
	}

	@ModifyArg(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
	private static Class<?> trsclient$fastChannel(Class<?> original) {
		return FastConnect.channelClass(original);
	}

	@ModifyArg(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;", remap = false))
	private static java.net.InetAddress trsclient$fastTarget(java.net.InetAddress original) {
		return FastConnect.connectAddress(original);
	}

	@Inject(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;", at = @At("RETURN"))
	private static void trsclient$fastDone(java.net.InetSocketAddress address, boolean epoll, Connection connection,
			CallbackInfoReturnable<io.netty.channel.ChannelFuture> cir) {
		FastConnect.afterConnect();
	}
	*///?} elif >=1.17 {
	/*@Inject(method = "connectToServer(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;", at = @At("HEAD"))
	private static void trsclient$fastConnect(java.net.InetSocketAddress address, boolean epoll, CallbackInfoReturnable<Connection> cir) {
		FastConnect.beforeConnect(address);
	}

	@ModifyArg(method = "connectToServer(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
	private static Class<?> trsclient$fastChannel(Class<?> original) {
		return FastConnect.channelClass(original);
	}

	@ModifyArg(method = "connectToServer(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;", remap = false))
	private static java.net.InetAddress trsclient$fastTarget(java.net.InetAddress original) {
		return FastConnect.connectAddress(original);
	}

	@Inject(method = "connectToServer(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;", at = @At("RETURN"))
	private static void trsclient$fastDone(java.net.InetSocketAddress address, boolean epoll, CallbackInfoReturnable<Connection> cir) {
		FastConnect.afterConnect();
	}
	*///?} else {
	@Inject(method = "connectToServer(Ljava/net/InetAddress;IZ)Lnet/minecraft/network/Connection;", at = @At("HEAD"))
	private static void trsclient$fastConnect(java.net.InetAddress address, int port, boolean epoll, CallbackInfoReturnable<Connection> cir) {
		FastConnect.beforeConnect(new java.net.InetSocketAddress(address, port));
	}

	@ModifyArg(method = "connectToServer(Ljava/net/InetAddress;IZ)Lnet/minecraft/network/Connection;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
	private static Class<?> trsclient$fastChannel(Class<?> original) {
		return FastConnect.channelClass(original);
	}

	@ModifyArg(method = "connectToServer(Ljava/net/InetAddress;IZ)Lnet/minecraft/network/Connection;",
			at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;", remap = false))
	private static java.net.InetAddress trsclient$fastTarget(java.net.InetAddress original) {
		return FastConnect.connectAddress(original);
	}

	@Inject(method = "connectToServer(Ljava/net/InetAddress;IZ)Lnet/minecraft/network/Connection;", at = @At("RETURN"))
	private static void trsclient$fastDone(java.net.InetAddress address, int port, boolean epoll, CallbackInfoReturnable<Connection> cir) {
		FastConnect.afterConnect();
	}
	//?}
}
