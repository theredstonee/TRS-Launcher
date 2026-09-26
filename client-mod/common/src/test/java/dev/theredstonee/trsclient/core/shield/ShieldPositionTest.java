package dev.theredstonee.trsclient.core.shield;

import dev.theredstonee.trsclient.core.module.NewSince;
import dev.theredstonee.trsclient.core.module.NumberSetting;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vorlagen, Überblendung Normal ↔ Blocken, Grenzwerte und Vorschau der Schild-Position. */
class ShieldPositionTest {
	private static final double EPS = 1e-5;

	// --- Modul und Vorlagen ---

	@Test
	void moduleIsOffByDefaultAndMarkedNew() {
		TrsModules m = new TrsModules();
		assertFalse(m.shieldPosition.isEnabled(), "Schild-Position ist ab Werk aus");
		assertFalse(m.shieldTransparent.get(), "Durchsichtig beim Blocken ist ab Werk aus");
		assertSame(ShieldPreset.SIDE, m.shieldPreset.get(), "Standard-Vorlage beim Einschalten: Seitlich");
		assertEquals(NewSince.SHIELD, NewSince.of("shieldPosition"));
		assertTrue(NewSince.compare(NewSince.SHIELD, "0.8.0") > 0, "NEU gilt für die Version nach 0.8.0");
		assertNotNull(ModulePanel.Registry.of(m.shieldPosition), "Live-Vorschau hängt an der Modulseite");
		assertTrue(ModulePanel.Registry.of(m.shieldPosition).pinned());
	}

	@Test
	void slidersDefaultToThePresetSide() {
		TrsModules m = new TrsModules();
		assertTrue(m.shield.normalPose().near(ShieldPreset.SIDE.normal()));
		assertTrue(m.shield.blockingPose().near(ShieldPreset.SIDE.blocking()));
	}

	@Test
	void presetValuesFitTheSlidersExactly() {
		TrsModules m = new TrsModules();
		for (ShieldPreset p : ShieldPreset.values()) {
			if (!p.fixed()) continue;
			check(m.shieldNormal, p.normal().toSettings(), p + " normal");
			check(m.shieldBlocking, p.blocking().toSettings(), p + " blocken");
		}
	}

	private static void check(NumberSetting[] sliders, double[] values, String what) {
		for (int i = 0; i < sliders.length; i++) {
			NumberSetting s = sliders[i];
			double v = values[i];
			assertTrue(v >= s.min() - EPS && v <= s.max() + EPS, what + " Regler " + s.key() + " außerhalb: " + v);
			double steps = (v - s.min()) / s.step();
			assertEquals(Math.round(steps), steps, 1e-6, what + " Regler " + s.key() + " nicht auf Raster: " + v);
		}
	}

	@Test
	void vanillaPresetIsNeutral() {
		assertTrue(ShieldPreset.VANILLA.normal().near(ShieldPose.NEUTRAL));
		assertTrue(ShieldPreset.VANILLA.blocking().near(ShieldPose.NEUTRAL));
		ShieldTransform t = ShieldPosition.compute(ShieldPose.NEUTRAL, ShieldPose.NEUTRAL, false, 0, new ShieldTransform());
		assertTrue(t.isIdentity(), "Vanilla ohne Blocken ändert nichts: " + t);
	}

	// --- Überblendung ---

	@Test
	void neutralBlockingReproducesVanillasBlockingModel() {
		for (boolean left : new boolean[]{false, true}) {
			ShieldTransform t = ShieldPosition.compute(ShieldPose.NEUTRAL, ShieldPose.NEUTRAL, left, 1, new ShieldTransform());
			double[] applied = ShieldMath.mul(matrix(t), ShieldPosition.vanillaDisplay(left, false));
			assertMatrix(ShieldPosition.vanillaDisplay(left, true), applied, "Vanilla-Blockmodell " + (left ? "links" : "rechts"));
		}
	}

	@Test
	void blendEndpointsAreExact() {
		ShieldPose n = ShieldPreset.SIDE.normal(), b = ShieldPreset.SIDE.blocking();
		for (boolean left : new boolean[]{false, true}) {
			assertMatrix(ShieldMath.pose(n, left), matrix(ShieldPosition.compute(n, b, left, 0, new ShieldTransform())), "Anfang");
			assertMatrix(ShieldPosition.offset(b, left, true), matrix(ShieldPosition.compute(n, b, left, 1, new ShieldTransform())), "Ende");
		}
	}

