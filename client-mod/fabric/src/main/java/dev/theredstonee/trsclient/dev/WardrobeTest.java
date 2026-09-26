package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.ui.wardrobe.WardrobeUi;
import dev.theredstonee.trsclient.core.wardrobe.SkinEditor;
import dev.theredstonee.trsclient.core.wardrobe.SkinLayout;
import dev.theredstonee.trsclient.core.wardrobe.WardrobeService;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.WardrobeScreen;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest Garderobe ({@code -PtrsAutotestOnly=wardrobe}): öffnet die Garderobe vom Titelbildschirm, legt (lokal)
 * zwei Skins und ein Outfit an und fotografiert jede Kategorie, das Menü „Skin hinzufügen“, den Skin-Editor und den
 * neuen Eintrag im TRS-Menü. Beendet das Spiel danach. Screenshots: wardrobe, -current (Karte „Aktueller Skin“),
 * -twin (nach „In Bibliothek speichern“: Schild „= aktuell“), -outfits, -capes, -emotes, -add, -editor, menu-wardrobe.
 */
public final class WardrobeTest {
	private int phase;
	private int wait;
	private int waited;
	private WardrobeUi ui;

	public static void install() {
		WardrobeTest test = new WardrobeTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private void log(String what) {
		WardrobeService.State s = ui == null ? null : ui.service().state();
		TrsClient.LOGGER.info("[Autotest] Garderobe {}: aufgabe={} skins={} outfits={} trs={} sitzung={} meldung={}", what,
				s == null ? null : s.task, s == null ? 0 : s.skins.size(), s == null ? 0 : s.doc.outfits.size(),
				s != null && s.trs, s != null && s.session, s == null ? null : s.message);
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || Mc.screen() == null) return;
				mc.options.pauseOnLostFocus = false;
				if (!(Mc.screen() instanceof TrsTitleScreen)) Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 20;
				return;
			case 1:
				Mc.setScreen(WardrobeScreen.create(Mc.screen()));
				ui = WardrobeScreen.last;
				log("geöffnet");
				phase++;
				wait = 20;
				return;
			case 2: {
				WardrobeService.State s = ui.service().state();
				if (s.busy() && waited++ < 40) {
					wait = 10;
					return;
				}
				if (s.skins.size() < 2) {
					int[] alex = SkinEditor.blankTemplate(true);
					for (int i = 0; i < alex.length; i++) {
						if (SkinLayout.of(true).region(i % 64, i / 64) >= 0 && (alex[i] & 0xFFFFFF) == 0x2E8B8B) alex[i] = 0xFFB02E26;
					}
					ui.service().saveEdited(SkinEditor.blankTemplate(false), false, "Puppe Classic", null);
					ui.service().saveEdited(alex, true, "Puppe Rot", null);
				}
				waited = 0;
				phase++;
				wait = 30;
				return;
			}
			case 3: {
				WardrobeService.State s = ui.service().state();
				if ((s.skins.size() < 2 || s.busy()) && waited++ < 40) {
					wait = 5;
					return;
				}
				if (!s.skins.isEmpty()) {
					String first = s.skins.get(0).id;
					if (!s.doc.favorite(first)) ui.service().toggleFavorite(first);
					ui.selectSkin(first);
				}
				phase++;
				wait = 30;
				return;
			}
			case 4:
				log("Skins");
				AutoTest.shot(mc, "trsclient-wardrobe");
				// Karte „Aktueller Skin“ (getragen) auswählen
				ui.selectCurrent();
				phase++;
				wait = 20;
				return;
			case 5: {
				dev.theredstonee.trsclient.core.wardrobe.CurrentSkin cur = ui.current();
				TrsClient.LOGGER.info("[Autotest] Garderobe aktueller Skin: quelle={} pixel={} slim={} zwilling={}",
						cur == null ? null : cur.source, cur != null && cur.pixels != null, cur != null && cur.slim,
						cur == null ? null : cur.twin);
				AutoTest.shot(mc, "trsclient-wardrobe-current");
				// In die Bibliothek sichern → der Bibliotheks-Eintrag bekommt „= aktuell“, keine zweite Lampe
				boolean saved = ui.saveCurrentSkin();
				TrsClient.LOGGER.info("[Autotest] Garderobe aktueller Skin gesichert={}", saved);
				phase++;
				wait = 30;
				return;
			}
			case 6: {
				WardrobeService.State s = ui.service().state();
				if (s.busy() && waited++ < 40) {
					wait = 5;
					return;
				}
				waited = 0;
				ui.selectCurrent();
				phase++;
				wait = 10;
				return;
			}
			case 7: {
				dev.theredstonee.trsclient.core.wardrobe.CurrentSkin cur = ui.current();
				TrsClient.LOGGER.info("[Autotest] Garderobe nach Sichern: zwilling={} skins={} meldung={}",
						cur == null ? null : cur.twin, ui.service().state().skins.size(), ui.service().state().message);
				AutoTest.shot(mc, "trsclient-wardrobe-twin");
				ui.category("outfits");
				if (ui.service().state().doc.outfits.isEmpty() && !ui.service().state().skins.isEmpty()) {
					ui.service().saveOutfit("Rotes Outfit", ui.service().state().skins.get(0).id, "none");
				}
				phase++;
				wait = 30;
				return;
			}
			case 8:
				AutoTest.shot(mc, "trsclient-wardrobe-outfits");
				ui.category("capes");
				phase++;
				wait = 30;
				return;
			case 9:
				AutoTest.shot(mc, "trsclient-wardrobe-capes");
				ui.category("emotes");
				ui.selectEmote("winken");
				phase++;
				wait = 25;
				return;
			case 10:
				AutoTest.shot(mc, "trsclient-wardrobe-emotes");
				ui.category("skins");
				ui.showAddMenu();
				phase++;
				wait = 15;
				return;
			case 11: {
				AutoTest.shot(mc, "trsclient-wardrobe-add");
				ui.closeOverlays();
				ui.openEditorBlank();
				SkinEditor e = ui.editorModel();
				e.setMirror(true);
				e.setColor(0xFFB02E26);
				e.beginStroke();
				for (int y = 20; y < 32; y++) e.line(44, y, 47, y);
				e.endStroke();
				e.setLayer(SkinLayout.OVERLAY);
				e.setColor(0xFFFED83D);
				e.setTool(SkinEditor.Tool.FILL);
				e.apply(40, 8);
				e.setTool(SkinEditor.Tool.BRUSH);
				e.setColor(0xFF3AB3DA);
				log("Editor");
				phase++;
				wait = 20;
				return;
			}
			case 12:
				AutoTest.shot(mc, "trsclient-wardrobe-editor");
				ui.closeOverlays();
				Mc.setScreen(new TrsMenuScreen(new TrsTitleScreen()));
				phase++;
				wait = 30;
				return;
			case 13:
				AutoTest.shot(mc, "trsclient-menu-wardrobe");
				TrsClient.LOGGER.info("[Autotest] Garderobe fertig");
				phase++;
				wait = 20;
				return;
			case 14:
				phase++;
				mc.stop();
				return;
			default:
		}
	}
}
