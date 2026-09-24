package dev.theredstonee.trsclient.core.input;

import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zustandsautomat von Toggle-Sprint/-Schleichen und Flug-Boost. */
class MovementTogglesTest {
	private TrsModules modules;
	private MovementToggles toggles;
	private MovementToggles.Input in;

	@BeforeEach
	void setUp() {
		modules = new TrsModules();
		modules.toggleSprint.setEnabled(true);
		modules.toggleSneak.setEnabled(true);
		toggles = new MovementToggles(modules);
		in = toggles.input();
		inWorld(1);
		toggles.tick();
	}

	private void inWorld(int context) {
		in.clear();
		in.hasPlayer = true;
		in.inGame = true;
		in.context = context;
		in.forwardDown = true;
	}

	/** Ein Tick; die Vanilla-Taste folgt danach unserer Aktion (wie im Spiel). */
	private void tick() {
		toggles.tick();
		if (toggles.sprintAction() == MovementToggles.PRESS) in.sprintKeyDown = true;
		if (toggles.sprintAction() == MovementToggles.RELEASE) in.sprintKeyDown = false;
		if (toggles.sneakAction() == MovementToggles.PRESS) in.sneakKeyDown = true;
		if (toggles.sneakAction() == MovementToggles.RELEASE) in.sneakKeyDown = false;
		in.sprintPresses = 0;
		in.sneakPresses = 0;
	}

	@Test
	void pressTogglesAndHoldsTheKeyUntilPressedAgain() {
		in.sprintPresses = 1;
		tick();
		assertTrue(toggles.sprint().active());
		assertEquals(MovementToggles.PRESS, toggles.sprintAction());
		assertEquals(ToggleStatus.SPRINT_TOGGLED, toggles.sprintStatus());
		tick();
		assertEquals(MovementToggles.PRESS, toggles.sprintAction(), "bleibt gedrückt");
		in.sprintPresses = 1;
		tick();
		assertFalse(toggles.sprint().active());
		assertEquals(MovementToggles.RELEASE, toggles.sprintAction(), "einmal loslassen");
		tick();
		assertEquals(MovementToggles.KEEP, toggles.sprintAction(), "danach nicht mehr anfassen");
		assertEquals(ToggleStatus.NONE, toggles.sprintStatus());
	}

	@Test
	void doublePressInOneTickCancelsOut() {
		in.sneakPresses = 2;
		tick();
		assertFalse(toggles.sneak().active());
		in.sneakPresses = 3;
		tick();
		assertTrue(toggles.sneak().active());
	}

	@Test
	void guiDoesNotStickTheKeyAndResumesAfterwards() {
		in.sprintPresses = 1;
		tick();
		// Menü auf: Minecraft hat die Tasten losgelassen, wir drücken nichts, Drücke zählen nicht.
		in.inGame = false;
		in.sprintKeyDown = false;
		in.sprintPresses = 1;
		tick();
		assertEquals(MovementToggles.KEEP, toggles.sprintAction());
		assertTrue(toggles.sprint().active(), "Druck im Menü schaltet nicht um");
		assertFalse(in.sprintKeyDown);
		// Menü zu: wieder gedrückt.
		in.inGame = true;
		tick();
		assertEquals(MovementToggles.PRESS, toggles.sprintAction());
	}

	@Test
	void sprintOnlyForward() {
		in.sprintPresses = 1;
		tick();
		in.forwardDown = false;
		tick();
		assertEquals(MovementToggles.RELEASE, toggles.sprintAction());
		assertTrue(toggles.sprint().active(), "bleibt umgeschaltet");
		assertEquals(ToggleStatus.SPRINT_TOGGLED, toggles.sprintStatus());
		in.forwardDown = true;
		tick();
		assertEquals(MovementToggles.PRESS, toggles.sprintAction());
		modules.toggleSprintOnlyForward.set(false);
		in.forwardDown = false;
		tick();
		assertEquals(MovementToggles.PRESS, toggles.sprintAction());
	}

