package dev.theredstonee.trsclient.core.chatheads;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Absender, Zeilenmaß, Haut-Mischung und der Cache – ohne Minecraft. */
class ChatHeadTest {
	private static final List<String> NAMES = Arrays.asList("Steve", "Alex", "Steve2");

	@Test
	void angleBracketsColonRankAndGuillemet() {
		assertName("Steve", "<Steve> hello");
		assertName("Steve", "Steve: hello");
		assertName("Steve", "[VIP] Steve » hello");
		assertName("Steve", "Steve » hello");
		assertName("Alex", "From Alex: mail");
		assertName("Alex", "[Alex -> me] secret");
	}

	@Test
	void whispersAndSelf() {
		assertName("Alex", "Alex whispers to you: hi");
		ChatHeadDetect.Hit self = ChatHeadDetect.find("You whisper to Alex: hi", NAMES);
		assertNotNull(self);
		assertTrue(self.self);
		ChatHeadDetect.Hit me = ChatHeadDetect.find("[me -> Alex] hi", NAMES);
		assertNotNull(me);
		assertTrue(me.self);
	}

	@Test
	void earliestNameWinsAndWordBoundariesHold() {
		assertName("Alex", "Alex: Steve: hi");
		assertName("Steve2", "Steve2: hi");
		assertNull(ChatHeadDetect.find("tomato: hi", Collections.singletonList("tom")));
		assertNull(ChatHeadDetect.find("custom: hi", Collections.singletonList("tom")));
		assertNull(ChatHeadDetect.find("[Server] restart", NAMES));
		assertNull(ChatHeadDetect.find("Steve hello", NAMES));
	}

	@Test
	void colorCodesAreIgnoredAndFakeSlotsSkipped() {
		assertName("Steve", "§a<§bSteve§r> hi");
		assertNull(ChatHeadDetect.find("<|slot_11> x", Collections.singletonList("|slot_11")));
		assertName("Steve", "Steve  : spaced");
	}

	@Test
	void wrapWidthAndTint() {
		assertEquals(91, ChatHeadLayout.wrapWidth(100, ChatHeadLayout.WIDTH));
		assertEquals(20, ChatHeadLayout.wrapWidth(20, ChatHeadLayout.WIDTH));
		assertEquals(100, ChatHeadLayout.wrapWidth(100, 0));
		assertTrue(ChatHeadLayout.drawOnLine(0));
		assertFalse(ChatHeadLayout.drawOnLine(1));
		assertEquals(9, ChatHeadLayout.WIDTH);
		assertEquals(8, ChatHeadLayout.LEGACY_PAD);
		assertEquals("  <Steve> hi\n  more", ChatHeadLayout.indentLines(Arrays.asList("<Steve> hi", "more")));
		assertEquals("", ChatHeadLayout.indentLines(Collections.<String>emptyList()));
		assertEquals(255, ChatHeadLayout.opacityByte(0xFFFFFFFF));
		assertEquals(128, ChatHeadLayout.opacityByte(16777215 + (128 << 24)));
		assertEquals(0, ChatHeadLayout.tint(0x00FFFFFF));
		assertEquals(0x80FFFFFF, ChatHeadLayout.tint(16777215 + (128 << 24)));
	}

	@Test
	void blendAndExtractHead() {
		assertEquals(0xFFFF0000, ChatHeadBlend.blend(0xFFFF0000, 0x00000000));
		assertEquals(0xFF0000FF, ChatHeadBlend.blend(0xFFFF0000, 0xFF0000FF));
		int[] skin = new int[64 * 64];
		skin[8 * 64 + 8] = 0xFFFF0000;
		skin[8 * 64 + 40] = 0x00000000;
		int[] head = ChatHeadBlend.extract(skin, 64, 64, true);
		assertNotNull(head);
		assertEquals(64, head.length);
		assertEquals(0xFFFF0000, head[0]);
		skin[8 * 64 + 40] = 0xFF00FF00;
		assertEquals(0xFF00FF00, ChatHeadBlend.extract(skin, 64, 64, true)[0]);
		assertEquals(0xFFFF0000, ChatHeadBlend.extract(skin, 64, 64, false)[0]);

		int[] legacy = new int[64 * 32];
		legacy[8 * 64 + 8] = 0xFF112233;
		int[] legacyHead = ChatHeadBlend.extract(legacy, 64, 32, true);
		assertNotNull(legacyHead);
		assertEquals(0xFF112233, legacyHead[0]);
		assertNull(ChatHeadBlend.extract(new int[8], 8, 8, true));
	}

	@Test
	void cacheIsIdentityAndDropsTheOldest() {
		ChatHeadCache cache = new ChatHeadCache();
		String a = new String("same");
		String b = new String("same");
		cache.put(a, ChatHeadSender.name("Steve"));
		cache.put(b, ChatHeadSender.NONE);
		assertEquals("Steve", cache.get(a).name);
		assertFalse(cache.get(b).hasHead());
		assertNull(cache.get("same"));
		Object oldest = new Object();
		Object newest = oldest;
		cache.put(oldest, ChatHeadSender.self());
		for (int i = 0; i < ChatHeadCache.CAP + 10; i++) {
			newest = new Object();
			cache.put(newest, ChatHeadSender.self());
		}
		assertTrue(cache.size() <= ChatHeadCache.CAP);
		assertNull(cache.get(oldest));
		assertTrue(cache.get(newest).self);
		cache.clear();
		assertEquals(0, cache.size());
	}

	@Test
	void senderHasAHeadOnlyWhenKnown() {
		assertFalse(ChatHeadSender.NONE.hasHead());
		assertTrue(ChatHeadSender.name("Steve").hasHead());
		assertTrue(ChatHeadSender.self().hasHead());
		assertTrue(ChatHeadSender.uuid("00000000-0000-0000-0000-000000000001", null).hasHead());
		assertFalse(ChatHeadSender.name("").hasHead());
	}

	private static void assertName(String expected, String message) {
		ChatHeadDetect.Hit hit = ChatHeadDetect.find(message, NAMES);
		assertNotNull(hit, message);
		assertEquals(expected, hit.name, message);
		assertFalse(hit.self);
	}
}
