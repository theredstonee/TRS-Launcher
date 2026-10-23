package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Netzwerk-Anbindung der TRS Server Rules (Forge 1.13.2): Ereignis-Kanal {@code trsclient:rules} (empfangen, nimmt
 * jede Gegenstelle an – auch Vanilla und Server ohne den Kanal) und {@code trsclient:hello} (senden); die Regeln selbst
 * wertet {@link ServerRules} in common aus. Forge 1.13.2 meldet Kanäle nicht per {@code minecraft:register} an (das kam
 * erst mit 1.14) – deshalb schickt {@link #joined} die Anmeldung selbst, sonst schreibt ein Bukkit-Server nichts.
 * Pakete kommen im Netzwerk-Thread an und werden an den Spiel-Thread übergeben; Betreten und Trennen erkennt
 * {@link #tick} an der Verbindung des Spiels.
 * Alles ist abgefangen – eine Ausnahme beim Paket-Verarbeiten würde sonst die Verbindung trennen.
 */
public final class ServerRulesNet {
	private static final ResourceLocation RULES = new ResourceLocation(ServerRules.NAMESPACE, ServerRules.PATH_RULES);
	private static final ResourceLocation HELLO = new ResourceLocation(ServerRules.NAMESPACE, ServerRules.PATH_HELLO);
	private static final ResourceLocation REGISTER = new ResourceLocation("minecraft", "register");

	private static Consumer<String> log = message -> {
	};
	/** Verbindung beim letzten Tick (NetHandlerPlayClient; null = keine). */
	private static Object connection;

	private ServerRulesNet() {
	}

	/** Einmal beim Start (Mod-Konstruktor, bevor Forge die Kanäle sperrt): Regeln anlegen, Kanal registrieren. */
	public static void init(TrsModules modules, String modVersion, Consumer<String> logger) {
		if (logger != null) log = logger;
		ServerRules.init(modules.registry, modVersion, "1.13.2", "forge", new ServerRules.Platform() {
			@Override
			public void notice(String text) {
				Minecraft mc = Minecraft.getInstance();
				if (mc.ingameGUI == null) return;
				// Rotes [TRS] vor dem Text.
				String s = String.valueOf((char) 0xA7);
				mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(s + "4[TRS] " + s + "r" + text));
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
			// Jede Gegenstelle annehmen (fehlender Kanal, Vanilla-Server): die Kanäle sind rein optional.
			NetworkRegistry.ChannelBuilder.named(RULES)
					.networkProtocolVersion(() -> "1")
					.clientAcceptedVersions(version -> true)
					.serverAcceptedVersions(version -> true)
					.eventNetworkChannel()
					.addListener(ServerRulesNet::onNetworkEvent);
		} catch (RuntimeException | LinkageError e) {
			log.accept("TRS Server Rules: Kanal nicht registriert: " + e);
		}
	}

	/** Je Client-Tick: Betreten bzw. Trennen an der Verbindung erkennen. */
	public static void tick(Minecraft mc) {
		Object now = mc.getConnection();
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
			byte[] channels = (RULES + "\0" + HELLO).getBytes(StandardCharsets.UTF_8);
			handler.sendPacket(new CPacketCustomPayload(REGISTER, new PacketBuffer(Unpooled.wrappedBuffer(channels))));
			handler.sendPacket(new CPacketCustomPayload(HELLO, new PacketBuffer(Unpooled.wrappedBuffer(r.helloBytes()))));
		} catch (RuntimeException | LinkageError e) {
			log.accept("TRS Server Rules: hello fehlgeschlagen: " + e);
		}
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

	// Alle Ereignisse des Kanals; nur Pakete vom Server sind Regeln. Netzwerk-Thread → Spiel-Thread.
	private static void onNetworkEvent(NetworkEvent event) {
		if (!(event instanceof NetworkEvent.ServerCustomPayloadEvent) || event.getPayload() == null) return;
		NetworkEvent.Context context = event.getSource().get();
		context.setPacketHandled(true);
		byte[] data = readAll(event.getPayload());
		context.enqueueWork(() -> received(data));
	}

	// Alle restlichen Bytes; mehr als ServerRules.MAX_BYTES wird übersprungen (null).
	private static byte[] readAll(PacketBuffer buf) {
		int n = buf.readableBytes();
		if (n > ServerRules.MAX_BYTES) {
			buf.skipBytes(n);
			return null;
		}
		byte[] out = new byte[n];
		buf.readBytes(out);
		return out;
	}
}
