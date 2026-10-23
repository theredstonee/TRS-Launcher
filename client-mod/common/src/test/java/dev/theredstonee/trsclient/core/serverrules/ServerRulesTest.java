package dev.theredstonee.trsclient.core.serverrules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.UiKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerRulesTest {
	/** Das Beispiel aus dem Protokoll (Vertrag Abschnitt 1). */
	static final String EXAMPLE = "{\"v\":1,"
			+ "\"modules\":{"
			+ "\"minimap\":{\"enabled\":false},"
			+ "\"fullbright\":{\"enabled\":false},"
			+ "\"zoom\":{\"settings\":{\"factor\":{\"max\":4.0}}},"
			+ "\"cinematic\":{\"settings\":{\"freecamFairPlay\":{\"value\":true}}},"
			+ "\"toggleSprint\":{\"enabled\":true}"
			+ "},"
			+ "\"message\":\"Optional: kurzer Text, den der Client einmal anzeigt\"}";

	private TrsModules m;
	private ServerRules rules;
	private final List<String> notices = new ArrayList<String>();

	@BeforeEach
	void setUp() {
		m = new TrsModules();
		rules = new ServerRules(m.registry, "0.20.0", "1.21.11", "fabric", new ServerRules.SilentPlatform() {
			@Override
			public void notice(String text) {
				notices.add(text);
			}
		});
		ServerRules.install(rules);
		ServerRules.teamForTests(false);
		BypassPrompt.cancel();
	}

	@AfterEach
	void tearDown() {
		ServerRules.install(null);
		ServerRules.teamForTests(false);
		BypassPrompt.cancel();
	}

	private static byte[] bytes(String json) {
		return json.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void parsesTheContractExample() {
		RuleSet set = RuleSet.parse(bytes(EXAMPLE));
		assertNotNull(set);
		assertEquals(1, set.version);
		assertEquals(Arrays.asList("minimap", "fullbright", "zoom", "cinematic", "toggleSprint"),
				new ArrayList<String>(set.modules.keySet()));
		assertEquals(Boolean.FALSE, set.module("minimap").enabled);
		assertEquals(Boolean.TRUE, set.module("toggleSprint").enabled);
		assertNull(set.module("zoom").enabled);
		SettingLock factor = set.module("zoom").settings.get("factor");
		assertNull(factor.value);
		assertTrue(Double.isNaN(factor.min));
		assertEquals(4.0, factor.max, 1e-9);
		assertEquals(Boolean.TRUE, set.module("cinematic").settings.get("freecamFairPlay").value);
		assertEquals("Optional: kurzer Text, den der Client einmal anzeigt", set.message);
	}

	@Test
	void appliesLocksWithoutTouchingOwnValues() {
		m.minimap.setEnabled(true);
		m.fullbright.setEnabled(true);
		m.toggleSprint.setEnabled(false);
		m.zoomFactor.set(8);
		m.cinematic.freecamFairPlay.set(false);

		assertTrue(rules.onRules(bytes(EXAMPLE)));
		assertTrue(rules.received());
		assertFalse(m.minimap.isEnabled(), "gesperrt aus");
		assertTrue(m.minimap.ownEnabled(), "eigener Zustand bleibt");
		assertTrue(m.minimap.serverLocked());
		assertFalse(m.fullbright.isEnabled());
		assertFalse(m.gammaOverride().isPresent(), "Gamma folgt der Sperre");
		assertTrue(m.toggleSprint.isEnabled(), "erzwungen an");
		assertEquals(4.0, m.zoomFactor.get(), 1e-9, "auf max geklemmt");
		assertEquals(8.0, m.zoomFactor.own(), 1e-9);
		assertTrue(m.zoomFactor.serverLocked());
		assertFalse(m.zoomFactor.serverFixed(), "Grenzen: Regler bleibt bedienbar");
		assertTrue(m.zoom.serverRestricted());
		assertFalse(m.zoom.serverLocked(), "Modul selbst nicht gesperrt");
		assertTrue(m.cinematic.freecamFairPlay.get());
		assertTrue(m.cinematic.freecamFairPlay.locked());

		// Umschalten bleibt wirkungslos, gespeichert wird der eigene Wert.
		m.minimap.toggle();
		assertFalse(m.minimap.isEnabled());
		assertTrue(m.minimap.ownEnabled());
		m.cinematic.freecamFairPlay.toggle();
		assertFalse(m.cinematic.freecamFairPlay.own());
		ModuleConfig saved = m.minimap.write();
		assertEquals(Boolean.TRUE, saved.enabled, "Config enthält den eigenen Zustand, nicht die Sperre");
		assertEquals(8.0, m.zoom.write().numbers.get("factor"), 1e-9);
		assertEquals(Boolean.FALSE, m.cinematic.cinematic.write().flags.get("freecamFairPlay"));

		// Hinweistext genau einmal.
		assertEquals(1, notices.size());
		assertTrue(notices.get(0).contains("kurzer Text"));
		rules.onRules(bytes(EXAMPLE));
		assertEquals(1, notices.size(), "gleicher Text nicht noch einmal");
	}

	@Test
	void newRulesReplaceOldOnesCompletely() {
		m.minimap.setEnabled(true);
		rules.onRules(bytes(EXAMPLE));
		assertFalse(m.minimap.isEnabled());
		rules.onRules(bytes("{\"v\":1,\"modules\":{\"freelook\":{\"enabled\":false}}}"));
		assertTrue(m.minimap.isEnabled(), "minimap wieder frei");
		assertFalse(m.minimap.serverLocked());
		assertFalse(m.zoomFactor.serverLocked());
		assertFalse(m.cinematic.freecamFairPlay.locked());
		assertFalse(m.freelook.isEnabled());
		// Leere Regeln: alles frei.
		rules.onRules(bytes("{\"v\":1}"));
		assertFalse(m.freelook.serverLocked());
	}

	@Test
	void disconnectDropsEverything() {
		rules.onRules(bytes(EXAMPLE));
		rules.onDisconnect();
		assertFalse(rules.received());
		assertTrue(rules.rules().isEmpty());
		for (Module mod : m.registry.all()) assertFalse(mod.serverRestricted(), mod.id());
		// Nach neuem Betreten wird der Hinweis wieder gezeigt.
		rules.onJoin();
		rules.onRules(bytes(EXAMPLE));
		assertEquals(2, notices.size());
	}

	@Test
	void brokenOrHugeMessagesAreIgnored() {
		rules.onRules(bytes(EXAMPLE));
		assertFalse(rules.onRules(bytes("{nope")));
		assertFalse(rules.onRules(bytes("[1,2]")));
		assertFalse(rules.onRules(null));
		byte[] huge = new byte[ServerRules.MAX_BYTES + 1];
		Arrays.fill(huge, (byte) ' ');
		assertFalse(rules.onRules(huge));
		assertFalse(m.minimap.isEnabled(), "alte Regeln bleiben");
	}

	@Test
	void unknownThingsAreIgnoredQuietly() {
		String json = "{\"v\":2,\"future\":{\"x\":1},\"modules\":{"
				+ "\"doesNotExist\":{\"enabled\":false},"
				+ "\"zoom\":{\"enabled\":\"no\",\"settings\":{\"nope\":{\"value\":1},\"factor\":{\"value\":true}}},"
				+ "\"minimap\":{\"settings\":{\"shape\":{\"value\":\"square\"},\"size\":{\"value\":999},\"rotate\":{\"value\":\"x\"}}},"
				+ "\"freelook\":{\"settings\":{\"x\":{\"min\":5,\"max\":1}}},"
				+ "\"bad id!\":{\"enabled\":false}"
				+ "},\"message\":42}";
		RuleSet set = RuleSet.parse(json);
		assertNotNull(set);
		assertEquals(2, set.version);
		assertNull(set.message);
		assertFalse(set.modules.containsKey("bad id!"));
		assertFalse(set.modules.containsKey("freelook"), "min > max ist keine Regel");
		assertTrue(rules.onRules(bytes(json)));
		assertFalse(m.zoom.serverRestricted(), "falsche Typen: nichts gesperrt");
		assertEquals(TrsModules.MapShape.SQUARE, m.minimapShape.get(), "Auswahl per Enum-Name, ohne Groß/klein");
		assertTrue(m.minimapShape.serverLocked());
		assertEquals(256.0, m.minimapSize.get(), 1e-9, "fester Wert auf den eigenen Bereich geklemmt");
		assertTrue(m.minimapSize.serverFixed());
		assertFalse(m.minimapRotate.serverLocked(), "String für Schalter wird übergangen");
		// Erzwungene Auswahl: Durchschalten wirkungslos.
		m.minimapShape.cycle();
		assertEquals(TrsModules.MapShape.SQUARE, m.minimapShape.get());
		assertEquals(TrsModules.MapShape.ROUND, m.minimapShape.own());
	}

	@Test
	void unknownChoiceNameIsIgnored() {
		rules.onRules(bytes("{\"modules\":{\"minimap\":{\"settings\":{\"shape\":{\"value\":\"triangle\"}}}}}"));
		assertFalse(m.minimapShape.serverLocked());
	}

	@Test
	void numberRangeWithOnlyMinimum() {
		m.zoomFactor.set(2);
		rules.onRules(bytes("{\"modules\":{\"zoom\":{\"settings\":{\"factor\":{\"min\":3}}}}}"));
		assertEquals(3.0, m.zoomFactor.get(), 1e-9);
		assertEquals(3.0, m.zoomFactor.serverMin(), 1e-9);
		assertTrue(Double.isNaN(m.zoomFactor.serverMax()));
		m.zoomFactor.set(6);
		assertEquals(6.0, m.zoomFactor.get(), 1e-9);
		// Mausrad (nudge) geht vom wirksamen Wert aus.
		m.zoomFactor.set(2);
		m.zoomFactor.nudge(1);
		assertEquals(3.5, m.zoomFactor.own(), 1e-9);
	}

	@Test
	void sanitizesTheMessage() {
		String json = "{\"message\":\"  \\u00a7cRot\\u00a7r und\\n\\tweiter \\u0000 \"}";
		RuleSet set = RuleSet.parse(json);
		assertEquals("Rot und weiter", set.message);
		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 400; i++) longText.append('x');
		assertEquals(RuleSet.MAX_MESSAGE, RuleSet.parse("{\"message\":\"" + longText + "\"}").message.length());
		assertNull(RuleSet.parse("{\"message\":\"   \"}").message);
	}

	@Test
	void helloFollowsTheProtocol() {
		JsonObject o = new JsonParser().parse(new String(rules.helloBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(1, o.get("v").getAsInt());
		assertEquals("trsclient", o.get("client").getAsString());
		assertEquals("0.20.0", o.get("version").getAsString());
		assertEquals("1.21.11", o.get("mc").getAsString());
		assertEquals("fabric", o.get("loader").getAsString());
		JsonArray modules = o.getAsJsonArray("modules");
		List<String> ids = new ArrayList<String>();
		for (int i = 0; i < modules.size(); i++) ids.add(modules.get(i).getAsString());
		assertTrue(ids.contains("minimap"));
		assertTrue(ids.contains("cinematic"));
		assertTrue(ids.contains("fps"));
		assertEquals("trsclient:rules", ServerRules.CHANNEL_RULES);
		assertEquals("trsclient:hello", ServerRules.CHANNEL_HELLO);
		assertEquals("TRS|Rules", ServerRules.LEGACY_RULES);
		assertEquals("TRS|Hello", ServerRules.LEGACY_HELLO);
	}

	@Test
	void localLockWinsOverServerValue() {
		BoolSetting fair = m.cinematic.freecamFairPlay;
		final boolean[] codeSaysFair = {true};
		fair.lock(new java.util.function.BooleanSupplier() {
			@Override
			public boolean getAsBoolean() {
				return codeSaysFair[0];
			}
		}, true);
		fair.serverForce(Boolean.FALSE);
		assertTrue(fair.get(), "§-Code (Fair Play) gilt zusätzlich und ist strenger");
		assertTrue(fair.localLocked());
		codeSaysFair[0] = false;
		assertFalse(fair.get(), "danach der Server-Wert");
		assertTrue(fair.locked());
		fair.clearServerLock();
		assertFalse(fair.locked());
	}

	@Test
	void teamBypassNeedsThePermission() {
		rules.onRules(bytes(EXAMPLE));
		assertFalse(ServerRules.canBypass());
		assertFalse(rules.bypass("minimap"));
		assertFalse(BypassPrompt.ask("minimap", null), "ohne Team-Recht keine Nachfrage");
		assertFalse(BypassPrompt.available(m.minimap));

		ServerRules.team(Boolean.FALSE, Arrays.asList("issues.manage"));
		assertFalse(ServerRules.canBypass());
		ServerRules.team(null, Arrays.asList("issues.manage", ServerRules.BYPASS_PERMISSION));
		assertTrue(ServerRules.canBypass());
		ServerRules.team(Boolean.TRUE, Collections.<String>emptyList());
		assertTrue(ServerRules.canBypass(), "Owner darf alles");
		ServerRules.team(null, null);
		assertFalse(ServerRules.canBypass());
	}

	@Test
	void bypassUnlocksOneModuleUntilDisconnect() {
		m.minimap.setEnabled(true);
		m.zoomFactor.set(8);
		rules.onRules(bytes(EXAMPLE));
		ServerRules.team(null, Collections.singletonList(ServerRules.BYPASS_PERMISSION));
		notices.clear();

		assertTrue(BypassPrompt.available(m.minimap));
		assertTrue(BypassPrompt.available(m.zoomFactor));
		final int[] ran = {0};
		assertTrue(BypassPrompt.ask("minimap", new Runnable() {
			@Override
			public void run() {
				ran[0]++;
			}
		}));
		assertTrue(BypassPrompt.isOpen());
		assertEquals("minimap", BypassPrompt.openFor());
		// Andere Tasten werden geschluckt, Esc = Nein.
		assertTrue(BypassPrompt.key(UiKey.BACKSPACE));
		assertTrue(BypassPrompt.key(UiKey.ESCAPE));
		assertFalse(BypassPrompt.isOpen());
		assertFalse(m.minimap.isEnabled(), "Nein: Sperre bleibt");
		assertEquals(0, ran[0]);

		BypassPrompt.ask("minimap", new Runnable() {
			@Override
			public void run() {
				ran[0]++;
			}
		});
		BypassPrompt.key(UiKey.ENTER);
		assertEquals(1, ran[0], "nach Ja läuft die Folgeaktion");
		assertTrue(m.minimap.isEnabled());
		assertTrue(rules.bypassed("minimap"));
		assertEquals(1, notices.size(), "Hinweis nach der Freigabe");
		assertEquals(4.0, m.zoomFactor.get(), 1e-9, "andere Module bleiben gesperrt");

		// Neue Regeln derselben Verbindung: Freigabe bleibt.
		rules.onRules(bytes(EXAMPLE));
		assertTrue(m.minimap.isEnabled());
		assertFalse(m.minimap.serverLocked());
		assertFalse(m.fullbright.isEnabled());

		// Trennen: Freigabe weg.
		rules.onDisconnect();
		rules.onJoin();
		rules.onRules(bytes(EXAMPLE));
		assertFalse(m.minimap.isEnabled());
		assertFalse(rules.bypassed("minimap"));
	}

	@Test
	void disconnectClosesAnOpenPrompt() {
		rules.onRules(bytes(EXAMPLE));
		ServerRules.team(Boolean.TRUE, null);
		assertTrue(BypassPrompt.ask("minimap", null));
		rules.onDisconnect();
		assertFalse(BypassPrompt.isOpen());
	}
}
