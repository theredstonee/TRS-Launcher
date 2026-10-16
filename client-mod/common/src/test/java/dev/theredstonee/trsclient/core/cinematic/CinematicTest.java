package dev.theredstonee.trsclient.core.cinematic;

import dev.theredstonee.trsclient.core.module.CinematicModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kamera-Fahrten: Kurve, Zeitplan, Datei und Steuerung. */
class CinematicTest {
	static final double EPS = 1e-6;

	@TempDir
	Path dir;

	static Keyframe k(double x, double y, double z, float yaw, float pitch, float fov) {
		return new Keyframe(x, y, z, yaw, pitch, fov);
	}

	static List<Keyframe> three() {
		return Arrays.asList(k(0, 64, 0, 0, 0, 70), k(10, 70, 0, 90, -20, 50), k(20, 64, 10, 180, 10, 90));
	}

	@Test
	void linearPathInterpolatesEveryChannel() {
		CameraPath p = CameraPath.of(three(), CameraPath.Smoothing.LINEAR, CameraPath.Timing.PER_SEGMENT, 4, false);
		assertEquals(8, p.duration(), EPS);
		CameraPose pose = p.pose(2, new CameraPose());
		assertEquals(5, pose.x, EPS);
		assertEquals(67, pose.y, EPS);
		assertEquals(45, pose.yaw, 1e-4);
		assertEquals(-10, pose.pitch, 1e-4);
		assertEquals(60, pose.fov, 1e-4);
		p.pose(-5, pose);
		assertEquals(0, pose.x, EPS, "vor dem Start = erster Keyframe");
		p.pose(99, pose);
		assertEquals(20, pose.x, EPS, "nach dem Ende = letzter Keyframe");
		assertEquals(10, pose.z, EPS);
	}

	@Test
	void smoothPathPassesThroughEveryKeyframeWithoutJumps() {
		CameraPath p = CameraPath.of(three(), CameraPath.Smoothing.SMOOTH, CameraPath.Timing.PER_SEGMENT, 3, false);
		CameraPose pose = new CameraPose();
		for (int i = 0; i < 3; i++) {
			p.pose(p.timeOf(i), pose);
			Keyframe want = three().get(i);
			assertEquals(want.x, pose.x, 1e-6);
			assertEquals(want.y, pose.y, 1e-6);
			assertEquals(want.z, pose.z, 1e-6);
			assertEquals(want.fov, pose.fov, 1e-4);
		}
		// Stetig: kleine Zeitschritte → kleine Wege (keine Sprünge an den Keyframes).
		CameraPose prev = p.pose(0, new CameraPose());
		double maxStep = 0;
		for (int i = 1; i <= 600; i++) {
			CameraPose cur = p.pose(i * 0.01, new CameraPose());
			double d = Math.sqrt(sq(cur.x - prev.x) + sq(cur.y - prev.y) + sq(cur.z - prev.z));
			maxStep = Math.max(maxStep, d);
			prev = cur;
		}
		assertTrue(maxStep < 0.1, "größter Schritt " + maxStep);
		// Glatt ≠ linear zwischen den Punkten (Kurve).
		CameraPose mid = p.pose(1.5, new CameraPose());
		CameraPose lin = CameraPath.of(three(), CameraPath.Smoothing.LINEAR, CameraPath.Timing.PER_SEGMENT, 3, false)
				.pose(1.5, new CameraPose());
		assertTrue(Math.abs(mid.z - lin.z) > 0.05 || Math.abs(mid.y - lin.y) > 0.05);
	}

	static double sq(double v) {
		return v * v;
	}

	@Test
	void yawTakesTheShortWayRound() {
		CameraPath p = CameraPath.of(Arrays.asList(k(0, 0, 0, 350, 0, 70), k(0, 0, 1, 10, 0, 70)), CameraPath.Smoothing.LINEAR,
				CameraPath.Timing.PER_SEGMENT, 2, false);
		CameraPose pose = p.pose(1, new CameraPose());
		assertEquals(0, CameraPath.wrapDegrees(pose.yaw), 1e-4, "350° → 10° über 0°");
		CameraPath back = CameraPath.of(Arrays.asList(k(0, 0, 0, -170, 0, 70), k(0, 0, 1, 170, 0, 70)), CameraPath.Smoothing.SMOOTH,
				CameraPath.Timing.PER_SEGMENT, 2, false);
		assertEquals(180, Math.abs(back.pose(1, pose).yaw), 1e-3);
	}

