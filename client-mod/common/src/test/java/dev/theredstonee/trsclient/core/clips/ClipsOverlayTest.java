package dev.theredstonee.trsclient.core.clips;

import static org.junit.jupiter.api.Assertions.*;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.link.FakeLauncher;
import dev.theredstonee.trsclient.core.link.TrsLink;
import dev.theredstonee.trsclient.core.module.TrsModules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Launcher mit Overlay ({@code clips.overlay}): Puffer-Anzeige und Clip-Meldungen zeigt der Launcher außerhalb der
 * Aufnahme – die Mod zeichnet sie nicht (sonst stünden sie im Clip) und meldet nur ihren Wunsch zur Puffer-Anzeige
 * ({@code clips.hud}). Ältere Launcher: alles wie bisher im Spiel.
 */
class ClipsOverlayTest {
	private static final String BUFFER_STATE = "{\"type\":\"state\",\"available\":true,\"buffer\":true,"
			+ "\"recording\":false,\"recordingMs\":0,\"clipSeconds\":30}";
	private static final String SAVED = "{\"type\":\"saved\",\"kind\":\"clip\",\"seconds\":30}";
	private static final String BUSY = "{\"type\":\"failed\",\"kind\":\"clip\",\"code\":\"busy\"}";
	private static final String DISABLED = "{\"type\":\"failed\",\"kind\":\"clip\",\"code\":\"disabled\"}";

	private final Clips before = Clips.get();

	@AfterEach
	void restore() {
		Clips.set(before);
		Clips.useBufferIcon(null);
		I18n.use("en");
	}

	private static ClipLink connect(FakeLauncher launcher, Path dir) throws Exception {
		Path cfg = dir.resolve("trsclient");
		Files.createDirectories(cfg);
		Files.write(cfg.resolve("clips.json"),
				("{\"version\":2,\"enabled\":true,\"port\":" + launcher.port() + "}").getBytes(StandardCharsets.UTF_8));
		ClipLink link = new ClipLink(new TrsLink(dir, launcher.env()));
		link.start();
		long until = System.currentTimeMillis() + 20000;
		while (!link.status().connected && System.currentTimeMillis() < until) Thread.sleep(20);
		assertTrue(link.status().connected, "verbunden");
		return link;
	}

	/** Tickt, bis eine Meldung im Spiel steht (oder die Zeit um ist). */
	private static ClipNotice tickUntilNotice(Clips clips, List<String> bar) throws InterruptedException {
		long until = System.currentTimeMillis() + 10000;
		while (System.currentTimeMillis() < until) {
			clips.tick(true, bar::add);
			ClipNotice n = clips.currentNotice();
			if (n != null) return n;
			Thread.sleep(20);
		}
		return null;
	}

