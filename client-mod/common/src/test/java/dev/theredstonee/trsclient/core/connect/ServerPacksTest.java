package dev.theredstonee.trsclient.core.connect;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server-Ressourcenpakete: merken, vorladen (lokaler HTTP-Server), gleiches Paket beim Wechsel überspringen. */
class ServerPacksTest {
	static final String UUID_A = "3f1a2b4c-0000-4000-8000-00000000000a";
	static final String UUID_B = "3f1a2b4c-0000-4000-8000-00000000000b";

	Path dir;
	HttpServer http;
	byte[] pack;
	String sha1;
	final AtomicInteger hits = new AtomicInteger();
	volatile boolean fastSwitch = true;
	volatile boolean preload = true;

	@BeforeEach
	void setUp() throws Exception {
		dir = Files.createTempDirectory("trs-packs");
		Files.createDirectories(dir.resolve("config"));
		pack = new byte[300_000];
		new Random(7).nextBytes(pack);
		sha1 = PackDownloader.hex(MessageDigest.getInstance("SHA-1").digest(pack));
		http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		http.createContext("/pack.zip", ex -> {
			hits.incrementAndGet();
			ex.sendResponseHeaders(200, pack.length);
			try (OutputStream o = ex.getResponseBody()) {
				o.write(pack);
			}
		});
		http.createContext("/moved", ex -> {
			ex.getResponseHeaders().add("Location", "/pack.zip");
			ex.sendResponseHeaders(302, -1);
			ex.close();
		});
		http.createContext("/big", ex -> {
			ex.sendResponseHeaders(200, 10_000_000);
			ex.close();
		});
		http.start();
		FastConnect.reset();
		FastConnect.init(null, () -> true, () -> true, null);
		ServerPacks.setup(dir.resolve("config"), ServerPacks.Era.DOWNLOADS_UUID, 262144000L, Collections.<String, String>emptyMap(),
				() -> fastSwitch, () -> preload);
		ServerPacks.resetForTests();
	}

	@AfterEach
	void tearDown() throws IOException {
		http.stop(0);
		FastConnect.reset();
		Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
	}

	String url(String path) {
		return "http://127.0.0.1:" + http.getAddress().getPort() + path;
	}

	// --- Download ---

	@Test
	void downloadVerifiesSha1AndMovesIntoPlace() throws Exception {
		Path target = dir.resolve("downloads").resolve(UUID_A).resolve(sha1);
		PackDownloader.Result r = PackDownloader.download(url("/pack.zip"), target, sha1, 1_000_000, null, 0, null, true);
		assertTrue(r.ok, r.toString());
		assertArrayEquals(pack, Files.readAllBytes(target));
		assertFalse(Files.exists(target.resolveSibling(sha1 + ".trs-part")));
	}

	@Test
	void wrongHashLeavesNothingBehind() throws Exception {
		Path target = dir.resolve("x").resolve("pack");
		PackDownloader.Result r = PackDownloader.download(url("/pack.zip"), target, "0000000000000000000000000000000000000000",
				1_000_000, null, 0, null, true);
		assertFalse(r.ok);
		assertTrue(r.error.contains("SHA-1"), r.error);
		assertFalse(Files.exists(target));
		assertFalse(Files.exists(target.resolveSibling("pack.trs-part")));
	}

	@Test
	void sizeLimitAndCancelAndPrivateTargets() throws Exception {
		Path target = dir.resolve("y").resolve("pack");
		assertTrue(PackDownloader.download(url("/big"), target, sha1, 1_000_000, null, 0, null, true).error.startsWith("too large"));
		assertEquals("cancelled", PackDownloader.download(url("/pack.zip"), target, sha1, 1_000_000, null, 0, new AtomicBoolean(true), true).error);
		assertEquals("private address", PackDownloader.download(url("/pack.zip"), target, sha1, 1_000_000, null, 0, null, false).error);
		assertEquals("scheme", PackDownloader.download("file:///etc/passwd", target, sha1, 1_000_000, null, 0, null, true).error);
		assertFalse(Files.exists(target));
	}

	@Test
	void redirectsAreFollowedAndChecked() {
		Path target = dir.resolve("z").resolve("pack");
		assertTrue(PackDownloader.download(url("/moved"), target, sha1, 1_000_000, null, 0, null, true).ok);
	}

	@Test
	void throttleLimitsTheRate() {
		Path target = dir.resolve("t").resolve("pack");
		long t0 = System.nanoTime();
		PackDownloader.Result r = PackDownloader.download(url("/pack.zip"), target, sha1, 1_000_000, null, 600_000, null, true);
		long ms = (System.nanoTime() - t0) / 1_000_000L;
		assertTrue(r.ok);
		assertTrue(ms >= 400, "300 KB at 600 KB/s takes about 0.5 s, took " + ms);
	}

