package dev.theredstonee.trsclient.core.comfort;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.ComfortModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.panorama.CubeToEquirect;
import dev.theredstonee.trsclient.core.panorama.Panorama;
import dev.theredstonee.trsclient.core.panorama.PanoramaPng;
import dev.theredstonee.trsclient.core.profile.ServerPattern;
import dev.theredstonee.trsclient.core.profile.ServerProfiles;
import dev.theredstonee.trsclient.core.tooltip.DyeColors;
import dev.theredstonee.trsclient.core.tooltip.FoodIcons;
import dev.theredstonee.trsclient.core.tooltip.MapPalette;
import dev.theredstonee.trsclient.core.tooltip.MapPreviews;
import dev.theredstonee.trsclient.core.tooltip.TooltipCard;
import dev.theredstonee.trsclient.core.tooltip.TooltipPlacement;
import dev.theredstonee.trsclient.core.tooltip.TooltipText;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Komfort-Paket 2: Server-Profile (Muster, Wechsel, Speichern), Tooltip-Berechnungen und Panorama-Zusammensetzen. */
class ComfortLogicTest {
	@BeforeEach
	void english() {
		I18n.use("en");
	}

	@AfterEach
	void reset() {
		I18n.use("en");
		Textures.replaceForTests(null);
	}

	// --- Muster ---

	@Test
	void patternsNormalize() {
		assertEquals("hypixel.net", ServerPattern.normalize("  Hypixel.NET. "));
		assertEquals("*.hypixel.net", ServerPattern.normalize("*.hypixel.net"));
		assertEquals("play.example.net:25566", ServerPattern.normalize("minecraft://play.example.net:25566/"));
		assertEquals("[::1]:25570", ServerPattern.normalize("[::1]:25570"));
		assertEquals("*", ServerPattern.normalize("*"));
		assertEquals(ServerPattern.SINGLEPLAYER, ServerPattern.normalize("@SinglePlayer"));
		assertNull(ServerPattern.normalize("hypixel.net:99999"));
		assertNull(ServerPattern.normalize("hy pixel"));
		assertNull(ServerPattern.normalize("bad:port"));
		assertNull(ServerPattern.normalize(""));
		List<String> invalid = new ArrayList<String>();
		assertEquals(Arrays.asList("hypixel.net", "*.minemen.club"), ServerPattern.parseList("hypixel.net; *.minemen.club,hypixel.net", invalid));
		assertTrue(invalid.isEmpty());
		ServerPattern.parseList("ok.net x:y", invalid);
		assertEquals(Arrays.asList("x:y"), invalid);
	}

	@Test
	void patternsScoreSpecificity() {
		assertTrue(ServerPattern.score("hypixel.net", "mc.hypixel.net") > 0, "Host trifft Subdomains");
		assertTrue(ServerPattern.score("hypixel.net", "hypixel.net:25565") > ServerPattern.score("hypixel.net", "mc.hypixel.net"));
		assertEquals(0, ServerPattern.score("*.hypixel.net", "hypixel.net"), "*.x nur Subdomains");
		assertTrue(ServerPattern.score("*.hypixel.net", "mc.hypixel.net") > 0);
		assertEquals(0, ServerPattern.score("hypixel.net", "nothypixel.net"));
		assertEquals(0, ServerPattern.score("play.x.net:25566", "play.x.net"), "anderer Port (Standard 25565)");
		assertTrue(ServerPattern.score("play.x.net:25566", "PLAY.x.net:25566") > ServerPattern.score("play.x.net", "play.x.net:25566"));
		assertEquals(1, ServerPattern.score("*", "anything.org"));
		assertEquals(0, ServerPattern.score("*", ServerPattern.SINGLEPLAYER), "Einzelspieler ist kein Server");
		assertEquals(1000, ServerPattern.score(ServerPattern.SINGLEPLAYER, ServerPattern.SINGLEPLAYER));
		assertEquals(0, ServerPattern.score(ServerPattern.SINGLEPLAYER, "localhost"));
		assertEquals(1, ServerPattern.score("*", ServerPattern.UNKNOWN_SERVER));
		assertEquals(0, ServerPattern.score("hypixel.net", ServerPattern.UNKNOWN_SERVER));
		assertEquals("mc.hypixel.net", ServerPattern.forContext("mc.hypixel.net:25565"));
		assertEquals(ServerPattern.SINGLEPLAYER, ServerPattern.forContext(ServerPattern.SINGLEPLAYER));
	}

