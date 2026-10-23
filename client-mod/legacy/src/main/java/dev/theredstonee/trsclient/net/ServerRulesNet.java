package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Netzwerk-Anbindung der TRS Server Rules (Legacy-Forge 1.8.9–1.12.2): Plugin-Kanäle alter Art {@code TRS|Rules}
 * (empfangen, FML-Ereignis-Kanal) und {@code TRS|Hello} (senden); die Regeln selbst wertet {@link ServerRules} in common
 * aus. Beim Betreten meldet der Client beide Kanäle per {@code REGISTER} an (FML tut das gegenüber Vanilla-/Bukkit-
 * Servern nicht verlässlich; doppelt melden schadet nicht) und schickt das hello – beides als Vanilla-Paket direkt an
 * die Verbindung. Ein Server ohne das Plugin ignoriert beides. Pakete kommen im Netzwerk-Thread an und werden an den
 * Spiel-Thread übergeben; Betreten und Trennen erkennt {@link #tick} an der Verbindung des Spiels.
 * Alles ist abgefangen – eine Ausnahme beim Paket-Verarbeiten würde sonst die Verbindung trennen.
 */
public final class ServerRulesNet {
	private static Consumer<String> log = message -> {
	};
	/** Verbindung beim letzten Tick (NetHandlerPlayClient; null = keine). */
	private static Object connection;

	private ServerRulesNet() {
	}

	/** Einmal beim Start (preInit): Regeln anlegen und den Kanal registrieren. */
	public static void init(TrsModules modules, String modVersion, String minecraft, Consumer<String> logger) {
		if (logger != null) log = logger;
		ServerRules.init(modules.registry, modVersion, minecraft, "forge", new ServerRules.Platform() {
			@Override
			public void notice(String text) {
				Minecraft mc = Mc.mc();
				if (mc.ingameGUI == null) return;
				// Rotes [TRS] vor dem Text.
				String s = String.valueOf((char) 0xA7);
				String line = s + "4[TRS] " + s + "r" + text;
				//? if >=1.9 {
				/*mc.ingameGUI.getChatGUI().printChatMessage(new net.minecraft.util.text.TextComponentString(line));
				*///?} else
				mc.ingameGUI.getChatGUI().printChatMessage(new net.minecraft.util.ChatComponentText(line));
			}

			@Override
			public void log(String message) {
				log.accept(message);
			}

			@Override
			public boolean supports(Module module) {
				return module.available() && new dev.theredstonee.trsclient.screen.TrsMenuHost(null).supports(module);
			}
		});
		try {
			NetworkRegistry.INSTANCE.newEventDrivenChannel(ServerRules.LEGACY_RULES).register(new ServerRulesNet.Listener());
		} catch (RuntimeException | LinkageError e) {
			log.accept("TRS Server Rules: Kanal nicht registriert: " + e);
		}
	}

	/** Je Client-Tick: Betreten bzw. Trennen an der Verbindung erkennen. */
	public static void tick(Minecraft mc) {
		Object now = Mc.connection();
		if (now == connection) return;
		if (connection != null) left();
		connection = now;
		if (now != null) joined((NetHandlerPlayClient) now);
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

	// Als Vanilla-Paket (FMLProxyPacket baut das passende C17-/CPacketCustomPayload jeder Version).
	private static void send(NetHandlerPlayClient handler, String channel, byte[] data) {
		FMLProxyPacket packet = new FMLProxyPacket(new PacketBuffer(Unpooled.wrappedBuffer(data)), channel);
		handler.getNetworkManager().sendPacket(packet.toC17Packet());
	}

	/** Verbindung getrennt. */
	static void left() {
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

	/** Empfänger am FML-Kanal {@code TRS|Rules} (Netzwerk-Thread → Spiel-Thread). */
	public static final class Listener {
		@SubscribeEvent
		public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
			try {
				//? if >=1.9 {
				/*FMLProxyPacket packet = event.getPacket();
				*///?} else
				FMLProxyPacket packet = event.packet;
				if (packet == null || packet.payload() == null) return;
				byte[] data = readAll(packet.payload());
				Minecraft.getMinecraft().addScheduledTask(() -> received(data));
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
