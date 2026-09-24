package dev.theredstonee.trsclient.core.clips;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipsTest {
	private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	private static void writeConfig(Path configDir, String json) throws Exception {
		Path dir = configDir.resolve("trsclient");
		Files.createDirectories(dir);
		Files.write(dir.resolve("clips.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void konfigurationWirdStrengGeprueft(@TempDir Path dir) throws Exception {
		assertEquals(ClipConfig.Kind.MISSING, ClipConfig.load(dir).kind, "ohne Datei: kein Launcher");
		writeConfig(dir, "{\"version\":1,\"enabled\":false}");
		assertEquals(ClipConfig.Kind.DISABLED, ClipConfig.load(dir).kind);
		writeConfig(dir, "{\"version\":1,\"enabled\":true,\"port\":51234,\"token\":\"" + TOKEN + "\"}");
		ClipConfig ok = ClipConfig.load(dir);
		assertEquals(ClipConfig.Kind.ENABLED, ok.kind);
		assertEquals(51234, ok.port);
		assertEquals(TOKEN, ok.token);
		// Kaputtes Token, Port außerhalb, Müll → wie ohne Launcher.
		writeConfig(dir, "{\"version\":1,\"enabled\":true,\"port\":51234,\"token\":\"\\\"}\\n{x\"}");
		assertEquals(ClipConfig.Kind.MISSING, ClipConfig.load(dir).kind);
		writeConfig(dir, "{\"version\":1,\"enabled\":true,\"port\":70000,\"token\":\"" + TOKEN + "\"}");
		assertEquals(ClipConfig.Kind.MISSING, ClipConfig.load(dir).kind);
		writeConfig(dir, "kein json");
		assertEquals(ClipConfig.Kind.MISSING, ClipConfig.load(dir).kind);
		assertFalse(ClipConfig.validToken(TOKEN.toUpperCase()));
		assertFalse(ClipConfig.validToken(TOKEN.substring(1)));
	}

	@Test
	void zeitformate() {
		assertEquals("0:05", ClipNotice.clock(5_400));
		assertEquals("1:02", ClipNotice.clock(62_000));
		assertEquals("1:02:03", ClipNotice.clock(3_723_000));
		assertEquals("error", ClipLink.safeCode("<script>"));
		assertEquals("noWindow", ClipLink.safeCode("noWindow"));
	}

	@Test
	void ohneLauncherGibtEsNurEinenHinweis(@TempDir Path dir) {
		ClipLink link = new ClipLink(dir);
		link.press(ClipLink.CLIP);
		ClipNotice n = link.pollNotice();
		assertNotNull(n);
		assertEquals(ClipNotice.Type.HINT, n.type);
		assertEquals("noLauncher", n.code);
		assertFalse(link.status().connected);
	}

	@Test
	void ausgeschaltetImLauncher(@TempDir Path dir) throws Exception {
		writeConfig(dir, "{\"version\":1,\"enabled\":false}");
		ClipLink link = new ClipLink(dir);
		link.press(ClipLink.RECORD);
		ClipNotice n = link.pollNotice();
		assertNotNull(n);
		assertEquals("disabled", n.code);
	}

	/** Spielt den Launcher: prüft das Token, schickt Status und bestätigt den Clip. */
	@Test
	void verbindetSichMeldetTastenUndZeigtStatus(@TempDir Path dir) throws Exception {
		final ServerSocket server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
		final BlockingQueue<String> received = new LinkedBlockingQueue<String>();
		Thread fake = new Thread(new Runnable() {
			@Override
			public void run() {
				try (Socket s = server.accept()) {
					BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
					OutputStream out = s.getOutputStream();
					String hello = in.readLine();
					received.add(hello);
					out.write(("{\"type\":\"state\",\"available\":true,\"reason\":null,\"buffer\":true,\"recording\":false,"
							+ "\"recordingMs\":0,\"clipSeconds\":30}\n").getBytes(StandardCharsets.UTF_8));
					out.flush();
					String command = in.readLine();
					received.add(command);
					out.write("{\"type\":\"saved\",\"kind\":\"clip\",\"seconds\":30}\n".getBytes(StandardCharsets.UTF_8));
					out.write(("{\"type\":\"state\",\"available\":true,\"buffer\":true,\"recording\":true,"
							+ "\"recordingMs\":61000,\"clipSeconds\":30}\n").getBytes(StandardCharsets.UTF_8));
					out.flush();
					in.readLine(); // bis die Mod die Verbindung schließt
				} catch (Exception ignored) {
					// Test endet
				}
			}
		});
		fake.setDaemon(true);
		fake.start();
		writeConfig(dir, "{\"version\":1,\"enabled\":true,\"port\":" + server.getLocalPort() + ",\"token\":\"" + TOKEN + "\"}");

		ClipLink link = new ClipLink(dir);
		link.start();
		try {
			String hello = received.poll(5, TimeUnit.SECONDS);
			assertEquals("{\"type\":\"hello\",\"v\":1,\"token\":\"" + TOKEN + "\"}", hello);
			waitFor(link, true);
			assertTrue(link.status().buffer);
			assertEquals(30, link.status().clipSeconds);

			link.press(ClipLink.CLIP);
			assertEquals("{\"type\":\"clip\"}", received.poll(5, TimeUnit.SECONDS));
			List<ClipNotice> notices = new ArrayList<ClipNotice>();
			long until = System.currentTimeMillis() + 5000;
			while (notices.size() < 2 && System.currentTimeMillis() < until) {
				ClipNotice n = link.pollNotice();
				if (n != null) notices.add(n);
				else Thread.sleep(20);
			}
			assertEquals(ClipNotice.Type.CLIP_SAVED, notices.get(0).type);
			assertEquals(30, notices.get(0).seconds);
			assertEquals(ClipNotice.Type.RECORDING_STARTED, notices.get(1).type);
			long ms = link.status().recordingMillis(System.currentTimeMillis());
			assertTrue(ms >= 61000 && ms < 70000, "Aufnahmezeit übernommen: " + ms);
		} finally {
			link.stop();
			server.close();
		}
	}

	private static void waitFor(ClipLink link, boolean connected) throws InterruptedException {
		long until = System.currentTimeMillis() + 5000;
		while (link.status().connected != connected && System.currentTimeMillis() < until) Thread.sleep(20);
		assertEquals(connected, link.status().connected);
	}
}
