package dev.theredstonee.trsclient.core.circuit.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.circuit.Circuit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Führt die Tests einer Schaltung ({@code "tests"} in der JSON) in der {@link RedstoneSim} aus. Formate:
 * <pre>
 * {"truth": {"in": ["A","B"], "out": ["Q"], "rows": ["00:0", "01:1", "10:1", "11:0"], "ticks": 30}}
 *     – Eingänge je Zeile setzen (Hex-Ziffer 0–f, z. B. Behälter-Füllstand), warten, Ausgänge prüfen
 * {"steps": [{"set": {"A": 1}}, {"press": "B"}, {"bump": "G"}, {"run": 10}, {"expect": {"Q": 1}},
 *            {"count": {"out": "Q", "ticks": 200, "toggles": [4, 999]}},
 *            {"count": {"out": "Q", "ticks": 60, "on": [2, 12]}},
 *            {"mark": "Q"}, {"press": "T"}, {"run": 40}, {"changed": {"Q": 1}}]}
 * </pre>
 * Vor jedem Test läuft die Schaltung 40 Spiel-Ticks „warm“ (Fackeln gehen an, Takte starten …).
 */
public final class CircuitSimTest {
	private CircuitSimTest() {
	}

	/** @return Fehlerbeschreibungen (leer = alles bestanden) */
	public static List<String> run(Circuit c) {
		List<String> errors = new ArrayList<String>();
		JsonArray tests = c.tests;
		for (int i = 0; i < tests.size(); i++) {
			JsonObject t = tests.get(i).getAsJsonObject();
			String where = c.id + " Test " + (i + 1);
			try {
				RedstoneSim sim = new RedstoneSim(c);
				sim.run(40);
				if (t.has("setup")) steps(sim, t.getAsJsonArray("setup"), where, errors);
				if (t.has("truth")) truth(sim, t.getAsJsonObject("truth"), where, errors);
				if (t.has("steps")) steps(sim, t.getAsJsonArray("steps"), where, errors);
			} catch (RuntimeException e) {
				errors.add(where + ": " + e);
			}
		}
		return errors;
	}

	private static List<String> names(JsonElement e) {
		List<String> out = new ArrayList<String>();
		if (e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
		} else {
			out.add(e.getAsString());
		}
		return out;
	}

	private static void truth(RedstoneSim sim, JsonObject t, String where, List<String> errors) {
		List<String> in = names(t.get("in"));
		List<String> out = names(t.get("out"));
		int ticks = t.has("ticks") ? t.get("ticks").getAsInt() : 30;
		for (JsonElement r : t.getAsJsonArray("rows")) {
			String row = r.getAsString();
			int colon = row.indexOf(':');
			String ins = row.substring(0, colon);
			String outs = row.substring(colon + 1);
			for (int i = 0; i < in.size(); i++) sim.set(in.get(i), Character.digit(ins.charAt(i), 16));
			sim.run(ticks);
			for (int i = 0; i < out.size(); i++) {
				boolean want = outs.charAt(i) != '0';
				boolean got = sim.on(out.get(i));
				if (want != got) errors.add(where + ": Zeile " + row + " → " + out.get(i) + " ist " + (got ? 1 : 0));
			}
		}
	}

	private static void steps(RedstoneSim sim, JsonArray steps, String where, List<String> errors) {
		int index = 0;
		java.util.Map<String, Boolean> marked = new java.util.HashMap<String, Boolean>();
		for (JsonElement e : steps) {
			index++;
			JsonObject s = e.getAsJsonObject();
			if (s.has("set")) {
				for (Map.Entry<String, JsonElement> x : s.getAsJsonObject("set").entrySet()) {
					sim.set(x.getKey(), x.getValue().getAsInt());
				}
			}
			if (s.has("press")) sim.press(s.get("press").getAsString());
			if (s.has("bump")) sim.bump(s.get("bump").getAsString());
			if (s.has("run")) sim.run(s.get("run").getAsInt());
			if (s.has("mark")) {
				for (String m : names(s.get("mark"))) marked.put(m, sim.on(m));
			}
			if (s.has("changed")) {
				for (Map.Entry<String, JsonElement> x : s.getAsJsonObject("changed").entrySet()) {
					Boolean before = marked.get(x.getKey());
					boolean want = x.getValue().getAsInt() != 0;
					boolean got = before != null && before.booleanValue() != sim.on(x.getKey());
					if (want != got) errors.add(where + " Schritt " + index + ": " + x.getKey() + (got ? " hat gewechselt" : " hat nicht gewechselt"));
					marked.put(x.getKey(), sim.on(x.getKey()));
				}
			}
			if (s.has("expect")) {
				for (Map.Entry<String, JsonElement> x : s.getAsJsonObject("expect").entrySet()) {
					boolean want = x.getValue().getAsInt() != 0;
					boolean got = sim.on(x.getKey());
					if (want != got) errors.add(where + " Schritt " + index + ": " + x.getKey() + " ist " + (got ? 1 : 0));
				}
			}
			if (s.has("count")) {
				JsonObject c = s.getAsJsonObject("count");
				String out = c.get("out").getAsString();
				int ticks = c.get("ticks").getAsInt();
				int toggles = 0;
				int onTicks = 0;
				boolean last = sim.on(out);
				for (int i = 0; i < ticks; i++) {
					sim.tick();
					boolean now = sim.on(out);
					if (now != last) toggles++;
					if (now) onTicks++;
					last = now;
				}
				check(c, "toggles", toggles, where + " Schritt " + index + " (" + out + " Wechsel)", errors);
				check(c, "on", onTicks, where + " Schritt " + index + " (" + out + " an-Ticks)", errors);
			}
		}
	}

	private static void check(JsonObject c, String key, int value, String what, List<String> errors) {
		if (!c.has(key)) return;
		JsonArray range = c.getAsJsonArray(key);
		int min = range.get(0).getAsInt();
		int max = range.get(1).getAsInt();
		if (value < min || value > max) errors.add(what + ": " + value + " statt " + min + "–" + max);
	}
}
