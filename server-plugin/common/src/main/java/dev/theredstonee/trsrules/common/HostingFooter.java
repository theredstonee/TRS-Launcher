package dev.theredstonee.trsrules.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Fixed second MOTD line for servers hosted through the TRS Launcher (CraftingHosting free servers). The TRS API writes
 * {@code motd-footer: "..."} into a separate hosting file; normal servers never have it, so the feature stays off.
 */
public final class HostingFooter {
	public static final String KEY = "motd-footer";
	public static final int MAX_LENGTH = 120;
	private static final String CODES = "0123456789abcdefklmnor";

	private HostingFooter() {
	}

	/** Footer text, or an error (then the caller keeps the previous footer). */
	public static final class Result {
		public final String footer;
		public final String error;

		private Result(String footer, String error) {
			this.footer = footer;
			this.error = error;
		}

		public boolean ok() {
			return error == null;
		}
	}

	/** Reads the hosting file. Missing file = feature off ({@code ""}). */
	public static Result load(Path file) {
		if (file == null || !Files.isRegularFile(file)) return new Result("", null);
		try {
			String text = new String(Files.readAllBytes(file), Protocol.UTF8);
			if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
			return parse(text);
		} catch (IOException e) {
			return new Result(null, "cannot read " + file + ": " + e);
		}
	}

	public static Result parse(String yaml) {
		try {
			LoaderOptions opts = new LoaderOptions();
			opts.setMaxAliasesForCollections(5);
			opts.setNestingDepthLimit(5);
			opts.setCodePointLimit(64 * 1024);
			Object doc = new Yaml(new SafeConstructor(opts)).load(yaml);
			if (doc == null) return new Result("", null);
			if (!(doc instanceof Map)) return new Result(null, "expected '" + KEY + ": <text>'");
			Object v = ((Map<?, ?>) doc).get(KEY);
			if (v instanceof Map || v instanceof Iterable) return new Result(null, KEY + " must be text");
			return new Result(sanitize(v == null ? null : String.valueOf(v)), null);
		} catch (RuntimeException e) {
			return new Result(null, String.valueOf(e.getMessage()).trim());
		}
	}

	/**
	 * One line, max {@value #MAX_LENGTH} chars, only {@code §[0-9a-fk-or]} codes (others are dropped with their code
	 * char). Line breaks and tabs become a space, other control chars are removed. No visible text = {@code ""}.
	 */
	public static String sanitize(String raw) {
		if (raw == null) return "";
		StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_LENGTH + 8));
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '§') {
				if (i + 1 < raw.length()) {
					char code = Character.toLowerCase(raw.charAt(++i));
					if (CODES.indexOf(code) >= 0) out.append('§').append(code);
				}
				continue;
			}
			if (c == '\n' || c == '\r' || c == '\t' || c == ' ' || c == ' ' || c == '\u0085') {
				if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
				continue;
			}
			if (Character.isISOControl(c)) continue;
			out.append(c);
		}
		String s = out.toString().trim();
		if (s.length() > MAX_LENGTH) {
			s = s.substring(0, MAX_LENGTH);
			char last = s.charAt(s.length() - 1);
			if (last == '§' || Character.isHighSurrogate(last)) s = s.substring(0, s.length() - 1);
			s = s.trim();
		}
		return s.replaceAll("§[0-9a-fk-or]", "").trim().isEmpty() ? "" : s;
	}

	/** First line of a MOTD (up to the first line break); {@code null} → {@code ""}. */
	public static String firstLine(String motd) {
		if (motd == null) return "";
		for (int i = 0; i < motd.length(); i++) {
			char c = motd.charAt(i);
			if (c == '\n' || c == '\r') return motd.substring(0, i);
		}
		return motd;
	}

	/**
	 * Server MOTD with the footer as line 2. Without footer the MOTD is returned unchanged. {@code §r} keeps the
	 * formatting of line 1 out of the footer.
	 */
	public static String motd(String serverMotd, String footer) {
		if (footer == null || footer.isEmpty()) return serverMotd;
		return firstLine(serverMotd) + "\n§r" + footer;
	}
}
