package dev.theredstonee.trsclient.core.cosmetic;

import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1-Vorlagen kommen von der API (oder aus dem Cache), nie blockierend im Render-Thread. */
class CosmeticModelsTest {
	static final OnlineConfig CONFIG = new OnlineConfig(true, "https://trs-launcher.theredstonee.de", OnlineConfig.DEFAULT_SESSION);
	static final OnlineConfig LOCAL = new OnlineConfig(true, "http://127.0.0.1:8790", OnlineConfig.DEFAULT_SESSION);
	/** Synthetischer Würfel mit der Vorlagen-ID {@code duck} – nicht das Quietscheenten-Modell. */
	static final String JSON = "{\"id\":\"duck\",\"kind\":\"model\",\"slot\":\"hat\",\"textureWidth\":64,\"textureHeight\":32,"
			+ "\"cubes\":[{\"from\":[0,0,0],\"to\":[1,1,1],\"uv\":[0,0],\"attach\":\"head\"}]}";
	static final byte[] BODY = JSON.getBytes(StandardCharsets.UTF_8);
	static final String HASH = "abc123abc123";
	static final String URL = "https://trs-launcher.theredstonee.de/v1/cosmetics/rubber_duck/template.json?v=" + HASH;
	static final String TEXTURE = "https://trs-launcher.theredstonee.de/v1/cosmetics/rubber_duck.png?v=1";

	@TempDir
	Path dir;

	@BeforeEach
	void clean() {
		CosmeticModels.reset();
	}

	@AfterEach
	void done() {
		CosmeticModels.reset();
	}

	@Test
	void duckTemplateIsNotBundled() {
		assertNull(CosmeticModels.bundled("duck"));
		assertNull(CosmeticModels.class.getResource("/assets/trsclient/cosmetics/duck.json"));
		assertTrue(CosmeticModels.supported("duck"));
		assertFalse(CosmeticModels.supported("crown"));
	}

	@Test
	void loadsFromTemplateUrlAndStoresTheHash() throws Exception {
		AtomicReference<String> seen = new AtomicReference<String>();
		CosmeticModel model = CosmeticModels.load(dir, "rubber_duck", "duck", URL, CONFIG, url -> {
			seen.set(url);
			return BODY;
		});
		assertNotNull(model);
		assertEquals("duck", model.id);
		assertEquals(URL, seen.get());
		assertEquals(JSON, new String(Files.readAllBytes(dir.resolve("rubber_duck.json")), StandardCharsets.UTF_8));
		assertEquals(HASH, new String(Files.readAllBytes(dir.resolve("rubber_duck.hash")), StandardCharsets.UTF_8).trim());
	}

	@Test
	void fallsBackToTheFixedPath() {
		AtomicReference<String> seen = new AtomicReference<String>();
		assertNotNull(CosmeticModels.load(dir, "rubber_duck", "duck", null, CONFIG, url -> {
			seen.set(url);
			return BODY;
		}));
		assertEquals(CONFIG.apiBase() + "/v1/cosmetics/rubber_duck/template.json", seen.get());
	}

	@Test
	void allowsLocalhostAndDropsForeignTemplateUrls() {
		AtomicReference<String> seen = new AtomicReference<String>();
		assertNotNull(CosmeticModels.load(dir, "rubber_duck", "duck", null, LOCAL, url -> {
			seen.set(url);
			return BODY;
		}));
		assertEquals("http://127.0.0.1:8790/v1/cosmetics/rubber_duck/template.json", seen.get());

		seen.set(null);
		assertNotNull(CosmeticModels.load(dir, "other_duck", "duck", "https://evil.example/template.json", CONFIG, url -> {
			seen.set(url);
			return BODY;
		}));
		assertEquals(CONFIG.apiBase() + "/v1/cosmetics/other_duck/template.json", seen.get());

		AtomicInteger calls = new AtomicInteger();
		assertNull(CosmeticModels.load(dir, "../duck", "duck", "https://evil.example/template.json", CONFIG, url -> {
			calls.incrementAndGet();
			return BODY;
		}));
		assertEquals(0, calls.get());
		assertFalse(CosmeticModels.allowed("http://10.0.0.8/v1/cosmetics/rubber_duck/template.json", LOCAL));
		assertFalse(CosmeticModels.allowed("https://evil.example/template.json", CONFIG));
		assertTrue(CosmeticModels.allowed(URL, CONFIG));
		assertNull(CosmeticModels.hashOf(CONFIG.apiBase() + "/v1/cosmetics/rubber_duck/template.json"));
		assertNull(CosmeticModels.hashOf(URL.replace(HASH, "nope")));
	}

