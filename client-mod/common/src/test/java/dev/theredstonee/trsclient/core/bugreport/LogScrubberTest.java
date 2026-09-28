package dev.theredstonee.trsclient.core.bugreport;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Säuberung des Logs für „Bug melden“: was raus muss, was stehen bleiben soll. */
class LogScrubberTest {
	private static final List<String> NONE = Collections.emptyList();

	private static String scrub(String text) {
		return LogScrubber.scrub(text).text;
	}

	private static String scrub(String text, String... names) {
		return LogScrubber.scrub(text, Arrays.asList(names), null, null).text;
	}

	// --- Tokens und Start-Argumente ---

	@Test
	void accessTokenArgumentIsRemoved() {
		String out = scrub("Launching with --accessToken abcDEF123456.secret-value --version 1.21.1");
		assertFalse(out.contains("abcDEF123456"), out);
		assertTrue(out.contains("--accessToken <token>"), out);
		assertTrue(out.contains("--version 1.21.1"), out);
	}

	@Test
	void forgeArgumentListIsCleaned() {
		String line = "[main/INFO] [cpw.mods.modlauncher.Launcher/MODLAUNCHER]: ModLauncher running: args [--username, Steve_42, "
				+ "--version, 1.20.1, --gameDir, C:\\Users\\Max\\AppData\\Roaming\\.minecraft, --accessToken, ❄❄❄❄❄❄❄❄, "
				+ "--uuid, 069a79f444e94726a5befca90e38aaf5, --clientId, c2VjcmV0Y2xpZW50aWQ, --xuid, 2535400000000000, "
				+ "--userType, msa, --versionType, release]";
		LogScrubber.Result r = LogScrubber.scrub(line);
		String out = r.text;
		for (String secret : new String[]{"Steve_42", "❄", "069a79f4", "c2VjcmV0", "2535400000000000", "Max"}) {
			assertFalse(out.contains(secret), secret + " in " + out);
		}
		assertTrue(out.contains("--version, 1.20.1"), out);
		assertTrue(out.contains("--userType, msa"), out);
		assertTrue(out.contains("--username, <player>"), out);
		assertTrue(out.contains("--uuid, <uuid>"), out);
		assertTrue(out.contains("C:\\Users\\<user>\\AppData"), out);
		assertTrue(r.tokens >= 3 && r.uuids >= 1 && r.names >= 1 && r.paths >= 1, "Zählung");
	}

	@Test
	void legacySessionLineAndSettingUserAreRemoved() {
		String log = "[12:00:01] [Client thread/INFO]: Setting user: Notch\n"
				+ "[12:00:01] [Client thread/INFO]: (Session ID is token:0123456789abcdef0123456789abcdef:069a79f444e94726a5befca90e38aaf5)\n"
				+ "[12:05:00] [Client thread/INFO]: Notch joined the game\n";
		String out = scrub(log);
		assertFalse(out.contains("Notch"), out);
		assertFalse(out.contains("0123456789abcdef"), out);
		assertTrue(out.contains("Setting user: <player>"), out);
		assertTrue(out.contains("Session ID is <token>"), out);
		assertTrue(out.contains("<player> joined the game"), out);
	}

	@Test
	void jwtBearerAndTrsTokensAreRemoved() {
		String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
		String out = scrub("Got " + jwt + " and Authorization: Bearer abcdefghijklmnop and trs_" + repeat('x', 43) + " done");
		assertFalse(out.contains("eyJ"), out);
		assertFalse(out.contains("abcdefghijklmnop"), out);
		assertFalse(out.contains("trs_x"), out);
		assertTrue(out.endsWith(" done"), out);
	}

	@Test
	void keyValueSecretsAreRemoved() {
		String out = scrub("accessToken=abc123xyz789 {\"refresh_token\":\"M.C5_BAY.0.U.-Cq1Q\",\"name\":\"x\"} password: hunter22 "
				+ "session_id=s3cr3t&x=1 client_secret='topsecret'");
		for (String secret : new String[]{"abc123xyz789", "M.C5_BAY", "hunter22", "s3cr3t", "topsecret"}) {
			assertFalse(out.contains(secret), secret + " in " + out);
		}
		assertTrue(out.contains("\"name\":\"x\""), out);
		assertTrue(out.contains("&x=1"), out);
	}

	@Test
	void harmlessTokenWordsStay() {
		String line = "Unexpected token: } at line 3; token 12";
		assertEquals(line, scrub(line));
	}

	@Test
	void microsoftTokensAreRemoved() {
		String out = scrub("refresh M.R3_BAY.-CRrPNSyhKf2Qj8Ypl!wR3Hm5dFzKx$aB done");
		assertFalse(out.contains("M.R3_BAY"), out);
	}

