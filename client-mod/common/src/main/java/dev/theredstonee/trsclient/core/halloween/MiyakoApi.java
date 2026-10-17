package dev.theredstonee.trsclient.core.halloween;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Private MiyakoMC-Endpunkte (Vertrag Part A) der TRS API. Nur der angemeldete Spieler mit MiyakoMCs UUID bekommt hier
 * etwas; für alle anderen antwortet der Server mit 404 – nichts leckt. Die Medien (Bild-Folge, Ton, Jumpscare-Bild)
 * liegen als privates Paket auf dem Server und werden NIE in die Mod/JARs gepackt.
 *
 * <ul>
 *   <li>{@code GET /v1/me/miyako} → {@code {introDone, timerMaxMs}}</li>
 *   <li>{@code GET /v1/me/miyako/bundle} → ZIP (frames/*.jpg, intro.ogg, jumpscare.png)</li>
 *   <li>{@code POST /v1/me/miyako/intro-done}</li>
 * </ul>
 *
 * <p>Angemeldet wird wie überall mit dem Mojang-Join-Token aus {@code TrsOnline} (Bearer). Blockierend – nur aus dem
 * Hintergrund-Thread von {@link Miyako}.
 */
public final class MiyakoApi {
	static final Gson GSON = new Gson();
	private static final String JSON = "application/json";
	/** Das Paket ist höchstens so groß (320 JPEGs ~ 5 MB + Ton + Bild). */
	public static final int MAX_BUNDLE = 16 * 1024 * 1024;

	private final Http http;
	private final String base;

	public MiyakoApi(Http http, String base) {
		this.http = http;
		this.base = base;
	}

	/** {@code introDone} (schon gesehen?) und {@code timerMaxMs} (obere Grenze des Zufalls-Timers). */
	public static final class Meta {
		public Boolean introDone;
		public Integer timerMaxMs;
	}

	/** Metadaten holen; 404 (kein Miyako) wird als {@link ApiException} mit Status 404 geworfen. */
	public Meta meta(String token) throws IOException, ApiException {
		Http.Response r = http.send(request("GET", "/v1/me/miyako", null, token));
		if (r.status != 200) throw new ApiException(r.status, HalloweenApi.errorCode(r), 0);
		try {
			Meta m = GSON.fromJson(r.text(), Meta.class);
			return m == null ? new Meta() : m;
		} catch (JsonSyntaxException | IllegalStateException | NumberFormatException e) {
			throw new ApiException(r.status, "invalid_json", 0);
		}
	}

	/** Das Medien-Paket als ZIP-Bytes; 404 (kein Miyako) → {@link ApiException}. */
	public byte[] bundle(String token) throws IOException, ApiException {
		Http.Request request = new Http.Request("GET", base + "/v1/me/miyako/bundle").header("Accept", "application/zip");
		if (token != null) request.header("Authorization", "Bearer " + token);
		request.maxBytes = MAX_BUNDLE;
		Http.Response r = http.send(request);
		if (r.status != 200) throw new ApiException(r.status, "http_" + r.status, 0);
		if (r.body == null || r.body.length == 0) throw new IOException("leeres Paket");
		return r.body;
	}

	/** Server-Flag setzen: das Intro wurde gesehen. */
	public void introDone(String token) throws IOException, ApiException {
		Http.Response r = http.send(request("POST", "/v1/me/miyako/intro-done", "{}", token));
		if (r.status != 200 && r.status != 204) throw new ApiException(r.status, HalloweenApi.errorCode(r), 0);
	}

	private Http.Request request(String method, String path, String json, String token) {
		Http.Request request = new Http.Request(method, base + path).header("Accept", JSON);
		if (json != null) {
			request.header("Content-Type", JSON);
			request.body = json.getBytes(StandardCharsets.UTF_8);
		}
		if (token != null) request.header("Authorization", "Bearer " + token);
		request.maxBytes = 64 * 1024;
		return request;
	}
}
