package dev.theredstonee.trsclient.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unveränderliche, bereinigte Ansichten des Chats (API.md §18.1). Alles, was hier drin steht, darf die Oberfläche
 * direkt zeichnen: Text ohne Formatierungscodes, gültige IDs/UUIDs, begrenzte Listen.
 */
public final class Chat {
	private Chat() {
	}

	/** Feste Reaktionen (API.md §18.1) in Anzeige-Reihenfolge. */
	public static final String[] REACTIONS = {"thumbs_up", "heart", "laugh", "wow", "sad", "angry", "party", "fire", "eyes", "check"};

	public static boolean validReaction(String id) {
		if (id == null) return false;
		for (String r : REACTIONS) if (r.equals(id)) return true;
		return false;
	}

	public static boolean validConversationId(String id) {
		return id != null && id.matches("c[0-9a-f]{20}");
	}

	public static boolean validMessageId(String id) {
		return id != null && id.matches("m[0-9a-f]{20}");
	}

	public static boolean validAttachmentId(String id) {
		return id != null && id.matches("a[0-9a-f]{8,40}");
	}

	/** Ein Spieler (UUID 32 Hex, Minecraft-Name). */
	public static final class User {
		public final String uuid;
		public final String name;

		public User(String uuid, String name) {
			this.uuid = uuid;
			this.name = name;
		}
	}

	/** Mitglied einer Unterhaltung. */
	public static final class Member {
		public final String uuid;
		public final String name;
		public final boolean owner;

		public Member(String uuid, String name, boolean owner) {
			this.uuid = uuid;
			this.name = name;
			this.owner = owner;
		}
	}

	/** Lesestand eines anderen Mitglieds (nur bei gegenseitigen Lesebestätigungen). */
	public static final class Read {
		public final String uuid;
		public final long seq;
		public final long at;

		public Read(String uuid, long seq, long at) {
			this.uuid = uuid;
			this.seq = seq;
			this.at = at;
		}
	}

	/** Servereinladung (API.md §18.6) oder Weltkarte (§21.8, dann ist {@link #world} gesetzt). */
	public static final class Invite {
		public final String address;
		/** Beschriftung (≤ 32 Zeichen) oder null. */
		public final String name;
		/** Weltkarte des Welt-Hostings (dann ist {@link #address} leer) oder null. */
		public final World world;

		public Invite(String address, String name) {
			this(address, name, null);
		}

		public Invite(String address, String name, World world) {
			this.address = address;
			this.name = name;
			this.world = world;
		}
	}

	/** Weltkarte: Momentaufnahme einer gehosteten Welt (API.md §21.8). Beitreten = {@code join {code}}. */
	public static final class World {
		public final String roomId;
		public final String code;
		public final String mcVersion;
		public final String loader;
		public final String hostUuid;
		public final String hostName;

		public World(String roomId, String code, String mcVersion, String loader, String hostUuid, String hostName) {
			this.roomId = roomId;
			this.code = code;
			this.mcVersion = mcVersion;
			this.loader = loader;
			this.hostUuid = hostUuid;
			this.hostName = hostName;
		}
	}

	/** Bild einer Nachricht. {@code path} wird nie vom Server übernommen, sondern aus der ID gebaut. */
	public static final class Attachment {
		public final String id;
		public final boolean png;
		public final int width;
		public final int height;
		public final long bytes;
		public final int thumbWidth;
		public final int thumbHeight;

		public Attachment(String id, boolean png, int width, int height, long bytes, int thumbWidth, int thumbHeight) {
			this.id = id;
			this.png = png;
			this.width = width;
			this.height = height;
			this.bytes = bytes;
			this.thumbWidth = thumbWidth;
			this.thumbHeight = thumbHeight;
		}

		public String path(boolean thumb) {
			return "/v1/chat/attachments/" + id + (thumb ? "?thumb=1" : "");
		}
	}

	/** Antwort-Vorschau. */
	public static final class Reply {
		public final String id;
		public final long seq;
		public final User sender;
		/** Vorschau (≤ 120 Zeichen) oder null (gelöscht/verborgen). */
		public final String preview;
		public final int attachments;
		public final boolean invite;
		public final boolean deleted;

		public Reply(String id, long seq, User sender, String preview, int attachments, boolean invite, boolean deleted) {
			this.id = id;
			this.seq = seq;
			this.sender = sender;
			this.preview = preview;
			this.attachments = attachments;
			this.invite = invite;
			this.deleted = deleted;
		}
	}

	/** Gruppenereignis ({@code kind: "system"}). */
	public static final class SystemInfo {
		/** group_created, member_added, member_removed, member_left, renamed, owner_changed. */
		public final String event;
		public final User actor;
		public final User target;
		public final String name;

