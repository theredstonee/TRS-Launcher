package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.ui.TextureRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sozialfunktionen im Spiel: Chat (API.md §18), Echtzeit über {@code /v1/events/me} (§19) mit Wiederaufnahme und
 * Resync, Meldungen (§20), Benachrichtigungen ({@link Toasts}) und Chat-Bilder ({@link ChatImages}).
 *
 * <p>Netzwerk läuft in eigenen Hintergrund-Threads (REST, Uploads, Bilder); Ergebnisse landen in einer Warteschlange
 * und werden im nächsten {@link #tick} im Spiel-Thread übernommen. Die Oberfläche liest {@link #store()} und ruft die
 * Aktionen hier auf (Spiel-Thread).
 */
public final class Social {
	/** Rückfall ohne Stream: Ungelesen-Stand so oft, offene Unterhaltung so oft nachholen (API.md §19). */
	static final long POLL_UNREAD_MS = 45_000L;
	static final long POLL_OPEN_MS = 10_000L;
	/** Tippen höchstens so oft melden (API.md §18.5). */
	static final long TYPING_EVERY_MS = 3_000L;
	/** Wie lange ein offener Bildschirm als „offen“ gilt (er meldet sich jedes Bild). */
	static final long INTEREST_MS = 3_000L;
	static final long STATUS_TTL_MS = 60_000L;
	static final long NOTICE_MS = 6_000L;
	public static final int MAX_IMAGES = 10;

	/** Bekannte API-Fehlercodes mit eigener Meldung ({@code social.error.<code>}). */
	static final Set<String> KNOWN_ERRORS = new HashSet<String>(Arrays.asList(
			"not_friends", "chat_muted", "message_too_long", "empty_message", "links_not_allowed", "spam_detected",
			"message_blocked", "rate_limited", "group_full", "group_limit", "target_group_limit", "invalid_name",
			"not_owner", "member_not_found", "conversation_not_found", "message_not_found", "message_deleted",
			"not_sender", "already_reported", "too_many_open_reports", "cannot_target_self", "not_reportable",
			"too_many_pending_attachments", "storage_quota", "storage_full", "payload_too_large", "image_too_large",
			"invalid_image", "unsupported_media_type", "player_not_found", "attachment_not_found", "offline",
			"image_unreadable", "busy"));

	/** Anbindung an TrsOnline. */
	public interface Backend {
		/** 401 für dieses Token: neu anmelden. */
		void unauthorized(String rejectedToken);

		/** Die Freunde des Spiels (Aktualisieren bei Freundes-Ereignissen). */
		Friends friends();

		/**
		 * Welt-Hosting-Ereignis ({@code hosting_*}) oder Stream-Neubeginn ({@code hello}/{@code resync}) – Spiel-Thread.
		 */
		default void hosting(MeEvent e) {
		}

		/** Braucht jemand anderes (Welt-Hosting) gerade den Echtzeit-Stream? */
		default boolean wantsStream() {
			return false;
		}
	}

	/** Ergebnis einer Aktion im Spiel-Thread; {@code error} = i18n-Schlüssel oder null. */
	public interface Done<T> {
		void done(T value, String error);
	}

	/** Meldung für die Oberfläche (Erfolg oder Fehler einer Aktion). */
	public static final class Notice {
		public final String key;
		public final Object[] args;
		public final boolean error;
		public final long at;

		Notice(String key, Object[] args, boolean error, long at) {
			this.key = key;
			this.args = args == null ? new Object[0] : args;
			this.error = error;
			this.at = at;
		}
	}

	/** Wie weit ein Bild-Upload ist. */
	public static final class Upload {
		public final int done;
		public final int total;

		Upload(int done, int total) {
			this.done = done;
			this.total = total;
		}
	}

	private static final class SendJob {
		final String conversationId;
		final String nonce;
		final String text;
		final String replyTo;
		final List<Path> images;
		final Chat.Invite invite;

		SendJob(String conversationId, String nonce, String text, String replyTo, List<Path> images, Chat.Invite invite) {
			this.conversationId = conversationId;
			this.nonce = nonce;
			this.text = text;
			this.replyTo = replyTo;
			this.images = images;
			this.invite = invite;
		}
	}

	private static final class StatusEntry {
		Chat.ServerStatus status;
		long fetchedAt;
		boolean loading;
	}

	private final ChatApi api;
	private final MeStream stream;
	private final Backend backend;
	private final Executor rest;
	private final Executor uploads;
	private final ChatImages images;
	private final ChatStore store = new ChatStore();
	private final Toasts toasts = new Toasts();
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<Runnable>();
	private final List<MeEvent> buffer = new ArrayList<MeEvent>();
	private final Map<String, SendJob> sendJobs = new HashMap<String, SendJob>();
	private final Map<String, Upload> uploadProgress = new java.util.concurrent.ConcurrentHashMap<String, Upload>();
	private final Map<String, StatusEntry> statuses = new HashMap<String, StatusEntry>();

	// Nur Spiel-Thread:
	private String token;
	private String self;
	private String selfName;
	private int generation;
	/** Zählt Kontowechsel: Ergebnisse einer alten Sitzung werden verworfen. */
	private int session;
	private boolean listInFlight;
	private long listNotBefore;
	private boolean resyncWanted;
	private long lastUnreadPoll;
	private long lastOpenPoll;
	private boolean unreadInFlight;
	private boolean moderationLoaded;
	private boolean settingsLoaded;
	private Chat.Moderation moderation = Chat.Moderation.NONE;
	private ChatApi.ChatSettings chatSettings = new ChatApi.ChatSettings(true, true);
	private long screenUntil;
	private String viewing;
	private String suppressAutoRead;
	private long lastReadSent;
	private long lastReadSeq;
	private String typingIn;
	private long typingSentAt;
	private Notice notice;
	private boolean streamWanted;
	private int eventsSeen;
	/** Schon ein hello in dieser Sitzung (das erste braucht keinen Resync). */
	private boolean helloSeen;

	public Social(ChatApi api, MeStream stream, Backend backend) {
		this(api, stream, backend, worker("TRS-Chat", 1, 128), worker("TRS-Chat-Upload", 1, 32),
				worker("TRS-Chat-Bilder", 2, 256));
	}

	Social(ChatApi api, MeStream stream, Backend backend, Executor rest, Executor uploads, Executor imageWorker) {
		this.api = api;
		this.stream = stream;
		this.backend = backend;
		this.rest = rest;
		this.uploads = uploads;
		this.images = new ChatImages(imageWorker, 64);
	}

	static ThreadPoolExecutor worker(final String name, int threads, int queue) {
		ThreadPoolExecutor ex = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<Runnable>(queue), new java.util.concurrent.ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, name);
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY + 1);
				return t;
			}
		});
		ex.allowCoreThreadTimeOut(true);
		return ex;
	}

	// --- Lesen (Spiel-/Render-Thread) ---

	public ChatStore store() {
		return store;
	}

	public Toasts toasts() {
		return toasts;
	}

	public ChatImages images() {
		return images;
	}

	/** Eigene UUID (32 Hex) oder null. */
	public String self() {
		return self;
	}

	public String selfName() {
		return selfName;
	}

	public boolean signedIn() {
		return token != null;
	}

	/** Steht der Echtzeit-Stream? */
	public boolean live() {
		return stream.connected();
	}

	public Chat.Moderation moderation() {
		return moderation;
	}

	public ApiSettings settings() {
		return new ApiSettings(chatSettings.readReceipts, chatSettings.typing);
	}

	/** Chat-Einstellungen des Kontos (für die Oberfläche). */
	public static final class ApiSettings {
		public final boolean readReceipts;
		public final boolean typing;

		ApiSettings(boolean readReceipts, boolean typing) {
			this.readReceipts = readReceipts;
			this.typing = typing;
		}
	}

	/** Aktuelle Meldung (≤ {@link #NOTICE_MS} alt) oder null. */
	public Notice notice(long now) {
		Notice n = notice;
		return n != null && now - n.at < NOTICE_MS ? n : null;
	}

	public void clearNotice() {
		notice = null;
	}

	/** Wird bei jeder Zustandsänderung erhöht (Oberflächen bauen damit ihre Zwischenspeicher neu). */
	public int generation() {
		return generation + store.generation();
	}

	/** Upload-Fortschritt einer ausstehenden Nachricht (nonce) oder null. */
	public Upload upload(String nonce) {
		return nonce == null ? null : uploadProgress.get(nonce);
	}

	public int eventsSeen() {
		return eventsSeen;
	}

	// --- Takt (Spiel-Thread) ---

	/**
	 * Einmal je Client-Tick aus {@link dev.theredstonee.trsclient.core.online.TrsOnline#tick}.
	 *
	 * @param currentToken TRS-Token oder null (nicht angemeldet)
	 * @param uuid eigene UUID (32 Hex)
	 * @param name eigener Name
	 * @param enabled Modul „Sozial“ an (Stream + Toasts auch ohne offenen Bildschirm)
	 */
	public void tick(long now, String currentToken, String uuid, String name, boolean enabled) {
		Runnable r;
		while ((r = results.poll()) != null) r.run();
		SocialOverlay.applySettings(toasts);
		if (currentToken == null || uuid == null) {
			token = null;
			stream.stop();
			return;
		}
		if (!uuid.equals(self)) reset(uuid, name);
		token = currentToken;
		boolean screen = now <= screenUntil;
		boolean want = enabled || screen || backend.wantsStream();
		streamWanted = want;
		toasts.viewing(screen ? viewing : null);
		stream.update(now, currentToken, want);
		if (stream.takeUnauthorized()) backend.unauthorized(currentToken);
		if (stream.takeOverflow()) resyncWanted = true;
		buffer.clear();
		stream.drain(buffer);
		for (MeEvent e : buffer) {
			try {
				apply(e, now, screen);
			} catch (RuntimeException ex) {
				// Ein kaputtes Ereignis darf den Rest nicht aufhalten.
			}
		}
		if (!want) return;
		if (!moderationLoaded) loadModeration();
		if (!settingsLoaded) loadSettings();
		if (resyncWanted && !listInFlight) {
			resyncWanted = false;
			resync(now);
		} else if (!store.listLoaded() && !listInFlight && now >= listNotBefore) {
			loadList(now);
		}
		// Rückfall ohne Stream: höflich pollen.
		if (!stream.connected() && store.listLoaded()) {
			if (now - lastUnreadPoll >= POLL_UNREAD_MS && !unreadInFlight) pollUnread(now);
			if (screen && viewing != null && now - lastOpenPoll >= POLL_OPEN_MS) {
				lastOpenPoll = now;
				catchUp(viewing);
			}
		}
		if (screen && viewing != null) tickViewing(now);
		if (typingIn != null && (!screen || !typingIn.equals(viewing))) typing(typingIn, false);
	}

	private void reset(String uuid, String name) {
		self = uuid;
		selfName = name;
		token = null;
		stream.reset();
		store.clear();
		toasts.clear();
		statuses.clear();
		sendJobs.clear();
		uploadProgress.clear();
		moderation = Chat.Moderation.NONE;
		moderationLoaded = false;
		settingsLoaded = false;
		listInFlight = false;
		listNotBefore = 0;
		resyncWanted = false;
		viewing = null;
		typingIn = null;
		helloSeen = false;
		generation++;
		session++;
	}

	/** Der Sozial-Bildschirm ist offen (jedes Bild melden) – mit {@code conversationId} als offener Unterhaltung. */
	public void screenOpen(String conversationId, long now) {
		screenUntil = now + INTEREST_MS;
		if (conversationId == null ? viewing != null : !conversationId.equals(viewing)) {
			viewing = conversationId;
			suppressAutoRead = null;
			lastReadSeq = 0;
			if (conversationId != null) {
				toasts.dismissConversation(conversationId);
				ChatStore.Thread t = store.peek(conversationId);
				if (t == null || !t.loaded || t.stale) loadLatest(conversationId);
				else catchUp(conversationId);
			}
		}
	}

	/** Bildschirm geschlossen. */
	public void screenClosed() {
		if (typingIn != null) typing(typingIn, false);
		screenUntil = 0;
		viewing = null;
	}

	public String viewing() {
		return viewing;
	}

	private void tickViewing(long now) {
		Chat.Conversation c = store.get(viewing);
		if (c == null) return;
		ChatStore.Thread t = store.peek(viewing);
		if (t != null && t.loaded && t.stale && !t.loading) loadLatest(viewing);
		if (viewing.equals(suppressAutoRead)) return;
		if ((c.unread > 0 || c.markedUnread || c.readSeq < c.lastSeq) && now - lastReadSent > 1000L && c.lastSeq > lastReadSeq) {
			lastReadSent = now;
			lastReadSeq = c.lastSeq;
			markRead(viewing, c.lastSeq);
		}
	}

	// --- Ereignisse ---

	void apply(MeEvent e, long now, boolean screen) {
		eventsSeen++;
		Friends friends = backend.friends();
		String t = e.type;
		if (t.startsWith("hosting_") || t.equals("hello") || t.equals("resync")) {
			try {
				backend.hosting(e);
			} catch (RuntimeException ignored) {
				// Hosting darf den Chat nie stören.
			}
			if (t.startsWith("hosting_")) return;
		}
		if (t.equals("hello")) {
			// Neuer Stream ohne Wiederaufnahme, obwohl schon einer lief (Lücke unbekannt): alles neu laden.
			if (!e.resumed && helloSeen && store.listLoaded()) resyncWanted = true;
			helloSeen = true;
			return;
		}
		if (t.equals("resync")) {
			resyncWanted = true;
			return;
		}
		if (t.equals("chat_message")) {
			Chat.Message m = e.message;
			if (m == null) return;
			if (store.get(m.conversationId) == null) fetchConversation(m.conversationId);
			boolean own = m.from(self);
			boolean reading = screen && m.conversationId.equals(viewing);
			boolean counts = !own && !m.hidden && !m.system && !m.deleted;
			store.message(m, counts && !reading ? 1 : 0);
			if (own && m.nonce != null) {
				sendJobs.remove(m.nonce);
				uploadProgress.remove(m.nonce);
			}
			if (reading && counts && viewing.equals(suppressAutoRead)) suppressAutoRead = null;
			Chat.Conversation c = store.get(m.conversationId);
			if (counts && !reading && (c == null || !c.muted)) messageToast(m, c, now);
			return;
		}
		if (t.equals("chat_message_edited") || t.equals("chat_message_deleted")) {
			if (e.message != null) store.message(e.message, 0);
			return;
		}
		if (t.equals("chat_reactions")) {
			if (e.conversationId != null && e.messageId != null && e.reactions != null) {
				store.reactions(e.conversationId, e.messageId, e.reactions);
			}
			return;
		}
		if (t.equals("chat_typing")) {
			if (e.uuid != null && !e.uuid.equals(self)) store.typing(e.conversationId, e.uuid, e.typing, now + e.expiresInMs);
			return;
		}
		if (t.equals("chat_read")) {
			if (e.conversationId != null && e.uuid != null) {
				store.read(e.conversationId, e.uuid, e.seq, e.at, e.uuid.equals(self));
				if (e.uuid.equals(self)) toasts.dismissConversation(e.conversationId);
			}
			return;
		}
		if (t.equals("chat_state")) {
			if (e.conversationId != null) {
				store.state(e.conversationId, e.unread, e.markedUnread, e.readSeq, e.muted, e.mutedUntil);
			}
			return;
		}
		if (t.equals("chat_conversation")) {
			store.upsert(e.conversation);
			return;
		}
		if (t.equals("chat_conversation_removed")) {
			if (e.conversationId != null) {
				store.remove(e.conversationId);
				toasts.dismissConversation(e.conversationId);
				if (e.conversationId.equals(viewing)) viewing = null;
			}
			return;
		}
		if (t.equals("chat_reload")) {
			if (e.conversationId != null) {
				store.markStale(e.conversationId);
				if (e.conversationId.equals(viewing)) loadLatest(viewing);
			}
			return;
		}
		if (t.equals("friend_request")) {
			if (friends != null) friends.refresh();
			if (e.user != null) {
				toasts.add(Toasts.Kind.REQUEST, "req:" + e.user.uuid, e.user.name, I18n.tr("social.toast.request"),
						e.user.uuid, e.user.name, null, null, now);
			}
			return;
		}
		if (t.equals("cape_offer")) {
			if (friends != null) friends.refresh();
			if (e.user != null) {
				String cape = e.capeName == null ? "?" : e.capeName;
				toasts.add(Toasts.Kind.CAPE_OFFER, "cape:" + e.user.uuid + ":" + cape, e.user.name,
						I18n.tr("social.toast.capeOffer", cape), e.user.uuid, e.user.name, null, null, now);
			}
			return;
		}
		if (t.equals("presence") || t.equals("friend_online")) {
			if (friends != null && e.uuid != null) {
				friends.applyPresence(e.uuid, e.presence, e.presenceVersion, e.presenceLoader, e.presenceServer);
			} else if (friends != null && e.user != null) {
				friends.applyPresence(e.user.uuid, e.presence == null ? "online" : e.presence, e.presenceVersion,
						e.presenceLoader, e.presenceServer);
			}
			if (t.equals("friend_online") && e.user != null) {
				toasts.add(Toasts.Kind.ONLINE, "online:" + e.user.uuid, e.user.name, I18n.tr("social.toast.online"),
						e.user.uuid, e.user.name, null, null, now);
			}
			return;
		}
		if (t.equals("friend_request_cancelled") || t.equals("friend_added") || t.equals("friend_removed")
				|| t.equals("friends_changed") || t.equals("cape_offer_accepted") || t.equals("cape_share_removed")) {
			if (friends != null) friends.refresh();
			if (t.equals("friend_removed") || t.equals("friend_added")) {
				// Schreibrecht der DM ändert sich – kommt als chat_conversation; sicherheitshalber Liste auffrischen.
				listNotBefore = 0;
			}
			return;
		}
		if (t.equals("report_update")) {
			if (e.reportStatus != null) {
				String key = "resolved".equals(e.reportStatus)
						? ("actioned".equals(e.reportOutcome) ? "social.toast.reportActioned" : "social.toast.reportReviewed")
						: "in_review".equals(e.reportStatus) ? "social.toast.reportInReview" : null;
				if (key != null) {
					toasts.add(Toasts.Kind.REPORT, "report:" + e.reportId, I18n.tr("social.toast.reportTitle"), I18n.tr(key),
							null, null, null, null, now);
				}
			}
			return;
		}
		if (t.equals("moderation")) {
			if ("mute".equals(e.action)) {
				moderation = new Chat.Moderation(true, e.until, e.reason);
				toasts.add(Toasts.Kind.MODERATION, "moderation", I18n.tr("social.toast.mutedTitle"),
						e.until == 0 ? I18n.tr("social.moderation.mutedOpen") : I18n.tr("social.moderation.mutedUntil",
								Times.dateTime(e.until)), null, null, null, null, now);
			} else if ("unmute".equals(e.action)) {
				moderation = Chat.Moderation.NONE;
				toasts.add(Toasts.Kind.MODERATION, "moderation", I18n.tr("social.toast.unmutedTitle"),
						I18n.tr("social.toast.unmuted"), null, null, null, null, now);
			} else if ("warn".equals(e.action)) {
				toasts.add(Toasts.Kind.MODERATION, "warn", I18n.tr("social.toast.warnTitle"),
						e.reason == null ? I18n.tr("social.toast.warn") : e.reason, null, null, null, null, now);
			}
			generation++;
			// Der Stand der Unterhaltungen (readOnlyReason) ändert sich mit.
			listNotBefore = 0;
			return;
		}
		if (t.equals("settings")) {
			settingsLoaded = false;
		}
		// Unbekannte Ereignisse ignorieren (API.md §19).
	}

	private void messageToast(Chat.Message m, Chat.Conversation c, long now) {
		String sender = m.sender == null ? "?" : m.sender.name;
		String title = c != null && c.group ? I18n.tr("social.toast.inGroup", sender, c.title()) : sender;
		String text = m.preview();
		if (m.invite != null && m.invite.world != null) {
			// Weltkarte: die Einladung selbst kommt als hosting_invite (eigener Toast mit „Beitreten“).
			toasts.add(Toasts.Kind.MESSAGE, "conv:" + m.conversationId, title, I18n.tr("hosting.toast.card", m.invite.name),
					m.sender == null ? null : m.sender.uuid, sender, m.conversationId, null, now);
			return;
		}
		if (m.invite != null) {
			String label = m.invite.name != null ? m.invite.name : m.invite.address;
			toasts.add(Toasts.Kind.INVITE, "conv:" + m.conversationId, title, I18n.tr("social.toast.invite", label),
					m.sender == null ? null : m.sender.uuid, sender, m.conversationId, m.invite, now);
			return;
		}
		if (text == null || text.isEmpty()) {
			text = m.attachments.isEmpty() ? I18n.tr("social.preview.message")
					: I18n.tr(m.attachments.size() == 1 ? "social.preview.image" : "social.preview.images", m.attachments.size());
		}
		toasts.add(Toasts.Kind.MESSAGE, "conv:" + m.conversationId, title, text, m.sender == null ? null : m.sender.uuid,
				sender, m.conversationId, null, now);
	}

	// --- Laden ---

	private void loadList(long now) {
		final String t = token;
		listInFlight = true;
		final int gen = session;
		if (!submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					List<Chat.Conversation> all = new ArrayList<Chat.Conversation>();
					String cursor = null;
					boolean complete = true;
					for (int page = 0; page < 4; page++) {
						ChatApi.ConversationPage p = api.conversations(t, cursor);
						all.addAll(p.conversations);
						cursor = p.nextCursor;
						if (cursor == null) break;
						if (page == 3) complete = false;
					}
					final boolean full = complete;
					post(new Runnable() {
						@Override
						public void run() {
							if (gen != session) return;
							listInFlight = false;
							store.setConversations(all, full);
							lastUnreadPoll = System.currentTimeMillis();
						}
					});
				} catch (final ApiException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (gen != session) return;
							listInFlight = false;
							failed(e, t);
							listNotBefore = System.currentTimeMillis() + (e.rateLimited() ? Math.max(5000L, e.retryAfterMs()) : 15_000L);
						}
					});
				} catch (IOException | RuntimeException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (gen != session) return;
							listInFlight = false;
							listNotBefore = System.currentTimeMillis() + 15_000L;
						}
					});
				}
			}
		})) listInFlight = false;
	}

	/** Resync (API.md §19): Liste, Ungelesen, Moderation neu; offene Unterhaltung nachholen, andere als veraltet. */
	private void resync(long now) {
		store.markAllStale();
		loadList(now);
		moderationLoaded = false;
		if (viewing != null) catchUp(viewing);
		Friends f = backend.friends();
		if (f != null) f.refresh();
	}

	private void pollUnread(long now) {
		final String t = token;
		unreadInFlight = true;
		lastUnreadPoll = now;
		final int gen = session;
		if (!submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					final Map<String, ChatApi.UnreadState> states = api.unread(t);
					post(new Runnable() {
						@Override
						public void run() {
							unreadInFlight = false;
							if (gen == session) store.applyUnread(states);
						}
					});
				} catch (final ApiException e) {
					post(new Runnable() {
						@Override
						public void run() {
							unreadInFlight = false;
							if (gen == session && e.unauthorized()) backend.unauthorized(t);
						}
					});
				} catch (IOException | RuntimeException e) {
					post(new Runnable() {
						@Override
						public void run() {
							unreadInFlight = false;
						}
					});
				}
			}
		})) unreadInFlight = false;
	}

	private void fetchConversation(final String id) {
		final String t = token;
		final int gen = session;
		submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					final Chat.Conversation c = api.conversation(t, id);
					post(new Runnable() {
						@Override
						public void run() {
							if (gen == session) store.upsert(c);
						}
					});
				} catch (ApiException | IOException | RuntimeException ignored) {
					// kommt mit der nächsten Liste
				}
			}
		});
	}

	/** Neueste Seite einer Unterhaltung laden. */
	public void loadLatest(final String id) {
		final String t = token;
		if (t == null || !Chat.validConversationId(id)) return;
		final ChatStore.Thread th = store.thread(id);
		if (th.loading) return;
		th.loading = true;
		final int gen = session;
		if (!submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					final ChatApi.MessagePage p = api.messages(t, id, 0, -1, 50);
					post(new Runnable() {
						@Override
						public void run() {
							if (gen == session) store.loadedLatest(id, p);
						}
					});
				} catch (final ApiException e) {
					post(new Runnable() {
						@Override
						public void run() {
							th.loading = false;
							if (gen != session) return;
							if (e.status() == 404) store.remove(id);
							else failed(e, t);
						}
					});
				} catch (IOException | RuntimeException e) {
					post(new Runnable() {
						@Override
						public void run() {
							th.loading = false;
							if (gen == session) note("social.error.offline", null, true);
						}
					});
				}
			}
		})) th.loading = false;
	}

	/** Ältere Nachrichten (beim Hochscrollen). */
	public void loadOlder(final String id) {
		final String t = token;
		final ChatStore.Thread th = store.peek(id);
		if (t == null || th == null || !th.loaded || th.loadingOlder || !th.hasMore) return;
		final long before = th.oldestSeq();
		if (before <= 1) {
			th.hasMore = false;
			return;
		}
		th.loadingOlder = true;
		final int gen = session;
		if (!submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					final ChatApi.MessagePage p = api.messages(t, id, before, -1, 50);
					post(new Runnable() {
						@Override
						public void run() {
							if (gen == session) store.loadedOlder(id, p);
						}
					});
				} catch (ApiException | IOException | RuntimeException e) {
					post(new Runnable() {
						@Override
						public void run() {
							th.loadingOlder = false;
						}
					});
				}
			}
		})) th.loadingOlder = false;
	}

	/** Nachholen (after=): alles Neuere seit der letzten bekannten Nachricht, seitenweise. */
	private void catchUp(final String id) {
		final String t = token;
		final ChatStore.Thread th = store.peek(id);
		if (t == null || th == null || !th.loaded || th.loading) return;
		final long after = th.newestSeq();
		th.loading = true;
		final int gen = session;
		if (!submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					long from = after;
					for (int i = 0; i < 5; i++) {
						final ChatApi.MessagePage p = api.messages(t, id, 0, from, 100);
						post(new Runnable() {
							@Override
							public void run() {
								if (gen == session) store.loadedAfter(id, p);
							}
						});
						if (!p.hasMore || p.messages.isEmpty()) break;
						from = p.messages.get(p.messages.size() - 1).seq;
					}
				} catch (ApiException | IOException | RuntimeException e) {
					post(new Runnable() {
						@Override
						public void run() {
							th.loading = false;
						}
					});
				}
			}
		})) th.loading = false;
	}

	private void loadModeration() {
		final String t = token;
		moderationLoaded = true;
		final int gen = session;
		submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					final Chat.Moderation m = api.moderation(t);
					post(new Runnable() {
						@Override
						public void run() {
							if (gen == session) {
								moderation = m;
								generation++;
							}
						}
					});
				} catch (ApiException | IOException | RuntimeException ignored) {
					// ohne Angabe: nicht stumm; der Server lehnt Senden dann mit chat_muted ab
				}
			}
		});
	}

	private void loadSettings() {
		final String t = token;
		settingsLoaded = true;
		final int gen = session;
		submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					final ChatApi.ChatSettings s = api.settings(t);
					post(new Runnable() {
						@Override
						public void run() {
							if (gen == session) chatSettings = s;
						}
					});
				} catch (ApiException | IOException | RuntimeException ignored) {
					// Standard bleibt
				}
			}
		});
	}

	// --- Aktionen (Spiel-Thread) ---

	/**
	 * Nachricht senden: sofort als ausstehend zeigen, Bilder (≤ 10 Bildschirmfotos) hochladen, dann senden – mit
	 * nonce, damit ein Wiederholen keine Dopplung erzeugt.
	 */
	public boolean send(String conversationId, String text, String replyTo, List<Path> imageFiles, Chat.Invite invite) {
		if (token == null || !Chat.validConversationId(conversationId)) {
			note("social.error.offline", null, true);
			return false;
		}
		String clean = text == null ? "" : SafeText.message(text, SafeText.MAX_MESSAGE + 1);
		if (SafeText.length(clean) > SafeText.MAX_MESSAGE) {
			note("social.error.message_too_long", null, true);
			return false;
		}
		List<Path> files = imageFiles == null ? Collections.<Path>emptyList() : new ArrayList<Path>(imageFiles);
		if (files.size() > MAX_IMAGES) files = files.subList(0, MAX_IMAGES);
		if (clean.isEmpty() && files.isEmpty() && invite == null) return false;
		if (moderation.active(System.currentTimeMillis())) {
			note("social.error.chat_muted", null, true);
			return false;
		}
		String nonce = UUID.randomUUID().toString().replace("-", "");
		Chat.Reply reply = null;
		if (replyTo != null) {
			ChatStore.Thread th = store.peek(conversationId);
			Chat.Message target = th == null ? null : th.find(replyTo);
			if (target != null) {
				reply = new Chat.Reply(target.id, target.seq, target.sender, target.preview(), target.attachments.size(),
						target.invite != null, false);
			}
		}
		store.addPending(conversationId, nonce, new Chat.User(self, selfName == null ? "?" : selfName), clean, reply, invite,
				files.size(), System.currentTimeMillis());
		SendJob job = new SendJob(conversationId, nonce, clean, replyTo, files, invite);
		sendJobs.put(nonce, job);
		if (typingIn != null && typingIn.equals(conversationId)) typingIn = null;
		run(job);
		return true;
	}

	/** Fehlgeschlagene Nachricht erneut senden (gleiche nonce). */
	public void retry(Chat.Message local) {
		if (local == null || local.nonce == null) return;
		SendJob job = sendJobs.get(local.nonce);
		if (job == null) return;
		store.dropLocal(local.conversationId, local.id);
		store.addPending(job.conversationId, job.nonce, new Chat.User(self, selfName == null ? "?" : selfName), job.text,
				local.reply, job.invite, job.images.size(), System.currentTimeMillis());
		run(job);
	}

	/** Fehlgeschlagene Nachricht verwerfen. */
	public void discard(Chat.Message local) {
		if (local == null) return;
		if (local.nonce != null) {
			sendJobs.remove(local.nonce);
			uploadProgress.remove(local.nonce);
		}
		store.dropLocal(local.conversationId, local.id);
	}

	private void run(final SendJob job) {
		final String t = token;
		final int gen = session;
		if (!job.images.isEmpty()) uploadProgress.put(job.nonce, new Upload(0, job.images.size()));
		if (!submit(uploads, new Runnable() {
			@Override
			public void run() {
				try {
					List<String> ids = new ArrayList<String>();
					int i = 0;
					for (Path p : job.images) {
						ChatImages.Upload u;
						try {
							if (Files.size(p) > 64L * 1024 * 1024) throw new IOException("zu groß");
							u = ChatImages.prepare(Files.readAllBytes(p));
						} catch (IOException | RuntimeException | OutOfMemoryError e) {
							throw new ApiException(0, "image_unreadable", 0);
						}
						ids.add(api.upload(t, u.bytes, u.mime).id);
						uploadProgress.put(job.nonce, new Upload(++i, job.images.size()));
					}
					final Chat.Message m = api.send(t, job.conversationId, job.text, job.replyTo, ids, job.invite, job.nonce);
					post(new Runnable() {
						@Override
						public void run() {
							if (gen != session) return;
							sendJobs.remove(job.nonce);
							uploadProgress.remove(job.nonce);
							store.message(m, 0);
						}
					});
				} catch (final ApiException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (gen != session) return;
							uploadProgress.remove(job.nonce);
							if (e.unauthorized()) backend.unauthorized(t);
							if ("chat_muted".equals(e.code())) moderationLoaded = false;
							String key = errorKey(e);
							store.pendingFailed(job.conversationId, job.nonce, key);
							note(key, null, true);
						}
					});
				} catch (IOException | RuntimeException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (gen != session) return;
							uploadProgress.remove(job.nonce);
							store.pendingFailed(job.conversationId, job.nonce, "social.error.offline");
						}
					});
				}
			}
		})) {
			store.pendingFailed(job.conversationId, job.nonce, "social.error.busy");
		}
	}

	/** Eigene Nachricht bearbeiten. */
	public void edit(final Chat.Message m, final String text) {
		if (m == null || !m.from(self) || m.deleted) return;
		final String clean = SafeText.message(text == null ? "" : text, SafeText.MAX_MESSAGE + 1);
		if (SafeText.length(clean) > SafeText.MAX_MESSAGE) {
			note("social.error.message_too_long", null, true);
			return;
		}
		action(new Job<Chat.Message>() {
			@Override
			public Chat.Message run(String t) throws IOException, ApiException {
				return api.edit(t, m.conversationId, m.id, clean);
			}
		}, new Done<Chat.Message>() {
			@Override
			public void done(Chat.Message value, String error) {
				if (value != null) store.message(value, 0);
			}
		}, "social.msg.edited");
	}

	/** Nachricht löschen (eigene; Gruppenbesitzer auch fremde). */
	public void delete(final Chat.Message m) {
		if (m == null || m.deleted) return;
		action(new Job<Chat.Message>() {
			@Override
			public Chat.Message run(String t) throws IOException, ApiException {
				return api.delete(t, m.conversationId, m.id);
			}
		}, new Done<Chat.Message>() {
			@Override
			public void done(Chat.Message value, String error) {
				if (value != null) store.message(value, 0);
			}
		}, "social.msg.deleted");
	}

	/** Reaktion umschalten. */
	public void react(final Chat.Message m, final String emoji) {
		if (m == null || !Chat.validReaction(emoji) || !m.hasContent()) return;
		boolean mine = false;
		for (Chat.Reaction r : m.reactions) if (r.emoji.equals(emoji) && r.by(self)) mine = true;
		final boolean on = !mine;
		// Sofort zeigen, Antwort des Servers ersetzt es.
		store.reactions(m.conversationId, m.id, toggled(m.reactions, emoji, self, on));
		action(new Job<List<Chat.Reaction>>() {
			@Override
			public List<Chat.Reaction> run(String t) throws IOException, ApiException {
				return api.react(t, m.id, emoji, on);
			}
		}, new Done<List<Chat.Reaction>>() {
			@Override
			public void done(List<Chat.Reaction> value, String error) {
				store.reactions(m.conversationId, m.id, value != null ? value : m.reactions);
			}
		}, null);
	}

	static List<Chat.Reaction> toggled(List<Chat.Reaction> in, String emoji, String self, boolean on) {
		List<Chat.Reaction> out = new ArrayList<Chat.Reaction>();
		boolean found = false;
		for (String id : Chat.REACTIONS) {
			Chat.Reaction r = null;
			for (Chat.Reaction x : in) if (x.emoji.equals(id)) r = x;
			if (id.equals(emoji)) {
				found = true;
				List<String> users = r == null ? new ArrayList<String>() : new ArrayList<String>(r.users);
				int count = r == null ? 0 : r.count;
				if (on && !users.contains(self)) {
					users.add(self);
					count++;
				} else if (!on && users.remove(self)) {
					count--;
				}
				if (count > 0) out.add(new Chat.Reaction(id, count, users));
			} else if (r != null) {
				out.add(r);
			}
		}
		if (!found && on) out.add(new Chat.Reaction(emoji, 1, Collections.singletonList(self)));
		return out;
	}

	private void markRead(final String id, final long seq) {
		final String t = token;
		final int gen = session;
		submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					final Chat.Conversation c = api.read(t, id, seq);
					post(new Runnable() {
						@Override
						public void run() {
							if (gen == session) store.upsert(c);
						}
					});
				} catch (ApiException | IOException | RuntimeException ignored) {
					// nächster Versuch beim nächsten Lesen
				}
			}
		});
	}

	/** Als ungelesen markieren (ab dieser Nachricht, oder ganze Unterhaltung mit {@code m} = null). */
	public void markUnread(final String conversationId, final Chat.Message m) {
		suppressAutoRead = conversationId;
		action(new Job<Chat.Conversation>() {
			@Override
			public Chat.Conversation run(String t) throws IOException, ApiException {
				return api.markUnread(t, conversationId, m == null ? 0 : m.seq);
			}
		}, new Done<Chat.Conversation>() {
			@Override
			public void done(Chat.Conversation value, String error) {
				if (value != null) store.upsert(value);
			}
		}, "social.msg.markedUnread");
	}

	/** Benachrichtigungen einer Unterhaltung stumm/an. */
	public void mute(final String conversationId, final boolean muted) {
		action(new Job<Chat.Conversation>() {
			@Override
			public Chat.Conversation run(String t) throws IOException, ApiException {
				return api.mute(t, conversationId, muted, 0);
			}
		}, new Done<Chat.Conversation>() {
			@Override
			public void done(Chat.Conversation value, String error) {
				if (value != null) store.upsert(value);
			}
		}, muted ? "social.msg.muted" : "social.msg.unmuted");
	}

	/** Tippt gerade (höchstens alle 3 s melden) bzw. nicht mehr. Nur wenn Tippt-Anzeige an. */
	public void typing(final String conversationId, final boolean active) {
		if (token == null || !chatSettings.typing || conversationId == null) return;
		long now = System.currentTimeMillis();
		if (active) {
			if (conversationId.equals(typingIn) && now - typingSentAt < TYPING_EVERY_MS) return;
			typingIn = conversationId;
			typingSentAt = now;
		} else {
			if (!conversationId.equals(typingIn)) return;
			typingIn = null;
		}
		final String t = token;
		submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					api.typing(t, conversationId, active);
				} catch (ApiException | IOException | RuntimeException ignored) {
					// Tippen ist nur ein Hinweis
				}
			}
		});
	}

	/** DM mit einem Freund öffnen; {@code done} bekommt die Unterhaltung. */
	public void openDm(final String uuid, final Done<Chat.Conversation> done) {
		Chat.Conversation existing = store.dmWith(Uuids.normalize(uuid));
		if (existing != null) {
			done.done(existing, null);
			return;
		}
		action(new Job<Chat.Conversation>() {
			@Override
			public Chat.Conversation run(String t) throws IOException, ApiException {
				return api.openDm(t, uuid);
			}
		}, new Done<Chat.Conversation>() {
			@Override
			public void done(Chat.Conversation value, String error) {
				if (value != null) store.upsert(value);
				done.done(value, error);
			}
		}, null);
	}

	public void createGroup(final String name, final List<String> members, final Done<Chat.Conversation> done) {
		final String clean = SafeText.line(name == null ? "" : name, SafeText.MAX_NAME);
		if (clean.isEmpty()) {
			note("social.error.invalid_name", null, true);
			return;
		}
		action(new Job<Chat.Conversation>() {
			@Override
			public Chat.Conversation run(String t) throws IOException, ApiException {
				return api.createGroup(t, clean, members);
			}
		}, new Done<Chat.Conversation>() {
			@Override
			public void done(Chat.Conversation value, String error) {
				if (value != null) store.upsert(value);
				done.done(value, error);
			}
		}, "social.msg.groupCreated");
	}

	public void renameGroup(final String id, final String name) {
		final String clean = SafeText.line(name == null ? "" : name, SafeText.MAX_NAME);
		if (clean.isEmpty()) {
			note("social.error.invalid_name", null, true);
			return;
		}
		conversationAction(new Job<Chat.Conversation>() {
			@Override
			public Chat.Conversation run(String t) throws IOException, ApiException {
				return api.renameGroup(t, id, clean);
			}
		}, "social.msg.renamed");
	}

	public void addMembers(final String id, final List<String> members) {
		conversationAction(new Job<Chat.Conversation>() {
			@Override
			public Chat.Conversation run(String t) throws IOException, ApiException {
				return api.addMembers(t, id, members);
			}
		}, "social.msg.membersAdded");
	}

	public void transferOwner(final String id, final String member) {
		conversationAction(new Job<Chat.Conversation>() {
			@Override
			public Chat.Conversation run(String t) throws IOException, ApiException {
				return api.transferOwner(t, id, member);
			}
		}, "social.msg.ownerChanged");
	}

	public void removeMember(final String id, final String member) {
		action(new Job<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.removeMember(t, id, member);
				return Boolean.TRUE;
			}
		}, new Done<Boolean>() {
			@Override
			public void done(Boolean value, String error) {
				if (value != null) fetchConversation(id);
			}
		}, "social.msg.memberRemoved");
	}

	public void leaveGroup(final String id) {
		action(new Job<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.leaveGroup(t, id);
				return Boolean.TRUE;
			}
		}, new Done<Boolean>() {
			@Override
			public void done(Boolean value, String error) {
				if (value != null) {
					store.remove(id);
					if (id.equals(viewing)) viewing = null;
				}
			}
		}, "social.msg.left");
	}

	public void deleteGroup(final String id) {
		action(new Job<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.deleteGroup(t, id);
				return Boolean.TRUE;
			}
		}, new Done<Boolean>() {
			@Override
			public void done(Boolean value, String error) {
				if (value != null) {
					store.remove(id);
					if (id.equals(viewing)) viewing = null;
				}
			}
		}, "social.msg.groupDeleted");
	}

	/**
	 * Melden (API.md §20.1): {@code kind} message|image|player|group, Grund aus der festen Liste, optionaler Text.
	 * {@code done} bekommt true bei Erfolg.
	 */
	public void report(final String kind, final String reason, final String note, final String target,
			final String conversationId, final Done<Boolean> done) {
		action(new Job<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.report(t, kind, reason, note, target, conversationId);
				return Boolean.TRUE;
			}
		}, new Done<Boolean>() {
			@Override
			public void done(Boolean value, String error) {
				if (value != null) {
					toasts.add(Toasts.Kind.REPORT, null, I18n.tr("social.toast.reportTitle"), I18n.tr("social.toast.reportSent"),
							null, null, null, null, System.currentTimeMillis());
				}
				if (done != null) done.done(value, error);
			}
		}, "social.msg.reported");
	}

	/** Lesebestätigungen / Tippt-Anzeige ändern (gegenseitig, API.md §3.1). */
	public void updateChatSettings(final Boolean readReceipts, final Boolean typingIndicator) {
		action(new Job<ChatApi.ChatSettings>() {
			@Override
			public ChatApi.ChatSettings run(String t) throws IOException, ApiException {
				return api.updateSettings(t, readReceipts, typingIndicator);
			}
		}, new Done<ChatApi.ChatSettings>() {
			@Override
			public void done(ChatApi.ChatSettings value, String error) {
				if (value != null) chatSettings = value;
			}
		}, null);
	}

	// --- Servereinladungen ---

	/** Status eines Servers (gecacht 60 s) oder null (lädt). */
	public Chat.ServerStatus serverStatus(final String address, long now) {
		final String a = SafeText.serverAddress(address);
		if (a == null || token == null) return null;
		StatusEntry e = statuses.get(a);
		if (e == null) {
			if (statuses.size() > 64) statuses.clear();
			e = new StatusEntry();
			statuses.put(a, e);
		}
		if (!e.loading && (e.status == null || now - e.fetchedAt > STATUS_TTL_MS) && now - e.fetchedAt > 5_000L) {
			e.loading = true;
			e.fetchedAt = now;
			final StatusEntry entry = e;
			final String t = token;
			if (!submit(rest, new Runnable() {
				@Override
				public void run() {
					Chat.ServerStatus s;
					try {
						s = api.serverStatus(t, a);
					} catch (ApiException ex) {
						s = new Chat.ServerStatus(a, false, ex.rateLimited() ? "busy" : "unavailable", 0, 0, null, null, null);
					} catch (IOException | RuntimeException ex) {
						s = new Chat.ServerStatus(a, false, "unavailable", 0, 0, null, null, null);
					}
					final Chat.ServerStatus result = s;
					post(new Runnable() {
						@Override
						public void run() {
							entry.status = result;
							entry.loading = false;
							entry.fetchedAt = System.currentTimeMillis();
						}
					});
				}
			})) e.loading = false;
		}
		return e.status;
	}

	/** Server-Symbol als Textur (oder null). */
	public TextureRef serverIcon(final Chat.ServerStatus status) {
		if (status == null || status.icon == null) return null;
		final byte[] png = status.icon;
		return images.get("icon:" + status.address + ":" + Arrays.hashCode(png), new Callable<byte[]>() {
			@Override
			public byte[] call() {
				return png;
			}
		}, 64);
	}

	/** Anhang als Textur (Vorschau ≤ 400 px bzw. voll ≤ 1600 px) oder null (lädt / Fehler). */
	public TextureRef attachment(final Chat.Attachment a, final boolean full) {
		if (a == null || token == null) return null;
		final String t = token;
		return images.get((full ? "f:" : "t:") + a.id, new Callable<byte[]>() {
			@Override
			public byte[] call() throws Exception {
				return api.attachment(t, a.id, !full);
			}
		}, full ? 1600 : 400);
	}

	/** Lokales Bild (Auswahl aus den Bildschirmfotos) – liest die Datei im Bild-Thread. */
	public TextureRef localImage(final Path file, int maxSide) {
		if (file == null) return null;
		return images.get("p:" + file.toString(), new Callable<byte[]>() {
			@Override
			public byte[] call() throws Exception {
				if (Files.size(file) > 64L * 1024 * 1024) throw new IOException("zu groß");
				return Files.readAllBytes(file);
			}
		}, maxSide);
	}

	// --- Hilfen ---

	/** Eine Aktion im REST-Thread; Ergebnis (bzw. Fehler als Meldung) im Spiel-Thread. */
	interface Job<T> {
		T run(String token) throws IOException, ApiException;
	}

	private <T> void action(final Job<T> job, final Done<T> done, final String successKey) {
		final String t = token;
		if (t == null) {
			note("social.error.offline", null, true);
			if (done != null) done.done(null, "social.error.offline");
			return;
		}
		final int gen = session;
		if (!submit(rest, new Runnable() {
			@Override
			public void run() {
				try {
					final T value = job.run(t);
					post(new Runnable() {
						@Override
						public void run() {
							if (gen != session) return;
							if (successKey != null) note(successKey, null, false);
							if (done != null) done.done(value, null);
						}
					});
				} catch (final ApiException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (gen != session) return;
							String key = failed(e, t);
							if (done != null) done.done(null, key);
						}
					});
				} catch (IOException | RuntimeException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (gen != session) return;
							note("social.error.offline", null, true);
							if (done != null) done.done(null, "social.error.offline");
						}
					});
				}
			}
		})) {
			note("social.error.busy", null, true);
			if (done != null) done.done(null, "social.error.busy");
		}
	}

	private void conversationAction(Job<Chat.Conversation> job, String successKey) {
		action(job, new Done<Chat.Conversation>() {
			@Override
			public void done(Chat.Conversation value, String error) {
				if (value != null) store.upsert(value);
			}
		}, successKey);
	}

	/** Fehler behandeln (401 → neu anmelden) und als Meldung setzen; Rückgabe: Schlüssel. */
	private String failed(ApiException e, String t) {
		if (e.unauthorized()) backend.unauthorized(t);
		if ("chat_muted".equals(e.code())) moderationLoaded = false;
		String key = errorKey(e);
		note(key, null, true);
		return key;
	}

	static String errorKey(ApiException e) {
		if (e.status() == 429) return "social.error.rate_limited";
		if (KNOWN_ERRORS.contains(e.code())) return "social.error." + e.code();
		return "social.error.generic";
	}

	private void note(String key, Object[] args, boolean error) {
		notice = new Notice(key, args, error, System.currentTimeMillis());
		generation++;
	}

	private void post(Runnable r) {
		results.add(r);
	}

	private static boolean submit(Executor ex, Runnable task) {
		try {
			ex.execute(task);
			return true;
		} catch (RejectedExecutionException e) {
			return false;
		}
	}

	/** Für Tests: Ereignis direkt einspielen. */
	void applyForTest(MeEvent e, long now, boolean screen) {
		apply(e, now, screen);
	}

	boolean streamWanted() {
		return streamWanted;
	}
}
