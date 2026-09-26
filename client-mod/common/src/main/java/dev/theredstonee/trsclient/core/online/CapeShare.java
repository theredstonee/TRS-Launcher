package dev.theredstonee.trsclient.core.online;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Umhänge mit Freunden teilen (API.md §5.10): Angebote an mich und die Inhaber eines eigenen bzw. weitergeteilten
 * Umhangs. Alles unveränderlich und streng bereinigt (ungültige IDs/Namen fallen weg).
 */
public final class CapeShare {
	/** Umhang-IDs wie in der API ({@code ^[a-z0-9][a-z0-9_-]{0,39}$}). */
	static final String CAPE_ID = "[a-z0-9][a-z0-9_-]{0,39}";

	private CapeShare() {
	}

	/** Offenes Angebot eines Freundes an mich (mit allem, was die Vorschau braucht). */
	public static final class Offer {
		public final String capeId;
		public final String capeName;
		public final String fromUuid;
		public final String fromName;
		/** Wer den Umhang gemacht hat (bei Weitergeteiltem ≠ {@link #fromName}). */
		public final String creatorUuid;
		public final String creatorName;
		/** Textur-Adresse (API) oder null. */
		public final String url;
		public final int width;
		public final int height;
		public final int frames;
		public final String at;

		public Offer(String capeId, String capeName, String fromUuid, String fromName, String creatorUuid,
				String creatorName, String url, int width, int height, int frames, String at) {
			this.capeId = capeId;
			this.capeName = capeName;
			this.fromUuid = fromUuid;
			this.fromName = fromName;
			this.creatorUuid = creatorUuid;
			this.creatorName = creatorName;
			this.url = url;
			this.width = width;
			this.height = height;
			this.frames = frames;
			this.at = at;
		}

		/** Weitergeteilt (Anbieter ist nicht der Ersteller)? */
		public boolean reshared() {
			return !fromUuid.equals(creatorUuid);
		}

		/** Eindeutig je Angebot (derselbe Umhang kann nur einmal an mich angeboten sein). */
		public String key() {
			return capeId + ":" + fromUuid;
		}
	}

	/** Wer einen Umhang von mir hat (angenommen) oder ein offenes Angebot dafür. */
	public static final class Holder {
		public final String uuid;
		public final String name;
		/** Offenes Angebot (sonst angenommen). */
		public final boolean offered;
		public final String grantedByUuid;
		public final String grantedByName;

		public Holder(String uuid, String name, boolean offered, String grantedByUuid, String grantedByName) {
			this.uuid = uuid;
			this.name = name;
			this.offered = offered;
			this.grantedByUuid = grantedByUuid;
			this.grantedByName = grantedByName;
		}
	}

	/** Inhaber eines Umhangs ({@code GET /v1/capes/{id}/holders}). */
	public static final class Holders {
		public final String capeId;
		public final List<Holder> holders;
		/** Alle Inhaber des Umhangs (auch außerhalb des eigenen Asts) – zählt gegen {@link #limit}. */
		public final int count;
		public final int limit;

		public Holders(String capeId, List<Holder> holders, int count, int limit) {
			this.capeId = capeId;
			this.holders = Collections.unmodifiableList(new ArrayList<Holder>(holders));
			this.count = Math.max(count, this.holders.size());
			this.limit = limit;
		}

		/** Kein Platz mehr für neue Angebote? */
		public boolean full() {
			return limit > 0 && count >= limit;
		}

		public boolean has(String uuid) {
			for (Holder h : holders) if (h.uuid.equals(uuid)) return true;
			return false;
		}
	}

	public static boolean validCapeId(String id) {
		return id != null && id.matches(CAPE_ID);
	}

	/** Umhang-Name zur Anzeige: ohne Steuerzeichen, höchstens 48 Zeichen; fehlt → die ID. */
	static String capeName(String raw, String id) {
		if (raw == null) return id;
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < raw.length() && b.length() < 48; i++) {
			char ch = raw.charAt(i);
			if (!Character.isISOControl(ch) && Character.getType(ch) != Character.FORMAT) b.append(ch);
		}
		String s = b.toString().trim();
		return s.isEmpty() ? id : s;
	}
}
