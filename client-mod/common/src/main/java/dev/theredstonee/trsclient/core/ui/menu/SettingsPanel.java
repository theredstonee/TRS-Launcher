package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.module.ChoiceSetting;
import dev.theredstonee.trsclient.core.module.ColorSetting;
import dev.theredstonee.trsclient.core.module.KeySetting;
import dev.theredstonee.trsclient.core.module.NumberSetting;
import dev.theredstonee.trsclient.core.module.Setting;
import dev.theredstonee.trsclient.core.module.TextSetting;
import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;

import java.util.List;

/**
 * Zeichnet Einstellungen als Zeilen: Schalter, Schieberegler, Farbwähler (mit Deckkraft und
 * Chroma), Auswahlliste und Tastenbelegung. Wird vom Menü und vom HUD-Editor genutzt.
 * Immediate Mode: beim Zeichnen werden die Klickflächen in {@link Hits} registriert.
 */
public final class SettingsPanel {
	public static final int ROW_BOOL = 20;
	public static final int ROW_NUMBER = 30;
	public static final int PICKER_HEIGHT = 64;
	private static final int CHOICE_ITEM = 14;

	/** Aufgeklappter Farbwähler/Auswahlliste (nur eine Einstellung gleichzeitig). */
	private Setting expanded;
	/** Einstellung, die gerade auf einen Tastendruck wartet. */
	private KeySetting capturing;
	/** Textfeld, das gerade bearbeitet wird (Auto-GG-Nachricht, Text-Hotkeys). */
	private TextSetting editingText;
	private final TextInput textInput = new TextInput(TextSetting.MAX_LENGTH);
	/** Farbton/Sättigung/Helligkeit des offenen Farbwählers. */
	private final float[] hsv = new float[3];

	public void reset() {
		expanded = null;
		capturing = null;
		stopEditing();
	}

	public boolean isCapturing() {
		return capturing != null;
	}

	/** Höhe aller Zeilen (inkl. aufgeklapptem Bereich). */
	public int height(List<Setting> settings) {
		int h = 0;
		for (int i = 0; i < settings.size(); i++) h += rowHeight(settings.get(i));
		return h;
	}

	public int rowHeight(Setting s) {
		int base = s instanceof NumberSetting ? ROW_NUMBER : ROW_BOOL;
		if (s == expanded) {
			if (s instanceof ColorSetting) return base + PICKER_HEIGHT;
			if (s instanceof ChoiceSetting) return base + ((ChoiceSetting<?>) s).size() * CHOICE_ITEM + 4;
		}
		return base;
	}

	/**
	 * Zeichnet die Zeilen ab (x, y) in der Breite w.
	 * @return y unter der letzten Zeile
	 */
	public int draw(Canvas c, Hits hits, List<Setting> settings, int x, int y, int w, int mouseX, int mouseY) {
		int line = ColorMath.withAlpha(Theme.get().border, 150);
		for (int i = 0; i < settings.size(); i++) {
			Setting s = settings.get(i);
			int rh = rowHeight(s);
			boolean rowHover = inside(mouseX, mouseY, x - 2, y, w + 4, s instanceof NumberSetting ? ROW_NUMBER : ROW_BOOL);
			// Die Zeile unter der Maus liegt leicht erhöht – so ist klar, wozu ein Regler gehört.
			if (rowHover) Redstone.block(c, x - 2, y, w + 4, (s instanceof NumberSetting ? ROW_NUMBER : ROW_BOOL) - 1, ColorMath.withAlpha(Theme.get().surfaceHover, 150));
			row(c, hits, s, x, y, w, rh, mouseX, mouseY);
			if (i < settings.size() - 1) c.fill(x, y + rh - 1, x + w, y + rh, line);
			y += rh;
		}
		return y;
	}

