package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Nur Minecraft 1.14: KeyMapping#setDown gibt es erst ab 1.15 (Toggle-Sprint/-Schleichen).
 * (Nur in 1.14 in trsclient.mixins.json eingetragen.)
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("isDown")
	void trsclient$setDown(boolean down);
}
