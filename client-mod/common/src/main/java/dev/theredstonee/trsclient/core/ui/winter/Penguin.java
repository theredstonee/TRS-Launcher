package dev.theredstonee.trsclient.core.ui.winter;

import java.util.ArrayList;
import java.util.List;

/**
 * Der Weihnachts-Pinguin aus der Hütte (Port von {@code app/utils/winter/penguin.ts}). Meist schaut er aus dem
 * erleuchteten Fenster; alle ein bis zwei Minuten öffnet er die Tür, watschelt ein paar Schritte hinaus, winkt und
 * geht wieder hinein. Am Tag ist er öfter und länger draußen; ohne Animation steht er ruhig neben der Tür.
 * Nur der Ablauf (Takte à 80 ms) – gemalt wird im {@link WinterPainter}.
 */
public final class Penguin {
	public static final int WINDOW = 0;
	public static final int OPENING = 1;
	public static final int WALK_OUT = 2;
	public static final int WAVE = 3;
	public static final int LINGER = 4;
	public static final int WALK_IN = 5;
	public static final int CLOSING = 6;
	public static final int STILL = 7;

	public static final int W = 7;
	public static final int H = 11;

	// Figur von vorn: Mütze mit Bommel, Kopf, Schnabel, weißer Bauch.
	// . leer, W Fell/Bommel, R Mütze, r Mützenschatten, K Gefieder, e Auge, O Schnabel/Füße, w Bauch
	private static final String[] BODY = {
			"...RRW.",
			"..RRR..",
			".RRRRr.",
			".WWWWW.",
			".KKKKK.",
			".KeKeK.",
			".KKOKK.",
			"KKwwwKK",
			"KKwwwKK",
			".KwwwK.",
	};
	private static final String[] FEET = {".OO.OO.", "OO...OO"};

	/** Farbe eines Codes der Figur (deckend). */
	public static int color(char c) {
		switch (c) {
			case 'W': return 0xFFFFFFFF;
			case 'R': return 0xFFD8263A;
			case 'r': return 0xFFA51A2C;
			case 'K': return 0xFF1B1F2B;
			case 'e': return 0xFFFFFFFF;
			case 'O': return 0xFFFFA31A;
			case 'w': return 0xFFF4F8FF;
			default: return 0;
		}
	}

	/** Pixel der Figur {x, y, Farbe}; {@code wave} -1 = winkt nicht, 0 halb, 1 Flosse oben. */
	public static List<int[]> pixels(int step, int wave) {
		List<int[]> out = new ArrayList<int[]>();
		for (int y = 0; y <= BODY.length; y++) {
			String row = y < BODY.length ? BODY[y] : FEET[step & 1];
			for (int x = 0; x < row.length(); x++) {
				char c = row.charAt(x);
				if (c == '.') continue;
				if (wave >= 0 && x == 6 && (y == 7 || y == 8)) continue;
				out.add(new int[]{x, y, color(c)});
			}
		}
		if (wave >= 0) {
			int k = color('K');
			int a = wave == 1 ? 4 : 5;
			out.add(new int[]{6, a, k});
			out.add(new int[]{6, a + 1, k});
		}
		return out;
	}

	/** Draußen: in der Tür (Start) und vor dem Fenster (Ziel). */
	public static final int DOOR_X = Landscape.DOOR.x0;
	public static final int TARGET_X = Landscape.WINDOWS[0].x0 + 3;
	private static final int[] WINDOW_SPOTS = {Landscape.WINDOWS[0].x0, Landscape.WINDOWS[0].x1 - W + 1};
	private static final int OPEN_TICKS = 6;
	private static final int STEP_TICKS = 3;
	private static final int WAVE_TICKS = 32;

	private final WinterMath.Rng rand;
	private boolean day;
	private boolean still;
	private int phase = WINDOW;
	private int x = WINDOW_SPOTS[0];
	private int t;
	private int next;
	private int spot;
	private int dip;
	private int stepFrame;

	public Penguin(WinterMath.Rng rand, boolean day, boolean still) {
		this.rand = rand;
		this.day = day;
		// Der erste Ausflug kommt bald, damit man ihn überhaupt kennenlernt.
		this.next = 250 + (int) Math.floor(rand.next() * 200);
		if (still) setStill(true);
	}

	/** Ohne Animation (an) bzw. wieder mit (aus). */
	public void setStill(boolean on) {
		still = on;
		if (on) {
			phase = STILL;
			x = Landscape.DOOR.x1 + 2;
		} else if (phase == STILL) {
			toWindow();
		}
	}

	public void setDay(boolean day) {
		this.day = day;
	}

	private void toWindow() {
		phase = WINDOW;
		x = WINDOW_SPOTS[spot];
		t = 0;
		// Nachts alle 60–120 s, am Tag alle 30–60 s.
		int base = day ? 375 : 750;
		next = base + (int) Math.floor(rand.next() * base);
	}

	/** Sofort hinausgehen (Selbsttest). */
	public void trigger() {
		if (phase == WINDOW) next = 0;
	}

	public void step() {
		if (phase == STILL) return;
		t++;
		switch (phase) {
			case WINDOW:
				// Ab und zu taucht er kurz ab oder wechselt die Scheibe.
				if (t % 40 == 0) {
					double r = rand.next();
					if (r < 0.25) {
						dip = 1;
					} else if (r < 0.4) {
						spot = 1 - spot;
						x = WINDOW_SPOTS[spot];
					}
				} else if (t % 40 == 6) {
					dip = 0;
				}
				if (t >= next) go(OPENING);
				break;
			case OPENING:
				if (t >= OPEN_TICKS) {
					x = DOOR_X;
					go(WALK_OUT);
				}
				break;
			case WALK_OUT:
				if (t % STEP_TICKS == 0) {
					x--;
					stepFrame ^= 1;
				}
				if (x <= TARGET_X) go(WAVE);
				break;
			case WAVE:
				if (t >= WAVE_TICKS) go(LINGER);
				break;
			case LINGER:
				if (t >= (day ? 90 : 14)) go(WALK_IN);
				break;
			case WALK_IN:
				if (t % STEP_TICKS == 0) {
					x++;
					stepFrame ^= 1;
				}
				if (x >= DOOR_X) go(CLOSING);
				break;
			case CLOSING:
				if (t >= OPEN_TICKS) toWindow();
				break;
			default:
				break;
		}
	}

	private void go(int p) {
		phase = p;
		t = 0;
		stepFrame = 0;
	}

	public int phase() {
		return phase;
	}

	/** Linke Spalte der Figur relativ zur Hütte. */
	public int x() {
		return x;
	}

	/** Im Fenster: wie viele Zeilen er gerade abtaucht. */
	public int dip() {
		return phase == WINDOW ? dip : 0;
	}

	public int stepFrame() {
		return stepFrame;
	}

	/** Tür: 0 zu, 1 halb, 2 offen. */
	public int door() {
		if (phase == OPENING) return t < OPEN_TICKS / 2 ? 1 : 2;
		if (phase == CLOSING) return t < OPEN_TICKS / 2 ? 2 : 1;
		if (phase == WALK_OUT || phase == WAVE || phase == LINGER || phase == WALK_IN) return 2;
		return 0;
	}

	/** Winken: -1 nein, sonst 0/1 im Wechsel. */
	public int wave() {
		return phase == WAVE ? (t >> 2) & 1 : -1;
	}

	/** Ist er draußen (vor der Hütte zu sehen)? */
	public boolean outside() {
		return phase != WINDOW && phase != OPENING && phase != CLOSING;
	}
}