	private void row(Canvas c, Hits hits, Setting s, int x, int y, int w, int rowHeight, int mx, int my) {
		Theme t = Theme.get();
		int right = x + w;
		// Textzeilen haben rechts ein breites Eingabefeld – dann den Namen früher abschneiden.
		int labelWidth = s instanceof TextSetting ? w - textFieldWidth(c, s, w) - 8 : w - 90;
		Paint.textClipped(c, s.label(), x + 2, y + 6, labelWidth, t.text, false);
		if (s instanceof BoolSetting) {
			BoolSetting b = (BoolSetting) s;
			int bw = 24;
			int bx = right - bw;
			boolean hover = inside(mx, my, bx - 4, y, bw + 4, 18);
			Paint.toggle(c, bx, y + 4, bw, 12, b.get() ? 1f : 0f, hover);
			hits.add(bx - 4, y, bw + 4, 18, new Toggle(b));
		} else if (s instanceof NumberSetting) {
			number(c, hits, (NumberSetting) s, x, y, w, mx, my);
		} else if (s instanceof ColorSetting) {
			color(c, hits, (ColorSetting) s, x, y, w, rowHeight, mx, my);
		} else if (s instanceof ChoiceSetting) {
			choice(c, hits, (ChoiceSetting<?>) s, x, y, w, mx, my);
		} else if (s instanceof KeySetting) {
			key(c, hits, (KeySetting) s, x, y, w, mx, my);
		} else if (s instanceof TextSetting) {
			text(c, hits, (TextSetting) s, x, y, w, mx, my);
		}
	}

	private void number(Canvas c, Hits hits, final NumberSetting n, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		Paint.textRight(c, n.display(), x + w, y + 6, t.dustOn, false);
		final int sliderX = x + 2;
		final int sliderW = Math.max(20, w - 4);
		int sliderY = y + 17;
		boolean hover = inside(mx, my, sliderX - 2, sliderY - 4, sliderW + 4, 12);
		Paint.slider(c, sliderX, sliderY - 4, sliderW, 10, (float) n.fraction(), hover);
		hits.addDrag(sliderX - 3, sliderY - 6, sliderW + 6, 14, new Hits.Drag() {
			@Override
			public void to(double mouseX, double mouseY) {
				n.setFraction((mouseX - sliderX) / (double) sliderW);
			}
		});
	}

	private void key(Canvas c, Hits hits, final KeySetting k, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		String label = capturing == k ? "Taste drücken …" : (k.isBound() ? keyLabel.label(k.get()) : "—");
		int bw = Math.max(34, c.textWidth(label) + 12);
		int bx = x + w - bw;
		boolean hover = inside(mx, my, bx, y + 2, bw, 14);
		if (capturing == k) {
			Redstone.lamp(c, bx, y + 2, bw, 15, 1f, 0f);
			Paint.textCentered(c, label, bx + bw / 2, y + 6, t.lampTextLit, false);
		} else {
			Redstone.keycap(c, bx, y + 2, bw, label, hover);
		}
		hits.add(bx, y + 2, bw, 14, new Runnable() {
			@Override
			public void run() {
				capturing = capturing == k ? null : k;
			}
		});
		hits.add(bx, y + 2, bw, 14, 1, new Runnable() {
			@Override
			public void run() {
				k.unbind();
				capturing = null;
			}
		});
	}

	/** Freitext-Zeile (Auto-GG-Nachricht, Text-Hotkeys): Klick öffnet das Feld, Tippen schreibt direkt. */
	private void text(Canvas c, Hits hits, final TextSetting s, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		boolean editing = editingText == s;
		int bw = textFieldWidth(c, s, w);
		int bx = x + w - bw;
		boolean hover = inside(mx, my, bx, y + 2, bw, 14);
		Redstone.well(c, bx, y + 2, bw, 15, editing ? t.accent : (hover ? t.textDim : t.border));
		if (editing) Redstone.glow(c, bx, y + 2, bw, 15, t.glow, 0.5f);
		String shown = editing ? textInput.text() : s.get();
		boolean placeholder = shown.isEmpty();
		if (placeholder) shown = s.placeholder();
		c.text(c.clip(shown, bw - 8), bx + 4, y + 6, placeholder ? t.textDim : t.text, false);
		if (editing && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cursorX = bx + 4 + Math.min(bw - 8, c.textWidth(textInput.text()));
			c.fill(cursorX, y + 5, cursorX + 1, y + 14, t.dustOn);
		}
		hits.add(bx, y + 2, bw, 14, new Runnable() {
			@Override
			public void run() {
				startEditing(s);
			}
		});
	}

	/** Breite des Eingabefelds einer Textzeile. */
	private static int textFieldWidth(Canvas c, Setting s, int w) {
		// Die Beschriftung bekommt, was sie braucht – das Feld bleibt aber mindestens 70 px breit.
		return Math.max(70, Math.min(w / 2, w - c.textWidth(s.label()) - 14));
	}

