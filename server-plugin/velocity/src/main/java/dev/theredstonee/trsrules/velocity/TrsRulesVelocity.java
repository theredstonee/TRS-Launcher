package dev.theredstonee.trsrules.velocity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import dev.theredstonee.trsrules.common.Protocol;
import dev.theredstonee.trsrules.common.RulesConfig;
import dev.theredstonee.trsrules.common.RulesEngine;
import dev.theredstonee.trsrules.common.ScopeKind;
import dev.theredstonee.trsrules.common.ServerRules;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * TRS Server Rules for Velocity 3.x: rules per backend server ({@code servers:}).
 *
 * <p>The client's hello is answered by the proxy and also forwarded to the backend server, so a TRS Server Rules plugin
 * there can answer too. Rules coming from a backend are intercepted and merged below the proxy rules (the proxy wins
 * for the same module/setting). On every server switch the proxy re-sends the rules of the new server and replays the
 * hello to the new backend.</p>
 */
public final class TrsRulesVelocity {
	private static final ChannelIdentifier HELLO = MinecraftChannelIdentifier.from(Protocol.HELLO);
	private static final ChannelIdentifier HELLO_LEGACY = new LegacyChannelIdentifier(Protocol.HELLO_LEGACY);
	private static final ChannelIdentifier RULES = MinecraftChannelIdentifier.from(Protocol.RULES);
	private static final ChannelIdentifier RULES_LEGACY = new LegacyChannelIdentifier(Protocol.RULES_LEGACY);
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

	private final ProxyServer server;
	private final Logger logger;
	private final Path dataDirectory;
	private ServerRules<Player> rules;

	@Inject
	public TrsRulesVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
		this.server = server;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	@Subscribe
	public void onInit(ProxyInitializeEvent e) {
		rules = new ServerRules<Player>(new Bridge(), dataDirectory.resolve("config.yml"), ScopeKind.SERVERS);
		server.getChannelRegistrar().register(HELLO, HELLO_LEGACY, RULES, RULES_LEGACY);
		server.getCommandManager().register(server.getCommandManager().metaBuilder(ServerRules.COMMAND).build(), new RulesCommand());
	}

	// ------------------------------------------------------------------ events

	@Subscribe
	public void onPluginMessage(PluginMessageEvent e) {
		if (rules == null || !e.getResult().isAllowed()) return;
		String id = e.getIdentifier().getId();
		if (Protocol.isHelloChannel(id) && e.getSource() instanceof Player) {
			// answer, and let it pass on to the backend (a backend plugin may answer as well)
			rules.engine().onHello((Player) e.getSource(), e.getData(), id);
		} else if (Protocol.isRulesChannel(id) && e.getSource() instanceof ServerConnection && e.getTarget() instanceof Player) {
			// backend rules: merge below the proxy rules instead of passing them through
			e.setResult(PluginMessageEvent.ForwardResult.handled());
			ServerConnection from = (ServerConnection) e.getSource();
			rules.engine().onUpstreamRules((Player) e.getTarget(), from.getServerInfo().getName(), e.getData());
		}
	}

	@Subscribe
	public void onServerConnected(ServerPostConnectEvent e) {
		if (rules == null) return;
		Player p = e.getPlayer();
		rules.engine().onServerSwitch(p);
		// The new backend has not seen the hello (older clients only send it once per connection).
		byte[] hello = rules.engine().helloPayload(p);
		String channel = rules.engine().helloChannel(p);
		Optional<ServerConnection> current = p.getCurrentServer();
		if (hello != null && channel != null && current.isPresent()) {
			current.get().sendPluginMessage(Protocol.HELLO_LEGACY.equals(channel) ? HELLO_LEGACY : HELLO, hello);
		}
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent e) {
		if (rules != null) rules.engine().onQuit(e.getPlayer());
	}

	// ------------------------------------------------------------------ command

	private final ServerRules.PlayerLookup<Player> lookup = new ServerRules.PlayerLookup<Player>() {
		@Override
		public Player find(String name) {
			return server.getPlayer(name).orElse(null);
		}

		@Override
		public Collection<String> names() {
			List<String> names = new ArrayList<String>();
			for (Player p : server.getAllPlayers()) names.add(p.getUsername());
			return names;
		}
	};

	private final class RulesCommand implements SimpleCommand {
		@Override
		public void execute(Invocation invocation) {
			CommandSource src = invocation.source();
			for (String line : rules.command(invocation.arguments(), lookup)) src.sendMessage(LEGACY.deserialize(line));
		}

		@Override
		public List<String> suggest(Invocation invocation) {
			return rules.complete(invocation.arguments(), lookup);
		}

		@Override
		public boolean hasPermission(Invocation invocation) {
			return invocation.source().hasPermission(RulesConfig.ADMIN_PERMISSION);
		}
	}

	// ------------------------------------------------------------------ bridge

	private final class Bridge implements RulesEngine.Bridge<Player> {
		@Override
		public UUID id(Player player) {
			return player.getUniqueId();
		}

		@Override
		public String name(Player player) {
			return player.getUsername();
		}

		@Override
		public boolean hasPermission(Player player, String permission) {
			return player.hasPermission(permission);
		}

		@Override
		public String scope(Player player) {
			return player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
		}

		@Override
		public boolean sendRules(Player player, byte[] payload) {
			String ch = Protocol.rulesChannelFor(player.getProtocolVersion().getProtocol());
			player.sendPluginMessage(Protocol.RULES.equals(ch) ? RULES : RULES_LEGACY, payload);
			return true;
		}

		@Override
		public void sendMessage(Player player, String legacyText) {
			player.sendMessage(LEGACY.deserialize(legacyText));
		}

		@Override
		public void kick(Player player, String legacyText) {
			Component reason = LEGACY.deserialize(legacyText);
			player.disconnect(reason);
		}

		@Override
		public void runLater(Player player, long delayMillis, Runnable task) {
			server.getScheduler().buildTask(TrsRulesVelocity.this, task).delay(delayMillis, TimeUnit.MILLISECONDS).schedule();
		}

		@Override
		public void run(Player player, Runnable task) {
			task.run();
		}

		@Override
		public void info(String message) {
			logger.info(message);
		}

		@Override
		public void warn(String message) {
			logger.warn(message);
		}
	}
}
