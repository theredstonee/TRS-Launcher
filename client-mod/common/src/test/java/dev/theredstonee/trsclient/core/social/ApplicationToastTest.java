package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.theredstonee.trsclient.core.social.SocialFakes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Team-Bewerbungen (API.md §24.3): Ereignis {@code application_updated} lesen und als Hinweis zeigen. */
class ApplicationToastTest {
	private static String app(String status, String response) {
		return "{\"application\":{\"id\":\"a0123456789abcdef\",\"job\":{\"id\":\"moderator\",\"title\":{\"en\":\"Moderator\",\"de\":\"Moderator:in\"},"
				+ "\"open\":true},\"status\":\"" + status + "\",\"response\":" + (response == null ? "null" : "\"" + response + "\"")
				+ ",\"createdAt\":\"2026-09-27T08:00:00.000Z\",\"canWithdraw\":false}}";
	}

	@AfterEach
	void english() {
		I18n.use("en");
	}

	@Test
	void parsesTheApplicationOnlyForItsEvent() {
		MeEvent e = MeEvent.parse("application_updated", app("rejected", "Danke‮!"), "e.1");
		assertNotNull(e.application);
		assertEquals("a0123456789abcdef", e.application.id);
		assertEquals("rejected", e.application.status);
		assertEquals("Moderator:in", e.application.title("de"));
		assertEquals("Moderator", e.application.title("pt-BR"), "Rückfall auf Englisch");
		assertEquals("Danke!", e.application.response, "Steuerzeichen raus");
		assertNull(MeEvent.parse("application_updated", app("hired", null), "e.2").application);
		assertNull(MeEvent.parse("application_updated", "{\"application\":{\"id\":\"../x\",\"status\":\"new\"}}", "e.3").application);
		assertNull(MeEvent.parse("report_update", app("new", null), "e.4").application);
	}

	@Test
	void toastTextInTheGameLanguage() {
		I18n.use("de");
		MeEvent.Application a = MeEvent.parse("application_updated", app("rejected", "Diesmal nicht"), "e.1").application;
		String[] toast = Social.applicationToast(a, "de");
		assertEquals("Deine Bewerbung: Moderator:in", toast[0]);
		assertEquals("Diesmal leider nicht angenommen. – „Diesmal nicht“", toast[1]);
		I18n.use("en");
		MeEvent.Application w = MeEvent.parse("application_updated", app("withdrawn", "egal"), "e.2").application;
		assertEquals("Withdrawn.", Social.applicationToast(w, "en")[1], "zurückgezogen: ohne Antwort");
		assertNull(Social.applicationToast(null, "en"));
	}

	@Test
	void everyStatusIsTranslatedInAllLanguages() {
		for (String lang : I18n.LANGUAGES) {
			Map<String, String> raw = I18n.raw(lang);
			for (String s : new String[]{"new", "review", "interview", "accepted", "rejected", "withdrawn"}) {
				assertTrue(raw.containsKey("social.toast.application." + s), lang + " " + s);
			}
			assertTrue(raw.containsKey("social.toast.applicationTitle"), lang);
			assertTrue(raw.containsKey("social.toast.applicationResponse"), lang);
		}
	}

	@Test
	void eventShowsOneToastPerApplication() {
		long now = System.currentTimeMillis();
		Opener opener = new Opener();
		opener.defaultStatus = 503;
		Social social = new Social(new ChatApi(new FakeHttp(), "http://127.0.0.1:1"), new MeStream(opener, "http://127.0.0.1:1"),
				new Backend(), DIRECT, DIRECT, DIRECT);
		social.applyForTest(MeEvent.parse("application_updated", app("review", null), "e.1"), now, false);
		social.applyForTest(MeEvent.parse("application_updated", app("interview", null), "e.2"), now, false);
		List<Toasts.Toast> visible = social.toasts().visible(now);
		assertEquals(1, visible.size(), "gleiche Bewerbung ersetzt den Hinweis");
		assertEquals("Your application: Moderator", visible.get(0).title);
		assertEquals("Invited to an interview.", visible.get(0).text);
	}
}
