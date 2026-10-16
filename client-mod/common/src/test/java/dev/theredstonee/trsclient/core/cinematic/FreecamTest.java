package dev.theredstonee.trsclient.core.cinematic;

import dev.theredstonee.trsclient.core.map.FairPlay;
import dev.theredstonee.trsclient.core.module.CinematicModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Freie Kamera: Kollision (Fair Play), Bewegung, Server-Regeln und Steuerung. */
class FreecamTest {
	@TempDir
	Path dir;

	/** Wand: alle Blöcke mit x == 5. */
	static final CameraCollision.Blocks WALL_X5 = (x, y, z) -> x == 5;

	@Test
	void sweepStopsBeforeWallWithMargin() {
		double[] p = {2.5, 64.5, 0.5};
		int hit = CameraCollision.move(p, 10, 0, 0, WALL_X5);
		assertEquals(CameraCollision.HIT_X, hit);
		assertEquals(5 - CameraCollision.MARGIN, p[0], 1e-3);
		assertTrue(p[0] < 5 - CameraCollision.MARGIN, "nicht in der Wand");
		assertFalse(CameraCollision.blocked(WALL_X5, p[0], p[1], p[2], CameraCollision.MARGIN));
		// Erneut gegen die Wand: bleibt stehen.
		CameraCollision.move(p, 1, 0, 0, WALL_X5);
		assertEquals(5 - CameraCollision.MARGIN, p[0], 1e-3);
		// Rückwärts frei.
		CameraCollision.move(p, -3, 0, 0, WALL_X5);
		assertEquals(5 - CameraCollision.MARGIN - 3, p[0], 1e-3);
	}

	@Test
	void highSpeedCannotTunnelThroughThinWall() {
		double[] p = {0.5, 64.5, 0.5};
		CameraCollision.move(p, 1000, 0, 0, WALL_X5);
		assertTrue(p[0] < 5, "ein Bild mit 1000 Blöcken bleibt vor der Wand: " + p[0]);
		// Von der anderen Seite ebenso.
		double[] q = {9.5, 64.5, 0.5};
		CameraCollision.move(q, -1000, 0, 0, WALL_X5);
		assertEquals(6 + CameraCollision.MARGIN, q[0], 1e-3);
	}

	@Test
	void slidesAlongWallsAndPassesWithoutFairPlay() {
		double[] p = {4.5, 64.5, 0.5};
		int hit = CameraCollision.move(p, 2, 0, 3, WALL_X5);
		assertEquals(CameraCollision.HIT_X, hit);
		assertEquals(3.5, p[2], 1e-9, "gleitet an der Wand entlang");
		double[] free = {4.5, 64.5, 0.5};
		assertEquals(0, CameraCollision.move(free, 2, 0, 0, null));
		assertEquals(6.5, free[0], 1e-9, "ohne Fair Play durch alles");
	}

	@Test
	void settleFindsFreeSpotUnderLowCeilingAndStuckPointMovesFreely() {
		// Decke bei y = 66 (Blockschicht 66), Augen 0,18 darunter.
		CameraCollision.Blocks ceiling = (x, y, z) -> y == 66;
		double[] p = {0.5, 65.82, 0.5};
		assertTrue(CameraCollision.blocked(ceiling, p[0], p[1], p[2], CameraCollision.MARGIN));
		assertTrue(CameraCollision.settle(p, ceiling));
		assertFalse(CameraCollision.blocked(ceiling, p[0], p[1], p[2], CameraCollision.MARGIN));
		assertTrue(p[1] < 65.82 && p[1] > 65.5, "nur ein kleines Stück nach unten: " + p[1]);
		// Punkt mitten im Block (erstickt): bewegt sich frei heraus.
		CameraCollision.Blocks all = (x, y, z) -> y >= 0;
		double[] q = {0.5, 10.5, 0.5};
		CameraCollision.move(q, 0, 3, 0, all);
		assertEquals(13.5, q[1], 1e-9);
	}

	@Test
	void freecamFliesRelativeToYawWithSmoothStart() {
		Freecam f = new Freecam();
		f.enter(new CameraPose().set(0, 64, 0, 0, 0, 70), 0, null);
		Freecam.Input in = new Freecam.Input();
		in.forward = true;
		long t = 0;
		for (int i = 0; i < 100; i++) {
			t += 10_000_000L; // 100 × 10 ms = 1 s
			f.update(in, 10, null, t);
		}
		assertEquals(0, f.x(), 1e-6);
		assertTrue(f.z() > 9 && f.z() < 10, "yaw 0 = +Z, ~10 Blöcke abzüglich Anfahren: " + f.z());
		// yaw 90 = −X; Sprint schneller; hoch = +Y.
		f.teleport(0, 64, 0, 90, 0);
		in.boost = true;
		in.up = true;
		for (int i = 0; i < 100; i++) {
			t += 10_000_000L;
			f.update(in, 10, null, t);
		}
		assertTrue(f.x() < -20, "Sprint: " + f.x());
		assertTrue(f.y() > 64 + 20);
		assertEquals(0, f.z(), 1e-6);
		// Maus: wie Entity#turn, Blick nach oben/unten begrenzt.
		f.turn(100, 1000);
		assertEquals(105, f.yaw(), 1e-4);
		assertEquals(90, f.pitch(), 1e-4);
		// Nach dem Loslassen bremst sie ab und steht.
		in.clear();
		for (int i = 0; i < 200; i++) {
			t += 10_000_000L;
			f.update(in, 10, null, t);
		}
		double x = f.x();
		t += 10_000_000L;
		f.update(in, 10, null, t);
		assertEquals(x, f.x(), 1e-9);
	}

