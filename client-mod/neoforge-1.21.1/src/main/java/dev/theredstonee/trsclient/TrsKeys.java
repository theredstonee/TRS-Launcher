package dev.theredstonee.trsclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** Tastenbelegungen – erscheinen in den Steuerungs-Optionen unter "TRS Client". */
public final class TrsKeys {
	public static final String CATEGORY = "key.categories.trsclient";

	public static final KeyMapping menu =
			new KeyMapping("key.trsclient.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY);
	public static final KeyMapping zoom =
			new KeyMapping("key.trsclient.zoom", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
	/** Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten. */
	public static final KeyMapping fullbright =
			new KeyMapping("key.trsclient.fullbright", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

	private TrsKeys() {
	}

	static void register(RegisterKeyMappingsEvent event) {
		event.register(menu);
		event.register(zoom);
		event.register(fullbright);
	}
}
