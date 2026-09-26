package dev.theredstonee.trsclient.core.social;

import com.google.gson.JsonSyntaxException;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsApi;
import dev.theredstonee.trsclient.core.online.Uuids;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat-Endpunkte der TRS API (API.md §18, §20) für den Mod. Blockierend – nur aus den Hintergrund-Threads von
 * {@link Social} aufrufen. Pfade enthalten nur geprüfte IDs; Antworten werden über {@link ChatJson} bereinigt.
 */
public final class ChatApi {
	private static final String JSON = "application/json";
	/** Größtes Bild (API: 5 MiB Upload; volle Bilder ≤ 2048 px, JPEG/PNG). */
	public static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
	public static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

	private final Http http;
	private final String base;

	public ChatApi(Http http, String apiBase) {
		this.http = http;
		this.base = apiBase;
	}

	// --- DTOs der Antworten ---

	static final class ConversationsBody {
		List<ChatJson.ConversationDto> conversations;
		String nextCursor;
	}

	static final class ConversationBody {
		ChatJson.ConversationDto conversation;
	}

	static final class MessagesBody {
		List<ChatJson.MessageDto> messages;
		Boolean hasMore;
	}

	static final class MessageBody {
		ChatJson.MessageDto message;
	}

	static final class ReactionsBody {
		List<ChatJson.ReactionDto> reactions;
	}

	static final class UnreadEntry {
		String id;
		Integer unread;
		Boolean markedUnread;
		Boolean muted;
	}

	static final class UnreadBody {
		Integer total;
		List<UnreadEntry> conversations;
	}

	static final class AttachmentBody {
		ChatJson.AttachmentDto attachment;
	}

	static final class StatusVersion {
		String name;
	}

	static final class StatusPlayers {
		Integer online;
		Integer max;
	}

	static final class StatusDto {
		String address;
		Boolean online;
		String reason;
		StatusVersion version;
		StatusPlayers players;
		String motd;
		String icon;
	}

	static final class StatusBody {
		StatusDto status;
	}

	static final class MuteDto {
		String until;
		String reason;
		String auto;
	}

	static final class ModerationBody {
		MuteDto mute;
	}

	static final class MeSettings {
		Boolean chatReadReceipts;
		Boolean chatTypingIndicator;
	}

	static final class MeBody {
		MeSettings settings;
	}

	static final class ReportBody {
		ChatJson.ReportDto report;
	}

	// Anfragen
	static final class SendRequest {
		String text;
		String replyTo;
		List<String> attachments;
		ChatJson.InviteDto invite;
		String nonce;
	}

	// --- Ergebnisse ---

	/** Eine Seite Unterhaltungen. */
	public static final class ConversationPage {
		public final List<Chat.Conversation> conversations;
		public final String nextCursor;

		ConversationPage(List<Chat.Conversation> conversations, String nextCursor) {
			this.conversations = conversations;
			this.nextCursor = nextCursor;
		}
	}

	/** Eine Seite Nachrichten (aufsteigend nach seq). */
	public static final class MessagePage {
		public final List<Chat.Message> messages;
		public final boolean hasMore;

		MessagePage(List<Chat.Message> messages, boolean hasMore) {
			this.messages = messages;
			this.hasMore = hasMore;
		}
	}

	/** Ungelesen-Stand einer Unterhaltung ({@code GET /v1/chat/unread}). */
	public static final class UnreadState {
		public final int unread;
		public final boolean marked;
		public final boolean muted;

		UnreadState(int unread, boolean marked, boolean muted) {
			this.unread = unread;
			this.marked = marked;
			this.muted = muted;
		}
	}

	/** Chat-Einstellungen des Kontos (API.md §3.1). */
	public static final class ChatSettings {
		public final boolean readReceipts;
		public final boolean typing;

		public ChatSettings(boolean readReceipts, boolean typing) {
			this.readReceipts = readReceipts;
			this.typing = typing;
		}
	}

	// --- Unterhaltungen (§18.2) ---

