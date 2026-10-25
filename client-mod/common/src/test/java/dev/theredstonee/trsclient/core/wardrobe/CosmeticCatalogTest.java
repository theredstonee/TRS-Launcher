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
		// Flügel (Halloween 2026): eigenes Fach, nur Format 2; unbekannte Fächer fallen weg.
		CosmeticCatalog.Item wings = CosmeticCatalog.parse(json("{\"id\":\"redstone_wings\",\"slot\":\"wings\",\"format\":2,\"model\":\"/v1/a\",\"texture\":\"/v1/b\"}"), CFG);
		assertNotNull(wings);
		assertEquals("wings", wings.slot);
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"old_wings\",\"slot\":\"wings\",\"format\":1,\"template\":\"duck\",\"texture\":\"/v1/b\"}"), CFG));
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"tail\",\"slot\":\"tail\",\"format\":2,\"model\":\"/v1/a\",\"texture\":\"/v1/b\"}"), CFG));
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"evil\",\"slot\":\"hat\",\"format\":2,\"model\":\"https://evil.example/m.json\","
				+ "\"texture\":\"/v1/b.png\"}"), CFG));
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"secret\",\"slot\":\"hat\",\"hidden\":true,\"owned\":false,\"format\":2,"
				+ "\"model\":\"/v1/a.json\",\"texture\":\"/v1/b.png\"}"), CFG));
		// Format 1: nur bekannte Vorlagen und nur, wenn besessen (Ente)
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"old_crown\",\"slot\":\"hat\",\"template\":\"crown\",\"owned\":true,"
				+ "\"texture\":{\"url\":\"/v1/cosmetics/old_crown.png\"}}"), CFG));
		CosmeticCatalog.Item duck = CosmeticCatalog.parse(json("{\"id\":\"rubber_duck\",\"name\":\"Quietscheente\",\"slot\":\"hat\","
				+ "\"template\":\"duck\",\"owned\":true,\"hidden\":true,\"texture\":{\"url\":\"/v1/cosmetics/rubber_duck.png?v=1\"},"
				+ "\"templateUrl\":\"/v1/cosmetics/rubber_duck/template.json?v=abc123abc123\"}"), CFG);
		assertNotNull(duck);
		assertEquals(1, duck.format);
		assertEquals("https://trs-launcher.theredstonee.de/v1/cosmetics/rubber_duck/template.json?v=abc123abc123", duck.templateUrl);
		CosmeticCatalog.Item plain = CosmeticCatalog.parse(json("{\"id\":\"rubber_duck\",\"name\":\"Quietscheente\",\"slot\":\"hat\","
				+ "\"template\":\"duck\",\"owned\":true,\"texture\":{\"url\":\"/v1/cosmetics/rubber_duck.png?v=1\"},"
				+ "\"templateUrl\":\"https://evil.example/template.json\"}"), CFG);
		assertNotNull(plain);
		assertNull(plain.templateUrl);
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"rubber_duck\",\"slot\":\"hat\",\"template\":\"duck\",\"owned\":false,"
				+ "\"texture\":{\"url\":\"/v1/cosmetics/rubber_duck.png?v=1\"}}"), CFG));
		// Begleiter nur Format 2
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"bat_buddy\",\"slot\":\"companion\",\"template\":\"duck\",\"owned\":true,"
				+ "\"texture\":{\"url\":\"/v1/cosmetics/bat_buddy.png\"}}"), CFG));
	}

	@Test
	void readsCompanionAndEventUnlock() {
		CosmeticCatalog.Item buddy = CosmeticCatalog.parse(json("{\"id\":\"bat_buddy\",\"name\":\"Fledermaus\",\"slot\":\"companion\","
				+ "\"unlock\":{\"type\":\"event\",\"event\":\"halloween\"},\"owned\":false,\"format\":2,"
				+ "\"model\":\"/v1/cosmetics/bat_buddy/model.json?v=abc123\",\"texture\":\"/v1/cosmetics/bat_buddy.png?v=abc123\","
				+ "\"hash\":\"abc123\"}"), CFG);
		assertNotNull(buddy);
		assertEquals("companion", buddy.slot);
		assertEquals("event", buddy.unlock);
		assertEquals("halloween", buddy.event);
		assertTrue(buddy.eventClaim());
		assertTrue(buddy.locked());
		CosmeticCatalog.Item hat = CosmeticCatalog.parse(json("{\"id\":\"witch_hat\",\"name\":\"Hexenhut\",\"slot\":\"hat\","
				+ "\"unlock\":\"event\",\"event\":\"halloween\",\"owned\":true,\"equipped\":true,\"format\":2,"
				+ "\"model\":\"/v1/cosmetics/witch_hat/model.json\",\"texture\":\"/v1/cosmetics/witch_hat.png\"}"), CFG);
		assertNotNull(hat);
		assertEquals("hat", hat.slot);
		assertEquals("event", hat.unlock);
		assertFalse(hat.locked());
		CosmeticCatalog.Item reward = CosmeticCatalog.parse(json("{\"id\":\"spook_hat\",\"name\":\"Spuk\",\"slot\":\"hat\","
				+ "\"unlock\":\"reward\",\"rewardOf\":\"halloween2026\",\"owned\":false,\"format\":2,"
				+ "\"model\":\"/v1/cosmetics/spook_hat/model.json\",\"texture\":\"/v1/cosmetics/spook_hat.png\"}"), CFG);
		assertNotNull(reward);
		assertTrue(reward.reward());
		assertFalse(reward.eventClaim());
	}

	@Test
	void readsPetSlotOptionsAndMyChoices() {
		CosmeticCatalog.Item pet = CosmeticCatalog.parse(json("{\"id\":\"test_pet\",\"name\":\"Pinguin\",\"slot\":\"pet\","
				+ "\"unlock\":\"code\",\"owned\":true,\"format\":2,\"model\":\"/v1/cosmetics/test_pet/model.json\","
				+ "\"texture\":{\"url\":\"/v1/cosmetics/test_pet.png\"}}"), CFG);
		assertNotNull(pet);
		assertEquals("pet", pet.slot);
		assertTrue(pet.options.isEmpty());
		assertNull(CosmeticCatalog.parse(json("{\"id\":\"old_pet\",\"slot\":\"pet\",\"template\":\"duck\",\"owned\":true,"
				+ "\"texture\":\"/v1/b\"}"), CFG), "Begleiter am Boden nur als Format 2");
		CosmeticCatalog.Item hat = CosmeticCatalog.parse(json("{\"id\":\"test_hat\",\"name\":\"Mütze\",\"slot\":\"hat\","
				+ "\"unlock\":\"code\",\"owned\":true,\"format\":2,\"model\":\"/v1/m\",\"texture\":\"/v1/t\","
				+ "\"options\":[{\"id\":\"beard\",\"default\":true,\"name\":{\"de\":\"Bart\",\"en\":\"Beard\"}},{\"id\":\"BAD\"},"
				+ "{\"id\":\"pompom\",\"default\":false,\"name\":\"Pompom\"}]}"), CFG);
		assertNotNull(hat);
		assertEquals(2, hat.options.size());
		assertEquals("beard", hat.options.get(0).id);
		assertTrue(hat.options.get(0).defaultOn);
		assertEquals("Bart", hat.options.get(0).name("de"));
		assertEquals("Beard", hat.options.get(0).name("fr"));
		assertFalse(hat.options.get(1).defaultOn);
		assertEquals("Pompom", hat.options.get(1).name("de"));

		CosmeticCatalog.Mine mine = CosmeticCatalog.parseMine("{\"equipped\":{\"hat\":{\"id\":\"test_hat\",\"options\":{\"beard\":false,"
				+ "\"x\":1}},\"pet\":{\"id\":\"test_pet\",\"count\":3},\"wings\":null},\"emotes\":[]}");
		assertNotNull(mine);
		assertEquals(Boolean.FALSE, mine.options.get("hat").get("beard"));
		assertFalse(mine.options.get("hat").containsKey("x"));
		assertTrue(mine.options.get("wings").isEmpty());
		assertEquals(3, mine.petCount);
		// Optionen auch oben als options.<slot>; ohne pet-Fach (ältere API) bleibt die Anzahl unbekannt
		CosmeticCatalog.Mine top = CosmeticCatalog.parseMine("{\"equipped\":{\"hat\":{\"id\":\"test_hat\"}},"
				+ "\"options\":{\"hat\":{\"beard\":true}}}");
		assertEquals(Boolean.TRUE, top.options.get("hat").get("beard"));
		assertEquals(-1, top.petCount);
		assertEquals(0, CosmeticCatalog.parseMine("{\"equipped\":{\"pet\":null}}").petCount, "kein Begleiter am Boden");
		assertEquals(4, CosmeticCatalog.parseMine("{\"equipped\":{\"pet\":{\"id\":\"p\",\"count\":12}}}").petCount);
		assertNull(CosmeticCatalog.parseMine("kaputt["));
	}

	@Test
	void readsTheEscortOfTheWornPet() {
		dev.theredstonee.trsclient.core.online.OnlineConfig cfg = new dev.theredstonee.trsclient.core.online.OnlineConfig(true,
				"https://trs-launcher.theredstonee.de", "https://sessionserver.mojang.com");
		String raw = "{\"equipped\":{\"pet\":{\"id\":\"penguin\",\"format\":2,\"count\":1,\"scale\":8,\"extras\":["
				+ "{\"id\":\"penguin_emperor\",\"format\":2,\"model\":\"/v1/cosmetics/penguin_emperor/model.json\","
				+ "\"url\":\"/v1/cosmetics/penguin_emperor.png\",\"hash\":\"aa11bb\",\"scale\":8,\"petScale\":1.25},"
				+ "{\"id\":\"penguin_beanie\",\"model\":\"/v1/cosmetics/penguin_beanie/model.json\","
				+ "\"texture\":{\"url\":\"/v1/cosmetics/penguin_beanie.png\"}},"
				+ "{\"id\":\"bad\",\"format\":1,\"model\":\"/v1/x\",\"url\":\"/v1/y\"},\"nope\"]}}}";
		CosmeticCatalog.Mine m = CosmeticCatalog.parseMine(raw, cfg);
		assertEquals(1, m.petCount);
		assertEquals(1.0, m.petScale, 0);
		assertEquals(2, m.petExtras.size());
		assertEquals("penguin_emperor", m.petExtras.get(0).id);
		assertEquals(1.25, m.petExtras.get(0).petScale, 0);
		assertEquals("penguin_beanie", m.petExtras.get(1).id);
		assertTrue(CosmeticCatalog.parseMine(raw).petExtras.isEmpty(), "ohne API-Adresse kein Gefolge");
		assertTrue(CosmeticCatalog.parseMine("{\"equipped\":{\"pet\":{\"id\":\"p\"}}}", cfg).petExtras.isEmpty(),
				"ältere API ohne extras");
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
