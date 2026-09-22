package dev.theredstonee.trsclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
//? if >=1.19 {
/*import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
*///?} elif >=1.18 {
/*import net.minecraftforge.client.ClientRegistry;
*///?} elif >=1.17 {
/*import net.minecraftforge.fmlclient.registry.ClientRegistry;
*///?} else
import net.minecraftforge.fml.client.registry.ClientRegistry;

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
	public static final KeyMapping freelook =
			new KeyMapping("key.trsclient.freelook", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, CATEGORY);

	private static final KeyMapping[] ALL = {menu, zoom, fullbright, freelook};

	private TrsKeys() {
	}

	/** Aktuell belegte Taste (GLFW-Code) einer Tastenbelegung. */
	public static int boundKey(KeyMapping mapping) {
		return mapping.getKey().getValue();
	}

	//? if >=1.19 {
	/*static void register(RegisterKeyMappingsEvent event) {
		for (KeyMapping key : ALL) event.register(key);
	}
	*///?} else {
	/** Aus FMLClientSetupEvent. */
	static void register() {
		for (KeyMapping key : ALL) ClientRegistry.registerKeyBinding(key);
	}
	//?}
}
