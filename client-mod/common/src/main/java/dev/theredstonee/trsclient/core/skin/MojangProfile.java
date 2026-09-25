package dev.theredstonee.trsclient.core.skin;

import com.google.gson.Gson;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Liest die Textur-Angaben eines Mojang-Profils (sessionserver: {@code /session/minecraft/profile/<uuid>}):
 * Skin-URL, Armform und Umhang-URL. URLs werden nur für {@code textures.minecraft.net} akzeptiert und auf
 * HTTPS gehoben (Mojang liefert sie als {@code http://}).
 */
public final class MojangProfile {
	public static final String HOST = "textures.minecraft.net";

	/** Skin-URL (https) oder null. */
	public final String skinUrl;
	public final boolean slim;
	/** Umhang-URL (https) oder null. */
	public final String capeUrl;

	MojangProfile(String skinUrl, boolean slim, String capeUrl) {
		this.skinUrl = skinUrl;
		this.slim = slim;
		this.capeUrl = capeUrl;
	}

	/** Adresse des Profils (UUID mit oder ohne Bindestriche); null bei ungültiger UUID. */
	public static String profileUrl(String uuid) {
		if (uuid == null) return null;
		String u = uuid.replace("-", "").toLowerCase(Locale.ROOT);
		if (!u.matches("[0-9a-f]{32}")) return null;
		return "https://sessionserver.mojang.com/session/minecraft/profile/" + u;
	}

	/** Gson-DTOs (Felder statt JsonParser – Minecraft 1.8.9 bringt Gson 2.2.4 mit). */
	static final class Response {
		java.util.List<Property> properties;
	}

	static final class Property {
		String name;
		String value;
	}

	static final class TexturesFile {
		java.util.Map<String, Texture> textures;
	}

	static final class Texture {
		String url;
		java.util.Map<String, String> metadata;
	}

	private static final Gson GSON = new Gson();

	/**
	 * Parst die Antwort des Sessionservers.
	 *
	 * @return Profil (URLs evtl. null) oder null bei unlesbarer Antwort
	 */
	public static MojangProfile parse(String json) {
		try {
			Response r = GSON.fromJson(json, Response.class);
			if (r == null) return null;
			if (r.properties != null) {
				for (Property p : r.properties) {
					if (p == null || !"textures".equals(p.name)) continue;
					if (p.value == null || p.value.length() > 16 * 1024) return null;
					String decoded = new String(Base64.getDecoder().decode(p.value.trim()), StandardCharsets.UTF_8);
					return parseTextures(decoded);
				}
			}
			return new MojangProfile(null, false, null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Der dekodierte Inhalt der {@code textures}-Eigenschaft. */
	static MojangProfile parseTextures(String json) {
		TexturesFile f = GSON.fromJson(json, TexturesFile.class);
		if (f == null) return null;
		if (f.textures == null) return new MojangProfile(null, false, null);
		Texture skin = f.textures.get("SKIN");
		Texture cape = f.textures.get("CAPE");
		boolean slim = skin != null && skin.metadata != null && "slim".equals(skin.metadata.get("model"));
		return new MojangProfile(skin == null ? null : safeUrl(skin.url), slim, cape == null ? null : safeUrl(cape.url));
	}

	/** Nur textures.minecraft.net, immer HTTPS, ohne Benutzer/Port/Query; sonst null. */
	static String safeUrl(String url) {
		if (url == null || url.length() > 300) return null;
		try {
			URI u = URI.create(url.trim());
			String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) return null;
			if (!HOST.equalsIgnoreCase(u.getHost()) || u.getUserInfo() != null || u.getPort() != -1 || u.getRawQuery() != null) return null;
			String path = u.getRawPath();
			if (path == null || !path.matches("/texture/[0-9a-fA-F]{1,80}")) return null;
			return "https://" + HOST + path;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
