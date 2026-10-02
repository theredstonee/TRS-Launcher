package dev.theredstonee.trsclient.core.cosmetic.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kosmetik-Format v2: Prüfung (Port von {@code validateModel}) und Mathematik gegen Werte der Studio-Referenz
 * ({@code cosmetic-format.mjs} → {@code *.expected.json}) für die freien Teile lamp_helmet, trs_cap und top_hat.
 */
class CosmeticV2Test {
	private static final double EPS = 1e-4;
	private static final double[] EYE = { 30, 25, 40 };

	static String res(String name) throws IOException {
		return new String(bytes(name), StandardCharsets.UTF_8);
	}

	static byte[] bytes(String name) throws IOException {
		try (InputStream in = CosmeticV2Test.class.getResourceAsStream("/cosmetic-v2/" + name)) {
			assertNotNull(in, name);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
			return out.toByteArray();
		}
	}

	@Test
	void parsesStudioExports() throws IOException {
		for (String id : new String[] { "lamp_helmet", "trs_cap", "top_hat" }) {
			CosmeticV2 m = CosmeticV2.parse(res(id + ".json"));
			JsonObject exp = new JsonParser().parse(res(id + ".expected.json")).getAsJsonObject();
			assertEquals(id, m.id);
			assertEquals(exp.get("faceCount").getAsInt(), m.faceCount, id);
			assertTrue(exp.get("valid").getAsBoolean());
			assertTrue(m.glow);
			assertEquals(12, m.glowFrames);
		}
		CosmeticV2 lamp = CosmeticV2.parse(res("lamp_helmet.json"));
		assertEquals(48, lamp.textureWidth);
		assertEquals(32, lamp.textureHeight);
		assertEquals(8, lamp.scale);
		assertEquals(384, lamp.pixelWidth());
		assertEquals(160, lamp.glowFrameTimeMs);
		assertEquals(5, lamp.halos.size());
		assertTrue(lamp.has(CosmeticV2.EMISSIVE));
		assertFalse(lamp.has(CosmeticV2.TRANSLUCENT));
	}

