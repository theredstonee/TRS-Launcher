package dev.theredstonee.trsclient.core.screenshot;

import com.google.gson.Gson;
import dev.theredstonee.trsclient.core.clips.ScreenshotShare;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lokale Daten zu Bildschirmfotos in {@code config/trsclient/screenshots.json}: Favoriten (Dateinamen unter
 * {@code screenshots/}) und ob der TRS Client Essentials „Screenshot preview“ ausgeschaltet hat (nur dann schaltet er
 * sie beim Abschalten der Einstellung wieder ein). Bewusst nur lokal – nichts davon wird synchronisiert.
 */
public final class ScreenshotStore {
	public static final String FILE = "screenshots.json";
	static final int MAX_FAVORITES = 2000;
	private static final long MAX_BYTES = 256 * 1024;
	private static final Gson GSON = new Gson();

	/** Datei-Form (Gson 2.2.4-tauglich). */
	static final class Data {
		Integer version;
		List<String> favorites;
		Boolean essentialPreviewOff;
	}

	private final Path file;
	private final Set<String> favorites = new LinkedHashSet<>();
	private boolean essentialPreviewOff;
	/** Zählt jede Änderung (Oberflächen erkennen daran, dass sie neu zeichnen/filtern müssen). */
	private volatile int revision;

	ScreenshotStore(Path file) {
		this.file = file;
	}

	private static volatile ScreenshotStore shared;

	/** Gemeinsamer Speicher unter {@code <configDir>/trsclient/screenshots.json} (lädt beim ersten Aufruf). */
	public static ScreenshotStore shared(Path configDir) {
		ScreenshotStore s = shared;
		if (s == null) {
			synchronized (ScreenshotStore.class) {
				s = shared;
				if (s == null) {
					s = new ScreenshotStore(configDir == null ? null : configDir.resolve("trsclient").resolve(FILE));
					s.load();
					shared = s;
				}
			}
		}
		return s;
	}

	/** Bereits geladener Speicher oder null. */
	public static ScreenshotStore current() {
		return shared;
	}

	synchronized void load() {
		favorites.clear();
		essentialPreviewOff = false;
		try {
			if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return;
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				Data d = GSON.fromJson(r, Data.class);
				if (d == null) return;
				if (d.favorites != null) {
					for (String n : d.favorites) {
						if (ScreenshotShare.valid(n) && favorites.size() < MAX_FAVORITES) favorites.add(n);
					}
				}
				essentialPreviewOff = d.essentialPreviewOff != null && d.essentialPreviewOff;
			}
		} catch (IOException | RuntimeException e) {
			favorites.clear();
		}
		revision++;
	}

	private void save() {
		revision++;
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(FILE + ".tmp");
			Data d = new Data();
			d.version = 1;
			d.favorites = new ArrayList<>(favorites);
			d.essentialPreviewOff = essentialPreviewOff ? Boolean.TRUE : null;
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(d, w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException ignored) {
			// Nächster Versuch bei der nächsten Änderung.
		}
	}

	public int revision() {
		return revision;
	}

	public synchronized boolean isFavorite(String name) {
		return name != null && favorites.contains(name);
	}

	/** Favorit an/aus; Rückgabe: jetzt Favorit? Ungültige Namen bleiben unberührt (false). */
	public synchronized boolean toggleFavorite(String name) {
		if (!ScreenshotShare.valid(name)) return false;
		boolean now;
		if (favorites.remove(name)) {
			now = false;
		} else {
			if (favorites.size() >= MAX_FAVORITES) return false;
			favorites.add(name);
			now = true;
		}
		save();
		return now;
	}

	/** Datei gelöscht/umbenannt: Favorit vergessen. */
	public synchronized void forget(String name) {
		if (name != null && favorites.remove(name)) save();
	}

	public synchronized Set<String> favorites() {
		return new LinkedHashSet<>(favorites);
	}

	public synchronized boolean essentialPreviewOff() {
		return essentialPreviewOff;
	}

	public synchronized void essentialPreviewOff(boolean off) {
		if (essentialPreviewOff == off) return;
		essentialPreviewOff = off;
		save();
	}
}
