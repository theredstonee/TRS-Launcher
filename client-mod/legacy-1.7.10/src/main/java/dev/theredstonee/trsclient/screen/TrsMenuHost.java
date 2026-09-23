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
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Die Minecraft-Seite des TRS-Menüs unter 1.7.10. Resourcepacks gibt es hier nicht;
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
		Minecraft.getMinecraft().getSoundHandler()
				.playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
	}

	@Override
	public void closeScreen() {
		Minecraft.getMinecraft().displayGuiScreen(parent);
	}

	@Override
	public void openHudEditor() {
		Minecraft.getMinecraft().displayGuiScreen(new HudEditorScreen(parent));
	}

	@Override
	public void openMenu() {
		Minecraft.getMinecraft().displayGuiScreen(new TrsMenuScreen(parent));
	}

	/** 1.7.10 hat keinen eigenen Resourcepack-Bildschirm im TRS-Menü. */
	@Override
	public void openPacks() {
	}

	@Override
	public boolean hasPacks() {
		return false;
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
					Minecraft.getMinecraft().displayGuiScreen(new CrosshairEditorScreen(new TrsMenuScreen(parent)));
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
		return Minecraft.getMinecraft().theWorld != null;
	}
}