	// --- Server-Profile ---

	/** Profil mit Muster anlegen (ohne Welt) und Abweichungen setzen. */
	private static ServerProfiles.Profile profile(TrsModules m, String name, String patterns) {
		assertNull(m.serverProfiles.create(name));
		int index = m.serverProfiles.size() - 1;
		assertNull(m.serverProfiles.setPatterns(index, patterns));
		return m.serverProfiles.get(index);
	}

	@Test
	void profileSwitchesOnJoinAndRestoresStandard() {
		TrsModules m = new TrsModules();
		assertFalse(m.fullbright.isEnabled());
		boolean zoomStandard = m.zoom.isEnabled();
		// Profil „Hypixel“: Fullbright an, Zoom umgedreht – per „merken“ in einer Sitzung aufgezeichnet.
		assertNull(m.serverProfiles.update("mc.hypixel.net"));
		m.fullbright.setEnabled(true);
		m.zoom.setEnabled(!zoomStandard);
		assertNull(m.serverProfiles.rememberCurrent());
		ServerProfiles.Profile p = m.serverProfiles.active();
		assertNotNull(p);
		assertEquals("mc.hypixel.net", p.name());
		assertEquals(Arrays.asList("mc.hypixel.net"), p.patterns());
		assertTrue(p.moduleIds().contains("fullbright"));
		assertTrue(p.moduleIds().contains("zoom"));
		// Verlassen: Standard zurück.
		assertEquals("", m.serverProfiles.update(null));
		assertFalse(m.fullbright.isEnabled());
		assertEquals(zoomStandard, m.zoom.isEnabled());
		// Anderer Server: nichts passiert.
		assertNull(m.serverProfiles.update("other.org"));
		assertFalse(m.fullbright.isEnabled());
		assertNull(m.serverProfiles.update(null), "ohne Profil kein Wechsel");
		// Wieder rein (über Subdomain-Muster): Profil gilt.
		assertEquals("Server profile: mc.hypixel.net", m.serverProfiles.update("mc.hypixel.net:25565"));
		assertTrue(m.fullbright.isEnabled());
		assertEquals(!zoomStandard, m.zoom.isEnabled());
		// Änderung während der Sitzung bleibt im Profil, der Standard bleibt unberührt.
		m.keystrokes.setEnabled(!m.keystrokes.isEnabled());
		boolean keystrokesInProfile = m.keystrokes.isEnabled();
		m.serverProfiles.update(null);
		assertEquals(!keystrokesInProfile, m.keystrokes.isEnabled());
		m.serverProfiles.update("mc.hypixel.net");
		assertEquals(keystrokesInProfile, m.keystrokes.isEnabled());
	}

	@Test
	void mostSpecificProfileWinsAndSingleplayerIsOwnProfile() {
		TrsModules m = new TrsModules();
		ServerProfiles.Profile any = profile(m, "All", "*");
		ServerProfiles.Profile hypixel = profile(m, "Hypixel", "*.hypixel.net");
		ServerProfiles.Profile exact = profile(m, "Lobby", "lobby.hypixel.net:25565");
		assertSame(exact, m.serverProfiles.match("lobby.hypixel.net"));
		assertSame(hypixel, m.serverProfiles.match("mc.hypixel.net"));
		assertSame(any, m.serverProfiles.match("example.org"));
		assertNull(m.serverProfiles.match(ServerPattern.SINGLEPLAYER));
		m.serverProfiles.toggleSingleplayer(0);
		assertSame(any, m.serverProfiles.match(ServerPattern.SINGLEPLAYER));
		assertTrue(any.patterns().contains(ServerPattern.SINGLEPLAYER));
	}

