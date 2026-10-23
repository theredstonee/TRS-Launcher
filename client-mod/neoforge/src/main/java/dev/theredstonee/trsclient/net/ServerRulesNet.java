package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.function.Consumer;

//? if >=1.20.5 {
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
//?} elif >=1.20.4 {
/*import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.registration.IPayloadRegistrar;
*///?} else {
/*import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.neoforge.network.NetworkEvent;
import net.neoforged.neoforge.network.NetworkRegistry;
*///?}

/**
 * Netzwerk-Anbindung der TRS Server Rules (NeoForge): Kanäle {@code trsclient:rules} (empfangen) und
 * {@code trsclient:hello} (senden); die Regeln selbst wertet {@link ServerRules} in common aus.
 * <ul>
 *   <li>ab 1.20.5: optionale Payloads über {@code RegisterPayloadHandlersEvent} (Empfang im Spiel-Thread),</li>
 *   <li>1.20.4: optionale Payloads über {@code RegisterPayloadHandlerEvent} (Empfang im Netzwerk-Thread → Spiel-Thread),</li>
 *   <li>1.20.2/1.20.3: Ereignis-Kanal alter Art, der fehlende Gegenstellen und Vanilla-Server zulässt. Deren Payload
 *       liest vor den Daten eine VarInt-Kennung – die gibt das Ereignis als {@code getLoginIndex()} heraus, daraus
 *       werden die rohen Bytes wieder zusammengesetzt.</li>
 * </ul>
 * Optional heißt: Server ohne das Plugin (Vanilla, Paper, andere NeoForge-Server) lassen die Verbindung ganz normal zu.
 * NeoForge meldet die Kanäle selbst per {@code minecraft:register} an. Das hello geht direkt an die Verbindung: NeoForge
 * würde es sonst verwerfen, solange der Server den Kanal nicht angemeldet hat (kommt bei Paper erst nach dem Betreten,
 * hinter einem Proxy gar nicht) – ein Server ohne Plugin ignoriert es einfach.
 * Alles ist abgefangen – eine Ausnahme beim Paket-Verarbeiten würde sonst die Verbindung trennen.
 */
public final class ServerRulesNet {
	private static Consumer<String> log = message -> {
	};

	private ServerRulesNet() {
	}

	/** Einmal beim Start: Regeln anlegen, Kanäle registrieren, Betreten/Trennen verfolgen. */
	public static void init(IEventBus modBus, TrsModules modules, String modVersion, String minecraft, Consumer<String> logger) {
		if (logger != null) log = logger;
		ServerRules.init(modules.registry, modVersion, minecraft, "neoforge", new ServerRules.Platform() {
			@Override
			public void notice(String text) {
				// Rotes [TRS] vor dem Text (Formatcodes im Klartext gehen in allen Versionen).
				String s = String.valueOf((char) 0xA7);
				ChatLines.addMessage(Component.literal(s + "4[TRS] " + s + "r" + text));
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
			register(modBus);
			NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> joined());
			NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> left());
		} catch (RuntimeException | LinkageError e) {
			log.accept("TRS Server Rules: Kanäle nicht registriert: " + e);
		}
	}

	/** Spielphase betreten (auch nach einem Server-Wechsel mit neuer Konfigurationsphase): alte Regeln weg, hello senden. */
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

	private static void sendHello(byte[] data) {
		ClientPacketListener listener = Minecraft.getInstance().getConnection();
		if (listener == null) return;
		// An NeoForges Kanal-Prüfung vorbei (siehe Klassenkommentar); der Codec ist registriert (playToServer).
		listener.getConnection().send(new ServerboundCustomPayloadPacket(new ServerRulesPayload.Hello(data)));
	}

	//? if >=1.20.5 {
	private static void register(IEventBus modBus) {
		modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
			// optional: Gegenstelle ohne die Kanäle ist kein Grund, die Verbindung abzulehnen.
			PayloadRegistrar registrar = event.registrar("1").optional();
			// Standard-Thread ist der Spiel-Thread.
			registrar.playToClient(ServerRulesPayload.Rules.TYPE, ServerRulesPayload.Rules.CODEC,
					(payload, context) -> received(payload.data()));
			// Nur zum Senden (Codec); auf einem Server ohne TRS Server Rules passiert damit nichts.
			registrar.playToServer(ServerRulesPayload.Hello.TYPE, ServerRulesPayload.Hello.CODEC, (payload, context) -> {
			});
		});
	}
	//?} elif >=1.20.4 {
	/*private static void register(IEventBus modBus) {
		modBus.addListener(RegisterPayloadHandlerEvent.class, event -> {
			IPayloadRegistrar registrar = event.registrar(ServerRules.NAMESPACE).versioned("1").optional();
			// 1.20.4 ruft den Empfänger im Netzwerk-Thread auf → in den Spiel-Thread geben.
			registrar.play(ServerRulesPayload.Rules.ID, ServerRulesPayload.Rules::read, handler -> handler
					.client((payload, context) -> context.workHandler().execute(() -> received(payload.data()))));
			registrar.play(ServerRulesPayload.Hello.ID, ServerRulesPayload.Hello::read, handler -> handler
					.server((payload, context) -> {
					}));
		});
	}
	*///?} else {
	/*private static void register(IEventBus modBus) {
		NetworkRegistry.ChannelBuilder.named(dev.theredstonee.trsclient.online.OnlineHooks.id(ServerRules.PATH_RULES))
				.networkProtocolVersion(() -> "1")
				.clientAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
				.serverAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
				.eventNetworkChannel()
				.addListener(ServerRulesNet::onNetworkEvent);
	}

	// Alle Ereignisse des Kanals; nur Pakete vom Server sind Regeln.
	private static void onNetworkEvent(NetworkEvent event) {
		if (!(event instanceof NetworkEvent.ServerCustomPayloadEvent) || event.getPayload() == null) return;
		NetworkEvent.Context context = event.getSource();
		context.setPacketHandled(true);
		// Die VarInt-Kennung, die NeoForge vorn abgeschnitten hat, gehört zu den Daten.
		FriendlyByteBuf whole = new FriendlyByteBuf(Unpooled.buffer());
		whole.writeVarInt(event.getLoginIndex());
		whole.writeBytes(event.getPayload());
		byte[] data = ServerRulesPayload.readAll(whole);
		context.enqueueWork(() -> received(data));
	}
	*///?}
}
