package dev.theredstonee.trsclient.core.social;

import com.google.gson.JsonSyntaxException;
import dev.theredstonee.trsclient.core.online.Uuids;

import java.util.List;

/**
 * Ein bereinigtes Ereignis von {@code GET /v1/events/me} (API.md §19). Wird im Lese-Thread aus dem JSON gebaut;
 * nicht benutzte Felder sind null/0.
 */
public final class MeEvent {
	public final String type;
	/** Ereignis-ID (für Last-Event-ID) oder null. */
	public final String id;
	public final boolean resumed;
	public final String reason;
	public final String conversationId;
	public final Chat.Message message;
	public final String messageId;
	public final List<Chat.Reaction> reactions;
	public final String uuid;
	public final boolean typing;
	public final long expiresInMs;
	public final long seq;
	public final long at;
	public final int unread;
	public final boolean markedUnread;
	public final long readSeq;
	public final boolean muted;
	public final long mutedUntil;
	public final Chat.Conversation conversation;
	/** Absender einer Anfrage / neuer Freund / wer angenommen hat. */
	public final Chat.User user;
	/** Präsenz: "online", "in-game" oder null (offline/unsichtbar). */
	public final String presence;
	public final String presenceVersion;
	public final String presenceLoader;
	public final String presenceServer;
	/** Meldung (report_update). */
	public final String reportId;
	public final String reportStatus;
	public final String reportOutcome;
	/** Moderation: warn | mute | unmute. */
	public final String action;
	public final long until;
	/** Umhang-Angebot: Name des Umhangs. */
	public final String capeName;
	/** Rohes JSON der Welt-Hosting-Ereignisse ({@code hosting_*}, ≤ 16 KiB) – ausgewertet in core.hosting. */
	public final String hostingData;

	private MeEvent(String type, String id, ChatJson.EventDto d, String raw) {
		this.type = type;
		this.id = id;
		this.hostingData = raw != null && type.startsWith("hosting_") && raw.length() <= 16 * 1024 ? raw : null;
		this.resumed = d != null && Boolean.TRUE.equals(d.resumed);
		this.reason = d == null || d.reason == null ? null : SafeText.line(d.reason, 200);
		this.conversationId = d != null && Chat.validConversationId(d.conversationId) ? d.conversationId : null;
		this.message = d == null ? null : ChatJson.message(d.message, conversationId);
		this.messageId = d != null && Chat.validMessageId(d.messageId) ? d.messageId : null;
		this.reactions = d == null || d.reactions == null ? null : ChatJson.reactions(d.reactions);
		this.uuid = d == null ? null : Uuids.normalize(d.uuid);
		this.typing = d != null && Boolean.TRUE.equals(d.typing);
		this.expiresInMs = d == null || d.expiresInMs == null ? 8000L : Math.max(1000L, Math.min(30_000L, d.expiresInMs));
		this.seq = d == null || d.seq == null ? 0 : d.seq;
		this.at = d == null ? 0 : ChatJson.time(d.at);
		this.unread = d == null || d.unread == null ? 0 : Math.max(0, d.unread);
		this.markedUnread = d != null && Boolean.TRUE.equals(d.markedUnread);
		this.readSeq = d == null || d.readSeq == null ? -1 : d.readSeq;
		this.muted = d != null && Boolean.TRUE.equals(d.muted);
		this.mutedUntil = d == null ? 0 : ChatJson.time(d.mutedUntil);
		this.conversation = d == null ? null : ChatJson.conversation(d.conversation);
		Chat.User u = null;
		if (d != null) {
			u = ChatJson.user(d.from);
			if (u == null) u = ChatJson.user(d.friend);
			if (u == null) u = ChatJson.user(d.by);
			if (u == null && d.offer != null) u = ChatJson.user(d.offer.from);
		}
		this.user = u;
		String state = null;
		String version = null;
		String loader = null;
		String server = null;
		if (d != null && d.presence != null && d.presence.state != null) {
			state = d.presence.state.equals("in-game") || d.presence.state.equals("online") ? d.presence.state : null;
			if (state != null && d.presence.game != null) {
				version = d.presence.game.version == null ? null : SafeText.line(d.presence.game.version, 32);
				loader = d.presence.game.loader != null && d.presence.game.loader.matches("[a-z]{1,16}") ? d.presence.game.loader : null;
				server = SafeText.serverAddress(d.presence.game.server);
			}
		}
		this.presence = state;
		this.presenceVersion = version;
		this.presenceLoader = loader;
		this.presenceServer = server;
		ChatJson.ReportDto r = d == null ? null : d.report;
		this.reportId = r != null && r.id != null && r.id.matches("r[0-9a-f]{8,40}") ? r.id : null;
		this.reportStatus = r != null && r.status != null && r.status.matches("open|in_review|resolved") ? r.status : null;
		this.reportOutcome = r != null && r.outcome != null && r.outcome.matches("actioned|dismissed") ? r.outcome : null;
		this.action = d != null && d.action != null && d.action.matches("warn|mute|unmute") ? d.action : null;
		this.until = d == null ? 0 : ChatJson.time(d.until);
		this.capeName = d != null && d.offer != null && d.offer.cape != null && d.offer.cape.name != null
				? SafeText.line(d.offer.cape.name, SafeText.MAX_NAME) : null;
	}

	/** Aus Ereignisname, JSON und ID; kaputtes JSON → null. */
	public static MeEvent parse(String event, String data, String id) {
		if (event == null || !event.matches("[a-z_]{1,40}")) return null;
		ChatJson.EventDto d = null;
		if (data != null && !data.isEmpty()) {
			try {
				d = ChatJson.GSON.fromJson(data, ChatJson.EventDto.class);
			} catch (JsonSyntaxException | IllegalStateException | NumberFormatException e) {
				return null;
			}
		}
		return new MeEvent(event, id, d, data);
	}

	/** Für Tests: Ereignis ohne Daten. */
	static MeEvent of(String type) {
		return new MeEvent(type, null, null, null);
	}

	/** Für Tests: Hosting-Ereignis mit rohem JSON. */
	public static MeEvent hosting(String type, String json) {
		return new MeEvent(type, null, null, json);
	}
}
