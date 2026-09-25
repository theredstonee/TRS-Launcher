package dev.theredstonee.trsclient.core.menus;

import com.google.gson.Gson;

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
import java.util.Locale;
import java.util.Set;

/**
 * Angeheftete Server (Favoriten) der Mehrspieler-Liste: stehen immer oben und tragen eine Markierung.
 * Gespeichert in {@code config/trsclient/server-pins.json} als normalisierte Adressen – die Reihenfolge in
 * {@code servers.dat} ändert die Mod nur, indem sie angeheftete Server nach oben schiebt (Vanilla-Funktion
 * „Verschieben“), alles andere bleibt, wie der Spieler es angelegt hat.
 */
public final class ServerPins {
	public static final String FILE = "server-pins.json";
	static final int MAX = 200;
	private static final long MAX_BYTES = 64 * 1024;
	private static final Gson GSON = new Gson();

	private final Path file;
	private final Set<String> pinned = new LinkedHashSet<>();

	/** Datei-Form (Gson 2.2.4-tauglich). */
	static final class Data {
		Integer version;
		List<String> pinned;
	}

	ServerPins(Path file) {
		this.file = file;
	}

	private static volatile ServerPins shared;

	/** Gemeinsame Liste unter {@code <configDir>/trsclient/server-pins.json} (lädt beim ersten Aufruf). */
	public static ServerPins shared(Path configDir) {
		ServerPins s = shared;
		if (s == null) {
			synchronized (ServerPins.class) {
				s = shared;
				if (s == null) {
					s = new ServerPins(configDir.resolve("trsclient").resolve(FILE));
					s.load();
					shared = s;
				}
			}
		}
		return s;
	}

	/** Bereits geladene Liste oder null (für Stellen ohne Konfigurationsordner). */
	public static ServerPins current() {
		return shared;
	}

	void load() {
		pinned.clear();
		try {
			if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return;
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				Data d = GSON.fromJson(r, Data.class);
				if (d == null || d.pinned == null) return;
				for (String a : d.pinned) {
					String n = normalize(a);
					if (n != null && pinned.size() < MAX) pinned.add(n);
				}
			}
		} catch (IOException | RuntimeException e) {
			pinned.clear();
		}
	}

	private void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(FILE + ".tmp");
			Data d = new Data();
			d.version = 1;
			d.pinned = new ArrayList<>(pinned);
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(d, w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException ignored) {
			// Nächster Versuch beim nächsten Anheften.
		}
	}

	public synchronized boolean isPinned(String address) {
		String n = normalize(address);
		return n != null && pinned.contains(n);
	}

	/** Anheften bzw. lösen; Rückgabe: jetzt angeheftet? */
	public synchronized boolean toggle(String address) {
		String n = normalize(address);
		if (n == null) return false;
		boolean now;
		if (pinned.remove(n)) {
			now = false;
		} else {
			if (pinned.size() >= MAX) return false;
			pinned.add(n);
			now = true;
		}
		save();
		return now;
	}

	/**
	 * Zielreihenfolge einer Serverliste: angeheftete zuerst (in ihrer bisherigen Reihenfolge), dann der Rest.
	 * Liefert die Indizes der Adressen in neuer Reihenfolge.
	 */
	public synchronized int[] order(List<String> addresses) {
		int[] out = new int[addresses.size()];
		int k = 0;
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < addresses.size(); i++) {
				String n = normalize(addresses.get(i));
				boolean pin = n != null && pinned.contains(n);
				if (pin == (pass == 0)) out[k++] = i;
			}
		}
		return out;
	}

	/**
	 * Tauschschritte (i, j), die eine Liste mit Vanilla-{@code swap(i, j)} in {@link #order} bringen – leer, wenn
	 * sie schon passt. Stabil: nur angeheftete Server wandern nach oben.
	 */
	public List<int[]> swaps(List<String> addresses) {
		int[] target = order(addresses);
		List<int[]> steps = new ArrayList<>();
		int[] cur = new int[addresses.size()];
		int[] pos = new int[addresses.size()];
		for (int i = 0; i < cur.length; i++) {
			cur[i] = i;
			pos[i] = i;
		}
		for (int i = 0; i < target.length; i++) {
			int want = target[i];
			int at = pos[want];
			// Nach vorn „blubbern“: benachbarte Tausche wie Vanilla (Umschalt+Pfeil).
			while (at > i) {
				steps.add(new int[]{at - 1, at});
				int a = cur[at - 1];
				cur[at - 1] = cur[at];
				cur[at] = a;
				pos[cur[at]] = at;
				pos[cur[at - 1]] = at - 1;
				at--;
			}
		}
		return steps;
	}

	/**
	 * Adresse vergleichbar machen: klein, ohne Leerzeichen, ohne Standard-Port 25565, ohne Schema.
	 * Ungültiges → null.
	 */
	public static String normalize(String address) {
		if (address == null) return null;
		String a = address.trim().toLowerCase(Locale.ROOT);
		if (a.startsWith("minecraft://")) a = a.substring("minecraft://".length());
		while (a.endsWith("/") || a.endsWith(".")) a = a.substring(0, a.length() - 1);
		if (a.isEmpty() || a.length() > 261) return null;
		for (int i = 0; i < a.length(); i++) {
			char c = a.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == ':' || c == '_'
					|| c == '[' || c == ']';
			if (!ok) return null;
		}
		if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
		return a.isEmpty() ? null : a;
	}
}
