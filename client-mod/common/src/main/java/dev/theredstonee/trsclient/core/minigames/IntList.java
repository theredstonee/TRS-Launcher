package dev.theredstonee.trsclient.core.minigames;

import java.util.Arrays;

/** Wachsende Liste von {@code int}: die Eingaben einer Partie (abwechselnd Abstand in Takten und Tastenmaske). */
public final class IntList {
	private int[] values = new int[64];
	private int size;

	public void add(int value) {
		if (size == values.length) values = Arrays.copyOf(values, size * 2);
		values[size++] = value;
	}

	public int size() {
		return size;
	}

	public int get(int index) {
		if (index >= size) throw new IndexOutOfBoundsException(String.valueOf(index));
		return values[index];
	}

	public int[] toArray() {
		return Arrays.copyOf(values, size);
	}
}
