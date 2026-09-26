package dev.theredstonee.trsclient.core.online;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Umhänge mit Freunden teilen (API.md §5.10): Angebote laden/melden, annehmen, anbieten, Inhaber, entziehen. */
class CapeShareTest {
	static final String TOKEN = FriendsTest.TOKEN;
	static final String BOB = FriendsTest.BOB;
	static final String ALEX = FriendsTest.ALEX;
	static final String CAPE = "u0123456789abcdef0123";
	static final String BASE = "https://trs-launcher.theredstonee.de";

	static String friendsJson(int offers) {
		return "{\"friends\":[{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\"}],\"requests\":{\"incoming\":[],\"outgoing\":[]},"
				+ "\"capeOffers\":" + offers + "}";
	}

	static String offer(String capeId, String name, String from, String fromName) {
		return "{\"cape\":{\"id\":\"" + capeId + "\",\"name\":\"" + name + "\",\"url\":\"" + BASE + "/v1/capes/" + capeId
				+ ".png?v=1\",\"width\":64,\"height\":32,\"frames\":1},\"from\":{\"uuid\":\"" + from + "\",\"name\":\""
				+ fromName + "\"},\"creator\":{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\"},\"createdAt\":\"2026-09-25T18:00:00.000Z\"}";
	}

	private static Friends friends(FakeHttp http, FriendsTest.Backend backend) {
		return new Friends(new TrsApi(http, OnlineTest.CONFIG), backend);
	}

	@Test
	void parsesOffersAndHoldersStrictly() throws Exception {
		FakeHttp http = new FakeHttp()
				.json("GET /v1/cape-offers", 200, "{\"incoming\":[" + offer(CAPE, "Blitz\\u0007", BOB, "Bob") + ","
						+ offer("BAD ID", "x", BOB, "Bob") + "," + offer(CAPE, "x", "kaputt", "Bob") + ","
						+ "{\"cape\":{\"id\":\"u1111111111111111111a\",\"name\":\"Fremd\",\"url\":\"https://evil.example/x.png\"},"
						+ "\"from\":{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\"},\"creator\":{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\"}}"
						+ "],\"outgoing\":[]}")
				.json("GET /v1/capes/" + CAPE + "/holders", 200, "{\"holders\":["
						+ "{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\",\"status\":\"accepted\",\"grantedBy\":{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\"}},"
						+ "{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\",\"status\":\"weird\",\"grantedBy\":{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\"}},"
						+ "{\"uuid\":\"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0\",\"name\":\"Cleo\",\"status\":\"offered\",\"grantedBy\":{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\"}}"
						+ "],\"count\":3,\"limit\":20}");
		TrsApi api = new TrsApi(http, OnlineTest.CONFIG);
		List<CapeShare.Offer> offers = api.capeOffers(TOKEN);
		assertEquals(2, offers.size(), "kaputte ID/UUID fallen weg");
		CapeShare.Offer o = offers.get(0);
		assertEquals("Blitz", o.capeName, "Steuerzeichen weg");
		assertEquals(BASE + "/v1/capes/" + CAPE + ".png?v=1", o.url);
		assertTrue(o.reshared(), "Bob bietet Alex' Umhang an");
		assertNull(offers.get(1).url, "fremde Adressen werden nie geladen");

		CapeShare.Holders h = api.capeHolders(TOKEN, CAPE);
		assertEquals(2, h.holders.size(), "unbekannter Zustand fällt weg");
		assertFalse(h.holders.get(0).offered);
		assertTrue(h.holders.get(1).offered);
		assertEquals(3, h.count);
		assertFalse(h.full());
		assertTrue(h.has(BOB));
	}

	@Test
	void offersLoadWithTheFriendsListAndAnnounceOnlyNewOnes() {
		final String[] offersJson = {"{\"incoming\":[" + offer(CAPE, "Blitz", BOB, "Bob") + "]}"};
		final int[] count = {1};
		FakeHttp http = new FakeHttp()
				.on("GET /v1/friends", r -> FakeHttp.response(200, friendsJson(count[0])))
				.on("GET /v1/cape-offers", r -> FakeHttp.response(200, offersJson[0]));
		FriendsTest.Backend backend = new FriendsTest.Backend();
		Friends f = friends(http, backend);
		long now = System.currentTimeMillis();
		f.want(Friends.Interest.BACKGROUND, false, now);
		f.tick(now, TOKEN);
		backend.run();
		Friends.Snapshot s = f.snapshot();
		assertEquals(1, s.offers());
		assertEquals(1, s.incoming(), "Angebote zählen zu den wartenden Dingen (Abzeichen)");
		assertEquals(0, s.requests());
		assertEquals(1, s.offerList().size());
		assertEquals(1, s.unseenOffers, "ungesehen = NEU");
		assertEquals(0, s.offerNotice, "beim ersten Laden keine Meldung");

		// Ein zweites Angebot kommt dazu → genau eine Meldung mit Anbieter und Umhang.
		count[0] = 2;
		offersJson[0] = "{\"incoming\":[" + offer(CAPE, "Blitz", BOB, "Bob") + "," + offer("u1111111111111111111a", "Glut", BOB, "Bob") + "]}";
		f.refresh();
		f.tick(now + Friends.MIN_GAP_MS + 1, TOKEN);
		backend.run();
		s = f.snapshot();
		assertEquals(1, s.offerNotice);
		assertEquals("Glut", s.offerNoticeArgs[1]);
		assertEquals(2, s.unseenOffers);
		f.markOffersSeen();
		assertEquals(0, f.snapshot().unseenOffers);
		assertFalse(f.unseen(s.offerList().get(0)));

		// Keine Angebote mehr → keine Nachfrage bei /v1/cape-offers.
		count[0] = 0;
		int before = http.calls().size();
		f.refresh();
		f.tick(now + 2 * Friends.MIN_GAP_MS + 2, TOKEN);
		backend.run();
		assertEquals(before + 1, http.calls().size(), "nur GET /v1/friends");
		assertEquals(0, f.snapshot().offers());
	}

