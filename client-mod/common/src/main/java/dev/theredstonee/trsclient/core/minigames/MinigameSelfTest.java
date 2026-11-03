package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.ui.Theme;

/**
 * Ablauf des Autotests {@code -PtrsAutotestOnly=minigames}, je Client-Tick aufgerufen und in allen Bäumen gleich: Auswahl,
 * beide Spiele mit Bot, Pause, Spielende, Anstriche, Ressourcen neu laden und zuletzt beide Spiele in einer Welt. Misst das
 * Bild ({@link FrameMeter}) und schreibt die Werte ins Protokoll. Die Bäume liefern nur den {@link Hook}.
 */
public final class MinigameSelfTest {
	/** Was der Baum dazu beiträgt. */
	public interface Hook {
		/** Öffnet einen neuen Minispiel-Bildschirm. */
		MinigamesUi open();

		void shot(String name);

		void log(String text);

		void theme(String design);

		/** Lädt die Ressourcen neu. */
		void reloadResources();

		/** Startet die Testwelt. */
		void startWorld();

		/** Ist die Welt geladen und kein Bildschirm offen? */
		boolean worldReady();

		/** Test beendet: Aussehen zurück, Spiel schließen. */
		void finish();
	}

	private static final int SECOND = 20;
	private static final int MEASURE = 20 * SECOND;

	private final Hook hook;
	private MinigamesUi ui;
	private int phase;
	private int wait;
	private int t;
	private int restarts;
	private long nextSeed = 12345;

	public MinigameSelfTest(Hook hook) {
		this.hook = hook;
	}

	/** Ein Client-Tick. */
	public void tick() {
		if (wait > 0) {
			wait--;
			drive();
			return;
		}
		t++;
		drive();
		step();
	}

	private MinigameSession session() {
		return ui == null ? null : ui.session();
	}

	private void log(String text) {
		hook.log(text);
	}

	private void next(int ticks) {
		phase++;
		wait = ticks;
		t = 0;
	}

	/** Bots für beide Spiele; beendete Partien starten neu, damit die Messung durchläuft. */
	private void drive() {
		MinigameSession s = session();
		if (s == null) return;
		if (s.state == MinigameSession.State.OVER && restartAfterOver) {
			restarts++;
			s.start(nextSeed++, null);
		}
		if (s.stapler != null && s.state == MinigameSession.State.PLAYING) ui.testMask(staplerBot());
		if (s.loren != null && s.state == MinigameSession.State.PLAYING) ui.testMask(lorenBot(s.loren));
	}

	private boolean restartAfterOver = true;
	private int botTick;

	private int staplerBot() {
		botTick++;
		int mask = 0;
		int cycle = botTick % 14;
		if (cycle == 13) mask |= Stapler.K_HARD;
		else if (botTick % 56 < 12) mask |= Stapler.K_LEFT;
		else if (botTick % 56 >= 28 && botTick % 56 < 44) mask |= Stapler.K_RIGHT;
		if (botTick % 28 == 4) mask |= Stapler.K_CW;
		if (botTick % 112 == 60) mask |= Stapler.K_HOLD;
		if (botTick % 40 == 20) mask |= Stapler.K_PULSE;
		return mask;
	}

	private boolean steer = true;

	/** Weicht Hindernissen aus, solange {@link #steer} gilt. */
	private int lorenBot(Loren g) {
		if (!steer) return 0;
		botTick++;
		int row = g.pos / (Loren.UNIT * Loren.ROW_GAP) + 1;
		Loren.Row next = g.row(row);
		int o = next.o[g.lane];
		if (o == Loren.O_NONE) return 0;
		int free = g.lane > 0 && next.o[g.lane - 1] == Loren.O_NONE ? g.lane - 1 : g.lane < 2 && next.o[g.lane + 1] == Loren.O_NONE ? g.lane + 1 : -1;
		boolean tap = botTick % 2 == 0;
		if (free >= 0) return tap ? (free < g.lane ? Loren.K_LEFT : Loren.K_RIGHT) : 0;
		if (o == Loren.O_BEAM) return tap ? Loren.K_DUCK : 0;
		return tap ? Loren.K_JUMP : 0;
	}

