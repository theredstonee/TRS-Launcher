package dev.theredstonee.trsclient.core.social;

import com.google.gson.Gson;
import dev.theredstonee.trsclient.core.online.Uuids;

import java.util.ArrayList;
import java.util.List;

/**
 * Gson-DTOs des Chats (API.md §18–§20) und die Umwandlung in die bereinigten {@link Chat}-Ansichten.
 * Alles Unbekannte oder Ungültige fällt weg; Listen sind begrenzt.
 */
public final class ChatJson {
	static final Gson GSON = new Gson();
	static final int MAX_MEMBERS = 64;
	static final int MAX_ATTACHMENTS = 10;

	private ChatJson() {
	}

	// --- DTOs (alle Felder optional) ---

	static final class UserDto {
		String uuid;
		String name;
	}

	static final class MemberDto {
		String uuid;
		String name;
		String role;
	}

	static final class ReadDto {
		String uuid;
		Long seq;
		String at;
	}

	static final class InviteDto {
		String address;
		String name;
	}

	static final class WorldDto {
		String roomId;
		String code;
		String name;
		String mcVersion;
		String loader;
		UserDto host;
	}

	static final class ThumbDto {
		String mime;
		Integer width;
		Integer height;
	}

	static final class AttachmentDto {
		String id;
		String mime;
		Integer width;
		Integer height;
		Long bytes;
		ThumbDto thumb;
	}

	static final class ReplyDto {
		String id;
		Long seq;
		UserDto sender;
		String preview;
		Integer attachments;
		Boolean invite;
		Boolean world;
		Boolean deleted;
	}

	static final class SystemDto {
		String event;
		UserDto actor;
		UserDto target;
		String name;
	}

	static final class ReactionDto {
		String emoji;
		Integer count;
		List<String> users;
	}

	static final class MessageDto {
		String id;
		String conversationId;
		Long seq;
		String kind;
		UserDto sender;
		String text;
		InviteDto invite;
		WorldDto world;
		List<AttachmentDto> attachments;
		ReplyDto replyTo;
		SystemDto system;
		List<ReactionDto> reactions;
		String createdAt;
		String editedAt;
		Boolean deleted;
		String deletedBy;
		Boolean hidden;
		String nonce;
	}

	static final class ConversationDto {
		String id;
		String kind;
		String name;
		String owner;
		List<MemberDto> members;
		UserDto peer;
		Boolean canWrite;
		String readOnlyReason;
		MessageDto lastMessage;
		Long lastSeq;
		Integer unread;
		Boolean markedUnread;
		Long readSeq;
		Boolean muted;
		String mutedUntil;
		List<ReadDto> reads;
		String updatedAt;
	}

	static final class GameDto {
		String version;
		String loader;
		String server;
	}

	static final class PresenceDto {
		String state;
		GameDto game;
	}

	static final class ReportDto {
		String id;
		String kind;
		String status;
		String outcome;
	}

	static final class OfferCapeDto {
		String id;
		String name;
	}

	static final class OfferDto {
		OfferCapeDto cape;
		UserDto from;
	}

	/** Ein Ereignis von {@code /v1/events/me} (alle Varianten in einem DTO). */
	static final class EventDto {
		String type;
		Boolean resumed;
		String reason;
		String conversationId;
		MessageDto message;
		String messageId;
		List<ReactionDto> reactions;
		String uuid;
		Boolean typing;
		Long expiresInMs;
		Long seq;
		String at;
		Integer unread;
		Boolean markedUnread;
		Long readSeq;
		Boolean muted;
		String mutedUntil;
		ConversationDto conversation;
		UserDto from;
		UserDto friend;
		UserDto by;
		PresenceDto presence;
		ReportDto report;
		String action;
		String until;
		OfferDto offer;
		String capeId;
	}

	// --- Umwandlung ---

	/** ISO 8601 → Epoch-ms (0 = fehlt/ungültig). */
	public static long time(String iso) {
		if (iso == null || iso.length() > 40) return 0;
		try {
			return java.time.Instant.parse(iso).toEpochMilli();
		} catch (RuntimeException e) {
			return 0;
		}
	}