	@Test
	void deathAndWorldChangeResetUnlessRemembered() {
		modules.toggleSprintRemember.set(true);
		modules.toggleSneakRemember.set(false);
		in.sprintPresses = 1;
		in.sneakPresses = 1;
		tick();
		assertTrue(toggles.sprint().active() && toggles.sneak().active());
		// Tod: Todesbildschirm, Spielfigur tot.
		in.dead = true;
		in.inGame = false;
		tick();
		assertTrue(toggles.sprint().active(), "Sprint gemerkt");
		assertFalse(toggles.sneak().active(), "Schleichen zurückgesetzt");
		// Respawn: neue Spielfigur.
		inWorld(2);
		tick();
		assertTrue(toggles.sprint().active());
		assertEquals(MovementToggles.PRESS, toggles.sprintAction());

		modules.toggleSprintRemember.set(false);
		// Welt verlassen.
		in.clear();
		tick();
		assertFalse(toggles.sprint().active());
		assertEquals(ToggleStatus.NONE, toggles.sprintStatus());
	}

	@Test
	void dimensionChangeCountsAsNewContext() {
		modules.toggleSneakRemember.set(false);
		in.sneakPresses = 1;
		tick();
		assertTrue(toggles.sneak().active());
		inWorld(99);
		tick();
		assertFalse(toggles.sneak().active());
	}

	@Test
	void vanillaToggleOptionWins() {
		in.sprintPresses = 1;
		tick();
		in.vanillaToggleSprint = true;
		tick();
		assertFalse(toggles.sprint().active());
		assertEquals(MovementToggles.RELEASE, toggles.sprintAction());
	}

	@Test
	void disablingTheModuleReleasesTheKey() {
		in.sneakPresses = 1;
		tick();
		modules.toggleSneak.setEnabled(false);
		tick();
		assertEquals(MovementToggles.RELEASE, toggles.sneakAction());
		assertFalse(toggles.sneak().active());
	}

	@Test
	void heldKeyShowsHeldStatus() {
		in.sprintKeyDown = true;
		in.sprinting = true;
		tick();
		assertEquals(ToggleStatus.SPRINT_HELD, toggles.sprintStatus());
		in.sneakKeyDown = true;
		tick();
		assertEquals(ToggleStatus.SNEAK_HELD, toggles.sneakStatus());
		assertEquals(ToggleStatus.SPRINT_TOGGLED, toggles.status(true, true), "Vorschau im HUD-Editor");
	}

	@Test
	void flyBoostOnlyInCreativeFlightAndRestoresSpeed() {
		modules.toggleSprintFlyBoost.set(true);
		modules.toggleSprintFlyBoostFactor.set(3.0);
		in.sprintPresses = 1;
		tick();
		assertEquals(0.05F, toggles.flySpeed(0.05F), 1e-6, "nicht im Flug: kein Boost");
		in.creativeFlying = true;
		tick();
		float boosted = toggles.flySpeed(0.05F);
		assertEquals(0.15F, boosted, 1e-6);
		assertEquals(ToggleStatus.FLY_BOOST, toggles.sprintStatus());
		tick();
		assertEquals(boosted, toggles.flySpeed(boosted), 1e-6, "bleibt stabil");
		in.creativeFlying = false;
		tick();
		assertEquals(0.05F, toggles.flySpeed(boosted), 1e-6, "vorheriger Wert zurück");
		assertFalse(toggles.boosting());
	}

	@Test
	void flyBoostAdoptsServerChangedSpeed() {
		FlyBoost b = new FlyBoost();
		assertEquals(0.1F, b.apply(0.05F, true, 2.0), 1e-6);
		// Server setzt 0.08 (neues Fähigkeiten-Paket): das ist der neue Grundwert.
		assertEquals(0.16F, b.apply(0.08F, true, 2.0), 1e-6);
		assertEquals(0.08F, b.apply(0.16F, false, 2.0), 1e-6);
		// Hat der Server beim Beenden schon etwas anderes gesetzt, bleibt das.
		b.apply(0.05F, true, 2.0);
		assertEquals(0.07F, b.apply(0.07F, false, 2.0), 1e-6);
		assertEquals(0.05F, b.apply(0.05F, true, 1.0), 1e-6, "Faktor 1 = kein Boost");
	}
}
