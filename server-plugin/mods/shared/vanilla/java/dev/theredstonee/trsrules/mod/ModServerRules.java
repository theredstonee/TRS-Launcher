package dev.theredstonee.trsrules.mod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.theredstonee.trsrules.common.Protocol;
import dev.theredstonee.trsrules.common.RulesConfig;
import dev.theredstonee.trsrules.common.RulesEngine;
import dev.theredstonee.trsrules.common.ScopeKind;
import dev.theredstonee.trsrules.common.ServerRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-independent part of the Fabric / NeoForge / Forge server mods: the {@link RulesEngine.Bridge} for vanilla
 * {@link ServerPlayer}s, the tick scheduler for the missing-client timers, the {@code /trsrules} command and the event
 * entry points the loader glue calls.
 *
 * <p>Rules per world use the dimension id ({@code minecraft:overworld}, {@code minecraft:the_nether}, …; a config
 * section named {@code the_nether} matches too). Vanilla servers have no permission system: the command needs OP level
 * 3 (or the permission {@code trsrules.admin} where the loader has a permission API), the bypass works through
 * {@code bypass-players}, {@code bypass-op-level} or – with a permission API – {@code bypass-permission}.</p>
 *
 * <p>Version differences of Minecraft itself are hidden in {@code Mc} (one source file per Minecraft version range).</p>
 */
public final class ModServerRules {
	public static final String MOD_ID = "trsserverrules";
	public static final String CONFIG_FILE = "trs-server-rules.yml";
	/** OP level for {@code /trsrules} (like other admin commands, e.g. /ban). */
	public static final int ADMIN_OP_LEVEL = 3;
	/** How long to wait for the client to register the rules channel before giving up (rare). */
	private static final int CHANNEL_RETRIES = 10;

	private static final Logger LOG = LoggerFactory.getLogger("TRS Server Rules");

	private final ModPlatform platform;
	private final TickScheduler scheduler = new TickScheduler();
	private final ServerRules<ServerPlayer> rules;
	/** Last known dimension per player (respawns only count as a world change if the dimension differs). */
	private final Map<UUID, String> lastScope = new ConcurrentHashMap<UUID, String>();
	private volatile MinecraftServer server;

	/** Loads (or creates) {@code <configDir>/trs-server-rules.yml}. */
	public ModServerRules(ModPlatform platform, Path configDir) {
		this.platform = platform;
		this.rules = new ServerRules<ServerPlayer>(new Bridge(), configDir.resolve(CONFIG_FILE), ScopeKind.WORLDS);
		LOG.info("TRS Server Rules ({}) ready – config: {}", platform.name(), rules.configFile());
	}

	public RulesEngine<ServerPlayer> engine() {
		return rules.engine();
	}

	// ------------------------------------------------------------------ lifecycle (called by the loader glue)

	public void serverStarting(MinecraftServer server) {
		this.server = server;
	}

	public void serverStopped() {
		server = null;
		scheduler.clear();
		lastScope.clear();
	}

	/** End of every server tick: runs due timers (missing-client checks, channel retries). */
	public void tick() {
		scheduler.tick(System.currentTimeMillis(), new TickScheduler.ErrorHandler() {
			@Override
			public void onError(RuntimeException e) {
				LOG.error("Scheduled task failed", e);
			}
		});
	}

	public void onJoin(ServerPlayer player) {
		lastScope.put(player.getUUID(), Mc.dimension(player));
		engine().onJoin(player);
	}

	public void onQuit(ServerPlayer player) {
		lastScope.remove(player.getUUID());
		engine().onQuit(player.getUUID());
	}

	/** Dimension change or respawn: sends the rules of the new world if the dimension actually changed. */
	public void onWorldChange(ServerPlayer player) {
		String now = Mc.dimension(player);
		String before = lastScope.put(player.getUUID(), now);
		if (!now.equals(before)) engine().onScopeChange(player);
	}

	/** A {@code trsclient:hello} payload arrived (any thread). */
	public void onHello(final ServerPlayer player, final byte[] payload) {
		if (payload == null || payload.length > Protocol.MAX_PAYLOAD) return;
		runOnServer(new Runnable() {
			@Override
			public void run() {
				engine().onHello(current(player), payload, Protocol.HELLO);
			}
		});
	}

	/** The client registered plugin channels (Fabric event); delivers pending rules. */
	public void onChannelsRegistered(final ServerPlayer player) {
		runOnServer(new Runnable() {
			@Override
			public void run() {
				engine().onChannelRegistered(current(player));
			}
		});
	}

	// ------------------------------------------------------------------ command

