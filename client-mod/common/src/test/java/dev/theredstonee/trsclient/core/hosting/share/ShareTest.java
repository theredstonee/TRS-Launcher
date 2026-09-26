package dev.theredstonee.trsclient.core.hosting.share;

import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Welt-Hosting mit Mods + Resource Pack: Mod-Liste (Einlesen, Vorauswahl, Bauen) und ihre PrÃ¼fung (gleiche Regeln wie
 * die API), gemerkte Einstellungen je Welt, Datei-Kanal (Freigabeliste, kein Pfad-Zugriff, Hash-/GrÃ¶ÃŸenprÃ¼fung,
 * Grenzen), Weiche Minecraft/Datei-Kanal und der lokale Pack-Endpunkt.
 */
class ShareTest {
	static final String A40 = rep('a', 40);
	static final String B128 = rep('b', 128);
	static final String C64 = rep('c', 64);

	static String rep(char c, int n) {
		char[] a = new char[n];
		Arrays.fill(a, c);
		return new String(a);
	}

	// --- Hilfen ---

	static Path jar(Path dir, String name, Map<String, String> entries) throws IOException {
		Path p = dir.resolve(name);
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
			for (Map.Entry<String, String> e : entries.entrySet()) {
				z.putNextEntry(new ZipEntry(e.getKey()));
				z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		return p;
	}

	static Map<String, String> m(String... kv) {
		Map<String, String> out = new LinkedHashMap<String, String>();
		for (int i = 0; i < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
		return out;
	}

	static String fabric(String id, String env, String... deps) {
		StringBuilder d = new StringBuilder();
		for (String x : deps) d.append(d.length() == 0 ? "" : ",").append('"').append(x).append("\":\"*\"");
		return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"name\":\"" + id.toUpperCase() + " Mod\",\"version\":\"1.0\""
				+ (env == null ? "" : ",\"environment\":\"" + env + "\"") + ",\"depends\":{\"minecraft\":\"*\"" + (deps.length > 0 ? "," : "")
				+ d + "}}";
	}

	static String sha(String alg, byte[] data) throws Exception {
		return ModScan.hex(MessageDigest.getInstance(alg).digest(data));
	}

	// --- SharedContent: gleiche Regeln wie die API ---

	static SharedContent.Mod host(String sha1, long size) {
		return new SharedContent.Mod("Eigene BlÃ¶cke", "1.0", "eigene-bloecke.jar", size, true, SharedContent.Source.HOST, null, null,
				sha1, null, C64, -1);
	}

	@Test
	void contentRulesMatchTheApi() {
		SharedContent.Mod mr = new SharedContent.Mod("Sodium", "0.6", "sodium.jar", 1000, false, SharedContent.Source.MODRINTH,
				"AANobbMI", "Yp8wLY1P", A40, B128, C64, -1);
		SharedContent.Mod cf = new SharedContent.Mod("JEI", "19", "jei.jar", 1000, true, SharedContent.Source.CURSEFORGE, "238222",
				"5846800", rep('d', 40), null, null, 123L);
		assertNull(new SharedContent(Arrays.asList(mr, cf, host(rep('e', 40), 5)), null).problem());
		// Modrinth braucht SHA-512 und 8-stellige IDs, CurseForge Zahlen, Host SHA-256 + â‰¤ 64 MB.
		assertEquals("sha512", new SharedContent.Mod("x", "", "x.jar", 1, true, SharedContent.Source.MODRINTH, "AANobbMI",
				"Yp8wLY1P", A40, null, null, -1).problem());
		assertEquals("fileId", new SharedContent.Mod("x", "", "x.jar", 1, true, SharedContent.Source.CURSEFORGE, "1", "abc", A40,
				null, null, -1).problem());
		assertEquals("sha256", new SharedContent.Mod("x", "", "x.jar", 1, true, SharedContent.Source.HOST, null, null, A40, null,
				null, -1).problem());
		assertEquals("size", host(A40, SharedContent.MAX_HOST_FILE + 1).problem());
		assertEquals("projectId", new SharedContent.Mod("x", "", "x.jar", 1, true, SharedContent.Source.MANUAL, "1", null, A40, null,
				null, -1).problem());
		// Pfade/Traversal/Steuerzeichen im Dateinamen.
		for (String bad : new String[] { "../evil.jar", "mods/evil.jar", "C:\\evil.jar", "evil.zip", ".hidden.jar", "a..b.jar" }) {
			assertFalse(SharedContent.validFileName(bad), bad);
		}
		assertTrue(SharedContent.validFileName("fabric-api-0.119.2+1.21.11.jar"));
		assertEquals("name", new SharedContent.Mod("BÃ¶se\u202eName", "", "x.jar", 1, true, SharedContent.Source.MANUAL, null, null,
				A40, null, null, -1).problem());
		// Doppelt, zu viele, Host-Summe.
		assertEquals("duplicate", new SharedContent(Arrays.asList(host(A40, 1), host(A40, 2)), null).problem());
		java.util.List<SharedContent.Mod> many = new java.util.ArrayList<SharedContent.Mod>();
		for (int i = 0; i < 9; i++) many.add(host(String.format("%040x", i), 60L * 1024 * 1024));
		assertEquals("host_total", new SharedContent(many, null).problem());
		java.util.List<SharedContent.Mod> tooMany = new java.util.ArrayList<SharedContent.Mod>();
		for (int i = 0; i <= SharedContent.MAX_MODS; i++) {
			tooMany.add(new SharedContent.Mod("m", "", "m.jar", 1, false, SharedContent.Source.MANUAL, null, null, String.format("%040x", i),
					null, null, -1));
		}
		assertEquals("too_many_mods", new SharedContent(tooMany, null).problem());
		assertEquals("size", new SharedContent.Pack("p", SharedContent.MAX_PACK + 1, A40, C64).problem());
	}

	@Test
	@SuppressWarnings("deprecation")
	void parseDropsWhatAForeignHostShouldNotSend() {
		String json = "{\"mods\":["
				+ "{\"name\":\"Gut\",\"version\":\"1\",\"file\":\"gut.jar\",\"size\":10,\"required\":true,\"source\":\"host\",\"sha1\":\"" + A40
				+ "\",\"sha256\":\"" + C64 + "\"},"
				+ "{\"name\":\"Pfad\",\"file\":\"../../evil.jar\",\"size\":10,\"required\":true,\"source\":\"host\",\"sha1\":\"" + rep('1', 40)
				+ "\",\"sha256\":\"" + C64 + "\"},"
				+ "{\"name\":\"GroÃŸ\",\"file\":\"big.jar\",\"size\":999999999999,\"required\":true,\"source\":\"host\",\"sha1\":\"" + rep('2', 40)
				+ "\",\"sha256\":\"" + C64 + "\"},"
				+ "{\"name\":\"Farbe \u00a7cRot\",\"file\":\"rot.jar\",\"size\":10,\"required\":false,\"source\":\"manual\",\"sha1\":\"" + rep('3', 40) + "\"},"
				+ "{\"name\":\"Doppelt\",\"file\":\"gut2.jar\",\"size\":10,\"required\":true,\"source\":\"manual\",\"sha1\":\"" + A40 + "\"},"
				+ "{\"name\":\"URL\",\"file\":\"u.jar\",\"size\":10,\"required\":true,\"source\":\"url\",\"sha1\":\"" + rep('4', 40) + "\"}"
				+ "],\"pack\":{\"name\":\"Pack\",\"size\":5,\"sha1\":\"" + A40 + "\",\"sha256\":\"zz\"}}";
		SharedContent c = SharedContent.parse(new JsonParser().parse(json));
		assertNotNull(c);
		assertEquals(2, c.mods.size());
		assertEquals("gut.jar", c.mods.get(0).file);
		assertEquals("Farbe Rot", c.mods.get(1).name, "Formatcodes werden entfernt");
		assertNull(c.pack, "Pack ohne gÃ¼ltigen SHA-256 fÃ¤llt weg");
		// Rundreise: was wir bauen, liest sich gleich zurÃ¼ck.
		SharedContent back = SharedContent.parse(new JsonParser().parse(c.json()));
		assertEquals(c.mods.size(), back.mods.size());
		assertEquals(c.mods.get(0).sha256, back.mods.get(0).sha256);
		SharedContent.Summary s = SharedContent.Summary.parse(new JsonParser().parse(
				"{\"mods\":12,\"required\":5,\"fromHost\":2,\"manual\":1,\"pack\":{\"name\":\"P\",\"size\":10,\"sha1\":\"" + A40 + "\"}}"));
		assertEquals(12, s.mods);
		assertEquals(5, s.required);
		assertTrue(s.hasPack());
		assertNull(SharedContent.Summary.parse(new JsonParser().parse("{\"mods\":0}")));
	}

	// --- Einlesen + Vorauswahl ---

	@Test
	void scanReadsMetadataHashesAndContent(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path content = jar(mods, "blocks.jar", m("fabric.mod.json", fabric("blocks", "*", "lib"),
				"assets/blocks/blockstates/ore.json", "{}"));
		jar(mods, "zoom.jar", m("fabric.mod.json", fabric("zoom", "client")));
		jar(mods, "lib.jar", m("fabric.mod.json", fabric("lib", "*")));
		jar(mods, "trs.jar", m("fabric.mod.json", fabric("trsclient", "client")));
		jar(mods, "fapi.jar", m("fabric.mod.json", fabric("fabric-api", "*")));
		jar(mods, "forge-client.jar", m("META-INF/mods.toml",
				"modLoader=\"javafml\"\n[[mods]]\nmodId=\"minimap\"\nversion=\"${file.jarVersion}\"\ndisplayName=\"Minimap\"\n"
						+ "displayTest=\"IGNORE_ALL_VERSION\"\n", "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 2.5\n"));
		jar(mods, "neo-items.jar", m("META-INF/neoforge.mods.toml",
				"[[mods]]\nmodId=\"gear\"\ndisplayName=\"Gear\"\nversion=\"3.1\"\n[[dependencies.gear]]\nmodId=\"lib\"\ntype=\"required\"\n",
				"data/gear/recipe/sword.json", "{}"));
		Files.write(mods.resolve(".hidden.jar"), new byte[] { 1 });
		Files.write(mods.resolve("notes.txt"), new byte[] { 1 });
		List<ModScan.LocalMod> list = ModScan.scan(mods);
		assertEquals(7, list.size());
		ModScan.LocalMod blocks = find(list, "blocks");
		byte[] raw = Files.readAllBytes(content);
		assertEquals(sha("SHA-1", raw), blocks.sha1);
		assertEquals(sha("SHA-512", raw), blocks.sha512);
		assertEquals(sha("SHA-256", raw), blocks.sha256);
		assertTrue(blocks.content);
		assertEquals(Collections.singleton("lib"), blocks.depends);
		assertTrue(find(list, "zoom").clientOnly());
		assertTrue(find(list, "minimap").clientOnly());
		assertEquals("2.5", find(list, "minimap").version);
		assertTrue(find(list, "gear").content);
		assertEquals(Collections.singleton("lib"), find(list, "gear").depends);
		assertFalse(find(list, "lib").content);
		// Gemerkt (gleiche Datei â†’ gleiches Objekt).
		assertTrue(ModScan.read(content) == blocks);
	}

	static ModScan.LocalMod find(List<ModScan.LocalMod> l, String id) {
		for (ModScan.LocalMod x : l) if (id.equals(x.id)) return x;
		throw new AssertionError(id);
	}

	static ShareModel.Row row(ShareModel model, String id) {
		for (ShareModel.Row r : model.rows()) if (id.equals(r.mod.id)) return r;
		return null;
	}

	@Test
	void defaultsSelectionAndBuild(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		jar(mods, "blocks.jar", m("fabric.mod.json", fabric("blocks", "*", "lib"), "assets/blocks/blockstates/ore.json", "{}"));
		jar(mods, "zoom.jar", m("fabric.mod.json", fabric("zoom", "client")));
		jar(mods, "lib.jar", m("fabric.mod.json", fabric("lib", "*")));
		jar(mods, "trs.jar", m("fabric.mod.json", fabric("trsclient", "client")));
		jar(mods, "fapi.jar", m("fabric.mod.json", fabric("fabric-api", "*")));
		jar(mods, "other.jar", m("fabric.mod.json", fabric("other", "*")));
		Path packs = Files.createDirectories(dir.resolve("resourcepacks"));
		Files.write(packs.resolve("SchÃ¶ne Texturen.zip"), "PK-pack".getBytes(StandardCharsets.UTF_8));
		List<ModScan.LocalMod> scanned = ModScan.scan(mods);
		Map<String, StoreLookup.Match> matches = new HashMap<String, StoreLookup.Match>();
		ModScan.LocalMod fapi = find(scanned, "fabric-api");
		matches.put(fapi.sha1, new StoreLookup.Match(fapi.sha1, SharedContent.Source.MODRINTH, "P7dR8mSH", "abcdEFGH", -1));

		ShareSettings store = new ShareSettings(dir.resolve("config/trsclient/hosting-share.json"));
		ShareModel model = new ShareModel(dir, "world-1", store);
		assertFalse(model.shareMods(), "ab Werk nichts teilen");
		assertFalse(model.direct());
		assertFalse(model.sharePack());
		model.apply(scanned, ShareModel.listPacks(packs), matches);
		assertNull(row(model, "trsclient"), "TRS Client steht nie in der Liste");
		assertTrue(row(model, "fabric-api").locked() && row(model, "fabric-api").required);
		assertTrue(row(model, "blocks").on && row(model, "blocks").required, "Inhalts-Mod = Pflicht");
		assertFalse(row(model, "zoom").on, "Client-Mod nicht gewÃ¤hlt");
		assertTrue(row(model, "lib").required && row(model, "lib").dependency, "AbhÃ¤ngigkeit einer Pflicht-Mod");
		assertTrue(row(model, "other").on && !row(model, "other").required);

		// Aus: nichts geteilt, keine Freigabe.
		ShareModel.Built off = ShareModel.build(new ShareSettings.World(), model.rows(), null);
		assertTrue(off.content.isEmpty());
		assertTrue(off.files.isEmpty());

		// Mods an, direkt aus: Nicht-Store-Mods nur als Hinweis, keine Datei-Freigabe.
		model.setShareMods(true);
		ShareModel.Built b = buildReady(model);
		assertNull(b.content.problem());
		assertEquals(SharedContent.Source.MODRINTH, byFile(b.content, "fapi.jar").source);
		assertEquals(SharedContent.Source.MANUAL, byFile(b.content, "blocks.jar").source);
		assertTrue(b.files.isEmpty());
		assertNull(byFile(b.content, "zoom.jar"));

		// Direkt an: vom Host + Freigabeliste mit genau diesen Dateien (per SHA-256).
		model.setDirect(true);
		model.toggle(row(model, "zoom"));
		b = buildReady(model);
		SharedContent.Mod blocks = byFile(b.content, "blocks.jar");
		assertEquals(SharedContent.Source.HOST, blocks.source);
		assertTrue(b.files.containsKey(blocks.sha256));
		assertFalse(byFile(b.content, "zoom.jar").required, "Client-Mod optional");
		assertEquals(4, b.files.size(), "blocks, lib, other, zoom â€“ nicht die Store-Mod");

		// Pack erst mit â€žResource Pack teilenâ€œ + Auswahl.
		assertNull(b.content.pack);
		model.setSharePack(true);
		model.choosePack("SchÃ¶ne Texturen.zip");
		b = buildReady(model);
		assertNotNull(b.content.pack);
		assertEquals("SchÃ¶ne Texturen", b.content.pack.name);
		FileServer.Entry pe = b.files.get(b.content.pack.sha256);
		assertEquals(FileChannel.KIND_PACK, pe.kind);

		// Gemerkt je Welt; andere Welt = alles aus.
		ShareSettings again = new ShareSettings(dir.resolve("config/trsclient/hosting-share.json"));
		ShareSettings.World w = again.get("world-1");
		assertTrue(w.shareMods && w.direct && w.sharePack);
		assertEquals("SchÃ¶ne Texturen.zip", w.packFile);
		assertTrue(w.choices.get(row(model, "zoom").mod.sha1).on);
		assertFalse(again.get("world-2").shareMods);
		ShareModel reopened = new ShareModel(dir, "world-1", again);
		reopened.apply(scanned, ShareModel.listPacks(packs), matches);
		assertTrue(row(reopened, "zoom").on, "Wahl wird wiederhergestellt");
	}

	static ShareModel.Built buildReady(ShareModel model) throws IOException {
		// apply() setzt keinen READY-Zustand (das macht poll()) â€“ fÃ¼r den Test direkt bauen.
		ShareSettings.World w = new ShareSettings.World();
		w.shareMods = model.shareMods();
		w.direct = model.direct();
		w.sharePack = model.sharePack();
		w.packFile = model.packFile();
		return ShareModel.build(w, model.rows(), model.selectedPack());
	}

	static SharedContent.Mod byFile(SharedContent c, String file) {
		for (SharedContent.Mod x : c.mods) if (x.file.equals(file)) return x;
		return null;
	}

	@Test
	void oddFileNamesAreCleanedOrSkipped() {
		assertEquals("my_mod_1.0.jar", ShareModel.fileName("my mod?1.0.jar"));
		assertEquals("kept (1).jar", ShareModel.fileName("kept (1).jar"));
		assertEquals("abc.jar", ShareModel.fileName("..abc.jar"));
		assertNull(ShareModel.fileName("???.jar"));
	}

	@Test
	void guestCheckComparesBySha1() {
		SharedContent c = new SharedContent(Arrays.asList(host(A40, 1),
				new SharedContent.Mod("Opt", "", "opt.jar", 1, false, SharedContent.Source.MANUAL, null, null, rep('5', 40), null, null, -1)),
				null);
		GuestCheck all = GuestCheck.of("h0123456789abcdef0123", c, new HashSet<String>(Arrays.asList(A40, rep('5', 40))));
		assertTrue(all.complete());
		GuestCheck none = GuestCheck.of("h0123456789abcdef0123", c, Collections.<String>emptySet());
		assertFalse(none.canJoinWithout(), "Pflicht fehlt â†’ nicht ohne Mods");
		assertEquals(1, none.missingFromHost());
		GuestCheck opt = GuestCheck.of("h0123456789abcdef0123", c, Collections.singleton(A40));
		assertTrue(opt.canJoinWithout());
		assertEquals(1, opt.missingOptional.size());
	}

	// --- Datei-Kanal ---

	static final class Guests implements FileServer.Access {
		final java.util.Set<String> ok = java.util.Collections.synchronizedSet(new HashSet<String>());

		@Override
		public boolean allowed(String guestUuid) {
			return ok.contains(guestUuid);
		}
	}

	static FileServer.Entry entry(int kind, Path p) throws Exception {
		byte[] raw = Files.readAllBytes(p);
		return new FileServer.Entry(kind, p, raw.length, Files.getLastModifiedTime(p).toMillis(), sha("SHA-256", raw));
	}

	/** Wie der Host: Weiche am ersten Byte, Datei-Kanal an den Server. */
	static FileClient connect(FileServer server, String guest, final List<String> mcSeen) throws IOException {
		SharePipe[] pipe = SharePipe.pair(PeerStream.Path.RELAY);
		final String g = guest;
		final FileServer s = server;
		FileChannel.route(pipe[1], new FileChannel.Route() {
			@Override
			public void minecraft(PeerStream stream) {
				mcSeen.add("mc");
				stream.close("test");
			}

			@Override
			public void files(PeerStream stream) {
				s.serve(stream, g);
			}
		});
		return new FileClient(pipe[0]);
	}

	@Test
	void fileChannelServesOnlyTheAllowlist(@TempDir Path dir) throws Exception {
		byte[] data = new byte[200_000];
		new java.util.Random(7).nextBytes(data);
		Path modFile = dir.resolve("mods").resolve("eigen.jar");
		Files.createDirectories(modFile.getParent());
		Files.write(modFile, data);
		Path secret = dir.resolve("options.txt");
		Files.write(secret, "geheim".getBytes(StandardCharsets.UTF_8));
		Guests access = new Guests();
		access.ok.add("gast");
		FileServer server = new FileServer(access);
		server.unthrottled = true;
		FileServer.Entry e = entry(FileChannel.KIND_MOD, modFile);
		Map<String, FileServer.Entry> allow = new HashMap<String, FileServer.Entry>();
		allow.put(e.sha256, e);
		server.share(allow);
		List<String> mc = new java.util.concurrent.CopyOnWriteArrayList<String>();

		try (FileClient fc = connect(server, "gast", mc)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			FileClient.Result r = fc.fetch(FileChannel.KIND_MOD, e.sha256, e.size, SharedContent.MAX_HOST_FILE, sha("SHA-1", data), out, null);
			assertArrayEquals(data, out.toByteArray());
			assertEquals(e.sha256, r.sha256);
			// Andere Dateien des Hosts: nur Ã¼ber den Hash erreichbar â€“ und der steht nicht in der Liste.
			String secretHash = sha("SHA-256", Files.readAllBytes(secret));
			FileClient.FileException ex = assertThrows(FileClient.FileException.class,
					() -> fc.fetch(FileChannel.KIND_MOD, secretHash, 6, 100, null, new ByteArrayOutputStream(), null));
			assertEquals("not_shared", ex.code);
			// Richtiger Hash, falsche Art (Pack statt Mod) â†’ nicht geteilt.
			ex = assertThrows(FileClient.FileException.class,
					() -> fc.fetch(FileChannel.KIND_PACK, e.sha256, e.size, SharedContent.MAX_PACK, null, new ByteArrayOutputStream(), null));
			assertEquals("not_shared", ex.code);
		}
		assertTrue(mc.isEmpty(), "Datei-Kanal geht nie an Minecraft");

		// AngekÃ¼ndigte GrÃ¶ÃŸe/Hash passen nicht â†’ Gast bricht ab.
		try (FileClient fc = connect(server, "gast", mc)) {
			FileClient.FileException ex = assertThrows(FileClient.FileException.class,
					() -> fc.fetch(FileChannel.KIND_MOD, e.sha256, e.size - 1, SharedContent.MAX_HOST_FILE, null, new ByteArrayOutputStream(), null));
			assertEquals("size", ex.code);
		}
		try (FileClient fc = connect(server, "gast", mc)) {
			FileClient.FileException ex = assertThrows(FileClient.FileException.class,
					() -> fc.fetch(FileChannel.KIND_MOD, e.sha256, e.size, SharedContent.MAX_HOST_FILE, rep('0', 40), new ByteArrayOutputStream(), null));
			assertEquals("hash", ex.code);
		}

		// Nicht angenommen â†’ abgewiesen.
		try (FileClient fc = connect(server, "fremd", mc)) {
			FileClient.FileException ex = assertThrows(FileClient.FileException.class,
					() -> fc.fetch(FileChannel.KIND_MOD, e.sha256, e.size, SharedContent.MAX_HOST_FILE, null, new ByteArrayOutputStream(), null));
			assertEquals("not_allowed", ex.code);
		}

		// Datei nach der Freigabe geÃ¤ndert â†’ nicht mehr geliefert.
		Files.write(modFile, new byte[] { 1, 2, 3 });
		try (FileClient fc = connect(server, "gast", mc)) {
			FileClient.FileException ex = assertThrows(FileClient.FileException.class,
					() -> fc.fetch(FileChannel.KIND_MOD, e.sha256, e.size, SharedContent.MAX_HOST_FILE, null, new ByteArrayOutputStream(), null));
			assertEquals("changed", ex.code);
		}

		// Teilen aus â†’ nichts mehr.
		server.stop();
		try (FileClient fc = connect(server, "gast", mc)) {
			FileClient.FileException ex = assertThrows(FileClient.FileException.class,
					() -> fc.fetch(FileChannel.KIND_MOD, e.sha256, e.size, SharedContent.MAX_HOST_FILE, null, new ByteArrayOutputStream(), null));
			assertEquals("not_allowed", ex.code);
		}
	}

	@Test
	void fileChannelRateLimit(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("small.jar");
		Files.write(f, new byte[] { 9, 9, 9 });
		Guests access = new Guests();
		access.ok.add("gast");
		FileServer server = new FileServer(access);
		server.unthrottled = true;
		FileServer.Entry e = entry(FileChannel.KIND_MOD, f);
		server.share(Collections.singletonMap(e.sha256, e));
		List<String> mc = new java.util.ArrayList<String>();
		int ok = 0;
		String last = null;
		try (FileClient fc = connect(server, "gast", mc)) {
			for (int i = 0; i < FileServer.MAX_REQUESTS + 5; i++) {
				try {
					fc.fetch(FileChannel.KIND_MOD, e.sha256, 3, 100, null, new ByteArrayOutputStream(), null);
					ok++;
				} catch (FileClient.FileException ex) {
					last = ex.code;
				}
			}
		}
		assertTrue(ok <= FileServer.MAX_REQUESTS);
		assertEquals("rate_limited", last);
	}

	@Test
	void routeKeepsMinecraftBytes() throws Exception {
		SharePipe[] pipe = SharePipe.pair(PeerStream.Path.DIRECT);
		final java.util.concurrent.CompletableFuture<PeerStream> mc = new java.util.concurrent.CompletableFuture<PeerStream>();
		FileChannel.route(pipe[1], new FileChannel.Route() {
			@Override
			public void minecraft(PeerStream stream) {
				mc.complete(stream);
			}

			@Override
			public void files(PeerStream stream) {
				mc.completeExceptionally(new AssertionError("files"));
			}
		});
		byte[] handshake = { 0x10, 0x00, (byte) 0xF4, 0x05, 0x09 };
		pipe[0].write(handshake, 0, handshake.length);
		PeerStream s = mc.get(5, java.util.concurrent.TimeUnit.SECONDS);
		final ByteArrayOutputStream got = new ByteArrayOutputStream();
		final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
		s.start(new PeerStream.Sink() {
			@Override
			public void data(byte[] b, int off, int len) {
				got.write(b, off, len);
				if (got.size() >= handshake.length) done.countDown();
			}

			@Override
			public void closed(String reason) {
			}
		});
		assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
		assertArrayEquals(handshake, got.toByteArray(), "Minecraft bekommt alle Bytes unverÃ¤ndert");
	}

	// --- Lokaler Pack-Endpunkt (Gast) ---

	@Test
	void packEndpointServesOnlyItsTokenPathAndVerifies(@TempDir Path dir) throws Exception {
		final byte[] pack = "PK\u0003\u0004 test pack".getBytes(StandardCharsets.ISO_8859_1);
		Path pf = dir.resolve("pack.zip");
		Files.write(pf, pack);
		Guests access = new Guests();
		access.ok.add("gast");
		final FileServer server = new FileServer(access);
		server.unthrottled = true;
		FileServer.Entry e = entry(FileChannel.KIND_PACK, pf);
		server.share(Collections.singletonMap(e.sha256, e));
		final SharedContent.Pack info = new SharedContent.Pack("Pack", pack.length, sha("SHA-1", pack), e.sha256);
		assertEquals(info.sha1, PackServer.markerSha1(PackServer.marker(info.sha1)));
		assertNull(PackServer.markerSha1("http://example.com/pack.zip"));
		PackServer ps = PackServer.start(info.sha1, new PackServer.Resolver() {
			@Override
			public SharedContent.Pack pack() {
				return info;
			}
		}, new PackServer.Opener() {
			@Override
			public PeerStream open() {
				SharePipe[] pipe = SharePipe.pair(PeerStream.Path.DIRECT);
				FileChannel.route(pipe[1], new FileChannel.Route() {
					@Override
					public void minecraft(PeerStream stream) {
						stream.close("x");
					}

					@Override
					public void files(PeerStream stream) {
						server.serve(stream, "gast");
					}
				});
				return pipe[0];
			}
		});
		try {
			String url = ps.url();
			assertTrue(url.startsWith("http://127.0.0.1:"));
			java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
			assertEquals(200, c.getResponseCode());
			byte[] body = readAll(c.getInputStream());
			assertArrayEquals(pack, body);
			java.net.HttpURLConnection wrong = (java.net.HttpURLConnection) new java.net.URL(url.replace("/pack.zip", "/x.zip")).openConnection();
			assertEquals(404, wrong.getResponseCode());
			String other = url.substring(0, url.lastIndexOf('/', url.length() - 10)) + "/" + rep('0', 32) + "/pack.zip";
			assertEquals(404, ((java.net.HttpURLConnection) new java.net.URL(other).openConnection()).getResponseCode());
		} finally {
			ps.stop();
		}
		// Anderer SHA-1 als angekÃ¼ndigt â†’ 404 (Resolver liefert anderes Pack).
		PackServer mismatch = PackServer.start(rep('9', 40), new PackServer.Resolver() {
			@Override
			public SharedContent.Pack pack() {
				return info;
			}
		}, null);
		try {
			assertEquals(404, ((java.net.HttpURLConnection) new java.net.URL(mismatch.url()).openConnection()).getResponseCode());
		} finally {
			mismatch.stop();
		}
	}

	static byte[] readAll(java.io.InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] b = new byte[4096];
		int n;
		while ((n = in.read(b)) > 0) out.write(b, 0, n);
		in.close();
		return out.toByteArray();
	}

	@Test
	void settingsFileSurvivesGarbage(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("hosting-share.json");
		Files.write(f, "{kaputt".getBytes(StandardCharsets.UTF_8));
		ShareSettings s = new ShareSettings(f);
		assertFalse(s.get("w").shareMods);
		ShareSettings.World w = new ShareSettings.World();
		w.shareMods = true;
		w.packFile = "../evil.zip";
		s.put("w", w);
		ShareSettings back = new ShareSettings(f);
		assertTrue(back.get("w").shareMods);
		assertNull(back.get("w").packFile, "Pfad im Pack-Namen wird verworfen");
		assertFalse(ShareSettings.validPackFile("a/b.zip"));
		assertTrue(ShareSettings.validPackFile("Faithful 32x.zip"));
	}
}
