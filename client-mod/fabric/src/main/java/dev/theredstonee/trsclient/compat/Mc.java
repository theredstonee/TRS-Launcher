package dev.theredstonee.trsclient.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
//? if >=1.20.5 {
import net.minecraft.core.Holder;
//?}
//? if >=1.18.2 {
import net.minecraft.world.level.biome.Biome;
//?}
//? if >=1.19 {
import net.minecraft.network.chat.MutableComponent;
//?} elif >=1.16 {
/*import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
*///?} else
/*import net.minecraft.network.chat.TextComponent;*/
//? if >=1.16 {
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.CameraType;
//?} else
/*import net.minecraft.client.AttackIndicatorStatus;*/
//? if >=1.16 && <1.18.2 {
/*import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
*///?}

/**
 * Zugriffe auf Minecraft, die zwischen den Versionen umgezogen sind.
 * Ab 26.2 verwaltet {@code Minecraft.gui} Bildschirme/Overlays und {@code gui.hud} das HUD;
 * bis 1.18 sind viele Optionen noch einfache Felder, bis 1.16 gibt es kein CameraType usw.
 */
public final class Mc {
	private Mc() {
	}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static Window window() {
		//? if >=1.15 {
		return mc().getWindow();
		//?} else
		/*return mc().window;*/
	}

	/** Aktuell offener Bildschirm (null = im Spiel). */
	public static Screen screen() {
		//? if >=26.2 {
		/*return mc().gui.screen();
		*///?} else
		return mc().screen;
	}

	public static void setScreen(Screen screen) {
		//? if >=26.2 {
		/*mc().gui.setScreen(screen);
		*///?} else
		mc().setScreen(screen);
	}

	/** Lade-Overlay (Ressourcen werden geladen), sonst null. */
	public static Overlay overlay() {
		//? if >=26.2 {
		/*return mc().gui.overlay();
		*///?} else
		return mc().getOverlay();
	}

	/** HUD per F1 ausgeblendet? */
	public static boolean hudHidden() {
		//? if >=26.2 {
		/*return mc().gui.hud.isHidden();
		*///?} else
		return mc().options.hideGui;
	}

	/** Kurzer Hinweis über der Hotbar. */
	public static void actionBar(Component message) {
		//? if >=26.2 {
		/*mc().gui.hud.setOverlayMessage(message, false);
		*///?} else
		mc().gui.setOverlayMessage(message, false);
	}

	public static RenderTarget mainRenderTarget() {
		//? if >=26.2 {
		/*return mc().gameRenderer.mainRenderTarget();
		*///?} else
		return mc().getMainRenderTarget();
	}

	/** Klartext-Komponente (Component.literal ab 1.19, davor TextComponent). */
	//? if >=1.19 {
	public static MutableComponent text(String text) {
		return Component.literal(text);
	}
	//?} elif >=1.16 {
	/*public static MutableComponent text(String text) {
		return new TextComponent(text);
	}
	*///?} else {
	/*public static Component text(String text) {
		return new TextComponent(text);
	}
	*///?}

	/** Übersetzt einen Sprachschlüssel. */
	public static String translated(String key) {
		//? if >=1.19 {
		return Component.translatable(key).getString();
		//?} else
		/*return new net.minecraft.network.chat.TranslatableComponent(key).getString();*/
	}

	/** Übersetzter Anzeigename einer Tastenbelegung. */
	public static String keyName(KeyMapping mapping) {
		//? if >=1.16 {
		return mapping.getTranslatedKeyMessage().getString();
		//?} else
		/*return mapping.getTranslatedKeyMessage();*/
	}

	/** Bilder pro Sekunde. */
	public static int fps() {
		//? if >=1.19.3 {
		return mc().getFps();
		//?} else {
		/*// Bis 1.19.2 nur als Text ("60 fps T: …").
		String s = mc().fpsString;
		int end = s == null ? -1 : s.indexOf(' ');
		if (end <= 0) return 0;
		try {
			return Integer.parseInt(s.substring(0, end));
		} catch (NumberFormatException e) {
			return 0;
		}
		*///?}
	}

	// --- Spieler / Entities ---

	public static float yRot(Entity e) {
		//? if >=1.17 {
		return e.getYRot();
		//?} else
		/*return e.yRot;*/
	}

