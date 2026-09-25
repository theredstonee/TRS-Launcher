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
import dev.theredstonee.trsclient.online.OnlineHooks;
import dev.theredstonee.trsclient.online.PlayerPreview;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import dev.theredstonee.trsclient.core.ui.menu.HudItem;
import dev.theredstonee.trsclient.core.ui.menu.MenuAction;
import dev.theredstonee.trsclient.core.ui.menu.MenuHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
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
	public void openAccounts() {
		Mc.setScreen(AccountsScreen.create(new TrsMenuScreen(parent)));
	}

	@Override
	public boolean hasAccounts() {
		return AccountsScreen.available();
	}

	@Override
	public boolean hasIntro() {
		return true;
	}

	/** Einführung (Sprache, Leistung, Tasten, Modul-Pakete); „Schließen“ führt zum Bildschirm unter diesem Host. */
	@Override
	public void openIntro() {
		Mc.setScreen(new TrsUiScreen(I18n.tr("intro.title"), new dev.theredstonee.trsclient.core.intro.IntroUi(this)));
	}

	/** Alle Tastenbelegungen (Vanilla, TRS, andere Mods) – für Konflikte in der Einführung. */
	@Override
	public List<dev.theredstonee.trsclient.core.intro.KeyBind> keyBindings() {
		List<dev.theredstonee.trsclient.core.intro.KeyBind> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.options == null) return out;
		for (net.minecraft.client.KeyMapping k : mc.options.keyMappings) {
			if (k == null) continue;
			final net.minecraft.client.KeyMapping km = k;
			out.add(new dev.theredstonee.trsclient.core.intro.KeyBind(k.getName(),
					net.minecraft.client.resources.language.I18n.get(k.getName()), k.getDefaultKey().getName(), TrsKeys.link(() -> km)));
		}
		return out;
	}

	@Override
	public void openWardrobe() {
		Mc.setScreen(WardrobeScreen.create(new TrsMenuScreen(parent)));
	}

	@Override
	public boolean hasWardrobe() {
		return WardrobeScreen.available();
	}

	@Override
	public void openFriends() {
		Mc.setScreen(MenuScreens.friends(new TrsMenuScreen(parent)));
	}

	@Override
	public boolean hasFriends() {
		return MenuScreens.friendsAvailable();
	}

	@Override
	public void openClips() {
		Mc.setScreen(MenuScreens.clips(new TrsMenuScreen(parent)));
	}

	@Override
	public boolean hasClips() {
		return true;
	}

	/** Ohne Mixin (Forge 1.14.4) fehlen einige Module – die bleiben aus dem Menü heraus. */
	@Override
	public boolean supports(Module module) {
		if (module == modules().colors && !dev.theredstonee.trsclient.render.ColorPass.supported()) return false;
		return TrsClient.get().visibleModules().contains(module);
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

	// --- Spieler-Vorschau (Umhang-Physik) ---

	@Override
	public int playerPreviewState() {
		if (!PlayerPreview.supported() || !supports(modules().capePhysics)) return PREVIEW_UNSUPPORTED;
		return Minecraft.getInstance().player == null ? PREVIEW_NO_PLAYER : PREVIEW_OK;
	}

	@Override
	public boolean previewHasCape() {
		net.minecraft.client.player.AbstractClientPlayer player = Minecraft.getInstance().player;
		return player != null && OnlineHooks.hasCapeVisible(player);
	}

	@Override
	public boolean previewWalking() {
		return OnlineHooks.features() != null && OnlineHooks.features().physics().previewWalking();
	}

	@Override
	public void drawPlayerPreview(Canvas c, int x, int y, int w, int h, float yawDegrees) {
		if (OnlineHooks.features() != null) OnlineHooks.features().physics().preview();
		PlayerPreview.draw(GfxCanvas.current().gfx().raw(), x, y, w, h, yawDegrees);
	}
}