	@Test
	void disabledModuleDoesNotSwitch() {
		TrsModules m = new TrsModules();
		m.serverProfiles.update("a.net");
		m.fullbright.setEnabled(true);
		m.serverProfiles.rememberCurrent();
		m.serverProfiles.update(null);
		m.comfort.serverProfiles.setEnabled(false);
		m.serverProfiles.update("a.net");
		assertNull(m.serverProfiles.active());
		assertFalse(m.fullbright.isEnabled());
		// Einschalten mitten auf dem Server: Profil greift sofort.
		m.comfort.serverProfiles.setEnabled(true);
		assertNotNull(m.serverProfiles.update("a.net"));
		assertTrue(m.fullbright.isEnabled());
	}

	@Test
	void fileAlwaysHoldsStandardAndSessionSurvivesReload() {
		TrsModules m = new TrsModules();
		m.serverProfiles.update("pvp.example.net");
		m.fullbright.setEnabled(true);
		m.serverProfiles.rememberCurrent();
		assertTrue(m.fullbright.isEnabled());
		TrsConfig saved = m.registry.capture();
		assertFalse(Boolean.TRUE.equals(saved.modules.get("fullbright").enabled), "Datei = Standard");
		assertNotNull(saved.serverProfiles);
		assertEquals(1, saved.serverProfiles.profiles.size());
		assertTrue(saved.serverProfiles.profiles.get(0).modules.containsKey("fullbright"));
		// Neu laden während der Sitzung (Sync/Rückgängig): Profil gilt weiter.
		m.registry.apply(saved);
		assertTrue(m.fullbright.isEnabled());
		assertNotNull(m.serverProfiles.active());
		// Neuer Start (keine Welt): Standard.
		TrsModules fresh = new TrsModules();
		fresh.registry.apply(saved);
		assertFalse(fresh.fullbright.isEnabled());
		assertEquals(1, fresh.serverProfiles.size());
		fresh.serverProfiles.update("pvp.example.net");
		assertTrue(fresh.fullbright.isEnabled());
	}

	@Test
	void hudProfileBelongsToServerProfile() {
		TrsModules m = new TrsModules();
		boolean fpsStandard = m.fps.isEnabled();
		assertNull(m.profiles.create("PvP"));
		m.fps.setEnabled(!fpsStandard);
		m.profiles.switchTo(0);
		assertEquals(fpsStandard, m.fps.isEnabled());
		ServerProfiles.Profile p = profile(m, "Arena", "arena.net");
		m.serverProfiles.cycleHudProfile(0); // Standard
		m.serverProfiles.cycleHudProfile(0); // PvP
		assertEquals("PvP", p.hudProfile());
		m.serverProfiles.update("arena.net");
		assertEquals("PvP", m.profiles.activeName());
		assertEquals(!fpsStandard, m.fps.isEnabled());
		TrsConfig saved = m.registry.capture();
		assertEquals("Standard", saved.hudProfiles.active, "Datei zeigt das HUD-Profil des Standards");
		assertEquals(fpsStandard, Boolean.TRUE.equals(saved.modules.get("fps").enabled));
		m.serverProfiles.update(null);
		assertEquals("Standard", m.profiles.activeName());
		assertEquals(fpsStandard, m.fps.isEnabled());
	}

	@Test
	void deletingActiveProfileRestoresStandard() {
		TrsModules m = new TrsModules();
		m.serverProfiles.update(ServerPattern.SINGLEPLAYER);
		m.fullbright.setEnabled(true);
		assertNull(m.serverProfiles.rememberCurrent());
		assertEquals("Singleplayer", m.serverProfiles.active().name());
		assertTrue(m.serverProfiles.delete(0));
		assertFalse(m.fullbright.isEnabled());
		assertNull(m.serverProfiles.active());
		assertEquals("Join a world first.", new TrsModules().serverProfiles.rememberCurrent());
	}

