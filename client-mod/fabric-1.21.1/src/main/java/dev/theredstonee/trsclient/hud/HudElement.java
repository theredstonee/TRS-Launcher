package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.core.module.HudModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Zeichnet ein HUD-Modul. Größen sind unskaliert; die Skalierung übernimmt der {@link HudManager}. */
public abstract class HudElement {
	protected final HudModule module;
	protected final Minecraft mc = Minecraft.getInstance();

	protected HudElement(HudModule module) {
		this.module = module;
	}

	public HudModule module() {
		return module;
	}

	/** Im Spiel sichtbar? (z. B. Ping nur auf Servern). Im Editor wird immer gezeichnet. */
	public boolean visible() {
		return true;
	}

	/** Unskalierte Breite. {@code preview} = Editor-Vorschau mit Beispielwerten. */
	public abstract int width(Font font, boolean preview);

	/** Unskalierte Höhe. */
	public abstract int height(Font font, boolean preview);

	/** Zeichnet bei (0, 0) im bereits verschobenen/skalierten Koordinatensystem. */
	public abstract void draw(GuiGraphics g, Font font, boolean preview);

	/** Textfarbe des Moduls (ARGB). */
	protected int textColor() {
		return module.textColor.argb();
	}
}
