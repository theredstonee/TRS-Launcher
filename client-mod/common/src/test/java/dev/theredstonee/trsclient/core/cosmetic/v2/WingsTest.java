package dev.theredstonee.trsclient.core.cosmetic.v2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Flügel (Halloween 2026): Slot {@code wings} am Körper, Zustände idle/air/sneak mit Überblendung und Rückfall auf idle. */
class WingsTest {
	/** Ein Knochen am Rücken; idle dreht 0 → 10°, air steht fest bei 40° (beides um y), sneak fehlt. */
	static String model(String slot, String attach) {
		return "{\"format\":2,\"id\":\"test_wings\",\"name\":\"Test\",\"slot\":\"" + slot + "\",\"attach\":\"" + attach + "\","
				+ "\"texture\":{\"file\":\"t.png\",\"width\":8,\"height\":8,\"scale\":1},"
				+ "\"bones\":[{\"id\":\"w\",\"pivot\":[0,-3,-2.5]}],"
				+ "\"cubes\":[{\"id\":\"c\",\"bone\":\"w\",\"from\":[0,-6,-3],\"to\":[4,-2,-3],\"faces\":{\"south\":{\"uv\":[0,0,4,4]}}}],"
				+ "\"animations\":["
				+ "{\"id\":\"i\",\"driver\":\"idle\",\"lengthMs\":1000,\"tracks\":[{\"bone\":\"w\",\"channel\":\"rotation\",\"keys\":[{\"t\":0,\"v\":[0,0,0]},{\"t\":1000,\"v\":[0,10,0]}]}]},"
				+ "{\"id\":\"a\",\"driver\":\"air\",\"lengthMs\":1000,\"tracks\":[{\"bone\":\"w\",\"channel\":\"rotation\",\"keys\":[{\"t\":0,\"v\":[0,40,0]},{\"t\":1000,\"v\":[0,40,0]}]}]}]}";
	}

	@AfterEach
	void reset() {
		WingState.forced = -1;
	}

	@Test
	void wingsNeedBodyAttachAndHatsStayOnTheHead() {
		CosmeticV2.Check ok = CosmeticV2.check(model("wings", "body"), -1, -1, -1, -1);
		assertTrue(ok.ok(), ok.errors.toString());
		assertEquals(CosmeticV2.SLOT_WINGS, ok.model.slot);
		assertTrue(ok.model.hasDriver(CosmeticV2.driverIndex("air")));
		assertFalse(ok.model.hasDriver(CosmeticV2.driverIndex("sneak")));
		assertFalse(CosmeticV2.check(model("wings", "head"), -1, -1, -1, -1).ok());
		assertFalse(CosmeticV2.check(model("hat", "body"), -1, -1, -1, -1).ok());
		assertTrue(CosmeticV2.check(model("hat", "head"), -1, -1, -1, -1).ok());
	}

	/** Drehwinkel (Grad) des Knochens um y nach der Pose. */
	private static double yaw(CosmeticV2Renderer r) {
		double[] out = new double[6];
		double[] w = r.world(0);
		V2Math.transformPoint(w, 0, -3, -2.5, out, 0);
		V2Math.transformPoint(w, 1, -3, -2.5, out, 3);
		return Math.toDegrees(Math.atan2(-(out[5] - out[2]), out[3] - out[0]));
	}

	@Test
	void statesBlendAndFallBackToIdle() {
		CosmeticV2 m = CosmeticV2.parse(model("wings", "body"));
		CosmeticV2Renderer r = new CosmeticV2Renderer();
		int idle = WingState.driver(WingState.IDLE);
		int air = WingState.driver(WingState.AIR);
		int sneak = WingState.driver(WingState.SNEAK);
		r.pose(m, 0, true, false, air, air, 1f);
		assertEquals(40, Math.abs(yaw(r)), 1e-3);
		r.pose(m, 0, true, false, idle, idle, 1f);
		assertEquals(0, yaw(r), 1e-3);
		// Halb überblendet: idle (0°) → air (40°) = 20°
		r.pose(m, 0, true, false, air, idle, 0.5f);
		assertEquals(20, Math.abs(yaw(r)), 1e-3);
		// sneak fehlt im Modell → idle (bei t = 500 ms: 5°)
		r.pose(m, 500, true, false, sneak, sneak, 1f);
		assertEquals(5, Math.abs(yaw(r)), 1e-3);
		// Hüte (ohne Zustand) wie bisher: nur idle
		r.pose(m, 500, true, false);
		assertEquals(5, Math.abs(yaw(r)), 1e-3);
	}

	@Test
	void stateTrackerFadesAndDelaysAir() {
		assertEquals(WingState.SNEAK, WingState.classify(true, true, false, false, false, false));
		assertEquals(WingState.AIR, WingState.classify(false, true, true, false, false, false));
		assertEquals(WingState.AIR, WingState.classify(false, false, false, false, false, false));
		assertEquals(WingState.IDLE, WingState.classify(false, false, false, true, false, false), "reiten");
		assertEquals(WingState.IDLE, WingState.classify(false, false, false, false, true, false), "Wasser");
		assertEquals(WingState.IDLE, WingState.classify(false, false, false, false, false, true), "Leiter");

		WingState ws = new WingState();
		WingState.Snapshot s = ws.update("p", WingState.IDLE, 1000);
		assertEquals(1f, s.blend, 1e-6);
		// Luft erst nach 100 ms
		s = ws.update("p", WingState.AIR, 1050);
		assertEquals(WingState.IDLE, s.state);
		s = ws.update("p", WingState.AIR, 1160);
		assertEquals(WingState.AIR, s.state);
		assertEquals(4, s.driver);
		assertEquals(0, s.prevDriver);
		assertEquals(0f, s.blend, 1e-6);
		s = ws.update("p", WingState.AIR, 1160 + 125);
		assertEquals(0.5f, s.blend, 1e-6);
		s = ws.update("p", WingState.AIR, 1160 + 250);
		assertEquals(1f, s.blend, 1e-6);
		// Schleichen sofort
		s = ws.update("p", WingState.SNEAK, 2000);
		assertEquals(2, s.driver);
		assertEquals(4, s.prevDriver);
		// Erzwungen (Selbsttest)
		WingState.forced = WingState.AIR;
		s = ws.update("q", WingState.IDLE, 3000);
		assertEquals(WingState.AIR, s.state);
		assertNotNull(s);
	}
}
