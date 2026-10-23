package dev.theredstonee.trsrules.common;

/** What the per-scope section of the config refers to. */
public enum ScopeKind {
	/** Game servers and server mods: per world ({@code worlds:}). */
	WORLDS("worlds", "world"),
	/** Proxies: per backend server ({@code servers:}). */
	SERVERS("servers", "server");

	private final String configKey;
	private final String singular;

	ScopeKind(String configKey, String singular) {
		this.configKey = configKey;
		this.singular = singular;
	}

	/** Name of the config section ({@code worlds} / {@code servers}). */
	public String configKey() {
		return configKey;
	}

	/** "world" / "server" (for messages). */
	public String singular() {
		return singular;
	}

	public ScopeKind other() {
		return this == WORLDS ? SERVERS : WORLDS;
	}
}
