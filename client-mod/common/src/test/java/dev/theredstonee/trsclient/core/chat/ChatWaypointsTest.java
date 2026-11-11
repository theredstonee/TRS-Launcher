package dev.theredstonee.trsclient.core.chat;

import dev.theredstonee.trsclient.core.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wegpunkte von Xaero's Minimap und JourneyMap im Chat: erkennen, zurücklesen und selbst schreiben. */
class ChatWaypointsTest {
	@AfterEach
	void english() {
		I18n.use("en");
	}

	private static ChatWaypoints.Shared only(String line) {
		List<ChatCoords.Hit> hits = ChatCoords.find(line);
		assertEquals(1, hits.size(), "ein Treffer erwartet in: " + line);
		ChatCoords.Hit h = hits.get(0);
		assertNotNull(h.shared, "geteilter Wegpunkt erwartet in: " + line);
		assertEquals(h.shared.text, line.substring(h.start, h.end));
		assertEquals(h.shared.text, h.insertion());
		assertEquals(h.x, h.shared.x);
		assertEquals(h.z, h.shared.z);
		return h.shared;
	}

	// --- Xaero's Minimap ---

	@Test
	void xaeroModernShare() {
		ChatWaypoints.Shared s = only("<Steve> xaero-waypoint:Steves Base:S:350:72:-260:10:false:0:Internal-overworld");
		assertSame(ChatWaypoints.Format.XAERO, s.format);
		assertEquals("Steves Base", s.name);
		assertEquals("S", s.mark);
		assertEquals(350, s.x);
		assertEquals(72, s.y);
		assertEquals(-260, s.z);
		assertTrue(s.hasY);
		assertEquals("minecraft:overworld", s.dimension);
		assertEquals(0x55FF55, s.color);
		assertEquals("xaero-waypoint:Steves Base:S:350:72:-260:10:false:0:Internal-overworld", s.text);
	}

	@Test
	void xaeroEscapesUnknownHeightAndDimensions() {
		ChatWaypoints.Shared s = only("xaero-waypoint:Home-base^min^east^col^1:HB:-12:~:9:12:true:90:Internal-the-nether");
		assertEquals("Home_base-east:1", s.name);
		assertEquals("HB", s.mark);
		assertFalse(s.hasY);
		assertEquals("minecraft:the_nether", s.dimension);
		assertEquals(0xFF0000, s.color);
		assertEquals("minecraft:the_end", ChatWaypoints.parse("xaero-waypoint:E:E:1:64:3:0:false:0:Internal-the-end").dimension);
		assertEquals("Stern*", ChatWaypoints.parse("xaero-waypoint:Stern^ast^:S:1:64:3:0:false:0:Internal-the-end").name);
	}

	@Test
	void xaeroOlderVersionsWithWaypointSetAndLegacyPrefix() {
		assertEquals("minecraft:the_nether",
				ChatWaypoints.parse("xaero-waypoint:Home:H:1:64:3:12:false:0:Internal-the-nether-waypoints").dimension);
		assertEquals("minecraft:overworld",
				ChatWaypoints.parse("xaero-waypoint:Home:H:1:64:3:12:false:0:Internal-overworld-mw$default").dimension);
		// „Internal“ ohne Dimension, „Internal-waypoints“ (nur der Satz) und „External“: keine Angabe.
		assertNull(ChatWaypoints.parse("xaero-waypoint:Home:H:1:64:3:12:false:0:Internal-waypoints").dimension);
		assertNull(ChatWaypoints.parse("xaero-waypoint:Home:H:1:64:3:12:false:0:Internal").dimension);
		assertNull(ChatWaypoints.parse("xaero-waypoint:Home:H:1:64:3:12:false:0:External").dimension);
		// Ohne Ziel (letzter Parameter darf fehlen).
		ChatWaypoints.Shared s = ChatWaypoints.parse("xaero-waypoint:Home:H:1:64:3:12:false:0");
		assertNotNull(s);
		assertNull(s.dimension);
		// Alte Präfixe: nur „^col^“ ist maskiert, Bindestriche bleiben.
		s = only("[Server] Alex: xaero_waypoint:Mine-1:M:100:12:-5:6:false:0");
		assertEquals("Mine-1", s.name);
		assertEquals(0xFFAA00, s.color);
		assertNotNull(ChatWaypoints.parse("xaero_waypoint_add:Shop:S:5:70:5:3:false:0:Internal-overworld"));
	}

