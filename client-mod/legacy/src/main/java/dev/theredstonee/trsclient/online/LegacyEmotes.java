package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.emote.EmoteController;
import dev.theredstonee.trsclient.core.emote.EmotePlayback;
import dev.theredstonee.trsclient.core.emote.EmoteRig;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
//? if >=1.9 {
/*import net.minecraft.client.renderer.entity.RenderLivingBase;
*///?} else {
import net.minecraft.client.renderer.entity.RendererLivingEntity;
//?}

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Emotes für Forge 1.8.9–1.12.2 (ohne Mixins): die Spieler-Renderer bekommen ein {@link EmoteModelPlayer} (und ihre
 * Rüstungs-Ebenen {@link EmoteModelBiped}), deren {@code setRotationAngles} nach Vanilla die Emote-Pose anlegt.
 * Private Felder werden wie in {@link LegacyOnline} über ihren Typ gefunden (keine SRG-Namen nötig). Nur exakt
 * die Vanilla-Klassen werden ersetzt – eigene Modelle anderer Mods bleiben unangetastet.
 */
public final class LegacyEmotes {
	private static boolean installed;
	/** Ruhehaltung je Modell, nur für Modelle, die schon ein Emote gezeigt haben. */
	private static final Map<Object, float[]> REST = new WeakHashMap<>();
	private static final float[] PARTS = new float[EmoteRig.PARTS * EmoteRig.STRIDE];

	private LegacyEmotes() {
	}

	public static EmoteController emotes() {
		OnlineFeatures<Object> f = LegacyOnline.features();
		return f == null ? null : f.emotes();
	}

	/** Ist das Emote-Modul an (Rad-Taste aktiv)? */
	public static boolean enabled() {
		TrsClient client = TrsClient.get();
		return LegacyOnline.features() != null && client != null && client.modules().emotes.isEnabled();
	}

	private static void report(RuntimeException e) {
		OnlineFeatures<Object> f = LegacyOnline.features();
		if (f != null) f.online().reportError(e);
	}

	/** Steht der Ereignis-Stream der TRS API (Selbsttest)? */
	public static boolean eventsConnected() {
		OnlineFeatures<Object> f = LegacyOnline.features();
		return f != null && f.online().eventsConnected();
	}

	// --- Tick ---

	/** Einmal pro Client-Tick (nach {@link LegacyOnline#tick}). Eine Ausnahme hier würde das Spiel beenden. */
	public static void tick(Minecraft mc) {
		EmoteController c = emotes();
		if (c == null) return;
		try {
			if (!installed && mc.getRenderManager() != null) install(mc);
			List<EmotePlayback.Mover> movers = new ArrayList<>();
			EntityPlayer self = Mc.player();
			if (Mc.world() != null && self != null) {
				for (EntityPlayer p : new ArrayList<>(Mc.world().playerEntities)) {
					movers.add(new EmotePlayback.Mover(p.getUniqueID(), p.posX, p.posZ, p.isSneaking(), p == self));
				}
			}
			c.tick(System.currentTimeMillis(), movers, self != null ? CAMERA : null);
		} catch (RuntimeException e) {
			report(e);
		}
	}

	/** Eigener Linksklick im Spiel: eigenes Emote beenden. */
	public static void onAttack() {
		EmoteController c = emotes();
		if (c != null) c.onAttack(System.currentTimeMillis());
	}

	/** Kamera: 0 = Ich-Perspektive, 1 = von hinten, 2 = von vorn ({@code thirdPersonView}). */
	public static final EmoteController.Camera CAMERA = new EmoteController.Camera() {
		@Override
		public int get() {
			return Minecraft.getMinecraft().gameSettings.thirdPersonView;
		}

		@Override
		public void set(int mode) {
			Minecraft.getMinecraft().gameSettings.thirdPersonView = mode;
		}
	};

