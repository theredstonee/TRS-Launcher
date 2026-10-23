package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import dev.theredstonee.trsclient.online.OnlineHooks;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;

import java.util.function.Consumer;

//? if >=1.18 {
/*import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
*///?} elif >=1.17 {
/*import net.minecraftforge.fmllegacy.network.NetworkEvent;
import net.minecraftforge.fmllegacy.network.NetworkRegistry;
*///?} else {
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
//?}

/**
 * Netzwerk-Anbindung der TRS Server Rules (Forge 1.14.4–1.19.4): Ereignis-Kanal {@code trsclient:rules} (empfangen,
 * nimmt jede Gegenstelle an – auch Vanilla und Server ohne den Kanal) und {@code trsclient:hello} (senden); die Regeln
 * selbst wertet {@link ServerRules} in common aus. Forge meldet registrierte Kanäle beim Betreten selbst per
 * {@code minecraft:register} an. Pakete kommen im Netzwerk-Thread an und werden an den Spiel-Thread übergeben;
 * Betreten und Trennen erkennt {@link #tick} an der Verbindung des Spiels.
 * Alles ist abgefangen – eine Ausnahme beim Paket-Verarbeiten würde sonst die Verbindung trennen.
 */
public final class ServerRulesNet {
	private static Consumer<String> log = message -> {
	};
	/** Verbindung beim letzten Tick (ClientPacketListener; null = keine). */
	private static Object connection;

	private ServerRulesNet() {
	}

	/** Einmal beim Start (Mod-Konstruktor): Regeln anlegen und den Kanal registrieren. */
	public static void init(TrsModules modules, String modVersion, String minecraft, Consumer<String> logger) {
		if (logger != null) log = logger;
		ServerRules.init(modules.registry, modVersion, minecraft, "forge", new ServerRules.Platform() {
			@Override
			public void notice(String text) {
				// Rotes [TRS] vor dem Text (Formatcodes im Klartext gehen in allen Versionen).
				String s = String.valueOf((char) 0xA7);
				ChatLines.addMessage(Mc.literal(s + "4[TRS] " + s + "r" + text));
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
			// Jede Gegenstelle annehmen (fehlender Kanal, Vanilla-Server): die Kanäle sind rein optional.
			NetworkRegistry.ChannelBuilder.named(OnlineHooks.id(ServerRules.PATH_RULES))
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
		if (now != null) joined((ClientPacketListener) now);
	}

	/** Spielphase betreten: alte Regeln weg, hello senden. */
	static void joined(ClientPacketListener listener) {
		ServerRules r = ServerRules.get();
		if (r == null) return;
		try {
			r.onJoin();
			listener.send(new ServerboundCustomPayloadPacket(OnlineHooks.id(ServerRules.PATH_HELLO),
					new FriendlyByteBuf(Unpooled.wrappedBuffer(r.helloBytes()))));
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
	private static byte[] readAll(FriendlyByteBuf buf) {
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
