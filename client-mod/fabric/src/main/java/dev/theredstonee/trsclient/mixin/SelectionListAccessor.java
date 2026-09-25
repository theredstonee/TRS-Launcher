package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Bis 1.20.2: Lage einer Liste (danach über getX/getY). Nur in der Mixin-Liste der alten Versionen. */
@Mixin(AbstractSelectionList.class)
public interface SelectionListAccessor {
	@Accessor("x0")
	int trsclient$x0();

	@Accessor("y0")
	int trsclient$y0();

	@Accessor("x1")
	int trsclient$x1();

	@Accessor("y1")
	int trsclient$y1();
}
