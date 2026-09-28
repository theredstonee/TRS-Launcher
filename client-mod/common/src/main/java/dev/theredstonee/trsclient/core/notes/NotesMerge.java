package dev.theredstonee.trsclient.core.notes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zusammenführen der Notizen (reine Logik, getestet): je Notiz gewinnt die neuere Änderung ({@code updatedAt});
 * Löschungen reisen als Grabsteine. Was dieses Konto von einer Notiz schon kennt, merkt sich {@link Account#synced}
 * (ID → Änderungszeit) – so lädt ein Kontowechsel die lokalen Notizen auch in das andere Konto hoch, und ein
 * unverändertes Buch kostet keinen Verkehr.
 */
public final class NotesMerge {
	private NotesMerge() {
	}

	/** Sync-Stand eines TRS-Kontos (Gson-DTO in {@code notes/sync-state.json}). */
	public static final class Account {
		/** Position im Änderungsstrom des Kontos (vom Server, undurchsichtig). */
		public String cursor;
		/** Notiz-ID → Änderungszeit, die das Konto sicher hat. */
		public Map<String, Long> synced = new LinkedHashMap<String, Long>();
		/** Notiz-ID → Änderungszeit, die der Server abgelehnt hat (Grenze/ungültig) – erst nach einer Änderung neu. */
		public Map<String, Long> rejected = new LinkedHashMap<String, Long>();
		/** Grund der letzten Ablehnung ({@code note_limit}/{@code invalid}) oder null. */
		public String rejectReason;

		Account normalized() {
			if (synced == null) synced = new LinkedHashMap<String, Long>();
			if (rejected == null) rejected = new LinkedHashMap<String, Long>();
			return this;
		}
	}

	/** Ergebnis: vom Konto übernehmen, hochladen. */
	public static final class Plan {
		public final List<NotesSyncApi.Remote> apply = new ArrayList<NotesSyncApi.Remote>();
		public final List<NotesStore.Entry> upload = new ArrayList<NotesStore.Entry>();
	}

	/**
	 * Plan aus dem lokalen Stand ({@code local}, Kopien) und den geholten Änderungen des Kontos. Merkt sich in
	 * {@code acc}, was das Konto danach hat.
	 */
	public static Plan plan(List<NotesStore.Entry> local, Account acc, List<NotesSyncApi.Remote> pulled) {
		acc.normalized();
		Plan plan = new Plan();
		Map<String, NotesStore.Entry> byId = new HashMap<String, NotesStore.Entry>();
		for (NotesStore.Entry e : local) byId.put(e.note.id, e);
		Map<String, NotesSyncApi.Remote> latest = new LinkedHashMap<String, NotesSyncApi.Remote>();
		for (NotesSyncApi.Remote r : pulled) {
			NotesSyncApi.Remote old = latest.get(r.note.id);
			if (old == null || r.note.updated >= old.note.updated) latest.put(r.note.id, r);
		}
		for (NotesSyncApi.Remote r : latest.values()) {
			NotesStore.Entry l = byId.get(r.note.id);
			if (l == null) {
				if (!r.note.deleted) plan.apply.add(r);
			} else if (r.note.updated > l.note.updated
					|| (r.note.updated == l.note.updated && (!r.note.sameContent(l.note) || !r.world.equals(l.world)))) {
				plan.apply.add(r);
			}
			acc.synced.put(r.note.id, Long.valueOf(r.note.updated));
		}
		for (NotesStore.Entry l : local) {
			String id = l.note.id;
			NotesSyncApi.Remote r = latest.get(id);
			if (r != null && r.note.updated >= l.note.updated) continue;
			Long s = acc.synced.get(id);
			if (s != null && s.longValue() == l.note.updated) continue;
			Long rej = acc.rejected.get(id);
			if (rej != null && rej.longValue() == l.note.updated) continue;
			// Grabstein einer Notiz, die das Konto nie hatte: nichts zu tun.
			if (l.note.deleted && s == null && r == null) continue;
			plan.upload.add(l);
		}
		return plan;
	}

	/** Ergebnis eines POST einarbeiten; neuere Stände des Kontos landen in {@code apply}. */
	public static void results(Account acc, List<NotesStore.Entry> sent, List<NotesSyncApi.Result> results,
			List<NotesSyncApi.Remote> apply) {
		acc.normalized();
		Map<String, NotesStore.Entry> byId = new HashMap<String, NotesStore.Entry>();
		for (NotesStore.Entry e : sent) byId.put(e.note.id, e);
		for (NotesSyncApi.Result r : results) {
			NotesStore.Entry e = byId.get(r.id);
			if (e == null) continue;
			long at = e.note.updated;
			if (NotesSyncApi.Result.OK.equals(r.status)) {
				acc.synced.put(r.id, Long.valueOf(at));
				acc.rejected.remove(r.id);
			} else if (NotesSyncApi.Result.STALE.equals(r.status)) {
				if (r.current != null && r.current.note.id.equals(r.id)) {
					apply.add(r.current);
					acc.synced.put(r.id, Long.valueOf(r.current.note.updated));
				}
			} else {
				acc.rejected.put(r.id, Long.valueOf(at));
				acc.rejectReason = NotesSyncApi.Result.LIMIT.equals(r.status) ? NotesSyncApi.Result.LIMIT : NotesSyncApi.Result.INVALID;
			}
		}
	}

	/** Pakete für POST: höchstens {@link NotesSyncApi#MAX_BATCH} Einträge und {@link NotesSyncApi#MAX_BATCH_BYTES}. */
	public static List<List<NotesStore.Entry>> batches(List<NotesStore.Entry> upload) {
		List<List<NotesStore.Entry>> out = new ArrayList<List<NotesStore.Entry>>();
		List<NotesStore.Entry> cur = new ArrayList<NotesStore.Entry>();
		int bytes = 32;
		for (NotesStore.Entry e : upload) {
			int size = NotesSyncApi.size(e);
			if (!cur.isEmpty() && (cur.size() >= NotesSyncApi.MAX_BATCH || bytes + size > NotesSyncApi.MAX_BATCH_BYTES)) {
				out.add(cur);
				cur = new ArrayList<NotesStore.Entry>();
				bytes = 32;
			}
			cur.add(e);
			bytes += size;
		}
		if (!cur.isEmpty()) out.add(cur);
		return out;
	}

	/** Merker für Notizen entfernen, die es hier nicht mehr gibt (Grabsteine abgelaufen). */
	public static void prune(Account acc, List<NotesStore.Entry> local, List<NotesSyncApi.Remote> applied) {
		acc.normalized();
		Set<String> keep = new HashSet<String>();
		for (NotesStore.Entry e : local) keep.add(e.note.id);
		for (NotesSyncApi.Remote r : applied) keep.add(r.note.id);
		for (Iterator<String> it = acc.synced.keySet().iterator(); it.hasNext(); ) {
			if (!keep.contains(it.next())) it.remove();
		}
		for (Iterator<String> it = acc.rejected.keySet().iterator(); it.hasNext(); ) {
			if (!keep.contains(it.next())) it.remove();
		}
		if (acc.rejected.isEmpty()) acc.rejectReason = null;
	}
}
