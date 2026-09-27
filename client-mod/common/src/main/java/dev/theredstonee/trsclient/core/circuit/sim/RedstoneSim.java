package dev.theredstonee.trsclient.core.circuit.sim;

import dev.theredstonee.trsclient.core.circuit.BlockSpec;
import dev.theredstonee.trsclient.core.circuit.Circuit;
import dev.theredstonee.trsclient.core.redstone.Dir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kleine Redstone-Simulation für die Schaltungs-Bibliothek – bildet die Regeln von Minecraft Java nach, soweit die
 * Bibliotheks-Schaltungen sie brauchen: Staub (Form, Abschwächung, Treppen, keine Kraft durch schwach gepowerte
 * Blöcke), Fackeln, Verstärker (Verzögerung, Mindestpuls, Verriegeln), Komparatoren (Vergleichen/Subtrahieren,
 * Behälter, Seiteneingänge), Hebel/Knöpfe/Druckplatten, Lampen, Kolben (nur „angesteuert“, inkl.
 * Quasi-Konnektivität), Beobachter, Trichter (gesperrt), Spender, Kupfer-Birne, Tageslichtsensor.
 *
 * <p>Zeitbasis: Spiel-Ticks (1 Redstone-Tick = 2 Spiel-Ticks). Nichts bewegt sich (Kolben verschieben keine
 * Blöcke) – für Logik-Prüfungen der Bibliothek genügt das. Die Welt außerhalb der Schaltung ist Luft über festem,
 * nicht leitendem Boden.
 */
public final class RedstoneSim {
	/** Bauteil-Arten. */
	enum Kind {
		AIR, SOLID, OTHER, DUST, TORCH, WALL_TORCH, REPEATER, COMPARATOR, LEVER, BUTTON, PLATE, REDSTONE_BLOCK, LAMP,
		PISTON, OBSERVER, HOPPER, CONTAINER, DROPPER, BULB, DAYLIGHT;

		static Kind of(String sim) {
			if ("solid".equals(sim)) return SOLID;
			if ("dust".equals(sim)) return DUST;
			if ("torch".equals(sim)) return TORCH;
			if ("wall_torch".equals(sim)) return WALL_TORCH;
			if ("repeater".equals(sim)) return REPEATER;
			if ("comparator".equals(sim)) return COMPARATOR;
			if ("lever".equals(sim)) return LEVER;
			if ("button".equals(sim)) return BUTTON;
			if ("plate".equals(sim)) return PLATE;
			if ("redstone_block".equals(sim)) return REDSTONE_BLOCK;
			if ("lamp".equals(sim)) return LAMP;
			if ("piston".equals(sim)) return PISTON;
			if ("observer".equals(sim)) return OBSERVER;
			if ("hopper".equals(sim)) return HOPPER;
			if ("container".equals(sim)) return CONTAINER;
			if ("dropper".equals(sim)) return DROPPER;
			if ("bulb".equals(sim)) return BULB;
			if ("daylight".equals(sim)) return DAYLIGHT;
			return OTHER;
		}

		boolean conductor() {
			return this == SOLID || this == LAMP || this == DROPPER || this == BULB;
		}

		boolean signalSource() {
			switch (this) {
				case DUST: case TORCH: case WALL_TORCH: case REPEATER: case COMPARATOR: case LEVER: case BUTTON: case PLATE:
				case REDSTONE_BLOCK: case OBSERVER: case DAYLIGHT:
					return true;
				default:
					return false;
			}
		}

		boolean diode() {
			return this == REPEATER || this == COMPARATOR;
		}

		boolean analog() {
			return this == HOPPER || this == CONTAINER || this == DROPPER || this == BULB;
		}
	}

	/** Zustand eines Feldes. */
	static final class Node {
		final int x, y, z;
		final Kind kind;
		/** Richtung (Dir) aus der Eigenschaft facing, sonst -1. */
		final int facing;
		/** Hebel/Knopf: Richtung zum Block, an dem er hängt. */
		final int attach;
		final int delay;
		final boolean subtract;
		final String marker;

