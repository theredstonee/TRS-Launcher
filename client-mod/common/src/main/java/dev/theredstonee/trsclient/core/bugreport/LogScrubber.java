package dev.theredstonee.trsclient.core.bugreport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Säubert Log-Text für „Bug melden“, bevor er die TRS API erreicht (und damit öffentlich werden kann): Zugangs- und
 * Sitzungs-Tokens, Start-Argumente wie {@code --accessToken}, UUIDs, eigener Spielername und Kontonamen,
 * IP-Adressen, Benutzernamen in Pfaden ({@code C:\Users\<name>} → {@code C:\Users\<user>}), E-Mail-Adressen und
 * Chat-Inhalte ({@code [CHAT]}-Zeilen – das sind die Nachrichten anderer Spieler).
 *
 * <p>Reine Funktion ohne Minecraft und ohne Dateizugriff. Ersetzungen werden zuerst als Platzhalter aus
 * Privatbereich-Zeichen eingesetzt und erst am Ende in lesbare Marken wie {@code <token>} umgewandelt – so kann
 * keine spätere Regel eine frühere Ersetzung wieder anfassen (ein Spieler namens „user“ trifft nicht
 * {@code <user>}).
 */
public final class LogScrubber {
	/** Arten der Ersetzung (Index = Platzhalter-Ziffer). */
	static final int TOKEN = 0;
	static final int SECRET = 1;
	static final int UUID = 2;
	static final int PLAYER = 3;
	static final int USER = 4;
	static final int IP = 5;
	static final int EMAIL = 6;
	static final int CHAT = 7;
	static final int HOME = 8;
	private static final String[] LABELS = {"<token>", "<redacted>", "<uuid>", "<player>", "<user>", "<ip>", "<email>",
			"<chat removed>", "<home>"};
	private static final char OPEN = '\uE000';
	private static final char CLOSE = '\uE001';

	/** Längste Zeile (Rest wird abgeschnitten) – eine Riesenzeile soll nicht das ganze Log verdrängen. */
	public static final int MAX_LINE = 2000;

	/**
	 * Benutzernamen aus Pfaden bzw. vom Betriebssystem, die zu allgemein sind, um sie überall im Text zu ersetzen
	 * (im Pfad selbst werden sie trotzdem ersetzt).
	 */
	private static final Set<String> GENERIC = new HashSet<String>(java.util.Arrays.asList("user", "users", "admin",
			"administrator", "public", "default", "root", "owner", "guest", "pc", "minecraft", "player", "runner", "home",
			"desktop", "documents", "appdata", "local", "roaming", "all users", "default user", "shared", "system", "test"));

