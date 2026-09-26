package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.packs.resources.ReloadInstance;
//? if >=1.21.11 {
/*import net.minecraft.util.Util;
*///?} else
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} elif >=1.16
/*import com.mojang.blaze3d.vertex.PoseStack;*/
//? if >=1.15 && <1.21.9 {
import java.util.Optional;
import java.util.function.Consumer;
//?}

/**
 * Ressourcen laden (sonst Mojang-Logo): die ganze Überblendung im Redstone-Stil – Einblenden über dem
 * offenen Bildschirm, Laden, Ausblenden über dem nächsten Bildschirm. Vanilla zeichnet dabei gar nichts:
 * würde unsere Fläche nur darübergelegt, schiene das Rot/Logo beim halbdurchsichtigen Ein- und Ausblenden
 * durch. Der Ablauf (Zeiten, Fortschritt, Abschluss, Overlay entfernen) ist der von Minecraft – je Version
 * nachgebaut; ab 1.21.9 liegt der Abschluss in tick() und bleibt ganz bei Minecraft.
 * Nur Rechtecke – beim ersten Start ist die Schrift noch nicht geladen. Menü-Stil aus → Vanilla.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private ReloadInstance reload;
	//? if <1.15 {
	/*@Shadow
	@Final
	private Runnable onFinish;
	*///?} elif <1.21.9 {
	@Shadow
	@Final
	private Consumer<Optional<Throwable>> onFinish;
	//?}
	@Shadow
	@Final
	private boolean fadeIn;
	@Shadow
	private float currentProgress;
	@Shadow
	private long fadeOutStart;
	@Shadow
	private long fadeInStart;

	private static float trsclient$clamp(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	/**
	 * Ein Bild der Überblendung (statt Vanilla). Rückgabe false = Menü-Stil aus, Vanilla zeichnet.
	 * {@code under} zeichnet den Bildschirm darunter (true) bzw. nur die verschobenen Untertitel (false).
	 */
	private boolean trsclient$frame(Gfx gfx, int mouseX, int mouseY, float partialTick, VanillaMenus.OverlayUnder under) {
		if (!VanillaMenus.resourceOverlayStyled()) return false;
		// Dieselbe Uhr wie Vanilla (fadeOutStart setzt ab 1.21.9 LoadingOverlay#tick): Util.getMillis() läuft je nach
		// Version über den GLFW-Zeitgeber, nicht über System.nanoTime() – mit falscher Uhr sprang die Deckkraft beim
		// Ausblenden sofort auf 0 und zeigte zwei Sekunden lang das Mojang-Rot mit Logo und Ladebalken.
		long now = Util.getMillis();
		//? if <1.17 {
		/*if (fadeIn && (reload.isApplying() || Mc.screen() != null) && fadeInStart == -1L) fadeInStart = now;
		*///?} else
		if (fadeIn && fadeInStart == -1L) fadeInStart = now;
		float out = fadeOutStart > -1L ? (now - fadeOutStart) / 1000.0F : -1.0F;
		float in = fadeInStart > -1L ? (now - fadeInStart) / 500.0F : -1.0F;
		float alpha;
		if (out >= 1.0F) {
			// Ausblenden: der nächste Bildschirm darunter, unsere Fläche verblasst in einer Sekunde.
			under.draw(true, 0, 0, partialTick);
			alpha = 1.0F - trsclient$clamp(out - 1.0F);
		} else if (fadeIn) {
			// Einblenden (F3+T, Pakete wechseln, Server-Paket): eine halbe Sekunde über dem offenen Bildschirm.
			under.draw(in < 1.0F, mouseX, mouseY, partialTick);
			alpha = trsclient$clamp(in);
		} else {
			// Erster Start: deckend.
			alpha = 1.0F;
		}
		currentProgress = trsclient$clamp(currentProgress * 0.95F + reload.getActualProgress() * 0.050000012F);
		VanillaMenus.overlayAlpha = alpha;
		VanillaMenus.overlayPhase = out >= 1.0F ? "out" : (out >= 0.0F ? "hold" : (fadeIn && in < 1.0F ? "in" : "load"));
		if (!VanillaMenus.resourceOverlay(gfx, gfx.width(), gfx.height(), currentProgress, alpha)) {
			// Stil fehlgeschlagen: schlicht abdunkeln statt Mojang-Rot aufblitzen zu lassen.
			int a = Math.round(alpha * 255f);
			if (a > 0) gfx.fill(0, 0, gfx.width(), gfx.height(), a << 24 | 0x120D0E);
		}
		if (out >= 2.0F) {
			//? if >=26.2 {
			/*minecraft.gui.setOverlay(null);
			*///?} else
			minecraft.setOverlay(null);
		}
		trsclient$finish(in);
		return true;
	}

	/** Abschluss wie Vanilla bis 1.21.8 (in render); ab 1.21.9 erledigt das LoadingOverlay#tick selbst. */
	//? if <1.15 {
	/*private void trsclient$finish(float in) {
		if (fadeOutStart != -1L || !reload.isDone() || (fadeIn && in < 2.0F)) return;
		reload.checkExceptions();
		fadeOutStart = Util.getMillis();
		onFinish.run();
		Screen s = Mc.screen();
		if (s != null) s.init(minecraft, Mc.window().getGuiScaledWidth(), Mc.window().getGuiScaledHeight());
	}
	*///?} elif <1.21.9 {
	private void trsclient$finish(float in) {
		if (fadeOutStart != -1L || !reload.isDone() || (fadeIn && in < 2.0F)) return;
		// Wie Forge: Zeitpunkt vor dem Rückruf setzen (ein Rückruf, der neu lädt, kann sonst kreisen).
		fadeOutStart = Util.getMillis();
		try {
			reload.checkExceptions();
			onFinish.accept(Optional.empty());
		} catch (Throwable t) {
			onFinish.accept(Optional.of(t));
		}
		Screen s = Mc.screen();
		if (s != null) s.init(minecraft, Mc.window().getGuiScaledWidth(), Mc.window().getGuiScaledHeight());
	}
	//?} else {
	/*private void trsclient$finish(float in) {
		// ab 1.21.9: LoadingOverlay#tick
	}
	*///?}

	//? if >=26.2 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		final Minecraft mc = minecraft;
		if (trsclient$frame(Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.extractRenderStateWithTooltipAndSubtitles(g, x, y, pt);
			else mc.gui.hud.extractDeferredSubtitles();
		})) ci.cancel();
	}
	*///?} elif >=26.1 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		final Minecraft mc = minecraft;
		if (trsclient$frame(Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.extractRenderStateWithTooltipAndSubtitles(g, x, y, pt);
			else mc.gui.extractDeferredSubtitles();
		})) ci.cancel();
	}
	*///?} elif >=1.21.9 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		final Minecraft mc = minecraft;
		if (trsclient$frame(Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.renderWithTooltipAndSubtitles(g, x, y, pt);
			else mc.gui.renderDeferredSubtitles();
		})) ci.cancel();
	}
	*///?} elif >=1.21.6 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.renderWithTooltip(g, x, y, pt);
		})) ci.cancel();
	}
	*///?} elif >=1.20 {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (!screen || s == null) return;
			// Vanilla ruft hier render() statt renderWithTooltip() – Ladebildschirme selbst nachziehen.
			s.render(g, x, y, pt);
			VanillaMenus.afterRender(s, Gfx.of(g), x, y);
		})) ci.cancel();
	}
	//?} elif >=1.19.4 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(Gfx.of(pose), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (!screen || s == null) return;
			s.render(pose, x, y, pt);
			VanillaMenus.afterRender(s, Gfx.of(pose), x, y);
		})) ci.cancel();
	}
	*///?} elif >=1.16 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(Gfx.of(pose), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			// Bis 1.19.3 stylt LoadingScreenMixin die Ladebildschirme direkt in render().
			if (screen && s != null) s.render(pose, x, y, pt);
		})) ci.cancel();
	}
	*///?} else {
	/*@Inject(method = "render(IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(Gfx.of(), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.render(x, y, pt);
		})) ci.cancel();
	}
	*///?}
}
