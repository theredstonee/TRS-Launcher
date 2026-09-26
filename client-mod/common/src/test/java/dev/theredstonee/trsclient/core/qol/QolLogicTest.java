package dev.theredstonee.trsclient.core.qol;

import dev.theredstonee.trsclient.core.alert.DeathCompass;
import dev.theredstonee.trsclient.core.alert.Notices;
import dev.theredstonee.trsclient.core.alert.Warnings;
import dev.theredstonee.trsclient.core.alert.WindowAttention;
import dev.theredstonee.trsclient.core.chat.ChatHistory;
import dev.theredstonee.trsclient.core.chat.ChatMentions;
import dev.theredstonee.trsclient.core.chat.ChatStacker;
import dev.theredstonee.trsclient.core.chat.ChatText;
import dev.theredstonee.trsclient.core.connect.AutoReconnect;
import dev.theredstonee.trsclient.core.connect.KickReasons;
import dev.theredstonee.trsclient.core.connect.QueueWatcher;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.pvp.HitFeedback;
import dev.theredstonee.trsclient.core.pvp.ItemCounter;
import dev.theredstonee.trsclient.core.pvp.TotemPops;
import dev.theredstonee.trsclient.core.streamer.StreamerMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Logik des Komfort-/PvP-Pakets: Muster, Schwellen, Zähler, Namensersetzung (ohne Minecraft). */
class QolLogicTest {
	private static final String S = "§";

	// --- Warteschlange ---

	@Test
	void queueReadsKnownPluginFormats() {
		QueueWatcher q = new QueueWatcher();
		q.configure("");
		assertEquals(QueueWatcher.Event.NONE, q.onText("Position in queue: 12", 0, 1));
		assertEquals(12, q.position());
		assertEquals(QueueWatcher.Event.NONE, q.onText(S + "6Position in queue: " + S + "l1,204", 10, 1));
		assertEquals(1204, q.position(), "Tausender-Trenner und Farbcodes");
		assertEquals(QueueWatcher.Event.NONE, q.onText("You are in position 3 of 57. Estimated time: 1m", 20, 1));
		assertEquals(3, q.position(), "ajQueue");
		assertEquals(QueueWatcher.Event.ALMOST, q.onText("Du bist auf Platz 1 in der Warteschlange", 30, 1));
		assertEquals(QueueWatcher.Event.NONE, q.onText("Position in queue: 1", 40, 1), "nur einmal melden");
		assertEquals(QueueWatcher.Event.DONE, q.onText("Connecting to the server...", 50, 1));
		assertEquals(-1, q.position());
		assertEquals(QueueWatcher.Event.NONE, q.onText("Connecting to the server...", 60, 1), "ohne Warteschlange kein Ende");
	}

	@Test
	void queueCustomPatternsAndThreshold() {
		QueueWatcher q = new QueueWatcher();
		q.configure("you are # in line;warte #");
		assertEquals(QueueWatcher.Event.NONE, q.onText("you are 7 in line", 0, 5));
		assertEquals(QueueWatcher.Event.ALMOST, q.onText("You are 5 in line", 1, 5));
		assertEquals(QueueWatcher.Event.NONE, q.onText("Hallo, ich bin 1 Spieler", 2, 5), "kein Muster");
		// Neue Warteschlange (Position springt deutlich hoch) → wieder scharf
		assertEquals(QueueWatcher.Event.NONE, q.onText("you are 40 in line", 3, 5));
		assertEquals(QueueWatcher.Event.ALMOST, q.onText("you are 2 in line", 4, 5));
		// Nach langer Pause vergessen
		assertEquals(QueueWatcher.Event.NONE, q.onText("you are 9 in line", 5, 5));
		assertEquals(QueueWatcher.Event.NONE, q.onText("Welcome!", 5 + QueueWatcher.FORGET_MS + 1, 5));
		assertEquals(-1, q.position());
	}

	// --- Kick-Gründe / Auto-Reconnect ---

