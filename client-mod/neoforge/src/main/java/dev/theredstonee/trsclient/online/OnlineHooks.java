package dev.theredstonee.trsclient.online;

import com.mojang.blaze3d.platform.NativeImage;
import dev.theredstonee.trsclient.core.cape.CapePhysics;
import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.cape.ClothMesh;
import dev.theredstonee.trsclient.core.cape.ClothSim;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.GameSession;
import dev.theredstonee.trsclient.core.online.OnlineFeatures;
import dev.theredstonee.trsclient.core.online.OnlinePlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.Items;
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
//?}
//? if >=1.16 {
import net.minecraft.ChatFormatting;
//?}

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * TRS-Online-Funktionen im Spiel (Abzeichen, TRS-Umhänge, Umhang-Physik): alles, was Minecraft-Klassen
 * braucht, aber vom Loader unabhängig ist. Die Logik steckt in {@code core.online}/{@code core.cape};
 * hier nur Konto, Spieler-Liste, Texturen und Zeichnen je Minecraft-Version.
 */
public final class OnlineHooks {
	/** Zeichen des Abzeichens in der eigenen Schrift {@code trsclient:badge} (Privatbereich). */
	public static final String GLYPH = "\ue2a5";
	/** Ersatz ohne eigene Schrift (≤ 1.15): dunkelrotes Quadrat aus der Standardschrift. */
	public static final String LEGACY_BADGE = "\u00a74\u25a0\u00a7r ";

	private static OnlineFeatures<Object> features;
	private static TrsModules modules;

	private OnlineHooks() {
	}

	/** Beim Start (Konfig-Ordner des Spiels). */
	public static void init(Path configDir, TrsModules trsModules, String modVersion, String minecraftVersion, String loader,
			Consumer<String> log) {
		modules = trsModules;
		features = OnlineFeatures.create(trsModules, configDir, new Platform(minecraftVersion, loader, log), modVersion,
				new Textures());
	}

	public static OnlineFeatures<Object> features() {
		return features;
	}

	// --- Tick ---

