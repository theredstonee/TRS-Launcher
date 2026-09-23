package dev.theredstonee.trsclient.online;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cape.CapePhysics;
import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.cape.ClothSim;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.GameSession;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.online.OnlinePlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
//? if >=1.9 {
/*import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
*///?} else {
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
//?}

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * TRS-Online-Funktionen für Forge 1.8.9–1.12.2 (ohne Mixins): Anmeldung/Lookup/Presence laufen wie überall in
 * {@code core.online}. Der TRS-Umhang wird in die Textur-Einträge des Tablisten-Eintrags ({@link NetworkPlayerInfo})
 * geschrieben (Umhang und – ab 1.9 – Elytra nehmen ihn dann von selbst), die Abzeichen kommen über den
 * Anzeigenamen der Tabliste und {@code PlayerEvent.NameFormat}, und die Umhang-Physik ersetzt den Vanilla-
 * {@link LayerCape} durch {@link ClothCapeLayer}. Private Felder werden über ihren Typ gefunden (keine Namen –
 * die sind im fertigen Spiel SRG-Namen).
 */
public final class LegacyOnline {
	/** Abzeichen als Text (die Schrift von 1.8–1.12 kennt keine eigenen Zeichen): dunkelrotes Quadrat. */
	public static final String BADGE = "\u00a74\u25a0";

	private static OnlineFeatures<Object> features;
	private static boolean layersInstalled;
	/** Vom Mod veränderte Tablisten-Einträge: was vorher drin war, um es zurückzusetzen. */
	private static final Map<NetworkPlayerInfo, CapeOverride> capes = new WeakHashMap<>();
	private static final Map<NetworkPlayerInfo, NameOverride> names = new WeakHashMap<>();
	/** Letzter Abzeichen-Zustand je Spieler (Namensschild): Wechsel → Anzeigenamen neu berechnen lassen. */
	private static final Map<UUID, Boolean> nameBadges = new HashMap<>();

	private LegacyOnline() {
	}

	public static void init(Path configDir, TrsModules modules, String modVersion, String minecraftVersion,
			Consumer<String> log) {
		features = OnlineFeatures.create(modules, configDir, new Platform(minecraftVersion, log), modVersion,
				new Textures());
	}

	public static OnlineFeatures<Object> features() {
		return features;
	}

	// --- Tick ---

	public static void tick(Minecraft mc) {
		if (features == null) return;
		try {
			tickSafe(mc);
		} catch (RuntimeException e) {
			// Nichts hiervon darf je das Spiel abstürzen lassen.
			features.online().reportError(e);
		}
	}

	private static void tickSafe(Minecraft mc) {
		if (!layersInstalled && mc.getRenderManager() != null) installLayers(mc);
		List<UUID> visible = new ArrayList<>();
		List<CapePhysics.Sample> samples = new ArrayList<>();
		Collection<NetworkPlayerInfo> infos = Collections.emptyList();
		try {
			NetHandlerPlayClient net = Mc.connection();
			if (net != null) infos = new ArrayList<>(net.getPlayerInfoMap());
			for (NetworkPlayerInfo info : infos) {
				UUID id = info.getGameProfile().getId();
				if (id != null) visible.add(id);
			}
			EntityPlayer self = Mc.player();
			if (Mc.world() != null && self != null) {
				for (EntityPlayer p : new ArrayList<>(Mc.world().playerEntities)) {
					visible.add(p.getUniqueID());
					if (p instanceof AbstractClientPlayer) samples.add(sample((AbstractClientPlayer) p, self));
				}
			}
		} catch (RuntimeException e) {
			visible = Collections.emptyList();
			samples = Collections.emptyList();
		}
		features.tick(visible, samples);
		for (NetworkPlayerInfo info : infos) {
			try {
				applyCape(info);
				applyTabName(info);
			} catch (IllegalAccessException e) {
				features.online().reportError(new IllegalStateException(e));
			} catch (RuntimeException e) {
				features.online().reportError(e);
			}
		}
		refreshNameTags();
	}

