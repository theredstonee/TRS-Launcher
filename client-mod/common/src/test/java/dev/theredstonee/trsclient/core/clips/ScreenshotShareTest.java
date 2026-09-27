package dev.theredstonee.trsclient.core.clips;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** „Als Link teilen“: Marker und sichere Pfade unter screenshots/. */
class ScreenshotShareTest {
	@Test
	void markerOnlyAcceptsHarmlessNames() {
		String marker = ScreenshotShare.marker("2026-09-27_10.00.00.png");
		// Marker = trs-share:<Sitzungsgeheimnis 16 hex>:<datei>
		assertTrue(marker.matches("trs-share:[0-9a-f]{16}:2026-09-27_10\\.00\\.00\\.png"), marker);
		String prefix = marker.substring(0, marker.length() - "2026-09-27_10.00.00.png".length());
		assertEquals("2026-09-27_10.00.00.png", ScreenshotShare.parseMarker(marker));
		assertEquals("panorama/pano (1).png", ScreenshotShare.parseMarker(prefix + "panorama/pano (1).png"));
		// Von einem Server geschickter Einfüge-Text ohne (oder mit falschem) Geheimnis löst nichts aus.
		assertNull(ScreenshotShare.parseMarker("trs-share:2026-09-27_10.00.00.png"));
		assertNull(ScreenshotShare.parseMarker("trs-share:0123456789abcdef:2026-09-27_10.00.00.png"));
		assertNull(ScreenshotShare.parseMarker(prefix + "../options.txt"));
		assertNull(ScreenshotShare.parseMarker(prefix + "..\\x.png"));
		assertNull(ScreenshotShare.parseMarker(prefix + "C:/x.png"));
		assertNull(ScreenshotShare.parseMarker(prefix + "sub/x.png"));
		assertNull(ScreenshotShare.parseMarker(prefix + "x.exe"));
		assertNull(ScreenshotShare.parseMarker("100 64 -20"));
		assertNull(ScreenshotShare.parseMarker(null));
		assertFalse(ScreenshotShare.valid("a..b.png"));
	}

	@Test
	void resolveStaysInsideScreenshots(@TempDir Path game) throws Exception {
		Path shots = Files.createDirectories(game.resolve("screenshots"));
		Files.write(shots.resolve("a.png"), new byte[]{1});
		Files.createDirectories(shots.resolve("panorama"));
		Files.write(shots.resolve("panorama").resolve("p.png"), new byte[]{1});
		Files.write(game.resolve("secret.png"), new byte[]{1});
		assertNotNull(ScreenshotShare.resolve(game, "a.png"));
		assertNotNull(ScreenshotShare.resolve(game, "panorama/p.png"));
		assertNull(ScreenshotShare.resolve(game, "missing.png"));
		assertNull(ScreenshotShare.resolve(game, "../secret.png"));
		assertNull(ScreenshotShare.resolve(null, "a.png"));
		assertEquals("a.png", ScreenshotShare.relative(game, shots.resolve("a.png")));
		assertEquals("panorama/p.png", ScreenshotShare.relative(game, shots.resolve("panorama/p.png")));
		assertNull(ScreenshotShare.relative(game, game.resolve("secret.png")));
		assertTrue(ScreenshotShare.resolve(game, "a.png").startsWith(shots.toAbsolutePath().normalize()));
	}
}