	@Test
	void mitLauncherOverlayZeichnetDieModWederPufferNochMeldungen(@TempDir Path dir) throws Exception {
		try (FakeLauncher launcher = new FakeLauncher()) {
			launcher.features = "[\"clips\",\"accounts\",\"clips.overlay\"]";
			launcher.initialState = BUFFER_STATE;
			ClipLink link = connect(launcher, dir);
			try {
				Clips clips = new Clips(link);
				Clips.set(clips);
				TrsModules modules = new TrsModules();
				ClipPanel panel = new ClipPanel(modules);

				boolean named = false;
				for (String line : launcher.received) {
					if (line.contains("\"type\":\"auth\"") && line.contains("\"clips.overlay\"")) named = true;
				}
				assertTrue(named, "die Mod nennt das Merkmal im auth");
				assertTrue(clips.launcherOverlay());
				assertTrue(link.status().buffer);

				long now = System.currentTimeMillis();
				assertNull(panel.statusLine(false, now), "kein Puffer im Spiel (sonst im Clip)");
				assertFalse(panel.visible(), "nichts zu zeichnen → kein leerer Kasten");
				assertNotNull(panel.statusLine(true, now), "HUD-Editor-Vorschau bleibt");

				// Wunsch zur Puffer-Anzeige: nach dem Verbinden, dann bei jeder Änderung.
				List<String> bar = new CopyOnWriteArrayList<String>();
				clips.tick(true, bar::add);
				assertEquals("true", launcher.huds.poll(10, TimeUnit.SECONDS));
				modules.clipsBufferIcon.set(false);
				String next = null;
				long until = System.currentTimeMillis() + 10000;
				while (next == null && System.currentTimeMillis() < until) {
					clips.tick(true, bar::add);
					next = launcher.huds.poll(50, TimeUnit.MILLISECONDS);
				}
				assertEquals("false", next, "Einstellung aus → Launcher zeigt keinen Puffer");
				for (int i = 0; i < 10; i++) {
					clips.tick(true, bar::add);
					Thread.sleep(20);
				}
				assertNull(launcher.huds.poll(200, TimeUnit.MILLISECONDS), "unverändert → nichts erneut senden");

				// Gespeichert + Fehler des Launchers: zeigt der Launcher, nicht die Mod.
				launcher.push(SAVED);
				launcher.push(BUSY);
				// „Clips sind aus“ bleibt im Spiel – kommt nach den beiden, zeigt also, dass sie verarbeitet sind.
				launcher.push(DISABLED);
				ClipNotice shown = tickUntilNotice(clips, bar);
				assertNotNull(shown);
				assertEquals(ClipNotice.Type.FAILED, shown.type);
				assertEquals("disabled", shown.code);
				assertEquals(1, bar.size(), "Aktionsleiste nur mit „Clips sind aus“: " + bar);
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void aeltererLauncherOhneOverlayWieBisher(@TempDir Path dir) throws Exception {
		try (FakeLauncher launcher = new FakeLauncher()) {
			launcher.features = "[\"clips\",\"accounts\",\"clips.enable\"]";
			launcher.initialState = BUFFER_STATE;
			ClipLink link = connect(launcher, dir);
			try {
				Clips clips = new Clips(link);
				Clips.set(clips);
				ClipPanel panel = new ClipPanel(new TrsModules());
				assertFalse(clips.launcherOverlay());

				String line = panel.statusLine(false, System.currentTimeMillis());
				assertNotNull(line, "Puffer zeichnet die Mod selbst");
				assertTrue(line.contains("30"), line);
				assertTrue(panel.visible());

				List<String> bar = new CopyOnWriteArrayList<String>();
				launcher.push(SAVED);
				ClipNotice shown = tickUntilNotice(clips, bar);
				assertNotNull(shown);
				assertEquals(ClipNotice.Type.CLIP_SAVED, shown.type);
				assertNull(launcher.huds.poll(300, TimeUnit.MILLISECONDS), "kein clips.hud an ältere Launcher");
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void welcheMeldungenDerLauncherZeigt() {
		assertTrue(new ClipNotice(ClipNotice.Type.CLIP_SAVED, 30, null).shownByLauncher());
		assertTrue(new ClipNotice(ClipNotice.Type.RECORDING_SAVED, 30, null).shownByLauncher());
		assertTrue(new ClipNotice(ClipNotice.Type.RECORDING_STARTED, 0, null).shownByLauncher());
		assertTrue(ClipNotice.failedByLauncher("busy", -1).shownByLauncher());
		assertFalse(ClipNotice.failedByLauncher("disabled", -1).shownByLauncher(), "Clips aus: Hinweis im Spiel");
		// Selbst erkannte Fehler (z. B. nach dem Einschalten) und Hinweise bleiben im Spiel.
		assertFalse(ClipNotice.failed("encoder", -1).shownByLauncher());
		assertFalse(ClipNotice.hint("unreachable").shownByLauncher());
		assertFalse(ClipNotice.offer(false, true, false).shownByLauncher());
		assertFalse(ClipNotice.ready(30).shownByLauncher());
		assertFalse(ClipNotice.preparing(10).shownByLauncher());
	}
}
