package dev.theredstonee.trsclient.core.sync;

import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.intro.ClientState;
import dev.theredstonee.trsclient.core.intro.FpsModeChooser;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Client-Sync: hält die TRS-Client-Einstellungen (Module, HUD-Profile, TRS-Tasten, Einführung, „NEU“-Stand) je
 * TRS-Konto auf allen PCs gleich (Dokument {@code client}, siehe {@link ClientDoc}) und übernimmt Thema, Akzent und
 * Sprache aus den Launcher-Einstellungen des Kontos (Dokument {@code settings}) live ins Spiel.
 *
 * <ul>
 *   <li>Nur mit Einwilligung im Launcher ({@code trs-api.json}), eingeschaltetem Modul „TRS-Online“ und Schalter
 *   „Mit TRS-Konto synchronisieren“.</li>
 *   <li>Abgleich nach der Anmeldung, alle 5 Minuten und etwa 3 s nach einer lokalen Änderung (die Config wird ohnehin
 *   im Hintergrund gespeichert – {@link ConfigStore.Listener}). Vor jedem Hochladen wird der Stand des Kontos geholt
 *   und je Abschnitt zusammengeführt ({@link ClientMerge}); bei {@code 409 stale} erneut.</li>
 *   <li>Alles Netz läuft im Thread „TRS-Sync“; der Spiel-Thread ruft nur {@link #tick} (billig) und wendet fertige
 *   Ergebnisse an. Offline/Fehler: still, mit wachsendem Abstand neu versuchen.</li>
 *   <li>Kontowechsel: das neue Konto wird abgeglichen, sein Stand gewinnt beim ersten Mal.</li>
 * </ul>
 */
public final class ClientSync {
	public static final long DEBOUNCE_MS = 3000L;
	public static final long PULL_INTERVAL_MS = 5 * 60_000L;
	static final long[] BACKOFF_MS = {30_000L, 2 * 60_000L, 10 * 60_000L, 30 * 60_000L};

	/** Zustand für das Menü. */
	public enum Status {
		/** Schalter/Modul aus. */
		OFF,
		/** Keine Einwilligung im Launcher. */
		NO_CONSENT,
		/** Noch nicht bei der TRS API angemeldet. */
		WAITING,
		SYNCING,
		SYNCED,
		/** Nicht erreichbar – später erneut. */
		OFFLINE,
		/** Dokument von einer neueren Client-Version – hier nur lesen. */
		NEWER_CLIENT
	}

	/** Was der Sync von der TRS-Anmeldung braucht (Tests: Attrappe). */
	public interface Online {
		boolean consent();

		/** TRS-Token (null = nicht angemeldet). */
		String token();

		/** UUID des angemeldeten Kontos (32 Hex) oder null. */
		String uuid();

		/** 401 bekommen – neu anmelden. */
		void rejected(String token);
	}

	private enum Kind {
		PULL, LOCAL
	}

	private static volatile ClientSync instance;

	private final TrsModules modules;
	private final ClientDoc doc;
	private final SyncApi api;
	private final SyncState state;
	private final Online online;
	private final Path configDir;
	private final String modVersion;
	private final Executor executor;
	private final Consumer<String> log;
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<Runnable>();

	// Spiel-Thread:
	private ConfigStore store;
	private boolean lookInitialized;
	private long fileLookAt = -1;
	private long appliedLookAt = -1;
	private String currentUuid;
	private boolean forceRemote;
	private boolean inFlight;
	private long nextPullAt;
	private long nextAllowedAt;
	private int failures;
	private String firstRoundFor;
	private SyncState.Look lookUpload;
	private volatile Status status = Status.OFF;
	private volatile long lastSyncAt;
	/** Uhrzeit des letzten Ticks (Wartezeiten beziehen sich auf die Tick-Uhr). */
	private long tickNow;

	// Beliebiger Thread (Config-Beobachter):
	private volatile boolean localDirty;
	private volatile long localChangeAt;
	private volatile long saveCount;

	public ClientSync(TrsModules modules, Online online, SyncApi api, SyncState state, Path configDir, String modVersion,
			Executor executor, Consumer<String> log) {
		this.modules = modules;
		this.doc = new ClientDoc(modules.registry);
		this.online = online;
		this.api = api;
		this.state = state;
		this.configDir = configDir;
		this.modVersion = modVersion;
		this.executor = executor;
		this.log = log != null ? log : new Consumer<String>() {
			@Override
			public void accept(String s) {
			}
		};
	}

	/** Standard-Aufbau im Spiel: eigener Thread, Zustand unter {@code <configDir>/trsclient/sync-state.json}. */
	public static ClientSync create(TrsModules modules, final TrsOnline trs, Path configDir, String userAgent, String modVersion,
			Consumer<String> log) {
		ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8), r -> {
			Thread t = new Thread(r, "TRS-Sync");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});
		ex.allowCoreThreadTimeOut(true);
		Online online = new Online() {
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
		SyncState state = new SyncState(configDir == null ? null : configDir.resolve("trsclient").resolve("sync-state.json")).load();
		ClientSync sync = new ClientSync(modules, online, new SyncApi(new Http.UrlConnection(userAgent), trs.apiBase()), state,
				configDir, modVersion, ex, log);
		instance = sync;
		dev.theredstonee.trsclient.core.ui.menu.ModulePanel.Registry.set(modules.trsOnline, new SyncPanel(sync));
		return sync;
	}

	/** Der Sync des laufenden Spiels (null ohne Online-Funktionen, z. B. Forge 1.7.10). */
	public static ClientSync get() {
		return instance;
	}

	/** Nur Tests: Instanz setzen. */
	public static void setInstance(ClientSync sync) {
		instance = sync;
	}

	/** Beobachtet diese Config (Tests; im Spiel automatisch {@link ConfigStore#active()}). */
	public void attach(ConfigStore configStore) {
		if (configStore == null || configStore == store) return;
		store = configStore;
		configStore.setListener(new ConfigStore.Listener() {
			@Override
			public void saved(TrsConfig snapshot) {
				saveCount++;
				localDirty = true;
				localChangeAt = System.currentTimeMillis();
			}
		});
	}

	// --- Zustand ---

	/** Darf/soll gerade synchronisiert werden (Einwilligung, Modul, Schalter)? */
	public boolean enabled() {
		return online.consent() && modules.trsOnline.isEnabled() && modules.syncClient.get();
	}

	public Status status() {
		return status;
	}

	/** Letzter erfolgreicher Abgleich (ms, 0 = noch keiner in dieser Sitzung). */
	public long lastSyncAt() {
		return lastSyncAt;
	}

	/**
	 * Ist der erste Abgleich des aktuellen Kontos durch (erfolgreich oder nicht)? Die Einführung wartet kurz darauf,
	 * damit sie nicht auf einem zweiten PC erscheint, auf dem das Konto sie schon erledigt hat.
	 */
	public boolean firstRoundDone() {
		return currentUuid != null && currentUuid.equals(firstRoundFor);
	}

	/** Nächsten Abgleich sofort anstoßen („Jetzt synchronisieren“). */
	public void syncNow() {
		nextPullAt = 0;
		nextAllowedAt = 0;
	}

	// --- Aussehen (Thema, Akzent, Sprache) ---

	/**
	 * Im Client gewählt (Einführung): sofort anwenden, lokal merken und – mit Sync – in die Launcher-Einstellungen des
	 * Kontos schreiben, damit Launcher und andere PCs folgen.
	 */
	public void chooseLook(String theme, String accent, String language, long now) {
		modules.clientState.chooseLook(theme, accent, language, now);
		applyLook(theme, accent, language);
		appliedLookAt = now;
		SyncState.Look l = new SyncState.Look();
		l.theme = theme;
		l.accent = accent;
		l.language = language;
		l.at = now;
		lookUpload = l;
	}

	/** Beim ersten Tick: die neueste Quelle (Launcher-Datei, Wahl im Client, Konto) gilt. */
	void initLook() {
		lookInitialized = true;
		fileLookAt = fileTime(configDir == null ? null : configDir.resolve("trsclient").resolve("launcher-theme.json"));
		appliedLookAt = fileLookAt;
		ClientState cs = modules.clientState;
		String last = state.lastAccount();
		SyncState.Look remote = last == null ? null : state.look(last);
		if (cs.lookAt() > 0 && (remote == null || cs.lookAt() >= remote.at)) {
			offerLook(cs.lookTheme(), cs.lookAccent(), cs.lookLanguage(), cs.lookAt());
		} else if (remote != null) {
			offerLook(remote.theme, remote.accent, remote.language, remote.at);
		}
	}

	/** Übernimmt ein Aussehen, wenn es neuer ist als alles bisher Angewandte (inkl. Launcher-Datei). */
	private void offerLook(String theme, String accent, String language, long at) {
		if (at <= appliedLookAt) return;
		appliedLookAt = at;
		applyLook(theme, accent, language);
	}

	/** Setzt Thema/Akzent/Sprache sofort (null = unverändert). */
	public static void applyLook(String theme, String accent, String language) {
		Theme current = Theme.get();
		String t = theme != null ? theme : current.themeName;
		String a = accent != null ? accent : current.accentName;
		Theme.set(Theme.of(t, a, null));
		if (language != null && I18n.supported(language) != null) I18n.override(language);
	}

	private static long fileTime(Path file) {
		try {
			return file != null && Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}

	// --- Tick (Spiel-Thread) ---

	/** Einmal je Client-Tick; blockiert nie. */
	public void tick(long now) {
		tickNow = now;
		Runnable r;
		while ((r = results.poll()) != null) r.run();
		if (!lookInitialized) initLook();
		if (store == null || store != ConfigStore.active()) {
			ConfigStore active = ConfigStore.active();
			if (active != null && store == null) attach(active);
		}
		if (!online.consent()) {
			status = Status.NO_CONSENT;
			return;
		}
		if (!modules.trsOnline.isEnabled() || !modules.syncClient.get()) {
			status = Status.OFF;
			return;
		}
		String token = online.token();
		String uuid = online.uuid();
		if (token == null || uuid == null) {
			if (status != Status.OFFLINE) status = Status.WAITING;
			return;
		}
		if (!uuid.equals(currentUuid)) {
			// Anderes Konto (oder erster Abgleich dieser Sitzung): sofort abgleichen; nach einem Wechsel gewinnt das Konto.
			currentUuid = uuid;
			String last = state.lastAccount();
			forceRemote = last != null && !last.equals(uuid);
			nextPullAt = 0;
			nextAllowedAt = 0;
			failures = 0;
			firstRoundFor = null;
			if (status == Status.SYNCED || status == Status.NEWER_CLIENT) status = Status.WAITING;
		}
		if (inFlight || now < nextAllowedAt) return;
		boolean pull = now >= nextPullAt || lookUpload != null;
		boolean local = localDirty && now - localChangeAt >= DEBOUNCE_MS;
		if (!pull && !local) return;
		start(pull ? Kind.PULL : Kind.LOCAL, token, uuid, now);
	}

	private void start(final Kind kind, final String token, final String uuid, final long now) {
		final TrsConfig snapshot = modules.registry.capture();
		final long savesAtSnapshot = saveCount;
		final boolean force = forceRemote;
		final SyncState.Look look = lookUpload;
		final long changedAt = localChangeAt > 0 ? localChangeAt : System.currentTimeMillis();
		localDirty = false;
		inFlight = true;
		if (kind == Kind.PULL) nextPullAt = now + PULL_INTERVAL_MS;
		if (status != Status.SYNCED) status = Status.SYNCING;
		try {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					round(kind, token, uuid, snapshot, savesAtSnapshot, force, look, changedAt);
				}
			});
		} catch (RejectedExecutionException e) {
			inFlight = false;
		}
	}

	// --- Abgleich (Sync-Thread) ---

	private void round(Kind kind, final String token, final String uuid, TrsConfig snapshot, final long savesAtSnapshot,
			final boolean force, final SyncState.Look look, final long changedAt) {
		final long now = System.currentTimeMillis();
		try {
			if (kind == Kind.LOCAL && look == null && !force && state.known(uuid) && !changedSinceSync(uuid, snapshot, changedAt)) {
				post(new Runnable() {
					@Override
					public void run() {
						inFlight = false;
					}
				});
				return;
			}
			SyncApi.Snapshot remote = api.get(token);
			SyncState.Look remoteLook = look(remote.settings);
			if (look != null) remoteLook = uploadLook(token, look, remote.settings, remoteLook);
			if (remoteLook != null) state.setLook(uuid, remoteLook);

			SyncApi.Doc rc = remote.client;
			ClientMerge.Result res = ClientMerge.merge(doc, state, uuid, snapshot, rc == null ? null : rc.data,
					rc == null ? -1 : rc.updatedAtMillis(), now, changedAt, force, modVersion);
			for (int attempt = 0; res.upload != null && attempt < 3; attempt++) {
				try {
					api.putClient(token, res.upload, res.uploadAt);
					break;
				} catch (SyncApi.StaleException stale) {
					SyncApi.Doc cur = stale.current;
					res = ClientMerge.merge(doc, state, uuid, snapshot, cur == null ? null : cur.data,
							cur == null ? -1 : cur.updatedAtMillis(), System.currentTimeMillis(), changedAt, force, modVersion);
					if (attempt == 2) throw new IOException("stale");
				}
			}
			if (!res.readOnly) {
				for (Map.Entry<String, SyncState.Mark> e : res.synced.entrySet()) {
					if (e.getValue().hash != null) state.setSynced(uuid, e.getKey(), e.getValue().hash, e.getValue().at);
				}
			}
			state.touched(uuid, now, true);
			state.save();
			final ClientMerge.Result done = res;
			final SyncState.Look lookDone = remoteLook;
			post(new Runnable() {
				@Override
				public void run() {
					finished(uuid, done, lookDone, savesAtSnapshot, look);
				}
			});
		} catch (final ApiException e) {
			post(new Runnable() {
				@Override
				public void run() {
					failed(uuid, e.unauthorized() ? token : null, e.rateLimited() ? e.retryAfterMs() : 0,
							"HTTP " + e.status() + " " + e.code(), e.status() >= 400 && e.status() < 500 && !e.unauthorized() && !e.rateLimited());
				}
			});
		} catch (final IOException | RuntimeException e) {
			post(new Runnable() {
				@Override
				public void run() {
					failed(uuid, null, 0, e.getClass().getSimpleName(), e instanceof RuntimeException);
				}
			});
		}
	}

	/** Hat sich seit dem letzten Abgleich lokal etwas geändert (ohne Netz geprüft)? */
	private boolean changedSinceSync(String uuid, TrsConfig snapshot, long now) {
		boolean changed = false;
		Map<String, JsonObject> sections = doc.sections(snapshot);
		for (String s : ClientDoc.LWW) {
			String hash = ClientDoc.hash(sections.get(s));
			state.observeLocal(s, hash, now);
			SyncState.Mark m = state.synced(uuid, s);
			if (m == null || !hash.equals(m.hash)) changed = true;
		}
		SyncState.Mark intro = state.synced(uuid, ClientDoc.INTRO);
		SyncState.Mark seen = state.synced(uuid, ClientDoc.SEEN);
		if (intro == null || !intro.hash.equals(ClientMerge.introHash(snapshot.clientState))) changed = true;
		if (seen == null || !seen.hash.equals(ClientMerge.seenHash(snapshot.clientState))) changed = true;
		return changed;
	}

	private static SyncState.Look look(SyncApi.Doc settings) {
		if (settings == null) return null;
		SyncState.Look l = new SyncState.Look();
		l.theme = str(settings.data, "theme");
		l.accent = str(settings.data, "accent");
		l.language = str(settings.data, "language");
		l.at = settings.updatedAtMillis();
		return l.at > 0 ? l : null;
	}

	/** Schreibt die im Client gewählte Aussehen-Wahl in die Launcher-Einstellungen des Kontos. */
	private SyncState.Look uploadLook(String token, SyncState.Look look, SyncApi.Doc current, SyncState.Look currentLook)
			throws IOException, ApiException {
		if (currentLook != null && currentLook.at > look.at) return currentLook;
		JsonObject data = new JsonObject();
		if (current != null) {
			for (Map.Entry<String, com.google.gson.JsonElement> e : current.data.entrySet()) {
				if (e.getKey().equals("theme") || e.getKey().equals("accent") || e.getKey().equals("language")) data.add(e.getKey(), e.getValue());
			}
		}
		if (safe(look.theme)) data.addProperty("theme", look.theme);
		if (safe(look.accent)) data.addProperty("accent", look.accent);
		if (safe(look.language)) data.addProperty("language", look.language);
		long at = Math.max(look.at, currentLook == null ? 0 : currentLook.at);
		try {
			api.putSettings(token, data, SyncTime.iso(at));
			SyncState.Look l = new SyncState.Look();
			l.theme = str(data, "theme");
			l.accent = str(data, "accent");
			l.language = str(data, "language");
			l.at = at;
			return l;
		} catch (SyncApi.StaleException stale) {
			return look(stale.current);
		}
	}

	private static boolean safe(String s) {
		return s != null && s.matches("[A-Za-z0-9_-]{1,16}");
	}

	private static String str(JsonObject o, String key) {
		com.google.gson.JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private void post(Runnable r) {
		results.add(r);
	}

	// --- Ergebnisse (Spiel-Thread) ---

	private void finished(String uuid, ClientMerge.Result res, SyncState.Look look, long savesAtSnapshot, SyncState.Look sentLook) {
		inFlight = false;
		failures = 0;
		if (sentLook != null && sentLook == lookUpload) lookUpload = null;
		if (!uuid.equals(currentUuid)) return;
		firstRoundFor = uuid;
		forceRemote = false;
		lastSyncAt = System.currentTimeMillis();
		status = res.readOnly ? Status.NEWER_CLIENT : Status.SYNCED;
		if (look != null) offerLook(look.theme, look.accent, look.language, look.at);
		if (res.merged == null) return;
		if (saveCount != savesAtSnapshot) {
			// Während des Abgleichs lokal geändert: nicht darüberschreiben, gleich neu abgleichen.
			nextPullAt = 0;
			return;
		}
		modules.registry.apply(res.merged);
		TrsConfig after = modules.registry.capture();
		Map<String, JsonObject> sections = doc.sections(after);
		long now = System.currentTimeMillis();
		for (Map.Entry<String, ClientMerge.Decision> e : res.decisions.entrySet()) {
			if (e.getValue() != ClientMerge.Decision.APPLY_REMOTE) continue;
			String hash = ClientDoc.hash(sections.get(e.getKey()));
			state.observeLocal(e.getKey(), hash, now);
			state.setSynced(uuid, e.getKey(), hash, res.synced.get(e.getKey()).at);
		}
		state.setSynced(uuid, ClientDoc.INTRO, ClientMerge.introHash(after.clientState), 0);
		state.setSynced(uuid, ClientDoc.SEEN, ClientMerge.seenHash(after.clientState), 0);
		if (res.fpsModeChanged != null) {
			try {
				FpsModeChooser.get().apply(modules, res.fpsModeChanged);
			} catch (RuntimeException ex) {
				log.accept("TRS-Sync: FPS-Modus nicht anwendbar: " + ex);
			}
		}
		saveConfig();
		try {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					state.save();
				}
			});
		} catch (RejectedExecutionException ignored) {
			// nächstes Mal
		}
		log.accept("TRS-Sync: Einstellungen vom TRS-Konto übernommen (" + res.decisions + ")");
	}

	private void failed(String uuid, String rejectedToken, long retryAfterMs, String reason, boolean permanent) {
		inFlight = false;
		if (rejectedToken != null) {
			online.rejected(rejectedToken);
			nextAllowedAt = tickNow + 2000L;
			return;
		}
		if (uuid.equals(currentUuid)) firstRoundFor = uuid;
		long wait = permanent ? BACKOFF_MS[BACKOFF_MS.length - 1] : BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
		failures++;
		nextAllowedAt = tickNow + Math.max(wait, retryAfterMs);
		// Nach der Wartezeit vollständig neu abgleichen (deckt auch nicht hochgeladene lokale Änderungen ab).
		nextPullAt = nextAllowedAt;
		status = Status.OFFLINE;
		if (failures <= 2 || permanent) log.accept("TRS-Sync: nicht möglich (" + reason + "), neuer Versuch in " + wait / 1000 + " s");
	}

	/** Speichert nach dem Übernehmen (der Beobachter merkt: nichts Neues – Hashes gleich). */
	private void saveConfig() {
		ConfigStore s = store;
		if (s == null) return;
		try {
			s.saveLater(modules.registry);
		} catch (IOException e) {
			log.accept("TRS-Sync: Config nicht gespeichert: " + e.getMessage());
		}
	}
}
