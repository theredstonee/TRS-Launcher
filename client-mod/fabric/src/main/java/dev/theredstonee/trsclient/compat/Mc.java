package dev.theredstonee.trsclient.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
}
