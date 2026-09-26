package dev.theredstonee.trsclient.core.hosting;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import dev.theredstonee.trsclient.core.hosting.net.UdpLink;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Welt-Hosting ohne Minecraft: Signalisierung (Angebot/Antwort/bye, alte sid, Verdrängen) mit einer nachgebauten API,
 * Namensprüfung im Login, Rechte-Abbildung, Warn-Dialog des öffentlichen Links, API-Antworten.
 */
class HostingLogicTest {
	static final String HOST = "75c1a6f3112240abbdb57b9d21c64232";
	static final String GUEST = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

	// --- Signalisierung: DirectConnect über SignalBox + nachgebaute API ---

	/** „API“: reicht Signale an das Postfach der Gegenseite weiter (wie hosting_signal über /v1/events/me). */
	static final class FakeSignalApi {
		final SignalBox hostBox = new SignalBox();
		final SignalBox guestBox = new SignalBox();
		final List<String> log = Collections.synchronizedList(new ArrayList<String>());
		volatile boolean dropAnswers;

		DirectConnect.Signaller from(final String me) {
			return new DirectConnect.Signaller() {
				@Override
				public void send(String to, String kind, String sid, String data) {
					log.add(me.substring(0, 4) + "->" + kind);
					if (dropAnswers && kind.equals("answer")) return;
					SignalBox target = to.equals(HOST) ? hostBox : guestBox;
					target.deliver("h0123456789abcdef0123", new SignalBox.Signal(me, kind, sid, data));
				}
			};
		}
	}

	@Test
	void offerAnswerPunchAndStream() throws Exception {
		final FakeSignalApi api = new FakeSignalApi();
		final AtomicReference<UdpLink> hostLink = new AtomicReference<UdpLink>();
		final CountDownLatch hostDone = new CountDownLatch(1);
		api.hostBox.offers(new SignalBox.OfferHandler() {
			@Override
			public void offer(String roomId, final SignalBox.Signal s) {
				new Thread(() -> {
					hostLink.set(DirectConnect.host(api.hostBox, api.from(HOST), s, Collections.<InetSocketAddress>emptyList(), true,
							5000, "1.21.11", "fabric"));
					hostDone.countDown();
				}).start();
			}
		});
		UdpLink guest = DirectConnect.guest(api.guestBox, api.from(GUEST), HOST, Collections.<InetSocketAddress>emptyList(), true,
				5000, "1.21.11", "fabric");
		assertNotNull(guest, "Gast hat kein Paar: " + api.log);
		assertTrue(hostDone.await(6, TimeUnit.SECONDS));
		assertNotNull(hostLink.get());
		assertEquals("[b0b0->offer, 75c1->answer]", api.log.toString());
		guest.begin("g");
		hostLink.get().begin("h");
		final ByteArrayOutputStream got = new ByteArrayOutputStream();
		final CountDownLatch done = new CountDownLatch(1);
		hostLink.get().start(new PeerStream.Sink() {
			@Override
			public synchronized void data(byte[] b, int off, int len) {
				got.write(b, off, len);
				if (got.size() >= 11) done.countDown();
			}

			@Override
			public void closed(String reason) {
			}
		});
		guest.start(new PeerStream.Sink() {
			@Override
			public void data(byte[] b, int off, int len) {
			}

			@Override
			public void closed(String reason) {
			}
		});
		guest.write("hello world".getBytes(StandardCharsets.UTF_8), 0, 11);
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals("hello world", new String(got.toByteArray(), StandardCharsets.UTF_8));
		guest.close("t");
		hostLink.get().close("t");
	}

	@Test
	void noAnswerMeansByeAndRelayFallback() {
		FakeSignalApi api = new FakeSignalApi();
		api.dropAnswers = true;
		api.hostBox.offers(new SignalBox.OfferHandler() {
			@Override
			public void offer(String roomId, SignalBox.Signal s) {
				// Host antwortet nicht (z. B. Direktverbindungen aus)
			}
		});
		long start = System.currentTimeMillis();
		assertNull(DirectConnect.guest(api.guestBox, api.from(GUEST), HOST, Collections.<InetSocketAddress>emptyList(), true, 800,
				"1.21.11", "fabric"));
		assertTrue(System.currentTimeMillis() - start < 3000, "Budget eingehalten");
		assertTrue(api.log.contains("b0b0->bye"), "bye verschickt: " + api.log);
	}

