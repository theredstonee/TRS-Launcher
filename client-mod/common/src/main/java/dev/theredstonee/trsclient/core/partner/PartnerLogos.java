package dev.theredstonee.trsclient.core.partner;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Partner-Logos: Datei unter {@code config/trsclient/partner-logos/<sha256 der Adresse>.png} (die Adresse enthält die
 * Prüfsumme, der Inhalt ändert sich also nie), dekodiert und auf {@link #SIZE}² verkleinert im Hintergrund, hochgeladen im
 * Render-Thread.
 */
final class PartnerLogos {
	static final int SIZE = 64;
	static final int MAX_BYTES = 256 * 1024;
	static final long RETRY_MS = 5 * 60_000L;

	private static final class Slot {
		volatile int[] pixels;
		TextureRef texture;
		boolean loading;
		long retryAt;
	}

	private final Path dir;
	private final Map<String, Slot> slots = new HashMap<String, Slot>();

	PartnerLogos(Path dir) {
		this.dir = dir;
	}

	synchronized TextureRef texture(final String key, final String url, final Http http, Executor executor, long now) {
		Slot slot = slots.get(key);
		if (slot == null) {
			if (slots.size() >= 16) return null;
			slot = new Slot();
			slots.put(key, slot);
		}
		if (slot.texture != null) return slot.texture;
		int[] px = slot.pixels;
		if (px != null) {
			Textures.Store store = Textures.store();
			if (store == null) return null;
			String h = hash(key);
			slot.texture = store.upload("trsclient/partner/" + (h.length() > 24 ? h.substring(0, 24) : h), SIZE, SIZE, px);
			if (slot.texture != null) slot.pixels = null;
			return slot.texture;
		}
		if (slot.loading || now < slot.retryAt || http == null || executor == null) return null;
		slot.loading = true;
		final Slot s = slot;
		try {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					int[] result = null;
					try {
						result = scaled(PngDecoder.decode(bytes(key, url, http), 1024L * 1024));
					} catch (IOException | RuntimeException ignored) {
						// später noch einmal
					}
					synchronized (PartnerLogos.this) {
						s.loading = false;
						if (result != null) s.pixels = result;
						else s.retryAt = System.currentTimeMillis() + RETRY_MS;
					}
				}
			});
		} catch (RuntimeException e) {
			slot.loading = false;
			slot.retryAt = now + RETRY_MS;
		}
		return null;
	}

	private byte[] bytes(String key, String url, Http http) throws IOException {
		Path file = dir == null ? null : dir.resolve(hash(key) + ".png");
		if (file != null && Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES) return Files.readAllBytes(file);
		Http.Request req = new Http.Request("GET", url).header("Accept", "image/png");
		req.maxBytes = MAX_BYTES;
		Http.Response res = http.send(req);
		if (res.status != 200 || res.body.length == 0) throw new IOException("Logo " + res.status);
		if (file != null) {
			try {
				Files.createDirectories(dir);
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				Files.write(tmp, res.body);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// nur ein Cache
			}
		}
		return res.body;
	}

	/** Auf SIZE² bringen (Mittelwert je Zelle, vormultipliziert); kleinere Bilder werden hochgezogen. */
	static int[] scaled(PngDecoder.Image img) throws IOException {
		if (img == null || img.width <= 0 || img.height <= 0) throw new IOException("leer");
		int[] out = new int[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			int y0 = y * img.height / SIZE;
			int y1 = Math.max(y0 + 1, (y + 1) * img.height / SIZE);
			for (int x = 0; x < SIZE; x++) {
				int x0 = x * img.width / SIZE;
				int x1 = Math.max(x0 + 1, (x + 1) * img.width / SIZE);
				long a = 0, r = 0, g = 0, b = 0;
				int n = 0;
				for (int sy = y0; sy < y1; sy++) {
					for (int sx = x0; sx < x1; sx++) {
						int p = img.argb[sy * img.width + sx];
						int pa = p >>> 24;
						a += pa;
						r += ((p >> 16) & 0xFF) * pa;
						g += ((p >> 8) & 0xFF) * pa;
						b += (p & 0xFF) * pa;
						n++;
					}
				}
				int oa = (int) (a / n);
				out[y * SIZE + x] = a == 0 ? 0 : (oa << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
			}
		}
		return out;
	}

	static String hash(String s) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(64);
			for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(s.hashCode());
		}
	}
}
