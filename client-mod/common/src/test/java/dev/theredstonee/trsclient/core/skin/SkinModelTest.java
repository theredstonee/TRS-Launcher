package dev.theredstonee.trsclient.core.skin;

import dev.theredstonee.trsclient.core.online.GameSession;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spielerfigur ohne Spieler: Zerlegung der 2D-Abbildung, Sichtbarkeit, UV, Skin-Aufbereitung, eigener Skin. */
class SkinModelTest {
	@AfterEach
	void noStore() {
		Textures.replaceForTests(null);
	}

	// --- Affine ---

	@Test
	void decompositionRebuildsTheMatrix() {
		float[][] cases = {{1, 0, 0, 1}, {2, 0, 0, 3}, {0.7f, 0.7f, -0.7f, 0.7f}, {1.5f, 0.3f, 0.8f, 2.1f}, {0.2f, -1.3f, 1.1f, 0.4f}};
		for (float[] m : cases) {
			float[] s = Affine.decompose(m[0], m[1], m[2], m[3]);
			assertNotNull(s);
			assertTrue(s[1] > 0 && s[2] > 0, "Skalierung positiv");
			// R(theta) · diag(sx, sy) · R(phi)
			double ct = Math.cos(s[0]);
			double st = Math.sin(s[0]);
			double cp = Math.cos(s[3]);
			double sp = Math.sin(s[3]);
			double a = ct * s[1] * cp - st * s[2] * sp;
			double c = -ct * s[1] * sp - st * s[2] * cp;
			double b = st * s[1] * cp + ct * s[2] * sp;
			double d = -st * s[1] * sp + ct * s[2] * cp;
			assertEquals(m[0], a, 1e-4);
			assertEquals(m[1], b, 1e-4);
			assertEquals(m[2], c, 1e-4);
			assertEquals(m[3], d, 1e-4);
		}
		assertNull(Affine.decompose(-1, 0, 0, 1), "gespiegelt");
		assertNull(Affine.decompose(1, 2, 2, 4), "entartet");
	}

	@Test
	void affineAppliesTranslateRotateScaleRotate() {
		RecordingCanvas c = new RecordingCanvas();
		assertTrue(Affine.apply(c, 2, 0, 0, 3, 10, 20));
		assertEquals(10f, c.tx, 1e-4);
		assertEquals(20f, c.ty, 1e-4);
		// Punkt (1, 1) → (12, 23)
		float[] p = c.map(1, 1);
		assertEquals(12f, p[0], 1e-3);
		assertEquals(23f, p[1], 1e-3);
		assertFalse(Affine.apply(new RecordingCanvas(), -1, 0, 0, 1, 0, 0));
	}

	// --- Figur ---

	@Test
	void frontViewShowsFrontFacesWithTheRightUv() {
		RecordingCanvas c = new RecordingCanvas();
		SkinModel model = new SkinModel();
		SkinModelSpec spec = spec();
		spec.pitch = 0f;
		spec.layers = false;
		assertTrue(model.draw(c, 100, 200, 4f, spec));
		// Gerade von vorn, ohne Neigung: je Körperteil genau die Vorderseite (Seiten/Oben sind hochkant)
		assertEquals(6, model.lastFaceCount(), c.images.toString());
		// Gesicht: UV (8, 8) 8×8, oben links bei (100 - 16, 200 - 128)
		Img face = c.find(8, 8);
		assertNotNull(face, c.images.toString());
		assertEquals(8, face.w);
		assertEquals(8, face.h);
		float[] tl = face.corner(0, 0);
		assertEquals(84f, tl[0], 0.01f);
		assertEquals(72f, tl[1], 0.01f);
		float[] br = face.corner(8, 8);
		assertEquals(116f, br[0], 0.01f);
		assertEquals(104f, br[1], 0.01f);
		// Körper vorn (20, 20) 8×12
		assertNotNull(c.find(20, 20));
		// Linker Arm (Classic) vorn (36, 52) 4×12, rechts vom Betrachter
		Img left = c.find(36, 52);
		assertNotNull(left);
		assertTrue(left.corner(0, 0)[0] > 100f);
	}

