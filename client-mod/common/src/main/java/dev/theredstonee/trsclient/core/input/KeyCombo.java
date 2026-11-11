package dev.theredstonee.trsclient.core.input;

import java.util.ArrayList;
import java.util.List;

/**
 * Tasten-Kombis für TRS-Tasten: {@code "key.keyboard.left.control+key.keyboard.m"}.
 * Modifikatoren zuerst, die Haupttaste zuletzt. Links/rechts zählt gleich.
 */
public final class KeyCombo {
	public static final String SEP = "+";
	public static final String F3 = "key.keyboard.f3";
	static final int MAX_PARTS = 3;

	private static final String[][] MODIFIERS = {
			{ "key.keyboard.left.control", "key.keyboard.right.control" },
			{ "key.keyboard.left.alt", "key.keyboard.right.alt" },
			{ "key.keyboard.left.shift", "key.keyboard.right.shift" },
			{ F3 },
	};

	private KeyCombo() {
	}

	public static String[] parts(String name) {
		return name.split("\\+", -1);
	}

	public static String main(String name) {
		int i = name.lastIndexOf(SEP);
		return i < 0 ? name : name.substring(i + 1);
	}

	public static boolean isCombo(String name) {
		return name.contains(SEP);
	}

	/** Gemeinsamer Name für linke/rechte Variante, null = kein Modifikator. */
	public static String modifier(String key) {
		for (String[] group : MODIFIERS) {
			for (String k : group) {
				if (k.equals(key)) return group[0];
			}
		}
		return null;
	}

	static boolean held(String modifier, KeyPresses.Down down) {
		for (String[] group : MODIFIERS) {
			if (!group[0].equals(modifier)) continue;
			for (String k : group) {
				if (down.isDown(k)) return true;
			}
		}
		return false;
	}

	/**
	 * Passen die Modifikatoren gerade? Bei einer einzelnen Taste darf F3 nicht gehalten sein,
	 * F3-Kombis (F3+B usw.) gehören Minecraft.
	 */
	public static boolean modifiersMatch(String name, KeyPresses.Down down) {
		String[] parts = parts(name);
		if (parts.length == 1) return F3.equals(name) || !down.isDown(F3);
		for (int i = 0; i < parts.length - 1; i++) {
			if (!held(parts[i], down)) return false;
		}
		return true;
	}

	/** Kombi aus den gerade gehaltenen Modifikatoren und der gedrückten Taste. */
	public static String of(String pressed, KeyPresses.Down down) {
		List<String> mods = new ArrayList<String>();
		if (down != null) {
			for (String[] group : MODIFIERS) {
				if (mods.size() < MAX_PARTS - 1 && held(group[0], down)) mods.add(group[0]);
			}
		}
		StringBuilder b = new StringBuilder();
		for (String m : mods) b.append(m).append(SEP);
		return b.append(pressed).toString();
	}

	public interface Label {
		String label(String keyName);
	}

	public static String label(String name, Label label) {
		String[] parts = parts(name);
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) b.append(" + ");
			b.append(label.label(parts[i]));
		}
		return b.toString();
	}
}
