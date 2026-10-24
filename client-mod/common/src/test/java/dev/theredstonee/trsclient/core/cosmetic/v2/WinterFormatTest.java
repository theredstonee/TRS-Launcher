package dev.theredstonee.trsclient.core.cosmetic.v2;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Winter 2026 (Format Anhang D) mit synthetischen Platzhalter-Modellen – nie mit den echten (proprietären) Teilen:
 * abschaltbare Teile ({@code options}), Begleiter am Boden ({@code slot: pet}, {@code attach: ground}), neue Treiber
 * {@code slide}/{@code swim}, Grenzfälle der Winter-Teile (Flocken mit Skalierung 0,01, Wolke hoch über dem Kopf,
 * durchsichtige Flächen mit Höfen).
 */
class WinterFormatTest {
	/** Mütze mit Bart: Knochen cap → beard → beard_tip, Würfel an jedem, Hof am Bart. */
	static String hat(String options) {
		return "{\"format\":2,\"id\":\"test_hat\",\"name\":\"Test\",\"slot\":\"hat\",\"attach\":\"head\","
				+ "\"texture\":{\"file\":\"t.png\",\"width\":16,\"height\":16,\"scale\":1},"
				+ "\"bones\":[{\"id\":\"cap\",\"pivot\":[0,8,0]},{\"id\":\"beard\",\"parent\":\"cap\",\"pivot\":[0,1,4]},"
				+ "{\"id\":\"beard_tip\",\"parent\":\"beard\",\"pivot\":[0,-2,4]}],"
				+ "\"cubes\":[" + cube("c", "cap", "[-4,8,-4]", "[4,10,4]") + "," + cube("b", "beard", "[-3,-1,4]", "[3,2,5]") + ","
				+ cube("t", "beard_tip", "[-1,-3,4]", "[1,-1,5]") + "],"
				+ "\"halos\":[{\"bone\":\"beard_tip\",\"pos\":[0,-2,5],\"size\":4,\"color\":\"#ffffff\"}]"
				+ (options == null ? "" : ",\"options\":" + options) + "}";
	}

	static String cube(String id, String bone, String from, String to) {
		return "{\"id\":\"" + id + "\",\"bone\":\"" + bone + "\",\"from\":" + from + ",\"to\":" + to
				+ ",\"faces\":{\"south\":{\"uv\":[0,0,2,2]},\"up\":{\"uv\":[2,0,4,2]}}}";
	}

	static final String BEARD = "[{\"id\":\"beard\",\"bones\":[\"beard\"],\"default\":true,"
			+ "\"name\":{\"de\":\"Bart\",\"en\":\"Beard\",\"es\":\"Barba\"}}]";

	/** Platzhalter-Begleiter am Boden: ein Körper, Zustände idle/walk/slide/swim/air. */
	static String pet(String slot, String attach) {
		StringBuilder anims = new StringBuilder();
		String[] drivers = { "idle", "walk", "slide", "swim", "air" };
		for (int i = 0; i < drivers.length; i++) {
			if (i > 0) anims.append(',');
			anims.append("{\"id\":\"a").append(i).append("\",\"driver\":\"").append(drivers[i])
					.append("\",\"lengthMs\":1000,\"tracks\":[{\"bone\":\"body\",\"channel\":\"rotation\",\"keys\":[{\"t\":0,\"v\":[")
					.append(i * 10).append(",0,0]},{\"t\":1000,\"v\":[").append(i * 10).append(",0,0]}]}]}");
		}
		return "{\"format\":2,\"id\":\"test_pet\",\"name\":\"Test\",\"slot\":\"" + slot + "\",\"attach\":\"" + attach + "\","
				+ "\"texture\":{\"file\":\"t.png\",\"width\":16,\"height\":16,\"scale\":1},"
				+ "\"bones\":[{\"id\":\"body\",\"pivot\":[0,1,0]}],"
				+ "\"cubes\":[" + cube("b", "body", "[-3,0,-3]", "[3,10,3]") + "],"
				+ "\"animations\":[" + anims + "]}";
	}

