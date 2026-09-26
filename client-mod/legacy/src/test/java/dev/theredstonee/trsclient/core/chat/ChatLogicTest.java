package dev.theredstonee.trsclient.core.chat;

import dev.theredstonee.trsclient.core.util.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLogicTest {
	@Test
	void stackerCountsRepeats() {
		ChatStacker stacker = new ChatStacker();
		assertEquals(1, stacker.accept("Hallo", 0, 30_000));
		assertEquals(2, stacker.accept("Hallo", 100, 30_000));
		assertEquals(3, stacker.accept("Hallo", 200, 30_000));
		assertEquals(1, stacker.accept("Anders", 300, 30_000));
		assertEquals(1, stacker.accept("Hallo", 400, 30_000));
	}

	@Test
	void stackerForgetsAfterWindowAndReset() {
		ChatStacker stacker = new ChatStacker();
		stacker.accept("Hallo", 0, 1000);
		assertEquals(1, stacker.accept("Hallo", 2000, 1000));
		stacker.accept("Hallo", 2100, 1000);
		stacker.reset();
		assertEquals(1, stacker.accept("Hallo", 2200, 1000));
		assertEquals("", ChatStacker.suffix(1));
		assertEquals(" (x4)", ChatStacker.suffix(4));
	}

	@Test
	void timestampFormats() {
		assertEquals("[09:05] ", ChatTimestamp.format(9, 5, 7, false, false));
		assertEquals("[09:05:07] ", ChatTimestamp.format(9, 5, 7, true, false));
		assertEquals("[09:05 AM] ", ChatTimestamp.format(9, 5, 7, false, true));
		assertEquals("[01:05 PM] ", ChatTimestamp.format(13, 5, 7, false, true));
		assertEquals("[12:00 AM] ", ChatTimestamp.format(0, 0, 0, false, true));
	}

	@Test
	void autoGgOnlyTriggersOnGameMessages() {
		List<String> none = Collections.emptyList();
		assertTrue(AutoGg.matches("                 Winner: RED TEAM", none));
		assertTrue(AutoGg.matches("1st Killer - Steve - 7", none));
		assertTrue(AutoGg.matches("Steve won the game!", none));
		// Spieler-Chat darf nicht auslösen
		assertFalse(AutoGg.matches("[MVP+] Foo: winner: haha", none));
		assertFalse(AutoGg.matches("<Foo> gg won the game", none));
		assertFalse(AutoGg.matches("Guten Morgen", none));
		assertTrue(AutoGg.matches("Runde beendet", AutoGg.extraTriggers(" Runde beendet ; ")));
	}

	@Test
	void autoGgSchedulesOnceAndIsRateLimited() {
		AutoGg gg = new AutoGg();
		List<String> extra = Collections.emptyList();
		assertTrue(gg.onMessage("Winner: RED", extra, 1000, 1000));
		assertFalse(gg.onMessage("Winner: BLUE", extra, 1100, 1000), "es ist schon eine Nachricht geplant");
		assertFalse(gg.due(1500));
		assertTrue(gg.due(2000));
		assertFalse(gg.due(2001), "nur einmal fällig");
		// innerhalb von 10 s kein zweites Mal (eine Runde = ein gg)
		assertFalse(gg.onMessage("Winner: BLUE", extra, 9_000, 1000));
		assertTrue(gg.onMessage("Winner: BLUE", extra, 11_000, 1000));
	}

	@Test
	void autoGgDelayStaysBetweenHalfAndTwoSeconds() {
		assertEquals(500, AutoGg.clampDelay(0));
		assertEquals(2000, AutoGg.clampDelay(5000));
		assertEquals(1250, AutoGg.clampDelay(1250));
		AutoGg gg = new AutoGg();
		assertTrue(gg.onMessage("Winner: RED", Collections.<String>emptyList(), 0, 60_000));
		assertFalse(gg.due(1999));
		assertTrue(gg.due(2000), "höchstens 2 s nach dem Rundenende");
	}

	@Test
	void autoGgKnowsServerPatternsAndTitles() {
		List<String> none = Collections.emptyList();
		assertTrue(AutoGg.matches("  Match Results (click to view)", none, true), "Minemen/Übungsserver");
		assertTrue(AutoGg.matches("Steve has won the duel!", none, true), "PvP.Land-artige Duelle");
		assertTrue(AutoGg.matches("§6§l1st Killer §7- Steve - 7", none, true), "Farbcodes stören nicht");
		assertFalse(AutoGg.matches("Winner: RED", none, false), "eingebaute Muster abschaltbar");
		assertTrue(AutoGg.matches("Runde vorbei", AutoGg.extraTriggers("runde vorbei"), false));
		assertTrue(AutoGg.matchesTitle("§6§lVICTORY!", none, true));
		assertTrue(AutoGg.matchesTitle("  YOU WIN", none, true));
		assertFalse(AutoGg.matchesTitle("YOU DIED", none, true), "Tod mitten in der Runde ist kein Ende");
		AutoGg gg = new AutoGg();
		assertTrue(gg.onTitle("VICTORY!", none, true, 0, 700));
		assertTrue(gg.due(700));
	}

	@Test
	void chatOutSanitizes() {
		assertEquals("Hallo Welt", ChatOut.sanitize("  Hallo\nWelt  "));
		assertEquals("Test", ChatOut.sanitize("Te§st"), "§ wird entfernt");
		assertEquals(ChatOut.MAX_LENGTH, ChatOut.sanitize(longText()).length());
		assertTrue(ChatOut.isCommand("/gg"));
		assertFalse(ChatOut.isCommand("gg"));
		assertFalse(ChatOut.isCommand("/"));
		assertEquals("gg", ChatOut.command("/gg"));
	}

	private static String longText() {
		char[] chars = new char[ChatOut.MAX_LENGTH + 50];
		Arrays.fill(chars, 'a');
		return new String(chars);
	}

	@Test
	void rateLimiterKeepsIntervalAndWindow() {
		RateLimiter limiter = new RateLimiter(1000, 3, 10_000);
		assertTrue(limiter.tryAcquire(0));
		assertFalse(limiter.tryAcquire(500), "Mindestabstand");
		assertTrue(limiter.tryAcquire(1000));
		assertTrue(limiter.tryAcquire(2000));
		assertFalse(limiter.tryAcquire(3000), "drei im Fenster reichen");
		assertFalse(limiter.tryAcquire(9999));
		assertTrue(limiter.tryAcquire(10_000));
		limiter.reset();
		assertTrue(limiter.tryAcquire(10_100));
	}
}
