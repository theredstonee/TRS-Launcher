package dev.theredstonee.trsclient.core.bugreport;

import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Die zwei Aufrufe von „Bug melden“ (API.md §28), blockierend – nur aus dem Hintergrund-Thread:
 * {@code POST /v1/issues/uploads} (Bild roh, ≤ 8 MB) und {@code POST /v1/issues}. Fehler kommen als
 * {@link ApiException} mit dem Code des Servers ({@code invalid_request}, {@code issue_daily_limit},
 * {@code sanctioned}, {@code upload_not_found}, …); keine Verbindung = {@link IOException}.
 */
public final class BugReportApi {
	public static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
	private static final String JSON = "application/json";

	private final Http http;
	private final String base;

	public BugReportApi(Http http, String apiBase) {
		this.http = http;
		this.base = apiBase;
	}

	/** Bild hochladen → Upload-ID (22 Zeichen). */
	public String upload(String token, byte[] image, String mime) throws IOException, ApiException {
		if (image == null || image.length == 0 || image.length > MAX_UPLOAD_BYTES) throw new ApiException(413, "payload_too_large", 0);
		if (!"image/png".equals(mime) && !"image/jpeg".equals(mime) && !"image/webp".equals(mime)) {
			throw new ApiException(415, "unsupported_media_type", 0);
		}
		Http.Request req = new Http.Request("POST", base + "/v1/issues/uploads").header("Accept", JSON)
				.header("Content-Type", mime).header("Authorization", "Bearer " + token);
		req.body = image;
		Http.Response res = http.send(req);
		if (res.status != 201 && res.status != 200) throw error(res);
		String id = BugReportBody.uploadId(res.text());
		if (id == null) throw new ApiException(res.status, "invalid_response", 0);
		return id;
	}

	/** Issue anlegen → Nummer (+ Link auf die Website, falls gültig). */
	public BugReportBody.Created create(String token, JsonObject body) throws IOException, ApiException {
		Http.Request req = new Http.Request("POST", base + "/v1/issues").header("Accept", JSON).header("Content-Type", JSON)
				.header("Authorization", "Bearer " + token);
		req.body = body.toString().getBytes(StandardCharsets.UTF_8);
		Http.Response res = http.send(req);
		if (res.status != 201 && res.status != 200) throw error(res);
		BugReportBody.Created created = BugReportBody.created(res.text());
		if (created == null) throw new ApiException(res.status, "invalid_response", 0);
		return created;
	}

	private static ApiException error(Http.Response res) {
		return new ApiException(res.status, TrsApi.errorCode(res), TrsApi.retryAfter(res), res.status == 403 ? res.text() : null);
	}
}
