package dev.theredstonee.trsclient.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Warteschlange der Sozial-Benachrichtigungen im Spiel (neue Nachricht, Servereinladung, Freundschaftsanfrage,
 * Umhang-Angebot, „X ist online“, Meldungs-Rückmeldung, Moderation). Reine Logik ohne Minecraft: höchstens
 * {@link #MAX_VISIBLE} gleichzeitig, der Rest wartet; gleiche Unterhaltung = ein Toast, der hochzählt; „online“ je
 * Freund höchstens alle {@link #ONLINE_COOLDOWN_MS}; Nicht stören und Schalter je Art.
 *
 * <p>Nur aus dem Spiel-/Render-Thread benutzen (Minecraft: derselbe Thread).
 */
public final class Toasts {
	public static final int MAX_VISIBLE = 3;
	public static final int MAX_QUEUED = 12;
	public static final long ONLINE_COOLDOWN_MS = 5 * 60_000L;
	/** Ein- und Ausblenden. */
	public static final long SLIDE_IN_MS = 180L;
	public static final long SLIDE_OUT_MS = 240L;

	/** Art einer Benachrichtigung. */
	public enum Kind {
		MESSAGE, INVITE, REQUEST, CAPE_OFFER, ONLINE, REPORT, MODERATION,
		/** Welt-Hosting: „X lädt dich in seine Welt ein“ ({@link Toast#conversationId} = Raum-ID). */
		WORLD_INVITE,
		/** Welt-Hosting (Host): Beitrittsanfrage ({@link Toast#conversationId} = Raum-ID, Gesicht = Anfragender). */
		JOIN_REQUEST,
		/** Welt-Hosting: Hinweis (angenommen, abgelehnt, entfernt, Welt geschlossen). */
		WORLD;

		/** Kann die Schnelltaste etwas damit anfangen? */
		public boolean actionable() {
			return this == MESSAGE || this == INVITE || this == REQUEST || this == CAPE_OFFER || this == WORLD_INVITE
					|| this == JOIN_REQUEST;
		}
	}

	/** Ecke des Bildschirms. */
	public enum Corner implements dev.theredstonee.trsclient.core.module.ChoiceSetting.Option {
		TOP_RIGHT("Top right"), TOP_LEFT("Top left"), BOTTOM_RIGHT("Bottom right"), BOTTOM_LEFT("Bottom left");

		private final String label;

		Corner(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	/** Einstellungen (aus dem Modul „Sozial“, je Bild gelesen). */
	public static final class Settings {
		public boolean enabled = true;
		public boolean dnd;
		public boolean dndFullscreen;
		public boolean messages = true;
		public boolean invites = true;
		public boolean requests = true;
		public boolean online = true;
		public boolean sound = true;
		public long durationMs = 5000L;
		public Corner corner = Corner.TOP_RIGHT;

		boolean allows(Kind kind) {
			switch (kind) {
				case MESSAGE:
					return messages;
				case INVITE:
				case WORLD_INVITE:
					return invites;
				case JOIN_REQUEST:
				case REQUEST:
				case CAPE_OFFER:
					return requests;
				case ONLINE:
					return online;
				default:
					return true;
			}
		}
	}

	/** Eine Benachrichtigung (unveränderlich bis auf Anzeigezeit und Zähler). */
	public static final class Toast {
		public final long id;
		public final Kind kind;
		/** Zusammenfassungsschlüssel (z. B. Unterhaltung), null = nie zusammenfassen. */
		final String key;
		public final String title;
		public final String text;
		/** UUID für das Gesicht oder null. */
		public final String faceUuid;
		public final String faceName;
		/** Unterhaltung (Nachricht/Einladung) oder null. */
		public final String conversationId;
		public final Chat.Invite invite;
		/** Anzahl zusammengefasster Ereignisse (≥ 1). */
		public final int count;
		/** 0 = noch nicht angezeigt (wartet). */
		long shownAt;
		long duration;

		Toast(long id, Kind kind, String key, String title, String text, String faceUuid, String faceName,
				String conversationId, Chat.Invite invite, int count) {
			this.id = id;
			this.kind = kind;
			this.key = key;
			this.title = title;
			this.text = text;
			this.faceUuid = faceUuid;
			this.faceName = faceName;
			this.conversationId = conversationId;
			this.invite = invite;
			this.count = count;
		}

		/** Ein-/Ausblende-Fortschritt 0..1 (1 = voll sichtbar). */
		public float visibility(long now) {
			if (shownAt == 0) return 0f;
			long age = now - shownAt;
			if (age < SLIDE_IN_MS) return Math.max(0f, age / (float) SLIDE_IN_MS);
			long left = shownAt + duration - now;
			if (left < SLIDE_OUT_MS) return Math.max(0f, left / (float) SLIDE_OUT_MS);
			return 1f;
		}

		/** Verbleibender Anteil der Anzeigezeit 1..0 (für den Fortschrittsbalken). */
		public float remaining(long now) {
			if (shownAt == 0 || duration <= 0) return 1f;
			return Math.max(0f, Math.min(1f, (shownAt + duration - now) / (float) duration));
		}

		boolean expired(long now) {
			return shownAt != 0 && now >= shownAt + duration;
		}
	}

	private final List<Toast> visible = new ArrayList<Toast>();
	private final List<Toast> queue = new ArrayList<Toast>();
	private final Map<String, Long> onlineShown = new HashMap<String, Long>();
	private long nextId = 1;
	private int soundRequests;
	private Settings settings = new Settings();
	/** Wird eine Unterhaltung gerade im Sozial-Bildschirm gelesen? Dann keine Toasts dafür. */
	private String viewing;
	private boolean fullscreen;

	public void settings(Settings s) {
		if (s != null) settings = s;
	}

	public Settings settings() {
		return settings;
	}

	/** Die Unterhaltung, die gerade offen ist (null = keine). */
	public void viewing(String conversationId) {
		this.viewing = conversationId;
	}

	public void fullscreen(boolean value) {
		this.fullscreen = value;
	}

	/** Gerade „Nicht stören“ (Schalter oder Vollbild-Automatik)? */
	public boolean quiet() {
		return settings.dnd || (settings.dndFullscreen && fullscreen);
	}

	/**
	 * Neue Benachrichtigung. Rückgabe: angenommen (angezeigt oder in der Warteschlange); false = unterdrückt
	 * (aus, Nicht stören, Art abgeschaltet, gerade gelesen, doppelt).
	 */
	public boolean add(Kind kind, String key, String title, String text, String faceUuid, String faceName,
			String conversationId, Chat.Invite invite, long now) {
		boolean important = kind == Kind.MODERATION;
		if (!important) {
			if (!settings.enabled || quiet() || !settings.allows(kind)) return false;
			if (conversationId != null && conversationId.equals(viewing) && (kind == Kind.MESSAGE || kind == Kind.INVITE)) {
				return false;
			}
		}
		if (kind == Kind.ONLINE && faceUuid != null) {
			Long last = onlineShown.get(faceUuid);
			if (last != null && now - last < ONLINE_COOLDOWN_MS) return false;
			onlineShown.put(faceUuid, now);
			if (onlineShown.size() > 512) onlineShown.clear();
		}
		if (key != null) {
			// Gleiche Unterhaltung: vorhandenen Toast ersetzen (hochzählen, Anzeigezeit neu).
			for (int i = 0; i < visible.size(); i++) {
				Toast old = visible.get(i);
				if (key.equals(old.key)) {
					Toast t = new Toast(old.id, kind, key, title, text, faceUuid, faceName, conversationId, invite, old.count + 1);
					t.shownAt = now - SLIDE_IN_MS;
					t.duration = settings.durationMs;
					visible.set(i, t);
					soundRequests++;
					return true;
				}
			}
			for (int i = 0; i < queue.size(); i++) {
				Toast old = queue.get(i);
				if (key.equals(old.key)) {
					queue.set(i, new Toast(old.id, kind, key, title, text, faceUuid, faceName, conversationId, invite,
							old.count + 1));
					return true;
				}
			}
		}
		Toast t = new Toast(nextId++, kind, key, title, text, faceUuid, faceName, conversationId, invite, 1);
		if (queue.size() >= MAX_QUEUED) {
			// Älteste unwichtige wegwerfen (Moderation bleibt).
			for (Iterator<Toast> it = queue.iterator(); it.hasNext(); ) {
				if (it.next().kind != Kind.MODERATION) {
					it.remove();
					break;
				}
			}
			if (queue.size() >= MAX_QUEUED) return false;
		}
		if (important) queue.add(0, t);
		else queue.add(t);
		advance(now);
		return true;
	}

	/** Abgelaufene entfernen, Wartende nachrücken. Rückgabe: die sichtbaren (neueste zuerst). */
	public List<Toast> visible(long now) {
		advance(now);
		if (visible.isEmpty()) return Collections.emptyList();
		List<Toast> out = new ArrayList<Toast>(visible);
		Collections.reverse(out);
		return out;
	}

	private void advance(long now) {
		for (Iterator<Toast> it = visible.iterator(); it.hasNext(); ) {
			if (it.next().expired(now)) it.remove();
		}
		while (visible.size() < MAX_VISIBLE && !queue.isEmpty()) {
			Toast t = queue.remove(0);
			if (!t.kind.equals(Kind.MODERATION) && (!settings.enabled || quiet())) continue;
			t.shownAt = now;
			t.duration = t.kind == Kind.MODERATION ? Math.max(8000L, settings.durationMs) : settings.durationMs;
			visible.add(t);
			soundRequests++;
		}
	}

	/** Gibt es etwas zu zeichnen (sichtbar oder wartend)? Billig – für den HUD-Pfad. */
	public boolean active() {
		return !visible.isEmpty() || !queue.isEmpty();
	}

	/** Soll ein Ton gespielt werden (seit dem letzten Aufruf kam etwas Neues)? */
	public boolean takeSound() {
		boolean s = soundRequests > 0 && settings.sound;
		soundRequests = 0;
		return s;
	}

	/** Der neueste sichtbare Toast, mit dem die Schnelltaste etwas anfangen kann, oder null. */
	public Toast quickTarget(long now) {
		advance(now);
		for (int i = visible.size() - 1; i >= 0; i--) {
			Toast t = visible.get(i);
			if (t.kind.actionable() && t.visibility(now) > 0f) return t;
		}
		return null;
	}

	/** Toast schließen (Schnelltaste benutzt, Unterhaltung geöffnet). */
	public void dismiss(long id) {
		for (Iterator<Toast> it = visible.iterator(); it.hasNext(); ) {
			if (it.next().id == id) it.remove();
		}
		for (Iterator<Toast> it = queue.iterator(); it.hasNext(); ) {
			if (it.next().id == id) it.remove();
		}
	}

	/** Toast(s) mit diesem Zusammenfassungsschlüssel entfernen (z. B. vor einem aktualisierten Hinweis). */
	public void dismissKey(String key) {
		if (key == null) return;
		for (Iterator<Toast> it = visible.iterator(); it.hasNext(); ) {
			if (key.equals(it.next().key)) it.remove();
		}
		for (Iterator<Toast> it = queue.iterator(); it.hasNext(); ) {
			if (key.equals(it.next().key)) it.remove();
		}
	}

	/** Alle Toasts einer Unterhaltung weg (sie wurde gerade gelesen). */
	public void dismissConversation(String conversationId) {
		if (conversationId == null) return;
		for (Iterator<Toast> it = visible.iterator(); it.hasNext(); ) {
			if (conversationId.equals(it.next().conversationId)) it.remove();
		}
		for (Iterator<Toast> it = queue.iterator(); it.hasNext(); ) {
			if (conversationId.equals(it.next().conversationId)) it.remove();
		}
	}

	/** Alles vergessen (Kontowechsel). */
	public void clear() {
		visible.clear();
		queue.clear();
		onlineShown.clear();
		soundRequests = 0;
	}

	int queued() {
		return queue.size();
	}
}
