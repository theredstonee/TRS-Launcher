package dev.theredstonee.trsclient.core.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.intro.ClientState;
import dev.theredstonee.trsclient.core.module.NewMarkers;
import dev.theredstonee.trsclient.core.module.NewSince;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zusammenführen des lokalen Stands mit dem {@code client}-Dokument des TRS-Kontos (reine Logik, testbar).
 *
 * <p>Je überschreibbarem Abschnitt ({@link ClientDoc#LWW}):
 * <ul>
 *   <li>Konto hat den Abschnitt nicht → lokaler Stand wird hochgeladen.</li>
 *   <li>Lokal unverändert seit dem letzten Abgleich → Stand des Kontos wird übernommen (falls anders).</li>
 *   <li>Lokal geändert → die neuere Änderung gewinnt ({@code at} des Kontos gegen die lokale Änderungszeit).</li>
 *   <li>Erster Abgleich dieses Kontos auf diesem PC oder gerade das Konto gewechselt → das Konto gewinnt (die
 *   Einstellungen folgen dem Konto); hat es noch nichts, wird der lokale Stand sein Anfangsstand.</li>
 * </ul>
 * {@code intro} und {@code seen} werden vereinigt (einmal erledigt = überall erledigt; gesehen = irgendwo gesehen).
 * Ein Dokument mit neuerem Format wird weder übernommen noch überschrieben.
 */
public final class ClientMerge {
	public enum Decision {
		SAME, ADOPT, APPLY_REMOTE, KEEP_LOCAL
	}

	public static final class Result {
		public final Map<String, Decision> decisions = new HashMap<String, Decision>();
		/** Neuer lokaler Stand (null = lokal nichts zu ändern). */
		public TrsConfig merged;
		/** Hochzuladendes Dokument (null = nichts hochzuladen). */
		public JsonObject upload;
		public String uploadAt;
		/** Stand je Abschnitt nach dem Hochladen/Übernehmen (Hash null = erst nach dem Anwenden bekannt). */
		public final Map<String, SyncState.Mark> synced = new HashMap<String, SyncState.Mark>();
		/** Einführung wurde auf einem anderen PC erledigt (kurze Begrüßung zeigen). */
		public boolean welcome;
		/** Konto-Dokument hat ein neueres Format – nichts tun. */
		public boolean readOnly;
		/** Config-Modus der Optimierungs-Mods vom Konto geändert (neuer Wert) – anwenden. */
		public String fpsModeChanged;
	}

	private ClientMerge() {
	}

	/**
	 * @param local       aktueller lokaler Stand
	 * @param remote      {@code data} des client-Dokuments (null = gibt es noch nicht)
	 * @param remoteAt    {@code updatedAt} des Dokuments (ms, -1 = keins)
	 * @param changedAt   Zeit der letzten lokalen Änderung (gilt für Abschnitte, deren Hash sich geändert hat)
	 * @param forceRemote erster Abgleich nach Kontowechsel/Neuinstallation: das Konto gewinnt
	 */
	public static Result merge(ClientDoc doc, SyncState state, String uuid, TrsConfig local, JsonObject remote,
			long remoteAt, long now, long changedAt, boolean forceRemote, String modVersion) {
		Result res = new Result();
		if (remote != null) {
			JsonElement f = remote.get("format");
			int format = f != null && f.isJsonPrimitive() && f.getAsJsonPrimitive().isNumber() ? f.getAsInt() : 1;
			if (format > ClientDoc.FORMAT) {
				res.readOnly = true;
				return res;
			}
		}
		boolean first = forceRemote || !state.known(uuid);
		Map<String, JsonObject> sections = doc.sections(local);
		Set<String> apply = new LinkedHashSet<String>();
		boolean upload = remote == null;
		Map<String, JsonObject> uploadSections = new HashMap<String, JsonObject>();
		for (String s : ClientDoc.LWW) {
			JsonObject data = sections.get(s);
			String hash = ClientDoc.hash(data);
			state.observeLocal(s, hash, changedAt);
			SyncState.Mark lm = state.local(s);
			long localAt = lm != null && lm.at > 0 ? lm.at : now;
			SyncState.Mark sm = state.synced(uuid, s);
			JsonObject rdata = ClientDoc.data(remote, s);
			long rat = ClientDoc.at(remote, s);
			Decision d;
			if (rdata == null || rat < 0) {
				d = Decision.KEEP_LOCAL;
			} else {
				boolean dirty = !first && sm != null && !hash.equals(sm.hash);
				if (!dirty) {
					if (sm != null && sm.at == rat && hash.equals(sm.hash)) d = Decision.SAME;
					else if (ClientDoc.hash(rdata).equals(hash)) d = Decision.ADOPT;
					else d = Decision.APPLY_REMOTE;
				} else {
					d = rat > localAt ? Decision.APPLY_REMOTE : Decision.KEEP_LOCAL;
				}
			}
			res.decisions.put(s, d);
			switch (d) {
				case KEEP_LOCAL:
					upload = true;
					uploadSections.put(s, ClientDoc.section(localAt, doc.carry(s, copy(data), rdata)));
					res.synced.put(s, new SyncState.Mark(hash, localAt));
					break;
				case APPLY_REMOTE:
					apply.add(s);
					res.synced.put(s, new SyncState.Mark(null, rat));
					break;
				default:
					res.synced.put(s, new SyncState.Mark(hash, rat));
					break;
			}
		}

		// --- Einführung und NEU (vereinigen) ---
		TrsConfig.ClientStateData cs = local.clientState != null ? local.clientState : new TrsConfig.ClientStateData();
		TrsConfig.ClientStateData newCs = copy(cs);
		boolean csChanged = false;
		JsonObject rIntro = ClientDoc.object(remote, ClientDoc.INTRO);
		// „existing“ (frühe Testversionen: Update von 0.5.x galt als eingerichtet) zählt nicht – die Einführung
		// kommt dann einmal für alle.
		boolean remoteDone = rIntro != null && rIntro.get("done") != null && rIntro.get("done").isJsonPrimitive()
				&& rIntro.get("done").getAsBoolean() && !ClientState.countsAsOpen(str(rIntro, "how", 16));
		boolean localDone = Boolean.TRUE.equals(cs.introDone) && !ClientState.countsAsOpen(cs.introHow);
		if (remoteDone && !localDone) {
			newCs.introDone = true;
			newCs.introHow = ClientState.ACCOUNT;
			newCs.introPack = str(rIntro, "pack", 24);
			long at = SyncTime.parse(str(rIntro, "at", 40));
			newCs.introAt = at > 0 ? at : now;
			csChanged = true;
			res.welcome = !Boolean.TRUE.equals(cs.welcomeShown);
		} else if (localDone && !remoteDone) {
			upload = true;
		}
		NewMarkers news = new NewMarkers(NewSince.valid(cs.newBaseline) ? cs.newBaseline : NewSince.latest());
		news.set(news.baseline(), cs.newSeen);
		JsonObject rSeen = ClientDoc.object(remote, ClientDoc.SEEN);
		String rBaseline = rSeen == null ? null : str(rSeen, "baseline", 20);
		List<String> rIds = rSeen == null ? new ArrayList<String>() : ClientDoc.strings(rSeen.get("ids"));
		if (news.merge(rBaseline, rIds)) {
			newCs.newBaseline = news.baseline();
			newCs.newSeen = news.seen();
			csChanged = true;
		}
		JsonObject seenJson = ClientDoc.seen(news.baseline(), news.seen());
		if (rSeen == null || !ClientDoc.hash(seenJson).equals(ClientDoc.hash(ClientDoc.seen(rBaseline, rIds)))) upload = true;

		res.synced.put(ClientDoc.INTRO, new SyncState.Mark(introHash(newCs), 0));
		res.synced.put(ClientDoc.SEEN, new SyncState.Mark(ClientDoc.hash(seenJson), 0));

		if (!apply.isEmpty() || csChanged) {
			TrsConfig merged = doc.apply(local, remote, apply);
			if (merged.clientState == null) merged.clientState = new TrsConfig.ClientStateData();
			String fpsBefore = cs.fpsMode;
			String fpsRemote = merged.clientState.fpsMode;
			TrsConfig.ClientStateData m = copy(newCs);
			m.fpsMode = fpsRemote;
			merged.clientState = m;
			res.merged = merged;
			if (apply.contains(ClientDoc.PREFS) && fpsRemote != null && !fpsRemote.equals(fpsBefore)) res.fpsModeChanged = fpsRemote;
		}

		if (upload) {
			JsonObject out = new JsonObject();
			// Unbekannte Teile neuerer Clients bleiben erhalten.
			if (remote != null) {
				for (Map.Entry<String, JsonElement> e : remote.entrySet()) out.add(e.getKey(), e.getValue());
			}
			out.addProperty("format", ClientDoc.FORMAT);
			if (modVersion != null && modVersion.matches("[0-9A-Za-z.+-]{1,32}")) out.addProperty("mod", modVersion);
			for (String s : ClientDoc.LWW) {
				JsonObject sec = uploadSections.get(s);
				if (sec != null) out.add(s, sec);
			}
			JsonObject intro = ClientDoc.intro(newCs);
			if (intro != null) out.add(ClientDoc.INTRO, intro);
			out.add(ClientDoc.SEEN, seenJson);
			if (!ClientDoc.fit(out)) {
				res.upload = null;
			} else {
				res.upload = out;
				res.uploadAt = SyncTime.iso(Math.max(now, remoteAt));
			}
		}
		return res;
	}

	/** Hash des Einführungs-Stands (für „hat sich lokal etwas geändert?“). */
	public static String introHash(TrsConfig.ClientStateData cs) {
		JsonObject intro = ClientDoc.intro(cs);
		return intro == null ? "-" : ClientDoc.hash(intro);
	}

	/** Hash des NEU-Stands. */
	public static String seenHash(TrsConfig.ClientStateData cs) {
		if (cs == null) return "-";
		NewMarkers news = new NewMarkers(NewSince.valid(cs.newBaseline) ? cs.newBaseline : NewSince.latest());
		news.set(news.baseline(), cs.newSeen);
		return ClientDoc.hash(ClientDoc.seen(news.baseline(), news.seen()));
	}

	private static JsonObject copy(JsonObject o) {
		return ClientDoc.GSON.fromJson(ClientDoc.json(o), JsonObject.class);
	}

	private static TrsConfig.ClientStateData copy(TrsConfig.ClientStateData d) {
		return ClientDoc.GSON.fromJson(ClientDoc.GSON.toJson(d), TrsConfig.ClientStateData.class);
	}

	private static String str(JsonObject o, String key, int max) {
		JsonElement e = o == null ? null : o.get(key);
		if (e == null || !e.isJsonPrimitive()) return null;
		String s = e.getAsString();
		return s.length() <= max ? s : null;
	}
}
