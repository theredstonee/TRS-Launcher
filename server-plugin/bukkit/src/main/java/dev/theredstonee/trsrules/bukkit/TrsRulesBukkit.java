package dev.theredstonee.trsrules.bukkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import dev.theredstonee.trsrules.common.Protocol;
import dev.theredstonee.trsrules.common.RulesConfig;
import dev.theredstonee.trsrules.common.RulesEngine;
import dev.theredstonee.trsrules.common.ScopeKind;
import dev.theredstonee.trsrules.common.ServerRules;

/**
 * TRS Server Rules for Spigot / Paper / Purpur 1.8.8 – 26.x and Folia.
 *
 * <p>Only API that exists unchanged since 1.8.8 is used. Plugin channels: both names are registered where the server
 * allows it ({@code trsclient:*} everywhere, {@code TRS|*} only before 1.13 – newer servers reject legacy names),
 * so ViaVersion setups with old and new clients both work. Rules are only sent on a channel the player registered.</p>
 */
public final class TrsRulesBukkit extends JavaPlugin implements Listener, PluginMessageListener, TabExecutor {
	private ServerRules<Player> rules;
	private Scheduling scheduling;
	/** Outgoing rules channels this server accepted, preferred first. */
	private final List<String> rulesChannels = new ArrayList<String>();

	@Override
	public void onEnable() {
		scheduling = createScheduling();
		rules = new ServerRules<Player>(new Bridge(), getDataFolder().toPath().resolve("config.yml"), ScopeKind.WORLDS);

		Messenger messenger = getServer().getMessenger();
		for (String ch : new String[] {Protocol.RULES, Protocol.RULES_LEGACY}) {
			try {
				messenger.registerOutgoingPluginChannel(this, ch);
				rulesChannels.add(ch);
			} catch (IllegalArgumentException notAllowedHere) {
				// e.g. "TRS|Rules" on 1.13+ (namespaced channels only)
			}
		}
		for (String ch : new String[] {Protocol.HELLO, Protocol.HELLO_LEGACY}) {
			try {
				messenger.registerIncomingPluginChannel(this, ch, this);
			} catch (IllegalArgumentException notAllowedHere) {
				// see above
			}
		}
		getServer().getPluginManager().registerEvents(this, this);
		PluginCommand cmd = getCommand(ServerRules.COMMAND);
		if (cmd != null) {
			cmd.setExecutor(this);
			cmd.setTabCompleter(this);
		}
		// /reload or late loading: players that are already online
		for (Player p : getServer().getOnlinePlayers()) rules.engine().onJoin(p);
		getLogger().info("Channels: out " + rulesChannels + (isFolia() ? ", Folia scheduler" : ""));
	}

	@Override
	public void onDisable() {
		Messenger messenger = getServer().getMessenger();
		messenger.unregisterOutgoingPluginChannel(this);
		messenger.unregisterIncomingPluginChannel(this);
	}

	private static boolean isFolia() {
		try {
			Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	private Scheduling createScheduling() {
		if (isFolia()) {
			try {
				return (Scheduling) Class.forName("dev.theredstonee.trsrules.bukkit.folia.FoliaScheduling")
						.getConstructor(org.bukkit.plugin.Plugin.class).newInstance(this);
			} catch (Exception e) {
				throw new IllegalStateException("Folia detected, but the Folia scheduler bridge could not be loaded", e);
			}
		}
		return new BukkitScheduling(this);
	}

	// ------------------------------------------------------------------ events

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent e) {
		rules.engine().onJoin(e.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent e) {
		rules.engine().onQuit(e.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldChange(PlayerChangedWorldEvent e) {
		rules.engine().onScopeChange(e.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRegisterChannel(PlayerRegisterChannelEvent e) {
		if (Protocol.isRulesChannel(e.getChannel())) rules.engine().onChannelRegistered(e.getPlayer());
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (Protocol.isHelloChannel(channel)) rules.engine().onHello(player, message, channel);
	}

	// ------------------------------------------------------------------ command

	private final ServerRules.PlayerLookup<Player> lookup = new ServerRules.PlayerLookup<Player>() {
		@Override
		public Player find(String name) {
			Player p = getServer().getPlayerExact(name);
			return p != null ? p : getServer().getPlayer(name);
		}

		@Override
		public Collection<String> names() {
			List<String> names = new ArrayList<String>();
			for (Player p : getServer().getOnlinePlayers()) names.add(p.getName());
			return names;
		}
	};

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission(RulesConfig.ADMIN_PERMISSION)) {
			sender.sendMessage("§cYou do not have permission (" + RulesConfig.ADMIN_PERMISSION + ").");
			return true;
		}
		for (String line : rules.command(args, lookup)) sender.sendMessage(line);
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!sender.hasPermission(RulesConfig.ADMIN_PERMISSION)) return Collections.emptyList();
		return rules.complete(args, lookup);
	}

	// ------------------------------------------------------------------ bridge

	private final class Bridge implements RulesEngine.Bridge<Player> {
		@Override
		public UUID id(Player player) {
			return player.getUniqueId();
		}

		@Override
		public String name(Player player) {
			return player.getName();
		}

		@Override
		public boolean hasPermission(Player player, String permission) {
			return player.hasPermission(permission);
		}

		@Override
		public String scope(Player player) {
			return player.getWorld().getName();
		}

		@Override
		public boolean sendRules(Player player, byte[] payload) {
			// Bukkit rule: only send on channels the client registered (otherwise the server drops the message).
			Collection<String> listening = player.getListeningPluginChannels();
			for (String ch : rulesChannels) {
				if (listening.contains(ch)) {
					player.sendPluginMessage(TrsRulesBukkit.this, ch, payload);
					return true;
				}
			}
			return false;
		}

		@Override
		public void sendMessage(Player player, String legacyText) {
			for (String line : legacyText.split("\n")) player.sendMessage(line);
		}

		@Override
		public void kick(Player player, String legacyText) {
			player.kickPlayer(legacyText);
		}

		@Override
		public void runLater(Player player, long delayMillis, Runnable task) {
			scheduling.runLater(player, delayMillis / 50L, task);
		}

		@Override
		public void run(Player player, Runnable task) {
			scheduling.run(player, task);
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