	@Test
	void serverRulesKnownServersAndCodes() {
		FreecamRules r = new FreecamRules();
		assertTrue(r.update(true, null));
		assertTrue(r.allowed(), "Einzelspieler");
		assertFalse(r.fairForced());
		assertTrue(r.update(true, "mc.hypixel.net:25565"));
		assertFalse(r.allowed(), "bekannter PvP-Server");
		assertTrue(r.knownServer());
		assertTrue(r.fairForced());
		assertFalse(r.update(true, "mc.hypixel.net:25565"), "gleicher Server");
		assertTrue(r.update(true, "play.example.net"));
		assertTrue(r.allowed());
		String s = String.valueOf(FairPlay.SECTION);
		assertFalse(r.onServerText("Willkommen!"));
		assertTrue(r.onServerText("Hallo " + codes(s, "trsfairfreecam")));
		assertTrue(r.allowed());
		assertTrue(r.fairForced());
		assertTrue(r.onServerText(codes(s, "trsnofreecam")));
		assertFalse(r.allowed());
		assertTrue(r.onServerText(codes(s, "trsresetfreecam")));
		assertTrue(r.allowed());
		assertFalse(r.fairForced());
		assertTrue(r.onServerText(codes(s, "fairxaero")), "Fair-Play-Code der Karten zählt auch");
		assertTrue(r.fairForced());
		// Anderer Server bzw. Welt verlassen: Merker weg.
		r.update(true, "other.example.org");
		assertFalse(r.fairForced());
		r.onServerText(codes(s, "trsnofreecam"));
		r.update(false, null);
		assertTrue(r.update(true, "other.example.org"));
		assertTrue(r.allowed());
	}

	static String codes(String section, String word) {
		StringBuilder sb = new StringBuilder();
		for (char ch : word.toCharArray()) sb.append(section).append(ch);
		return sb.toString();
	}

	static final class Host implements Cinematic.Host {
		String world = "sp:Testwelt";
		String address;
		String motd;
		CameraPose camera = new CameraPose().set(0.5, 65, 0.5, 0, 0, 70);
		CameraCollision.Blocks blocks = (x, y, z) -> false;
		final List<String> messages = new ArrayList<>();
		final List<Boolean> detach = new ArrayList<>();
		int holds;
		Freecam.Input keys = new Freecam.Input();
		boolean hud;

		@Override
		public String worldKey() {
			return world;
		}

		@Override
		public String dimension() {
			return "minecraft:overworld";
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
		}

		@Override
		public boolean recordingAvailable() {
			return false;
		}

		@Override
		public boolean recording() {
			return false;
		}

		@Override
		public void detachView(boolean detached) {
			detach.add(detached);
		}

		@Override
		public void holdView() {
			holds++;
		}

		@Override
		public boolean solid(int x, int y, int z) {
			return blocks.solid(x, y, z);
		}

		@Override
		public void freecamInput(Freecam.Input in) {
			in.set(keys);
		}

		@Override
		public String serverAddress() {
			return address;
		}

		@Override
		public String serverMotd() {
			return motd;
		}
	}

	private Cinematic controller(long[] now) {
		TrsModules modules = new TrsModules();
		Cinematic c = new Cinematic(modules.cinematic, new CinematicStore(dir.resolve("c.json")));
		c.clock(() -> now[0]);
		return c;
	}

	private static void run(Cinematic c, Host host, long[] now, int frames) {
		for (int i = 0; i < frames; i++) {
			now[0] += 10_000_000L;
			c.frame(host);
		}
	}

