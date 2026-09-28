package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.MapColors;
import dev.theredstonee.trsclient.core.map.MapCompose;
import dev.theredstonee.trsclient.core.map.MapDimensions;
import dev.theredstonee.trsclient.core.map.MapDisk;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapExport;
import dev.theredstonee.trsclient.core.map.MapRegion;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.screen.WorldMapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Selbsttest Weltkarte 2 unter 1.8.9–1.12.2 – Teil von {@code -PtrsAutotestOnly=maps}, eigene Klasse
 * ({@link MapsTest} ruft nur {@link #step}). Diese Versionen haben kein {@code /execute in}: die Nether-Karte wird
 * deshalb als kleine Beispiel-Höhlenschicht direkt gespeichert (so wie die Karte sie selbst schreiben würde) und dann
 * von der Oberwelt aus angesehen. Sonst wie unter Fabric: Wegpunkt-Liste + Suche, stufenloser Zoom zur Maus,
 * Weitkacheln, Export (Ausschnitt und ganz). Screenshots: trsclient-&lt;mc&gt;-maps-worldmap2-*.png.
 */
final class WorldMapTest {
	private final String mcVersion = Mc.version();
	private int phase;
	private int polls;
	private boolean settled;
	private int sx, sy;
	private double[] anchor;
	private float before;

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Karten: Weltkarte 2: {}", text);
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-maps-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		MapEngine e = MapEngine.get();
		log(String.format(Locale.ROOT, "Bild %s – %d Texturen, %d hochgeladen, %d Ladeaufträge übersprungen", name,
				e.textures().regionTextureCount(), e.textures().uploads(), e.disk() == null ? 0 : e.disk().skippedLoads()));
	}

	private static WorldMapUi ui(Minecraft mc) {
		return (WorldMapUi) ((TrsUiScreen) mc.currentScreen).ui();
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

	/**
	 * Schreibt eine Beispiel-Höhlenschicht des Nethers ({@code dim-1}, Band 7 = Y 56–63) rund um (nx, nz): Netherrack
	 * mit Lavaseen, wie sie die Karte selbst speichern würde.
	 */
	private static int fakeNether(MapEngine e, int nx, int nz) {
		MapDisk disk = e.disk();
		if (disk == null || e.worldKey().isEmpty()) return 0;
		String dim = "dim-1";
		disk.rememberDimension(e.worldKey(), dim);
		Path dir = disk.layerDir(e.worldKey(), dim, "cave7");
		int written = 0;
		int crx = nx >> MapRegion.SHIFT, crz = nz >> MapRegion.SHIFT;
		for (int rz = crz - 1; rz <= crz + 1; rz++) {
			for (int rx = crx - 1; rx <= crx + 1; rx++) {
				MapRegion r = new MapRegion(rx, rz);
				boolean any = false;
				for (int z = 0; z < MapRegion.SIZE; z++) {
					for (int x = 0; x < MapRegion.SIZE; x++) {
						int wx = rx * MapRegion.SIZE + x, wz = rz * MapRegion.SIZE + z;
						double d = Math.hypot(wx - nx, wz - nz);
						if (d > 150) continue;
						double lava = Math.sin(wx * 0.07) * Math.cos(wz * 0.09);
						int rgb = lava > 0.6 ? 0xCF5A12 : ((wx * 31 + wz * 17) % 7 == 0 ? 0x5E2A28 : 0x70302C);
						r.pixels[z * MapRegion.SIZE + x] = MapColors.KNOWN | rgb;
						r.heights[z * MapRegion.SIZE + x] = (short) (lava > 0.6 ? 31 : 58 + (int) (Math.sin(wx * 0.2) * 2));
						any = true;
					}
				}
				if (!any) continue;
				int[] composed = new int[MapRegion.AREA];
				MapCompose.compose(null, r, composed);
				disk.save(MapDisk.regionFile(dir, rx, rz), rx, rz, r.pixels.clone(), r.heights.clone(), MapCompose.summaryOf(composed));
				written++;
			}
		}
		return written;
	}

	/** Nächster Schritt; liefert die Wartezeit in Ticks oder -1, wenn fertig. */
	int step(Minecraft mc, int bx, int by, int bz) {
		MapEngine e = MapEngine.get();
		switch (phase) {
			case 0:
				log("Beispiel-Nether gespeichert: " + fakeNether(e, Math.floorDiv(bx, 8), Math.floorDiv(bz, 8)) + " Bereiche");
				phase++;
				return 20;
			case 1:
				mc.displayGuiScreen(WorldMapScreen.create());
				phase++;
				return 40;
			case 2:
				ui(mc).testSidebar(true, "");
				phase++;
				return 10;
			case 3:
				shot(mc, "worldmap2-list");
				log("Wegpunkt-Liste: " + ui(mc).testListRows() + " Einträge");
				ui(mc).testSidebar(true, "bas");
				phase++;
				return 10;
			case 4:
				shot(mc, "worldmap2-search");
				log("Suche „bas“: " + ui(mc).testListRows() + " Treffer (erwartet 1)");
				ui(mc).testSidebar(false, "");
				sx = Mc.scaledResolution().getScaledWidth() / 2 + 80;
				sy = Mc.scaledResolution().getScaledHeight() / 2 - 40;
				anchor = ui(mc).testWorldAt(sx, sy);
				before = ui(mc).testScreenPx();
				ui(mc).testScroll(3, sx, sy);
				phase++;
				return 30;
			case 5: {
				double[] after = ui(mc).testWorldAt(sx, sy);
				double moved = Math.hypot(after[0] - anchor[0], after[1] - anchor[1]);
				log(String.format(Locale.ROOT, "stufenlos zoomen: %.2f → %.2f px/Block, Punkt unter der Maus um %.3f Blöcke verschoben (erwartet ≈ 0)",
						before, ui(mc).testScreenPx(), moved));
				shot(mc, "worldmap2-zoom");
				ui(mc).setScreenPxNow(0.1f);
				phase++;
				return 60;
			}
			case 6: {
				shot(mc, "worldmap2-far");
				log("weit (0.1 px/Block): Kachelstufe " + ui(mc).testTileLevel() + " (erwartet 2)");
				List<String> dims = ui(mc).testDimensions();
				log("Dimensionen: " + dims);
				String nether = null;
				for (String d : dims) if (MapDimensions.kind(d) == MapDimensions.NETHER) nether = d;
				if (nether == null) {
					log("FEHLER: Nether nicht in der Liste");
					phase = 8;
					return 5;
				}
				log("Nether ansehen: " + ui(mc).viewDimension(nether));
				ui(mc).setScreenPxNow(2f);
				phase++;
				return 80;
			}
			case 7:
				shot(mc, "worldmap2-nether");
				log("Ansicht jetzt: " + ui(mc).viewDimension() + " (andere Dimension: " + ui(mc).viewingOther() + ")");
				ui(mc).viewDimension(null);
				ui(mc).setScreenPxNow(2f);
				phase++;
				return 30;
			case 8:
				log("Export Ausschnitt gestartet: " + ui(mc).startExport(true));
				polls = 0;
				phase++;
				return 10;
			case 9:
				if (MapExport.running() && polls++ < 400) return 5;
				// Die Meldung kommt über den nächsten Karten-Tick an: kurz warten.
				if (!settled) {
					settled = true;
					return 5;
				}
				logExport("Ausschnitt");
				log("Hinweis über der Leiste: " + java.util.Arrays.toString(ui(mc).testToast()));
				shot(mc, "worldmap2-export");
				log("Export ganz gestartet: " + ui(mc).startExport(false));
				polls = 0;
				phase++;
				return 10;
			case 10:
				if (MapExport.running() && polls++ < 1200) return 5;
				logExport("ganz");
				mc.displayGuiScreen(null);
				phase++;
				return 5;
			default:
				return -1;
		}
	}
}
