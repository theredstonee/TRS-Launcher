package dev.theredstonee.trsclient.core.notes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.sync.ClientSync;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Notiz-Sync mit dem TRS-Konto (Thread „TRS-Notes“): holt die Änderungen des Kontos seit dem letzten Abgleich,
 * führt je Notiz zusammen ({@link NotesMerge}) und lädt eigene Änderungen hoch. Nur mit Einwilligung, TRS-Online,
 * „Mit TRS-Konto synchronisieren“ (Client-Sync) und dem Notiz-Schalter „Mit TRS-Konto synchronisieren“.
 *
 * <p>Der Server-Teil ist ein Vorschlag (siehe {@link NotesSyncApi}); kennt die API ihn nicht, meldet der Sync
 * {@link Status#UNSUPPORTED}, fragt erst nach {@link #UNSUPPORTED_RETRY_MS} wieder nach und die Notizen bleiben lokal.
 */
public final class NotesSync {
	public static final long DEBOUNCE_MS = 3000L;
	public static final long PULL_INTERVAL_MS = 5 * 60_000L;
	public static final long UNSUPPORTED_RETRY_MS = 6 * 60 * 60_000L;
	static final long[] BACKOFF_MS = {30_000L, 2 * 60_000L, 10 * 60_000L, 30 * 60_000L};
	/** Höchstens so viele Seiten bzw. Pakete je Runde (der Rest folgt in der nächsten). */
	static final int MAX_PAGES = 25;
	static final int MAX_BATCHES = 10;

	public enum Status {
		/** Schalter aus (Notizen bleiben lokal). */
		OFF,
		NO_CONSENT,
		/** Nicht bei TRS angemeldet. */
		WAITING,
		SYNCING,
		SYNCED,
		OFFLINE,
		/** Die TRS API kann (noch) keine Notizen speichern. */
		UNSUPPORTED
	}

	/** Zustand je Konto (Gson-DTO). */
	static final class StateFile {
		long unsupportedUntil;
		Map<String, NotesMerge.Account> accounts = new LinkedHashMap<String, NotesMerge.Account>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static volatile NotesSync instance;

	private final TrsModules modules;
	private final ClientSync.Online online;
	private final NotesSyncApi api;
	private final Path stateFile;
	private final Executor executor;
	private final Consumer<String> log;
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<Runnable>();
	/** Nur im Sync-Thread (nach dem Laden). */
	private final StateFile state;

	// Spiel-Thread:
	private String currentUuid;
	private boolean inFlight;
	private long nextPullAt;
	private long nextAllowedAt;
	private int failures;
	private long syncedRevision = -1;
	private long tickNow;
	private volatile Status status = Status.OFF;
	private volatile long lastSyncAt;
	private volatile String rejectReason;
	private volatile boolean remoteChanged;

	public NotesSync(TrsModules modules, ClientSync.Online online, NotesSyncApi api, Path stateFile, Executor executor,
			Consumer<String> log) {
		this.modules = modules;
		this.online = online;
		this.api = api;
		this.stateFile = stateFile;
		this.executor = executor;
		this.log = log != null ? log : new Consumer<String>() {
			@Override
			public void accept(String s) {
			}
		};
		this.state = load(stateFile);
	}

	/** Standard-Aufbau im Spiel: eigener Thread, Zustand unter {@code <configDir>/trsclient/notes/sync-state.json}. */
	public static NotesSync create(TrsModules modules, final TrsOnline trs, Path configDir, String userAgent, Consumer<String> log) {
		ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(4), r -> {
			Thread t = new Thread(r, "TRS-Notes");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});
		ex.allowCoreThreadTimeOut(true);
		ClientSync.Online online = new ClientSync.Online() {
			@Override
			public boolean consent() {
				return trs.launcherEnabled();
			}

			@Override
			public String token() {
				return trs.apiToken();
			}

			@Override
			public String uuid() {
				return trs.ownUuid();
			}

			@Override
			public void rejected(String token) {
				trs.tokenRejected(token);
			}
		};
		Path file = configDir == null ? null : configDir.resolve("trsclient").resolve("notes").resolve(NotesStore.SYNC_STATE);
		NotesSync sync = new NotesSync(modules, online, new NotesSyncApi(new Http.UrlConnection(userAgent), trs.apiBase()), file, ex, log);
		instance = sync;
		return sync;
	}

	/** Der Notiz-Sync des laufenden Spiels (null ohne Online-Funktionen, z. B. Forge 1.7.10). */
	public static NotesSync get() {
		return instance;
	}

	public static void setInstance(NotesSync sync) {
		instance = sync;
	}

	public Status status() {
		return status;
	}

	public long lastSyncAt() {
		return lastSyncAt;
	}

	/** {@code note_limit}/{@code invalid}, wenn das Konto Notizen abgelehnt hat, sonst null. */
	public String rejectReason() {
		return rejectReason;
	}

	/** Nächsten Abgleich sofort anstoßen. */
	public void syncNow() {
		nextPullAt = 0;
		nextAllowedAt = 0;
	}

	/**
	 * {@code notes_changed} (oder {@code resync}) auf {@code /v1/events/me}: beim nächsten Tick holen – beliebiger
	 * Thread. Die Wartezeit nach Fehlern/„nicht verfügbar“ bleibt bestehen.
	 */
	public void remoteChanged() {
		remoteChanged = true;
	}

	/** Darf synchronisiert werden (Einwilligung + alle Schalter)? */
	public boolean enabled() {
		return online.consent() && modules.trsOnline.isEnabled() && modules.syncClient.get() && modules.notes.notes.isEnabled()
				&& modules.notes.sync.get();
	}

	// --- Tick (Spiel-Thread) ---

	/** Einmal je Client-Tick; blockiert nie. */
	public void tick(long now) {
		tickNow = now;
		Runnable r;
		while ((r = results.poll()) != null) r.run();
		Notes notes = Notes.get();
		if (notes == null) return;
		if (!online.consent()) {
			status = Status.NO_CONSENT;
			return;
		}
		if (!modules.trsOnline.isEnabled() || !modules.syncClient.get() || !modules.notes.notes.isEnabled() || !modules.notes.sync.get()) {
			status = Status.OFF;
			return;
		}
		String token = online.token();
		String uuid = online.uuid();
		if (token == null || uuid == null) {
			if (status != Status.OFFLINE && status != Status.UNSUPPORTED) status = Status.WAITING;
			return;
		}
		if (!uuid.equals(currentUuid)) {
			currentUuid = uuid;
			nextPullAt = 0;
			nextAllowedAt = 0;
			failures = 0;
			syncedRevision = -1;
			if (status == Status.SYNCED) status = Status.WAITING;
		}
		if (inFlight || now < nextAllowedAt) return;
		NotesStore store = notes.store();
		boolean pull = now >= nextPullAt || remoteChanged;
		remoteChanged = false;
		boolean local = store.revision() != syncedRevision && now - store.changedAt() >= DEBOUNCE_MS;
		if (!pull && !local) return;
		start(store, token, uuid, now);
	}

	private void start(NotesStore store, final String token, final String uuid, long now) {
		final List<NotesStore.Entry> snapshot = store.snapshot();
		final long revision = store.revision();
		inFlight = true;
		nextPullAt = now + PULL_INTERVAL_MS;
		if (status != Status.SYNCED) status = Status.SYNCING;
		try {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					round(token, uuid, snapshot, revision);
				}
			});
		} catch (RejectedExecutionException e) {
			inFlight = false;
		}
	}

	// --- Abgleich (Sync-Thread) ---

	void round(final String token, final String uuid, List<NotesStore.Entry> snapshot, final long revision) {
		long now = System.currentTimeMillis();
		try {
			if (state.unsupportedUntil > now) throw new NotesSyncApi.Unsupported(404);
			NotesMerge.Account acc = state.accounts.get(uuid);
			if (acc == null) {
				acc = new NotesMerge.Account();
				state.accounts.put(uuid, acc);
			}
			acc.normalized();
			List<NotesSyncApi.Remote> pulled = new ArrayList<NotesSyncApi.Remote>();
			String cursor = acc.cursor;
			boolean more = false;
			// Ohne Cursor ist die Antwort ohnehin die vollständige Liste (mit Grabsteinen).
			boolean full = cursor == null || cursor.isEmpty();
			for (int page = 0; page < MAX_PAGES; page++) {
				NotesSyncApi.Page p = api.pull(token, cursor);
				if (p.reset) {
					// Cursor zu alt/unbekannt: ab hier kommt die vollständige Liste – Bisheriges verwerfen.
					pulled.clear();
					full = true;
				}
				pulled.addAll(p.notes);
				cursor = p.cursor;
				more = p.more;
				if (!more) break;
			}
			// Nur eine ganz geholte Liste darf Notizen verwerfen; sonst beim nächsten Mal von vorn.
			boolean complete = full && !more;
			if (full && more) cursor = null;
			NotesMerge.Plan plan = NotesMerge.plan(snapshot, acc, pulled, complete);
			final List<NotesStore.Entry> drop = new ArrayList<NotesStore.Entry>(plan.drop);
			final List<NotesSyncApi.Remote> apply = new ArrayList<NotesSyncApi.Remote>(plan.apply);
			List<List<NotesStore.Entry>> batches = NotesMerge.batches(plan.upload);
			boolean done0 = batches.size() <= MAX_BATCHES && !more;
			for (int i = 0; i < batches.size() && i < MAX_BATCHES; i++) {
				List<NotesStore.Entry> batch = batches.get(i);
				List<NotesSyncApi.Result> res = api.push(token, batch);
				NotesMerge.results(acc, batch, res, apply);
			}
			acc.cursor = cursor;
			NotesMerge.prune(acc, snapshot, apply);
			state.unsupportedUntil = 0;
			save();
			final String reason = acc.rejectReason;
			final boolean done = done0;
			post(new Runnable() {
				@Override
				public void run() {
					finished(uuid, apply, drop, revision, reason, done);
				}
			});
		} catch (final NotesSyncApi.Unsupported e) {
			if (state.unsupportedUntil <= now) {
				state.unsupportedUntil = now + UNSUPPORTED_RETRY_MS;
				save();
			}
			post(new Runnable() {
				@Override
				public void run() {
					unsupported();
				}
			});
		} catch (final ApiException e) {
			post(new Runnable() {
				@Override
				public void run() {
					failed(e.unauthorized() ? token : null, e.rateLimited() ? e.retryAfterMs() : 0, "HTTP " + e.status() + " " + e.code(),
							e.status() >= 400 && e.status() < 500 && !e.unauthorized() && !e.rateLimited());
				}
			});
		} catch (final IOException | RuntimeException e) {
			post(new Runnable() {
				@Override
				public void run() {
					failed(null, 0, e.getClass().getSimpleName(), e instanceof RuntimeException);
				}
			});
		}
	}

	private void post(Runnable r) {
		results.add(r);
	}

	// --- Ergebnisse (Spiel-Thread) ---

	private void finished(String uuid, List<NotesSyncApi.Remote> apply, List<NotesStore.Entry> drop, long revision, String reason,
			boolean complete) {
		inFlight = false;
		failures = 0;
		rejectReason = reason;
		if (!uuid.equals(currentUuid)) return;
		Notes notes = Notes.get();
		int applied = 0;
		if (notes != null) {
			NotesStore store = notes.store();
			for (NotesStore.Entry d : drop) {
				if (store.dropIfUnchanged(d.note.id, d.note.updated)) applied++;
			}
			for (NotesSyncApi.Remote r : apply) {
				if (store.applyRemote(r.world, r.note)) applied++;
			}
			if (applied > 0) {
				String err = store.saveQuietly();
				if (err != null) log.accept("TRS-Notizen: nicht gespeichert: " + err);
			}
			// Nur als „abgeglichen“ merken, wenn seitdem nichts geändert wurde (sonst gleich die nächste Runde).
			if (store.revision() == revision && complete) syncedRevision = revision;
		}
		if (!complete) nextPullAt = 0;
		lastSyncAt = System.currentTimeMillis();
		status = Status.SYNCED;
		if (applied > 0) log.accept("TRS-Notizen: " + applied + " Notiz(en) vom TRS-Konto übernommen");
	}

	private void unsupported() {
		inFlight = false;
		status = Status.UNSUPPORTED;
		nextAllowedAt = tickNow + UNSUPPORTED_RETRY_MS;
		nextPullAt = nextAllowedAt;
	}

	private void failed(String rejectedToken, long retryAfterMs, String reason, boolean permanent) {
		inFlight = false;
		if (rejectedToken != null) {
			online.rejected(rejectedToken);
			nextAllowedAt = tickNow + 2000L;
			return;
		}
		long wait = permanent ? BACKOFF_MS[BACKOFF_MS.length - 1] : BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
		failures++;
		nextAllowedAt = tickNow + Math.max(wait, retryAfterMs);
		nextPullAt = nextAllowedAt;
		status = Status.OFFLINE;
		if (failures <= 2 || permanent) log.accept("TRS-Notizen: Sync nicht möglich (" + reason + "), neuer Versuch in " + wait / 1000 + " s");
	}

	// --- Zustand ---

	private static StateFile load(Path file) {
		if (file == null || !Files.isRegularFile(file)) return new StateFile();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			StateFile s = GSON.fromJson(r, StateFile.class);
			if (s == null) return new StateFile();
			if (s.accounts == null) s.accounts = new LinkedHashMap<String, NotesMerge.Account>();
			for (NotesMerge.Account a : s.accounts.values()) if (a != null) a.normalized();
			return s;
		} catch (IOException | RuntimeException e) {
			return new StateFile();
		}
	}

	private void save() {
		if (stateFile == null) return;
		try {
			NotesStore.write(stateFile, state);
		} catch (IOException | RuntimeException e) {
			log.accept("TRS-Notizen: Sync-Zustand nicht gespeichert: " + e.getMessage());
		}
	}
}
