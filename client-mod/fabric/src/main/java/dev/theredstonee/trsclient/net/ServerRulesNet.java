package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;

//? if >=1.20.5 {
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//?} elif >=1.16.4 {
/*import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
*///?} elif >=1.16 {
/*import net.fabricmc.fabric.api.network.ClientSidePacketRegistry;
import net.minecraft.network.FriendlyByteBuf;
*///?} else {
/*import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
*///?}

/**
 * Netzwerk-Anbindung der TRS Server Rules (Fabric): Kanäle {@code trsclient:rules} (empfangen) und
 * {@code trsclient:hello} (senden) über die Fabric Networking API; die Regeln selbst wertet
 * {@link ServerRules} in common aus.
 * <ul>
 *   <li>ab 1.20.5: Payload-Typen ({@link ServerRulesPayload}), Empfang läuft schon im Spiel-Thread,</li>
 *   <li>1.14.4–1.20.4 (außer 1.16.2/1.16.3): fabric-networking-api-v1 mit Puffern, Empfang im Netzwerk-Thread
 *       → in den Spiel-Thread geben,</li>
 *   <li>1.16.2/1.16.3: deren Fabric API hat nur fabric-networking-v0 (keine Verbindungs-Events) → Betreten/Trennen
 *       erkennt {@link #tick} an der Verbindung.</li>
 * </ul>
 * Alles ist abgefangen – eine Ausnahme beim Paket-Verarbeiten würde sonst die Verbindung trennen.
 */
public final class ServerRulesNet {
	private static Consumer<String> log = message -> {
	};

	private ServerRulesNet() {
	}

	/** Einmal beim Start: Regeln anlegen und Kanäle registrieren. */
	public static void init(TrsModules modules, String modVersion, String minecraft, Consumer<String> logger) {
		if (logger != null) log = logger;
		ServerRules.init(modules.registry, modVersion, minecraft, "fabric", new ServerRules.Platform() {
			@Override
			public void notice(String text) {
				// Rotes [TRS] vor dem Text (Formatcodes im Klartext gehen in allen Versionen).
				String s = String.valueOf((char) 0xA7);
				dev.theredstonee.trsclient.compat.ChatLines.addMessage(
						dev.theredstonee.trsclient.compat.Mc.text(s + "4[TRS] " + s + "r" + text));
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

	/** Spielphase betreten: alte Regeln weg, hello senden. */
	static void joined() {
		ServerRules r = ServerRules.get();
		if (r == null) return;
		try {
			r.onJoin();
			sendHello(r.helloBytes());
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

	//? if >=1.20.5 {
	private static void register() {
		//? if >=26.1 {
		/*PayloadTypeRegistry.clientboundPlay().register(ServerRulesPayload.Rules.TYPE, ServerRulesPayload.Rules.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ServerRulesPayload.Hello.TYPE, ServerRulesPayload.Hello.CODEC);
		*///?} else {
		PayloadTypeRegistry.playS2C().register(ServerRulesPayload.Rules.TYPE, ServerRulesPayload.Rules.CODEC);
		PayloadTypeRegistry.playC2S().register(ServerRulesPayload.Hello.TYPE, ServerRulesPayload.Hello.CODEC);
		//?}
		// Ab 1.20.5 ruft Fabric den Empfänger im Spiel-Thread auf.
		ClientPlayNetworking.registerGlobalReceiver(ServerRulesPayload.Rules.TYPE, (payload, context) -> received(payload.data()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> joined());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ServerRulesNet::left));
	}

	private static void sendHello(byte[] data) {
		ClientPlayNetworking.send(new ServerRulesPayload.Hello(data));
	}
	//?} elif >=1.16.4 {
	/*private static void register() {
		ClientPlayNetworking.registerGlobalReceiver(dev.theredstonee.trsclient.online.OnlineHooks.id(ServerRules.PATH_RULES),
				(client, handler, buf, sender) -> {
					// Netzwerk-Thread: Bytes jetzt kopieren, anwenden im Spiel-Thread.
					byte[] data = readAll(buf);
					client.execute(() -> received(data));
				});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> joined());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ServerRulesNet::left));
	}

	private static void sendHello(byte[] data) {
		ClientPlayNetworking.send(dev.theredstonee.trsclient.online.OnlineHooks.id(ServerRules.PATH_HELLO),
				new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)));
	}
	*///?} elif >=1.16 {
	/*private static void register() {
		ClientSidePacketRegistry.INSTANCE.register(dev.theredstonee.trsclient.online.OnlineHooks.id(ServerRules.PATH_RULES),
				(context, buf) -> {
					byte[] data = readAll(buf);
					context.getTaskQueue().execute(() -> received(data));
				});
	}

	private static void sendHello(byte[] data) {
		ClientSidePacketRegistry.INSTANCE.sendToServer(dev.theredstonee.trsclient.online.OnlineHooks.id(ServerRules.PATH_HELLO),
				new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)));
	}
	*///?} else {
	/*private static void register() {
		ClientPlayNetworking.registerGlobalReceiver(dev.theredstonee.trsclient.online.OnlineHooks.id(ServerRules.PATH_RULES),
				(client, handler, buf, sender) -> {
					byte[] data = readAll(buf);
					client.execute(() -> received(data));
				});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> joined());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ServerRulesNet::left));
	}

	private static void sendHello(byte[] data) {
		ClientPlayNetworking.send(dev.theredstonee.trsclient.online.OnlineHooks.id(ServerRules.PATH_HELLO),
				new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)));
	}
	*///?}

	//? if <1.20.5 {
	/*// Alle restlichen Bytes; mehr als ServerRules.MAX_BYTES wird übersprungen (null).
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
	*///?}

	//? if >=1.16 && <1.16.4 {
	/*private static Object connection;
	*///?}

	/**
	 * Je Client-Tick. Nur 1.16.2/1.16.3 (ohne Verbindungs-Events): Betreten bzw. Trennen an der Verbindung erkennen
	 * ({@code getConnection()} gibt es erst mit Spielfigur – dann hat Fabric die Kanäle schon angemeldet).
	 */
	public static void tick(Minecraft mc) {
		//? if >=1.16 && <1.16.4 {
		/*Object now = mc.getConnection();
		if (now == connection) return;
		if (connection != null) left();
		connection = now;
		if (now != null) joined();
		*///?}
	}
}
