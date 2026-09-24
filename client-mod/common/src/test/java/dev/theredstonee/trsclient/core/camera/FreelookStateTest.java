package dev.theredstonee.trsclient.core.camera;

import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.module.KeySetting;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Freelook: Winkel-Begrenzung, Halten/Umschalten, Serverliste, Tastenverbindung. */
class FreelookStateTest {
	@Test
	void pitchIsClampedLikeVanilla() {
		FreelookState f = new FreelookState();
		f.start(10, 120);
		assertEquals(90.0F, f.pitch());
		f.turn(0, -10000);
		assertEquals(-90.0F, f.pitch());
		f.turn(0, 100);
		assertEquals(-75.0F, f.pitch(), 1e-4);
		assertEquals(0.0F, FreelookState.clampPitch(Float.NaN));
	}

	@Test
	void yawTurnsFreelyButStaysFinite() {
		FreelookState f = new FreelookState();
		f.start(0, 0);
		f.turn(100, 0);
		assertEquals(15.0F, f.yaw(), 1e-4, "0,15° je Einheit wie Vanilla");
		f.turn(-1000, 0);
		assertEquals(-135.0F, f.yaw(), 1e-3);
		for (int i = 0; i < 1000; i++) f.turn(1e6, 0);
		assertTrue(Math.abs(f.yaw()) <= 36000.0F + 150000.0F);
		assertEquals(0.0F, FreelookState.wrapNear(Float.POSITIVE_INFINITY));
	}

	@Test
	void holdMode() {
		FreelookState f = new FreelookState();
		assertFalse(f.wanted(false, false, true, true, false));
		assertTrue(f.wanted(true, false, true, true, false));
		assertFalse(f.wanted(true, false, false, true, false), "Menü offen");
		assertFalse(f.wanted(false, false, true, true, false), "losgelassen");
		assertFalse(f.wanted(true, false, true, false, false), "Modul aus");
	}

	@Test
	void toggleMode() {
		FreelookState f = new FreelookState();
		assertTrue(f.wanted(true, true, true, true, false), "Druck schaltet ein");
		assertTrue(f.wanted(true, true, true, true, false), "Halten ändert nichts");
		assertTrue(f.wanted(false, true, true, true, false), "Loslassen ändert nichts");
		assertTrue(f.wanted(false, true, false, true, false), "Menü offen: bleibt an");
		assertFalse(f.wanted(true, true, true, true, false), "zweiter Druck schaltet aus");
		f.wanted(false, true, true, true, false);
		assertTrue(f.wanted(true, true, true, true, false));
		assertFalse(f.wanted(true, true, true, false, false), "Modul aus beendet");
		assertFalse(f.wanted(false, true, true, true, false), "und bleibt aus");
	}

	@Test
	void blockedServerDisablesAndNotifiesOnce() {
		FreelookState f = new FreelookState();
		assertFalse(f.wanted(true, false, true, true, true));
		assertTrue(f.consumeBlockedNotice());
		assertFalse(f.consumeBlockedNotice());
		assertFalse(f.wanted(true, false, true, true, true), "gehalten: kein neuer Hinweis");
		assertFalse(f.consumeBlockedNotice());
		assertFalse(f.wanted(true, true, true, true, true), "auch im Umschalt-Modus");
	}

	@Test
	void perspectiveMapsToVanillaCameraModes() {
		assertEquals(1, FreelookState.Perspective.BACK.cameraMode());
		assertEquals(2, FreelookState.Perspective.FRONT.cameraMode());
		assertEquals(FreelookState.Perspective.BACK, new TrsModules().freelookPerspective.get());
	}

	@Test
	void serverListMatchesHostsAndSubdomains() {
		String list = " Example.net ; *.wild.org,play.other.com\n";
		assertEquals(Arrays.asList("example.net", "*.wild.org", "play.other.com"), ServerList.entries(list));
		assertTrue(ServerList.matches("example.net", list));
		assertTrue(ServerList.matches("mc.EXAMPLE.net:25565", list));
		assertTrue(ServerList.matches("example.net.", list));
		assertFalse(ServerList.matches("notexample.net", list));
		assertTrue(ServerList.matches("eu.wild.org", list));
		assertFalse(ServerList.matches("wild.org", list), "*.x trifft nur Subdomains");
		assertTrue(ServerList.matches("play.other.com", list));
		assertFalse(ServerList.matches("other.com", list));
		assertFalse(ServerList.matches(null, list), "Einzelspieler");
		assertFalse(ServerList.matches("example.net", ""), "leere Liste (Standard)");
		assertEquals("::1", ServerList.host("[::1]:25565"));
		assertEquals("10.0.0.1", ServerList.host("10.0.0.1:25565"));
		ServerList cached = new ServerList();
		assertTrue(cached.contains("mc.example.net", list));
		assertTrue(cached.contains("mc.example.net", list));
		assertFalse(cached.contains("mc.example.net", "other.net"));
	}

	@Test
	void keySettingCanBeLinkedToAVanillaBinding() {
		final String[] vanilla = {"key.keyboard.left.alt"};
		KeySetting k = new TrsModules().freelookKey;
		k.link(new KeySetting.Link() {
			@Override
			public String get() {
				return vanilla[0];
			}

			@Override
			public void set(String keyName) {
				vanilla[0] = keyName;
			}
		});
		assertEquals("key.keyboard.left.alt", k.get());
		k.set("key.keyboard.g");
		assertEquals("key.keyboard.g", vanilla[0]);
		vanilla[0] = "key.keyboard.h";
		assertEquals("key.keyboard.h", k.get(), "Änderung in den Vanilla-Steuerungen sichtbar");
		k.unbind();
		assertFalse(k.isBound());
		k.reset();
		assertEquals("key.keyboard.left.alt", vanilla[0]);
		ModuleConfig config = new ModuleConfig();
		k.write(config);
		assertTrue(config.keys.isEmpty(), "verbundene Tasten stehen in options.txt, nicht in der TRS-Config");
		vanilla[0] = "nonsense";
		assertEquals(KeySetting.NONE, k.get());
	}
}