	@Test
	void totalTimingGivesEvenSpeedAndEaseSlowsTheEnds() {
		List<Keyframe> keys = Arrays.asList(k(0, 0, 0, 0, 0, 70), k(30, 0, 0, 0, 0, 70), k(40, 0, 0, 0, 0, 70));
		CameraPath p = CameraPath.of(keys, CameraPath.Smoothing.LINEAR, CameraPath.Timing.TOTAL, 8, false);
		assertEquals(8, p.duration(), EPS);
		assertEquals(6, p.timeOf(1), EPS, "30 von 40 Blöcken = 6 von 8 s");
		assertEquals(20, p.pose(4, new CameraPose()).x, EPS);
		CameraPath eased = CameraPath.of(keys, CameraPath.Smoothing.LINEAR, CameraPath.Timing.TOTAL, 8, true);
		assertTrue(eased.pose(0.5, new CameraPose()).x < p.pose(0.5, new CameraPose()).x, "langsamer Start");
		assertEquals(20, eased.pose(4, new CameraPose()).x, 1e-6, "Mitte gleich");
		assertEquals(40, eased.pose(8, new CameraPose()).x, 1e-6);
		// Nur ein Keyframe: keine Fahrt.
		assertNull(CameraPath.of(keys.subList(0, 1), CameraPath.Smoothing.SMOOTH, CameraPath.Timing.TOTAL, 8, true));
		// Zwei gleiche Orte: trotzdem eine (kurze) Dauer.
		CameraPath turn = CameraPath.of(Arrays.asList(k(1, 1, 1, 0, 0, 70), k(1, 1, 1, 90, 0, 70)), CameraPath.Smoothing.SMOOTH,
				CameraPath.Timing.TOTAL, 3, false);
		assertEquals(3, turn.duration(), EPS);
		assertEquals(45, turn.pose(1.5, new CameraPose()).yaw, 1e-3);
		// Vorschau-Punkte liegen auf der Bahn, vom ersten bis zum letzten Keyframe.
		List<double[]> pts = p.sample(1, 100);
		assertEquals(0, pts.get(0)[0], EPS);
		assertEquals(40, pts.get(pts.size() - 1)[0], EPS);
		assertTrue(pts.size() >= 40);
	}

	@Test
	void storeRoundTripsAndLimits() throws Exception {
		Path file = dir.resolve("trsclient-cinematic.json");
		CinematicStore store = new CinematicStore(file);
		String key = CinematicStore.key("sp:Welt", "minecraft:overworld");
		for (Keyframe kf : three()) assertTrue(store.add(key, kf));
		store.add(CinematicStore.key("mp:play.example.net", "minecraft:the_nether"), k(1, 2, 3, 4, 95, 500));
		store.save();
		CinematicStore again = new CinematicStore(file);
		again.load();
		assertEquals(3, again.get(key).size());
		assertEquals(10, again.get(key).get(1).x, EPS);
		Keyframe clamped = again.get(CinematicStore.key("mp:play.example.net", "minecraft:the_nether")).get(0);
		assertEquals(90, clamped.pitch, 1e-4, "Nickwinkel begrenzt");
		assertEquals(170, clamped.fov, 1e-4, "Sichtfeld begrenzt");
		assertTrue(again.get("sp:Andere|x").isEmpty());
		assertTrue(again.removeLast(key));
		assertEquals(2, again.get(key).size());
		assertTrue(again.clear(key));
		assertFalse(again.clear(key));
		for (int i = 0; i < CinematicStore.MAX_KEYFRAMES; i++) again.add(key, k(i, 0, 0, 0, 0, 70));
		assertFalse(again.add(key, k(0, 0, 0, 0, 0, 70)), "Grenze");
		// Kaputte Datei → leer weiter.
		java.nio.file.Files.write(file, "{kaputt".getBytes("UTF-8"));
		CinematicStore broken = new CinematicStore(file);
		broken.load();
		assertTrue(broken.get(key).isEmpty());
	}

	/** Attrappe der Minecraft-Seite. */
	static final class Host implements Cinematic.Host {
		String world = "sp:Testwelt";
		String dim = "minecraft:overworld";
		CameraPose camera = new CameraPose().set(0, 70, 0, 0, 0, 70);
		boolean hud;
		boolean available = true;
		boolean recording;
		int toggles;
		final List<String> messages = new ArrayList<>();
		int particles;
		int keyParticles;