	// --- UUIDs ---

	@Test
	void uuidsAreRemovedButHashesStay() {
		String sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
		String out = scrub("player 853c80ef-3c37-49fd-aa49-938b674adae6 and 853c80ef3c3749fdaa49938b674adae6, asset " + sha1);
		assertFalse(out.contains("853c80ef"), out);
		assertTrue(out.contains("player <uuid> and <uuid>"), out);
		assertTrue(out.contains(sha1), out);
	}

	// --- Namen ---

	@Test
	void ownAndAccountNamesAreRemovedAsWholeWordsIgnoringCase() {
		String out = scrub("Steve joined; STEVE left; Steven stays; xSteve stays; Alex_2 ok; alex_2 too", "Steve", "Alex_2");
		assertEquals("<player> joined; <player> left; Steven stays; xSteve stays; <player> ok; <player> too", out);
	}

	@Test
	void namesShorterThanThreeAreIgnored() {
		assertEquals("a b ab", scrub("a b ab", "ab"));
	}

	@Test
	void placeholdersAreNotMatchedAgain() {
		// Ein Spieler namens „user“ oder „token“ darf die Marken nicht verändern.
		String out = LogScrubber.scrub("C:\\Users\\max\\x --accessToken abc12345 user token", Arrays.asList("user", "token"),
				null, null).text;
		assertEquals("C:\\Users\\<user>\\x --accessToken <token> <player> <player>", out);
	}

	@Test
	void namesWithRegexCharactersAreQuoted() {
		assertEquals("<player> a.b.c", scrub("a.b a.b.c", "a.b"));
	}

	// --- IP-Adressen ---

	@Test
	void ipv4AddressesAreRemovedButLoopbackAndVersionsStay() {
		String out = scrub("Connecting to 192.168.1.20, 25565 / 8.8.8.8:53 from /127.0.0.1:1234 bind 0.0.0.0 "
				+ "Minecraft 1.21.1 Forge 47.4.10 build 10.13.4.1614 fabric 0.16.5");
		assertFalse(out.contains("192.168"), out);
		assertFalse(out.contains("8.8.8.8"), out);
		assertTrue(out.contains("127.0.0.1:1234"), out);
		assertTrue(out.contains("0.0.0.0"), out);
		assertTrue(out.contains("Minecraft 1.21.1 Forge 47.4.10 build 10.13.4.1614 fabric 0.16.5"), out);
	}

	@Test
	void ipv6AddressesAreRemovedButTimesStay() {
		String out = scrub("[12:34:56] [Server thread/INFO]: from [2001:db8::ff00:42:8329]:25565 and fe80::1ff:fe23:4567:890a%eth0 "
				+ "and 2001:0db8:0000:0000:0000:ff00:0042:8329 loopback ::1 time 23:59:59.123");
		assertFalse(out.contains("2001:db8"), out);
		assertFalse(out.contains("fe80"), out);
		assertFalse(out.contains("0db8"), out);
		assertTrue(out.startsWith("[12:34:56]"), out);
		assertTrue(out.contains("::1"), out);
		assertTrue(out.contains("23:59:59.123"), out);
		assertTrue(LogScrubber.ipv6Like("2001:db8::1"));
		assertFalse(LogScrubber.ipv6Like("12:34:56"));
		assertFalse(LogScrubber.ipv6Like("::1"));
		assertFalse(LogScrubber.ipv6Like("1:2:3:4:5:6:7:8:9"));
	}

	@Test
	void javaMethodReferencesStay() {
		String line = "at com.example.Foo::lambda$run$0 and Bar::new and dead::beef? no";
		String out = scrub(line);
		assertTrue(out.contains("Foo::lambda$run$0"), out);
		assertTrue(out.contains("Bar::new"), out);
	}

	// --- Pfade ---

	@Test
	void windowsUserPathsAreCleanedInAllSpellings() {
		String out = scrub("C:\\Users\\Max Mustermann\\AppData\\Roaming\\trs-launcher\\instances\\A | c:/users/max/Desktop | "
				+ "{\"path\":\"D:\\\\Users\\\\max\\\\x\"} | C:\\Documents and Settings\\Erika\\x");
		assertFalse(out.contains("Mustermann"), out);
		assertFalse(out.toLowerCase().contains("/max/"), out);
		assertFalse(out.contains("\\\\max\\\\"), out);
		assertFalse(out.contains("Erika"), out);
		assertTrue(out.contains("C:\\Users\\<user>\\AppData\\Roaming\\trs-launcher\\instances\\A"), out);
		assertTrue(out.contains("c:/users/<user>/Desktop"), out);
		assertTrue(out.contains("D:\\\\Users\\\\<user>\\\\x"), out);
	}