	@Test
	void kickReasonsAreClassified() {
		assertEquals(KickReasons.Kind.BANNED, KickReasons.classify("You are permanently banned from this server!"));
		assertEquals(KickReasons.Kind.BANNED, KickReasons.classify("Du wurdest gebannt. Grund: Hacking"));
		assertEquals(KickReasons.Kind.BANNED, KickReasons.classify("You are temporarily banned for 29d. Ban ID: #123"));
		assertEquals(KickReasons.Kind.WHITELIST, KickReasons.classify("You are not white-listed on this server!"));
		assertEquals(KickReasons.Kind.DUPLICATE_LOGIN, KickReasons.classify("You logged in from another location"));
		assertEquals(KickReasons.Kind.OUTDATED, KickReasons.classify("Outdated client! Please use 1.21.11"));
		assertEquals(KickReasons.Kind.KICKED, KickReasons.classify("You have been kicked for being AFK"));
		assertEquals(KickReasons.Kind.RESTART, KickReasons.classify("Server is restarting. Please reconnect in a minute."));
		assertEquals(KickReasons.Kind.FULL, KickReasons.classify("The server is full!"));
		assertEquals(KickReasons.Kind.CONNECTION, KickReasons.classify("Timed out"));
		assertEquals(KickReasons.Kind.CONNECTION, KickReasons.classify("Internal Exception: java.io.IOException: Connection reset"));
		assertEquals(KickReasons.Kind.UNKNOWN, KickReasons.classify("Bye!"));
		assertEquals(KickReasons.Kind.CONNECTION, KickReasons.classify("Bandwidth test: connection lost"), "'ban' nur als ganzes Wort");
	}

	@Test
	void reconnectOnlyOnHarmlessReasons() {
		List<String> none = Collections.emptyList();
		assertTrue(KickReasons.allowed("Timed out", false, none));
		assertTrue(KickReasons.allowed("Server closed", false, none));
		assertFalse(KickReasons.allowed("You are banned", true, none), "Bann nie, auch im großzügigen Modus");
		assertFalse(KickReasons.allowed("not whitelisted", true, none));
		assertFalse(KickReasons.allowed("Kicked by an operator", false, none));
		assertTrue(KickReasons.allowed("Kicked by an operator", true, none));
		assertFalse(KickReasons.allowed("Bye!", false, none));
		assertTrue(KickReasons.allowed("Bye!", true, none));
		assertFalse(KickReasons.allowed("Server closed for maintenance", false, ChatText.words("maintenance")), "eigene Ausnahme");
	}

	@Test
	void reconnectCountdownAttemptsAndCancel() {
		AutoReconnect r = new AutoReconnect();
		List<String> none = Collections.emptyList();
		assertEquals(AutoReconnect.Phase.IDLE, r.onDisconnected(null, "Timed out", 0, 5000, 3, false, none), "Einzelspieler");
		assertEquals(AutoReconnect.Phase.BLOCKED, r.onDisconnected("mc.example.net", "You are banned", 0, 5000, 3, false, none));
		assertEquals(AutoReconnect.Phase.COUNTDOWN, r.onDisconnected("mc.example.net", "Timed out", 0, 5000, 3, false, none));
		assertEquals(5, r.secondsLeft(1));
		assertEquals(1, r.secondsLeft(4500));
		assertFalse(r.due(4999));
		assertTrue(r.due(5000));
		assertFalse(r.due(5001), "nur einmal");
		assertEquals(1, r.attempts());
		// zweiter und dritter Versuch, dann Schluss
		r.onDisconnected("mc.example.net", "Timed out", 10_000, 5000, 3, false, none);
		assertTrue(r.due(15_000));
		r.onDisconnected("mc.example.net", "Timed out", 20_000, 5000, 3, false, none);
		assertTrue(r.due(25_000));
		assertEquals(AutoReconnect.Phase.EXHAUSTED, r.onDisconnected("mc.example.net", "Timed out", 30_000, 5000, 3, false, none));
		// anderer Server zählt neu, Abbrechen stoppt
		assertEquals(AutoReconnect.Phase.COUNTDOWN, r.onDisconnected("other.net", "Timed out", 40_000, 5000, 3, false, none));
		r.cancel();
		assertEquals(AutoReconnect.Phase.CANCELLED, r.phase());
		assertFalse(r.due(50_000));
		// stabile Verbindung setzt die Versuche zurück
		r.onDisconnected("other.net", "Timed out", 60_000, 5000, 3, false, none);
		assertTrue(r.due(65_000));
		r.inWorld("other.net", 66_000);
		r.inWorld("other.net", 66_000 + AutoReconnect.STABLE_MS);
		assertEquals(0, r.attempts());
	}

	// --- Chat ---