	@Test
	void optionsAreValidatedLikeTheReference() {
		CosmeticV2.Check ok = CosmeticV2.check(hat(BEARD), -1, -1, -1, -1);
		assertTrue(ok.ok(), ok.errors.toString());
		CosmeticV2.Option beard = ok.model.option("beard");
		assertNotNull(beard);
		assertTrue(beard.defaultOn);
		assertEquals("Bart", beard.name("de"));
		assertEquals("Barba", beard.name("es"));
		assertEquals("Beard", beard.name("fr"), "Rückfall Englisch");
		assertEquals("Bart", beard.name("de-AT"));
		assertNull(ok.model.option("hair"));
		// Fehler wie validateModel: unbekannter Knochen, doppelte/ungültige id, default fehlt, mehr als 4, kein Array
		assertFalse(CosmeticV2.check(hat("[{\"id\":\"beard\",\"bones\":[\"nope\"],\"default\":true}]"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(hat("[{\"id\":\"beard\",\"bones\":[],\"default\":true}]"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(hat("[{\"id\":\"Beard\",\"bones\":[\"beard\"],\"default\":true}]"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(hat("[{\"id\":\"beard\",\"bones\":[\"beard\"]}]"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(hat("[{\"id\":\"b\",\"bones\":[\"beard\"],\"default\":true},"
				+ "{\"id\":\"b\",\"bones\":[\"cap\"],\"default\":true}]"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(hat("{\"id\":\"beard\"}"), -1, -1, -1, -1).ok());
		StringBuilder five = new StringBuilder("[");
		for (int i = 0; i < 5; i++) five.append(i == 0 ? "" : ",").append("{\"id\":\"o").append(i).append("\",\"bones\":[\"cap\"],\"default\":false}");
		assertFalse(CosmeticV2.check(hat(five + "]"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(hat("[{\"id\":\"beard\",\"bones\":[\"beard\"],\"default\":true,\"name\":{\"de\":"
				+ "\"0123456789012345678901234567890123456789X\"}}]"), -1, -1, -1, -1).ok(), "Name ≤ 40");
		// ohne options: leer, nichts versteckt
		CosmeticV2 plain = CosmeticV2.parse(hat(null));
		assertTrue(plain.options.isEmpty());
		assertEquals(0L, plain.hiddenBones(Collections.singletonMap("beard", false)));
	}

	@Test
	void hiddenOptionSkipsBoneSubtreeAndItsHalos() {
		CosmeticV2 m = CosmeticV2.parse(hat(BEARD));
		assertEquals(0L, m.hiddenBones(null), "Standard an");
		assertEquals(0L, m.hiddenBones(Collections.singletonMap("beard", true)));
		long off = m.hiddenBones(Collections.singletonMap("beard", false));
		assertEquals((1L << 1) | (1L << 2), off, "Bart und Kind-Knochen");
		Map<String, Boolean> unknown = new HashMap<String, Boolean>();
		unknown.put("hair", false);
		assertEquals(0L, m.hiddenBones(unknown), "unbekannte Option ändert nichts");

		CosmeticV2Renderer r = new CosmeticV2Renderer();
		int[] faces = new int[1];
		r.pose(m, 0, true, false, 0, 0, 1f, 0L);
		r.faces(m, -1, (p, uv, mat, nx, ny, nz) -> faces[0]++);
		assertEquals(6, faces[0]);
		int[] halos = new int[1];
		r.halos(m, 0, 0, 0, 40, false, (cx, cy, cz, half, rgb, i) -> halos[0]++);
		assertEquals(1, halos[0]);

		faces[0] = 0;
		halos[0] = 0;
		r.pose(m, 0, true, false, 0, 0, 1f, off);
		r.faces(m, -1, (p, uv, mat, nx, ny, nz) -> faces[0]++);
		r.halos(m, 0, 0, 0, 40, false, (cx, cy, cz, half, rgb, i) -> halos[0]++);
		assertEquals(2, faces[0], "nur die Mütze");
		assertEquals(0, halos[0], "Hof am Bart ist mit weg");
		// nächstes pose ohne Maske zeigt wieder alles
		faces[0] = 0;
		r.pose(m, 0, true);
		r.faces(m, -1, (p, uv, mat, nx, ny, nz) -> faces[0]++);
		assertEquals(6, faces[0]);
		// emit (Minecraft-Weg) überspringt ebenfalls
		int[] quads = new int[1];
		r.pose(m, 0, true, false, 0, 0, 1f, off);
		r.emit(m, CosmeticV2Renderer.PASS_CUTOUT, 0, null, (x, y, z, u, v, nx, ny, nz, argb) -> quads[0]++);
		assertEquals(8, quads[0], "2 Flächen × 4 Ecken");
	}

	@Test
	void petSlotNeedsGroundAttachAndKnowsSlideSwim() {
		CosmeticV2.Check ok = CosmeticV2.check(pet("pet", "ground"), -1, -1, -1, -1);
		assertTrue(ok.ok(), ok.errors.toString());
		CosmeticV2 m = ok.model;
		assertTrue(m.pet());
		assertEquals(CosmeticV2.ATTACH_GROUND, m.attach);
		assertEquals(CosmeticV2.DRIVER_SLIDE, CosmeticV2.driverIndex("slide"));
		assertEquals(CosmeticV2.DRIVER_SWIM, CosmeticV2.driverIndex("swim"));
		for (int d : new int[] { CosmeticV2.DRIVER_IDLE, CosmeticV2.DRIVER_WALK, CosmeticV2.DRIVER_SLIDE, CosmeticV2.DRIVER_SWIM,
				CosmeticV2.DRIVER_AIR }) {
			assertTrue(m.hasDriver(d), "Treiber " + d);
		}
		assertFalse(CosmeticV2.check(pet("pet", "head"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(pet("hat", "ground"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(pet("tail", "ground"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(pet("pet", "feet"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(pet("pet", "ground").replace("\"swim\"", "\"dive\""), -1, -1, -1, -1).ok());
		// Zustände werden exklusiv gespielt: slide (20°) statt idle (0°), Überblenden 50 % = 10°
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		r.pose(m, 0, true, false, CosmeticV2.DRIVER_SLIDE, CosmeticV2.DRIVER_SLIDE, 1f);
		assertEquals(20, pitch(r), 1e-3);
		r.pose(m, 0, true, false, CosmeticV2.DRIVER_SLIDE, CosmeticV2.DRIVER_IDLE, 0.5f);
		assertEquals(10, pitch(r), 1e-3);
		r.pose(m, 0, true, false, CosmeticV2.DRIVER_SWIM, CosmeticV2.DRIVER_SWIM, 1f);
		assertEquals(30, pitch(r), 1e-3);
		assertEquals(CosmeticV2.ATTACH_GROUND, CosmeticV2.attachOf("pet"));
		assertEquals(CosmeticV2.ATTACH_BODY, CosmeticV2.attachOf("wings"));
		assertEquals(CosmeticV2.ATTACH_HEAD, CosmeticV2.attachOf("companion"));
		assertNull(CosmeticV2.attachOf("tail"));
	}

	/** Neigung des Körpers um x (Grad) nach der Pose. */
	private static double pitch(CosmeticV2Renderer r) {
		double[] out = new double[6];
		double[] w = r.world(0);
		V2Math.transformPoint(w, 0, 1, 0, out, 0);
		V2Math.transformPoint(w, 0, 2, 0, out, 3);
		return Math.toDegrees(Math.atan2(out[5] - out[2], out[4] - out[1]));
	}

	@Test
	void tinyFlakesTallCloudsAndTranslucentIceRenderFinite() {
		// Schneeflocke: Würfel mit Positions- und Skalierungs-Spur bis 0,01 (Schneeflügel), Wolke bei y 23 (Schneewolke)
		String json = "{\"format\":2,\"id\":\"test_cloud\",\"name\":\"Test\",\"slot\":\"companion\",\"attach\":\"head\","
				+ "\"texture\":{\"file\":\"t.png\",\"width\":16,\"height\":16,\"scale\":8},"
				+ "\"glow\":{\"file\":\"g.png\",\"frames\":2,\"frameTimeMs\":100},"
				+ "\"bones\":[{\"id\":\"c1\",\"pivot\":[0,20,0]},{\"id\":\"f1\",\"parent\":\"c1\",\"pivot\":[0,18.75,0]}],"
				+ "\"cubes\":[{\"id\":\"cloud\",\"bone\":\"c1\",\"from\":[-6,17,-3],\"to\":[6,23,3],\"material\":\"translucent\","
				+ "\"faces\":{\"south\":{\"uv\":[0,0,12,6]},\"up\":{\"uv\":[0,6,12,12],\"material\":\"emissive\"}}},"
				+ "{\"id\":\"flake\",\"bone\":\"f1\",\"from\":[-0.5,18.25,-0.5],\"to\":[0.5,19.25,0.5],\"material\":\"emissive\","
				+ "\"faces\":{\"south\":{\"uv\":[12,0,13,1]},\"east\":{\"uv\":[12,1,13,2]}}}],"
				+ "\"halos\":[{\"bone\":\"f1\",\"pos\":[0,18.75,0.6],\"size\":3,\"color\":\"#cfefff\",\"normal\":[0,0,1]}],"
				+ "\"animations\":[{\"id\":\"fall\",\"driver\":\"idle\",\"lengthMs\":2000,\"tracks\":["
				+ "{\"bone\":\"f1\",\"channel\":\"position\",\"keys\":[{\"t\":0,\"v\":[0,0,0]},{\"t\":2000,\"v\":[0.3,-9.6,0]}]},"
				+ "{\"bone\":\"f1\",\"channel\":\"scale\",\"keys\":[{\"t\":0,\"v\":[1,1,1]},{\"t\":1900,\"v\":[0.01,0.01,0.01]},"
				+ "{\"t\":2000,\"v\":[0.01,0.01,0.01]}]}]}]}";
		CosmeticV2.Check c = CosmeticV2.check(json, 128, 128, 128, 256);
		assertTrue(c.ok(), c.errors.toString());
		CosmeticV2 m = c.model;
		assertTrue(m.has(CosmeticV2.TRANSLUCENT));
		assertTrue(m.has(CosmeticV2.EMISSIVE));
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		final double[] maxY = { -1e9 };
		final boolean[] finite = { true };
		for (long t = 0; t <= 2000; t += 50) {
			r.pose(m, t, true);
			r.emit(m, CosmeticV2Renderer.PASS_TRANSLUCENT, t, null, (x, y, z, u, v, nx, ny, nz, argb) -> {
				if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(nx) || !Float.isFinite(ny)) finite[0] = false;
				maxY[0] = Math.max(maxY[0], -y * 16);
			});
			r.emit(m, CosmeticV2Renderer.PASS_EMISSIVE, t, null, (x, y, z, u, v, nx, ny, nz, argb) -> {
				if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz)) {
					finite[0] = false;
				}
			});
			r.emit(m, CosmeticV2Renderer.PASS_HALO, t, new double[] { 0, 18, 40 }, (x, y, z, u, v, nx, ny, nz, argb) -> {
				if (!Float.isFinite(x) || !Float.isFinite(y)) finite[0] = false;
			});
			r.emit(m, CosmeticV2Renderer.PASS_GLOW, t, null, (x, y, z, u, v, nx, ny, nz, argb) -> {
				if (!Float.isFinite(x)) finite[0] = false;
			});
		}
		assertTrue(finite[0], "auch bei Skalierung 0,01 nur endliche Werte (Normalen normiert)");
		assertEquals(23, maxY[0], 1e-3, "Wolke 23 Pixel über dem Nacken – keine Höhen-Grenze");
	}
}
