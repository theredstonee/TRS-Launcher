package dev.theredstonee.trsclient.core.redstone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Kleine Testwelt: Staub, Bauteile, Behälter und feste Blöcke an festen Positionen. */
final class FakeWorld implements RedstoneWorld {
	final Map<Long, Integer> dust = new HashMap<Long, Integer>();
	final Map<Long, BlockProbe> parts = new HashMap<Long, BlockProbe>();
	final Map<Long, Integer> analog = new HashMap<Long, Integer>();
	final Set<Long> containers = new HashSet<Long>();
	final Set<Long> solid = new HashSet<Long>();
	/** Signal, das ein Block abgibt (egal in welche Richtung). */
	final Map<Long, Integer> signals = new HashMap<Long, Integer>();
	int dustReads;

	static long key(int x, int y, int z) {
		return Dir.pack(x, y, z);
	}

	FakeWorld dust(int x, int y, int z, int power) {
		dust.put(key(x, y, z), power);
		return this;
	}

	FakeWorld solid(int x, int y, int z) {
		solid.add(key(x, y, z));
		return this;
	}

	FakeWorld container(int x, int y, int z) {
		containers.add(key(x, y, z));
		return this;
	}

	FakeWorld analog(int x, int y, int z, int value) {
		analog.put(key(x, y, z), value);
		return this;
	}

	FakeWorld source(int x, int y, int z, int value) {
		signals.put(key(x, y, z), value);
		return this;
	}

	FakeWorld comparator(int x, int y, int z, int facing, boolean subtract, boolean powered) {
		BlockProbe p = new BlockProbe();
		p.kind = RedstoneKind.COMPARATOR;
		p.facing = facing;
		p.subtract = subtract;
		p.powered(powered);
		parts.put(key(x, y, z), p);
		return this;
	}

	@Override
	public boolean probe(int x, int y, int z, BlockProbe out) {
		out.clear();
		Integer d = dust.get(key(x, y, z));
		if (d != null) {
			out.kind = RedstoneKind.DUST;
			out.power = d;
			return true;
		}
		BlockProbe p = parts.get(key(x, y, z));
		if (p != null) {
			out.copyFrom(p);
			return true;
		}
		if (containers.contains(key(x, y, z))) {
			out.kind = RedstoneKind.CONTAINER;
			out.container = true;
			return true;
		}
		return false;
	}

	@Override
	public String name(int x, int y, int z) {
		BlockProbe p = new BlockProbe();
		return probe(x, y, z, p) ? p.kind.name() : "Air";
	}

	@Override
	public int dustPower(int x, int y, int z) {
		dustReads++;
		Integer d = dust.get(key(x, y, z));
		return d == null ? -1 : d;
	}

	@Override
	public int signal(int x, int y, int z, int dir) {
		Integer s = signals.get(key(x, y, z));
		if (s != null) return s;
		Integer d = dust.get(key(x, y, z));
		return d == null ? 0 : d;
	}

	@Override
	public int sideSignal(int x, int y, int z, int dir) {
		return signal(x, y, z, dir);
	}

	@Override
	public int analog(int x, int y, int z) {
		if (containers.contains(key(x, y, z))) return CONTAINER_ANALOG;
		Integer a = analog.get(key(x, y, z));
		return a == null ? NO_ANALOG : a;
	}

	@Override
	public boolean conductor(int x, int y, int z) {
		return solid.contains(key(x, y, z));
	}

	@Override
	public boolean opaque(int x, int y, int z) {
		return solid.contains(key(x, y, z));
	}
}