	@Test
	void chatWordsMatchWholeWordsAndSkipColorCodes() {
		List<String> words = ChatText.words("Steve; ;x;team;Team");
		assertEquals(Arrays.asList("steve", "team"), words, "zu kurz/doppelt fällt weg");
		String raw = "Hi " + S + "aSte" + S + "lve and stevenson, team!";
		List<int[]> hits = ChatText.find(raw, words);
		assertEquals(2, hits.size(), "stevenson ist kein ganzes Wort");
		assertEquals("Ste" + S + "lve", raw.substring(hits.get(0)[0], hits.get(0)[1]));
		assertTrue(ChatText.containsAny("Join our DISCORD.gg now", ChatText.words("discord.gg")));
		assertEquals(S + "c" + S + "l", ChatText.activeCodes("x" + S + "a" + S + "c" + S + "lbold"));
		assertEquals("", ChatText.activeCodes(S + "c" + S + "r plain"));
	}

	@Test
	void ownMessagesAreNoMentions() {
		List<String> words = Collections.singletonList("steve");
		assertTrue(ChatMentions.mentions("<Steve> hello", words, "Steve").isEmpty());
		assertTrue(ChatMentions.mentions("[MVP" + S + "c+" + S + "b] Steve" + S + "f: gg", words, "Steve").isEmpty());
		assertEquals(1, ChatMentions.mentions("<Alex> hey steve, come here", words, "Steve").size());
		assertEquals(1, ChatMentions.mentions("Steve was slain by Zombie", words, "Steve").size());
		ChatMentions m = new ChatMentions();
		assertEquals(Arrays.asList("steve", "party"), m.words("Steve", true, "party"));
		assertEquals(Collections.singletonList("party"), m.words("Steve", false, "party"));
		assertTrue(m.soundAllowed(1000));
		assertFalse(m.soundAllowed(2000), "höchstens alle 1,5 s");
		assertTrue(m.soundAllowed(2600));
	}

	@Test
	void chatStackingAndLongerHistory() {
		ChatStacker stacker = new ChatStacker();
		assertEquals(1, stacker.accept("[Server] Restart in 5 minutes", 0, ChatStacker.DEFAULT_WINDOW_MS));
		assertEquals(2, stacker.accept("[Server] Restart in 5 minutes", 1, ChatStacker.DEFAULT_WINDOW_MS));
		assertEquals(" (x2)", ChatStacker.suffix(2));
		assertEquals(100, ChatHistory.limit(false, 800));
		assertEquals(800, ChatHistory.limit(true, 800));
		assertEquals(1000, ChatHistory.limit(true, 5000));
		assertEquals(100, ChatHistory.limit(true, 12));

		// Vanilla fügt vorne ein und kürzt hinten auf 100 – der Keeper hängt die abgeschnittenen Zeilen wieder an.
		ChatHistory.Keeper<Object> keeper = new ChatHistory.Keeper<Object>();
		List<Object> live = new ArrayList<Object>();
		List<Object> all = new ArrayList<Object>();
		for (int i = 0; i < 250; i++) {
			Object line = "line" + i;
			all.add(0, line);
			live.add(0, line);
			while (live.size() > 100) live.remove(live.size() - 1);
			keeper.keep(live, 200);
		}
		assertEquals(200, live.size());
		assertEquals(all.subList(0, 200), live);
		// Mitten gelöschte Zeile (Stapeln „(x2)“) bleibt weg, der Rest bleibt erhalten.
		Object removed = live.remove(5);
		keeper.keep(live, 200);
		assertFalse(live.contains(removed));
		assertEquals(199, live.size());
		// Chat geleert
		live.clear();
		keeper.keep(live, 200);
		assertEquals(0, keeper.size());
	}

	// --- Warnungen ---

	@Test
	void warningsFireOnceBelowThresholdAndRearmAfterRecovery() {
		Warnings w = new Warnings();
		Warnings.Config c = new Warnings.Config();
		c.durabilityPercent = 10;
		c.cooldownMs = 30_000;
		fill(w, 300, 407, 20, 20);
		assertTrue(w.tick(c, 0).isEmpty(), "26 % – noch gut");
		fill(w, 370, 407, 20, 20);
		List<Warnings.Alert> a = w.tick(c, 1000);
		assertEquals(1, a.size());
		assertEquals(Warnings.Kind.ARMOR, a.get(0).kind);
		assertEquals(9, a.get(0).value);
		fill(w, 380, 407, 20, 20);
		assertTrue(w.tick(c, 2000).isEmpty(), "nur einmal");
		// repariert → wieder scharf, aber Abklingzeit
		fill(w, 0, 407, 20, 20);
		w.tick(c, 3000);
		fill(w, 380, 407, 20, 20);
		assertTrue(w.tick(c, 4000).isEmpty(), "Abklingzeit");
		fill(w, 380, 407, 20, 20);
		assertEquals(1, w.tick(c, 32_000).size());
	}

