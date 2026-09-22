package dev.theredstonee.trsclient.core.chat;

/**
 * Gemeinsame Regeln für Nachrichten, die der Client selbst sendet (Auto-GG, Text-Hotkeys):
 * säubern, kürzen und Befehle erkennen. Nichts wird automatisch wiederholt.
 */
public final class ChatOut {
	/** Maximale Länge einer Chat-Nachricht in Minecraft. */
	public static final int MAX_LENGTH = 256;

	private ChatOut() {
	}

	/** Entfernt Steuerzeichen/Zeilenumbrüche und kürzt auf die Maximallänge; "" = nichts senden. */
	public static String sanitize(String text) {
		if (text == null) return "";
		StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_LENGTH));
		for (int i = 0; i < text.length() && sb.length() < MAX_LENGTH; i++) {
			char c = text.charAt(i);
			// § (Formatierung) und Steuerzeichen sind im Chat nicht erlaubt.
			if (c == '\n' || c == '\r' || c == '\t') {
				sb.append(' ');
			} else if (c >= ' ' && c != 127 && c != '§') {
				sb.append(c);
			}
		}
		return sb.toString().trim();
	}

	/** Beginnt der Text mit "/"? Dann ist es ein Befehl (ohne den Schrägstrich senden). */
	public static boolean isCommand(String sanitized) {
		return sanitized.length() > 1 && sanitized.charAt(0) == '/';
	}

	/** Befehl ohne führenden "/" (für sendCommand). */
	public static String command(String sanitized) {
		return isCommand(sanitized) ? sanitized.substring(1) : sanitized;
	}
}