		@Override
		public String worldKey() {
			return world;
		}

		@Override
		public String dimension() {
			return dim;
		}

		@Override
		public boolean camera(CameraPose out) {
			if (world == null) return false;
			out.set(camera.x, camera.y, camera.z, camera.yaw, camera.pitch, camera.fov);
			return true;
		}

		@Override
		public void actionBar(String text) {
			messages.add(text);
		}

		@Override
		public boolean hudHidden() {
			return hud;
		}

		@Override
		public void setHudHidden(boolean hidden) {
			hud = hidden;
		}

		@Override
		public void particle(boolean keyframe, double x, double y, double z) {
			if (keyframe) keyParticles++;
			else particles++;
		}

		@Override
		public boolean recordingAvailable() {
			return available;
		}

		@Override
		public boolean recording() {
			return recording;
		}

		@Override
		public void toggleRecording() {
			toggles++;
			recording = !recording;
		}
	}

	@Test
	void controllerRecordsPlaysAndRestores() {
		TrsModules modules = new TrsModules();
		CinematicModules s = modules.cinematic;
		s.record.set(true);
		Cinematic c = new Cinematic(s, new CinematicStore(dir.resolve("c.json")));
		long[] now = {1_000_000_000L};
		c.clock(() -> now[0]);
		Host host = new Host();
		c.togglePlay(host);
		assertFalse(c.playing(), "ohne Keyframes kein Abspielen");
		c.addKeyframe(host);
		host.camera.set(10, 72, 0, 90, 10, 60);
		c.addKeyframe(host);
		host.camera.set(10, 72, 10, 180, 0, 80);
		c.addKeyframe(host);
		assertEquals(3, c.keyframes());
		assertEquals(4, host.messages.size());

		// Vorschau: Partikel an Keyframes und entlang der Bahn (nicht direkt vor der Kamera).
		host.camera.set(10, 72, 10, 0, 0, 70);
		for (int i = 0; i < Cinematic.PREVIEW_TICKS; i++) c.tick(host, false);
		assertEquals(2, host.keyParticles, "Keyframe an der Kamera ausgelassen");
		host.keyParticles = 0;
		host.particles = 0;
		host.camera.set(5, 90, 5, 0, 0, 70);
		for (int i = 0; i < Cinematic.PREVIEW_TICKS; i++) c.tick(host, false);
		assertEquals(3, host.keyParticles);
		assertTrue(host.particles > 10);

		c.togglePlay(host);
		assertTrue(c.playing());
		assertTrue(host.hud, "HUD aus");
		assertEquals(1, host.toggles, "Aufnahme gestartet");
		assertEquals(10, c.duration(), EPS, "2 × 5 s");
		CameraPose start = c.frame();
		assertEquals(0, start.x, EPS);
		now[0] += 5_000_000_000L;
		CameraPose mid = c.frame();
		assertEquals(10, mid.x, 1e-6);
		assertEquals(72, mid.y, 1e-6);
		assertEquals(60, mid.fov, 1e-3);
		c.tick(host, false);
		assertTrue(c.playing());
		now[0] += 6_000_000_000L;
		c.tick(host, false);
		assertFalse(c.playing(), "Ende erreicht");
		assertNull(c.frame());
		assertFalse(host.hud, "HUD wieder an");
		assertEquals(2, host.toggles, "Aufnahme beendet");

		// Menü öffnen beendet; ein schon verstecktes HUD bleibt versteckt.
		host.hud = true;
		c.togglePlay(host);
		c.tick(host, true);
		assertFalse(c.playing());
		assertTrue(host.hud);

		// Welt wechseln beendet und lädt die Fahrt der neuen Welt.
		host.hud = false;
		c.togglePlay(host);
		host.world = "mp:server";
		c.tick(host, false);
		assertFalse(c.playing());
		assertEquals(0, c.keyframes());
		host.world = "sp:Testwelt";
		c.tick(host, false);
		assertEquals(3, c.keyframes());
		c.removeLast(host);
		assertEquals(2, c.keyframes());
		c.clear(host);
		assertEquals(0, c.keyframes());

		// Modul aus: Tasten tun nichts.
		s.cinematic.setEnabled(false);
		c.addKeyframe(host);
		assertEquals(0, c.keyframes());
		assertNotNull(Cinematic.Host.class);
	}
}