	@Test
	void profileNamesAndPatternsAreValidated() {
		TrsModules m = new TrsModules();
		assertNull(m.serverProfiles.create("A"));
		assertEquals("Name is already taken", m.serverProfiles.create("a"));
		assertEquals("Not a valid server: x:y", m.serverProfiles.setPatterns(0, "ok.net; x:y"));
		assertTrue(m.serverProfiles.get(0).patterns().isEmpty());
		assertNull(m.serverProfiles.setPatterns(0, "ok.net; @singleplayer"));
		assertEquals("ok.net; @singleplayer", m.serverProfiles.patternsText(0));
	}

	// --- Tooltips ---

	@Test
	void durabilityTexts() {
		assertEquals("1234 / 1561 (79%)", TooltipText.durabilityValue(ComfortModules.Durability.BOTH, 1561, 327));
		assertEquals("1234 / 1561", TooltipText.durabilityValue(ComfortModules.Durability.NUMBER, 1561, 327));
		assertEquals("79%", TooltipText.durabilityValue(ComfortModules.Durability.PERCENT, 1561, 327));
		assertNull(TooltipText.durabilityValue(ComfortModules.Durability.OFF, 1561, 327));
		assertNull(TooltipText.durabilityValue(ComfortModules.Durability.BOTH, 0, 0));
		assertEquals("Durability: 0 / 10 (0%)", TooltipText.durabilityLine(ComfortModules.Durability.BOTH, 10, 12));
		assertEquals('a', TooltipText.colorCode(100, 10));
		assertEquals('e', TooltipText.colorCode(100, 60));
		assertEquals('6', TooltipText.colorCode(100, 85));
		assertEquals('c', TooltipText.colorCode(100, 95));
		I18n.use("de");
		assertEquals("Haltbarkeit: 50 / 100 (50 %)", TooltipText.durabilityLine(ComfortModules.Durability.BOTH, 100, 50));
	}

	@Test
	void enchantmentsAreDetectedAndPacked() {
		assertTrue(TooltipText.isEnchantmentKey("enchantment.minecraft.sharpness"));
		assertTrue(TooltipText.isEnchantmentKey("enchantment.mymod.lifesteal"));
		assertFalse(TooltipText.isEnchantmentKey("enchantment.level.5"));
		assertFalse(TooltipText.isEnchantmentKey("item.minecraft.enchanted_book"));
		assertFalse(TooltipText.isEnchantmentKey("enchantment.unknown"));
		List<List<String>> lines = TooltipText.pack(Arrays.asList("aaaa", "bbbb", "cc", "dddddddddd"), String::length, 2, 14);
		assertEquals(Arrays.asList(Arrays.asList("aaaa", "bbbb", "cc"), Arrays.asList("dddddddddd")), lines);
		assertEquals(1, TooltipText.pack(Arrays.asList("very-long-entry"), String::length, 2, 5).size());
		assertTrue(TooltipText.compact(true, true, 3, false));
		assertFalse(TooltipText.compact(true, true, 3, true), "Umschalt zeigt Details");
		assertTrue(TooltipText.compact(true, false, 3, true), "ohne „Umschalt für Details“ immer kompakt");
		assertFalse(TooltipText.compact(true, true, 1, false), "eine Verzauberung bleibt wie sie ist");
		assertFalse(TooltipText.compact(false, true, 5, false));
	}

