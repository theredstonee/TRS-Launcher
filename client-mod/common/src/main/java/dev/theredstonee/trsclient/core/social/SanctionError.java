package dev.theredstonee.trsclient.core.social;

import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.online.ApiException;

/**
 * Fehler {@code 403 sanctioned | chat_muted | banned} samt Angaben zur Strafe (API.md §22.2): {@code until} und
 * {@code sanction}; beim gesperrten Login zusätzlich {@code appealToken} (nur für Einsprüche, 1 h).
 * Fehlende Felder sind erlaubt – dann bleibt es beim Code.
 */
public final class SanctionError {
	public final String code;
	/** Ende laut Fehler (0 = dauerhaft/unbekannt). */
	public final long until;
	/** Strafe oder null (ältere Server). */
	public final Sanction sanction;
	/** Einspruch-Token (nur Login eines gesperrten Kontos) oder null. Nur im Speicher, nie loggen. */
	public final String appealToken;
	public final long appealTokenExpiresAt;

	SanctionError(String code, long until, Sanction sanction, String appealToken, long appealTokenExpiresAt) {
		this.code = code;
		this.until = until;
		this.sanction = sanction;
		this.appealToken = appealToken;
		this.appealTokenExpiresAt = appealTokenExpiresAt;
	}

	/** Gehört dieser Code zu einer Strafe? */
	public static boolean isSanctionCode(String code) {
		return "sanctioned".equals(code) || "chat_muted".equals(code) || "banned".equals(code);
	}

	/** Aus einer ApiException (Status 403 + Körper) oder null. */
	public static SanctionError of(ApiException e) {
		if (e == null || e.status() != 403 || !isSanctionCode(e.code())) return null;
		SanctionError parsed = parse(e.body());
		return parsed != null ? parsed : new SanctionError(e.code(), 0, null, null, 0);
	}

	/** Fehlerkörper {@code {"error": {...}}} → Angaben oder null (kein Strafen-Fehler / kaputt). */
	public static SanctionError parse(String body) {
		JsonObject error = SanctionJson.obj(SanctionJson.object(body), "error");
		String code = SanctionJson.str(error, "code");
		if (!isSanctionCode(code)) return null;
		Sanction s = SanctionJson.sanction(SanctionJson.obj(error, "sanction"));
		long until = ChatJson.time(SanctionJson.str(error, "until"));
		if (until == 0 && s != null) until = s.endsAt;
		String token = SanctionJson.str(error, "appealToken");
		if (token != null && !token.matches("trs_[A-Za-z0-9_-]{20,100}")) token = null;
		long expires = token == null ? 0 : ChatJson.time(SanctionJson.str(error, "appealTokenExpiresAt"));
		return new SanctionError(code, until, s, token, expires);
	}

	/** Die Art der Strafe (aus {@code sanction}, sonst aus dem Code geschlossen). */
	public String kind() {
		if (sanction != null && !"unknown".equals(sanction.kind)) return sanction.kind;
		if ("chat_muted".equals(code)) return "chat_mute";
		if ("banned".equals(code)) return "account_ban";
		return "unknown";
	}
}
