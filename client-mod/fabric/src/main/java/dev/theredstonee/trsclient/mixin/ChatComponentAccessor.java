package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Zugriff auf die Chat-Zeilen (Zusammenfassen gleicher Nachrichten, Kopieren per Strg+Klick). */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Accessor("allMessages")
	List<?> trsclient$allMessages();

	@Accessor("trimmedMessages")
	List<?> trsclient$trimmedMessages();

	@Accessor("chatScrollbarPos")
	int trsclient$chatScrollbarPos();
}