	@Test
	void tooltipPlacementFollowsVanilla() {
		assertEquals(8, TooltipPlacement.contentHeight(1));
		assertEquals(40, TooltipPlacement.contentHeight(4));
		// Platz rechts: Maus + 12 / − 12.
		assertArrayEquals(new int[]{112, 88, 80, 40}, TooltipPlacement.tooltip(100, 100, 80, 40, 400, 300, true));
		// Kein Platz rechts: links von der Maus (modern: − 24 − Breite, alt: − 28 − Breite).
		assertEquals(350 + 12 - 24 - 80, TooltipPlacement.tooltip(350, 100, 80, 40, 400, 300, true)[0]);
		assertEquals(350 + 12 - 28 - 80, TooltipPlacement.tooltip(350, 100, 80, 40, 400, 300, false)[0]);
		// Unten kein Platz: hochgeschoben.
		assertEquals(300 - 40 - 3, TooltipPlacement.tooltip(100, 290, 80, 40, 400, 300, true)[1]);
		assertEquals(300 - 40 - 6, TooltipPlacement.tooltip(100, 290, 80, 40, 400, 300, false)[1]);
		// Karte darüber …
		int[] tip = {112, 88, 80, 40};
		int[] card = TooltipPlacement.card(tip, 60, 30, 400, 300);
		assertEquals(112 - 4, card[0]);
		assertEquals(88 - 4 - 2 - 30, card[1]);
		// … sonst darunter …
		int[] low = TooltipPlacement.card(new int[]{112, 10, 80, 40}, 60, 30, 400, 300);
		assertEquals(10 + 40 + 4 + 2, low[1]);
		// … sonst daneben (nie über dem Tooltip).
		int[] side = TooltipPlacement.card(new int[]{112, 10, 80, 250}, 60, 280, 400, 300);
		assertEquals(112 + 80 + 4 + 2, side[0]);
		int[] left = TooltipPlacement.card(new int[]{300, 10, 80, 250}, 60, 280, 400, 300);
		assertEquals(300 - 4 - 2 - 60, left[0]);
	}

	@Test
	void foodIconsAndCardSize() {
		assertArrayEquals(new int[]{3, 0}, FoodIcons.icons(6));
		assertArrayEquals(new int[]{3, 1}, FoodIcons.icons(7.2f));
		assertArrayEquals(new int[]{0, 1}, FoodIcons.icons(0.4f));
		assertArrayEquals(new int[]{0, 0}, FoodIcons.icons(0));
		assertArrayEquals(new int[]{10, 0}, FoodIcons.icons(30));
		assertEquals(3 * 8 + 1, FoodIcons.rowWidth(6));
		Canvas c = new FakeCanvas();
		TooltipCard card = new TooltipCard();
		assertTrue(card.empty());
		card.food(6, 7.2f);
		assertFalse(card.empty());
		assertEquals(4 + 18 + 2 + 4, card.height(c));
		card.grid(new Object[27], 27, 9, DyeColors.argb("red"));
		assertEquals(9 * 18 + 2 + 8, card.width(c));
		assertEquals(4 + 3 * 18 + 2 + 4 + 18 + 2 + 4, card.height(c));
		card.draw(c, 0, 0);
		card.clear();
		assertTrue(card.empty());
	}

	@Test
	void mapPaletteAndDyes() {
		assertEquals(0, MapPalette.argb((byte) 0));
		assertEquals(0, MapPalette.argb((byte) 3), "Grundfarbe 0 ist durchsichtig");
		// Gras (1), Helligkeit hoch (2) = Grundfarbe unverändert.
		assertEquals(0xFF7FB238, MapPalette.argb((byte) (1 * 4 + 2)));
		// Wasser (12), normal (1): × 220/255.
		assertEquals(0xFF000000 | (0x40 * 220 / 255) << 16 | (0x40 * 220 / 255) << 8 | (0xFF * 220 / 255), MapPalette.argb((byte) (12 * 4 + 1)));
		// Flechten (61) als Byte > 127.
		assertEquals(0xFF7FA796, MapPalette.argb((byte) (61 * 4 + 2)));
		int[] px = MapPalette.toArgb(new byte[]{0, (byte) 6}, 0xFF123456, null);
		assertEquals(128 * 128, px.length);
		assertEquals(0xFF123456, px[0]);
		assertEquals(0xFF7FB238, px[1]);
		assertEquals(0xFF123456, px[500], "fehlende Daten = Papier");
		assertEquals(0xFFB02E26, DyeColors.argb("RED"));
		assertEquals(0xFF9D9D97, DyeColors.argb("silver"));
		assertEquals(DyeColors.SHULKER, DyeColors.argb(null));
		assertEquals(DyeColors.SHULKER, DyeColors.argb("rainbow"));
	}