	public ConversationPage conversations(String token, String cursor) throws IOException, ApiException {
		String q = "/v1/chat/conversations?limit=50";
		if (cursor != null) {
			if (!cursor.matches("[A-Za-z0-9._~-]{1,200}")) throw new ApiException(0, "invalid_cursor", 0);
			q += "&cursor=" + cursor;
		}
		ConversationsBody body = parse(call("GET", q, null, token, 200), ConversationsBody.class);
		List<Chat.Conversation> out = new ArrayList<Chat.Conversation>();
		if (body != null && body.conversations != null) {
			for (ChatJson.ConversationDto d : body.conversations) {
				Chat.Conversation c = ChatJson.conversation(d);
				if (c != null && out.size() < 100) out.add(c);
			}
		}
		String next = body == null || body.nextCursor == null || !body.nextCursor.matches("[A-Za-z0-9._~-]{1,200}") ? null
				: body.nextCursor;
		return new ConversationPage(out, next);
	}

	public Chat.Conversation conversation(String token, String id) throws IOException, ApiException {
		return conversationOf(call("GET", "/v1/chat/conversations/" + conv(id), null, token, 200));
	}

	/** {@code POST /v1/chat/dms}: DM mit einem Freund öffnen (oder die vorhandene). */
	public Chat.Conversation openDm(String token, String uuid) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("uuid", uuid(uuid));
		return conversationOf(call("POST", "/v1/chat/dms", ChatJson.GSON.toJson(body), token, 200, 201));
	}

	/** {@code GET /v1/chat/unread}: nur Unterhaltungen mit Ungelesenem (Rückfall ohne Ereignis-Stream). */
	public Map<String, UnreadState> unread(String token) throws IOException, ApiException {
		UnreadBody body = parse(call("GET", "/v1/chat/unread", null, token, 200), UnreadBody.class);
		Map<String, UnreadState> out = new LinkedHashMap<String, UnreadState>();
		if (body == null || body.conversations == null) return out;
		for (UnreadEntry e : body.conversations) {
			if (e == null || !Chat.validConversationId(e.id) || out.size() >= 500) continue;
			out.put(e.id, new UnreadState(e.unread == null ? 0 : Math.max(0, e.unread), Boolean.TRUE.equals(e.markedUnread),
					Boolean.TRUE.equals(e.muted)));
		}
		return out;
	}

	// --- Gruppen (§18.3) ---

	public Chat.Conversation createGroup(String token, String name, List<String> members) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("name", name);
		body.put("members", uuids(members));
		return conversationOf(call("POST", "/v1/chat/groups", ChatJson.GSON.toJson(body), token, 201, 200));
	}

	public Chat.Conversation renameGroup(String token, String id, String name) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("name", name);
		return conversationOf(call("PATCH", "/v1/chat/groups/" + conv(id), ChatJson.GSON.toJson(body), token, 200));
	}

	public Chat.Conversation addMembers(String token, String id, List<String> members) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("members", uuids(members));
		return conversationOf(call("POST", "/v1/chat/groups/" + conv(id) + "/members", ChatJson.GSON.toJson(body), token, 200));
	}

	public void removeMember(String token, String id, String member) throws IOException, ApiException {
		call("DELETE", "/v1/chat/groups/" + conv(id) + "/members/" + uuid(member), null, token, 204, 200);
	}

	public void leaveGroup(String token, String id) throws IOException, ApiException {
		call("POST", "/v1/chat/groups/" + conv(id) + "/leave", null, token, 204, 200);
	}

	public Chat.Conversation transferOwner(String token, String id, String member) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("uuid", uuid(member));
		return conversationOf(call("POST", "/v1/chat/groups/" + conv(id) + "/owner", ChatJson.GSON.toJson(body), token, 200));
	}

	public void deleteGroup(String token, String id) throws IOException, ApiException {
		call("DELETE", "/v1/chat/groups/" + conv(id), null, token, 204, 200);
	}

	// --- Nachrichten (§18.4) ---

	/**
	 * Nachrichten laden: {@code before} &gt; 0 = ältere Seite, {@code after} ≥ 0 = Nachholen (nicht beides), sonst die
	 * neuesten.
	 */
	public MessagePage messages(String token, String id, long before, long after, int limit) throws IOException, ApiException {
		StringBuilder q = new StringBuilder("/v1/chat/conversations/").append(conv(id)).append("/messages?limit=")
				.append(Math.max(1, Math.min(100, limit)));
		if (before > 0) q.append("&before=").append(before);
		else if (after >= 0) q.append("&after=").append(after);
		MessagesBody body = parse(call("GET", q.toString(), null, token, 200), MessagesBody.class);
		List<Chat.Message> out = new ArrayList<Chat.Message>();
		if (body != null && body.messages != null) {
			for (ChatJson.MessageDto d : body.messages) {
				Chat.Message m = ChatJson.message(d, id);
				if (m != null && m.conversationId.equals(id) && out.size() < 100) out.add(m);
			}
		}
		return new MessagePage(out, body != null && Boolean.TRUE.equals(body.hasMore));
	}

	/** Senden (mit {@code nonce} idempotent). */
	public Chat.Message send(String token, String id, String text, String replyTo, List<String> attachments,
			Chat.Invite invite, String nonce) throws IOException, ApiException {
		SendRequest body = new SendRequest();
		if (text != null && !text.isEmpty()) body.text = text;
		if (replyTo != null) {
			if (!Chat.validMessageId(replyTo)) throw new ApiException(0, "invalid_request", 0);
			body.replyTo = replyTo;
		}
		if (attachments != null && !attachments.isEmpty()) {
			List<String> ids = new ArrayList<String>();
			for (String a : attachments) {
				if (!Chat.validAttachmentId(a)) throw new ApiException(0, "invalid_request", 0);
				ids.add(a);
			}
			body.attachments = ids;
		}
		if (invite != null) {
			body.invite = new ChatJson.InviteDto();
			body.invite.address = invite.address;
			body.invite.name = invite.name;
		}
		if (nonce != null && nonce.matches("[A-Za-z0-9_-]{8,64}")) body.nonce = nonce;
		return messageOf(call("POST", "/v1/chat/conversations/" + conv(id) + "/messages", ChatJson.GSON.toJson(body), token,
				201, 200), id);
	}

	public Chat.Message edit(String token, String conversationId, String messageId, String text) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("text", text == null ? "" : text);
		return messageOf(call("PATCH", "/v1/chat/messages/" + msg(messageId), ChatJson.GSON.toJson(body), token, 200),
				conversationId);
	}

	public Chat.Message delete(String token, String conversationId, String messageId) throws IOException, ApiException {
		return messageOf(call("DELETE", "/v1/chat/messages/" + msg(messageId), null, token, 200), conversationId);
	}

	/** Reaktion setzen ({@code on}) oder entfernen; Rückgabe: alle Reaktionen der Nachricht. */
	public List<Chat.Reaction> react(String token, String messageId, String emoji, boolean on) throws IOException, ApiException {
		if (!Chat.validReaction(emoji)) throw new ApiException(0, "invalid_request", 0);
		ReactionsBody body = parse(call(on ? "PUT" : "DELETE", "/v1/chat/messages/" + msg(messageId) + "/reactions/" + emoji,
				null, token, 200), ReactionsBody.class);
		return ChatJson.reactions(body == null ? null : body.reactions);
	}

	// --- Gelesen, ungelesen, stumm, tippt (§18.5) ---

	public Chat.Conversation read(String token, String id, long seq) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("seq", Math.max(0, seq));
		return conversationOf(call("POST", "/v1/chat/conversations/" + conv(id) + "/read", ChatJson.GSON.toJson(body), token, 200));
	}

	/** Als ungelesen markieren; {@code seq} &gt; 0 = „ab dieser Nachricht ungelesen“. */
	public Chat.Conversation markUnread(String token, String id, long seq) throws IOException, ApiException {
		String json = null;
		if (seq > 0) {
			Map<String, Object> body = new LinkedHashMap<String, Object>();
			body.put("seq", seq);
			json = ChatJson.GSON.toJson(body);
		}
		return conversationOf(call("POST", "/v1/chat/conversations/" + conv(id) + "/unread", json, token, 200));
	}

	/** Stumm (Benachrichtigungen) – {@code untilMs} 0 = bis aufgehoben. */
	public Chat.Conversation mute(String token, String id, boolean muted, long untilMs) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("muted", muted);
		if (muted && untilMs > 0) body.put("until", java.time.Instant.ofEpochMilli(untilMs).toString());
		return conversationOf(call("PUT", "/v1/chat/conversations/" + conv(id) + "/mute", ChatJson.GSON.toJson(body), token, 200));
	}

	public void typing(String token, String id, boolean typing) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("typing", typing);
		call("POST", "/v1/chat/conversations/" + conv(id) + "/typing", ChatJson.GSON.toJson(body), token, 204, 200);
	}

	// --- Bilder (§18.7) ---

	/** Bild hochladen (PNG/JPEG, ≤ 5 MiB); Rückgabe: Anhang (noch privat, bis er gesendet wird). */
	public Chat.Attachment upload(String token, byte[] image, String mime) throws IOException, ApiException {
		if (image == null || image.length == 0 || image.length > MAX_UPLOAD_BYTES) throw new ApiException(413, "payload_too_large", 0);
		if (!"image/png".equals(mime) && !"image/jpeg".equals(mime)) throw new ApiException(415, "unsupported_media_type", 0);
		Http.Request request = new Http.Request("POST", base + "/v1/chat/attachments").header("Accept", JSON)
				.header("Content-Type", mime).header("Authorization", "Bearer " + token);
		request.body = image;
		Http.Response response = http.send(request);
		if (response.status != 201 && response.status != 200) throw error(response);
		AttachmentBody body = parse(response, AttachmentBody.class);
		Chat.Attachment a = ChatJson.attachment(body == null ? null : body.attachment);
		if (a == null) throw new ApiException(response.status, "invalid_json", 0);
		return a;
	}

	/** Bildbytes eines Anhangs (Vorschau oder voll) mit dem Token des Mods. */
	public byte[] attachment(String token, String id, boolean thumb) throws IOException, ApiException {
		if (!Chat.validAttachmentId(id)) throw new ApiException(0, "invalid_request", 0);
		Http.Request request = new Http.Request("GET", base + "/v1/chat/attachments/" + id + (thumb ? "?thumb=1" : ""))
				.header("Accept", "image/png, image/jpeg").header("Authorization", "Bearer " + token);
		request.maxBytes = MAX_IMAGE_BYTES;
		Http.Response response = http.send(request);
		if (response.status != 200) throw new ApiException(response.status, TrsApi.errorCode(response), TrsApi.retryAfter(response));
		return response.body;
	}

	// --- Servereinladungen (§18.6) ---

	public Chat.ServerStatus serverStatus(String token, String address) throws IOException, ApiException {
		String a = SafeText.serverAddress(address);
		if (a == null) throw new ApiException(0, "invalid_request", 0);
		StatusBody body = parse(call("GET", "/v1/servers/status?address=" + java.net.URLEncoder.encode(a, "UTF-8"), null, token,
				200), StatusBody.class);
		StatusDto s = body == null ? null : body.status;
		if (s == null) return new Chat.ServerStatus(a, false, "invalid_response", 0, 0, null, null, null);
		byte[] icon = null;
		if (s.icon != null && s.icon.startsWith("data:image/png;base64,") && s.icon.length() < 200_000) {
			try {
				icon = java.util.Base64.getDecoder().decode(s.icon.substring("data:image/png;base64,".length()));
			} catch (IllegalArgumentException ignored) {
				icon = null;
			}
		}
		String reason = s.reason != null && s.reason.matches("[a-z_]{1,32}") ? s.reason : null;
		int players = s.players == null ? 0 : ChatJson.clamp(s.players.online, 0, 1_000_000);
		int max = s.players == null ? 0 : ChatJson.clamp(s.players.max, 0, 1_000_000);
		String motd = s.motd == null ? null : SafeText.line(s.motd, 120);
		String version = s.version == null || s.version.name == null ? null : SafeText.line(s.version.name, 40);
		return new Chat.ServerStatus(a, Boolean.TRUE.equals(s.online), reason, players, max, motd, version, icon);
	}

	// --- Meldungen (§20) ---

	/**
	 * {@code POST /v1/reports}. {@code kind} message|image|player|group, {@code target} = Nachrichten-, Anhang-,
	 * Spieler- bzw. Unterhaltungs-ID; {@code conversationId} optional bei Spielern. Rückgabe: Meldungs-ID.
	 */
	public String report(String token, String kind, String reason, String note, String target, String conversationId)
			throws IOException, ApiException {
		if (!kind.matches("message|image|player|group")) throw new ApiException(0, "invalid_request", 0);
		if (!reason.matches("insult_hate|spam|inappropriate|scam_phishing|harassment|other")) {
			throw new ApiException(0, "invalid_request", 0);
		}
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("kind", kind);
		body.put("reason", reason);
		if (note != null && !note.trim().isEmpty()) body.put("note", SafeText.message(note, 500));
		if (kind.equals("message")) body.put("messageId", msg(target));
		else if (kind.equals("image")) {
			if (!Chat.validAttachmentId(target)) throw new ApiException(0, "invalid_request", 0);
			body.put("attachmentId", target);
		} else if (kind.equals("player")) {
			body.put("uuid", uuid(target));
			if (conversationId != null) body.put("conversationId", conv(conversationId));
		} else {
			body.put("conversationId", conv(target));
		}
		ReportBody r = parse(call("POST", "/v1/reports", ChatJson.GSON.toJson(body), token, 201, 200), ReportBody.class);
		return r == null || r.report == null ? null : r.report.id;
	}

	/** {@code GET /v1/me/moderation}: eigene Chat-Stummschaltung. */
	public Chat.Moderation moderation(String token) throws IOException, ApiException {
		ModerationBody body = parse(call("GET", "/v1/me/moderation", null, token, 200), ModerationBody.class);
		if (body == null || body.mute == null) return Chat.Moderation.NONE;
		return new Chat.Moderation(true, ChatJson.time(body.mute.until),
				body.mute.reason == null ? null : SafeText.line(body.mute.reason, 200));
	}

	// --- Strafen und Einspruch (§22.8) ---

	/**
	 * {@code GET /v1/me/sanctions} → {aktiv, vergangen}. {@code token} = TRS-Token oder (gesperrtes Konto) der
	 * Einspruch-Token. 404 = Server ohne Moderation v2.
	 */
	public List<List<Sanction>> sanctions(String token) throws IOException, ApiException {
		return SanctionJson.lists(call("GET", "/v1/me/sanctions", null, token, 200).text());
	}

	/** {@code POST /v1/me/sanctions/{id}/appeal} (Text 20–1000 Zeichen) → die Strafe mit Einspruch. */
	public Sanction appeal(String token, long sanctionId, String text) throws IOException, ApiException {
		if (sanctionId <= 0) throw new ApiException(0, "invalid_request", 0);
		String clean = Sanctions.cleanAppeal(text);
		if (Sanctions.appealProblem(clean) != null) throw new ApiException(400, "invalid_request", 0);
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("text", clean);
		Http.Response r = call("POST", "/v1/me/sanctions/" + sanctionId + "/appeal", ChatJson.GSON.toJson(body), token, 201, 200);
		Sanction s = SanctionJson.wrapped(r.text());
		if (s == null) throw new ApiException(r.status, "invalid_json", 0);
		return s;
	}

	/** Chat-Einstellungen aus {@code GET /v1/me}. */
	public ChatSettings settings(String token) throws IOException, ApiException {
		MeBody body = parse(call("GET", "/v1/me", null, token, 200), MeBody.class);
		MeSettings s = body == null ? null : body.settings;
		return new ChatSettings(s == null || !Boolean.FALSE.equals(s.chatReadReceipts),
				s == null || !Boolean.FALSE.equals(s.chatTypingIndicator));
	}

	/** {@code PATCH /v1/me}: Lesebestätigungen / Tippt-Anzeige (gegenseitig). */
	public ChatSettings updateSettings(String token, Boolean readReceipts, Boolean typing) throws IOException, ApiException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		if (readReceipts != null) body.put("chatReadReceipts", readReceipts);
		if (typing != null) body.put("chatTypingIndicator", typing);
		MeBody me = parse(call("PATCH", "/v1/me", ChatJson.GSON.toJson(body), token, 200), MeBody.class);
		MeSettings s = me == null ? null : me.settings;
		return new ChatSettings(s == null || !Boolean.FALSE.equals(s.chatReadReceipts),
				s == null || !Boolean.FALSE.equals(s.chatTypingIndicator));
	}

	// --- Hilfen ---

	private Chat.Conversation conversationOf(Http.Response response) throws ApiException {
		ConversationBody body = parse(response, ConversationBody.class);
		Chat.Conversation c = ChatJson.conversation(body == null ? null : body.conversation);
		if (c == null) throw new ApiException(response.status, "invalid_json", 0);
		return c;
	}

	private Chat.Message messageOf(Http.Response response, String conversationId) throws ApiException {
		MessageBody body = parse(response, MessageBody.class);
		Chat.Message m = ChatJson.message(body == null ? null : body.message, conversationId);
		if (m == null) throw new ApiException(response.status, "invalid_json", 0);
		return m;
	}

	private static String conv(String id) throws ApiException {
		if (!Chat.validConversationId(id)) throw new ApiException(0, "invalid_request", 0);
		return id;
	}

	private static String msg(String id) throws ApiException {
		if (!Chat.validMessageId(id)) throw new ApiException(0, "invalid_request", 0);
		return id;
	}

	private static String uuid(String raw) throws ApiException {
		String n = Uuids.normalize(raw);
		if (n == null) throw new ApiException(0, "invalid_request", 0);
		return n;
	}

	private static List<String> uuids(List<String> raw) throws ApiException {
		if (raw == null) return Collections.emptyList();
		List<String> out = new ArrayList<String>();
		for (String r : raw) {
			String n = uuid(r);
			if (!out.contains(n)) out.add(n);
		}
		return out;
	}

	private Http.Response call(String method, String path, String json, String token, int expected)
			throws IOException, ApiException {
		return call(method, path, json, token, expected, expected);
	}

	private Http.Response call(String method, String path, String json, String token, int expected, int alsoOk)
			throws IOException, ApiException {
		Http.Request request = new Http.Request(method, base + path).header("Accept", JSON);
		if (json != null) {
			request.header("Content-Type", JSON);
			request.body = json.getBytes(StandardCharsets.UTF_8);
		}
		if (token != null) request.header("Authorization", "Bearer " + token);
		request.maxBytes = 1024 * 1024;
		Http.Response response = http.send(request);
		if (response.status != expected && response.status != alsoOk) throw error(response);
		return response;
	}

	/** Fehler mit Körper (Strafen-Angaben bei 403, API.md §22.2). */
	static ApiException error(Http.Response response) {
		return new ApiException(response.status, TrsApi.errorCode(response), TrsApi.retryAfter(response),
				response.status == 403 ? response.text() : null);
	}

	static <T> T parse(Http.Response response, Class<T> type) throws ApiException {
		try {
			return ChatJson.GSON.fromJson(response.text(), type);
		} catch (JsonSyntaxException | IllegalStateException e) {
			throw new ApiException(response.status, "invalid_json", 0);
		}
	}
}