	@Test
	void unixHomePathsAreCleaned() {
		String out = scrub("/home/maxi/.minecraft/logs and /Users/erika/Library/Application Support/minecraft; https://x.org/home/page");
		assertTrue(out.contains("/home/<user>/.minecraft/logs"), out);
		assertTrue(out.contains("/Users/<user>/Library"), out);
		assertTrue(out.contains("https://x.org/home/page"), out);
	}

	@Test
	void userNameFromPathIsRemovedEverywhereUnlessGeneric() {
		String out = scrub("C:\\Users\\ronald\\x then ronald said hi; C:\\Users\\Admin\\y and Admin panel");
		assertEquals("C:\\Users\\<user>\\x then <user> said hi; C:\\Users\\<user>\\y and Admin panel", out);
	}

	@Test
	void osUserAndUnusualHomeAreRemoved() {
		LogScrubber.Result r = LogScrubber.scrub("D:\\Profiles\\kim.lee\\mc\\x and kim.lee was here", NONE, "kim.lee",
				"D:\\Profiles\\kim.lee");
		assertEquals("<home>\\mc\\x and <user> was here", r.text);
		assertEquals(2, r.paths);
	}

	// --- E-Mail, Chat ---

	@Test
	void emailsAreRemovedButModVersionsStay() {
		String out = scrub("contact max.mustermann+mc@example.co.uk now; mod fabric-api@0.92.0+1.20.1; TRANSFORMER/trsclient@0.12.0/");
		assertFalse(out.contains("mustermann"), out);
		assertTrue(out.contains("contact <email> now"), out);
		assertTrue(out.contains("fabric-api@0.92.0+1.20.1"), out);
		assertTrue(out.contains("trsclient@0.12.0"), out);
	}

	@Test
	void chatLinesLoseTheirContent() {
		String log = "[12:00:00] [Render thread/INFO]: [System] [CHAT] <Bob> my password is 1234\n"
				+ "[12:00:01] [Render thread/INFO]: [CHAT] Alice whispers to you: secret\n"
				+ "[12:00:02] [Render thread/INFO]: Loaded 12 advancements";
		LogScrubber.Result r = LogScrubber.scrub(log);
		assertEquals("[12:00:00] [Render thread/INFO]: [System] [CHAT] <chat removed>\n"
				+ "[12:00:01] [Render thread/INFO]: [CHAT] <chat removed>\n"
				+ "[12:00:02] [Render thread/INFO]: Loaded 12 advancements", r.text);
		assertEquals(2, r.chat);
	}

	// --- Allgemeines ---

	@Test
	void stackTracesAndNormalLinesStayUnchanged() {
		String log = "java.lang.NullPointerException: Cannot invoke \"net.minecraft.world.entity.Entity.getX()\"\n"
				+ "\tat net.minecraft.client.Minecraft.run(Minecraft.java:123) ~[client-intermediary.jar:?]\n"
				+ "\tat dev.theredstonee.trsclient.TrsClient.onTick(TrsClient.java:77) ~[trsclient-fabric-1.21.1.jar:0.12.0]\n"
				+ "[12:00:00] [Render thread/WARN]: Missing sound for event: minecraft:item.goat_horn.play";
		LogScrubber.Result r = LogScrubber.scrub(log);
		assertEquals(log, r.text);
		assertEquals(0, r.total());
	}

	@Test
	void controlCharactersAnsiAndBidiAreRemoved() {
		String out = scrub("\u001B[32mgreen\u001B[0m a\u0000b\u202Ec\r\nnext\rline\u0007");
		assertEquals("green abc\nnext\nline", out);
	}

	@Test
	void veryLongLinesAreCut() {
		String out = scrub(repeat('a', LogScrubber.MAX_LINE + 500) + "\nshort");
		String[] lines = out.split("\n");
		assertEquals(LogScrubber.MAX_LINE + 1, lines[0].length());
		assertTrue(lines[0].endsWith("…"));
		assertEquals("short", lines[1]);
	}

	@Test
	void scrubbingTwiceChangesNothing() {
		String log = "Setting user: Steve\n--accessToken abcdefgh1234 C:\\Users\\max\\x 10.0.0.1 a@b.de "
				+ "069a79f4-44e9-4726-a5be-fca90e38aaf5 [CHAT] hi";
		String once = scrub(log);
		assertEquals(once, scrub(once));
	}