	@Test
	void xaeroModdedAndNumericDimensions() {
		assertEquals("twilightforest:twilight_forest",
				ChatWaypoints.parse("xaero-waypoint:T:T:1:64:3:2:false:0:Internal-dim%twilightforest$twilight-forest").dimension);
		assertEquals("example:rooms/base..",
				ChatWaypoints.parse("xaero-waypoint:T:T:1:64:3:2:false:0:Internal-dim%example$rooms%base,,").dimension);
		assertEquals("example:room_2", ChatWaypoints.parse("xaero-waypoint:T:T:1:64:3:2:false:0:Internal-dim%example$room-2").dimension);
		assertEquals("minecraft:the_nether", ChatWaypoints.parse("xaero-waypoint:T:T:1:64:3:2:false:0:Internal-dim%^min^1").dimension);
		assertEquals("legacy:dim7", ChatWaypoints.parse("xaero-waypoint:T:T:1:64:3:2:false:0:Internal-dim%7").dimension);
	}

	@Test
	void xaeroColorsWrapAndRandomHasNone() {
		assertEquals(0x000000, ChatWaypoints.xaeroColor(0));
		assertEquals(0xFFFFFF, ChatWaypoints.xaeroColor(15));
		assertEquals(0x0000AA, ChatWaypoints.xaeroColor(17));
		assertEquals(-1, ChatWaypoints.xaeroColor(-1));
		assertEquals(-1, ChatWaypoints.parse("xaero-waypoint:R:R:1:64:3:-1:false:0:Internal-overworld").color);
	}

	@Test
	void xaeroSuffixOfOtherModsBelongsToTheShare() {
		String line = "<Piny> xaero-waypoint:Spawn:S:0:64:0:3:false:0:Internal-overworld:xaeroshare";
		ChatWaypoints.Shared s = only(line);
		assertEquals("Spawn", s.name);
		assertTrue(s.text.endsWith(":xaeroshare"));
		assertNotNull(ChatWaypoints.parse(s.text));
	}

	@Test
	void xaeroRejectsBrokenShares() {
		assertNull(ChatWaypoints.parse("xaero-waypoint::S:1:64:3:3:false:0:Internal-overworld"));
		assertNull(ChatWaypoints.parse("xaero-waypoint:Far:F:30000001:64:3:3:false:0:Internal-overworld"));
		assertNull(ChatWaypoints.parse("xaero-waypoint:Deep:D:1:-5000:3:3:false:0:Internal-overworld"));
		assertNull(ChatWaypoints.parse("xaero-waypoint:X:X:a:64:3:3:false:0:Internal-overworld"));
		assertNull(ChatWaypoints.parse("xaero-waypoint:X:X:1:64:3"));
		assertNull(ChatWaypoints.parse("ich nutze xaero-waypoint: Mod"));
		assertTrue(ChatCoords.find("superxaero-waypoint:A:A:1:2:3:4:false:0:Internal-overworld").isEmpty());
	}

	// --- JourneyMap ---

	@Test
	void journeyMapModernShare() {
		ChatWaypoints.Shared s = only("<Alex> schau mal [name:\"Base, Ost\", x:-1174, y:72, z:-1394, dim:minecraft:the_nether] hier");
		assertSame(ChatWaypoints.Format.JOURNEYMAP, s.format);
		assertEquals("Base, Ost", s.name);
		assertEquals(-1174, s.x);
		assertEquals(72, s.y);
		assertEquals(-1394, s.z);
		assertTrue(s.hasY);
		assertEquals("minecraft:the_nether", s.dimension);
		assertEquals(-1, s.color);
		assertEquals("", s.mark);
	}

