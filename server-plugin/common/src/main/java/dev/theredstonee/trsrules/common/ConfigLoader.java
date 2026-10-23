package dev.theredstonee.trsrules.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code config.yml} (SnakeYAML, safe constructor – no arbitrary types) and writes the commented default config.
 */
public final class ConfigLoader {
	/** Default config template (resource inside the jar). */
	public static final String TEMPLATE = "/trs-server-rules/config.yml";

	private ConfigLoader() {
	}

	/** Result of loading: the config, or an error text (then the previous config should stay active). */
	public static final class Result {
		public final RulesConfig config;
		public final String error;

		private Result(RulesConfig config, String error) {
			this.config = config;
			this.error = error;
		}

		public boolean ok() {
			return config != null;
		}
	}

	/** Parses YAML text. Syntax errors are returned as {@link Result#error}. */
	public static Result parse(String yaml, ScopeKind kind) {
		return parse(new StringReader(yaml), kind);
	}

	public static Result parse(Reader reader, ScopeKind kind) {
		try {
			LoaderOptions opts = new LoaderOptions();
			opts.setAllowDuplicateKeys(false);
			opts.setMaxAliasesForCollections(20);
			opts.setNestingDepthLimit(20);
			Yaml yaml = new Yaml(new SafeConstructor(opts));
			Object doc = yaml.load(reader);
			if (doc != null && !(doc instanceof Map)) return new Result(null, "config.yml must contain options (key: value), not a list or text");
			return new Result(RulesConfig.parse((Map<?, ?>) doc, kind), null);
		} catch (RuntimeException e) {
			// SnakeYAML reports line/column in the message
			return new Result(null, String.valueOf(e.getMessage()).trim());
		}
	}

	/**
	 * Loads {@code file}; writes the default config first if it does not exist yet.
	 */
	public static Result load(Path file, ScopeKind kind) {
		try {
			writeDefaultIfMissing(file, kind);
			byte[] bytes = Files.readAllBytes(file);
			String text = new String(bytes, Protocol.UTF8);
			if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1); // BOM from Windows editors
			return parse(text, kind);
		} catch (IOException e) {
			return new Result(null, "cannot read " + file + ": " + e);
		}
	}

	/** Writes the commented default config if {@code file} does not exist. Returns true if written. */
	public static boolean writeDefaultIfMissing(Path file, ScopeKind kind) throws IOException {
		if (Files.exists(file)) return false;
		Path dir = file.toAbsolutePath().getParent();
		if (dir != null) Files.createDirectories(dir);
		Files.write(file, defaultConfig(kind).getBytes(Protocol.UTF8));
		return true;
	}

	/** The default config for a platform kind (template with the module list and scope section filled in). */
	public static String defaultConfig(ScopeKind kind) {
		String t = readTemplate();
		t = t.replace("%MODULE_LIST%", moduleList());
		if (kind == ScopeKind.SERVERS) {
			t = t.replace("%SCOPE_HELP%",
					"# Per backend server (names exactly as in your proxy config). Entries here replace the global entries\n"
							+ "# for players on that server; 'free' lifts a global lock. Rules are re-sent on every server switch.\n"
							+ "# Rules of this proxy plugin take precedence over a TRS Server Rules plugin on the backend server:\n"
							+ "# backend rules are merged in, but for the same module/setting the proxy wins.");
			t = t.replace("%SCOPE_KEY%", "servers");
			t = t.replace("%SCOPE_EXAMPLE%", "pvp");
			t = t.replace("%SCOPE_EXAMPLE_2%", "creative");
		} else {
			t = t.replace("%SCOPE_HELP%",
					"# Per world (folder name, e.g. world_nether; namespaced keys like minecraft:the_nether also work).\n"
							+ "# Entries here replace the global entries for players in that world; 'free' lifts a global lock.\n"
							+ "# Rules are re-sent whenever a player changes worlds.");
			t = t.replace("%SCOPE_KEY%", "worlds");
			t = t.replace("%SCOPE_EXAMPLE%", "pvp_arena");
			t = t.replace("%SCOPE_EXAMPLE_2%", "creative");
		}
		return t;
	}

	private static String moduleList() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, List<String>> e : ModuleIds.BY_CATEGORY.entrySet()) {
			sb.append("  # --- ").append(e.getKey()).append(" ---\n");
			for (String id : e.getValue()) {
				String label = ModuleLabels.label(id);
				StringBuilder line = new StringBuilder("  # ").append(id).append(": false");
				while (line.length() < 34) line.append(' ');
				line.append("# ").append(label);
				sb.append(line).append('\n');
			}
		}
		// without the trailing newline (the template has one after the placeholder)
		return sb.substring(0, sb.length() - 1);
	}

	private static String readTemplate() {
		InputStream in = ConfigLoader.class.getResourceAsStream(TEMPLATE);
		if (in == null) throw new IllegalStateException("missing resource " + TEMPLATE);
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
			return new String(out.toByteArray(), Protocol.UTF8);
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + TEMPLATE, e);
		} finally {
			try {
				in.close();
			} catch (IOException ignored) {
				// ignore
			}
		}
	}
}
