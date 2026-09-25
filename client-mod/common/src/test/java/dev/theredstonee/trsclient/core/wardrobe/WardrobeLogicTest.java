package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.skin.SkinModel;
import dev.theredstonee.trsclient.core.skin.SkinModelSpec;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Garderobe ohne Minecraft: UV-Aufteilung, Editor-Werkzeuge, PNG, Prüfungen, SSRF-Schutz, Dokument, Picking. */
class WardrobeLogicTest {
	private static int idx(int x, int y) {
		return y * 64 + x;
	}

	// --- SkinLayout ---

	@Test
	void layoutRegionsAndMirror() {
		for (boolean slim : new boolean[]{false, true}) {
			SkinLayout l = SkinLayout.of(slim);
			// Spiegeln zweimal = identisch, und Region-Layer bleibt gleich
			for (int i = 0; i < 4096; i++) {
				int m = l.mirror(i);
				if (m < 0) continue;
				assertEquals(i, l.mirror(m), "Spiegel ist keine Involution bei " + i);
				assertEquals(SkinLayout.layerOf(l.region(i % 64, i / 64)), SkinLayout.layerOf(l.region(m % 64, m / 64)));
			}
		}
		SkinLayout l = SkinLayout.of(false);
		// Kopf vorn: (8..15, 8..15) spiegelt in sich, links ↔ rechts
		assertEquals(idx(15, 8), l.mirror(idx(8, 8)));
		// Rechter Arm vorn (44,20) ↔ linker Arm vorn (36,52) + 3
		assertEquals(idx(39, 52), l.mirror(idx(44, 20)));
		// Rechtes Bein rechte Seite (0,20) ↔ linkes Bein linke Seite (16+4+4=24, 52) gespiegelt
		assertEquals(idx(27, 52), l.mirror(idx(0, 20)));
		// Kopf-Hut (zweite Ebene)
		assertEquals(SkinLayout.OVERLAY, SkinLayout.layerOf(l.region(40, 8)));
		assertEquals(-1, l.region(0, 0), "Ecke des Kopfbereichs gehört zu keiner Seite");
		// Slim: vierte Armspalte gehört zu nichts
		SkinLayout s = SkinLayout.of(true);
		assertEquals(-1, s.region(54, 20 + 4 - 4 + 4));
		assertTrue(s.region(44, 20) >= 0);
	}

	// --- SkinEditor ---

	@Test
	void brushOnlyPaintsActiveLayerAndUndoRedo() {
		SkinEditor e = new SkinEditor();
		e.load(new int[4096], false);
		e.setColor(0xFF112233);
		assertTrue(e.apply(8, 8));
		assertEquals(0xFF112233, e.pixel(8, 8));
		assertFalse(e.apply(40, 8), "Hut gehört zur zweiten Ebene");
		e.setLayer(SkinLayout.OVERLAY);
		assertTrue(e.apply(40, 8));
		assertTrue(e.canUndo());
		assertTrue(e.undo());
		assertEquals(0, e.pixel(40, 8));
		assertTrue(e.redo());
		assertEquals(0xFF112233, e.pixel(40, 8));
		assertTrue(e.dirty());
		assertEquals(0xFF112233, (int) e.recentColors().get(0));
	}

	@Test
	void mirrorAndFillStayInFace() {
		SkinEditor e = new SkinEditor();
		e.load(new int[4096], false);
		e.setMirror(true);
		e.setColor(0xFFFF0000);
		e.apply(44, 20);
		assertEquals(0xFFFF0000, e.pixel(39, 52), "gespiegelt auf den linken Arm");
		e.setMirror(false);
		e.setTool(SkinEditor.Tool.FILL);
		e.setColor(0xFF00FF00);
		e.apply(8, 8); // Kopf vorn, 8×8
		int count = 0;
		for (int i = 0; i < 4096; i++) if (e.pixels()[i] == 0xFF00FF00) count++;
		assertEquals(64, count, "Füllen bleibt in der Kopf-Vorderseite");
		// Pipette übernimmt Farbe und schaltet zum Pinsel
		e.setTool(SkinEditor.Tool.PICKER);
		assertTrue(e.apply(44, 20));
		assertEquals(0xFFFF0000, e.color());
		assertEquals(SkinEditor.Tool.BRUSH, e.tool());
		// Radierer
		e.setTool(SkinEditor.Tool.ERASER);
		e.apply(8, 8);
		assertEquals(0, e.pixel(8, 8));
	}