	@Test
	void mathMatchesStudio() throws IOException {
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		for (String id : new String[] { "lamp_helmet", "trs_cap", "top_hat" }) {
			CosmeticV2 m = CosmeticV2.parse(res(id + ".json"));
			JsonObject exp = new JsonParser().parse(res(id + ".expected.json")).getAsJsonObject();
			for (JsonElement te : exp.getAsJsonArray("times")) {
				JsonObject t = te.getAsJsonObject();
				long time = t.get("t").getAsLong();
				r.pose(m, time, true);
				// Knochen
				JsonObject bones = t.getAsJsonObject("bones");
				for (int b = 0; b < m.bones.size(); b++) {
					JsonArray e = bones.getAsJsonArray(m.bones.get(b).id);
					double[] w = r.world(b);
					for (int k = 0; k < 16; k++) {
						assertEquals(e.get(k).getAsDouble(), w[k], EPS, id + " t=" + time + " bone " + m.bones.get(b).id + "[" + k + "]");
					}
				}
				// Flächen (Reihenfolge wie die Referenz: Würfel, dann north/south/east/west/up/down)
				final List<double[]> corners = new ArrayList<double[]>();
				final List<float[]> uvs = new ArrayList<float[]>();
				r.faces(m, -1, (p, uv, mat, nx, ny, nz) -> {
					corners.add(p.clone());
					uvs.add(uv.clone());
				});
				JsonArray faces = t.getAsJsonArray("faces");
				assertEquals(faces.size(), corners.size());
				for (int f = 0; f < faces.size(); f++) {
					JsonObject fe = faces.get(f).getAsJsonObject();
					JsonArray ec = fe.getAsJsonArray("corners");
					JsonArray eu = fe.getAsJsonArray("uv");
					for (int k = 0; k < 4; k++) {
						for (int a = 0; a < 3; a++) {
							assertEquals(ec.get(k).getAsJsonArray().get(a).getAsDouble(), corners.get(f)[k * 3 + a], EPS,
									id + " face " + f);
						}
						assertEquals(eu.get(k).getAsJsonArray().get(0).getAsDouble(), uvs.get(f)[k * 2], 1e-6);
						assertEquals(eu.get(k).getAsJsonArray().get(1).getAsDouble(), uvs.get(f)[k * 2 + 1], 1e-6);
					}
				}
				// Höfe (Kamera bei EYE)
				final List<double[]> halos = new ArrayList<double[]>();
				r.halos(m, time, EYE[0], EYE[1], EYE[2], false,
						(cx, cy, cz, half, rgb, intensity) -> halos.add(new double[] { cx, cy, cz, half, intensity }));
				List<double[]> expected = new ArrayList<double[]>();
				for (JsonElement he : t.getAsJsonArray("halos")) {
					JsonObject h = he.getAsJsonObject();
					if (h.get("intensity").getAsDouble() <= 0.001) continue;
					JsonArray c = h.getAsJsonArray("center");
					expected.add(new double[] { c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble(),
							h.get("half").getAsDouble(), h.get("intensity").getAsDouble() });
				}
				assertEquals(expected.size(), halos.size(), id + " sichtbare Höfe");
				for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), halos.get(i), EPS, id + " Hof " + i);
				assertEquals(t.get("glowFrame").getAsInt(), CosmeticV2Renderer.frameAt(time, m.glowFrames, m.glowFrameTimeMs));
			}
		}
	}

	@Test
	void minecraftVerticesAreModelPartSpace() throws IOException {
		CosmeticV2 m = CosmeticV2.parse(res("top_hat.json"));
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		r.pose(m, 0, false);
		final float[] minY = { Float.MAX_VALUE };
		final int[] count = { 0 };
		r.emit(m, CosmeticV2Renderer.PASS_CUTOUT, 0, null, (x, y, z, u, v, nx, ny, nz, argb) -> {
			count[0]++;
			minY[0] = Math.min(minY[0], y);
			assertTrue(u >= 0 && u <= 1 && v >= 0 && v <= 1);
		});
		assertEquals(0, count[0] % 4);
		// Hut: y im Modell 7…16 (Krempe leicht schräg) → ModelPart y = −y/16 (oben negativ), ohne Anhebung.
		assertTrue(minY[0] < -0.95f && minY[0] > -1.1f, "oberste Ecke " + minY[0]);
		// Höfe nur mit Kamera
		final int[] halo = { 0 };
		r.emit(m, CosmeticV2Renderer.PASS_HALO, 0, null, (x, y, z, u, v, nx, ny, nz, argb) -> halo[0]++);
		assertEquals(0, halo[0]);
		r.emit(m, CosmeticV2Renderer.PASS_HALO, 0, new double[] { 0, 10, 60 }, (x, y, z, u, v, nx, ny, nz, argb) -> halo[0]++);
		assertTrue(halo[0] > 0 && halo[0] % 4 == 0);
	}

	@Test
	void helmetPutsTheModelOnTopOfTheHelmet() throws IOException {
		CosmeticV2 m = CosmeticV2.parse(res("lamp_helmet.json"));
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		final List<double[]> plain = new ArrayList<double[]>();
		final List<double[]> helmet = new ArrayList<double[]>();
		r.pose(m, 1234567, true, false);
		r.faces(m, -1, (p, uv, mat, nx, ny, nz) -> plain.add(p.clone()));
		r.pose(m, 1234567, true, true);
		r.faces(m, -1, (p, uv, mat, nx, ny, nz) -> helmet.add(p.clone()));
		assertEquals(plain.size(), helmet.size());
		double minAbsX = Double.MAX_VALUE;
		for (int f = 0; f < plain.size(); f++) {
			for (int k = 0; k < 4; k++) {
				double[] a = plain.get(f);
				double[] b = helmet.get(f);
				// x/z um die Kopfachse × 10/8, y um 1 px höher, Form sonst gleich
				assertEquals(a[k * 3] * 1.25, b[k * 3], 1e-9);
				assertEquals(a[k * 3 + 1] + 1.0, b[k * 3 + 1], 1e-9);
				assertEquals(a[k * 3 + 2] * 1.25, b[k * 3 + 2], 1e-9);
				if (b[k * 3 + 1] < 9.0) minAbsX = Math.min(minAbsX, Math.max(Math.abs(b[k * 3]), Math.abs(b[k * 3 + 2])));
			}
		}
		// Alles, was unterhalb der Helm-Oberseite (y 9) liegt, bleibt außerhalb der Helm-Schicht (±5).
		assertTrue(minAbsX > 5.0, "Abstand zur Kopfachse unter y 9: " + minAbsX);
		// Höfe wachsen mit (um bis zu × 10/8 – bei gedrehten Knochen etwas weniger, die Streckung ist nur waagerecht)
		final List<Double> halfPlain = new ArrayList<Double>();
		final List<Double> halfHelmet = new ArrayList<Double>();
		r.pose(m, 0, false, false);
		r.halos(m, 0, 0, 0, 1, true, (cx, cy, cz, half, rgb, i) -> halfPlain.add(half));
		r.pose(m, 0, false, true);
		r.halos(m, 0, 0, 0, 1, true, (cx, cy, cz, half, rgb, i) -> halfHelmet.add(half));
		assertEquals(halfPlain.size(), halfHelmet.size());
		for (int i = 0; i < halfPlain.size(); i++) {
			double ratio = halfHelmet.get(i) / halfPlain.get(i);
			assertTrue(ratio >= 1.0 - 1e-9 && ratio <= 1.25 + 1e-9, "Hof " + i + ": " + ratio);
		}
	}

	@Test
	void validationMirrorsReference() throws IOException {
		String good = res("top_hat.json");
		assertTrue(CosmeticV2.check(good, -1, -1, -1, -1).ok());
		assertInvalid(good.replaceFirst("\"format\": 2", "\"format\": 1"), "format");
		assertInvalid(good.replaceFirst("\"slot\": \"hat\"", "\"slot\": \"wings\""), "slot");
		assertInvalid(good.replaceFirst("\"scale\": 8", "\"scale\": 3"), "texture.scale");
		assertInvalid(good.replaceFirst("\"frames\": 12", "\"frames\": 17"), "glow.frames");
		assertInvalid("[]", "Modell ist kein Objekt");
		assertInvalid("{nope", "json");
		// Bildmaße gegen die PNGs
		assertFalse(CosmeticV2.check(good, 384, 320, 384, 3840).errors.size() > 0);
		assertTrue(CosmeticV2.check(good, 320, 320, 384, 3840).errors.get(0).startsWith("texture"));
		assertTrue(CosmeticV2.check(good, 384, 320, 384, 320).errors.get(0).startsWith("glow"));
		// Würfel außerhalb des Rasters / zu dick
		JsonObject m = new JsonParser().parse(good).getAsJsonObject();
		JsonObject cube = m.getAsJsonArray("cubes").get(0).getAsJsonObject();
		cube.getAsJsonArray("from").set(0, new com.google.gson.JsonPrimitive(-5.1));
		assertInvalid(m.toString(), "cubes[0]");
		// Unbekannter Knochen einer Spur
		JsonObject m2 = new JsonParser().parse(good).getAsJsonObject();
		m2.getAsJsonArray("animations").get(0).getAsJsonObject().getAsJsonArray("tracks").get(0).getAsJsonObject()
				.addProperty("bone", "missing");
		assertInvalid(m2.toString(), "animations[0].tracks[0]");
		// Eltern-Knochen muss vorher stehen
		JsonObject m3 = new JsonParser().parse(res("lamp_helmet.json")).getAsJsonObject();
		JsonArray bones = m3.getAsJsonArray("bones");
		JsonElement first = bones.get(0);
		bones.set(0, bones.get(1));
		bones.set(1, first);
		assertInvalid(m3.toString(), "bones[0]");
		assertThrows(IllegalArgumentException.class, () -> CosmeticV2.parse("{}"));
	}

	private static void assertInvalid(String json, String path) {
		CosmeticV2.Check c = CosmeticV2.check(json, -1, -1, -1, -1);
		assertFalse(c.ok(), path);
		assertNull(c.model);
		boolean found = false;
		for (String e : c.errors) if (e.startsWith(path)) found = true;
		assertTrue(found, path + " in " + c.errors);
	}

	@Test
	void decodesAndSplitsStrips() throws IOException {
		CosmeticV2Cache.Loaded l = CosmeticV2Cache.decode(res("lamp_helmet.json"), bytes("lamp_helmet.png"), bytes("lamp_helmet-glow.png"));
		assertEquals(1, l.base.length);
		assertEquals(384 * 256, l.base[0].length);
		assertEquals(12, l.glow.length);
		// Leuchten vormultipliziert: Alpha immer 255
		for (int[] f : l.glow) for (int p : f) assertEquals(0xFF, p >>> 24);
		// Falsche Maße (Leucht-Streifen als Textur) → Fehler
		assertThrows(IOException.class, () -> CosmeticV2Cache.decode(res("lamp_helmet.json"), bytes("lamp_helmet-glow.png"), null));
	}

	@Test
	void imagesHelpers() throws IOException {
		int[] strip = new int[4 * 2 * 3];
		for (int i = 0; i < strip.length; i++) strip[i] = i;
		int[][] frames = V2Images.split(strip, 4, 6, 4, 2, 3);
		assertEquals(3, frames.length);
		assertEquals(8, frames[1][0]);
		// Datei hat weniger Bilder als versprochen → weniger
		assertEquals(2, V2Images.split(new int[4 * 4], 4, 4, 4, 2, 5).length);
		assertThrows(IOException.class, () -> V2Images.split(new int[5 * 4], 5, 4, 4, 2, 2));
		int[] m = V2Images.mirror(new int[] { 1, 2, 3, 4, 5, 6 }, 3, 2, null);
		assertArrayEquals(new int[] { 3, 2, 1, 6, 5, 4 }, m);
		int[] c = V2Images.composite(new int[] { 0xFF808080, 0x00FFFFFF }, new int[] { 0xFF400000, 0xFFFFFFFF }, 0.5f, null);
		assertEquals(0xFF804040, c[0]);
		assertEquals(0, c[1]);
		int[] halo = V2Images.haloAdditive();
		assertEquals(V2Images.HALO_SIZE * V2Images.HALO_SIZE, halo.length);
		int mid = halo[(V2Images.HALO_SIZE / 2) * V2Images.HALO_SIZE + V2Images.HALO_SIZE / 2] & 0xFF;
		assertTrue(mid > 230);
		assertEquals(0, halo[0] & 0xFF);
	}

	@Test
	void hatInfoV2ResolvesAndChecksUrls() {
		OnlineConfig cfg = new OnlineConfig(true, "https://trs-launcher.theredstonee.de", OnlineConfig.DEFAULT_SESSION);
		HatInfo h = HatInfo.v2("redstone_crown", "/v1/cosmetics/redstone_crown/model.json?v=abc123def456",
				"https://trs-launcher.theredstonee.de/v1/cosmetics/redstone_crown.png?v=abc123def456",
				"/v1/cosmetics/redstone_crown/glow.png?v=abc123def456", "abc123def456", 1, 12, cfg);
		assertNotNull(h);
		assertTrue(h.v2());
		assertEquals("https://trs-launcher.theredstonee.de/v1/cosmetics/redstone_crown/model.json?v=abc123def456", h.modelUrl);
		assertEquals("abc123def456", h.hash);
		// fremder Host, kaputte ID
		assertNull(HatInfo.v2("redstone_crown", "https://evil.example/model.json", "/v1/x.png", null, null, 1, 0, cfg));
		assertNull(HatInfo.v2("Redstone", "/v1/a.json", "/v1/a.png", null, null, 1, 0, cfg));
		// ohne Hash: aus den Adressen gebildet, stabil
		HatInfo a = HatInfo.v2("halo", "/v1/cosmetics/halo/model.json?v=1", "/v1/cosmetics/halo.png?v=1", null, null, null, null, cfg);
		HatInfo b = HatInfo.v2("halo", "/v1/cosmetics/halo/model.json?v=1", "/v1/cosmetics/halo.png?v=1", null, null, null, null, cfg);
		assertEquals(a.key(), b.key());
		assertEquals(12, a.hash.length());
		// Format 1 (Ente): Adresse ebenfalls API-relativ erlaubt
		HatInfo duck = HatInfo.of("rubber_duck", "duck", "/v1/cosmetics/rubber_duck.png?v=1", 2, 1, null, cfg);
		assertNotNull(duck);
		assertFalse(duck.v2());
		assertEquals("https://trs-launcher.theredstonee.de/v1/cosmetics/rubber_duck.png?v=1", duck.texture.url);
		assertNull(HatInfo.of("rubber_duck", "duck", "https://evil.example/duck.png", 2, 1, null, cfg));
	}

	@Test
	void diskCacheServesSecondLoadWithoutNetwork(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
		final Map<String, byte[]> files = new java.util.HashMap<String, byte[]>();
		files.put("model", bytes("lamp_helmet.json"));
		files.put("tex", bytes("lamp_helmet.png"));
		files.put("glow", bytes("lamp_helmet-glow.png"));
		final int[] calls = { 0 };
		OnlineConfig cfg = new OnlineConfig(true, "http://127.0.0.1:1", OnlineConfig.DEFAULT_SESSION);
		dev.theredstonee.trsclient.core.online.Http http = request -> {
			calls[0]++;
			String u = request.url;
			byte[] body = u.contains("model.json") ? files.get("model") : u.contains("glow") ? files.get("glow") : files.get("tex");
			return new dev.theredstonee.trsclient.core.online.Http.Response(200, new java.util.HashMap<String, String>(), body);
		};
		dev.theredstonee.trsclient.core.online.TrsApi api = new dev.theredstonee.trsclient.core.online.TrsApi(http, cfg);
		HatInfo hat = HatInfo.v2("lamp_helmet", "/v1/cosmetics/lamp_helmet/model.json?v=aaa111", "/v1/cosmetics/lamp_helmet.png?v=aaa111",
				"/v1/cosmetics/lamp_helmet/glow.png?v=aaa111", "aaa111", 1, 12, cfg);
		CosmeticV2Cache cache = new CosmeticV2Cache(dir);
		CosmeticV2Cache.Loaded first = cache.load(hat, api, null);
		assertEquals(3, calls[0]);
		assertTrue(Files.isRegularFile(dir.resolve("lamp_helmet-aaa111.json")));
		CosmeticV2Cache.Loaded second = cache.load(hat, api, null);
		assertEquals(3, calls[0]);
		assertEquals(first.model.faceCount, second.model.faceCount);
		// neuer Stand löscht den alten
		HatInfo newer = HatInfo.v2("lamp_helmet", "/v1/cosmetics/lamp_helmet/model.json?v=bbb222", "/v1/cosmetics/lamp_helmet.png?v=bbb222",
				"/v1/cosmetics/lamp_helmet/glow.png?v=bbb222", "bbb222", 1, 12, cfg);
		cache.load(newer, api, null);
		assertEquals(6, calls[0]);
		assertFalse(Files.exists(dir.resolve("lamp_helmet-aaa111.json")));
		assertTrue(Files.exists(dir.resolve("lamp_helmet-bbb222-glow.png")));
	}
}
