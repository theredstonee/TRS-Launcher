package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.core.emote.EmoteController;
import dev.theredstonee.trsclient.core.emote.EmotePlayback;
import dev.theredstonee.trsclient.core.emote.EmoteRig;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Emotes im Spiel (Fabric, NeoForge, Forge, Forge-Mojmap – eine Datei, Stonecutter-Zweige 1.14.4–26.3): Spieler
 * und Kamera an {@link EmoteController} geben, die Rad-Taste abfragen und die Pose auf das Spielermodell legen.
 * Die Posen selbst rechnet {@code core.emote}; hier werden nur die sechs Modellteile gelesen und geschrieben.
 *
 * <p>Modell-Hook: am Ende von {@code HumanoidModel#setupAnim} (auch Rüstung, die bis 1.21.1 die Haltung per
 * {@code copyPropertiesTo} übernimmt und ab 1.21.2 denselben Render-State bekommt). Bis 1.21.1 setzt Vanilla
 * nicht jeden Kanal in jedem Bild neu – deshalb wird die Ruhehaltung eines schon einmal veränderten Modells am
 * Anfang von {@code setupAnim} wiederhergestellt. Ab 1.21.2 setzt {@code resetPose} alles selbst zurück; der
 * Spieler wird dort über seinen Render-State gefunden ({@code extractRenderState}).
 */
public final class EmoteHooks {
	/** Ruhehaltung je Modell (≤ 1.21.1), nur für Modelle, die schon ein Emote gezeigt haben. */
	private static final Map<Object, float[]> REST = new WeakHashMap<>();
	/** Render-State → Spieler mit Emote (ab 1.21.2). */
	private static final Map<Object, UUID> STATES = new WeakHashMap<>();
	private static final float[] PARTS = new float[EmoteRig.PARTS * EmoteRig.STRIDE];

	private EmoteHooks() {
	}

	/** Emote-Steuerung oder null (Online-Funktionen noch nicht gestartet). */
	public static EmoteController emotes() {
		OnlineFeatures<Object> f = OnlineHooks.features();
		return f == null ? null : f.emotes();
	}

	/** Ist das Emote-Modul an (Rad-Taste aktiv)? */
	public static boolean enabled() {
		dev.theredstonee.trsclient.TrsClient client = dev.theredstonee.trsclient.TrsClient.get();
		return OnlineHooks.features() != null && client != null && client.modules().emotes.isEnabled();
	}

	private static void report(RuntimeException e) {
		OnlineFeatures<Object> f = OnlineHooks.features();
		if (f != null) f.online().reportError(e);
	}

	// --- Tick ---

	/** Einmal pro Client-Tick (nach {@link OnlineHooks#tick}). */
	public static void tick(Minecraft mc) {
		EmoteController c = emotes();
		if (c == null) return;
		try {
			List<EmotePlayback.Mover> movers = new ArrayList<>();
			if (mc.level != null && mc.player != null) {
				for (AbstractClientPlayer p : mc.level.players()) {
					movers.add(new EmotePlayback.Mover(p.getUUID(), x(p), z(p), crouching(p), p == mc.player));
				}
			}
			c.tick(System.currentTimeMillis(), movers, mc.player != null && mc.options != null ? CAMERA : null);
		} catch (RuntimeException e) {
			report(e);
		}
	}

	/** Steht der Ereignis-Stream der TRS API (Selbsttest)? */
	public static boolean eventsConnected() {
		OnlineFeatures<Object> f = OnlineHooks.features();
		return f != null && f.online().eventsConnected();
	}

	/** Eigener Angriff (Linksklick im Spiel): eigenes Emote beenden. */
	public static void onAttack() {
		EmoteController c = emotes();
		if (c != null) c.onAttack(System.currentTimeMillis());
	}

	private static double x(Player p) {
		//? if >=1.15 {
		return p.getX();
		//?} else {
		/*return p.x;
		*///?}
	}

	private static double z(Player p) {
		//? if >=1.15 {
		return p.getZ();
		//?} else {
		/*return p.z;
		*///?}
	}

	private static boolean crouching(Player p) {
		//? if >=1.15 {
		return p.isCrouching();
		//?} else {
		/*return p.isSneaking();
		*///?}
	}