	@Test
	void strokeIsOneUndoStepAndBrushSize() {
		SkinEditor e = new SkinEditor();
		e.load(new int[4096], false);
		e.setColor(0xFF0000FF);
		e.beginStroke();
		e.line(8, 8, 15, 8);
		e.endStroke();
		for (int x = 8; x <= 15; x++) assertEquals(0xFF0000FF, e.pixel(x, 8));
		e.undo();
		for (int x = 8; x <= 15; x++) assertEquals(0, e.pixel(x, 8));
		assertFalse(e.canUndo());
		e.setBrushSize(3);
		e.apply(8, 8); // Rand der Seite: nur Texel derselben Seite
		assertEquals(0xFF0000FF, e.pixel(9, 9));
		assertEquals(0, e.pixel(7, 8), "nicht in die Nachbarseite");
	}

	@Test
	void hexParsing() {
		assertEquals(0xFFAABBCC, (int) SkinEditor.parseHex("#aabbcc"));
		assertEquals(0xFFAABBCC, (int) SkinEditor.parseHex("#abc"));
		assertEquals(0xFF112233, (int) SkinEditor.parseHex("#123"));
		assertEquals(0x80112233, (int) SkinEditor.parseHex("80112233"));
		assertNull(SkinEditor.parseHex("#12345"));
		assertNull(SkinEditor.parseHex("xyz"));
		assertEquals("#0A0B0C", SkinEditor.hex(0xFF0A0B0C));
	}

	@Test
	void blankTemplatesAreOpaqueBase() {
		for (boolean slim : new boolean[]{false, true}) {
			int[] px = SkinEditor.blankTemplate(slim);
			SkinLayout l = SkinLayout.of(slim);
			for (int i = 0; i < 4096; i++) {
				int r = l.region(i % 64, i / 64);
				if (r >= 0 && SkinLayout.layerOf(r) == SkinLayout.BASE) assertEquals(0xFF, px[i] >>> 24);
				if (r >= 0 && SkinLayout.layerOf(r) == SkinLayout.OVERLAY) assertEquals(0, px[i]);
			}
		}
	}

	// --- PNG + Prüfungen ---

	@Test
	void pngRoundTripKeepsTransparentColours() throws Exception {
		int[] px = new int[4096];
		for (int i = 0; i < px.length; i++) px[i] = (i * 2654435761L) % 7 == 0 ? 0x00123456 : 0xFF000000 | (i * 97);
		byte[] png = PngWriter.write(64, 64, px);
		PngDecoder.Image img = PngDecoder.decode(png);
		assertEquals(64, img.width);
		assertArrayEquals(px, img.argb);
		SkinFiles.Decoded d = SkinFiles.decode(png);
		assertFalse(d.legacy);
		assertArrayEquals(px, d.pixels);
	}

	@Test
	void skinFileChecks() {
		assertEquals("not_png", assertThrows(SkinFiles.SkinException.class, () -> SkinFiles.decode("hallo".getBytes())).code);
		assertEquals("too_large", assertThrows(SkinFiles.SkinException.class, () -> SkinFiles.decode(new byte[SkinFiles.MAX_BYTES + 1])).code);
		assertEquals("invalid_size", assertThrows(SkinFiles.SkinException.class,
				() -> SkinFiles.decode(PngWriter.write(32, 32, new int[32 * 32]))).code);
		byte[] broken = PngWriter.write(64, 64, new int[4096]);
		broken[40] ^= 0x55;
		assertThrows(SkinFiles.SkinException.class, () -> SkinFiles.decode(broken));
		// 64×32 wird ins neue Format gebracht
		assertTrue(assertDoesNotThrowDecode(PngWriter.write(64, 32, new int[64 * 32])).legacy);
		// Slim-Erkennung
		int[] slim = SkinEditor.blankTemplate(true);
		assertTrue(SkinFiles.looksSlim(slim));
		assertFalse(SkinFiles.looksSlim(SkinEditor.blankTemplate(false)));
		// Namen und IDs
		assertEquals("Mein Skin", SkinFiles.cleanName("  Mein" + (char) 0 + " Skin" + (char) 0x202E + " ", "x"));
		assertEquals("x", SkinFiles.cleanName(String.valueOf((char) 7), "x"));
		assertEquals(48, SkinFiles.cleanName(new String(new char[100]).replace('\0', 'a'), "x").length());
		assertEquals("mein_skin", SkinFiles.nameFromFile("C:\\skins\\mein_skin.png", "x"));
		assertTrue(SkinFiles.validId(SkinFiles.newId()));
		assertFalse(SkinFiles.validId("ABCDEF123456"));
	}

