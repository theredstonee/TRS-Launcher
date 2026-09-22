package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.hud.HudPosition;

/** Modul, das ein verschiebbares Element im HUD zeichnet. */
public class HudModule extends Module {
	private final HudPosition defaultPosition;
	private final HudPosition position;

	/** Gemeinsame HUD-Einstellungen. */
	public final NumberSetting scale;
	public final BoolSetting background;
	public final ColorSetting textColor;

	public HudModule(String id, String name, String description, boolean defaultEnabled, HudPosition defaultPosition) {
		super(id, name, description, defaultEnabled);
		this.defaultPosition = defaultPosition.copy();
		this.position = defaultPosition.copy();
		this.textColor = add(new ColorSetting("textColor", "Textfarbe", 0xFFFFFF));
		this.background = add(new BoolSetting("background", "Hintergrund", true));
		this.scale = add(new NumberSetting("scale", "Größe", 1.0, 0.5, 2.0, 0.25, "×"));
	}

	/** Aktuelle Position (veränderbares Objekt – im Editor direkt gesetzt). */
	public HudPosition position() {
		return position;
	}

	public void resetPosition() {
		position.set(defaultPosition);
	}

	@Override
	public boolean isHud() {
		return true;
	}

	@Override
	public void read(ModuleConfig config) {
		super.read(config);
		position.anchor = HudAnchor.parse(config.anchor, defaultPosition.anchor);
		position.offsetX = finiteOr(config.offsetX, config.anchor == null ? defaultPosition.offsetX : 0);
		position.offsetY = finiteOr(config.offsetY, config.anchor == null ? defaultPosition.offsetY : 0);
	}

	@Override
	public ModuleConfig write() {
		ModuleConfig config = super.write();
		config.anchor = position.anchor.name();
		config.offsetX = position.offsetX;
		config.offsetY = position.offsetY;
		return config;
	}

	@Override
	public void reset() {
		super.reset();
		resetPosition();
	}

	/** Versatz auf ±1 Bildschirm begrenzen, NaN/fehlend → Fallback. */
	private static double finiteOr(Double v, double fallback) {
		if (v == null || v.isNaN() || v.isInfinite()) return fallback;
		return Math.max(-1.0, Math.min(1.0, v));
	}
}
