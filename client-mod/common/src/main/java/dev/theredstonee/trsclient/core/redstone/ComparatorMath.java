package dev.theredstonee.trsclient.core.redstone;

/**
 * Komparator-Rechnung wie in Vanilla – aus Blockzuständen, die der Client kennt.
 * Die Ausgabe eines Komparators speichert Minecraft im Block-Entity, das dem Client nicht
 * geschickt wird; deshalb wird sie hier aus den Eingängen nachgerechnet.
 */
public final class ComparatorMath {
	/** Wie tief Komparator-Ketten verfolgt werden (Komparator hinter Komparator …). */
	private static final int MAX_DEPTH = 6;

	private ComparatorMath() {
	}

	/**
	 * Signal eines Behälters (Vanilla {@code getRedstoneSignalFromContainer}):
	 * Füllstand = Σ(Anzahl / min(64, Stapelgröße)) / Plätze, Ausgabe = ⌊Füllstand · 14⌋ + 1 (leer = 0).
	 *
	 * @param counts   Anzahl je Platz (0 = leer)
	 * @param maxStack größter Stapel des Gegenstands je Platz (1, 16, 64)
	 * @param slots    Anzahl der Plätze des Behälters
	 */
	public static int containerSignal(int[] counts, int[] maxStack, int slots) {
		if (slots <= 0) return 0;
		float fill = 0f;
		boolean any = false;
		int n = Math.min(Math.min(counts.length, maxStack.length), slots);
		for (int i = 0; i < n; i++) {
			if (counts[i] <= 0) continue;
			any = true;
			fill += counts[i] / (float) Math.max(1, Math.min(64, maxStack[i]));
		}
		if (!any) return 0;
		fill /= slots;
		return Math.min(15, (int) Math.floor(fill * 14.0f) + 1);
	}

	/** Ausgabe eines Komparators aus hinterem und stärkstem seitlichem Eingang. */
	public static int output(boolean subtract, int rear, int side) {
		if (rear <= 0) return 0;
		if (side > rear) return 0;
		return subtract ? rear - side : rear;
	}

	/**
	 * Ausgabe des Komparators an (x, y, z) oder -1, wenn ein Eingang unbekannt ist
	 * (Behälter, dessen Inhalt der Client nicht kennt).
	 */
	public static int output(RedstoneWorld world, int x, int y, int z, BlockProbe comparator, ContainerMemory memory) {
		return output(world, x, y, z, comparator.facing, comparator.subtract, memory, 0);
	}

	private static int output(RedstoneWorld world, int x, int y, int z, int facing, boolean subtract,
			ContainerMemory memory, int depth) {
		if (!Dir.horizontal(facing)) return -1;
		int rear = rearInput(world, x, y, z, facing, memory, depth);
		if (rear < 0) return -1;
		if (rear == 0) return 0;
		int side = Math.max(sideInput(world, x, y, z, Dir.left(facing), memory, depth),
				sideInput(world, x, y, z, Dir.right(facing), memory, depth));
		return output(subtract, rear, side);
	}

	/** Hinterer Eingang (Vanilla {@code ComparatorBlock#getInputSignal}); -1 = unbekannt. */
	static int rearInput(RedstoneWorld world, int x, int y, int z, int facing, ContainerMemory memory, int depth) {
		int ix = x + Dir.dx(facing);
		int iy = y + Dir.dy(facing);
		int iz = z + Dir.dz(facing);
		int analog = world.analog(ix, iy, iz);
		if (analog == RedstoneWorld.CONTAINER_ANALOG) return memory != null ? memory.get(ix, iy, iz) : -1;
		if (analog >= 0) return analog;
		int signal = diodeInput(world, ix, iy, iz, facing, memory, depth);
		if (signal < 15 && world.conductor(ix, iy, iz)) {
			int jx = ix + Dir.dx(facing);
			int jy = iy + Dir.dy(facing);
			int jz = iz + Dir.dz(facing);
			int behind = world.analog(jx, jy, jz);
			if (behind == RedstoneWorld.CONTAINER_ANALOG) return memory != null ? memory.get(jx, jy, jz) : -1;
			if (behind >= 0) return behind;
		}
		return signal;
	}

	/** Signal am Eingang eines Verstärkers/Komparators (Vanilla {@code DiodeBlock#getInputSignal}). */
	private static int diodeInput(RedstoneWorld world, int ix, int iy, int iz, int facing, ContainerMemory memory, int depth) {
		int signal = world.signal(ix, iy, iz, facing);
		if (signal >= 15) return signal;
		int dust = world.dustPower(ix, iy, iz);
		if (dust > signal) signal = dust;
		// Ein Komparator davor, der in diesen hineinzeigt: seine Ausgabe kennt der Client nicht – nachrechnen.
		int chained = chainedComparator(world, ix, iy, iz, facing, memory, depth);
		return Math.max(signal, chained);
	}

	/** Seiteneingang aus Richtung {@code dir} (Vanilla {@code getAlternateSignalAt}). */
	private static int sideInput(RedstoneWorld world, int x, int y, int z, int dir, ContainerMemory memory, int depth) {
		if (dir < 0) return 0;
		int sx = x + Dir.dx(dir);
		int sy = y + Dir.dy(dir);
		int sz = z + Dir.dz(dir);
		int signal = world.sideSignal(sx, sy, sz, dir);
		return Math.max(signal, chainedComparator(world, sx, sy, sz, dir, memory, depth));
	}

	/**
	 * Steht an (x, y, z) ein Komparator, dessen Ausgang zu uns zeigt (sein Eingang liegt in Richtung
	 * {@code towards} weiter weg), dann dessen berechnete Ausgabe, sonst 0.
	 */
	private static int chainedComparator(RedstoneWorld world, int x, int y, int z, int towards,
			ContainerMemory memory, int depth) {
		if (depth >= MAX_DEPTH) return 0;
		BlockProbe probe = new BlockProbe();
		if (!world.probe(x, y, z, probe) || probe.kind != RedstoneKind.COMPARATOR) return 0;
		if (probe.facing != towards) return 0;
		int out = output(world, x, y, z, probe.facing, probe.subtract, memory, depth + 1);
		if (out < 0) return probe.hasPowered && probe.powered ? 15 : 0;
		return out;
	}
}