	@Test
	void signalBoxIgnoresStaleSidAndReplacesOldAttempts() throws Exception {
		SignalBox box = new SignalBox();
		final List<String> offers = new ArrayList<String>();
		box.offers((roomId, s) -> offers.add(s.sid));
		SignalBox.Session first = box.open(HOST, "one");
		SignalBox.Session second = box.open(HOST, "two");
		assertTrue(first.isClosed(), "neuer Versuch verdrängt den alten");
		box.deliver("r", new SignalBox.Signal(HOST, "answer", "one", "x"));
		assertNull(second.poll(50), "alte sid wird verworfen");
		box.deliver("r", new SignalBox.Signal(HOST, "answer", "two", "y"));
		assertEquals("y", second.poll(1000).data);
		box.deliver("r", new SignalBox.Signal(GUEST, "offer", "s9", "{}"));
		assertEquals(Collections.singletonList("s9"), offers);
		box.clear();
		assertTrue(second.isClosed());
		assertNull(second.poll(10));
	}

	@Test
	void iceDataRoundTripAndValidation() {
		DirectConnect.Ice ice = new DirectConnect.Ice(new byte[16], Collections.singletonList(UdpLink.parseCandidate("10.1.2.3:4567")),
				"1.21.11", "fabric");
		DirectConnect.Ice back = DirectConnect.Ice.parse(ice.json());
		assertNotNull(back);
		assertEquals(1, back.candidates.size());
		assertEquals("1.21.11", back.mc);
		assertNull(DirectConnect.Ice.parse("{\"v\":2,\"k\":\"AAAAAAAAAAAAAAAAAAAAAA==\"}"));
		assertNull(DirectConnect.Ice.parse("{\"v\":1,\"k\":\"AAAA\"}"), "Nonce zu kurz");
		assertNull(DirectConnect.Ice.parse("not json"));
		DirectConnect.Ice noHost = DirectConnect.Ice.parse("{\"v\":1,\"k\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"c\":[\"evil.example:1\",\"1.2.3.4:5\"]}");
		assertEquals(1, noHost.candidates.size(), "nur IP-Literale");
	}

	// --- Login-Mitlesen ---

