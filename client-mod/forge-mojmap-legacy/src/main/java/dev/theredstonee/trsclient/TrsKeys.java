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
	// Zoom liegt auf V: C ist ab Minecraft 1.12 mit "Hotbar speichern" belegt.
	public static final KeyMapping zoom =
			new KeyMapping("key.trsclient.zoom", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
	/** Standardmäßig unbelegt – Fullbright lässt sich auch im Menü schalten. */
	public static final KeyMapping fullbright =
			new KeyMapping("key.trsclient.fullbright", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
	public static final KeyMapping freelook =
			new KeyMapping("key.trsclient.freelook", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, CATEGORY);

	/** Wechselt das HUD-Profil (standardmäßig unbelegt). */
	public static final KeyMapping hudProfile =
			new KeyMapping("key.trsclient.hudProfile", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

	private static final KeyMapping[] ALL = {menu, zoom, fullbright, freelook, hudProfile};

	private TrsKeys() {
	}

	/** Aktuell belegte Taste (GLFW-Code) einer Tastenbelegung. */
	public static int boundKey(KeyMapping mapping) {
		return mapping.getKey().getValue();
	}

	/**
	 * Stellt den alten Zoom-Standard C auf V um – aber nur, wenn die Taste noch auf C liegt,
	 * also nie geändert wurde.
	 * @return true, wenn umgestellt wurde
	 */
	public static boolean migrateZoomKey() {
		if (boundKey(zoom) != GLFW.GLFW_KEY_C) return false;
		zoom.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V));
		KeyMapping.resetMapping();
		return true;
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

	/**
	 * Verbindung einer Modul-Tasteneinstellung (TRS-Menü) mit einer Vanilla-Tastenbelegung: gelesen und
	 * geschrieben wird die Belegung selbst (gespeichert in options.txt). Die Belegung wird erst beim
	 * Aufruf geholt, weil sie je nach Loader erst später angelegt wird.
	 */
	public static dev.theredstonee.trsclient.core.module.KeySetting.Link link(final java.util.function.Supplier<KeyMapping> mapping) {
		return new dev.theredstonee.trsclient.core.module.KeySetting.Link() {
			@Override
			public String get() {
				KeyMapping m = mapping.get();
				return m == null ? dev.theredstonee.trsclient.core.module.KeySetting.NONE : m.saveString();
			}

			@Override
			public void set(String keyName) {
				KeyMapping m = mapping.get();
				if (m == null) return;
				m.setKey(InputConstants.getKey(keyName));
				KeyMapping.resetMapping();
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				if (mc != null && mc.options != null) mc.options.save();
			}
		};
	}
}
