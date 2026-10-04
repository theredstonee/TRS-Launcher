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
		dev.theredstonee.trsclient.core.connect.FastConnect.install(m, dev.theredstonee.trsclient.core.i18n.I18n.configDir(), NetBoost.logger());
		// Server-Ressourcenpakete: Speicher-Ort und Größengrenze von Vanilla in dieser Version.
		java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
		headers.put("User-Agent", "Minecraft Java");
		//? if >=1.20.3 {
		dev.theredstonee.trsclient.core.connect.ServerPacks.Era era = dev.theredstonee.trsclient.core.connect.ServerPacks.Era.DOWNLOADS_UUID;
		long maxPack = 262144000L;
		//?} elif >=1.19 {
		/*dev.theredstonee.trsclient.core.connect.ServerPacks.Era era = dev.theredstonee.trsclient.core.connect.ServerPacks.Era.URL_SHA1_URL;
		long maxPack = 262144000L;
		*///?} elif >=1.18 {
		/*dev.theredstonee.trsclient.core.connect.ServerPacks.Era era = dev.theredstonee.trsclient.core.connect.ServerPacks.Era.URL_SHA1_RAW;
		long maxPack = 262144000L;
		*///?} elif >=1.16 {
		/*dev.theredstonee.trsclient.core.connect.ServerPacks.Era era = dev.theredstonee.trsclient.core.connect.ServerPacks.Era.URL_SHA1_RAW;
		long maxPack = 104857600L;
		*///?} else {
		/*dev.theredstonee.trsclient.core.connect.ServerPacks.Era era = dev.theredstonee.trsclient.core.connect.ServerPacks.Era.URL_SHA1_RAW;
		long maxPack = 52428800L;
		*///?}
		dev.theredstonee.trsclient.core.connect.ServerPacks.setup(dev.theredstonee.trsclient.core.i18n.I18n.configDir(), era, maxPack, headers,
				dev.theredstonee.trsclient.core.connect.FastConnect.fastSwitchSwitch(m), dev.theredstonee.trsclient.core.connect.FastConnect.packsSwitch(m));
		dev.theredstonee.trsclient.core.connect.FastConnect.startup();
	}

	/** Je Client-Tick. */
	public static void tick(Minecraft mc) {
		TrsModules m = modules;
		if (m == null) return;
		ClientPacketListener listener = mc.getConnection();
		NetBoost.tick(listener == null ? null : NetBoost.channelOf(listener.getConnection()), m.ping.isEnabled(),
				Math.round(m.pingInterval.get() * 1000), Math.round(m.pingSpikeThreshold.get()));
		// Schneller Serverwechsel: fertig, sobald die Welt da und kein Lade-/Paket-Bildschirm mehr offen ist.
		net.minecraft.client.gui.screens.Screen screen = dev.theredstonee.trsclient.compat.Mc.screen();
		//? if >=26.2 {
		/*boolean overlay = mc.gui.overlay() != null;
		*///?} else
		boolean overlay = mc.getOverlay() != null;
		dev.theredstonee.trsclient.core.connect.FastSwitch.tick(mc.level != null && mc.player != null && !overlay
				&& (screen == null || !dev.theredstonee.trsclient.menus.VanillaMenus.isLoading(screen)));
	}

	/** Aus Connection#channelActive (Mixin): Client-Verbindung steht. */
	public static void channelActive(io.netty.channel.Channel channel) {
		if (modules != null) NetBoost.attach(channel);
	}

	// --- NetPlatform ---

	@Override
	public ChannelHandler upgradeDecompress(ChannelHandler vanilla) {
		//? if <1.20.2 {
		/*if (vanilla.getClass() != CompressionDecoder.class) return null;
		int threshold = NetBoost.intField(vanilla, -1);
		if (threshold < 0) return null;
		return new TrsCompressionDecoder(threshold, NetBoost.boolField(vanilla, true));
		*///?} else {
		// Ab 1.20.2 entpackt Vanilla selbst schon ohne Kopien (direkt zwischen Netty-Puffern).
		return null;
		//?}
	}

	@Override
	public boolean inflateAlreadyLean() {
		//? if >=1.20.2 {
		return true;
		//?} else {
		/*return false;
		*///?}
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
		if (packet instanceof net.minecraft.network.protocol.ping.ClientboundPongResponsePacket) {
			return ((net.minecraft.network.protocol.ping.ClientboundPongResponsePacket) packet).time();
		}
		//?} elif >=1.20.2 {
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
		if (packet instanceof net.minecraft.network.protocol.common.ClientboundKeepAlivePacket) {
			return ((net.minecraft.network.protocol.common.ClientboundKeepAlivePacket) packet).getId();
		}
		//?} else {
		/*if (packet instanceof net.minecraft.network.protocol.game.ClientboundKeepAlivePacket) {
			return ((net.minecraft.network.protocol.game.ClientboundKeepAlivePacket) packet).getId();
		}
		*///?}
		return NONE;
	}

	@Override
	public boolean activePing() {
		//? if >=1.20.2 {
		return true;
		//?} else {
		/*return false;
		*///?}
	}

	@Override
	public boolean sendPing(long millis) {
		ClientPacketListener listener = Minecraft.getInstance().getConnection();
		// Nur im Spiel-Protokoll: während einer Rekonfiguration (Proxy-Serverwechsel) gibt es das Paket dort nicht.
		if (listener == null || !(listener.getConnection().getPacketListener() instanceof ClientPacketListener)) return false;
		//? if >=1.20.5 {
		listener.send(new net.minecraft.network.protocol.ping.ServerboundPingRequestPacket(millis));
		return true;
		//?} elif >=1.20.2 {
		/*listener.send(new net.minecraft.network.protocol.status.ServerboundPingRequestPacket(millis));
		return true;
		*///?} else {
		/*return false;
		*///?}
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
