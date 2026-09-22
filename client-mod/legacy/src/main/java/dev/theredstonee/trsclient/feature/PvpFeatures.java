package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.camera.FreelookState;
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
	private final ToggleState sprint = new ToggleState();
	private final ToggleState sneak = new ToggleState();
	private final FreelookState freelook = new FreelookState();
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
	}

	public void tick(Minecraft mc) {
		EntityPlayer player = Mc.player();
		boolean inGame = player != null && mc.currentScreen == null;
		// Vanilla hat bis 1.12.2 keine eigene Umschalt-Option für Sprint/Schleichen.
		tickToggle(sprint, mc.gameSettings.keyBindSprint, modules.toggleSprint.isEnabled(), inGame);
		tickToggle(sneak, mc.gameSettings.keyBindSneak, modules.toggleSneak.isEnabled(), inGame);
		if (player == null) {
			sprint.reset();
			sneak.reset();
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
	 * Jeder Druck (isPressed der Taste) schaltet um; aktiv → Taste gilt als gehalten.
	 */
	private static void tickToggle(ToggleState state, KeyBinding key, boolean enabled, boolean inGame) {
		int presses = 0;
		while (key.isPressed()) presses++;
		boolean wasActive = state.active();
		boolean active = state.update(presses, enabled, !inGame);
		if (key.getKeyCode() == 0) return;
		if (active && inGame) KeyBinding.setKeyBindState(key.getKeyCode(), true);
		else if (wasActive && !active) KeyBinding.setKeyBindState(key.getKeyCode(), false);
	}

	private void tickFreelook(Minecraft mc, EntityPlayer player, boolean inGame) {
		boolean forced = !Float.isNaN(forcedFreelookYaw);
		boolean want = (modules.freelook.isEnabled() && inGame && TrsKeys.freelook.isKeyDown()) || (forced && player != null);
		if (want && !freelook.active()) {
			freelook.start(player.rotationYaw + (forced ? forcedFreelookYaw : 0), player.rotationPitch);
			viewBeforeFreelook = mc.gameSettings.thirdPersonView;
			if (viewBeforeFreelook == 0) mc.gameSettings.thirdPersonView = 1;
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
		return sprint;
	}

	public ToggleState sneak() {
		return sneak;
	}

	public FreelookState freelook() {
		return freelook;
	}
}
