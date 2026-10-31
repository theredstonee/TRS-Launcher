package dev.theredstonee.trsclient.core.account;

import dev.theredstonee.trsclient.core.online.Http;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FaceCacheRetryTest {
	static final String UUID = "069a79f444e94726a5befca90e38aaf5";
	static final String SKIN = "https://textures.minecraft.net/texture/abc123";

	static byte[] skinPng() throws IOException {
		BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) img.setRGB(x, y, 0xFF336699);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	private static int[] waitFace(FaceCache cache) throws InterruptedException {
		for (int i = 0; i < 200 && !cache.settled(UUID); i++) Thread.sleep(10);
		return cache.face(UUID, SKIN);
	}

	@Test
	void retriesAfterAFailureWithBackoff() throws Exception {
		final byte[] png = skinPng();
		final AtomicInteger calls = new AtomicInteger();
		Http http = request -> {
			int n = calls.incrementAndGet();
			// Erst zweimal gedrosselt (wie Mojang bei derselben UUID), dann klappt es.
			if (n <= 2) return new Http.Response(429, Collections.<String, String>emptyMap(), new byte[0]);
			return new Http.Response(200, Collections.<String, String>emptyMap(), png);
		};
		FaceCache cache = new FaceCache(http);
		final long[] now = {1_000_000L};
		cache.clock = () -> now[0];

		assertNull(cache.face(UUID, SKIN));
		assertNull(waitFace(cache), "erster Versuch gedrosselt");
		assertEquals(1, calls.get());
		// Vor Ablauf der Wartezeit kein neuer Versuch.
		now[0] += FaceCache.RETRY_MS[0] - 1;
		assertNull(cache.face(UUID, SKIN));
		Thread.sleep(50);
		assertEquals(1, calls.get());
		// Danach erneut (wieder gedrosselt → längere Pause).
		now[0] += 2;
		assertNull(cache.face(UUID, SKIN));
		waitFace(cache);
		assertEquals(2, calls.get());
		now[0] += FaceCache.RETRY_MS[0] + 1;
		assertNull(cache.face(UUID, SKIN));
		Thread.sleep(50);
		assertEquals(2, calls.get(), "zweite Pause ist länger");
		now[0] += FaceCache.RETRY_MS[1];
		cache.face(UUID, SKIN);
		int[] face = waitFace(cache);
		assertNotNull(face, "dritter Versuch klappt");
		assertEquals(128, face.length);
		assertEquals(3, calls.get());
	}
}
