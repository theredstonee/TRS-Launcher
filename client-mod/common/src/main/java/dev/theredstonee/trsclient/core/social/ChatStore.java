package dev.theredstonee.trsclient.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat-Zustand im Speicher: Unterhaltungen, geladene Nachrichten je Unterhaltung (nach {@code seq} sortiert, ohne
 * Dopplungen), wer gerade tippt, Lesestände. Reine Logik – REST-Antworten und Stream-Ereignisse werden hier
 * eingespielt; die Oberfläche liest. Jede Änderung erhöht {@link #generation()}.
 *
 * <p>Nur aus dem Spiel-Thread benutzen.
 */
public final class ChatStore {
	/** Höchstens so viele Nachrichten je Unterhaltung im Speicher (ältere fallen weg, {@code hasMore}). */
	public static final int MAX_MESSAGES = 400;
	public static final int MAX_CONVERSATIONS = 300;

	/** Geladene Nachrichten einer Unterhaltung. */
	public static final class Thread {
		public final String conversationId;
		private final List<Chat.Message> messages = new ArrayList<Chat.Message>();
		/** Es gibt ältere Nachrichten auf dem Server. */
		public boolean hasMore;
		/** Die neueste Seite ist geladen. */
		public boolean loaded;
		public boolean loadingOlder;
		public boolean loading;
		/** Muss neu geladen werden (chat_reload, Resync). */
		public boolean stale;

		Thread(String conversationId) {
			this.conversationId = conversationId;
		}

		/** Nachrichten aufsteigend; ausstehende eigene am Ende. Nicht verändern. */
		public List<Chat.Message> messages() {
			return Collections.unmodifiableList(messages);
		}

		/** Höchste bestätigte seq (0 = keine). */
		public long newestSeq() {
			long max = 0;
			for (int i = messages.size() - 1; i >= 0; i--) {
				Chat.Message m = messages.get(i);
				if (!m.pending && m.failed == null) {
					max = Math.max(max, m.seq);
					break;
				}
			}
			return max;
		}

		/** Niedrigste geladene seq (0 = keine). */
		public long oldestSeq() {
			for (Chat.Message m : messages) if (!m.pending && m.failed == null) return m.seq;
			return 0;
		}

		public Chat.Message find(String id) {
			for (Chat.Message m : messages) if (m.id.equals(id)) return m;
			return null;
		}
	}

	private final Map<String, Chat.Conversation> conversations = new LinkedHashMap<String, Chat.Conversation>();
	private final Map<String, Thread> threads = new HashMap<String, Thread>();
	/** Unterhaltung → Spieler → läuft ab (ms). */
	private final Map<String, Map<String, Long>> typing = new HashMap<String, Map<String, Long>>();
	private List<Chat.Conversation> sorted;
	private boolean listLoaded;
	private int generation;
	private long pendingCounter;

	public int generation() {
		return generation;
	}

	private void changed() {
		generation++;
		sorted = null;
	}

	/** Die Liste der Unterhaltungen wurde mindestens einmal geladen. */
	public boolean listLoaded() {
		return listLoaded;
	}

	// --- Unterhaltungen ---

	/**
	 * Geladene Liste übernehmen. {@code complete} = das ist die ganze Liste (dann fallen fehlende weg, außer denen mit
	 * geladenen Nachrichten, die nur nicht auf der ersten Seite stehen).
	 */
	public void setConversations(List<Chat.Conversation> list, boolean complete) {
		Map<String, Chat.Conversation> next = new LinkedHashMap<String, Chat.Conversation>();
		for (Chat.Conversation c : list) next.put(c.id, merge(conversations.get(c.id), c));
		if (!complete) {
			for (Chat.Conversation c : conversations.values()) if (!next.containsKey(c.id)) next.put(c.id, c);
		}
		conversations.clear();
		conversations.putAll(next);
		threads.keySet().retainAll(conversations.keySet());
		listLoaded = true;
		trim();
		changed();
	}

	/** Neue/geänderte Unterhaltung (chat_conversation, Antworten von read/mute/…). */
	public void upsert(Chat.Conversation c) {
		if (c == null) return;
		conversations.put(c.id, merge(conversations.get(c.id), c));
		trim();
		changed();
	}

	/**
	 * Der Server kennt die neueste Nachricht, wir aber eventuell eine neuere aus dem Stream: die neuere Vorschau
	 * behalten.
	 */
	private static Chat.Conversation merge(Chat.Conversation old, Chat.Conversation fresh) {
		if (old == null || old.lastMessage == null) return fresh;
		if (fresh.lastMessage == null || old.lastMessage.seq > fresh.lastMessage.seq) {
			return new Chat.Conversation(fresh.id, fresh.group, fresh.name, fresh.owner, fresh.members, fresh.peer,
					fresh.canWrite, fresh.readOnlyReason, old.lastMessage, Math.max(old.lastSeq, fresh.lastSeq), fresh.unread,
					fresh.markedUnread, fresh.readSeq, fresh.muted, fresh.mutedUntil, fresh.reads,
					Math.max(old.updatedAt, fresh.updatedAt));
		}
		return fresh;
	}

	public void remove(String id) {
		if (conversations.remove(id) != null | threads.remove(id) != null) changed();
		typing.remove(id);
	}

	public Chat.Conversation get(String id) {
		return id == null ? null : conversations.get(id);
	}

	/** Nach letzter Aktivität, neueste zuerst. */
	public List<Chat.Conversation> sorted() {
		if (sorted == null) {
			List<Chat.Conversation> list = new ArrayList<Chat.Conversation>(conversations.values());
			Collections.sort(list, new Comparator<Chat.Conversation>() {
				@Override
				public int compare(Chat.Conversation a, Chat.Conversation b) {
					if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt ? -1 : 1;
					return a.id.compareTo(b.id);
				}
			});
			sorted = Collections.unmodifiableList(list);
		}
		return sorted;
	}

	/** DM mit diesem Spieler oder null. */
	public Chat.Conversation dmWith(String uuid) {
		for (Chat.Conversation c : conversations.values()) {
			if (!c.group && c.peer != null && c.peer.uuid.equals(uuid)) return c;
		}
		return null;
	}

	/** Summe für Abzeichen (API.md §18.2: stummgeschaltete zählen nicht, Markierung ohne Nachrichten = 1). */
	public int unreadTotal() {
		int n = 0;
		for (Chat.Conversation c : conversations.values()) n += c.badge();
		return n;
	}

	/** Ungelesen-Stand aus {@code GET /v1/chat/unread}: fehlende Unterhaltungen haben nichts Ungelesenes. */
	public void applyUnread(Map<String, ChatApi.UnreadState> states) {
		boolean any = false;
		for (Map.Entry<String, Chat.Conversation> e : conversations.entrySet()) {
			Chat.Conversation c = e.getValue();
			ChatApi.UnreadState s = states.get(c.id);
			int unread = s == null ? 0 : s.unread;
			boolean marked = s != null && s.marked;
			boolean muted = s == null ? c.muted : s.muted;
			if (unread != c.unread || marked != c.markedUnread || muted != c.muted) {
				e.setValue(c.withState(unread, marked, c.readSeq, muted, c.mutedUntil));
				any = true;
			}
		}
		if (any) changed();
	}

	private void trim() {
		if (conversations.size() <= MAX_CONVERSATIONS) return;
		List<Chat.Conversation> list = new ArrayList<Chat.Conversation>(conversations.values());
		sorted = null;
		Collections.sort(list, new Comparator<Chat.Conversation>() {
			@Override
			public int compare(Chat.Conversation a, Chat.Conversation b) {
				return Long.compare(b.updatedAt, a.updatedAt);
			}
		});
		for (int i = MAX_CONVERSATIONS; i < list.size(); i++) {
			conversations.remove(list.get(i).id);
			threads.remove(list.get(i).id);
		}
	}

	// --- Nachrichten ---

	public Thread thread(String id) {
		Thread t = threads.get(id);
		if (t == null) {
			t = new Thread(id);
			threads.put(id, t);
		}
		return t;
	}

	/** Schon geladene Nachrichten (null = nie geöffnet). */
	public Thread peek(String id) {
		return threads.get(id);
	}

	/** Neueste Seite geladen: ersetzt die bestätigten Nachrichten (ausstehende eigene bleiben). */
	public void loadedLatest(String id, ChatApi.MessagePage page) {
		Thread t = thread(id);
		List<Chat.Message> pending = new ArrayList<Chat.Message>();
		for (Chat.Message m : t.messages) if (m.pending || m.failed != null) pending.add(m);
		t.messages.clear();
		for (Chat.Message m : page.messages) insert(t, m);
		for (Chat.Message m : pending) {
			if (m.nonce == null || findByNonce(t, m.nonce) == null) t.messages.add(m);
		}
		t.hasMore = page.hasMore;
		t.loaded = true;
		t.loading = false;
		t.stale = false;
		cap(t, true);
		refreshLast(id, t);
		changed();
	}

	/** Ältere Seite davor. */
	public void loadedOlder(String id, ChatApi.MessagePage page) {
		Thread t = thread(id);
		for (Chat.Message m : page.messages) insert(t, m);
		t.hasMore = page.hasMore;
		t.loadingOlder = false;
		cap(t, false);
		changed();
	}

	/** Nachgeholte neuere (after=). */
	public void loadedAfter(String id, ChatApi.MessagePage page) {
		Thread t = thread(id);
		for (Chat.Message m : page.messages) insert(t, m);
		t.loading = false;
		cap(t, true);
		refreshLast(id, t);
		changed();
	}

	/**
	 * Neue oder geänderte Nachricht (Stream oder Antwort). {@code unreadDelta}: um so viel das Ungelesen der
	 * Unterhaltung erhöhen (nur neue fremde Nachrichten, die gerade niemand liest).
	 */
	public void message(Chat.Message m, int unreadDelta) {
		if (m == null) return;
		Thread t = threads.get(m.conversationId);
		boolean fresh = true;
		if (t != null) {
			fresh = t.find(m.id) == null && (m.nonce == null || findByNonce(t, m.nonce) == null);
			if (t.loaded) {
				insert(t, m);
				cap(t, true);
			}
		}
		Chat.Conversation c = conversations.get(m.conversationId);
		if (c != null) {
			int unread = c.unread + (fresh ? Math.max(0, unreadDelta) : 0);
			if (c.lastMessage == null || m.seq >= c.lastMessage.seq || c.lastMessage.id.equals(m.id)) {
				conversations.put(c.id, c.withMessage(m, unread));
			} else if (unread != c.unread) {
				conversations.put(c.id, c.withState(unread, c.markedUnread, c.readSeq, c.muted, c.mutedUntil));
			}
		}
		// Wer schreibt, tippt nicht mehr.
		if (m.sender != null) {
			Map<String, Long> ty = typing.get(m.conversationId);
			if (ty != null) ty.remove(m.sender.uuid);
		}
		changed();
	}

	/** Reaktionen einer Nachricht ersetzen. */
	public void reactions(String conversationId, String messageId, List<Chat.Reaction> reactions) {
		Thread t = threads.get(conversationId);
		if (t == null) return;
		for (int i = 0; i < t.messages.size(); i++) {
			Chat.Message m = t.messages.get(i);
			if (m.id.equals(messageId)) {
				t.messages.set(i, m.withReactions(reactions));
				changed();
				return;
			}
		}
	}

	/** Eigene Nachricht sofort zeigen (bis die Bestätigung mit derselben nonce kommt). */
	public Chat.Message addPending(String conversationId, String nonce, Chat.User self, String text, Chat.Reply reply,
			Chat.Invite invite, int attachments, long now) {
		Thread t = thread(conversationId);
		List<Chat.Attachment> none = Collections.emptyList();
		Chat.Message m = new Chat.Message("local-" + nonce, conversationId, Long.MAX_VALUE / 2 + (pendingCounter++), false,
				self, text, invite, none, reply, null, null, now, 0, false, null, false, nonce, true, null);
		t.messages.add(m);
		changed();
		return m;
	}

	/** Senden fehlgeschlagen: als Fehler markieren ({@code error} = i18n-Schlüssel). */
	public void pendingFailed(String conversationId, String nonce, String error) {
		Thread t = threads.get(conversationId);
		if (t == null) return;
		for (int i = 0; i < t.messages.size(); i++) {
			Chat.Message m = t.messages.get(i);
			if (m.pending && nonce.equals(m.nonce)) {
				t.messages.set(i, m.withFailed(error));
				changed();
				return;
			}
		}
	}

	/** Fehlgeschlagene eigene Nachricht verwerfen. */
	public void dropLocal(String conversationId, String localId) {
		Thread t = threads.get(conversationId);
		if (t == null) return;
		for (Iterator<Chat.Message> it = t.messages.iterator(); it.hasNext(); ) {
			Chat.Message m = it.next();
			if (m.id.equals(localId) && (m.pending || m.failed != null)) {
				it.remove();
				changed();
				return;
			}
		}
	}

	private static Chat.Message findByNonce(Thread t, String nonce) {
		for (Chat.Message m : t.messages) if (nonce.equals(m.nonce)) return m;
		return null;
	}

	/** Einsortieren nach seq; gleiche ID ersetzt, bestätigte eigene ersetzt die ausstehende mit derselben nonce. */
	static void insert(Thread t, Chat.Message m) {
		List<Chat.Message> list = t.messages;
		for (int i = 0; i < list.size(); i++) {
			Chat.Message old = list.get(i);
			if (old.id.equals(m.id)) {
				list.set(i, m);
				return;
			}
		}
		if (m.nonce != null) {
			for (Iterator<Chat.Message> it = list.iterator(); it.hasNext(); ) {
				Chat.Message old = it.next();
				if ((old.pending || old.failed != null) && m.nonce.equals(old.nonce)) it.remove();
			}
		}
		int i = list.size();
		while (i > 0) {
			Chat.Message prev = list.get(i - 1);
			if (prev.pending || prev.failed != null || prev.seq > m.seq) i--;
			else break;
		}
		list.add(i, m);
	}

	private static void cap(Thread t, boolean keepNewest) {
		int over = t.messages.size() - MAX_MESSAGES;
		if (over <= 0) return;
		if (keepNewest) {
			t.messages.subList(0, over).clear();
			t.hasMore = true;
		} else {
			// Beim Nachladen älterer Nachrichten: die neuesten verwerfen (werden beim Zurückscrollen nachgeholt).
			int size = t.messages.size();
			t.messages.subList(size - over, size).clear();
			t.stale = true;
		}
	}

	private void refreshLast(String id, Thread t) {
		Chat.Conversation c = conversations.get(id);
		if (c == null) return;
		Chat.Message last = null;
		for (int i = t.messages.size() - 1; i >= 0; i--) {
			Chat.Message m = t.messages.get(i);
			if (!m.pending && m.failed == null) {
				last = m;
				break;
			}
		}
		if (last != null && (c.lastMessage == null || last.seq >= c.lastMessage.seq)) {
			conversations.put(id, c.withMessage(last, c.unread));
		}
	}

	/** Nachrichten neu laden (chat_reload / Resync): Nachrichten verwerfen, beim Öffnen neu holen. */
	public void markStale(String id) {
		Thread t = threads.get(id);
		if (t != null) {
			t.stale = true;
			changed();
		}
	}

	public void markAllStale() {
		for (Thread t : threads.values()) t.stale = true;
		changed();
	}

	// --- Tippen, Lesen, Status ---

	public void typing(String conversationId, String uuid, boolean isTyping, long expiresAt) {
		if (conversationId == null || uuid == null) return;
		Map<String, Long> ty = typing.get(conversationId);
		if (isTyping) {
			if (ty == null) {
				ty = new LinkedHashMap<String, Long>();
				typing.put(conversationId, ty);
			}
			ty.put(uuid, expiresAt);
		} else if (ty != null) {
			ty.remove(uuid);
		}
		changed();
	}

	/** Wer tippt gerade (abgelaufene fallen ohne Ereignis weg, API.md §18.5)? */
	public List<String> typers(String conversationId, long now) {
		Map<String, Long> ty = typing.get(conversationId);
		if (ty == null || ty.isEmpty()) return Collections.emptyList();
		List<String> out = new ArrayList<String>();
		for (Iterator<Map.Entry<String, Long>> it = ty.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Long> e = it.next();
			if (e.getValue() <= now) it.remove();
			else out.add(e.getKey());
		}
		return out;
	}

	/** Lesebestätigung eines Mitglieds, oder mein eigener Lesestand von einem anderen Gerät ({@code self}). */
	public void read(String conversationId, String uuid, long seq, long at, boolean self) {
		Chat.Conversation c = conversations.get(conversationId);
		if (c == null || uuid == null) return;
		if (self) {
			if (seq > c.readSeq || c.unread > 0) {
				int unread = seq >= c.lastSeq ? 0 : c.unread;
				conversations.put(c.id, c.withState(unread, false, Math.max(c.readSeq, seq), c.muted, c.mutedUntil));
			}
		} else {
			conversations.put(c.id, c.withRead(uuid, seq, at));
		}
		changed();
	}

	/** Mein Stand einer Unterhaltung (chat_state). */
	public void state(String conversationId, int unread, boolean marked, long readSeq, boolean muted, long mutedUntil) {
		Chat.Conversation c = conversations.get(conversationId);
		if (c == null) return;
		conversations.put(c.id, c.withState(unread, marked, readSeq < 0 ? c.readSeq : readSeq, muted, mutedUntil));
		changed();
	}

	/** Alles vergessen (Kontowechsel). */
	public void clear() {
		conversations.clear();
		threads.clear();
		typing.clear();
		listLoaded = false;
		changed();
	}
}
