package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.theredstonee.trsclient.core.social.SocialFakes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Moderation v2 im Mod (API.md §22): Fehler/Ereignisse lesen, Zeiten, Einspruch, Zustand, Benachrichtigungen. */
class SanctionsTest {
	static final String BASE = "http://127.0.0.1:1";
	static final long HOUR = 3_600_000L;
	static final long DAY = 24 * HOUR;
	static final String TOKEN = "trs_token";
	static final String APPEAL_TOKEN = "trs_appealAppealAppealAppealAppealAppealAppeal1";

	@BeforeEach
	void english() {
		I18n.use("en");
	}

	/** Die Toast-Einstellungen sind (wie im Spiel) ein gemeinsames Objekt – Nicht stören nach jedem Test zurück. */
	private final java.util.List<Social> created = new java.util.ArrayList<>();

	@AfterEach
	void reset() {
		I18n.use("en");
		for (Social s : created) s.toasts().settings().dnd = false;
	}

	static String iso(long ms) {
		return Instant.ofEpochMilli(ms).toString();
	}

	/** MySanctionView als JSON. {@code ends} 0 = dauerhaft; {@code appeal} = JSON oder null. */
	static String view(long id, String kind, String code, String reason, long starts, long ends, String status, String appeal,
			boolean appealable) {
		return "{\"id\":" + id + ",\"kind\":\"" + kind + "\",\"reasonCode\":\"" + code + "\",\"reason\":"
				+ (reason == null ? "null" : "\"" + reason + "\"") + ",\"startsAt\":\"" + iso(starts) + "\",\"endsAt\":"
				+ (ends == 0 ? "null" : "\"" + iso(ends) + "\"") + ",\"status\":\"" + status + "\",\"liftedAt\":null,\"appeal\":"
				+ (appeal == null ? "null" : appeal) + ",\"appealable\":" + appealable + "}";
	}

	static String error(String code, String sanction) {
		return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"x\",\"until\":null,\"sanction\":" + sanction + "}}";
	}

	static Sanction parse(String json) {
		return SanctionJson.sanction(SanctionJson.object(json));
	}

	// --- Lesen ---

	@Test
	void parsesErrorBodyWithSanction() {
		long now = System.currentTimeMillis();
		String body = "{\"error\":{\"code\":\"sanctioned\",\"message\":\"blocked\",\"until\":\"" + iso(now + DAY) + "\",\"sanction\":"
				+ view(5, "upload_ban", "copyright", "Cape with a §cforeign logo", now - HOUR, now + DAY, "active", null, true) + "}}";
		SanctionError e = SanctionError.parse(body);
		assertNotNull(e);
		assertEquals("sanctioned", e.code);
		assertEquals("upload_ban", e.kind());
		assertEquals(5, e.sanction.id);
		assertEquals("copyright", e.sanction.reasonCode);
		assertEquals("Cape with a foreign logo", e.sanction.reason, "Formatierungscodes raus");
		assertTrue(e.sanction.active(now));
		assertTrue(e.sanction.canAppeal(now));
		assertEquals(now + DAY, e.until, 1000);
		assertNull(e.appealToken);
	}

	@Test
	void toleratesMissingAndWrongFields() {
		// Nur der Code: Art aus dem Code geschlossen.
		SanctionError bare = SanctionError.parse("{\"error\":{\"code\":\"chat_muted\"}}");
		assertNotNull(bare);
		assertNull(bare.sanction);
		assertEquals("chat_mute", bare.kind());
		assertEquals(0, bare.until);
		// Falsche Typen, unbekannte Art/Vorlage, ID als Text – nichts wirft.
		Sanction s = parse("{\"id\":\"7\",\"kind\":\"space_ban\",\"reasonCode\":42,\"reason\":{\"x\":1},\"startsAt\":5,"
				+ "\"endsAt\":\"kaputt\",\"status\":\"weird\",\"appeal\":\"nein\",\"appealable\":\"yes\"}");
		assertNotNull(s);
		assertEquals(7, s.id);
		assertEquals("unknown", s.kind);
		assertEquals("other", s.reasonCode);
		assertNull(s.reason);
		assertTrue(s.permanent());
		assertEquals("active", s.status, "Stand abgeleitet");
		assertNull(s.appeal);
		assertFalse(s.appealable);
		// Ohne ID keine Strafe; kein Strafen-Code / kaputtes JSON → null.
		assertNull(parse("{\"kind\":\"warn\"}"));
		assertNull(SanctionError.parse("{\"error\":{\"code\":\"not_friends\"}}"));
		assertNull(SanctionError.parse("<html>502</html>"));
		assertNull(SanctionError.parse(null));
		// Aus einer ApiException ohne Körper: trotzdem als Strafe erkannt.
		SanctionError fromException = SanctionError.of(new ApiException(403, "banned", 0));
		assertNotNull(fromException);
		assertEquals("account_ban", fromException.kind());
		assertNull(SanctionError.of(new ApiException(404, "banned", 0)));
	}

