package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.i18n.I18n;

/** Texte zu Strafen in der Sprache des Mods: Art, Grund, Ende (relativ + Datum), Einspruch. */
public final class SanctionText {
	static final long MINUTE = 60_000L;
	static final long HOUR = 60 * MINUTE;
	static final long DAY = 24 * HOUR;

	private SanctionText() {
	}

	/** „Chat-Stummschaltung“ … (unbekannte Art: „Strafe“). */
	public static String kind(String kind) {
		return I18n.tr("sanction.kind." + (Sanction.KINDS.contains(kind) ? kind : "unknown"));
	}

	/** Was die Strafe sperrt (ein Satz). */
	public static String effect(String kind) {
		return I18n.tr("sanction.effect." + (Sanction.KINDS.contains(kind) ? kind : "unknown"));
	}

	/** Vorlage des Grundes übersetzt. */
	public static String reasonTemplate(String code) {
		return I18n.tr("sanction.reason." + (Sanction.REASONS.contains(code) ? code : "other"));
	}

	/** Grund: Vorlage, dazu der öffentliche Text – „Spam – „Werbung im Chat““. */
	public static String reason(Sanction s) {
		String t = reasonTemplate(s.reasonCode);
		return s.reason == null ? t : I18n.tr("sanction.reasonWithText", t, s.reason);
	}

	/**
	 * Restzeit kurz: „noch 3 Tage“, „noch 5 Std.“, „noch 12 Min.“, „gleich vorbei“. Tage ab 48 h (abgerundet), Stunden
	 * ab 60 min (abgerundet), darunter Minuten (aufgerundet).
	 */
	public static String remaining(long endsAt, long now) {
		long left = endsAt - now;
		if (left < MINUTE) return I18n.tr("sanction.left.soon");
		if (left < HOUR) return I18n.tr("sanction.left.minutes", (left + MINUTE - 1) / MINUTE);
		if (left < 2 * DAY) {
			long h = left / HOUR;
			return h == 1 ? I18n.tr("sanction.left.hour") : I18n.tr("sanction.left.hours", h);
		}
		return I18n.tr("sanction.left.days", left / DAY);
	}

	/** Ende einer aktiven Strafe: „noch 2 Tage (bis 28.09.2026, 15:52)“ bzw. „dauerhaft“ / „bis zur Prüfung“. */
	public static String end(Sanction s, long now) {
		if (s.untilReview()) return I18n.tr("sanction.untilReview");
		if (s.permanent()) return I18n.tr("sanction.permanent");
		return I18n.tr("sanction.endsIn", remaining(s.endsAt, now), Times.dateTime(s.endsAt));
	}

	/** Kurz für Hinweise/Toasts: „noch 2 Tage“ bzw. „dauerhaft“. */
	public static String endShort(Sanction s, long now) {
		if (s.untilReview()) return I18n.tr("sanction.untilReview");
		if (s.permanent()) return I18n.tr("sanction.permanent");
		return remaining(s.endsAt, now);
	}

	/** Zeitraum einer Strafe für die Liste: aktiv → Ende, sonst „abgelaufen am …“ / „aufgehoben am …“. */
	public static String period(Sanction s, long now) {
		if (s.lifted()) return I18n.tr("sanction.liftedAt", Times.dateTime(s.liftedAt > 0 ? s.liftedAt : s.endsAt));
		if (!s.active(now)) return I18n.tr("sanction.expiredAt", Times.dateTime(s.endsAt));
		return end(s, now);
	}

	/** Stand des Einspruchs: „Einspruch wird geprüft“ … */
	public static String appealStatus(Sanction.Appeal a) {
		return I18n.tr("sanction.appeal.status." + a.status);
	}

	/** Ein Satz für einen gesperrten Vorgang: „Chat-Stummschaltung – noch 2 Tage“. */
	public static String blocked(SanctionError e, long now) {
		String kind = kind(e.kind());
		if (e.sanction != null) return I18n.tr("sanction.blocked", kind, endShort(e.sanction, now));
		if (e.until > 0) return I18n.tr("sanction.blocked", kind, remaining(e.until, now));
		return kind;
	}

	/** Banner-Zeile: „Aktive Strafe: Chat-Stummschaltung – noch 2 Tage“ (+ „und 1 weitere“). */
	public static String banner(Sanction s, int count, long now) {
		String text = I18n.tr("sanction.banner", kind(s.kind), endShort(s, now));
		return count > 1 ? text + " " + I18n.tr("sanction.bannerMore", count - 1) : text;
	}
}
