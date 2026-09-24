package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.CapeInfo;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Umhang-PNGs auf der Platte ({@code config/trsclient/capes/<id>.png} + {@code .etag} + {@code .url}).
 * Gleiche URL (das {@code ?v=} ändert sich mit dem Inhalt) → ohne Netz aus dem Cache; sonst mit
 * {@code If-None-Match} fragen, 304 → Cache weiter benutzen.
 */
public final class CapeDiskCache {
	private static final int MAX_BYTES = 8 * 1024 * 1024;

	private final Path dir;

	public CapeDiskCache(Path dir) {
		this.dir = dir;
	}

	/** Liefert die PNG-Bytes (Platte oder Netz). Blockierend – nur im Hintergrund aufrufen. */
	public byte[] load(CapeInfo cape, TrsApi api, String token) throws IOException, ApiException {
		Path png = dir.resolve(cape.id + ".png");
		Path etagFile = dir.resolve(cape.id + ".etag");
		Path urlFile = dir.resolve(cape.id + ".url");
		byte[] cached = readSmall(png, MAX_BYTES);
		if (cached != null && cape.url.equals(readText(urlFile))) return cached;
		String etag = cached != null ? readText(etagFile) : null;
		Http.Response response = api.texture(cape.url, etag, token);
		if (response.status == 304 && cached != null) {
			writeText(urlFile, cape.url);
			return cached;
		}
		byte[] body = response.body;
		if (body.length == 0) throw new IOException("leere Textur");
		Files.createDirectories(dir);
		Path tmp = dir.resolve(cape.id + ".png.tmp");
		Files.write(tmp, body);
		Files.move(tmp, png, StandardCopyOption.REPLACE_EXISTING);
		String newEtag = response.header("ETag");
		if (newEtag != null && newEtag.length() < 200) writeText(etagFile, newEtag);
		else Files.deleteIfExists(etagFile);
		writeText(urlFile, cape.url);
		return body;
	}

	private static byte[] readSmall(Path file, int max) {
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > max) return null;
			return Files.readAllBytes(file);
		} catch (IOException e) {
			return null;
		}
	}

	private static String readText(Path file) {
		byte[] b = readSmall(file, 1024);
		return b == null ? null : new String(b, StandardCharsets.UTF_8).trim();
	}

	private static void writeText(Path file, String text) {
		try {
			Files.write(file, text.getBytes(StandardCharsets.UTF_8));
		} catch (IOException ignored) {
			// Cache ist nur eine Beschleunigung
		}
	}
}