	@Test
	void blendIsContinuousWithoutJumps() {
		ShieldPose n = ShieldPreset.LOW.normal(), b = ShieldPreset.SIDE.blocking();
		ShieldTransform prev = ShieldPosition.compute(n, b, true, 0, new ShieldTransform());
		double maxStep = 0;
		for (int i = 1; i <= 100; i++) {
			ShieldTransform cur = ShieldPosition.compute(n, b, true, i / 100.0, new ShieldTransform());
			double d = Math.abs(cur.x - prev.x) + Math.abs(cur.y - prev.y) + Math.abs(cur.z - prev.z) + Math.abs(cur.scale - prev.scale)
					+ Math.abs(cur.qx - prev.qx) + Math.abs(cur.qy - prev.qy) + Math.abs(cur.qz - prev.qz) + Math.abs(cur.qw - prev.qw);
			maxStep = Math.max(maxStep, d);
			double qn = cur.qx * cur.qx + cur.qy * cur.qy + cur.qz * cur.qz + cur.qw * cur.qw;
			assertEquals(1, qn, 1e-4, "Quaternion bleibt normiert");
			prev = cur;
		}
		assertTrue(maxStep < 0.05, "kein Sprung beim Überblenden (größter Schritt " + maxStep + ")");
	}

	@Test
	void leftHandIsTheMirrorImageOfTheRightHand() {
		ShieldPose p = new ShieldPose(0.3, -0.2, 0.1, 10, 25, -15, 0.8);
		double[] right = ShieldMath.pose(p, false), left = ShieldMath.pose(p, true);
		double[] mirror = ShieldMath.scale(-1, 1, 1);
		assertMatrix(ShieldMath.mul(mirror, right, mirror), left, "Spiegelung");
	}

	@Test
	void extremeValuesStayFinite() {
		ShieldPose a = new ShieldPose(ShieldPosition.POS_MIN, ShieldPosition.POS_MAX, ShieldPosition.POS_MIN,
				ShieldPosition.ROT_MIN, ShieldPosition.ROT_MAX, ShieldPosition.ROT_MIN, ShieldPosition.SCALE_MIN / 100);
		ShieldPose b = new ShieldPose(ShieldPosition.POS_MAX, ShieldPosition.POS_MIN, ShieldPosition.POS_MAX,
				ShieldPosition.ROT_MAX, ShieldPosition.ROT_MIN, ShieldPosition.ROT_MAX, ShieldPosition.SCALE_MAX / 100);
		for (double f = 0; f <= 1.0001; f += 0.125) {
			ShieldTransform t = ShieldPosition.compute(a, b, false, f, new ShieldTransform());
			for (float v : new float[]{t.x, t.y, t.z, t.qx, t.qy, t.qz, t.qw, t.scale}) assertTrue(Float.isFinite(v), "endlich bei " + f + ": " + t);
		}
		assertEquals(0.3, ShieldPosition.compute(a, a, false, 0, new ShieldTransform()).scale, 1e-6);
		assertEquals(1.5, ShieldPosition.compute(b, b, false, 0, new ShieldTransform()).scale, 1e-6);
	}

	@Test
	void slidersClampAtTheirLimits() {
		TrsModules m = new TrsModules();
		m.shieldNormal[0].set(5);
		m.shieldNormal[3].set(-999);
		m.shieldNormal[6].set(1);
		m.shieldOpacity.set(100);
		assertEquals(ShieldPosition.POS_MAX, m.shieldNormal[0].get(), 0);
		assertEquals(ShieldPosition.ROT_MIN, m.shieldNormal[3].get(), 0);
		assertEquals(ShieldPosition.SCALE_MIN, m.shieldNormal[6].get(), 0);
		assertEquals(ShieldPosition.OPACITY_MAX, m.shieldOpacity.get(), 0);
	}

	@Test
	void decomposeComposeRoundTrip() {
		double[] m = ShieldMath.pose(new ShieldPose(0.1, -0.4, 0.25, 33, -120, 170, 1.3), true);
		ShieldMath.Decomposed d = ShieldMath.decompose(m);
		assertMatrix(m, ShieldMath.compose(d.tx, d.ty, d.tz, d.qx, d.qy, d.qz, d.qw, d.scale), "Rundreise");
	}

