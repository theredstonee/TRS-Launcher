package dev.theredstonee.trsrules.common;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What every platform needs besides the event wiring: config file handling (load, reload, keep the old rules on
 * errors) and the {@code /trsrules reload|status [player]} command.
 *
 * @param <P> platform player type
 */
public final class ServerRules<P> {
	public static final String COMMAND = "trsrules";
	private static final String PREFIX = "§c[TRS Rules] §r";

	/** Finds online players by name (for {@code status <player>}). */
	public interface PlayerLookup<P> {
		P find(String name);

		Collection<String> names();
	}

	private final RulesEngine.Bridge<P> bridge;
	private final Path configFile;
	private final ScopeKind kind;
	private final RulesEngine<P> engine;

	/** Creates the engine and loads the config (writing the default config on first start). */
	public ServerRules(RulesEngine.Bridge<P> bridge, Path configFile, ScopeKind kind) {
		this.bridge = bridge;
		this.configFile = configFile;
		this.kind = kind;
		RulesConfig initial = RulesConfig.empty(kind);
		ConfigLoader.Result r = ConfigLoader.load(configFile, kind);
		if (r.ok()) {
			initial = r.config;
			logWarnings(r.config);
			bridge.info("Loaded " + configFile.getFileName() + ": " + summary(r.config));
		} else {
			bridge.warn("Could not load " + configFile + ": " + r.error + " – no rules active until the file is fixed (/trsrules reload).");
		}
		this.engine = new RulesEngine<P>(bridge, initial);
	}

	public RulesEngine<P> engine() {
		return engine;
	}

	public Path configFile() {
		return configFile;
	}

	/** Reloads the config. On errors the previous rules stay active. Returns chat lines for the sender. */
	public List<String> reload() {
		ConfigLoader.Result r = ConfigLoader.load(configFile, kind);
		List<String> out = new ArrayList<String>();
		if (!r.ok()) {
			bridge.warn("Reload failed: " + r.error);
			out.add(PREFIX + "§cReload failed: §f" + r.error);
			out.add(PREFIX + "§7The previous rules stay active.");
			return out;
		}
		logWarnings(r.config);
		engine.reload(r.config);
		int[] c = engine.counts();
		out.add(PREFIX + "§aReloaded. §f" + summary(r.config) + "§7; rules re-sent to " + c[1] + " TRS Client player(s).");
		if (!r.config.warnings().isEmpty()) {
			out.add(PREFIX + "§e" + r.config.warnings().size() + " warning(s) – see the server log:");
			int shown = 0;
			for (String w : r.config.warnings()) {
				if (shown++ == 5) {
					out.add("§7 …");
					break;
				}
				out.add("§e - §7" + w);
			}
		}
		return out;
	}

	private void logWarnings(RulesConfig cfg) {
		for (String w : cfg.warnings()) bridge.warn("config: " + w);
	}

	private static String summary(RulesConfig cfg) {
		RuleSet g = cfg.global();
		int settings = 0;
		for (java.util.Map<String, SettingRule> m : g.settings().values()) settings += m.size();
		return g.modules().size() + " module rule(s), " + settings + " setting rule(s), " + cfg.scopes().size() + " "
				+ cfg.kind().configKey() + " section(s), missing-client: " + cfg.missingClient().action().name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Runs {@code /trsrules <args>} (permission {@value RulesConfig#ADMIN_PERMISSION} is checked by the platform).
	 * Returns chat lines with {@code §} color codes.
	 */
	public List<String> command(String[] args, PlayerLookup<P> players) {
		List<String> out = new ArrayList<String>();
		String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
		if ("reload".equals(sub)) {
			return reload();
		}
		if ("status".equals(sub)) {
			if (args.length < 2) {
				int[] c = engine.counts();
				out.add(PREFIX + "§f" + c[0] + "§7 player(s) tracked, §f" + c[1] + "§7 with the TRS Client.");
				out.add(PREFIX + "§7Config: §f" + summary(engine.config()));
				out.add(PREFIX + "§7Global rules: §f" + engine.config().resolve(null).toJson());
				out.add(PREFIX + "§7Details: /" + COMMAND + " status <player>");
				return out;
			}
			P p = players.find(args[1]);
			if (p == null) {
				out.add(PREFIX + "§cPlayer '" + args[1] + "' is not online.");
				return out;
			}
			for (String line : engine.status(p)) out.add(PREFIX + "§7" + line);
			return out;
		}
		out.add(PREFIX + "§7Usage: §f/" + COMMAND + " reload §7| §f/" + COMMAND + " status [player]");
		return out;
	}

	/** Tab completion for {@code /trsrules}. */
	public List<String> complete(String[] args, PlayerLookup<P> players) {
		List<String> out = new ArrayList<String>();
		if (args.length <= 1) {
			String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
			for (String s : new String[] {"reload", "status"}) if (s.startsWith(prefix)) out.add(s);
			return out;
		}
		if (args.length == 2 && "status".equalsIgnoreCase(args[0])) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			for (String n : players.names()) if (n.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(n);
			Collections.sort(out);
		}
		return out;
	}
}
