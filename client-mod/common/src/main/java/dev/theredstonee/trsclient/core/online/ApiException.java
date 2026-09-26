package dev.theredstonee.trsclient.core.online;

/** Fehlerantwort der TRS API (oder von Mojang) mit stabilem Fehlercode. */
public final class ApiException extends Exception {
	private final int status;
	private final String code;
	/** Wartezeit aus {@code Retry-After} in Millisekunden (0 = keine Angabe). */
	private final long retryAfterMs;
	/** Fehlerkörper (JSON) mit Zusatzangaben, z. B. Strafe bei 403 (API.md §22) – sonst null. */
	private final String body;

	public ApiException(int status, String code, long retryAfterMs) {
		this(status, code, retryAfterMs, null);
	}

	public ApiException(int status, String code, long retryAfterMs, String body) {
		super("HTTP " + status + " " + code);
		this.status = status;
		this.code = code == null ? "" : code;
		this.retryAfterMs = retryAfterMs;
		this.body = body == null || body.length() > 16_384 ? null : body;
	}

	public String body() {
		return body;
	}

	public int status() {
		return status;
	}

	public String code() {
		return code;
	}

	public long retryAfterMs() {
		return retryAfterMs;
	}

	/** Token ungültig/abgelaufen → neu anmelden. */
	public boolean unauthorized() {
		return status == 401;
	}

	public boolean banned() {
		return status == 403 && "banned".equals(code);
	}

	public boolean rateLimited() {
		return status == 429;
	}
}
