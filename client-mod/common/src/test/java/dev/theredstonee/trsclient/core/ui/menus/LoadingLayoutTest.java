package dev.theredstonee.trsclient.core.ui.menus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lage des Ladebildschirm-Inhalts gegenüber den Knöpfen (Vanilla „Abbrechen“ bei h/4+132, 20 hoch). */
class LoadingLayoutTest {
	private static int cancelTop(int guiHeight) {
		return guiHeight / 4 + 120 + 12;
	}

	/** Panel (Inhalt ± 10) endet über dem Knopf und beginnt im Bild. */
	private static void assertAboveButton(int guiHeight) {
		int top = cancelTop(guiHeight);
		int[] l = MenuSkin.loadingLayout(guiHeight, top, top + 20);
		int panelBottom = l[0] + MenuSkin.loadingBlockHeight(l[1]) + 10;
		assertTrue(panelBottom <= top - 4, "Höhe " + guiHeight + ": Panel bis " + panelBottom + ", Knopf ab " + top);
		assertTrue(l[0] - 10 >= 0, "Höhe " + guiHeight + ": Panel ragt oben hinaus");
		assertTrue(l[1] >= 3, "Höhe " + guiHeight + ": Logo zu klein");
	}

	@Test
	void contentStaysAboveTheCancelButtonAtEveryWindowSize() {
		// 854×480 GUI 2 (240), 1920×1080 GUI auto (270), 1080p GUI 3 (360), 1440p GUI 2 (720 – der gemeldete Fall), 4K GUI 2
		for (int h : new int[]{240, 250, 270, 300, 360, 480, 540, 720, 1080}) assertAboveButton(h);
	}

	@Test
	void withoutButtonsTheContentIsCentered() {
		int[] l = MenuSkin.loadingLayout(720, -1, -1);
		assertEquals(4, l[1]);
		assertEquals((720 - MenuSkin.loadingBlockHeight(4)) / 2 - 10, l[0]);
		assertEquals(3, MenuSkin.loadingLayout(240, -1, -1)[1]);
	}

	@Test
	void smallWindowsKeepTheCenteredPositionWhenItFits() {
		// 240 hoch: Knopf bei 192 – mittig passt, also unverändert mittig.
		int[] l = MenuSkin.loadingLayout(240, cancelTop(240), cancelTop(240) + 20);
		assertEquals(MenuSkin.loadingLayout(240, -1, -1)[0], l[0]);
	}

	@Test
	void buttonsHighUpPushTheContentBelowThem() {
		int[] l = MenuSkin.loadingLayout(400, 20, 44);
		assertTrue(l[0] - 10 >= 44, "Inhalt unter den Knöpfen");
	}
}
