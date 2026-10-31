package dev.theredstonee.trsclient.core.waypoint.live;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.GameSession;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import dev.theredstonee.trsclient.core.online.PlayerEventStream;
import dev.theredstonee.trsclient.core.online.TrsApi;
import dev.theredstonee.trsclient.core.social.MeEvent;
import dev.theredstonee.trsclient.core.social.MeStream;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integrationstest gegen die ECHTE TRS API (§41) – nur mit
 * {@code -Dtrsclient.test.wpshare.api=http://127.0.0.1:<port> -Dtrsclient.test.wpshare.mojang=http://127.0.0.1:<port>}
 * (lokal gestarteter Server mit leerem DATA_DIR und Mojang-Attrappe für die Testkonten Alice_T/Bob_T); sonst übersprungen.
 * Zwei Nutzer mit den echten Client-Klassen: Anmeldung, Freundschaft, Freigabe, Sitzungen in derselben Server-Welt,
 * Positionen über /v1/events/me, geteilte Liste mit rev-Konflikt, changed/revoked, Entfreunden räumt auf.
 */
@EnabledIfSystemProperty(named = "trsclient.test.wpshare.api", matches = "http://127\\.0\\.0\\.1:\\d+")
class LiveShareApiIT {
	static final String A = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";
	static final String B = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2";

	final String api = System.getProperty("trsclient.test.wpshare.api");
	final String mojang = System.getProperty("trsclient.test.wpshare.mojang", api);
	final Http http = new Http.UrlConnection("TRS-Client-IT");
	final TrsApi trs = new TrsApi(http, new OnlineConfig(true, api, mojang));
	final List<Client> clients = new ArrayList<Client>();

	/** Ein Spieler: Token, Konto-Stream, Live-Teilen mit Attrappe der Spielwelt. */
	final class Client {
		final String uuid;
		final String token;
		final MeStream stream;
		final LiveShareTest.FakeEnv env;
		final TrsModules modules = new TrsModules();
		final LiveShare live;
		final List<String> events = new ArrayList<String>();
		final List<String> unauthorized = new ArrayList<String>();

		Client(String uuid, String name, Path dir) throws Exception {
			this.uuid = uuid;
			this.token = trs.login(new GameSession(uuid, name, "test-access-token")).token;
			this.stream = new MeStream(new PlayerEventStream.UrlOpener("TRS-Client-IT"), api);
			this.env = new LiveShareTest.FakeEnv(new WaypointStore(dir.resolve(name + ".json")));
			modules.waypoints.setEnabled(true);
			this.live = new LiveShare(new LiveApi(http, api), () -> modules, env, Runnable::run, t -> unauthorized.add(t));
			clients.add(this);
		}

		void pump(long now) {
			stream.update(now, token, true);
			List<MeEvent> out = new ArrayList<MeEvent>();
			stream.drain(out);
			for (MeEvent e : out) {
				events.add(e.type);
				live.event(e);
			}
			live.tick(now, token, uuid);
		}

		boolean saw(String type) {
			return events.contains(type);
		}
	}

	@AfterEach
	void tearDown() {
		for (Client c : clients) {
			try {
				c.live.shutdown();
			} catch (RuntimeException ignored) {
				// egal
			}
			c.stream.stop();
		}
	}

	private void pumpAll() throws InterruptedException {
		long now = System.currentTimeMillis();
		for (Client c : clients) c.pump(now);
		Thread.sleep(50);
	}

	private void waitFor(String what, long ms, BooleanSupplier ok) throws InterruptedException {
		long until = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < until) {
			pumpAll();
			if (ok.getAsBoolean()) return;
		}
		StringBuilder state = new StringBuilder();
		for (Client c : clients) {
			state.append(" | ").append(c.uuid, 0, 4).append(": events=").append(c.events).append(" owners=").append(c.live.owners().size())
					.append(" peers send=").append(c.live.peersSend()).append(" receive=").append(c.live.peersReceive());
		}
		fail("Zeitüberschreitung: " + what + state);
	}

	@Test
	void twoFriendsShareLive(@TempDir Path dir) throws Exception {
		Client a = new Client(A, "Alice_T", dir);
		Client b = new Client(B, "Bob_T", dir);
		// Freundschaft: beide fragen an → die zweite Anfrage macht sie zu Freunden.
		assertEquals("sent", trs.requestFriend(a.token, B));
		assertEquals("accepted", trs.requestFriend(b.token, A));
		waitFor("Konto-Streams verbunden", 10_000, () -> a.stream.connected() && b.stream.connected());

		// Fremde UUID → 403/404 (nicht befreundet).
		LiveApi raw = new LiveApi(http, api);
		try {
			raw.grant(a.token, "cccccccccccccccccccccccccccccccc", true);
			fail("Freigabe an Fremde muss scheitern");
		} catch (ApiException e) {
			assertTrue(e.status() == 403 || e.status() == 404, e.toString());
		}

		// Alice gibt Bob frei (über den Client wie im Freunde-Bildschirm).
		a.live.wantGrants();
		b.live.wantGrants();
		waitFor("Freigaben geladen", 10_000, () -> a.live.grantsLoaded() && b.live.grantsLoaded());
		a.live.setGrant(B, true);
		pumpAll();
		assertTrue(a.live.granted(B));
		assertNull(a.live.takeNotice());
		b.live.refreshGrants();
		waitFor("Bob sieht „teilt mit dir“", 10_000, () -> b.live.grantedToMe(A));

		// Beide auf demselben Server.
		a.env.key = "mp:Play.Example.NET:25565";
		b.env.key = "mp:play.example.net";
		waitFor("Sitzungen + Peers", 15_000, () -> a.live.active() && b.live.active() && a.live.peersSend() == 1
				&& b.live.peersReceive().contains(A));
		assertEquals(0, b.live.peersSend(), "Bob hat Alice nichts freigegeben");

		// Alice teilt einen Wegpunkt → Bob bekommt wpshare_changed und holt die Liste.
		Waypoint basis = a.env.store.add(a.env.storeKey(), new Waypoint("Basis", 100, 64, -20, "minecraft:overworld", 0xE0281E));
		basis.shared = true;
		waitFor("Bob sieht Alices Wegpunkt", 20_000, () -> {
			Owner o = b.live.owner(A);
			return o != null && o.waypoints().size() == 1 && "Basis".equals(o.waypoints().get(0).name);
		});
		assertTrue(b.saw("wpshare_changed"), b.events.toString());
		Owner owner = b.live.owner(A);
		assertEquals("Alice_T", owner.name());
		assertTrue(owner.rev() >= 1);
		FriendWaypoint fw = owner.waypoints().get(0);
		assertEquals(100, fw.x);
		assertEquals("minecraft:overworld", fw.dimension);
		assertEquals(0xE0281E, fw.color);

		// Position: Alice bewegt sich → Bob bekommt wpshare_position.
		a.env.x = 120.53;
		a.env.z = -33.25;
		waitFor("Bob sieht Alices Position", 20_000, () -> {
			Owner o = b.live.owner(A);
			return o != null && o.position() != null && Math.abs(o.position().x - 120.53) < 0.1;
		});
		assertTrue(b.saw("wpshare_position"), b.events.toString());
		assertEquals("minecraft:overworld", b.live.owner(A).position().dimension);
		// Bob gibt Alice nichts frei → Alice bekommt keine Positionen von Bob.
		assertFalse(a.saw("wpshare_position"));

		// rev-Konflikt: ein „zweiter PC“ von Alice ändert die Liste mit dem richtigen rev …
		String own = raw.ownWaypoints(a.token, LiveWorld.server("play.example.net"));
		long rev = LiveJson.rev(own);
		assertTrue(rev >= 1, own);
		String other = LiveJson.putBody(LiveWorld.server("play.example.net"), rev,
				LiveJson.sharedList(java.util.Collections.singletonList(shared("Zweiter PC", 5, 70, 5)), true));
		raw.putWaypoints(a.token, other);
		// … ein alter rev scheitert mit 409 und dem aktuellen rev im Fehler …
		try {
			raw.putWaypoints(a.token, LiveJson.putBody(LiveWorld.server("play.example.net"), rev, new com.google.gson.JsonArray()));
			fail("alter rev muss 409 ergeben");
		} catch (ApiException e) {
			assertEquals(409, e.status());
			assertEquals("rev_mismatch", e.code());
			assertEquals(rev + 1, LiveJson.errorRev(e.body()), e.body());
		}
		// … und Alices Client (mit altem rev) gewinnt nach einer Änderung über den 409-Weg.
		basis.name = "Basis neu";
		waitFor("Alices Liste wieder oben", 20_000, () -> {
			try {
				String now = raw.ownWaypoints(a.token, LiveWorld.server("play.example.net"));
				return now.contains("Basis neu") && !now.contains("Zweiter PC");
			} catch (Exception ex) {
				return false;
			}
		});
		waitFor("Bob sieht die Änderung", 20_000, () -> {
			Owner o = b.live.owner(A);
			return o != null && !o.waypoints().isEmpty() && "Basis neu".equals(o.waypoints().get(0).name);
		});

		// Freigabe entziehen → Bob bekommt wpshare_revoked und vergisst Alice.
		a.live.setGrant(B, false);
		waitFor("Bob vergisst Alice", 15_000, () -> b.live.owner(A) == null);
		assertTrue(b.saw("wpshare_revoked"), b.events.toString());

		// Wieder freigeben, dann entfreunden → aufgeräumt (beide Richtungen), Bob bekommt revoked.
		a.live.setGrant(B, true);
		waitFor("wieder sichtbar", 20_000, () -> b.live.owner(A) != null);
		int revokedBefore = count(b.events, "wpshare_revoked");
		trs.removeFriend(a.token, B);
		waitFor("Entfreunden räumt auf", 15_000, () -> b.live.owner(A) == null && count(b.events, "wpshare_revoked") > revokedBefore);
		LiveJson.Grants g = LiveJson.grants(raw.grants(a.token));
		assertNotNull(g);
		assertFalse(g.grants.contains(B), "Freigabe weg");
		LiveJson.Grants gb = LiveJson.grants(raw.grants(b.token));
		assertFalse(gb.grantedToMe.contains(A));
		assertTrue(a.unauthorized.isEmpty() && b.unauthorized.isEmpty());

		// Welt verlassen → DELETE /session; GET /friends danach 409 not_in_world.
		a.env.inWorld = false;
		pumpAll();
		pumpAll();
		try {
			raw.friends(a.token, LiveWorld.server("play.example.net"));
			fail("ohne Sitzung muss GET /friends scheitern");
		} catch (ApiException e) {
			assertEquals("not_in_world", e.code());
		}
	}

	private static Waypoint shared(String name, int x, int y, int z) {
		Waypoint w = new Waypoint(name, x, y, z, "minecraft:overworld", 0x123456);
		w.shared = true;
		return w;
	}

	private static int count(List<String> list, String type) {
		int n = 0;
		for (String s : list) if (s.equals(type)) n++;
		return n;
	}
}
