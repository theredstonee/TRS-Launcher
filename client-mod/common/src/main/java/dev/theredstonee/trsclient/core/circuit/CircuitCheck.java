package dev.theredstonee.trsclient.core.circuit;

import java.util.HashMap;
import java.util.Map;

/**
 * Abgleich einer eingeblendeten Vorlage mit der Welt: je Feld richtig (grün), falsch (rot – anderer Block oder
 * falsche Richtung/Verzögerung/Modus), fehlt (grau) oder unbekannt (Chunk nicht geladen). Reine Anzeige.
 */
public final class CircuitCheck {
	public static final byte UNKNOWN = 0;
	public static final byte CORRECT = 1;
	public static final byte WRONG = 2;
	public static final byte MISSING = 3;
	/** Nur Anzeige ({@code ?}-Feld) – nicht geprüft. */
	public static final byte OPTIONAL = 4;

	private final Circuit circuit;
	private final Placement placement;
	/** Status je Feld (Index wie {@link Circuit#cells}). */
	private final byte[] status;
	/** Falsches Feld: stimmt nur der Zustand nicht (richtiger Block)? */
	private final boolean[] stateOnly;
	/** Welt-Koordinaten je Feld {x, y, z}. */
	private final int[][] world;
	private int correct;
	private int wrong;
	private int missing;

	private final Map<String, String> props = new HashMap<String, String>();
	private final BlockCatalog.Normalized normalized = new BlockCatalog.Normalized();

	public CircuitCheck(Circuit circuit, Placement placement) {
		this.circuit = circuit;
		this.placement = placement;
		int n = circuit.cells.size();
		status = new byte[n];
		stateOnly = new boolean[n];
		world = new int[n][3];
		for (int i = 0; i < n; i++) {
			Circuit.Cell c = circuit.cells.get(i);
			placement.toWorld(circuit, c.x, c.y, c.z, world[i]);
			status[i] = c.spec.optional ? OPTIONAL : UNKNOWN;
		}
	}

	public Circuit circuit() {
		return circuit;
	}

	public Placement placement() {
		return placement;
	}

	/** Alle Felder gegen die Welt prüfen (ohne Welt: alles unbekannt). */
	public void run(CircuitWorld w, BlockCatalog catalog) {
		correct = 0;
		wrong = 0;
		missing = 0;
		for (int i = 0; i < status.length; i++) {
			Circuit.Cell cell = circuit.cells.get(i);
			if (cell.spec.optional) continue;
			byte s = w == null ? UNKNOWN : checkCell(cell, world[i], w, catalog, i);
			status[i] = s;
			if (s == CORRECT) correct++;
			else if (s == WRONG) wrong++;
			else if (s == MISSING) missing++;
		}
	}

	private byte checkCell(Circuit.Cell cell, int[] p, CircuitWorld w, BlockCatalog catalog, int index) {
		props.clear();
		String raw = w.block(p[0], p[1], p[2], props);
		stateOnly[index] = false;
		if (raw == null) return UNKNOWN;
		catalog.normalize(raw, props, w.legacy(), normalized);
		return match(cell.spec, placement.props(cell.spec), normalized, w.conductor(p[0], p[1], p[2]), index);
	}

	/** Ergebnis von {@link #match}: Block stimmt, nur der Zustand nicht. */
	private static final byte WRONG_STATE = 5;

	private byte match(BlockSpec spec, Map<String, String> expected, BlockCatalog.Normalized found, boolean conductor,
			int index) {
		byte r = match(spec, expected, found, conductor);
		if (r == WRONG_STATE) {
			stateOnly[index] = true;
			return WRONG;
		}
		return r;
	}

	private static byte match(BlockSpec spec, Map<String, String> expected, BlockCatalog.Normalized found, boolean conductor) {
		if (found.isAir()) return MISSING;
		if (spec.movable && ("piston_head".equals(found.key) || "moving_piston".equals(found.key)
				|| "piston_extension".equals(found.key))) {
			return CORRECT;
		}
		if (spec.def.anySolid()) return conductor ? CORRECT : WRONG;
		if (!spec.def.key.equals(found.key)) return WRONG;
		for (String key : spec.def.check) {
			String want = expected.get(key);
			String got = found.props.get(key);
			if (want == null || got == null) continue;
			if (!want.equals(got)) return WRONG_STATE;
		}
		return CORRECT;
	}

	/**
	 * Vergleich eines einzelnen Feldes (Tests): Block aus der Welt gegen die Angabe der Schaltung.
	 *
	 * @return {@link #CORRECT}, {@link #WRONG}, {@link #MISSING} oder {@link #UNKNOWN}
	 */
	public static byte compare(BlockSpec spec, Placement placement, String rawId, Map<String, String> worldProps,
			boolean legacy, boolean conductor, BlockCatalog catalog) {
		if (rawId == null) return UNKNOWN;
		BlockCatalog.Normalized n = catalog.normalize(rawId, worldProps, legacy, null);
		byte r = match(spec, placement.props(spec), n, conductor);
		return r == WRONG_STATE ? WRONG : r;
	}

	public byte status(int index) {
		return status[index];
	}

	/** Falsches Feld mit richtigem Block, aber falscher Richtung/Verzögerung/Modus? */
	public boolean stateOnly(int index) {
		return stateOnly[index];
	}

	public int[] world(int index) {
		return world[index];
	}

	public int size() {
		return status.length;
	}

	public int correct() {
		return correct;
	}

	public int wrong() {
		return wrong;
	}

	public int missing() {
		return missing;
	}

	/** Blöcke, die geprüft werden (ohne {@code ?}-Felder). */
	public int total() {
		return circuit.blockCount();
	}

	/** Alles richtig gebaut? */
	public boolean complete() {
		return correct == total();
	}
}
