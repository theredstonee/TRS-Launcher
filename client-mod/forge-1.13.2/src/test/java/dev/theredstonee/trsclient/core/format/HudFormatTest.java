package dev.theredstonee.trsclient.core.format;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudFormatTest {

	@Test
	void durationFromTicks() {
		assertEquals("0:00", HudFormat.duration(0, false));
		assertEquals("0:45", HudFormat.duration(45 * 20 + 19, false));
		assertEquals("12:03", HudFormat.duration((12 * 60 + 3) * 20, false));
		assertEquals("1:02:03", HudFormat.duration((3600 + 2 * 60 + 3) * 20, false));
		assertEquals("∞", HudFormat.duration(5, true));
		assertEquals("0:00", HudFormat.duration(-40, false));
	}

	@Test
	void effectLevels() {
		assertEquals("", HudFormat.level(0));
		assertEquals("II", HudFormat.level(1));
		assertEquals("X", HudFormat.level(9));
		assertEquals("11", HudFormat.level(10));
		assertEquals("", HudFormat.level(-1));
	}

	@Test
	void memoryInMegabytes() {
		long mb = 1024 * 1024;
		assertEquals("512 / 2048 MB (25 %)", HudFormat.memory(512 * mb, 2048 * mb));
		assertEquals("0 / 1 MB (0 %)", HudFormat.memory(0, 0));
	}

	@Test
	void coordinatesAreFloored() {
		assertEquals("X 12  Y 64  Z -6", HudFormat.coords(12.9, 64.0, -5.2));
	}

	@Test
	void directionsFollowMinecraftYaw() {
		assertEquals("S", HudFormat.direction(0));
		assertEquals("W", HudFormat.direction(90));
		assertEquals("N", HudFormat.direction(180));
		assertEquals("N", HudFormat.direction(-180));
		assertEquals("O", HudFormat.direction(-90));
		assertEquals("O", HudFormat.direction(270));
		assertEquals("SW", HudFormat.direction(45));
		assertEquals("S", HudFormat.direction(22));
		assertEquals("SW", HudFormat.direction(23));
		assertEquals("S", HudFormat.direction(720 + 10));
		assertEquals("Nordosten", HudFormat.directionName(-135));
	}

	@Test
	void clockFormats() {
		LocalTime t = LocalTime.of(14, 5, 9);
		assertEquals("14:05", HudFormat.clock(t, false, false));
		assertEquals("14:05:09", HudFormat.clock(t, true, false));
		assertEquals("02:05 PM", HudFormat.clock(t, false, true));
		assertEquals("12:00 AM", HudFormat.clock(LocalTime.MIDNIGHT, false, true));
		assertEquals("12:00 PM", HudFormat.clock(LocalTime.NOON, false, true));
	}

	@Test
	void durabilityPercentAndColor() {
		assertEquals(100, HudFormat.durabilityPercent(0, 363));
		assertEquals(50, HudFormat.durabilityPercent(50, 100));
		assertEquals(0, HudFormat.durabilityPercent(200, 100));
		assertEquals(100, HudFormat.durabilityPercent(0, 0));
		assertEquals(0x00FF00, HudFormat.durabilityColor(100));
		assertEquals(0xFFFF00, HudFormat.durabilityColor(50));
		assertEquals(0xFF0000, HudFormat.durabilityColor(0));
	}
}
