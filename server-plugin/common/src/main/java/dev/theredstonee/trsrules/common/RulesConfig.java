package dev.theredstonee.trsrules.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parsed {@code config.yml}: global rules, per-world (or per-server on proxies) layers, missing-client policy and
 * bypass permission. Immutable; a reload creates a new instance.
 *
 * <p>Parsing never throws for content problems – broken entries are skipped and reported in {@link #warnings()},
 * so a typo never disables the whole rule set.</p>
 */
public final class RulesConfig {
	public static final String DEFAULT_BYPASS_PERMISSION = "trsrules.bypass";
	public static final String ADMIN_PERMISSION = "trsrules.admin";
	/** Words that mean "no lock" (useful in a world/server section to lift a global lock). */
	private static final Set<String> FREE_WORDS = new HashSet<String>(Arrays.asList("free", "default", "player", "unset"));
	private static final Set<String> TOP_KEYS = new HashSet<String>(Arrays.asList("config-version", "modules", "settings",
			"worlds", "servers", "message", "missing-client", "bypass-permission"));
	private static final Set<String> SCOPE_KEYS = new HashSet<String>(Arrays.asList("modules", "settings", "message"));

	private final ScopeKind kind;
	private final RuleSet global;
	private final Map<String, RuleSet> scopes;
	private final MissingClientPolicy missingClient;
	private final String bypassPermission;
	private final List<String> warnings;

	private RulesConfig(ScopeKind kind, RuleSet global, Map<String, RuleSet> scopes, MissingClientPolicy missingClient,
			String bypassPermission, List<String> warnings) {
		this.kind = kind;
		this.global = global;
		this.scopes = scopes;
		this.missingClient = missingClient;
		this.bypassPermission = bypassPermission;
		this.warnings = warnings;
	}

	/** No rules at all (used before the config could be loaded). */
	public static RulesConfig empty(ScopeKind kind) {
		return new RulesConfig(kind, RuleSet.empty(), Collections.<String, RuleSet>emptyMap(), MissingClientPolicy.defaults(),
				DEFAULT_BYPASS_PERMISSION, Collections.<String>emptyList());
	}

	public ScopeKind kind() {
		return kind;
	}

	public RuleSet global() {
		return global;
	}

	/** Per-scope layers as configured (may contain "free" = {@code null} entries). */
	public Map<String, RuleSet> scopes() {
		return Collections.unmodifiableMap(scopes);
	}

	public MissingClientPolicy missingClient() {
		return missingClient;
	}

	/** Permission that exempts a player from all rules, or {@code null} if disabled. */
	public String bypassPermission() {
		return bypassPermission;
	}

	/** Problems found while parsing (to be logged). */
	public List<String> warnings() {
		return Collections.unmodifiableList(warnings);
	}

	/**
	 * Effective rules for a world (or backend server): the global rules with the matching section applied on top
	 * (world/server entries win). {@code scope} may be {@code null} (global only).
	 *
	 * <p>Matching: exact name, then ignoring case, then – for namespaced world keys like {@code minecraft:the_nether}
	 * (Sponge, server mods) – the part after the colon.</p>
	 */
	public RuleSet resolve(String scope) {
		return global.overlay(scopeLayer(scope));
	}

	/** The section for a scope name, or {@code null}. */
	public RuleSet scopeLayer(String scope) {
		if (scope == null || scopes.isEmpty()) return null;
		RuleSet r = find(scope);
		if (r == null) {
			int colon = scope.indexOf(':');
			if (colon >= 0 && colon < scope.length() - 1) r = find(scope.substring(colon + 1));
		}
		return r;
	}

	private RuleSet find(String name) {
		RuleSet r = scopes.get(name);
		if (r != null) return r;
		for (Map.Entry<String, RuleSet> e : scopes.entrySet()) {
			if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
		}
		return null;
	}

	// ------------------------------------------------------------------ parsing

	/**
	 * Builds the config from the YAML document (as nested Maps/Lists/scalars, e.g. from SnakeYAML).
	 *
	 * @param root top-level map, may be {@code null} (empty file)
	 */
	public static RulesConfig parse(Map<?, ?> root, ScopeKind kind) {
		List<String> warnings = new ArrayList<String>();
		if (root == null) root = Collections.emptyMap();

		for (Object k : root.keySet()) {
			String key = String.valueOf(k);
			if (!TOP_KEYS.contains(key)) warnings.add("Unknown option '" + key + "' – ignored.");
		}

		RuleSet global = new RuleSet();
		parseModules(root.get("modules"), global, "modules", warnings, false);
		parseSettings(root.get("settings"), global, "settings", warnings, false);
		Object msg = root.get("message");
		global.message(msg == null ? null : String.valueOf(msg));

		Map<String, RuleSet> scopes = new LinkedHashMap<String, RuleSet>();
		Object scopeSection = root.get(kind.configKey());
		if (scopeSection instanceof Map) {
			for (Map.Entry<?, ?> e : ((Map<?, ?>) scopeSection).entrySet()) {
				String name = String.valueOf(e.getKey());
				String path = kind.configKey() + "." + name;
				RuleSet layer = new RuleSet();
				if (e.getValue() instanceof Map) {
					Map<?, ?> sec = (Map<?, ?>) e.getValue();
					for (Object k : sec.keySet()) {
						if (!SCOPE_KEYS.contains(String.valueOf(k))) {
							warnings.add(path + ": unknown option '" + k + "' (allowed: modules, settings, message) – ignored.");
						}
					}
					parseModules(sec.get("modules"), layer, path + ".modules", warnings, true);
					parseSettings(sec.get("settings"), layer, path + ".settings", warnings, true);
					if (sec.containsKey("message")) {
						Object m = sec.get("message");
						layer.message(m == null ? "" : String.valueOf(m));
					}
				} else if (e.getValue() != null) {
					warnings.add(path + ": expected a section with modules/settings/message – ignored.");
				}
				scopes.put(name, layer);
			}
		} else if (scopeSection != null) {
			warnings.add("'" + kind.configKey() + "' must be a section (name: {modules: …}) – ignored.");
		}
		if (root.get(kind.other().configKey()) instanceof Map && !((Map<?, ?>) root.get(kind.other().configKey())).isEmpty()) {
			warnings.add("'" + kind.other().configKey() + ":' is not used on this platform (use '" + kind.configKey()
					+ ":' – " + (kind == ScopeKind.SERVERS ? "proxies apply rules per backend server" : "rules apply per world") + ").");
		}

		MissingClientPolicy missing = parseMissing(root.get("missing-client"), warnings);

		String bypass = DEFAULT_BYPASS_PERMISSION;
		if (root.containsKey("bypass-permission")) {
			Object b = root.get("bypass-permission");
			bypass = b == null ? null : String.valueOf(b).trim();
			if (bypass != null && bypass.isEmpty()) bypass = null;
		}

		RulesConfig cfg = new RulesConfig(kind, global, scopes, missing, bypass, warnings);
		cfg.checkSizes();
		return cfg;
	}

	private void checkSizes() {
		List<String> names = new ArrayList<String>();
		names.add(null);
		names.addAll(scopes.keySet());
		for (String n : names) {
			int size = resolve(n).toBytes().length;
			if (size > Protocol.MAX_PAYLOAD) {
				warnings.add("Rules " + (n == null ? "(global)" : "for " + kind.singular() + " '" + n + "'") + " are " + size
						+ " bytes – more than the protocol limit of " + Protocol.MAX_PAYLOAD + " bytes; they will not be sent.");
			}
		}
	}

	private static boolean isFree(Object v) {
		return v == null || (v instanceof String && FREE_WORDS.contains(((String) v).trim().toLowerCase(Locale.ROOT)));
	}

	private static void parseModules(Object section, RuleSet target, String path, List<String> warnings, boolean layer) {
		if (section == null) return;
		if (!(section instanceof Map)) {
			warnings.add(path + " must be a section (moduleId: true/false) – ignored.");
			return;
		}
		for (Map.Entry<?, ?> e : ((Map<?, ?>) section).entrySet()) {
			String id = String.valueOf(e.getKey()).trim();
			Object v = e.getValue();
			if (!ModuleIds.isKnown(id)) {
				warnings.add(path + ": unknown module id '" + id + "' (typo?) – sent anyway, TRS Clients that do not know it ignore it.");
			}
			if (v instanceof Boolean) {
				target.module(id, (Boolean) v);
			} else if (isFree(v)) {
				if (layer) target.module(id, null);
			} else {
				warnings.add(path + "." + id + ": expected true (forced on), false (forced off) or free – ignored.");
			}
		}
	}

	private static void parseSettings(Object section, RuleSet target, String path, List<String> warnings, boolean layer) {
		if (section == null) return;
		if (!(section instanceof Map)) {
			warnings.add(path + " must be a section (module.setting: value) – ignored.");
			return;
		}
		for (Map.Entry<?, ?> e : ((Map<?, ?>) section).entrySet()) {
			String key = String.valueOf(e.getKey()).trim();
			int dot = key.indexOf('.');
			if (dot < 0) {
				// Nested form: zoom: {factor: {max: 4}}
				if (e.getValue() instanceof Map) {
					for (Map.Entry<?, ?> n : ((Map<?, ?>) e.getValue()).entrySet()) {
						parseSetting(key, String.valueOf(n.getKey()).trim(), n.getValue(), target, path, warnings, layer);
					}
				} else {
					warnings.add(path + "." + key + ": setting keys look like module.setting (e.g. zoom.factor) – ignored.");
				}
				continue;
			}
			parseSetting(key.substring(0, dot), key.substring(dot + 1), e.getValue(), target, path, warnings, layer);
		}
	}

	private static void parseSetting(String module, String setting, Object v, RuleSet target, String path,
			List<String> warnings, boolean layer) {
		String full = path + "." + module + "." + setting;
		if (module.isEmpty() || setting.isEmpty()) {
			warnings.add(full + ": empty module or setting name – ignored.");
			return;
		}
		if (!ModuleIds.isKnown(module)) {
			warnings.add(full + ": unknown module id '" + module + "' (typo?) – sent anyway.");
		}
		try {
			if (isFree(v)) {
				if (layer) target.setting(module, setting, null);
				return;
			}
			if (v instanceof Map) {
				Map<?, ?> spec = (Map<?, ?>) v;
				for (Object k : spec.keySet()) {
					String sk = String.valueOf(k);
					if (!"value".equals(sk) && !"min".equals(sk) && !"max".equals(sk)) {
						warnings.add(full + ": unknown key '" + sk + "' (allowed: value, or min/max) – ignored.");
					}
				}
				if (spec.containsKey("value")) {
					if (spec.containsKey("min") || spec.containsKey("max")) {
						warnings.add(full + ": 'value' together with min/max – only 'value' is used.");
					}
					target.setting(module, setting, SettingRule.value(spec.get("value")));
				} else {
					target.setting(module, setting, SettingRule.range(number(spec.get("min"), "min"), number(spec.get("max"), "max")));
				}
				return;
			}
			target.setting(module, setting, SettingRule.value(v));
		} catch (IllegalArgumentException ex) {
			warnings.add(full + ": " + ex.getMessage() + " – ignored.");
		}
	}

	private static Double number(Object o, String name) {
		if (o == null) return null;
		if (o instanceof Number) return Double.valueOf(((Number) o).doubleValue());
		if (o instanceof String) {
			try {
				return Double.valueOf(((String) o).trim());
			} catch (NumberFormatException ignored) {
				// fall through
			}
		}
		throw new IllegalArgumentException(name + " must be a number");
	}

	private static MissingClientPolicy parseMissing(Object section, List<String> warnings) {
		if (section == null) return MissingClientPolicy.defaults();
		if (!(section instanceof Map)) {
			warnings.add("missing-client must be a section – using defaults (action: ignore).");
			return MissingClientPolicy.defaults();
		}
		Map<?, ?> m = (Map<?, ?>) section;
		MissingClientPolicy.Action action = MissingClientPolicy.Action.IGNORE;
		Object a = m.get("action");
		if (a != null) {
			action = MissingClientPolicy.Action.parse(String.valueOf(a));
			if (action == null) {
				warnings.add("missing-client.action: '" + a + "' is not ignore, notify or kick – using ignore.");
				action = MissingClientPolicy.Action.IGNORE;
			}
		}
		int timeout = MissingClientPolicy.DEFAULT_TIMEOUT_SECONDS;
		Object t = m.get("timeout-seconds");
		if (t instanceof Number) {
			timeout = ((Number) t).intValue();
		} else if (t != null) {
			warnings.add("missing-client.timeout-seconds must be a number – using " + timeout + ".");
		}
		if (timeout < MissingClientPolicy.MIN_TIMEOUT_SECONDS || timeout > MissingClientPolicy.MAX_TIMEOUT_SECONDS) {
			warnings.add("missing-client.timeout-seconds must be between " + MissingClientPolicy.MIN_TIMEOUT_SECONDS + " and "
					+ MissingClientPolicy.MAX_TIMEOUT_SECONDS + " – clamped.");
		}
		Object n = m.get("notify-message");
		Object k = m.get("kick-message");
		return new MissingClientPolicy(action, timeout, n == null ? null : String.valueOf(n), k == null ? null : String.valueOf(k));
	}
}
