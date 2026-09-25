package dev.theredstonee.trsclient.core.ui.menus;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.FriendsView;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * „Server-Info“ aus dem Pausenmenü: Name, Adresse (kopierbar), Server-Software, Version, Ping als Staub-Balken,
 * Spielerzahl, Dimension, Position und – falls bekannt – TRS-Freunde auf demselben Server.
 */
public final class ServerInfoUi extends WindowUi {
	private final ServerInfoHost host;
	private final TrsOnline online;
	private final FaceCache faces;
	private long copiedAt;

	public ServerInfoUi(ServerInfoHost host, TrsOnline online) {
		this.host = host;
		this.online = online;
		this.faces = FaceCache.shared(host.userAgent());
		I18n.refresh();
	}

	@Override
	protected String title() {
		return I18n.tr("menus.serverInfo.title");
	}

	@Override
	protected void playClick() {
		host.playClick();
	}

	@Override
	protected void onClosed() {
		host.closeScreen();
	}

	@Override
	protected int[] size(int width, int height) {
		return new int[]{Math.min(width - 16, 320), Math.min(height - 16, 250)};
	}

	@Override
	protected void content(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Theme t = Theme.get();
		ServerInfoHost.Info info = host.info();
		if (info == null) info = new ServerInfoHost.Info();
		if (online != null) online.friends().want(Friends.Interest.BACKGROUND, false);

		List<String[]> rows = new ArrayList<>();
		if (info.singleplayer) {
			rows.add(new String[]{I18n.tr("menus.serverInfo.world"), info.name});
			rows.add(new String[]{I18n.tr("menus.serverInfo.mode"),
					I18n.tr(info.lan ? "menus.serverInfo.lan" : "menus.serverInfo.singleplayer")});
		} else {
			if (info.name != null) rows.add(new String[]{I18n.tr("menus.serverInfo.name"), info.name});
			rows.add(new String[]{I18n.tr("menus.serverInfo.address"), info.address == null ? "?" : info.address});
			if (info.brand != null) rows.add(new String[]{I18n.tr("menus.serverInfo.software"), info.brand});
		}
		if (info.version != null) rows.add(new String[]{I18n.tr("menus.serverInfo.version"), info.version});
		if (info.players >= 0) rows.add(new String[]{I18n.tr("menus.serverInfo.players"), String.valueOf(info.players)});
		if (info.dimension != null) rows.add(new String[]{I18n.tr("menus.serverInfo.dimension"), info.dimension});
		if (info.position != null) rows.add(new String[]{I18n.tr("menus.serverInfo.position"), info.position});
		if (info.serverPack) rows.add(new String[]{I18n.tr("menus.serverInfo.pack"), I18n.tr("common.enabled")});

		int labelW = 0;
		for (String[] r : rows) labelW = Math.max(labelW, c.textWidth(r[0]));
		labelW = Math.min(labelW + 8, w / 2);
		int cy = y;
		Redstone.well(c, x, cy, w, rows.size() * 13 + (info.singleplayer ? 8 : 22), t.border);
		cy += 5;
		for (String[] r : rows) {
			Paint.textClipped(c, r[0], x + 6, cy, labelW - 6, t.textDim, false);
			Paint.textClipped(c, r[1] == null ? "?" : r[1], x + labelW, cy, w - labelW - 8, t.text, false);
			cy += 13;
		}
		if (!info.singleplayer) {
			// Ping als Staub-Balken + Zahl
			Paint.textClipped(c, I18n.tr("menus.serverInfo.ping"), x + 6, cy, labelW - 6, t.textDim, false);
			MenuSkin.pingBars(c, x + labelW, cy - 1, info.ping, false, t.deep);
			Paint.textClipped(c, info.ping >= 0 ? info.ping + " ms" : "?", x + labelW + 16, cy, w - labelW - 24, t.text, false);
			cy += 17;
		} else {
			cy += 3;
		}
		cy += 6;

		// Knöpfe
		if (!info.singleplayer && info.address != null) {
			final String address = info.address;
			boolean copied = System.currentTimeMillis() - copiedAt < 2_000L;
			String label = I18n.tr(copied ? "menus.serverInfo.copied" : "menus.serverInfo.copy");
			int bw = Math.min(w, c.textWidth(label) + 20);
			button(c, x, cy, bw, 18, label, false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					if (host.copy(address)) copiedAt = System.currentTimeMillis();
				}
			});
			cy += 24;
		}

		// Freunde auf diesem Server
		if (!info.singleplayer && online != null && online.online()) {
			Friends.Snapshot s = online.friends().snapshot();
			List<FriendsView.Friend> here = s.view == null ? new ArrayList<FriendsView.Friend>() : s.view.onServer(info.address);
			String head = here.isEmpty() ? I18n.tr("menus.serverInfo.noFriends") : I18n.tr("menus.serverInfo.friends", here.size());
			Paint.textClipped(c, head, x, cy, w, t.textDim, false);
			cy += 12;
			int fx = x;
			for (FriendsView.Friend f : here) {
				int nw = Math.min(90, c.textWidth(f.name) + 22);
				if (fx + nw > x + w || cy + 12 > y + h) break;
				Faces.draw(c, faces.face(f.uuid, null), f.uuid, f.name, fx + 1, cy, 1, true);
				Paint.textClipped(c, f.name, fx + 12, cy, nw - 14, t.text, false);
				fx += nw;
			}
		}
	}
}