	private void startEditing(TextSetting s) {
		editingText = s;
		textInput.setText(s.get());
		textInput.setFocused(true);
		capturing = null;
	}

	/** Beendet die Texteingabe (der Wert steht schon in der Einstellung). */
	public boolean stopEditing() {
		if (editingText == null) return false;
		editingText = null;
		textInput.setFocused(false);
		return true;
	}

	public boolean isEditingText() {
		return editingText != null;
	}

	/** Zeichen in ein offenes Textfeld; true = verbraucht. */
	public boolean typeChar(char c) {
		if (editingText == null) return false;
		if (!textInput.type(c)) return false;
		editingText.set(textInput.text());
		return true;
	}

	/** Sondertaste in einem offenen Textfeld (Enter/Escape schließen); true = verbraucht. */
	public boolean typeKey(UiKey key) {
		if (editingText == null) return false;
		if (key == UiKey.ENTER || key == UiKey.ESCAPE) {
			stopEditing();
			return true;
		}
		if (!textInput.key(key)) return false;
		editingText.set(textInput.text());
		return true;
	}

	/** Übersetzt Tastennamen in Anzeigenamen (setzt der Bildschirm einmalig). */
	public interface KeyLabel {
		String label(String keyName);
	}

	private KeyLabel keyLabel = new KeyLabel() {
		@Override
		public String label(String keyName) {
			return keyName;
		}
	};

	public void setKeyLabel(KeyLabel keyLabel) {
		if (keyLabel != null) this.keyLabel = keyLabel;
	}

	/** Tastendruck beim Belegen; true = verbraucht. */
	public boolean captureKey(int rawKey, UiKey key, MenuHost host) {
		if (capturing == null) return false;
		if (key == UiKey.ESCAPE) {
			capturing = null;
			return true;
		}
		if (key == UiKey.BACKSPACE || key == UiKey.DELETE) {
			capturing.unbind();
			capturing = null;
			return true;
		}
		String name = host.keyNameOf(rawKey);
		if (name != null) capturing.set(name);
		capturing = null;
		return true;
	}

	private void choice(Canvas c, Hits hits, final ChoiceSetting<?> choice, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		String label = choice.display() + "  ▾";
		int bw = Math.min(Math.max(46, c.textWidth(label) + 12), Math.max(46, w - 60));
		int bx = x + w - bw;
		boolean hover = inside(mx, my, bx, y + 2, bw, 14);
		boolean open = expanded == choice;
		Redstone.stone(c, bx, y + 2, bw, 15, hover || open ? t.surfaceHover : t.surfaceHigh, open ? t.accent : (hover ? t.textDim : t.border));
		Paint.textCentered(c, c.clip(label, bw - 6), bx + bw / 2, y + 6, t.text, false);
		hits.add(bx, y + 2, bw, 14, new Runnable() {
			@Override
			public void run() {
				expanded = expanded == choice ? null : choice;
			}
		});
		if (!open) return;
		c.flush();
		int listY = y + ROW_BOOL;
		int listH = choice.size() * CHOICE_ITEM + 4;
		Redstone.block(c, bx - 2, listY, bw + 4, listH, t.border);
		c.fill(bx - 1, listY + 1, bx + bw + 1, listY + listH - 1, t.background);
		for (int i = 0; i < choice.size(); i++) {
			final int index = i;
			int iy = listY + 2 + i * CHOICE_ITEM;
			boolean itemHover = inside(mx, my, bx - 2, iy, bw + 4, CHOICE_ITEM);
			boolean active = choice.index() == i;
			if (itemHover || active) {
				Redstone.block(c, bx - 1, iy, bw + 2, CHOICE_ITEM, active ? ColorMath.withAlpha(t.accent, 60) : t.surfaceHover);
				if (active) c.fill(bx - 1, iy + 2, bx + 1, iy + CHOICE_ITEM - 2, t.dustOn);
			}
			c.text(c.clip(choice.optionLabel(i), bw - 8), bx + 4, iy + 3, active ? t.dustOn : t.text, false);
			hits.add(bx - 2, iy, bw + 4, CHOICE_ITEM, new Runnable() {
				@Override
				public void run() {
					choice.setIndex(index);
					expanded = null;
				}
			});
		}
	}

