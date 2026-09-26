package dev.theredstonee.trsclient.core.hosting;

import dev.theredstonee.trsclient.core.hosting.e4mc.E4mcRuntime;
import dev.theredstonee.trsclient.core.hosting.e4mc.PublicTunnel;
import dev.theredstonee.trsclient.core.hosting.net.PeerStream;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * „Öffentlicher Link“ über den Fremddienst e4mc: jeder mit der Adresse kann der Welt beitreten. Ab Werk AUS.
 *
 * <p>Einschalten nur über den Warn-Dialog ({@link Gate}): Text „Jeder mit dem Link kann deiner Welt beitreten. Teile
 * ihn nur mit Leuten, denen du vertraust.“ + Häkchen „Ich habe verstanden“ – erst dann ist „Aktivieren“ möglich. Das
 * Häkchen gilt nur für GENAU diesen Dialog: jede Aktivierung braucht einen neuen {@link Gate} (nie dauerhaft
 * wegklickbar). Solange aktiv, zeigen HUD und Pausemenü ein rotes Abzeichen mit „Deaktivieren“.
 */
public final class PublicLink {
	public static final String BROKER = "https://broker.e4mc.link/getBestRelay";

	public enum State {
		OFF, STARTING, ON, ERROR
	}

	/**
	 * Zustand EINES Warn-Dialogs. Neu = nicht bestätigt; {@link #activate} nimmt nur einen bestätigten, noch nicht
	 * benutzten Gate an (danach ist er verbraucht).
	 */
	public static final class Gate {
		private boolean understood;
		private boolean used;

		public boolean understood() {
			return understood;
		}

		public void setUnderstood(boolean v) {
			if (!used) understood = v;
		}

		public void toggle() {
			setUnderstood(!understood);
		}

		/** Darf „Aktivieren“ gedrückt werden? */
		public boolean canActivate() {
			return understood && !used;
		}

		boolean consume() {
			if (!canActivate()) return false;
			used = true;
			return true;
		}
	}

	private static volatile Path dir = Paths.get("config", "trsclient", "e4mc");

	private final Hosting hosting;
	private volatile State state = State.OFF;
	private volatile String domain;
	private volatile String error;
	private volatile int progress;
	private volatile PublicTunnel tunnel;
	private volatile int session;

	PublicLink(Hosting hosting) {
		this.hosting = hosting;
	}

	/** Ablageort der nachgeladenen Laufzeit (je Loader beim Start). */
	public static void dir(Path d) {
		if (d != null) dir = d;
	}

	public State state() {
		return state;
	}

	/** Aktiv oder im Aufbau – dann gehört das rote Abzeichen ins HUD/Pausemenü. */
	public boolean active() {
		return state == State.ON || state == State.STARTING;
	}

	/** Öffentliche Adresse oder null. */
	public String domain() {
		return domain;
	}

	/** Fehler (i18n-Schlüssel) oder null. */
	public String error() {
		return error;
	}

	public int progress() {
		return progress;
	}

	/** Wird e4mc auf dieser Plattform unterstützt (Windows/Linux/macOS x64/arm64)? */
	public static boolean supported() {
		try {
			return E4mcRuntime.supported();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Nach bestätigtem Warn-Dialog einschalten. false = nicht erlaubt (nicht bestätigt, kein Hosting, läuft schon). */
	public boolean activate(Gate gate) {
		if (gate == null || !gate.canActivate()) return false;
		if (hosting.hostState() != Hosting.HostState.OPEN || active()) return false;
		if (!gate.consume()) return false;
		state = State.STARTING;
		domain = null;
		error = null;
		progress = 0;
		final int my = ++session;
		hosting.submit(new Runnable() {
			@Override
			public void run() {
				try {
					PublicTunnel t = E4mcRuntime.newTunnel(dir, new E4mcRuntime.Progress() {
						@Override
						public void progress(int percent) {
							progress = percent;
						}
					});
					if (my != session) return;
					tunnel = t;
					t.start(BROKER, new Events(my));
				} catch (Exception | LinkageError e) {
					if (my != session) return;
					hosting.log("TRS Hosting: öffentlicher Link fehlgeschlagen: " + e);
					fail("hosting.link.error");
				}
			}
		});
		return true;
	}

	/** Ausschalten (Knopf „Deaktivieren“, Hosting beendet). */
	public void stop() {
		session++;
		PublicTunnel t = tunnel;
		tunnel = null;
		boolean was = state != State.OFF;
		state = State.OFF;
		domain = null;
		progress = 0;
		if (t != null) {
			final PublicTunnel tt = t;
			hosting.submit(new Runnable() {
				@Override
				public void run() {
					try {
						tt.stop();
					} catch (RuntimeException | LinkageError ignored) {
						// egal
					}
				}
			});
		}
		if (was) hosting.log("TRS Hosting: öffentlicher Link aus");
	}

	void tick(long now) {
		if (active() && hosting.hostState() != Hosting.HostState.OPEN) stop();
	}

	private void fail(String key) {
		PublicTunnel t = tunnel;
		tunnel = null;
		state = State.ERROR;
		domain = null;
		error = key;
		if (t != null) {
			try {
				t.stop();
			} catch (RuntimeException | LinkageError ignored) {
				// egal
			}
		}
	}

	private final class Events implements PublicTunnel.Events {
		private final int my;

		Events(int my) {
			this.my = my;
		}

		@Override
		public void domain(String d) {
			if (my != session || d == null || !d.matches("[A-Za-z0-9.-]{1,253}")) return;
			domain = d;
			state = State.ON;
			hosting.log("TRS Hosting: öffentlicher Link aktiv");
		}

		@Override
		public void stream(PeerStream stream) {
			if (my != session || state != State.ON) {
				stream.close("link off");
				return;
			}
			hosting.attachPublic(stream);
		}

		@Override
		public void broadcast(final String message) {
			if (my != session || message == null) return;
			final String m = message.length() > 200 ? message.substring(0, 200) : message;
			hosting.post(new Runnable() {
				@Override
				public void run() {
					hosting.notice("hosting.link.broadcast", false, m);
				}
			});
		}

		@Override
		public void failed(String reason) {
			if (my != session) return;
			hosting.log("TRS Hosting: öffentlicher Link getrennt (" + reason + ")");
			fail("hosting.link.lost");
		}
	}
}
