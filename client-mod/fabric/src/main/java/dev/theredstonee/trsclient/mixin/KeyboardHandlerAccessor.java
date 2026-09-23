package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
//? if >=1.21.9
//import net.minecraft.client.input.KeyEvent;

/** Selbsttest: Tasten laufen durch Minecrafts eigene Tastatur-Verarbeitung (wie ein echter Tastendruck). */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {
	// keyPress(window, key, scancode, action, mods) bis 1.21.8, keyPress(window, action, event) ab 1.21.9.
	//? if >=1.21.9 {
	/*@Invoker("keyPress")
	void trsclient$keyPress(long window, int action, KeyEvent event);
	*///?} else {
	@Invoker("keyPress")
	void trsclient$keyPress(long window, int key, int scancode, int action, int mods);
	//?}
}
