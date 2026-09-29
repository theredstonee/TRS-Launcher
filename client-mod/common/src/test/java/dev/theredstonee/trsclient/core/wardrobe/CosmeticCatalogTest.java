package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Katalog der Garderobe (Reiter „Kosmetik“): welche Einträge gelesen werden und wie. */
class CosmeticCatalogTest {
	private static final OnlineConfig CFG = new OnlineConfig(true, "https://trs-launcher.theredstonee.de", OnlineConfig.DEFAULT_SESSION);

	private static JsonObject json(String s) {
		return new JsonParser().parse(s).getAsJsonObject();
	}

	@Test
	void readsFormat2WithTextureObjectAndRelativeUrls() {
		CosmeticCatalog.Item it = CosmeticCatalog.parse(json("{\"id\":\"redstone_crown\",\"name\":\"Redstone-Krone\",\"slot\":\"hat\","
				+ "\"unlock\":\"code\",\"owned\":false,\"format\":2,\"model\":\"/v1/cosmetics/redstone_crown/model.json?v=abc123abc123\","
				+ "\"texture\":{\"url\":\"https://trs-launcher.theredstonee.de/v1/cosmetics/redstone_crown.png?v=abc123abc123\",\"width\":320,\"height\":256,\"scale\":8,\"frames\":1},"
				+ "\"glow\":\"/v1/cosmetics/redstone_crown/glow.png?v=abc123abc123\",\"card\":\"/v1/cosmetics/redstone_crown/card.png?v=1\","
				+ "\"cardNight\":\"/v1/cosmetics/redstone_crown/card-night.png?v=1\",\"frames\":1,\"glowFrames\":12,\"glowFrameTimeMs\":140,"
				+ "\"hash\":\"abc123abc123\",\"template\":null}"), CFG);
		assertNotNull(it);
		assertEquals(2, it.format);
		assertTrue(it.locked());
		assertEquals("code", it.unlock);
		assertEquals("abc123abc123", it.hat.hash);
		assertEquals("https://trs-launcher.theredstonee.de/v1/cosmetics/redstone_crown/card-night.png?v=1", it.cardNightUrl);
		// Textur auch als Zeichenkette
		CosmeticCatalog.Item s = CosmeticCatalog.parse(json("{\"id\":\"halo\",\"name\":\"Heiligenschein\",\"slot\":\"hat\",\"unlock\":\"free\","
				+ "\"owned\":true,\"format\":2,\"model\":\"/v1/cosmetics/halo/model.json?v=1\",\"texture\":\"/v1/cosmetics/halo.png?v=1\"}"), CFG);
		assertNotNull(s);
		assertFalse(s.locked());
		assertNull(s.cardUrl);
	}

	@Test
	void skipsOtherSlotsForeignHostsAndHiddenUnowned() {
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"redstone_wings\",\"slot\":\"wings\",\"format\":2,\"model\":\"/v1/a\",\"texture\":\"/v1/b\"}"), CFG));
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"evil\",\"slot\":\"hat\",\"format\":2,\"model\":\"https://evil.example/m.json\","
				+ "\"texture\":\"/v1/b.png\"}"), CFG));
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"secret\",\"slot\":\"hat\",\"hidden\":true,\"owned\":false,\"format\":2,"
				+ "\"model\":\"/v1/a.json\",\"texture\":\"/v1/b.png\"}"), CFG));
		// Format 1: nur bekannte Vorlagen und nur, wenn besessen (Ente)
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"old_crown\",\"slot\":\"hat\",\"template\":\"crown\",\"owned\":true,"
				+ "\"texture\":{\"url\":\"/v1/cosmetics/old_crown.png\"}}"), CFG));
		CosmeticCatalog.Item duck = CosmeticCatalog.parse(json("{\"id\":\"rubber_duck\",\"name\":\"Quietscheente\",\"slot\":\"hat\","
				+ "\"template\":\"duck\",\"owned\":true,\"hidden\":true,\"texture\":{\"url\":\"/v1/cosmetics/rubber_duck.png?v=1\"}}"), CFG);
		assertNotNull(duck);
		assertEquals(1, duck.format);
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"rubber_duck\",\"slot\":\"hat\",\"template\":\"duck\",\"owned\":false,"
				+ "\"texture\":{\"url\":\"/v1/cosmetics/rubber_duck.png?v=1\"}}"), CFG));
	}

	@Test
	void shrinksCards() {
		int[] px = new int[400 * 400];
		java.util.Arrays.fill(px, 0xFF336699);
		CosmeticCatalog.Art a = CosmeticCatalog.shrink(new PngDecoder.Image(400, 400, px));
		assertTrue(a.width <= CosmeticCatalog.CARD_EDGE && a.height <= CosmeticCatalog.CARD_EDGE);
		assertEquals(0xFF336699, a.argb[0]);
	}
}