	@Test
	void bannedLoginCarriesAppealToken() {
		long now = System.currentTimeMillis();
		String body = "{\"error\":{\"code\":\"banned\",\"message\":\"This account is banned\",\"until\":null,\"sanction\":"
				+ view(9, "account_ban", "cheating", null, now - DAY, 0, "active", null, true) + ",\"appealToken\":\"" + APPEAL_TOKEN
				+ "\",\"appealTokenExpiresAt\":\"" + iso(now + HOUR) + "\"}}";
		SanctionError e = SanctionError.parse(body);
		assertEquals(APPEAL_TOKEN, e.appealToken);
		assertEquals(now + HOUR, e.appealTokenExpiresAt, 1000);
		assertTrue(e.sanction.permanent());
		// Ungültiger Token wird verworfen.
		assertNull(SanctionError.parse(body.replace(APPEAL_TOKEN, "evil token")).appealToken);
	}

	@Test
	void parsesSanctionEventsAndAppealDecision() {
		long now = System.currentTimeMillis();
		MeEvent added = MeEvent.parse("sanction_added", "{\"sanction\":" + view(3, "chat_mute", "spam", null, now, now + HOUR,
				"active", null, true) + "}", "e.1");
		assertEquals(3, added.sanction.id);
		assertEquals("chat_mute", added.sanction.kind);
		MeEvent decided = MeEvent.parse("appeal_decided", "{\"sanctionId\":3,\"appeal\":{\"id\":1,\"status\":\"shortened\","
				+ "\"createdAt\":\"" + iso(now) + "\",\"decidedAt\":\"" + iso(now) + "\",\"response\":\"Ok, einmal verkürzt\"},"
				+ "\"sanction\":" + view(3, "chat_mute", "spam", null, now, now + 60_000, "active",
				"{\"id\":1,\"status\":\"shortened\",\"createdAt\":null,\"decidedAt\":null,\"response\":\"Ok\"}", false) + "}", "e.2");
		assertEquals(3, decided.sanctionId);
		assertEquals("shortened", decided.appeal.status);
		assertEquals("Ok, einmal verkürzt", decided.appeal.response);
		assertEquals("shortened", decided.sanction.appeal.status);
		// Kaputte Strafe im Ereignis → sanction null, Ereignis bleibt.
		MeEvent broken = MeEvent.parse("sanction_updated", "{\"sanction\":{\"id\":\"x\"}}", "e.3");
		assertNotNull(broken);
		assertNull(broken.sanction);
		// Andere Ereignisse lesen keine Strafen.
		assertNull(MeEvent.parse("chat_typing", "{\"sanction\":" + view(3, "warn", "spam", null, now, 0, "active", null, false)
				+ "}", "e.4").sanction);
	}

	@Test
	void listsAreSortedAndBounded() {
		long now = System.currentTimeMillis();
		List<List<Sanction>> lists = SanctionJson.lists("{\"active\":[" + view(1, "warn", "spam", null, now - DAY, now + DAY,
				"active", null, true) + "," + view(2, "chat_mute", "insult_hate", "bad", now - HOUR, now + HOUR, "active", null, true)
				+ ",42],\"past\":[" + view(3, "social_ban", "harassment", null, now - 3 * DAY, now - 2 * DAY, "expired", null, false)
				+ "]}");
		assertEquals(Arrays.asList(2L, 1L), Arrays.asList(lists.get(0).get(0).id, lists.get(0).get(1).id), "neueste zuerst");
		assertEquals(1, lists.get(1).size());
		assertTrue(SanctionJson.lists("kaputt").get(0).isEmpty());
	}

	// --- Zeiten ---

