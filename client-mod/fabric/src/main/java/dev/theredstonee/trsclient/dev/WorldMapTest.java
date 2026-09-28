package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.MapDimensions;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapExport;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.screen.WorldMapScreen;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Selbsttest Weltkarte 2 – Teil von {@code -PtrsAutotestOnly=maps}, eigene Klasse ({@link MapsTest} ruft nur
 * {@link #step}): kurz in den Nether (echte Kartendaten dort), zurück, dann Weltkarte mit Wegpunkt-Liste und Suche,
 * stufenloser Zoom zur Maus (Punkt unter dem Zeiger bleibt stehen), weit herausgezoomt (Weitkacheln), Nether von der
 * Oberwelt aus ansehen, Export (Ausschnitt und ganz) mit Dateiprüfung. Screenshots: trsclient-&lt;mc&gt;-maps-worldmap2-*.png,
 * Log-Zeilen „[Autotest] Karten: Weltkarte 2: …“.
 */
final class WorldMapTest {
	private int phase;
	private int polls;
	private boolean settled;
	private int nx, nz;
	private int sx, sy;
	private double[] anchor;
	private float before;

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Karten: Weltkarte 2: {}", text);
	}

	private static void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-maps-" + name);
		MapEngine e = MapEngine.get();
		log(String.format(Locale.ROOT, "Bild %s – %d Texturen, %d hochgeladen, %d Ladeaufträge übersprungen", name,
				e.textures().regionTextureCount(), e.textures().uploads(), e.disk() == null ? 0 : e.disk().skippedLoads()));
	}

	private static WorldMapUi ui() {
		return (WorldMapUi) ((TrsUiScreen) Mc.screen()).ui();
	}

	private static void logExport(String what) {
		MapExport ex = MapExport.current();
		if (ex == null) {
			log("Export " + what + ": nicht gestartet");
			return;
		}
		long size = -1;
		try {
			size = Files.isRegularFile(ex.file()) ? Files.size(ex.file()) : -1;
		} catch (java.io.IOException ignored) {
			// bleibt -1
		}
		log(String.format(Locale.ROOT, "Export %s: %s, Datei %s, vorhanden %s (%d Bytes), %d×%d px, %d Blöcke/px, %d ms%s", what,
				ex.state(), ex.file().toAbsolutePath(), size >= 0, size, ex.plan().width, ex.plan().height, ex.plan().k, ex.millis(),
				ex.error() == null ? "" : ", Fehler " + ex.error()));
	}

	/** Nächster Schritt; liefert die Wartezeit in Ticks oder -1, wenn fertig. */
	int step(Minecraft mc, int bx, int by, int bz) {
		MapEngine e = MapEngine.get();
		switch (phase) {
			case 0:
				// Kurz in den Nether: dort entstehen echte Kartendaten (Höhlenschicht).
				nx = Math.floorDiv(bx, 8);
				nz = Math.floorDiv(bz, 8);
				AutoTest.command(mc, "execute in minecraft:the_nether run tp @p " + nx + " 70 " + nz);
				phase++;
				return 60;
			case 1:
				AutoTest.command(mc, "execute in minecraft:the_nether run fill " + (nx - 8) + " 61 " + (nz - 8) + " " + (nx + 8) + " 66 " + (nz + 8) + " air");
				AutoTest.command(mc, "execute in minecraft:the_nether run fill " + (nx - 8) + " 60 " + (nz - 8) + " " + (nx + 8) + " 60 " + (nz + 8) + " netherrack");
				AutoTest.command(mc, "execute in minecraft:the_nether run tp @p " + nx + " 61 " + nz + " 0 30");
				phase++;
				return 120;
			case 2:
				log("im Nether: " + e.dimension() + ", Höhlenansicht " + e.caveActive());
				shot(mc, "worldmap2-nether-minimap");
				AutoTest.command(mc, "execute in minecraft:overworld run tp @p " + bx + " " + (by + 20) + " " + bz + " 225 30");
				phase++;
				return 80;
			case 3:
				log("zurück in " + e.dimension());
				Mc.setScreen(WorldMapScreen.create());
				phase++;
				return 40;
			case 4:
				ui().testSidebar(true, "");
				phase++;
				return 10;
			case 5:
				shot(mc, "worldmap2-list");
				log("Wegpunkt-Liste: " + ui().testListRows() + " Einträge");
				ui().testSidebar(true, "bas");
				phase++;
				return 10;
			case 6: {
				shot(mc, "worldmap2-search");
				log("Suche „bas“: " + ui().testListRows() + " Treffer (erwartet 1)");
				ui().testSidebar(false, "");
				sx = Mc.window().getGuiScaledWidth() / 2 + 80;
				sy = Mc.window().getGuiScaledHeight() / 2 - 40;
				anchor = ui().testWorldAt(sx, sy);
				before = ui().testScreenPx();
				ui().testScroll(3, sx, sy);
				phase++;
				return 30;
			}
			case 7: {
				double[] after = ui().testWorldAt(sx, sy);
				double moved = Math.hypot(after[0] - anchor[0], after[1] - anchor[1]);
				log(String.format(Locale.ROOT, "stufenlos zoomen: %.2f → %.2f px/Block, Punkt unter der Maus um %.3f Blöcke verschoben (erwartet ≈ 0)",
						before, ui().testScreenPx(), moved));
				shot(mc, "worldmap2-zoom");
				ui().setScreenPxNow(0.1f);
				phase++;
				return 60;
			}
			case 8: {
				shot(mc, "worldmap2-far");
				log("weit (0.1 px/Block): Kachelstufe " + ui().testTileLevel() + " (erwartet 2)");
				List<String> dims = ui().testDimensions();
				log("Dimensionen: " + dims);
				String nether = null;
				for (String d : dims) if (MapDimensions.kind(d) == MapDimensions.NETHER) nether = d;
				if (nether == null) {
					log("FEHLER: Nether nicht in der Liste");
					phase = 10;
					return 5;
				}
				log("Nether ansehen: " + ui().viewDimension(nether));
				ui().setScreenPxNow(4f);
				phase++;
				return 80;
			}
			case 9:
				shot(mc, "worldmap2-nether");
				log("Ansicht jetzt: " + ui().viewDimension() + " (andere Dimension: " + ui().viewingOther() + ")");
				ui().viewDimension(null);
				ui().setScreenPxNow(2f);
				phase++;
				return 30;
			case 10:
				log("Export Ausschnitt gestartet: " + ui().startExport(true));
				polls = 0;
				phase++;
				return 10;
			case 11:
				if (MapExport.running() && polls++ < 400) return 5;
				// Die Meldung kommt über den nächsten Karten-Tick an: kurz warten.
				if (!settled) {
					settled = true;
					return 5;
				}
				logExport("Ausschnitt");
				log("Hinweis über der Leiste: " + java.util.Arrays.toString(ui().testToast()));
				shot(mc, "worldmap2-export");
				log("Export ganz gestartet: " + ui().startExport(false));
				polls = 0;
				phase++;
				return 10;
			case 12:
				if (MapExport.running() && polls++ < 1200) return 5;
				logExport("ganz");
				Mc.setScreen(null);
				phase++;
				return 5;
			default:
				return -1;
		}
	}
}
