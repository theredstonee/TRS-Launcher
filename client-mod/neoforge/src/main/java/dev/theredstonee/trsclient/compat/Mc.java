package dev.theredstonee.trsclient.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

/**
 * Zugriffe auf Minecraft, die zwischen den Versionen umgezogen sind.
 * Ab 26.2 verwaltet {@code Minecraft.gui} Bildschirme/Overlays und {@code gui.hud} das HUD.
 */
public final class Mc {
	private Mc() {
	}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Fenster des Spiels (Größe, Tastaturabfrage). */
	public static Window window() {
		return mc().getWindow();
	}

	/** Übersetzter Anzeigename einer Tastenbelegung. */
	public static String keyName(KeyMapping mapping) {
		return mapping.getTranslatedKeyMessage().getString();
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

	/** Der Effekt einer Effekt-Instanz (ab 1.20.5 als Holder verpackt). */
	public static MobEffect effect(MobEffectInstance instance) {
		//? if >=1.20.5 {
		return instance.getEffect().value();
		//?} else
		/*return instance.getEffect();*/
	}

	/** Übersetzter Name eines Bioms (z. B. "Ebene"). */
	public static String biomeName(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> {
			//? if >=1.21.11 {
			/*var id = key.identifier();
			*///?} else
			var id = key.location();
			return Component.translatable("biome." + id.getNamespace() + "." + id.getPath()).getString();
		}).orElse("?");
	}

	/**
	 * Gehört das Pack zu einer Mod (NeoForge: "mod_resources", "mod:<id>" bis 1.20.4, "mod/<id>" ab 1.20.5)?
	 * Solche Packs sind immer aktiv und werden – wie im Vanilla-Pack-Bildschirm von NeoForge – ausgeblendet.
	 */
	public static boolean modPack(Pack pack) {
		String id = pack.getId();
		return id.equals("mod_resources") || id.startsWith("mod/") || id.startsWith("mod:");
	}

	// --- Kamera (Wegpunkt-Markierungen) ---

	/** Kamera des laufenden Frames. */
	public static Camera camera() {
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

	private static Vec3 cameraPos() {
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

	/** ID der aktuellen Dimension ("minecraft:overworld"); "" wenn keine Welt geladen ist. */
	public static String dimensionId() {
		if (mc().level == null) return "";
		//? if >=1.21.11 {
		/*return mc().level.dimension().identifier().toString();
		*///?} else
		return mc().level.dimension().location().toString();
	}

	/** Name der Einzelspielerwelt bzw. null auf Servern. */
	public static String levelName() {
		MinecraftServer server = mc().getSingleplayerServer();
		return server == null ? null : server.getWorldData().getLevelName();
	}

	/** Adresse des Servers oder null im Einzelspieler. */
	public static String serverAddress() {
		if (mc().getSingleplayerServer() != null) return null;
		ServerData data = mc().getCurrentServer();
		return data == null ? null : data.ip;
	}

	// --- Chat ---

	/** Sendet eine Nachricht bzw. einen Befehl ("/...") als Spieler. */
	public static void sendChat(String message) {
		LocalPlayer player = mc().player;
		if (player == null || message == null || message.isEmpty()) return;
		if (dev.theredstonee.trsclient.core.chat.ChatOut.isCommand(message)) {
			player.connection.sendCommand(dev.theredstonee.trsclient.core.chat.ChatOut.command(message));
		} else {
			player.connection.sendChat(message);
		}
	}

	/** Text in die Zwischenablage legen. */
	public static void setClipboard(String text) {
		mc().keyboardHandler.setClipboard(text);
	}

	/** Chat-Skalierung (Option). */
	public static double chatScale() {
		return mc().options.chatScale().get();
	}

	/** Zeilenhöhe im Chat in Pixeln (mit dem Zeilenabstand der Optionen). */
	public static int chatLineHeight() {
		return (int) (9.0 * (mc().options.chatLineSpacing().get() + 1.0));
	}

	/** Liest einen Text über den Erzähler vor – nur wenn der Spieler ihn eingeschaltet hat. */
	public static void narrate(String text) {
		//? if >=1.21.6 {
		/*mc().getNarrator().saySystemNow(text);
		*///?} else
		mc().getNarrator().sayNow(text);
	}
}
