package dev.theredstonee.trsclient.core.hosting.netty;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Gast ohne Mixin (Legacy-Forge 1.8.9–1.12.2 hat keinen Einstieg in den Verbindungsaufbau): ein einmaliger
 * Loopback-Port (nur 127.0.0.1, zufälliger Port, nimmt genau EINE Verbindung innerhalb von {@link #ACCEPT_MS} an)
 * reicht die Bytes zwischen Minecraft und dem {@link PeerStream} durch. Von außen nicht erreichbar.
 */
public final class LoopbackBridge {
	static final int ACCEPT_MS = 30_000;

	private LoopbackBridge() {
	}

	/** Öffnet den Port und liefert "127.0.0.1:port" für Minecraft. */
	public static String open(final PeerStream stream) throws IOException {
		final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		server.setSoTimeout(ACCEPT_MS);
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				Socket s;
				try {
					s = server.accept();
				} catch (SocketTimeoutException e) {
					stream.close("not used");
					return;
				} catch (IOException e) {
					stream.close("bridge failed");
					return;
				} finally {
					try {
						server.close();
					} catch (IOException ignored) {
						// egal
					}
				}
				pump(s, stream);
			}
		}, "TRS-Hosting-Bruecke");
		t.setDaemon(true);
		t.start();
		return "127.0.0.1:" + server.getLocalPort();
	}

	static void pump(final Socket s, final PeerStream stream) {
		try {
			s.setTcpNoDelay(true);
			final OutputStream out = s.getOutputStream();
			stream.start(new PeerStream.Sink() {
				@Override
				public void data(byte[] b, int off, int len) {
					try {
						out.write(b, off, len);
						out.flush();
					} catch (IOException e) {
						stream.close("bridge closed");
					}
				}

				@Override
				public void closed(String reason) {
					try {
						s.close();
					} catch (IOException ignored) {
						// egal
					}
				}
			});
			InputStream in = s.getInputStream();
			byte[] buf = new byte[16384];
			int n;
			while ((n = in.read(buf)) >= 0) {
				if (n > 0 && !stream.write(buf, 0, n)) break;
			}
		} catch (IOException ignored) {
			// Ende
		} finally {
			stream.close("bridge closed");
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
		}
	}
}
