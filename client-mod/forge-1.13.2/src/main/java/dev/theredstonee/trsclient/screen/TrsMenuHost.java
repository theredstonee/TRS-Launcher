package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menu.HudItem;
import dev.theredstonee.trsclient.core.ui.menu.MenuAction;
import dev.theredstonee.trsclient.core.ui.menu.MenuHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.init.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Die Minecraft-Seite des TRS-Menüs unter 1.13.2. Resourcepacks gibt es hier nicht;
 * Module, die diese Version nicht kennt, blendet {@link TrsClient#supported} aus.
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
		Minecraft.getInstance().getSoundHandler().play(SimpleSound.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	@Override
	public void closeScreen() {
		Minecraft.getInstance().displayGuiScreen(parent);
	}

	@Override
	public void openHudEditor() {
		Minecraft.getInstance().displayGuiScreen(new HudEditorScreen(parent));
	}

	@Override
	public void openMenu() {
		Minecraft.getInstance().displayGuiScreen(new TrsMenuScreen(parent));
	}

	/** 1.13.2 hat keinen eigenen Resourcepack-Bildschirm im TRS-Menü. */
	@Override
	public void openPacks() {
	}

	@Override
	public boolean hasPacks() {
		return false;
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
		return TrsClient.supported(module);
	}

	@Override
	public List<MenuAction> actions(Module module) {
		List<MenuAction> actions = new ArrayList<MenuAction>();
		if (module == modules().crosshair) {
			actions.add(new MenuAction(I18n.tr("crosshairEditor.title"), "crosshair", new Runnable() {
				@Override
				public void run() {
					Minecraft.getInstance().displayGuiScreen(new CrosshairEditorScreen(new TrsMenuScreen(parent)));
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
		return TrsKeys.menu.getLocalizedName();
	}

	@Override
	public String profileKeyLabel() {
		return TrsKeys.hudProfile.isInvalid() ? "" : TrsKeys.hudProfile.getLocalizedName();
	}

	@Override
	public List<HudItem> hudItems() {
		return TrsClient.get().hud().editorItems();
	}

	@Override
	public boolean inWorld() {
		return Minecraft.getInstance().world != null;
	}
}