	@Test
	void warningsHealthHungerInventoryAndCreative() {
		Warnings w = new Warnings();
		Warnings.Config c = new Warnings.Config();
		c.healthLevel = 6;
		c.hungerLevel = 6;
		Warnings.Input in = inputOf(w, 0, 0, 5, 4);
		in.inventoryFull = true;
		List<Warnings.Alert> a = w.tick(c, 0);
		assertEquals(3, a.size());
		// Kreativ: nichts
		Warnings w2 = new Warnings();
		Warnings.Input in2 = inputOf(w2, 0, 0, 2, 2);
		in2.survival = false;
		assertTrue(w2.tick(c, 0).isEmpty());
		assertEquals(-1, Warnings.percent(0, 0));
		assertEquals(50, Warnings.percent(50, 100));
	}

	private static void fill(Warnings w, int damage, int max, float health, int food) {
		inputOf(w, damage, max, health, food);
	}

	private static Warnings.Input inputOf(Warnings w, int damage, int max, float health, int food) {
		Warnings.Input in = w.input();
		in.alive = true;
		in.survival = true;
		in.armorDamage[0] = damage;
		in.armorMax[0] = max;
		in.health = health;
		in.food = food;
		return in;
	}

	// --- Zähler ---

	@Test
	void itemCounterSortsStacks() {
		assertEquals(ItemCounter.bit(ItemCounter.Kind.ARROWS), ItemCounter.classify("minecraft:tipped_arrow", false, false, false));
		assertEquals(ItemCounter.bit(ItemCounter.Kind.TOTEMS), ItemCounter.classify("totem_of_undying", false, false, false));
		int healSplash = ItemCounter.classify("splash_potion", false, true, false);
		assertEquals(ItemCounter.bit(ItemCounter.Kind.HEALING) | ItemCounter.bit(ItemCounter.Kind.SPLASH), healSplash);
		assertEquals(ItemCounter.bit(ItemCounter.Kind.SPLASH), ItemCounter.classify("potion", false, false, true), "1.8.9-Wurftrank");
		assertEquals(0, ItemCounter.classify("potion", false, false, false), "normaler Trank ohne Heilung");
		assertEquals(ItemCounter.bit(ItemCounter.Kind.BLOCKS), ItemCounter.classify("oak_planks", true, false, false));
		assertEquals(0, ItemCounter.classify("diamond_sword", false, false, false));
		ItemCounter c = new ItemCounter();
		c.add(ItemCounter.classify("ender_pearl", false, false, false), 16, "a");
		c.add(ItemCounter.classify("ender_pearl", false, false, false), 3, "b");
		c.add(healSplash, 2, "p");
		assertEquals(19, c.count(ItemCounter.Kind.PEARLS));
		assertEquals("a", c.icon(ItemCounter.Kind.PEARLS));
		assertEquals(2, c.count(ItemCounter.Kind.HEALING));
		assertEquals(2, c.count(ItemCounter.Kind.SPLASH));
		c.clear();
		assertEquals(0, c.count(ItemCounter.Kind.PEARLS));
		assertNull(c.icon(ItemCounter.Kind.PEARLS));
	}

	@Test
	void totemPopsPerOpponent() {
		TotemPops t = new TotemPops();
		t.onPop("Alex", false, 0);
		t.onPop("alex", false, 10);
		t.onPop("Steve", true, 20);
		t.onPop("Notch", false, 30);
		assertEquals(2, t.pops("Alex"));
		assertEquals(0, t.pops("Steve"), "eigene Pops nicht");
		assertEquals("Notch", t.recent(40).get(0).name, "neueste zuerst");
		t.onDeath("Notch");
		assertEquals(1, t.recent(40).size());
		assertTrue(t.recent(10 + TotemPops.FORGET_MS + 1).isEmpty());
	}

	// --- Treffer ---

	@Test
	void hitMarkerFadesAndSoundOnce() {
		HitFeedback h = new HitFeedback();
		assertEquals(0f, h.alpha(0, 300));
		h.onHit(1000, false);
		assertEquals(1f, h.alpha(1100, 300));
		assertTrue(h.alpha(1250, 300) < 1f);
		assertEquals(0f, h.alpha(1300, 300));
		assertTrue(h.takeSound());
		assertFalse(h.takeSound());
		assertEquals(1, HitFeedback.particleCopies(0));
		assertEquals(3, HitFeedback.particleCopies(3));
		assertEquals(5, HitFeedback.particleCopies(99));
	}

