package dev.theredstonee.trsclient.core.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Gedächtnis „welcher Server schickte welches Ressourcenpaket“ ({@code config/trsclient/server-packs.json}): nur
 * Pakete, die der Spieler angenommen hat (oder die der Server-Eintrag automatisch annimmt), nur mit gültigem
 * SHA-1. Dazu die Dateien, die der TRS Client selbst vorgeladen hat (für Größengrenze und Aufräumen). Nur lokal.
 */
public final class PackMemory {
	public static final String FILE = "server-packs.json";
	static final int MAX_SERVERS = 32;
	static final int MAX_PACKS_PER_SERVER = 4;
	static final int MAX_FILES = 128;
	static final int MAX_BYTES = 256 * 1024;

	/** Ein gemerktes Paket (Gson-2.2.4-taugliche Felder). */
	public static final class Pack {
		public String url;
		public String sha1;
		/** Paket-ID (ab 1.20.3), sonst null. */
		public String uuid;
		public Long size;
		public Long used;

		Pack copy() {
			Pack p = new Pack();
			p.url = url;
			p.sha1 = sha1;
			p.uuid = uuid;
			p.size = size;
			p.used = used;
			return p;
		}
	}

	static final class Server {
		String address;
		List<Pack> packs = new ArrayList<Pack>();
		Long used;
	}

	/** Eine vom TRS Client vorgeladene Datei. */
	static final class OwnFile {
		String path;
		String sha1;
		Long size;
		Long created;
	}

	static final class Data {
		Integer version;
		List<Server> servers = new ArrayList<Server>();
		List<OwnFile> files = new ArrayList<OwnFile>();
	}

	private final Path file;
	private Data data;

	public PackMemory(Path configDir) {
		this.file = configDir == null ? null : configDir.resolve("trsclient").resolve(FILE);
	}

	static String key(String address) {
		return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
	}

	private Data data() {
		if (data != null) return data;
		Data d = null;
		if (file != null) {
			try {
				if (Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES) {
					try (Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
						d = new Gson().fromJson(r, Data.class);
					}
				}
			} catch (IOException | RuntimeException e) {
				d = null;
			}
		}
		if (d == null || d.version == null || d.version != 1) d = new Data();
		d.version = 1;
		if (d.servers == null) d.servers = new ArrayList<Server>();
		if (d.files == null) d.files = new ArrayList<OwnFile>();
		// Ungültige Einträge (von Hand bearbeitet o. ä.) verwerfen.
		Iterator<Server> it = d.servers.iterator();
		while (it.hasNext()) {
			Server s = it.next();
			if (s == null || s.address == null || s.packs == null) {
				it.remove();
				continue;
			}
			Iterator<Pack> pi = s.packs.iterator();
			while (pi.hasNext()) {
				Pack p = pi.next();
				if (p == null || !validUrl(p.url) || !validSha1(p.sha1)) pi.remove();
			}
		}
		data = d;
		return d;
	}

	/** Gemerkte Pakete eines Servers (Kopien, neueste zuerst). */
	public synchronized List<Pack> packs(String address) {
		List<Pack> out = new ArrayList<Pack>();
		Server s = find(key(address));
		if (s != null) for (Pack p : s.packs) out.add(p.copy());
		return out;
	}

	/** Paket für einen Server merken (oder auffrischen). */
	public synchronized void remember(String address, String url, String sha1, String uuid, long now) {
		String k = key(address);
		if (k.isEmpty() || !validUrl(url) || !validSha1(sha1)) return;
		Data d = data();
		Server s = find(k);
		if (s == null) {
			s = new Server();
			s.address = k;
			d.servers.add(0, s);
		}
		Pack hit = null;
		for (Pack p : s.packs) {
			if (p.sha1.equalsIgnoreCase(sha1) && (uuid == null ? p.uuid == null : uuid.equals(p.uuid))) {
				hit = p;
				break;
			}
		}
		if (hit == null) {
			hit = new Pack();
			hit.sha1 = sha1.toLowerCase(Locale.ROOT);
			hit.uuid = uuid;
		}
		s.packs.remove(hit);
		hit.url = url;
		hit.used = now;
		s.packs.add(0, hit);
		while (s.packs.size() > MAX_PACKS_PER_SERVER) s.packs.remove(s.packs.size() - 1);
		s.used = now;
		d.servers.remove(s);
		d.servers.add(0, s);
		while (d.servers.size() > MAX_SERVERS) d.servers.remove(d.servers.size() - 1);
		save();
	}