	private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");
	private static final Pattern SETTING_USER = Pattern.compile("(?i)(Setting user:\\s*)([A-Za-z0-9_]{3,16})\\b");
	private static final Pattern ARG_USERNAME = Pattern.compile("(?i)--username(?:\\s*[,=]\\s*|\\s+)[\"']?([A-Za-z0-9_]{3,16})\\b");
	private static final Pattern SESSION_ID = Pattern.compile("(?i)(Session ID is\\s+)token:[^\\s)\\]]+");
	private static final Pattern ARGS = Pattern.compile(
			"(?i)(--(accessToken|session|uuid|username|xuid|clientId|userProperties|profileProperties)\\b)(\\s*[,=]\\s*|\\s+)"
					+ "(\"[^\"]*\"|'[^']*'|[^\\s,\\]\\)]+)");
	private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*");
	private static final Pattern TRS_TOKEN = Pattern.compile("(?<![A-Za-z0-9_])trs(?:r1)?_[A-Za-z0-9_-]{16,}");
	private static final Pattern MS_TOKEN = Pattern.compile("(?<![A-Za-z0-9_])M\\.[CR][0-9]_[A-Za-z0-9!*$._-]{20,}");
	private static final Pattern BEARER = Pattern.compile("(?i)(?<![A-Za-z0-9_])(Bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
	private static final Pattern KEY_VALUE = Pattern.compile("(?i)(?<![A-Za-z0-9_])("
			+ "(?:access|refresh|id|session|client|api|auth|bearer|xbl|xsts|mc|user|login)?[_-]?token"
			+ "|(?:client[_-]?)?secret|password|passwd|pwd|api[_-]?key|session[_-]?id|authorization)"
			+ "([\"']?\\s*[:=]\\s*[\"']?)(?!(?:Bearer|Basic)\\b)([^\\s\"'&,;)}\\]<>" + OPEN + CLOSE + "]{4,})");
	private static final Pattern EMAIL_PATTERN = Pattern.compile(
			"(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}(?![A-Za-z0-9-])");
	private static final Pattern WINDOWS_USER = Pattern.compile(
			"(?i)((?<![A-Za-z])[A-Z]:(?:\\\\{1,2}|/{1,2})(?:Users|Documents and Settings)(?:\\\\{1,2}|/{1,2}))"
					+ "([^\\\\/\\r\\n\"'<>|:*?,\\]\\)" + OPEN + CLOSE + "]+)");
	private static final Pattern UNIX_USER = Pattern.compile(
			"((?<![A-Za-z0-9._-])/(?:home|Users)/)([^/\\s\"'<>|:*?,\\]\\)" + OPEN + CLOSE + "]+)");
	private static final Pattern UUID_DASHED = Pattern.compile(
			"(?i)(?<![0-9a-z])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-z])");
	private static final Pattern UUID_PLAIN = Pattern.compile("(?i)(?<![0-9a-z])[0-9a-f]{32}(?![0-9a-z])");
	private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";
	private static final Pattern IPV4 = Pattern.compile(
			"(?<![0-9A-Za-z.])(" + OCTET + "(?:\\." + OCTET + "){3})(?![0-9A-Za-z]|\\.[0-9])");
	/** Adresse (Gruppe 1), Zonen-ID (2), angehängter Port ohne Klammern (3, bleibt stehen). */
	private static final Pattern IPV6 = Pattern.compile(
			"(?<![0-9A-Za-z:.])((?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4})(%[0-9A-Za-z]+)?(:[0-9]{1,5})?(?![0-9A-Za-z:])");

	private LogScrubber() {
	}

	/** Ergebnis: gesäuberter Text und wie oft welche Art ersetzt wurde. */
	public static final class Result {
		public final String text;
		public final int tokens;
		public final int uuids;
		public final int names;
		public final int ips;
		public final int emails;
		public final int paths;
		public final int chat;

		Result(String text, int[] counts) {
			this.text = text;
			this.tokens = counts[TOKEN] + counts[SECRET];
			this.uuids = counts[UUID];
			this.names = counts[PLAYER];
			this.ips = counts[IP];
			this.emails = counts[EMAIL];
			this.paths = counts[USER] + counts[HOME];
			this.chat = counts[CHAT];
		}

		/** Anzahl aller Ersetzungen. */
		public int total() {
			return tokens + uuids + names + ips + emails + paths + chat;
		}
	}

	/** Säubert ohne bekannte Namen (nur Muster). */
	public static Result scrub(String text) {
		return scrub(text, Collections.<String>emptyList(), null, null);
	}

	/**
	 * Säubert {@code text}.
	 *
	 * @param names  Spieler- und Kontonamen, die überall (als ganzes Wort, ohne Groß/Klein) ersetzt werden
	 * @param osUser Benutzername des Betriebssystems ({@code user.name}) oder null
	 * @param home   Benutzerordner ({@code user.home}) oder null – wird ersetzt, falls er nicht unter
	 *               {@code C:\Users} bzw. {@code /home} liegt
	 */
	public static Result scrub(String text, Collection<String> names, String osUser, String home) {
		int[] counts = new int[LABELS.length];
		if (text == null || text.isEmpty()) return new Result("", counts);
		String s = normalize(text);

		// Namen, die das Log selbst verrät (Start-Argumente, „Setting user:“), kommen zu den bekannten dazu.
		Set<String> players = new LinkedHashSet<String>();
		if (names != null) {
			for (String n : names) {
				if (n != null && n.trim().length() >= 3) players.add(n.trim());
			}
		}
		harvest(SETTING_USER, 2, s, players);
		harvest(ARG_USERNAME, 1, s, players);

		s = chatLines(s, counts);
		s = replaceGroup(SESSION_ID, s, TOKEN, counts, 1, true);
		s = args(s, counts);
		s = replaceAll(JWT, s, TOKEN, counts);
		s = replaceAll(TRS_TOKEN, s, TOKEN, counts);
		s = replaceAll(MS_TOKEN, s, TOKEN, counts);
		s = replaceGroup(BEARER, s, TOKEN, counts, 1, true);
		s = keyValues(s, counts);
		s = replaceAll(EMAIL_PATTERN, s, EMAIL, counts);

		// Pfade mit Benutzernamen: Namen sammeln (für die Suche im restlichen Text) und ersetzen.
		Set<String> osUsers = new LinkedHashSet<String>();
		if (osUser != null && !osUser.trim().isEmpty()) osUsers.add(osUser.trim());
		s = userPaths(WINDOWS_USER, s, counts, osUsers);
		s = userPaths(UNIX_USER, s, counts, osUsers);
		s = home(s, home, counts);

		s = replaceAll(UUID_DASHED, s, UUID, counts);
		s = replaceAll(UUID_PLAIN, s, UUID, counts);
		s = ipv4(s, counts);
		s = ipv6(s, counts);

		s = names(s, players, PLAYER, counts, false);
		s = names(s, osUsers, USER, counts, true);
		return new Result(finish(s), counts);
	}

	// --- Schritte ---

	/** Zeilenenden vereinheitlichen, Farbcodes und Steuerzeichen entfernen (Tab bleibt), Riesenzeilen kürzen. */
	static String normalize(String text) {
		String s = ANSI.matcher(text).replaceAll("");
		StringBuilder out = new StringBuilder(s.length());
		int lineStart = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\r') {
				if (i + 1 < s.length() && s.charAt(i + 1) == '\n') continue;
				c = '\n';
			}
			if (c == '\n') {
				out.append('\n');
				lineStart = out.length();
				continue;
			}
			if (c == OPEN || c == CLOSE || (c < 0x20 && c != '\t') || c == 0x7F || (c >= 0x80 && c < 0xA0)) continue;
			// Bidi-Steuerzeichen (könnten die Anzeige auf der Website verdrehen)
			if ((c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069) || c == 0x200E || c == 0x200F) continue;
			if (out.length() - lineStart >= MAX_LINE) {
				if (out.length() - lineStart == MAX_LINE) out.append('…');
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

	private static void harvest(Pattern p, int group, String s, Set<String> into) {
		Matcher m = p.matcher(s);
		while (m.find()) {
			String n = m.group(group);
			if (n != null && n.length() >= 3) into.add(n);
		}
	}

	/** {@code [CHAT]}-Zeilen: alles hinter der Marke weg (Nachrichten anderer Spieler, private Nachrichten). */
	private static String chatLines(String s, int[] counts) {
		if (s.indexOf("[CHAT]") < 0) return s;
		StringBuilder out = new StringBuilder(s.length());
		int pos = 0;
		while (pos <= s.length()) {
			int end = s.indexOf('\n', pos);
			if (end < 0) end = s.length();
			String line = s.substring(pos, end);
			int at = line.indexOf("[CHAT]");
			if (at >= 0) {
				out.append(line, 0, at + 6).append(' ').append(ph(CHAT));
				counts[CHAT]++;
			} else {
				out.append(line);
			}
			if (end < s.length()) out.append('\n');
			pos = end + 1;
			if (end >= s.length()) break;
		}
		return out.toString();
	}

	private static String args(String s, int[] counts) {
		Matcher m = ARGS.matcher(s);
		StringBuffer out = new StringBuffer(s.length());
		while (m.find()) {
			String name = m.group(2).toLowerCase(Locale.ROOT);
			int kind = name.equals("uuid") ? UUID : name.equals("username") ? PLAYER : TOKEN;
			counts[kind]++;
			m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + m.group(3) + ph(kind)));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static String keyValues(String s, int[] counts) {
		Matcher m = KEY_VALUE.matcher(s);
		StringBuffer out = new StringBuffer(s.length());
		while (m.find()) {
			counts[SECRET]++;
			m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + m.group(2) + ph(SECRET)));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static String userPaths(Pattern p, String s, int[] counts, Set<String> found) {
		Matcher m = p.matcher(s);
		StringBuffer out = new StringBuffer(s.length());
		while (m.find()) {
			String name = m.group(2).trim();
			if (!name.isEmpty()) found.add(name);
			counts[USER]++;
			m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + ph(USER)));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** Benutzerordner außerhalb der üblichen Orte (z. B. {@code D:\Profile\max}) als Ganzes ersetzen. */
	private static String home(String s, String home, int[] counts) {
		if (home == null) return s;
		String h = home.trim();
		while (h.endsWith("\\") || h.endsWith("/")) h = h.substring(0, h.length() - 1);
		if (h.length() < 4) return s;
		String[] variants = {h, h.replace('\\', '/'), h.replace('/', '\\'), h.replace("\\", "\\\\")};
		for (String v : variants) {
			Matcher m = Pattern.compile("(?i)" + Pattern.quote(v) + "(?![A-Za-z0-9_.-])").matcher(s);
			StringBuffer out = new StringBuffer(s.length());
			boolean any = false;
			while (m.find()) {
				any = true;
				counts[HOME]++;
				m.appendReplacement(out, Matcher.quoteReplacement(ph(HOME)));
			}
			if (any) {
				m.appendTail(out);
				s = out.toString();
			}
		}
		return s;
	}

	private static String ipv4(String s, int[] counts) {
		Matcher m = IPV4.matcher(s);
		StringBuffer out = new StringBuffer(s.length());
		while (m.find()) {
			String ip = m.group(1);
			// Eigene Maschine (Loopback) und „alle Adressen“ verraten nichts – bleiben für die Fehlersuche stehen.
			if (ip.startsWith("127.") || ip.equals("0.0.0.0")) {
				m.appendReplacement(out, Matcher.quoteReplacement(ip));
				continue;
			}
			counts[IP]++;
			m.appendReplacement(out, Matcher.quoteReplacement(ph(IP)));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static String ipv6(String s, int[] counts) {
		if (s.indexOf(':') < 0) return s;
		Matcher m = IPV6.matcher(s);
		StringBuffer out = new StringBuffer(s.length());
		while (m.find()) {
			String cand = m.group(1);
			if (!ipv6Like(cand)) {
				m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
				continue;
			}
			counts[IP]++;
			m.appendReplacement(out, Matcher.quoteReplacement(ph(IP) + (m.group(3) == null ? "" : m.group(3))));
		}
		m.appendTail(out);
		return out.toString();
	}

	/**
	 * Sieht die Zeichenfolge wie eine IPv6-Adresse aus? Uhrzeiten ({@code 12:34:56}) haben kein {@code ::} und nur
	 * zwei Doppelpunkte – verlangt wird {@code ::} oder die volle Form mit 7 Doppelpunkten; {@code ::1} und
	 * {@code ::} (Loopback/alle) bleiben stehen.
	 */
	static boolean ipv6Like(String cand) {
		int colons = 0;
		int groups = 0;
		for (String g : cand.split(":", -1)) {
			if (g.length() > 4) return false;
			if (!g.isEmpty()) groups++;
		}
		for (int i = 0; i < cand.length(); i++) {
			if (cand.charAt(i) == ':') colons++;
		}
		boolean compressed = cand.contains("::");
		if (cand.contains(":::")) return false;
		if (!compressed && colons != 7) return false;
		if (compressed && groups < 2) return false; // "::", "::1", "fe80::" …
		return groups > 0;
	}

	private static String names(String s, Set<String> names, int kind, int[] counts, boolean skipGeneric) {
		if (names.isEmpty()) return s;
		List<String> list = new ArrayList<String>();
		for (String n : names) {
			if (n.length() < 3) continue;
			if (skipGeneric && GENERIC.contains(n.toLowerCase(Locale.ROOT))) continue;
			list.add(n);
		}
		// Längere Namen zuerst (ein Name kann in einem anderen stecken).
		Collections.sort(list, new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				return b.length() - a.length();
			}
		});
		for (String n : list) {
			// Ganzes Wort; ein Punkt direkt davor/dahinter mit Buchstaben gehört noch zum Wort („a.b“ steckt nicht in „a.b.c“).
			Matcher m = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?<![\\p{L}\\p{N}_]\\.)" + Pattern.quote(n)
					+ "(?![\\p{L}\\p{N}_])(?!\\.[\\p{L}\\p{N}_])").matcher(s);
			StringBuffer out = new StringBuffer(s.length());
			boolean any = false;
			while (m.find()) {
				any = true;
				counts[kind]++;
				m.appendReplacement(out, Matcher.quoteReplacement(ph(kind)));
			}
			if (any) {
				m.appendTail(out);
				s = out.toString();
			}
		}
		return s;
	}

