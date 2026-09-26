package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.net.NetBoost;
import dev.theredstonee.trsclient.core.net.NetPlatform;
import dev.theredstonee.trsclient.core.perf.LatencyPanels;
import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.core.perf.Performance;
import io.netty.channel.ChannelHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.CipherDecoder;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
//? if >=1.21.11 {
/*import net.minecraft.util.Util;
*///?} else
import net.minecraft.Util;

import java.util.function.Consumer;

/**
 * Netzwerk-Optimierung und Ping-Messung für die Mojmap-Bäume (Fabric, NeoForge, Forge – dieselbe Datei): Paketklassen
 * und Vanilla-Handler dieser Minecraft-Version für {@link NetBoost}. Die Logik steht in {@code core.net}.
 */
public final class NetHooks implements NetPlatform {
	private static final NetHooks INSTANCE = new NetHooks();
	private static TrsModules modules;

	private NetHooks() {
	}

	/** Beim Start (nach dem Laden der Config und von PerfHooks). */
	public static void init(final TrsModules m, final Consumer<String> log) {
		modules = m;
		NetBoost.init(INSTANCE, new NetBoost.Switch() {
			@Override
			public boolean on() {
				Performance p = Performance.current();
				return m.netOptimize.isEnabled() && (p == null || p.active(PerfFeature.NET_CODECS));
			}
		}, new NetBoost.Logger() {
			@Override
			public void info(String message) {
				if (log != null) log.accept(message);
			}

			@Override
			public void warn(String message, Throwable t) {
				if (log != null) log.accept(message + ": " + t);
			}
		});
		LatencyPanels.registerNetOnly(m.netOptimize);
	}

	/** Je Client-Tick. */
	public static void tick(Minecraft mc) {
		TrsModules m = modules;
		if (m == null) return;
		ClientPacketListener listener = mc.getConnection();
		NetBoost.tick(listener == null ? null : NetBoost.channelOf(listener.getConnection()), m.ping.isEnabled(),
				Math.round(m.pingInterval.get() * 1000), Math.round(m.pingSpikeThreshold.get()));
	}

	/** Aus Connection#channelActive (Mixin): Client-Verbindung steht. */
	public static void channelActive(io.netty.channel.Channel channel) {
		if (modules != null) NetBoost.attach(channel);
	}

	// --- NetPlatform ---

	@Override
	public ChannelHandler upgradeDecompress(ChannelHandler vanilla) {
		//? if <1.20.2 {
		if (vanilla.getClass() != CompressionDecoder.class) return null;
		int threshold = NetBoost.intField(vanilla, -1);
		if (threshold < 0) return null;
		return new TrsCompressionDecoder(threshold, NetBoost.boolField(vanilla, true));
		//?} else {
		/*// Ab 1.20.2 entpackt Vanilla selbst schon ohne Kopien (direkt zwischen Netty-Puffern).
		return null;
		*///?}
	}

	@Override
	public boolean inflateAlreadyLean() {
		//? if >=1.20.2 {
		/*return true;
		*///?} else {
		return false;
		//?}
	}

	@Override
	public ChannelHandler upgradeCompress(ChannelHandler vanilla) {
		if (vanilla.getClass() != CompressionEncoder.class) return null;
		int threshold = NetBoost.intField(vanilla, -1);
		return threshold < 0 ? null : new TrsCompressionEncoder(threshold);
	}

	@Override
	public boolean vanillaDecrypt(ChannelHandler handler) {
		return handler.getClass() == CipherDecoder.class;
	}

	@Override
	public long pongTime(Object packet) {
		//? if >=1.20.5 {
		/*if (packet instanceof net.minecraft.network.protocol.ping.ClientboundPongResponsePacket) {
			return ((net.minecraft.network.protocol.ping.ClientboundPongResponsePacket) packet).time();
		}
		*///?} elif >=1.20.2 {
		/*if (packet instanceof net.minecraft.network.protocol.status.ClientboundPongResponsePacket) {
			return ((net.minecraft.network.protocol.status.ClientboundPongResponsePacket) packet).getTime();
		}
		*///?}
		return NONE;
	}

	@Override
	public long gameTime(Object packet) {
		if (!(packet instanceof ClientboundSetTimePacket)) return NONE;
		//? if >=1.21.2 {
		/*return ((ClientboundSetTimePacket) packet).gameTime();
		*///?} else
		return ((ClientboundSetTimePacket) packet).getGameTime();
	}

	@Override
	public long keepAliveId(Object packet) {
		//? if >=1.20.2 {
		/*if (packet instanceof net.minecraft.network.protocol.common.ClientboundKeepAlivePacket) {
			return ((net.minecraft.network.protocol.common.ClientboundKeepAlivePacket) packet).getId();
		}
		*///?} else {
		if (packet instanceof net.minecraft.network.protocol.game.ClientboundKeepAlivePacket) {
			return ((net.minecraft.network.protocol.game.ClientboundKeepAlivePacket) packet).getId();
		}
		//?}
		return NONE;
	}

	@Override
	public boolean activePing() {
		//? if >=1.20.2 {
		/*return true;
		*///?} else {
		return false;
		//?}
	}

	@Override
	public boolean sendPing(long millis) {
		ClientPacketListener listener = Minecraft.getInstance().getConnection();
		// Nur im Spiel-Protokoll: während einer Rekonfiguration (Proxy-Serverwechsel) gibt es das Paket dort nicht.
		if (listener == null || !(listener.getConnection().getPacketListener() instanceof ClientPacketListener)) return false;
		//? if >=1.20.5 {
		/*listener.send(new net.minecraft.network.protocol.ping.ServerboundPingRequestPacket(millis));
		return true;
		*///?} elif >=1.20.2 {
		/*listener.send(new net.minecraft.network.protocol.status.ServerboundPingRequestPacket(millis));
		return true;
		*///?} else {
		return false;
		//?}
	}

	@Override
	public long millis() {
		return Util.getMillis();
	}

	@Override
	public int serverLatency() {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener listener = mc.getConnection();
		if (listener == null || mc.player == null) return -1;
		PlayerInfo info = listener.getPlayerInfo(mc.player.getUUID());
		return info == null ? -1 : info.getLatency();
	}

	@Override
	public boolean multiplayer() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() != null && mc.player != null && mc.getSingleplayerServer() == null;
	}
}