	/** Einmal pro Client-Tick. */
	public static void tick(Minecraft mc) {
		if (features == null) return;
		List<UUID> visible = new ArrayList<>();
		List<CapePhysics.Sample> samples = new ArrayList<>();
		try {
			if (mc.getConnection() != null) {
				for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
					//? if >=1.21.9 {
					/*UUID id = info.getProfile().id();
					*///?} else
					UUID id = info.getProfile().getId();
					if (id != null) visible.add(id);
				}
			}
			if (mc.level != null && mc.player != null) {
				for (AbstractClientPlayer p : mc.level.players()) {
					visible.add(p.getUUID());
					//? if >=1.15 {
					samples.add(sample(mc, p));
					//?}
				}
			}
		} catch (RuntimeException e) {
			// Liste wird gerade umgebaut (Welt wechselt) – nächster Tick.
			visible = Collections.emptyList();
			samples = Collections.emptyList();
		}
		features.tick(visible, samples);
	}

	//? if >=1.15 {
	private static CapePhysics.Sample sample(Minecraft mc, AbstractClientPlayer p) {
		CapePhysics.Sample s = new CapePhysics.Sample();
		s.id = p.getId();
		s.x = p.getX();
		s.y = p.getY();
		s.z = p.getZ();
		s.bodyYaw = p.yBodyRot;
		s.crouching = p.isCrouching();
		s.self = p == mc.player;
		s.distanceSq = p.distanceToSqr(mc.player);
		s.hasCape = hasCapeVisible(p);
		s.special = p.isFallFlying() || p.isVisuallySwimming() || p.isSleeping() || p.isInvisible() || p.isPassenger()
				|| p.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
		return s;
	}
	//?}

	/** Würde Vanilla (mit TRS-Textur) für diesen Spieler einen Umhang zeichnen (Textur da, Umhang-Teil an)? */
	public static boolean hasCapeVisible(AbstractClientPlayer p) {
		if (!p.isModelPartShown(PlayerModelPart.CAPE)) return false;
		//? if >=1.21.9 {
		/*return p.getSkin().cape() != null;
		*///?} elif >=1.20.2 {
		return p.getSkin().capeTexture() != null;
		//?} else
		/*return p.isCapeLoaded() && p.getCloakTextureLocation() != null;*/
	}

	// --- Texturen / Abzeichen (Aufrufe aus den Mixins) ---

	/** TRS-Umhang-Textur (aktuelles Bild) oder null → Vanilla/OptiFine. */
	public static Object capeTexture(AbstractClientPlayer player) {
		if (features == null || player == null) return null;
		return features.capeTexture(player.getUUID());
	}

	public static boolean badge(UUID uuid, boolean tab) {
		return features != null && uuid != null && features.badge(uuid, tab);
	}

	//? if >=1.16 {
	/** Name mit TRS-Abzeichen davor (eigene Pixel-Schrift, weiß, dann ein Leerzeichen). */
	public static Component badged(Component name) {
		if (name == null) return null;
		//? if >=1.21.9 {
		/*net.minecraft.network.chat.FontDescription font = new net.minecraft.network.chat.FontDescription.Resource(id("badge"));
		*///?} else
		net.minecraft.resources.ResourceLocation font = id("badge");
		return text("").append(text(GLYPH).withStyle(style -> style.withFont(font).withColor(ChatFormatting.WHITE)))
				.append(text(" ")).append(name);
	}
	//?} else {
	/*public static Component badged(Component name) {
		if (name == null) return null;
		return new net.minecraft.network.chat.TextComponent(LEGACY_BADGE).append(name);
	}
	*///?}

	/** Text-Komponente (ab 1.19 Component.literal, davor TextComponent). */
	//? if >=1.19 {
	public static net.minecraft.network.chat.MutableComponent text(String text) {
		return Component.literal(text);
	}
	//?} elif >=1.16 {
	/*public static net.minecraft.network.chat.MutableComponent text(String text) {
		return new net.minecraft.network.chat.TextComponent(text);
	}
	*///?} else {
	/*public static Component text(String text) {
		return new net.minecraft.network.chat.TextComponent(text);
	}
	*///?}

	/** Für Namensschilder als Text (1.14/1.15). */
	public static String badged(String name) {
		return name == null ? null : LEGACY_BADGE + name;
	}

	// --- Umhang zeichnen ---

	//? if >=1.15 {
	/**
	 * Simulation für diese Entity (null = Vanilla zeichnen lassen). Bis 1.21.1 noch ohne Render-State:
	 * die Vanilla-Prüfungen (unsichtbar, Umhang-Teil aus, Elytra) macht der Aufrufer.
	 */
	public static ClothSim sim(int entityId) {
		return features == null ? null : features.sim(entityId);
	}

	/**
	 * Zeichnet den simulierten Umhang. {@code body} = Oberkörper-Teil des Modells (bereits für diesen
	 * Spieler posiert, Schleichen inklusive), {@code texture} = Textur wie Vanilla sie nehmen würde.
	 */
	//? if >=1.21.11 {
	/*public static void render(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			Object texture, ClothSim sim, ModelPart body, boolean armor) {
		float partial = features.physics().partial();
		float zOff = zOffset(armor);
		pose.pushPose();
		body.translateAndRotate(pose);
		collector.submitCustomGeometry(pose, net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(
				(net.minecraft.resources.Identifier) texture), (last, vc) ->
				features.mesh().emit(sim, partial, sink(vc, last, light, zOff)));
		pose.popPose();
	}
	*///?} elif >=1.21.9 {
	/*public static void render(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
			Object texture, ClothSim sim, ModelPart body, boolean armor) {
		float partial = features.physics().partial();
		float zOff = zOffset(armor);
		pose.pushPose();
		body.translateAndRotate(pose);
		collector.submitCustomGeometry(pose, net.minecraft.client.renderer.RenderType.entitySolid(
				(net.minecraft.resources.ResourceLocation) texture), (last, vc) ->
				features.mesh().emit(sim, partial, sink(vc, last, light, zOff)));
		pose.popPose();
	}
	*///?} else {
	public static void render(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
			Object texture, ClothSim sim, ModelPart body, boolean armor) {
		float partial = features.physics().partial();
		VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entitySolid(
				(net.minecraft.resources.ResourceLocation) texture));
		pose.pushPose();
		body.translateAndRotate(pose);
		features.mesh().emit(sim, partial, sink(vc, pose.last(), light, zOffset(armor)));
		pose.popPose();
	}
	//?}

	/** Abstand des Umhangs hinter der Körpermitte (2 Pixel, mit Brustpanzer einen mehr). */
	private static float zOffset(boolean armor) {
		return (armor ? 3.1f : 2f) / 16f;
	}

	private static ClothMesh.QuadSink sink(VertexConsumer vc, PoseStack.Pose last, int light, float zOff) {
		int overlay = OverlayTexture.NO_OVERLAY;
		return (x, y, z, u, v, nx, ny, nz) -> {
			//? if >=1.21 {
			vc.addVertex(last, x, y, z + zOff).setColor(-1).setUv(u, v).setOverlay(overlay).setLight(light)
					.setNormal(last, nx, ny, nz);
			//?} elif >=1.20.5 {
			/*vc.vertex(last, x, y, z + zOff).color(255, 255, 255, 255).uv(u, v).overlayCoords(overlay).uv2(light)
					.normal(last, nx, ny, nz).endVertex();
			*///?} else {
			/*vc.vertex(last.pose(), x, y, z + zOff).color(255, 255, 255, 255).uv(u, v).overlayCoords(overlay).uv2(light)
					.normal(last.normal(), nx, ny, nz).endVertex();
			*///?}
		};
	}
	//?}

	/** Trägt der Spieler etwas auf der Brust, das den Umhang nach hinten drückt (Brustpanzer)? */
	public static boolean chestArmor(AbstractClientPlayer p) {
		return !p.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
	}

	// --- Hilfen ---

	//? if >=1.21.11 {
	/*public static net.minecraft.resources.Identifier id(String path) {
		return net.minecraft.resources.Identifier.fromNamespaceAndPath("trsclient", path);
	}
	*///?} elif >=1.21 {
	public static net.minecraft.resources.ResourceLocation id(String path) {
		return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("trsclient", path);
	}
	//?} else {
	/*public static net.minecraft.resources.ResourceLocation id(String path) {
		return new net.minecraft.resources.ResourceLocation("trsclient", path);
	}
	*///?}

	/** Umhang-Bilder als dynamische Texturen ({@code trsclient:capes/<id>_<n>/<bild>}). */
	static final class Textures implements CapeTextures.Backend<Object> {
		@Override
		public Object upload(String name, int width, int height, int[] argb) {
			try {
				NativeImage img = new NativeImage(width, height, false);
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int c = argb[y * width + x];
						//? if >=1.21.2 {
						/*img.setPixel(x, y, c);
						*///?} else
						img.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16));
					}
				}
				//? if >=1.21.5 {
				/*DynamicTexture texture = new DynamicTexture(() -> "trsclient " + name, img);
				*///?} else
				DynamicTexture texture = new DynamicTexture(img);
				Object id = id(name);
				//? if >=1.21.11 {
				/*Minecraft.getInstance().getTextureManager().register((net.minecraft.resources.Identifier) id, texture);
				*///?} else
				Minecraft.getInstance().getTextureManager().register((net.minecraft.resources.ResourceLocation) id, texture);
				return id;
			} catch (RuntimeException e) {
				return null;
			}
		}

		@Override
		public void release(Object texture) {
			//? if >=1.21.11 {
			/*Minecraft.getInstance().getTextureManager().release((net.minecraft.resources.Identifier) texture);
			*///?} else
			Minecraft.getInstance().getTextureManager().release((net.minecraft.resources.ResourceLocation) texture);
		}
	}

	/** Konto, Server und Version für die TRS API. */
	static final class Platform implements OnlinePlatform {
		private final String minecraftVersion;
		private final String loader;
		private final Consumer<String> log;

		Platform(String minecraftVersion, String loader, Consumer<String> log) {
			this.minecraftVersion = minecraftVersion;
			this.loader = loader;
			this.log = log;
		}

		@Override
		public GameSession session() {
			User user = Minecraft.getInstance().getUser();
			if (user == null) return null;
			//? if >=1.20.2 {
			String uuid = user.getProfileId() == null ? null : user.getProfileId().toString();
			//?} else
			/*String uuid = user.getUuid();*/
			return new GameSession(uuid, user.getName(), user.getAccessToken());
		}

		@Override
		public String minecraftVersion() {
			return minecraftVersion;
		}

		@Override
		public String loader() {
			return loader;
		}

		@Override
		public String serverAddress() {
			Minecraft mc = Minecraft.getInstance();
			if (mc.isLocalServer()) return null;
			ServerData data = mc.getCurrentServer();
			return data == null ? null : data.ip;
		}

		@Override
		public void log(String message) {
			log.accept(message);
		}
	}
}
