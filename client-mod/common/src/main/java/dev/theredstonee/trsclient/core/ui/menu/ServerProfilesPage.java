package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.hud.HudProfiles;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.profile.ServerPattern;
import dev.theredstonee.trsclient.core.profile.ServerProfiles;
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
 * Seite „Server-Profile“ im TRS-Menü: Liste der Profile (Name, Muster, aktiv), darunter das gewählte Profil zum
 * Bearbeiten (Muster, Einzelspieler, HUD-Profil, Abweichungen zurücksetzen) und „Aktuelles Setup für diesen Server
 * merken“. Immediate Mode wie das restliche Menü.
 */
public final class ServerProfilesPage {
	/** Knöpfe anderer Seiten (Modulseite) können diese Seite öffnen lassen. */
	private static volatile boolean openRequested;

	private final ServerProfiles profiles;
	private final Runnable click;
	private final Runnable save;
	private final TextInput nameInput = new TextInput(HudProfiles.MAX_NAME_LENGTH);
	private final TextInput patternInput = new TextInput(240);
	/** -1 = kein Umbenennen, -2 = neues Profil, sonst Index. */
	private int editingName = -1;
	private boolean editingPatterns;
	private int selected = -1;
	private String error;
	private String note;
	private long noteUntil;

	public ServerProfilesPage(ServerProfiles profiles, Runnable click, Runnable save) {
		this.profiles = profiles;
		this.click = click;
		this.save = save;
	}

	/** Beim nächsten Zeichnen des Menüs diese Seite zeigen. */
	public static void requestOpen() {
		openRequested = true;
	}

	/** Wurde das Öffnen angefordert? (setzt die Anforderung zurück) */
	public static boolean takeOpenRequest() {
		boolean r = openRequested;
		openRequested = false;
		return r;
	}

	/** Seite (wieder) betreten. */
	public void reset() {
		editingName = -1;
		editingPatterns = false;
		error = null;
		nameInput.setFocused(false);
		patternInput.setFocused(false);
		ServerProfiles.Profile active = profiles.active();
		selected = active != null ? profiles.all().indexOf(active) : (profiles.size() > 0 ? Math.min(Math.max(selected, 0), profiles.size() - 1) : -1);
	}

	public void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		if (selected >= profiles.size()) selected = profiles.size() - 1;
		// Status: wo bin ich, was ist aktiv?
		ServerProfiles.Profile active = profiles.active();
		String status;
		if (!profiles.inWorld()) status = I18n.tr("serverProfiles.status.noWorld");
		else status = I18n.tr("serverProfiles.status", ServerProfiles.contextLabel(profiles.context()),
				active != null ? active.name() : I18n.tr("serverProfiles.standard"));
		Paint.textClipped(c, status, x + 2, y + 2, w - 4, t.textDim, false);

