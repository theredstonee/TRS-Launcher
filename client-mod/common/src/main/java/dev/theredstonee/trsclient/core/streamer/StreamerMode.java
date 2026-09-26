package dev.theredstonee.trsclient.core.streamer;

import dev.theredstonee.trsclient.core.chat.ChatText;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Streamer-Modus / Namensschutz: ersetzt den eigenen Namen (und auf Wunsch die anderer Spieler) in angezeigtem Text
 * und verbirgt Server-Adressen. Nur Anzeige – gesendet wird nichts. Namen werden als ganze Wörter ersetzt
 * ([A-Za-z0-9_]), Farbcodes bleiben erhalten.
 */
public final class StreamerMode {
	/** Ersatz für verborgene Adressen. */
	public static final String HIDDEN_ADDRESS = "*****";

	private boolean active;
	private boolean others;
	private String ownName = "";
	private String replacement = "Player";
	/** Andere Spieler → stabiler Ersatzname ("Player-3F2A") je Sitzung. */
	private final Map<String, String> aliases = new HashMap<String, String>();
	/** Namen aller bekannten Spieler (Tabliste), klein geschrieben → Originalschreibweise. */
	private final Map<String, String> known = new HashMap<String, String>();

	/** Einstellungen übernehmen (je Tick, billig). */
	public void configure(boolean active, String ownName, String replacement, boolean others) {
		this.active = active;
		this.ownName = ownName == null ? "" : ownName;
		String r = replacement == null ? "" : ChatText.strip(replacement).trim();
		this.replacement = r.isEmpty() ? "Player" : r;
		this.others = others;
	}

	public boolean active() {
		return active;
	}

	/** Spieler der Tabliste merken (für „andere verbergen“ und Chat-Ersetzung). */
	public void knowPlayer(String name) {
		if (name == null || name.isEmpty() || name.length() > 16) return;
		known.put(name.toLowerCase(Locale.ROOT), name);
	}

	/** Welt verlassen: Liste der bekannten Spieler leeren (Ersatznamen bleiben stabil). */
	public void forgetPlayers() {
		known.clear();
	}

	/** Ersatz für einen einzelnen Spielernamen (oder unverändert). */
	public String name(String name) {
		if (!active || name == null || name.isEmpty()) return name;
		if (name.equalsIgnoreCase(ownName)) return replacement;
		if (!others) return name;
		return alias(name);
	}

	/** Stabiler Ersatzname für andere Spieler. */
	String alias(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		String a = aliases.get(key);
		if (a == null) {
			a = "Player-" + String.format(Locale.ROOT, "%04X", (key.hashCode() * 0x9E3779B1) >>> 16);
			aliases.put(key, a);
		}
		return a;
	}

	/** Muss der Text überhaupt angefasst werden? (schnelle Vorprüfung) */
	public boolean affects(String raw) {
		if (!active || raw == null || raw.isEmpty()) return false;
		String lower = raw.toLowerCase(Locale.ROOT);
		if (!ownName.isEmpty() && lower.contains(ownName.toLowerCase(Locale.ROOT))) return true;
		if (!others) return false;
		for (String k : known.keySet()) {
			if (lower.contains(k)) return true;
		}
		return false;
	}

	/** Ersetzt Namen im Rohtext (mit Farbcodes) als ganze Wörter. */
	public String replace(String raw) {
		if (!affects(raw)) return raw;
		StringBuilder sb = new StringBuilder(raw.length() + 8);
		int i = 0;
		while (i < raw.length()) {
			if (ChatText.inCode(raw, i) || !ChatText.nameChar(raw.charAt(i)) || ChatText.nameChar(ChatText.visibleBefore(raw, i))) {
				sb.append(raw.charAt(i));
				i++;
				continue;
			}
			int end = i;
			while (end < raw.length() && ChatText.nameChar(raw.charAt(end))) end++;
			String word = raw.substring(i, end);
			String repl = replacementFor(word);
			sb.append(repl == null ? word : repl);
			i = end;
		}
		return sb.toString();
	}

	private String replacementFor(String word) {
		if (word.length() < 3 || word.length() > 16) return null;
		if (word.equalsIgnoreCase(ownName)) return replacement;
		if (others && known.containsKey(word.toLowerCase(Locale.ROOT))) return alias(word);
		return null;
	}

	/** Server-Adresse verbergen (falls aktiv und gewünscht). */
	public String address(String address, boolean hideIp) {
		if (!active || !hideIp || address == null || address.isEmpty()) return address;
		return HIDDEN_ADDRESS;
	}

	/**
	 * Sieht ein (Server-)Name wie eine Adresse aus („play.example.net“, „127.0.0.1:25565“)? Dann wird er in der
	 * Serverliste verborgen; frei gewählte Namen („Mein Server“) bleiben.
	 */
	public static boolean looksLikeAddress(String name) {
		if (name == null) return false;
		String s = ChatText.strip(name).trim();
		if (s.isEmpty() || s.indexOf(' ') >= 0) return false;
		if (s.indexOf(':') >= 0 && s.indexOf('.') < 0) return s.matches("[0-9a-fA-F:\\[\\]]+(:[0-9]+)?");
		int dots = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '.') dots++;
			else if (!(Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_')) return false;
		}
		return dots >= 1 && !s.endsWith(".") && !s.startsWith(".");
	}
}
