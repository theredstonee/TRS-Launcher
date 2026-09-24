package dev.theredstonee.trsclient.core.online;

/**
 * Zeilenweiser Parser für Server-Sent Events (text/event-stream): {@code event:}, {@code data:} (mehrere
 * Zeilen werden mit Zeilenumbruch verbunden), Kommentare ({@code :}) und unbekannte Felder werden übergangen.
 * Eine Leerzeile schließt ein Ereignis ab. Übergroße Ereignisse werden verworfen.
 */
public final class SseParser {
	/** Größte angenommene Datenmenge eines Ereignisses (Zeichen). */
	public static final int MAX_DATA = 64 * 1024;

	/** Ein fertiges Rohereignis. */
	public static final class Raw {
		public final String event;
		public final String data;

		Raw(String event, String data) {
			this.event = event;
			this.data = data;
		}
	}

	private String event;
	private StringBuilder data;
	private boolean overflow;

	/** Eine Zeile (ohne Zeilenende) verarbeiten; liefert ein Ereignis, wenn sie eines abschließt. */
	public Raw line(String line) {
		if (line == null) return null;
		if (line.isEmpty()) {
			Raw out = null;
			if (!overflow && (event != null || data != null)) out = new Raw(event, data == null ? "" : data.toString());
			event = null;
			data = null;
			overflow = false;
			return out;
		}
		if (line.charAt(0) == ':') return null;
		int colon = line.indexOf(':');
		String field = colon < 0 ? line : line.substring(0, colon);
		String value = colon < 0 ? "" : line.substring(colon + 1);
		if (value.startsWith(" ")) value = value.substring(1);
		if (field.equals("event")) {
			event = value;
		} else if (field.equals("data")) {
			if (data == null) data = new StringBuilder();
			else data.append('\n');
			if (data.length() + value.length() > MAX_DATA) overflow = true;
			else data.append(value);
		}
		return null;
	}
}
