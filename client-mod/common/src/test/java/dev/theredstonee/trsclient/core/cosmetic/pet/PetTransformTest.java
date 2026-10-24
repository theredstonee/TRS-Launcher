package dev.theredstonee.trsclient.core.cosmetic.pet;

import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer;
import dev.theredstonee.trsclient.core.cosmetic.v2.V2Math;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vorzeichen der Begleiter-Darstellung (Pinguin-Flossen, Winter 2026) mit einem synthetischen Platzhalter-Modell: Drehung
 * nach Format §4 (rechtshändig, Rz(+) dreht +x Richtung +y), keine Spiegelung beim Einhängen am Boden ({@link PetDraw})
 * und +z = Blick-/Laufrichtung in der Welt.
 */
class PetTransformTest {
	/**
	 * Zwei Flossen wie beim Pinguin: links (+x) Knochen {@code flip} an (3, 7,5, 0), rechts die Spiegel-Kopie
	 * {@code flip_m}; Würfel hängen vom Drehpunkt nach unten. idle dreht die linke um {@code rz}, die rechte um −rz.
	 */
	static String flippers(double rz) {
		String cube = ",\"faces\":{\"south\":{\"uv\":[0,0,1,4]},\"east\":{\"uv\":[1,0,2,4]},\"west\":{\"uv\":[2,0,3,4]}}}";
		return "{\"format\":2,\"id\":\"test_flippers\",\"name\":\"Test\",\"slot\":\"pet\",\"attach\":\"ground\","
				+ "\"texture\":{\"file\":\"t.png\",\"width\":16,\"height\":16,\"scale\":1},"
				+ "\"bones\":[{\"id\":\"body\",\"pivot\":[0,1,0]},{\"id\":\"flip\",\"parent\":\"body\",\"pivot\":[3,7.5,0]},"
				+ "{\"id\":\"flip_m\",\"parent\":\"body\",\"pivot\":[-3,7.5,0]}],"
				+ "\"cubes\":[{\"id\":\"l\",\"bone\":\"flip\",\"from\":[3,3,-1],\"to\":[3.5,7.75,1]" + cube + ","
				+ "{\"id\":\"r\",\"bone\":\"flip_m\",\"from\":[-3.5,3,-1],\"to\":[-3,7.75,1]" + cube + "],"
				+ "\"animations\":[{\"id\":\"idle\",\"driver\":\"idle\",\"lengthMs\":1000,\"tracks\":["
				+ "{\"bone\":\"flip\",\"channel\":\"rotation\",\"keys\":[{\"t\":0,\"v\":[0,0," + rz + "]},{\"t\":1000,\"v\":[0,0," + rz + "]}]},"
				+ "{\"bone\":\"flip_m\",\"channel\":\"rotation\",\"keys\":[{\"t\":0,\"v\":[0,0," + (-rz) + "]},{\"t\":1000,\"v\":[0,0," + (-rz) + "]}]}]}]}";
	}

	private static CosmeticV2 model(double rz) {
		CosmeticV2.Check c = CosmeticV2.check(flippers(rz), -1, -1, -1, -1);
		assertTrue(c.ok(), c.errors.toString());
		return c.model;
	}

	/** Spitze der linken Flosse (Mitte der Unterkante) im Modellraum nach der Pose. */
	private static double[] leftTip(CosmeticV2 m) {
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		r.pose(m, 500, true);
		double[] out = new double[3];
		V2Math.transformPoint(r.world(1), 3.25, 3, 0, out, 0);
		return out;
	}

	@Test
	void positiveZRotationMovesTheLeftFlipperOutward() {
		// Format §4: Rz(+) dreht +x nach +y – eine nach unten hängende Flosse links (+x) schwingt damit nach AUSSEN.
		double[] out = leftTip(model(35));
		assertTrue(out[0] > 3.5 + 2.0, "Flossenspitze nach außen (+x): " + out[0]);
		assertTrue(out[1] > 3.0, "und angehoben: " + out[1]);
		// −35° (so stand es im ersten Pinguin-Export) zieht sie nach INNEN in den Rumpf (x < 3) – genau der gemeldete Fehler.
		double[] in = leftTip(model(-35));
		assertTrue(in[0] < 3.0 - 2.0, "−35° = nach innen: " + in[0]);
		// Rechte Flosse (Spiegel-Kopie, −rz) geht spiegelbildlich nach außen (−x)
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		r.pose(model(35), 500, true);
		double[] right = new double[3];
		V2Math.transformPoint(r.world(2), -3.25, 3, 0, right, 0);
		assertEquals(-out[0], right[0], 1e-9);
		assertEquals(out[1], right[1], 1e-9);
	}