	@Test
	void mapPreviewsUploadOnlyOnChange() {
		final List<String> uploads = new ArrayList<String>();
		final List<String> released = new ArrayList<String>();
		Textures.replaceForTests(new Textures.Store() {
			@Override
			public TextureRef upload(String name, int width, int height, int[] argb) {
				uploads.add(name);
				return new TextureRef(name, width, height);
			}

			@Override
			public void release(TextureRef texture) {
				released.add((String) texture.id);
			}

			@Override
			public TextureRef game(String location, int width, int height) {
				return null;
			}

			@Override
			public Textures.DefaultSkin defaultSkin(UUID uuid) {
				return null;
			}
		});
		MapPreviews maps = new MapPreviews();
		byte[] a = new byte[128 * 128];
		TextureRef t1 = maps.texture(5, a);
		assertSame(t1, maps.texture(5, a));
		assertEquals(1, uploads.size());
		a[100] = 6;
		TextureRef t2 = maps.texture(5, a);
		assertEquals(2, uploads.size());
		assertEquals(t1.id, t2.id, "gleiche Karte → gleicher Name (an Ort und Stelle)");
		for (int id = 10; id < 20; id++) maps.texture(id, a);
		assertTrue(released.contains("tooltip/map0"), "älteste freigegeben");
		maps.clear();
		assertEquals(new java.util.HashSet<String>(uploads).size(), released.size(), "alles freigegeben");
	}

	// --- Panorama ---

