package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import dev.theredstonee.trsclient.core.camera.ServerList;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.input.MovementToggles;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.pvp.ComboTracker;
import dev.theredstonee.trsclient.core.pvp.ReachTracker;
import dev.theredstonee.trsclient.core.pvp.SpeedTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Toggle-Sprint/-Schleichen und Freelook. Wird am Ende jedes Client-Ticks aufgerufen – nach der
 * Tastatur-Verarbeitung, damit der gesetzte Tastenzustand bis zur nächsten Spieler-Bewegung gilt.
 * (Die Treffer-Farbe der Fabric-Fassung fehlt hier: die Farbe ist in RendererLivingEntity fest
 * einprogrammiert und ohne Coremod/ASM nicht änderbar.)
 */
public final class PvpFeatures {
	private final TrsModules modules;
	/** Toggle-Sprint/-Schleichen + Flug-Boost (Logik in common). */
	private final MovementToggles toggles;
	private final FreelookState freelook = new FreelookState();
	/** Server, auf denen Freelook aus ist (zwischengespeichert). */
	private final ServerList freelookBlocked = new ServerList();
	/** Perspektive vor dem Freelook (0 = Ego), -1 = keine gemerkt. */
	private int viewBeforeFreelook = -1;
	/** Nur Selbsttest: Freelook ohne Tastendruck, mit Versatz zum Blick der Spielfigur. */
	private float forcedFreelookYaw = Float.NaN;
	private final ReachTracker reach = new ReachTracker();
	private final ComboTracker combo = new ComboTracker();
	private final SpeedTracker speed = new SpeedTracker();
	/** Zuletzt angegriffenes Ziel (für die Combo) und dessen Schadens-Zähler. */
	private Entity attacked;
	private int attackedHurtTime;
	private int selfHurtTime;
	/** Merkt, ob die Hitboxen von uns eingeschaltet wurden (damit F3+B nicht überschrieben bleibt). */
	private boolean hitboxesApplied;

	public PvpFeatures(TrsModules modules) {
		this.modules = modules;
		this.toggles = new MovementToggles(modules);
	}

	public void tick(Minecraft mc) {
		EntityPlayer player = Mc.player();
		boolean inGame = player != null && mc.currentScreen == null;
		tickToggles(mc, player, inGame);
		if (player == null) {
			reach.reset();
			combo.reset();
			speed.reset();
			attacked = null;
		}
		tickFreelook(mc, player, inGame);
		tickTrackers(player);
		tickHitboxes();
		tickBlockHit(mc, player, inGame);
	}

	// --- Reichweite, Combo, Geschwindigkeit (reine Anzeigen) ---

	/**
	 * Aus {@code AttackEntityEvent}: merkt sich Ziel und Entfernung des Schlages.
	 * Gemessen wird vom Auge zum tatsächlich getroffenen Punkt – die Reichweite selbst bleibt unverändert.
	 */
	public void onAttack(EntityPlayer player, Entity target) {
		if (player == null || target == null || player != Mc.player()) return;
		long now = System.currentTimeMillis();
		reach.record(Mc.hitDistance(player, target), now);
		combo.onAttack(target.getEntityId(), now);
		attacked = target;
		attackedHurtTime = target instanceof EntityLivingBase ? ((EntityLivingBase) target).hurtTime : 0;
	}

	/** Bestätigt Treffer (Ziel nimmt Schaden), bricht die Combo bei eigenem Schaden ab. */
	private void tickTrackers(EntityPlayer player) {
		long now = System.currentTimeMillis();
		if (player != null) {
			speed.tick(player.posX, player.posY, player.posZ, modules.speedVertical.get());
			int hurt = player.hurtTime;
			if (hurt > selfHurtTime) combo.onSelfHurt();
			selfHurtTime = hurt;
		}
		if (attacked != null) {
			boolean gone = !attacked.isEntityAlive();
			if (attacked instanceof EntityLivingBase) {
				int hurt = ((EntityLivingBase) attacked).hurtTime;
				if (hurt > attackedHurtTime) combo.onTargetHurt(attacked.getEntityId(), now);
				attackedHurtTime = hurt;
			} else {
				// Nicht lebende Ziele (z. B. Boote) melden keinen Schaden – Schlag sofort zählen.
				combo.onTargetHurt(attacked.getEntityId(), now);
				attacked = null;
			}
			if (gone) attacked = null;
		}
		combo.tick(now, (long) (modules.comboTimeout.get() * 1000));
	}

	/** Trefferboxen wie F3+B; schaltet nur, solange sich der Modulzustand ändert. */
	private void tickHitboxes() {
		boolean wanted = modules.hitboxes.isEnabled();
		if (wanted == hitboxesApplied) return;
		Mc.setDebugHitboxes(wanted);
		hitboxesApplied = wanted;
	}