		public SystemInfo(String event, User actor, User target, String name) {
			this.event = event;
			this.actor = actor;
			this.target = target;
			this.name = name;
		}
	}

	/** Reaktion mit Anzahl und wer. */
	public static final class Reaction {
		public final String emoji;
		public final int count;
		public final List<String> users;

		public Reaction(String emoji, int count, List<String> users) {
			this.emoji = emoji;
			this.count = count;
			this.users = Collections.unmodifiableList(new ArrayList<String>(users));
		}

		public boolean by(String uuid) {
			return uuid != null && users.contains(uuid);
		}
	}

	/** Eine Nachricht (auch Systemzeile). */
	public static final class Message {
		public final String id;
		public final String conversationId;
		public final long seq;
		public final boolean system;
		public final User sender;
		/** Text (bereinigt) oder null. */
		public final String text;
		public final Invite invite;
		public final List<Attachment> attachments;
		public final Reply reply;
		public final SystemInfo info;
		public final List<Reaction> reactions;
		public final long createdAt;
		/** 0 = nie bearbeitet. */
		public final long editedAt;
		public final boolean deleted;
		/** sender, owner, admin oder null. */
		public final String deletedBy;
		/** Von einem blockierten Spieler: ohne Inhalt. */
		public final boolean hidden;
		public final String nonce;
		/** Nur lokal: wird gerade gesendet. */
		public final boolean pending;
		/** Nur lokal: Senden fehlgeschlagen (i18n-Schlüssel) oder null. */
		public final String failed;

		public Message(String id, String conversationId, long seq, boolean system, User sender, String text, Invite invite,
				List<Attachment> attachments, Reply reply, SystemInfo info, List<Reaction> reactions, long createdAt,
				long editedAt, boolean deleted, String deletedBy, boolean hidden, String nonce, boolean pending, String failed) {
			this.id = id;
			this.conversationId = conversationId;
			this.seq = seq;
			this.system = system;
			this.sender = sender;
			this.text = text;
			this.invite = invite;
			this.attachments = attachments == null ? Collections.<Attachment>emptyList()
					: Collections.unmodifiableList(new ArrayList<Attachment>(attachments));
			this.reply = reply;
			this.info = info;
			this.reactions = reactions == null ? Collections.<Reaction>emptyList()
					: Collections.unmodifiableList(new ArrayList<Reaction>(reactions));
			this.createdAt = createdAt;
			this.editedAt = editedAt;
			this.deleted = deleted;
			this.deletedBy = deletedBy;
			this.hidden = hidden;
			this.nonce = nonce;
			this.pending = pending;
			this.failed = failed;
		}

		public Message withReactions(List<Reaction> r) {
			return new Message(id, conversationId, seq, system, sender, text, invite, attachments, reply, info, r, createdAt,
					editedAt, deleted, deletedBy, hidden, nonce, pending, failed);
		}

		public Message withFailed(String error) {
			return new Message(id, conversationId, seq, system, sender, text, invite, attachments, reply, info, reactions,
					createdAt, editedAt, deleted, deletedBy, hidden, nonce, error == null, error);
		}

		/** Von diesem Spieler? */
		public boolean from(String uuid) {
			return sender != null && uuid != null && uuid.equals(sender.uuid);
		}

		/** Inhalt vorhanden (nicht gelöscht, nicht verborgen, keine Systemzeile)? */
		public boolean hasContent() {
			return !deleted && !hidden && !system;
		}

		/** Einzeilige Vorschau für Liste und Toasts. */
		public String preview() {
			if (text != null && !text.isEmpty()) return SafeText.line(text, 120);
			return null;
		}
	}

	/** Eine Unterhaltung (DM oder Gruppe). */
	public static final class Conversation {
		public final String id;
		public final boolean group;
		/** Gruppenname (DM: null). */
		public final String name;
		public final String owner;
		public final List<Member> members;
		/** DM: der andere Spieler. */
		public final User peer;
		public final boolean canWrite;
		/** null, not_friends, chat_muted. */
		public final String readOnlyReason;
		public final Message lastMessage;
		public final long lastSeq;
		public final int unread;
		public final boolean markedUnread;
		public final long readSeq;
		public final boolean muted;
		/** 0 = unbegrenzt (wenn {@link #muted}). */
		public final long mutedUntil;
		public final List<Read> reads;
		public final long updatedAt;

