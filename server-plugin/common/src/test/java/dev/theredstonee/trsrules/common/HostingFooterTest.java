package dev.theredstonee.trsrules.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostingFooterTest {
	static final String API_EXAMPLE = "§7Free server: §ctrs-launcher.de §8× §bcraftinghosting.free";

	private static String footer(String yaml) {
		HostingFooter.Result r = HostingFooter.parse(yaml);
		assertTrue(r.ok(), () -> r.error);
		return r.footer;
	}

	@Test
	void parsesTheApiFile() {
		assertEquals(API_EXAMPLE, footer("motd-footer: \"§7Free server: §ctrs-launcher.de §8× §bcraftinghosting.free\"\n"));
		assertEquals(API_EXAMPLE, footer("motd-footer: \"\\u00a77Free server: \\u00a7ctrs-launcher.de \\u00a78× \\u00a7bcraftinghosting.free\""));
	}

	@Test
	void emptyOrMissingIsOff() {
		assertEquals("", footer(""));
		assertEquals("", footer("motd-footer:\n"));
		assertEquals("", footer("motd-footer: \"\"\n"));
		assertEquals("", footer("motd-footer: \"   \"\n"));
		assertEquals("", footer("motd-footer: \"§7§l \"\n"));
		assertEquals("", footer("other: x\n"));
	}

	@Test
	void brokenFilesAreErrors() {
		assertFalse(HostingFooter.parse("motd-footer: [a, b]").ok());
		assertFalse(HostingFooter.parse("motd-footer: {a: b}").ok());
		assertFalse(HostingFooter.parse("- just a list").ok());
		assertFalse(HostingFooter.parse("motd-footer: \"unclosed").ok());
		assertNull(HostingFooter.parse("motd-footer: [").footer);
	}

	@Test
	void sanitizeStripsLineBreaksAndControlChars() {
		assertEquals("a b", HostingFooter.sanitize("a\nb"));
		assertEquals("a b", HostingFooter.sanitize("a\r\n\tb"));
		assertEquals("a b", HostingFooter.sanitize("a\u2028b"));
		assertEquals("ab", HostingFooter.sanitize("a\u0000\u0007\u001b\u007fb"));
		assertEquals("x", HostingFooter.sanitize("\n x \n"));
	}

	@Test
	void sanitizeKeepsOnlyLegacyCodes() {
		assertEquals("§a§lOK§r", HostingFooter.sanitize("§a§LOK§R"));
		assertEquals("§cred", HostingFooter.sanitize("§x§cred"));
		assertEquals("ab", HostingFooter.sanitize("a§zb"));
		assertEquals("a", HostingFooter.sanitize("a§"));
		assertEquals("&cnot converted", HostingFooter.sanitize("&cnot converted"));
		assertEquals("", HostingFooter.sanitize(null));
	}

	@Test
	void sanitizeCutsAt120Chars() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 200; i++) sb.append('x');
		assertEquals(HostingFooter.MAX_LENGTH, HostingFooter.sanitize(sb.toString()).length());

		// no dangling § at the cut
		String s = HostingFooter.sanitize(sb.substring(0, 119) + "§aTAIL");
		assertEquals(119, s.length());
		assertFalse(s.endsWith("§"));

		// no half surrogate pair at the cut
		s = HostingFooter.sanitize(sb.substring(0, 119) + "\uD83D\uDE00");
		assertEquals(119, s.length());
	}

	@Test
	void firstLineOfTheServerMotd() {
		assertEquals("A Minecraft Server", HostingFooter.firstLine("A Minecraft Server"));
		assertEquals("§aline 1", HostingFooter.firstLine("§aline 1\n§bline 2"));
		assertEquals("one", HostingFooter.firstLine("one\r\ntwo"));
		assertEquals("", HostingFooter.firstLine("\nonly line 2"));
		assertEquals("", HostingFooter.firstLine(""));
		assertEquals("", HostingFooter.firstLine(null));
	}

	@Test
	void footerIsAlwaysLine2() {
		assertEquals("Survival\n§r" + API_EXAMPLE, HostingFooter.motd("Survival", API_EXAMPLE));
		assertEquals("Survival\n§rF", HostingFooter.motd("Survival\nold line 2\nline 3", "F"));
		assertEquals("\n§rF", HostingFooter.motd("", "F"));
		assertEquals("\n§rF", HostingFooter.motd(null, "F"));
		assertEquals("\n§rF", HostingFooter.motd("\nsecond", "F"));
		// off: MOTD untouched
		assertEquals("a\nb", HostingFooter.motd("a\nb", ""));
		assertEquals("a\nb", HostingFooter.motd("a\nb", null));
	}

	@Test
	void loadFromFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("hosting.yml");
		assertEquals("", HostingFooter.load(file).footer);
		assertEquals("", HostingFooter.load(null).footer);
		Files.write(file, ("\uFEFFmotd-footer: \"" + API_EXAMPLE + "\"\n").getBytes(Protocol.UTF8));
		assertEquals(API_EXAMPLE, HostingFooter.load(file).footer);
		assertEquals("", HostingFooter.load(dir).footer);
	}

	@Test
	void serverRulesLoadsAndReloadsTheFooter(@TempDir Path dir) throws Exception {
		EngineTest.FakeBridge b = new EngineTest.FakeBridge();
		Path hosting = dir.resolve("hosting.yml");
		ServerRules<EngineTest.FakePlayer> rules = new ServerRules<EngineTest.FakePlayer>(b, dir.resolve("config.yml"), hosting, ScopeKind.WORLDS);
		assertEquals("", rules.motdFooter());
		assertEquals("Survival\nx", rules.motd("Survival\nx"));
		assertFalse(Files.exists(hosting), "the hosting file is never created");

		Files.write(hosting, "motd-footer: \"§7Free\"\n".getBytes(Protocol.UTF8));
		rules.reload();
		assertEquals("§7Free", rules.motdFooter());
		assertEquals("Survival\n§r§7Free", rules.motd("Survival\nx"));

		// broken file keeps the footer, deleted file turns it off
		Files.write(hosting, "motd-footer: [".getBytes(Protocol.UTF8));
		rules.reload();
		assertEquals("§7Free", rules.motdFooter());
		assertTrue(b.log.toString().contains("WARN Could not load hosting.yml"), b.log.toString());
		Files.delete(hosting);
		rules.reload();
		assertEquals("", rules.motdFooter());

		// footer is read at startup too; platforms without hosting file never get one
		Files.write(hosting, "motd-footer: \"F\"\n".getBytes(Protocol.UTF8));
		assertEquals("F", new ServerRules<EngineTest.FakePlayer>(b, dir.resolve("config.yml"), hosting, ScopeKind.WORLDS).motdFooter());
		assertEquals("", new ServerRules<EngineTest.FakePlayer>(b, dir.resolve("config.yml"), ScopeKind.WORLDS).motdFooter());
	}
}
