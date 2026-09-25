package dev.theredstonee.trsclient.hud;

import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.core.module.HudModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/** Zeichnet ein HUD-Modul. Größen sind unskaliert; die Skalierung übernimmt der {@link HudManager}. */
public abstract class HudElement {
	protected final HudModule module;
	protected final Minecraft mc = Minecraft.getInstance();
	/** Lage im Bild beim letzten Zeichnen im Spiel (GUI-Pixel) und Skalierung – für Scissor in alten Versionen. */
	protected float originX, originY, originScale = 1f;

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
	public abstract void draw(Gfx g, Font font, boolean preview);

	/** Textfarbe des Moduls (ARGB). */
	protected int textColor() {
		return module.textColor.argb();
	}
}
