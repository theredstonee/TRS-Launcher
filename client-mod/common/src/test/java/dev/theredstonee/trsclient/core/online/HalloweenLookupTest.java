package dev.theredstonee.trsclient.core.online;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static dev.theredstonee.trsclient.core.online.OnlineTest.CONFIG;
import static dev.theredstonee.trsclient.core.online.OnlineTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Halloween im Lookup (API.md §36): Profil-Abzeichen, Spuk-Effekte, Flügel. */
class HalloweenLookupTest {
	static final String A = "0123456789abcdef0123456789abcdef";
	static final String B = "fedcba9876543210fedcba9876543210";

	@Test
	void lookupParsesBadgesFxAndWings() throws Exception {
		FakeHttp http = new FakeHttp().json("POST /v1/players/lookup", 200, "{\"players\":["
				+ "{\"uuid\":\"" + A + "\",\"badge\":true,\"badges\":[\"halloween2026\",\"pumpkinhunter2026\",\"BAD ID\",\"halloween2026\"],"
				+ "\"fx\":[\"hits\",\"trail\",\"fireworks\"],"
				+ "\"cosmetics\":{\"wings\":{\"id\":\"bat_wings\",\"format\":2,\"model\":\"/v1/cosmetics/bat_wings/model.json\",\"url\":\"/v1/cosmetics/bat_wings.png\",\"hash\":\"abc123\"}}},"
				+ "{\"uuid\":\"" + B + "\",\"badge\":false}]}");
		Map<String, PlayerInfo> out = new TrsApi(http, CONFIG).lookup(TOKEN, Arrays.asList(A, B));
		PlayerInfo a = out.get(A);
		assertEquals(Arrays.asList("halloween2026", "pumpkinhunter2026"), a.badges);
		assertTrue(a.hasBadge("pumpkinhunter2026"));
		assertEquals(PlayerInfo.FX_HITS | PlayerInfo.FX_TRAIL, a.fx);
		assertEquals("bat_wings", a.wings.id);
		assertTrue(a.wings.v2());
		PlayerInfo b = out.get(B);
		assertEquals(Collections.<String>emptyList(), b.badges);
		assertEquals(0, b.fx);
		assertNull(b.wings);
		assertFalse(b.withBadge(true).hasBadge("halloween2026"));
		assertTrue(a.withBadge(false).hasBadge("halloween2026"), "Live-Abzeichen ändert die Profil-Abzeichen nicht");
	}

	@Test
	void fxIgnoresUnknownValues() {
		assertEquals(0, TrsApi.fxBits(null));
		assertEquals(PlayerInfo.FX_TRAIL, TrsApi.fxBits(Arrays.asList("trail", "x")));
		assertNull(TrsApi.badgeIds(null));
	}
}
