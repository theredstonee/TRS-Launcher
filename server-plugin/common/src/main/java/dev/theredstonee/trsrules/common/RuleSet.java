package dev.theredstonee.trsrules.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A set of rules for one player: module on/off locks, setting locks and an optional message.
 *
 * <p>Also used as a <em>layer</em> (per world / per backend server) on top of the global rules: in a layer a
 * {@code null} entry means "free" – it removes a lock of the layer below. {@link #overlay(RuleSet)} resolves layers;
 * its result never contains {@code null} entries.</p>
 */
public final class RuleSet {
	/** Module id → forced state ({@code false} = locked off, {@code true} = forced on, {@code null} = free in a layer). */
	private final Map<String, Boolean> modules = new LinkedHashMap<String, Boolean>();
	/** Module id → (setting key → rule, {@code null} = free in a layer). */
	private final Map<String, Map<String, SettingRule>> settings = new LinkedHashMap<String, Map<String, SettingRule>>();
	/** Module ids in the order they were first mentioned (module state or setting) – keeps the JSON in config order. */
	private final Set<String> order = new LinkedHashSet<String>();
	/** Message shown once by the client; {@code null} = inherit (layers) / none. */
	private String message;

	public static RuleSet empty() {
		return new RuleSet();
	}

	public RuleSet module(String id, Boolean enabled) {
		order.add(id);
		modules.put(id, enabled);
		return this;
	}

	public RuleSet setting(String moduleId, String key, SettingRule rule) {
		order.add(moduleId);
		Map<String, SettingRule> m = settings.get(moduleId);
		if (m == null) {
			m = new LinkedHashMap<String, SettingRule>();
			settings.put(moduleId, m);
		}
		m.put(key, rule);
		return this;
	}

	public RuleSet message(String message) {
		this.message = message;
		return this;
	}

	public Map<String, Boolean> modules() {
		return Collections.unmodifiableMap(modules);
	}

	public Map<String, Map<String, SettingRule>> settings() {
		return Collections.unmodifiableMap(settings);
	}

	public String message() {
		return message;
	}

	/** Forced module state, or {@code null} if the player decides. */
	public Boolean moduleState(String id) {
		return modules.get(id);
	}

	public SettingRule settingRule(String moduleId, String key) {
		Map<String, SettingRule> m = settings.get(moduleId);
		return m == null ? null : m.get(key);
	}

	/** True if no module or setting is locked (a message alone does not count). */
	public boolean hasNoLocks() {
		for (Boolean b : modules.values()) if (b != null) return false;
		for (Map<String, SettingRule> m : settings.values()) for (SettingRule r : m.values()) if (r != null) return false;
		return true;
	}

	/**
	 * Returns a new rule set: this one with {@code top} applied over it. Entries of {@code top} replace entries here
	 * (per module state and per single setting); {@code null} entries of {@code top} remove the lock. The message of
	 * {@code top} replaces this message unless it is {@code null}. Neither input is modified.
	 */
	public RuleSet overlay(RuleSet top) {
		RuleSet out = new RuleSet();
		out.order.addAll(this.order);
		out.modules.putAll(this.modules);
		for (Map.Entry<String, Map<String, SettingRule>> e : this.settings.entrySet()) {
			out.settings.put(e.getKey(), new LinkedHashMap<String, SettingRule>(e.getValue()));
		}
		out.message = this.message;
		if (top != null) {
			for (Map.Entry<String, Boolean> e : top.modules.entrySet()) {
				out.module(e.getKey(), e.getValue());
			}
			for (Map.Entry<String, Map<String, SettingRule>> e : top.settings.entrySet()) {
				for (Map.Entry<String, SettingRule> s : e.getValue().entrySet()) {
					out.setting(e.getKey(), s.getKey(), s.getValue());
				}
			}
			if (top.message != null) out.message = top.message;
		}
		out.stripFree();
		return out;
	}

	/** Removes {@code null} ("free") entries. */
	private void stripFree() {
		modules.values().removeAll(Collections.singleton((Boolean) null));
		for (Map<String, SettingRule> m : settings.values()) m.values().removeAll(Collections.singleton((SettingRule) null));
		settings.values().removeIf(Map::isEmpty);
	}

	/** Protocol JSON ({@code trsclient:rules}), e.g. {@code {"v":1,"modules":{"minimap":{"enabled":false}}}}. */
	public String toJson() {
		StringBuilder out = new StringBuilder(64);
		out.append("{\"v\":").append(Protocol.VERSION).append(",\"modules\":{");
		boolean firstModule = true;
		for (String id : order) {
			if (!hasContent(id)) continue;
			if (!firstModule) out.append(',');
			firstModule = false;
			Json.quote(out, id);
			out.append(":{");
			boolean firstField = true;
			Boolean enabled = modules.get(id);
			if (enabled != null) {
				out.append("\"enabled\":").append(enabled.booleanValue() ? "true" : "false");
				firstField = false;
			}
			Map<String, SettingRule> s = settings.get(id);
			if (s != null) {
				boolean any = false;
				for (Map.Entry<String, SettingRule> e : s.entrySet()) {
					if (e.getValue() == null) continue;
					if (!any) {
						if (!firstField) out.append(',');
						out.append("\"settings\":{");
						any = true;
					} else {
						out.append(',');
					}
					Json.quote(out, e.getKey());
					out.append(':');
					e.getValue().writeJson(out);
				}
				if (any) out.append('}');
			}
			out.append('}');
		}
		out.append('}');
		if (message != null && !message.isEmpty()) {
			out.append(",\"message\":");
			Json.quote(out, message);
		}
		out.append('}');
		return out.toString();
	}

	private boolean hasContent(String id) {
		if (modules.get(id) != null) return true;
		Map<String, SettingRule> s = settings.get(id);
		if (s != null) for (SettingRule r : s.values()) if (r != null) return true;
		return false;
	}

	public byte[] toBytes() {
		return toJson().getBytes(Protocol.UTF8);
	}

	/**
	 * Reads a {@code rules} payload (used by proxies to merge rules sent by a backend plugin). Unknown fields and
	 * malformed entries are skipped, like the TRS Client does.
	 *
	 * @throws IllegalArgumentException if the payload is not a JSON object
	 */
	@SuppressWarnings("unchecked")
	public static RuleSet fromJson(String json) {
		Map<String, Object> root = Json.parseObject(json);
		RuleSet rs = new RuleSet();
		Object mods = root.get("modules");
		if (mods instanceof Map) {
			for (Map.Entry<String, Object> e : ((Map<String, Object>) mods).entrySet()) {
				if (!(e.getValue() instanceof Map)) continue;
				Map<String, Object> mod = (Map<String, Object>) e.getValue();
				Object enabled = mod.get("enabled");
				if (enabled instanceof Boolean) rs.module(e.getKey(), (Boolean) enabled);
				Object st = mod.get("settings");
				if (!(st instanceof Map)) continue;
				for (Map.Entry<String, Object> s : ((Map<String, Object>) st).entrySet()) {
					if (!(s.getValue() instanceof Map)) continue;
					Map<String, Object> spec = (Map<String, Object>) s.getValue();
					try {
						if (spec.containsKey("value")) {
							rs.setting(e.getKey(), s.getKey(), SettingRule.value(spec.get("value")));
						} else {
							Object min = spec.get("min");
							Object max = spec.get("max");
							rs.setting(e.getKey(), s.getKey(), SettingRule.range(
									min instanceof Number ? Double.valueOf(((Number) min).doubleValue()) : null,
									max instanceof Number ? Double.valueOf(((Number) max).doubleValue()) : null));
						}
					} catch (IllegalArgumentException ignored) {
						// malformed setting → ignored
					}
				}
			}
		}
		Object msg = root.get("message");
		if (msg instanceof String) rs.message((String) msg);
		return rs;
	}

	@Override
	public String toString() {
		return toJson();
	}
}