	private static CapePhysics.Sample sample(AbstractClientPlayer p, EntityPlayer self) {
		CapePhysics.Sample s = new CapePhysics.Sample();
		s.id = p.getEntityId();
		s.x = p.posX;
		s.y = p.posY;
		s.z = p.posZ;
		s.bodyYaw = p.renderYawOffset;
		s.crouching = p.isSneaking();
		s.self = p == self;
		s.distanceSq = p.getDistanceSq(self.posX, self.posY, self.posZ);
		s.hasCape = p.hasPlayerInfo() && p.isWearing(EnumPlayerModelParts.CAPE) && p.getLocationCape() != null;
		s.special = p.isInvisible() || p.isPlayerSleeping() || p.isRiding() || elytra(p);
		//? if >=1.9
		/*s.special |= p.isElytraFlying();*/
		return s;
	}

	/** Trägt der Spieler eine Elytra (dann zeichnet Vanilla keinen Umhang)? */
	static boolean elytra(EntityPlayer p) {
		ItemStack chest = Mc.equipment(p, 1);
		return chest != null && chest.getItem() == Mc.item("elytra");
	}

	/** Etwas auf der Brust (Brustpanzer) → Umhang ein Stück weiter hinten. */
	static boolean chestArmor(EntityPlayer p) {
		return Mc.equipment(p, 1) != null;
	}

	/** Umhang-Simulation einer Entity (null = Vanilla-Umhang zeichnen). */
	public static ClothSim sim(int entityId) {
		return features == null ? null : features.sim(entityId);
	}

	// --- TRS-Umhang: Textur in den Tablisten-Eintrag schreiben ---

	private static final class CapeOverride {
		ResourceLocation ours;
		ResourceLocation originalCape;
		ResourceLocation originalElytra;
	}

	private static void applyCape(NetworkPlayerInfo info) throws IllegalAccessException {
		UUID id = info.getGameProfile().getId();
		Object tex = id == null ? null : features.capeTexture(id);
		CapeOverride state = capes.get(info);
		ResourceLocation current = readCape(info);
		if (state != null && current != state.ours) {
			// Vanilla hat inzwischen seinen eigenen Umhang geladen → das ist das neue Original.
			state.originalCape = current;
			state.originalElytra = readElytra(info);
		}
		if (tex instanceof ResourceLocation) {
			if (state == null) {
				state = new CapeOverride();
				state.originalCape = current;
				state.originalElytra = readElytra(info);
				capes.put(info, state);
			}
			state.ours = (ResourceLocation) tex;
			writeCape(info, state.ours, state.ours);
		} else if (state != null) {
			writeCape(info, state.originalCape, state.originalElytra);
			capes.remove(info);
		}
	}

	//? if >=1.9 {
	/*private static Field texturesField;

	@SuppressWarnings("unchecked")
	private static Map<MinecraftProfileTexture.Type, ResourceLocation> textures(NetworkPlayerInfo info) throws IllegalAccessException {
		if (texturesField == null) texturesField = field(NetworkPlayerInfo.class, Map.class, 0);
		return (Map<MinecraftProfileTexture.Type, ResourceLocation>) texturesField.get(info);
	}

	private static ResourceLocation readCape(NetworkPlayerInfo info) throws IllegalAccessException {
		return textures(info).get(MinecraftProfileTexture.Type.CAPE);
	}

	private static ResourceLocation readElytra(NetworkPlayerInfo info) throws IllegalAccessException {
		return textures(info).get(MinecraftProfileTexture.Type.ELYTRA);
	}

	private static void writeCape(NetworkPlayerInfo info, ResourceLocation cape, ResourceLocation elytra) throws IllegalAccessException {
		Map<MinecraftProfileTexture.Type, ResourceLocation> map = textures(info);
		if (cape == null) map.remove(MinecraftProfileTexture.Type.CAPE);
		else map.put(MinecraftProfileTexture.Type.CAPE, cape);
		if (elytra == null) map.remove(MinecraftProfileTexture.Type.ELYTRA);
		else map.put(MinecraftProfileTexture.Type.ELYTRA, elytra);
	}
	*///?} else {
	private static Field capeField;

