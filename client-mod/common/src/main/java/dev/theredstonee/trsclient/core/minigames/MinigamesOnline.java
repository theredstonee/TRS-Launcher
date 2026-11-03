package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsOnline;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Rangliste im Hintergrund: Der Render-Thread stößt an und fragt je Bild nach, ob das Ergebnis da ist. Ohne Token oder
 * Verbindung läuft die Partie lokal, nur ohne Wertung.
 */
public final class MinigamesOnline {
	/** Ergebnis, das später eintrifft. */
	public static final class Pending<T> {
		private volatile boolean done;
		private volatile T value;

		public boolean done() {
			return done;
		}

		public T value() {
			return value;
		}

		void finish(T result) {
			value = result;
			done = true;
		}
	}

	/** Wie eine eingereichte Partie ausging. */
	public enum SubmitKind {
		RANKED, REJECTED, FAILED, LOCAL
	}

	/** Ausgang des Einreichens. */
	public static final class Submit {
		public final SubmitKind kind;
		public final int rank;
		public final long best;
		public final boolean improved;

		Submit(SubmitKind kind, int rank, long best, boolean improved) {
			this.kind = kind;
			this.rank = rank;
			this.best = best;
			this.improved = improved;
		}
	}

	/** Zustand der angezeigten Rangliste. */
	public enum BoardStatus {
		OK, NEEDS_TRS, ERROR
	}

	/** Rangliste oder Grund, warum sie fehlt. */
	public static final class BoardResult {
		public final BoardStatus status;
		public final MinigamesApi.Board board;

		BoardResult(BoardStatus status, MinigamesApi.Board board) {
			this.status = status;
			this.board = board;
		}
	}

	/** Woher Anmeldung und Adresse kommen; in Tests ersetzbar. */
	public interface Account {
		/** Token oder null. */
		String token();

		/** Adresse der TRS API oder null. */
		String apiBase();

		/** Der Server hat dieses Token abgelehnt. */
		void rejected(String token);
	}

	private static final class LiveAccount implements Account {
		@Override
		public String token() {
			TrsOnline online = TrsOnline.current();
			return online == null ? null : online.apiToken();
		}

		@Override
		public String apiBase() {
			TrsOnline online = TrsOnline.current();
			return online == null ? null : online.apiBase();
		}

		@Override
		public void rejected(String token) {
			TrsOnline online = TrsOnline.current();
			if (online != null) online.tokenRejected(token);
		}
	}

	private static ExecutorService worker;

	private static synchronized Executor worker() {
		if (worker == null) {
			worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "TRS-Minigames");
					t.setDaemon(true);
					return t;
				}
			});
		}
		return worker;
	}

	private final Account account;
	private final Http http;
	private final Executor executor;

	public MinigamesOnline(Account account, Http http, Executor executor) {
		this.account = account;
		this.http = http;
		this.executor = executor;
	}

	/** Wie im Spiel: Anmeldung aus {@link TrsOnline}, eigener Hintergrund-Thread. */
	public static MinigamesOnline live() {
		return new MinigamesOnline(new LiveAccount(), new Http.UrlConnection("TRS-Client"), worker());
	}

	private MinigamesApi api() {
		String base = account.apiBase();
		return base == null ? null : new MinigamesApi(http, base);
	}

	/** Startwert vom Server. Das Ergebnis ist null, wenn die Partie lokal läuft. */
	public Pending<MinigamesApi.Start> start(final String game) {
		final Pending<MinigamesApi.Start> out = new Pending<MinigamesApi.Start>();
		final String token = account.token();
		final MinigamesApi api = api();
		if (token == null || api == null) {
			out.finish(null);
			return out;
		}
		executor.execute(new Runnable() {
			@Override
			public void run() {
				MinigamesApi.Start start = null;
				try {
					start = api.start(token, game);
				} catch (ApiException e) {
					if (e.status() == 401) account.rejected(token);
				} catch (IOException | RuntimeException e) {
					// Spielen soll auch ohne Server gehen.
				}
				out.finish(start);
			}
		});
		return out;
	}

	/** Reicht eine beendete Partie ein. Ohne Kennung vom Server zählt sie nur lokal. */
	public Pending<Submit> submit(final MinigameSession.Finished run) {
		final Pending<Submit> out = new Pending<Submit>();
		final String token = account.token();
		final MinigamesApi api = api();
		if (run.runId == null || token == null || api == null || !Replay.validInputs(run.game, run.inputs)) {
			out.finish(new Submit(run.runId == null ? SubmitKind.LOCAL : SubmitKind.FAILED, 0, 0, false));
			return out;
		}
		executor.execute(new Runnable() {
			@Override
			public void run() {
				Submit result;
				try {
					MinigamesApi.Submitted s = api.submit(token, run.game, run.runId, run.inputs, run.score);
					result = s.accepted ? new Submit(SubmitKind.RANKED, s.rank, s.best, s.improved)
							: new Submit(SubmitKind.REJECTED, 0, 0, false);
				} catch (ApiException e) {
					if (e.status() == 401) account.rejected(token);
					result = new Submit(SubmitKind.FAILED, 0, 0, false);
				} catch (IOException | RuntimeException e) {
					result = new Submit(SubmitKind.FAILED, 0, 0, false);
				}
				out.finish(result);
			}
		});
		return out;
	}

	/** Rangliste eines Spiels. */
	public Pending<BoardResult> leaderboard(final String game) {
		final Pending<BoardResult> out = new Pending<BoardResult>();
		final String token = account.token();
		final MinigamesApi api = api();
		if (token == null || api == null) {
			out.finish(new BoardResult(BoardStatus.NEEDS_TRS, null));
			return out;
		}
		executor.execute(new Runnable() {
			@Override
			public void run() {
				BoardResult result;
				try {
					result = new BoardResult(BoardStatus.OK, api.leaderboard(token, game));
				} catch (ApiException e) {
					if (e.status() == 401) account.rejected(token);
					result = new BoardResult(BoardStatus.ERROR, null);
				} catch (IOException | RuntimeException e) {
					result = new BoardResult(BoardStatus.ERROR, null);
				}
				out.finish(result);
			}
		});
		return out;
	}
}
