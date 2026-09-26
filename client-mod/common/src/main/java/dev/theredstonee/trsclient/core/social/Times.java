package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.i18n.I18n;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Zeitangaben des Chats in der Sprache des Mods und der Zeitzone des Rechners. */
public final class Times {
	private Times() {
	}

	static ZoneId zone() {
		try {
			return ZoneId.systemDefault();
		} catch (RuntimeException e) {
			return ZoneId.of("UTC");
		}
	}

	static Locale locale() {
		Locale l = I18n.locale();
		return l == null ? Locale.ENGLISH : l;
	}

	/** "14:05" bzw. "2:05 PM". */
	public static String time(long ms) {
		if (ms <= 0) return "";
		return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale())
				.format(Instant.ofEpochMilli(ms).atZone(zone()));
	}

	/** Datum mit Uhrzeit, z. B. für „stumm bis …“. */
	public static String dateTime(long ms) {
		if (ms <= 0) return "";
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale())
				.format(Instant.ofEpochMilli(ms).atZone(zone()));
	}

	/** Trenner zwischen Tagen: „Heute“, „Gestern“, Wochentag (diese Woche) oder Datum. */
	public static String day(long ms, long now) {
		if (ms <= 0) return "";
		ZoneId z = zone();
		LocalDate d = Instant.ofEpochMilli(ms).atZone(z).toLocalDate();
		LocalDate today = Instant.ofEpochMilli(now).atZone(z).toLocalDate();
		long days = ChronoUnit.DAYS.between(d, today);
		if (days == 0) return I18n.tr("social.day.today");
		if (days == 1) return I18n.tr("social.day.yesterday");
		if (days > 1 && days < 7) return d.getDayOfWeek().getDisplayName(TextStyle.FULL, locale());
		return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale()).format(d);
	}

	/** Kurz für die Liste: heute Uhrzeit, diese Woche Wochentag, sonst Datum. */
	public static String shortLabel(long ms, long now) {
		if (ms <= 0) return "";
		ZoneId z = zone();
		ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(z);
		LocalDate today = Instant.ofEpochMilli(now).atZone(z).toLocalDate();
		long days = ChronoUnit.DAYS.between(t.toLocalDate(), today);
		if (days == 0) return time(ms);
		if (days > 0 && days < 7) return t.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale());
		return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale()).format(t);
	}

	/** Gleicher Kalendertag? */
	public static boolean sameDay(long a, long b) {
		if (a <= 0 || b <= 0) return false;
		ZoneId z = zone();
		return Instant.ofEpochMilli(a).atZone(z).toLocalDate().equals(Instant.ofEpochMilli(b).atZone(z).toLocalDate());
	}
}
