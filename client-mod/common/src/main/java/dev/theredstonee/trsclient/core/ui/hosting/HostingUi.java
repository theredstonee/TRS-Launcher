package dev.theredstonee.trsclient.core.ui.hosting;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.HostingPlatform;
import dev.theredstonee.trsclient.core.hosting.PlayerRights;
import dev.theredstonee.trsclient.core.hosting.PublicLink;
import dev.theredstonee.trsclient.core.hosting.Rooms;
import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.menus.WindowUi;
import dev.theredstonee.trsclient.core.ui.social.Dialog;
import dev.theredstonee.trsclient.core.ui.social.Dialogs;
import dev.theredstonee.trsclient.core.ui.social.Kit;
import dev.theredstonee.trsclient.core.ui.social.SocialHost;

import java.util.List;

/**
 * „Welt hosten“ (Pausemenü): vor dem Öffnen die Einstellungen (Name, Spielmodus, Cheats, PvP, max. Spieler,
 * Sichtbarkeit, Backup), danach die Verwaltung: Beitrittscode (kopieren), Spieler mit Verbindungsweg und Rechten
 * (OP / Zuschauer / Bauen) + Entfernen/Sperren, Beitrittsanfragen (Annehmen/Ablehnen), Freunde einladen,
 * Einstellungen, öffentlicher Link (mit Warn-Dialog) und „Hosting beenden“.
 */
public final class HostingUi extends WindowUi {
	static final String[] MODES = { "survival", "creative", "adventure" };
	static final int TAB_PLAYERS = 0;
	static final int TAB_REQUESTS = 1;
	static final int TAB_INVITE = 2;
	static final int TAB_SETTINGS = 3;

	private final SocialHost host;
	private final Kit kit;
	private final FaceCache faces;
	private final TextInput name = new TextInput(Rooms.MAX_NAME);
	private int mode;
	private boolean cheats;
	private boolean pvp = true;
	private int maxPlayers = 8;
	private boolean friendsVisible = true;
	private boolean backup = true;
	private int tab;
	private int scroll;
	private int[] listArea = new int[4];
	private Dialog dialog;
	private int formFor = -1;

	public HostingUi(SocialHost host) {
		this.host = host;
		I18n.refresh();
		this.kit = new Kit(hits, new Runnable() {
			@Override
			public void run() {
				HostingUi.this.host.playClick();
			}
		});
		this.faces = FaceCache.shared(host.userAgent());
		HostingPlatform p = Hosting.platform();
		String world = p == null ? null : p.worldName();
		name.setText(world == null ? "" : world.length() > Rooms.MAX_NAME ? world.substring(0, Rooms.MAX_NAME) : world);
		loadFromRoom();
	}

	/** Auf dem Reiter „Anfragen“ öffnen (Schnelltaste bei einer Beitrittsanfrage). */
	public static HostingUi requests(SocialHost host) {
		HostingUi ui = new HostingUi(host);
		ui.tab = TAB_REQUESTS;
		return ui;
	}

