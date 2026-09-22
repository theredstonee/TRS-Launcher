package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;

/**
 * Alle Module des TRS Clients mit ihren Standardwerten.
 * Versionsunabhängig – jede Minecraft-Version liefert nur Rendering und Hooks dazu.
 */
public final class TrsModules {
	public final ModuleRegistry registry = new ModuleRegistry();

	public final HudModule fps;
	public final HudModule cps;
	public final HudModule keystrokes;
	public final HudModule ping;
	public final Module zoom;
	public final Module fullbright;

	public final BoolSetting keystrokesShowCps;
	public final BoolSetting keystrokesShowSpace;
	public final NumberSetting zoomFactor;
	public final BoolSetting zoomSmooth;
	public final BoolSetting zoomScroll;
	public final BoolSetting zoomSlowMouse;

	public TrsModules() {
		fps = registry.register(new HudModule("fps", "FPS", "Bilder pro Sekunde", true,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.01)));
		cps = registry.register(new HudModule("cps", "CPS", "Klicks pro Sekunde (links | rechts)", true,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.075)));
		keystrokes = registry.register(new HudModule("keystrokes", "Tastenanzeige", "WASD, Maustasten und Leertaste", true,
				new HudPosition(HudAnchor.BOTTOM_RIGHT, -0.005, -0.08)));
		ping = registry.register(new HudModule("ping", "Ping", "Latenz zum Server (nicht im Einzelspieler)", true,
				new HudPosition(HudAnchor.TOP_LEFT, 0.005, 0.14)));
		zoom = registry.register(new Module("zoom", "Zoom", "Taste halten zum Heranzoomen", true));
		fullbright = registry.register(new Module("fullbright", "Fullbright", "Maximale Helligkeit überall", false));

		keystrokesShowCps = keystrokes.add(new BoolSetting("showCps", "CPS unter Maustasten", true));
		keystrokesShowSpace = keystrokes.add(new BoolSetting("showSpace", "Leertaste anzeigen", true));
		zoomFactor = zoom.add(new NumberSetting("factor", "Zoom-Faktor", 4.0, 2.0, 10.0, 0.5, "×"));
		zoomSmooth = zoom.add(new BoolSetting("smooth", "Weicher Übergang", true));
		zoomScroll = zoom.add(new BoolSetting("scroll", "Mausrad ändert Zoom", true));
		zoomSlowMouse = zoom.add(new BoolSetting("slowMouse", "Maus verlangsamen", true));
	}
}