		int ry = y + 14;
		if (profiles.inWorld()) {
			String remember = active != null ? I18n.tr("serverProfiles.rememberActive", active.name()) : I18n.tr("serverProfiles.remember");
			int bw = Math.min(w, c.textWidth(remember) + 24);
			boolean hover = inside(mx, my, x, ry, bw, 17);
			Paint.button(c, x, ry, bw, 17, c.clip(remember, bw - 12), true, hover);
			hits.add(x, ry, bw, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					String e = profiles.rememberCurrent();
					if (e != null) {
						error = e;
					} else {
						error = null;
						ServerProfiles.Profile a = profiles.active();
						selected = a != null ? profiles.all().indexOf(a) : selected;
						say(I18n.tr("serverProfiles.remembered"));
						save.run();
					}
				}
			});
			ry += 22;
		}

		// Liste
		int rowH = 20;
		int detailH = selected >= 0 ? 66 : 0;
		int listBottom = y + h - 34 - detailH;
		for (int i = 0; i < profiles.size() && ry + rowH <= listBottom; i++) {
			final int index = i;
			ServerProfiles.Profile p = profiles.get(i);
			boolean isActive = p == active;
			boolean isSelected = i == selected;
			boolean rowHover = inside(mx, my, x, ry, w, rowH - 2);
			int fill = isSelected ? ColorMath.lerp(t.surface, t.accent, 0.12f) : rowHover ? t.surfaceHover : t.surface;
			if (isActive) Redstone.glow(c, x, ry, w, rowH - 2, t.glow, 0.35f);
			Redstone.stone(c, x, ry, w, rowH - 2, fill, isSelected ? ColorMath.lerp(t.border, t.accent, 0.75f) : t.border);
			Redstone.pip(c, x + 6, ry + 5, 7, isActive ? 1f : 0f);
			if (editingName == index) {
				drawInput(c, hits, nameInput, x + 18, ry + 2, w - 60, mx, my);
			} else {
				int nameW = Math.min(c.textWidth(p.name()), (w - 60) / 2);
				Paint.textClipped(c, p.name(), x + 18, ry + 5, nameW, isActive ? t.text : ColorMath.lerp(t.textDim, t.text, 0.5f), false);
				Paint.textClipped(c, summary(p), x + 24 + nameW, ry + 5, w - 66 - nameW, t.textDim, false);
				hits.add(x, ry, w - 40, rowH - 2, new Runnable() {
					@Override
					public void run() {
						click.run();
						selected = index;
						editingPatterns = false;
						error = null;
					}
				});
			}
			int bx = x + w - 18;
			boolean delHover = inside(mx, my, bx, ry + 2, 14, 14);
			Paint.iconButton(c, bx, ry + 2, 14, "trash", delHover, false);
			hits.add(bx, ry + 2, 14, 14, new Runnable() {
				@Override
				public void run() {
					click.run();
					profiles.delete(index);
					if (selected >= profiles.size()) selected = profiles.size() - 1;
					editingName = -1;
					editingPatterns = false;
					error = null;
					save.run();
				}
			});
			bx -= 17;
			boolean editHover = inside(mx, my, bx, ry + 2, 14, 14);
			Paint.iconButton(c, bx, ry + 2, 14, "pencil", editHover, false);
			hits.add(bx, ry + 2, 14, 14, new Runnable() {
				@Override
				public void run() {
					click.run();
					editingName = index;
					editingPatterns = false;
					error = null;
					nameInput.setText(profiles.get(index).name());
					nameInput.setFocused(true);
				}
			});
			ry += rowH;
		}

		// Neues Profil
		if (editingName == -2) {
			drawInput(c, hits, nameInput, x, ry + 1, w - 70, mx, my);
			int okX = x + w - 64;
			boolean okHover = inside(mx, my, okX, ry, 64, 17);
			Paint.button(c, okX, ry, 64, 17, I18n.tr("common.create"), true, okHover);
			hits.add(okX, ry, 64, 17, new Runnable() {
				@Override
				public void run() {
					confirmName();
				}
			});
			ry += 21;
		} else if (profiles.canCreate()) {
			String label = I18n.tr("serverProfiles.new");
			int bw = Math.min(w, c.textWidth(label) + 30);
			boolean hover = inside(mx, my, x, ry, bw, 17);
			Paint.button(c, x, ry, bw, 17, "", false, hover);
			Icons.draw(c, "plus", x + 7, ry + 4, 1, hover ? t.dustOn : t.text);
			Paint.textClipped(c, label, x + 20, ry + 4 - (hover ? 1 : 0), bw - 24, t.text, false);
			hits.add(x, ry, bw, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					editingName = -2;
					editingPatterns = false;
					error = null;
					nameInput.setText(profiles.suggestName());
					nameInput.setFocused(true);
				}
			});
			ry += 21;
		}

		if (selected >= 0 && selected < profiles.size()) details(c, hits, x, y + h - 34 - detailH + 4, w, mx, my);

		String bottom = error != null ? error : note != null && System.currentTimeMillis() < noteUntil ? note : I18n.tr("serverProfiles.note");
		List<String> lines = Paint.wrap(c, bottom, w - 4);
		int ly = y + h - 9 - (Math.min(3, lines.size()) - 1) * 10;
		for (int i = 0; i < Math.min(3, lines.size()); i++) {
			Paint.textClipped(c, lines.get(i), x + 2, ly + i * 10, w - 4, error != null ? t.dustOn : t.textDim, false);
		}
	}

	/** Bearbeiten des gewählten Profils: Muster, Einzelspieler, HUD-Profil, Abweichungen. */
	private void details(Canvas c, Hits hits, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		final int index = selected;
		ServerProfiles.Profile p = profiles.get(index);
		Redstone.dustH(c, x, x + w, y, t.dustOff, 0f);
		int ly = y + 5;
		String patternsLabel = I18n.tr("serverProfiles.patterns");
		int lw = Math.min(90, c.textWidth(patternsLabel) + 6);
		c.text(c.clip(patternsLabel, lw - 4), x + 2, ly + 4, t.textDim, false);
		String spLabel = I18n.tr("serverProfiles.singleplayerShort");
		int spW = Math.min(100, c.textWidth(spLabel) + 14);
		int fx = x + lw, fw = w - lw - spW - 6;
		if (editingPatterns) {
			drawInput(c, hits, patternInput, fx, ly, fw, mx, my);
		} else {
			boolean hover = inside(mx, my, fx, ly, fw, 15);
			Redstone.well(c, fx, ly, fw, 15, hover ? t.accent : t.border);
			String text = p.patterns().isEmpty() ? I18n.tr("serverProfiles.patternsHint") : patternsDisplay(p);
			Paint.textClipped(c, text, fx + 4, ly + 4, fw - 8, p.patterns().isEmpty() ? t.textDim : t.text, false);
			hits.add(fx, ly, fw, 15, new Runnable() {
				@Override
				public void run() {
					editingPatterns = true;
					editingName = -1;
					patternInput.setText(profiles.patternsText(index));
					patternInput.setFocused(true);
				}
			});
		}
		int okX = x + w - spW;
		if (editingPatterns) {
			boolean okHover = inside(mx, my, okX, ly - 1, spW, 17);
			Paint.button(c, okX, ly - 1, spW, 17, c.clip(I18n.tr("serverProfiles.apply"), spW - 8), true, okHover);
			hits.add(okX, ly - 1, spW, 17, new Runnable() {
				@Override
				public void run() {
					confirmPatterns();
				}
			});
		} else {
			boolean sp = p.patterns().contains(ServerPattern.SINGLEPLAYER);
			boolean spHover = inside(mx, my, okX, ly - 1, spW, 17);
			Paint.button(c, okX, ly - 1, spW, 17, c.clip(spLabel, spW - 8), sp, spHover);
			hits.add(okX, ly - 1, spW, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					profiles.toggleSingleplayer(index);
					save.run();
				}
			});
		}

		ly += 21;
		String hudName = p.hudProfile() == null ? I18n.tr("serverProfiles.hudNone") : p.hudProfile();
		String hudLabel = I18n.tr("serverProfiles.hud", hudName);
		int hw = Math.min(w / 2 - 3, c.textWidth(hudLabel) + 20);
		boolean hudHover = inside(mx, my, x, ly, hw, 17);
		Paint.button(c, x, ly, hw, 17, c.clip(hudLabel, hw - 10), false, hudHover);
		hits.add(x, ly, hw, 17, new Runnable() {
			@Override
			public void run() {
				click.run();
				profiles.cycleHudProfile(index);
				save.run();
			}
		});
		String modsLabel = I18n.tr("serverProfiles.modules", p.moduleCount());
		int rx = x + hw + 6;
		String reset = I18n.tr("serverProfiles.reset");
		int resetW = Math.min(90, c.textWidth(reset) + 20);
		Paint.textClipped(c, modsLabel, rx, ly + 5, w - hw - resetW - 14, t.textDim, false);
		if (p.moduleCount() > 0) {
			int bx = x + w - resetW;
			boolean rh = inside(mx, my, bx, ly, resetW, 17);
			Paint.button(c, bx, ly, resetW, 17, reset, false, rh);
			hits.add(bx, ly, resetW, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					profiles.clearModules(index);
					save.run();
				}
			});
		}
		ly += 21;
		Paint.textClipped(c, I18n.tr("serverProfiles.editHint"), x + 2, ly + 2, w - 4, t.textDim, false);
	}

	private static String patternsDisplay(ServerProfiles.Profile p) {
		StringBuilder sb = new StringBuilder();
		for (String s : p.patterns()) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(ServerProfiles.patternLabel(s));
		}
		return sb.toString();
	}

	private static String summary(ServerProfiles.Profile p) {
		if (p.patterns().isEmpty()) return I18n.tr("serverProfiles.noPatterns");
		return patternsDisplay(p);
	}

	private void drawInput(Canvas c, Hits hits, final TextInput input, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		Redstone.well(c, x, y, w, 15, t.accent);
		String text = input.text();
		int avail = w - 8;
		// Lange Muster: das Ende (mit Cursor) sichtbar halten.
		while (c.textWidth(text) > avail && text.length() > 1) text = text.substring(1);
		c.text(text, x + 4, y + 4, t.text, false);
		if (input.focused() && (System.currentTimeMillis() / 500) % 2 == 0) {
			int caret = x + 4 + c.textWidth(text);
			c.fill(Math.min(caret, x + w - 3), y + 4, Math.min(caret + 1, x + w - 2), y + 12, t.dustOn);
		}
		hits.add(x, y, w, 15, new Runnable() {
			@Override
			public void run() {
				input.setFocused(true);
			}
		});
	}

	private void confirmName() {
		String name = nameInput.text();
		String e = editingName == -2 ? profiles.create(name) : profiles.rename(editingName, name);
		if (e != null) {
			error = e;
			return;
		}
		if (editingName == -2) selected = profiles.size() - 1;
		error = null;
		editingName = -1;
		nameInput.setFocused(false);
		click.run();
		save.run();
	}

	private void confirmPatterns() {
		String e = profiles.setPatterns(selected, patternInput.text());
		if (e != null) {
			error = e;
			return;
		}
		error = null;
		editingPatterns = false;
		patternInput.setFocused(false);
		click.run();
		save.run();
	}

	private void say(String text) {
		note = text;
		noteUntil = System.currentTimeMillis() + 4000;
	}

	/** Tasten; true = verbraucht. */
	public boolean keyPressed(UiKey key) {
		if (editingName != -1 && nameInput.focused()) {
			if (key == UiKey.ENTER) {
				confirmName();
				return true;
			}
			if (key == UiKey.ESCAPE) {
				editingName = -1;
				error = null;
				nameInput.setFocused(false);
				return true;
			}
			return nameInput.key(key);
		}
		if (editingPatterns && patternInput.focused()) {
			if (key == UiKey.ENTER) {
				confirmPatterns();
				return true;
			}
			if (key == UiKey.ESCAPE) {
				editingPatterns = false;
				error = null;
				patternInput.setFocused(false);
				return true;
			}
			return patternInput.key(key);
		}
		return false;
	}

	/** Zeichen; true = verbraucht. */
	public boolean charTyped(char ch) {
		if (editingName != -1 && nameInput.focused()) return nameInput.type(ch);
		if (editingPatterns && patternInput.focused()) return patternInput.type(ch);
		return false;
	}

	/** Tippt gerade jemand? (dann keine Suche starten) */
	public boolean typing() {
		return (editingName != -1 && nameInput.focused()) || (editingPatterns && patternInput.focused());
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