	@Test
	void skipsOversizedInvalidAndMismatchedTemplates() {
		AtomicInteger calls = new AtomicInteger();
		assertNull(CosmeticModels.load(dir, "rubber_duck", "crown", URL, CONFIG, url -> {
			calls.incrementAndGet();
			return BODY;
		}));
		assertEquals(0, calls.get());
		assertNull(CosmeticModels.load(dir, "rubber_duck", "duck", URL, CONFIG, url -> new byte[CosmeticModels.MAX_BYTES + 1]));
		assertNull(CosmeticModels.load(dir, "rubber_duck", "duck", URL, CONFIG, url -> "{".getBytes(StandardCharsets.UTF_8)));
		assertNull(CosmeticModels.load(dir, "rubber_duck", "duck", URL, CONFIG,
				url -> "{\"id\":\"crown\",\"kind\":\"model\",\"slot\":\"hat\",\"textureWidth\":64,\"textureHeight\":32,\"cubes\":[{\"from\":[0,0,0],\"to\":[1,1,1],\"uv\":[0,0],\"attach\":\"head\"}]}"
						.getBytes(StandardCharsets.UTF_8)));
		assertFalse(Files.exists(dir.resolve("rubber_duck.json")));
	}

	@Test
	void reusesTheCacheOfflineAndRefetchesWhenTheHashChanges() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		assertNotNull(CosmeticModels.load(dir, "rubber_duck", "duck", URL, CONFIG, url -> {
			calls.incrementAndGet();
			return BODY;
		}));
		CosmeticModels.clearMemory();
		assertNotNull(CosmeticModels.load(dir, "rubber_duck", "duck", URL, CONFIG, url -> {
			calls.incrementAndGet();
			return BODY;
		}));
		assertEquals(1, calls.get(), "gleicher Hash kommt von der Platte");

		CosmeticModels.clearMemory();
		String next = URL.replace(HASH, "bbb222bbb222");
		assertNotNull(CosmeticModels.load(dir, "rubber_duck", "duck", next, CONFIG, url -> {
			calls.incrementAndGet();
			throw new IOException("offline");
		}));
		assertEquals(2, calls.get(), "neuer Hash wird versucht");
		assertEquals(HASH, new String(Files.readAllBytes(dir.resolve("rubber_duck.hash")), StandardCharsets.UTF_8).trim(),
				"offline bleibt der alte Cache liegen");

		CosmeticModels.clearMemory();
		assertNotNull(CosmeticModels.load(dir, "rubber_duck", "duck", next, CONFIG, url -> {
			calls.incrementAndGet();
			return BODY;
		}));
		assertEquals(3, calls.get());
		assertEquals("bbb222bbb222", new String(Files.readAllBytes(dir.resolve("rubber_duck.hash")), StandardCharsets.UTF_8).trim());
	}

	@Test
	void getDoesNotBlockTheRenderThread() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		CosmeticModels.install(dir, CONFIG, url -> {
			calls.incrementAndGet();
			started.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout");
			} catch (InterruptedException e) {
				throw new IOException("interrupted");
			}
			return BODY;
		}, null);
		HatInfo hat = HatInfo.of("rubber_duck", "duck", TEXTURE, 2, 1, null, URL, CONFIG);
		try {
			long t0 = System.nanoTime();
			assertNull(CosmeticModels.get(hat));
			assertTrue(System.nanoTime() - t0 < 200_000_000L, "Render-Thread hat gewartet");
			assertTrue(started.await(2, TimeUnit.SECONDS));
			assertNull(CosmeticModels.get(hat));
			assertEquals(1, calls.get());
		} finally {
			release.countDown();
		}
		CosmeticModel model = null;
		for (int i = 0; i < 50 && model == null; i++) {
			Thread.sleep(20);
			model = CosmeticModels.get(hat);
		}
		assertNotNull(model);
		assertEquals(1, calls.get());
		assertEquals(HASH, new String(Files.readAllBytes(dir.resolve("rubber_duck.hash")), StandardCharsets.UTF_8).trim());
	}
}
