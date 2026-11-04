package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.config.ConfigPart;
import dev.theredstonee.trsclient.core.config.TrsConfig;

/** Eigene Rekorde je Spiel, auf diesem Rechner in {@code trsclient.json}; die Rangliste führt der Server. */
public final class MinigameBest implements ConfigPart {
	private long stapler;
	private long loren;

	public long get(String game) {
		return Replay.STAPLER.equals(game) ? stapler : loren;
	}

	/** Trägt ein Ergebnis ein; true, wenn es ein neuer Rekord ist. */
	public boolean record(String game, long score) {
		if (score <= get(game)) return false;
		if (Replay.STAPLER.equals(game)) stapler = score;
		else loren = score;
		return true;
	}

	@Override
	public void read(TrsConfig config) {
		TrsConfig.MinigamesData data = config.minigames;
		stapler = data == null || data.stapler == null ? 0 : Math.max(0, data.stapler);
		loren = data == null || data.loren == null ? 0 : Math.max(0, data.loren);
	}

	@Override
	public void write(TrsConfig config) {
		if (stapler == 0 && loren == 0) {
			config.minigames = null;
			return;
		}
		TrsConfig.MinigamesData data = new TrsConfig.MinigamesData();
		data.stapler = stapler;
		data.loren = loren;
		config.minigames = data;
	}
}
