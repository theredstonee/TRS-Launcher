package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.ui.BorderlessState;
import net.minecraft.client.Minecraft;

/**
 * Ab 26.3 spricht das Fenster SDL. Randlos ist dort der Fenstermodus von Windows, sobald exklusives Vollbild aus ist.
 */
public final class BorderlessSdl {
	private BorderlessSdl() {
	}

	public static void sync() {
		try {
			boolean module = BorderlessHooks.want();
			//? if >=26.3 {
			/*com.mojang.blaze3d.platform.Window window = Mc.window();
			if (window != null) window.setExclusiveFullscreen(!module);
			*///?}
			BorderlessState.mark(module && option());
		} catch (Throwable ignored) {
		}
	}

	private static boolean option() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.options == null) return false;
			//? if >=1.19 {
			return mc.options.fullscreen().get();
			//?} else
			/*return mc.options.fullscreen;*/
		} catch (Throwable ignored) {
			return false;
		}
	}
}
