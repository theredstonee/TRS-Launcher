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

import java.util.function.Consumer;

//? if >=1.20.2 {
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.ChannelListManager;
import net.minecraftforge.network.EventNetworkChannel;
//?} else {
/*import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
*///?}

/**
 * Netzwerk-Anbindung der TRS Server Rules (Forge): Kanäle {@code trsclient:rules} (empfangen) und
 * {@code trsclient:hello} (senden); die Regeln selbst wertet {@link ServerRules} in common aus.
 * <ul>
 *   <li>ab 1.20.2: Ereignis-Kanäle aus {@code ChannelBuilder} mit {@code optional()} – Vanilla-Server und Server ohne
 *       die Kanäle werden angenommen. Forge meldet Kanäle eines Clients von sich aus nicht per
 *       {@code minecraft:register} an (nur als Antwort auf eine Anmeldung des Servers), deshalb meldet
 *       {@link #joined} den Regel-Kanal selbst an – sonst schickt ein Bukkit-Server nichts.</li>
 *   <li>1.20/1.20.1: Ereignis-Kanal alter Art ({@code NetworkRegistry.ChannelBuilder}, {@code acceptMissingOr});
 *       die Anmeldung per {@code minecraft:register} erledigt Forge beim Betreten selbst.</li>
 * </ul>
 * Pakete kommen im Netzwerk-Thread an und werden an den Spiel-Thread übergeben. Betreten und Trennen erkennt
 * {@link #tick} an der Verbindung des Spiels (das gibt es in allen Forge-Versionen gleich; ein Server-Wechsel mit
 * neuer Konfigurationsphase bringt eine neue Verbindung und damit ein neues hello).
 * Alles ist abgefangen – eine Ausnahme beim Paket-Verarbeiten würde sonst die Verbindung trennen.
 */
public final class ServerRulesNet {
	private static Consumer<String> log = message -> {
	};
	/** Verbindung beim letzten Tick (ClientPacketListener; null = keine). */
	private static Object connection;

	private ServerRulesNet() {
	}

	/** Einmal beim Start (Mod-Konstruktor): Regeln anlegen und Kanäle registrieren. */
	public static void init(TrsModules modules, String modVersion, String minecraft, Consumer<String> logger) {
		if (logger != null) log = logger;
		ServerRules.init(modules.registry, modVersion, minecraft, "forge", new ServerRules.Platform() {
			@Override
			public void notice(String text) {
				// Rotes [TRS] vor dem Text (Formatcodes im Klartext gehen in allen Versionen).
				String s = String.valueOf((char) 0xA7);
				ChatLines.addMessage(Mc.text(s + "4[TRS] " + s + "r" + text));
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
			register();
		} catch (RuntimeException | LinkageError e) {
			log.accept("TRS Server Rules: Kanäle nicht registriert: " + e);
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

	/** Spielphase betreten: alte Regeln weg, Kanal anmelden, hello senden. */
	static void joined(ClientPacketListener listener) {
		ServerRules r = ServerRules.get();
		if (r == null) return;
		try {
			r.onJoin();
			sendHello(listener, r.helloBytes());
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

	//? if >=1.20.2 {
	private static EventNetworkChannel helloChannel;

	private static void register() {
		ChannelBuilder.named(OnlineHooks.id(ServerRules.PATH_RULES)).networkProtocolVersion(1).optional()
				.eventNetworkChannel().addListener(ServerRulesNet::onRules);
		helloChannel = ChannelBuilder.named(OnlineHooks.id(ServerRules.PATH_HELLO)).networkProtocolVersion(1).optional()
				.eventNetworkChannel();
	}

	// Netzwerk-Thread: Bytes jetzt kopieren, anwenden im Spiel-Thread.
	private static void onRules(CustomPayloadEvent event) {
		CustomPayloadEvent.Context context = event.getSource();
		context.setPacketHandled(true);
		if (!context.isClientSide()) return;
		FriendlyByteBuf buf = event.getPayload();
		byte[] data = buf == null ? null : readAll(buf);
		context.enqueueWork(() -> received(data));
	}

	private static void sendHello(ClientPacketListener listener, byte[] data) {
		if (helloChannel == null) return;
		// Bukkit schickt nur auf Kanäle, die der Client angemeldet hat (doppelt melden schadet nicht).
		ChannelListManager.addChannels(listener.getConnection(), OnlineHooks.id(ServerRules.PATH_RULES),
				OnlineHooks.id(ServerRules.PATH_HELLO));
		helloChannel.send(new FriendlyByteBuf(Unpooled.wrappedBuffer(data)), listener.getConnection());
	}
	//?} else {
	/*private static void register() {
		NetworkRegistry.ChannelBuilder.named(OnlineHooks.id(ServerRules.PATH_RULES))
				.networkProtocolVersion(() -> "1")
				.clientAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
				.serverAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
				.eventNetworkChannel()
				.addListener(ServerRulesNet::onNetworkEvent);
	}

	// Alle Ereignisse des Kanals; nur Pakete vom Server sind Regeln. Netzwerk-Thread → Spiel-Thread.
	private static void onNetworkEvent(NetworkEvent event) {
		if (!(event instanceof NetworkEvent.ServerCustomPayloadEvent) || event.getPayload() == null) return;
		NetworkEvent.Context context = event.getSource().get();
		context.setPacketHandled(true);
		byte[] data = readAll(event.getPayload());
		context.enqueueWork(() -> received(data));
	}

	private static void sendHello(ClientPacketListener listener, byte[] data) {
		listener.send(new ServerboundCustomPayloadPacket(OnlineHooks.id(ServerRules.PATH_HELLO),
				new FriendlyByteBuf(Unpooled.wrappedBuffer(data))));
	}
	*///?}

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
