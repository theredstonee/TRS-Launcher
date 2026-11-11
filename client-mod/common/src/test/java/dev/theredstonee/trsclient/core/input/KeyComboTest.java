package dev.theredstonee.trsclient.core.input;

import dev.theredstonee.trsclient.core.module.KeySetting;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyComboTest {
	private final Set<String> down = new HashSet<String>();
	private final KeyPresses keys = new KeyPresses(down::contains);

	private boolean press(KeySetting k, String... held) {
		down.clear();
		for (String h : held) down.add(h);
		boolean fired = keys.pressed(k);
		down.clear();
		keys.pressed(k);
		return fired;
	}

	@Test
	void singleKeyIgnoresF3Combos() {
		KeySetting k = new KeySetting("add", "Add", "key.keyboard.b");
		assertTrue(press(k, "key.keyboard.b"));
		assertFalse(press(k, "key.keyboard.f3", "key.keyboard.b"));
		assertTrue(press(k, "key.keyboard.left.shift", "key.keyboard.b"));
	}

	@Test
	void comboNeedsItsModifiers() {
		KeySetting k = new KeySetting("add", "Add");
		k.set("key.keyboard.left.control+key.keyboard.m");
		assertEquals("key.keyboard.left.control+key.keyboard.m", k.get());
		assertFalse(press(k, "key.keyboard.m"));
		assertTrue(press(k, "key.keyboard.left.control", "key.keyboard.m"));
		assertTrue(press(k, "key.keyboard.right.control", "key.keyboard.m"));
	}

	@Test
	void f3ComboCanBeBound() {
		KeySetting k = new KeySetting("x", "X");
		k.set("key.keyboard.f3+key.keyboard.x");
		assertTrue(press(k, "key.keyboard.f3", "key.keyboard.x"));
		assertFalse(press(k, "key.keyboard.x"));
	}

	@Test
	void rejectsBrokenCombos() {
		KeySetting k = new KeySetting("x", "X", "key.keyboard.b");
		k.set("key.keyboard.m+key.keyboard.b");
		assertEquals(KeySetting.NONE, k.get());
		k.set("key.keyboard.left.control+key.keyboard.left.alt+key.keyboard.left.shift+key.keyboard.b");
		assertEquals(KeySetting.NONE, k.get());
		k.set("key.keyboard.left.control+");
		assertEquals(KeySetting.NONE, k.get());
	}

	@Test
	void buildsComboFromHeldModifiers() {
		down.add("key.keyboard.right.alt");
		assertEquals("key.keyboard.left.alt+key.keyboard.g", KeyCombo.of("key.keyboard.g", down::contains));
		assertEquals("key.keyboard.g", KeyCombo.of("key.keyboard.g", null));
		assertEquals("Alt + G", KeyCombo.label("key.keyboard.left.alt+key.keyboard.g",
				name -> name.equals("key.keyboard.left.alt") ? "Alt" : "G"));
	}
}
