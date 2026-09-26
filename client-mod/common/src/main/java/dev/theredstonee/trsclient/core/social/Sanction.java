package dev.theredstonee.trsclient.core.social;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Eine eigene Strafe, wie der Spieler sie sieht (API.md §22.8 {@code MySanctionView}): Art, Vorlage für den Grund,
 * öffentlicher Grund, Zeitraum, Stand und Einspruch. Nie Notiz oder Moderator. Unveränderlich; bereinigt über
 * {@link SanctionJson}.
 */
public final class Sanction {
	/** Arten (API.md §22.2); unbekannte zeigt die Oberfläche als „Strafe“. */
	public static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList(
			"warn", "chat_mute", "social_ban", "upload_ban", "hosting_ban", "account_ban"));
	/** Vorlagen für den Grund (die letzten drei setzt nur das System). */
	public static final List<String> REASONS = Collections.unmodifiableList(Arrays.asList(
			"spam", "insult_hate", "harassment", "inappropriate_content", "inappropriate_name", "scam_phishing",
			"impersonation", "copyright", "cheating", "ban_evasion", "other", "auto_spam", "auto_reports", "legacy"));

	public final long id;
	/** Eine aus {@link #KINDS} oder {@code "unknown"}. */
	public final String kind;
	/** Eine aus {@link #REASONS} (unbekannt → {@code "other"}). */
	public final String reasonCode;
	/** Öffentlicher Grund (einzeilig bereinigt) oder null. */
	public final String reason;
	public final long startsAt;
	/** Ende (Epoch-ms); 0 = dauerhaft (automatische Stummschaltung: bis zur Prüfung). */
	public final long endsAt;
	/** active | expired | lifted. */
	public final String status;
	public final long liftedAt;
	/** Einspruch oder null. */
	public final Appeal appeal;
	/** Einspruch möglich (aktiv, noch keiner eingelegt). */
	public final boolean appealable;

	/** Einspruch des Spielers (API.md §22.8). */
	public static final class Appeal {
		public final long id;
		/** open | lifted | shortened | upheld. */
		public final String status;
		public final long createdAt;
		public final long decidedAt;
		/** Antwort des Teams (mehrzeilig bereinigt) oder null. */
		public final String response;

		public Appeal(long id, String status, long createdAt, long decidedAt, String response) {
			this.id = id;
			this.status = status;
			this.createdAt = createdAt;
			this.decidedAt = decidedAt;
			this.response = response;
		}

		public boolean open() {
			return "open".equals(status);
		}
	}

	public Sanction(long id, String kind, String reasonCode, String reason, long startsAt, long endsAt, String status,
			long liftedAt, Appeal appeal, boolean appealable) {
		this.id = id;
		this.kind = kind;
		this.reasonCode = reasonCode;
		this.reason = reason;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.status = status;
		this.liftedAt = liftedAt;
		this.appeal = appeal;
		this.appealable = appealable;
	}

	public boolean permanent() {
		return endsAt <= 0;
	}

	/** Gilt jetzt noch (Stand „active“ und Ende nicht erreicht)? */
	public boolean active(long now) {
		return "active".equals(status) && (endsAt <= 0 || endsAt > now);
	}

	public boolean lifted() {
		return "lifted".equals(status);
	}

	/** Automatische Stummschaltung ohne Ende: gilt bis ein Moderator geprüft hat. */
	public boolean untilReview() {
		return endsAt <= 0 && reasonCode.startsWith("auto_");
	}

	/** Einspruch jetzt möglich (nur aktive, nur einmal)? */
	public boolean canAppeal(long now) {
		return appealable && appeal == null && active(now);
	}

	/** Gleiche Strafe, aber zeitlich abgelaufen (Stand „expired“). */
	Sanction expired() {
		return new Sanction(id, kind, reasonCode, reason, startsAt, endsAt, "expired", liftedAt, appeal, false);
	}

	/** Wiegt schwerer (für Banner/Reihenfolge): Kontosperre vor Sozialsperre … vor Verwarnung. */
	public int severity() {
		int i = KINDS.indexOf(kind);
		return i < 0 ? 0 : i;
	}
}
