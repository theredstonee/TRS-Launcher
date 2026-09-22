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
	/** Textschatten. */
	public final BoolSetting textShadow;
	/** Deckkraft des Hintergrunds in Prozent (nur wenn {@link #background} an). */
	public final NumberSetting backgroundOpacity;

	/** Farbe (RGB) des HUD-Hintergrunds – Tiefenschiefer wie das Menü. */
	public static final int BACKGROUND_RGB = 0x17171E;

	public HudModule(String id, String name, String description, boolean defaultEnabled, HudPosition defaultPosition) {
		super(id, name, description, defaultEnabled);
		this.defaultPosition = defaultPosition.copy();
		this.position = defaultPosition.copy();
		this.textColor = add(new ColorSetting("textColor", "Textfarbe", 0xFFFFFF));
		this.textShadow = add(new BoolSetting("shadow", "Textschatten", true));
		this.background = add(new BoolSetting("background", "Hintergrund", true));
		this.backgroundOpacity = add(new NumberSetting("backgroundOpacity", "Hintergrund-Deckkraft", 56, 0, 100, 1, "", "%"));
		this.scale = add(new NumberSetting("scale", "Größe", 1.0, 0.5, 2.0, 0.05, "×"));
	}

	/** Aktuelle Position (veränderbares Objekt – im Editor direkt gesetzt). */
	public HudPosition position() {
		return position;
	}

	public void resetPosition() {
		position.set(defaultPosition);
	}

	/** Standard-Position (z. B. für "Zurücksetzen" im HUD-Editor). */
	public HudPosition defaultPosition() {
		return defaultPosition.copy();
	}

	/**
	 * Setzt Position und Aussehen (Größe, Farbe, Schatten, Hintergrund) zurück –
	 * An/Aus und modulspezifische Einstellungen bleiben.
	 */
	public void resetLayout() {
		resetPosition();
		textColor.reset();
		textShadow.reset();
		background.reset();
		backgroundOpacity.reset();
		scale.reset();
	}

	/** Hintergrundfarbe (ARGB) oder 0, wenn kein Hintergrund gezeichnet wird. */
	public int backgroundArgb() {
		if (!background.get()) return 0;
		int a = (int) Math.round(backgroundOpacity.get() * 2.55);
		if (a <= 0) return 0;
		return (Math.min(255, a) << 24) | BACKGROUND_RGB;
	}

	/** Text mit Schatten zeichnen? */
	public boolean shadow() {
		return textShadow.get();
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
