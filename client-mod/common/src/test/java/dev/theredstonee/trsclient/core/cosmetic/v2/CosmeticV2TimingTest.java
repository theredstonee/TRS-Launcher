package dev.theredstonee.trsclient.core.cosmetic.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tempo und Verlauf der Redstone-Lampe ({@code lamp_helmet}, freies Teil) im Spiel = Studio-Werkbank: für 5 s ab einer
 * echten Wanduhr-Zeit (20-ms-Schritte) dasselbe Leucht-Bild (über {@link CosmeticAssets} wie im Spiel, also mit
 * {@code glowFrameTimeMs} 160 statt {@code frameTimeMs}), dieselbe Hof-Helligkeit und dieselbe Pose (Lampen-Puls) wie
 * {@code cosmetic-format.mjs} (Referenz → {@code lamp_helmet.timeline.json}). Dazu die Entfernungs-Stufen gegen das
 * Flimmern der HD-Texturen.
 */
class CosmeticV2TimingTest {
	/** Spiel-Backend-Attrappe: Textur = ihr Name. */
	static final class Names implements CapeTextures.Backend<String> {
		final List<String> uploaded = new ArrayList<String>();

		@Override
		public String upload(String name, int width, int height, int[] argb) {
			assertEquals(width * height, argb.length, name);
			uploaded.add(name + "@" + width + "x" + height);
			return name;
		}

		@Override
		public void release(String texture) {
		}
	}

	static CosmeticV2Cache.Loaded pixels(CosmeticV2 m) {
		int n = m.pixelWidth() * m.pixelHeight();
		int[][] base = new int[Math.max(1, m.frames)][n];
		int[][] glow = new int[m.glowFrames][n];
		for (int f = 0; f < glow.length; f++) java.util.Arrays.fill(glow[f], 0xFF000000 | f);
		return new CosmeticV2Cache.Loaded(m, base, glow);
	}

	static CosmeticAssets.Entry<String> upload(CosmeticV2 m, Names names, int ticks) {
		final CosmeticV2Cache.Loaded data = pixels(m);
		CosmeticAssets<String> assets = new CosmeticAssets<String>(names, (hat, done, failed) -> done.accept(data));
		OnlineConfig cfg = new OnlineConfig(true, "https://trs-launcher.theredstonee.de", OnlineConfig.DEFAULT_SESSION);
		HatInfo hat = HatInfo.v2("lamp_helmet", "/v1/cosmetics/lamp_helmet/model.json?v=d8cb59273565",
				"/v1/cosmetics/lamp_helmet.png?v=d8cb59273565", "/v1/cosmetics/lamp_helmet/glow.png?v=d8cb59273565",
				"d8cb59273565", 1, 12, cfg);
		CosmeticAssets.Entry<String> e = null;
		for (int i = 0; i < ticks; i++) {
			e = assets.get(hat, 1000 + i);
			assets.cleanup(1000 + i);
		}
		return e;
	}