	@Test
	void axisAngleMatchesQuaternion() {
		ShieldTransform t = new ShieldTransform();
		ShieldMath.Decomposed d = ShieldMath.decompose(ShieldMath.rotY(90));
		t.set(0, 0, 0, d.qx, d.qy, d.qz, d.qw, 1);
		assertEquals(90, t.angleDegrees(), 1e-3);
		assertEquals(1, t.axisY(), 1e-5);
		assertEquals(0, new ShieldTransform().identity().angleDegrees(), 1e-6);
	}

	// --- Animation ---

	@Test
	void animatorRaisesAndLowersSmoothly() {
		ShieldAnimator a = new ShieldAnimator();
		long ms = 1_000_000L, now = 1000 * ms;
		assertEquals(0, a.update(false, now), 0);
		float prev = 0;
		int frames = 0;
		while (a.value() < 1f && frames < 200) {
			now += 5 * ms;
			float v = a.update(true, now);
			assertTrue(v >= prev, "steigt monoton");
			assertTrue(v - prev < 0.12f, "kein Sprung (" + (v - prev) + ")");
			prev = v;
			frames++;
		}
		assertEquals(ShieldAnimator.RAISE_MS / 5, frames, 1, "Hochnehmen dauert " + ShieldAnimator.RAISE_MS + " ms");
		now += 80 * ms;
		float mid = a.update(false, now);
		assertTrue(mid > 0.2f && mid < 0.8f, "halb abgesenkt: " + mid);
		now += 200 * ms;
		assertEquals(0, a.update(false, now), 0);
	}

	@Test
	void animatorSnapsAfterLongPauses() {
		ShieldAnimator a = new ShieldAnimator();
		long ms = 1_000_000L;
		a.update(false, 0);
		assertEquals(1, a.update(true, 2000 * ms), 0, "nach einer Pause (Menü) nicht nachholen");
		assertEquals(0.5, ShieldAnimator.smooth(0.5), 1e-9);
		assertEquals(0, ShieldAnimator.smooth(-1), 0);
		assertEquals(1, ShieldAnimator.smooth(2), 0);
	}

	// --- Vorlagen ↔ Regler ---

	@Test
	void choosingAPresetWritesItsValues() {
		TrsModules m = new TrsModules();
		m.shield.sync();
		m.shieldPreset.set(ShieldPreset.LOW);
		m.shield.sync();
		assertTrue(m.shield.normalPose().near(ShieldPreset.LOW.normal()));
		assertTrue(m.shield.blockingPose().near(ShieldPreset.LOW.blocking()));
		m.shieldPreset.set(ShieldPreset.VANILLA);
		m.shield.sync();
		assertTrue(m.shield.normalPose().near(ShieldPose.NEUTRAL));
		assertSame(ShieldPreset.VANILLA, m.shieldPreset.get());
	}

	@Test
	void movingASliderSwitchesToCustomAndCustomComesBack() {
		TrsModules m = new TrsModules();
		m.shield.sync();
		m.shieldBlocking[1].set(-0.5);
		m.shield.sync();
		assertSame(ShieldPreset.CUSTOM, m.shieldPreset.get(), "eigener Regler → Eigene");
		m.shieldPreset.set(ShieldPreset.SIDE);
		m.shield.sync();
		assertEquals(ShieldPreset.SIDE.blocking().y, m.shieldBlocking[1].get(), EPS);
		m.shieldPreset.set(ShieldPreset.CUSTOM);
		m.shield.sync();
		assertEquals(-0.5, m.shieldBlocking[1].get(), EPS, "Eigene bekommt ihre Werte zurück");
	}

	@Test
	void savedPresetWinsOnFirstSync() {
		TrsModules m = new TrsModules();
		m.shieldPreset.set(ShieldPreset.LOW);
		m.shieldNormal[0].set(0.9);
		m.shield.sync();
		assertSame(ShieldPreset.LOW, m.shieldPreset.get());
		assertTrue(m.shield.normalPose().near(ShieldPreset.LOW.normal()));
		// „Eigene“ behält gespeicherte Werte
		TrsModules c = new TrsModules();
		c.shieldPreset.set(ShieldPreset.CUSTOM);
		c.shieldNormal[0].set(0.9);
		c.shield.sync();
		assertEquals(0.9, c.shieldNormal[0].get(), EPS);
	}

	@Test
	void resetGoesBackToSideAndOff() {
		TrsModules m = new TrsModules();
		m.shieldPosition.setEnabled(true);
		m.shieldPreset.set(ShieldPreset.CUSTOM);
		m.shieldNormal[2].set(0.7);
		m.shieldTransparent.set(true);
		m.shieldPosition.reset();
		m.shield.sync();
		assertFalse(m.shieldPosition.isEnabled());
		assertFalse(m.shieldTransparent.get());
		assertSame(ShieldPreset.SIDE, m.shieldPreset.get());
		assertTrue(m.shield.normalPose().near(ShieldPreset.SIDE.normal()));
	}

