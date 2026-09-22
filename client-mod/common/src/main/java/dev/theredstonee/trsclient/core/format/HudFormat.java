package dev.theredstonee.trsclient.core.format;

import java.time.LocalTime;
import java.util.Locale;

/** Text-Formatierungen der HUD-Module (versionsunabhängig, getestet). */
public final class HudFormat {
	private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
	/** Himmelsrichtungen ab Süden (Minecraft: Yaw 0 = Süden, 90 = Westen). */
	private static final String[] DIRECTIONS = {"S", "SW", "W", "NW", "N", "NO", "O", "SO"};
	private static final String[] DIRECTION_NAMES = {"Süden", "Südwesten", "Westen", "Nordwesten", "Norden", "Nordosten", "Osten", "Südosten"};

	private HudFormat() {
	}

	/** Restzeit aus Ticks: "0:45", "12:03", "1:02:03"; unendlich → "∞". */
	public static String duration(int ticks, boolean infinite) {
		if (infinite) return "∞";
		int total = Math.max(0, ticks) / 20;
		int h = total / 3600;
		int m = (total / 60) % 60;
		int s = total % 60;
		return h > 0
				? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
				: String.format(Locale.ROOT, "%d:%02d", m, s);
	}

	/** Stufe eines Effekts (Amplifier 0 → "", 1 → "II", … sonst Zahl). */
	public static String level(int amplifier) {
		if (amplifier <= 0) return "";
		int lvl = amplifier + 1;
		return lvl <= ROMAN.length ? ROMAN[lvl - 1] : Integer.toString(lvl);
	}

	/** Speicher in MB: "512 / 2048 MB (25 %)". */
	public static String memory(long usedBytes, long maxBytes) {
		long used = usedBytes / (1024 * 1024);
		long max = Math.max(1, maxBytes / (1024 * 1024));
		long pct = Math.round(used * 100.0 / max);
		return used + " / " + max + " MB (" + pct + " %)";
	}

	/** Koordinaten ohne Nachkommastellen (abgerundet, wie der Block unter den Füßen). */
	public static String coords(double x, double y, double z) {
		return "X " + (long) Math.floor(x) + "  Y " + (long) Math.floor(y) + "  Z " + (long) Math.floor(z);
	}

	/** Achtel der Blickrichtung (0 = Süden … 7 = Südosten) aus dem Yaw in Grad. */
	public static int octant(float yaw) {
		double d = ((yaw % 360) + 360) % 360;
		return (int) Math.floor((d + 22.5) / 45.0) % 8;
	}

	/** Kurzform der Blickrichtung, z. B. "NO". */
	public static String direction(float yaw) {
		return DIRECTIONS[octant(yaw)];
	}

	/** Ausgeschriebene Blickrichtung, z. B. "Nordosten". */
	public static String directionName(float yaw) {
		return DIRECTION_NAMES[octant(yaw)];
	}

	/** Uhrzeit "HH:mm" bzw. "HH:mm:ss", 24 h oder 12 h mit "AM/PM". */
	public static String clock(LocalTime time, boolean seconds, boolean twelveHour) {
		int h = time.getHour();
		String suffix = "";
		if (twelveHour) {
			suffix = h < 12 ? " AM" : " PM";
			h = h % 12 == 0 ? 12 : h % 12;
		}
		return seconds
				? String.format(Locale.ROOT, "%02d:%02d:%02d%s", h, time.getMinute(), time.getSecond(), suffix)
				: String.format(Locale.ROOT, "%02d:%02d%s", h, time.getMinute(), suffix);
	}

	/** Restliche Haltbarkeit in Prozent (0–100). */
	public static int durabilityPercent(int damage, int maxDamage) {
		if (maxDamage <= 0) return 100;
		int left = Math.max(0, maxDamage - damage);
		return (int) Math.round(left * 100.0 / maxDamage);
	}

	/** Farbe der Haltbarkeit (RGB): grün → gelb → rot, wie die Vanilla-Leiste. */
	public static int durabilityColor(int percent) {
		float f = Math.max(0, Math.min(100, percent)) / 100f;
		// Farbton 0° (rot) über 60° (gelb) bis 120° (grün), volle Sättigung/Helligkeit.
		int r = f <= 0.5f ? 255 : Math.round((1f - f) * 2f * 255f);
		int g = f >= 0.5f ? 255 : Math.round(f * 2f * 255f);
		return (r << 16) | (g << 8);
	}
}