	/** 1.8.9: zweites ResourceLocation-Feld (locationSkin, locationCape). */
	private static Field capeField() {
		if (capeField == null) capeField = field(NetworkPlayerInfo.class, ResourceLocation.class, 1);
		return capeField;
	}

	private static ResourceLocation readCape(NetworkPlayerInfo info) throws IllegalAccessException {
		return (ResourceLocation) capeField().get(info);
	}

	private static ResourceLocation readElytra(NetworkPlayerInfo info) {
		return null;
	}

	private static void writeCape(NetworkPlayerInfo info, ResourceLocation cape, ResourceLocation elytra) throws IllegalAccessException {
		capeField().set(info, cape);
	}
	//?}

	/** {@code index}-tes deklarierte Feld dieses Typs (Reihenfolge wie im Quelltext – bleibt beim Umbenennen gleich). */
	static Field field(Class<?> owner, Class<?> type, int index) {
		int n = 0;
		for (Field f : owner.getDeclaredFields()) {
			if (f.getType() != type || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
			if (n++ == index) {
				f.setAccessible(true);
				return f;
			}
		}
		throw new IllegalStateException("Feld " + type.getSimpleName() + "#" + index + " fehlt in " + owner.getName());
	}

	// --- Abzeichen: Tabliste (Anzeigename des Eintrags) ---

	private static final class NameOverride {
		Object ours;
		Object original;
		String text;
	}

	private static void applyTabName(NetworkPlayerInfo info) {
		UUID id = info.getGameProfile().getId();
		boolean badge = id != null && features.badge(id, true);
		NameOverride state = names.get(info);
		Object current = info.getDisplayName();
		if (state != null && current != state.ours) {
			// Der Server hat einen eigenen Anzeigenamen gesetzt → neues Original.
			state.original = current;
			state.ours = null;
		}
		if (badge) {
			if (state == null) {
				state = new NameOverride();
				state.original = current;
				names.put(info, state);
			}
			String base = state.original != null ? formatted(state.original)
					: ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), info.getGameProfile().getName());
			String text = BADGE + "\u00a7r " + base;
			if (state.ours == null || !text.equals(state.text)) {
				state.ours = component(text);
				state.text = text;
				setDisplayName(info, state.ours);
			}
		} else if (state != null) {
			if (state.ours != null && current == state.ours) setDisplayName(info, state.original);
			names.remove(info);
		}
	}

	//? if >=1.9 {
	/*private static Object component(String text) {
		return new TextComponentString(text);
	}

	private static String formatted(Object component) {
		return ((ITextComponent) component).getFormattedText();
	}

	private static void setDisplayName(NetworkPlayerInfo info, Object component) {
		info.setDisplayName((ITextComponent) component);
	}
	*///?} else {
	private static Object component(String text) {
		return new ChatComponentText(text);
	}

	private static String formatted(Object component) {
		return ((IChatComponent) component).getFormattedText();
	}

	private static void setDisplayName(NetworkPlayerInfo info, Object component) {
		info.setDisplayName((IChatComponent) component);
	}
	//?}

	// --- Abzeichen: Namensschild (PlayerEvent.NameFormat) ---

	/** Hat sich der Abzeichen-Zustand eines Spielers geändert, Anzeigenamen neu berechnen lassen. */
	private static void refreshNameTags() {
		if (Mc.world() == null) {
			nameBadges.clear();
			return;
		}
		for (EntityPlayer p : new ArrayList<>(Mc.world().playerEntities)) {
			boolean badge = features.badge(p.getUniqueID(), false);
			Boolean before = nameBadges.put(p.getUniqueID(), badge);
			if (before == null ? badge : before != badge) p.refreshDisplayName();
		}
	}

	/** Forge-Ereignis: Anzeigename eines Spielers (Namensschild über dem Kopf). */
	public static final class NameTags {
		@SubscribeEvent
		public void onNameFormat(PlayerEvent.NameFormat event) {
			if (features == null) return;
			//? if >=1.9 {
			/*EntityPlayer player = event.getEntityPlayer();
			String name = event.getDisplayname();
			*///?} else {
			EntityPlayer player = event.entityPlayer;
			String name = event.displayname;
			//?}
			if (player == null || name == null || !features.badge(player.getUniqueID(), false)) return;
			// Das Team setzt Präfix/Suffix außen herum – dessen Farbe nach dem Abzeichen wiederholen.
			String prefix = ScorePlayerTeam.formatPlayerName(player.getTeam(), "\u0000");
			int cut = prefix.indexOf('\u0000');
			String color = FontRenderer.getFormatFromString(cut > 0 ? prefix.substring(0, cut) : "");
			String result = BADGE + " " + (color.isEmpty() ? "\u00a7r" : color) + name;
			//? if >=1.9 {
			/*event.setDisplayname(result);
			*///?} else
			event.displayname = result;
		}
	}

	// --- Umhang-Physik: LayerCape der Spieler-Renderer ersetzen ---

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void installLayers(Minecraft mc) {
		layersInstalled = true;
		try {
			//? if >=1.9 {
			/*Field list = field(RenderLivingBase.class, List.class, 0);
			*///?} else
			Field list = field(RendererLivingEntity.class, List.class, 0);
			int replaced = 0;
			for (RenderPlayer renderer : mc.getRenderManager().getSkinMap().values()) {
				List<LayerRenderer> layers = (List<LayerRenderer>) list.get(renderer);
				for (int i = 0; i < layers.size(); i++) {
					if (layers.get(i) instanceof LayerCape) {
						layers.set(i, new ClothCapeLayer(renderer, (LayerCape) layers.get(i)));
						replaced++;
					}
				}
			}
			log("Umhang-Physik: " + replaced + " Umhang-Ebenen ersetzt");
		} catch (IllegalAccessException | RuntimeException e) {
			log("Umhang-Physik nicht verfügbar: " + e);
		}
	}

	private static Consumer<String> logger = s -> { };

	private static void log(String message) {
		logger.accept(message);
	}

	// --- Texturen / Konto ---

	/** Umhang-Bilder als dynamische Texturen ({@code trsclient:capes/<id>_<n>/<bild>}). */
	static final class Textures implements CapeTextures.Backend<Object> {
		@Override
		public Object upload(String name, int width, int height, int[] argb) {
			try {
				DynamicTexture texture = new DynamicTexture(width, height);
				System.arraycopy(argb, 0, texture.getTextureData(), 0, width * height);
				texture.updateDynamicTexture();
				ResourceLocation id = new ResourceLocation("trsclient", name);
				Minecraft.getMinecraft().getTextureManager().loadTexture(id, texture);
				return id;
			} catch (RuntimeException e) {
				return null;
			}
		}

		@Override
		public void release(Object texture) {
			Minecraft.getMinecraft().getTextureManager().deleteTexture((ResourceLocation) texture);
		}
	}

	static final class Platform implements OnlinePlatform {
		private final String minecraftVersion;
		private final Consumer<String> log;

		Platform(String minecraftVersion, Consumer<String> log) {
			this.minecraftVersion = minecraftVersion;
			this.log = log;
			logger = log;
		}

		@Override
		public GameSession session() {
			net.minecraft.util.Session s = Minecraft.getMinecraft().getSession();
			if (s == null) return null;
			return new GameSession(s.getPlayerID(), s.getUsername(), s.getToken());
		}

		@Override
		public String minecraftVersion() {
			return minecraftVersion;
		}

		@Override
		public String loader() {
			return "forge";
		}

		@Override
		public String serverAddress() {
			if (Minecraft.getMinecraft().isSingleplayer()) return null;
			return Mc.serverAddress();
		}

		@Override
		public void log(String message) {
			log.accept(message);
		}
	}
}