		public Conversation(String id, boolean group, String name, String owner, List<Member> members, User peer,
				boolean canWrite, String readOnlyReason, Message lastMessage, long lastSeq, int unread, boolean markedUnread,
				long readSeq, boolean muted, long mutedUntil, List<Read> reads, long updatedAt) {
			this.id = id;
			this.group = group;
			this.name = name;
			this.owner = owner;
			this.members = members == null ? Collections.<Member>emptyList()
					: Collections.unmodifiableList(new ArrayList<Member>(members));
			this.peer = peer;
			this.canWrite = canWrite;
			this.readOnlyReason = readOnlyReason;
			this.lastMessage = lastMessage;
			this.lastSeq = lastSeq;
			this.unread = Math.max(0, unread);
			this.markedUnread = markedUnread;
			this.readSeq = readSeq;
			this.muted = muted;
			this.mutedUntil = mutedUntil;
			this.reads = reads == null ? Collections.<Read>emptyList() : Collections.unmodifiableList(new ArrayList<Read>(reads));
			this.updatedAt = updatedAt;
		}

		/** Anzeigename: Gruppenname bzw. Name des anderen Spielers. */
		public String title() {
			if (group) return name == null ? "?" : name;
			return peer == null ? "?" : peer.name;
		}

		/** UUID für das Gesicht (DM: der andere, Gruppe: der Besitzer). */
		public String faceUuid() {
			return group ? owner : peer == null ? null : peer.uuid;
		}

		public boolean ownedBy(String uuid) {
			return group && uuid != null && uuid.equals(owner);
		}

		public boolean isMember(String uuid) {
			for (Member m : members) if (m.uuid.equals(uuid)) return true;
			return false;
		}

		/** Zählt für die Gesamtzahl ungelesener Unterhaltungen/Nachrichten (API.md §18.2). */
		public int badge() {
			if (muted) return 0;
			if (unread > 0) return unread;
			return markedUnread ? 1 : 0;
		}

		public Conversation withMessage(Message m, int newUnread) {
			long seq = Math.max(lastSeq, m.seq);
			Message last = lastMessage == null || m.seq >= lastMessage.seq || lastMessage.id.equals(m.id) ? m : lastMessage;
			return new Conversation(id, group, name, owner, members, peer, canWrite, readOnlyReason, last, seq, newUnread,
					markedUnread, readSeq, muted, mutedUntil, reads, Math.max(updatedAt, m.createdAt));
		}

		public Conversation withLast(Message m) {
			return new Conversation(id, group, name, owner, members, peer, canWrite, readOnlyReason, m, lastSeq, unread,
					markedUnread, readSeq, muted, mutedUntil, reads, updatedAt);
		}

		public Conversation withState(int unreadCount, boolean marked, long read, boolean isMuted, long until) {
			return new Conversation(id, group, name, owner, members, peer, canWrite, readOnlyReason, lastMessage, lastSeq,
					unreadCount, marked, read, isMuted, until, reads, updatedAt);
		}

		public Conversation withRead(String uuid, long seq, long at) {
			List<Read> next = new ArrayList<Read>();
			boolean found = false;
			for (Read r : reads) {
				if (r.uuid.equals(uuid)) {
					next.add(new Read(uuid, Math.max(r.seq, seq), at));
					found = true;
				} else {
					next.add(r);
				}
			}
			if (!found && next.size() < 64) next.add(new Read(uuid, seq, at));
			return new Conversation(id, group, name, owner, members, peer, canWrite, readOnlyReason, lastMessage, lastSeq,
					unread, markedUnread, readSeq, muted, mutedUntil, next, updatedAt);
		}

		/** Hat ein anderes Mitglied bis {@code seq} gelesen? */
		public boolean seenBy(long seq) {
			for (Read r : reads) if (r.seq >= seq) return true;
			return false;
		}
	}

	/** Status eines eingeladenen Servers (API.md §18.6). */
	public static final class ServerStatus {
		public final String address;
		public final boolean online;
		public final String reason;
		public final int players;
		public final int max;
		public final String motd;
		public final String version;
		/** PNG-Bytes des Symbols (64×64) oder null. */
		public final byte[] icon;

		public ServerStatus(String address, boolean online, String reason, int players, int max, String motd, String version,
				byte[] icon) {
			this.address = address;
			this.online = online;
			this.reason = reason;
			this.players = players;
			this.max = max;
			this.motd = motd;
			this.version = version;
			this.icon = icon;
		}
	}

	/** Eigene Moderation (API.md §20.4): Stummschaltung im Chat. */
	public static final class Moderation {
		public static final Moderation NONE = new Moderation(false, 0, null);
		public final boolean muted;
		/** 0 = bis zur Prüfung/Aufhebung. */
		public final long until;
		public final String reason;

		public Moderation(boolean muted, long until, String reason) {
			this.muted = muted;
			this.until = until;
			this.reason = reason;
		}

		public boolean active(long now) {
			return muted && (until == 0 || until > now);
		}
	}
}
