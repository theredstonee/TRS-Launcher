package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.ui.menu.HudEditor;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.screens.Screen;

/**
 * „HUD bearbeiten“: Elemente verschieben (mit Einrasten und Hilfslinien), Größe, Hintergrund,
 * Schatten und Chroma je Modul, Profilwechsel. Die Logik steht in {@link HudEditor}.
 */
public final class HudEditorScreen extends TrsUiScreen {

	private final HudEditor editor;

	public HudEditorScreen(Screen parent) {
		this(new HudEditor(new TrsMenuHost(parent)));
	}

	private HudEditorScreen(HudEditor editor) {
		super("HUD bearbeiten", editor);
		this.editor = editor;
	}

	/** Öffnet den Editor mit ausgewähltem erstem Element (Selbsttest). */
	public HudEditorScreen selectFirst() {
		editor.selectFirst();
		return this;
	}

	/** Kein Vanilla-Hintergrund – das Spiel soll beim Einrichten sichtbar bleiben. */
	@Override
	protected boolean customBackground() {
		return true;
	}

	@Override
	protected void drawBackground(Gfx g, float partialTick) {
		// Der Editor zeichnet seinen Hintergrund selbst (abhängig davon, ob eine Welt läuft).
	}
}
