package dev.theredstonee.trsclient.core.creator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Fan-Symbole auf der Platte ({@code config/trsclient/symbols/<sha256>.png}). Die Dateien der API sind unveränderlich
 * (Adresse = Inhalts-Kennung): liegt eine Datei mit passender Prüfsumme da, wird nie wieder geladen.
 */
public final class SymbolCache {
	/** Größte Symbol-Datei (die API erlaubt 256 KiB). */
	public static final int MAX_BYTES = 512 * 1024;

	/** Holt die Datei aus dem Netz (blockierend, nur im Hintergrund). */
	public interface Fetch {
		byte[] get(String url) throws IOException;
	}

	private final Path dir;

	public SymbolCache(Path dir) {
		this.dir = dir;
	}

	/**
	 * PNG-Bytes des Symbols: von der Platte, wenn die Prüfsumme stimmt, sonst aus dem Netz (und dann gespeichert, wenn
	 * die Prüfsumme stimmt – eine abweichende Datei wird benutzt, aber nie gespeichert).
	 */
	public byte[] load(String sha256, String url, Fetch fetch) throws IOException {
		if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IOException("Prüfsumme");
		Path file = dir == null ? null : dir.resolve(sha256 + ".png");
		if (file != null) {
			try {
				if (Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES) {
					byte[] cached = Files.readAllBytes(file);
					if (sha256.equals(sha256(cached))) return cached;
				}
			} catch (IOException ignored) {
				// neu laden
			}
		}
		byte[] body = fetch.get(url);
		if (body == null || body.length == 0) throw new IOException("leeres Symbol");
		if (body.length > MAX_BYTES) throw new IOException("Symbol zu groß");
		if (file != null && sha256.equals(sha256(body))) {
			try {
				Files.createDirectories(dir);
				Path tmp = dir.resolve(sha256 + ".png.tmp");
				Files.write(tmp, body);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// Cache ist nur eine Beschleunigung
			}
		}
		return body;
	}

	static String sha256(byte[] data) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder sb = new StringBuilder(64);
			for (byte b : d) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			return "";
		}
	}
}