	/** Kamera: 0 = Ich-Perspektive, 1 = von hinten, 2 = von vorn. */
	public static final EmoteController.Camera CAMERA = new EmoteController.Camera() {
		@Override
		public int get() {
			//? if >=1.16 {
			return Minecraft.getInstance().options.getCameraType().ordinal();
			//?} else {
			/*return Minecraft.getInstance().options.thirdPersonView;
			*///?}
		}

		@Override
		public void set(int mode) {
			//? if >=1.16 {
			Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.values()[mode]);
			//?} else {
			/*Minecraft.getInstance().options.thirdPersonView = mode;
			*///?}
		}
	};

	// --- Rad ---

	/**
	 * Ist die Rad-Taste gerade gedrückt? Abgefragt wird die echte Taste (ein offener Bildschirm hält die
	 * Tastenbelegungen an). null, wenn die Belegung keine Tastaturtaste ist (dann Klick-Modus im Rad).
	 */
	public static Boolean keyHeld(KeyMapping mapping) {
		if (mapping == null) return null;
		String name = mapping.saveString();
		if (name == null || !name.startsWith("key.keyboard.") || name.equals("key.keyboard.unknown")) return null;
		return Keys.isDown(name);
	}

	/** Anzeigename der belegten Taste. */
	public static String keyName(KeyMapping mapping) {
		if (mapping == null) return "?";
		//? if >=1.16 {
		return mapping.getTranslatedKeyMessage().getString();
		//?} else {
		/*return mapping.getTranslatedKeyMessage();
		*///?}
	}

	/** Hinweis über der Schnellleiste. */
	public static void actionBar(String text) {
		Minecraft mc = Minecraft.getInstance();
		//? if >=26.2 {
		/*mc.gui.hud.setOverlayMessage(OnlineHooks.text(text), false);
		*///?} else {
		mc.gui.setOverlayMessage(OnlineHooks.text(text), false);
		//?}
	}

	/** Offenen Bildschirm schließen (zurück ins Spiel). */
	public static void closeScreen() {
		Minecraft mc = Minecraft.getInstance();
		//? if >=26.2 {
		/*mc.gui.setScreen(null);
		*///?} else {
		mc.setScreen(null);
		//?}
	}

	/** Ist gerade ein Bildschirm offen? */
	public static boolean screenOpen() {
		Minecraft mc = Minecraft.getInstance();
		//? if >=26.2 {
		/*return mc.gui.screen() != null;
		*///?} else {
		return mc.screen != null;
		//?}
	}

	// --- Modell ---

	/** Anfang von {@code setupAnim} (≤ 1.21.1): von uns veränderte Kanäle auf Ruhehaltung zurück. */
	public static void beforeSetup(HumanoidModel<?> model) {
		if (REST.isEmpty()) return;
		float[] rest = REST.get(model);
		if (rest != null) write(model, rest);
	}

	/**
	 * Ende von {@code setupAnim} (≤ 1.21.1). {@code ageInTicks} 0 = Hand in der Ich-Perspektive
	 * ({@code renderHand} ruft setupAnim mit Nullen) – dort bleibt alles Vanilla.
	 */
	public static void afterSetup(HumanoidModel<?> model, LivingEntity entity, float ageInTicks) {
		if (!(entity instanceof Player) || ageInTicks == 0f) return;
		apply(model, entity.getUUID(), true);
	}

	/** Ende von {@code setupAnim} (ab 1.21.2): Spieler über den Render-State. */
	public static void afterSetup(HumanoidModel<?> model, Object state) {
		if (STATES.isEmpty() || state == null) return;
		UUID uuid = STATES.get(state);
		if (uuid != null) apply(model, uuid, false);
	}

	/** {@code extractRenderState} eines Spielers (ab 1.21.2): Render-State dem Spieler zuordnen. */
	public static void extracted(Object state, UUID uuid) {
		EmoteController c = emotes();
		if (c != null && uuid != null && c.animating(uuid)) STATES.put(state, uuid);
		else if (!STATES.isEmpty()) STATES.remove(state);
	}

	private static void apply(HumanoidModel<?> model, UUID uuid, boolean keepRest) {
		EmoteController c = emotes();
		if (c == null || !c.animating(uuid)) return;
		try {
			read(model, PARTS);
			if (keepRest && !REST.containsKey(model)) REST.put(model, PARTS.clone());
			if (c.apply(uuid, System.currentTimeMillis(), PARTS)) {
				write(model, PARTS);
				//? if <1.21.2 {
				// Bis 1.21.1 ist die Kopfbedeckung ein eigenes Teil, das Vanilla vorher kopiert hat.
				model.hat.copyFrom(model.head);
				//?}
			}
		} catch (RuntimeException e) {
			report(e);
		}
	}

	private static ModelPart part(HumanoidModel<?> m, int i) {
		switch (i) {
			case EmoteRig.HEAD:
				return m.head;
			case EmoteRig.BODY:
				return m.body;
			case EmoteRig.RIGHT_ARM:
				return m.rightArm;
			case EmoteRig.LEFT_ARM:
				return m.leftArm;
			case EmoteRig.RIGHT_LEG:
				return m.rightLeg;
			default:
				return m.leftLeg;
		}
	}

	private static void read(HumanoidModel<?> m, float[] out) {
		for (int i = 0; i < EmoteRig.PARTS; i++) {
			ModelPart p = part(m, i);
			int o = i * EmoteRig.STRIDE;
			out[o] = p.x;
			out[o + 1] = p.y;
			out[o + 2] = p.z;
			out[o + 3] = p.xRot;
			out[o + 4] = p.yRot;
			out[o + 5] = p.zRot;
		}
	}

	private static void write(HumanoidModel<?> m, float[] in) {
		for (int i = 0; i < EmoteRig.PARTS; i++) {
			ModelPart p = part(m, i);
			int o = i * EmoteRig.STRIDE;
			p.x = in[o];
			p.y = in[o + 1];
			p.z = in[o + 2];
			p.xRot = in[o + 3];
			p.yRot = in[o + 4];
			p.zRot = in[o + 5];
		}
	}
}