	@Test
	void journeyMapDocumentedForms() {
		ChatWaypoints.Shared s = only("[x:12, z:-9]");
		assertEquals("", s.name);
		assertFalse(s.hasY);
		assertNull(s.dimension);
		s = only("NPC says: \"Here's where I buried my loot:\" [name:treasure, x:1212, y:70, z:456, dim:0]");
		assertEquals("treasure", s.name);
		assertEquals("minecraft:overworld", s.dimension);
		assertEquals("minecraft:the_nether", ChatWaypoints.parse("[name:'Tor', dim:-1, z:4, y:60, x:3]").dimension);
		assertEquals("legacy:dim7", ChatWaypoints.parse("[x:1, y:2, z:3, dim:7]").dimension);
		assertEquals("minecraft:the_end", ChatWaypoints.parse("[X: 1, Y: 2, Z: 3, DIM: the_end]").dimension);
		assertEquals("Steve's house", ChatWaypoints.parse("[name:\"Steve's house\", x:1, y:2, z:3]").name);
		assertEquals("Doppel:punkt", ChatWaypoints.parse("[name:\"Doppel:punkt\", x:1, y:2, z:3]").name);
		// VoxelMap: gleiche Klammern, Farbe und Symbol dürfen dabei sein.
		s = ChatWaypoints.parse("[name:Home, x:1, y:64, z:3, dim:minecraft:overworld, icon:star, color:#3D7BFF]");
		assertEquals(0x3D7BFF, s.color);
	}

	@Test
	void journeyMapRejectsOrdinaryBrackets() {
		for (String line : new String[]{"[Admin] Willkommen!", "[x:1, y:2]", "[name:Home, x:NaN, z:3]", "[x:1, x:2, z:3]",
				"[name:Home, x:1, z:3, dim:bad dimension]", "[x:1, z:2, ]", "[x:1, z:2, foo]", "[name:\"a\"b, x:1, z:2]",
				"[x:99999999, z:2]", "[x:1, y:9999, z:2]"}) {
			assertNull(ChatWaypoints.parse(line), line);
			for (ChatCoords.Hit h : ChatCoords.find(line)) assertNull(h.shared, line);
		}
	}

	@Test
	void sharesWinOverTheCoordinatesInsideAndMixWithPlainOnes() {
		String line = "Treffpunkt [name:\"Mitte\", x:100, y:64, z:-20] und Portal 5 70 -300, Xaero: "
				+ "xaero-waypoint:Dorf:D:-1500:~:900:6:false:0:Internal-overworld";
		List<ChatCoords.Hit> hits = ChatCoords.find(line);
		assertEquals(3, hits.size());
		assertEquals("Mitte", hits.get(0).shared.name);
		assertNull(hits.get(1).shared);
		assertEquals(5, hits.get(1).x);
		assertEquals("Dorf", hits.get(2).shared.name);
		assertTrue(hits.get(0).end <= hits.get(1).start && hits.get(1).end <= hits.get(2).start);
		// Einfache Koordinaten behalten ihren Marker „x y z“.
		assertEquals("5 70 -300", hits.get(1).insertion());
	}

	@Test
	void parseOnlyAcceptsWholeShares() {
		assertNull(ChatWaypoints.parse(null));
		assertNull(ChatWaypoints.parse("100 64 -20"));
		assertNull(ChatWaypoints.parse("Steve"));
		assertNull(ChatWaypoints.parse("[x:1, z:2] [x:3, z:4]"));
		assertNull(ChatWaypoints.parse("<A> xaero-waypoint:H:H:1:2:3:4:false:0:Internal-overworld"));
		assertNotNull(ChatWaypoints.parse(" [x:1, z:2] "));
	}

	// --- Schreiben ---

