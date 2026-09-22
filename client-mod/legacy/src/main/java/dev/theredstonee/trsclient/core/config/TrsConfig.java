package dev.theredstonee.trsclient.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wurzel der Datei {@code config/trsclient.json} (Gson-DTO). */
public final class TrsConfig {
	public static final int CURRENT_VERSION = 1;

	public int configVersion = CURRENT_VERSION;
	public Map<String, ModuleConfig> modules = new LinkedHashMap<>();
}