	@Test
	void privateAddressDetection() throws Exception {
		assertTrue(PackDownloader.isPrivate(InetAddress.getByName("10.1.2.3")));
		assertTrue(PackDownloader.isPrivate(InetAddress.getByName("192.168.0.1")));
		assertTrue(PackDownloader.isPrivate(InetAddress.getByName("172.16.5.5")));
		assertTrue(PackDownloader.isPrivate(InetAddress.getByName("100.64.0.1")));
		assertTrue(PackDownloader.isPrivate(InetAddress.getByName("127.0.0.1")));
		assertTrue(PackDownloader.isPrivate(InetAddress.getByName("fd00::1")));
		assertTrue(PackDownloader.isPrivate(InetAddress.getByName("fe80::1")));
		assertFalse(PackDownloader.isPrivate(InetAddress.getByName("203.0.113.7")));
		assertFalse(PackDownloader.isPrivate(InetAddress.getByName("2001:db8::1")));
	}

	// --- Ablauf ---

	@Test
	void rememberedOnlyWhenAccepted() {
		assertFalse(ServerPacks.onPush("play.example.net", UUID_A, url("/pack.zip"), sha1));
		assertTrue(ServerPacks.memory().packs("play.example.net").isEmpty(), "not before the player accepted");
		ServerPacks.onStatus(UUID_A, "ACCEPTED");
		List<PackMemory.Pack> l = ServerPacks.memory().packs("Play.Example.net");
		assertEquals(1, l.size());
		assertEquals(sha1, l.get(0).sha1);
		assertEquals(UUID_A, l.get(0).uuid);
		// Abgelehnt → vergessen.
		ServerPacks.onPush("play.example.net", UUID_A, url("/pack.zip"), sha1);
		ServerPacks.onStatus(UUID_A, "DECLINED");
		assertTrue(ServerPacks.memory().packs("play.example.net").isEmpty());
	}

	@Test
	void packsWithoutValidHashAreNeverRemembered() {
		ServerPacks.onPush("play.example.net", UUID_A, url("/pack.zip"), "");
		ServerPacks.onStatus(UUID_A, "ACCEPTED");
		assertTrue(ServerPacks.memory().packs("play.example.net").isEmpty());
	}

	@Test
	void samePackAgainIsSkippedOnlyWhenActive() {
		assertFalse(ServerPacks.onPush("net.example", UUID_A, url("/pack.zip"), sha1));
		ServerPacks.onStatus(UUID_A, "ACCEPTED");
		// Noch nicht geladen → kein Überspringen.
		assertFalse(ServerPacks.onPush("net.example", UUID_A, url("/pack.zip"), sha1));
		ServerPacks.onStatus(UUID_A, "ACCEPTED");
		ServerPacks.onStatus(UUID_A, "DOWNLOADED");
		ServerPacks.onStatus(UUID_A, "SUCCESSFULLY_LOADED");
		// Proxy-Wechsel: dasselbe Paket noch einmal → überspringen.
		assertTrue(ServerPacks.onPush("net.example", UUID_A, url("/pack.zip"), sha1.toUpperCase()));
		// Andere ID, anderer Hash, Schalter aus → Vanilla.
		assertFalse(ServerPacks.onPush("net.example", UUID_B, url("/pack.zip"), sha1));
		assertFalse(ServerPacks.onPush("net.example", UUID_A, url("/pack.zip"), "1111111111111111111111111111111111111111"));
		ServerPacks.onStatus(UUID_A, "SUCCESSFULLY_LOADED");
		fastSwitch = false;
		assertFalse(ServerPacks.onPush("net.example", UUID_A, url("/pack.zip"), "1111111111111111111111111111111111111111"));
	}

	@Test
	void popAndDisconnectEndTheActiveState() {
		ServerPacks.onPush("net.example", UUID_A, url("/pack.zip"), sha1);
		ServerPacks.onStatus(UUID_A, "SUCCESSFULLY_LOADED");
		ServerPacks.onPop(UUID_A);
		assertFalse(ServerPacks.onPush("net.example", UUID_A, url("/pack.zip"), sha1));
		ServerPacks.onStatus(UUID_A, "SUCCESSFULLY_LOADED");
		ServerPacks.onDisconnect();
		assertFalse(ServerPacks.onPush("net.example", UUID_A, url("/pack.zip"), sha1));
		ServerPacks.onStatus(UUID_A, "SUCCESSFULLY_LOADED");
		ServerPacks.onStatus(UUID_A, "FAILED_RELOAD");
		assertFalse(ServerPacks.onPush("net.example", UUID_A, url("/pack.zip"), sha1));
	}

