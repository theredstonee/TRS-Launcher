package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.online.LegacyOnline;
import dev.theredstonee.trsclient.online.PlayerPreview;
import dev.theredstonee.trsclient.core.ui.menu.HudItem;
import dev.theredstonee.trsclient.core.ui.menu.MenuAction;
import dev.theredstonee.trsclient.core.ui.menu.MenuHost;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Die Minecraft-Seite des TRS-Menüs unter 1.8.9–1.12.2: Bildschirme öffnen, Geräusche, Tastennamen.
 * Alles Weitere steht versionsunabhängig in {@code core.ui.menu}.
 */
public final class TrsMenuHost implements MenuHost {
	private final GuiScreen parent;

	public TrsMenuHost(GuiScreen parent) {
		this.parent = parent;
	}

	@Override
	public TrsModules modules() {
		return TrsClient.get().modules();
	}

	@Override
	public void playClick() {
		Mc.clickSound();
	}

	@Override
	public void closeScreen() {
		Mc.setScreen(parent);
	}

	@Override
	public void openHudEditor() {
		Mc.setScreen(new HudEditorScreen(parent));
	}

	@Override
	public void openMenu() {
		Mc.setScreen(new TrsMenuScreen(parent));
	}

	@Override
	public void openPacks() {
		Mc.setScreen(new PackScreen(new TrsMenuScreen(parent)));
	}

	@Override
	public boolean hasPacks() {
		return true;
	}

	@Override
	public void openAccounts() {
		// closeScreen() eines Hosts zeigt dessen „parent“ – hier also den Kontobildschirm (zurück führt ins Menü).
		new TrsMenuHost(AccountsScreen.create(new TrsMenuScreen(parent))).closeScreen();
	}

	@Override
	public boolean hasAccounts() {
		return AccountsScreen.available();
	}

	/** Was unter Legacy-Forge nicht umsetzbar ist (Treffer-Farbe, niedriges Feuer), bleibt aus dem Menü heraus. */
	@Override
	public void openFriends() {
		new TrsMenuHost(MenuScreens.friends(new TrsMenuScreen(parent))).closeScreen();
	}

	@Override
	public boolean hasFriends() {
		return MenuScreens.friendsAvailable();
	}

	@Override
	public void openClips() {
		new TrsMenuHost(MenuScreens.clips(new TrsMenuScreen(parent))).closeScreen();
	}

	@Override
	public boolean hasClips() {
		return true;
	}

	@Override
	public boolean supports(Module module) {
		if (module == modules().colors && !dev.theredstonee.trsclient.render.ColorPass.supported()) return false;
		return TrsClient.get().menuModules().contains(module);
	}

	@Override
	public List<MenuAction> actions(Module module) {
		List<MenuAction> actions = new ArrayList<MenuAction>();
		if (module == modules().crosshair) {
			actions.add(new MenuAction(I18n.tr("crosshairEditor.title"), "crosshair", new Runnable() {
				@Override
				public void run() {
					Mc.setScreen(new CrosshairEditorScreen(new TrsMenuScreen(parent)));
				}
			}));
		}
		if (module == modules().waypoints) {
			actions.add(new MenuAction(I18n.tr("menu.action.waypoints"), "compass", new Runnable() {
				@Override
				public void run() {
					Mc.setScreen(new WaypointListScreen(new TrsMenuScreen(parent)));
				}
			}));
		}
		if (module instanceof HudModule) {
			actions.add(new MenuAction(I18n.tr("menu.action.showInEditor"), "move", new Runnable() {
				@Override
				public void run() {
					openHudEditor();
				}
			}));
		}
		return actions;
	}

	@Override
	public void save() {
		TrsClient.get().saveConfig();
	}

	@Override
	public boolean shiftDown() {
		return GuiScreen.isShiftKeyDown();
	}

	@Override
	public String keyLabel(String keyName) {
		return Keys.display(keyName);
	}

	@Override
	public String keyNameOf(int rawKey) {
		return Keys.name(rawKey);
	}

	@Override
	public String menuKeyLabel() {
		return GameSettings.getKeyDisplayString(TrsKeys.menu.getKeyCode());
	}

	@Override
	public String profileKeyLabel() {
		int code = TrsKeys.hudProfile.getKeyCode();
		return code == Keys.UNBOUND ? "" : GameSettings.getKeyDisplayString(code);
	}

	@Override
	public List<HudItem> hudItems() {
		return TrsClient.get().hud().editorItems();
	}

	@Override
	public boolean inWorld() {
		return Mc.world() != null;
	}

	// --- Spieler-Vorschau (Umhang-Physik) ---

	@Override
	public int playerPreviewState() {
		if (!supports(modules().capePhysics)) return PREVIEW_UNSUPPORTED;
		return Mc.player() == null ? PREVIEW_NO_PLAYER : PREVIEW_OK;
	}

	@Override
	public boolean previewHasCape() {
		net.minecraft.client.entity.EntityPlayerSP p = Mc.player();
		return p != null && p.hasPlayerInfo() && p.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.CAPE)
				&& p.getLocationCape() != null;
	}

	@Override
	public boolean previewWalking() {
		return LegacyOnline.features() != null && LegacyOnline.features().physics().previewWalking();
	}

	@Override
	public void drawPlayerPreview(Canvas c, int x, int y, int w, int h, float yawDegrees) {
		if (LegacyOnline.features() != null) LegacyOnline.features().physics().preview();
		PlayerPreview.draw(x, y, w, h, yawDegrees);
	}
}
