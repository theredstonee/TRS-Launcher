package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menu.HudItem;
import dev.theredstonee.trsclient.core.ui.menu.MenuAction;
import dev.theredstonee.trsclient.core.ui.menu.MenuHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Die Minecraft-Seite des TRS-Menüs: Bildschirme öffnen, Geräusche, Tastennamen.
 * Alles Weitere steht versionsunabhängig in {@code core.ui.menu}.
 */
public final class TrsMenuHost implements MenuHost {
	private final Screen parent;

	public TrsMenuHost(Screen parent) {
		this.parent = parent;
	}

	public Screen parent() {
		return parent;
	}

	@Override
	public TrsModules modules() {
		return TrsClient.get().modules();
	}

	@Override
	public void playClick() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
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
	public List<MenuAction> actions(Module module) {
		List<MenuAction> actions = new ArrayList<>();
		if (module == modules().crosshair) {
			actions.add(new MenuAction("Fadenkreuz bearbeiten", "crosshair", new Runnable() {
				@Override
				public void run() {
					Mc.setScreen(new CrosshairEditorScreen(new TrsMenuScreen(parent)));
				}
			}));
		}
		if (module instanceof HudModule) {
			actions.add(new MenuAction("Im HUD-Editor zeigen", "move", new Runnable() {
				@Override
				public void run() {
					openHudEditor();
				}
			}));
		}
		return actions.isEmpty() ? Collections.<MenuAction>emptyList() : actions;
	}

	@Override
	public void save() {
		TrsClient.get().saveConfig();
	}

	@Override
	public boolean shiftDown() {
		return Keys.isDown("key.keyboard.left.shift") || Keys.isDown("key.keyboard.right.shift");
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
		return Mc.keyName(TrsKeys.menu);
	}

	@Override
	public String profileKeyLabel() {
		return TrsKeys.boundKey(TrsKeys.hudProfile) == Keys.UNBOUND ? "" : Mc.keyName(TrsKeys.hudProfile);
	}

	@Override
	public List<HudItem> hudItems() {
		return TrsClient.get().hud().editorItems();
	}

	@Override
	public boolean inWorld() {
		return Minecraft.getInstance().level != null;
	}
}
