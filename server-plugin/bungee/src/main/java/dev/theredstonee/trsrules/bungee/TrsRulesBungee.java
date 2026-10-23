package dev.theredstonee.trsrules.bungee;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.theredstonee.trsrules.common.Protocol;
import dev.theredstonee.trsrules.common.RulesConfig;
import dev.theredstonee.trsrules.common.RulesEngine;
import dev.theredstonee.trsrules.common.ScopeKind;
import dev.theredstonee.trsrules.common.ServerRules;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * TRS Server Rules for BungeeCord / Waterfall: rules per backend server ({@code servers:}).
 *
 * <p>The client's hello is answered by the proxy and also forwarded to the backend server, so a TRS Server Rules plugin
 * there can answer too. Rules coming from a backend are intercepted and merged below the proxy rules (the proxy wins
 * for the same module/setting) before they reach the player. On every server switch the proxy re-sends the rules of the
 * new server and replays the hello to the new backend.</p>
 */
public final class TrsRulesBungee extends Plugin implements Listener {
	private ServerRules<ProxiedPlayer> rules;

	@Override
	public void onEnable() {
		rules = new ServerRules<ProxiedPlayer>(new Bridge(), getDataFolder().toPath().resolve("config.yml"), ScopeKind.SERVERS);
		for (String ch : new String[] {Protocol.HELLO, Protocol.HELLO_LEGACY, Protocol.RULES, Protocol.RULES_LEGACY}) {
			getProxy().registerChannel(ch);
		}
		getProxy().getPluginManager().registerListener(this, this);
		getProxy().getPluginManager().registerCommand(this, new RulesCommand());
		for (ProxiedPlayer p : getProxy().getPlayers()) {
			if (p.getServer() != null) rules.engine().onServerSwitch(p);
		}
	}

	@Override
	public void onDisable() {
		for (String ch : new String[] {Protocol.HELLO, Protocol.HELLO_LEGACY, Protocol.RULES, Protocol.RULES_LEGACY}) {
			getProxy().unregisterChannel(ch);
		}
	}

	// ------------------------------------------------------------------ events

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPluginMessage(PluginMessageEvent e) {
		if (e.isCancelled()) return;
		String tag = e.getTag();
		if (Protocol.isHelloChannel(tag) && e.getSender() instanceof ProxiedPlayer) {
			// answer, and let it pass on to the backend (a backend plugin may answer as well)
			rules.engine().onHello((ProxiedPlayer) e.getSender(), e.getData(), tag);
		} else if (Protocol.isRulesChannel(tag) && e.getSender() instanceof Server && e.getReceiver() instanceof ProxiedPlayer) {
			// backend rules: merge below the proxy rules instead of passing them through
			e.setCancelled(true);
			rules.engine().onUpstreamRules((ProxiedPlayer) e.getReceiver(), ((Server) e.getSender()).getInfo().getName(), e.getData());
		}
	}

	@EventHandler
	public void onServerSwitch(ServerSwitchEvent e) {
		ProxiedPlayer p = e.getPlayer();
		rules.engine().onServerSwitch(p);
		// The new backend has not seen the hello (older clients only send it once per connection).
		byte[] hello = rules.engine().helloPayload(p);
		String channel = rules.engine().helloChannel(p);
		Server server = p.getServer();
		if (hello != null && channel != null && server != null) server.sendData(channel, hello);
	}

	@EventHandler
	public void onDisconnect(PlayerDisconnectEvent e) {
		rules.engine().onQuit(e.getPlayer());
	}

	// ------------------------------------------------------------------ command

	private final ServerRules.PlayerLookup<ProxiedPlayer> lookup = new ServerRules.PlayerLookup<ProxiedPlayer>() {
		@Override
		public ProxiedPlayer find(String name) {
			ProxiedPlayer p = getProxy().getPlayer(name);
			if (p != null) return p;
			for (ProxiedPlayer o : getProxy().getPlayers()) if (o.getName().equalsIgnoreCase(name)) return o;
			return null;
		}

		@Override
		public Collection<String> names() {
			List<String> names = new ArrayList<String>();
			for (ProxiedPlayer p : getProxy().getPlayers()) names.add(p.getName());
			return names;
		}
	};

	private final class RulesCommand extends Command implements TabExecutor {
		RulesCommand() {
			super(ServerRules.COMMAND, RulesConfig.ADMIN_PERMISSION);
		}

		@Override
		public void execute(CommandSender sender, String[] args) {
			for (String line : rules.command(args, lookup)) sender.sendMessage(TextComponent.fromLegacyText(line));
		}

		@Override
		public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
			if (!sender.hasPermission(RulesConfig.ADMIN_PERMISSION)) return new ArrayList<String>();
			return rules.complete(args, lookup);
		}
	}

	// ------------------------------------------------------------------ bridge

	private final class Bridge implements RulesEngine.Bridge<ProxiedPlayer> {
		@Override
		public UUID id(ProxiedPlayer player) {
			return player.getUniqueId();
		}

		@Override
		public String name(ProxiedPlayer player) {
			return player.getName();
		}

		@Override
		public boolean hasPermission(ProxiedPlayer player, String permission) {
			return player.hasPermission(permission);
		}

		@Override
		public String scope(ProxiedPlayer player) {
			Server s = player.getServer();
			return s == null ? null : s.getInfo().getName();
		}

		@Override
		public boolean sendRules(ProxiedPlayer player, byte[] payload) {
			player.sendData(Protocol.rulesChannelFor(player.getPendingConnection().getVersion()), payload);
			return true;
		}

		@Override
		public void sendMessage(ProxiedPlayer player, String legacyText) {
			player.sendMessage(TextComponent.fromLegacyText(legacyText));
		}

		@Override
		public void kick(ProxiedPlayer player, String legacyText) {
			player.disconnect(TextComponent.fromLegacyText(legacyText));
		}

		@Override
		public void runLater(ProxiedPlayer player, long delayMillis, Runnable task) {
			getProxy().getScheduler().schedule(TrsRulesBungee.this, task, delayMillis, TimeUnit.MILLISECONDS);
		}

		@Override
		public void run(ProxiedPlayer player, Runnable task) {
			task.run();
		}

		@Override
		public void info(String message) {
			getLogger().info(message);
		}

		@Override
		public void warn(String message) {
			getLogger().warning(message);
		}
	}
}