	@Test
	void backViewShowsBackFacesAndTheCape() {
		RecordingCanvas c = new RecordingCanvas();
		SkinModel model = new SkinModel();
		SkinModelSpec spec = spec();
		spec.yaw = 180f;
		spec.pitch = 0f;
		spec.layers = false;
		spec.cape = new TextureRef("cape", 512, 256);
		assertTrue(model.draw(c, 100, 200, 4f, spec));
		// Kopf hinten (24, 8)
		assertNotNull(c.find(24, 8));
		assertNull(c.find(8, 8), "Gesicht nicht von hinten");
		// Umhang außen: Vorderseiten-UV (1, 1) × Faktor 8, 80×128 Texel
		Img cape = c.find(8, 8, "cape");
		assertNotNull(cape, c.images.toString());
		assertEquals(80, cape.w);
		assertEquals(128, cape.h);
		// Der Umhang liegt vor dem Körper (später gezeichnet)
		assertTrue(c.images.indexOf(cape) > c.images.indexOf(c.find(32, 20)));
	}

	@Test
	void slimArmsAndLayersAndTilt() {
		RecordingCanvas c = new RecordingCanvas();
		SkinModel model = new SkinModel();
		SkinModelSpec spec = spec();
		spec.slim = true;
		spec.yaw = 30f;
		spec.pitch = 15f;
		assertTrue(model.draw(c, 100, 200, 3f, spec));
		// Schräg von oben: Vorderseite, eine Seite und Oberseite je Teil – mit zweiter Ebene gut doppelt so viele
		assertTrue(model.lastFaceCount() > 24 && model.lastFaceCount() <= 42, "Flächen " + model.lastFaceCount());
		Img arm = c.find(44, 20);
		assertNotNull(arm, "rechter Arm vorn");
		assertEquals(3, arm.w, "schmaler Arm");
		// Alle Abbildungen sind orientierungserhaltend (keine Spiegelung → kein Culling in der Version)
		for (Img i : c.images) assertTrue(i.det() > 0, i.toString());
	}

	@Test
	void noTexturesMeansNoFigure() {
		SkinModel model = new SkinModel();
		Canvas plain = new RecordingCanvas() {
			@Override
			public boolean images() {
				return false;
			}
		};
		assertFalse(model.draw(plain, 0, 0, 2f, spec()));
		SkinModelSpec none = spec();
		none.skin = null;
		assertFalse(model.draw(new RecordingCanvas(), 0, 0, 2f, none));
	}

	@Test
	void fadeCanvasPassesImagesWithFadedTint() {
		RecordingCanvas c = new RecordingCanvas();
		Canvas f = FadeCanvas.of(c, 0.5f);
		assertTrue(f.images());
		f.image(new TextureRef("t", 64, 64), 0, 0, 8, 8, 0xFFFFFFFF);
		assertEquals(1, c.images.size());
		assertEquals(0x7F, c.images.get(0).argb >>> 24, 1);
	}

	// --- Skin-Aufbereitung ---

	@Test
	void legacySkinsAreConvertedLikeVanilla() {
		int[] px = new int[64 * 32];
		// rechtes Bein vorn (4..8, 20..32) – Spalte 4 rot, Rest blau
		for (int y = 20; y < 32; y++) {
			for (int x = 4; x < 8; x++) px[y * 64 + x] = x == 4 ? 0xFFFF0000 : 0xFF0000FF;
		}
		// Hut komplett deckend → wird durchsichtig
		for (int y = 0; y < 16; y++) for (int x = 32; x < 64; x++) px[y * 64 + x] = 0xFF00FF00;
		int[] out = SkinImage.normalize(64, 32, px);
		assertEquals(64 * 64, out.length);
		// linkes Bein vorn (20..24, 52..64): gespiegelt → rote Spalte rechts
		assertEquals(0xFFFF0000, out[52 * 64 + 23]);
		assertEquals(0xFF0000FF, out[52 * 64 + 20]);
		assertEquals(0, out[5 * 64 + 40] >>> 24, "Notch-Hack");
		// Grundebene deckend
		assertEquals(0xFF, out[0] >>> 24);
		assertTrue(SkinImage.validSize(64, 64));
		assertFalse(SkinImage.validSize(128, 128));
		assertEquals(64, CapeImage.width(22, 17));
		assertEquals(512, CapeImage.width(512, 256));
		assertEquals(0, CapeImage.width(100, 50));
		assertEquals(64 * 32, CapeImage.normalize(22, 17, new int[22 * 17]).length);
	}

	// --- Mojang-Profil ---

