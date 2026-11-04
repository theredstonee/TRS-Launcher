package dev.theredstonee.trsclient.core.minigames;

import java.util.HashMap;
import java.util.Map;

/**
 * Loren-Rausch: reine Spiellogik ohne Anzeige, genaue Übertragung von {@code app/utils/minigames/loren.ts}. Nur Ganzzahlen
 * und fester 60-Hz-Takt, damit Launcher, Server und Client jede Fahrt gleich rechnen. Wer die Regeln ändert, ändert alle
 * drei Fassungen; die Testpartien in {@code minigames-vectors.json} decken Abweichungen auf.
 */
public final class Loren {
	public static final int K_LEFT = 1;
	public static final int K_RIGHT = 2;
	public static final int K_JUMP = 4;
	public static final int K_DUCK = 8;
	public static final int K_TURBO = 16;
	public static final int MAX_MASK = 31;

	public static final int O_NONE = 0;
	public static final int O_TNT = 1;
	public static final int O_CRATE = 2;
	public static final int O_BEAM = 3;
	public static final int O_CREEPER = 4;

	public static final int ROW_GAP = 7;
	public static final int UNIT = 256;
	public static final int LANE_T = 9;
	public static final int BASE_SPEED = 30;
	public static final int MAX_SPEED = 92;
	private static final int JUMP_T = 40;
	private static final int JUMP_H = 410;
	private static final int CLEAR_H = 230;
	private static final int DUCK_T = 28;
	private static final int TURBO_T = 300;
	public static final int STAGGER_T = 90;
	private static final int ROW_UNITS = UNIT * ROW_GAP;

	/** Eine Reihe der Strecke. */
	public static final class Row {
		public final int[] o = new int[3];
		public final boolean[] item = new boolean[3];
		public final boolean[] pow = new boolean[3];
		public final boolean[] used = new boolean[3];
		public final boolean[] got = new boolean[3];
	}

	/** Art eines Ereignisses für Bild und Ton. */
	public enum EventType {
		LEVER, LAND, JUMP, DUCK, ITEM, DODGE, TURBO, OVER, SMASH, HIT, CRASH
	}

	/** Empfängt die Ereignisse einer Fahrt; {@code o} ist nur bei SMASH, HIT und CRASH gesetzt. */
	public interface Listener {
		void onEvent(EventType type, int o);
	}

	/** Startwert, vorzeichenlos (0 bis 2^32 - 1). */
	public final long seed;
	private final Listener listener;
	private final Map<Integer, Row> rows = new HashMap<Integer, Row>();
	public int pos;
	public int speed = BASE_SPEED;
	public int lane = 1;
	public int fromLane = 1;
	public int laneT;
	public int jumpT;
	public int duckT;
	public int signal;
	public int turbo;
	public long score;
	public int tick;
	public boolean over;
	public int stagger;
	public int items;
	public int smashed;
	/** Eingaben der Fahrt: abwechselnd Abstand in Takten und Tastenmaske. */
	public final IntList inputs = new IntList();
	private int prev;
	private int lastMask;
	private int lastInputTick;

	public Loren(long seed) {
		this(seed, null);
	}

	public Loren(long seed, Listener listener) {
		this.seed = seed & 0xFFFFFFFFL;
		this.listener = listener;
	}

	public boolean finished() {
		return over;
	}

	private void emit(EventType type, int o) {
		if (listener != null) listener.onEvent(type, o);
	}

	/** Jede Reihe hängt nur vom Startwert und von der Vorreihe ab, die Reihenfolge der Abfragen ändert also nichts. */
	public Row row(int i) {
		Row known = rows.get(i);
		if (known != null) return known;
		Row r = new Row();
		rows.put(i, r);
		if (i < 5) return r;
		Mulberry rnd = new Mulberry(((int) seed ^ i) * (int) 2654435761L);
		int hard = Math.min(100, i / 2);
		if (rnd.below(100) < 40 + hard / 2) {
			int many = rnd.below(100) < 12 + hard / 2 ? 2 : 1;
			int first = rnd.below(3);
			for (int k = 0; k < many; k++) {
				int roll = rnd.below(100);
				r.o[(first + k) % 3] = roll < 35 ? O_TNT : roll < 60 ? O_CRATE : roll < 82 ? O_BEAM : O_CREEPER;
			}
			if (hard >= 30 && rnd.below(100) < hard - 20) {
				int lane = (first + many) % 3;
				if (rnd.below(2) != 0) r.o[lane] = rnd.below(2) != 0 ? O_TNT : O_BEAM;
			}
		}
		for (int l = 0; l < 3; l++) {
			if (r.o[l] == 0 && rnd.below(100) < 26) r.item[l] = true;
		}
		Row before = i > 5 ? row(i - 1) : null;
		for (int l = 0; l < 3; l++) r.pow[l] = rnd.below(100) < (before != null && before.pow[l] ? 55 : 7);
		return r;
	}

