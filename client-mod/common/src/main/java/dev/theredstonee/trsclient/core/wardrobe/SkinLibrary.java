package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Lokale Kopie von „Meine Skins“ je Konto: {@code config/trsclient/wardrobe/<konto>/skins/<id>.png} plus
 * {@code index.json} (Name, Armform, Sync-Stand). Offline angelegte/gelöschte Skins werden vermerkt und beim nächsten
 * Sync nachgeholt. Nur aus dem Garderoben-Thread benutzen (nicht thread-sicher).
 */
public final class SkinLibrary {
	/** Wie die API (API.md §17.2). */
	public static final int MAX_SKINS = 60;
	private static final Gson GSON = new Gson();

	/** Ein Eintrag (Gson-Felder). */
	public static final class Entry {
		public String id;
		public String name;
		public boolean slim;
		/** SHA-256 der Server-Datei (null = nie synchronisiert). */
		public String sha256;
		/** Serverzeit des letzten Stands oder null. */
		public String updatedAt;
		/** Lokal geändert/angelegt, noch nicht hochgeladen. */
		public boolean pendingPut;
		/** War schon einmal auf dem Server (verschwindet er dort ohne Grabstein, war er gelöscht). */
		public boolean synced;
		/** Lokale Änderungszeit (ms), für die Reihenfolge. */
		public long changedAt;

		Entry copy() {
			Entry e = new Entry();
			e.id = id;
			e.name = name;
			e.slim = slim;
			e.sha256 = sha256;
			e.updatedAt = updatedAt;
			e.pendingPut = pendingPut;
			e.synced = synced;
			e.changedAt = changedAt;
			return e;
		}
	}

	static final class IndexFile {
		int version = 1;
		List<Entry> skins = new ArrayList<Entry>();
		/** Lokal gelöscht, auf dem Server noch zu löschen. */
		List<String> pendingDelete = new ArrayList<String>();
	}

	private final Path dir;
	private IndexFile index = new IndexFile();

	public SkinLibrary(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	/** Liest den Index (fehlend/kaputt = leer). */
	public void load() {
		index = new IndexFile();
		Path f = dir.resolve("index.json");
		try {
			if (Files.isRegularFile(f) && Files.size(f) < 512 * 1024) {
				IndexFile read = GSON.fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), IndexFile.class);
				if (read != null) {
					if (read.skins != null) {
						for (Entry e : read.skins) {
							if (e != null && SkinFiles.validId(e.id) && find(e.id) == null && index.skins.size() < MAX_SKINS * 2) {
								e.name = SkinFiles.cleanName(e.name, "Skin");
								index.skins.add(e);
							}
						}
					}
					if (read.pendingDelete != null) {
						for (String id : read.pendingDelete) if (SkinFiles.validId(id) && !index.pendingDelete.contains(id)) index.pendingDelete.add(id);
					}
				}
			}
		} catch (IOException | JsonSyntaxException | IllegalStateException e) {
			index = new IndexFile();
		}
	}

	/** Schreibt den Index (atomar über eine Temp-Datei). */
	public void save() throws IOException {
		Files.createDirectories(dir);
		Path tmp = dir.resolve("index.json.tmp");
		Files.write(tmp, GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
		try {
			Files.move(tmp, dir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, dir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public List<Entry> entries() {
		return index.skins;
	}

	/** Kopien aller Einträge (für den Zustand der Oberfläche). */
	public List<Entry> snapshot() {
		List<Entry> out = new ArrayList<Entry>(index.skins.size());
		for (Entry e : index.skins) out.add(e.copy());
		return out;
	}

	public List<String> pendingDeletes() {
		return index.pendingDelete;
	}

	public Entry find(String id) {
		for (Entry e : index.skins) if (e.id.equals(id)) return e;
		return null;
	}

	public int size() {
		return index.skins.size();
	}

	public Path file(String id) {
		return dir.resolve("skins").resolve(id + ".png");
	}

	public byte[] png(String id) throws IOException {
		Path f = file(id);
		if (!Files.isRegularFile(f) || Files.size(f) > SkinFiles.MAX_BYTES) throw new IOException("fehlt");
		return Files.readAllBytes(f);
	}

	/** Legt einen Skin an oder ersetzt ihn (Datei + Eintrag); {@code local} = noch hochzuladen. */
	public Entry put(String id, String name, boolean slim, byte[] png, boolean local) throws IOException {
		Files.createDirectories(dir.resolve("skins"));
		Files.write(file(id), png);
		Entry e = find(id);
		if (e == null) {
			e = new Entry();
			e.id = id;
			index.skins.add(0, e);
		}
		e.name = SkinFiles.cleanName(name, "Skin");
		e.slim = slim;
		e.changedAt = System.currentTimeMillis();
		if (local) e.pendingPut = true;
		index.pendingDelete.remove(id);
		return e;
	}

	/** Löscht lokal; {@code remote} = auf dem Server noch löschen. */
	public void remove(String id, boolean remote) {
		for (Iterator<Entry> it = index.skins.iterator(); it.hasNext(); ) {
			Entry e = it.next();
			if (!e.id.equals(id)) continue;
			it.remove();
			if (remote && e.synced && !index.pendingDelete.contains(id)) index.pendingDelete.add(id);
		}
		try {
			Files.deleteIfExists(file(id));
		} catch (IOException ignored) {
			// bleibt als Waise liegen – harmlos
		}
	}
}
