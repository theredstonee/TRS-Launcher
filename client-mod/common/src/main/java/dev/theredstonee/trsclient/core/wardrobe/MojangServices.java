package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.skin.MojangProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mojang-Dienste für die Garderobe (mit dem Zugangs-Token der laufenden Sitzung, nur im Hintergrund):
 * Profil mit Skins/Umhängen lesen, Skin hochladen (aktiver Minecraft-Skin), Umhang wählen/ablegen, Spieler per
 * Name nachschlagen. Token und Antworten landen nie im Log.
 *
 * <p>Adressen: {@code https://api.minecraftservices.com} und {@code https://api.mojang.com}; für Tests per
 * {@code -Dtrsclient.services.url} / {@code -Dtrsclient.mojang.url} umstellbar (http nur für localhost).
 */
public final class MojangServices {
	public static final String SERVICES = "https://api.minecraftservices.com";
	public static final String MOJANG_API = "https://api.mojang.com";
	private static final Gson GSON = new Gson();

	private final Http http;
	private final String services;
	private final String mojang;

	public MojangServices(Http http) {
		this(http, base(System.getProperty("trsclient.services.url"), SERVICES),
				base(System.getProperty("trsclient.mojang.url"), MOJANG_API));
	}

	public MojangServices(Http http, String services, String mojang) {
		this.http = http;
		this.services = services;
		this.mojang = mojang;
	}

	/** Fehler mit Code für die Oberfläche ({@code wardrobe.error.<code>}). */
	public static final class ServiceException extends Exception {
		public final String code;
		public final int status;

		public ServiceException(int status, String code) {
			super("HTTP " + status + " " + code);
			this.status = status;
			this.code = code;
		}
	}

	// --- DTOs (Felder, Gson 2.2.4) ---

	static final class ProfileJson {
		String id;
		String name;
		List<TextureJson> skins;
		List<TextureJson> capes;
	}

	static final class TextureJson {
		String id;
		String state;
		String url;
		String variant;
		String alias;
	}

	static final class CapeRequest {
		String capeId;
	}

	static final class NameJson {
		String id;
		String name;
	}

	/** Ein Umhang des Kontos. */
	public static final class Cape {
		public final String id;
		public final String alias;
		/** https-Adresse bei textures.minecraft.net oder null. */
		public final String url;
		public final boolean active;

		public Cape(String id, String alias, String url, boolean active) {
			this.id = id;
			this.alias = alias;
			this.url = url;
			this.active = active;
		}
	}

	/** Profil: Name, aktiver Skin, Umhänge. */
	public static final class Profile {
		public final String uuid;
		public final String name;
		public final String skinUrl;
		public final boolean slim;
		public final List<Cape> capes;

		public Profile(String uuid, String name, String skinUrl, boolean slim, List<Cape> capes) {
			this.uuid = uuid;
			this.name = name;
			this.skinUrl = skinUrl;
			this.slim = slim;
			this.capes = capes;
		}

		/** Aktiver Umhang oder null. */
		public Cape activeCape() {
			for (Cape c : capes) if (c.active) return c;
			return null;
		}
	}

	/** {@code GET /minecraft/profile}. */
	public Profile profile(String token) throws IOException, ServiceException {
		Http.Request req = new Http.Request("GET", services + "/minecraft/profile");
		auth(req, token);
		Http.Response res = http.send(req);
		check(res, 200);
		return parseProfile(res.text());
	}

	/**
	 * Setzt den aktiven Skin: {@code POST /minecraft/profile/skins} (multipart: variant + file).
	 *
	 * @return das neue Profil
	 */
	public Profile uploadSkin(String token, byte[] png, boolean slim) throws IOException, ServiceException {
		if (png == null || png.length > SkinFiles.MAX_BYTES) throw new ServiceException(0, "too_large");
		String boundary = "----TRSClient" + Long.toHexString(new SecureRandom().nextLong());
		ByteArrayOutputStream body = new ByteArrayOutputStream(png.length + 512);
		String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n" + (slim ? "slim" : "classic")
				+ "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n";
		body.write(head.getBytes(StandardCharsets.US_ASCII));
		body.write(png);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
		Http.Request req = new Http.Request("POST", services + "/minecraft/profile/skins");
		auth(req, token);
		req.header("Content-Type", "multipart/form-data; boundary=" + boundary);
		req.body = body.toByteArray();
		Http.Response res = http.send(req);
		check(res, 200);
		return res.body.length == 0 ? null : parseProfile(res.text());
	}

	/** Umhang tragen ({@code PUT …/capes/active}) bzw. ablegen ({@code capeId} null → DELETE). */
	public Profile setCape(String token, String capeId) throws IOException, ServiceException {
		Http.Request req;
		if (capeId == null) {
			req = new Http.Request("DELETE", services + "/minecraft/profile/capes/active");
		} else {
			if (!capeId.matches("[0-9a-fA-F-]{8,64}")) throw new ServiceException(0, "invalid_request");
			CapeRequest c = new CapeRequest();
			c.capeId = capeId;
			req = new Http.Request("PUT", services + "/minecraft/profile/capes/active");
			req.header("Content-Type", "application/json");
			req.body = GSON.toJson(c).getBytes(StandardCharsets.UTF_8);
		}
		auth(req, token);
		Http.Response res = http.send(req);
		check(res, 200);
		return res.body.length == 0 ? null : parseProfile(res.text());
	}

	/** UUID (32 Hex) zu einem Spielernamen oder null, wenn es ihn nicht gibt. */
	public String uuidOf(String name) throws IOException, ServiceException {
		if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) throw new ServiceException(0, "invalid_name");
		Http.Response res = http.send(new Http.Request("GET", mojang + "/users/profiles/minecraft/" + name));
		if (res.status == 204 || res.status == 404) return null;
		check(res, 200);
		try {
			NameJson n = GSON.fromJson(res.text(), NameJson.class);
			String id = n == null || n.id == null ? null : n.id.replace("-", "").toLowerCase(Locale.ROOT);
			return id != null && id.matches("[0-9a-f]{32}") ? id : null;
		} catch (JsonSyntaxException | IllegalStateException e) {
			throw new ServiceException(res.status, "invalid_json");
		}
	}

	/** Lädt eine Textur von textures.minecraft.net (≤ 128 KiB). */
	public byte[] texture(String url) throws IOException, ServiceException {
		String safe = MojangProfile.safeUrl(url);
		if (safe == null) throw new ServiceException(0, "invalid_url");
		Http.Request req = new Http.Request("GET", safe);
		req.maxBytes = SkinFiles.MAX_BYTES;
		Http.Response res = http.send(req);
		check(res, 200);
		return res.body;
	}

	static Profile parseProfile(String json) throws ServiceException {
		ProfileJson p;
		try {
			p = GSON.fromJson(json, ProfileJson.class);
		} catch (JsonSyntaxException | IllegalStateException e) {
			throw new ServiceException(200, "invalid_json");
		}
		if (p == null) throw new ServiceException(200, "invalid_json");
		String skinUrl = null;
		boolean slim = false;
		if (p.skins != null) {
			for (TextureJson s : p.skins) {
				if (s == null || !"ACTIVE".equalsIgnoreCase(s.state)) continue;
				skinUrl = MojangProfile.safeUrl(s.url);
				slim = "SLIM".equalsIgnoreCase(s.variant);
			}
		}
		List<Cape> capes = new ArrayList<Cape>();
		if (p.capes != null) {
			for (TextureJson c : p.capes) {
				if (c == null || c.id == null || !c.id.matches("[0-9a-fA-F-]{8,64}")) continue;
				String alias = c.alias == null ? "" : SkinFiles.cleanName(c.alias, "");
				capes.add(new Cape(c.id, alias, MojangProfile.safeUrl(c.url), "ACTIVE".equalsIgnoreCase(c.state)));
				if (capes.size() >= 64) break;
			}
		}
		String uuid = p.id == null ? null : p.id.replace("-", "").toLowerCase(Locale.ROOT);
		return new Profile(uuid, p.name, skinUrl, slim, capes);
	}

	private static void auth(Http.Request req, String token) throws ServiceException {
		if (token == null || token.length() < 8 || token.equals("0")) throw new ServiceException(401, "no_session");
		req.header("Authorization", "Bearer " + token);
		req.header("Accept", "application/json");
	}

	private static void check(Http.Response res, int expected) throws ServiceException {
		if (res.status == expected) return;
		String code;
		if (res.status == 401 || res.status == 403) code = "session_expired";
		else if (res.status == 429) code = "rate_limited";
		else if (res.status == 400) code = "rejected";
		else if (res.status == 404) code = "not_found";
		else code = "mojang_unavailable";
		throw new ServiceException(res.status, code);
	}

	/** Basisadresse aus einer System-Eigenschaft: https beliebig, http nur für localhost; sonst Standard. */
	static String base(String raw, String fallback) {
		if (raw == null || raw.trim().isEmpty()) return fallback;
		try {
			URI u = new URI(raw.trim());
			String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
			String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
			boolean local = host.equals("localhost") || host.equals("127.0.0.1");
			if (host.isEmpty() || !(scheme.equals("https") || (scheme.equals("http") && local))) return fallback;
			return scheme + "://" + host + (u.getPort() > 0 ? ":" + u.getPort() : "");
		} catch (Exception e) {
			return fallback;
		}
	}
}
