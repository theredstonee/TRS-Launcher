package dev.theredstonee.trsrules.common;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Platform-independent core: tracks players, answers hellos with their rules, re-sends rules on world/server changes
 * and reloads, and applies the missing-client policy.
 *
 * <p>A platform (Bukkit, BungeeCord, Velocity, Sponge, later server mods) implements {@link Bridge} for its player
 * type {@code P} and forwards events: {@link #onJoin}, {@link #onQuit}, {@link #onHello}, {@link #onScopeChange},
 * {@link #onChannelRegistered}; proxies additionally {@link #onServerSwitch} and {@link #onUpstreamRules}.
 * All methods are thread-safe.</p>
 *
 * @param <P> platform player type
 */
public final class RulesEngine<P> {

	/** What the engine needs from a platform. */
	public interface Bridge<P> {
		UUID id(P player);

		String name(P player);

		boolean hasPermission(P player, String permission);

		/**
		 * Minecraft operator level of the player (0–4) for {@code bypass-op-level}, or {@code -1} if the platform does
		 * not know it (plugin platforms – they use {@code bypass-permission}).
		 */
		default int opLevel(P player) {
			return -1;
		}

		/** Current world (game server) or backend server name (proxy); {@code null} if unknown. */
		String scope(P player);

		/**
		 * Sends a {@code rules} payload on the channel the player can receive.
		 *
		 * @return {@code false} if the player cannot receive it yet (channel not registered) – the engine retries on
		 *         {@link RulesEngine#onChannelRegistered}
		 */
		boolean sendRules(P player, byte[] payload);

		/** Chat message; {@code legacyText} uses {@code §} color codes. */
		void sendMessage(P player, String legacyText);

		/** Disconnects the player; {@code legacyText} uses {@code §} color codes. */
		void kick(P player, String legacyText);

		/** Runs {@code task} after {@code delayMillis} on the thread that may act on the player (main/region thread). */
		void runLater(P player, long delayMillis, Runnable task);

		/** Runs {@code task} on the thread that may act on the player (now if already there). */
		void run(P player, Runnable task);

		void info(String message);

		void warn(String message);
	}

	/** Per-player state. */
	static final class Session<P> {
		final P player;
		final long joinedAt;
		volatile Hello hello;
		volatile byte[] helloPayload;
		volatile String helloChannel;
		/** Rules are due but the player has not registered the rules channel yet. */
		volatile boolean pending;
		/** Rules sent by a backend server plugin (proxies only) and the server that sent them. */
		volatile RuleSet upstream;
		volatile String upstreamFrom;
		volatile String lastSent;
		volatile boolean missingHandled;

		Session(P player, long joinedAt) {
			this.player = player;
			this.joinedAt = joinedAt;
		}
	}

	private final Bridge<P> bridge;
	private final LongSupplier clock;
	private final ConcurrentHashMap<UUID, Session<P>> sessions = new ConcurrentHashMap<UUID, Session<P>>();
	private volatile RulesConfig config;

	public RulesEngine(Bridge<P> bridge, RulesConfig config) {
		this(bridge, config, new LongSupplier() {
			@Override
			public long getAsLong() {
				return System.currentTimeMillis();
			}
		});
	}

	public RulesEngine(Bridge<P> bridge, RulesConfig config, LongSupplier clock) {
		this.bridge = bridge;
		this.config = config;
		this.clock = clock;
	}

	public RulesConfig config() {
		return config;
	}

	// ------------------------------------------------------------------ events

	/** Player joined (game server) / first connected to a backend server (proxy). Starts the missing-client timer. */
	public void onJoin(final P player) {
		final Session<P> s = new Session<P>(player, clock.getAsLong());
		sessions.put(bridge.id(player), s);
		MissingClientPolicy policy = config.missingClient();
		if (policy.action() != MissingClientPolicy.Action.IGNORE) {
			bridge.runLater(player, policy.timeoutMillis(), new Runnable() {
				@Override
				public void run() {
					checkMissing(s);
				}
			});
		}
	}

	/** Player left: forget everything about them. */
	public void onQuit(P player) {
		sessions.remove(bridge.id(player));
	}

	/** Player left (for platforms whose disconnect event only carries the profile). */
	public void onQuit(UUID playerId) {
		sessions.remove(playerId);
	}

	/**
	 * A payload arrived on a hello channel. Answers with the player's rules (always, also when empty).
	 *
	 * @return true if it was a valid TRS Client hello
	 */
	public boolean onHello(P player, byte[] payload, String channel) {
		Hello hello = Hello.parse(payload);
		if (hello == null) return false;
		Session<P> s = session(player);
		boolean first = s.hello == null;
		s.hello = hello;
		s.helloPayload = payload.clone();
		s.helloChannel = channel;
		if (first) bridge.info(bridge.name(player) + " uses the TRS Client (" + hello.describe() + ")");
		sendRules(player);
		return true;
	}

	/** The player changed worlds (game server): send the rules of the new world. */
	public void onScopeChange(P player) {
		Session<P> s = sessions.get(bridge.id(player));
		if (s != null && s.hello != null) sendRules(player);
	}

	/**
	 * Proxy: the player is now on a (new) backend server. Starts a session on the first connect; on later switches the
	 * proxy rules of the new server are sent (rules of the previous backend no longer apply).
	 */
	public void onServerSwitch(P player) {
		Session<P> s = sessions.get(bridge.id(player));
		if (s == null) {
			onJoin(player);
			return;
		}
		if (s.hello != null) sendRules(player);
	}

	/**
	 * Proxy: a backend server sent rules to the player (from a TRS Server Rules plugin on the backend). They are merged
	 * below the proxy rules (proxy wins) and sent again. The caller must not forward the original message.
	 * They apply only while the player is on {@code fromServer}.
	 */
	public void onUpstreamRules(P player, String fromServer, byte[] payload) {
		if (payload == null || payload.length > Protocol.MAX_PAYLOAD) return;
		RuleSet upstream;
		try {
			upstream = RuleSet.fromJson(new String(payload, Protocol.UTF8));
		} catch (IllegalArgumentException e) {
			return;
		}
		Session<P> s = session(player);
		s.upstream = upstream;
		s.upstreamFrom = fromServer;
		sendRules(player);
	}

	/** The player registered plugin channels: deliver rules that could not be sent before. */
	public void onChannelRegistered(P player) {
		Session<P> s = sessions.get(bridge.id(player));
		if (s != null && s.pending && s.hello != null) sendRules(player);
	}

	/** New config: re-send rules to every TRS Client player. */
	public void reload(RulesConfig newConfig) {
		this.config = newConfig;
		for (final Session<P> s : sessions.values()) {
			if (s.hello == null) continue;
			bridge.run(s.player, new Runnable() {
				@Override
				public void run() {
					sendRules(s.player);
				}
			});
		}
	}

	// ------------------------------------------------------------------ rules

	/** Effective rules for a player right now (empty for bypass players). */
	public RuleSet effectiveRules(P player) {
		return effectiveRules(player, sessions.get(bridge.id(player)));
	}

	private RuleSet effectiveRules(P player, Session<P> s) {
		RulesConfig cfg = config;
		if (isBypassed(player, cfg)) return RuleSet.empty();
		String scope = bridge.scope(player);
		RuleSet own = cfg.resolve(scope);
		RuleSet upstream = currentUpstream(s, scope);
		return upstream == null ? own : upstream.overlay(own);
	}

	/** Backend rules if they came from the server the player is on now. */
	private static RuleSet currentUpstream(Session<?> s, String scope) {
		if (s == null || s.upstream == null) return null;
		String from = s.upstreamFrom;
		return from == null || scope == null || from.equals(scope) ? s.upstream : null;
	}

	public boolean isBypassed(P player) {
		return isBypassed(player, config);
	}

	private boolean isBypassed(P player, RulesConfig cfg) {
		return bypassReason(player, cfg) != null;
	}

	/** Why the player is exempt from all rules, or {@code null} if not. */
	private String bypassReason(P player, RulesConfig cfg) {
		String perm = cfg.bypassPermission();
		if (perm != null && bridge.hasPermission(player, perm)) return perm;
		if (cfg.isBypassPlayer(bridge.name(player), bridge.id(player))) return "bypass-players";
		int level = cfg.bypassOpLevel();
		if (level > 0) {
			int op = bridge.opLevel(player);
			if (op >= level) return "OP level " + op + ", bypass-op-level: " + level;
		}
		return null;
	}

	/** Sends the current rules if the player has sent a hello. */
	public void sendRules(P player) {
		Session<P> s = sessions.get(bridge.id(player));
		if (s == null || s.hello == null) return;
		String json = effectiveRules(player, s).toJson();
		byte[] bytes = json.getBytes(Protocol.UTF8);
		if (bytes.length > Protocol.MAX_PAYLOAD) {
			bridge.warn("Rules for " + bridge.name(player) + " are " + bytes.length + " bytes (limit " + Protocol.MAX_PAYLOAD
					+ ") – not sent. Shorten the config.");
			return;
		}
		if (bridge.sendRules(player, bytes)) {
			s.pending = false;
			s.lastSent = json;
		} else {
			s.pending = true;
		}
	}

	// ------------------------------------------------------------------ missing client

	/** Timer expired: apply the missing-client action if no hello arrived (package-private for tests). */
	void checkMissing(Session<P> s) {
		P player = s.player;
		if (sessions.get(bridge.id(player)) != s) return; // left (or rejoined) meanwhile
		if (s.hello != null || s.missingHandled) return;
		RulesConfig cfg = config;
		if (isBypassed(player, cfg)) return;
		s.missingHandled = true;
		MissingClientPolicy policy = cfg.missingClient();
		switch (policy.action()) {
			case NOTIFY:
				bridge.sendMessage(player, Text.colorize(policy.notifyMessage()));
				break;
			case KICK:
				bridge.info("Kicking " + bridge.name(player) + ": no TRS Client hello within " + policy.timeoutSeconds() + " s");
				bridge.kick(player, Text.colorize(policy.kickMessage()));
				break;
			default:
				break;
		}
	}

	// ------------------------------------------------------------------ queries

	private Session<P> session(P player) {
		UUID id = bridge.id(player);
		Session<P> s = sessions.get(id);
		if (s == null) {
			// Hello before join event (should not happen) or plugin loaded late: track without timer.
			s = new Session<P>(player, clock.getAsLong());
			Session<P> prev = sessions.putIfAbsent(id, s);
			if (prev != null) s = prev;
		}
		return s;
	}

	/** True if the player has answered with a valid TRS Client hello. */
	public boolean hasTrsClient(P player) {
		Session<P> s = sessions.get(bridge.id(player));
		return s != null && s.hello != null;
	}

	/** The hello of a player, or {@code null}. */
	public Hello hello(P player) {
		Session<P> s = sessions.get(bridge.id(player));
		return s == null ? null : s.hello;
	}

	/** Raw hello payload and its channel (proxies replay it to a new backend server), or {@code null}. */
	public byte[] helloPayload(P player) {
		Session<P> s = sessions.get(bridge.id(player));
		return s == null || s.helloPayload == null ? null : s.helloPayload.clone();
	}

	public String helloChannel(P player) {
		Session<P> s = sessions.get(bridge.id(player));
		return s == null ? null : s.helloChannel;
	}

	/** Number of tracked players / players with the TRS Client. */
	public int[] counts() {
		int all = 0;
		int trs = 0;
		for (Session<P> s : sessions.values()) {
			all++;
			if (s.hello != null) trs++;
		}
		return new int[] {all, trs};
	}

	/** Lines for {@code /trsrules status <player>}. */
	public List<String> status(P player) {
		List<String> out = new ArrayList<String>();
		RulesConfig cfg = config;
		Session<P> s = sessions.get(bridge.id(player));
		out.add("Player: " + bridge.name(player));
		if (s == null) {
			out.add("TRS Client: unknown (player not tracked yet)");
		} else if (s.hello != null) {
			out.add("TRS Client: yes (" + s.hello.describe() + ")");
		} else {
			long waited = (clock.getAsLong() - s.joinedAt) / 1000L;
			MissingClientPolicy p = cfg.missingClient();
			if (p.action() == MissingClientPolicy.Action.IGNORE) {
				out.add("TRS Client: no answer (" + waited + " s since join)");
			} else if (s.missingHandled) {
				out.add("TRS Client: no (no hello within " + p.timeoutSeconds() + " s, action: " + p.action().name().toLowerCase(java.util.Locale.ROOT) + ")");
			} else {
				out.add("TRS Client: no answer yet (" + waited + " s of " + p.timeoutSeconds() + " s)");
			}
		}
		String bypass = bypassReason(player, cfg);
		out.add("Bypass: " + (bypass != null ? "yes (" + bypass + ") – gets no rules" : "no"));
		String scope = bridge.scope(player);
		boolean section = cfg.scopeLayer(scope) != null;
		out.add(capitalize(cfg.kind().singular()) + ": " + (scope == null ? "unknown" : scope)
				+ (section ? " (has its own section)" : " (global rules)"));
		RuleSet upstream = currentUpstream(s, scope);
		if (upstream != null) out.add("Backend rules: " + upstream.toJson());
		out.add("Rules: " + effectiveRules(player, s).toJson());
		if (s != null && s.hello != null) {
			if (s.pending) out.add("Delivery: waiting – the client has not registered the rules channel yet");
			else if (s.lastSent != null) out.add("Delivery: sent");
		}
		return out;
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
