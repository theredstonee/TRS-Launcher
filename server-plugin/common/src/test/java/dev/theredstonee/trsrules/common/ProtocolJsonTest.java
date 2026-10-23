package dev.theredstonee.trsrules.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProtocolJsonTest {
	/** The rules example from the contract (§1), byte for byte. */
	@Test
	void rulesJsonMatchesProtocolExample() {
		RuleSet rs = RuleSet.empty()
				.module("minimap", false)
				.module("fullbright", false)
				.setting("zoom", "factor", SettingRule.range(null, 4.0))
				.setting("cinematic", "freecamFairPlay", SettingRule.value(true))
				.module("toggleSprint", true)
				.message("Optional: kurzer Text, den der Client einmal anzeigt");
		assertEquals("{\"v\":1,"
				+ "\"modules\":{"
				+ "\"minimap\":{\"enabled\":false},"
				+ "\"fullbright\":{\"enabled\":false},"
				+ "\"zoom\":{\"settings\":{\"factor\":{\"max\":4.0}}},"
				+ "\"cinematic\":{\"settings\":{\"freecamFairPlay\":{\"value\":true}}},"
				+ "\"toggleSprint\":{\"enabled\":true}"
				+ "},"
				+ "\"message\":\"Optional: kurzer Text, den der Client einmal anzeigt\"}", rs.toJson());
	}

	@Test
	void emptyRulesAreStillAValidMessage() {
		assertEquals("{\"v\":1,\"modules\":{}}", RuleSet.empty().toJson());
		assertEquals("{\"v\":1,\"modules\":{}}", RuleSet.empty().message("").toJson());
	}

	@Test
	void stringsAreEscaped() {
		String json = RuleSet.empty().message("a \"b\" \\ c\nd\u0001 ü").toJson();
		assertEquals("{\"v\":1,\"modules\":{},\"message\":\"a \\\"b\\\" \\\\ c\\nd\\u0001 ü\"}", json);
		Map<String, Object> back = Json.parseObject(json);
		assertEquals("a \"b\" \\ c\nd\u0001 ü", back.get("message"));
	}

	@Test
	void rulesRoundTripThroughParser() {
		RuleSet rs = RuleSet.empty().module("zoom", true)
				.setting("zoom", "factor", SettingRule.range(2.0, 4.0))
				.setting("hud", "mode", SettingRule.value("COMPACT"))
				.setting("x", "n", SettingRule.value(7))
				.message("m");
		assertEquals(rs.toJson(), RuleSet.fromJson(rs.toJson()).toJson());
	}

	@Test
	void overlayTopWinsPerEntry() {
		RuleSet backend = RuleSet.empty().module("minimap", false).module("zoom", false)
				.setting("zoom", "factor", SettingRule.range(null, 2.0))
				.setting("zoom", "speed", SettingRule.value(1)).message("backend");
		RuleSet proxy = RuleSet.empty().module("zoom", true).setting("zoom", "factor", SettingRule.range(null, 4.0));
		RuleSet merged = backend.overlay(proxy);
		assertEquals("{\"v\":1,\"modules\":{\"minimap\":{\"enabled\":false},"
				+ "\"zoom\":{\"enabled\":true,\"settings\":{\"factor\":{\"max\":4.0},\"speed\":{\"value\":1.0}}}},"
				+ "\"message\":\"backend\"}", merged.toJson());
		// inputs untouched
		assertEquals(Boolean.FALSE, backend.moduleState("zoom"));
	}

	@Test
	void jsonParserHandlesTypesAndRejectsGarbage() {
		Map<String, Object> m = Json.parseObject(" {\"a\": [1, 2.5, -3e2, true, false, null, \"\\u00e4\"], \"b\": {}} ");
		List<?> a = (List<?>) m.get("a");
		assertEquals(Arrays.asList(1L, 2.5, -300.0, true, false, null, "ä"), a);
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,}"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("[1] x"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("\"abc"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("NaN"));
		StringBuilder deep = new StringBuilder();
		for (int i = 0; i < 100; i++) deep.append('[');
		assertThrows(IllegalArgumentException.class, () -> Json.parse(deep.toString()));
	}

	@Test
	void helloParsing() {
		byte[] ok = "{\"v\":1,\"client\":\"trsclient\",\"version\":\"0.20.0\",\"mc\":\"1.21.11\",\"loader\":\"fabric\",\"modules\":[\"fps\",\"zoom\"]}"
				.getBytes(Protocol.UTF8);
		Hello h = Hello.parse(ok);
		assertNotNull(h);
		assertEquals(1, h.protocolVersion());
		assertEquals("0.20.0", h.clientVersion());
		assertEquals("1.21.11", h.minecraft());
		assertEquals("fabric", h.loader());
		assertEquals(Arrays.asList("fps", "zoom"), h.modules());
		assertEquals("0.20.0, MC 1.21.11, fabric, 2 modules", h.describe());

		// newer protocol versions and missing optional fields are fine
		Hello v2 = Hello.parse("{\"v\":2,\"client\":\"trsclient\",\"extra\":{\"x\":1}}".getBytes(Protocol.UTF8));
		assertNotNull(v2);
		assertEquals(2, v2.protocolVersion());

		assertNull(Hello.parse("{\"v\":1,\"client\":\"other\"}".getBytes(Protocol.UTF8)));
		assertNull(Hello.parse("not json".getBytes(Protocol.UTF8)));
		assertNull(Hello.parse("[1,2]".getBytes(Protocol.UTF8)));
		assertNull(Hello.parse(new byte[0]));
		assertNull(Hello.parse(null));
		byte[] huge = new byte[Protocol.MAX_PAYLOAD + 1];
		Arrays.fill(huge, (byte) ' ');
		assertNull(Hello.parse(huge));
	}

	@Test
	void channelHelpers() {
		assertEquals(Protocol.RULES, Protocol.rulesChannelFor(767));
		assertEquals(Protocol.RULES, Protocol.rulesChannelFor(393));
		assertEquals(Protocol.RULES_LEGACY, Protocol.rulesChannelFor(340));
		assertEquals(Protocol.RULES_LEGACY, Protocol.rulesChannelFor(47));
		assertEquals(true, Protocol.isHelloChannel("TRS|Hello"));
		assertEquals(true, Protocol.isRulesChannel("trsclient:rules"));
		assertEquals(false, Protocol.isHelloChannel("trsclient:rules"));
	}

	@Test
	void colorCodes() {
		assertEquals("\u00a7cRed \u00a7lbold & more &z &", Text.colorize("&cRed &Lbold && more &z &"));
	}
}