	/** Sprunghöhe in Einheiten von {@link #UNIT}. */
	public int height() {
		if (jumpT <= 0) return 0;
		int e = JUMP_T - jumpT;
		return 4 * JUMP_H * e * (JUMP_T - e) / (JUMP_T * JUMP_T);
	}

	/** Ein Takt (1/60 s) mit den gedrückten Tasten. */
	public void step(int mask) {
		if (over) return;
		if (tick >= Stapler.MAX_TICKS) {
			over = true;
			emit(EventType.OVER, 0);
			return;
		}
		if (mask != lastMask) {
			inputs.add(tick - lastInputTick);
			inputs.add(mask);
			lastInputTick = tick;
			lastMask = mask;
		}
		tick++;
		int edge = mask & ~prev;
		prev = mask;
		if (laneT > 0) laneT--;
		if ((edge & K_LEFT) != 0 && lane > 0) {
			fromLane = lane;
			lane--;
			laneT = LANE_T;
			emit(EventType.LEVER, 0);
		}
		if ((edge & K_RIGHT) != 0 && lane < 2) {
			fromLane = lane;
			lane++;
			laneT = LANE_T;
			emit(EventType.LEVER, 0);
		}
		if (jumpT > 0) {
			jumpT--;
			if (jumpT == 0) emit(EventType.LAND, 0);
		}
		if ((edge & K_JUMP) != 0 && jumpT == 0) {
			jumpT = JUMP_T;
			duckT = 0;
			emit(EventType.JUMP, 0);
		}
		if ((edge & K_DUCK) != 0 && jumpT == 0) {
			duckT = DUCK_T;
			emit(EventType.DUCK, 0);
		}
		if (duckT > 0) duckT--;
		if ((edge & K_TURBO) != 0 && signal >= 15) {
			signal = 0;
			turbo = TURBO_T;
			emit(EventType.TURBO, 0);
		}
		if (turbo > 0) turbo--;
		if (stagger > 0) stagger--;
		int base = Math.min(MAX_SPEED, BASE_SPEED + pos / (UNIT * 24));
		boolean powered = row(pos / ROW_UNITS).pow[lane] && jumpT == 0;
		int want = base + (powered ? 12 : 0) + (turbo > 0 ? 34 : 0);
		if (stagger > 0) want = want / 2;
		speed += want > speed ? 1 : want < speed ? -1 : 0;
		int before = pos;
		int after = before + speed;
		pos = after;
		int from = before / ROW_UNITS;
		int to = after / ROW_UNITS;
		for (int i = from + 1; i <= to; i++) cross(row(i));
		score += after / UNIT - before / UNIT;
		if (powered && tick % 4 == 0) score += 1;
	}

	private void cross(Row r) {
		if (over) return;
		int l = lane;
		int o = r.o[l];
		int h = height();
		if (r.item[l] && !r.got[l] && h < JUMP_H - 60) {
			r.got[l] = true;
			items++;
			signal = Math.min(15, signal + 1);
			score += 50;
			emit(EventType.ITEM, 0);
		}
		if (o == 0 || r.used[l]) return;
		r.used[l] = true;
		boolean clear = o == O_CRATE ? false : o == O_BEAM ? duckT > 0 : h >= CLEAR_H;
		if (clear) {
			score += 25;
			emit(EventType.DODGE, 0);
			return;
		}
		if (turbo > 0) {
			smashed++;
			score += 150;
			emit(EventType.SMASH, o);
			return;
		}
		if (signal >= 5) {
			signal -= 5;
			stagger = STAGGER_T;
			emit(EventType.HIT, o);
			return;
		}
		over = true;
		emit(EventType.CRASH, o);
	}

	/** Prüfsumme des Endstands, vorzeichenlos (0 bis 2^32 - 1). */
	public long hash() {
		int h = (int) 2166136261L;
		h = (h ^ pos) * 16777619;
		h = (h ^ (int) score) * 16777619;
		h = (h ^ lane) * 16777619;
		h = (h ^ signal) * 16777619;
		h = (h ^ tick) * 16777619;
		h = (h ^ items) * 16777619;
		h = (h ^ turbo) * 16777619;
		h = (h ^ jumpT) * 16777619;
		return h & 0xFFFFFFFFL;
	}
}
