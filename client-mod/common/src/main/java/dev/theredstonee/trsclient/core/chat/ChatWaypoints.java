package dev.theredstonee.trsclient.core.chat;

import dev.theredstonee.trsclient.core.i18n.I18n;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wegpunkte anderer Karten-Mods im Minecraft-Chat – lesen und schreiben:
 * <ul>
 *   <li><b>Xaero's Minimap</b>: {@code xaero-waypoint:Name:N:x:y:z:farbe:false:0:Internal-the-nether} (Höhe {@code ~} =
 *   unbekannt, Farbe = Index 0–15 der Chatfarben). In Name, Kürzel und Dimension maskiert Xaero
 *   {@code ":" → "^col^"}, {@code "-" → "^min^"}, {@code "_" → "-"} und {@code "*" → "^ast^"}. Ältere Fassungen hängen den
 *   Wegpunkt-Satz an ({@code Internal-overworld-waypoints}) bzw. nutzen {@code xaero_waypoint:}.</li>
 *   <li><b>JourneyMap</b> (dasselbe Format liest VoxelMap): {@code [name:"Name", x:1, y:64, z:-2, dim:minecraft:the_nether]} –
 *   Reihenfolge beliebig, nur x und z sind Pflicht, bis 1.15 ist {@code dim} eine Zahl (0, -1, 1).</li>
 * </ul>
 *
 * <p>Erkannte Nachrichten werden im Chat anklickbar ({@link ChatCoords#find} liefert sie als Treffer mit
 * {@link ChatCoords.Hit#shared}) und landen im Fenster „Als Wegpunkt speichern“ – nur lokal, es wird nichts gesendet.
 * Gesendet wird nur, was der Spieler selbst über „Für Xaero's Minimap/JourneyMap posten“ verschickt. Reine Logik, Java 8.
 */
public final class ChatWaypoints {
	/** Höchstlänge einer eigenen Nachricht: Minecraft bis 1.10 kürzt alles über 100 Zeichen (und zerstört so das Format). */
	public static final int MAX_MESSAGE = 100;
	/** Längere Texte werden nie als ein geteilter Wegpunkt gelesen. */
	static final int MAX_TEXT = 512;
	/** Höchstens so viele geteilte Wegpunkte je Zeile. */
	static final int MAX_PER_LINE = 3;

	/** Die 16 Farben von Xaero's Minimap (Index = Chatfarbe 0–15). */
	private static final int[] XAERO_COLORS = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF0000, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
	/** Farbe für Stellen ohne eigene Farbe (blau wie die TRS-Standardfarbe). */
	private static final int XAERO_DEFAULT_COLOR = 9;

	private static final String XAERO_PREFIX = "xaero-waypoint";
	/** Befehl hinter Xaeros „[Add]“ in dessen eigener Chatzeile: {@code /xaero_waypoint_add:Name:N:x:y:z:…}. */
	private static final String XAERO_ADD_PREFIX = "xaero_waypoint_add";
	/** Einfüge-Text des Knopfs „[Hinzufügen]“ (davor der Marker, danach die Nachricht): sofort speichern, ohne Fenster. */
	static final String ADD_MARKER = "trs-waypoint-add:";
	/**
	 * Befehl hinter „[Add]“ (echte Klick-Aktion, damit Minecraft und andere Chat-Mods den Knopf als anklickbar behandeln):
	 * {@code /trsclient-waypoint-add <Nachricht>}. Der TRS Client fängt den Klick ab, bevor etwas an den Server geht.
	 */
	static final String ADD_COMMAND = "trsclient-waypoint-add";
	/** Platzhalter für den Namen beim Zerlegen des übersetzten Satzes (Privatbereich, kommt in Übersetzungen nicht vor). */
	private static final String NAME_SLOT = "";
	/**
	 * Präfix : Name : Kürzel : x : y|~ : z : Farbe [: Drehen [: Blickrichtung [: Ziel [: weitere Felder]]]]. Weitere Felder
	 * (z. B. {@code :xaeroshare} anderer Mods) gehören zur Nachricht, werden aber ignoriert.
	 */
	private static final Pattern XAERO = Pattern.compile("(?<![\\p{L}\\p{N}])((?i:xaero[-_]waypoint(?:_add)?)):"
			+ "([^:\\n]{1,160}):([^:\\n]{0,16}):(-?\\d{1,9}):(~|-?\\d{1,9}):(-?\\d{1,9}):(-?\\d{1,4})"
			+ "(?::(true|false)?(?::(-?\\d{1,5})?(?::([^\\s:]{0,128})(?::[^\\s:]{1,32}){0,4})?)?)?");
	/** Eckige Klammern ohne Klammern darin (JourneyMap-Ort). */
	private static final Pattern BRACKETS = Pattern.compile("\\[([^\\[\\]\\n]{3,300})]");
	private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,15}");
	private static final Pattern INT = Pattern.compile("[-+]?\\d{1,9}");
	private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}");
	private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]{1,64}");
	private static final Pattern HEX_COLOR = Pattern.compile("(?:#|0x)([0-9a-f]{6})");

	private ChatWaypoints() {
	}

	/** Karten-Mod, aus dessen Format ein Wegpunkt stammt. */
	public enum Format {
		XAERO("Xaero's Minimap"),
		JOURNEYMAP("JourneyMap");

		private final String label;

		Format(String label) {
			this.label = label;
		}

		/** Name des Mods (Eigenname, wird nicht übersetzt). */
		public String label() {
			return label;
		}
	}

	/** Ein geteilter Wegpunkt aus dem Chat. */
	public static final class Shared {
		public final Format format;
		/** Name ("" = ohne Namen, z. B. JourneyMap {@code [x:1, z:2]}). */
		public final String name;
		/** Kürzel im Kästchen (Xaero, höchstens zwei Zeichen), "" = keins. */
		public final String mark;
		public final int x;
		public final int y;
		public final int z;
		/** Höhe angegeben? (Xaero {@code ~} bzw. JourneyMap ohne {@code y} = nein, {@link #y} ist dann 0.) */
		public final boolean hasY;
		/** Dimension als Namensraum-Kennung ({@code minecraft:the_nether}, Legacy-Mods {@code legacy:dim7}), null = keine Angabe. */
		public final String dimension;
		/** Farbe als 0xRRGGBB, -1 = keine. */
		public final int color;
		/** Der Text, wie er im Chat stand – zugleich Einfüge-Text der Chat-Spanne (Umschalt-Klick fügt ihn ein). */
		public final String text;

		Shared(Format format, String name, String mark, int x, int y, int z, boolean hasY, String dimension, int color, String text) {
			this.format = format;
			this.name = name == null ? "" : name;
			this.mark = mark == null ? "" : mark;
			this.x = x;
			this.y = y;
			this.z = z;
			this.hasY = hasY;
			this.dimension = dimension;
			this.color = color;
			this.text = text;
		}

		@Override
		public String toString() {
			return format + "[" + name + " " + x + " " + (hasY ? String.valueOf(y) : "~") + " " + z + " " + dimension + "]";
		}
	}

	// --- Lesen ---

	/** Alle geteilten Wegpunkte einer Zeile als Chat-Treffer (aufsteigend, ohne Überschneidung). */
	public static List<ChatCoords.Hit> find(String text) {
		List<ChatCoords.Hit> out = new ArrayList<ChatCoords.Hit>();
		if (text == null || text.length() < 7 || text.length() > ChatCoords.MAX_LENGTH) return out;
		if (text.indexOf(':') < 0) return out;
		Matcher m = XAERO.matcher(text);
		while (m.find() && out.size() < MAX_PER_LINE) {
			Shared s = xaero(m, text.substring(m.start(), m.end()));
			if (s != null) out.add(new ChatCoords.Hit(m.start(), m.end(), s.x, s.y, s.z, s));
		}
		if (text.indexOf('[') >= 0) {
			m = BRACKETS.matcher(text);
			while (m.find() && out.size() < MAX_PER_LINE) {
				if (overlaps(out, m.start(), m.end())) continue;
				Shared s = journeyMap(m.group(1), m.group());
				if (s != null) out.add(new ChatCoords.Hit(m.start(), m.end(), s.x, s.y, s.z, s));
			}
		}
		return out;
	}

	/** Genau ein geteilter Wegpunkt (z. B. der Einfüge-Text einer Chat-Spanne) oder null. */
	public static Shared parse(String text) {
		if (text == null || text.length() > MAX_TEXT) return null;
		String t = text.trim();
		if (t.startsWith("[") && t.endsWith("]") && t.length() > 2) {
			return t.indexOf('[', 1) < 0 && t.indexOf(']') == t.length() - 1 ? journeyMap(t.substring(1, t.length() - 1), t) : null;
		}
		Matcher m = XAERO.matcher(t);
		return m.matches() ? xaero(m, t) : null;
	}

	private static Shared xaero(Matcher m, String raw) {
		try {
			// „xaero_waypoint_add:“ (Xaeros eigener „[Add]“-Befehl) trägt die Felder der modernen Nachricht weiter.
			String prefix = m.group(1).toLowerCase(Locale.ROOT);
			boolean modern = prefix.equals(XAERO_PREFIX) || prefix.equals(XAERO_ADD_PREFIX);
			String name = clean(modern ? xaeroUnescape(m.group(2)) : m.group(2).replace("^col^", ":"));
			String mark = clean(modern ? xaeroUnescape(m.group(3)) : m.group(3).replace("^col^", ":"));
			int x = Integer.parseInt(m.group(4));
			boolean hasY = !m.group(5).equals("~");
			int y = hasY ? Integer.parseInt(m.group(5)) : 0;
			int z = Integer.parseInt(m.group(6));
			if (!inWorld(x, hasY ? y : 0, z) || name.isEmpty()) return null;
			int color = xaeroColor(Integer.parseInt(m.group(7)));
			String dim = xaeroDimension(m.group(10));
			return new Shared(Format.XAERO, name, mark, x, y, z, hasY, dim, color, raw);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Inhalt der eckigen Klammern im JourneyMap-Format; null = kein Ort. */
	private static Shared journeyMap(String content, String raw) {
		Map<String, String> fields = fields(content);
		if (fields == null || !fields.containsKey("x") || !fields.containsKey("z")) return null;
		String sx = fields.get("x"), sy = fields.get("y"), sz = fields.get("z");
		if (!INT.matcher(sx).matches() || !INT.matcher(sz).matches() || (sy != null && !INT.matcher(sy).matches())) return null;
		int x = Integer.parseInt(sx), z = Integer.parseInt(sz);
		boolean hasY = sy != null;
		int y = hasY ? Integer.parseInt(sy) : 0;
		if (!inWorld(x, y, z)) return null;
		String dim = null;
		String d = fields.containsKey("dim") ? fields.get("dim") : fields.get("dimension");
		if (d != null) {
			dim = journeyMapDimension(d);
			if (dim == null) return null;
		}
		String name = fields.get("name");
		int color = -1;
		String c = fields.get("color");
		if (c != null) {
			Matcher hex = HEX_COLOR.matcher(c.toLowerCase(Locale.ROOT));
			if (hex.matches()) color = Integer.parseInt(hex.group(1), 16);
		}
		return new Shared(Format.JOURNEYMAP, clean(name), "", x, y, z, hasY, dim, color, raw);
	}

	/**
	 * {@code key:wert}-Paare, durch Kommas getrennt; Werte in "…" oder '…' dürfen Kommas und Doppelpunkte enthalten
	 * (JourneyMap setzt Namen in Anführungszeichen). Doppelte oder kaputte Paare → null.
	 */
	static Map<String, String> fields(String content) {
		Map<String, String> out = new HashMap<String, String>();
		int i = 0, n = content.length();
		while (i < n) {
			int colon = content.indexOf(':', i);
			if (colon < 0) return null;
			String key = content.substring(i, colon).trim().toLowerCase(Locale.ROOT);
			if (!KEY.matcher(key).matches()) return null;
			int v = colon + 1;
			while (v < n && content.charAt(v) == ' ') v++;
			String value;
			int next;
			char q = v < n ? content.charAt(v) : 0;
			int close = q == '"' || q == '\'' ? content.indexOf(q, v + 1) : -1;
			if (close > 0) {
				value = content.substring(v + 1, close);
				next = close + 1;
				while (next < n && content.charAt(next) == ' ') next++;
				if (next < n && content.charAt(next) != ',') return null;
			} else {
				next = content.indexOf(',', v);
				if (next < 0) next = n;
				value = content.substring(v, next).trim();
			}
			if (out.put(key, value) != null) return null;
			i = next + 1;
			if (next < n && i >= n) return null;
		}
		return out.size() >= 2 ? out : null;
	}

	/** {@code dim} von JourneyMap/VoxelMap → Namensraum-Kennung; null = unbrauchbar. */
	static String journeyMapDimension(String value) {
		String d = value.trim().toLowerCase(Locale.ROOT);
		if (INT.matcher(d).matches()) return legacyDimension(Integer.parseInt(d));
		if (NAMESPACED.matcher(d).matches()) return d;
		if (d.equals("nether")) return "minecraft:the_nether";
		if (d.equals("end")) return "minecraft:the_end";
		return PATH.matcher(d).matches() ? "minecraft:" + d : null;
	}

	/** Ziel einer Xaero-Nachricht → Namensraum-Kennung; null = keine Angabe (aktuelle Dimension). */
	static String xaeroDimension(String destination) {
		if (destination == null || !destination.startsWith("Internal-")) return null;
		String d = destination.substring("Internal-".length());
		// Ältere Fassungen hängen den Wegpunkt-Satz an („Internal-overworld-waypoints“).
		if (d.endsWith("-waypoints")) d = d.substring(0, d.length() - "-waypoints".length());
		if (d.equals("overworld") || d.startsWith("overworld-")) return "minecraft:overworld";
		if (d.equals("the-nether") || d.startsWith("the-nether-")) return "minecraft:the_nether";
		if (d.equals("the-end") || d.startsWith("the-end-")) return "minecraft:the_end";
		if (!d.startsWith("dim%")) return null;
		String id = xaeroUnescape(d.substring(4)).replace('$', ':').replace('%', '/').replace(',', '.').toLowerCase(Locale.ROOT);
		if (INT.matcher(id).matches()) return legacyDimension(Integer.parseInt(id));
		return NAMESPACED.matcher(id).matches() ? id : null;
	}

	private static String legacyDimension(int n) {
		if (n == 0) return "minecraft:overworld";
		if (n == -1) return "minecraft:the_nether";
		if (n == 1) return "minecraft:the_end";
		return "legacy:dim" + n;
	}

	/** Farbindex von Xaero → 0xRRGGBB; größere Werte laufen um, negative (-1 = Zufall) → -1. */
	public static int xaeroColor(int index) {
		if (index < 0) return -1;
		return XAERO_COLORS[index % XAERO_COLORS.length];
	}

	/** Nächste der 16 Farben von Xaero zu einer Farbe (0xRRGGBB; -1 = Standard). */
	public static int xaeroColorIndex(int rgb) {
		if (rgb < 0) return XAERO_DEFAULT_COLOR;
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		int best = XAERO_DEFAULT_COLOR;
		long bestDistance = Long.MAX_VALUE;
		for (int i = 0; i < XAERO_COLORS.length; i++) {
			int c = XAERO_COLORS[i];
			int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF;
			// „Redmean“: grob wie das Auge gewichtet (Grün zählt am meisten).
			long mean = (r + cr) / 2;
			long dr = r - cr, dg = g - cg, db = b - cb;
			long d = ((512 + mean) * dr * dr >> 8) + 4 * dg * dg + ((767 - mean) * db * db >> 8);
			if (d < bestDistance) {
				bestDistance = d;
				best = i;
			}
		}
		return best;
	}

	static String xaeroUnescape(String s) {
		return s.replace("-", "_").replace("^min^", "-").replace("^col^", ":").replace("^ast^", "*");
	}

	static String xaeroEscape(String s) {
		return s.replace("-", "^min^").replace("_", "-").replace(":", "^col^").replace("*", "^ast^");
	}

	// --- Schreiben ---

	/**
	 * Nachricht im Format von Xaero's Minimap. {@code dimension} als Namensraum-Kennung (null = keine Angabe),
	 * {@code rgb} -1 = Standardfarbe; der Name wird gekürzt, bis die Nachricht in {@link #MAX_MESSAGE} passt.
	 */
	public static String xaero(String name, String mark, int x, int y, int z, int rgb, String dimension) {
		String n = clean(name);
		if (n.isEmpty()) n = x + ", " + z;
		String k = cleanMark(mark);
		if (k.isEmpty()) k = initial(n);
		String tail = ":" + xaeroEscape(k) + ":" + x + ":" + y + ":" + z + ":" + xaeroColorIndex(rgb) + ":false:0:"
				+ xaeroDestination(dimension);
		String line = XAERO_PREFIX + ":" + xaeroEscape(n) + tail;
		while (line.length() > MAX_MESSAGE && n.length() > 1) {
			n = cut(n);
			line = XAERO_PREFIX + ":" + xaeroEscape(n) + tail;
		}
		return line;
	}

	/** Ziel ({@code Internal-…}) für eine Dimension wie Xaero es schreibt. */
	static String xaeroDestination(String dimension) {
		if (dimension == null || dimension.isEmpty()) return "Internal";
		if (dimension.equals("minecraft:overworld")) return "Internal-overworld";
		if (dimension.equals("minecraft:the_nether")) return "Internal-the-nether";
		if (dimension.equals("minecraft:the_end")) return "Internal-the-end";
		String id = dimension.startsWith("legacy:dim") ? dimension.substring("legacy:dim".length())
				: dimension.replace(':', '$').replace('/', '%').replace('.', ',');
		return "Internal-dim%" + xaeroEscape(id);
	}

	/**
	 * Nachricht im Format von JourneyMap ({@code [name:"…", x:…, y:…, z:…, dim:…]}). {@code numericDimension}: Zahlen wie
	 * JourneyMap bis 1.15 ({@code dim:-1}) statt Kennungen; {@code dimension} null = ohne {@code dim} (JourneyMap nimmt dann
	 * die Dimension des Empfängers).
	 */
	public static String journeyMap(String name, int x, int y, int z, String dimension, boolean numericDimension) {
		// Anführungszeichen ersetzt JourneyMap selbst durch Leerzeichen; Klammern würden den Ort beenden.
		String n = name == null ? "" : clean(name.replace('"', ' ').replace('[', '(').replace(']', ')'));
		String tail = "x:" + x + ", y:" + y + ", z:" + z;
		String dim = journeyMapDimensionText(dimension, numericDimension);
		if (dim != null) tail += ", dim:" + dim;
		String line = n.isEmpty() ? "[" + tail + "]" : "[name:\"" + n + "\", " + tail + "]";
		while (line.length() > MAX_MESSAGE && n.length() > 1) {
			n = cut(n).trim();
			line = "[name:\"" + n + "\", " + tail + "]";
		}
		return line;
	}

	private static String journeyMapDimensionText(String dimension, boolean numeric) {
		if (dimension == null || dimension.isEmpty()) return null;
		if (!numeric) return dimension;
		if (dimension.equals("minecraft:overworld")) return "0";
		if (dimension.equals("minecraft:the_nether")) return "-1";
		if (dimension.equals("minecraft:the_end")) return "1";
		if (dimension.startsWith("legacy:dim")) return dimension.substring("legacy:dim".length());
		return dimension;
	}

	// --- Darstellung im Chat ---

	/** Ein Stück der Chat-Darstellung eines geteilten Wegpunkts (der Loader baut daraus Komponenten). */
	public static final class Part {
		/** TEXT = normaler Text, NAME = Name (Klick: Fenster), ADD = Knopf (Klick: sofort speichern), MOD = Mod-Name. */
		public enum Kind {
			TEXT, NAME, ADD, MOD
		}

		public final Kind kind;
		public final String text;
		/** Einfüge-Text (Marker für den Klick), null = keiner. */
		public final String insertion;
		/** Hinweis beim Darüberfahren, null = keiner. */
		public final String hover;
		/** Befehl der Klick-Aktion (mit „/“), null = keine (dann zählt nur der Einfüge-Text). */
		public final String command;

		Part(Kind kind, String text, String insertion, String hover) {
			this(kind, text, insertion, hover, null);
		}

		Part(Kind kind, String text, String insertion, String hover, String command) {
			this.kind = kind;
			this.text = text;
			this.insertion = insertion;
			this.hover = hover;
			this.command = command;
		}
	}

	/**
	 * Anzeige statt der Rohnachricht, wie Xaero's Minimap sie zeigt: „shared a waypoint called “Dorf” from Nether!
	 * [Add] · Xaero's Minimap“ (Text davor, z. B. „&lt;Alex&gt; “, bleibt stehen). Der Name öffnet „Als Wegpunkt
	 * speichern“ (Einfüge-Text = die Nachricht, Umschalt-Klick fügt sie wie bisher ein), „[Add]“ speichert sofort.
	 */
	public static List<Part> display(Shared s) {
		List<Part> out = new ArrayList<Part>();
		String sentence = s.dimension != null
				? I18n.tr("chat.waypoint.sharedFrom", NAME_SLOT, dev.theredstonee.trsclient.core.map.WorldMapUi.dimensionName(s.dimension))
				: I18n.tr("chat.waypoint.shared", NAME_SLOT);
		int slot = sentence.indexOf(NAME_SLOT);
		String before = slot < 0 ? sentence + " " : sentence.substring(0, slot);
		String after = slot < 0 ? "" : sentence.substring(slot + NAME_SLOT.length());
		if (!before.isEmpty()) out.add(new Part(Part.Kind.TEXT, before, null, null));
		out.add(new Part(Part.Kind.NAME, displayName(s), s.text, hover(s)));
		if (!after.isEmpty()) out.add(new Part(Part.Kind.TEXT, after, null, null));
		out.add(addButton(s, "chat.waypoint.add"));
		out.add(new Part(Part.Kind.MOD, " · " + s.format.label(), null, null));
		return out;
	}

	private static Part addButton(Shared s, String labelKey) {
		String hover = I18n.tr("chat.waypoint.addHover", displayName(s)) + "\n" + details(s);
		return new Part(Part.Kind.ADD, " [" + I18n.tr(labelKey) + "]", ADD_MARKER + s.text, hover, "/" + ADD_COMMAND + " " + s.text);
	}

	/** Klick-Befehl eines „[Add]“-Knopfs ({@code /trsclient-waypoint-add …}) → der Wegpunkt; null = kein solcher Befehl. */
	public static Shared parseAddCommand(String command) {
		if (command == null) return null;
		String c = command.trim();
		if (c.startsWith("/")) c = c.substring(1);
		if (!c.startsWith(ADD_COMMAND + " ")) return null;
		return parse(c.substring(ADD_COMMAND.length() + 1));
	}

	/** Ist das der Einfüge-Text eines „[Add]“-Knopfs? */
	public static boolean isAddMarker(String insertion) {
		return insertion != null && insertion.startsWith(ADD_MARKER);
	}

	/** Einfüge-Text eines „[Add]“-Knopfs → der Wegpunkt; null = kein solcher Knopf. */
	public static Shared parseAddMarker(String insertion) {
		if (insertion == null || !insertion.startsWith(ADD_MARKER)) return null;
		return parse(insertion.substring(ADD_MARKER.length()));
	}

	/**
	 * Klick-Aktion eines anderen Mods als geteilter Wegpunkt – Xaeros „[Add]“ führt {@code /xaero_waypoint_add:…} aus –,
	 * sonst null (alle anderen Befehle und Links).
	 */
	public static Shared fromClickCommand(String command) {
		if (command == null) return null;
		String c = command.trim();
		if (c.startsWith("/")) c = c.substring(1);
		return c.regionMatches(true, 0, XAERO_ADD_PREFIX + ":", 0, XAERO_ADD_PREFIX.length() + 1) ? parse(c) : null;
	}

	/** Name für die Anzeige (ohne Namen: „Aus dem Chat“). */
	public static String displayName(Shared s) {
		return s.name.isEmpty() ? I18n.tr("waypoint.save.defaultName") : s.name;
	}

	/** Mod, Position und Dimension in einer Zeile. */
	public static String details(Shared s) {
		StringBuilder b = new StringBuilder(s.format.label()).append(" · ").append(position(s));
		if (s.dimension != null) b.append(" · ").append(dev.theredstonee.trsclient.core.map.WorldMapUi.dimensionName(s.dimension));
		return b.toString();
	}

	// --- Hilfen ---

	/** Hinweis an der Chat-Spanne: Klick speichert „Name“; zweite Zeile Mod, Position und Dimension. */
	public static String hover(Shared s) {
		return I18n.tr("chat.waypoint.hover", displayName(s)) + "\n" + details(s);
	}

	/** „x y z“, unbekannte Höhe als „~“. */
	public static String position(Shared s) {
		return s.x + " " + (s.hasY ? String.valueOf(s.y) : "~") + " " + s.z;
	}

	/** Keine Formatierungs-/Steuerzeichen, eine Zeile, höchstens 32 Zeichen (wie Wegpunkt-Namen). */
	static String clean(String s) {
		if (s == null) return "";
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == ChatText.CODE) {
				i++;
			} else if (c < ' ' || c == 127) {
				b.append(' ');
			} else {
				b.append(c);
			}
		}
		String out = b.toString().replaceAll("\\s+", " ").trim();
		return out.length() > 32 ? out.substring(0, 32).trim() : out;
	}

	/** Kürzel: höchstens zwei Zeichen ohne Leer-/Formatierungszeichen. */
	static String cleanMark(String s) {
		String c = clean(s).replace(" ", "");
		int end = c.isEmpty() ? 0 : c.offsetByCodePoints(0, Math.min(2, c.codePointCount(0, c.length())));
		return c.substring(0, end);
	}

	private static String initial(String name) {
		for (int i = 0; i < name.length(); ) {
			int cp = name.codePointAt(i);
			if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
			i += Character.charCount(cp);
		}
		return "W";
	}

	/** Letztes Zeichen weg (ohne Ersatzzeichen-Paare zu trennen). */
	private static String cut(String s) {
		int end = s.offsetByCodePoints(s.length(), -1);
		return s.substring(0, end);
	}

	private static boolean inWorld(long x, long y, long z) {
		return Math.abs(x) <= ChatCoords.MAX_XZ && Math.abs(z) <= ChatCoords.MAX_XZ && y >= ChatCoords.MIN_Y && y <= ChatCoords.MAX_Y;
	}

	private static boolean overlaps(List<ChatCoords.Hit> hits, int start, int end) {
		for (ChatCoords.Hit h : hits) if (start < h.end && end > h.start) return true;
		return false;
	}
}