	@Test
	void acceptDeclineOfferRevokeAndHolders() {
		FakeHttp http = new FakeHttp()
				.json("GET /v1/friends", 200, friendsJson(1))
				.json("GET /v1/cape-offers", 200, "{\"incoming\":[" + offer(CAPE, "Blitz", BOB, "Bob") + "]}")
				.json("POST /v1/cape-offers/" + CAPE + "/accept", 200, "{\"cape\":{}}")
				.on("POST /v1/cape-offers/" + CAPE + "/decline", r -> FakeHttp.response(204, null))
				.json("POST /v1/cape-offers", 201, "{\"offer\":{}}")
				.json("GET /v1/capes/" + CAPE + "/holders", 200, "{\"holders\":[{\"uuid\":\"" + BOB
						+ "\",\"name\":\"Bob\",\"status\":\"offered\",\"grantedBy\":{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\"}}],\"count\":1,\"limit\":20}")
				.on("DELETE /v1/capes/" + CAPE + "/holders/" + BOB, r -> FakeHttp.response(204, null))
				.on("DELETE /v1/capes/" + CAPE + "/holders/" + ALEX, r -> FakeHttp.response(204, null));
		FriendsTest.Backend backend = new FriendsTest.Backend();
		Friends f = friends(http, backend);
		long now = System.currentTimeMillis();
		f.want(Friends.Interest.FOREGROUND, false, now);
		f.tick(now, TOKEN);
		backend.run();
		CapeShare.Offer o = f.snapshot().offerList().get(0);

		assertTrue(f.acceptOffer(o));
		backend.run();
		assertEquals("friends.msg.capeAccepted", f.snapshot().message);
		assertEquals("Blitz", f.snapshot().args[0]);
		assertEquals(1, f.snapshot().capesChanged, "Garderobe lädt neu");

		assertTrue(f.declineOffer(o));
		backend.run();
		assertEquals("friends.msg.capeDeclined", f.snapshot().message);

		assertTrue(f.offerCape(CAPE, "Blitz", BOB, "Bob"));
		backend.run();
		assertEquals("friends.msg.capeOffered", f.snapshot().message);
		assertTrue(FakeHttp.body(find(http, "POST /v1/cape-offers")).contains("\"friend\":\"" + BOB + "\""));
		assertNotNull(f.snapshot().holdersOf(CAPE), "Inhaber danach neu geladen");
		assertTrue(f.snapshot().holdersOf(CAPE).holders.get(0).offered);
		assertNull(f.snapshot().holdersOf("andere"));

		assertTrue(f.revokeShare(CAPE, BOB, "Bob", true, false));
		backend.run();
		assertEquals("friends.msg.capeWithdrawn", f.snapshot().message);

		int changed = f.snapshot().capesChanged;
		assertTrue(f.revokeShare(CAPE, ALEX, "Blitz", false, true));
		backend.run();
		assertEquals("friends.msg.capeGivenBack", f.snapshot().message);
		assertEquals(changed + 1, f.snapshot().capesChanged);

		assertFalse(f.offerCape("../x", "x", BOB, "Bob"), "nur gültige IDs");
		assertFalse(f.offerCape(CAPE, "x", "nope", "Bob"), "nur UUIDs");
	}

	@Test
	void shareErrorsGetTheirOwnMessages() {
		FakeHttp http = new FakeHttp()
				.json("GET /v1/friends", 200, friendsJson(0))
				.json("POST /v1/cape-offers", 409, "{\"error\":{\"code\":\"share_limit\"}}");
		FriendsTest.Backend backend = new FriendsTest.Backend();
		Friends f = friends(http, backend);
		f.tick(System.currentTimeMillis(), TOKEN);
		assertTrue(f.offerCape(CAPE, "Blitz", BOB, "Bob"));
		backend.run();
		assertEquals("friends.error.share_limit", f.snapshot().message);
		assertTrue(f.snapshot().messageError);
		for (String code : new String[]{"cape_not_approved", "already_shared", "offer_not_found", "holder_not_found", "offer_inbox_full"}) {
			assertEquals("friends.error." + code, Friends.errorKey(code, 409));
		}
	}

	private static Http.Request find(FakeHttp http, String call) {
		synchronized (http.requests) {
			for (Http.Request r : http.requests) {
				if ((r.method + " " + r.url.replaceFirst("^https?://[^/]+", "")).equals(call)) return r;
			}
		}
		throw new AssertionError("keine Anfrage " + call);
	}
}
