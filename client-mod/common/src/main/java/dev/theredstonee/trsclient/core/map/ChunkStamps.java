package dev.theredstonee.trsclient.core.map;

import java.util.Arrays;

/**
 * Wann wurde welcher Chunk zuletzt abgetastet? Offene Adressierung ohne Boxing (long → long). Wird zu groß
 * → geleert (dann wird eben neu abgetastet).
 */
final class ChunkStamps {
	private static final long EMPTY = Long.MIN_VALUE;
	private long[] keys;
	private long[] values;
	private int size;

	ChunkStamps() {
		keys = new long[1024];
		values = new long[1024];
		Arrays.fill(keys, EMPTY);
	}

	static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}

	/** Zeitstempel oder -1. */
	long get(long key) {
		int mask = keys.length - 1;
		int i = mix(key) & mask;
		while (true) {
			long k = keys[i];
			if (k == EMPTY) return -1;
			if (k == key) return values[i];
			i = (i + 1) & mask;
		}
	}

	void put(long key, long value) {
		if (key == EMPTY) return;
		if (size * 2 >= keys.length) {
			if (keys.length >= (1 << 16)) {
				clear();
			} else {
				grow();
			}
		}
		int mask = keys.length - 1;
		int i = mix(key) & mask;
		while (true) {
			long k = keys[i];
			if (k == EMPTY) {
				keys[i] = key;
				values[i] = value;
				size++;
				return;
			}
			if (k == key) {
				values[i] = value;
				return;
			}
			i = (i + 1) & mask;
		}
	}

	int size() {
		return size;
	}

	void clear() {
		Arrays.fill(keys, EMPTY);
		size = 0;
	}

	private void grow() {
		long[] oldK = keys, oldV = values;
		keys = new long[oldK.length * 2];
		values = new long[oldK.length * 2];
		Arrays.fill(keys, EMPTY);
		size = 0;
		for (int i = 0; i < oldK.length; i++) {
			if (oldK[i] != EMPTY) put(oldK[i], oldV[i]);
		}
	}

	private static int mix(long key) {
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h ^ (h >>> 32));
	}
}