	private static String replaceAll(Pattern p, String s, int kind, int[] counts) {
		Matcher m = p.matcher(s);
		StringBuffer out = null;
		while (m.find()) {
			if (out == null) out = new StringBuffer(s.length());
			counts[kind]++;
			m.appendReplacement(out, Matcher.quoteReplacement(ph(kind)));
		}
		if (out == null) return s;
		m.appendTail(out);
		return out.toString();
	}

	/** Wie {@link #replaceAll}, aber Gruppe {@code keep} bleibt vor dem Platzhalter stehen. */
	private static String replaceGroup(Pattern p, String s, int kind, int[] counts, int keep, boolean prefix) {
		Matcher m = p.matcher(s);
		StringBuffer out = null;
		while (m.find()) {
			if (out == null) out = new StringBuffer(s.length());
			counts[kind]++;
			m.appendReplacement(out, Matcher.quoteReplacement(prefix ? m.group(keep) + ph(kind) : ph(kind) + m.group(keep)));
		}
		if (out == null) return s;
		m.appendTail(out);
		return out.toString();
	}

	private static String ph(int kind) {
		return OPEN + Integer.toString(kind) + CLOSE;
	}

	/** Platzhalter in lesbare Marken umwandeln. */
	private static String finish(String s) {
		if (s.indexOf(OPEN) < 0) return s;
		StringBuilder out = new StringBuilder(s.length() + 32);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == OPEN && i + 2 < s.length() && s.charAt(i + 2) == CLOSE) {
				int kind = s.charAt(i + 1) - '0';
				if (kind >= 0 && kind < LABELS.length) {
					out.append(LABELS[kind]);
					i += 2;
					continue;
				}
			}
			if (c != OPEN && c != CLOSE) out.append(c);
		}
		return out.toString();
	}

	/** Namen, die ein Log selbst verrät ({@code Setting user: …}, {@code --username …}). */
	public static Set<String> namesIn(String text) {
		Set<String> out = new LinkedHashSet<String>();
		if (text == null || text.isEmpty()) return out;
		harvest(SETTING_USER, 2, text, out);
		harvest(ARG_USERNAME, 1, text, out);
		return out;
	}

	// --- Kürzen ---

	/**
	 * Die letzten {@code maxLines} Zeilen, zusammen höchstens {@code maxChars} Zeichen (vorne wird zeilenweise
	 * gekürzt; eine einzelne zu lange letzte Zeile verliert ihren Anfang). Leere Zeilen am Ende fallen weg.
	 */
	public static String tail(String text, int maxLines, int maxChars) {
		if (text == null || text.isEmpty() || maxLines <= 0 || maxChars <= 0) return "";
		int end = text.length();
		while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
		if (end == 0) return "";
		int start = end;
		int pos = end;
		int lines = 0;
		while (lines < maxLines) {
			int nl = text.lastIndexOf('\n', pos - 1);
			int lineStart = nl + 1;
			if (end - lineStart > maxChars) {
				// Schon die letzte Zeile ist zu lang: nur ihr Ende.
				if (lines == 0) {
					start = end - maxChars;
					if (Character.isLowSurrogate(text.charAt(start))) start++;
				}
				break;
			}
			start = lineStart;
			lines++;
			if (nl < 0) break;
			pos = nl;
		}
		return text.substring(start, end);
	}

	/**
	 * Die ersten {@code maxLines} Zeilen, zusammen höchstens {@code maxChars} Zeichen (Absturzberichte: der Anfang
	 * mit Beschreibung und Stack ist das Wichtige).
	 */
	public static String head(String text, int maxLines, int maxChars) {
		if (text == null || text.isEmpty() || maxLines <= 0 || maxChars <= 0) return "";
		int end = 0;
		int lines = 0;
		while (lines < maxLines && end < text.length()) {
			int nl = text.indexOf('\n', end);
			int lineEnd = nl < 0 ? text.length() : nl;
			if (lineEnd > maxChars) {
				if (lines == 0) {
					end = maxChars;
					if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
				}
				break;
			}
			end = lineEnd;
			lines++;
			if (nl < 0) break;
			end = nl + 1;
		}
		while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
		return text.substring(0, end);
	}
}
