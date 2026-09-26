package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.PlayerEventStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Attrappen für die Sozial-Tests: HTTP je „METHODE pfad“, SSE-Verbindungen zum Befüllen, JSON-Bausteine. */
final class SocialFakes {
	static final String SELF = "75c1a6f3112240abbdb57b9d21c64232";
	static final String BOB = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";
	static final String CARL = "ca11ca11ca11ca11ca11ca11ca11ca11";
	static final String DM = "c00000000000000000001";
	static final String GROUP = "c00000000000000000002";
	static final Executor DIRECT = Runnable::run;

	private SocialFakes() {
	}

	/** HTTP-Attrappe. */
	static final class FakeHttp implements Http {
		final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
		final Map<String, Function<Request, Response>> routes = new HashMap<>();

		FakeHttp on(String key, Function<Request, Response> handler) {
			routes.put(key, handler);
			return this;
		}

		FakeHttp json(String key, int status, String body) {
			return on(key, r -> response(status, body));
		}

		static Response response(int status, String body) {
			Map<String, String> headers = new HashMap<>();
			headers.put("content-type", "application/json");
			return new Response(status, headers, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public Response send(Request request) throws IOException {
			requests.add(request);
			String path = request.url.replaceFirst("^https?://[^/]+", "");
			int q = path.indexOf('?');
			String bare = q >= 0 ? path.substring(0, q) : path;
			Function<Request, Response> h = routes.get(request.method + " " + bare);
			if (h == null) throw new IOException("keine Route: " + request.method + " " + bare);
			return h.apply(request);
		}

		List<String> calls() {
			List<String> out = new ArrayList<>();
			synchronized (requests) {
				for (Request r : requests) out.add(r.method + " " + r.url.replaceFirst("^https?://[^/]+", ""));
			}
			return out;
		}

		long count(String prefix) {
			return calls().stream().filter(c -> c.startsWith(prefix)).count();
		}

		static String body(Request r) {
			return r.body == null ? "" : new String(r.body, StandardCharsets.UTF_8);
		}
	}

	/** Eine SSE-Verbindung, in die der Test Zeilen schiebt. */
	static final class Conn implements PlayerEventStream.Connection {
		final int status;
		final String url;
		final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		volatile boolean closed;

		Conn(int status, String url) {
			this.status = status;
			this.url = url;
		}

		void event(String id, String type, String data) {
			if (id != null) lines.add("id: " + id);
			lines.add("event: " + type);
			lines.add("data: " + data);
			lines.add("");
		}

		void end() {
			lines.add("\u0000END");
		}

		@Override
		public int status() {
			return status;
		}

		@Override
		public String header(String name) {
			return null;
		}

		@Override
		public String readLine() throws IOException {
			try {
				String l = lines.poll(5, TimeUnit.SECONDS);
				if (l == null || l.equals("\u0000END") || closed) return null;
				return l;
			} catch (InterruptedException e) {
				throw new IOException(e);
			}
		}

		@Override
		public void close() {
			closed = true;
			lines.add("\u0000END");
		}
	}

	/** Öffnet der Reihe nach vorbereitete Verbindungen und merkt sich die Adressen. */
	static final class Opener implements PlayerEventStream.Opener {
		final List<String> urls = new CopyOnWriteArrayList<>();
		final BlockingQueue<Conn> next = new LinkedBlockingQueue<>();
		final List<Conn> opened = new CopyOnWriteArrayList<>();
		int defaultStatus = 200;

		Conn prepare(int status) {
			Conn c = new Conn(status, null);
			next.add(c);
			return c;
		}

		@Override
		public PlayerEventStream.Connection open(String url, String token) {
			urls.add(url);
			Conn c = next.poll();
			if (c == null) c = new Conn(defaultStatus, url);
			opened.add(c);
			return c;
		}
	}

	static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
		long until = System.currentTimeMillis() + 5000;
		while (!done.getAsBoolean()) {
			if (System.currentTimeMillis() > until) throw new AssertionError("Zeitüberschreitung");
			Thread.sleep(5);
		}
	}

	// --- JSON-Bausteine ---

	static String msg(String id, String conv, long seq, String sender, String text) {
		return msg(id, conv, seq, sender, text, null, "2026-09-26T10:00:" + String.format("%02d", seq % 60) + ".000Z");
	}

	static String msg(String id, String conv, long seq, String sender, String text, String nonce, String at) {
		return "{\"id\":\"" + id + "\",\"conversationId\":\"" + conv + "\",\"seq\":" + seq + ",\"kind\":\"text\","
				+ "\"sender\":{\"uuid\":\"" + sender + "\",\"name\":\"" + (sender.equals(SELF) ? "Theredstonee" : sender.equals(BOB) ? "Bob" : "Carl") + "\"},"
				+ "\"text\":" + (text == null ? "null" : "\"" + text + "\"") + ",\"invite\":null,\"attachments\":[],\"replyTo\":null,"
				+ "\"system\":null,\"reactions\":[],\"createdAt\":\"" + at + "\",\"editedAt\":null,\"deleted\":false,"
				+ "\"deletedBy\":null,\"hidden\":false,\"nonce\":" + (nonce == null ? "null" : "\"" + nonce + "\"") + "}";
	}

	static String dm(int unread, long lastSeq, String last) {
		return "{\"id\":\"" + DM + "\",\"kind\":\"dm\",\"name\":null,\"owner\":null,\"members\":[{\"uuid\":\"" + SELF
				+ "\",\"name\":\"Theredstonee\",\"role\":\"member\"},{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\",\"role\":\"member\"}],"
				+ "\"peer\":{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\"},\"canWrite\":true,\"readOnlyReason\":null,\"lastMessage\":"
				+ (last == null ? "null" : last) + ",\"lastSeq\":" + lastSeq + ",\"unread\":" + unread
				+ ",\"markedUnread\":false,\"readSeq\":0,\"muted\":false,\"mutedUntil\":null,\"reads\":[],"
				+ "\"createdAt\":\"2026-09-26T09:00:00.000Z\",\"updatedAt\":\"2026-09-26T10:00:00.000Z\"}";
	}

	static String group(boolean muted) {
		return "{\"id\":\"" + GROUP + "\",\"kind\":\"group\",\"name\":\"Bau-Crew\",\"owner\":\"" + SELF + "\",\"members\":[{\"uuid\":\""
				+ SELF + "\",\"name\":\"Theredstonee\",\"role\":\"owner\"},{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\",\"role\":\"member\"},"
				+ "{\"uuid\":\"" + CARL + "\",\"name\":\"Carl\",\"role\":\"member\"}],\"peer\":null,\"canWrite\":true,"
				+ "\"readOnlyReason\":null,\"lastMessage\":null,\"lastSeq\":1,\"unread\":0,\"markedUnread\":false,\"readSeq\":1,"
				+ "\"muted\":" + muted + ",\"mutedUntil\":null,\"reads\":[],\"createdAt\":\"2026-09-26T08:00:00.000Z\","
				+ "\"updatedAt\":\"2026-09-26T08:00:00.000Z\"}";
	}

	/** Backend ohne Freunde. */
	static final class Backend implements Social.Backend {
		final List<String> unauthorized = new ArrayList<>();

		@Override
		public void unauthorized(String rejectedToken) {
			unauthorized.add(rejectedToken);
		}

		@Override
		public Friends friends() {
			return null;
		}
	}
}
