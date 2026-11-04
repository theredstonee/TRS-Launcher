package dev.theredstonee.trsclient.core.minigames;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Texturen mit Rückfällen und die Bilder beider Spiele, ohne Minecraft und mit selbst erzeugten Farben. */
class MinigameViewsTest {
	/** Kleinstes PNG (RGBA, 8 Bit). */
	private static byte[] png(int w, int h, int[] argb) throws IOException {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		for (int y = 0; y < h; y++) {
			raw.write(0);
			for (int x = 0; x < w; x++) {
				int c = argb[y * w + x];
				raw.write((c >> 16) & 255);
				raw.write((c >> 8) & 255);
				raw.write(c & 255);
				raw.write(c >>> 24);
			}
		}
		Deflater deflater = new Deflater();
		deflater.setInput(raw.toByteArray());
		deflater.finish();
		ByteArrayOutputStream packed = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(head);
		d.writeInt(w);
		d.writeInt(h);
		d.write(new byte[] {8, 6, 0, 0, 0});
		chunk(out, "IHDR", head.toByteArray());
		chunk(out, "IDAT", packed.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
		DataOutputStream d = new DataOutputStream(out);
		d.writeInt(data.length);
		byte[] name = type.getBytes("US-ASCII");
		d.write(name);
		d.write(data);
		CRC32 crc = new CRC32();
		crc.update(name);
		crc.update(data);
		d.writeInt((int) crc.getValue());
	}

	private static int[] filled(int w, int h, int color) {
		int[] out = new int[w * h];
		java.util.Arrays.fill(out, color);
		return out;
	}

	private static GameTextures source(final Map<String, byte[]> files) {
		return new GameTextures(new GameTextures.Source() {
			@Override
			public byte[] read(String location) {
				return files.get(location);
			}
		});
	}

	@Test
	void modernPathsWinOverOldOnes() throws IOException {
		Map<String, byte[]> files = new HashMap<String, byte[]>();
		files.put("minecraft:textures/block/bricks.png", png(16, 16, filled(16, 16, 0xFF112233)));
		files.put("minecraft:textures/blocks/brick.png", png(16, 16, filled(16, 16, 0xFF445566)));
		assertEquals(0xFF112233, source(files).block("bricks").d[0]);
	}

	@Test
	void oldVersionsUseOldPathsAndVanillaFallbacks() throws IOException {
		Map<String, byte[]> files = new HashMap<String, byte[]>();
		files.put("minecraft:textures/blocks/planks_oak.png", png(16, 16, filled(16, 16, 0xFF0A0B0C)));
		files.put("minecraft:textures/blocks/hardened_clay_stained_orange.png", png(16, 16, filled(16, 16, 0xFF0D0E0F)));
		files.put("minecraft:textures/blocks/stonebrick.png", png(16, 16, filled(16, 16, 0xFF101112)));
		GameTextures textures = source(files);
		assertEquals(0xFF0A0B0C, textures.block("oak_planks").d[0], "alter Pfad");
		assertEquals(0xFF0D0E0F, textures.block("copper_block").d[0], "Kupfer gibt es vor 1.17 nicht");
		assertEquals(0xFF101112, textures.block("deepslate_bricks").d[0], "Tiefenschiefer gibt es vor 1.17 nicht");
	}

	@Test
	void withoutAnyFileThereIsAlwaysAReplacement() {
		GameTextures textures = source(new HashMap<String, byte[]>());
		GameTextures.Tex stone = textures.block("stone");
		assertEquals(16, stone.w);
		assertEquals(256, stone.d.length);
		assertEquals(255, stone.d[0] >>> 24);
		assertEquals(0, textures.block("rail").d[0] >>> 24, "Schienen bleiben durchsichtig, sonst verdecken sie den Boden");
		assertEquals(0xFF123456, textures.entity("creeper", 8, 8, 8, 8, 0xFF123456).d[0]);
	}

	@Test
	void animatedStripsGiveTheFirstFrameAndLargePacksAreScaledDown() throws IOException {
		int[] strip = filled(16, 48, 0xFF00FF00);
		java.util.Arrays.fill(strip, 0, 16 * 16, 0xFFFF0000);
		int[] large = filled(64, 64, 0xFF0000FF);
		large[0] = 0xFFFFFFFF;
		Map<String, byte[]> files = new HashMap<String, byte[]>();
		files.put("minecraft:textures/block/lava_still.png", png(16, 48, strip));
		files.put("minecraft:textures/block/stone.png", png(64, 64, large));
		GameTextures textures = source(files);
		GameTextures.Tex lava = textures.block("lava_still");
		assertEquals(0xFFFF0000, lava.d[255]);
		GameTextures.Tex stone = textures.block("stone");
		assertEquals(256, stone.d.length);
		assertEquals(0xFFFFFFFF, stone.d[0]);
		assertEquals(0xFF0000FF, stone.d[1]);
	}

	@Test
	void largeEntitySheetsKeepTheirLayout() throws IOException {
		int[] sheet = filled(128, 64, 0xFF000000);
		// Kopf des Creepers bei (8, 8) in 64 × 32 beginnt bei (16, 16) in 128 × 64; abgetastet wird die Mitte des Felds.
		sheet[17 * 128 + 17] = 0xFF00FF00;
		Map<String, byte[]> files = new HashMap<String, byte[]>();
		files.put("minecraft:textures/entity/creeper/creeper.png", png(128, 64, sheet));
		GameTextures.Tex head = source(files).entity("creeper", 8, 8, 8, 8, 0xFFFFFFFF);
		assertEquals(64, head.d.length);
		assertEquals(0xFF00FF00, head.d[0]);
		assertEquals(0xFF000000, head.d[1]);
	}

	private static void assertOpaque(int[] pixels) {
		for (int i = 0; i < pixels.length; i++) {
			if ((pixels[i] >>> 24) != 255) throw new AssertionError("Pixel " + i + " nicht deckend: " + Integer.toHexString(pixels[i]));
		}
	}

	private static GameSound silent() {
		return new GameSound(null);
	}

	@Test
	void staplerDrawsWholeGamesWithAllSkins() {
		GameTextures textures = source(new HashMap<String, byte[]>());
		for (GameSkin skin : new GameSkin[] {GameSkin.REDSTONE, GameSkin.WINTER, GameSkin.FOX}) {
			final StaplerView view = new StaplerView(textures, skin, silent());
			Stapler game = new Stapler(99, new Stapler.Listener() {
				@Override
				public void onEvent(Stapler.Event event) {
					view.onEvent(event);
				}
			});
			view.draw(null);
			assertOpaque(view.pixels);
			int frames = 0;
			while (!game.finished() && frames < 4000) {
				int mask = frames % 7 == 0 ? Stapler.K_HARD : frames % 13 < 3 ? Stapler.K_LEFT : frames % 17 < 3 ? Stapler.K_RIGHT : 0;
				game.step(mask);
				if (frames % 4 == 0) {
					view.update(1f / 15f, game);
					view.draw(game);
				}
				frames++;
			}
			assertTrue(game.finished(), "Partie endet");
			view.update(0.1f, game);
			view.draw(game);
			assertOpaque(view.pixels);
		}
	}

	@Test
	void lorenDrawsARideWithAllSkins() {
		GameTextures textures = source(new HashMap<String, byte[]>());
		for (GameSkin skin : new GameSkin[] {GameSkin.REDSTONE, GameSkin.WINTER, GameSkin.FOX}) {
			final LorenView view = new LorenView(textures, skin, silent());
			final Loren[] holder = new Loren[1];
			Loren game = new Loren(5, new Loren.Listener() {
				@Override
				public void onEvent(Loren.EventType type, int o) {
					view.onEvent(type, holder[0]);
				}
			});
			holder[0] = game;
			view.draw(new Loren(4242));
			assertOpaque(view.pixels);
			int frames = 0;
			while (!game.finished() && frames < 2000) {
				game.signal = frames % 600 == 0 ? 15 : game.signal;
				int mask = frames % 600 == 1 ? Loren.K_TURBO : frames % 90 == 0 ? Loren.K_JUMP : frames % 140 == 0 ? Loren.K_LEFT : frames % 150 == 0 ? Loren.K_RIGHT : 0;
				game.step(mask);
				if (frames % 20 == 0) {
					view.update(1f / 3f, game);
					view.draw(game);
					assertOpaque(view.pixels);
				}
				frames++;
			}
			assertTrue(view.zoneName(game).length() > 0);
		}
	}

	@Test
	void lorenZoneNamesExistAsTranslationKeys() {
		GameTextures textures = source(new HashMap<String, byte[]>());
		LorenView view = new LorenView(textures, GameSkin.REDSTONE, silent());
		Loren far = new Loren(1);
		assertEquals("mine", view.zoneName(far));
		far.pos = 420 * Loren.UNIT;
		assertEquals("deepslate", view.zoneName(far));
		far.pos = 840 * Loren.UNIT;
		assertEquals("nether", view.zoneName(far));
		far.pos = 1260 * Loren.UNIT;
		assertEquals("mine", view.zoneName(far));
		assertEquals("frost", new LorenView(textures, GameSkin.WINTER, silent()).zoneName(new Loren(1)));
		assertEquals("den", new LorenView(textures, GameSkin.FOX, silent()).zoneName(new Loren(1)));
	}
}