	@Test
	void legacySinglePackNeedsSameUrlAndHash() throws Exception {
		ServerPacks.setup(dir.resolve("config"), ServerPacks.Era.HASH, 52428800L, null, () -> true, () -> true);
		ServerPacks.resetForTests();
		ServerPacks.onPush("mc.example", null, url("/pack.zip"), sha1);
		ServerPacks.onStatus(null, "ACCEPTED");
		ServerPacks.onStatus(null, "SUCCESSFULLY_LOADED");
		assertTrue(ServerPacks.onPush("mc.example", null, url("/pack.zip"), sha1));
		assertFalse(ServerPacks.onPush("mc.example", null, url("/other.zip"), sha1));
		assertEquals(dir.toAbsolutePath().resolve("server-resource-packs").resolve(sha1), ServerPacks.cachePath(null, url("/pack.zip"), sha1));
	}

	@Test
	void cachePathsMatchVanilla() throws Exception {
		Path game = dir.toAbsolutePath();
		assertEquals(game.resolve("downloads").resolve(UUID_A).resolve(sha1), ServerPacks.cachePath(UUID_A, "http://x/p.zip", sha1.toUpperCase()));
		assertNull(ServerPacks.cachePath(null, "http://x/p.zip", sha1), "1.20.3+ needs the pack id");
		assertNull(ServerPacks.cachePath(UUID_A, "http://x/p.zip", "nothex"));
		ServerPacks.setup(dir.resolve("config"), ServerPacks.Era.URL_SHA1_RAW, 52428800L, null, () -> true, () -> true);
		// Vanilla 1.14–1.18: DigestUtils.sha1Hex(url) – sha1("http://x/p.zip").
		String expected = PackDownloader.hex(MessageDigest.getInstance("SHA-1").digest("http://x/p.zip".getBytes("UTF-8")));
		assertEquals(game.resolve("server-resource-packs").resolve(expected), ServerPacks.cachePath(null, "http://x/p.zip", sha1));
	}

	@Test
	void preloadPutsThePackWhereVanillaLooksAndReusesIt() throws Exception {
		ServerPacks.onPush("127.0.0.1:25565", UUID_A, url("/pack.zip"), sha1);
		ServerPacks.onStatus(UUID_A, "ACCEPTED");
		Path target = ServerPacks.cachePath(UUID_A, url("/pack.zip"), sha1);
		assertFalse(Files.exists(target));
		ServerPacks.preloadNow("127.0.0.1:25565", ServerPacks.memory().packs("127.0.0.1:25565").get(0), target, false, true, new AtomicBoolean());
		assertArrayEquals(pack, Files.readAllBytes(target));
		assertEquals(1, hits.get());
		// Zweites Mal: Datei geprüft, kein Download.
		ServerPacks.preloadNow("127.0.0.1:25565", ServerPacks.memory().packs("127.0.0.1:25565").get(0), target, false, true, new AtomicBoolean());
		assertEquals(1, hits.get());
		// Server schickt dasselbe Paket mit neuer ID → Datei wird lokal an den neuen Platz kopiert.
		ServerPacks.onPush("127.0.0.1:25565", UUID_B, url("/pack.zip"), sha1);
		Path other = ServerPacks.cachePath(UUID_B, url("/pack.zip"), sha1);
		assertArrayEquals(pack, Files.readAllBytes(other));
		assertEquals(1, hits.get(), "no second download");
	}

	@Test
	void privateServersMayUsePrivatePackHosts() {
		assertTrue(ServerPacks.privateServer("127.0.0.1"));
		assertTrue(ServerPacks.privateServer("192.168.1.20:25565"));
		assertTrue(ServerPacks.privateServer("localhost"));
		assertFalse(ServerPacks.privateServer("203.0.113.7"));
		assertFalse(ServerPacks.privateServer("unknown.example"));
	}

	@Test
	void ownFilesAreCappedOldestFirst() throws Exception {
		PackMemory m = new PackMemory(dir.resolve("config"));
		Path a = dir.resolve("a.bin");
		Path b = dir.resolve("b.bin");
		Files.write(a, new byte[1000]);
		Files.write(b, new byte[1000]);
		m.ownFile(a, sha1, 1000, 1);
		m.ownFile(b, sha1, 1000, 2);
		assertEquals(2000, m.ownBytes());
		List<String> deleted = m.makeRoom(500, 2000);
		assertEquals(1, deleted.size());
		assertFalse(Files.exists(a), "oldest deleted");
		assertTrue(Files.exists(b));
		assertEquals(b.toAbsolutePath().normalize(), m.ownFileWithHash(sha1));
		// Gedächtnis überlebt einen Neustart.
		m.remember("srv", url("/pack.zip"), sha1, UUID_A, 5);
		PackMemory again = new PackMemory(dir.resolve("config"));
		assertNotNull(again.packs("srv").get(0));
		assertEquals(UUID_A, again.packs("srv").get(0).uuid);
	}
}