	@Test
	void profileParsingAcceptsOnlyTheTextureHost() {
		String textures = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123\",\"metadata\":{\"model\":\"slim\"}},"
				+ "\"CAPE\":{\"url\":\"http://evil.example/texture/abc\"}}}";
		String json = "{\"id\":\"x\",\"name\":\"Steve\",\"properties\":[{\"name\":\"textures\",\"value\":\""
				+ Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8)) + "\"}]}";
		MojangProfile p = MojangProfile.parse(json);
		assertNotNull(p);
		assertEquals("https://textures.minecraft.net/texture/abc123", p.skinUrl);
		assertTrue(p.slim);
		assertNull(p.capeUrl, "fremder Host");
		assertNull(MojangProfile.safeUrl("https://textures.minecraft.net:8443/texture/abc"));
		assertNull(MojangProfile.safeUrl("https://textures.minecraft.net/texture/abc?x=1"));
		assertNull(MojangProfile.safeUrl("file:///etc/passwd"));
		assertNull(MojangProfile.parse("kein json"));
		assertNull(MojangProfile.profileUrl("../../x"));
		assertEquals("https://sessionserver.mojang.com/session/minecraft/profile/0123456789abcdef0123456789abcdef",
				MojangProfile.profileUrl("01234567-89AB-CDEF-0123-456789ABCDEF"));
	}

	// --- Eigener Skin ---

	@Test
	void localSkinUsesDefaultThenCacheThenNetwork(@TempDir Path dir) throws Exception {
		FakeStore store = new FakeStore();
		Textures.replaceForTests(store);
		String uuid = "0123456789abcdef0123456789abcdef";
		byte[] png = png(64, 64, 0xFF336699);
		String textures = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/aa\"}}}";
		Map<String, byte[]> web = new HashMap<String, byte[]>();
		web.put(MojangProfile.profileUrl(uuid), ("{\"properties\":[{\"name\":\"textures\",\"value\":\""
				+ Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8)) + "\"}]}").getBytes(StandardCharsets.UTF_8));
		web.put("https://textures.minecraft.net/texture/aa", png);
		List<String> requests = new ArrayList<String>();
		Http http = req -> {
			requests.add(req.url);
			byte[] body = web.get(req.url);
			return new Http.Response(body == null ? 404 : 200, new HashMap<String, String>(), body);
		};
		List<Runnable> queue = new ArrayList<Runnable>();
		LocalSkin skin = new LocalSkin(dir, () -> new GameSession(uuid, "Steve", "token-123456789"), http, queue::add, m -> { });
		PlayerLook look = skin.look(0);
		assertEquals("Steve", look.name);
		assertEquals("default", look.skin.id);
		assertTrue(look.loading);
		assertEquals(1, queue.size());
		queue.get(0).run();
		look = skin.look(1);
		assertFalse(look.loading);
		assertTrue(look.ownSkin);
		assertEquals(LocalSkin.SKIN_TEXTURE, look.skin.id);
		assertEquals(0xFF336699, store.pixels.get(LocalSkin.SKIN_TEXTURE)[8 * 64 + 8]);
		assertTrue(Files.isRegularFile(dir.resolve(uuid + ".png")), "Cache geschrieben");
		assertEquals(2, requests.size());

		// Neuer Start ohne Netz: Cache reicht
		FakeStore store2 = new FakeStore();
		Textures.replaceForTests(store2);
		List<Runnable> queue2 = new ArrayList<Runnable>();
		Http offline = req -> {
			throw new IOException("offline");
		};
		LocalSkin again = new LocalSkin(dir, () -> new GameSession(uuid, "Steve", "token-123456789"), offline, queue2::add, m -> { });
		again.look(0);
		queue2.get(0).run();
		look = again.look(1);
		assertTrue(look.ownSkin);
		assertFalse(look.loading);

		// Offline-Konto: kein Netz, Standard-Skin
		List<Runnable> queue3 = new ArrayList<Runnable>();
		List<String> none = new ArrayList<String>();
		LocalSkin dev = new LocalSkin(dir, () -> new GameSession(null, "Dev", "0"), req -> {
			none.add(req.url);
			return null;
		}, queue3::add, m -> { });
		look = dev.look(0);
		assertEquals("Dev", look.name);
		assertEquals("default", look.skin.id);
		assertTrue(queue3.isEmpty());
		assertTrue(none.isEmpty());
	}

	// --- Hilfen ---

	private static SkinModelSpec spec() {
		SkinModelSpec s = new SkinModelSpec();
		s.skin = new TextureRef("skin", 64, 64);
		return s;
	}

	/** Minimale PNG (RGBA, Filter 0) für den eigenen Decoder. */
	static byte[] png(int w, int h, int argb) throws IOException {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		for (int y = 0; y < h; y++) {
			raw.write(0);
			for (int x = 0; x < w; x++) {
				raw.write((argb >> 16) & 0xFF);
				raw.write((argb >> 8) & 0xFF);
				raw.write(argb & 0xFF);
				raw.write(argb >>> 24);
			}
		}
		Deflater def = new Deflater();
		def.setInput(raw.toByteArray());
		def.finish();
		ByteArrayOutputStream z = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		while (!def.finished()) z.write(buf, 0, def.deflate(buf));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
		ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
		int32(ihdr, w);
		int32(ihdr, h);
		ihdr.write(new byte[]{8, 6, 0, 0, 0});
		chunk(out, "IHDR", ihdr.toByteArray());
		chunk(out, "IDAT", z.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
		int32(out, data.length);
		byte[] t = type.getBytes(StandardCharsets.US_ASCII);
		out.write(t);
		out.write(data);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data);
		int32(out, (int) crc.getValue());
	}

	private static void int32(ByteArrayOutputStream out, int v) {
		out.write(v >>> 24);
		out.write((v >>> 16) & 0xFF);
		out.write((v >>> 8) & 0xFF);
		out.write(v & 0xFF);
	}

	static final class FakeStore implements Textures.Store {
		final Map<String, int[]> pixels = new HashMap<String, int[]>();

		@Override
		public TextureRef upload(String name, int width, int height, int[] argb) {
			assertTrue(Textures.validName(name), name);
			pixels.put(name, argb.clone());
			return new TextureRef(name, width, height);
		}

		@Override
		public void release(TextureRef texture) {
			pixels.remove(String.valueOf(texture.id));
		}

		@Override
		public TextureRef game(String location, int width, int height) {
			return new TextureRef(location, width, height);
		}

		@Override
		public Textures.DefaultSkin defaultSkin(UUID uuid) {
			return new Textures.DefaultSkin(new TextureRef("default", 64, 64), false);
		}
	}

	/** Gezeichnetes Bild mit der Abbildung (Texel → Bildschirm) zum Zeitpunkt des Zeichnens. */
	static final class Img {
		final Object tex;
		final float u;
		final float v;
		final int w;
		final int h;
		final int argb;
		final float[] m;

		Img(Object tex, float u, float v, int w, int h, int argb, float[] m) {
			this.tex = tex;
			this.u = u;
			this.v = v;
			this.w = w;
			this.h = h;
			this.argb = argb;
			this.m = m;
		}

		float[] corner(float s, float t) {
			return new float[]{m[0] * s + m[2] * t + m[4], m[1] * s + m[3] * t + m[5]};
		}

		float det() {
			return m[0] * m[3] - m[1] * m[2];
		}

		@Override
		public String toString() {
			return tex + "(" + u + "," + v + " " + w + "x" + h + ")";
		}
	}

	/** Canvas, der die 2D-Transformation mitrechnet. */
	static class RecordingCanvas implements Canvas {
		final List<Img> images = new ArrayList<Img>();
		/** a, b, c, d, e, f (x' = a x + c y + e) */
		float[] m = {1, 0, 0, 1, 0, 0};
		final List<float[]> stack = new ArrayList<float[]>();
		float tx;
		float ty;

		float[] map(float x, float y) {
			return new float[]{m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]};
		}

		Img find(float u, float v) {
			return find(u, v, "skin");
		}

		Img find(float u, float v, Object tex) {
			for (Img i : images) if (i.tex.equals(tex) && Math.abs(i.u - u) < 0.01f && Math.abs(i.v - v) < 0.01f) return i;
			return null;
		}

		private void mul(float a, float b, float c, float d, float e, float f) {
			float[] n = {
					m[0] * a + m[2] * b, m[1] * a + m[3] * b,
					m[0] * c + m[2] * d, m[1] * c + m[3] * d,
					m[0] * e + m[2] * f + m[4], m[1] * e + m[3] * f + m[5]};
			m = n;
		}

		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { }
		@Override public void text(String text, int x, int y, int argb, boolean shadow) { }
		@Override public int textWidth(String text) { return text.length() * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public String clip(String text, int maxWidth) { return text; }
		@Override public void flush() { }
		@Override public void scissor(int x1, int y1, int x2, int y2) { }
		@Override public void noScissor() { }
		@Override public void raise(float z) { }
		@Override public void push() { stack.add(m.clone()); }
		@Override public void translate(float x, float y) { tx = x; ty = y; mul(1, 0, 0, 1, x, y); }
		@Override public void scale(float factor) { mul(factor, 0, 0, factor, 0, 0); }
		@Override public void pop() { m = stack.remove(stack.size() - 1); }
		@Override public boolean images() { return true; }
		@Override public void image(TextureRef t, float u, float v, int w, int h, int argb) { images.add(new Img(t.id, u, v, w, h, argb, m.clone())); }
		@Override public void rotate(float r) { float c = (float) Math.cos(r); float s = (float) Math.sin(r); mul(c, s, -s, c, 0, 0); }
		@Override public void scale(float sx, float sy) { mul(sx, 0, 0, sy, 0, 0); }
	}
}
