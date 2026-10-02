package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.ui.BorderlessState;
import dev.theredstonee.trsclient.ui.BorderlessGlfw;
import dev.theredstonee.trsclient.ui.BorderlessHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Randloses Vollbild. {@code toggleFullScreen} kippt nur ein Bit; das Fenster stellt {@code setMode} um
 * ({@code glfwSetWindowMonitor}). Solange das Modul an ist, bleibt der Monitor 0 und der Rahmen aus.
 * Ab 26.3 (SDL) reicht {@code setExclusiveFullscreen(false)} – das Spiel macht das Randlos-Fenster selbst.
 */
@Mixin(com.mojang.blaze3d.platform.Window.class)
public abstract class WindowMixin {
	//? if >=26.3 {
	/*@Shadow private boolean exclusiveFullscreen;

	@Shadow public abstract void setExclusiveFullscreen(boolean exclusive);

	@Inject(method = "updateFullscreenIfChanged", at = @At("HEAD"))
	private void trsclient$borderless(CallbackInfo ci) {
		boolean wantExclusive = !BorderlessHooks.want();
		if (this.exclusiveFullscreen != wantExclusive) this.setExclusiveFullscreen(wantExclusive);
	}
	*///?}

	//? if <26.3 {
	@Shadow private boolean fullscreen;
	@Shadow private boolean actuallyFullscreen;
	@Shadow private int x;
	@Shadow private int y;
	@Shadow private int width;
	@Shadow private int height;
	//? if >=1.21.9 {
	/*@Shadow private long handle;
	*///?} else
	@Shadow private long window;

	/** Letzter Modul-Stand. Weicht er ab und ist Vollbild an, läuft {@code setMode} noch einmal. */
	private boolean trsclient$want = true;

	//? if >=26.1 {
	/*@Inject(method = "updateFullscreenIfChanged", at = @At("HEAD"))
	private void trsclient$reapply(CallbackInfo ci) {
		trsclient$force();
	}
	*///?} elif >=1.21.2 {
	/*@Inject(method = "updateDisplay", at = @At("HEAD"))
	private void trsclient$reapply(com.mojang.blaze3d.TracyFrameCapture tracy, CallbackInfo ci) {
		trsclient$force();
	}
	*///?} elif >=1.15 {
	@Inject(method = "updateDisplay", at = @At("HEAD"))
	private void trsclient$reapply(CallbackInfo ci) {
		trsclient$force();
	}
	//?} else {
	/*@Inject(method = "updateDisplay", at = @At("HEAD"))
	private void trsclient$reapply(boolean vsync, CallbackInfo ci) {
		trsclient$force();
	}
	*///?}

	private void trsclient$force() {
		boolean want = BorderlessHooks.want();
		if (want == trsclient$want) return;
		trsclient$want = want;
		if (this.fullscreen) this.actuallyFullscreen = !this.fullscreen;
	}

	/**
	 * Beim erneuten Betreten aus dem randlosen Vollbild ist {@code glfwGetWindowMonitor} 0, also würde Vanilla die
	 * gemerkte Fenstergröße mit der Vollbildgröße überschreiben. Vorher die echte Lage zurückschreiben.
	 */
	@Inject(method = "setMode", at = @At("HEAD"))
	private void trsclient$remember(CallbackInfo ci) {
		if (!this.fullscreen) return;
		if (org.lwjgl.glfw.GLFW.glfwGetWindowMonitor(trsclient$handle()) != 0L) return;
		if (BorderlessState.active() && BorderlessGlfw.memory().saved()) {
			this.x = BorderlessGlfw.memory().x();
			this.y = BorderlessGlfw.memory().y();
			this.width = BorderlessGlfw.memory().width();
			this.height = BorderlessGlfw.memory().height();
		} else if (!BorderlessState.active()) {
			BorderlessGlfw.memory().track(this.x, this.y, this.width, this.height);
		}
	}

	@Redirect(method = "setMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V", ordinal = 0))
	private void trsclient$monitor0(long window, long monitor, int x, int y, int w, int h, int rate) {
		trsclient$monitor(window, monitor, x, y, w, h, rate);
	}

	@Redirect(method = "setMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V", ordinal = 1))
	private void trsclient$monitor1(long window, long monitor, int x, int y, int w, int h, int rate) {
		trsclient$monitor(window, monitor, x, y, w, h, rate);
	}

	private void trsclient$monitor(long window, long monitor, int x, int y, int w, int h, int rate) {
		if (monitor != 0L && BorderlessHooks.want()) {
			int[] mx = new int[1];
			int[] my = new int[1];
			org.lwjgl.glfw.GLFW.glfwGetMonitorPos(monitor, mx, my);
			org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(window, BorderlessGlfw.DECORATED, BorderlessGlfw.FALSE);
			BorderlessState.mark(true);
			org.lwjgl.glfw.GLFW.glfwSetWindowMonitor(window, 0L, mx[0], my[0], w, h, BorderlessGlfw.DONT_CARE);
			return;
		}
		if (BorderlessState.active()) {
			org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(window, BorderlessGlfw.DECORATED, BorderlessGlfw.TRUE);
			BorderlessState.mark(false);
		}
		org.lwjgl.glfw.GLFW.glfwSetWindowMonitor(window, monitor, x, y, w, h, rate);
	}

	private long trsclient$handle() {
		//? if >=1.21.9 {
		/*return this.handle;
		*///?} else
		return this.window;
	}
	//?}
}