	private void loadFromRoom() {
		Hosting h = Hosting.current();
		if (h == null) return;
		Rooms.Room r = h.room();
		HostingPlatform.Options o = h.options();
		if (r == null || o == null) return;
		name.setText(r.name);
		for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(o.gameMode)) mode = i;
		cheats = o.cheats;
		pvp = o.pvp;
		maxPlayers = o.maxPlayers;
		friendsVisible = !"invited".equals(h.visibility());
		formFor = r.id.hashCode();
	}

	@Override
	protected String title() {
		return I18n.tr("hosting.title");
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
		return new int[] { Math.min(width - 10, 460), Math.min(height - 10, 300) };
	}

	@Override
	protected void content(Canvas c, int x, int y, int w, int h, int mx, int my, float dt) {
		Hosting hosting = Hosting.current();
		Theme t = Theme.get();
		int screenW = window[0] * 2 + window[2];
		int screenH = window[1] * 2 + window[3];
		if (hosting == null || !hosting.signedIn()) {
			Redstone.well(c, x, y, w, h, t.border);
			List<String> lines = Paint.wrap(c, I18n.tr("hosting.error.offline"), w - 20);
			int ty = y + h / 2 - lines.size() * 5;
			for (String l : lines) {
				Paint.textCentered(c, l, x + w / 2, ty, t.textDim, false);
				ty += 11;
			}
		} else if (hosting.hostState() == Hosting.HostState.OPEN && hosting.room() != null) {
			Rooms.Room r = hosting.room();
			if (formFor != r.id.hashCode()) loadFromRoom();
			manage(c, hosting, r, x, y, w, h, mx, my);
		} else {
			setup(c, hosting, x, y, w, h, mx, my);
		}
		if (dialog != null) {
			if (dialog.closed()) dialog = null;
			else dialog.draw(c, kit, screenW, screenH, mx, my);
		}
	}

	// --- Einstellungen (vor dem Öffnen und Reiter „Einstellungen“) ---

	private void setup(Canvas c, final Hosting hosting, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		boolean busy = hosting.hostState() == Hosting.HostState.BACKUP || hosting.hostState() == Hosting.HostState.OPENING;
		int bottom = form(c, x, y, w, h - 24, mx, my, true);
		long now = System.currentTimeMillis();
		Hosting.Notice n = hosting.notice(now);
		int by = y + h - 18;
		String status = busy ? I18n.tr(hosting.hostState() == Hosting.HostState.BACKUP ? "hosting.state.backup" : "hosting.state.opening")
				: n != null ? n.text() : !hosting.canHost() ? I18n.tr("hosting.error.no_world") : null;
		if (status != null) {
			if (busy) Redstone.pip(c, x, by + 5, 8, (float) (0.5 + 0.5 * Math.sin(now / 160.0)));
			Paint.textClipped(c, status, x + (busy ? 12 : 0), by + 5, w - 110, n != null && n.error && !busy ? t.dustOn : t.text,
					false);
		} else if (bottom < by) {
			Paint.textClipped(c, I18n.tr("hosting.directNote"), x, by + 5, w - 110, t.textDim, false);
		}
		kit.button(c, x + w - 100, by, 100, 18, I18n.tr("hosting.host"), true, !busy && hosting.canHost(), mx, my,
				new Runnable() {
					@Override
					public void run() {
						Hosting.Request req = new Hosting.Request();
						req.name = name.text();
						req.options = options();
						req.visibility = friendsVisible ? "friends" : "invited";
						req.backup = backup;
						name.setFocused(false);
						hosting.host(req);
					}
				});
	}

	HostingPlatform.Options options() {
		HostingPlatform.Options o = new HostingPlatform.Options();
		o.gameMode = MODES[mode];
		o.cheats = cheats;
		o.pvp = pvp;
		o.maxPlayers = maxPlayers;
		return o;
	}

	/** Formular in zwei Spalten; Rückgabe: unterste benutzte y-Koordinate. */
	private int form(Canvas c, int x, int y, int w, int h, int mx, int my, boolean withBackup) {
		Theme t = Theme.get();
		boolean two = w >= 300;
		int colW = two ? (w - 12) / 2 : w;
		int lx = x;
		int ly = y;
		Paint.textClipped(c, I18n.tr("hosting.form.name"), lx, ly, colW, t.textDim, false);
		kit.input(c, name, lx, ly + 10, colW, 16, I18n.tr("hosting.form.nameHint"), null);
		ly += 32;
		Paint.textClipped(c, I18n.tr("hosting.form.gameMode"), lx, ly, colW, t.textDim, false);
		ly += 10;
		for (int i = 0; i < MODES.length; i++) {
			final int idx = i;
			kit.radio(c, lx, ly, colW, I18n.tr("hosting.mode." + MODES[i]), mode == i, mx, my, new Runnable() {
				@Override
				public void run() {
					mode = idx;
				}
			});
			ly += 14;
		}
		int rx = two ? x + colW + 12 : x;
		int ry = two ? y : ly + 6;
		kit.toggle(c, rx, ry, colW, I18n.tr("hosting.form.cheats"), cheats, mx, my, new Runnable() {
			@Override
			public void run() {
				cheats = !cheats;
			}
		});
		ry += 17;
		kit.toggle(c, rx, ry, colW, I18n.tr("hosting.form.pvp"), pvp, mx, my, new Runnable() {
			@Override
			public void run() {
				pvp = !pvp;
			}
		});
		ry += 17;
		Paint.textClipped(c, I18n.tr("hosting.form.maxPlayers"), rx, ry + 3, colW - 60, t.text, false);
		int sx = rx + colW - 54;
		kit.button(c, sx, ry, 16, 14, "-", false, maxPlayers > Rooms.MIN_PLAYERS, mx, my, new Runnable() {
			@Override
			public void run() {
				maxPlayers = Math.max(Rooms.MIN_PLAYERS, maxPlayers - 1);
			}
		});
		String mp = String.valueOf(maxPlayers);
		c.text(mp, sx + 27 - c.textWidth(mp) / 2, ry + 3, t.text, false);
		kit.button(c, sx + 38, ry, 16, 14, "+", false, maxPlayers < Rooms.MAX_PLAYERS, mx, my, new Runnable() {
			@Override
			public void run() {
				maxPlayers = Math.min(Rooms.MAX_PLAYERS, maxPlayers + 1);
			}
		});
		ry += 18;
		Paint.textClipped(c, I18n.tr("hosting.form.visibility"), rx, ry, colW, t.textDim, false);
		ry += 10;
		kit.radio(c, rx, ry, colW, I18n.tr("hosting.visibility.friends"), friendsVisible, mx, my, new Runnable() {
			@Override
			public void run() {
				friendsVisible = true;
			}
		});
		ry += 14;
		kit.radio(c, rx, ry, colW, I18n.tr("hosting.visibility.invited"), !friendsVisible, mx, my, new Runnable() {
			@Override
			public void run() {
				friendsVisible = false;
			}
		});
		ry += 16;
		if (withBackup) {
			kit.check(c, rx, ry, colW, I18n.tr("hosting.form.backup"), backup, true, mx, my, new Runnable() {
				@Override
				public void run() {
					backup = !backup;
				}
			});
			ry += 16;
		}
		return Math.max(ly, ry);
	}

	// --- Verwaltung ---

	private void manage(Canvas c, final Hosting hosting, final Rooms.Room r, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		long now = System.currentTimeMillis();
		// Kopf: Name, Code, Kopieren, Relay-Lampe
		final String code = r.prettyCode();
		String codeText = I18n.tr("hosting.code", code == null ? "?" : code);
		int cw = c.textWidth(codeText);
		Redstone.well(c, x, y, w, 20, t.accent);
		boolean relayOk = hosting.relayProblem() == null;
		Redstone.pip(c, x + 5, y + 6, 8, relayOk ? 1f : 0.2f);
		Paint.textClipped(c, r.name, x + 17, y + 6, w - cw - 100, t.text, false);
		c.text(codeText, x + w - 70 - cw, y + 6, ColorMath.lerp(t.text, t.lampOn, 0.6f), false);
		kit.button(c, x + w - 64, y + 2, 62, 16, I18n.tr("hosting.copy"), false, code != null, mx, my, new Runnable() {
			@Override
			public void run() {
				host.copy(code);
			}
		});
		int ty = y + 24;
		// Reiter
		List<Hosting.Guest> guests = hosting.guests();
		int requests = r.members("requested").size();
		String[] labels = { I18n.tr("hosting.tab.players", guests.size(), r.maxPlayers), I18n.tr("hosting.tab.requests"),
				I18n.tr("hosting.tab.invite"), I18n.tr("hosting.tab.settings") };
		int tabW = Math.max(50, Math.min(100, (w - 12) / 4));
		for (int i = 0; i < labels.length; i++) {
			final int idx = i;
			kit.tab(c, x + i * (tabW + 4), ty, tabW, 16, labels[i], tab == i, mx, my, new Runnable() {
				@Override
				public void run() {
					tab = idx;
					scroll = 0;
				}
			});
		}
		if (requests > 0) Kit.badge(c, x + tabW * 2 + 3, ty - 2, requests);
		int cy = ty + 20;
		int bottomH = 40;
		int ch = y + h - bottomH - cy - 2;
		switch (tab) {
			case TAB_REQUESTS:
				requestsTab(c, hosting, r, x, cy, w, ch, mx, my);
				break;
			case TAB_INVITE:
				inviteTab(c, hosting, r, x, cy, w, ch, mx, my);
				break;
			case TAB_SETTINGS:
				settingsTab(c, hosting, x, cy, w, ch, mx, my);
				break;
			default:
				playersTab(c, hosting, guests, x, cy, w, ch, mx, my);
		}
		// Unten: Meldung/Relay-Hinweis, öffentlicher Link, Beenden.
		int by = y + h - bottomH;
		Hosting.Notice n = hosting.notice(now);
		String line = n != null ? n.text() : !relayOk ? I18n.tr(hosting.relayProblem()) : null;
		if (line != null) Paint.textClipped(c, line, x, by + 2, w, n != null && n.error || n == null ? t.dustOn : t.text, false);
		publicLinkRow(c, hosting, x, by + 20, w - 110, mx, my);
		kit.button(c, x + w - 104, by + 20, 104, 18, I18n.tr("hosting.stop"), false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				dialog = new Dialogs.Confirm(I18n.tr("hosting.stop"), I18n.tr("hosting.stopConfirm"), null,
						I18n.tr("hosting.stop"), true, new Runnable() {
							@Override
							public void run() {
								hosting.stopHosting("closed");
							}
						});
			}
		});
	}

	private void publicLinkRow(Canvas c, Hosting hosting, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		final PublicLink link = hosting.publicLink();
		if (!PublicLink.supported()) {
			Paint.textClipped(c, I18n.tr("hosting.link.unsupported"), x, y + 5, w, t.textDim, false);
			return;
		}
		if (link.active()) {
			String label = link.state() == PublicLink.State.STARTING ? I18n.tr("hosting.link.starting", link.progress())
					: I18n.tr("hosting.link.active");
			int bw = Math.min(w, Math.max(c.textWidth(label) + 16, 90));
			badge(c, x, y, bw, 18, label);
			final String domain = link.domain();
			int rx = x + bw + 4;
			if (domain != null && w - (rx - x) > 150) {
				kit.button(c, rx, y, 70, 18, I18n.tr("hosting.link.copy"), false, true, mx, my, new Runnable() {
					@Override
					public void run() {
						host.copy(domain);
					}
				});
				rx += 74;
			}
			kit.button(c, rx, y, Math.min(90, x + w - rx), 18, I18n.tr("hosting.link.deactivate"), false, true, mx, my,
					new Runnable() {
						@Override
						public void run() {
							link.stop();
						}
					});
			return;
		}
		String err = link.error();
		if (err != null) Paint.textClipped(c, I18n.tr(err), x + 104, y + 5, w - 104, t.dustOn, false);
		kit.button(c, x, y, Math.min(100, w), 18, I18n.tr("hosting.link.enable"), false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				dialog = new PublicLinkDialog(link);
			}
		});
	}

	/** Rotes Abzeichen „Öffentlicher Link aktiv“. */
	public static void badge(Canvas c, int x, int y, int w, int h, String label) {
		int red = 0xFFD02020;
		Redstone.glow(c, x, y, w, h, red, 0.35f);
		Redstone.block(c, x, y, w, h, 0xFF7A0E0E);
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, red);
		float pulse = (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 250.0));
		c.fill(x + 4, y + h / 2 - 2, x + 8, y + h / 2 + 2, ColorMath.withAlpha(0xFFFFFFFF, Math.round(255 * pulse)));
		Paint.textClipped(c, label, x + 11, y + (h - 8) / 2, w - 13, 0xFFFFFFFF, false);
	}

	private void playersTab(Canvas c, final Hosting hosting, List<Hosting.Guest> guests, int x, int y, int w, int h,
			int mx, int my) {
		Theme t = Theme.get();
		Redstone.well(c, x, y, w, h, t.border);
		listArea = new int[] { x, y, w, h };
		int rowH = 24;
		int content = guests.size() * rowH;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, content - h + 4)));
		c.scissor(x + 1, y + 1, x + w - 1, y + h - 1);
		int ry = y + 2 - scroll;
		boolean narrow = w < 360;
		for (final Hosting.Guest g : guests) {
			if (ry + rowH > y && ry < y + h) {
				boolean vis = my >= y && my < y + h;
				int[] face = faces.face(g.uuid, null);
				Faces.draw(c, face, g.uuid, g.name, x + 5, ry + 4, 2, true);
				int tx = x + 26;
				Paint.textClipped(c, g.name, tx, ry + 3, narrow ? 80 : 110, t.text, false);
				String path = g.host ? I18n.tr("hosting.path.host") : pathLabel(g.path);
				int pathColor = g.path == PeerStream.Path.PUBLIC ? 0xFFE04040 : g.path == PeerStream.Path.DIRECT ? t.lampOn
						: t.textDim;
				Paint.textClipped(c, path, tx, ry + 13, narrow ? 80 : 110, g.host ? t.textDim : pathColor, false);
				if (!g.host) {
					int bx = x + w - 6;
					bx -= 18;
					final boolean publicGuest = g.path == PeerStream.Path.PUBLIC;
					kit.icon(c, bx, ry + 3, 16, "lock", false, vis ? mx : -1, vis ? my : -1, new Runnable() {
						@Override
						public void run() {
							dialog = banDialog(hosting, g, publicGuest);
						}
					});
					bx -= 18;
					kit.icon(c, bx, ry + 3, 16, "close", false, vis ? mx : -1, vis ? my : -1, new Runnable() {
						@Override
						public void run() {
							if (publicGuest) hosting.kickPublic(g.name);
							else hosting.kick(g.uuid, false, false);
						}
					});
					final PlayerRights r = g.rights;
					int cwid = narrow ? 44 : 64;
					bx -= cwid + 4;
					kit.check(c, bx, ry + 4, cwid, I18n.tr("hosting.right.build"), r.build && !r.spectator, !r.spectator && vis, mx, my,
							new Runnable() {
								@Override
								public void run() {
									hosting.setRights(g.uuid, r.withBuild(!r.build));
								}
							});
					bx -= cwid + 2;
					kit.check(c, bx, ry + 4, cwid, I18n.tr("hosting.right.spectator"), r.spectator, vis, mx, my, new Runnable() {
						@Override
						public void run() {
							hosting.setRights(g.uuid, r.withSpectator(!r.spectator));
						}
					});
					bx -= 36;
					kit.check(c, bx, ry + 4, 34, I18n.tr("hosting.right.op"), r.op, vis, mx, my, new Runnable() {
						@Override
						public void run() {
							hosting.setRights(g.uuid, r.withOp(!r.op));
						}
					});
				}
			}
			ry += rowH;
		}
		c.noScissor();
		if (guests.size() <= 1) {
			Paint.textCentered(c, I18n.tr("hosting.players.empty"), x + w / 2, y + h - 16, t.textDim, false);
		}
	}

	static String pathLabel(PeerStream.Path p) {
		if (p == null) return I18n.tr("hosting.path.unknown");
		switch (p) {
			case DIRECT:
				return I18n.tr("hosting.path.direct");
			case RELAY:
				return I18n.tr("hosting.path.relay");
			default:
				return I18n.tr("hosting.path.public");
		}
	}

	private Dialog banDialog(final Hosting hosting, final Hosting.Guest g, boolean publicGuest) {
		if (publicGuest) {
			return new Dialogs.Confirm(I18n.tr("hosting.ban.title", g.name), I18n.tr("hosting.ban.publicText"), null,
					I18n.tr("hosting.ban.button"), true, new Runnable() {
						@Override
						public void run() {
							hosting.kickPublic(g.name);
						}
					});
		}
		return new BanDialog(hosting, g.uuid, g.name);
	}

	private void requestsTab(Canvas c, final Hosting hosting, Rooms.Room r, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		Redstone.well(c, x, y, w, h, t.border);
		listArea = new int[] { x, y, w, h };
		List<Rooms.Member> req = r.members("requested");
		List<Rooms.Member> inv = r.members("invited");
		List<Rooms.Member> ban = r.members("banned");
		int rowH = 22;
		int content = (req.size() + inv.size() + ban.size()) * rowH;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, content - h + 4)));
		if (content == 0) {
			Paint.textCentered(c, I18n.tr("hosting.requests.empty"), x + w / 2, y + h / 2 - 4, t.textDim, false);
			return;
		}
		c.scissor(x + 1, y + 1, x + w - 1, y + h - 1);
		int ry = y + 2 - scroll;
		boolean vis = my >= y && my < y + h;
		int vx = vis ? mx : -1;
		int vy = vis ? my : -1;
		for (final Rooms.Member m : req) {
			memberRow(c, m, I18n.tr("hosting.state.requested"), x, ry, w);
			kit.button(c, x + w - 150, ry + 2, 72, 16, I18n.tr("hosting.accept"), true, vis, vx, vy, new Runnable() {
				@Override
				public void run() {
					hosting.accept(m.uuid);
				}
			});
			kit.button(c, x + w - 76, ry + 2, 72, 16, I18n.tr("hosting.decline"), false, vis, vx, vy, new Runnable() {
				@Override
				public void run() {
					hosting.decline(m.uuid);
				}
			});
			ry += rowH;
		}
		for (final Rooms.Member m : inv) {
			memberRow(c, m, I18n.tr("hosting.state.invited"), x, ry, w);
			kit.button(c, x + w - 96, ry + 2, 92, 16, I18n.tr("hosting.revoke"), false, vis, vx, vy, new Runnable() {
				@Override
				public void run() {
					hosting.revokeInvite(m.uuid);
				}
			});
			ry += rowH;
		}
		for (final Rooms.Member m : ban) {
			memberRow(c, m, I18n.tr("hosting.state.banned"), x, ry, w);
			kit.button(c, x + w - 96, ry + 2, 92, 16, I18n.tr("hosting.unban"), false, vis, vx, vy, new Runnable() {
				@Override
				public void run() {
					hosting.unban(m.uuid);
				}
			});
			ry += rowH;
		}
		c.noScissor();
	}

	private void memberRow(Canvas c, Rooms.Member m, String state, int x, int y, int w) {
		Theme t = Theme.get();
		int[] face = faces.face(m.uuid, null);
		Faces.draw(c, face, m.uuid, m.name, x + 5, y + 3, 2, true);
		Paint.textClipped(c, m.name, x + 26, y + 2, w - 190, t.text, false);
		Paint.textClipped(c, state, x + 26, y + 11, w - 190, t.textDim, false);
	}

	private void inviteTab(Canvas c, final Hosting hosting, Rooms.Room r, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		hosting.refreshFriendsRooms(false);
		Redstone.well(c, x, y, w, h, t.border);
		listArea = new int[] { x, y, w, h };
		List<Hosting.Friend> friends = hosting.friendsForInvite();
		if (friends.isEmpty()) {
			Paint.textCentered(c, I18n.tr("hosting.invite.empty"), x + w / 2, y + h / 2 - 4, t.textDim, false);
			return;
		}
		int rowH = 22;
		int content = friends.size() * rowH;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, content - h + 4)));
		c.scissor(x + 1, y + 1, x + w - 1, y + h - 1);
		int ry = y + 2 - scroll;
		boolean vis = my >= y && my < y + h;
		for (final Hosting.Friend f : friends) {
			if (ry + rowH > y && ry < y + h) {
				int[] face = faces.face(f.uuid, null);
				Faces.draw(c, face, f.uuid, f.name, x + 5, ry + 3, 2, true);
				Redstone.pip(c, x + 17, ry + 13, 5, f.inGame ? 1f : f.online ? 0.55f : 0f);
				Paint.textClipped(c, f.name, x + 26, ry + 2, w - 140, t.text, false);
				Paint.textClipped(c, I18n.tr(f.inGame ? "hosting.invite.inGame" : f.online ? "hosting.invite.online"
						: "hosting.invite.offline"), x + 26, ry + 11, w - 140, t.textDim, false);
				Rooms.Member m = r.member(f.uuid);
				String state = m == null ? null : m.state;
				boolean can = state == null || "requested".equals(state);
				String label = state == null ? I18n.tr("hosting.invite.button") : "requested".equals(state)
						? I18n.tr("hosting.accept") : I18n.tr("hosting.state." + state);
				kit.button(c, x + w - 96, ry + 2, 92, 16, label, can, can && vis, mx, my, new Runnable() {
					@Override
					public void run() {
						hosting.invite(f.uuid);
					}
				});
			}
			ry += rowH;
		}
		c.noScissor();
	}

	private void settingsTab(Canvas c, final Hosting hosting, int x, int y, int w, int h, int mx, int my) {
		form(c, x, y, w, h - 20, mx, my, false);
		kit.button(c, x + w - 100, y + h - 18, 100, 18, I18n.tr("hosting.apply"), true, true, mx, my, new Runnable() {
			@Override
			public void run() {
				name.setFocused(false);
				hosting.update(name.text(), options(), friendsVisible ? "friends" : "invited");
			}
		});
	}

	/** Sperren: nur diese Welt oder dauerhaft merken. */
	static final class BanDialog extends Dialog {
		private final Hosting hosting;
		private final String uuid;
		private final String name;
		private boolean remember;

		BanDialog(Hosting hosting, String uuid, String name) {
			this.hosting = hosting;
			this.uuid = uuid;
			this.name = name;
		}

		@Override
		protected int[] size(int screenW, int screenH) {
			return new int[] { Math.min(260, screenW - 20), 118 };
		}

		@Override
		protected String title() {
			return I18n.tr("hosting.ban.title", name);
		}

		@Override
		protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
			Theme t = Theme.get();
			int used = Paint.paragraph(c, I18n.tr("hosting.ban.text"), x, y, w, 10, t.text);
			kit.check(c, x, y + used + 4, w, I18n.tr("hosting.ban.remember"), remember, true, mx, my, new Runnable() {
				@Override
				public void run() {
					remember = !remember;
				}
			});
			int bw = Math.min(100, (w - 6) / 2);
			int by = y + h - 18;
			kit.button(c, x + w - bw, by, bw, 18, I18n.tr("hosting.ban.button"), false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					close();
					hosting.kick(uuid, true, remember);
				}
			});
			kit.button(c, x + w - bw * 2 - 6, by, bw, 18, I18n.tr("social.cancel"), false, true, mx, my, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});
		}
	}

	// --- Eingaben ---

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (dialog != null) {
			dialog.mouseClicked(mouseX, mouseY);
			super.mouseClicked(mouseX, mouseY, button);
			return true;
		}
		boolean hit = super.mouseClicked(mouseX, mouseY, button);
		if (!hit) name.setFocused(false);
		return hit;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (dialog != null) return dialog.mouseScrolled(mouseX, mouseY, amount);
		if (!Kit.inside(mouseX, mouseY, listArea[0], listArea[1], listArea[2], listArea[3])) return false;
		scroll = Math.max(0, scroll - (int) Math.round(amount * 20));
		return true;
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (dialog != null) {
			dialog.keyPressed(key, key == UiKey.PASTE ? host.paste() : null);
			if (dialog.closed()) dialog = null;
			return true;
		}
		if (name.focused()) {
			if (key == UiKey.ESCAPE || key == UiKey.ENTER) {
				name.setFocused(false);
				return true;
			}
			if (key == UiKey.PASTE) {
				String p = host.paste();
				if (p != null) for (int i = 0; i < p.length(); i++) name.type(p.charAt(i));
				return true;
			}
			return name.key(key);
		}
		return super.keyPressed(rawKey, key, shift);
	}

	@Override
	public boolean charTyped(char ch) {
		if (dialog != null) return dialog.charTyped(ch);
		return name.focused() && name.type(ch);
	}

	// --- Selbsttest ---

	public void testTab(int index) {
		tab = Math.max(0, Math.min(3, index));
	}

	/** Warn-Dialog des öffentlichen Links öffnen und zurückgeben. */
	public PublicLinkDialog testPublicLinkDialog() {
		Hosting h = Hosting.current();
		if (h == null) return null;
		PublicLinkDialog d = new PublicLinkDialog(h.publicLink());
		dialog = d;
		return d;
	}

	public void testCloseDialog() {
		dialog = null;
	}

	/** Mit den Formularwerten hosten (ohne Backup im Autotest, wenn gewünscht). */
	public void testHost(boolean withBackup) {
		Hosting h = Hosting.current();
		if (h == null) return;
		Hosting.Request req = new Hosting.Request();
		req.name = name.text();
		req.options = options();
		req.visibility = friendsVisible ? "friends" : "invited";
		req.backup = withBackup;
		h.host(req);
	}
}
