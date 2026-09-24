package dev.theredstonee.trsclient.core.util;

import java.util.Arrays;

/**
 * Kleine Hash-Map mit {@code long}-Schlüsseln ohne Boxing (offene Adressierung, lineares Sondieren,
 * Löschen durch Rückwärtsverschieben). Für Blockpositionen, die je Tick tausendfach nachgeschlagen werden.
 * Nicht threadsicher.
 */
public final class LongObjectMap<V> {
	private long[] keys;
	private Object[] values;
	private boolean[] used;
	private int size;
	private int mask;

	public LongObjectMap() {
		this(64);
	}

	public LongObjectMap(int expected) {
		int cap = 16;
		while (cap < expected * 2) cap <<= 1;
		alloc(cap);
	}

	private void alloc(int cap) {
		keys = new long[cap];
		values = new Object[cap];
		used = new boolean[cap];
		mask = cap - 1;
	}

	private int slot(long key) {
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h ^ (h >>> 32)) & mask;
	}

	@SuppressWarnings("unchecked")
	public V get(long key) {
		for (int i = slot(key); used[i]; i = (i + 1) & mask) {
			if (keys[i] == key) return (V) values[i];
		}
		return null;
	}

	public void put(long key, V value) {
		if ((size + 1) * 2 > keys.length) grow();
		int i = slot(key);
		while (used[i]) {
			if (keys[i] == key) {
				values[i] = value;
				return;
			}
			i = (i + 1) & mask;
		}
		used[i] = true;
		keys[i] = key;
		values[i] = value;
		size++;
	}

	@SuppressWarnings("unchecked")
	public V remove(long key) {
		int i = slot(key);
		while (used[i]) {
			if (keys[i] == key) {
				V old = (V) values[i];
				delete(i);
				return old;
			}
			i = (i + 1) & mask;
		}
		return null;
	}

	/** Löscht Platz {@code i} und rückt nachfolgende Einträge der Sondierkette nach. */
	private void delete(int i) {
		int gap = i;
		int j = i;
		while (true) {
			j = (j + 1) & mask;
			if (!used[j]) break;
			int home = slot(keys[j]);
			// Darf j in die Lücke? Nur wenn die Lücke zwischen home und j liegt (zyklisch).
			boolean movable = gap <= j ? (home <= gap || home > j) : (home <= gap && home > j);
			if (movable) {
				keys[gap] = keys[j];
				values[gap] = values[j];
				gap = j;
			}
		}
		used[gap] = false;
		values[gap] = null;
		size--;
	}

	private void grow() {
		long[] oldKeys = keys;
		Object[] oldValues = values;
		boolean[] oldUsed = used;
		alloc(keys.length * 2);
		size = 0;
		for (int i = 0; i < oldKeys.length; i++) {
			if (oldUsed[i]) {
				@SuppressWarnings("unchecked")
				V v = (V) oldValues[i];
				put(oldKeys[i], v);
			}
		}
	}

	public int size() {
		return size;
	}

	public void clear() {
		Arrays.fill(used, false);
		Arrays.fill(values, null);
		size = 0;
	}
}
