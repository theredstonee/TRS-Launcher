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
