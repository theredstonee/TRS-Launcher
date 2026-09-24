package dev.theredstonee.trsclient.core.input;

import dev.theredstonee.trsclient.core.module.TrsModules;

/**
 * Toggle-Sprint, Toggle-Schleichen und Flug-Boost – die komplette Logik, versionsunabhängig.
 * <p>
 * Der Loader füllt pro Client-Tick (vor der Spieler-Bewegung) ein {@link Input}, ruft
 * {@link #tick} auf und setzt danach die Vanilla-Tasten nach {@link #sprintAction()} /
 * {@link #sneakAction()} sowie die Fluggeschwindigkeit nach {@link #flySpeed(float)}.
 * <ul>
 *   <li>Jeder Druck der Sprint-/Schleich-Taste schaltet um; umgeschaltet gilt die Taste als gehalten.</li>
 *   <li>In Menüs zählen keine Drücke und es wird nichts gedrückt (Minecraft lässt beim Öffnen
 *       eines Menüs alle Tasten los) – nach dem Schließen geht es mit dem gemerkten Zustand weiter.</li>
 *   <li>Tod, Respawn, Dimensions- oder Weltwechsel setzen den Zustand zurück, außer „Zustand merken“ ist an.</li>
 *   <li>Vanillas eigene Umschalt-Option (ab 1.15) hat Vorrang: dann tut das Modul nichts.</li>
 * </ul>
 */
public final class MovementToggles {
	/** Vanilla-Taste unverändert lassen. */
	public static final int KEEP = 0;
	/** Vanilla-Taste als gedrückt setzen. */
	public static final int PRESS = 1;
	/** Vanilla-Taste loslassen (wir hatten sie gedrückt). */
	public static final int RELEASE = 2;

	/** Eingaben eines Ticks – vom Loader gefüllt und wiederverwendet. */
	public static final class Input {
		/** Neue Drücke der Sprint-/Schleich-Taste seit dem letzten Tick. */
		public int sprintPresses;
		public int sneakPresses;
		/** Gibt es eine Spielfigur? */
		public boolean hasPlayer;
		/** Spielfigur da und kein Menü offen. */
		public boolean inGame;
		/** Spielfigur tot (Lebenspunkte ≤ 0). */
		public boolean dead;
		/**
		 * Kennung von Spielfigur + Welt (z. B. identityHashCode beider). Minecraft erzeugt bei
		 * Respawn und Dimensionswechsel eine neue Spielfigur – dann ändert sich die Kennung.
		 */
		public int context;
		/** Vorwärts-Taste gedrückt. */
		public boolean forwardDown;
		/** Zustand der Vanilla-Tasten vor diesem Tick (inkl. unseres Drucks aus dem letzten Tick). */
		public boolean sprintKeyDown;
		public boolean sneakKeyDown;
		/** Vanillas eigene Umschalt-Optionen (ab 1.15). */
		public boolean vanillaToggleSprint;
		public boolean vanillaToggleSneak;
		/** Fliegt im Kreativmodus. */
		public boolean creativeFlying;
		/** Die Spielfigur sprintet gerade. */
		public boolean sprinting;

		/** Alles auf „keine Spielfigur“. */
		public Input clear() {
			sprintPresses = 0;
			sneakPresses = 0;
			hasPlayer = false;
			inGame = false;
			dead = false;
			context = 0;
			forwardDown = false;
			sprintKeyDown = false;
			sneakKeyDown = false;
			vanillaToggleSprint = false;
			vanillaToggleSneak = false;
			creativeFlying = false;
			sprinting = false;
			return this;
		}
	}

	private final TrsModules modules;
	private final ToggleState sprint = new ToggleState();
	private final ToggleState sneak = new ToggleState();
	private final FlyBoost boost = new FlyBoost();
	private final Input input = new Input();

	private boolean hadPlayer;
	private int lastContext;
	private boolean wasDead;
	/** Haben wir die Taste im letzten Tick gedrückt? */
	private boolean sprintForced;
	private boolean sneakForced;
	private int sprintAction = KEEP;
	private int sneakAction = KEEP;
	private boolean boostWanted;
	private ToggleStatus sprintStatus = ToggleStatus.NONE;
	private ToggleStatus sneakStatus = ToggleStatus.NONE;

	public MovementToggles(TrsModules modules) {
		this.modules = modules;
	}

	/** Das wiederverwendete Eingabe-Objekt (vom Loader zu füllen). */
	public Input input() {
		return input;
	}

