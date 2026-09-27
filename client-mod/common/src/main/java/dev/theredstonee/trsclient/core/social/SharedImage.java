package dev.theredstonee.trsclient.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ein als Link geteiltes Bild (API.md §23). Alle Adressen baut der Mod selbst aus der API-Basis und der geprüften ID –
 * nie aus der Antwort übernommen (kein fremder Host, keine Umleitung auf andere Seiten).
 */
public final class SharedImage {
	/** 128 Bit Zufall als base64url (22 Zeichen). */
	private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{22}");
	/** Gültigkeit laut API (für Anzeigen ohne Antwort). */
	public static final long LIFETIME_MS = 30L * 24 * 60 * 60 * 1000;

	public final String id;
	public final String url;
	public final String imageUrl;
	public final String thumbUrl;
	public final boolean png;
	public final int width;
	public final int height;
	public final long bytes;
	public final long createdAt;
	public final long expiresAt;

	SharedImage(String base, String id, boolean png, int width, int height, long bytes, long createdAt, long expiresAt) {
		this.id = id;
		this.url = base + "/s/" + id;
		this.imageUrl = base + "/v1/shares/" + id + "/image";
		this.thumbUrl = imageUrl + "?thumb=1";
		this.png = png;
		this.width = width;
		this.height = height;
		this.bytes = bytes;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public static boolean validId(String id) {
		return id != null && ID.matcher(id).matches();
	}

	/** Abgelaufen (dann zeigt die Liste es nicht mehr)? */
	public boolean expired(long now) {
		return expiresAt > 0 && expiresAt <= now;
	}

	// --- JSON ---

	static final class Dto {
		String id;
		String mime;
		Integer width;
		Integer height;
		Long bytes;
		String createdAt;
		String expiresAt;
	}

	static final class Envelope {
		Dto share;
	}

	static final class LimitsDto {
		Integer active;
		Integer maxActive;
		Integer uploadsToday;
		Integer maxPerDay;
	}

	static final class ListBody {
		List<Dto> shares;
		LimitsDto limits;
	}

	/** Bereinigt; ungültig → null. {@code base} = API-Basis ohne Schrägstrich am Ende. */
	static SharedImage of(String base, Dto d) {
		if (d == null || !validId(d.id)) return null;
		long created = ChatJson.time(d.createdAt);
		long expires = ChatJson.time(d.expiresAt);
		if (expires == 0 && created > 0) expires = created + LIFETIME_MS;
		return new SharedImage(base, d.id, "image/png".equals(d.mime), ChatJson.clamp(d.width, 1, 8192),
				ChatJson.clamp(d.height, 1, 8192), d.bytes == null ? 0 : Math.max(0, d.bytes), created, expires);
	}

	/** Eigene geteilte Bilder + Grenzen ({@code GET /v1/shares}). */
	public static final class Listing {
		public final List<SharedImage> shares;
		public final int active;
		public final int maxActive;
		public final int uploadsToday;
		public final int maxPerDay;

		Listing(List<SharedImage> shares, int active, int maxActive, int uploadsToday, int maxPerDay) {
			this.shares = Collections.unmodifiableList(new ArrayList<SharedImage>(shares));
			this.active = active;
			this.maxActive = maxActive;
			this.uploadsToday = uploadsToday;
			this.maxPerDay = maxPerDay;
		}

		static Listing of(String base, ListBody body) {
			List<SharedImage> out = new ArrayList<SharedImage>();
			if (body != null && body.shares != null) {
				for (Dto d : body.shares) {
					SharedImage s = SharedImage.of(base, d);
					if (s != null && out.size() < 200) out.add(s);
				}
			}
			LimitsDto l = body == null ? null : body.limits;
			int active = l == null ? out.size() : ChatJson.clamp(l.active, 0, 10_000);
			int maxActive = l == null ? 50 : ChatJson.clamp(l.maxActive, 0, 10_000);
			int today = l == null ? 0 : ChatJson.clamp(l.uploadsToday, 0, 10_000);
			int perDay = l == null ? 20 : ChatJson.clamp(l.maxPerDay, 0, 10_000);
			return new Listing(out, active, maxActive, today, perDay);
		}
	}
}
