package dev.theredstonee.trsclient.core.sync;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

/** Stand des Client-Syncs auf der Seite „TRS-Online“ (Lampe + Text + „Jetzt synchronisieren“). */
public final class SyncPanel implements ModulePanel {
	private final ClientSync sync;

	public SyncPanel(ClientSync sync) {
		this.sync = sync;
	}

	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mouseX, int mouseY, final Runnable click) {
		Theme t = Theme.get();
		ClientSync.Status s = sync.status();
		String text;
		float lit;
		switch (s) {
			case SYNCED:
				long ago = Math.max(0, (System.currentTimeMillis() - sync.lastSyncAt()) / 60_000L);
				text = ago < 1 ? I18n.tr("sync.status.justNow") : I18n.tr("sync.status.minutesAgo", ago);
				lit = 1f;
				break;
			case SYNCING:
				text = I18n.tr("sync.status.syncing");
				lit = 0.6f;
				break;
			case WAITING:
				text = I18n.tr("sync.status.waiting");
				lit = 0.3f;
				break;
			case OFFLINE:
				text = I18n.tr("sync.status.offline");
				lit = 0f;
				break;
			case NEWER_CLIENT:
				text = I18n.tr("sync.status.newer");
				lit = 0.3f;
				break;
			case NO_CONSENT:
				text = I18n.tr("sync.status.noConsent");
				lit = 0f;
				break;
			default:
				text = I18n.tr("sync.status.off");
				lit = 0f;
				break;
		}
		Redstone.pip(c, x, y + 2, 7, lit);
		boolean button = s == ClientSync.Status.SYNCED || s == ClientSync.Status.OFFLINE;
		String label = I18n.tr("sync.now");
		int bw = button ? c.textWidth(label) + 16 : 0;
		Paint.textClipped(c, I18n.tr("sync.title") + ": " + text, x + 12, y + 1, w - 12 - bw - 6, t.text, false);
		if (button) {
			int bx = x + w - bw;
			boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= y - 3 && mouseY < y + 12;
			Paint.button(c, bx, y - 3, bw, 15, label, false, hover);
			hits.add(bx, y - 3, bw, 15, new Runnable() {
				@Override
				public void run() {
					click.run();
					sync.syncNow();
				}
			});
		}
		int ny = Paint.paragraph(c, I18n.tr("sync.what"), x, y + 14, Math.min(w, 360), 10, t.textDim);
		return ny + 6;
	}
}
