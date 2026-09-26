package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.qol.QolHooks;
import dev.theredstonee.trsclient.qol.QolReconnectButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto-Reconnect: Knopf im „Verbindung getrennt“-Bildschirm mit Countdown („Neu verbinden in 7 s – abbrechen“) bzw.
 * dem Grund, warum nicht neu verbunden wird. Beschriftung setzt {@link QolHooks} je Tick.
 */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectScreenMixin extends Screen implements QolReconnectButton {
	@Unique
	private Button trsclient$reconnect;

	protected DisconnectScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init()V", at = @At("TAIL"), require = 1)
	private void trsclient$addButton(CallbackInfo ci) {
		trsclient$reconnect = null;
		if (!QolHooks.reconnectEnabled()) return;
		int w = 260;
		int x = this.width / 2 - w / 2;
		int y = this.height - 28;
		//? if >=1.19.3 {
		/*Button b = Button.builder(QolHooks.label(""), button -> QolHooks.cancelReconnect()).bounds(x, y, w, 20).build();
		*///?} elif >=1.16 {
		Button b = new Button(x, y, w, 20, QolHooks.label(""), button -> QolHooks.cancelReconnect());
		//?} else
		/*Button b = new Button(x, y, w, 20, "", button -> QolHooks.cancelReconnect());*/
		b.visible = false;
		//? if >=1.17 {
		/*trsclient$reconnect = this.addRenderableWidget(b);
		*///?} else
		trsclient$reconnect = this.addButton(b);
	}

	@Override
	public void trsclient$update(String label, boolean active) {
		Button b = trsclient$reconnect;
		if (b == null) return;
		b.visible = label != null;
		b.active = active;
		if (label == null) return;
		//? if >=1.16 {
		b.setMessage(QolHooks.label(label));
		//?} else
		/*b.setMessage(label);*/
	}
}