	@Test
	void writesXaeroAndReadsItBack() {
		String line = ChatWaypoints.xaero("Home_base-east:1*", "", 100, 64, -200, 0x3DDC84, "minecraft:the_nether");
		assertEquals("xaero-waypoint:Home-base^min^east^col^1^ast^:H:100:64:-200:10:false:0:Internal-the-nether", line);
		ChatWaypoints.Shared s = ChatWaypoints.parse(line);
		assertEquals("Home_base-east:1*", s.name);
		assertEquals("H", s.mark);
		assertEquals("minecraft:the_nether", s.dimension);
		assertEquals(0x55FF55, s.color);
		assertEquals("xaero-waypoint:Ziel:ZI:1:2:3:9:false:0:Internal-overworld",
				ChatWaypoints.xaero("Ziel", "ZIEL", 1, 2, 3, -1, "minecraft:overworld"));
		assertEquals("Internal-the-end", ChatWaypoints.xaeroDestination("minecraft:the_end"));
		assertEquals("Internal-dim%twilightforest$twilight-forest",
				ChatWaypoints.xaeroDestination("twilightforest:twilight_forest"));
		assertEquals("Internal-dim%^min^5", ChatWaypoints.xaeroDestination("legacy:dim-5"));
		assertEquals("legacy:dim-5", ChatWaypoints.xaeroDimension("Internal-dim%^min^5"));
		assertEquals("Internal", ChatWaypoints.xaeroDestination(null));
		for (String dim : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "legacy:dim7",
				"twilightforest:twilight_forest", "example:rooms/base.v2", "a-b:c-d_e"}) {
			assertEquals(dim, ChatWaypoints.parse(ChatWaypoints.xaero("W", "W", 1, 2, 3, 0, dim)).dimension, dim);
		}
	}

	@Test
	void writesJourneyMapAndReadsItBack() {
		assertEquals("[name:\"Base, Ost\", x:-1174, y:72, z:-1394, dim:minecraft:the_nether]",
				ChatWaypoints.journeyMap("Base, Ost", -1174, 72, -1394, "minecraft:the_nether", false));
		assertEquals("[name:\"Tor\", x:1, y:2, z:3, dim:-1]", ChatWaypoints.journeyMap("Tor", 1, 2, 3, "minecraft:the_nether", true));
		assertEquals("[name:\"Mod\", x:1, y:2, z:3, dim:7]", ChatWaypoints.journeyMap("Mod", 1, 2, 3, "legacy:dim7", true));
		assertEquals("[x:1, y:2, z:3]", ChatWaypoints.journeyMap("", 1, 2, 3, null, false));
		// Anführungszeichen und Klammern würden den Ort zerstören.
		String line = ChatWaypoints.journeyMap("Der \"[Bunker]\"", 1, 2, 3, "minecraft:overworld", false);
		ChatWaypoints.Shared s = ChatWaypoints.parse(line);
		assertNotNull(s, line);
		assertEquals("Der (Bunker)", s.name);
		assertEquals(1, ChatCoords.find("<Me> " + line).size());
		for (String dim : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "legacy:dim7",
				"twilightforest:twilight_forest"}) {
			assertEquals(dim, ChatWaypoints.parse(ChatWaypoints.journeyMap("W", 1, 2, 3, dim, false)).dimension, dim);
			assertEquals(dim, ChatWaypoints.parse(ChatWaypoints.journeyMap("W", 1, 2, 3, dim, true)).dimension, dim);
		}
	}

	@Test
	void ownMessagesStayShortCleanAndNeverCommands() {
		String longName = "Ein_sehr-langer:Name*mit-vielen_Zeichen";
		String x = ChatWaypoints.xaero(longName, "", -29999999, -64, 29999999, 0xFFFFFF, "twilightforest:twilight_forest");
		String j = ChatWaypoints.journeyMap(longName, -29999999, -64, 29999999, "twilightforest:twilight_forest", false);
		for (String line : new String[]{x, j}) {
			assertTrue(line.length() <= ChatWaypoints.MAX_MESSAGE, line + " (" + line.length() + ")");
			assertFalse(line.startsWith("/"), line);
			assertNotNull(ChatWaypoints.parse(line), line);
		}
		String colored = ChatWaypoints.xaero((char) 0xA7 + "cRot\nNeu", "", 1, 2, 3, 0xE0281E, "minecraft:overworld");
		assertEquals("xaero-waypoint:Rot Neu:R:1:2:3:12:false:0:Internal-overworld", colored);
		assertTrue(ChatWaypoints.xaero("", "", 7, 8, 9, 0, null).startsWith("xaero-waypoint:7, 9:7:7:8:9:"));
	}

	@Test
	void nearestXaeroColorsForTheTrsPalette() {
		assertEquals(12, ChatWaypoints.xaeroColorIndex(0xE0281E));
		assertEquals(6, ChatWaypoints.xaeroColorIndex(0xFF7A1F));
		assertEquals(14, ChatWaypoints.xaeroColorIndex(0xF2E14C));
		assertEquals(10, ChatWaypoints.xaeroColorIndex(0x3DDC84));
		assertEquals(11, ChatWaypoints.xaeroColorIndex(0x4DD8E0));
		assertEquals(9, ChatWaypoints.xaeroColorIndex(0x3D7BFF));
		assertEquals(13, ChatWaypoints.xaeroColorIndex(0xFF6FB5));
		assertEquals(15, ChatWaypoints.xaeroColorIndex(0xFFFFFF));
		for (int i = 0; i < 16; i++) assertEquals(i, ChatWaypoints.xaeroColorIndex(ChatWaypoints.xaeroColor(i)));
	}

	@Test
	void hoverNamesModPositionAndDimension() {
		I18n.use("en");
		String hover = ChatWaypoints.hover(ChatWaypoints.parse("xaero-waypoint:Dorf:D:-1500:~:900:6:false:0:Internal-the-nether"));
		assertTrue(hover.contains("Dorf"), hover);
		assertTrue(hover.contains("Xaero's Minimap · -1500 ~ 900 · "), hover);
		assertEquals(2, hover.split("\n").length, hover);
		ChatCoords.Hit plain = ChatCoords.find("100 64 -20").get(0);
		assertEquals("coords", plain.hover("coords"));
	}

	private static String joined(List<ChatWaypoints.Part> parts) {
		StringBuilder b = new StringBuilder();
		for (ChatWaypoints.Part p : parts) b.append(p.text);
		return b.toString();
	}

	@Test
	void displayLooksLikeXaeroWithAddButtonAndModAtTheEnd() {
		I18n.use("en");
		ChatWaypoints.Shared s = ChatWaypoints.parse("xaero-waypoint:Dorf:D:-1500:~:900:6:false:0:Internal-the-nether");
		List<ChatWaypoints.Part> parts = ChatWaypoints.display(s);
		assertEquals("shared a waypoint called “Dorf” from Nether! [Add] · Xaero's Minimap", joined(parts));
		// Name: Klick öffnet das Fenster (Einfüge-Text = Nachricht), „[Add]“: sofort speichern, Mod-Name zuletzt.
		ChatWaypoints.Part name = parts.get(1);
		assertEquals(ChatWaypoints.Part.Kind.NAME, name.kind);
		assertEquals("Dorf", name.text);
		assertEquals(s.text, name.insertion);
		ChatWaypoints.Part add = parts.get(parts.size() - 2);
		assertEquals(ChatWaypoints.Part.Kind.ADD, add.kind);
		assertTrue(ChatWaypoints.isAddMarker(add.insertion));
		assertTrue(add.hover.contains("Dorf") && add.hover.contains("-1500 ~ 900"), add.hover);
		assertEquals(ChatWaypoints.Part.Kind.MOD, parts.get(parts.size() - 1).kind);
		// Echte Klick-Aktion (damit Minecraft und andere Chat-Mods den Knopf als anklickbar behandeln).
		assertEquals("/trsclient-waypoint-add " + s.text, add.command);
		ChatWaypoints.Shared viaCommand = ChatWaypoints.parseAddCommand(add.command);
		assertNotNull(viaCommand);
		assertEquals("Dorf", viaCommand.name);
		assertNull(ChatWaypoints.parseAddCommand("/tp 1 2 3"));
		assertNull(ChatWaypoints.parseAddCommand(null));
		assertNull(name.command);
		ChatWaypoints.Shared back = ChatWaypoints.parseAddMarker(add.insertion);
		assertNotNull(back);
		assertEquals("Dorf", back.name);
		assertEquals("minecraft:the_nether", back.dimension);
		// Der Name ist kein „[Add]“, ein „[Add]“ kein Name.
		assertNull(ChatWaypoints.parseAddMarker(name.insertion));
		assertNull(ChatWaypoints.parse(add.insertion));
	}

	@Test
	void displayInGermanAndWithoutDimensionOrName() {
		I18n.use("de");
		ChatWaypoints.Shared turm = ChatWaypoints.parse("[name:\"Turm\", x:40, y:90, z:-60, dim:minecraft:overworld]");
		assertEquals("hat einen Wegpunkt namens „Turm“ geteilt (Oberwelt)! [Hinzufügen] · JourneyMap", joined(ChatWaypoints.display(turm)));
		ChatWaypoints.Shared bare = ChatWaypoints.parse("[x:1, z:2]");
		assertEquals("hat einen Wegpunkt namens „Aus dem Chat“ geteilt! [Hinzufügen] · JourneyMap", joined(ChatWaypoints.display(bare)));
	}

	@Test
	void displayReplacesTheRawShareInTheLine() {
		I18n.use("en");
		List<ChatCoords.Hit> hits = ChatCoords.find("<Alex> xaero-waypoint:Dorf:D:-1500:~:900:6:false:0:Internal-the-nether");
		assertEquals(1, hits.size());
		assertEquals("<Alex> ", "<Alex> xaero-waypoint:Dorf:D:-1500:~:900:6:false:0:Internal-the-nether".substring(0, hits.get(0).start));
	}

	@Test
	void xaerosOwnAddCommandIsReadBack() {
		// So baut Xaero's Minimap den Klick hinter seinem „[Add]“: „/“ + xaero_waypoint_add: + die Felder der Nachricht.
		ChatWaypoints.Shared s = ChatWaypoints.fromClickCommand("/xaero_waypoint_add:Dorf:D:-1500:~:900:6:false:0:Internal-the-nether");
		assertNotNull(s);
		assertEquals(ChatWaypoints.Format.XAERO, s.format);
		assertEquals("Dorf", s.name);
		assertEquals(-1500, s.x);
		assertFalse(s.hasY);
		assertEquals(900, s.z);
		assertEquals("minecraft:the_nether", s.dimension);
		// Maskierte Zeichen wie in der modernen Nachricht („^min^“ = „-“, „-“ = „_“).
		ChatWaypoints.Shared esc = ChatWaypoints.fromClickCommand("/xaero_waypoint_add:Spawn^min^Hub:S:1:64:2:9:false:0:Internal-overworld");
		assertNotNull(esc);
		assertEquals("Spawn-Hub", esc.name);
		assertEquals("minecraft:overworld", esc.dimension);
		// Andere Befehle und Links sind keine Wegpunkte.
		assertNull(ChatWaypoints.fromClickCommand(null));
		assertNull(ChatWaypoints.fromClickCommand("/tp 1 2 3"));
		assertNull(ChatWaypoints.fromClickCommand("/xaero_waypoint_add:kaputt"));
		assertNull(ChatWaypoints.fromClickCommand("https://example.org/xaero_waypoint_add:a:b:1:2:3:4"));
	}
}
