package dev.theredstonee.trsclient.core.sync;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Zeitangaben des Syncs: ISO 8601 (UTC, Millisekunden) ⇄ Millisekunden seit 1970. */
public final class SyncTime {
	private SyncTime() {
	}

	/** z. B. {@code 2026-09-25T10:00:00.000Z}. */
	public static String iso(long millis) {
		String s = Instant.ofEpochMilli(millis).toString();
		// Instant lässt ".000" weg – immer mit Millisekunden schreiben (gleiche Länge, gleiche Sortierung).
		if (s.length() == 20 && s.endsWith("Z")) s = s.substring(0, 19) + ".000Z";
		return s;
	}

	/** ISO-Zeit → Millisekunden; ungültig/null → -1. */
	public static long parse(String iso) {
		if (iso == null || iso.length() > 40) return -1;
		try {
			return Instant.parse(iso).toEpochMilli();
		} catch (DateTimeParseException e) {
			try {
				return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
			} catch (DateTimeParseException e2) {
				return -1;
			}
		}
	}
}