	@Test
	void lampHelmetMatchesTheWorkbenchOverTime() throws IOException {
		CosmeticV2 m = CosmeticV2.parse(CosmeticV2Test.res("lamp_helmet.json"));
		assertEquals(12, m.glowFrames);
		assertEquals(160, m.glowFrameTimeMs, "Leucht-Streifen: 12 Bilder à 160 ms");
		assertEquals(0, m.frameTimeMs, "Grundtextur steht (1 Bild)");
		CosmeticAssets.Entry<String> e = upload(m, new Names(), 100);
		assertNotNull(e);
		JsonObject ref = new JsonParser().parse(CosmeticV2Test.res("lamp_helmet.timeline.json")).getAsJsonObject();
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		int changes = 0;
		String last = null;
		for (JsonElement se : ref.getAsJsonArray("samples")) {
			JsonObject s = se.getAsJsonObject();
			long t = s.get("t").getAsLong();
			// Leucht-Bild: genau das Bild der Werkbank (Textur-Name endet auf /g<bild>)
			V2Hat<String> hat = new V2Hat<String>(m, e.base(t), e.glow(t), "halo", t, false, e);
			assertEquals("cosmetics/lamp_helmet_0/g" + s.get("glowFrame").getAsInt(), hat.glow, "Leucht-Bild bei t=" + t);
			assertEquals("cosmetics/lamp_helmet_0/b0", hat.base, "Grundbild bei t=" + t);
			if (!hat.glow.equals(last)) changes++;
			last = hat.glow;
			// Hof-Helligkeit
			JsonArray halos = s.getAsJsonArray("halos");
			for (int i = 0; i < m.halos.size(); i++) {
				assertEquals(halos.get(i).getAsDouble(), CosmeticV2Renderer.haloIntensity(m.halos.get(i), t), 1e-6,
						"Hof " + i + " bei t=" + t);
			}
			// Pose: Helm steht, die Lampe pulst
			r.pose(m, t, true);
			assertMatrix(s.getAsJsonArray("helmet"), r.world(boneIndex(m, "helmet")), "helmet t=" + t);
			assertMatrix(s.getAsJsonArray("lamp"), r.world(boneIndex(m, "lamp")), "lamp t=" + t);
		}
		// 5 s bei 160 ms je Bild: genau 32 Wechsel (erster Stand plus 31 Schritte), nicht je Render-Bild
		assertEquals(32, changes, "Wechsel in 5 s: " + changes);
	}

	@Test
	void lodPicksLowerResolutionWithDistance() {
		// Faktor 8: Stufen 0–3
		assertEquals(3, CosmeticV2Renderer.maxLod(8));
		assertEquals(0, CosmeticV2Renderer.maxLod(1));
		assertEquals(4, CosmeticV2Renderer.maxLod(16));
		// 1080 Pixel hoch, 70° Sichtfeld: K = 540 / tan 35° ≈ 771 Pixel je Einheit auf 1 Einheit Abstand.
		// Werkbank-Nähe / Zoom (≈ 30 Modell-Pixel Abstand): 1 Unter-Pixel ≥ 3 Bildschirm-Pixel → volle Auflösung
		assertEquals(0, CosmeticV2Renderer.lodLevel(8, new double[]{0, 0, 30}, 70, 1080));
		// 3. Person (4 Blöcke ≈ 68 Einheiten): ρ = 8·68/771 ≈ 0,7 → 0
		assertEquals(0, CosmeticV2Renderer.lodLevel(8, new double[]{0, 20, 65}, 70, 1080));
		// anderer Spieler in 8 Blöcken (≈ 137 Einheiten): ρ ≈ 1,4 → Stufe 0/1 an der Grenze
		int mid = CosmeticV2Renderer.lodLevel(8, new double[]{0, 0, 137}, 70, 1080);
		assertTrue(mid == 0 || mid == 1);
		// kleines Fenster (480 hoch) in 4 Blöcken: ρ ≈ 1,6 → 1
		assertEquals(1, CosmeticV2Renderer.lodLevel(8, new double[]{0, 20, 65}, 70, 480));
		// 20 Blöcke weit: ρ ≈ 3,5 → 2; 60 Blöcke: gedeckelt auf 3
		assertEquals(2, CosmeticV2Renderer.lodLevel(8, new double[]{0, 0, 340}, 70, 1080));
		assertEquals(3, CosmeticV2Renderer.lodLevel(8, new double[]{0, 0, 1020}, 70, 1080));
		// Zoom (Sichtfeld 70/4) holt die volle Auflösung zurück
		assertEquals(0, CosmeticV2Renderer.lodLevel(8, new double[]{0, 0, 340}, 17.5, 1080));
		// ohne Kamera/Unsinn → volle Auflösung
		assertEquals(0, CosmeticV2Renderer.lodLevel(8, null, 70, 1080));
		assertEquals(0, CosmeticV2Renderer.lodLevel(8, new double[]{0, 0, 340}, 0, 1080));
	}