	private void color(Canvas c, Hits hits, final ColorSetting color, int x, int y, int w, int rowHeight, int mx, int my) {
		Theme t = Theme.get();
		int sw = 30;
		int sx = x + w - sw;
		boolean hover = inside(mx, my, sx, y + 2, sw, 14);
		boolean open = expanded == color;
		Redstone.block(c, sx, y + 2, sw, 15, open || hover ? t.accent : t.border);
		checker(c, sx + 1, y + 3, sw - 2, 13);
		c.fill(sx + 1, y + 3, sx + sw - 1, y + 16, color.argb());
		if (color.chroma()) Paint.textCentered(c, "RGB", sx + sw / 2, y + 6, ColorMath.contrastText(color.argb()), false);
		hits.add(sx, y + 2, sw, 14, new Runnable() {
			@Override
			public void run() {
				if (expanded == color) {
					expanded = null;
				} else {
					expanded = color;
					float[] v = ColorMath.rgbToHsv(color.rgb());
					hsv[0] = v[0];
					hsv[1] = v[1];
					hsv[2] = v[2];
				}
			}
		});
		if (open) {
			c.flush();
			picker(c, hits, color, x, y + ROW_BOOL, w, mx, my);
		}
	}

	/** Farbwähler: Sättigungs-/Helligkeitsfeld, Farbtonleiste, Deckkraft, Chroma und Palette. */
	private void picker(Canvas c, Hits hits, final ColorSetting color, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		Redstone.block(c, x, y, w, PICKER_HEIGHT - 4, t.border);
		c.fill(x + 1, y + 1, x + w - 1, y + PICKER_HEIGHT - 5, t.deep);

		final int fieldX = x + 6;
		final int fieldY = y + 6;
		final int fieldW = Math.max(30, Math.min(80, w - 90));
		final int fieldH = 44;
		int step = 2;
		for (int px = 0; px < fieldW; px += step) {
			float s = px / (float) (fieldW - 1);
			for (int py = 0; py < fieldH; py += step) {
				float v = 1f - py / (float) (fieldH - 1);
				c.fill(fieldX + px, fieldY + py, fieldX + Math.min(px + step, fieldW), fieldY + Math.min(py + step, fieldH),
						0xFF000000 | ColorMath.hsvToRgb(hsv[0], s, v));
			}
		}
		Paint.outline(c, fieldX - 1, fieldY - 1, fieldW + 2, fieldH + 2, t.border);
		int markX = fieldX + Math.round(hsv[1] * (fieldW - 1));
		int markY = fieldY + Math.round((1 - hsv[2]) * (fieldH - 1));
		Paint.outline(c, markX - 2, markY - 2, 5, 5, 0xFFFFFFFF);
		hits.addDrag(fieldX - 2, fieldY - 2, fieldW + 4, fieldH + 4, new Hits.Drag() {
			@Override
			public void to(double mouseX, double mouseY) {
				hsv[1] = ColorMath.clamp01((float) ((mouseX - fieldX) / (fieldW - 1)));
				hsv[2] = 1f - ColorMath.clamp01((float) ((mouseY - fieldY) / (fieldH - 1)));
				color.set(ColorMath.hsvToRgb(hsv[0], hsv[1], hsv[2]));
				color.setChroma(false);
			}
		});

		// Farbtonleiste
		final int hueX = fieldX + fieldW + 6;
		final int hueY = fieldY;
		final int hueW = 10;
		for (int py = 0; py < fieldH; py += 2) {
			int rgb = ColorMath.hsvToRgb(py / (float) fieldH, 0.9f, 1f);
			c.fill(hueX, hueY + py, hueX + hueW, hueY + Math.min(py + 2, fieldH), 0xFF000000 | rgb);
		}
		Paint.outline(c, hueX - 1, hueY - 1, hueW + 2, fieldH + 2, t.border);
		int hueMark = hueY + Math.round(hsv[0] * (fieldH - 1));
		c.fill(hueX - 2, hueMark, hueX + hueW + 2, hueMark + 1, 0xFFFFFFFF);
		hits.addDrag(hueX - 2, hueY - 2, hueW + 4, fieldH + 4, new Hits.Drag() {
			@Override
			public void to(double mouseX, double mouseY) {
				hsv[0] = ColorMath.clamp01((float) ((mouseY - hueY) / (double) fieldH));
				color.set(ColorMath.hsvToRgb(hsv[0], hsv[1], hsv[2]));
				color.setChroma(false);
			}
		});

		// Deckkraft
		int controlsX = hueX + hueW + 8;
		int controlsW = Math.max(40, x + w - 6 - controlsX);
		int cy = fieldY;
		if (color.alphaEditable()) {
			final int alphaX = controlsX;
			final int alphaW = controlsW;
			checker(c, alphaX, cy, alphaW, 8);
			for (int px = 0; px < alphaW; px += 2) {
				int a = Math.round(px / (float) alphaW * 255);
				c.fill(alphaX + px, cy, alphaX + Math.min(px + 2, alphaW), cy + 8, (a << 24) | color.rgb());
			}
			Paint.outline(c, alphaX - 1, cy - 1, alphaW + 2, 10, t.border);
			int am = alphaX + Math.round(color.alpha() / 255f * (alphaW - 1));
			c.fill(am, cy - 2, am + 1, cy + 10, 0xFFFFFFFF);
			hits.addDrag(alphaX - 2, cy - 3, alphaW + 4, 14, new Hits.Drag() {
				@Override
				public void to(double mouseX, double mouseY) {
					int a = Math.round(ColorMath.clamp01((float) ((mouseX - alphaX) / (double) alphaW)) * 255);
					color.setArgb(ColorMath.withAlpha(color.storedArgb(), a));
				}
			});
			cy += 14;
		}

		// Chroma
		boolean chromaHover = inside(mx, my, controlsX, cy, controlsW, 12);
		Paint.toggle(c, controlsX, cy, 22, 11, color.chroma() ? 1f : 0f, chromaHover);
		c.text(c.clip("Chroma", controlsW - 26), controlsX + 26, cy + 2, t.textDim, false);
		hits.add(controlsX, cy, controlsW, 12, new Runnable() {
			@Override
			public void run() {
				color.setChroma(!color.chroma());
			}
		});
		cy += 15;

		// Palette
		int px = controlsX;
		for (int i = 0; i < ColorSetting.PALETTE.length; i++) {
			final int rgb = ColorSetting.PALETTE[i];
			if (px + 10 > controlsX + controlsW) break;
			Redstone.block(c, px, cy, 9, 9, inside(mx, my, px, cy, 9, 9) ? t.text : t.border);
			c.fill(px + 1, cy + 1, px + 8, cy + 8, 0xFF000000 | rgb);
			hits.add(px, cy, 9, 9, new Runnable() {
				@Override
				public void run() {
					color.set(rgb);
					color.setChroma(false);
					float[] v = ColorMath.rgbToHsv(rgb);
					hsv[0] = v[0];
					hsv[1] = v[1];
					hsv[2] = v[2];
				}
			});
			px += 11;
		}
		cy += 12;
		Paint.textClipped(c, ColorSetting.toHexArgb(color.storedArgb()), controlsX, cy, controlsW, t.textDim, false);
	}

