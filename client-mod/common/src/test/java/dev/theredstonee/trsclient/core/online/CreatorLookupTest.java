package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.creator.CreatorTier;
import dev.theredstonee.trsclient.core.creator.Milestone;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static dev.theredstonee.trsclient.core.online.OnlineTest.CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Creator-Programm im Lookup (API.md §35.5): {@code creator}, {@code fan}, Ereignisse {@code fan}/{@code fan_creator}. */
class CreatorLookupTest {
	static final String A = "0123456789abcdef0123456789abcdef";
	static final String B = "fedcba9876543210fedcba9876543210";
	static final String C = "11112222333344445555666677778888";
	static final String SHA = "aa" + repeat('b', 62);

	static String repeat(char c, int n) {
		char[] a = new char[n];
		Arrays.fill(a, c);
		return new String(a);
	}

	static String symbol(String extra) {
		return "{\"url\":\"/v1/creator-symbols/" + SHA + ".png\",\"size\":16,\"frames\":4,\"frameMs\":200,\"sha256\":\"" + SHA + "\"" + extra + "}";
	}

	@Test
	void lookupParsesCreatorAndFan() throws Exception {
		FakeHttp http = new FakeHttp().json("POST /v1/players/lookup", 200, "{\"players\":["
				+ "{\"uuid\":\"" + A + "\",\"badge\":true,\"creator\":{\"tier\":\"partner\"}},"
				+ "{\"uuid\":\"" + B + "\",\"fan\":{\"creator\":\"" + A + "\",\"name\":\"Pixelmaus\",\"symbol\":" + symbol("")
				+ ",\"milestone\":\"gold\"}},"
				+ "{\"uuid\":\"" + C + "\",\"creator\":{\"tier\":\"emperor\"},\"fan\":{\"creator\":\"" + A + "\",\"symbol\":"
				+ "{\"url\":\"https://evil.example/x.png\",\"size\":16,\"sha256\":\"" + SHA + "\"}}}"
				+ "]}");
		Map<String, PlayerInfo> out = new TrsApi(http, CONFIG).lookup("t", Arrays.asList(A, B, C));
		assertEquals(CreatorTier.PARTNER, out.get(A).creator);
		assertNull(out.get(A).fan);
		FanInfo fan = out.get(B).fan;
		assertNotNull(fan);
		assertEquals(A, fan.creator);
		assertEquals("Pixelmaus", fan.name);
		assertEquals(Milestone.GOLD, fan.milestone);
		assertEquals(CONFIG.apiBase() + "/v1/creator-symbols/" + SHA + ".png", fan.symbol.url);
		assertEquals(16, fan.symbol.size);
		assertEquals(4, fan.symbol.frames);
		assertEquals(200, fan.symbol.frameMs);
		assertFalse(out.get(B).badge);
		// Unbekannte Stufe und fremde Symbol-Adresse → nichts zeigen, Spieler bleibt erhalten.
		assertNull(out.get(C).creator);
		assertNull(out.get(C).fan);
	}

	@Test
	void oldServersWithoutCreatorFieldsStillWork() throws Exception {
		FakeHttp http = new FakeHttp().json("POST /v1/players/lookup", 200, "{\"players\":[{\"uuid\":\"" + A + "\",\"badge\":true}]}");
		PlayerInfo info = new TrsApi(http, CONFIG).lookup("t", Collections.singletonList(A)).get(A);
		assertTrue(info.badge);
		assertNull(info.creator);
		assertNull(info.fan);
	}

	@Test
	void symbolValuesAreCheckedStrictly() {
		assertNotNull(SymbolInfo.of("/v1/creator-symbols/x.png", 64, 8, 50, SHA, CONFIG));
		assertNull(SymbolInfo.of("/v1/creator-symbols/x.png", 65, 1, 150, SHA, CONFIG), "zu groß");
		assertNull(SymbolInfo.of("/v1/creator-symbols/x.png", 15, 1, 150, SHA, CONFIG), "zu klein");
		assertNull(SymbolInfo.of("/v1/creator-symbols/x.png", 16, 9, 150, SHA, CONFIG), "zu viele Bilder");
		assertNull(SymbolInfo.of("/v1/creator-symbols/x.png", 16, 2, 20, SHA, CONFIG), "zu schnell");
		assertNull(SymbolInfo.of("/v1/creator-symbols/x.png", 16, 1, 150, "abc", CONFIG), "keine Prüfsumme");
		assertNull(SymbolInfo.of(null, 16, 1, 150, SHA, CONFIG));
		SymbolInfo still = SymbolInfo.of("/v1/creator-symbols/x.png", 32, null, null, SHA.toUpperCase(), CONFIG);
		assertEquals(1, still.frames);
		assertEquals(SymbolInfo.DEFAULT_FRAME_MS, still.frameMs);
		assertEquals(SHA, still.sha256, "Prüfsumme klein");
		SymbolInfo anim = SymbolInfo.of("/v1/creator-symbols/x.png", 16, 4, 100, SHA, CONFIG);
		assertEquals(0, anim.frameAt(0, 4));
		assertEquals(1, anim.frameAt(150, 4));
		assertEquals(3, anim.frameAt(399, 4));
		assertEquals(0, anim.frameAt(400, 4));
		assertEquals(1, anim.frameAt(300, 2), "weniger geladene Bilder → nur die");
		assertNull(FanInfo.of("nope", null, anim, null));
		assertNull(FanInfo.of(A, null, null, "gold"));
		assertNull(FanInfo.of(A, null, anim, "platinum").milestone, "unbekannter Meilenstein → ohne Rahmen");
	}

	@Test
	void fanEventsAreParsed() {
		PlayerEvent fan = PlayerEvent.parse("fan", "{\"type\":\"fan\",\"uuid\":\"" + B + "\"}");
		assertEquals("fan", fan.type);
		assertEquals(B, fan.uuid);
		PlayerEvent fc = PlayerEvent.parse("fan_creator", "{\"type\":\"fan_creator\",\"creator\":\"" + A + "\"}");
		assertEquals("fan_creator", fc.type);
		assertEquals(A, fc.uuid, "uuid = Creator");
		assertNull(PlayerEvent.parse("fan_creator", "{\"creator\":\"x\"}"));
		assertNull(PlayerEvent.parse("fan", "{}"));
	}

	@Test
	void fanCreatorEventInvalidatesOnlyThatCreatorsFans() {
		List<String> all = Arrays.asList(A, B, C);
		SymbolInfo s = SymbolInfo.of("/v1/creator-symbols/x.png", 16, 1, 150, SHA, CONFIG);
		Map<String, PlayerInfo> results = new java.util.HashMap<>();
		results.put(B, new PlayerInfo(false, null, null, null, FanInfo.of(A, "P", s, null)));
		results.put(C, new PlayerInfo(true, null, null, null, FanInfo.of(B, "Q", s, null)));
		PlayerDirectory fresh = new PlayerDirectory();
		fresh.observe(all, 0);
		assertEquals(3, fresh.nextBatch(0).size());
		fresh.complete(all, results, 100);
		assertEquals(1, fresh.invalidateFansOf(A));
		assertEquals(Collections.singletonList(B), fresh.nextBatch(5_000));
		assertEquals(0, fresh.invalidateFansOf("nope"));
		assertEquals(2, fresh.fans().size());
		// Live-Abzeichen ändern die Fan-Infos nicht.
		fresh.applyBadge(B, true);
		assertNotNull(fresh.get(B).fan);
		assertTrue(fresh.get(B).badge);
	}
}