	public static float xRot(Entity e) {
		//? if >=1.17 {
		return e.getXRot();
		//?} else
		/*return e.xRot;*/
	}

	public static double x(Entity e) {
		//? if >=1.15 {
		return e.getX();
		//?} else
		/*return e.x;*/
	}

	public static double y(Entity e) {
		//? if >=1.15 {
		return e.getY();
		//?} else
		/*return e.y;*/
	}

	public static double z(Entity e) {
		//? if >=1.15 {
		return e.getZ();
		//?} else
		/*return e.z;*/
	}

	/** Der Effekt einer Effekt-Instanz (ab 1.20.5 als Holder verpackt). */
	public static MobEffect effect(MobEffectInstance instance) {
		//? if >=1.20.5 {
		return instance.getEffect().value();
		//?} else
		/*return instance.getEffect();*/
	}

	/** Unendliche Effektdauer (gibt es erst ab 1.19.4). */
	public static boolean infinite(MobEffectInstance instance) {
		//? if >=1.19.4 {
		return instance.isInfiniteDuration();
		//?} else
		/*return false;*/
	}

	/** Übersetzter Name des Bioms, in dem der Spieler steht (z. B. "Ebene"). */
	public static String biomeName(Player p) {
		if (mc().level == null) return "?";
		//? if >=1.18.2 {
		return mc().level.getBiome(p.blockPosition()).unwrapKey().map(key -> {
			//? if >=1.21.11 {
			/*net.minecraft.resources.Identifier id = key.identifier();
			*///?} else
			net.minecraft.resources.ResourceLocation id = key.location();
			return translated("biome." + id.getNamespace() + "." + id.getPath());
		}).orElse("?");
		//?} elif >=1.16 {
		/*Biome biome = mc().level.getBiome(p.blockPosition());
		ResourceLocation id = mc().level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY).getKey(biome);
		if (id == null) return "?";
		return translated("biome." + id.getNamespace() + "." + id.getPath());
		*///?} else
		/*return mc().level.getBiome(p.getCommandSenderBlockPosition()).getName().getString();*/
	}

	// --- Optionen ---

	/** Taste "Schleichen" (bis 1.14 keySneak, danach keyShift). */
	public static KeyMapping sneakKey() {
		//? if >=1.15 {
		return mc().options.keyShift;
		//?} else
		/*return mc().options.keySneak;*/
	}

	/** Vanillas eigenes Umschalt-Sprinten aktiv? (gibt es erst ab 1.15) */
	public static boolean vanillaToggleSprint() {
		//? if >=1.19 {
		return mc().options.toggleSprint().get();
		//?} elif >=1.15 {
		/*return mc().options.toggleSprint;
		*///?} else
		/*return false;*/
	}

	/** Vanillas eigenes Umschalt-Schleichen aktiv? (gibt es erst ab 1.15) */
	public static boolean vanillaToggleCrouch() {
		//? if >=1.19 {
		return mc().options.toggleCrouch().get();
		//?} elif >=1.15 {
		/*return mc().options.toggleCrouch;
		*///?} else
		/*return false;*/
	}

	/** Angriffs-Abklinganzeige am Fadenkreuz eingestellt? */
	public static boolean attackIndicatorOnCrosshair() {
		//? if >=1.19 {
		return mc().options.attackIndicator().get() == AttackIndicatorStatus.CROSSHAIR;
		//?} else
		/*return mc().options.attackIndicator == AttackIndicatorStatus.CROSSHAIR;*/
	}

	/** Perspektive: 0 = Ego, 1 = von hinten, 2 = von vorne. */
	public static int cameraMode() {
		//? if >=1.16 {
		return mc().options.getCameraType().ordinal();
		//?} else
		/*return mc().options.thirdPersonView;*/
	}

	public static void setCameraMode(int mode) {
		//? if >=1.16 {
		mc().options.setCameraType(CameraType.values()[mode]);
		//?} else
		/*mc().options.thirdPersonView = mode;*/
	}

	// --- Kamera (Wegpunkt-Markierungen) ---