	@Test
	void controllerFairPlayStopsAtStoneAndOffPassesThrough() {
		long[] now = {1_000_000_000L};
		Cinematic c = controller(now);
		CinematicModules s = settingsOf(c);
		Host host = new Host();
		host.blocks = WALL_X5;
		host.camera.set(2.5, 65, 0.5, -90, 0, 70); // Blick nach +X
		c.tick(host, false);
		assertNull(c.frame(host));
		c.toggleFreecam(host);
		assertTrue(c.freecam());
		assertTrue(c.overridden());
		assertEquals(1, host.detach.size());
		assertTrue(host.detach.get(0));
		CameraPose p = c.frame(host);
		assertNotNull(p);
		assertEquals(0f, p.fov, "freie Kamera ändert das Sichtfeld nicht");
		host.keys.forward = true;
		run(c, host, now, 300);
		assertEquals(5 - CameraCollision.MARGIN, c.frame(host).x, 1e-3, "Fair Play: vor der Wand angehalten");
		// Keyframe aus der freien Kamera: deren Lage, Sichtfeld der Ansicht.
		c.addKeyframe(host);
		assertEquals(1, c.keyframes());
		// Fair Play aus: durch die Wand.
		s.freecamFairPlay.set(false);
		run(c, host, now, 100);
		assertTrue(c.frame(host).x > 6, "ohne Fair Play durch Stein");
		// Maus dreht die freie Kamera, nicht die Figur.
		assertTrue(c.turn(10, 0));
		// Mausrad: Geschwindigkeit.
		double before = s.freecamSpeed.get();
		assertTrue(c.scroll(host, 1));
		assertTrue(s.freecamSpeed.get() > before);
		s.freecamSpeed.set(0.5);
		assertTrue(c.scroll(host, 1));
		assertEquals(1.0, s.freecamSpeed.get(), 1e-9, "kleine Werte: mindestens ein Schritt");
		// Taste erneut: zurück zur Figur.
		c.toggleFreecam(host);
		assertFalse(c.freecam());
		assertFalse(c.overridden());
		assertFalse(c.turn(10, 0));
		assertFalse(c.scroll(host, 1));
		assertEquals(2, host.detach.size());
		assertFalse(host.detach.get(1));
		assertNull(c.frame(host));
	}

	@Test
	void serverLockDisablesFreecamAndForcesFairPlay() {
		long[] now = {1_000_000_000L};
		Cinematic c = controller(now);
		CinematicModules s = settingsOf(c);
		Host host = new Host();
		s.freecamFairPlay.set(false);
		host.world = "mp:play.example.net";
		host.address = "play.example.net";
		host.motd = "A server " + codes(String.valueOf(FairPlay.SECTION), "trsfairfreecam");
		c.tick(host, false);
		assertTrue(s.freecamFairPlay.locked(), "Server erzwingt Fair Play");
		assertTrue(s.freecamFairPlay.get());
		s.freecamFairPlay.toggle();
		assertTrue(s.freecamFairPlay.get(), "gesperrt: Umschalten wirkungslos");
		c.toggleFreecam(host);
		assertTrue(c.freecam(), "erlaubt, aber mit Fair Play");
		// Server sperrt die freie Kamera per Chat: beim nächsten Tick aus, mit Hinweis.
		Cinematic previous = Cinematic.get();
		assertTrue(previous == null || previous != c);
		c.rules().onServerText(codes(String.valueOf(FairPlay.SECTION), "trsnofreecam"));
		host.messages.clear();
		c.tick(host, false);
		assertFalse(c.freecam());
		assertEquals(1, host.messages.size());
		c.toggleFreecam(host);
		assertFalse(c.freecam(), "gesperrt");
		// Bekannter Server: gesperrt; Welt verlassen gibt alles frei und Fair Play wieder nach eigener Wahl.
		host.address = "mc.hypixel.net";
		host.world = "mp:mc.hypixel.net";
		c.tick(host, false);
		assertFalse(c.rules().allowed());
		host.world = null;
		c.tick(host, false);
		assertFalse(s.freecamFairPlay.locked());
		assertFalse(s.freecamFairPlay.get(), "eigener Wert blieb gespeichert");
	}

	@Test
	void playbackWithFairPlayIsClampedAtWalls() {
		long[] now = {1_000_000_000L};
		Cinematic c = controller(now);
		CinematicModules s = settingsOf(c);
		s.ease.set(false);
		s.smoothing.set(CinematicModules.Smoothing.LINEAR);
		s.seconds.set(1);
		s.hideHud.set(false);
		s.record.set(false);
		Host host = new Host();
		host.camera.set(2.5, 65, 0.5, 0, 0, 70);
		c.addKeyframe(host);
		host.camera.set(9.5, 65, 0.5, 0, 0, 70);
		c.addKeyframe(host);
		host.blocks = WALL_X5;
		c.togglePlay(host);
		assertTrue(c.playing());
		run(c, host, now, 120);
		CameraPose p = c.frame(host);
		assertEquals(5 - CameraCollision.MARGIN, p.x, 1e-3, "Fahrt hält an der Wand");
		c.stop(host);
		// Ohne Fair Play fährt sie durch.
		s.freecamFairPlay.set(false);
		c.togglePlay(host);
		run(c, host, now, 50);
		assertTrue(c.frame(host).x > 5);
		c.stop(host);
		// Fahrt aus der freien Kamera: danach wieder die freie Kamera (Ansicht bleibt gelöst).
		c.toggleFreecam(host);
		int detaches = host.detach.size();
		c.togglePlay(host);
		assertTrue(c.overridden());
		c.stop(host);
		assertTrue(c.freecam());
		assertEquals(detaches, host.detach.size(), "kein Hin und Her der Ansicht");
		c.tick(host, false);
		assertTrue(host.holds > 0, "Ansicht wird je Tick gehalten");
	}

	private static CinematicModules settingsOf(Cinematic c) {
		try {
			java.lang.reflect.Field f = Cinematic.class.getDeclaredField("settings");
			f.setAccessible(true);
			return (CinematicModules) f.get(c);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
}
