package dev.theredstonee.trsclient.core.account;

import dev.theredstonee.trsclient.core.link.TrsLink;
import dev.theredstonee.trsclient.core.online.Http;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kontowechsel im Spiel ohne Neustart.
 *
 * <ul>
 * <li>Über den TRS Launcher gestartet: alle Konten des Launchers; frische Tokens kommen über die gesicherte
 * Verbindung, „Konto hinzufügen“ meldet im Launcher an (Browser) und speichert dort.</li>
 * <li>Ohne TRS Launcher: das Startkonto + im Spiel angemeldete Konten dieser Instanz ({@link AccountVault},
 * Refresh-Token verschlüsselt). Anmeldung mit der Azure-App des TRS Launchers ({@link MsAuth}).</li>
 * </ul>
 * Gewechselt wird nur ohne laufende Welt. Alle Netz-/Plattenarbeit läuft im Thread „TRS-Accounts“; das Spiel
 * liest nur {@link #state()}.
 */
public final class AccountManager {
	public enum Mode {
		/** Konten des Launchers. */
		LAUNCHER,
		/** Vom Launcher gestartet, Verbindung steht noch nicht. */
		CONNECTING,
		/** Ohne Launcher: Startkonto + eigene Konten. */
		LOCAL
	}

	public enum Task {
		NONE, LOADING, SWITCHING, ADDING_LAUNCHER, ADDING_BROWSER, ADDING_CODE
	}

	/** Unveränderlicher Stand für die Oberfläche. */
	public static final class State {
		public final Mode mode;
		public final List<GameAccount> accounts;
		/** UUID des Kontos, mit dem das Spiel gerade läuft. */
		public final String current;
		public final Task task;
		/** Konto, zu dem gerade gewechselt wird. */
		public final String taskAccount;
		/** Device-Code-Anmeldung: Code + Seite. */
		public final String userCode;
		public final String verificationUri;
		/** Übersetzungsschlüssel der letzten Meldung (oder null) und ihre Argumente. */
		public final String message;
		public final Object[] messageArgs;
		public final boolean error;

		State(Mode mode, List<GameAccount> accounts, String current, Task task, String taskAccount, String userCode,
				String verificationUri, String message, Object[] messageArgs, boolean error) {
			this.mode = mode;
			this.accounts = accounts;
			this.current = current;
			this.task = task;
			this.taskAccount = taskAccount;
			this.userCode = userCode;
			this.verificationUri = verificationUri;
			this.message = message;
			this.messageArgs = messageArgs;
			this.error = error;
		}

		State with(Task task, String taskAccount) {
			return new State(mode, accounts, current, task, taskAccount, null, null, message, messageArgs, error);
		}

		State message(String key, boolean error, Object... args) {
			return new State(mode, accounts, current, task, taskAccount, userCode, verificationUri, key, args, error);
		}

		public boolean busy() {
			return task != Task.NONE && task != Task.LOADING;
		}
	}

	private static volatile AccountManager instance;

	private final AccountPlatform platform;
	private final TrsLink link;
	private final LauncherAccounts launcher;
	private final AccountVault vault;
	private final MsAuth auth;
	private final ExecutorService worker;
	private final AtomicReference<State> state = new AtomicReference<State>();
	/** Abbruch-Marke der laufenden Anmeldung (je Anmeldung neu). */
	private volatile AtomicBoolean cancel = new AtomicBoolean();
	private volatile MsAuth.BrowserLogin browser;
	/** Das Konto, mit dem das Spiel gestartet wurde (Token nur im Speicher). */
	private volatile SessionData startup;
	private List<GameAccount> launcherList = Collections.emptyList();
	private final TrsLink.Listener linkListener = new TrsLink.Listener() {
		@Override
		public void onConnected(TrsLink l, boolean accounts) {
			if (accounts) refresh();
		}

		@Override
		public void onLine(TrsLink l, TrsLink.Line line) {
			if ("accountsChanged".equals(line.type)) refresh();
		}

		@Override
		public void onDisconnected(TrsLink l) {
			publishList();
		}
	};

	AccountManager(AccountPlatform platform, TrsLink link, Http http, Path keyDir, ExecutorService worker) {
		this.platform = platform;
		this.link = link;
		this.launcher = link == null ? null : new LauncherAccounts(link);
		this.vault = new AccountVault(platform.configDir(), keyDir);
		this.auth = new MsAuth(http);
		this.worker = worker;
		state.set(new State(Mode.LOCAL, Collections.<GameAccount>emptyList(), null, Task.NONE, null, null, null, null,
				new Object[0], false));
		if (link != null) link.addListener(linkListener);
	}

	/** Beim Start je Loader (nach {@code Clips.init}, damit die gemeinsame Verbindung steht). */
	public static synchronized AccountManager init(AccountPlatform platform) {
		if (instance == null) {
			ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "TRS-Accounts");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY + 1);
				return t;
			});
			instance = new AccountManager(platform, TrsLink.shared(), new Http.UrlConnection(platform.userAgent()),
					SecretBox.defaultKeyDir(), worker);
		}
		return instance;
	}

	/** null vor {@link #init}. */
	public static AccountManager get() {
		return instance;
	}

	/** Nur für Tests. */
	static void set(AccountManager manager) {
		instance = manager;
	}

	public State state() {
		return state.get();
	}

	/**
	 * Aktuelle Spielsitzung (mit Zugangs-Token – nur für Mojang-Dienste im Hintergrund, nie loggen) oder null.
	 */
	public SessionData currentSession() {
		try {
			return platform.current();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** User-Agent für Netzabrufe der Oberfläche (Gesichter). */
	public String userAgent() {
		return platform.userAgent();
	}

	private void update(java.util.function.UnaryOperator<State> change) {
		State prev;
		State next;
		do {
			prev = state.get();
			next = change.apply(prev);
		} while (!state.compareAndSet(prev, next));
	}

	private void submit(Runnable task) {
		try {
			worker.execute(() -> {
				try {
					task.run();
				} catch (RuntimeException e) {
					platform.log("Konten: " + e.getClass().getSimpleName());
					update(s -> s.with(Task.NONE, null).message("accounts.error.error", true));
				}
			});
		} catch (RejectedExecutionException e) {
			// beendet
		}
	}

	private Mode mode() {
		if (launcher != null && launcher.available()) return Mode.LAUNCHER;
		if (link != null && link.launchedByLauncher()) return Mode.CONNECTING;
		return Mode.LOCAL;
	}

	/** Beim ersten Blick auf das Spiel: das Startkonto merken (vor jedem Wechsel). */
	private void captureStartup() {
		if (startup != null) return;
		SessionData now = platform.current();
		if (now != null && now.uuid != null && now.name != null) startup = now;
	}

	/** Kontobildschirm geöffnet: Liste neu laden. */
	public void open() {
		captureStartup();
		refresh();
	}

	/** Liste neu laden (Launcher bzw. Instanz). */
	public void refresh() {
		submit(() -> {
			captureStartup();
			if (launcher != null && launcher.available()) {
				update(s -> s.task == Task.NONE ? s.with(Task.LOADING, null) : s);
				try {
					launcherList = launcher.list();
				} catch (LauncherAccounts.LinkException e) {
					update(s -> s.message("accounts.error." + e.code, true));
				}
				update(s -> s.task == Task.LOADING ? s.with(Task.NONE, null) : s);
			}
			publishList();
		});
	}

	/** Liste aus Launcher/Instanz + Startkonto zusammenstellen. */
	private void publishList() {
		Mode mode = mode();
		List<GameAccount> out = new ArrayList<GameAccount>();
		if (mode == Mode.LAUNCHER) {
			out.addAll(launcherList);
		} else {
			for (AccountVault.Stored s : vault.list()) {
				out.add(new GameAccount(s.uuid, s.name, s.skinUrl, GameAccount.Source.LOCAL, false, s.locked));
			}
		}
		SessionData start = startup;
		if (start != null && start.uuid != null && !contains(out, start.uuid)) {
			out.add(0, new GameAccount(start.uuid, start.name, null, GameAccount.Source.STARTUP, false, false));
		}
		SessionData cur = platform.current();
		final String current = cur == null ? null : cur.uuid;
		final List<GameAccount> list = Collections.unmodifiableList(out);
		update(s -> new State(mode, list, current, s.task, s.taskAccount, s.userCode, s.verificationUri, s.message,
				s.messageArgs, s.error));
	}

	private static boolean contains(List<GameAccount> list, String uuid) {
		for (GameAccount g : list) {
			if (g.uuid.equals(uuid)) return true;
		}
		return false;
	}

	private GameAccount find(String uuid) {
		for (GameAccount g : state().accounts) {
			if (g.uuid.equals(uuid)) return g;
		}
		return null;
	}

	/** Zu einem Konto wechseln (nur ohne Welt). */
	public void switchTo(final String uuid) {
		if (state().busy()) return;
		if (platform.inWorld()) {
			update(s -> s.message("accounts.error.inWorld", true));
			return;
		}
		update(s -> s.with(Task.SWITCHING, uuid).message(null, false));
		submit(() -> {
			try {
				GameAccount target = find(uuid);
				if (target == null) throw new SwitchException("unknown_account");
				SessionData session = obtain(target);
				apply(session);
				platform.log("Konto gewechselt: " + session.name);
				publishList();
				update(s -> s.with(Task.NONE, null).message("accounts.switched", false, session.name));
			} catch (SwitchException e) {
				update(s -> s.with(Task.NONE, null).message("accounts.error." + e.code, true));
			}
		});
	}

	private static final class SwitchException extends Exception {
		private static final long serialVersionUID = 1L;
		final String code;

		SwitchException(String code) {
			super(code);
			this.code = code;
		}
	}

	/** Frische Sitzung für ein Konto (Konto-Thread). */
	private SessionData obtain(GameAccount target) throws SwitchException {
		switch (target.source) {
			case STARTUP: {
				SessionData s = startup;
				if (s == null || !s.uuid.equals(target.uuid)) throw new SwitchException("unknown_account");
				return s;
			}
			case LAUNCHER:
				try {
					return launcher.session(target.uuid);
				} catch (LauncherAccounts.LinkException e) {
					throw new SwitchException(e.code);
				}
			default:
				return refreshLocal(target.uuid);
		}
	}

	private SessionData refreshLocal(String uuid) throws SwitchException {
		String refresh = vault.refreshToken(uuid);
		if (refresh == null) throw new SwitchException("locked");
		try {
			MsAuth.Tokens tokens = auth.refresh(refresh);
			MsAuth.McProfile profile = auth.minecraftLogin(tokens.accessToken);
			if (!profile.uuid.equals(uuid)) throw new SwitchException("accountMismatch");
			// Microsoft dreht Refresh-Tokens – das neue speichern.
			vault.put(profile.uuid, profile.name, profile.skinUrl, profile.xuid, tokens.refreshToken);
			return new SessionData(profile.uuid, profile.name, profile.accessToken, profile.xuid);
		} catch (MsAuth.AuthException e) {
			throw new SwitchException(e.code);
		} catch (java.io.IOException e) {
			throw new SwitchException("network");
		} catch (java.security.GeneralSecurityException e) {
			throw new SwitchException("storage");
		}
	}

	/** Im Spiel-Thread einsetzen und auf das Ergebnis warten. */
	private void apply(final SessionData session) throws SwitchException {
		if (!session.valid()) throw new SwitchException("error");
		final Object prepared;
		try {
			prepared = platform.prepare(session);
		} catch (Exception e) {
			platform.log("Sitzung konnte nicht vorbereitet werden: " + e.getClass().getSimpleName());
			throw new SwitchException("swapFailed");
		}
		final CountDownLatch done = new CountDownLatch(1);
		final String[] error = new String[1];
		platform.execute(() -> {
			try {
				if (platform.inWorld()) error[0] = "inWorld";
				else platform.apply(session, prepared);
			} catch (Throwable t) {
				platform.log("Sitzung konnte nicht eingesetzt werden: " + t.getClass().getSimpleName());
				error[0] = "swapFailed";
			} finally {
				done.countDown();
			}
		});
		try {
			if (!done.await(20, TimeUnit.SECONDS)) throw new SwitchException("swapFailed");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SwitchException("cancelled");
		}
		if (error[0] != null) throw new SwitchException(error[0]);
	}

	/**
	 * Konto hinzufügen: mit Launcher dort (Browser), sonst hier im Spiel über den Browser. Danach wird gleich
	 * gewechselt (ohne Welt).
	 */
	public void add() {
		if (state().busy()) return;
		final AtomicBoolean token = new AtomicBoolean();
		cancel = token;
		if (mode() == Mode.LAUNCHER) {
			update(s -> s.with(Task.ADDING_LAUNCHER, null).message("accounts.addInLauncher", false));
			submit(() -> {
				try {
					GameAccount added = launcher.add();
					launcherList = launcher.list();
					publishList();
					update(s -> s.with(Task.NONE, null).message("accounts.added", false, added.name));
					if (!platform.inWorld()) switchTo(added.uuid);
				} catch (LauncherAccounts.LinkException e) {
					if (!token.get()) update(s -> s.with(Task.NONE, null).message("accounts.error." + e.code, !"cancelled".equals(e.code)));
				}
			});
			return;
		}
		update(s -> s.with(Task.ADDING_BROWSER, null).message("accounts.browserOpened", false));
		submit(() -> {
			try (MsAuth.BrowserLogin login = auth.startBrowser()) {
				browser = login;
				platform.openUrl(login.authorizeUrl);
				MsAuth.Tokens tokens = auth.finishBrowser(login, token::get);
				finishLocalLogin(tokens);
			} catch (MsAuth.AuthException e) {
				if (cancel == token) update(s -> s.with(Task.NONE, null).message("accounts.error." + e.code, !"cancelled".equals(e.code)));
			} catch (java.io.IOException e) {
				// Abgebrochen (Server geschlossen) oder ersetzt durch die Code-Anmeldung: still.
				if (cancel == token && !token.get()) update(s -> s.with(Task.NONE, null).message("accounts.error.network", true));
				else if (cancel == token) update(s -> s.with(Task.NONE, null).message("accounts.error.cancelled", false));
			} finally {
				if (browser != null && cancel == token) browser = null;
			}
		});
	}

	/** Ohne Launcher: Anmeldung mit Code (für Browser auf einem anderen Gerät). */
	public void addWithCode() {
		if (mode() == Mode.LAUNCHER) return;
		if (state().busy() && state().task != Task.ADDING_BROWSER) return;
		cancelBrowser();
		final AtomicBoolean token = new AtomicBoolean();
		cancel = token;
		update(s -> s.with(Task.ADDING_CODE, null).message("accounts.codeLoading", false));
		submit(() -> {
			// Ein laufender Browser-Login endet zuerst (seine Marke ist abgebrochen und nicht mehr aktuell).
			try {
				MsAuth.DeviceCode code = auth.deviceCodeStart();
				update(s -> new State(s.mode, s.accounts, s.current, Task.ADDING_CODE, null, code.userCode,
						code.verificationUri, "accounts.codeHint", new Object[0], false));
				MsAuth.Tokens tokens = auth.deviceCodePoll(code, token::get, MsAuth.REAL_SLEEP);
				finishLocalLogin(tokens);
			} catch (MsAuth.AuthException e) {
				if (cancel == token) update(s -> s.with(Task.NONE, null).message("accounts.error." + e.code, !"cancelled".equals(e.code)));
			} catch (java.io.IOException e) {
				if (cancel == token) update(s -> s.with(Task.NONE, null).message("accounts.error.network", true));
			}
		});
	}

	private void cancelBrowser() {
		MsAuth.BrowserLogin b = browser;
		if (b != null) {
			cancel.set(true);
			b.close();
			browser = null;
		}
	}

	private void finishLocalLogin(MsAuth.Tokens tokens) throws MsAuth.AuthException, java.io.IOException {
		MsAuth.McProfile profile = auth.minecraftLogin(tokens.accessToken);
		try {
			vault.put(profile.uuid, profile.name, profile.skinUrl, profile.xuid, tokens.refreshToken);
		} catch (java.security.GeneralSecurityException e) {
			throw new MsAuth.AuthException("storage");
		}
		publishList();
		SessionData session = new SessionData(profile.uuid, profile.name, profile.accessToken, profile.xuid);
		try {
			apply(session);
			publishList();
			update(s -> s.with(Task.NONE, null).message("accounts.switched", false, session.name));
		} catch (SwitchException e) {
			update(s -> s.with(Task.NONE, null).message("accounts.added", false, session.name));
		}
	}

	/** Laufende Anmeldung abbrechen. */
	public void cancel() {
		cancel.set(true);
		MsAuth.BrowserLogin b = browser;
		if (b != null) b.close();
		// Anzeige sofort zurück (der Launcher bricht seine Anmeldung selbst nach 5 min ab).
		update(x -> x.task == Task.SWITCHING || x.task == Task.NONE ? x : x.with(Task.NONE, null).message(null, false));
	}

	/** Im Spiel hinzugefügtes Konto aus dieser Instanz entfernen. */
	public void remove(final String uuid) {
		GameAccount g = find(uuid);
		if (g == null || !g.removable() || state().busy()) return;
		submit(() -> {
			try {
				vault.remove(uuid);
				update(s -> s.message("accounts.removed", false, g.name));
			} catch (java.io.IOException e) {
				update(s -> s.message("accounts.error.storage", true));
			}
			publishList();
		});
	}

	/** Device-Code-Seite (erneut) im Browser öffnen. */
	public void openVerificationPage() {
		String uri = state().verificationUri;
		if (uri != null && MsAuth.trustedVerificationUri(uri)) platform.openUrl(uri);
	}

	/** Darf gerade gewechselt werden (keine Welt)? */
	public boolean canSwitch() {
		return !platform.inWorld();
	}

	/** Nur für Tests: warten, bis die Arbeit im Konto-Thread erledigt ist. */
	void drain() throws InterruptedException {
		final CountDownLatch done = new CountDownLatch(1);
		worker.execute(done::countDown);
		done.await(30, TimeUnit.SECONDS);
	}
}
