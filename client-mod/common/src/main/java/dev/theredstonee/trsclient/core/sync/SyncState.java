package dev.theredstonee.trsclient.core.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Buchführung des Client-Syncs in {@code config/trsclient/sync-state.json} (nur Hashes und Zeiten, keine
 * Einstellungen, kein Token):
 * <ul>
 *   <li>{@code local}: je Abschnitt der Hash des lokalen Stands und wann er sich zuletzt geändert hat;</li>
 *   <li>{@code accounts}: je TRS-Konto (UUID) der zuletzt abgeglichene Stand je Abschnitt (Hash + Zeit des Kontos)
 *   und die zuletzt gesehenen Launcher-Einstellungen (Thema, Akzent, Sprache) des Kontos;</li>
 *   <li>{@code lastAccount}: Konto des letzten Abgleichs – nach einem Kontowechsel gilt beim ersten Abgleich das
 *   Konto.</li>
 * </ul>
 * Alle Methoden sind synchronisiert (Spiel-Thread und Sync-Thread).
 */
public final class SyncState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int MAX_ACCOUNTS = 8;
	private static final long MAX_BYTES = 256 * 1024;

	/** Stand eines Abschnitts: Hash + Zeit (ms). */
	public static final class Mark {
		public String hash;
		public long at;

		public Mark() {
		}

		public Mark(String hash, long at) {
			this.hash = hash;
			this.at = at;
		}
	}

	/** Zuletzt gesehene Launcher-Einstellungen eines Kontos. */
	public static final class Look {
		public String theme;
		public String accent;
		public String language;
		public long at;
	}

	public static final class Account {
		public Map<String, Mark> synced = new LinkedHashMap<String, Mark>();
		public Look look;
		/** Letzter erfolgreicher Abgleich (ms). */
		public long lastSync;
		/** Wann zuletzt benutzt (zum Aufräumen). */
		public long used;
	}

	static final class Data {
		int version = 1;
		Map<String, Mark> local = new LinkedHashMap<String, Mark>();
		Map<String, Account> accounts = new LinkedHashMap<String, Account>();
		String lastAccount;
	}

	private final Path file;
	private Data data = new Data();

	public SyncState(Path file) {
		this.file = file;
	}

	/** Liest die Datei (fehlt/kaputt → leer). */
	public synchronized SyncState load() {
		data = new Data();
		if (file == null) return this;
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return this;
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				Data d = GSON.fromJson(r, Data.class);
				if (d != null) data = d;
			}
		} catch (IOException | RuntimeException e) {
			data = new Data();
		}
		if (data.local == null) data.local = new LinkedHashMap<String, Mark>();
		if (data.accounts == null) data.accounts = new LinkedHashMap<String, Account>();
		return this;
	}

	/** Schreibt atomar (Fehler still – dann gleicht der nächste Start eben einmal mehr ab). */
	public synchronized void save() {
		if (file == null) return;
		try {
			Path dir = file.toAbsolutePath().getParent();
			if (dir != null) Files.createDirectories(dir);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(data, w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException ignored) {
			// nächstes Mal
		}
	}

	/** Lokaler Stand eines Abschnitts (null = unbekannt). */
	public synchronized Mark local(String section) {
		Mark m = data.local.get(section);
		return m == null ? null : new Mark(m.hash, m.at);
	}

	/**
	 * Merkt sich den lokalen Hash; hat er sich geändert, zählt {@code changedAt} als Zeit der Änderung.
	 *
	 * @return true, wenn sich der Abschnitt geändert hat
	 */
	public synchronized boolean observeLocal(String section, String hash, long changedAt) {
		Mark m = data.local.get(section);
		if (m != null && hash.equals(m.hash)) return false;
		data.local.put(section, new Mark(hash, changedAt));
		return true;
	}

	/** Konto (wird angelegt). */
	public synchronized Account account(String uuid) {
		Account a = data.accounts.get(uuid);
		if (a == null) {
			a = new Account();
			data.accounts.put(uuid, a);
			prune(uuid);
		}
		if (a.synced == null) a.synced = new LinkedHashMap<String, Mark>();
		return a;
	}

	/** Gibt es für das Konto schon einen Abgleich? */
	public synchronized boolean known(String uuid) {
		Account a = data.accounts.get(uuid);
		return a != null && a.synced != null && !a.synced.isEmpty();
	}

	public synchronized Mark synced(String uuid, String section) {
		Mark m = account(uuid).synced.get(section);
		return m == null ? null : new Mark(m.hash, m.at);
	}

	public synchronized void setSynced(String uuid, String section, String hash, long at) {
		account(uuid).synced.put(section, new Mark(hash, at));
	}

	public synchronized void setLook(String uuid, Look look) {
		account(uuid).look = look;
	}

	public synchronized Look look(String uuid) {
		Look l = account(uuid).look;
		if (l == null) return null;
		Look c = new Look();
		c.theme = l.theme;
		c.accent = l.accent;
		c.language = l.language;
		c.at = l.at;
		return c;
	}

	public synchronized void touched(String uuid, long now, boolean success) {
		Account a = account(uuid);
		a.used = now;
		if (success) a.lastSync = now;
		data.lastAccount = uuid;
	}

	public synchronized long lastSync(String uuid) {
		Account a = data.accounts.get(uuid);
		return a == null ? 0 : a.lastSync;
	}

	public synchronized String lastAccount() {
		return data.lastAccount;
	}

	private void prune(String keep) {
		while (data.accounts.size() > MAX_ACCOUNTS) {
			String oldest = null;
			long at = Long.MAX_VALUE;
			for (Map.Entry<String, Account> e : data.accounts.entrySet()) {
				if (e.getKey().equals(keep)) continue;
				if (e.getValue().used < at) {
					at = e.getValue().used;
					oldest = e.getKey();
				}
			}
			if (oldest == null) return;
			for (Iterator<String> it = data.accounts.keySet().iterator(); it.hasNext(); ) {
				if (it.next().equals(oldest)) it.remove();
			}
		}
	}
}