	public void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal(ServerRules.COMMAND)
				.requires(this::isAdmin)
				.executes(c -> reply(c.getSource(), new String[0]))
				.then(Commands.literal("reload")
						.executes(c -> reply(c.getSource(), new String[] {"reload"})))
				.then(Commands.literal("status")
						.executes(c -> reply(c.getSource(), new String[] {"status"}))
						.then(Commands.argument("player", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(lookup.names(), b))
								.executes(c -> reply(c.getSource(), new String[] {"status", StringArgumentType.getString(c, "player")})))));
	}

	private boolean isAdmin(CommandSourceStack source) {
		if (Mc.hasLevel(source, ADMIN_OP_LEVEL)) return true;
		ServerPlayer p = source.getPlayer();
		if (p == null) return false;
		Boolean allowed = platform.permission(p, RulesConfig.ADMIN_PERMISSION);
		return allowed != null && allowed;
	}

	private int reply(CommandSourceStack source, String[] args) {
		boolean console = source.getEntity() == null;
		for (String line : rules.command(args, lookup)) {
			source.sendSystemMessage(Component.literal(console ? stripColors(line) : line));
		}
		return 1;
	}

	private static String stripColors(String s) {
		return s.replaceAll("§.", "");
	}

	private final ServerRules.PlayerLookup<ServerPlayer> lookup = new ServerRules.PlayerLookup<ServerPlayer>() {
		@Override
		public ServerPlayer find(String name) {
			MinecraftServer s = server;
			return s == null ? null : s.getPlayerList().getPlayerByName(name);
		}

		@Override
		public Collection<String> names() {
			MinecraftServer s = server;
			return s == null ? new ArrayList<String>() : Arrays.asList(s.getPlayerList().getPlayerNamesArray());
		}
	};

	// ------------------------------------------------------------------ helpers

	/** The current entity of a player: respawning replaces the ServerPlayer object, the engine keeps the first one. */
	private ServerPlayer current(ServerPlayer player) {
		MinecraftServer s = server;
		if (s == null) return player;
		ServerPlayer now = s.getPlayerList().getPlayer(player.getUUID());
		return now != null ? now : player;
	}

	private ServerPlayer online(UUID id) {
		MinecraftServer s = server;
		return s == null ? null : s.getPlayerList().getPlayer(id);
	}

	private void runOnServer(Runnable task) {
		MinecraftServer s = server;
		if (s == null || s.isSameThread()) {
			task.run();
		} else {
			s.execute(task);
		}
	}

	/** Some loaders have no "channel registered" event: check again once per second for a few seconds. */
	private void retryWhenRegistered(final UUID id, final int left) {
		if (left <= 0) return;
		scheduler.schedule(1000L, new Runnable() {
			@Override
			public void run() {
				ServerPlayer p = online(id);
				if (p == null) return;
				if (platform.canReceive(p)) engine().onChannelRegistered(p);
				else retryWhenRegistered(id, left - 1);
			}
		});
	}

	// ------------------------------------------------------------------ bridge

	private final class Bridge implements RulesEngine.Bridge<ServerPlayer> {
		@Override
		public UUID id(ServerPlayer player) {
			return player.getUUID();
		}

		@Override
		public String name(ServerPlayer player) {
			return player.getScoreboardName();
		}

		@Override
		public boolean hasPermission(ServerPlayer player, String permission) {
			Boolean allowed = platform.permission(current(player), permission);
			return allowed != null && allowed;
		}

		@Override
		public int opLevel(ServerPlayer player) {
			return Mc.opLevel(current(player));
		}

		@Override
		public String scope(ServerPlayer player) {
			return Mc.dimension(current(player));
		}

		@Override
		public boolean sendRules(ServerPlayer player, byte[] payload) {
			ServerPlayer p = current(player);
			if (!platform.canReceive(p)) {
				retryWhenRegistered(p.getUUID(), CHANNEL_RETRIES);
				return false;
			}
			platform.send(p, payload);
			return true;
		}

		@Override
		public void sendMessage(ServerPlayer player, String legacyText) {
			current(player).sendSystemMessage(Component.literal(legacyText));
		}

		@Override
		public void kick(ServerPlayer player, String legacyText) {
			current(player).connection.disconnect(Component.literal(legacyText));
		}

		@Override
		public void runLater(ServerPlayer player, long delayMillis, Runnable task) {
			scheduler.schedule(delayMillis, task);
		}

		@Override
		public void run(ServerPlayer player, Runnable task) {
			runOnServer(task);
		}

		@Override
		public void info(String message) {
			LOG.info(message);
		}

		@Override
		public void warn(String message) {
			LOG.warn(message);
		}
	}

	/** Logger for the loader glue. */
	public static Logger log() {
		return LOG;
	}
}
