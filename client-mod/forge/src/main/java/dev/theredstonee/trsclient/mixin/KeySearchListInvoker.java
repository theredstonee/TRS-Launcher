package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;

/**
 * Suche in der Tastenbelegung: Einträge einer Liste ersetzen (bis 1.20.x geschützt, danach öffentlich – der Invoker
 * geht in beiden Fällen). So bleiben Kategorie-Zeilen, Knöpfe und Scrollen ganz die von Minecraft.
 */
@Mixin(AbstractSelectionList.class)
public interface KeySearchListInvoker {
	@Invoker("replaceEntries")
	void trsclient$replaceEntries(Collection<?> entries);
}