	// --- Deckkraft ---

	@Test
	void opacityOnlyWhenWantedAndOnlyWhileBlocking() {
		TrsModules m = new TrsModules();
		ShieldPosition s = m.shield;
		long ms = 1_000_000L;
		m.shieldPosition.setEnabled(true);
		s.transform(ShieldPosition.OFF_HAND, true, true, 0, new ShieldTransform());
		assertEquals(1f, s.alpha(ShieldPosition.OFF_HAND), 0, "ohne Schalter immer deckend");
		m.shieldTransparent.set(true);
		m.shieldOpacity.set(40);
		assertEquals(0.4f, s.alpha(ShieldPosition.OFF_HAND), 1e-4, "voll geblockt = eingestellte Deckkraft");
		assertEquals(1f, s.alpha(ShieldPosition.MAIN_HAND), 0, "andere Hand blockt nicht");
		s.transform(ShieldPosition.OFF_HAND, true, false, 80 * ms, new ShieldTransform());
		float half = s.alpha(ShieldPosition.OFF_HAND);
		assertTrue(half > 0.4f && half < 1f, "beim Absenken weich zurück: " + half);
		m.shieldPosition.setEnabled(false);
		assertEquals(1f, s.alpha(ShieldPosition.OFF_HAND), 0, "Modul aus → deckend");
	}

	// --- Vorschau ---

	@Test
	void previewCoverageShowsTheBenefit() {
		double aspect = 16 / 9.0;
		double vanillaNormal = ShieldPreview.coverage(ShieldPreview.silhouette(ShieldMath.identity(), true, aspect));
		double vanillaBlock = ShieldPreview.coverage(ShieldPreview.silhouette(ShieldPosition.vanillaDelta(true), true, aspect));
		double sideNormal = ShieldPreview.coverage(ShieldPreview.silhouette(ShieldPosition.offset(ShieldPreset.SIDE.normal(), true, false), true, aspect));
		double sideBlock = ShieldPreview.coverage(ShieldPreview.silhouette(ShieldPosition.offset(ShieldPreset.SIDE.blocking(), true, true), true, aspect));
		double lowBlock = ShieldPreview.coverage(ShieldPreview.silhouette(ShieldPosition.offset(ShieldPreset.LOW.blocking(), true, true), true, aspect));
		System.out.printf("Verdeckt: Vanilla %.1f %% / blockend %.1f %%, Seitlich %.1f %% / %.1f %%, Tief blockend %.1f %%%n",
				vanillaNormal * 100, vanillaBlock * 100, sideNormal * 100, sideBlock * 100, lowBlock * 100);
		assertTrue(vanillaNormal > 0.02 && vanillaNormal < 0.5, "Vanilla-Schild sichtbar: " + vanillaNormal);
		assertTrue(vanillaBlock > vanillaNormal, "Vanilla-Blocken verdeckt mehr");
		assertTrue(sideNormal < vanillaNormal, "Seitlich verdeckt weniger als Vanilla");
		assertTrue(sideBlock < vanillaBlock * 0.8, "Seitliches Blocken verdeckt deutlich weniger");
		assertTrue(lowBlock < vanillaBlock * 0.8, "Tiefes Blocken verdeckt deutlich weniger");
	}

	@Test
	void polygonHelpers() {
		double[][] full = {{-2, -2}, {2, -2}, {2, 2}, {-2, 2}};
		assertEquals(1, ShieldPreview.coverage(full), 1e-9, "größer als der Bildschirm → 100 %");
		double[][] quarter = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
		assertEquals(0.25, ShieldPreview.coverage(quarter), 1e-9);
		double[][] outside = {{2, 2}, {3, 2}, {3, 3}};
		assertEquals(0, ShieldPreview.coverage(outside), 1e-9);
	}

	// --- Hilfen ---

	private static double[] matrix(ShieldTransform t) {
		return ShieldMath.compose(t.x, t.y, t.z, t.qx, t.qy, t.qz, t.qw, t.scale);
	}

	private static void assertMatrix(double[] expected, double[] actual, String what) {
		for (int i = 0; i < 16; i++) assertEquals(expected[i], actual[i], 1e-5, what + " [" + i + "]");
	}
}