	@Test
	void relativeTimeAndEnd() {
		long now = 1_800_000_000_000L;
		assertEquals("ends in less than a minute", SanctionText.remaining(now + 30_000, now));
		assertEquals("1 min left", SanctionText.remaining(now + 60_000, now));
		assertEquals("5 min left", SanctionText.remaining(now + 4 * 60_000 + 1, now), "Minuten aufgerundet");
		assertEquals("1 hour left", SanctionText.remaining(now + HOUR + 59 * 60_000, now));
		assertEquals("47 hours left", SanctionText.remaining(now + 47 * HOUR + 30 * 60_000, now), "Stunden abgerundet");
		assertEquals("2 days left", SanctionText.remaining(now + 2 * DAY + 23 * HOUR, now));
		assertEquals("30 days left", SanctionText.remaining(now + 30 * DAY, now));
		Sanction perm = new Sanction(1, "account_ban", "cheating", null, now, 0, "active", 0, null, true);
		assertEquals("permanent", SanctionText.end(perm, now));
		Sanction review = new Sanction(2, "chat_mute", "auto_reports", null, now, 0, "active", 0, null, true);
		assertEquals("until a moderator has reviewed it", SanctionText.end(review, now));
		Sanction timed = new Sanction(3, "chat_mute", "spam", "Werbung", now, now + 3 * DAY, "active", 0, null, true);
		String end = SanctionText.end(timed, now);
		assertTrue(end.startsWith("3 days left (until "), end);
		assertTrue(end.contains(Times.dateTime(now + 3 * DAY)), end);
		assertEquals("Spam: “Werbung”", SanctionText.reason(timed));
		assertEquals("Chat mute", SanctionText.kind("chat_mute"));
		assertEquals("Sanction", SanctionText.kind("space_ban"));
		I18n.use("de");
		assertEquals("noch 3 Tage", SanctionText.remaining(now + 3 * DAY, now));
		assertEquals("Chat-Stummschaltung", SanctionText.kind("chat_mute"));
		assertEquals("dauerhaft", SanctionText.end(perm, now));
	}

	@Test
	void everyKindAndReasonIsTranslatedInAllLanguages() {
		for (String lang : I18n.LANGUAGES) {
			java.util.Map<String, String> raw = I18n.raw(lang);
			for (String k : Sanction.KINDS) {
				assertTrue(raw.containsKey("sanction.kind." + k), lang + " kind " + k);
				assertTrue(raw.containsKey("sanction.effect." + k), lang + " effect " + k);
			}
			for (String r : Sanction.REASONS) assertTrue(raw.containsKey("sanction.reason." + r), lang + " reason " + r);
			for (String s : new String[]{"open", "lifted", "shortened", "upheld"}) {
				assertTrue(raw.containsKey("sanction.appeal.status." + s), lang + " appeal " + s);
			}
		}
	}

	// --- Einspruch ---

	@Test
	void appealTextIsCleanedAndValidatedLikeTheApi() {
		assertEquals("line one\nline two", Sanctions.cleanAppeal("  line one\r\nline two\u0007  "));
		assertEquals("ab", Sanctions.cleanAppeal("a‍b‮"), "Format-Zeichen (Cf) lehnt die API ab");
		assertEquals("a b", Sanctions.cleanAppeal("a\tb"));
		assertEquals("", Sanctions.cleanAppeal(null));
		String nineteen = "abcdefghijklmnopqrs";
		assertEquals("sanction.appeal.tooShort", Sanctions.appealProblem(nineteen));
		assertEquals("sanction.appeal.tooShort", Sanctions.appealProblem("   " + nineteen + "​   "), "zählt nach dem Säubern");
		assertNull(Sanctions.appealProblem(nineteen + "t"));
		String thousand = String.join("", Collections.nCopies(100, "abcdefghi\n"));
		assertEquals(999, Sanctions.appealLength(thousand), "Zeilenumbruch am Ende wird getrimmt");
		assertNull(Sanctions.appealProblem(thousand.trim() + "x"));
		assertEquals("sanction.appeal.tooLong", Sanctions.appealProblem(thousand.trim() + "xy"));
	}