	/** Spieler hat ein Paket abgelehnt: nicht mehr vorladen. */
	public synchronized void forget(String address, String sha1) {
		Server s = find(key(address));
		if (s == null || sha1 == null) return;
		Iterator<Pack> it = s.packs.iterator();
		boolean changed = false;
		while (it.hasNext()) {
			if (it.next().sha1.equalsIgnoreCase(sha1)) {
				it.remove();
				changed = true;
			}
		}
		if (changed) save();
	}

	/** Größe einer Datei merken, die der TRS Client vorgeladen hat. */
	public synchronized void ownFile(Path path, String sha1, long size, long now) {
		Data d = data();
		String p = path.toAbsolutePath().normalize().toString();
		Iterator<OwnFile> it = d.files.iterator();
		while (it.hasNext()) if (p.equals(it.next().path)) it.remove();
		OwnFile f = new OwnFile();
		f.path = p;
		f.sha1 = sha1 == null ? null : sha1.toLowerCase(Locale.ROOT);
		f.size = size;
		f.created = now;
		d.files.add(f);
		while (d.files.size() > MAX_FILES) d.files.remove(0);
		save();
	}

	/**
	 * Eigene vorgeladene Dateien so weit löschen (älteste zuerst), dass {@code incoming} Bytes noch unter
	 * {@code limit} passen. Fremde Dateien (von Minecraft selbst) bleiben immer. Liefert die gelöschten Pfade.
	 */
	public synchronized List<String> makeRoom(long incoming, long limit) {
		Data d = data();
		List<String> deleted = new ArrayList<String>();
		long total = 0;
		Iterator<OwnFile> it = d.files.iterator();
		while (it.hasNext()) {
			OwnFile f = it.next();
			if (f == null || f.path == null || !Files.isRegularFile(java.nio.file.Paths.get(f.path))) {
				it.remove();
				continue;
			}
			total += f.size == null ? 0 : f.size;
		}
		while (total + incoming > limit && !d.files.isEmpty()) {
			OwnFile f = d.files.remove(0);
			try {
				Files.deleteIfExists(java.nio.file.Paths.get(f.path));
				deleted.add(f.path);
			} catch (IOException | RuntimeException ignored) {
				// wird beim nächsten Mal erneut versucht
			}
			total -= f.size == null ? 0 : f.size;
		}
		if (!deleted.isEmpty()) save();
		return deleted;
	}

	/** Eine eigene vorgeladene Datei mit diesem SHA-1, die noch existiert, oder null. */
	public synchronized Path ownFileWithHash(String sha1) {
		if (sha1 == null) return null;
		for (int i = data().files.size() - 1; i >= 0; i--) {
			OwnFile f = data().files.get(i);
			if (f != null && f.path != null && sha1.equalsIgnoreCase(f.sha1)) {
				Path p = java.nio.file.Paths.get(f.path);
				if (Files.isRegularFile(p)) return p;
			}
		}
		return null;
	}

	/** Summe der eigenen vorgeladenen Dateien (für Tests/Anzeige). */
	public synchronized long ownBytes() {
		long total = 0;
		for (OwnFile f : data().files) total += f.size == null ? 0 : f.size;
		return total;
	}

	private Server find(String k) {
		for (Server s : data().servers) if (k.equals(s.address)) return s;
		return null;
	}

	private void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(FILE + ".tmp");
			Files.write(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(data).getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException ignored) {
			// nur ein Gedächtnis
		}
	}

	static boolean validSha1(String s) {
		if (s == null || s.length() != 40) return false;
		for (int i = 0; i < 40; i++) {
			char c = s.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
		}
		return true;
	}

	static boolean validUrl(String s) {
		if (s == null || s.length() > 2048) return false;
		String l = s.toLowerCase(Locale.ROOT);
		return l.startsWith("http://") || l.startsWith("https://");
	}
}
