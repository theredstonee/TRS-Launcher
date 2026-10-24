package dev.theredstonee.trsclient.core.map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entwickler-Werkzeug (kein normaler Test): rendert einen gespeicherten Kartenordner (Karte v3) mit der aktuellen
 * Schattierung als PNG – zum Kalibrieren von Farben/Relief ohne Spielstart. Läuft nur mit
 * {@code -Dtrsclient.test.mapRender=<Ebenenordner>} und {@code -Dtrsclient.test.mapOut=<datei.png>}, optional
 * {@code -Dtrsclient.test.mapCenter=x,z} und {@code -Dtrsclient.test.mapSize=<Blöcke>} (Standard 512).
 */
class MapRenderTool {
	@Test
	void render() throws Exception {
		String dir = System.getProperty("trsclient.test.mapRender");
		String outFile = System.getProperty("trsclient.test.mapOut");
		Assumptions.assumeTrue(dir != null && outFile != null, "nur als Werkzeug");
		MapPreview.setForTests(true);
		try {
			MapLayer layer = new MapLayer("render", 1, Integer.MIN_VALUE, null, null);
			try (DirectoryStream<Path> files = Files.newDirectoryStream(Paths.get(dir), "r.*.trsm")) {
				for (Path f : files) {
					RegionCodec.Decoded d;
					try (InputStream in = Files.newInputStream(f)) {
						d = RegionCodec.decode(in);
					}
					MapRegion r = layer.forWrite(d.rx, d.rz, 0);
					System.arraycopy(d.pixels, 0, r.pixels, 0, MapRegion.AREA);
					System.arraycopy(d.heights, 0, r.heights, 0, MapRegion.AREA);
					r.depth = d.depth;
					r.floor = d.floor;
				}
			}
			String[] c = System.getProperty("trsclient.test.mapCenter", "0,0").split(",");
			int cx = Integer.parseInt(c[0].trim()), cz = Integer.parseInt(c[1].trim());
			int size = Integer.getInteger("trsclient.test.mapSize", 512);
			int x0 = cx - size / 2, z0 = cz - size / 2;
			int[] img = new int[size * size];
			int[] buf = new int[MapRegion.AREA];
			for (MapRegion r : layer.loaded()) {
				int bx = r.rx * MapRegion.SIZE, bz = r.rz * MapRegion.SIZE;
				if (bx + MapRegion.SIZE <= x0 || bz + MapRegion.SIZE <= z0 || bx >= x0 + size || bz >= z0 + size) continue;
				MapShade.compose(layer, r, buf);
				for (int z = 0; z < MapRegion.SIZE; z++) {
					int iz = bz + z - z0;
					if (iz < 0 || iz >= size) continue;
					for (int x = 0; x < MapRegion.SIZE; x++) {
						int ix = bx + x - x0;
						if (ix < 0 || ix >= size) continue;
						int v = buf[z * MapRegion.SIZE + x];
						img[iz * size + ix] = (v >>> 24) == 0 ? 0xFF0B0A0E : v;
					}
				}
			}
			Path out = Paths.get(outFile);
			if (out.getParent() != null) Files.createDirectories(out.getParent());
			try (MapPng png = new MapPng(out, size, size)) {
				for (int y = 0; y < size; y++) png.writeRow(img, y * size);
				png.finish();
			}
		} finally {
			MapPreview.setForTests(null);
		}
	}
}
