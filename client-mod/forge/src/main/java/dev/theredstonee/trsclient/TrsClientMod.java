package dev.theredstonee.trsclient;

import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
//? if <1.20.5 {
/*import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
*///?}

/**
 * Forge-Einstieg. Reine Client-Mod: auf einem dedizierten Server passiert nichts
 * (und es werden keine Client-Klassen geladen).
 * Forge-API wird nur für die Tastenbelegungen (RegisterKeyMappingsEvent) und bis 1.20.4 für das HUD genutzt.
 */
@Mod(TrsClient.MOD_ID)
public final class TrsClientMod {
	// Konstruktor-Injektion des Lade-Kontexts gibt es erst in neueren Forge-Builds; bis 1.20.4 daher
	// FMLJavaModLoadingContext.get() (funktioniert in allen 46.x–49.x-Builds).
	//? if >=1.20.5 {
	public TrsClientMod(FMLJavaModLoadingContext context) {
	//?} else
	/*@SuppressWarnings("removal") public TrsClientMod() {*/
		if (FMLEnvironment.dist != Dist.CLIENT) return;
		TrsClient.init();

		// Tastenbelegungen: EventBus 7 (ab Forge 1.21.6) hat pro Event einen eigenen Bus –
		// bis 1.21.11 je Mod-Bus-Gruppe (getBus), ab 26.1 ein globaler (BUS).
		//? if >=26.1 {
		/*RegisterKeyMappingsEvent.BUS.addListener(TrsKeys::register);
		*///?} elif >=1.21.6 {
		/*RegisterKeyMappingsEvent.getBus(context.getModBusGroup()).addListener(TrsKeys::register);
		*///?} elif >=1.20.5 {
		context.getModEventBus().addListener((RegisterKeyMappingsEvent e) -> TrsKeys.register(e));
		//?} else
		/*FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterKeyMappingsEvent e) -> TrsKeys.register(e));*/

		// "Konfigurieren" in Forges Mod-Liste öffnet das TRS-Menü.
		ConfigScreenHandler.ConfigScreenFactory configScreen =
				new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new TrsMenuScreen(parent));
		// ModLoadingContext.get() gibt es in allen Versionen (1.21 hat keine Instanzmethode am Kontext).
		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, () -> configScreen);

		// HUD bis 1.20.4: ForgeGui ersetzt Gui#render komplett → Forge-Event nach dem HUD.
		// Ab 1.20.6 zeichnet GuiMixin am Ende von Gui#render.
		//? if <1.20.5
		/*MinecraftForge.EVENT_BUS.addListener((RenderGuiEvent.Post e) -> TrsClient.get().hud().render(Gfx.of(e.getGuiGraphics())));*/
	}
}
