package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.net.NetBoost;
import dev.theredstonee.trsclient.core.net.NetPlatform;
import dev.theredstonee.trsclient.core.perf.LatencyPanels;
import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.core.perf.Performance;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.NettyCompressionDecoder;
import net.minecraft.network.NettyCompressionEncoder;
import net.minecraft.network.NettyEncryptingDecoder;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
//? if >=1.9 {
/*import net.minecraft.network.play.server.SPacketKeepAlive;
import net.minecraft.network.play.server.SPacketTimeUpdate;
*///?} else {
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
//?}

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

/**
 * Netzwerk-Optimierung und Ping für Forge 1.8.9–1.12.2 (ohne Mixins): Die TRS-Handler kommen per Abfrage an die
 * Verbindung – schon während „Verbinde …“ (bevor die Verschlüsselung startet) und danach je Tick. Eigene Ping-Anfragen
 * gibt es im Protokoll dieser Versionen nicht: Ping = Wert der Spielerliste, dazu Keepalive-Jitter und TPS-Schätzung.
 */
public final class NetHooks implements NetPlatform {
	private static final NetHooks INSTANCE = new NetHooks();
	private static TrsModules modules;
	private static Field connectingField;
	private static boolean connectingFieldSearched;

	private NetHooks() {
	}

	public static NetHooks get() {
		return INSTANCE;
	}

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

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END || modules == null) return;
		TrsModules m = modules;
		NetHandlerPlayClient net = Mc.connection();
		Channel ch = net == null || net.getNetworkManager() == null ? null : net.getNetworkManager().channel();
		NetBoost.tick(ch, m.ping.isEnabled(), Math.round(m.pingInterval.get() * 1000), Math.round(m.pingSpikeThreshold.get()));
	}

	/** Je Bild, solange „Verbinde …“ offen ist: Handler früh einhängen (vor der Verschlüsselung). */
	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		if (event.phase != TickEvent.Phase.START || modules == null) return;
		Object screen = Minecraft.getMinecraft().currentScreen;
		if (!(screen instanceof GuiConnecting)) return;
		NetworkManager nm = connecting((GuiConnecting) screen);
		if (nm != null && nm.channel() != null) NetBoost.attach(nm.channel());
	}

	/** Verbindung des „Verbinde …“-Bildschirms (privates Feld, per Typ gesucht). */
	private static NetworkManager connecting(GuiConnecting screen) {
		try {
			if (!connectingFieldSearched) {
				connectingFieldSearched = true;
				for (Field f : GuiConnecting.class.getDeclaredFields()) {
					if (!Modifier.isStatic(f.getModifiers()) && f.getType() == NetworkManager.class) {
						f.setAccessible(true);
						connectingField = f;
						break;
					}
				}
			}
			return connectingField == null ? null : (NetworkManager) connectingField.get(screen);
		} catch (Throwable t) {
			return null;
		}
	}

	// --- NetPlatform ---

	@Override
	public ChannelHandler upgradeDecompress(ChannelHandler vanilla) {
		if (vanilla.getClass() != NettyCompressionDecoder.class) return null;
		int threshold = NetBoost.intField(vanilla, -1);
		return threshold < 0 ? null : new TrsCompressionDecoder(threshold);
	}

	@Override
	public ChannelHandler upgradeCompress(ChannelHandler vanilla) {
		if (vanilla.getClass() != NettyCompressionEncoder.class) return null;
		int threshold = NetBoost.intField(vanilla, -1);
		return threshold < 0 ? null : new TrsCompressionEncoder(threshold);
	}

	@Override
	public boolean vanillaDecrypt(ChannelHandler handler) {
		return handler.getClass() == NettyEncryptingDecoder.class;
	}

	@Override
	public long pongTime(Object packet) {
		return NONE;
	}

	@Override
	public long gameTime(Object packet) {
		//? if >=1.9 {
		/*return packet instanceof SPacketTimeUpdate ? ((SPacketTimeUpdate) packet).getTotalWorldTime() : NONE;
		*///?} else
		return packet instanceof S03PacketTimeUpdate ? ((S03PacketTimeUpdate) packet).getTotalWorldTime() : NONE;
	}

	@Override
	public long keepAliveId(Object packet) {
		//? if >=1.9 {
		/*return packet instanceof SPacketKeepAlive ? ((SPacketKeepAlive) packet).getId() : NONE;
		*///?} else
		return packet instanceof S00PacketKeepAlive ? ((S00PacketKeepAlive) packet).func_149134_c() : NONE;
	}

	@Override
	public boolean activePing() {
		return false;
	}

	@Override
	public void sendPing(long millis) {
		// Gibt es in diesen Versionen nicht (siehe Klassenbeschreibung).
	}

	@Override
	public long millis() {
		return System.nanoTime() / 1_000_000L;
	}

	@Override
	public int serverLatency() {
		EntityPlayerSP player = Mc.player();
		NetHandlerPlayClient net = Mc.connection();
		if (player == null || net == null) return -1;
		NetworkPlayerInfo info = net.getPlayerInfo(player.getUniqueID());
		return info == null ? -1 : info.getResponseTime();
	}

	@Override
	public boolean multiplayer() {
		Minecraft mc = Minecraft.getMinecraft();
		return Mc.connection() != null && Mc.player() != null && !mc.isSingleplayer();
	}
}
