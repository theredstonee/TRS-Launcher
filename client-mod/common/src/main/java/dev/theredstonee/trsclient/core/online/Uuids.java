package dev.theredstonee.trsclient.core.online;

import java.util.Locale;
import java.util.UUID;

/** UUIDs in der Form der TRS API: 32 kleingeschriebene Hex-Ziffern ohne Bindestriche. */
public final class Uuids {
	private Uuids() {
	}

	/** "75c1a6f3-1122-…" oder "75C1…" → "75c1a6f3112240ab…"; ungültig → null. */
	public static String normalize(String raw) {
		if (raw == null) return null;
		String t = raw.trim().replace("-", "").toLowerCase(Locale.ROOT);
		if (t.length() != 32) return null;
		for (int i = 0; i < 32; i++) {
			char c = t.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return null;
		}
		return t;
	}

	public static String of(UUID uuid) {
		if (uuid == null) return null;
		return normalize(uuid.toString());
	}
}