	private static int[][] solidFaces(int size) {
		int[] colors = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFFFFFFFF, 0xFF000000};
		int[][] faces = new int[6][];
		for (int i = 0; i < 6; i++) {
			faces[i] = new int[size * size];
			Arrays.fill(faces[i], colors[i]);
		}
		return faces;
	}

	@Test
	void equirectPutsFacesWhereTheyBelong() {
		int s = 16, w = 64, h = 32;
		int[] out = CubeToEquirect.compose(solidFaces(s), s, w);
		assertEquals(w * h, out.length);
		assertEquals(0xFFFF0000, out[(h / 2) * w + w / 2], "Mitte = Blickrichtung");
		assertEquals(0xFF00FF00, out[(h / 2) * w + w * 3 / 4], "rechts = 90° rechts");
		assertEquals(0xFF0000FF, out[(h / 2) * w + 1], "Rand = hinten");
		assertEquals(0xFFFFFF00, out[(h / 2) * w + w / 4], "links = 90° links");
		assertEquals(0xFFFFFFFF, out[w / 2], "oben = Zenit");
		assertEquals(0xFF000000, out[(h - 1) * w + w / 2], "unten = Nadir");
	}

	@Test
	void equirectOrientsUpAndDownFaces() {
		int s = 16;
		int[][] faces = solidFaces(s);
		// Oben: obere Bildhälfte (= hinten) grau, untere (= vorn) weiß. Unten: obere Hälfte (= vorn) rot-schwarz.
		for (int y = 0; y < s / 2; y++) Arrays.fill(faces[CubeToEquirect.UP], y * s, (y + 1) * s, 0xFF808080);
		for (int y = 0; y < s / 2; y++) Arrays.fill(faces[CubeToEquirect.DOWN], y * s, (y + 1) * s, 0xFF400000);
		int w = 128, h = 64;
		int[] out = CubeToEquirect.compose(faces, s, w);
		int nearTop = 3, nearBottom = h - 4;
		assertEquals(0xFFFFFFFF, out[nearTop * w + w / 2], "Himmel in Blickrichtung = vordere Hälfte des Oben-Bildes");
		assertEquals(0xFF808080, out[nearTop * w + 2], "Himmel hinten = obere Hälfte des Oben-Bildes");
		assertEquals(0xFF400000, out[nearBottom * w + w / 2], "Boden vorn = obere Hälfte des Unten-Bildes");
		assertEquals(0xFF000000, out[nearBottom * w + 2]);
		int[] square = CubeToEquirect.cropCenterSquare(new int[]{1, 2, 3, 4, 5, 6}, 3, 2);
		assertArrayEquals(new int[]{1, 2, 4, 5}, square);
	}

	@Test
	void pngWriterRoundTrips(@TempDir Path dir) throws Exception {
		int w = 37, h = 11;
		int[] px = new int[w * h];
		for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | (i * 7919) & 0xFFFFFF;
		Path file = dir.resolve("p.png");
		PanoramaPng.write(file, w, h, px);
		PngDecoder.Image img = PngDecoder.decode(Files.readAllBytes(file));
		assertEquals(w, img.width);
		assertEquals(h, img.height);
		assertArrayEquals(px, img.argb);
		assertFalse(Files.exists(dir.resolve("p.png.tmp")));
	}

	@Test
	void ownCaptureWritesCubeAndEquirect(@TempDir Path game) throws Exception {
		Panorama p = Panorama.get();
		p.request(true, true);
		assertEquals(Panorama.POLL_CLOSE_SCREEN, p.poll(1000, true, true));
		assertEquals(Panorama.POLL_NONE, p.poll(1100, true, false));
		assertEquals(Panorama.POLL_CAPTURE, p.poll(1500, true, false));
		Panorama.OwnCapture c = p.beginOwn(game, 30f, System.currentTimeMillis());
		assertNotNull(c);
		int frames = 0;
		float[] yaws = new float[6];
		while (!c.finished() && frames < 200) {
			frames++;
			int face = c.face();
			yaws[face] = c.yaw();
			if (!c.wantsFrame()) continue;
			int[] img = new int[24 * 16];
			Arrays.fill(img, 0xFF000000 | face * 40);
			c.frame(img, 24, 16);
		}
		assertTrue(c.finished());
		assertArrayEquals(new float[]{30f, 120f, 210f, -60f, 30f, 30f}, yaws, 0.001f);
		long deadline = System.currentTimeMillis() + 10000;
		while (p.state() != Panorama.State.IDLE && System.currentTimeMillis() < deadline) Thread.sleep(20);
		Path folder = p.lastFolder();
		assertNotNull(folder);
		assertTrue(folder.startsWith(game.resolve("screenshots").resolve("panorama")));
		for (int i = 0; i < 6; i++) {
			PngDecoder.Image face = PngDecoder.decode(Files.readAllBytes(folder.resolve("panorama_" + i + ".png")));
			assertEquals(16, face.width);
			assertEquals(0xFF000000 | i * 40, face.argb[0]);
		}
		PngDecoder.Image eq = PngDecoder.decode(Files.readAllBytes(folder.resolve("panorama_360.png")));
		assertEquals(64, eq.width);
		assertEquals(32, eq.height);
		assertEquals(folder.resolve("panorama_360.png"), p.lastImage());
		assertNotNull(p.toast(System.currentTimeMillis()));
		assertFalse(p.canShare(), "ohne Handler kein Teilen");
		Panorama.setShareHandler((image, dir) -> { });
		assertTrue(p.canShare());
		Panorama.setShareHandler(null);
	}

	@Test
	void panoramaNeedsAWorld() {
		Panorama p = Panorama.get();
		p.request(true, false);
		assertEquals(Panorama.POLL_NONE, p.poll(0, false, false));
		assertEquals(Panorama.State.IDLE, p.state());
		assertEquals("Panoramas only work in a world.", p.toast(1).text);
	}

	/** Zeichenfläche ohne Ausgabe (Maße wie die Minecraft-Schrift: 6 px je Zeichen). */
	private static final class FakeCanvas implements Canvas {
		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { }
		@Override public void text(String text, int x, int y, int argb, boolean shadow) { }
		@Override public int textWidth(String text) { return text.length() * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public String clip(String text, int maxWidth) { return text; }
		@Override public void flush() { }
		@Override public void scissor(int x1, int y1, int x2, int y2) { }
		@Override public void noScissor() { }
		@Override public void raise(float z) { }
		@Override public void push() { }
		@Override public void translate(float x, float y) { }
		@Override public void scale(float factor) { }
		@Override public void pop() { }
	}
}
