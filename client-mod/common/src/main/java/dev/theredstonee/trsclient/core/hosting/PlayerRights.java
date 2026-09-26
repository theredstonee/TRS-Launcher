package dev.theredstonee.trsclient.core.hosting;

/**
 * Rechte eines Gasts in der gehosteten Welt und wie sie auf Vanilla abgebildet werden (keine eigene Schutz-Logik
 * im Server – alles über Mittel, die jede Minecraft-Version kennt):
 *
 * <ul>
 * <li><b>OP</b> → Operator der Welt ({@code /op}: Befehle, Stufe laut Server-Voreinstellung).</li>
 * <li><b>Zuschauer</b> → Spielmodus Zuschauer (kann nichts verändern, fliegt durch Blöcke).</li>
 * <li><b>Bauen erlaubt</b> aus → Spielmodus Abenteuer (Blöcke weder abbauen noch setzen; Truhen/Türen/Knöpfe gehen
 * weiter – so wie Vanilla-Abenteuerkarten). An → der Spielmodus der Welt (Überleben/Kreativ).</li>
 * </ul>
 *
 * Zuschauer hat Vorrang vor „Bauen“. Unveränderlich.
 */
public final class PlayerRights {
	public static final PlayerRights DEFAULT = new PlayerRights(false, false, true);

	public final boolean op;
	public final boolean spectator;
	public final boolean build;

	public PlayerRights(boolean op, boolean spectator, boolean build) {
		this.op = op;
		this.spectator = spectator;
		this.build = build;
	}

	public PlayerRights withOp(boolean v) {
		return new PlayerRights(v, spectator, build);
	}

	public PlayerRights withSpectator(boolean v) {
		return new PlayerRights(op, v, build);
	}

	public PlayerRights withBuild(boolean v) {
		return new PlayerRights(op, spectator, v);
	}

	public boolean isDefault() {
		return !op && !spectator && build;
	}

	/**
	 * Spielmodus für diesen Gast: "spectator", "adventure" oder der Modus der Welt ({@code worldMode} =
	 * survival|creative|adventure|spectator).
	 */
	public String gameMode(String worldMode) {
		if (spectator) return "spectator";
		if (!build) return "adventure";
		return worldMode == null ? "survival" : worldMode;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof PlayerRights)) return false;
		PlayerRights r = (PlayerRights) o;
		return r.op == op && r.spectator == spectator && r.build == build;
	}

	@Override
	public int hashCode() {
		return (op ? 1 : 0) | (spectator ? 2 : 0) | (build ? 4 : 0);
	}
}
