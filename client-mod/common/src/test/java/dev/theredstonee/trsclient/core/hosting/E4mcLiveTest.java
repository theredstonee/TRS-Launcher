package dev.theredstonee.trsclient.core.hosting;

import dev.theredstonee.trsclient.core.hosting.e4mc.E4mcRuntime;
import dev.theredstonee.trsclient.core.hosting.e4mc.PublicTunnel;
import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nur auf Wunsch ({@code -Dtrsclient.test.e4mc=true}, braucht Internet): echte Laufzeit laden (Maven Central, SHA-256),
 * beim e4mc-Relay eine Adresse holen, per TCP über die öffentliche Adresse verbinden – die Bytes kommen als
 * {@link PeerStream} an und die Antwort geht zurück.
 */
class E4mcLiveTest {
	@Test
	void publicLinkEndToEnd() throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean("trsclient.test.e4mc"), "nur mit -Dtrsclient.test.e4mc=true");
		Path dir = Files.createTempDirectory("trs-e4mc");
		PublicTunnel t = E4mcRuntime.newTunnel(dir, null);
		final BlockingQueue<String> domains = new ArrayBlockingQueue<String>(1);
		final BlockingQueue<PeerStream> streams = new ArrayBlockingQueue<PeerStream>(4);
		final AtomicReference<String> failed = new AtomicReference<String>();
		t.start(PublicLink.BROKER, new PublicTunnel.Events() {
			@Override
			public void domain(String domain) {
				domains.add(domain);
			}

			@Override
			public void stream(PeerStream stream) {
				streams.add(stream);
			}

			@Override
			public void broadcast(String message) {
			}

			@Override
			public void failed(String reason) {
				failed.set(reason);
			}
		});
		String domain = domains.poll(30, TimeUnit.SECONDS);
		assertNotNull(domain, "keine Adresse: " + failed.get());
		System.out.println("e4mc-Adresse: " + domain);
		try (Socket s = new Socket(domain, 25565)) {
			// Handshake (Status) wie ein Minecraft-Client: das Relay leitet anhand der Adresse weiter.
			byte[] host = domain.getBytes(StandardCharsets.UTF_8);
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			body.write(0);
			body.write(new byte[] { (byte) 0xF0, 0x05 }); // Protokoll 752 als VarInt
			body.write(host.length);
			body.write(host);
			body.write(new byte[] { 0x63, (byte) 0xDD });
			body.write(1);
			OutputStream out = s.getOutputStream();
			out.write(body.size());
			out.write(body.toByteArray());
			out.write(new byte[] { 1, 0 }); // Status-Anfrage
			out.flush();
			PeerStream in = streams.poll(20, TimeUnit.SECONDS);
			assertNotNull(in, "kein Strom angekommen");
			final ByteArrayOutputStream got = new ByteArrayOutputStream();
			in.start(new PeerStream.Sink() {
				@Override
				public synchronized void data(byte[] b, int off, int len) {
					got.write(b, off, len);
				}

				@Override
				public void closed(String reason) {
				}
			});
			long end = System.currentTimeMillis() + 10_000;
			while (System.currentTimeMillis() < end) {
				synchronized (in) {
					if (got.size() >= body.size() + 3) break;
				}
				Thread.sleep(50);
			}
			assertTrue(got.size() >= body.size() + 1, "Handshake kam an: " + got.size());
			byte[] answer = "trs".getBytes(StandardCharsets.UTF_8);
			in.write(answer, 0, answer.length);
			InputStream r = s.getInputStream();
			s.setSoTimeout(10_000);
			byte[] back = new byte[3];
			int n = 0;
			while (n < 3) {
				int k = r.read(back, n, 3 - n);
				if (k < 0) break;
				n += k;
			}
			assertEquals("trs", new String(back, 0, n, StandardCharsets.UTF_8));
		} finally {
			t.stop();
		}
	}
}