	// --- Streamer-Modus ---

	@Test
	void streamerReplacesNamesAsWholeWords() {
		StreamerMode s = new StreamerMode();
		s.configure(true, "Steve", "Player", false);
		assertEquals("<Player> hi Stevenson", s.replace("<Steve> hi Stevenson"));
		assertEquals(S + "aPlayer" + S + "r joined", s.replace(S + "aSteve" + S + "r joined"));
		assertEquals("Alex killed Player", s.replace("Alex killed steve"));
		s.knowPlayer("Alex");
		assertEquals("Alex", s.name("Alex"), "andere nur auf Wunsch");
		s.configure(true, "Steve", "Player", true);
		String alias = s.name("Alex");
		assertTrue(alias.startsWith("Player-"));
		assertEquals(alias + " killed Player", s.replace("Alex killed Steve"));
		assertEquals(alias, s.name("alex"), "stabil");
		s.configure(false, "Steve", "Player", true);
		assertEquals("Steve", s.replace("Steve"), "aus = unverändert");
		assertEquals("play.example.net", s.address("play.example.net", true));
		s.configure(true, "Steve", "", false);
		assertEquals("Player", s.name("Steve"), "leerer Name → Player");
		assertEquals(StreamerMode.HIDDEN_ADDRESS, s.address("play.example.net", true));
		assertTrue(StreamerMode.looksLikeAddress("play.example.net"));
		assertTrue(StreamerMode.looksLikeAddress("127.0.0.1:25565"));
		assertFalse(StreamerMode.looksLikeAddress("Mein Server"));
		assertFalse(StreamerMode.looksLikeAddress("Hypixel"));
	}

	// --- Todespunkt ---

	@Test
	void deathCompassPointsRelativeToView() {
		// Blick nach Süden (+Z): Ziel im Süden = geradeaus, Westen = rechts, Osten = links, Norden = hinten
		assertEquals(0, DeathCompass.sector(0, 10, 0));
		assertEquals(2, DeathCompass.sector(-10, 0, 0));
		assertEquals(6, DeathCompass.sector(10, 0, 0));
		assertEquals(4, DeathCompass.sector(0, -10, 0));
		// Blick nach Westen (90°): Ziel im Westen = geradeaus
		assertEquals(0, DeathCompass.sector(-10, 0, 90));
		DeathCompass d = new DeathCompass();
		d.set(100, 64, 100, "overworld", 0);
		assertTrue(d.visible(0, 0, "overworld", 1000));
		assertFalse(d.visible(0, 0, "the_nether", 1000), "andere Dimension");
		assertTrue(d.visible(0, 0, "overworld", 2000));
		assertFalse(d.visible(100, 101, "overworld", 3000), "angekommen");
		assertFalse(d.active());
	}

	// --- Hinweise, Fenster, Module ---

	@Test
	void noticesMergeAndExpire() {
		Notices n = new Notices();
		n.show("a", Notices.Level.WARN, "eins", null, 0);
		n.show("a", Notices.Level.WARN, "eins neu", null, 100);
		n.show("b", Notices.Level.INFO, "zwei", null, 200);
		assertEquals(2, n.items().size());
		assertEquals("eins neu", n.items().get(0).text);
		for (int i = 0; i < 5; i++) n.show("x" + i, Notices.Level.INFO, "t", null, 300);
		assertEquals(Notices.MAX_VISIBLE, n.items().size());
		n.prune(300 + Notices.DURATION_MS + 1);
		assertTrue(n.isEmpty());
	}

	@Test
	void windowAttentionNeverThrowsWithoutLwjgl() {
		assertFalse(WindowAttention.request(0));
		// Ohne LWJGL im Test-Klassenpfad: kein Fehler, nur „nicht verfügbar“.
		WindowAttention.request(12345L);
	}

	@Test
	void qolModulesAreRegisteredAndTranslated() {
		TrsModules modules = new TrsModules();
		assertTrue(modules.registry.all().contains(modules.qol.streamer));
		assertFalse(modules.qol.autoReconnect.isEnabled(), "ab Werk aus");
		assertFalse(modules.autoGg.isEnabled(), "Auto-GG ab Werk aus");
		I18n.use("de");
		assertEquals("Streamer-Modus", modules.qol.streamer.name());
		assertEquals("Helm fast kaputt (8 %)", I18n.tr("warn.armor", I18n.tr("warn.slot.0"), 8));
		I18n.use("en");
	}
}
