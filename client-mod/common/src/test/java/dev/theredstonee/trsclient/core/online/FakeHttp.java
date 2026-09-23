package dev.theredstonee.trsclient.core.online;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** HTTP-Attrappe: Antworten je "METHODE pfad" (Pfad ohne Host), mitgeschriebene Anfragen. */
final class FakeHttp implements Http {
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

	static String body(Request r) {
		return r.body == null ? "" : new String(r.body, StandardCharsets.UTF_8);
	}
}
