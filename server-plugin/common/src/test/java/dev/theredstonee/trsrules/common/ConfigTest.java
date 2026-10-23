package dev.theredstonee.trsrules.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
	/** The example from the contract (§2). */
	static final String CONTRACT_EXAMPLE = ""
			+ "modules:\n"
			+ "  minimap: false\n"
			+ "settings:\n"
			+ "  zoom.factor: {max: 4}\n"
			+ "  cinematic.freecamFairPlay: true\n"
			+ "worlds:\n"
			+ "  pvp_arena:\n"
			+ "    modules: {zoom: false, freelook: false}\n"
			+ "message: \"\"\n"
			+ "missing-client:\n"
			+ "  action: kick\n"
			+ "  timeout-seconds: 15\n"
			+ "  notify-message: \"n\"\n"
			+ "  kick-message: \"k\"\n"
			+ "bypass-permission: trsrules.bypass\n";

	private static RulesConfig parse(String yaml, ScopeKind kind) {
		ConfigLoader.Result r = ConfigLoader.parse(yaml, kind);
		assertTrue(r.ok(), () -> "parse error: " + r.error);
		return r.config;
	}

	@Test
	void defaultConfigsParseCleanAndLockNothing() {
		for (ScopeKind kind : ScopeKind.values()) {
			String text = ConfigLoader.defaultConfig(kind);
			assertFalse(text.matches("(?s).*%[A-Z_]+%.*"), "unreplaced placeholder in " + kind);
			RulesConfig cfg = parse(text, kind);
			assertEquals(0, cfg.warnings().size(), () -> kind + ": " + cfg.warnings());
			assertEquals("{\"v\":1,\"modules\":{}}", cfg.resolve(null).toJson());
			assertEquals("{\"v\":1,\"modules\":{}}", cfg.resolve("anything").toJson());
			assertEquals(MissingClientPolicy.Action.IGNORE, cfg.missingClient().action());
			assertEquals(10, cfg.missingClient().timeoutSeconds());
			assertEquals("trsrules.bypass", cfg.bypassPermission());
			assertTrue(text.contains("serverrules.bypass"), "team bypass note must be in the default config");
			assertTrue(text.contains(kind.configKey() + ": {}"));
		}
	}

	@Test
	void defaultConfigListsEveryModuleCommentedOut() {
		String text = ConfigLoader.defaultConfig(ScopeKind.WORLDS);
		assertEquals(79, ModuleIds.ALL.size());
		for (String id : ModuleIds.ALL) {
			assertTrue(text.contains("\n  # " + id + ": false "), "missing module " + id);
			assertTrue(ModuleLabels.has(id), "missing label " + id);
		}
		for (String cat : ModuleIds.BY_CATEGORY.keySet()) assertTrue(text.contains("# --- " + cat + " ---"));
	}

	@Test
	void uncommentedDefaultModulesAllParse() {
		// Uncomment every module line → every module locked off, no warnings.
		String text = ConfigLoader.defaultConfig(ScopeKind.WORLDS).replaceAll("(?m)^  # ([A-Za-z]+): false", "  $1: false");
		RulesConfig cfg = parse(text, ScopeKind.WORLDS);
		assertEquals(0, cfg.warnings().size(), () -> cfg.warnings().toString());
		assertEquals(79, cfg.global().modules().size());
		assertEquals(Boolean.FALSE, cfg.global().moduleState("streamerMode"));
	}

	@Test
	void contractExampleParses() {
		RulesConfig cfg = parse(CONTRACT_EXAMPLE, ScopeKind.WORLDS);
		assertEquals(0, cfg.warnings().size(), () -> cfg.warnings().toString());
		assertEquals("{\"v\":1,\"modules\":{\"minimap\":{\"enabled\":false},"
				+ "\"zoom\":{\"settings\":{\"factor\":{\"max\":4.0}}},"
				+ "\"cinematic\":{\"settings\":{\"freecamFairPlay\":{\"value\":true}}}}}", cfg.resolve("world").toJson());
		assertEquals(MissingClientPolicy.Action.KICK, cfg.missingClient().action());
		assertEquals(15, cfg.missingClient().timeoutSeconds());
		assertEquals("n", cfg.missingClient().notifyMessage());
		assertEquals("k", cfg.missingClient().kickMessage());
	}

	@Test
	void worldRulesWinOverGlobal() {
		RulesConfig cfg = parse(CONTRACT_EXAMPLE, ScopeKind.WORLDS);
		RuleSet arena = cfg.resolve("pvp_arena");
		assertEquals(Boolean.FALSE, arena.moduleState("minimap")); // global stays
		assertEquals(Boolean.FALSE, arena.moduleState("freelook")); // world adds
		assertEquals("{\"v\":1,\"modules\":{\"minimap\":{\"enabled\":false},"
				+ "\"zoom\":{\"enabled\":false,\"settings\":{\"factor\":{\"max\":4.0}}},"
				+ "\"cinematic\":{\"settings\":{\"freecamFairPlay\":{\"value\":true}}},"
				+ "\"freelook\":{\"enabled\":false}}}", arena.toJson());
	}

	@Test
	void worldOverridesSameKeysAndFreeLiftsLocks() {
		String yaml = "modules: {minimap: false, zoom: true}\n"
				+ "settings: {zoom.factor: {max: 4}, fullbright.level: {max: 100}}\n"
				+ "message: global\n"
				+ "worlds:\n"
				+ "  creative:\n"
				+ "    modules: {minimap: free, zoom: false}\n"
				+ "    settings: {zoom.factor: {min: 2, max: 8}, fullbright.level: free}\n"
				+ "    message: \"\"\n"
				+ "  quiet:\n"
				+ "    message: hi\n";
		RulesConfig cfg = parse(yaml, ScopeKind.WORLDS);
		assertEquals(0, cfg.warnings().size(), () -> cfg.warnings().toString());
		RuleSet creative = cfg.resolve("creative");
		assertNull(creative.moduleState("minimap"));
		assertEquals(Boolean.FALSE, creative.moduleState("zoom"));
		assertEquals("2.0..8.0", creative.settingRule("zoom", "factor").toString());
		assertNull(creative.settingRule("fullbright", "level"));
		assertEquals("{\"v\":1,\"modules\":{\"zoom\":{\"enabled\":false,\"settings\":{\"factor\":{\"min\":2.0,\"max\":8.0}}}}}",
				creative.toJson());
		assertEquals("hi", cfg.resolve("quiet").message());
		assertEquals("global", cfg.resolve("unknown_world").message());
		// global itself never contains "free"
		assertEquals(Boolean.FALSE, cfg.resolve(null).moduleState("minimap"));
	}

	@Test
	void scopeMatchingIgnoresCaseAndNamespace() {
		String yaml = "worlds:\n  The_Nether:\n    modules: {minimap: false}\n";
		RulesConfig cfg = parse(yaml, ScopeKind.WORLDS);
		assertEquals(Boolean.FALSE, cfg.resolve("The_Nether").moduleState("minimap"));
		assertEquals(Boolean.FALSE, cfg.resolve("the_nether").moduleState("minimap"));
		assertEquals(Boolean.FALSE, cfg.resolve("minecraft:the_nether").moduleState("minimap"));
		assertNull(cfg.resolve("minecraft:overworld").moduleState("minimap"));
		assertNull(cfg.resolve(null).moduleState("minimap"));
	}

	@Test
	void proxiesUseServersSection() {
		String yaml = "servers:\n  pvp:\n    modules: {zoom: false}\nworlds:\n  w:\n    modules: {fps: false}\n";
		RulesConfig cfg = parse(yaml, ScopeKind.SERVERS);
		assertEquals(Boolean.FALSE, cfg.resolve("pvp").moduleState("zoom"));
		assertNull(cfg.resolve("w").moduleState("fps"));
		assertEquals(1, cfg.warnings().size(), () -> cfg.warnings().toString());
		assertTrue(cfg.warnings().get(0).contains("'worlds:' is not used"));
	}

	@Test
	void settingValueForms() {
		String yaml = "settings:\n"
				+ "  a.bool: false\n"
				+ "  a.num: 3\n"
				+ "  a.choice: FANCY\n"
				+ "  a.long: {value: 1.5}\n"
				+ "  a.min: {min: 0.5}\n"
				+ "  zoom: {factor: {max: 6}}\n"; // nested form
		RulesConfig cfg = parse(yaml, ScopeKind.WORLDS);
		assertEquals("{\"v\":1,\"modules\":{\"a\":{\"settings\":{\"bool\":{\"value\":false},\"num\":{\"value\":3.0},"
				+ "\"choice\":{\"value\":\"FANCY\"},\"long\":{\"value\":1.5},\"min\":{\"min\":0.5}}},"
				+ "\"zoom\":{\"settings\":{\"factor\":{\"max\":6.0}}}}}", cfg.resolve(null).toJson());
		// only the unknown module "a" is reported
		assertEquals(5, cfg.warnings().size(), () -> cfg.warnings().toString());
	}

	/** Exactly the forms used in the docs page (5.developers/07.server-rules.md) and on the website (/servers). */
	@Test
	void documentedSettingForms() {
		String yaml = "settings:\n"
				+ "  zoom.factor: {max: 4}\n"
				+ "  cinematic.freecamFairPlay: true\n"
				+ "  fullbright.level: {max: 200}\n"
				+ "  minimap.mode: {value: SMOOTH}\n"
				+ "  zoom.cinematic: false\n"
				+ "  minimap.size: 128\n"
				+ "  freelook.mode: SMOOTH\n"
				+ "  crosshair.size: {min: 2, max: 6}\n";
		RulesConfig cfg = parse(yaml, ScopeKind.WORLDS);
		assertEquals(0, cfg.warnings().size(), () -> cfg.warnings().toString());
		RuleSet g = cfg.resolve(null);
		// a bare value means {value: …}
		assertEquals(SettingRule.value(true), g.settingRule("cinematic", "freecamFairPlay"));
		assertEquals(SettingRule.value(false), g.settingRule("zoom", "cinematic"));
		assertEquals(SettingRule.value(128), g.settingRule("minimap", "size"));
		assertEquals(SettingRule.value("SMOOTH"), g.settingRule("freelook", "mode"));
		assertEquals(SettingRule.value("SMOOTH"), g.settingRule("minimap", "mode"));
		assertEquals(SettingRule.range(null, 4.0), g.settingRule("zoom", "factor"));
		assertEquals(SettingRule.range(null, 200.0), g.settingRule("fullbright", "level"));
		assertEquals(SettingRule.range(2.0, 6.0), g.settingRule("crosshair", "size"));
		assertEquals("{\"v\":1,\"modules\":{"
				+ "\"zoom\":{\"settings\":{\"factor\":{\"max\":4.0},\"cinematic\":{\"value\":false}}},"
				+ "\"cinematic\":{\"settings\":{\"freecamFairPlay\":{\"value\":true}}},"
				+ "\"fullbright\":{\"settings\":{\"level\":{\"max\":200.0}}},"
				+ "\"minimap\":{\"settings\":{\"mode\":{\"value\":\"SMOOTH\"},\"size\":{\"value\":128.0}}},"
				+ "\"freelook\":{\"settings\":{\"mode\":{\"value\":\"SMOOTH\"}}},"
				+ "\"crosshair\":{\"settings\":{\"size\":{\"min\":2.0,\"max\":6.0}}}}}", g.toJson());
	}

	/** The config reference in README.md must stay valid. */
	@Test
	void readmeExampleParses() throws IOException {
		Path readme = java.nio.file.Paths.get("..", "README.md");
		String text = new String(Files.readAllBytes(readme), Protocol.UTF8);
		int start = text.indexOf("```yaml\nconfig-version: 1");
		assertTrue(start >= 0, "yaml block not found in README");
		start = text.indexOf('\n', start) + 1;
		String yaml = text.substring(start, text.indexOf("```", start));
		RulesConfig cfg = parse(yaml, ScopeKind.WORLDS);
		assertEquals(0, cfg.warnings().size(), () -> cfg.warnings().toString());
		RuleSet arena = cfg.resolve("pvp_arena");
		assertEquals(Boolean.FALSE, arena.moduleState("zoom"));
		assertEquals(SettingRule.range(null, 100.0), arena.settingRule("fullbright", "level"));
		assertNull(cfg.resolve("creative").moduleState("minimap"));
		assertEquals(SettingRule.value("SMOOTH"), cfg.resolve(null).settingRule("minimap", "mode"));
	}

	@Test
	void brokenEntriesAreSkippedWithWarnings() {
		String yaml = "modules:\n"
				+ "  minimapp: false\n"     // typo → warning, still sent
				+ "  zoom: maybe\n"         // bad value → skipped
				+ "settings:\n"
				+ "  zoom.factor: {min: 5, max: 2}\n" // min > max → skipped
				+ "  fullbright: 3\n"       // no dot, not nested → skipped
				+ "  zoom.speed: {foo: 1}\n" // no min/max/value → skipped
				+ "missing-client:\n"
				+ "  action: explode\n"
				+ "  timeout-seconds: 0\n"
				+ "colour: red\n";
		RulesConfig cfg = parse(yaml, ScopeKind.WORLDS);
		List<String> w = cfg.warnings();
		assertEquals(Boolean.FALSE, cfg.global().moduleState("minimapp"));
		assertNull(cfg.global().moduleState("zoom"));
		assertNull(cfg.global().settingRule("zoom", "factor"));
		assertNull(cfg.global().settingRule("zoom", "speed"));
		assertEquals(MissingClientPolicy.Action.IGNORE, cfg.missingClient().action());
		assertEquals(MissingClientPolicy.MIN_TIMEOUT_SECONDS, cfg.missingClient().timeoutSeconds());
		assertTrue(w.size() >= 8, w::toString);
		assertTrue(String.valueOf(w).contains("minimapp"));
		assertTrue(String.valueOf(w).contains("colour"));
	}

	@Test
	void syntaxErrorsAndDuplicatesAreReported() {
		ConfigLoader.Result r = ConfigLoader.parse("modules:\n  zoom: false\n   fps: [", ScopeKind.WORLDS);
		assertFalse(r.ok());
		assertNotNull(r.error);
		ConfigLoader.Result dup = ConfigLoader.parse("modules:\n  zoom: false\n  zoom: true\n", ScopeKind.WORLDS);
		assertFalse(dup.ok());
		ConfigLoader.Result list = ConfigLoader.parse("- a\n- b\n", ScopeKind.WORLDS);
		assertFalse(list.ok());
		assertTrue(ConfigLoader.parse("", ScopeKind.WORLDS).ok());
	}

	@Test
	void bypassPermissionCanBeDisabled() {
		assertNull(parse("bypass-permission: \"\"\n", ScopeKind.WORLDS).bypassPermission());
		assertEquals("my.perm", parse("bypass-permission: my.perm\n", ScopeKind.WORLDS).bypassPermission());
	}

	@Test
	void bypassPlayersAndOpLevel() {
		RulesConfig cfg = parse("bypass-players: [Notch, 069A79F4-44E9-4726-A5BE-FCA90E38AAF5, \"bad name!\"]\nbypass-op-level: 3\n", ScopeKind.WORLDS);
		assertTrue(cfg.isBypassPlayer("notch", null));
		assertTrue(cfg.isBypassPlayer("x", java.util.UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
		assertFalse(cfg.isBypassPlayer("Steve", java.util.UUID.randomUUID()));
		assertEquals(2, cfg.bypassPlayers().size());
		assertTrue(String.valueOf(cfg.warnings()).contains("bad name!"));
		assertEquals(3, cfg.bypassOpLevel());

		RulesConfig defaults = parse("", ScopeKind.WORLDS);
		assertTrue(defaults.bypassPlayers().isEmpty());
		assertEquals(0, defaults.bypassOpLevel());

		RulesConfig clamped = parse("bypass-op-level: 7\n", ScopeKind.WORLDS);
		assertEquals(4, clamped.bypassOpLevel());
		assertTrue(String.valueOf(clamped.warnings()).contains("bypass-op-level"));
	}

	@Test
	void oversizedRulesAreReported() {
		StringBuilder sb = new StringBuilder("message: \"");
		for (int i = 0; i < Protocol.MAX_PAYLOAD; i++) sb.append('x');
		sb.append("\"\n");
		RulesConfig cfg = parse(sb.toString(), ScopeKind.WORLDS);
		assertTrue(String.valueOf(cfg.warnings()).contains("protocol limit"));
	}

	@Test
	void loadWritesDefaultOnceAndKeepsUserFile(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("sub").resolve("config.yml");
		ConfigLoader.Result first = ConfigLoader.load(file, ScopeKind.WORLDS);
		assertTrue(first.ok());
		assertTrue(Files.exists(file));
		Files.write(file, "\uFEFFmodules: {zoom: false}\n".getBytes(Protocol.UTF8));
		ConfigLoader.Result second = ConfigLoader.load(file, ScopeKind.WORLDS);
		assertTrue(second.ok(), () -> second.error);
		assertEquals(Boolean.FALSE, second.config.global().moduleState("zoom"));
	}
}
