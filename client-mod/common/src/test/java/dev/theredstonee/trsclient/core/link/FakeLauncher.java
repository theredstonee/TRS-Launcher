package dev.theredstonee.trsclient.core.link;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Attrappe des TRS Launchers (Protokoll 2) für Tests: prüft die Anmeldung wie der echte Launcher und beantwortet
 * Konto-Anfragen. Zwei Konten: Alex (Standard) und Steve.
 */
public final class FakeLauncher implements AutoCloseable {
	public static final String SID = "0123456789abcdef";
	public static final byte[] KEY = new byte[32];

	static {
		for (int i = 0; i < 32; i++) KEY[i] = (byte) i;
	}

	public static final String ALEX = "11111111111111111111111111111111";
	public static final String STEVE = "22222222222222222222222222222222";

	final ServerSocket server;
	public final BlockingQueue<String> received = new LinkedBlockingQueue<String>();
	/** Beweis des Launchers absichtlich falsch (fremdes Programm auf dem Port). */
	public volatile boolean forgeProof;
	public volatile boolean authOk;
	/** Merkmale im {@code challenge} (JSON-Liste). */
	public volatile String features = "[\"clips\",\"accounts\"]";
	/** Erste Statuszeile nach der Anmeldung. */
	public volatile String initialState = "{\"type\":\"state\",\"available\":false,\"reason\":\"disabled\",\"buffer\":false,"
			+ "\"recording\":false,\"recordingMs\":0,\"clipSeconds\":30,\"audio\":true,\"mic\":false}";
	/** Antwort auf {@code clips.enable}: null = ok (dann FFmpeg-Fortschritt und laufender Puffer), sonst Fehlercode. */
	public volatile String enableError;
	public final java.util.concurrent.atomic.AtomicInteger enables = new java.util.concurrent.atomic.AtomicInteger();
	/** Antwort auf {@code clips.preview} (JSON-Objekt für {@code "preview"}) bzw. Fehlercode. */
	public volatile String previewJson;
	public volatile String previewError = "unknown_clip";
	/** Verzögerung der Vorschau-Antwort (FFmpeg rechnet). */
	public volatile long previewDelayMs;
	public final java.util.concurrent.atomic.AtomicInteger previews = new java.util.concurrent.atomic.AtomicInteger();
	/** Clips, die per {@code clips.open} geöffnet wurden. */
	public final BlockingQueue<String> opened = new LinkedBlockingQueue<String>();
	private final Thread thread;
	private volatile OutputStream out;

	public FakeLauncher() throws IOException {
		server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
		thread = new Thread(this::serve, "fake-launcher");
		thread.setDaemon(true);
		thread.start();
	}

	public int port() {
		return server.getLocalPort();
	}

	public String env() {
		return "2:" + port() + ":" + SID + ":" + LinkCrypto.hex(KEY);
	}

	public void push(String line) throws IOException {
		OutputStream o = out;
		if (o != null) {
			synchronized (this) {
				o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
				o.flush();
			}
		}
	}

