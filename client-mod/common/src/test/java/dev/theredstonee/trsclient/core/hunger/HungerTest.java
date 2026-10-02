package dev.theredstonee.trsclient.core.hunger;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HungerTest {
	@AfterEach
	void resetTextures() {
		Textures.replaceForTests(null);
	}

	@Test
	void foodAndSaturationAfterEatingAreCapped() {
		assertEquals(20, HungerMath.foodAfter(16, 8));
		assertEquals(14, HungerMath.foodAfter(6, 8));
		// Sättigung höchstens so hoch wie der neue Hunger.
		assertEquals(14f, HungerMath.saturationAfter(6, 2f, 8, 12.8f), 1e-4);
		assertEquals(5f, HungerMath.saturationAfter(6, 2f, 8, 3f), 1e-4);
	}

	@Test
	void slowRegenerationCostsSixOrThreeExhaustion() {
		// Ab 1.11: 1 Leben, 6 Erschöpfung → ein Hungerpunkt weg → unter 18.
		assertEquals(1f, HungerMath.healthGain(18, 0, 0, 10, 20, true), 1e-4);
		// Bis 1.10: nur 3 Erschöpfung je Leben → zweimal heilen.
		assertEquals(2f, HungerMath.healthGain(18, 0, 0, 10, 20, false), 1e-4);
	}

	@Test
	void fastRegenerationUsesSaturation() {
		float gain = HungerMath.healthGain(20, 5, 0, 2, 20, true);
		assertTrue(gain > 2.4f && gain < 18f, "gain " + gain);
		// Ohne Sättigung gibt es nur langsames Heilen.
		assertEquals(3f, HungerMath.healthGain(20, 0, 0, 2, 20, true), 1e-4);
		// Erschöpfung unbekannt (NaN) zählt wie 0.
		assertEquals(gain, HungerMath.healthGain(20, 5, Float.NaN, 2, 20, true), 1e-4);
	}

	@Test
	void noHealingWhenFullOrHungry() {
		assertEquals(0f, HungerMath.healthGain(20, 20, 0, 20, 20, true));
		assertEquals(0f, HungerMath.healthGain(17, 20, 0, 5, 20, true));
		// Nie mehr als das fehlende Leben.
		assertEquals(1f, HungerMath.healthGain(20, 20, 0, 19, 20, true), 1e-4);
	}

	@Test
	void tinySaturationDoesNotLoopForever() {
		float gain = HungerMath.healthGain(20, Float.MIN_VALUE, 0, 1, 1000, true);
		assertTrue(gain >= 0 && gain < 1000);
	}

	@Test
	void flashStaysBetweenZeroAndMax() {
		float min = 1, max = 0;
		for (long t = 0; t < HungerMath.FLASH_PERIOD_MS; t += 10) {
			float a = HungerMath.flashAlpha(t);
			min = Math.min(min, a);
			max = Math.max(max, a);
		}
		assertEquals(0f, min, 1e-4);
		assertEquals(HungerMath.FLASH_MAX, max, 1e-4);
	}

	@Test
	void jitterReplaysTheVanillaRandomSequence() {
		HungerState s = state();
		s.health = 3;
		s.saturation = 0;
		s.food = 0;
		s.guiTicks = 1234;
		int[] hearts = new int[20];
		int[] food = new int[10];
		HungerMath.jitter(s, hearts, food);
		Random r = new Random((long) (1234 * 312871));
		for (int i = 9; i >= 0; i--) assertEquals(r.nextInt(2), hearts[i], "heart " + i);
		// food*3+1 = 1 → jeder Tick wackelt.
		for (int i = 0; i < 10; i++) assertEquals(r.nextInt(3) - 1, food[i], "food " + i);
	}

	@Test
	void noJitterWhenHealthyAndSaturated() {
		HungerState s = state();
		int[] hearts = new int[20];
		int[] food = new int[10];
		HungerMath.jitter(s, hearts, food);
		for (int v : hearts) assertEquals(0, v);
		for (int v : food) assertEquals(0, v);
	}

	@Test
	void heartLayoutFollowsVanilla() {
		HungerState s = state();
		assertEquals(10, HungerMath.heartSlots(s));
		// Eine Reihe: Vanilla rechnet 10 − (1 − 2) = 11 (spielt bei einer Reihe keine Rolle).
		assertEquals(11, HungerMath.heartRowHeight(s));
		s.absorption = 20;
		assertEquals(20, HungerMath.heartSlots(s));
		s.maxHealth = 100;
		// 60 Herzen in 6 Reihen → Abstand 6.
		assertEquals(60, HungerMath.heartSlots(s));
		assertEquals(6, HungerMath.heartRowHeight(s));
		s.absorption = Float.POSITIVE_INFINITY;
		assertEquals(0, HungerMath.heartSlots(s));
	}

	@Test
	void outlineHasOnlyEdgePixels() {
		int[][][] o = HungerOverlay.outline();
		assertEquals(9, o.length);
		assertArrayEquals(new int[]{2, 4}, o[0][0]);
		// Zeile 3 (#######..): Mitte ist innen, nur Rand links und rechts.
		assertEquals(2, o[3].length);
	}

	@Test
	void overlayDrawsPreviewsOnlyWhenHoldingFood() {
		HungerState s = state();
		s.food = 10;
		s.saturation = 3;
		s.health = 12;
		Recorder c = new Recorder();
		HungerOverlay overlay = new HungerOverlay();
		HudIcons icons = HudIcons.sprites(new FakeStore());
		overlay.draw(c, s, icons, 800);
		assertEquals(0, c.images.size(), "ohne Essen keine Vorschau");
		assertTrue(c.fills > 0, "Sättigungsrand");

		s.heldNutrition = 8;
		s.heldSaturation = 12.8f;
		s.heldCanEat = true;
		c = new Recorder();
		overlay.draw(c, s, icons, 800);
		// 4 neue Keulen (je Hintergrund + Keule).
		assertEquals(8, countPrefix(c.images, "minecraft:textures/gui/sprites/hud/food_"));
		assertTrue(countPrefix(c.images, "minecraft:textures/gui/sprites/hud/heart/") > 0, "Herz-Vorschau");

		s.survival = false;
		c = new Recorder();
		overlay.draw(c, s, icons, 800);
		assertEquals(0, c.images.size());
		assertEquals(0, c.fills);
	}

	@Test
	void appleskinTextureWinsOverAPackOutline() {
		int[][][] pack = block3();
		assertEquals(HungerLook.Source.APPLESKIN, HungerLook.choose(new byte[]{1, 2, 3}, pack));
		assertEquals(HungerLook.Source.PACK, HungerLook.choose(null, pack));
		assertEquals(HungerLook.Source.PACK, HungerLook.choose(new byte[0], pack));
		assertEquals(HungerLook.Source.BUILTIN, HungerLook.choose(null, null));
		assertEquals(HungerLook.Source.BUILTIN, HungerLook.choose(new byte[0], null));
		// Kaputte PNG fällt auf die eingebaute Keule zurück.
		assertNull(HungerLook.fromPng(new byte[]{1, 2, 3, 4}, false));
		assertNull(HungerLook.fromPng(null, true));
		assertEquals(HungerLook.Source.BUILTIN, HungerLook.choose(null, HungerLook.fromPng(new byte[]{9}, false)));
	}

	@Test
	void saturationColumnsMatchTheAppleSkinLayout() {
		assertEquals(0, HungerLook.saturationU(0f));
		assertEquals(0, HungerLook.saturationU(0.25f));
		assertEquals(9, HungerLook.saturationU(0.26f));
		assertEquals(9, HungerLook.saturationU(0.5f));
		assertEquals(18, HungerLook.saturationU(0.51f));
		assertEquals(27, HungerLook.saturationU(1f));
		assertEquals(0, HungerLook.exhaustionWidth(0f));
		assertEquals(81, HungerLook.exhaustionWidth(1f));
		assertEquals(41, HungerLook.exhaustionWidth(0.5f));
	}

	@Test
	void customMaskBecomesAnOutlineOn9And18() {
		int[][][] small = HungerLook.outline(blockArgb(9, 3), 9, 9);
		int[][][] big = HungerLook.outline(blockArgb(18, 6), 18, 18);
		assertNotNull(small);
		assertNotNull(big);
		assertEquals(9, small.length);
		// 3×3-Block links oben: die Mitte (1,1) ist innen, nur der Rand bleibt.
		assertArrayEquals(new int[]{0, 3}, small[0][0]);
		assertEquals(2, small[1].length);
		assertArrayEquals(new int[]{0, 1}, small[1][0]);
		assertArrayEquals(new int[]{2, 3}, small[1][1]);
		assertArrayEquals(small[0][0], big[0][0]);
		assertEquals(small[1].length, big[1].length);
		assertArrayEquals(small[1][0], big[1][0]);
		assertArrayEquals(small[1][1], big[1][1]);
		// Volle Durchsichtigkeit ist kein Umriss.
		assertNull(HungerLook.outline(new int[81], 9, 9));
	}

	@Test
	void appleskinIconsAreDrawnAtTheFoodSlots() {
		HungerState s = state();
		s.food = 20;
		s.saturation = 20;
		s.exhaustion = 4f;
		Textures.replaceForTests(new ByteStore(new byte[]{8}));
		Recorder c = new Recorder();
		new HungerOverlay().draw(c, s, HudIcons.sprites(new FakeStore()), 800);
		assertEquals(1, countExact(c.images, "appleskin:textures/icons.png@0,18"), "Erschöpfung");
		assertEquals(10, countExact(c.images, "appleskin:textures/icons.png@27,0"), "volle Sättigung");
		assertEquals(0, c.fills);
	}

	@Test
	void missingPackTextureFallsBackToTheBuiltinDrumstick() {
		Textures.replaceForTests(new ByteStore(null));
		HungerState s = state();
		s.saturation = 3;
		Recorder c = new Recorder();
		new HungerOverlay().draw(c, s, HudIcons.sprites(new FakeStore()), 800);
		assertEquals(0, c.images.size());
		assertTrue(c.fills > 0, "eingebaute Keule");
	}

	@Test
	void sheetIconsUseTheLegacyAtlas() {
		HudIcons icons = HudIcons.sheet(new FakeStore());
		Recorder c = new Recorder();
		icons.draw(c, HudIcons.FOOD_FULL, 0, 0, -1);
		assertEquals("minecraft:textures/gui/icons.png@52,27", c.images.get(0));
	}

	private static int countPrefix(List<String> list, String prefix) {
		int n = 0;
		for (String s : list) if (s.startsWith(prefix)) n++;
		return n;
	}

	private static int countExact(List<String> list, String value) {
		int n = 0;
		for (String s : list) if (value.equals(s)) n++;
		return n;
	}

	/** Deckender Block links oben, Kante {@code edge} px in einem {@code size}×{@code size}-Bild. */
	private static int[] blockArgb(int size, int edge) {
		int[] argb = new int[size * size];
		for (int y = 0; y < edge; y++) {
			for (int x = 0; x < edge; x++) argb[y * size + x] = 0xFF000000;
		}
		return argb;
	}

	private static int[][][] block3() {
		return HungerLook.outline(blockArgb(9, 3), 9, 9);
	}

	private static HungerState state() {
		HungerState s = new HungerState();
		s.survival = true;
		s.foodBar = true;
		s.width = 854 / 2;
		s.height = 480 / 2;
		s.food = 20;
		s.saturation = 5;
		s.health = 20;
		s.maxHealth = 20;
		s.guiTicks = 77;
		return s;
	}

	/** Store, der für die AppleSkin-Datei feste Bytes liefert (null = fehlt). */
	private static final class ByteStore extends FakeStore {
		private final byte[] appleskin;

		ByteStore(byte[] appleskin) {
			this.appleskin = appleskin;
		}

		@Override
		public byte[] gameBytes(String location) {
			if (HungerLook.APPLESKIN.equals(location)) return appleskin;
			return null;
		}

		@Override
		public int resourceGeneration() {
			return 1;
		}
	}

	private static class FakeStore implements Textures.Store {
		@Override public TextureRef upload(String name, int width, int height, int[] argb) { return null; }
		@Override public void release(TextureRef texture) { }
		@Override public TextureRef game(String location, int width, int height) { return new TextureRef(location, width, height); }
		@Override public Textures.DefaultSkin defaultSkin(UUID uuid) { return null; }
	}

	private static final class Recorder implements Canvas {
		final List<String> images = new ArrayList<String>();
		int fills;

		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { fills++; }
		@Override public void text(String text, int x, int y, int argb, boolean shadow) { }
		@Override public int textWidth(String text) { return text.length() * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public String clip(String text, int maxWidth) { return text; }
		@Override public void flush() { }
		@Override public void scissor(int x1, int y1, int x2, int y2) { }
		@Override public void noScissor() { }
		@Override public void raise(float z) { }
		@Override public void push() { }
		@Override public void translate(float x, float y) { }
		@Override public void scale(float factor) { }
		@Override public void pop() { }
		@Override public boolean images() { return true; }
		@Override public void image(TextureRef texture, float u, float v, int w, int h, int argb) {
			images.add(texture.id + "@" + (int) u + "," + (int) v);
		}
	}
}