	// --- Modelle ersetzen ---

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void install(Minecraft mc) {
		installed = true;
		try {
			//? if >=1.9 {
			/*Class<?> living = RenderLivingBase.class;
			*///?} else {
			Class<?> living = RendererLivingEntity.class;
			//?}
			Field main = LegacyOnline.field(living, ModelBase.class, 0);
			Field layerList = LegacyOnline.field(living, List.class, 0);
			int models = 0;
			int armor = 0;
			for (Map.Entry<String, RenderPlayer> e : mc.getRenderManager().getSkinMap().entrySet()) {
				RenderPlayer renderer = e.getValue();
				Object model = main.get(renderer);
				if (model != null && model.getClass() == ModelPlayer.class) {
					main.set(renderer, new EmoteModelPlayer(0.0F, "slim".equals(e.getKey())));
					models++;
				}
				for (LayerRenderer layer : (List<LayerRenderer>) layerList.get(renderer)) {
					if (layer instanceof LayerBipedArmor) armor += replaceArmor(layer);
				}
			}
			TrsClient.LOGGER.info("Emotes: {} Spielermodelle, {} Rüstungsmodelle vorbereitet", models, armor);
		} catch (IllegalAccessException | RuntimeException e) {
			TrsClient.LOGGER.warn("Emotes: Modelle nicht ersetzbar ({}) – keine Emote-Animationen", e.toString());
		}
	}

	/** Rüstungsmodelle (Beine 0,5, Rest 1,0 aufgeblasen) durch Emote-fähige gleicher Größe ersetzen. */
	private static int replaceArmor(Object layer) throws IllegalAccessException {
		int n = 0;
		for (Field f : LayerArmorBase.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) || !ModelBase.class.isAssignableFrom(f.getType())) continue;
			f.setAccessible(true);
			Object model = f.get(layer);
			if (model == null || model.getClass() != ModelBiped.class) continue;
			f.set(layer, new EmoteModelBiped(inflation((ModelBiped) model)));
			n++;
		}
		return n;
	}

	/** Aufblasung eines Modells aus seinem Kopf-Würfel (Vanilla: −4 − Größe). */
	static float inflation(ModelBiped model) {
		List<ModelBox> boxes = model.bipedHead.cubeList;
		if (boxes == null || boxes.isEmpty()) return 0f;
		return -4f - boxes.get(0).posX1;
	}

	// --- Pose ---

	/** Vor Vanilla: Ruhehaltung eines schon veränderten Modells wiederherstellen. */
	static void before(ModelBiped model) {
		if (REST.isEmpty()) return;
		float[] rest = REST.get(model);
		if (rest != null) write(model, rest);
	}

	/**
	 * Nach Vanilla: Emote-Pose anlegen. {@code ageInTicks} 0 = Hand in der Ich-Perspektive – dort bleibt alles
	 * Vanilla. Liefert true, wenn die Pose geändert wurde (Überzüge dann neu kopieren).
	 */
	static boolean after(ModelBiped model, Entity entity, float ageInTicks) {
		if (!(entity instanceof EntityPlayer) || ageInTicks == 0f) return false;
		EmoteController c = emotes();
		if (c == null || !c.animating(entity.getUniqueID())) return false;
		try {
			read(model, PARTS);
			if (!REST.containsKey(model)) REST.put(model, PARTS.clone());
			if (!c.apply(entity.getUniqueID(), System.currentTimeMillis(), PARTS)) return false;
			write(model, PARTS);
			ModelBase.copyModelAngles(model.bipedHead, model.bipedHeadwear);
			return true;
		} catch (RuntimeException e) {
			report(e);
			return false;
		}
	}

	private static ModelRenderer part(ModelBiped m, int i) {
		switch (i) {
			case EmoteRig.HEAD:
				return m.bipedHead;
			case EmoteRig.BODY:
				return m.bipedBody;
			case EmoteRig.RIGHT_ARM:
				return m.bipedRightArm;
			case EmoteRig.LEFT_ARM:
				return m.bipedLeftArm;
			case EmoteRig.RIGHT_LEG:
				return m.bipedRightLeg;
			default:
				return m.bipedLeftLeg;
		}
	}

	private static void read(ModelBiped m, float[] out) {
		for (int i = 0; i < EmoteRig.PARTS; i++) {
			ModelRenderer p = part(m, i);
			int o = i * EmoteRig.STRIDE;
			out[o] = p.rotationPointX;
			out[o + 1] = p.rotationPointY;
			out[o + 2] = p.rotationPointZ;
			out[o + 3] = p.rotateAngleX;
			out[o + 4] = p.rotateAngleY;
			out[o + 5] = p.rotateAngleZ;
		}
	}

	private static void write(ModelBiped m, float[] in) {
		for (int i = 0; i < EmoteRig.PARTS; i++) {
			ModelRenderer p = part(m, i);
			int o = i * EmoteRig.STRIDE;
			p.rotationPointX = in[o];
			p.rotationPointY = in[o + 1];
			p.rotationPointZ = in[o + 2];
			p.rotateAngleX = in[o + 3];
			p.rotateAngleY = in[o + 4];
			p.rotateAngleZ = in[o + 5];
		}
	}
}