	/** 1.7-Animationen: Schlagbewegung auch, während ein Gegenstand benutzt wird (nur Optik). */
	private void tickBlockHit(Minecraft mc, EntityPlayer player, boolean inGame) {
		if (!inGame || player == null) return;
		if (!modules.oldAnimations.isEnabled() || !modules.oldAnimationsBlockHit.get()) return;
		if (!Mc.usingItem(player) || !mc.gameSettings.keyBindAttack.isKeyDown()) return;
		if (player.isSwingInProgress) return;
		// Nur die Anzeige: kein Paket, kein Angriff – Minecraft spielt die Animation lokal ab.
		Mc.startSwingAnimation(player);
	}

	public ReachTracker reach() {
		return reach;
	}

	public ComboTracker combo() {
		return combo;
	}

	public SpeedTracker speed() {
		return speed;
	}

	/**
	 * Toggle-Sprint/-Schleichen und Flug-Boost: Tastendrücke zählen (isPressed), die Logik in
	 * {@link MovementToggles} entscheiden lassen und das Ergebnis auf die Vanilla-Tasten bzw. die
	 * Fluggeschwindigkeit übertragen. Vanilla hat bis 1.12.2 keine eigene Umschalt-Option.
	 */
	private void tickToggles(Minecraft mc, EntityPlayer player, boolean inGame) {
		KeyBinding sprintKey = mc.gameSettings.keyBindSprint;
		KeyBinding sneakKey = mc.gameSettings.keyBindSneak;
		MovementToggles.Input in = toggles.input().clear();
		while (sprintKey.isPressed()) in.sprintPresses++;
		while (sneakKey.isPressed()) in.sneakPresses++;
		if (player != null) {
			in.hasPlayer = true;
			in.inGame = inGame;
			in.dead = player.getHealth() <= 0;
			// Respawn und Dimensionswechsel erzeugen eine neue Spielfigur bzw. Welt.
			in.context = System.identityHashCode(player) * 31 + System.identityHashCode(Mc.world());
			in.forwardDown = mc.gameSettings.keyBindForward.isKeyDown();
			in.sprintKeyDown = sprintKey.isKeyDown();
			in.sneakKeyDown = sneakKey.isKeyDown();
			in.creativeFlying = player.capabilities.isFlying && player.capabilities.isCreativeMode;
			in.sprinting = player.isSprinting();
		}
		toggles.tick();
		apply(sprintKey, toggles.sprintAction());
		apply(sneakKey, toggles.sneakAction());
		if (player != null) {
			float current = player.capabilities.getFlySpeed();
			float wanted = toggles.flySpeed(current);
			if (wanted != current) player.capabilities.setFlySpeed(wanted);
		}
	}

	private static void apply(KeyBinding key, int action) {
		if (key.getKeyCode() == 0 || action == MovementToggles.KEEP) return;
		KeyBinding.setKeyBindState(key.getKeyCode(), action == MovementToggles.PRESS);
	}

	private void tickFreelook(Minecraft mc, EntityPlayer player, boolean inGame) {
		boolean forced = !Float.isNaN(forcedFreelookYaw);
		boolean blocked = freelookBlocked.contains(Mc.serverAddress(), modules.freelookServers.get());
		boolean want = freelook.wanted(TrsKeys.freelook.isKeyDown(), modules.freelookToggle.get(), inGame,
				modules.freelook.isEnabled() && player != null, blocked) || (forced && player != null);
		if (freelook.consumeBlockedNotice()) Mc.actionBar(I18n.tr("toast.freelookBlocked"));
		if (want && !freelook.active()) {
			freelook.start(player.rotationYaw + (forced ? forcedFreelookYaw : 0), player.rotationPitch);
			viewBeforeFreelook = mc.gameSettings.thirdPersonView;
			mc.gameSettings.thirdPersonView = modules.freelookPerspective.get().cameraMode();
		} else if (!want && freelook.active()) {
			freelook.stop();
			if (viewBeforeFreelook >= 0) mc.gameSettings.thirdPersonView = viewBeforeFreelook;
			viewBeforeFreelook = -1;
		}
	}

	/** Selbsttest: Freelook erzwingen (Kamera um {@code yawOffset} gedreht) bzw. mit NaN beenden. */
	public void forceFreelook(float yawOffset) {
		forcedFreelookYaw = yawOffset;
	}

	public ToggleState sprint() {
		return toggles.sprint();
	}

	public ToggleState sneak() {
		return toggles.sneak();
	}

	public MovementToggles toggles() {
		return toggles;
	}

	public FreelookState freelook() {
		return freelook;
	}
}
