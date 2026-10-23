package dev.theredstonee.trsclient.net;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.ChatComponentText;

import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Netzwerk-Anbindung der TRS Server Rules (Forge 1.7.10): Plugin-Kanäle alter Art {@code TRS|Rules} (empfangen,
 * FML-Ereignis-Kanal) und {@code TRS|Hello} (senden); die Regeln selbst wertet {@link ServerRules} in common aus. Beim
 * Betreten meldet der Client beide Kanäle per {@code REGISTER} an (doppelt melden schadet nicht) und schickt das hello –
 * beides als Vanilla-Paket an die Verbindung; ein Server ohne das Plugin ignoriert beides.
 * Pakete kommen im Netzwerk-Thread an; 1.7.10 hat keine Aufgaben-Warteschlange im Spiel-Thread, deshalb sammelt
 * {@link #PENDING} sie und {@link #tick} wendet sie an. Betreten und Trennen erkennt {@link #tick} an der Verbindung.
 * Alles ist abgefangen – eine Ausnahme beim Paket-Verarbeiten würde sonst die Verbindung trennen.
 */
public final class ServerRulesNet {
	/** Empfangene Regeln (Netzwerk-Thread → Spiel-Thread); null-Einträge gehen nicht (zu groß = leeres Feld). */
	private static final Queue<byte[]> PENDING = new ConcurrentLinkedQueue<byte[]>();
	private static final byte[] TOO_LARGE = new byte[0];

	private static Consumer<String> log = message -> {
	};
	/** Verbindung beim letzten Tick (NetHandlerPlayClient; null = keine). */
	private static Object connection;

	private ServerRulesNet() {
	}

	/** Einmal beim Start: Regeln anlegen und den Kanal registrieren. */
	public static void init(TrsModules modules, String modVersion, Consumer<String> logger) {
		if (logger != null) log = logger;
		ServerRules.init(modules.registry, modVersion, "1.7.10", "forge", new ServerRules.Platform() {
			@Override
			public void notice(String text) {
				Minecraft mc = Minecraft.getMinecraft();
				if (mc.ingameGUI == null) return;
				// Rotes [TRS] vor dem Text.
				String s = String.valueOf((char) 0xA7);
				mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(s + "4[TRS] " + s + "r" + text));
			}

			@Override
			public void log(String message) {
				log.accept(message);
			}

			@Override
			public boolean supports(Module module) {
				return module.available() && TrsClient.supported(module);
			}
		});
		try {
			NetworkRegistry.INSTANCE.newEventDrivenChannel(ServerRules.LEGACY_RULES).register(new ServerRulesNet.Listener());
		} catch (RuntimeException | LinkageError e) {
			log.accept("TRS Server Rules: Kanal nicht registriert: " + e);
		}
	}

	/** Je Client-Tick: Betreten bzw. Trennen an der Verbindung erkennen, empfangene Regeln anwenden. */
	public static void tick(Minecraft mc) {
		Object now = mc.getNetHandler();
		if (now != connection) {
			if (connection != null) left();
			connection = now;
			if (now != null) joined((NetHandlerPlayClient) now);
		}
		byte[] data;
		while ((data = PENDING.poll()) != null) {
			// Regeln aus einer alten Verbindung nicht mehr anwenden.
			if (connection != null) received(data == TOO_LARGE ? null : data);
		}
	}

	/** Spielphase betreten: alte Regeln weg, Kanäle anmelden, hello senden. */
	static void joined(NetHandlerPlayClient handler) {
		ServerRules r = ServerRules.get();
		if (r == null) return;
		try {
			r.onJoin();
			send(handler, "REGISTER", (ServerRules.LEGACY_RULES + "\0" + ServerRules.LEGACY_HELLO).getBytes(StandardCharsets.UTF_8));
			send(handler, ServerRules.LEGACY_HELLO, r.helloBytes());
		} catch (RuntimeException | LinkageError e) {
			log.accept("TRS Server Rules: hello fehlgeschlagen: " + e);
		}
	}

	// Als Vanilla-Paket (C17PacketCustomPayload über FMLProxyPacket).
	private static void send(NetHandlerPlayClient handler, String channel, byte[] data) {
		handler.addToSendQueue(new FMLProxyPacket(Unpooled.wrappedBuffer(data), channel).toC17Packet());
	}

	/** Verbindung getrennt. */
	static void left() {
		PENDING.clear();
		ServerRules r = ServerRules.get();
		if (r == null) return;
		try {
			r.onDisconnect();
		} catch (RuntimeException e) {
			log.accept("TRS Server Rules: Trennen: " + e);
		}
	}

	/** Regeln empfangen – im Spiel-Thread. */
	static void received(byte[] data) {
		ServerRules r = ServerRules.get();
		if (r == null) return;
		try {
			r.onRules(data);
		} catch (RuntimeException e) {
			log.accept("TRS Server Rules: Regeln nicht angewandt: " + e);
		}
	}

	/** Empfänger am FML-Kanal {@code TRS|Rules} (Netzwerk-Thread). */
	public static final class Listener {
		@SubscribeEvent
		public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
			try {
				FMLProxyPacket packet = event.packet;
				if (packet == null || packet.payload() == null) return;
				byte[] data = readAll(packet.payload());
				PENDING.add(data == null ? TOO_LARGE : data);
			} catch (RuntimeException e) {
				log.accept("TRS Server Rules: Paket nicht gelesen: " + e);
			}
		}
	}

	// Alle lesbaren Bytes (ohne den Puffer zu verändern); mehr als ServerRules.MAX_BYTES wird verworfen (null).
	private static byte[] readAll(ByteBuf buf) {
		int n = buf.readableBytes();
		if (n > ServerRules.MAX_BYTES) return null;
		byte[] out = new byte[n];
		buf.getBytes(buf.readerIndex(), out);
		return out;
	}
}
