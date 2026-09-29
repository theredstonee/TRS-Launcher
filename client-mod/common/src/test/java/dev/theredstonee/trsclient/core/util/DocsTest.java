package dev.theredstonee.trsclient.core.util;

import dev.theredstonee.trsclient.core.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Links in die Docs: Sprache aus dem Client (en/de/es, sonst en), nur saubere Seitenpfade. */
class DocsTest {
	@AfterEach
	void reset() {
		I18n.use("en");
	}

	@Test
	void buildsLanguageAndPage() {
		assertEquals("https://trs-launcher.theredstonee.de/docs/de/client/overview", Docs.url(Docs.CLIENT_OVERVIEW, "de"));
		assertEquals("https://trs-launcher.theredstonee.de/docs/es/help/crash-helper", Docs.url("help/crash-helper", "es"));
		assertEquals("https://trs-launcher.theredstonee.de/docs/en/client/overview", Docs.url(Docs.CLIENT_OVERVIEW, "en"));
		assertEquals("https://trs-launcher.theredstonee.de/docs/de/", Docs.url(null, "de"));
	}

	@Test
	void otherLanguagesFallBackToEnglish() {
		for (String code : new String[]{"fr", "pl", "pt-BR", "tr", "nl", "xx", "", null}) {
			assertEquals("en", Docs.language(code), String.valueOf(code));
		}
		assertEquals("de", Docs.language("de_AT"));
		assertEquals("es", Docs.language("es-MX"));
		assertEquals("de", Docs.language("DE"));
	}

	@Test
	void followsTheClientLanguage() {
		I18n.use("de");
		assertEquals(Docs.BASE + "/de/client/overview", Docs.url(Docs.CLIENT_OVERVIEW));
		I18n.use("pt-BR");
		assertEquals(Docs.BASE + "/en/client/overview", Docs.url(Docs.CLIENT_OVERVIEW));
		I18n.use("es");
		assertEquals(Docs.BASE + "/es/client/overview", Docs.url(Docs.CLIENT_OVERVIEW));
	}

	@Test
	void rejectsStrangePages() {
		for (String bad : new String[]{"../admin", "help//logs", "/help/logs", "help/logs/", "help/logs?x=1", "Help/Logs",
				"https://evil.example", "help/lo gs", ""}) {
			assertEquals(Docs.BASE + "/de/", Docs.url(bad, "de"), bad);
		}
		assertTrue(Links.allowed(Docs.url(Docs.CLIENT_OVERVIEW, "de")), "Links.open nimmt die Adresse an");
	}
}