	/** Ein Client-Tick mit den Eingaben aus {@link #input()}. */
	public void tick() {
		tick(input);
	}

	public void tick(Input in) {
		boolean changed = in.hasPlayer != hadPlayer
				|| (in.hasPlayer && in.context != lastContext)
				|| (in.dead && !wasDead);
		if (changed) {
			if (!modules.toggleSprintRemember.get()) sprint.reset();
			if (!modules.toggleSneakRemember.get()) sneak.reset();
			boost.reset();
		}
		hadPlayer = in.hasPlayer;
		lastContext = in.context;
		wasDead = in.dead;

		boolean sprintEnabled = modules.toggleSprint.isEnabled() && !in.vanillaToggleSprint;
		boolean sneakEnabled = modules.toggleSneak.isEnabled() && !in.vanillaToggleSneak;
		boolean blocked = !in.inGame || in.dead;
		sprint.update(in.sprintPresses, sprintEnabled, blocked);
		sneak.update(in.sneakPresses, sneakEnabled, blocked);

		boolean sprintWasForced = sprintForced;
		boolean sneakWasForced = sneakForced;
		boolean sprintWant = sprint.active() && in.inGame && !in.dead
				&& (!modules.toggleSprintOnlyForward.get() || in.forwardDown);
		boolean sneakWant = sneak.active() && in.inGame && !in.dead;
		sprintAction = action(sprintWant, sprintForced, in.inGame);
		sprintForced = sprintWant;
		sneakAction = action(sneakWant, sneakForced, in.inGame);
		sneakForced = sneakWant;

		boolean sprintHeldByPlayer = in.sprintKeyDown && !sprintWasForced;
		boostWanted = in.hasPlayer && in.creativeFlying && modules.toggleSprint.isEnabled()
				&& modules.toggleSprintFlyBoost.get() && (sprint.active() || sprintHeldByPlayer);

		if (!in.hasPlayer) {
			sprintStatus = ToggleStatus.NONE;
			sneakStatus = ToggleStatus.NONE;
			return;
		}
		if (boostWanted) sprintStatus = ToggleStatus.FLY_BOOST;
		else if (sprint.active()) sprintStatus = ToggleStatus.SPRINT_TOGGLED;
		else if (sprintHeldByPlayer && in.sprinting) sprintStatus = ToggleStatus.SPRINT_HELD;
		else sprintStatus = ToggleStatus.NONE;

		if (sneak.active()) sneakStatus = ToggleStatus.SNEAK_TOGGLED;
		else if (in.sneakKeyDown && !sneakWasForced && in.inGame) sneakStatus = ToggleStatus.SNEAK_HELD;
		else sneakStatus = ToggleStatus.NONE;
	}

	/**
	 * Drücken, solange gewünscht; einmal loslassen, wenn wir vorher gedrückt hatten. In Menüs
	 * hat Minecraft die Taste schon selbst losgelassen – dann nichts tun.
	 */
	private static int action(boolean want, boolean forced, boolean inGame) {
		if (want) return PRESS;
		if (forced && inGame) return RELEASE;
		return KEEP;
	}

	/**
	 * Fluggeschwindigkeit nach dem Tick (Flug-Boost im Kreativmodus).
	 * @param current aktuelle Fluggeschwindigkeit der Spielfigur
	 * @return neue Fluggeschwindigkeit (gleich {@code current} = nichts ändern)
	 */
	public float flySpeed(float current) {
		return boost.apply(current, boostWanted, modules.toggleSprintFlyBoostFactor.get());
	}

	public int sprintAction() {
		return sprintAction;
	}

	public int sneakAction() {
		return sneakAction;
	}

	public ToggleState sprint() {
		return sprint;
	}

	public ToggleState sneak() {
		return sneak;
	}

	public boolean boosting() {
		return boost.active();
	}

	public ToggleStatus sprintStatus() {
		return sprintStatus;
	}

	public ToggleStatus sneakStatus() {
		return sneakStatus;
	}

	/** Anzeige im HUD (Vorschau im Editor: Beispieltext). */
	public ToggleStatus status(boolean sprintIndicator, boolean preview) {
		if (preview) return sprintIndicator ? ToggleStatus.SPRINT_TOGGLED : ToggleStatus.SNEAK_TOGGLED;
		return sprintIndicator ? sprintStatus : sneakStatus;
	}
}
