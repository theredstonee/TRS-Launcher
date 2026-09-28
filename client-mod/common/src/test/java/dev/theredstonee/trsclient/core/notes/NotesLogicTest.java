package dev.theredstonee.trsclient.core.notes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.chat.ChatCoords;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.sync.ClientSync;
import dev.theredstonee.trsclient.core.sync.SyncTime;
import dev.theredstonee.trsclient.core.ui.social.ChatLayout;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Notizen je Welt: Text (Checklisten, Koordinaten), Welten, Speicher, Zusammenführen und Sync gegen eine Attrappe. */
class NotesLogicTest {
	private static final Executor DIRECT = new Executor() {
		@Override
		public void execute(Runnable command) {
			command.run();
		}
	};
	private static final ChatLayout.Measure MONO = new ChatLayout.Measure() {
		@Override
		public int width(String s) {
			return s.codePointCount(0, s.length()) * 6;
		}
	};
	private static final NoteWorld SERVER = NoteWorld.fromWaypointKey("mp:play.example.net");
	private static final NoteWorld WORLD = NoteWorld.fromWaypointKey("sp:Neue Welt");

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		Notes.forTest(new TrsModules(), new NotesStore(null));
		NotesSync.setInstance(null);
	}

	// --- Text ---

	@Test
	void checklistLinesAreRecognised() {
		String text = "Einkauf\n[ ] Eisen\n- [x] Redstone\n  [X] fertig\n[y] kein Kästchen\n[ ]";
		List<NoteText.Line> lines = NoteText.lines(text);
		assertEquals(6, lines.size());
		assertFalse(lines.get(0).isCheck());
		assertEquals(0, lines.get(1).check);
		assertEquals("Eisen", text.substring(lines.get(1).contentStart, lines.get(1).end));
		assertEquals(1, lines.get(2).check);
		assertEquals("Redstone", text.substring(lines.get(2).contentStart, lines.get(2).end));
		assertEquals(1, lines.get(3).check);
		assertFalse(lines.get(4).isCheck());
		assertEquals(0, lines.get(5).check);
		assertEquals(lines.get(5).end, lines.get(5).contentStart);
		assertEquals(2, NoteText.progress(text)[0]);
		assertEquals(4, NoteText.progress(text)[1]);
	}

	@Test
	void toggleAndChecklistEditing() {
		String text = "a\n[ ] b\n[x] c";
		assertEquals("a\n[x] b\n[x] c", NoteText.toggle(text, 1));
		assertEquals("a\n[ ] b\n[ ] c", NoteText.toggle(text, 2));
		assertEquals(text, NoteText.toggle(text, 0));
		assertEquals(text, NoteText.toggle(text, 9));

		Object[] on = NoteText.toggleChecklistLine("Eisen", 2);
		assertEquals("[ ] Eisen", on[0]);
		assertEquals(6, ((Integer) on[1]).intValue());
		Object[] off = NoteText.toggleChecklistLine("[ ] Eisen", 6);
		assertEquals("Eisen", off[0]);
		assertEquals(2, ((Integer) off[1]).intValue());

		Object[] enter = NoteText.enterInChecklist("[ ] Eisen", 9);
		assertEquals("[ ] Eisen\n[ ] ", enter[0]);
		assertEquals(14, ((Integer) enter[1]).intValue());
		Object[] end = NoteText.enterInChecklist("[ ] Eisen\n[ ] ", 14);
		assertEquals("[ ] Eisen\n", end[0]);
		assertNull(NoteText.enterInChecklist("Eisen", 5));
	}

	@Test
	void coordinatesAreLinksWithOffsets() {
		String text = "Basis\n[ ] Portal x: 100, y: 64, z: -20 bauen";
		List<NoteText.Line> lines = NoteText.lines(text);
		List<ChatCoords.Hit> hits = NoteText.coords(text, lines.get(1));
		assertEquals(1, hits.size());
		ChatCoords.Hit h = hits.get(0);
		assertEquals(100, h.x);
		assertEquals(64, h.y);
		assertEquals(-20, h.z);
		assertEquals("x: 100, y: 64, z: -20", text.substring(h.start, h.end));
		assertEquals("x: 12, y: -3, z: 4", NoteText.position(12, -3, 4));
		assertEquals(1, ChatCoords.find(NoteText.position(12, -3, 4)).size());
	}

	@Test
	void layoutIndentsChecklistsAndClipsLinks() {
		String text = "[ ] eins zwei drei vier fuenf\nx: 1, y: 2, z: 3";
		List<NoteLayout.Row> rows = NoteLayout.layout(text, 6 * 12 + NoteLayout.BOX_W, MONO);
		assertEquals(0, rows.get(0).check);
		assertEquals(NoteLayout.BOX_W, rows.get(0).indent);
		assertTrue(rows.size() >= 3);
		assertEquals(-1, rows.get(1).check);
		assertEquals(NoteLayout.BOX_W, rows.get(1).indent);
		NoteLayout.Row last = rows.get(rows.size() - 1);
		assertFalse(last.coords.isEmpty());
		assertEquals(1, last.coords.get(0).x);
	}

	@Test
	void searchMatchesAllWords() {
		Note n = Note.create(1);
		n.title = "Farm";
		n.text = "Eisen und Redstone";
		assertTrue(NoteText.matches(n, "eisen farm"));
		assertTrue(NoteText.matches(n, "  "));
		assertFalse(NoteText.matches(n, "eisen gold"));
	}

	@Test
	void cleanRemovesControlAndFormattingAndLimits() {
		assertEquals("ab c", Note.clean("a\u00A7cb\tc\u0007", 64, false).replace("  ", " "));
		assertEquals("a b", Note.clean("a\nb", 64, false));
		assertEquals("a\nb", Note.clean("a\r\nb", 64, true));
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < Note.MAX_TEXT + 50; i++) big.append('x');
		assertEquals(Note.MAX_TEXT, Note.clean(big.toString(), Note.MAX_TEXT, true).length());
		assertEquals(2, Note.length(Note.clean("😀😀😀", 2, false)));
	}

	// --- Welten ---

	@Test
	void worldsMatchWaypointIds() {
		NoteWorld a = NoteWorld.fromWaypointKey("mp:Play.Example.NET:25565");
		assertEquals("server:play.example.net", a.key());
		assertEquals(SERVER, a);
		assertEquals("world:" + WaypointShare.worldId("sp:Neue Welt"), WORLD.key());
		assertEquals("Neue Welt", WORLD.label());
		assertNull(NoteWorld.fromWaypointKey("sp:?"));
		assertNull(NoteWorld.fromWaypointKey(""));
		assertNull(NoteWorld.fromWaypointKey("mp:"));

		NoteWorld back = NoteWorld.fromJson(WORLD.toJson());
		assertEquals(WORLD, back);
		assertEquals("Neue Welt", back.name);
		assertEquals(SERVER, NoteWorld.fromJson(SERVER.toJson()));
		JsonObject bad = new JsonObject();
		bad.addProperty("type", "world");
		bad.addProperty("id", "../../etc");
		assertNull(NoteWorld.fromJson(bad));
		JsonObject evil = new JsonObject();
		evil.addProperty("type", "server");
		evil.addProperty("address", "a b/c");
		assertNull(NoteWorld.fromJson(evil));
		// Unbekannter Name (nur Kennung vom Konto): Anfang der Kennung.
		assertTrue(NoteWorld.fromKey(WORLD.key(), null).label().startsWith("#"));
	}

	// --- Speicher ---

	@Test
	void storeCreatesEditsDeletesAndReloads() throws IOException {
		NotesStore store = new NotesStore(tmp.resolve("notes")).load(1000);
		NoteBook book = store.book(SERVER);
		Note a = store.create(book, 1000);
		assertTrue(store.update(book, a, "Farm", "[ ] Eisen", 2000));
		assertFalse(store.update(book, a, "Farm", "[ ] Eisen", 3000));
		Note b = store.create(book, 2500);
		store.pin(book, a);
		store.save();
		assertTrue(Files.isRegularFile(tmp.resolve("notes").resolve(SERVER.fileName())));

		NotesStore again = new NotesStore(tmp.resolve("notes")).load(4000);
		NoteBook book2 = again.existing(SERVER.key());
		assertNotNull(book2);
		assertEquals(2, book2.liveCount());
		assertEquals("Farm", book2.byId(a.id).title);
		assertEquals(a.id, again.pinned(book2).id);

		again.delete(book2, book2.byId(a.id), 5000);
		assertNull(again.pinned(book2));
		assertEquals(1, book2.liveCount());
		assertTrue(book2.byId(a.id).deleted);
		assertEquals("", book2.byId(a.id).text);
		again.save();
		// Grabsteine laufen ab.
		NotesStore later = new NotesStore(tmp.resolve("notes")).load(5000 + NotesStore.TOMBSTONE_TTL_MS + 1);
		assertNull(later.existing(SERVER.key()).byId(a.id));
		assertNotNull(later.existing(SERVER.key()).byId(b.id));
		assertEquals(1, later.booksWithNotes().size());
	}

	@Test
	void limitOfNotesPerWorld() {
		NotesStore store = new NotesStore(null);
		NoteBook book = store.book(WORLD);
		for (int i = 0; i < NoteBook.MAX_NOTES; i++) assertNotNull(store.create(book, i + 1));
		assertFalse(book.canAdd());
		assertNull(store.create(book, 999));
		store.delete(book, book.live().get(0), 1000);
		assertNotNull(store.create(book, 1001));
	}

	@Test
	void brokenFilesAreSkipped() throws IOException {
		Path dir = tmp.resolve("notes");
		Files.createDirectories(dir);
		Files.write(dir.resolve("abc.json"), "{nope".getBytes(StandardCharsets.UTF_8));
		Files.write(dir.resolve("def.json"), "{\"key\":\"world:../x\",\"notes\":[]}".getBytes(StandardCharsets.UTF_8));
		Files.write(dir.resolve("ghi.json"), ("{\"key\":\"server:a.b\",\"notes\":[{\"id\":\"bad\"},{\"id\":\"0123456789abcdef\","
				+ "\"title\":\"ok\",\"text\":\"\\u00a7ctext\",\"updated\":5}]}").getBytes(StandardCharsets.UTF_8));
		NotesStore store = new NotesStore(dir).load(10);
		NoteBook b = store.existing("server:a.b");
		assertNotNull(b);
		assertEquals(1, b.all().size());
		assertEquals("text", b.byId("0123456789abcdef").text);
	}

	@Test
	void remoteWinsOnlyWhenNewerAndCanMoveWorlds() {
		NotesStore store = new NotesStore(null);
		NoteBook book = store.book(SERVER);
		Note local = store.create(book, 100);
		store.update(book, local, "lokal", "", 200);
		Note older = local.copy();
		older.title = "alt";
		older.updated = 150;
		assertFalse(store.applyRemote(SERVER, older));
		assertEquals("lokal", book.byId(local.id).title);
		Note newer = local.copy();
		newer.title = "neu";
		newer.updated = 300;
		assertTrue(store.applyRemote(WORLD, newer));
		assertNull(book.byId(local.id));
		assertEquals("neu", store.existing(WORLD.key()).byId(local.id).title);
		// Unbekannter Grabstein: nichts anlegen.
		Note ghost = Note.create(50);
		ghost.deleted = true;
		assertFalse(store.applyRemote(SERVER, ghost));
	}

	@Test
	void temporaryWaypointsAreNeverSaved() throws IOException {
		Path file = tmp.resolve("wp.json");
		WaypointStore wps = new WaypointStore(file);
		Waypoint keep = wps.add("mp:x", new Waypoint("Basis", 1, 2, 3, "", 0xFF0000));
		Waypoint temp = new Waypoint("Notiz", 4, 5, 6, "", 0x00FF00);
		temp.temporary = true;
		wps.add("mp:x", temp);
		wps.save();
		String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		assertTrue(json.contains("Basis"));
		assertFalse(json.contains("Notiz"));
		assertFalse(json.contains("temporary"));
		WaypointStore again = new WaypointStore(file);
		again.load();
		assertEquals(1, again.all("mp:x").size());
		assertEquals(keep.name, again.all("mp:x").get(0).name);
	}

	// --- Zusammenführen ---

	private static NotesStore.Entry entry(NoteWorld w, String id, long updated, boolean deleted) {
		Note n = new Note();
		n.id = id;
		n.title = deleted ? "" : "t" + updated;
		n.created = 1;
		n.updated = updated;
		n.deleted = deleted;
		return new NotesStore.Entry(w, n);
	}

	private static NotesSyncApi.Remote remote(NoteWorld w, String id, long updated, boolean deleted) {
		NotesStore.Entry e = entry(w, id, updated, deleted);
		return new NotesSyncApi.Remote(e.world, e.note);
	}

	@Test
	void planTakesNewerAndUploadsOwnChanges() {
		String a = "000000000000000a", b = "000000000000000b", c = "000000000000000c", d = "000000000000000d", e = "000000000000000e";
		List<NotesStore.Entry> local = new ArrayList<NotesStore.Entry>();
		local.add(entry(SERVER, a, 100, false)); // Konto hat neuer → übernehmen
		local.add(entry(SERVER, b, 300, false)); // lokal neuer → hochladen
		local.add(entry(SERVER, c, 50, false)); // Konto kennt es nicht → hochladen
		local.add(entry(SERVER, d, 70, true)); // Grabstein, Konto kannte es nie → nichts
		NotesMerge.Account acc = new NotesMerge.Account();
		List<NotesSyncApi.Remote> pulled = new ArrayList<NotesSyncApi.Remote>();
		pulled.add(remote(SERVER, a, 200, false));
		pulled.add(remote(SERVER, b, 250, false));
		pulled.add(remote(WORLD, e, 10, false)); // neu vom Konto
		NotesMerge.Plan plan = NotesMerge.plan(local, acc, pulled);
		List<String> applied = new ArrayList<String>();
		for (NotesSyncApi.Remote r : plan.apply) applied.add(r.note.id);
		List<String> uploaded = new ArrayList<String>();
		for (NotesStore.Entry x : plan.upload) uploaded.add(x.note.id);
		Collections.sort(applied);
		Collections.sort(uploaded);
		assertEquals(java.util.Arrays.asList(a, e), applied);
		assertEquals(java.util.Arrays.asList(b, c), uploaded);

		// Nach erfolgreichem Hochladen: nichts mehr zu tun.
		List<NotesSyncApi.Result> results = new ArrayList<NotesSyncApi.Result>();
		for (NotesStore.Entry x : plan.upload) results.add(new NotesSyncApi.Result(x.note.id, NotesSyncApi.Result.OK, null));
		NotesMerge.results(acc, plan.upload, results, new ArrayList<NotesSyncApi.Remote>());
		List<NotesStore.Entry> after = new ArrayList<NotesStore.Entry>(local);
		after.set(0, entry(SERVER, a, 200, false));
		after.add(entry(WORLD, e, 10, false));
		assertTrue(NotesMerge.plan(after, acc, Collections.<NotesSyncApi.Remote>emptyList()).upload.isEmpty());

		// Löschen einer bekannten Notiz reist als Grabstein.
		after.set(1, entry(SERVER, b, 400, true));
		NotesMerge.Plan del = NotesMerge.plan(after, acc, Collections.<NotesSyncApi.Remote>emptyList());
		assertEquals(1, del.upload.size());
		assertTrue(del.upload.get(0).note.deleted);
	}

	@Test
	void staleAndLimitResults() {
		String a = "000000000000000a", b = "000000000000000b";
		NotesMerge.Account acc = new NotesMerge.Account();
		List<NotesStore.Entry> sent = new ArrayList<NotesStore.Entry>();
		sent.add(entry(SERVER, a, 100, false));
		sent.add(entry(SERVER, b, 100, false));
		List<NotesSyncApi.Result> res = new ArrayList<NotesSyncApi.Result>();
		res.add(new NotesSyncApi.Result(a, NotesSyncApi.Result.STALE, remote(SERVER, a, 500, false)));
		res.add(new NotesSyncApi.Result(b, NotesSyncApi.Result.LIMIT, null));
		List<NotesSyncApi.Remote> apply = new ArrayList<NotesSyncApi.Remote>();
		NotesMerge.results(acc, sent, res, apply);
		assertEquals(1, apply.size());
		assertEquals(500L, acc.synced.get(a).longValue());
		assertEquals(NotesSyncApi.Result.LIMIT, acc.rejectReason);
		// Abgelehnte Fassung wird nicht erneut geschickt – erst nach einer Änderung.
		List<NotesStore.Entry> local = new ArrayList<NotesStore.Entry>();
		local.add(entry(SERVER, b, 100, false));
		assertTrue(NotesMerge.plan(local, acc, Collections.<NotesSyncApi.Remote>emptyList()).upload.isEmpty());
		local.set(0, entry(SERVER, b, 101, false));
		assertEquals(1, NotesMerge.plan(local, acc, Collections.<NotesSyncApi.Remote>emptyList()).upload.size());
	}

	@Test
	void batchesRespectCountAndSize() {
		List<NotesStore.Entry> many = new ArrayList<NotesStore.Entry>();
		for (int i = 0; i < 120; i++) many.add(entry(SERVER, String.format("%016x", i + 1), 10, false));
		List<List<NotesStore.Entry>> b = NotesMerge.batches(many);
		assertEquals(3, b.size());
		assertEquals(NotesSyncApi.MAX_BATCH, b.get(0).size());
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < Note.MAX_TEXT; i++) big.append('ä');
		List<NotesStore.Entry> heavy = new ArrayList<NotesStore.Entry>();
		for (int i = 0; i < 30; i++) {
			NotesStore.Entry e = entry(SERVER, String.format("%016x", i + 1), 10, false);
			e.note.text = big.toString();
			heavy.add(e);
		}
		for (List<NotesStore.Entry> batch : NotesMerge.batches(heavy)) {
			int bytes = 0;
			for (NotesStore.Entry e : batch) bytes += NotesSyncApi.size(e);
			assertTrue(bytes <= NotesSyncApi.MAX_BATCH_BYTES, "Paket zu groß: " + bytes);
		}
	}

	@Test
	void jsonRoundTripOfRemoteNotes() {
		NotesStore.Entry e = entry(WORLD, "00000000000000ff", 1234567, false);
		e.note.text = "[ ] a\nb";
		JsonObject o = NotesSyncApi.change(e);
		o.addProperty("updatedAt", SyncTime.iso(1234567));
		NotesSyncApi.Remote r = NotesSyncApi.remote(o);
		assertNotNull(r);
		assertEquals(WORLD, r.world);
		assertEquals("[ ] a\nb", r.note.text);
		assertEquals(1234567, r.note.updated);
		NotesStore.Entry del = entry(SERVER, "00000000000000fe", 99, true);
		JsonObject d = NotesSyncApi.change(del);
		assertTrue(d.get("deleted").getAsBoolean());
		assertNull(d.get("text"));
		assertTrue(NotesSyncApi.remote(d).note.deleted);
	}

	// --- Sync gegen die Attrappe ---

	/** In-Memory-Attrappe des vorgeschlagenen Vertrags (API.md §17.5). */
	static final class FakeNotesServer implements Http {
		boolean supported = true;
		final Map<String, JsonObject> notes = new LinkedHashMap<String, JsonObject>();
		final Map<String, Long> seq = new LinkedHashMap<String, Long>();
		long counter;
		int posts;
		int gets;

		@Override
		@SuppressWarnings("deprecation")
		public Response send(Request request) throws IOException {
			if (!supported) return json(404, "{\"error\":{\"code\":\"not_found\"}}");
			String url = request.url;
			if (request.method.equals("GET") && url.contains(NotesSyncApi.PATH)) {
				gets++;
				long since = 0;
				int i = url.indexOf("since=");
				if (i >= 0) since = Long.parseLong(url.substring(i + 6).split("&")[0]);
				JsonArray arr = new JsonArray();
				for (Map.Entry<String, JsonObject> e : notes.entrySet()) {
					if (seq.get(e.getKey()) > since) arr.add(e.getValue());
				}
				JsonObject body = new JsonObject();
				body.add("notes", arr);
				body.addProperty("cursor", String.valueOf(counter));
				body.addProperty("more", false);
				return json(200, body.toString());
			}
			if (request.method.equals("POST") && url.endsWith(NotesSyncApi.PATH)) {
				posts++;
				JsonObject in = new JsonParser().parse(new String(request.body, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonArray results = new JsonArray();
				for (JsonElement c : in.getAsJsonArray("changes")) {
					JsonObject ch = c.getAsJsonObject();
					String id = ch.get("id").getAsString();
					JsonObject cur = notes.get(id);
					JsonObject r = new JsonObject();
					r.addProperty("id", id);
					if (cur != null && SyncTime.parse(cur.get("updatedAt").getAsString()) > SyncTime.parse(ch.get("updatedAt").getAsString())) {
						r.addProperty("status", "stale");
						r.add("current", cur);
					} else {
						notes.put(id, ch);
						seq.put(id, ++counter);
						r.addProperty("status", "ok");
					}
					results.add(r);
				}
				JsonObject body = new JsonObject();
				body.add("results", results);
				return json(200, body.toString());
			}
			return json(404, "{}");
		}

		void putRemote(NoteWorld w, String id, String title, long updated) {
			NotesStore.Entry e = entry(w, id, updated, false);
			e.note.title = title;
			notes.put(id, NotesSyncApi.change(e));
			seq.put(id, ++counter);
		}

		private static Response json(int status, String body) {
			return new Response(status, new LinkedHashMap<String, String>(), body.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static final class Pc {
		final TrsModules modules = new TrsModules();
		final NotesStore store;
		final NotesSync sync;
		long clock = 1_000_000;

		Pc(Path dir, FakeNotesServer server) {
			store = new NotesStore(dir.resolve("notes")).load(clock);
			modules.trsOnline.setEnabled(true);
			ClientSync.Online online = new ClientSync.Online() {
				@Override
				public boolean consent() {
					return true;
				}

				@Override
				public String token() {
					return "token";
				}

				@Override
				public String uuid() {
					return "0123456789abcdef0123456789abcdef";
				}

				@Override
				public void rejected(String token) {
				}
			};
			sync = new NotesSync(modules, online, new NotesSyncApi(server, "http://api"), dir.resolve("notes").resolve("sync-state.json"),
					DIRECT, null);
		}

		/** Zwei Ticks: Runde starten (läuft direkt) und Ergebnis einspielen. */
		void round() {
			Notes.forTest(modules, store);
			sync.syncNow();
			clock += 10_000;
			sync.tick(clock);
			sync.tick(clock + 1);
		}
	}

	@Test
	void twoPcsShareNotesAndDeletions() {
		FakeNotesServer server = new FakeNotesServer();
		Pc one = new Pc(tmp.resolve("one"), server);
		Pc two = new Pc(tmp.resolve("two"), server);
		NoteBook book = one.store.book(SERVER);
		Note n = one.store.create(book, 1000);
		one.store.update(book, n, "Farm", "[ ] Eisen", 2000);
		one.round();
		assertEquals(NotesSync.Status.SYNCED, one.sync.status());
		assertEquals(1, server.notes.size());

		two.round();
		NoteBook book2 = two.store.existing(SERVER.key());
		assertNotNull(book2);
		assertEquals("Farm", book2.byId(n.id).title);

		// PC 2 hakt ab (neuer), PC 1 ändert parallel älter → PC 2 gewinnt.
		Note n2 = book2.byId(n.id);
		two.store.update(book2, n2, "Farm", "[x] Eisen", 5000);
		one.store.update(book, book.byId(n.id), "Farm alt", "[ ] Eisen", 4000);
		two.round();
		one.round();
		assertEquals("Farm", one.store.existing(SERVER.key()).byId(n.id).title);
		assertEquals("[x] Eisen", one.store.existing(SERVER.key()).byId(n.id).text);

		// Löschen auf PC 1 → Grabstein auf PC 2.
		one.store.delete(one.store.existing(SERVER.key()), one.store.existing(SERVER.key()).byId(n.id), 6000);
		one.round();
		two.round();
		assertTrue(two.store.existing(SERVER.key()).byId(n.id).deleted);
		assertEquals(0, two.store.existing(SERVER.key()).liveCount());

		// Nichts geändert → kein weiterer POST.
		int posts = server.posts;
		one.round();
		two.round();
		assertEquals(posts, server.posts);
	}

	@Test
	void remoteNoteForUnknownWorldCreatesBook() {
		FakeNotesServer server = new FakeNotesServer();
		server.putRemote(WORLD, "00000000000000aa", "Anderswo", 1234);
		Pc pc = new Pc(tmp.resolve("pc"), server);
		pc.round();
		NoteBook b = pc.store.existing(WORLD.key());
		assertNotNull(b);
		assertEquals("Anderswo", b.byId("00000000000000aa").title);
		assertEquals("Neue Welt", b.world.label());
	}

	@Test
	void unsupportedApiKeepsNotesLocal() {
		FakeNotesServer server = new FakeNotesServer();
		server.supported = false;
		Pc pc = new Pc(tmp.resolve("pc"), server);
		NoteBook book = pc.store.book(SERVER);
		pc.store.update(book, pc.store.create(book, 1), "x", "y", 2);
		pc.round();
		assertEquals(NotesSync.Status.UNSUPPORTED, pc.sync.status());
		assertEquals(1, book.liveCount());
		// Kein erneuter Versuch vor Ablauf der Wartezeit – auch nicht nach „Jetzt synchronisieren“ in einer neuen Sitzung.
		int gets = server.gets;
		Pc again = new Pc(tmp.resolve("pc"), server);
		again.round();
		assertEquals(gets, server.gets);
		assertEquals(NotesSync.Status.UNSUPPORTED, again.sync.status());
	}

	@Test
	void syncSwitchOffMeansNoTraffic() {
		FakeNotesServer server = new FakeNotesServer();
		Pc pc = new Pc(tmp.resolve("pc"), server);
		pc.modules.notes.sync.set(false);
		NoteBook book = pc.store.book(SERVER);
		pc.store.create(book, 1);
		pc.round();
		assertEquals(NotesSync.Status.OFF, pc.sync.status());
		assertEquals(0, server.gets + server.posts);
	}
}