	static Chat.User user(UserDto d) {
		if (d == null) return null;
		String uuid = Uuids.normalize(d.uuid);
		if (uuid == null) return null;
		String name = SafeText.playerName(d.name);
		return new Chat.User(uuid, name == null ? "?" : name);
	}

	static Chat.Invite invite(InviteDto d) {
		if (d == null) return null;
		String address = SafeText.serverAddress(d.address);
		if (address == null) return null;
		String name = d.name == null ? null : SafeText.line(d.name, SafeText.MAX_NAME);
		return new Chat.Invite(address, name == null || name.isEmpty() ? null : name);
	}

	/** Weltkarte → Invite mit {@link Chat.World}; ungültig → null. */
	static Chat.Invite world(WorldDto d) {
		if (d == null || d.roomId == null || !d.roomId.matches("h[0-9a-f]{20}")) return null;
		String code = d.code == null ? null : d.code.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
		if (code == null || !code.matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}")) return null;
		Chat.User host = user(d.host);
		String name = d.name == null ? null : SafeText.line(d.name, SafeText.MAX_NAME);
		String version = d.mcVersion != null && d.mcVersion.matches("[0-9A-Za-z][0-9A-Za-z._+ -]{0,31}") ? d.mcVersion : "?";
		String loader = d.loader != null && d.loader.matches("vanilla|fabric|forge|neoforge|quilt") ? d.loader : "vanilla";
		return new Chat.Invite("", name == null || name.isEmpty() ? "?" : name, new Chat.World(d.roomId, code, version, loader,
				host == null ? null : host.uuid, host == null ? "?" : host.name));
	}

	static Chat.Attachment attachment(AttachmentDto d) {
		if (d == null || !Chat.validAttachmentId(d.id)) return null;
		int w = clamp(d.width, 1, 8192);
		int h = clamp(d.height, 1, 8192);
		int tw = d.thumb == null ? Math.min(w, 400) : clamp(d.thumb.width, 1, 400);
		int th = d.thumb == null ? Math.min(h, 400) : clamp(d.thumb.height, 1, 400);
		long bytes = d.bytes == null ? 0 : Math.max(0, d.bytes);
		return new Chat.Attachment(d.id, "image/png".equals(d.mime), w, h, bytes, tw, th);
	}

	static int clamp(Integer v, int min, int max) {
		if (v == null) return min;
		return Math.max(min, Math.min(max, v));
	}

	static List<Chat.Reaction> reactions(List<ReactionDto> in) {
		List<Chat.Reaction> out = new ArrayList<Chat.Reaction>();
		if (in == null) return out;
		for (String id : Chat.REACTIONS) {
			for (ReactionDto r : in) {
				if (r == null || !id.equals(r.emoji)) continue;
				List<String> users = new ArrayList<String>();
				if (r.users != null) {
					for (String u : r.users) {
						String n = Uuids.normalize(u);
						if (n != null && !users.contains(n) && users.size() < 100) users.add(n);
					}
				}
				int count = r.count == null ? users.size() : Math.max(users.size(), Math.min(10000, r.count));
				if (count > 0) out.add(new Chat.Reaction(id, count, users));
				break;
			}
		}
		return out;
	}

