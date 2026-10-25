package dev.theredstonee.trsclient.core.online;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.theredstonee.trsclient.core.online.OnlineTest.CONFIG;
import static dev.theredstonee.trsclient.core.online.OnlineTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Winter 2026 im Lookup: {@code cosmetics.<slot>.options} und {@code cosmetics.pet} mit {@code count}. */
class WinterLookupTest {
	static final String A = "0123456789abcdef0123456789abcdef";
	static final String B = "fedcba9876543210fedcba9876543210";
	static final String C = "00112233445566778899aabbccddeeff";

	@Test
	void lookupParsesPetCountAndOptions() throws Exception {
		FakeHttp http = new FakeHttp().json("POST /v1/players/lookup", 200, "{\"players\":["
				+ "{\"uuid\":\"" + A + "\",\"cosmetics\":{"
				+ "\"hat\":{\"id\":\"test_hat\",\"format\":2,\"model\":\"/v1/cosmetics/test_hat/model.json\",\"url\":\"/v1/cosmetics/test_hat.png\","
				+ "\"hash\":\"abc123\",\"options\":{\"beard\":false,\"BAD\":true,\"glow\":\"yes\"}},"
				+ "\"pet\":{\"id\":\"test_pet\",\"format\":2,\"model\":\"/v1/cosmetics/test_pet/model.json\",\"url\":\"/v1/cosmetics/test_pet.png\","
				+ "\"hash\":\"def456\",\"count\":3}}},"
				+ "{\"uuid\":\"" + B + "\",\"cosmetics\":{\"pet\":{\"id\":\"test_pet\",\"format\":2,\"model\":\"/v1/cosmetics/test_pet/model.json\","
				+ "\"url\":\"/v1/cosmetics/test_pet.png\",\"hash\":\"def456\",\"count\":99},"
				+ "\"hat\":{\"id\":\"test_hat\",\"format\":2,\"model\":\"/v1/cosmetics/test_hat/model.json\",\"url\":\"/v1/cosmetics/test_hat.png\","
				+ "\"hash\":\"abc123\",\"options\":[1,2]}}}]}");
		Map<String, PlayerInfo> out = new TrsApi(http, CONFIG).lookup(TOKEN, Arrays.asList(A, B));
		PlayerInfo a = out.get(A);
		assertEquals("test_pet", a.pet.id);
		assertEquals(3, a.pet.count);
		assertFalse(a.hat.option("beard", true), "Bart aus");
		assertTrue(a.hat.option("other", true), "fehlt → Standard");
		assertEquals(Collections.singletonMap("beard", false), a.hat.options, "nur gültige id → bool");
		PlayerInfo b = out.get(B);
		assertEquals(HatInfo.MAX_COUNT, b.pet.count, "auf 4 begrenzt");
		assertTrue(b.hat.options.isEmpty(), "kaputte options kippen den Lookup nicht");
		// Gleiches Teil, andere Wahl: anderer Schlüssel, gleiche Dateien
		assertNotEquals(a.hat, b.hat);
		assertNotEquals(a.hat.key(), b.hat.key());
		assertEquals(a.hat.assetKey(), b.hat.assetKey());
		assertEquals(a.pet.assetKey(), b.pet.assetKey());
		assertNotEquals(a.pet, b.pet, "Anzahl gehört zum Schlüssel");
		assertNull(a.companion);
		assertNull(a.wings);
		assertEquals(a.pet, a.withBadge(true).pet, "Live-Abzeichen behält den Begleiter");
	}

	/** Ein Format-2-Begleiter im Lookup ({@code extra} = weitere Felder, mit führendem Komma). */
	static String pet(String id, String extra) {
		return "{\"id\":\"" + id + "\",\"format\":2,\"model\":\"/v1/cosmetics/" + id + "/model.json\",\"url\":\"/v1/cosmetics/" + id
				+ ".png\",\"scale\":8,\"hash\":\"" + Integer.toHexString(id.hashCode() | 0x100000) + "\"" + extra + "}";
	}