		boolean on;          // Fackel an, Hebel/Knopf/Platte gedrückt, Verstärker/Beobachter an, Lampe an …
		int power;           // Staub-Stärke, Komparator-Ausgabe, Tageslicht-Stärke
		int analog;          // Behälter-Füllstand (von außen gesetzt)
		int items;           // Spender: Anzahl Gegenstände
		boolean locked;      // Verstärker verriegelt; Trichter gesperrt
		boolean powered;     // Birne/Spender: zuletzt angesteuert
		boolean lit;         // Kupfer-Birne leuchtet
		long scheduled = -1; // Spiel-Tick der geplanten Änderung
		int observedKey = Integer.MIN_VALUE;
		int bump;            // von Tests geänderter Zustand (z. B. Zuckerrohr gewachsen)
		/** Staub: verbunden in Richtung (Dir 2–5). */
		final boolean[] connected = new boolean[6];

		Node(int x, int y, int z, Kind kind, int facing, int attach, int delay, boolean subtract, String marker) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.kind = kind;
			this.facing = facing;
			this.attach = attach;
			this.delay = delay;
			this.subtract = subtract;
			this.marker = marker;
		}

		/** Was ein Beobachter „sieht“ (jede Zustandsänderung). */
		int stateKey() {
			int k = kind.ordinal();
			k = k * 31 + (on ? 1 : 0);
			k = k * 31 + power;
			k = k * 31 + (locked ? 1 : 0);
			k = k * 31 + (lit ? 1 : 0);
			k = k * 31 + (powered ? 1 : 0);
			k = k * 31 + bump;
			return k;
		}
	}

	private final Map<Long, Node> nodes = new HashMap<Long, Node>();
	private final List<Node> list = new ArrayList<Node>();
	private final Map<String, Node> markers = new HashMap<String, Node>();
	private long time;
	/** Während der Staub-Berechnung: Staub gibt kein Signal ab (wie Vanilla {@code shouldSignal = false}). */
	private boolean dustSilent;

	/** Baut die Simulation aus einer Schaltung (unveränderte Lage). */
	public RedstoneSim(Circuit circuit) {
		for (Circuit.Cell c : circuit.cells) {
			BlockSpec s = c.spec;
			Kind kind = Kind.of(s.def.sim);
			int facing = dir(s.prop("facing"));
			int attach = -1;
			if (kind == Kind.LEVER || kind == Kind.BUTTON) {
				String face = s.prop("face");
				if ("ceiling".equals(face)) attach = Dir.UP;
				else if ("wall".equals(face)) attach = facing < 0 ? -1 : Dir.opposite(facing);
				else attach = Dir.DOWN;
			}
			int delay = 1;
			if (s.prop("delay") != null) delay = Integer.parseInt(s.prop("delay"));
			boolean subtract = "subtract".equals(s.prop("mode"));
			Node n = new Node(c.x, c.y, c.z, kind, facing, attach, delay, subtract, s.marker);
			if (kind == Kind.TORCH || kind == Kind.WALL_TORCH) n.on = true;
			if (kind == Kind.DAYLIGHT) n.power = 0;
			nodes.put(Dir.pack(c.x, c.y, c.z), n);
			list.add(n);
			if (s.marker != null) markers.put(s.marker, n);
		}
		for (Node n : list) if (n.kind == Kind.DUST) shape(n);
		settle();
		for (Node n : list) if (n.kind == Kind.OBSERVER) n.observedKey = keyAt(n.x + Dir.dx(n.facing), n.y + Dir.dy(n.facing), n.z + Dir.dz(n.facing));
	}

	static int dir(String name) {
		if (name == null) return -1;
		if ("down".equals(name)) return Dir.DOWN;
		if ("up".equals(name)) return Dir.UP;
		if ("north".equals(name)) return Dir.NORTH;
		if ("south".equals(name)) return Dir.SOUTH;
		if ("west".equals(name)) return Dir.WEST;
		if ("east".equals(name)) return Dir.EAST;
		return -1;
	}

	// --- Welt ---

	private Node at(int x, int y, int z) {
		return nodes.get(Dir.pack(x, y, z));
	}

	private Kind kind(int x, int y, int z) {
		Node n = at(x, y, z);
		return n == null ? Kind.AIR : n.kind;
	}

	private boolean conductor(int x, int y, int z) {
		return kind(x, y, z).conductor();
	}

	private int keyAt(int x, int y, int z) {
		Node n = at(x, y, z);
		return n == null ? 0 : n.stateKey();
	}

	// --- Signale (Vanilla-Benennung: d = Richtung vom Empfänger zum Sender) ---

	/** Schwaches Signal, das der Block an p in Richtung des Empfängers (bei p - d) abgibt. */
	private int emit(Node n, int d) {
		if (n == null) return 0;
		switch (n.kind) {
			case DUST:
				if (dustSilent || d == Dir.DOWN || n.power == 0) return 0;
				if (d == Dir.UP) return n.power;
				return n.connected[Dir.opposite(d)] ? n.power : 0;
			case TORCH:
				return n.on && d != Dir.UP ? 15 : 0;
			case WALL_TORCH:
				return n.on && d != n.facing ? 15 : 0;
			case REPEATER:
				return n.on && d == n.facing ? 15 : 0;
			case COMPARATOR:
				return d == n.facing ? n.power : 0;
			case LEVER:
			case BUTTON:
			case PLATE:
				return n.on ? 15 : 0;
			case REDSTONE_BLOCK:
				return 15;
			case OBSERVER:
				return n.on && d == n.facing ? 15 : 0;
			case DAYLIGHT:
				return n.power;
			default:
				return 0;
		}
	}

	/** Starkes Signal (das einen leitenden Block „stark“ antreibt). */
	private int emitDirect(Node n, int d) {
		if (n == null) return 0;
		switch (n.kind) {
			case DUST:
				return dustSilent ? 0 : emit(n, d);
			case TORCH:
			case WALL_TORCH:
				return d == Dir.DOWN ? emit(n, d) : 0;
			case REPEATER:
			case COMPARATOR:
			case OBSERVER:
				return emit(n, d);
			case LEVER:
			case BUTTON:
				// Empfänger = der Block, an dem er hängt: Richtung vom Block zum Hebel = Gegenrichtung von attach
				return n.on && d == Dir.opposite(n.attach) ? 15 : 0;
			case PLATE:
				return n.on && d == Dir.UP ? 15 : 0;
			default:
				return 0;
		}
	}

	/** Stärkstes starkes Signal, das den Block bei (x, y, z) antreibt. */
	private int directSignalTo(int x, int y, int z) {
		int best = 0;
		for (int d = 0; d < 6; d++) {
			best = Math.max(best, emitDirect(at(x + Dir.dx(d), y + Dir.dy(d), z + Dir.dz(d)), d));
			if (best >= 15) return 15;
		}
		return best;
	}

	/** Vanilla {@code Level.getSignal(pos, d)}: leitende Blöcke geben ihr starkes Signal weiter. */
	private int signal(int x, int y, int z, int d) {
		Node n = at(x, y, z);
		int s = emit(n, d);
		if (n != null && n.kind.conductor()) s = Math.max(s, directSignalTo(x, y, z));
		return s;
	}

	/** Vanilla {@code hasNeighborSignal}. */
	private boolean hasNeighborSignal(int x, int y, int z) {
		return bestNeighborSignal(x, y, z) > 0;
	}

	private int bestNeighborSignal(int x, int y, int z) {
		int best = 0;
		for (int d = 0; d < 6; d++) {
			best = Math.max(best, signal(x + Dir.dx(d), y + Dir.dy(d), z + Dir.dz(d), d));
			if (best >= 15) return 15;
		}
		return best;
	}

	/** Kolben/Spender: auch „angesteuert“, wenn der Platz darüber es wäre (Quasi-Konnektivität). */
	private boolean pistonPowered(Node n) {
		for (int d = 0; d < 6; d++) {
			if (n.kind == Kind.PISTON && d == n.facing) continue;
			if (signal(n.x + Dir.dx(d), n.y + Dir.dy(d), n.z + Dir.dz(d), d) > 0) return true;
		}
		int ux = n.x, uy = n.y + 1, uz = n.z;
		for (int d = 0; d < 6; d++) {
			if (d == Dir.DOWN) continue;
			if (signal(ux + Dir.dx(d), uy + Dir.dy(d), uz + Dir.dz(d), d) > 0) return true;
		}
		return false;
	}

	// --- Staub ---

	private void shape(Node n) {
		boolean upFree = !conductor(n.x, n.y + 1, n.z);
		int count = 0;
		for (int d = 2; d < 6; d++) {
			int nx = n.x + Dir.dx(d), nz = n.z + Dir.dz(d);
			boolean c = false;
			Node side = at(nx, n.y, nz);
			if (upFree && kind(nx, n.y + 1, nz) == Kind.DUST && side != null && side.kind != Kind.AIR) c = true;
			if (!c && connectsTo(side, d)) c = true;
			if (!c && (side == null || !side.kind.conductor()) && kind(nx, n.y - 1, nz) == Kind.DUST) c = true;
			n.connected[d] = c;
			if (c) count++;
		}
		if (count == 0) {
			for (int d = 2; d < 6; d++) n.connected[d] = true;
		} else if (count == 1) {
			for (int d = 2; d < 6; d++) if (n.connected[d]) n.connected[Dir.opposite(d)] = true;
		}
	}

	/** Vanilla {@code shouldConnectTo}: d = Richtung vom Staub zum Nachbarn. */
	private static boolean connectsTo(Node other, int d) {
		if (other == null) return false;
		switch (other.kind) {
			case DUST:
				return true;
			case REPEATER:
				return other.facing == d || other.facing == Dir.opposite(d);
			case OBSERVER:
				return other.facing == d;
			default:
				return other.kind.signalSource();
		}
	}

	private static int wirePower(Node n) {
		return n != null && n.kind == Kind.DUST ? n.power : 0;
	}

	/** Alle Staub-Stärken neu (Fixpunkt von max(Quelle, Nachbar − 1)). */
	private boolean updateDust() {
		List<Node> dust = new ArrayList<Node>();
		for (Node n : list) if (n.kind == Kind.DUST) dust.add(n);
		if (dust.isEmpty()) return false;
		int[] source = new int[dust.size()];
		dustSilent = true;
		for (int i = 0; i < dust.size(); i++) {
			Node n = dust.get(i);
			source[i] = bestNeighborSignal(n.x, n.y, n.z);
		}
		dustSilent = false;
		int[] old = new int[dust.size()];
		for (int i = 0; i < dust.size(); i++) {
			old[i] = dust.get(i).power;
			dust.get(i).power = source[i];
		}
		boolean changed = true;
		int guard = 0;
		while (changed && guard++ < 64) {
			changed = false;
			for (int i = 0; i < dust.size(); i++) {
				Node n = dust.get(i);
				int j = 0;
				boolean upConductor = conductor(n.x, n.y + 1, n.z);
				for (int d = 2; d < 6; d++) {
					int nx = n.x + Dir.dx(d), nz = n.z + Dir.dz(d);
					Node side = at(nx, n.y, nz);
					j = Math.max(j, wirePower(side));
					if (side != null && side.kind.conductor()) {
						if (!upConductor) j = Math.max(j, wirePower(at(nx, n.y + 1, nz)));
					} else {
						j = Math.max(j, wirePower(at(nx, n.y - 1, nz)));
					}
				}
				int v = Math.max(source[i], j - 1);
				if (v > n.power) {
					n.power = v;
					changed = true;
				}
			}
		}
		for (int i = 0; i < dust.size(); i++) if (dust.get(i).power != old[i]) return true;
		return false;
	}

	// --- Bauteile ---

	private int diodeInput(Node n) {
		int ix = n.x + Dir.dx(n.facing), iy = n.y, iz = n.z + Dir.dz(n.facing);
		int i = signal(ix, iy, iz, n.facing);
		Node in = at(ix, iy, iz);
		if (i < 15 && in != null && in.kind == Kind.DUST) i = Math.max(i, in.power);
		if (n.kind == Kind.COMPARATOR) {
			if (in != null && in.kind.analog()) {
				i = analogOf(in);
			} else if (i < 15 && in != null && in.kind.conductor()) {
				Node behind = at(ix + Dir.dx(n.facing), iy, iz + Dir.dz(n.facing));
				if (behind != null && behind.kind.analog()) i = analogOf(behind);
			}
		}
		return i;
	}

	private int analogOf(Node n) {
		switch (n.kind) {
			case BULB:
				return n.lit ? 15 : 0;
			case DROPPER:
				return n.items <= 0 ? 0 : 1 + (int) Math.floor(n.items / 64.0 / 9.0 * 14.0);
			default:
				return n.analog;
		}
	}

	private int sideInput(Node n) {
		int best = 0;
		int[] sides = {Dir.left(n.facing), Dir.right(n.facing)};
		for (int s : sides) {
			if (s < 0) continue;
			Node side = at(n.x + Dir.dx(s), n.y, n.z + Dir.dz(s));
			if (side == null) continue;
			int v;
			if (n.kind == Kind.REPEATER) {
				v = side.kind.diode() ? emitDirect(side, s) : 0;
			} else if (side.kind == Kind.REDSTONE_BLOCK) {
				v = 15;
			} else if (side.kind == Kind.DUST) {
				v = side.power;
			} else {
				v = side.kind.signalSource() ? emitDirect(side, s) : 0;
			}
			best = Math.max(best, v);
		}
		return best;
	}

	private int comparatorOutput(Node n) {
		int in = diodeInput(n);
		if (in == 0) return 0;
		int side = sideInput(n);
		if (side > in) return 0;
		return n.subtract ? in - side : in;
	}

	private boolean torchHasSignal(Node n) {
		if (n.kind == Kind.TORCH) return signal(n.x, n.y - 1, n.z, Dir.DOWN) > 0;
		int back = Dir.opposite(n.facing);
		return signal(n.x + Dir.dx(back), n.y, n.z + Dir.dz(back), back) > 0;
	}

	private void schedule(Node n, int ticks) {
		if (n.scheduled < 0) n.scheduled = time + ticks;
	}

	/** Sofortige Reaktionen + Planung; true = etwas hat sich geändert. */
	private boolean react() {
		boolean changed = false;
		for (Node n : list) {
			switch (n.kind) {
				case TORCH:
				case WALL_TORCH:
					if (n.on == torchHasSignal(n)) schedule(n, 2);
					break;
				case REPEATER: {
					boolean lock = sideInput(n) > 0;
					if (lock != n.locked) {
						n.locked = lock;
						changed = true;
					}
					if (!n.locked && n.on != (diodeInput(n) > 0)) schedule(n, 2 * n.delay);
					break;
				}
				case COMPARATOR:
					if (comparatorOutput(n) != n.power) schedule(n, 2);
					break;
				case LAMP:
				case PISTON: {
					boolean p = n.kind == Kind.PISTON ? pistonPowered(n) : hasNeighborSignal(n.x, n.y, n.z);
					if (p != n.on) {
						n.on = p;
						changed = true;
					}
					break;
				}
				case HOPPER: {
					boolean lock = hasNeighborSignal(n.x, n.y, n.z);
					if (lock != n.locked) {
						n.locked = lock;
						changed = true;
					}
					break;
				}
				case BULB: {
					boolean p = hasNeighborSignal(n.x, n.y, n.z);
					if (p != n.powered) {
						if (p) n.lit = !n.lit;
						n.powered = p;
						changed = true;
					}
					break;
				}
				case DROPPER: {
					boolean p = pistonPowered(n);
					if (p && !n.powered) {
						n.powered = true;
						schedule(n, 4);
						changed = true;
					} else if (!p && n.powered) {
						n.powered = false;
						changed = true;
					}
					break;
				}
				case OBSERVER: {
					int key = keyAt(n.x + Dir.dx(n.facing), n.y + Dir.dy(n.facing), n.z + Dir.dz(n.facing));
					if (key != n.observedKey) {
						n.observedKey = key;
						if (!n.on) schedule(n, 2);
					}
					break;
				}
				default:
					break;
			}
		}
		return changed;
	}

	/** Geplante Änderung ausführen. */
	private void fire(Node n) {
		n.scheduled = -1;
		switch (n.kind) {
			case TORCH:
			case WALL_TORCH: {
				boolean has = torchHasSignal(n);
				if (n.on && has) n.on = false;
				else if (!n.on && !has) n.on = true;
				break;
			}
			case REPEATER: {
				if (n.locked) break;
				boolean in = diodeInput(n) > 0;
				if (n.on && !in) {
					n.on = false;
				} else if (!n.on) {
					n.on = true;
					if (!in) schedule(n, 2 * n.delay);
				}
				break;
			}
			case COMPARATOR:
				n.power = comparatorOutput(n);
				break;
			case OBSERVER:
				if (n.on) {
					n.on = false;
				} else {
					n.on = true;
					schedule(n, 2);
				}
				break;
			case DROPPER: {
				if (n.items > 0) {
					Node target = at(n.x + Dir.dx(n.facing), n.y + Dir.dy(n.facing), n.z + Dir.dz(n.facing));
					if (target != null && (target.kind == Kind.DROPPER || target.kind == Kind.HOPPER)) {
						n.items--;
						target.items++;
					}
				}
				break;
			}
			case BUTTON:
				n.on = false;
				break;
			default:
				break;
		}
	}

	/** Bis alles ruht (nur sofortige Reaktionen, keine Zeit). */
	private void settle() {
		for (int i = 0; i < 64; i++) {
			boolean a = updateDust();
			boolean b = react();
			if (!a && !b) {
				// Staub kann sich nach react() erneut ändern (z. B. Lampe → nichts), einmal gegenprüfen
				if (!updateDust()) return;
			}
		}
	}

	/** Einen Spiel-Tick weiter. */
	public void tick() {
		time++;
		List<Node> due = new ArrayList<Node>();
		for (Node n : list) if (n.scheduled >= 0 && n.scheduled <= time) due.add(n);
		for (Node n : due) fire(n);
		settle();
	}

	public void run(int ticks) {
		for (int i = 0; i < ticks; i++) tick();
	}

	public long time() {
		return time;
	}

	// --- Ein- und Ausgänge für Tests ---

	private Node marker(String name) {
		Node n = markers.get(name);
		if (n == null) throw new IllegalArgumentException("Anschluss fehlt: " + name);
		return n;
	}

	/**
	 * Eingang setzen: Hebel/Platte an/aus (value &gt; 0), Behälter-Füllstand bzw. Tageslicht-Stärke (0–15),
	 * Spender-Inhalt (Anzahl Gegenstände).
	 */
	public void set(String name, int value) {
		Node n = marker(name);
		switch (n.kind) {
			case LEVER:
			case PLATE:
			case BUTTON:
				n.on = value > 0;
				break;
			case DAYLIGHT:
				n.power = Math.max(0, Math.min(15, value));
				break;
			case DROPPER:
				n.items = value;
				break;
			default:
				n.analog = Math.max(0, Math.min(15, value));
				break;
		}
		settle();
	}

	/** Knopf drücken (Steinknopf: 20 Spiel-Ticks an). */
	public void press(String name) {
		Node n = marker(name);
		n.on = true;
		n.scheduled = time + 20;
		settle();
	}

	/** Zustand eines Blocks ändern, den ein Beobachter sieht (z. B. „Zuckerrohr gewachsen“). */
	public void bump(String name) {
		marker(name).bump++;
		settle();
	}

	/** Ausgang lesen: Lampe/Kolben/Fackel/Verstärker/Beobachter an, Trichter gesperrt, Birne leuchtet, Staub &gt; 0. */
	public boolean on(String name) {
		Node n = marker(name);
		switch (n.kind) {
			case DUST:
				return n.power > 0;
			case COMPARATOR:
				return n.power > 0;
			case HOPPER:
				return n.locked;
			case BULB:
				return n.lit;
			case DROPPER:
				return n.items > 0;
			default:
				return n.on;
		}
	}

	/** Kurzbeschreibung aller Bauteile (Fehlersuche). */
	public String dump() {
		StringBuilder b = new StringBuilder();
		for (Node n : list) {
			if (n.kind == Kind.SOLID || n.kind == Kind.OTHER) continue;
			b.append(n.kind.name().charAt(0)).append(n.kind.name().length() > 1 ? n.kind.name().substring(1, 3).toLowerCase() : "")
					.append('(').append(n.x).append(',').append(n.y).append(',').append(n.z).append(")=")
					.append(n.kind == Kind.DUST || n.kind == Kind.COMPARATOR ? String.valueOf(n.power) : (n.on ? "1" : "0"))
					.append(n.locked ? "L" : "").append(n.scheduled >= 0 ? "*" : "").append(' ');
		}
		return b.toString();
	}

	/** Stärke eines Ausgangs (Staub/Komparator), sonst 15/0. */
	public int level(String name) {
		Node n = marker(name);
		if (n.kind == Kind.DUST || n.kind == Kind.COMPARATOR) return n.power;
		return on(name) ? 15 : 0;
	}
}
