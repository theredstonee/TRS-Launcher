package dev.theredstonee.trsrules.sponge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.entity.ChangeEntityWorldEvent;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.network.EngineConnection;
import org.spongepowered.api.network.channel.ChannelBuf;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.api.network.channel.raw.play.RawPlayDataHandler;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.Identifiable;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import com.google.inject.Inject;

import dev.theredstonee.trsrules.common.Protocol;
import dev.theredstonee.trsrules.common.RulesConfig;
import dev.theredstonee.trsrules.common.RulesEngine;
import dev.theredstonee.trsrules.common.ScopeKind;
import dev.theredstonee.trsrules.common.ServerRules;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * TRS Server Rules for Sponge API 8+ (rules per world, matched by world key, e.g. {@code minecraft:the_nether} or
 * {@code the_nether}).
 *
 * <p>Sponge only allows namespaced channels, so only {@code trsclient:hello}/{@code trsclient:rules} are registered
 * (Sponge API 8+ means Minecraft 1.16.5+, where clients use those names anyway).</p>
 */
@Plugin("trsserverrules")
public final class TrsRulesSponge {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
	/** How long to wait for the client to register the rules channel before giving up (rare). */
	private static final int CHANNEL_RETRIES = 10;

	private final PluginContainer container;
	private final Logger logger;
	private final Path configDir;
	private volatile ServerRules<ServerPlayer> rules;
	private volatile RawDataChannel rulesChannel;

	@Inject
	public TrsRulesSponge(PluginContainer container, Logger logger, @ConfigDir(sharedRoot = false) Path configDir) {
		this.container = container;
		this.logger = logger;
		this.configDir = configDir;
	}

	@Listener
	public void onConstruct(ConstructPluginEvent e) {
		rules = new ServerRules<ServerPlayer>(new Bridge(), configDir.resolve("config.yml"), ScopeKind.WORLDS);
	}

	@Listener
	public void onRegisterChannels(RegisterChannelEvent e) {
		RawDataChannel hello = e.register(ResourceKey.of("trsclient", "hello"), RawDataChannel.class);
		rulesChannel = e.register(ResourceKey.of("trsclient", "rules"), RawDataChannel.class);
		hello.play().addHandler(helloHandler());
	}

	/**
	 * The hello handler. Its parameter type changed between API 8 (EngineConnection) and API 10+
	 * (EngineConnectionState), so it is a dynamic proxy that works with both.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private RawPlayDataHandler helloHandler() {
		InvocationHandler h = (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				switch (method.getName()) {
					case "equals": return proxy == args[0];
					case "hashCode": return System.identityHashCode(proxy);
					default: return "TRS Server Rules hello handler";
				}
			}
			if ("handlePayload".equals(method.getName()) && args != null && args.length == 2) {
				ChannelBuf buf = (ChannelBuf) args[0];
				ServerPlayer player = playerOf(args[1]);
				ServerRules<ServerPlayer> r = rules;
				if (player != null && r != null) {
					int n = buf.available();
					if (n > 0 && n <= Protocol.MAX_PAYLOAD) {
						byte[] data = buf.readBytes(n);
						Sponge.server().scheduler().submit(Task.builder().plugin(container)
								.execute(() -> r.engine().onHello(player, data, Protocol.HELLO)).build());
					}
				}
			}
			return null;
		};
		return (RawPlayDataHandler) java.lang.reflect.Proxy.newProxyInstance(TrsRulesSponge.class.getClassLoader(),
				new Class<?>[] {RawPlayDataHandler.class}, h);
	}

	/** API 8: ServerPlayerConnection#player(); API 10+: ServerConnectionState.Game#player(). */
	private static ServerPlayer playerOf(Object connection) {
		for (String type : new String[] {"org.spongepowered.api.network.ServerPlayerConnection",
				"org.spongepowered.api.network.ServerConnectionState$Game"}) {
			try {
				Class<?> c = Class.forName(type);
				if (!c.isInstance(connection)) continue;
				for (Method m : c.getMethods()) {
					if ("player".equals(m.getName()) && m.getParameterCount() == 0) {
						Object p = m.invoke(connection);
						if (p instanceof ServerPlayer) return (ServerPlayer) p;
					}
				}
			} catch (ReflectiveOperationException | LinkageError ignored) {
				// type not in this API version
			}
		}
		return null;
	}

