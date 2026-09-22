package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.pvp.ComboTracker;
import dev.theredstonee.trsclient.core.pvp.ReachTracker;
import dev.theredstonee.trsclient.core.pvp.SpeedTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Toggle-Sprint/-Schleichen sowie die reinen PvP-Anzeigen (Reichweite, Combo, Geschwindigkeit),
 * Hitboxen und die 1.7-Schlaganimation für 1.13.2.
 * <p>
 * {@link #countPresses} läuft zu Beginn des Client-Ticks und zählt neue Tastendrücke;
 * {@link #apply} läuft im {@code LivingUpdateEvent} des eigenen Spielers – direkt BEVOR
 * {@code EntityPlayerSP.livingTick} die Tasten liest. Dadurch flackert Schleichen beim Loslassen nicht.
 * {@link #tickTrackers} läuft am Ende des Client-Ticks.
 */
public final class PvpFeatures {
	private final TrsModules modules;
	private final ToggleState sprint = new ToggleState();
	private final ToggleState sneak = new ToggleState();
	private boolean sprintHeld;
	private boolean sneakHeld;
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

	/** Anfang des Client-Ticks: Tastendrücke seit dem letzten Tick zählen und umschalten. */
	public void countPresses(Minecraft mc) {
		boolean inGame = mc.player != null && mc.currentScreen == null;
		sprintHeld = tick(mc, sprint, mc.gameSettings.keyBindSprint, modules.toggleSprint.isEnabled(), inGame, sprintHeld);
		sneakHeld = tick(mc, sneak, mc.gameSettings.keyBindSneak, modules.toggleSneak.isEnabled(), inGame, sneakHeld);
		if (mc.player == null) {
			sprint.reset();
			sneak.reset();
		}
	}

	private static boolean tick(Minecraft mc, ToggleState state, KeyBinding key, boolean enabled, boolean inGame, boolean held) {
		int presses = 0;
		// isPressed() verbraucht nur den Klick-Zähler; Vanilla liest Sprint/Schleichen über isKeyDown().
		while (key.isPressed()) presses++;
		boolean active = state.update(presses, enabled, !inGame);
		if (held && !active) {
			// Umschaltung beendet → echten Tastenzustand wiederherstellen.
			KeyBinding.setKeyBindState(key.getKey(), physicallyDown(mc, key.getKey()));
		}
		return active;
	}

	/** Direkt vor der Bewegungs-Auswertung des Spielers: umgeschaltete Tasten als gehalten markieren. */
	public void apply(Minecraft mc) {
		if (mc.currentScreen != null) return;
		if (sprintHeld) KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKey(), true);
		if (sneakHeld) KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKey(), true);
	}

	private static boolean physicallyDown(Minecraft mc, InputMappings.Input input) {
		if (input == null || input == InputMappings.INPUT_INVALID) return false;
		if (input.getType() == InputMappings.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(mc.mainWindow.getHandle(), input.getKeyCode()) == GLFW.GLFW_PRESS;
		}
		return input.getType() == InputMappings.Type.KEYSYM && InputMappings.isKeyDown(input.getKeyCode());
	}

	// --- Reichweite, Combo, Geschwindigkeit (reine Anzeigen) ---

	/**
	 * Aus {@code AttackEntityEvent}: merkt sich Ziel und Entfernung des Schlages.
	 * Gemessen wird vom Auge zum tatsächlich getroffenen Punkt – die Reichweite selbst bleibt unverändert.
	 */
	public void onAttack(Minecraft mc, EntityPlayer player, Entity target) {
		if (player == null || target == null || player != mc.player) return;
		long now = System.currentTimeMillis();
		reach.record(hitDistance(mc, player, target), now);
		combo.onAttack(target.getEntityId(), now);
		attacked = target;
		attackedHurtTime = target instanceof EntityLivingBase ? ((EntityLivingBase) target).hurtTime : 0;
	}

	/** Entfernung Auge → getroffener Punkt (fällt auf die Mitte des Ziels zurück). */
	private static double hitDistance(Minecraft mc, EntityPlayer player, Entity target) {
		Vec3d eye = player.getEyePosition(1.0F);
		RayTraceResult hit = mc.objectMouseOver;
		if (hit != null && hit.entity == target && hit.hitVec != null) return eye.distanceTo(hit.hitVec);
		return eye.distanceTo(new Vec3d(target.posX, target.posY + target.height / 2.0, target.posZ));
	}

	/** Ende des Client-Ticks: Treffer bestätigen, Geschwindigkeit messen, Hitboxen/Animation schalten. */
	public void tickTrackers(Minecraft mc) {
		EntityPlayer player = mc.player;
		boolean inGame = player != null && mc.currentScreen == null;
		if (player == null) {
			reach.reset();
			combo.reset();
			speed.reset();
			attacked = null;
		}
		long now = System.currentTimeMillis();
		if (player != null) {
			speed.tick(player.posX, player.posY, player.posZ, modules.speedVertical.get());
			int hurt = player.hurtTime;
			if (hurt > selfHurtTime) combo.onSelfHurt();
			selfHurtTime = hurt;
		}
		if (attacked != null) {
			boolean gone = !attacked.isAlive();
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
		tickHitboxes(mc);
		tickBlockHit(mc, player, inGame);
	}

	/** Trefferboxen wie F3+B; schaltet nur, solange sich der Modulzustand ändert. */
	private void tickHitboxes(Minecraft mc) {
		boolean wanted = modules.hitboxes.isEnabled();
		if (wanted == hitboxesApplied) return;
		if (mc.getRenderManager() != null) mc.getRenderManager().setDebugBoundingBox(wanted);
		hitboxesApplied = wanted;
	}

	/** 1.7-Animationen: Schlagbewegung auch, während ein Gegenstand benutzt wird (nur Optik). */
	private void tickBlockHit(Minecraft mc, EntityPlayer player, boolean inGame) {
		if (!inGame || player == null) return;
		if (!modules.oldAnimations.isEnabled() || !modules.oldAnimationsBlockHit.get()) return;
		if (!player.isHandActive() || !mc.gameSettings.keyBindAttack.isKeyDown()) return;
		if (player.isSwingInProgress) return;
		// Nur die Anzeige: kein Paket, kein Angriff – Minecraft spielt die Animation lokal ab.
		player.isSwingInProgress = true;
		player.swingProgressInt = -1;
		player.swingingHand = EnumHand.MAIN_HAND;
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

	public ToggleState sprint() {
		return sprint;
	}

	public ToggleState sneak() {
		return sneak;
	}
}
