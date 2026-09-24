package dev.theredstonee.trsclient.core.redstone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redstone-Staub im Umkreis des Spielers für das Welt-Overlay – gecacht statt je Frame gesucht:
 * <ul>
 *   <li>Suche: der Würfel um den Spieler wird Stück für Stück abgelaufen, höchstens
 *       {@code scanBudget} Blöcke je Tick (Radius 16 = 33³ Blöcke → ein Durchlauf in wenigen Ticks).</li>
 *   <li>Stärke: bekannter Staub wird jeden Tick neu gelesen (billig), verschwundener fällt heraus.</li>
 *   <li>Sicht: höchstens {@code sightBudget} Sichtprüfungen je Tick, reihum.</li>
 * </ul>
 * Nicht geladene Chunks liefern keinen Staub – es wird nie mehr gezeigt, als der Client kennt.
 */
public final class SignalCache {
	public static final int MIN_RADIUS = 4;
	public static final int MAX_RADIUS = 16;
	/** Höchstens so viel Staub wird gemerkt (und gezeichnet). */
	public static final int MAX_ENTRIES = 2048;

	/** Ein Stück Staub. */
	public static final class Entry {
		public final int x;
		public final int y;
		public final int z;
		public int power;
		public boolean visible;

		Entry(int x, int y, int z, int power) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.power = power;
		}
	}

	private final int scanBudget;
	private final int sightBudget;
	private final Map<Long, Entry> byPos = new HashMap<Long, Entry>();
	private final List<Entry> entries = new ArrayList<Entry>();
	private int radius = -1;
	private int cursor;
	private int sightCursor;
	private boolean passComplete;
	private int scannedThisPass;

	public SignalCache() {
		this(4096, 48);
	}

	public SignalCache(int scanBudget, int sightBudget) {
		this.scanBudget = Math.max(1, scanBudget);
		this.sightBudget = Math.max(0, sightBudget);
	}

	/**
	 * Ein Tick: weitersuchen, Stärken auffrischen, Sicht prüfen.
	 *
	 * @param cx         Blockposition des Spielers (Mitte des Würfels)
	 * @param radius     Radius in Blöcken ({@link #MIN_RADIUS}–{@link #MAX_RADIUS})
	 * @param checkSight false = alles gilt als sichtbar
	 */
	public void tick(RedstoneWorld world, int cx, int cy, int cz, int radius,
			double eyeX, double eyeY, double eyeZ, boolean checkSight) {
		radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
		if (radius != this.radius) {
			this.radius = radius;
			cursor = 0;
			passComplete = false;
			scannedThisPass = 0;
		}
		refresh(world, cx, cy, cz, radius);
		scan(world, cx, cy, cz, radius, eyeX, eyeY, eyeZ, checkSight);
		sight(world, eyeX, eyeY, eyeZ, checkSight);
	}

	/** Bekannten Staub neu lesen; weg ist, was nicht mehr da oder außer Reichweite ist. */
	private void refresh(RedstoneWorld world, int cx, int cy, int cz, int r) {
		int keep = 0;
		for (int i = 0, n = entries.size(); i < n; i++) {
			Entry e = entries.get(i);
			boolean inRange = Math.abs(e.x - cx) <= r && Math.abs(e.y - cy) <= r && Math.abs(e.z - cz) <= r;
			int power = inRange ? world.dustPower(e.x, e.y, e.z) : -1;
			if (power < 0) {
				byPos.remove(Long.valueOf(Dir.pack(e.x, e.y, e.z)));
				continue;
			}
			e.power = power;
			entries.set(keep++, e);
		}
		while (entries.size() > keep) entries.remove(entries.size() - 1);
	}

	private void scan(RedstoneWorld world, int cx, int cy, int cz, int r,
			double eyeX, double eyeY, double eyeZ, boolean checkSight) {
		int side = 2 * r + 1;
		int total = side * side * side;
		for (int n = 0; n < scanBudget; n++) {
			if (cursor >= total) {
				cursor = 0;
				passComplete = true;
				scannedThisPass = 0;
			}
			int i = cursor++;
			scannedThisPass++;
			int dx = i % side;
			int dz = (i / side) % side;
			int dy = i / (side * side);
			int x = cx - r + dx;
			int y = cy - r + dy;
			int z = cz - r + dz;
			int power = world.dustPower(x, y, z);
			Long key = Long.valueOf(Dir.pack(x, y, z));
			Entry e = byPos.get(key);
			if (power >= 0) {
				if (e != null) {
					e.power = power;
				} else if (entries.size() < MAX_ENTRIES) {
					e = new Entry(x, y, z, power);
					e.visible = !checkSight || LineOfSight.clear(world, eyeX, eyeY, eyeZ, x + 0.5, y + 0.25, z + 0.5);
					byPos.put(key, e);
					entries.add(e);
				}
			} else if (e != null) {
				byPos.remove(key);
				entries.remove(e);
			}
		}
	}

	private void sight(RedstoneWorld world, double eyeX, double eyeY, double eyeZ, boolean checkSight) {
		int n = entries.size();
		if (n == 0) return;
		if (!checkSight) {
			for (int i = 0; i < n; i++) entries.get(i).visible = true;
			return;
		}
		int budget = Math.min(sightBudget, n);
		for (int k = 0; k < budget; k++) {
			if (sightCursor >= n) sightCursor = 0;
			Entry e = entries.get(sightCursor++);
			e.visible = LineOfSight.clear(world, eyeX, eyeY, eyeZ, e.x + 0.5, e.y + 0.25, e.z + 0.5);
		}
	}

	/** Gefundener Staub (nicht verändern; gilt bis zum nächsten {@link #tick}). */
	public List<Entry> entries() {
		return entries;
	}

	public int size() {
		return entries.size();
	}

	/** Wurde der Würfel seit dem letzten Radiuswechsel schon einmal ganz abgesucht? */
	public boolean passComplete() {
		return passComplete;
	}

	/** Alles vergessen (Weltwechsel, Overlay aus). */
	public void clear() {
		byPos.clear();
		entries.clear();
		cursor = 0;
		sightCursor = 0;
		passComplete = false;
		scannedThisPass = 0;
		radius = -1;
	}
}