	/** Schachbrett als Untergrund für halbdurchsichtige Farben. */
	private static void checker(Canvas c, int x, int y, int w, int h) {
		for (int py = 0; py < h; py += 4) {
			for (int px = 0; px < w; px += 4) {
				boolean dark = ((px / 4) + (py / 4)) % 2 == 0;
				c.fill(x + px, y + py, x + Math.min(px + 4, w), y + Math.min(py + 4, h), dark ? 0xFF6A6A78 : 0xFF9A9AA8);
			}
		}
	}

	/** Schließt aufgeklappte Bereiche; true, wenn etwas geschlossen wurde. */
	public boolean collapse() {
		boolean was = expanded != null || capturing != null;
		expanded = null;
		capturing = null;
		return was;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** Schaltet eine Ja/Nein-Einstellung um (eigene Klasse wegen Java 8 ohne Lambda-Zwang). */
	private static final class Toggle implements Runnable {
		private final BoolSetting setting;

		Toggle(BoolSetting setting) {
			this.setting = setting;
		}

		@Override
		public void run() {
			setting.toggle();
		}
	}

	/** Weicher Übergang eines Schalters (für Kacheln im Menü). */
	public static float toggleProgress(float current, boolean on, float dt) {
		return Anim.approach(current, on ? 1f : 0f, dt, 0.06f);
	}

	/** Zeichnet ein Symbol vor einer Zeile (kleine Hilfe für das Menü). */
	public static void rowIcon(Canvas c, String icon, int x, int y, int argb) {
		Icons.draw(c, icon, x, y, 1, argb);
	}
}