	/** Nachricht bereinigen; ungültig → null. {@code fallbackConversation} für Antworten ohne conversationId. */
	static Chat.Message message(MessageDto d, String fallbackConversation) {
		if (d == null || !Chat.validMessageId(d.id) || d.seq == null || d.seq < 0) return null;
		String conv = Chat.validConversationId(d.conversationId) ? d.conversationId : fallbackConversation;
		if (!Chat.validConversationId(conv)) return null;
		boolean system = "system".equals(d.kind);
		boolean deleted = Boolean.TRUE.equals(d.deleted);
		boolean hidden = Boolean.TRUE.equals(d.hidden);
		boolean content = !deleted && !hidden;
		String text = content && d.text != null ? SafeText.message(d.text, SafeText.MAX_MESSAGE) : null;
		List<Chat.Attachment> atts = new ArrayList<Chat.Attachment>();
		if (content && d.attachments != null) {
			for (AttachmentDto a : d.attachments) {
				Chat.Attachment x = attachment(a);
				if (x != null && atts.size() < MAX_ATTACHMENTS) atts.add(x);
			}
		}
		Chat.Reply reply = null;
		if (content && d.replyTo != null && Chat.validMessageId(d.replyTo.id)) {
			ReplyDto r = d.replyTo;
			boolean gone = Boolean.TRUE.equals(r.deleted) || r.preview == null;
			reply = new Chat.Reply(r.id, r.seq == null ? 0 : r.seq, user(r.sender),
					gone ? null : SafeText.line(r.preview, 120), clamp(r.attachments, 0, 10), Boolean.TRUE.equals(r.invite) || Boolean.TRUE.equals(r.world),
					gone);
		}
		Chat.SystemInfo info = null;
		if (system && d.system != null && d.system.event != null && d.system.event.matches("[a-z_]{1,32}")) {
			info = new Chat.SystemInfo(d.system.event, user(d.system.actor), user(d.system.target),
					d.system.name == null ? null : SafeText.line(d.system.name, SafeText.MAX_NAME));
		}
		String deletedBy = deleted && d.deletedBy != null && d.deletedBy.matches("sender|owner|admin") ? d.deletedBy : null;
		String nonce = d.nonce != null && d.nonce.matches("[A-Za-z0-9_-]{8,64}") ? d.nonce : null;
		return new Chat.Message(d.id, conv, d.seq, system, user(d.sender), text, content ? (d.world != null ? world(d.world) : invite(d.invite)) : null, atts,
				reply, info, content ? reactions(d.reactions) : null, time(d.createdAt), time(d.editedAt), deleted, deletedBy,
				hidden, nonce, false, null);
	}

	static Chat.Conversation conversation(ConversationDto d) {
		if (d == null || !Chat.validConversationId(d.id)) return null;
		boolean group = "group".equals(d.kind);
		List<Chat.Member> members = new ArrayList<Chat.Member>();
		String owner = group ? Uuids.normalize(d.owner) : null;
		if (d.members != null) {
			for (MemberDto m : d.members) {
				if (m == null || members.size() >= MAX_MEMBERS) continue;
				String uuid = Uuids.normalize(m.uuid);
				if (uuid == null) continue;
				String name = SafeText.playerName(m.name);
				members.add(new Chat.Member(uuid, name == null ? "?" : name, group && uuid.equals(owner)));
			}
		}
		Chat.User peer = group ? null : user(d.peer);
		String name = group ? SafeText.line(d.name == null ? "" : d.name, SafeText.MAX_NAME) : null;
		if (group && (name == null || name.isEmpty())) name = "?";
		String reason = d.readOnlyReason != null && d.readOnlyReason.matches("[a-z_]{1,32}") ? d.readOnlyReason : null;
		List<Chat.Read> reads = new ArrayList<Chat.Read>();
		if (d.reads != null) {
			for (ReadDto r : d.reads) {
				String uuid = r == null ? null : Uuids.normalize(r.uuid);
				if (uuid == null || r.seq == null || reads.size() >= MAX_MEMBERS) continue;
				reads.add(new Chat.Read(uuid, r.seq, time(r.at)));
			}
		}
		Chat.Message last = message(d.lastMessage, d.id);
		long lastSeq = d.lastSeq == null ? last == null ? 0 : last.seq : Math.max(0, d.lastSeq);
		long updated = time(d.updatedAt);
		if (updated == 0 && last != null) updated = last.createdAt;
		return new Chat.Conversation(d.id, group, name, owner, members, peer, !Boolean.FALSE.equals(d.canWrite), reason, last,
				lastSeq, d.unread == null ? 0 : Math.min(100000, d.unread), Boolean.TRUE.equals(d.markedUnread),
				d.readSeq == null ? 0 : d.readSeq, Boolean.TRUE.equals(d.muted), time(d.mutedUntil), reads, updated);
	}

	static Chat.Conversation parseConversation(String json) {
		return conversation(GSON.fromJson(json, ConversationDto.class));
	}
}
