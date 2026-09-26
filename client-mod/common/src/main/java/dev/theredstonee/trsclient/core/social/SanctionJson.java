package dev.theredstonee.trsclient.core.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Liest Strafen aus JSON (Fehlerkörper, Ereignisse, {@code GET /v1/me/sanctions}) – bewusst ohne feste DTOs: fehlende
 * oder falsch getypte Felder werden toleriert (Standardwert), nur eine gültige ID und Art sind Pflicht.
 */
public final class SanctionJson {
	static final int MAX_LIST = 200;
	static final int MAX_REASON = 500;
	static final int MAX_RESPONSE = 1000;

	private SanctionJson() {
	}

	/** JSON-Text → Objekt oder null (kaputt/kein Objekt). */
	static JsonObject object(String json) {
		if (json == null || json.isEmpty() || json.length() > 256 * 1024) return null;
		try {
			JsonElement e = new JsonParser().parse(json);
			return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	static String str(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		if (e == null || !e.isJsonPrimitive()) return null;
		JsonPrimitive p = e.getAsJsonPrimitive();
		return p.isString() ? p.getAsString() : null;
	}

	static long num(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		if (e == null || !e.isJsonPrimitive()) return 0;
		JsonPrimitive p = e.getAsJsonPrimitive();
		try {
			if (p.isNumber()) return p.getAsLong();
			if (p.isString()) return Long.parseLong(p.getAsString().trim());
		} catch (RuntimeException ignored) {
			// kein Zahlwert
		}
		return 0;
	}

	static boolean bool(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	/** Eine {@code MySanctionView} bereinigen; ohne gültige ID → null. */
	public static Sanction sanction(JsonObject o) {
		if (o == null) return null;
		long id = num(o, "id");
		if (id <= 0) return null;
		String kind = str(o, "kind");
		if (kind == null || !Sanction.KINDS.contains(kind)) kind = "unknown";
		String code = str(o, "reasonCode");
		if (code == null || !Sanction.REASONS.contains(code)) code = "other";
		String reason = str(o, "reason");
		reason = reason == null ? null : SafeText.line(reason, MAX_REASON);
		if (reason != null && reason.isEmpty()) reason = null;
		long starts = ChatJson.time(str(o, "startsAt"));
		long ends = ChatJson.time(str(o, "endsAt"));
		long lifted = ChatJson.time(str(o, "liftedAt"));
		String status = str(o, "status");
		if (status == null || !status.matches("active|expired|lifted")) {
			// Stand fehlt: aus den Zeiten ableiten.
			status = lifted > 0 ? "lifted" : ends > 0 && ends <= System.currentTimeMillis() ? "expired" : "active";
		}
		Sanction.Appeal appeal = appeal(obj(o, "appeal"));
		boolean appealable = bool(o, "appealable") && appeal == null && "active".equals(status);
		return new Sanction(id, kind, code, reason, starts, ends, status, lifted, appeal, appealable);
	}

	/** Ein Einspruch ({@code MyAppealView}) oder null. */
	public static Sanction.Appeal appeal(JsonObject o) {
		if (o == null) return null;
		long id = num(o, "id");
		String status = str(o, "status");
		if (status == null || !status.matches("open|lifted|shortened|upheld")) status = "open";
		String response = str(o, "response");
		response = response == null ? null : SafeText.message(response, MAX_RESPONSE);
		if (response != null && response.isEmpty()) response = null;
		return new Sanction.Appeal(Math.max(0, id), status, ChatJson.time(str(o, "createdAt")),
				ChatJson.time(str(o, "decidedAt")), response);
	}

	/** {@code GET /v1/me/sanctions} → {aktiv, vergangen}, je neueste zuerst. */
	public static List<List<Sanction>> lists(String json) {
		JsonObject o = object(json);
		List<List<Sanction>> out = new ArrayList<List<Sanction>>();
		out.add(list(o, "active"));
		out.add(list(o, "past"));
		return out;
	}

	private static List<Sanction> list(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		if (e == null || !e.isJsonArray()) return new ArrayList<Sanction>();
		JsonArray a = e.getAsJsonArray();
		List<Sanction> out = new ArrayList<Sanction>();
		for (int i = 0; i < a.size() && out.size() < MAX_LIST; i++) {
			JsonElement x = a.get(i);
			Sanction s = x != null && x.isJsonObject() ? sanction(x.getAsJsonObject()) : null;
			if (s != null) out.add(s);
		}
		sort(out);
		return out;
	}

	/** Neueste zuerst (Beginn, dann ID). */
	static void sort(List<Sanction> list) {
		Collections.sort(list, new Comparator<Sanction>() {
			@Override
			public int compare(Sanction a, Sanction b) {
				if (a.startsAt != b.startsAt) return a.startsAt > b.startsAt ? -1 : 1;
				return a.id == b.id ? 0 : a.id > b.id ? -1 : 1;
			}
		});
	}

	/** Antwort mit {@code {sanction}} (Einspruch, Ereignisse) → Strafe oder null. */
	public static Sanction wrapped(String json) {
		return sanction(obj(object(json), "sanction"));
	}
}
