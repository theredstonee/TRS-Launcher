package dev.theredstonee.trsclient.touch;

import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.touch.TouchKeyboard;
import dev.theredstonee.trsclient.core.touch.TouchMode;
import dev.theredstonee.trsclient.core.touch.TouchRuntime;
import dev.theredstonee.trsclient.online.EmoteHooks;
import dev.theredstonee.trsclient.screen.EmoteWheelScreen;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
//? if >=1.19.3 {
/*import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
*///?} else
import net.minecraft.client.gui.screens.inventory.SignEditScreen;

/**
 * Touch-Modus (mobile Engine, {@code -Dtrs.touch=true}) je Client-Tick: feste Overlay-Tasten und
 * Bildschirmtastatur. Die Logik steht in {@link TouchRuntime}; ohne Touch-Modus passiert nichts.
 */
public final class TouchHooks {
	private static final TouchRuntime.Platform PLATFORM = new TouchRuntime.Platform() {
		@Override
		public boolean keyDown(String keyName) {
			return Keys.isDown(keyName);
		}

		@Override
		public boolean screenOpen() {
			return Mc.screen() != null;
		}

		@Override
		public boolean inWorld() {
			return Mc.mc().player != null && EmoteHooks.enabled();
		}

		@Override
		public void openMenu() {
			Mc.setScreen(new TrsMenuScreen(null));
		}

		@Override
		public void openEmoteWheel() {
			Mc.setScreen(new EmoteWheelScreen());
		}

		@Override
		public void openHudEditor() {
			Mc.setScreen(new HudEditorScreen(null));
		}

		@Override
		public String vanillaField() {
			return field(Mc.screen());
		}

		@Override
		public double guiScale() {
			return Mc.window().getGuiScale();
		}
	};

	private TouchHooks() {
	}

	/** Aus dem Client-Tick. */
	public static void tick() {
		if (!TouchMode.enabled()) return;
		TouchRuntime.tick(PLATFORM);
	}

	/** Art des fokussierten Vanilla-Textfelds (Chat, Schild, Amboss, Buch, sonst jedes fokussierte Eingabefeld). */
	static String field(Screen screen) {
		if (screen == null) return null;
		if (screen instanceof ChatScreen) return TouchKeyboard.FIELD_CHAT;
		//? if >=1.19.3 {
		/*if (screen instanceof AbstractSignEditScreen) return TouchKeyboard.FIELD_SIGN;
		*///?} else
		if (screen instanceof SignEditScreen) return TouchKeyboard.FIELD_SIGN;
		if (screen instanceof BookEditScreen) return TouchKeyboard.FIELD_BOOK;
		if (screen instanceof AnvilScreen) return TouchKeyboard.FIELD_ANVIL;
		if (screen.getFocused() instanceof EditBox) return TouchKeyboard.FIELD_TEXT;
		return null;
	}
}
