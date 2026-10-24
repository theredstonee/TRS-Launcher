package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?} elif >=1.16
import com.mojang.blaze3d.vertex.PoseStack;
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
 * <p>
 * Mit RRLS („Remove Reloading Screen“): RRLS zeichnet den Bildschirm selbst und ruft render() nur mit einer Attrappe
 * auf (ohne Text-Sammler – der Bildschirm darunter warf darin ab 1.21.11 bei jeder Beschriftung), damit Zeiten und
 * Abschluss weiterlaufen. Dann bleibt der Ablauf bei uns, gezeichnet wird nur die TRS-Fläche – über ein eigenes
 * Zeichenziel auf dem Oberflächen-Zustand des Bildes; die Ladeleiste von RRLS bleibt weg (RrlsOverlayMixin).
 * Nie auf Kosten des Abschlusses und nie Mojang: wirft das Zeichnen, kommt für dieses Bild nur die schlichte
 * TRS-Fläche, Ausblenden und Abschluss laufen weiter – sonst bliebe das Overlay für immer stehen (das Server-Paket
 * würde nie geladen → „Verbinden…“ hängt).
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

	/** Zeichnen dieser Überblendung schon einmal fehlgeschlagen (nur einmal ins Log). */
	@Unique
	private boolean trsclient$failed;

	private static float trsclient$clamp(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	/**
	 * Zeichnet Minecraft selbst in dieses Ziel? RRLS &amp; Co. rufen render() mit einer Attrappe (Unterklasse) auf,
	 * nur damit Zeiten und Abschluss weiterlaufen – darin lässt sich nichts zeichnen (ab 1.21.11 fehlt ihr der
	 * Text-Sammler: jede Beschriftung des Bildschirms darunter wirft).
	 */
	private static boolean trsclient$ownTarget(Object target) {
		//? if >=26.1 {
		/*return target != null && target.getClass() == GuiGraphicsExtractor.class;
		*///?} elif >=1.20 {
		/*return target != null && target.getClass() == GuiGraphics.class;
		*///?} else
		return true;
	}

	/**
	 * Eigenes Zeichenziel für dieses Bild, wenn render() nur eine Attrappe bekam (RRLS): ab 1.21.6 auf dem
	 * Oberflächen-Zustand des laufenden Bildes, 1.20–1.21.5 direkt in den Puffer. null = geht hier nicht.
	 */
	private Gfx trsclient$ownGfx() {
		//? if >=26.2 {
		/*return Gfx.of(new GuiGraphicsExtractor(minecraft, minecraft.gameRenderer.gameRenderState().guiRenderState, 0, 0));
		*///?} elif >=26.1 {
		/*return Gfx.of(new GuiGraphicsExtractor(minecraft, minecraft.gameRenderer.getGameRenderState().guiRenderState, 0, 0));
		*///?} elif >=1.21.11 {
		/*return Gfx.of(new GuiGraphics(minecraft, ((GuiStateAccessor) minecraft.gameRenderer).trsclient$guiRenderState(), 0, 0));
		*///?} elif >=1.21.6 {
		/*return Gfx.of(new GuiGraphics(minecraft, ((GuiStateAccessor) minecraft.gameRenderer).trsclient$guiRenderState()));
		*///?} elif >=1.20 {
		/*return Gfx.of(new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource()));
		*///?} else
		return null;
	}

	/**
	 * Ein Bild der Überblendung (statt Vanilla). Rückgabe false = Menü-Stil aus, Vanilla zeichnet.
	 * {@code under} zeichnet den Bildschirm darunter (true) bzw. nur die verschobenen Untertitel (false).
	 */
	private boolean trsclient$frame(Object target, Gfx gfx, int mouseX, int mouseY, float partialTick, VanillaMenus.OverlayUnder under) {
		if (!VanillaMenus.resourceOverlayStyled()) return false;
		// Nur eine Attrappe (RRLS): den Bildschirm hat der Mod schon gezeichnet – wir zeichnen nur die TRS-Fläche,
		// über ein eigenes Zeichenziel (null: geht in dieser Version nicht, dann nur der Ablauf).
		boolean hidden = !trsclient$ownTarget(target);
		if (hidden) {
			try {
				gfx = trsclient$ownGfx();
			} catch (RuntimeException | LinkageError e) {
				gfx = null;
			}
		}
		// Dieselbe Uhr wie Vanilla (fadeOutStart setzt ab 1.21.9 LoadingOverlay#tick): Util.getMillis() läuft je nach
		// Version über den GLFW-Zeitgeber, nicht über System.nanoTime() – mit falscher Uhr sprang die Deckkraft beim
		// Ausblenden sofort auf 0 und zeigte zwei Sekunden lang das Mojang-Rot mit Logo und Ladebalken.
		long now = Util.getMillis();
		//? if <1.17 {
		if (fadeIn && (reload.isApplying() || Mc.screen() != null) && fadeInStart == -1L) fadeInStart = now;
		//?} else
		/*if (fadeIn && fadeInStart == -1L) fadeInStart = now;*/
		float out = fadeOutStart > -1L ? (now - fadeOutStart) / 1000.0F : -1.0F;
		float in = fadeInStart > -1L ? (now - fadeInStart) / 500.0F : -1.0F;
		// Ausblenden: unsere Fläche verblasst in einer Sekunde; Einblenden (F3+T, Pakete wechseln, Server-Paket): eine
		// halbe Sekunde über dem offenen Bildschirm; erster Start: deckend.
		float alpha = out >= 1.0F ? 1.0F - trsclient$clamp(out - 1.0F) : (fadeIn ? trsclient$clamp(in) : 1.0F);
		currentProgress = trsclient$clamp(currentProgress * 0.95F + reload.getActualProgress() * 0.050000012F);
		if (gfx != null) {
			try {
				// Darunter: beim Ausblenden der nächste Bildschirm, beim Einblenden der offene (danach nur die Untertitel).
				if (!hidden) {
					if (out >= 1.0F) under.draw(true, 0, 0, partialTick);
					else if (fadeIn) under.draw(in < 1.0F, mouseX, mouseY, partialTick);
				}
				if (!VanillaMenus.resourceOverlay(gfx, gfx.width(), gfx.height(), currentProgress, alpha)) trsclient$plain(gfx, alpha);
			} catch (RuntimeException | LinkageError e) {
				// Nie hängen bleiben, nie Mojang: für dieses Bild nur die schlichte TRS-Fläche; Ausblenden und Abschluss
				// laufen unten weiter (Vanilla jetzt nachzuschieben zeichnete den Bildschirm doppelt – Absturz).
				if (!trsclient$failed) {
					trsclient$failed = true;
					TrsClient.LOGGER.warn("[TRS Client] Ladebildschirm: Zeichnen fehlgeschlagen, schlichte Fläche", e);
				}
				trsclient$plain(gfx, alpha);
			}
			if (hidden) {
				gfx.flush();
				VanillaMenus.hiddenOverlayDrawn();
			}
		}
		VanillaMenus.overlayAlpha = alpha;
		VanillaMenus.overlayPhase = out >= 1.0F ? "out" : (out >= 0.0F ? "hold" : (fadeIn && in < 1.0F ? "in" : "load"));
		if (out >= 2.0F) {
			//? if >=26.2 {
			/*minecraft.gui.setOverlay(null);
			*///?} else
			minecraft.setOverlay(null);
		}
		trsclient$finish(in);
		return true;
	}

	/** Schlichte TRS-Fläche (dunkel, Deckkraft wie die Überblendung) – wenn der Stil nicht zeichnen kann. */
	private static void trsclient$plain(Gfx gfx, float alpha) {
		try {
			int a = Math.round(alpha * 255f);
			if (a > 0) gfx.fill(0, 0, gfx.width(), gfx.height(), a << 24 | 0x120D0E);
		} catch (RuntimeException | LinkageError ignored) {
			// nichts mehr zu retten – der Ablauf läuft trotzdem weiter
		}
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
		if (trsclient$frame(g, Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.extractRenderStateWithTooltipAndSubtitles(g, x, y, pt);
			else mc.gui.hud.extractDeferredSubtitles();
		})) ci.cancel();
	}
	*///?} elif >=26.1 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		final Minecraft mc = minecraft;
		if (trsclient$frame(g, Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.extractRenderStateWithTooltipAndSubtitles(g, x, y, pt);
			else mc.gui.extractDeferredSubtitles();
		})) ci.cancel();
	}
	*///?} elif >=1.21.9 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		final Minecraft mc = minecraft;
		if (trsclient$frame(g, Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.renderWithTooltipAndSubtitles(g, x, y, pt);
			else mc.gui.renderDeferredSubtitles();
		})) ci.cancel();
	}
	*///?} elif >=1.21.6 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(g, Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.renderWithTooltip(g, x, y, pt);
		})) ci.cancel();
	}
	*///?} elif >=1.20 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(g, Gfx.of(g), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (!screen || s == null) return;
			// Vanilla ruft hier render() statt renderWithTooltip() – Ladebildschirme selbst nachziehen.
			s.render(g, x, y, pt);
			VanillaMenus.afterRender(s, Gfx.of(g), x, y);
		})) ci.cancel();
	}
	*///?} elif >=1.19.4 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(pose, Gfx.of(pose), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (!screen || s == null) return;
			s.render(pose, x, y, pt);
			VanillaMenus.afterRender(s, Gfx.of(pose), x, y);
		})) ci.cancel();
	}
	*///?} elif >=1.16 {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(final PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(pose, Gfx.of(pose), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			// Bis 1.19.3 stylt LoadingScreenMixin die Ladebildschirme direkt in render().
			if (screen && s != null) s.render(pose, x, y, pt);
		})) ci.cancel();
	}
	//?} else {
	/*@Inject(method = "render(IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$overlay(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (trsclient$frame(null, Gfx.of(), mouseX, mouseY, partialTick, (screen, x, y, pt) -> {
			Screen s = Mc.screen();
			if (screen && s != null) s.render(x, y, pt);
		})) ci.cancel();
	}
	*///?}
}