	/** Kamera des laufenden Frames. */
	public static net.minecraft.client.Camera camera() {
		//? if >=26.2 {
		/*return mc().gameRenderer.mainCamera();
		*///?} else
		return mc().gameRenderer.getMainCamera();
	}

	public static double cameraX() {
		return cameraPos().x;
	}

	public static double cameraY() {
		return cameraPos().y;
	}

	public static double cameraZ() {
		return cameraPos().z;
	}

	private static net.minecraft.world.phys.Vec3 cameraPos() {
		//? if >=1.21.11 {
		/*return camera().position();
		*///?} else
		return camera().getPosition();
	}

	public static float cameraYaw() {
		//? if >=1.21.11 {
		/*return camera().yRot();
		*///?} else
		return camera().getYRot();
	}

	public static float cameraPitch() {
		//? if >=1.21.11 {
		/*return camera().xRot();
		*///?} else
		return camera().getXRot();
	}

	// --- Welt / Dimension ---

	/** ID der aktuellen Dimension ("minecraft:overworld"); "" wenn unbekannt/zu alt. */
	public static String dimensionId() {
		if (mc().level == null) return "";
		//? if >=1.21.11 {
		/*return mc().level.dimension().identifier().toString();
		*///?} elif >=1.16 {
		return mc().level.dimension().location().toString();
		//?} else
		/*return String.valueOf(mc().level.dimension.getType());*/
	}

	/** Name der Einzelspielerwelt bzw. null auf Servern. */
	public static String levelName() {
		//? if >=1.16 {
		net.minecraft.server.MinecraftServer server = mc().getSingleplayerServer();
		return server == null ? null : server.getWorldData().getLevelName();
		//?} else {
		/*if (mc().getSingleplayerServer() == null || mc().level == null) return null;
		return mc().level.getLevelData().getLevelName();
		*///?}
	}

	/** Adresse des Servers oder null im Einzelspieler. */
	public static String serverAddress() {
		if (mc().getSingleplayerServer() != null) return null;
		net.minecraft.client.multiplayer.ServerData data = mc().getCurrentServer();
		return data == null ? null : data.ip;
	}

	// --- Chat ---

	/** Sendet eine Nachricht bzw. einen Befehl ("/..." ) als Spieler. */
	public static void sendChat(String message) {
		net.minecraft.client.player.LocalPlayer player = mc().player;
		if (player == null || message == null || message.isEmpty()) return;
		//? if >=1.19.3 {
		if (dev.theredstonee.trsclient.core.chat.ChatOut.isCommand(message)) {
			player.connection.sendCommand(dev.theredstonee.trsclient.core.chat.ChatOut.command(message));
		} else {
			player.connection.sendChat(message);
		}
		//?} elif >=1.19.1 {
		/*// 1.19.1/1.19.2: signierter Chat direkt über den Spieler.
		if (dev.theredstonee.trsclient.core.chat.ChatOut.isCommand(message)) {
			player.commandSigned(dev.theredstonee.trsclient.core.chat.ChatOut.command(message), null);
		} else {
			player.chatSigned(message, null);
		}
		*///?} elif >=1.19 {
		/*if (dev.theredstonee.trsclient.core.chat.ChatOut.isCommand(message)) {
			player.command(dev.theredstonee.trsclient.core.chat.ChatOut.command(message));
		} else {
			player.chat(message);
		}
		*///?} else {
		/*// Bis 1.18 erkennt chat() Befehle selbst am "/".
		player.chat(message);
		*///?}
	}

	/** Text in die Zwischenablage legen. */
	public static void setClipboard(String text) {
		mc().keyboardHandler.setClipboard(text);
	}

	/** Chat-Skalierung (Option). */
	public static double chatScale() {
		//? if >=1.19 {
		return mc().options.chatScale().get();
		//?} else
		/*return mc().options.chatScale;*/
	}

	/** Zeilenhöhe im Chat in Pixeln (mit dem Zeilenabstand der Optionen). */
	public static int chatLineHeight() {
		//? if >=1.19 {
		return (int) (9.0 * (mc().options.chatLineSpacing().get() + 1.0));
		//?} elif >=1.16 {
		/*return (int) (9.0 * (mc().options.chatLineSpacing + 1.0));
		*///?} else
		/*return 9;*/
	}
}