	@Test
	void lodTexturesUploadAfterFullResolutionAndKeepTheFrame() throws IOException {
		CosmeticV2 m = CosmeticV2.parse(CosmeticV2Test.res("lamp_helmet.json"));
		Names names = new Names();
		// 4 Uploads je Tick (erster Tick lädt nur): nach 5 Ticks ist Stufe 0 (1 + 12 Bilder) da, die kleineren Stufen fehlen noch
		CosmeticAssets.Entry<String> early = upload(m, names, 5);
		assertNotNull(early, "volle Auflösung nach 13 Uploads bereit");
		long t = 1790000000000L + 700;
		int frame = CosmeticV2Renderer.frameAt(t, 12, 160);
		V2Hat<String> hat = new V2Hat<String>(m, early.base(t), early.glow(t), null, t, false, early);
		// Stufe 2 noch nicht hochgeladen → volle Auflösung statt Lücke
		assertEquals("cosmetics/lamp_helmet_0/g" + frame, hat.glow(2));
		names = new Names();
		CosmeticAssets.Entry<String> e = upload(m, names, 20);
		assertEquals(13 * 4, names.uploaded.size(), "4 Stufen × (1 Grund + 12 Leucht)");
		assertTrue(names.uploaded.contains("cosmetics/lamp_helmet_0/g11_l3@48x32"), names.uploaded.toString());
		hat = new V2Hat<String>(m, e.base(t), e.glow(t), null, t, false, e);
		for (int level = 0; level <= 3; level++) {
			String suffix = level == 0 ? "" : "_l" + level;
			assertEquals("cosmetics/lamp_helmet_0/g" + frame + suffix, hat.glow(level), "gleiches Bild in Stufe " + level);
			assertEquals("cosmetics/lamp_helmet_0/b0" + suffix, hat.base(level));
		}
		assertEquals(hat.glow(3), hat.glow(9), "über der höchsten Stufe: höchste Stufe");
	}

	@Test
	void downsampleKeepsCutoutEdgesAndAveragesGlow() {
		// Grundtextur 4×2: links deckend rot, rechts halb deckend (2 von 4) blau, unten rechts einzelnes Pixel
		int R = 0xFFFF0000, B = 0xFF0000FF, T = 0x00000000;
		int[] base = { R, R, B, T, R, R, B, T };
		int[] half = V2Images.downsample(base, 4, 2, false);
		assertEquals(2, half.length);
		assertEquals(R, half[0]);
		assertEquals(B, half[1], "halbe Abdeckung bleibt deckend, Farbe ohne Schwarz vom leeren Rand");
		int[] sparse = { R, T, T, T };
		assertEquals(0, V2Images.downsample(sparse, 2, 2, false)[0], "ein Viertel Abdeckung → durchsichtig");
		// Leucht-Schicht (vormultipliziert): einfacher Mittelwert, Alpha 255
		int[] glow = { 0xFFFF0000, 0xFF000000, 0xFF000000, 0xFF000000 };
		assertEquals(0xFF400000, V2Images.downsample(glow, 2, 2, true)[0]);
		int[][] levels = V2Images.levels(new int[320 * 256], 320, 256, 3, true);
		assertEquals(4, levels.length);
		assertEquals(40 * 32, levels[3].length);
	}

	private static int boneIndex(CosmeticV2 m, String id) {
		for (int i = 0; i < m.bones.size(); i++) if (m.bones.get(i).id.equals(id)) return i;
		throw new AssertionError(id);
	}

	private static void assertMatrix(JsonArray expected, double[] actual, String what) {
		for (int k = 0; k < 16; k++) assertEquals(expected.get(k).getAsDouble(), actual[k], 1e-4, what + "[" + k + "]");
	}
}
