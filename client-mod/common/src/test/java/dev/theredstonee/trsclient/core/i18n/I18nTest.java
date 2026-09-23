package dev.theredstonee.trsclient.core.i18n;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.Setting;
import dev.theredstonee.trsclient.core.module.TextSetting;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.module.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Übersetzungen: Vollständigkeit je Sprache, Platzhalter, Rückfall, Sprachwahl. */
class I18nTest {
	/** Diese Sprachen müssen vollständig sein; die Beta-Sprachen werden nur gemeldet (wie im Launcher). */
	private static final List<String> FULL = Arrays.asList("en", "de", "es");
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d)}");
	/** I18n.tr("…") im Code – statische Schlüssel. */
	private static final Pattern TR_CALL = Pattern.compile("I18n\\.tr(?:Or)?\\(\"([A-Za-z0-9_.]*[A-Za-z0-9_])\"");

	@TempDir
	Path dir;

	@AfterEach
	void backToEnglish() {
		I18n.use("en");
	}

	@Test
	void everyLanguageHasAllKeysOfEnglish() {
		Map<String, String> en = I18n.raw("en");
		assertTrue(en.size() > 200, "en.json fehlt oder ist leer");
		StringBuilder report = new StringBuilder("TRS Client – Übersetzungsstand:\n");
		for (String lang : I18n.LANGUAGES) {
			Map<String, String> table = I18n.raw(lang);
			List<String> missing = new ArrayList<String>();
			for (String key : en.keySet()) {
				String v = table.get(key);
				if (v == null || v.trim().isEmpty()) missing.add(key);
			}
			Set<String> extra = new TreeSet<String>(table.keySet());
			extra.removeAll(en.keySet());
			int pct = (int) Math.floor((en.size() - missing.size()) * 100.0 / en.size());
			report.append(String.format("  %-6s %3d %% (%d/%d)%s%n", lang, pct, en.size() - missing.size(), en.size(),
					I18n.BETA.contains(lang) ? "  beta" : ""));
			assertTrue(extra.isEmpty(), lang + ": Schlüssel, die es im Englischen nicht gibt: " + extra);
			if (FULL.contains(lang) || I18n.BETA.contains(lang)) {
				assertTrue(missing.isEmpty(), lang + ": fehlende Schlüssel " + missing);
			}
		}
		System.out.print(report);
	}

	@Test
	void placeholdersMatchEnglish() {
		Map<String, String> en = I18n.raw("en");
		for (String lang : I18n.LANGUAGES) {
			Map<String, String> table = I18n.raw(lang);
			for (Map.Entry<String, String> e : table.entrySet()) {
				String base = en.get(e.getKey());
				if (base == null) continue;
				assertEquals(placeholders(base), placeholders(e.getValue()),
						lang + ": Platzhalter von " + e.getKey() + " passen nicht: " + e.getValue());
			}
		}
	}

	@Test
	void everyModuleSettingAndOptionIsTranslatedInEnglish() {
		TrsModules modules = new TrsModules();
		Map<String, String> en = I18n.raw("en");
		List<String> missing = new ArrayList<String>();
		for (Category c : Category.values()) require(en, c.key(), missing);
		for (Module m : modules.registry.all()) {
			require(en, m.nameKey(), missing);
			require(en, m.descriptionKey(), missing);
			for (Setting s : m.settings()) {
				require(en, s.labelKey(), missing);
				if (s instanceof ChoiceSetting) {
					ChoiceSetting<?> choice = (ChoiceSetting<?>) s;
					for (int i = 0; i < choice.size(); i++) require(en, choice.optionKey(i), missing);
				}
				if (s instanceof TextSetting && ((TextSetting) s).placeholderKey() != null) {
					require(en, ((TextSetting) s).placeholderKey(), missing);
				}
			}
		}
		assertTrue(missing.isEmpty(), "fehlende Schlüssel in en.json: " + missing);
	}

	/** Jeder feste Schlüssel aus I18n.tr("…") im Code aller Loader-Bäume steht in en.json. */
	@Test
	void everyKeyUsedInCodeExists() throws IOException {
		Path root = clientModRoot();
		Map<String, String> en = I18n.raw("en");
		List<String> missing = new ArrayList<String>();
		int files = 0;
		for (String tree : Arrays.asList("common", "fabric", "neoforge", "forge", "forge-mojmap-legacy", "legacy",
				"legacy-1.7.10", "forge-1.13.2")) {
			Path src = root.resolve(tree).resolve("src").resolve("main").resolve("java");
			if (!Files.isDirectory(src)) continue;
			List<Path> java = new ArrayList<Path>();
			try (Stream<Path> walk = Files.walk(src)) {
				walk.filter(p -> p.toString().endsWith(".java")).forEach(java::add);
			}
			for (Path p : java) {
				files++;
				Matcher m = TR_CALL.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
				while (m.find()) {
					String key = m.group(1);
					if (!en.containsKey(key)) missing.add(tree + ": " + key);
				}
			}
		}
		assertTrue(files > 0);
		assertTrue(missing.isEmpty(), "Schlüssel fehlen in en.json: " + missing);
	}

	/** Die Tastenbelegungs-Namen in den Minecraft-Sprachdateien stammen aus den i18n-Dateien. */
	@Test
	void minecraftLangFilesMatchTranslations() throws IOException {
		Path root = clientModRoot();
		Path lang = root.resolve("fabric/src/main/resources/assets/trsclient/lang/de_de.json");
		if (!Files.isRegularFile(lang)) return; // nur im Quellbaum
		String json = new String(Files.readAllBytes(lang), StandardCharsets.UTF_8);
		assertTrue(json.contains(I18n.raw("de").get("key.trsclient.menu")),
				"lang/de_de.json ist veraltet – scripts/gen-lang.mjs ausführen");
		String en = new String(Files.readAllBytes(root.resolve("fabric/src/main/resources/assets/trsclient/lang/en_us.json")),
				StandardCharsets.UTF_8);
		assertTrue(en.contains(I18n.raw("en").get("key.trsclient.menu")));
	}

	@Test
	void fallsBackToEnglishThenToTheKey() {
		I18n.use("de");
		assertEquals("Fertig", I18n.tr("common.done"));
		assertEquals("de", I18n.code());
		assertEquals("does.not.exist", I18n.tr("does.not.exist"));
		assertEquals("x", I18n.trOr("does.not.exist", "x"));
		I18n.use("klingonisch");
		assertEquals("en", I18n.code());
		assertEquals("Done", I18n.tr("common.done"));
	}

	@Test
	void formatsPlaceholdersAndKeepsApostrophes() {
		assertEquals("3 of 7 on", I18n.format("{0} of {1} on", 3, 7));
		assertEquals("l'Aide {1}", I18n.format("l'Aide {1}", "x"));
		assertEquals("{0}", I18n.format("{0}"));
		I18n.use("de");
		assertEquals("Höchstens 20 Zeichen", I18n.tr("profiles.error.long", 20));
	}

	@Test
	void labelsFollowTheLanguageWithoutReallocating() {
		TrsModules m = new TrsModules();
		I18n.use("en");
		assertEquals("Crosshair", m.crosshair.name());
		String first = m.crosshairShape.label();
		assertEquals("Shape", first);
		assertTrue(first == m.crosshairShape.label(), "zweiter Aufruf muss den Zwischenspeicher treffen");
		assertEquals("Text 2", m.hotkeyTexts[1].label());
		I18n.use("de");
		assertEquals("Fadenkreuz", m.crosshair.name());
		assertEquals("Form", m.crosshairShape.label());
		assertEquals("Kreuz", m.crosshairShape.display());
		assertEquals("Taste 3", m.hotkeyKeys[2].label());
		assertEquals("Welt", Category.WORLD.label());
		I18n.use("es");
		assertEquals("Mira", m.crosshair.name());
		assertTrue(m.crosshair.matches("mira"));
		assertTrue(m.crosshair.matches("crosshair"), "Englisch findet immer");
	}

	@Test
	void mapsMinecraftLocales() {
		assertEquals("de", I18n.fromMinecraft("de_de"));
		assertEquals("de", I18n.fromMinecraft("de_DE"));
		assertEquals("de", I18n.fromMinecraft("de_at"));
		assertEquals("es", I18n.fromMinecraft("es_mx"));
		assertEquals("es", I18n.fromMinecraft("es_es"));
		assertEquals("fr", I18n.fromMinecraft("fr_ca"));
		assertEquals("pt-BR", I18n.fromMinecraft("pt_br"));
		assertEquals("tr", I18n.fromMinecraft("tr_tr"));
		assertEquals("nl", I18n.fromMinecraft("nl_nl"));
		assertEquals("pl", I18n.fromMinecraft("pl_pl"));
		assertEquals("en", I18n.fromMinecraft("en_us"));
		assertNull(I18n.fromMinecraft("ja_jp"));
		assertEquals("pt-BR", I18n.supported("pt-br"));
		assertEquals("de", I18n.resolve("de", "es_es"));
		assertEquals("es", I18n.resolve(null, "es_es"));
		assertEquals("es", I18n.resolve("xx", "es_mx"));
		assertEquals("en", I18n.resolve(null, "ja_jp"));
	}

	@Test
	void launcherLanguageWinsOverMinecraftAndIsReReadWhenChanged() throws IOException {
		Path game = dir.resolve("minecraft");
		Path config = game.resolve("config");
		Files.createDirectories(config.resolve("trsclient"));
		Files.write(game.resolve("options.txt"), Arrays.asList("version:3700", "lang:es_es", "fov:0.0"), StandardCharsets.UTF_8);
		I18n.init(config);
		assertEquals("es", I18n.code(), "ohne Launcher-Datei gilt die Minecraft-Sprache");

		Path theme = config.resolve("trsclient").resolve("launcher-theme.json");
		Files.write(theme, "{\"version\":1,\"theme\":\"dark\",\"accent\":\"redstone\",\"language\":\"de\"}"
				.getBytes(StandardCharsets.UTF_8));
		I18n.refresh();
		assertEquals("de", I18n.code(), "Launcher-Sprache hat Vorrang");
		int gen = I18n.generation();
		I18n.refresh();
		assertEquals(gen, I18n.generation(), "unverändert → nichts neu laden");

		Files.write(theme, "{\"version\":1,\"language\":\"pt-BR\"}".getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(theme, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
		I18n.refresh();
		assertEquals("pt-BR", I18n.code());

		Files.delete(theme);
		Files.write(game.resolve("options.txt"), Collections.singletonList("lang:en_us"), StandardCharsets.UTF_8);
		I18n.refresh();
		assertEquals("en", I18n.code());
		assertNotEquals(gen, I18n.generation());

		Files.write(theme, "kaputt{".getBytes(StandardCharsets.UTF_8));
		I18n.refresh();
		assertEquals("en", I18n.code(), "kaputte Datei → Minecraft-Sprache");
		assertFalse(I18n.tr("common.done").isEmpty());
	}

	private static List<String> placeholders(String s) {
		List<String> out = new ArrayList<String>();
		Matcher m = PLACEHOLDER.matcher(s);
		while (m.find()) out.add(m.group(1));
		Collections.sort(out);
		return out;
	}

	private static void require(Map<String, String> en, String key, List<String> missing) {
		if (key == null || !en.containsKey(key)) missing.add(String.valueOf(key));
	}

	/** client-mod/ – Tests laufen in common/ (oder in forge/, neoforge/ mit eingebundenen Tests). */
	private static Path clientModRoot() {
		Path p = Paths.get("").toAbsolutePath();
		for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
			if (Files.isDirectory(p.resolve("common").resolve("src"))) return p;
		}
		return Paths.get("..").toAbsolutePath().normalize();
	}
}
