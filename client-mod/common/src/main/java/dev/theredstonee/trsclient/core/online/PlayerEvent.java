package dev.theredstonee.trsclient.core.online;

import com.google.gson.Gson;

/**
 * Ein Ereignis aus {@code GET /v1/events/players} (API.md §13): {@code hello}, {@code emote}, {@code skin},
 * {@code cape}, {@code cosmetics}, {@code badge}. Nur die Felder, die der Mod braucht; alles wird streng geprüft.
 */
public final class PlayerEvent {
	private static final Gson GSON = new Gson();

	public final String type;
	/** 32 Hex-Ziffern (normalisiert) oder null (z. B. {@code hello}). */
	public final String uuid;
	/** Emote-ID bei {@code emote}, sonst null. */
	public final String emote;
	/** Dauer bei {@code emote} (ms, 0 = keine gültige Angabe). */
	public final int durationMs;
	/** Live-Abzeichen bei {@code badge} (spielt gerade mit TRS), sonst false. */
	public final boolean badge;

	PlayerEvent(String type, String uuid, String emote, int durationMs) {
		this(type, uuid, emote, durationMs, false);
	}

	PlayerEvent(String type, String uuid, String emote, int durationMs, boolean badge) {
		this.type = type;
		this.uuid = uuid;
		this.emote = emote;
		this.durationMs = durationMs;
		this.badge = badge;
	}

	static final class Data {
		String type;
		String uuid;
		String emote;
		Double durationMs;
		Boolean badge;
	}

	/**
	 * Baut ein Ereignis aus {@code event:}-Namen und {@code data:}-JSON. Unbekannte Typen, kaputtes JSON oder
	 * Ereignisse ohne gültige UUID → null (werden ignoriert).
	 */
	public static PlayerEvent parse(String event, String data) {
		String type = event == null || event.isEmpty() ? null : event;
		Data d = null;
		if (data != null && !data.isEmpty()) {
			try {
				d = GSON.fromJson(data, Data.class);
			} catch (RuntimeException e) {
				return null;
			}
		}
		if (type == null && d != null) type = d.type;
		if (type == null) return null;
		if (type.equals("hello") || type.equals("ping")) return new PlayerEvent(type, null, null, 0);
		if (d == null) return null;
		String uuid = Uuids.normalize(d.uuid);
		if (uuid == null) return null;
		switch (type) {
			case "emote": {
				if (d.emote == null || !d.emote.matches("[a-z0-9][a-z0-9_-]{0,39}")) return null;
				int duration = 0;
				if (d.durationMs != null && d.durationMs > 0 && d.durationMs <= 60_000) {
					duration = (int) Math.round(d.durationMs);
				}
				return new PlayerEvent(type, uuid, d.emote, duration);
			}
			case "skin":
			case "cape":
			case "cosmetics":
				return new PlayerEvent(type, uuid, null, 0);
			case "badge":
				if (d.badge == null) return null;
				return new PlayerEvent(type, uuid, null, 0, d.badge);
			default:
				return null;
		}
	}

	@Override
	public String toString() {
		return "PlayerEvent{" + type + (uuid != null ? " " + uuid : "") + (emote != null ? " " + emote : "")
				+ (type.equals("badge") ? " " + badge : "") + "}";
	}
}