	static byte[] varInt(int v) {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		while ((v & ~0x7F) != 0) {
			o.write((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		o.write(v);
		return o.toByteArray();
	}

	static byte[] frame(byte[]... parts) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		for (byte[] p : parts) body.write(p, 0, p.length);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] len = varInt(body.size());
		out.write(len, 0, len.length);
		byte[] b = body.toByteArray();
		out.write(b, 0, b.length);
		return out.toByteArray();
	}

	static byte[] str(String s) {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		byte[] l = varInt(b.length);
		byte[] out = new byte[l.length + b.length];
		System.arraycopy(l, 0, out, 0, l.length);
		System.arraycopy(b, 0, out, l.length, b.length);
		return out;
	}

	static byte[] login(int nextState, String name) {
		byte[] hs = frame(varInt(0), varInt(774), str("127.84.82.83"), new byte[] { 0x63, (byte) 0xDD }, varInt(nextState));
		byte[] ls = frame(varInt(0), str(name), new byte[16]);
		byte[] all = new byte[hs.length + ls.length];
		System.arraycopy(hs, 0, all, 0, hs.length);
		System.arraycopy(ls, 0, all, hs.length, ls.length);
		return all;
	}

	static LoginSniffer.Policy guestPolicy() {
		return name -> name.equalsIgnoreCase("Bob") && !name.equalsIgnoreCase("Host");
	}

	@Test
	void loginSnifferAllowsExpectedNameAndBlocksHostImpersonation() {
		assertEquals(LoginSniffer.Verdict.PASS, new LoginSniffer(guestPolicy()).feed(login(2, "Bob"), 0, login(2, "Bob").length));
		LoginSniffer host = new LoginSniffer(guestPolicy());
		assertEquals(LoginSniffer.Verdict.REJECT, host.feed(login(2, "Host"), 0, login(2, "Host").length));
		assertEquals("Host", host.name());
		assertEquals(LoginSniffer.Verdict.REJECT, new LoginSniffer(guestPolicy()).feed(login(2, "Mallory"), 0, login(2, "Mallory").length));
		// Transfer (1.20.5+) wie Login
		assertEquals(LoginSniffer.Verdict.PASS, new LoginSniffer(guestPolicy()).feed(login(3, "bob"), 0, login(3, "bob").length));
	}

	@Test
	void loginSnifferHandlesSplitsStatusAndGarbage() {
		byte[] all = login(2, "Bob");
		LoginSniffer s = new LoginSniffer(guestPolicy());
		for (int i = 0; i < all.length - 1; i++) assertEquals(LoginSniffer.Verdict.MORE, s.feed(all, i, 1));
		assertEquals(LoginSniffer.Verdict.PASS, s.feed(all, all.length - 1, 1));
		byte[] status = frame(varInt(0), varInt(47), str("x"), new byte[2], varInt(1));
		assertEquals(LoginSniffer.Verdict.PASS, new LoginSniffer(guestPolicy()).feed(status, 0, status.length));
		assertEquals(LoginSniffer.Verdict.PASS, new LoginSniffer(guestPolicy()).feed(new byte[] { (byte) 0xFE, 1 }, 0, 2));
		byte[] junk = { 5, 9, 9, 9, 9, 9 };
		assertEquals(LoginSniffer.Verdict.REJECT, new LoginSniffer(guestPolicy()).feed(junk, 0, junk.length));
		byte[] badName = login(2, "Bob Space");
		assertEquals(LoginSniffer.Verdict.REJECT, new LoginSniffer(n -> true).feed(badName, 0, badName.length));
		byte[] huge = new byte[5000];
		huge[0] = (byte) 0x80;
		huge[1] = (byte) 0x80;
		assertEquals(LoginSniffer.Verdict.REJECT, new LoginSniffer(n -> true).feed(huge, 0, huge.length));
	}

	// --- Rechte ---

	@Test
	void rightsMapToVanillaModes() {
		assertEquals("survival", PlayerRights.DEFAULT.gameMode("survival"));
		assertEquals("creative", PlayerRights.DEFAULT.gameMode("creative"));
		assertEquals("adventure", PlayerRights.DEFAULT.withBuild(false).gameMode("creative"));
		assertEquals("spectator", PlayerRights.DEFAULT.withSpectator(true).gameMode("survival"));
		assertEquals("spectator", PlayerRights.DEFAULT.withSpectator(true).withBuild(false).gameMode("survival"),
				"Zuschauer vor Bauen");
		assertTrue(PlayerRights.DEFAULT.isDefault());
		assertFalse(PlayerRights.DEFAULT.withOp(true).isDefault());
		assertEquals(PlayerRights.DEFAULT.withOp(true), new PlayerRights(true, false, true));
	}

	// --- Öffentlicher Link: Warn-Dialog ---

	@Test
	void publicLinkGateNeedsFreshConfirmationEveryTime() {
		PublicLink.Gate g = new PublicLink.Gate();
		assertFalse(g.canActivate(), "ab Werk nicht aktivierbar");
		g.toggle();
		assertTrue(g.canActivate());
		g.toggle();
		assertFalse(g.canActivate(), "Häkchen wieder weg");
		g.setUnderstood(true);
		assertTrue(g.consume());
		assertFalse(g.canActivate(), "ein Dialog = eine Aktivierung");
		g.setUnderstood(true);
		assertFalse(g.canActivate(), "verbrauchter Dialog lässt sich nicht erneut bestätigen");
		assertFalse(new PublicLink.Gate().canActivate(), "jeder neue Dialog beginnt unbestätigt");
	}

	@Test
	void publicLinkRefusesWithoutConfirmationOrOpenWorld() {
		Hosting h = new Hosting(new HostingApi(null, "http://127.0.0.1:1"), new NoBackend());
		PublicLink link = h.publicLink();
		assertFalse(link.activate(new PublicLink.Gate()), "ohne Häkchen");
		PublicLink.Gate g = new PublicLink.Gate();
		g.setUnderstood(true);
		assertFalse(link.activate(g), "ohne offene Welt");
		assertTrue(g.canActivate(), "abgelehnt → Dialog bleibt nutzbar");
		assertFalse(link.active());
		assertEquals(PublicLink.State.OFF, link.state());
	}

	static final class NoBackend implements Hosting.Backend {
		@Override
		public dev.theredstonee.trsclient.core.social.Toasts toasts() {
			return null;
		}

		@Override
		public void unauthorized(String token) {
		}

		@Override
		public List<Hosting.Friend> friends() {
			return null;
		}

		@Override
		public void wantFriends() {
		}
	}

	// --- API-Antworten ---

	static final class FakeHttp implements Http {
		final List<Request> requests = new ArrayList<Request>();
		int status = 200;
		String body = "{}";

		@Override
		public Response send(Request request) {
			requests.add(request);
			return new Response(status, new LinkedHashMap<String, String>(), body.getBytes(StandardCharsets.UTF_8));
		}
	}

	static final String TOKEN = "trsr1.eyJ2IjoxfQ.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	@Test
	void createParsesRoomAndConnectInfo() throws Exception {
		FakeHttp http = new FakeHttp();
		http.status = 201;
		http.body = "{\"room\":{\"id\":\"h0123456789abcdef0123\",\"code\":\"K7QM2X\",\"name\":\"Insel\u0007\",\"host\":{\"uuid\":\"" + HOST
				+ "\",\"name\":\"Theredstonee\"},\"mcVersion\":\"1.21.11\",\"loader\":\"fabric\",\"maxPlayers\":42,\"gameMode\":\"creative\","
				+ "\"pvp\":false,\"cheats\":true,\"open\":true,\"visibility\":\"friends\",\"players\":1,\"members\":[{\"uuid\":\"" + GUEST
				+ "\",\"name\":\"Bob\",\"state\":\"requested\"},{\"uuid\":\"zz\",\"name\":\"x\",\"state\":\"accepted\"}]},"
				+ "\"role\":\"host\",\"relay\":{\"host\":\"relay.theredstonee.de\",\"tcpPort\":25503,\"udpPort\":25504,\"token\":\"" + TOKEN
				+ "\"},\"stun\":[\"relay.theredstonee.de:25504\",\"bad host:1\"]}";
		HostingApi api = new HostingApi(http, "http://api");
		HostingApi.Settings s = new HostingApi.Settings();
		s.name = "Insel";
		HostingApi.Opened o = api.create("tok", s);
		assertEquals("K7Q-M2X", o.room.prettyCode());
		assertEquals("Insel", o.room.name, "Steuerzeichen raus");
		assertEquals(10, o.room.maxPlayers, "auf 2–10 begrenzt");
		assertEquals("creative", o.room.gameMode);
		assertEquals(1, o.room.members.size(), "ungültige UUID fällt weg");
		assertEquals("requested", o.room.member(GUEST).state);
		assertEquals("relay.theredstonee.de", o.connect.relayHost);
		assertEquals(Collections.singletonList("relay.theredstonee.de:25504"), o.connect.stun);
		assertFalse(o.connect.toString().contains(TOKEN), "Token nie im toString");
		assertEquals("POST", http.requests.get(0).method);
		assertEquals("Bearer tok", http.requests.get(0).headers.get("Authorization"));
	}

	@Test
	void joinDistinguishesAcceptedAndRequestedAndValidatesCode() throws Exception {
		FakeHttp http = new FakeHttp();
		HostingApi api = new HostingApi(http, "http://api");
		http.status = 202;
		http.body = "{\"status\":\"requested\",\"room\":{\"id\":\"h0123456789abcdef0123\",\"name\":\"W\",\"host\":{\"uuid\":\"" + HOST
				+ "\",\"name\":\"H\"},\"mcVersion\":\"1.21.11\",\"loader\":\"fabric\",\"maxPlayers\":4,\"players\":1,\"myState\":\"requested\"}}";
		HostingApi.Joined j = api.join("t", null, "k7q-m2x");
		assertFalse(j.accepted);
		assertEquals("requested", j.room.myState);
		assertTrue(new String(http.requests.get(0).body, StandardCharsets.UTF_8).contains("\"code\":\"K7QM2X\""));
		ApiException e = assertThrows(ApiException.class, () -> api.join("t", null, "ABC-DE0"));
		assertEquals("invalid_code", e.code());
		http.status = 409;
		http.body = "{\"error\":{\"code\":\"world_closed\"}}";
		assertThrows(ApiException.class, () -> api.join("t", "h0123456789abcdef0123", null));
	}

	@Test
	void codesAndCompatibility() {
		assertEquals("ABCDEF", Rooms.normalizeCode(" abc-def "));
		assertNull(Rooms.normalizeCode("ABCDE0"), "0 gibt es nicht");
		assertNull(Rooms.normalizeCode("ABCDEFG"));
		assertNull(Rooms.normalizeCode("ABC"));
		assertTrue(Rooms.validRoomId("h0123456789abcdef0123"));
		assertFalse(Rooms.validRoomId("h0123"));
		assertFalse(Rooms.validRoomId("../../x"));
	}

	@Test
	void launcherJoinIsValidated() {
		dev.theredstonee.trsclient.core.link.TrsLink.JoinDto j = new dev.theredstonee.trsclient.core.link.TrsLink.JoinDto();
		j.roomId = "h0123456789abcdef0123";
		j.code = null;
		j.name = "Insel";
		j.mcVersion = "1.21.11";
		j.loader = "fabric";
		j.host = new dev.theredstonee.trsclient.core.link.TrsLink.UserDto();
		j.host.uuid = HOST;
		j.host.name = "Theredstonee";
		Rooms.Room r = Hosting.fromLauncher(j);
		assertNotNull(r);
		assertEquals("accepted", r.myState);
		assertEquals(HOST, r.hostUuid);
		j.roomId = "h../../";
		assertNull(Hosting.fromLauncher(j));
		assertNull(Hosting.fromLauncher(null));
	}

	@Test
	void hostingEventsKeepRawJson() {
		// "from" ist bei hosting_signal eine UUID als Text (sonst {uuid,name}) – darf das Ereignis nicht verwerfen.
		dev.theredstonee.trsclient.core.social.MeEvent e = dev.theredstonee.trsclient.core.social.MeEvent.parse("hosting_signal",
				"{\"roomId\":\"h0123456789abcdef0123\",\"from\":\"" + GUEST + "\",\"kind\":\"offer\",\"sid\":\"s1\",\"data\":\"{}\"}", "7");
		assertNotNull(e);
		assertTrue(e.hostingData.contains("offer"));
		assertEquals("7", e.id);
		assertNull(dev.theredstonee.trsclient.core.social.MeEvent.parse("chat_message", "{\"from\":\"x\"}", "8"),
				"Chat-Ereignisse bleiben streng");
	}

	@Test
	void backupZipsWorldWithoutSessionLock() throws IOException {
		java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("trs-backup");
		java.nio.file.Path world = tmp.resolve("saves").resolve("Meine Welt");
		java.nio.file.Files.createDirectories(world.resolve("region"));
		java.nio.file.Files.write(world.resolve("level.dat"), new byte[] { 1, 2, 3 });
		java.nio.file.Files.write(world.resolve("session.lock"), new byte[] { 9 });
		java.nio.file.Files.write(world.resolve("region").resolve("r.0.0.mca"), new byte[1000]);
		java.nio.file.Path zip = WorldBackup.zip(world, tmp.resolve("backups"), "Meine Welt");
		assertTrue(zip.getFileName().toString().endsWith("_Meine Welt.zip"));
		List<String> names = new ArrayList<String>();
		try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(zip))) {
			java.util.zip.ZipEntry e;
			while ((e = in.getNextEntry()) != null) names.add(e.getName());
		}
		Collections.sort(names);
		assertEquals(java.util.Arrays.asList("Meine Welt/level.dat", "Meine Welt/region/r.0.0.mca"), names);
	}
}
