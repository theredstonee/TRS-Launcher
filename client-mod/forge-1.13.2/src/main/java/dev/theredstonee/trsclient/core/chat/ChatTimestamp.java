package dev.theredstonee.trsclient.core.chat;

import java.util.Locale;

/** Zeitstempel vor Chat-Nachrichten ("[12:34] "). */
public final class ChatTimestamp {
	private ChatTimestamp() {
	}

	/**
	 * Formatiert die Uhrzeit als "[HH:MM]" bzw. "[HH:MM:SS]".
	 *
	 * @param hour24 Stunde 0–23
	 */
	public static String format(int hour24, int minute, int second, boolean withSeconds, boolean twelveHour) {
		int h = hour24;
		String suffix = "";
		if (twelveHour) {
			suffix = h < 12 ? " AM" : " PM";
			h = h % 12;
			if (h == 0) h = 12;
		}
		String time = withSeconds
				? String.format(Locale.ROOT, "%02d:%02d:%02d", h, minute, second)
				: String.format(Locale.ROOT, "%02d:%02d", h, minute);
		return "[" + time + suffix + "] ";
	}
}
