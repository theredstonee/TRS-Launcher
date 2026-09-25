package dev.theredstonee.trsclient.core.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Im Spiel hinzugefügte Konten dieser Instanz ({@code config/trsclient/accounts.json}) – nur für Spiele, die
 * NICHT über den TRS Launcher laufen. Gespeichert werden Name, UUID, Skin-Adresse und das Refresh-Token,
 * dieses immer verschlüsselt ({@link SecretBox}); Minecraft-Zugangs-Tokens nie.
 */
public final class AccountVault {
	public static final String FILE = "accounts.json";
	private static final long MAX_BYTES = 256 * 1024;
	static final int MAX_ACCOUNTS = 20;

	/** Ein gespeichertes Konto (Gson 2.2.4-tauglich). */
	static final class Entry {
		String uuid;
		String name;
		String skinUrl;
		String xuid;
		String protection;
		String refresh;
		Long addedAt;
	}

	static final class FileDto {
		Integer version;
		List<Entry> accounts;
	}

	/** Öffentliche Sicht ohne Geheimnis. */
	public static final class Stored {
		public final String uuid;
		public final String name;
		public final String skinUrl;
		public final String xuid;
		/** Refresh-Token hier nicht lesbar (anderer PC/Benutzer) → neu anmelden. */
		public final boolean locked;

		Stored(String uuid, String name, String skinUrl, String xuid, boolean locked) {
			this.uuid = uuid;
			this.name = name;
			this.skinUrl = skinUrl;
			this.xuid = xuid;
			this.locked = locked;
		}
	}

	private final Path file;
	private final Path keyDir;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public AccountVault(Path configDir, Path keyDir) {
		this.file = configDir.resolve("trsclient").resolve(FILE);
		this.keyDir = keyDir;
	}

	Path file() {
		return file;
	}

	private synchronized List<Entry> read() {
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return new ArrayList<Entry>();
			FileDto dto;
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				dto = gson.fromJson(r, FileDto.class);
			}
			List<Entry> out = new ArrayList<Entry>();
			if (dto == null || dto.version == null || dto.version != 1 || dto.accounts == null) return out;
			for (Entry e : dto.accounts) {
				if (e == null || e.uuid == null || !e.uuid.matches("[0-9a-f]{32}") || e.name == null
						|| !e.name.matches("[A-Za-z0-9_]{1,16}") || e.refresh == null) {
					continue;
				}
				e.skinUrl = MsAuth.safeSkinUrl(e.skinUrl);
				if (e.xuid != null && !e.xuid.matches("[0-9]{1,20}")) e.xuid = null;
				out.add(e);
				if (out.size() >= MAX_ACCOUNTS) break;
			}
			return out;
		} catch (IOException | RuntimeException e) {
			return new ArrayList<Entry>();
		}
	}

	private synchronized void write(List<Entry> entries) throws IOException {
		FileDto dto = new FileDto();
		dto.version = 1;
		dto.accounts = entries;
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(FILE + ".tmp");
		Files.write(tmp, gson.toJson(dto).getBytes(StandardCharsets.UTF_8));
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public List<Stored> list() {
		List<Stored> out = new ArrayList<Stored>();
		for (Entry e : read()) {
			SecretBox box = SecretBox.forKind(e.protection, keyDir);
			out.add(new Stored(e.uuid, e.name, e.skinUrl, e.xuid, box == null));
		}
		return Collections.unmodifiableList(out);
	}

	/** Neues oder erneut angemeldetes Konto speichern. */
	public synchronized void put(String uuid, String name, String skinUrl, String xuid, String refreshToken)
			throws IOException, GeneralSecurityException {
		SecretBox box = SecretBox.best(keyDir);
		List<Entry> entries = read();
		Long addedAt = null;
		for (int i = entries.size() - 1; i >= 0; i--) {
			if (entries.get(i).uuid.equals(uuid)) {
				addedAt = entries.get(i).addedAt;
				entries.remove(i);
			}
		}
		if (entries.size() >= MAX_ACCOUNTS) throw new IOException("zu viele Konten");
		Entry e = new Entry();
		e.uuid = uuid;
		e.name = name;
		e.skinUrl = MsAuth.safeSkinUrl(skinUrl);
		e.xuid = xuid;
		e.protection = box.kind();
		e.refresh = Base64.getEncoder().encodeToString(box.seal(refreshToken.getBytes(StandardCharsets.UTF_8)));
		e.addedAt = addedAt != null ? addedAt : System.currentTimeMillis();
		entries.add(e);
		write(entries);
	}

	/** Entschlüsseltes Refresh-Token oder null (fehlt/hier nicht lesbar). */
	public String refreshToken(String uuid) {
		for (Entry e : read()) {
			if (!e.uuid.equals(uuid)) continue;
			SecretBox box = SecretBox.forKind(e.protection, keyDir);
			if (box == null) return null;
			try {
				byte[] plain = box.open(Base64.getDecoder().decode(e.refresh));
				return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
			} catch (IllegalArgumentException ex) {
				return null;
			}
		}
		return null;
	}

	public synchronized boolean remove(String uuid) throws IOException {
		List<Entry> entries = read();
		boolean removed = false;
		for (int i = entries.size() - 1; i >= 0; i--) {
			if (entries.get(i).uuid.equals(uuid)) {
				entries.remove(i);
				removed = true;
			}
		}
		if (removed) write(entries);
		return removed;
	}
}