	private void serve() {
		while (!server.isClosed()) {
			try (Socket s = server.accept()) {
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
				OutputStream o = s.getOutputStream();
				String hello = in.readLine();
				received.add(hello);
				JsonObject h = new JsonParser().parse(hello).getAsJsonObject();
				if (!SID.equals(h.get("sid").getAsString())) {
					write(o, "{\"type\":\"denied\"}");
					continue;
				}
				String nc = h.get("nonce").getAsString();
				String nl = LinkCrypto.randomHex(16);
				String proof = forgeProof ? LinkCrypto.randomHex(32) : LinkCrypto.launcherProof(KEY, SID, nc, nl);
				write(o, "{\"type\":\"challenge\",\"nonce\":\"" + nl + "\",\"proof\":\"" + proof
						+ "\",\"features\":" + features + "}");
				String auth = in.readLine();
				if (auth == null) continue;
				received.add(auth);
				JsonObject a = new JsonParser().parse(auth).getAsJsonObject();
				if (!LinkCrypto.equalsConstantTime(LinkCrypto.gameProof(KEY, SID, nc, nl), a.get("proof").getAsString())) {
					write(o, "{\"type\":\"denied\"}");
					continue;
				}
				authOk = true;
				byte[] seal = LinkCrypto.sealKey(KEY, nc, nl);
				out = o;
				write(o, initialState);
				String line;
				while ((line = in.readLine()) != null) {
					received.add(line);
					JsonElement e = new JsonParser().parse(line);
					JsonObject req = e.getAsJsonObject();
					if (!"req".equals(req.get("type").getAsString())) continue;
					long id = req.get("id").getAsLong();
					String op = req.get("op").getAsString();
					if ("accounts.list".equals(op)) {
						write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":true,\"accounts\":["
								+ "{\"id\":\"" + ALEX + "\",\"name\":\"Alex\",\"skinUrl\":null,\"active\":true},"
								+ "{\"id\":\"" + STEVE + "\",\"name\":\"Steve\",\"skinUrl\":\"https://evil.example/x.png\",\"active\":false},"
								+ "{\"id\":\"kaputt\",\"name\":\"<script>\",\"active\":false}]}");
					} else if ("accounts.session".equals(op)) {
						String account = req.get("account").getAsString();
						if (!STEVE.equals(account) && !ALEX.equals(account)) {
							write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":false,\"error\":\"unknown_account\"}");
							continue;
						}
						String name = STEVE.equals(account) ? "Steve" : "Alex";
						byte[] n = LinkCrypto.unhex(LinkCrypto.randomHex(16));
						String token = LinkCrypto.seal(seal, ("mc-token-" + name).getBytes(StandardCharsets.UTF_8), n);
						write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":true,\"session\":{\"id\":\"" + account
								+ "\",\"name\":\"" + name + "\",\"xuid\":\"2535400000000000\",\"token\":\"" + token + "\"}}");
					} else if ("clips.enable".equals(op)) {
						enables.incrementAndGet();
						String error = enableError;
						if (error != null) {
							write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":false,\"error\":\"" + error + "\"}");
							continue;
						}
						write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":true,\"clipSeconds\":30}");
						write(o, "{\"type\":\"state\",\"available\":false,\"reason\":\"ffmpeg\",\"progress\":40,\"buffer\":false,"
								+ "\"recording\":false,\"recordingMs\":0,\"clipSeconds\":30}");
						write(o, "{\"type\":\"state\",\"available\":true,\"buffer\":true,\"recording\":false,"
								+ "\"recordingMs\":0,\"clipSeconds\":30}");
					} else if ("clips.preview".equals(op)) {
						previews.incrementAndGet();
						if (previewDelayMs > 0) {
							try {
								Thread.sleep(previewDelayMs);
							} catch (InterruptedException ignored) {
								Thread.currentThread().interrupt();
							}
						}
						String json = previewJson;
						if (json == null) {
							write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":false,\"error\":\"" + previewError + "\"}");
						} else {
							write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":true,\"preview\":" + json + "}");
						}
					} else if ("clips.open".equals(op)) {
						String clip = req.has("clip") ? req.get("clip").getAsString() : "";
						if (clip.endsWith(".mp4")) {
							opened.add(clip);
							write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":true}");
						} else {
							write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":false,\"error\":\"unknown_clip\"}");
						}
					} else if ("accounts.add".equals(op)) {
						write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":false,\"error\":\"busy\"}");
					} else {
						write(o, "{\"type\":\"res\",\"id\":" + id + ",\"ok\":false,\"error\":\"<b>unknown</b>\"}");
					}
				}
			} catch (IOException | RuntimeException e) {
				// nächste Verbindung
			} finally {
				out = null;
			}
		}
	}

	private synchronized void write(OutputStream o, String line) throws IOException {
		o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		o.flush();
	}

	@Override
	public void close() throws IOException {
		server.close();
	}
}
