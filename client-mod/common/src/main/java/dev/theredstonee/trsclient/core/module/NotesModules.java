package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;

/**
 * Notizen je Welt (TRS Client {@link NewSince#NOTES}): ein Notizbuch je Einzelspielerwelt bzw. Server mit Checklisten
 * und anklickbaren Koordinaten, synchronisiert mit dem TRS-Konto; eine Notiz lässt sich im HUD anheften.
 *
 * <p>Logik in {@code core.notes}, die Seite im TRS-Menü in {@code core.ui.notes}; die Bäume liefern nur die Taste und
 * das HUD-Element.
 */
public final class NotesModules {
	public final Module notes;
	/** Taste „Notizen öffnen“ (Vanilla-Belegung, standardmäßig unbelegt). */
	public final KeySetting openKey;
	/** Mit dem TRS-Konto synchronisieren. */
	public final BoolSetting sync;

	/** Angeheftete Notiz im HUD. */
	public final HudModule pinnedNote;
	public final NumberSetting hudLines;
	public final NumberSetting hudWidth;

	NotesModules(ModuleRegistry registry) {
		notes = registry.register(new Module("notes", "World Notes",
				"A notebook for every world and server: several notes with a title and text, checklists you can tick "
						+ "and coordinates that open on the world map or become a waypoint. Search the notes of the "
						+ "current world, look at the notes of other worlds and pin one to the HUD. Synced with your TRS "
						+ "account.", true));
		openKey = notes.add(new KeySetting("key", "Open notes"));
		sync = notes.add(new BoolSetting("sync", "Sync with TRS account", true));
		notes.icon("note").category(Category.WORLD);

		pinnedNote = registry.register(new HudModule("pinnedNote", "Pinned Note",
				"Shows the note you pinned on the Notes page for the current world or server, with its checklist.", false,
				new HudPosition(HudAnchor.CENTER_RIGHT, -0.005, -0.12)));
		hudLines = pinnedNote.add(new NumberSetting("lines", "Lines", 6, 1, 15, 1, ""));
		hudWidth = pinnedNote.add(new NumberSetting("width", "Width", 150, 80, 260, 10, "", " px"));
		pinnedNote.icon("pin");
	}
}
