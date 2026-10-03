package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.connect.FastSwitch;
import dev.theredstonee.trsclient.core.connect.ServerPacks;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.multiplayer.ServerData;
//? if >=1.9 {
/*import net.minecraft.network.play.client.CPacketResourcePackStatus;
import net.minecraft.network.play.server.SPacketJoinGame;
import net.minecraft.network.play.server.SPacketResourcePackSend;
import net.minecraft.network.play.server.SPacketRespawn;
*///?} else {
import net.minecraft.network.play.client.C19PacketResourcePackStatus;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S48PacketResourcePackSend;
//?}

import java.lang.reflect.Field;

/**
 * Schnell verbinden für Forge 1.8.9–1.12.2 (ohne Mixins): ein Handler direkt vor Minecrafts Paket-Handler sieht
 * Server-Ressourcenpakete (merken, vorgeladene Datei liegt schon an Vanillas Platz {@code server-resource-packs/<sha1>})
 * und die Status-Meldungen des Clients. Schickt der Server nach einem Wechsel im Proxy-Netzwerk genau das aktive Paket
 * noch einmal, antwortet der Handler „angenommen – geladen“ und reicht das Paket nicht weiter – Vanilla würde sonst das
 * Paket entfernen und zweimal alle Ressourcen neu laden. Außerdem: Beginn eines Serverwechsels (JoinGame/Respawn).
 */
final class LegacyPacks extends ChannelDuplexHandler {
	static final String NAME = "trsclient_packs";
	private static volatile Field actionField;

	/** Vor "packet_handler" einhängen (mehrfach aufrufen schadet nicht). */
	static void attach(Channel ch) {
		if (ch == null || !ch.isOpen()) return;
		final ChannelPipeline p = ch.pipeline();
		if (p.get(NAME) != null || p.get("packet_handler") == null) return;
		ch.eventLoop().execute(new Runnable() {
			@Override
			public void run() {
				try {
					if (p.get(NAME) == null && p.get("packet_handler") != null) p.addBefore("packet_handler", NAME, new LegacyPacks());
				} catch (RuntimeException ignored) {
					// Verbindung schon zu
				}
			}
		});
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		try {
			//? if >=1.9 {
			/*if (msg instanceof SPacketJoinGame || msg instanceof SPacketRespawn) FastSwitch.beginIfIdle();
			if (msg instanceof SPacketResourcePackSend) {
				SPacketResourcePackSend p = (SPacketResourcePackSend) msg;
				if (ServerPacks.onPush(server(), null, p.getURL(), p.getHash())) {
					reply(ctx, p.getHash());
					return;
				}
			}
			*///?} else {
			if (msg instanceof S01PacketJoinGame || msg instanceof S07PacketRespawn) FastSwitch.beginIfIdle();
			if (msg instanceof S48PacketResourcePackSend) {
				S48PacketResourcePackSend p = (S48PacketResourcePackSend) msg;
				if (ServerPacks.onPush(server(), null, p.getURL(), p.getHash())) {
					reply(ctx, p.getHash());
					return;
				}
			}
			//?}
		} catch (RuntimeException | LinkageError ignored) {
			// Vanilla macht weiter
		}
		super.channelRead(ctx, msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		try {
			//? if >=1.9 {
			/*if (msg instanceof CPacketResourcePackStatus) ServerPacks.onStatus(null, action(msg));
			*///?} else
			if (msg instanceof C19PacketResourcePackStatus) ServerPacks.onStatus(null, action(msg));
		} catch (RuntimeException | LinkageError ignored) {
			// nur Beobachtung
		}
		super.write(ctx, msg, promise);
	}

	/** „Angenommen“ + „geladen“ an den Server – wie Vanilla nach dem (hier unnötigen) Neuladen. */
	private static void reply(ChannelHandlerContext ctx, String hash) {
		//? if >=1.10 {
		/*ctx.writeAndFlush(new CPacketResourcePackStatus(CPacketResourcePackStatus.Action.ACCEPTED));
		ctx.writeAndFlush(new CPacketResourcePackStatus(CPacketResourcePackStatus.Action.SUCCESSFULLY_LOADED));
		*///?} elif >=1.9 {
		/*ctx.writeAndFlush(new CPacketResourcePackStatus(hash, CPacketResourcePackStatus.Action.ACCEPTED));
		ctx.writeAndFlush(new CPacketResourcePackStatus(hash, CPacketResourcePackStatus.Action.SUCCESSFULLY_LOADED));
		*///?} else {
		ctx.writeAndFlush(new C19PacketResourcePackStatus(hash, C19PacketResourcePackStatus.Action.ACCEPTED));
		ctx.writeAndFlush(new C19PacketResourcePackStatus(hash, C19PacketResourcePackStatus.Action.SUCCESSFULLY_LOADED));
		//?}
	}

	private static String server() {
		ServerData d = Mc.mc().getCurrentServerData();
		return d == null ? null : d.serverIP;
	}

	/** Status einer Meldung (privates Feld, per Typ gesucht – die Release-Jars haben SRG-Namen). */
	private static String action(Object packet) {
		try {
			Field f = actionField;
			if (f == null) {
				for (Field c : packet.getClass().getDeclaredFields()) {
					if (c.getType().isEnum()) {
						c.setAccessible(true);
						f = c;
						break;
					}
				}
				if (f == null) return null;
				actionField = f;
			}
			Object v = f.get(packet);
			return v instanceof Enum ? ((Enum<?>) v).name() : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
