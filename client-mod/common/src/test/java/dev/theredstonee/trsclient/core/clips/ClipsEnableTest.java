package dev.theredstonee.trsclient.core.clips;

import static org.junit.jupiter.api.Assertions.*;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.link.FakeLauncher;
import dev.theredstonee.trsclient.core.link.TrsLink;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sichtbare Rückmeldung auf F9/F10 in jeder Lage – vor allem „Clips sind aus“: bei neuen Launchern mit Angebot
 * (Taste noch einmal = einschalten), bei älteren nur mit dem Weg über die Launcher-Einstellungen.
 */
class ClipsEnableTest {
	@AfterEach
	void english() {
		I18n.use("en");
	}

	private static void writeConfig(Path configDir, String json) throws Exception {
		Path dir = configDir.resolve("trsclient");
		Files.createDirectories(dir);
		Files.write(dir.resolve("clips.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	private static ClipLink connect(FakeLauncher launcher, Path dir) throws Exception {
		writeConfig(dir, "{\"version\":2,\"enabled\":false,\"port\":" + launcher.port() + "}");
		ClipLink link = new ClipLink(new TrsLink(dir, launcher.env()));
		link.start();
		long until = System.currentTimeMillis() + 20000;
		while (!link.status().connected && System.currentTimeMillis() < until) Thread.sleep(20);
		assertTrue(link.status().connected, "verbunden");
		return link;
	}

	private static List<ClipNotice> notices(ClipLink link, int count) throws InterruptedException {
		List<ClipNotice> out = new ArrayList<ClipNotice>();
		long until = System.currentTimeMillis() + 20000;
		while (out.size() < count && System.currentTimeMillis() < until) {
			ClipNotice n = link.pollNotice();
			if (n != null) out.add(n);
			else Thread.sleep(20);
		}
		return out;
	}

	private static boolean sentCommand(FakeLauncher launcher) {
		for (String line : launcher.received) {
			if (line.contains("\"type\":\"clip\"") || line.contains("\"type\":\"record\"")) return true;
		}
		return false;
	}

	@Test
	void ausAnbietenUndBeimZweitenDruckEinschalten(@TempDir Path dir) throws Exception {
		try (FakeLauncher launcher = new FakeLauncher()) {
			launcher.features = "[\"clips\",\"accounts\",\"clips.enable\"]";
			ClipLink link = connect(launcher, dir);
			try {
				ClipStatus st = link.status();
				assertTrue(st.disabled());
				assertTrue(st.offersEnable(), "Launcher kann Clips einschalten");
				assertTrue(st.audio);
				assertFalse(st.mic);

				link.press(ClipLink.CLIP);
				ClipNotice offer = notices(link, 1).get(0);
				assertEquals(ClipNotice.Type.OFFER, offer.type);
				assertTrue(offer.sticky());
				assertTrue(link.offerActive(System.currentTimeMillis()));
				String text = offer.text();
				assertTrue(text.contains("F9"), text);
				assertTrue(text.contains("game window + system sound"), "sagt, was aufgenommen wird: " + text);
				assertFalse(text.contains("+ mic"), text);
				assertEquals(0, launcher.enables.get(), "erster Druck schaltet noch nichts ein");
				assertFalse(sentCommand(launcher), "bei „aus“ geht keine Taste an den Launcher");

				link.press(ClipLink.CLIP);
				List<ClipNotice> after = notices(link, 3);
				assertEquals(1, launcher.enables.get());
				assertEquals(ClipNotice.Type.ENABLED, after.get(0).type);
				assertEquals(ClipNotice.Type.PREPARING, after.get(1).type);
				assertEquals(40, after.get(1).progress);
				assertTrue(after.get(1).text().contains("40 %"), after.get(1).text());
				assertEquals(ClipNotice.Type.READY, after.get(2).type);
				assertEquals("Clips on – F9 saves the last 30 s", after.get(2).text());
				assertTrue(link.status().buffer);
				assertFalse(link.offerActive(System.currentTimeMillis()));
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void angebotNenntDasMikrofonWennEsMitaufnimmt(@TempDir Path dir) throws Exception {
		try (FakeLauncher launcher = new FakeLauncher()) {
			launcher.features = "[\"clips\",\"accounts\",\"clips.enable\"]";
			launcher.initialState = "{\"type\":\"state\",\"available\":false,\"reason\":\"disabled\",\"buffer\":false,"
					+ "\"recording\":false,\"recordingMs\":0,\"clipSeconds\":30,\"audio\":true,\"mic\":true}";
			ClipLink link = connect(launcher, dir);
			try {
				link.press(ClipLink.RECORD);
				ClipNotice offer = notices(link, 1).get(0);
				assertTrue(offer.recordKey);
				assertTrue(offer.text().contains("F10"), offer.text());
				assertTrue(offer.text().contains("game window + system sound + mic)"), offer.text());
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void launcherOhneEinschaltenZeigtDenWegUeberDieEinstellungen(@TempDir Path dir) throws Exception {
		try (FakeLauncher launcher = new FakeLauncher()) {
			ClipLink link = connect(launcher, dir);
			try {
				assertTrue(link.status().disabled());
				assertFalse(link.status().offersEnable());
				link.press(ClipLink.CLIP);
				link.press(ClipLink.CLIP);
				List<ClipNotice> n = notices(link, 2);
				assertEquals(ClipNotice.Type.HINT, n.get(0).type);
				assertEquals("disabled", n.get(0).code);
				assertEquals("disabled", n.get(1).code);
				assertEquals(I18n.tr("clips.hint.disabled"), n.get(0).text());
				assertEquals(0, launcher.enables.get());
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void einschaltenAbgelehntGibtEinenVerstaendlichenHinweis(@TempDir Path dir) throws Exception {
		try (FakeLauncher launcher = new FakeLauncher()) {
			launcher.features = "[\"clips\",\"accounts\",\"clips.enable\"]";
			launcher.enableError = "unsupported";
			ClipLink link = connect(launcher, dir);
			try {
				link.press(ClipLink.CLIP);
				link.press(ClipLink.CLIP);
				List<ClipNotice> n = notices(link, 2);
				assertEquals(ClipNotice.Type.OFFER, n.get(0).type);
				assertEquals(ClipNotice.Type.HINT, n.get(1).type);
				assertEquals("unsupported", n.get(1).code);
				// Unbekannter Fehler → Weg über die Einstellungen
				launcher.enableError = "not_allowed";
				link.enable();
				ClipNotice failed = notices(link, 1).get(0);
				assertEquals("enableFailed", failed.code);
				assertTrue(failed.text().contains("Settings → Clips"), failed.text());
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void tasteVorDemStatusWirdNachtraeglichZumAngebot(@TempDir Path dir) throws Exception {
		try (FakeLauncher launcher = new FakeLauncher()) {
			launcher.features = "[\"clips\",\"accounts\",\"clips.enable\"]";
			ClipLink link = connect(launcher, dir);
			try {
				// Der Launcher meldet „aus“ auf einen Tastendruck, der vor der ersten Statuszeile unterwegs war.
				launcher.push("{\"type\":\"failed\",\"kind\":\"clip\",\"code\":\"disabled\"}");
				ClipNotice n = notices(link, 1).get(0);
				assertEquals(ClipNotice.Type.OFFER, n.type);
				assertTrue(link.offerActive(System.currentTimeMillis()));
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void launcherFehlerNennenDenGrund(@TempDir Path dir) throws Exception {
		try (FakeLauncher launcher = new FakeLauncher()) {
			launcher.initialState = "{\"type\":\"state\",\"available\":false,\"reason\":\"ffmpeg\",\"progress\":55,\"buffer\":false,"
					+ "\"recording\":false,\"recordingMs\":0,\"clipSeconds\":30}";
			ClipLink link = connect(launcher, dir);
			try {
				assertEquals(55, link.status().progress);
				link.press(ClipLink.CLIP);
				assertEquals("{\"type\":\"clip\"}", lastCommand(launcher));
				launcher.push("{\"type\":\"failed\",\"kind\":\"clip\",\"code\":\"ffmpeg\"}");
				ClipNotice n = notices(link, 1).get(0);
				assertEquals(ClipNotice.Type.FAILED, n.type);
				assertTrue(n.text().contains("55 %"), n.text());
				assertEquals("FFmpeg could not be downloaded – check your internet, retrying soon",
						ClipNotice.failure("ffmpegFailed", -1));
				assertEquals(I18n.tr("clips.failed.encoder"), ClipNotice.failure("encoder", -1));
				assertEquals(I18n.tr("clips.failed.error"), ClipNotice.failure("<b>", -1));
			} finally {
				link.stop();
			}
		}
	}

	private static String lastCommand(FakeLauncher launcher) throws InterruptedException {
		long until = System.currentTimeMillis() + 20000;
		while (System.currentTimeMillis() < until) {
			for (String line : launcher.received) {
				if (line.startsWith("{\"type\":\"clip\"")) return line;
			}
			Thread.sleep(20);
		}
		return null;
	}

	@Test
	void ohneVerbindungErklaertDieModWarum(@TempDir Path dir) throws Exception {
		// Kein TRS Launcher (anderer Launcher): Clips brauchen den TRS Launcher.
		ClipLink none = new ClipLink(dir);
		none.press(ClipLink.CLIP);
		ClipNotice n = none.pollNotice();
		assertEquals("noLauncher", n.code);
		assertEquals(I18n.tr("clips.hint.noLauncher"), n.text());
		// Alter Launcher (0.5.x, Protokoll 1) mit Clips aus: nur der Weg über die Einstellungen.
		writeConfig(dir, "{\"version\":1,\"enabled\":false}");
		none.press(ClipLink.RECORD);
		assertEquals("disabled", none.pollNotice().code);
		// Datei eines neuen Launchers, aber das Spiel lief nicht über ihn (keine Umgebungsvariable).
		writeConfig(dir, "{\"version\":2,\"enabled\":true,\"port\":51234}");
		none.press(ClipLink.CLIP);
		assertEquals("noLauncher", none.pollNotice().code);
	}

	@Test
	void statusZeilenUndCodes() {
		assertEquals("encoder", ClipLink.safeCode("encoder"));
		assertEquals("ffmpegFailed", ClipLink.safeCode("ffmpegFailed"));
		assertEquals("unsupported", ClipLink.safeCode("unsupported"));
		assertEquals("error", ClipLink.safeCode("rm -rf"));
		assertNull(ClipLink.safeReason(null));
		assertEquals("game window", ClipNotice.what(false, false));
		assertNull(ClipNotice.reasonText(ClipStatus.OFFLINE));
		ClipStatus starting = new ClipStatus(true, false, "starting", false, false, 0, 30);
		assertEquals(I18n.tr("clips.link.starting"), ClipNotice.reasonText(starting));
		ClipStatus download = new ClipStatus(true, false, "ffmpeg", false, false, 0, 30, 12, true, false, false);
		assertTrue(ClipNotice.reasonText(download).contains("12 %"));
		I18n.use("de");
		assertEquals("Clips aus – F9 erneut: einschalten (nimmt Spielfenster + Systemton auf)",
				ClipNotice.offer(false, true, false).text());
		assertEquals("Clips an – F9 speichert die letzten 30 s", ClipNotice.ready(30).text());
	}
}
