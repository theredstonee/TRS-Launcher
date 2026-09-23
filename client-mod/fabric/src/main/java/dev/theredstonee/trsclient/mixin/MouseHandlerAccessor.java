package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
//? if >=1.21.9
//import net.minecraft.client.input.MouseButtonInfo;

/**
 * Selbsttest: Klicks laufen durch Minecrafts eigene Maus-Verarbeitung (Mausposition setzen,
 * dann dieselbe Methode, die das Fenster bei einem echten Klick aufruft) – so fallen Fehler in
 * der Übersetzung der Maustasten auf, die ein direkter Aufruf des Bildschirms verdecken würde.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("xpos")
	void trsclient$setXpos(double x);

	@Accessor("ypos")
	void trsclient$setYpos(double y);

	// onPress(window, button, action, mods) bis 1.21.8, onButton(window, info, action) ab 1.21.9.
	//? if >=1.21.9 {
	/*@Invoker("onButton")
	void trsclient$onButton(long window, MouseButtonInfo info, int action);
	*///?} else {
	@Invoker("onPress")
	void trsclient$onPress(long window, int button, int action, int mods);
	//?}
}
