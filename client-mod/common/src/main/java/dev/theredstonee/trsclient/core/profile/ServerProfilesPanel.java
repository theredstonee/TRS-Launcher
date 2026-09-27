package dev.theredstonee.trsclient.core.profile;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;
import dev.theredstonee.trsclient.core.ui.menu.ServerProfilesPage;

/** Bereich auf der Modulseite „Server-Profile“: Stand (aktives Profil) und „Server-Profile verwalten“. */
public final class ServerProfilesPanel implements ModulePanel {
	private final ServerProfiles profiles;

	public ServerProfilesPanel(ServerProfiles profiles) {
		this.profiles = profiles;
	}

	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mouseX, int mouseY, final Runnable click) {
		Theme t = Theme.get();
		String manage = I18n.tr("serverProfiles.manage");
		int bw = Math.min(w, c.textWidth(manage) + 24);
		boolean hover = mouseX >= x && mouseX < x + bw && mouseY >= y + 2 && mouseY < y + 19;
		Paint.button(c, x, y + 2, bw, 17, manage, true, hover);
		hits.add(x, y + 2, bw, 17, new Runnable() {
			@Override
			public void run() {
				click.run();
				ServerProfilesPage.requestOpen();
			}
		});
		ServerProfiles.Profile active = profiles.active();
		String line = I18n.tr("serverProfiles.count", profiles.size());
		if (profiles.inWorld()) {
			line += " · " + I18n.tr("serverProfiles.status", ServerProfiles.contextLabel(profiles.context()),
					active != null ? active.name() : I18n.tr("serverProfiles.standard"));
		}
		return Paint.paragraph(c, line, x + 2, y + 24, w - 4, 10, t.textDim) + 4;
	}
}
