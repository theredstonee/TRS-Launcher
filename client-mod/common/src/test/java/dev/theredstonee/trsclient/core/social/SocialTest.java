package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.SseParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.theredstonee.trsclient.core.social.SocialFakes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialTest {
	static final String BASE = "http://127.0.0.1:1";

	@BeforeAll
	static void english() {
		I18n.use("en");
	}

	// --- Säubern ---

	@Test
	void safeTextStripsFormattingControlAndBidi() {
		assertEquals("red bold", SafeText.message("§cred §lbold", 100));
		assertEquals("ab", SafeText.message("a‮b", 100));
		assertEquals("ab", SafeText.message("a\u0007b​", 100));
		assertEquals("line1\n\nline2", SafeText.message("line1\n\n\n\nline2\n\n", 100));
		assertEquals("abc", SafeText.message("abcdef", 3));
		// Emoji mit Verbinder bleibt (ZWJ), Tab wird Leerzeichen.
		assertEquals("a b", SafeText.message("a\tb", 10));
		assertTrue(SafeText.message("👨‍👩", 10).contains("‍"));
		assertEquals("one line", SafeText.line("one\nline", 20));
		assertNull(SafeText.playerName("Bob§c"));
		assertEquals("Bob_1", SafeText.playerName("Bob_1"));
	}

	@Test
	void linksAndServerAddressesAreStrict() {
		List<int[]> links = SafeText.links("see https://example.net/a?b=1. and http://x.org!");
		assertEquals(2, links.size());
		assertEquals("https://example.net/a?b=1", "see https://example.net/a?b=1. and".substring(links.get(0)[0], links.get(0)[1]));
		assertNull(SafeText.safeLink("javascript:alert(1)"));
		assertNull(SafeText.safeLink("file:///etc/passwd"));
		assertNotNull(SafeText.safeLink("https://trs-launcher.theredstonee.de/x"));
		assertEquals("play.example.net:25566", SafeText.serverAddress(" Play.Example.NET:25566 "));
		assertNull(SafeText.serverAddress("http://evil"));
		assertNull(SafeText.serverAddress("a b"));
	}

	@Test
	void messagesFromOthersAreSanitisedAndHiddenOnesHaveNoContent() {
		ChatJson.MessageDto d = ChatJson.GSON.fromJson(msg("m00000000000000000001", DM, 1, BOB, "§4Hallo §kWelt"),
				ChatJson.MessageDto.class);
		Chat.Message m = ChatJson.message(d, DM);
		assertEquals("Hallo Welt", m.text);
		assertEquals("Bob", m.sender.name);
		String hidden = msg("m00000000000000000002", DM, 2, BOB, "secret").replace("\"hidden\":false", "\"hidden\":true");
		Chat.Message h = ChatJson.message(ChatJson.GSON.fromJson(hidden, ChatJson.MessageDto.class), DM);
		assertTrue(h.hidden);
		assertNull(h.text);
		// Ungültige IDs fallen weg.
		assertNull(ChatJson.message(ChatJson.GSON.fromJson(msg("x", DM, 1, BOB, "a"), ChatJson.MessageDto.class), DM));
		// Server-Pfad wird nie übernommen – nur aus der ID gebaut.
		String withImage = msg("m00000000000000000003", DM, 3, BOB, "").replace("\"attachments\":[]",
				"\"attachments\":[{\"id\":\"a0123456789abcdef01234567\",\"mime\":\"image/jpeg\",\"width\":1920,\"height\":1080,"
						+ "\"bytes\":1,\"path\":\"https://evil/x\",\"thumb\":{\"width\":400,\"height\":225}}]");
		Chat.Message img = ChatJson.message(ChatJson.GSON.fromJson(withImage, ChatJson.MessageDto.class), DM);
		assertEquals("/v1/chat/attachments/a0123456789abcdef01234567?thumb=1", img.attachments.get(0).path(true));
	}

	@Test
	void sseParserKeepsEventIds() {
		SseParser p = new SseParser();
		assertNull(p.line("id: mfz2k1a3b4c.1842"));
		assertNull(p.line("event: chat_message"));
		assertNull(p.line("data: {}"));
		SseParser.Raw raw = p.line("");
		assertEquals("mfz2k1a3b4c.1842", raw.id);
		// Gefährliche IDs werden nicht übernommen (sie landen in der Adresse).
		p.line("id: a&b=c");
		p.line("event: x");
		assertNull(p.line("").id);
	}

	// --- Speicher ---

	private static Chat.Message m(String id, long seq, String sender, String text) {
		return ChatJson.message(ChatJson.GSON.fromJson(msg(id, DM, seq, sender, text), ChatJson.MessageDto.class), DM);
	}

	private static ChatStore storeWithDm() {
		ChatStore s = new ChatStore();
		s.setConversations(Arrays.asList(ChatJson.parseConversation(dm(0, 0, null))), true);
		s.loadedLatest(DM, new ChatApi.MessagePage(new ArrayList<Chat.Message>(), false));
		return s;
	}

	@Test
	void messagesAreOrderedBySeqWithoutDuplicates() {
		ChatStore s = storeWithDm();
		s.message(m("m00000000000000000003", 3, BOB, "c"), 1);
		s.message(m("m00000000000000000001", 1, BOB, "a"), 1);
		s.message(m("m00000000000000000002", 2, BOB, "b"), 1);
		s.message(m("m00000000000000000002", 2, BOB, "b"), 1);
		List<Chat.Message> list = s.peek(DM).messages();
		assertEquals(3, list.size());
		assertEquals(1, list.get(0).seq);
		assertEquals(3, list.get(2).seq);
		assertEquals(3, s.get(DM).unread, "Dopplung zählt nicht");
		assertEquals(3, s.get(DM).lastMessage.seq);
	}

	@Test
	void pendingMessageIsReplacedByTheConfirmedOneWithTheSameNonce() {
		ChatStore s = storeWithDm();
		s.message(m("m00000000000000000001", 1, BOB, "hi"), 0);
		s.addPending(DM, "nonce1234", new Chat.User(SELF, "Theredstonee"), "Hallo", null, null, 0, 1000);
		assertTrue(s.peek(DM).messages().get(1).pending);
		String json = msg("m00000000000000000002", DM, 2, SELF, "Hallo", "nonce1234", "2026-09-26T10:00:02.000Z");
		Chat.Message confirmed = ChatJson.message(ChatJson.GSON.fromJson(json, ChatJson.MessageDto.class), DM);
		s.message(confirmed, 0);
		// Zweimal (Antwort + Stream-Echo) → trotzdem nur einmal.
		s.message(confirmed, 0);
		List<Chat.Message> list = s.peek(DM).messages();
		assertEquals(2, list.size());
		assertFalse(list.get(1).pending);
		assertEquals("Hallo", list.get(1).text);
	}

	@Test
	void editsDeletesAndReactionsReplaceTheMessage() {
		ChatStore s = storeWithDm();
		s.message(m("m00000000000000000001", 1, BOB, "hi"), 0);
		String edited = msg("m00000000000000000001", DM, 1, BOB, "hi there").replace("\"editedAt\":null",
				"\"editedAt\":\"2026-09-26T10:05:00.000Z\"");
		s.message(ChatJson.message(ChatJson.GSON.fromJson(edited, ChatJson.MessageDto.class), DM), 0);
		Chat.Message e = s.peek(DM).messages().get(0);
		assertEquals("hi there", e.text);
		assertTrue(e.editedAt > 0);
		List<Chat.Reaction> r = new ArrayList<>();
		r.add(new Chat.Reaction("fire", 2, Arrays.asList(BOB, SELF)));
		s.reactions(DM, e.id, r);
		assertTrue(s.peek(DM).messages().get(0).reactions.get(0).by(SELF));
		String deleted = msg("m00000000000000000001", DM, 1, BOB, null).replace("\"deleted\":false", "\"deleted\":true")
				.replace("\"deletedBy\":null", "\"deletedBy\":\"sender\"");
		s.message(ChatJson.message(ChatJson.GSON.fromJson(deleted, ChatJson.MessageDto.class), DM), 0);
		Chat.Message d = s.peek(DM).messages().get(0);
		assertTrue(d.deleted);
		assertNull(d.text);
		assertTrue(d.reactions.isEmpty());
		assertEquals(1, s.peek(DM).messages().size());
	}

	@Test
	void toggledReactionsCountOwnOnce() {
		List<Chat.Reaction> none = new ArrayList<>();
		List<Chat.Reaction> on = Social.toggled(none, "heart", SELF, true);
		assertEquals(1, on.get(0).count);
		List<Chat.Reaction> twice = Social.toggled(on, "heart", SELF, true);
		assertEquals(1, twice.get(0).count);
		assertTrue(Social.toggled(on, "heart", SELF, false).isEmpty());
	}

	@Test
	void typingExpiresWithoutEventAndStateUpdatesUnread() {
		ChatStore s = storeWithDm();
		s.typing(DM, BOB, true, 1000);
		assertEquals(Arrays.asList(BOB), s.typers(DM, 500));
		assertTrue(s.typers(DM, 1001).isEmpty());
		s.state(DM, 4, false, 1, false, 0);
		assertEquals(4, s.unreadTotal());
		s.state(DM, 0, true, 1, false, 0);
		assertEquals(1, s.unreadTotal(), "Markierung ohne Nachrichten zählt 1");
		s.state(DM, 5, false, 1, true, 0);
		assertEquals(0, s.unreadTotal(), "stumm zählt nicht");
		s.read(DM, BOB, 7, 1, false);
		assertTrue(s.get(DM).seenBy(7));
	}

	// --- Toasts ---

	@Test
	void toastsQueueMergeAndRespectSettings() {
		Toasts t = new Toasts();
		long now = 1_000_000L;
		for (int i = 0; i < 5; i++) {
			assertTrue(t.add(Toasts.Kind.MESSAGE, "conv:c" + i, "P" + i, "hi", null, null, "c" + i, null, now));
		}
		assertEquals(Toasts.MAX_VISIBLE, t.visible(now).size());
		assertEquals(2, t.queued());
		// Gleiche Unterhaltung: zusammengefasst.
		t.add(Toasts.Kind.MESSAGE, "conv:c0", "P0", "second", null, null, "c0", null, now + 10);
		boolean merged = false;
		for (Toasts.Toast x : t.visible(now + 10)) if (x.conversationId.equals("c0") && x.count == 2) merged = true;
		assertTrue(merged);
		// Nach Ablauf rücken die Wartenden nach.
		assertEquals(2, t.visible(now + 6000).size());
		assertTrue(t.visible(now + 20_000).isEmpty());
		// Nicht stören: nichts – außer Moderation.
		t.settings().dnd = true;
		assertFalse(t.add(Toasts.Kind.MESSAGE, "conv:x", "X", "t", null, null, "x", null, now));
		assertTrue(t.add(Toasts.Kind.MODERATION, "m", "Muted", "t", null, null, null, null, now));
		t.settings().dnd = false;
		// Art abgeschaltet.
		t.settings().online = false;
		assertFalse(t.add(Toasts.Kind.ONLINE, "online:a", "A", "online", "a", "A", null, null, now));
		t.settings().online = true;
		assertTrue(t.add(Toasts.Kind.ONLINE, "online:a", "A", "online", "a", "A", null, null, now));
		assertFalse(t.add(Toasts.Kind.ONLINE, "online:a2", "A", "online", "a", "A", null, null, now + 1000), "Abklingzeit");
		// Gerade gelesene Unterhaltung: kein Toast.
		t.viewing("open");
		assertFalse(t.add(Toasts.Kind.MESSAGE, "conv:open", "B", "t", null, null, "open", null, now));
		// Vollbild-Automatik.
		t.viewing(null);
		t.settings().dndFullscreen = true;
		t.fullscreen(true);
		assertTrue(t.quiet());
	}

	@Test
	void quickTargetIsTheNewestActionableToast() {
		Toasts t = new Toasts();
		long now = 5_000_000L;
		t.add(Toasts.Kind.MESSAGE, "conv:a", "A", "hi", null, null, "a", null, now);
		t.add(Toasts.Kind.ONLINE, "online:b", "B", "online", "b", "B", null, null, now);
		Toasts.Toast q = t.quickTarget(now + 500);
		assertEquals(Toasts.Kind.MESSAGE, q.kind);
		t.add(Toasts.Kind.INVITE, "conv:c", "C", "join", null, null, "c", new Chat.Invite("play.x.net", null), now + 600);
		assertEquals(Toasts.Kind.INVITE, t.quickTarget(now + 800).kind);
		t.dismiss(t.quickTarget(now + 800).id);
		assertEquals(Toasts.Kind.MESSAGE, t.quickTarget(now + 800).kind);
		assertNull(t.quickTarget(now + 60_000));
	}

	// --- Stream: Wiederaufnahme ---

	@Test
	void streamResumesWithLastEventIdAndBacksOff() throws Exception {
		Opener opener = new Opener();
		Conn first = opener.prepare(200);
		MeStream stream = new MeStream(opener, BASE);
		long now = 1000;
		stream.update(now, "trs_a", true);
		first.event("e.1", "hello", "{\"type\":\"hello\",\"resumed\":false}");
		first.event("e.2", "chat_typing", "{\"conversationId\":\"" + DM + "\",\"uuid\":\"" + BOB + "\",\"typing\":true}");
		List<MeEvent> got = new ArrayList<>();
		waitFor(() -> {
			stream.drain(got);
			return got.size() >= 2;
		});
		assertEquals("e.2", stream.lastEventId());
		assertTrue(stream.connected());
		first.end();
		waitFor(() -> !stream.connected());
		stream.update(now + 10, "trs_a", true);
		assertEquals(1, opener.urls.size(), "Backoff: nicht sofort neu verbinden");
		Conn second = opener.prepare(200);
		stream.update(now + 1200, "trs_a", true);
		waitFor(() -> opener.urls.size() == 2);
		assertTrue(opener.urls.get(1).endsWith("/v1/events/me?lastEventId=e.2"), opener.urls.get(1));
		second.end();
	}

	@Test
	void streamReportsUnauthorizedOnce() throws Exception {
		Opener opener = new Opener();
		opener.prepare(401).end();
		MeStream stream = new MeStream(opener, BASE);
		stream.update(0, "trs_a", true);
		waitFor(() -> opener.opened.size() == 1);
		Thread.sleep(50);
		stream.update(10, "trs_a", true);
		assertTrue(stream.takeUnauthorized());
		assertFalse(stream.takeUnauthorized());
	}

	// --- Social: Ereignisse, Resync, Senden, Schnellantwort ---

	private static final String M1 = "m00000000000000000001";
	private static final String M2 = "m00000000000000000002";
	private static final String M3 = "m00000000000000000003";

	private static FakeHttp api() {
		FakeHttp http = new FakeHttp();
		http.json("GET /v1/chat/conversations", 200, "{\"conversations\":[" + dm(1, 2, msg(M2, DM, 2, BOB, "zwei")) + ","
				+ group(false) + "],\"nextCursor\":null}");
		http.json("GET /v1/me/moderation", 200, "{\"mute\":null,\"warnings\":[]}");
		http.json("GET /v1/me", 200, "{\"uuid\":\"" + SELF + "\",\"settings\":{\"chatReadReceipts\":true,\"chatTypingIndicator\":true}}");
		http.json("GET /v1/chat/conversations/" + DM + "/messages", 200, "{\"messages\":[" + msg(M1, DM, 1, SELF, "eins") + ","
				+ msg(M2, DM, 2, BOB, "zwei") + "],\"hasMore\":false}");
		http.json("POST /v1/chat/conversations/" + DM + "/read", 200, "{\"conversation\":" + dm(0, 2, null) + "}");
		http.on("POST /v1/chat/conversations/" + DM + "/messages", r -> {
			String body = FakeHttp.body(r);
			String nonce = body.replaceAll(".*\"nonce\":\"([A-Za-z0-9_-]+)\".*", "$1");
			return FakeHttp.response(201, "{\"message\":" + msg(M3, DM, 3, SELF, "Bin gleich da", nonce,
					"2026-09-26T10:00:03.000Z") + "}");
		});
		return http;
	}

	@Test
	void eventsUpdateStoreAndShowToastsResyncReloads() throws Exception {
		FakeHttp http = api();
		Opener opener = new Opener();
		Conn conn = opener.prepare(200);
		Backend backend = new Backend();
		Social social = new Social(new ChatApi(http, BASE), new MeStream(opener, BASE), backend, DIRECT, DIRECT, DIRECT);
		long now = System.currentTimeMillis();
		social.tick(now, "trs_token", SELF, "Theredstonee", true);
		social.tick(now + 50, "trs_token", SELF, "Theredstonee", true);
		assertTrue(social.store().listLoaded());
		assertEquals(2, social.store().sorted().size());
		assertEquals(1, social.store().unreadTotal());

		conn.event("e.1", "hello", "{\"type\":\"hello\",\"resumed\":false}");
		conn.event("e.2", "chat_message", "{\"type\":\"chat_message\",\"conversationId\":\"" + DM + "\",\"message\":"
				+ msg(M3, DM, 3, BOB, "Kommst du? §cjetzt") + "}");
		waitFor(() -> {
			social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", true);
			return social.store().unreadTotal() == 2;
		});
		List<Toasts.Toast> toasts = social.toasts().visible(System.currentTimeMillis());
		assertEquals(1, toasts.size());
		assertEquals("Bob", toasts.get(0).title);
		assertEquals("Kommst du? jetzt", toasts.get(0).text);

		// Eigene Nachricht von einem anderen Gerät: kein Toast, kein Ungelesen.
		conn.event("e.3", "chat_message", "{\"conversationId\":\"" + DM + "\",\"message\":" + msg("m00000000000000000004", DM, 4,
				SELF, "von woanders") + "}");
		// Tippen, Lesen, Reaktion.
		conn.event(null, "chat_typing", "{\"conversationId\":\"" + DM + "\",\"uuid\":\"" + BOB + "\",\"typing\":true,\"expiresInMs\":8000}");
		conn.event("e.4", "chat_read", "{\"conversationId\":\"" + DM + "\",\"uuid\":\"" + BOB + "\",\"seq\":4,\"at\":\"2026-09-26T10:01:00.000Z\"}");
		waitFor(() -> {
			social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", true);
			return !social.store().typers(DM, System.currentTimeMillis()).isEmpty() && social.store().get(DM).seenBy(4);
		});
		assertEquals(2, social.store().unreadTotal());
		assertEquals(1, social.toasts().visible(System.currentTimeMillis()).size());

		// Resync: Liste neu und offene Unterhaltung nachholen (after=).
		social.screenOpen(DM, System.currentTimeMillis());
		social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", true);
		long lists = http.count("GET /v1/chat/conversations?");
		conn.event(null, "resync", "{\"type\":\"resync\",\"reason\":\"gap\"}");
		waitFor(() -> {
			social.screenOpen(DM, System.currentTimeMillis());
			social.tick(System.currentTimeMillis(), "trs_token", SELF, "Theredstonee", true);
			return http.count("GET /v1/chat/conversations?") > lists;
		});
		assertTrue(http.calls().stream().anyMatch(c -> c.contains("/messages?limit=100&after=")), http.calls().toString());
		// Offene Unterhaltung wird gelesen.
		assertTrue(http.count("POST /v1/chat/conversations/" + DM + "/read") >= 1);
		conn.end();
	}

	@Test
	void quickReplySendsWithNonceAndReplacesPending() {
		FakeHttp http = api();
		Opener opener = new Opener();
		opener.defaultStatus = 503;
		Social social = new Social(new ChatApi(http, BASE), new MeStream(opener, BASE), new Backend(), DIRECT, DIRECT, DIRECT);
		long now = System.currentTimeMillis();
		social.tick(now, "trs_token", SELF, "Theredstonee", true);
		social.tick(now + 1, "trs_token", SELF, "Theredstonee", true);
		social.screenOpen(DM, now + 2);
		social.tick(now + 3, "trs_token", SELF, "Theredstonee", true);
		assertTrue(social.store().peek(DM).loaded);
		assertTrue(social.send(DM, "  Bin gleich da §c ", null, null, null));
		// Ergebnis liegt in der Warteschlange; nach dem nächsten Takt ist die Nachricht bestätigt.
		social.tick(now + 4, "trs_token", SELF, "Theredstonee", true);
		List<Chat.Message> list = social.store().peek(DM).messages();
		Chat.Message last = list.get(list.size() - 1);
		assertEquals(M3, last.id);
		assertFalse(last.pending);
		assertEquals(3, list.size());
		String sent = FakeHttp.body(http.requests.stream().filter(r -> r.method.equals("POST") && r.url.endsWith("/messages"))
				.findFirst().get());
		assertTrue(sent.contains("\"nonce\":\""), sent);
		assertFalse(sent.contains("§"), sent);
	}

	@Test
	void mutedAccountCannotSend() {
		FakeHttp http = api();
		http.json("GET /v1/me/moderation", 200, "{\"mute\":{\"until\":null,\"reason\":\"spam\",\"auto\":\"spam\"},\"warnings\":[]}");
		Opener opener = new Opener();
		opener.defaultStatus = 503;
		Social social = new Social(new ChatApi(http, BASE), new MeStream(opener, BASE), new Backend(), DIRECT, DIRECT, DIRECT);
		long now = System.currentTimeMillis();
		social.tick(now, "trs_token", SELF, "Theredstonee", true);
		social.tick(now + 1, "trs_token", SELF, "Theredstonee", true);
		assertTrue(social.moderation().active(now));
		assertFalse(social.send(DM, "hi", null, null, null));
		assertEquals("social.error.chat_muted", social.notice(now + 2).key);
	}

	@Test
	void moderationAndReportEventsShowToasts() {
		Social social = new Social(new ChatApi(api(), BASE), new MeStream(new Opener(), BASE), new Backend(), DIRECT, DIRECT, DIRECT);
		long now = System.currentTimeMillis();
		social.toasts().settings().dnd = true;
		social.applyForTest(MeEvent.parse("moderation", "{\"action\":\"mute\",\"reason\":\"spam\",\"until\":null}", "e.9"), now, false);
		assertTrue(social.moderation().active(now));
		assertEquals(Toasts.Kind.MODERATION, social.toasts().visible(now).get(0).kind, "Moderation auch bei Nicht stören");
		social.toasts().settings().dnd = false;
		social.applyForTest(MeEvent.parse("report_update", "{\"report\":{\"id\":\"r0123456789abcdef\",\"kind\":\"message\","
				+ "\"status\":\"resolved\",\"outcome\":\"actioned\"}}", "e.10"), now, false);
		boolean found = false;
		for (Toasts.Toast t : social.toasts().visible(now)) if (t.kind == Toasts.Kind.REPORT) found = true;
		assertTrue(found);
		social.applyForTest(MeEvent.parse("moderation", "{\"action\":\"unmute\"}", "e.11"), now, false);
		assertFalse(social.moderation().active(now));
	}

	@Test
	void unknownAndBrokenEventsAreIgnored() {
		assertNull(MeEvent.parse("chat_message", "{not json", "e.1"));
		assertNull(MeEvent.parse("Bad Type", "{}", null));
		MeEvent e = MeEvent.parse("future_event", "{\"x\":1}", "e.2");
		assertNotNull(e);
		Social social = new Social(new ChatApi(api(), BASE), new MeStream(new Opener(), BASE), new Backend(), DIRECT, DIRECT, DIRECT);
		social.applyForTest(e, 0, false);
		assertTrue(social.store().sorted().isEmpty());
	}
}
