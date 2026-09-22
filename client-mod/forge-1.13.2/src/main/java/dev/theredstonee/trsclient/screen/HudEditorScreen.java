package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.ui.menu.HudEditor;
import net.minecraft.client.gui.GuiScreen;

/**
 * „HUD bearbeiten“ unter 1.13.2: Elemente verschieben (mit Einrasten und Hilfslinien), Größe,
 * Hintergrund, Schatten und Chroma je Modul, Profilwechsel. Die Logik steht in {@link HudEditor}.
 */
public final class HudEditorScreen extends TrsUiScreen {
	private final HudEditor editor;

	public HudEditorScreen(GuiScreen parent) {
		this(new HudEditor(new TrsMenuHost(parent)));
	}

	private HudEditorScreen(HudEditor editor) {
		super(editor);
		this.editor = editor;
	}

	/** Öffnet den Editor mit ausgewähltem erstem Element (Selbsttest). */
	public HudEditorScreen selectFirst() {
		editor.selectFirst();
		return this;
	}

	/** Kein abgedunkelter Vanilla-Hintergrund – der Editor zeichnet ihn selbst. */
	@Override
	protected boolean vanillaBackground() {
		return false;
	}
}