	@Test
	void countsAddUp() {
		LogScrubber.Result r = LogScrubber.scrub("a@b.de c@d.de 10.1.2.3 --accessToken xyzxyzxyz", Arrays.asList("Steve"), null, null);
		assertEquals(2, r.emails);
		assertEquals(1, r.ips);
		assertEquals(1, r.tokens);
		assertEquals(4, r.total());
		assertEquals(0, LogScrubber.scrub(null).total());
		assertEquals("", LogScrubber.scrub("").text);
	}

	@Test
	void namesInFindsNamesTheLogReveals() {
		assertEquals(Arrays.asList("Steve", "Alex"),
				new java.util.ArrayList<String>(LogScrubber.namesIn("Setting user: Steve\nargs [--username, Alex, --version, 1]")));
		assertTrue(LogScrubber.namesIn(null).isEmpty());
	}

	// --- Kürzen ---

	@Test
	void tailKeepsTheLastLines() {
		StringBuilder b = new StringBuilder();
		for (int i = 1; i <= 500; i++) b.append("line ").append(i).append('\n');
		String t = LogScrubber.tail(b.toString(), 300, Integer.MAX_VALUE);
		String[] lines = t.split("\n");
		assertEquals(300, lines.length);
		assertEquals("line 201", lines[0]);
		assertEquals("line 500", lines[299]);
	}

	@Test
	void tailRespectsTheCharacterLimitLineByLine() {
		String t = LogScrubber.tail("aaaa\nbbbb\ncccc", 10, 10);
		assertEquals("bbbb\ncccc", t);
		assertEquals("cccc", LogScrubber.tail("aaaa\nbbbb\ncccc", 10, 4));
		// Nur eine, zu lange Zeile: ihr Ende.
		assertEquals("6789", LogScrubber.tail("0123456789", 5, 4));
		assertEquals("", LogScrubber.tail("\n\n", 5, 5));
		assertEquals("x", LogScrubber.tail("x\n\n", 5, 5));
	}

	@Test
	void tailNeverSplitsEmoji() {
		String t = LogScrubber.tail("ab\uD83D\uDE00c", 1, 2);
		assertFalse(Character.isLowSurrogate(t.charAt(0)), t);
	}

	@Test
	void headKeepsTheFirstLines() {
		assertEquals("a\nb", LogScrubber.head("a\nb\nc\n", 2, 100));
		assertEquals("a\nb", LogScrubber.head("a\nb\nccccccccc", 5, 5));
		assertEquals("abcde", LogScrubber.head("abcdefgh", 3, 5));
		assertEquals("", LogScrubber.head(null, 3, 5));
	}

	@Test
	void fullRealisticLogKeepsNothingPrivate() {
		String log = "[09:12:01] [main/INFO]: Loading Minecraft 1.21.1 with Fabric Loader 0.16.5\n"
				+ "[09:12:02] [Render thread/INFO]: Setting user: RedstoneRon\n"
				+ "[09:12:02] [Render thread/INFO]: Backend library: LWJGL version 3.3.3\n"
				+ "[09:12:03] [Render thread/INFO]: Reloading ResourceManager: vanilla, fabric, file/C:\\Users\\Ron Tamer\\Downloads\\pack.zip\n"
				+ "[09:14:10] [Render thread/INFO]: Connecting to 203.0.113.7, 25565\n"
				+ "[09:14:12] [Render thread/INFO]: [CHAT] RedstoneRon joined the game\n"
				+ "[09:14:15] [TRS-Online/INFO]: TRS Client: POST /v1/presence Authorization: Bearer trs_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ\n"
				+ "[09:15:00] [Render thread/INFO]: Saved screenshot as 2026-09-28_09.15.00.png\n"
				+ "[09:15:01] [Render thread/WARN]: Mail from ron@tamerin.tech rejected\n"
				+ "[09:16:00] [Render thread/INFO]: Player 5b7d3a0e-1b2c-4d5e-8f90-1a2b3c4d5e6f (RedstoneRon) at /2a02:810a:13c0:1f00::5:25565\n";
		String out = LogScrubber.scrub(log, Arrays.asList("RedstoneRon"), "Ron Tamer", "C:\\Users\\Ron Tamer").text;
		for (String secret : new String[]{"RedstoneRon", "Ron Tamer", "203.0.113.7", "joined the game", "trs_abc", "tamerin",
				"5b7d3a0e", "2a02:810a"}) {
			assertFalse(out.contains(secret), secret + " in\n" + out);
		}
		assertTrue(out.contains("Loading Minecraft 1.21.1 with Fabric Loader 0.16.5"), out);
		assertTrue(out.contains("LWJGL version 3.3.3"), out);
		assertTrue(out.contains("Saved screenshot as 2026-09-28_09.15.00.png"), out);
	}

	private static String repeat(char c, int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++) b.append(c);
		return b.toString();
	}
}