	@Test
	void chatApiRefusesInvalidAppealWithoutRequest() {
		FakeHttp http = new FakeHttp();
		ChatApi api = new ChatApi(http, BASE);
		try {
			api.appeal(TOKEN, 5, "too short");
			throw new AssertionError("erwartet: invalid_request");
		} catch (ApiException e) {
			assertEquals("invalid_request", e.code());
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		assertTrue(http.calls().isEmpty());
	}

	// --- Zustand ---

	@Test
	void storeTracksChangesAndExpiry() {
		long now = System.currentTimeMillis();
		Sanctions store = new Sanctions();
		assertFalse(store.supported());
		Sanction mute = parse(view(1, "chat_mute", "spam", null, now, now + HOUR, "active", null, true));
		assertEquals(Sanctions.Change.ADDED, store.apply(mute, now));
		assertTrue(store.supported());
		assertEquals(mute, store.activeOf("chat_mute", now));
		// Verkürzt, verlängert, Einspruch, aufgehoben.
		Sanction shorter = parse(view(1, "chat_mute", "spam", null, now, now + 60_000, "active", null, true));
		assertEquals(Sanctions.Change.SHORTENED, store.apply(shorter, now));
		Sanction longer = parse(view(1, "chat_mute", "spam", null, now, 0, "active", null, true));
		assertEquals(Sanctions.Change.EXTENDED, store.apply(longer, now));
		Sanction appealed = parse(view(1, "chat_mute", "spam", null, now, 0, "active",
				"{\"id\":4,\"status\":\"open\",\"createdAt\":null}", false));
		assertEquals(Sanctions.Change.APPEAL_FILED, store.apply(appealed, now));
		assertFalse(store.activeOf("chat_mute", now).canAppeal(now), "nur einmal");
		Sanction lifted = parse(view(1, "chat_mute", "spam", null, now, 0, "lifted", null, false));
		assertEquals(Sanctions.Change.LIFTED, store.apply(lifted, now));
		assertNull(store.activeOf("chat_mute", now));
		assertEquals(1, store.past().size());
		// Die schwerste aktive + die am längsten laufende gleicher Art.
		store.apply(parse(view(2, "warn", "spam", null, now, now + DAY, "active", null, true)), now);
		store.apply(parse(view(3, "social_ban", "harassment", null, now, now + HOUR, "active", null, true)), now);
		store.apply(parse(view(4, "social_ban", "harassment", null, now, now + 2 * HOUR, "active", null, true)), now);
		assertEquals(4, store.activeOf("social_ban", now).id);
		assertEquals("social_ban", store.mostSevere(now).kind);
		assertEquals(3, store.activeCount(now));
		// Zeit läuft ab.
		List<Sanction> gone = store.expire(now + HOUR + 1);
		assertEquals(1, gone.size());
		assertEquals(3, gone.get(0).id);
		assertEquals("expired", store.find(3).status);
		assertEquals(2, store.activeCount(now + HOUR + 1));
		assertEquals(Sanctions.Change.EXPIRED, Sanctions.classify(store.find(2), parse(view(2, "warn", "spam", null, now, now + DAY,
				"expired", null, false)), now));
	}

	// --- Benachrichtigungen ---

	@Test
	void toastDecision() {
		long now = System.currentTimeMillis();
		Sanction mute = parse(view(1, "chat_mute", "spam", null, now, now + 2 * HOUR + 60_000, "active", null, true));
		String[] added = Social.sanctionToast("sanction_added", Sanctions.Change.ADDED, mute, null, now);
		assertEquals("New sanction: Chat mute", added[0]);
		assertEquals("2 hours left · Spam", added[1]);
		Sanction warn = parse(view(2, "warn", "insult_hate", "Be nice", now, now + DAY, "active", null, false));
		assertEquals("Warning from the team", Social.sanctionToast("sanction_added", Sanctions.Change.ADDED, warn, null, now)[0]);
		assertEquals("Insults or hate: “Be nice”", Social.sanctionToast("sanction_added", Sanctions.Change.ADDED, warn, null, now)[1]);
		assertEquals("Sanction lifted", Social.sanctionToast("sanction_updated", Sanctions.Change.LIFTED, mute, null, now)[0]);
		assertEquals("Appeal submitted", Social.sanctionToast("sanction_updated", Sanctions.Change.APPEAL_FILED, mute, null, now)[0]);
		assertNull(Social.sanctionToast("sanction_updated", Sanctions.Change.NONE, mute, null, now));
		Sanction.Appeal upheld = new Sanction.Appeal(1, "upheld", now, now, "Rules are rules");
		String[] decided = Social.sanctionToast("appeal_decided", Sanctions.Change.NONE, mute, upheld, now);
		assertEquals("Appeal rejected", decided[0]);
		assertEquals("Rules are rules", decided[1]);
		assertNull(Social.sanctionToast("appeal_decided", Sanctions.Change.NONE, mute,
				new Sanction.Appeal(1, "open", now, 0, null), now));
	}

	private Social social(FakeHttp http) {
		Opener opener = new Opener();
		opener.defaultStatus = 503;
		Social s = new Social(new ChatApi(http, BASE), new MeStream(opener, BASE), new Backend(), DIRECT, DIRECT, DIRECT);
		created.add(s);
		return s;
	}

	private static FakeHttp api(String activeJson) {
		FakeHttp http = new FakeHttp();
		http.json("GET /v1/chat/conversations", 200, "{\"conversations\":[" + dm(0, 0, null) + "],\"nextCursor\":null}");
		http.json("GET /v1/me/moderation", 200, "{\"mute\":null,\"warnings\":[]}");
		http.json("GET /v1/me", 200, "{\"uuid\":\"" + SELF + "\",\"settings\":{}}");
		http.json("GET /v1/me/sanctions", 200, "{\"active\":[" + activeJson + "],\"past\":[]}");
		return http;
	}

	@Test
	void eventsUpdateStateAndAlwaysToastWithoutDoubleLegacyToast() {
		long now = System.currentTimeMillis();
		Social social = social(api(""));
		social.tick(now, TOKEN, SELF, "Theredstonee", true);
		social.tick(now + 1, TOKEN, SELF, "Theredstonee", true);
		assertTrue(social.sanctions().supported());
		assertFalse(social.moderation().active(now));
		social.toasts().settings().dnd = true;
		// Neue Stummschaltung: Ereignis v2, danach das alte „moderation“ – nur EIN Toast, auch bei Nicht stören.
		social.applyForTest(MeEvent.parse("sanction_added", "{\"sanction\":" + view(8, "chat_mute", "spam", "Werbung", now,
				now + 3 * DAY + HOUR, "active", null, true) + "}", "e.1"), now, false);
		social.applyForTest(MeEvent.parse("moderation", "{\"action\":\"mute\",\"reason\":\"Werbung\",\"until\":\"" + iso(now + 3 * DAY + HOUR)
				+ "\"}", "e.2"), now, false);
		List<Toasts.Toast> visible = social.toasts().visible(now);
		assertEquals(1, visible.size());
		assertEquals(Toasts.Kind.MODERATION, visible.get(0).kind);
		assertEquals("New sanction: Chat mute", visible.get(0).title);
		assertTrue(social.moderation().active(now));
		assertEquals(8, social.chatMute(now).id);
		// Senden ist gesperrt – mit Art und Restzeit im Hinweis.
		assertFalse(social.send(DM, "hi", null, null, null));
		Social.Notice n = social.notice(now);
		assertEquals("sanction.blockedNotice", n.key);
		assertEquals("Chat mute – 3 days left", n.args[0]);
		// Einspruch entschieden (aufgehoben): ersetzt den Toast statt hochzuzählen.
		social.applyForTest(MeEvent.parse("appeal_decided", "{\"sanctionId\":8,\"appeal\":{\"id\":2,\"status\":\"lifted\","
				+ "\"response\":\"Sorry, war ein Fehler\"},\"sanction\":" + view(8, "chat_mute", "spam", "Werbung", now, now + DAY,
				"lifted", "{\"id\":2,\"status\":\"lifted\",\"response\":\"Sorry, war ein Fehler\"}", false) + "}", "e.3"), now, false);
		visible = social.toasts().visible(now);
		assertEquals(1, visible.size());
		assertEquals("Appeal accepted", visible.get(0).title);
		assertEquals(1, visible.get(0).count);
		assertFalse(social.moderation().active(now));
		assertEquals(1, social.sanctions().past().size());
	}

	@Test
	void expiredMuteEndsByItselfWithToast() {
		long now = System.currentTimeMillis();
		Social social = social(api(view(4, "chat_mute", "spam", null, now - HOUR, now + 1500, "active", null, true)));
		social.tick(now, TOKEN, SELF, "Theredstonee", true);
		social.tick(now + 1, TOKEN, SELF, "Theredstonee", true);
		assertTrue(social.moderation().active(now + 1));
		social.tick(now + 2000, TOKEN, SELF, "Theredstonee", true);
		assertFalse(social.moderation().active(now + 2000));
		assertEquals("Sanction ended", social.toasts().visible(now + 2000).get(0).title);
	}

	@Test
	void sanctionErrorFromChatShowsDetailsAndUpdatesState() {
		long now = System.currentTimeMillis();
		FakeHttp http = api("");
		String mute = view(11, "chat_mute", "harassment", "Lass das", now, now + 3 * DAY + HOUR, "active", null, true);
		http.json("POST /v1/chat/conversations/" + DM + "/messages", 403, error("chat_muted", mute));
		Social social = social(http);
		social.tick(now, TOKEN, SELF, "Theredstonee", true);
		social.tick(now + 1, TOKEN, SELF, "Theredstonee", true);
		assertTrue(social.send(DM, "hallo", null, null, null), "lokal noch nicht stumm");
		social.tick(now + 2, TOKEN, SELF, "Theredstonee", true);
		assertTrue(social.moderation().active(now + 2));
		assertEquals("sanction.blockedNotice", social.notice(now + 2).key);
		assertEquals("Chat mute – 3 days left", social.notice(now + 2).args[0]);
		SanctionError hit = social.takeBlocked();
		assertNotNull(hit);
		assertEquals(11, hit.sanction.id);
		assertNull(social.takeBlocked(), "nur einmal");
		Chat.Message failed = social.store().peek(DM).messages().get(social.store().peek(DM).messages().size() - 1);
		assertEquals("social.error.chat_muted", failed.failed);
		// Andere Sperre über eine Aktion (Gruppe erstellen) → sanctioned mit social_ban.
		http.json("POST /v1/chat/groups", 403, error("sanctioned", view(12, "social_ban", "spam", null, now, now + HOUR,
				"active", null, true)));
		AtomicReference<String> err = new AtomicReference<>();
		social.createGroup("Neu", Collections.singletonList(BOB), (value, e) -> err.set(e));
		social.tick(now + 3, TOKEN, SELF, "Theredstonee", true);
		assertEquals("social.error.sanctioned", err.get());
		assertEquals("social_ban", social.takeBlocked().kind());
		assertNotNull(social.sanctions().activeOf("social_ban", now + 3));
	}

	@Test
	void appealIsSentOnceAndErrorsAreTranslated() {
		long now = System.currentTimeMillis();
		FakeHttp http = api(view(5, "upload_ban", "copyright", null, now, now + DAY, "active", null, true));
		http.on("POST /v1/me/sanctions/5/appeal", r -> {
			assertTrue(FakeHttp.body(r).contains("\"text\":\"Das Logo ist mein eigenes, siehe Entwurf.\\nDanke!\""), FakeHttp.body(r));
			return FakeHttp.response(201, "{\"sanction\":" + view(5, "upload_ban", "copyright", null, now, now + DAY, "active",
					"{\"id\":1,\"status\":\"open\",\"createdAt\":\"" + iso(now) + "\"}", false) + "}");
		});
		Social social = social(http);
		social.tick(now, TOKEN, SELF, "Theredstonee", true);
		social.tick(now + 1, TOKEN, SELF, "Theredstonee", true);
		assertTrue(social.sanctions().find(5).canAppeal(now));
		AtomicReference<Object[]> result = new AtomicReference<>();
		social.appeal(5, "short", (v, e) -> result.set(new Object[]{v, e}));
		assertEquals("sanction.appeal.tooShort", result.get()[1], "zu kurz: gar nicht erst senden");
		assertEquals(0, http.count("POST /v1/me/sanctions"));
		social.appeal(5, "Das Logo ist mein eigenes, siehe Entwurf.\r\nDanke!​", (v, e) -> result.set(new Object[]{v, e}));
		social.tick(now + 2, TOKEN, SELF, "Theredstonee", true);
		assertNull(result.get()[1]);
		assertEquals("open", social.sanctions().find(5).appeal.status);
		assertFalse(social.sanctions().find(5).canAppeal(now));
		// Zweiter Einspruch: 409 → Meldung + Liste neu.
		http.json("POST /v1/me/sanctions/5/appeal", 409, "{\"error\":{\"code\":\"appeal_exists\",\"message\":\"x\"}}");
		long before = http.count("GET /v1/me/sanctions");
		social.appeal(5, "Noch ein Versuch, bitte bitte bitte.", (v, e) -> result.set(new Object[]{v, e}));
		social.tick(now + 3, TOKEN, SELF, "Theredstonee", true);
		assertEquals("sanction.error.appeal_exists", result.get()[1]);
		assertTrue(http.count("GET /v1/me/sanctions") > before);
		assertEquals("sanction.error.rate_limited", Social.appealErrorKey(new ApiException(429, "rate_limited", 0), false));
		assertEquals("sanction.error.token_expired", Social.appealErrorKey(new ApiException(401, "unauthorized", 0), true));
		assertEquals("sanction.error.generic", Social.appealErrorKey(new ApiException(500, "internal_error", 0), false));
	}

	@Test
	void bannedLoginUsesAppealTokenOnlyForSanctions() {
		long now = System.currentTimeMillis();
		String ban = view(9, "account_ban", "cheating", "Killaura", now - DAY, 0, "active", null, true);
		FakeHttp http = api(ban);
		http.on("POST /v1/me/sanctions/9/appeal", r -> {
			assertEquals("Bearer " + APPEAL_TOKEN, r.headers.get("Authorization"));
			return FakeHttp.response(201, "{\"sanction\":" + view(9, "account_ban", "cheating", "Killaura", now - DAY, 0, "active",
					"{\"id\":3,\"status\":\"open\"}", false) + "}");
		});
		Social social = social(http);
		social.toasts().settings().dnd = true;
		String body = "{\"error\":{\"code\":\"banned\",\"message\":\"x\",\"until\":null,\"sanction\":" + ban
				+ ",\"appealToken\":\"" + APPEAL_TOKEN + "\",\"appealTokenExpiresAt\":\"" + iso(now + HOUR) + "\"}}";
		social.loginBanned(body);
		social.tick(now, null, null, null, false);
		assertNotNull(social.banned());
		assertEquals("TRS account banned", social.toasts().visible(now).get(0).title);
		assertEquals(9, social.sanctions().activeOf("account_ban", now).id);
		assertTrue(social.sanctionAccess(now));
		assertFalse(social.appealTokenExpired(now));
		// Liste mit dem Einspruch-Token – nichts anderes wird abgefragt.
		social.loadSanctions();
		social.tick(now + 1, null, null, null, false);
		assertEquals(1, http.calls().size());
		assertEquals("Bearer " + APPEAL_TOKEN, http.requests.get(0).headers.get("Authorization"));
		AtomicReference<Object[]> result = new AtomicReference<>();
		social.appeal(9, "Ich habe keine Hacks benutzt, bitte prüft das erneut.", (v, e) -> result.set(new Object[]{v, e}));
		social.tick(now + 2, null, null, null, false);
		assertNull(result.get()[1]);
		assertEquals("open", social.sanctions().find(9).appeal.status);
		// Token abgelaufen → kein Zugang mehr, verständlicher Fehler.
		assertTrue(social.appealTokenExpired(now + 2 * HOUR));
		assertFalse(social.sanctionAccess(now + 2 * HOUR));
		// Später erfolgreich angemeldet (Sperre vorbei): Sperr-Angaben weg.
		social.tick(now + 3, TOKEN, SELF, "Theredstonee", true);
		assertNull(social.banned());
		// Ohne Körper (ältere API): trotzdem als gesperrt bekannt, aber ohne Einspruch-Zugang.
		Social old = social(new FakeHttp());
		old.loginBanned(null);
		old.tick(now, null, null, null, false);
		assertEquals("account_ban", old.banned().kind());
		assertFalse(old.sanctionAccess(now));
		assertTrue(old.appealTokenExpired(now));
	}

	@Test
	void olderServerWithoutSanctionsKeepsLegacyModeration() {
		long now = System.currentTimeMillis();
		FakeHttp http = api("");
		http.json("GET /v1/me/sanctions", 404, "{\"error\":{\"code\":\"not_found\"}}");
		Social social = social(http);
		social.tick(now, TOKEN, SELF, "Theredstonee", true);
		social.tick(now + 1, TOKEN, SELF, "Theredstonee", true);
		assertFalse(social.sanctions().supported());
		social.applyForTest(MeEvent.parse("moderation", "{\"action\":\"mute\",\"reason\":\"spam\",\"until\":null}", "e.1"), now, false);
		assertTrue(social.moderation().active(now));
		assertEquals(Toasts.Kind.MODERATION, social.toasts().visible(now).get(0).kind, "altes Ereignis zeigt weiter einen Toast");
	}
}