	/** Weiteste Ecke nach links/rechts (Welt, kamerazentriert) aller Vierecke des Modells am Boden. */
	private static float[] extent(CosmeticV2 m, double yaw) {
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		r.pose(m, 500, true);
		PetDraw<String> d = new PetDraw<String>();
		d.set(0, 0, 0, yaw);
		final float[] e = { Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE };
		r.emit(m, CosmeticV2Renderer.PASS_CUTOUT, 500, null, d.wrap((x, y, z, u, v, nx, ny, nz, argb) -> {
			e[0] = Math.min(e[0], x);
			e[1] = Math.max(e[1], x);
			e[2] = Math.min(e[2], z);
			e[3] = Math.max(e[3], z);
		}));
		return e;
	}

	@Test
	void groundAttachIsNotMirroredAndFacesTheWalkingDirection() {
		// Gieren 0 = Blick nach Süden (+z): links vom Begleiter = Osten (+x). Ausgebreitete Flossen reichen weit nach ±x.
		float[] spread = extent(model(35), 0);
		float[] rest = extent(model(0), 0);
		assertTrue(spread[1] > rest[1] + 0.1f, "links (+x) weiter außen: " + spread[1] + " vs " + rest[1]);
		assertTrue(spread[0] < rest[0] - 0.1f, "rechts (−x) weiter außen: " + spread[0] + " vs " + rest[0]);
		assertEquals(3.5f / 16f, rest[1], 1e-5f, "Ruhe: Außenkante der linken Flosse bei +3,5 px (keine Spiegelung)");
		// Gieren 90 = Blick nach Westen (−x): links = Süden (+z) – die Flossen spreizen sich entlang z
		float[] west = extent(model(35), 90);
		assertEquals(spread[1], west[3], 1e-5f, "links = +z beim Blick nach Westen");
		assertEquals(-spread[1], west[2], 1e-5f);
		// Basis bleibt rechtshändig (links × oben = vorne) für jedes Gieren – sonst wäre alles gespiegelt.
		for (int yaw = -180; yaw < 180; yaw += 15) {
			float[] l = axis(yaw, 16, 0, 0), u = axis(yaw, 0, 16, 0), f = axis(yaw, 0, 0, 16);
			float cx = l[1] * u[2] - l[2] * u[1], cy = l[2] * u[0] - l[0] * u[2], cz = l[0] * u[1] - l[1] * u[0];
			assertEquals(f[0], cx, 1e-5f);
			assertEquals(f[1], cy, 1e-5f);
			assertEquals(f[2], cz, 1e-5f);
			// vorne = Minecraft-Blickrichtung (−sin, 0, cos)
			double h = Math.toRadians(yaw);
			assertEquals(-Math.sin(h), f[0], 1e-5);
			assertEquals(Math.cos(h), f[2], 1e-5);
		}
	}

	/** Eine Modellrichtung (Einheiten, Anhängepunkt-Raum) als Weltvektor in Blöcken bei Gieren {@code yaw}. */
	private static float[] axis(double yaw, float x, float y, float z) {
		PetDraw<String> d = new PetDraw<String>();
		d.set(0, 0, 0, yaw);
		final float[] o = new float[3];
		// emit liefert ModelPart-Raum: (x/16, −y/16, −z/16)
		d.wrap((wx, wy, wz, u, v, nx, ny, nz, argb) -> {
			o[0] = wx;
			o[1] = wy;
			o[2] = wz;
		}).vertex(x / 16f, -y / 16f, -z / 16f, 0, 0, 0, 0, 0, -1);
		return o;
	}
}