	@Test
	void lookupParsesEscortWithScalesAndToleratesBrokenEntries() throws Exception {
		String extras = ",\"extras\":[" + pet("penguin_emperor", ",\"petScale\":1.25") + "," + pet("penguin_beanie", ",\"petScale\":1")
				+ ",{\"id\":\"kaputt\",\"format\":2,\"model\":\"https://evil.example/m.json\",\"url\":\"/v1/x.png\"}"
				+ ",17," + pet("penguin_shades", ",\"petScale\":9")
				+ "," + pet("too_many_a", "") + "," + pet("too_many_b", "") + "]";
		FakeHttp http = new FakeHttp().json("POST /v1/players/lookup", 200, "{\"players\":["
				+ "{\"uuid\":\"" + A + "\",\"cosmetics\":{\"pet\":" + pet("penguin", ",\"count\":1,\"petScale\":1" + extras) + "}},"
				// Haupt-Teil selbst größer; ein Bruch in scale (Pixel-Faktor) darf den Lookup nicht kippen und ist keine Größe
				+ "{\"uuid\":\"" + B + "\",\"cosmetics\":{\"pet\":" + pet("penguin_emperor", ",\"count\":2,\"petScale\":1.25")
						.replace("\"scale\":8", "\"scale\":1.5") + "}},"
				// ältere API: weder petScale noch extras
				+ "{\"uuid\":\"" + C + "\",\"cosmetics\":{\"pet\":" + pet("penguin", ",\"count\":1") + "}}]}");
		Map<String, PlayerInfo> out = new TrsApi(http, CONFIG).lookup(TOKEN, Arrays.asList(A, B, C));
		HatInfo a = out.get(A).pet;
		assertEquals("penguin", a.id);
		assertEquals(1, a.count);
		assertEquals(1.0, a.petScale, 0, "scale 8 = Textur, keine Größe");
		assertEquals(HatInfo.MAX_EXTRAS, a.extras.size(), "kaputte fallen weg, höchstens 4");
		assertEquals("penguin_emperor", a.extras.get(0).id);
		assertEquals(1.25, a.extras.get(0).petScale, 0);
		assertEquals("penguin_beanie", a.extras.get(1).id);
		assertEquals(1.0, a.extras.get(1).petScale, 0);
		assertEquals("penguin_shades", a.extras.get(2).id);
		assertEquals(2.0, a.extras.get(2).petScale, 0, "auf 2 begrenzt");
		assertEquals("too_many_a", a.extras.get(3).id);
		assertEquals(5, a.petTotal());
		assertSame(a, a.petPiece(0));
		assertSame(a.extras.get(0), a.petPiece(1));
		assertNull(a.petPiece(5));
		HatInfo b = out.get(B).pet;
		assertEquals(2, b.count);
		assertEquals(1.25, b.petScale, 0);
		assertTrue(b.extras.isEmpty());
		assertEquals(2, b.petTotal());
		HatInfo c = out.get(C).pet;
		assertEquals(1.0, c.petScale, 0, "ohne petScale: 1 (scale 8 ist die Textur)");
		assertTrue(c.extras.isEmpty());
		// Gefolge und Größe gehören zum Schlüssel, nicht zu den Dateien
		HatInfo plain = a.withPet(1.0, null);
		assertNotEquals(a, plain);
		assertEquals(a.assetKey(), plain.assetKey());
		assertEquals(a, a.withPet(1.0, a.extras), "gleiches Gefolge → gleich");
		assertSame(plain, plain.withPet(1.0, null));
		assertEquals(a.extras, a.with(null, 1).extras, "Optionen/Anzahl ändern behält das Gefolge");
	}

	@Test
	void petScaleIsClampedAndRounded() {
		assertEquals(0.5, HatInfo.clampScale(0.1), 0);
		assertEquals(2.0, HatInfo.clampScale(9), 0);
		assertEquals(1.0, HatInfo.clampScale(Double.NaN), 0);
		assertEquals(1.25, HatInfo.clampScale(1.2500001), 0);
	}

	@Test
	void withKeepsTheSamePieceAndNormalisesChoices() {
		HatInfo base = HatInfo.v2("test_hat", "/v1/cosmetics/test_hat/model.json", "/v1/cosmetics/test_hat.png", null, "abc123",
				null, null, CONFIG);
		assertSame(base, base.with(null, null), "nichts gewählt → dasselbe Objekt");
		assertEquals(1, base.count);
		Map<String, Boolean> m = new LinkedHashMap<String, Boolean>();
		m.put("zeta", true);
		m.put("beard", false);
		m.put("Nope", false);
		HatInfo h = base.with(m, 0);
		assertEquals(1, h.count, "mindestens 1");
		assertEquals(Arrays.asList("beard", "zeta"), new java.util.ArrayList<String>(h.options.keySet()), "sortiert, nur gültige");
		Map<String, Boolean> same = new LinkedHashMap<String, Boolean>();
		same.put("beard", false);
		same.put("zeta", true);
		assertEquals(h, base.with(same, 1), "Reihenfolge egal");
		assertEquals(h.hashCode(), base.with(same, 1).hashCode());
		assertEquals(base.assetKey(), h.assetKey());
		assertEquals(base.id, h.id);
		assertEquals(base.modelUrl, h.modelUrl);
	}
}