	private static SkinFiles.Decoded assertDoesNotThrowDecode(byte[] png) {
		try {
			return SkinFiles.decode(png);
		} catch (SkinFiles.SkinException e) {
			throw new AssertionError(e.code);
		}
	}

	// --- SSRF-Schutz ---

	@Test
	void onlyPublicAddresses() throws Exception {
		String[] blocked = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
				"224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fc00::1", "fd12::1", "::ffff:127.0.0.1",
				"::ffff:10.0.0.1", "64:ff9b::a00:1", "2002:c0a8:0101::1", "2001:db8::1", "198.18.0.1"};
		for (String a : blocked) assertFalse(SafeFetch.isPublic(InetAddress.getByName(a)), a);
		String[] ok = {"8.8.8.8", "104.16.0.1", "2606:4700::1111", "::ffff:8.8.8.8"};
		for (String a : ok) assertTrue(SafeFetch.isPublic(InetAddress.getByName(a)), a);
		assertEquals("https_only", assertThrows(SafeFetch.FetchException.class, () -> SafeFetch.check("http://example.com/a.png")).code);
		assertEquals("invalid_url", assertThrows(SafeFetch.FetchException.class, () -> SafeFetch.check("https://user:pw@example.com/")).code);
		assertEquals("https_only", assertThrows(SafeFetch.FetchException.class, () -> SafeFetch.check("file:///etc/passwd")).code);
		assertNotNull(SafeFetch.check("https://example.com/skin.png?x=1"));
	}

	/** Verbindung, die eine feste Antwort liefert. */
	private static Socket fakeSocket(final String response) {
		return new Socket() {
			final ByteArrayInputStream in = new ByteArrayInputStream(response.getBytes(StandardCharsets.ISO_8859_1));
			final ByteArrayOutputStream out = new ByteArrayOutputStream();

			@Override
			public InputStream getInputStream() {
				return in;
			}

			@Override
			public OutputStream getOutputStream() {
				return out;
			}

			@Override
			public void setSoTimeout(int timeout) {
			}

			@Override
			public synchronized void close() {
			}
		};
	}

	@Test
	void fetchFollowsRedirectsAndRechecksAddresses() throws Exception {
		final byte[] png = PngWriter.write(64, 64, SkinEditor.blankTemplate(false));
		SafeFetch.Resolver resolver = host -> {
			if (host.equals("evil.example")) return new InetAddress[]{InetAddress.getByName("192.168.0.10")};
			if (host.equals("mixed.example")) return new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")};
			return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
		};
		SafeFetch.Connector connector = (address, host, port) -> {
			if (host.equals("start.example")) return fakeSocket("HTTP/1.1 302 Found\r\nLocation: https://cdn.example/s.png\r\n\r\n");
			if (host.equals("toevil.example")) return fakeSocket("HTTP/1.1 301 Moved\r\nLocation: https://evil.example/x\r\n\r\n");
			if (host.equals("chunked.example")) {
				String body = new String(png, StandardCharsets.ISO_8859_1);
				String half = body.substring(0, 100);
				String rest = body.substring(100);
				return fakeSocket("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: image/png\r\n\r\n"
						+ Integer.toHexString(half.length()) + "\r\n" + half + "\r\n" + Integer.toHexString(rest.length()) + "\r\n" + rest
						+ "\r\n0\r\n\r\n");
			}
			if (host.equals("big.example")) return fakeSocket("HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n");
			return fakeSocket("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " + png.length + "\r\n\r\n"
					+ new String(png, StandardCharsets.ISO_8859_1));
		};
		SafeFetch f = new SafeFetch(resolver, connector, "test");
		SafeFetch.Result r = f.get("https://start.example/a", SkinFiles.MAX_BYTES);
		assertArrayEquals(png, r.body);
		assertEquals("https://cdn.example/s.png", r.finalUrl);
		assertArrayEquals(png, f.get("https://chunked.example/", SkinFiles.MAX_BYTES).body);
		assertEquals("blocked_address", assertThrows(SafeFetch.FetchException.class, () -> f.get("https://toevil.example/", 1000)).code);
		assertEquals("blocked_address", assertThrows(SafeFetch.FetchException.class, () -> f.get("https://mixed.example/", 1000)).code);
		assertEquals("too_large", assertThrows(SafeFetch.FetchException.class, () -> f.get("https://big.example/", 1000)).code);
	}

	// --- Dokument ---

	@Test
	void docRoundTripAndValidation() {
		WardrobeDoc d = WardrobeDoc.EMPTY.toggleFavorite("aaaaaaaaaaaa").toggleFavorite("bbbbbbbbbbbb")
				.addOutfit(new WardrobeDoc.Outfit("cccccccccccc", "Rot", "aaaaaaaaaaaa", "trs:redstone", null))
				.setEmoteSlot(0, "winken").setEmoteSlot(2, "tanzen");
		assertEquals(Arrays.asList("bbbbbbbbbbbb", "aaaaaaaaaaaa"), d.favorites);
		assertEquals(Arrays.asList("winken", "", "tanzen"), d.emoteSlots);
		WardrobeDoc back = WardrobeDoc.fromJson(new JsonParser().parse(d.toJson().toString()).getAsJsonObject());
		assertEquals(d.favorites, back.favorites);
		assertEquals("trs:redstone", back.outfits.get(0).cape);
		assertEquals(d.emoteSlots, back.emoteSlots);
		// Doppeltes Emote wandert
		assertEquals(Arrays.asList("", "", "winken"), d.setEmoteSlot(2, "winken").emoteSlots);
		// Unfug vom Server fällt weg
		JsonObject junk = new JsonParser().parse("{\"favorites\":[\"x\",\"aaaaaaaaaaaa\",5,\"aaaaaaaaaaaa\"],"
				+ "\"outfits\":[{\"id\":\"zz\"},{\"id\":\"dddddddddddd\",\"name\":\"\\t\",\"cape\":\"http://x\"}],"
				+ "\"emoteSlots\":[\"../x\",\"winken\",\"winken\"]}").getAsJsonObject();
		WardrobeDoc j = WardrobeDoc.fromJson(junk);
		assertEquals(Arrays.asList("aaaaaaaaaaaa"), j.favorites);
		assertEquals(1, j.outfits.size());
		assertNull(j.outfits.get(0).cape);
		assertEquals("Outfit", j.outfits.get(0).name);
		assertEquals(Arrays.asList("", "winken"), j.emoteSlots);
		// Gelöschter Skin verschwindet überall
		WardrobeDoc f = d.forgetSkin("aaaaaaaaaaaa");
		assertEquals(Arrays.asList("bbbbbbbbbbbb"), f.favorites);
		assertNull(f.outfits.get(0).skin);
		assertTrue(WardrobeDoc.validCape("mojang:2340c0e0-3dd2-4d4c-9b6f-7c9c4f3a2b1c"));
		assertFalse(WardrobeDoc.validCape("trs:../x"));
	}

	// --- Mojang ---

	@Test
	void mojangProfileParsing() throws Exception {
		MojangServices.Profile p = MojangServices.parseProfile("{\"id\":\"5ce0000000000000000000000000abcd\",\"name\":\"Skinny\","
				+ "\"skins\":[{\"id\":\"1\",\"state\":\"ACTIVE\",\"url\":\"http://textures.minecraft.net/texture/abc\",\"variant\":\"SLIM\"}],"
				+ "\"capes\":[{\"id\":\"2340c0e0-3dd2-4d4c-9b6f-7c9c4f3a2b1c\",\"state\":\"ACTIVE\",\"url\":\"http://textures.minecraft.net/texture/def\",\"alias\":\"Migrator\"},"
				+ "{\"id\":\"bad id\",\"state\":\"INACTIVE\",\"url\":\"http://evil/x\",\"alias\":\"x\"}]}");
		assertEquals("https://textures.minecraft.net/texture/abc", p.skinUrl);
		assertTrue(p.slim);
		assertEquals(1, p.capes.size());
		assertEquals("Migrator", p.activeCape().alias);
		assertEquals(MojangServices.SERVICES, MojangServices.base("http://evil.com", MojangServices.SERVICES));
		assertEquals("http://127.0.0.1:9000", MojangServices.base("http://127.0.0.1:9000", MojangServices.SERVICES));
	}

	// --- Figur: Picking und Pose ---

	@Test
	void pickFindsTheFaceUnderTheCursor() {
		SkinModel m = new SkinModel();
		SkinModelSpec spec = new SkinModelSpec();
		spec.skin = new TextureRef("skin", 64, 64);
		spec.pitch = 0f;
		spec.yaw = 0f;
		float scale = 4f;
		// Kopfmitte vorn: Füße bei y=200, Kopf 24..32 Pixel hoch → Bildschirm y = 200 - 28*4
		int texel = m.pick(100f, 200f, scale, spec, 100.5f, 200f - 28f * scale, 0);
		assertTrue(texel >= 0);
		int x = texel % 64;
		int y = texel / 64;
		assertTrue(x >= 8 && x < 16 && y >= 8 && y < 16, "Kopf vorn getroffen, war " + x + "," + y);
		// Zweite Ebene: Hut
		int hat = m.pick(100f, 200f, scale, spec, 100.5f, 200f - 28f * scale, 1);
		assertTrue(hat % 64 >= 40 && hat % 64 < 48 && hat / 64 >= 8 && hat / 64 < 16, "Hut vorn, war " + hat);
		// Daneben: nichts
		assertEquals(-1, m.pick(100f, 200f, scale, spec, 10f, 10f, 0));
		// Von hinten: Rückseite des Kopfes
		spec.yaw = 180f;
		int back = m.pick(100f, 200f, scale, spec, 100.5f, 200f - 28f * scale, 0);
		assertTrue(back % 64 >= 24 && back % 64 < 32 && back / 64 >= 8, "Kopf hinten, war " + back);
	}

	@Test
	void poseMovesArms() {
		final int[] faces = new int[2];
		Canvas c = new CountingCanvas(faces);
		SkinModel m = new SkinModel();
		SkinModelSpec spec = new SkinModelSpec();
		spec.skin = new TextureRef("skin", 64, 64);
		spec.pitch = 0f;
		float[] pose = SkinModel.restPose(false, null);
		spec.pose = pose;
		assertTrue(m.draw(c, 100f, 200f, 4f, spec));
		assertTrue(m.lastFaceCount() > 10);
		// Ruhehaltung: vor dem rechten Arm (x = -6, y = 16 Pixel über den Füßen) liegt seine Vorderseite
		int before = m.pick(100f, 200f, 4f, spec, 76f, 136f, 0);
		assertTrue(before % 64 >= 44 && before % 64 < 48 && before / 64 >= 20 && before / 64 < 32, "Arm vorn, war " + before);
		// Arm nach vorn gestreckt (xRot -90°): dort ist nichts mehr, auf Schulterhöhe sieht man die Hand (Unterseite)
		pose[2 * 6 + 3] = (float) (-Math.PI / 2);
		assertEquals(-1, m.pick(100f, 200f, 4f, spec, 76f, 136f, 0));
		int hand = m.pick(100f, 200f, 4f, spec, 76f, 112f, 0);
		assertTrue(hand % 64 >= 48 && hand % 64 < 52 && hand / 64 >= 16 && hand / 64 < 20, "Hand, war " + hand);
	}

	private static final class CountingCanvas implements Canvas {
		final int[] faces;

		CountingCanvas(int[] faces) {
			this.faces = faces;
		}

		@Override
		public void fill(int x1, int y1, int x2, int y2, int argb) {
		}

		@Override
		public void text(String text, int x, int y, int argb, boolean shadow) {
		}

		@Override
		public int textWidth(String text) {
			return text.length() * 6;
		}

		@Override
		public int lineHeight() {
			return 9;
		}

		@Override
		public String clip(String text, int maxWidth) {
			return text;
		}

		@Override
		public void flush() {
		}

		@Override
		public void scissor(int x1, int y1, int x2, int y2) {
		}

		@Override
		public void noScissor() {
		}

		@Override
		public void raise(float z) {
		}

		@Override
		public void push() {
		}

		@Override
		public void translate(float x, float y) {
		}

		@Override
		public void scale(float factor) {
		}

		@Override
		public void pop() {
		}

		@Override
		public boolean images() {
			return true;
		}

		@Override
		public void image(TextureRef texture, float u, float v, int w, int h, int argb) {
			faces[0]++;
		}

		@Override
		public void scale(float sx, float sy) {
		}
	}
}
