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
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest Garderobe ({@code -PtrsAutotestOnly=wardrobe}): öffnet die Garderobe vom Titelbildschirm, legt (lokal)
 * zwei Skins und ein Outfit an und fotografiert jede Kategorie, das Menü „Skin hinzufügen“, den Skin-Editor und den
 * neuen Eintrag im TRS-Menü. Beendet das Spiel danach. Screenshots: wardrobe, -outfits, -capes, -emotes, -add,
 * -editor, menu-wardrobe.
 */
public final class WardrobeTest {
	private int phase;
	private int wait;
	private int waited;
	private WardrobeUi ui;

	private final String mcVersion = Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new WardrobeTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END) tick(Minecraft.getMinecraft());
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Screenshot {}", file);
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
				if (!(mc.currentScreen instanceof TrsTitleScreen) && !(mc.currentScreen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				if (!(mc.currentScreen instanceof TrsTitleScreen)) mc.displayGuiScreen(new TrsTitleScreen());
				phase++;
				wait = 20;
				return;
			case 1:
				mc.displayGuiScreen(WardrobeScreen.create(mc.currentScreen));
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
				shot(mc, "wardrobe");
				ui.category("outfits");
				if (ui.service().state().doc.outfits.isEmpty() && !ui.service().state().skins.isEmpty()) {
					ui.service().saveOutfit("Rotes Outfit", ui.service().state().skins.get(0).id, "none");
				}
				phase++;
				wait = 30;
				return;
			case 5:
				shot(mc, "wardrobe-outfits");
				ui.category("capes");
				phase++;
				wait = 30;
				return;
			case 6:
				shot(mc, "wardrobe-capes");
				ui.category("emotes");
				ui.selectEmote("winken");
				phase++;
				wait = 25;
				return;
			case 7:
				shot(mc, "wardrobe-emotes");
				ui.category("skins");
				ui.showAddMenu();
				phase++;
				wait = 15;
				return;
			case 8: {
				shot(mc, "wardrobe-add");
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
			case 9:
				shot(mc, "wardrobe-editor");
				ui.closeOverlays();
				mc.displayGuiScreen(new TrsMenuScreen(new TrsTitleScreen()));
				phase++;
				wait = 30;
				return;
			case 10:
				shot(mc, "menu-wardrobe");
				TrsClient.LOGGER.info("[Autotest] Garderobe fertig");
				phase++;
				wait = 20;
				return;
			case 11:
				phase++;
				mc.shutdown();
				return;
			default:
		}
	}
}
