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
import dev.theredstonee.trsclient.mixin.OverlayTextureAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Toggle-Sprint/-Schleichen, Freelook, Treffer-Farbe und die PvP-Zähler (Reichweite, Combo,
 * Geschwindigkeit). Wird zu Beginn jedes Client-Ticks aufgerufen (vor der Spieler-Bewegung,
 * damit gesetzte Tasten im selben Tick wirken).
 */
public final class PvpFeatures {
	/** Vanilla-Farbe der Treffer-Einfärbung (ARGB). */
	private static final int VANILLA_HIT = 0xB2FF0000;

	private final TrsModules modules;
	/** Toggle-Sprint/-Schleichen + Flug-Boost (Logik in common). */
	private final MovementToggles toggles;
	private final FreelookState freelook = new FreelookState();
	/** Server, auf denen Freelook aus ist (zwischengespeichert). */
	private final ServerList freelookBlocked = new ServerList();
	private CameraType cameraBeforeFreelook;
	/** Nur Selbsttest: Freelook ohne Tastendruck, mit Versatz zum Blick der Spielfigur. */
	private float forcedFreelookYaw = Float.NaN;
	private int appliedHitColor = VANILLA_HIT;
	private boolean hitColorFailed;
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
		boolean inGame = mc.player != null && Mc.screen() == null;
		tickToggles(mc, inGame);
		if (mc.player == null) {
			reach.reset();
			combo.reset();
			speed.reset();
			attacked = null;
		}
		tickFreelook(mc, inGame);
		tickHitColor(mc);
		tickPvpTrackers(mc);
		tickHitboxes(mc);
		tickBlockHit(mc, inGame);
	}

	// --- Reichweite, Combo, Geschwindigkeit ---

	/**
	 * Aus dem Angriffs-Mixin: merkt sich Ziel und Entfernung des Schlages.
	 * Gemessen wird vom Auge zum tatsächlich getroffenen Punkt – reine Anzeige.
	 */
	public void onAttack(Player player, Entity target) {
		if (player == null || target == null) return;
		long now = System.currentTimeMillis();
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = new Vec3(player.getX(), player.getY() + player.getEyeHeight(), player.getZ());
		HitResult hit = mc.hitResult;
		double distance;
		if (hit instanceof EntityHitResult && ((EntityHitResult) hit).getEntity() == target) {
			distance = eye.distanceTo(hit.getLocation());
		} else {
			distance = eye.distanceTo(new Vec3(target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ()));
		}
		reach.record(distance, now);
		combo.onAttack(target.getId(), now);
		attacked = target;
		attackedHurtTime = target instanceof LivingEntity ? ((LivingEntity) target).hurtTime : 0;
	}

	/** Bestätigt Treffer (Ziel nimmt Schaden), bricht die Combo bei eigenem Schaden ab. */
	private void tickPvpTrackers(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (mc.player != null) {
			speed.tick(mc.player.getX(), mc.player.getY(), mc.player.getZ(), modules.speedVertical.get());
			int hurt = mc.player.hurtTime;
			if (hurt > selfHurtTime) combo.onSelfHurt();
			selfHurtTime = hurt;
		}
		if (attacked != null) {
			boolean gone = !attacked.isAlive();
			if (attacked instanceof LivingEntity) {
				int hurt = ((LivingEntity) attacked).hurtTime;
				if (hurt > attackedHurtTime) combo.onTargetHurt(attacked.getId(), now);
				attackedHurtTime = hurt;
			} else {
				// Nicht lebende Ziele (z. B. Boote) melden keinen Schaden – Schlag sofort zählen.
				combo.onTargetHurt(attacked.getId(), now);
				attacked = null;
			}
			if (gone) attacked = null;
		}
		combo.tick(now, (long) (modules.comboTimeout.get() * 1000));
	}

	/** Hitboxen wie F3+B; schaltet sie nur, solange das Modul an ist. */
	private void tickHitboxes(Minecraft mc) {
		//? if <1.21.9 {
		boolean wanted = modules.hitboxes.isEnabled();
		if (wanted == hitboxesApplied) return;
		mc.getEntityRenderDispatcher().setRenderHitBoxes(wanted);
		hitboxesApplied = wanted;
		//?}
		// Ab 1.21.9 kennt Minecraft die Hitbox-Anzeige nur noch als Debug-Eintrag – dort nicht verfügbar.
	}

	/** 1.7-Animationen (Teil 2): Schlagbewegung auch, während ein Gegenstand benutzt wird (nur Optik). */
	private void tickBlockHit(Minecraft mc, boolean inGame) {
		if (!inGame || mc.player == null) return;
		if (!modules.oldAnimations.isEnabled() || !modules.oldAnimationsBlockHit.get()) return;
		if (!mc.player.isUsingItem() || !mc.options.keyAttack.isDown()) return;
		//? if >=26.3 {
		/*// Ab 26.3 verwaltet Minecraft die Schlaganimation in einem eigenen Zustand ohne
		// öffentliche Felder – dort ist dieser Teil der 1.7-Animationen nicht verfügbar.
		*///?} else {
		if (mc.player.swinging) return;
		// Nur die Anzeige: kein Paket, kein Angriff – Minecraft spielt die Animation lokal ab.
		mc.player.swinging = true;
		mc.player.swingingArm = InteractionHand.MAIN_HAND;
		mc.player.swingTime = -1;
		//?}
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
	 * Toggle-Sprint/-Schleichen und Flug-Boost: Tastendrücke zählen (clickCount), die Logik in
	 * {@link MovementToggles} entscheiden lassen und das Ergebnis auf die Vanilla-Tasten bzw. die
	 * Fluggeschwindigkeit übertragen. Läuft vor der Spieler-Bewegung, damit es im selben Tick wirkt.
	 */
	private void tickToggles(Minecraft mc, boolean inGame) {
		KeyMapping sprintKey = mc.options.keySprint;
		KeyMapping sneakKey = mc.options.keyShift;
		MovementToggles.Input in = toggles.input().clear();
		while (sprintKey.consumeClick()) in.sprintPresses++;
		while (sneakKey.consumeClick()) in.sneakPresses++;
		Player player = mc.player;
		if (player != null) {
			in.hasPlayer = true;
			in.inGame = inGame;
			in.dead = player.getHealth() <= 0;
			// Respawn und Dimensionswechsel erzeugen eine neue Spielfigur bzw. Welt.
			in.context = System.identityHashCode(player) * 31 + System.identityHashCode(mc.level);
			in.forwardDown = mc.options.keyUp.isDown();
			in.sprintKeyDown = sprintKey.isDown();
			in.sneakKeyDown = sneakKey.isDown();
			in.vanillaToggleSprint = mc.options.toggleSprint().get();
			in.vanillaToggleSneak = mc.options.toggleCrouch().get();
			in.creativeFlying = player.getAbilities().flying && player.getAbilities().instabuild;
			in.sprinting = player.isSprinting();
		}
		toggles.tick();
		apply(sprintKey, toggles.sprintAction());
		apply(sneakKey, toggles.sneakAction());
		if (player != null) {
			net.minecraft.world.entity.player.Abilities abilities = player.getAbilities();
			float current = abilities.getFlyingSpeed();
			float wanted = toggles.flySpeed(current);
			if (wanted != current) abilities.setFlyingSpeed(wanted);
		}
	}

	private static void apply(KeyMapping key, int action) {
		if (action == MovementToggles.PRESS) key.setDown(true);
		else if (action == MovementToggles.RELEASE) key.setDown(false);
	}

	private void tickFreelook(Minecraft mc, boolean inGame) {
		boolean forced = !Float.isNaN(forcedFreelookYaw);
		boolean blocked = freelookBlocked.contains(Mc.serverAddress(), modules.freelookServers.get());
		boolean want = freelook.wanted(TrsKeys.freelook.isDown(), modules.freelookToggle.get(), inGame,
				modules.freelook.isEnabled() && mc.player != null, blocked) || (forced && mc.player != null);
		if (freelook.consumeBlockedNotice()) Mc.actionBar(net.minecraft.network.chat.Component.literal(I18n.tr("toast.freelookBlocked")));
		if (want && !freelook.active()) {
			freelook.start(mc.player.getYRot() + (forced ? forcedFreelookYaw : 0), mc.player.getXRot());
			cameraBeforeFreelook = mc.options.getCameraType();
			mc.options.setCameraType(CameraType.values()[modules.freelookPerspective.get().cameraMode()]);
		} else if (!want && freelook.active()) {
			freelook.stop();
			if (cameraBeforeFreelook != null) mc.options.setCameraType(cameraBeforeFreelook);
			cameraBeforeFreelook = null;
		}
	}

	/** Färbt die obere Hälfte der Overlay-Textur (= Treffer-Einfärbung) um. */
	private void tickHitColor(Minecraft mc) {
		int wanted = modules.hitColor.isEnabled()
				? (Math.round(modules.hitColorOpacity.getInt() * 2.55F) << 24) | modules.hitColorColor.rgb()
				: VANILLA_HIT;
		if (wanted == appliedHitColor || hitColorFailed || mc.gameRenderer == null) return;
		try {
			DynamicTexture texture = ((OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).trsclient$getTexture();
			NativeImage image = texture.getPixels();
			if (image == null) return;
			for (int y = 0; y < 8; y++) {
				for (int x = 0; x < 16; x++) setPixel(image, x, y, wanted);
			}
			texture.upload();
			appliedHitColor = wanted;
		} catch (RuntimeException e) {
			// Nie das Spiel abstürzen lassen – nur einmal melden.
			hitColorFailed = true;
			dev.theredstonee.trsclient.TrsClient.LOGGER.error("Treffer-Farbe konnte nicht gesetzt werden", e);
		}
	}

	/** ARGB-Pixel setzen (bis 1.21.1 erwartet NativeImage ABGR). */
	private static void setPixel(NativeImage image, int x, int y, int argb) {
		//? if >=1.21.2 {
		/*image.setPixel(x, y, argb);
		*///?} else {
		int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
		image.setPixelRGBA(x, y, abgr);
		//?}
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