	private void step() {
		switch (phase) {
			case 0:
				ui = hook.open();
				next(30);
				return;
			case 1:
				hook.shot("hub");
				ui.testEnter(Replay.STAPLER);
				next(20);
				return;
			case 2:
				hook.shot("stapler-ready");
				ui.testStart(Replay.STAPLER, 12345);
				next(SECOND);
				return;
			case 3:
				session().meter.reset();
				next(5 * SECOND);
				return;
			case 4:
				hook.shot("stapler");
				next(MEASURE);
				return;
			case 5:
				log("Redstone-Stapler: " + session().meter.summary() + ", Neustarts " + restarts);
				restarts = 0;
				session().pause(true);
				next(10);
				return;
			case 6:
				hook.shot("stapler-paused");
				session().pause(false);
				restartAfterOver = false;
				next(0);
				return;
			case 7:
				// Bot stapelt bis zum Ende, höchstens 60 s.
				if (session().state == MinigameSession.State.OVER || t > 60 * SECOND) next(15);
				return;
			case 8:
				hook.shot("stapler-over");
				ui.testLeave();
				restartAfterOver = true;
				next(15);
				return;
			case 9:
				ui.testEnter(Replay.LOREN);
				next(20);
				return;
			case 10:
				hook.shot("loren-ready");
				ui.testStart(Replay.LOREN, 7);
				next(SECOND);
				return;
			case 11:
				session().meter.reset();
				next(5 * SECOND);
				return;
			case 12:
				hook.shot("loren");
				session().loren.signal = 15;
				ui.testMask(0);
				next(2);
				return;
			case 13:
				pressTurbo();
				next(SECOND);
				return;
			case 14:
				hook.shot("loren-turbo");
				next(MEASURE);
				return;
			case 15:
				log("Loren-Rausch: " + session().meter.summary() + ", Neustarts " + restarts);
				restarts = 0;
				steer = false;
				restartAfterOver = false;
				next(0);
				return;
			case 16:
				if (session().state == MinigameSession.State.OVER || t > 60 * SECOND) next(15);
				return;
			case 17:
				hook.shot("loren-over");
				ui.testLeave();
				steer = true;
				restartAfterOver = true;
				hook.theme(Theme.WINTER);
				next(5);
				return;
			case 18:
				ui.testStart(Replay.STAPLER, 21);
				next(4 * SECOND);
				return;
			case 19:
				hook.shot("stapler-winter");
				ui.testStart(Replay.LOREN, 22);
				next(3 * SECOND);
				return;
			case 20:
				hook.shot("loren-winter");
				ui.testLeave();
				hook.theme(Theme.FOX);
				next(5);
				return;
			case 21:
				ui.testStart(Replay.STAPLER, 23);
				next(4 * SECOND);
				return;
			case 22:
				hook.shot("stapler-fox");
				ui.testStart(Replay.LOREN, 24);
				next(3 * SECOND);
				return;
			case 23:
				hook.shot("loren-fox");
				ui.testLeave();
				hook.theme(Theme.REDSTONE);
				next(5);
				return;
			case 24:
				hook.reloadResources();
				next(6 * SECOND);
				return;
			case 25:
				ui.testStart(Replay.STAPLER, 25);
				next(3 * SECOND);
				return;
			case 26:
				hook.shot("stapler-reloaded");
				hook.startWorld();
				ui = null;
				next(0);
				return;
			case 27:
				if (hook.worldReady() || t > 120 * SECOND) next(4 * SECOND);
				return;
			case 28:
				ui = hook.open();
				ui.testStart(Replay.LOREN, 31);
				next(SECOND);
				return;
			case 29:
				session().meter.reset();
				next(MEASURE);
				return;
			case 30:
				hook.shot("world-loren");
				log("Loren-Rausch in der Welt: " + session().meter.summary());
				ui.testStart(Replay.STAPLER, 32);
				next(SECOND);
				return;
			case 31:
				session().meter.reset();
				next(10 * SECOND);
				return;
			case 32:
				hook.shot("world-stapler");
				log("Redstone-Stapler in der Welt: " + session().meter.summary());
				log("fertig");
				phase = 999;
				hook.finish();
				return;
			default:
		}
	}

	private void pressTurbo() {
		ui.testPress(Loren.K_TURBO);
	}
}
