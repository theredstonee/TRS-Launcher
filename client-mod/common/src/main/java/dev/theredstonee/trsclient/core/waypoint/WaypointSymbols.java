package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.ui.Canvas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wählbare Pixel-Symbole der Wegpunkte (8×8, eigene Zeichnungen, keine Spieltexturen) – im Code hinterlegt, damit sie in
 * jeder Minecraft-Version gleich aussehen. '#' = Pixel. Neue Symbole nur hinten anfügen: gespeichert wird die Kennung.
 */
public final class WaypointSymbols {
	/** Symbol der Todespunkte. */
	public static final String SKULL = "skull";

	private static final List<String> IDS = new ArrayList<String>();
	private static final Map<String, String[]> ROWS = new HashMap<String, String[]>();
	/** Je Symbol Läufe aus {x, y, Breite} – so bleiben es wenige Rechtecke je Bild. */
	private static final Map<String, int[][]> RUNS = new HashMap<String, int[][]>();

	private WaypointSymbols() {
	}

	static {
		put("bed", "........", "#.......", "#.##....", "#.##....", "########", "########", "#......#", "........");
		put("chest", ".######.", "#......#", "#......#", "###..###", "###..###", "#......#", "#......#", ".######.");
		put("sword", "......##", ".....###", "....###.", "#..###..", "##.##...", ".###....", "..##....", ".#..#...");
		put("portal", "########", "#..##..#", "#.#..#.#", "#.#..#.#", "#.#..#.#", "#.#..#.#", "#..##..#", "########");
		put("home", "...##...", "..####..", ".######.", "########", ".#....#.", ".#.##.#.", ".#.##.#.", ".######.");
		put("pickaxe", "..####..", ".#.##.#.", "#..##..#", "...##...", "...##...", "...##...", "...##...", "...##...");
		put(SKULL, ".######.", "########", "#..##..#", "#..##..#", "########", ".##..##.", ".######.", ".#.##.#.");
		put("star", "...##...", "...##...", "########", ".######.", "..####..", ".######.", ".##..##.", ".#....#.");
		put("tree", "...##...", "..####..", ".######.", "..####..", ".######.", "########", "...##...", "...##...");
		put("flag", ".#......", ".#####..", ".######.", ".#####..", ".#......", ".#......", ".#......", "###.....");
		put("heart", ".##..##.", "########", "########", "########", ".######.", "..####..", "...##...", "........");
		put("farm", "#..#..#.", ".#.#.#..", "#..#..#.", ".#.#.#..", "#..#..#.", ".#.#.#..", "...#....", "...#....");
		put("gem", "..####..", ".#.##.#.", "########", "#..##..#", ".#.##.#.", "..#..#..", "...##...", "........");
		put("cave", "..####..", ".######.", "###..###", "##....##", "##....##", "##....##", "##....##", "########");
		put("boat", "...#....", "...##...", "...###..", "...#....", "########", "#......#", ".######.", "........");
		put("key", ".###....", "#...#...", "#...#...", ".###....", "...#....", "...##...", "...#....", "...##...");
		put("flame", "...#....", "...##...", "..###.#.", ".#.####.", ".######.", "###..###", ".##..##.", "..####..");
		put("shield", "########", "#......#", "#.####.#", "#.####.#", "#.####.#", ".#.##.#.", "..#..#..", "...##...");
	}

	private static void put(String id, String... rows) {
		if (rows.length != 8) throw new IllegalArgumentException(id);
		IDS.add(id);
		ROWS.put(id, rows);
		List<int[]> runs = new ArrayList<int[]>();
		for (int y = 0; y < 8; y++) {
			String row = rows[y];
			if (row.length() != 8) throw new IllegalArgumentException(id);
			int x = 0;
			while (x < 8) {
				if (row.charAt(x) != '#') {
					x++;
					continue;
				}
				int start = x;
				while (x < 8 && row.charAt(x) == '#') x++;
				runs.add(new int[]{start, y, x - start});
			}
		}
		RUNS.put(id, runs.toArray(new int[0][]));
	}

	/** Alle Kennungen in Anzeigereihenfolge. */
	public static List<String> ids() {
		return Collections.unmodifiableList(IDS);
	}

	public static boolean known(String id) {
		return id != null && ROWS.containsKey(id);
	}

	/** Die acht Zeilen eines Symbols (Tests). */
	public static String[] rows(String id) {
		return ROWS.get(id);
	}

	/** Zeichnet das Symbol mit der linken oberen Ecke bei (x, y); {@code px} = Größe eines Pixels. */
	public static void draw(Canvas c, String id, int x, int y, int px, int argb) {
		int[][] runs = RUNS.get(id);
		if (runs == null) return;
		for (int[] r : runs) {
			int rx = x + r[0] * px, ry = y + r[1] * px;
			c.fill(rx, ry, rx + r[2] * px, ry + px, argb);
		}
	}
}