	/** ServerPlayer#connection() changed its return type over the API versions; both are an EngineConnection. */
	private static EngineConnection connectionOf(ServerPlayer player) {
		try {
			Object c = ServerPlayer.class.getMethod("connection").invoke(player);
			return c instanceof EngineConnection ? (EngineConnection) c : null;
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ events

	@Listener
	public void onJoin(ServerSideConnectionEvent.Join e) {
		if (rules != null) rules.engine().onJoin(e.player());
	}

	/** API 8: Disconnect#player(); API 11+: Disconnect#profile() (Optional) – read reflectively. */
	@Listener
	public void onDisconnect(ServerSideConnectionEvent.Disconnect e) {
		if (rules == null) return;
		UUID id = uuidOf(e);
		if (id != null) rules.engine().onQuit(id);
	}

	private static UUID uuidOf(ServerSideConnectionEvent.Disconnect event) {
		for (String name : new String[] {"player", "profile"}) {
			try {
				Object o = ServerSideConnectionEvent.Disconnect.class.getMethod(name).invoke(event);
				if (o instanceof Optional) o = ((Optional<?>) o).orElse(null);
				if (o instanceof Identifiable) return ((Identifiable) o).uniqueId();
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// not available in this API version
			}
		}
		return null;
	}

	@Listener
	public void onWorldChange(ChangeEntityWorldEvent.Post e) {
		if (rules != null && e.entity() instanceof ServerPlayer) rules.engine().onScopeChange((ServerPlayer) e.entity());
	}

	// ------------------------------------------------------------------ command

	private final ServerRules.PlayerLookup<ServerPlayer> lookup = new ServerRules.PlayerLookup<ServerPlayer>() {
		@Override
		public ServerPlayer find(String name) {
			return Sponge.server().player(name).orElse(null);
		}

		@Override
		public Collection<String> names() {
			List<String> names = new ArrayList<String>();
			for (ServerPlayer p : Sponge.server().onlinePlayers()) names.add(p.name());
			return names;
		}
	};

	@Listener
	public void onRegisterCommands(RegisterCommandEvent<Command.Parameterized> e) {
		Parameter.Value<ServerPlayer> playerParam = Parameter.player().key("player").optional().build();
		Command.Parameterized reload = Command.builder()
				.permission(RulesConfig.ADMIN_PERMISSION)
				.executor(ctx -> reply(ctx, new String[] {"reload"}))
				.build();
		Command.Parameterized status = Command.builder()
				.permission(RulesConfig.ADMIN_PERMISSION)
				.addParameter(playerParam)
				.executor(ctx -> {
					Optional<ServerPlayer> p = ctx.one(playerParam);
					return reply(ctx, p.isPresent() ? new String[] {"status", p.get().name()} : new String[] {"status"});
				})
				.build();
		Command.Parameterized root = Command.builder()
				.permission(RulesConfig.ADMIN_PERMISSION)
				.shortDescription(Component.text("Reload TRS Server Rules or show a player's TRS Client status"))
				.addChild(reload, "reload")
				.addChild(status, "status")
				.executor(ctx -> reply(ctx, new String[0]))
				.build();
		e.register(container, root, ServerRules.COMMAND);
	}

	private CommandResult reply(CommandContext ctx, String[] args) {
		ServerRules<ServerPlayer> r = rules;
		if (r != null) {
			for (String line : r.command(args, lookup)) ctx.cause().audience().sendMessage(LEGACY.deserialize(line));
		}
		return CommandResult.success();
	}

	// ------------------------------------------------------------------ bridge

	private final class Bridge implements RulesEngine.Bridge<ServerPlayer> {
		@Override
		public UUID id(ServerPlayer player) {
			return player.uniqueId();
		}

		@Override
		public String name(ServerPlayer player) {
			return player.name();
		}

		@Override
		public boolean hasPermission(ServerPlayer player, String permission) {
			return player.hasPermission(permission);
		}

		@Override
		public String scope(ServerPlayer player) {
			return player.world().key().asString();
		}

		@Override
		public boolean sendRules(ServerPlayer player, byte[] payload) {
			RawDataChannel ch = rulesChannel;
			if (ch == null) return false;
			EngineConnection conn = connectionOf(player);
			if (conn == null || !ch.play().isSupportedBy(conn)) {
				retryWhenRegistered(player, CHANNEL_RETRIES);
				return false;
			}
			ch.play().sendTo(player, buf -> buf.writeBytes(payload));
			return true;
		}

		/** Sponge has no "channel registered" event: check again once per second for a few seconds. */
		private void retryWhenRegistered(ServerPlayer player, int left) {
			if (left <= 0) return;
			runLater(player, 1000L, () -> {
				ServerRules<ServerPlayer> r = rules;
				RawDataChannel ch = rulesChannel;
				EngineConnection conn = connectionOf(player);
				if (r == null || ch == null || conn == null || !player.isOnline()) return;
				if (ch.play().isSupportedBy(conn)) r.engine().onChannelRegistered(player);
				else retryWhenRegistered(player, left - 1);
			});
		}

		@Override
		public void sendMessage(ServerPlayer player, String legacyText) {
			player.sendMessage(LEGACY.deserialize(legacyText));
		}

		@Override
		public void kick(ServerPlayer player, String legacyText) {
			player.kick(LEGACY.deserialize(legacyText));
		}

		@Override
		public void runLater(ServerPlayer player, long delayMillis, Runnable task) {
			Sponge.server().scheduler().submit(Task.builder().plugin(container)
					.delay(delayMillis, TimeUnit.MILLISECONDS).execute(task).build());
		}

		@Override
		public void run(ServerPlayer player, Runnable task) {
			Server server = Sponge.server();
			if (server.onMainThread()) {
				task.run();
			} else {
				server.scheduler().submit(Task.builder().plugin(container).execute(task).build());
			}
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
