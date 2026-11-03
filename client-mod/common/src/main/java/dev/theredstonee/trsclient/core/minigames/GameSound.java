package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.qol.QolSound;

import java.util.ArrayList;
import java.util.List;

/**
 * Ton der Minispiele über die Klänge des Spiels. Der Launcher erzeugt seine Töne selbst, im Client gibt es das nicht;
 * darum steht jedes Ereignis hier für einen passenden Minecraft-Klang. Tonfolgen laufen über {@link #later}. Wie laut es
 * wird, regeln die Einstellungen des Moduls und die Lautstärke „Master“ des Spiels.
 */
public final class GameSound {
	/** Spielt einen Klang (Lautstärke 0–1). */
	public interface Player {
		void play(QolSound sound, float volume, float pitch);
	}

	private static final class Delayed {
		float wait;
		final QolSound sound;
		final float volume;
		final float pitch;

		Delayed(float wait, QolSound sound, float volume, float pitch) {
			this.wait = wait;
			this.sound = sound;
			this.volume = volume;
			this.pitch = pitch;
		}
	}

	private final Player player;
	private final List<Delayed> pending = new ArrayList<Delayed>();
	/** Gesamtlautstärke 0–1; 0 = aus. */
	public float master = 1f;

	public GameSound(Player player) {
		this.player = player;
	}

	public void play(QolSound sound, float volume, float pitch) {
		if (master <= 0f || player == null) return;
		player.play(sound, Math.min(1f, volume * master), Math.max(0.5f, Math.min(2f, pitch)));
	}

	/** Spielt {@code seconds} später; Pause hält die Warteschlange an. */
	public void later(float seconds, QolSound sound, float volume, float pitch) {
		if (pending.size() < 32) pending.add(new Delayed(seconds, sound, volume, pitch));
	}

	public void update(float dt) {
		for (int i = pending.size() - 1; i >= 0; i--) {
			Delayed d = pending.get(i);
			d.wait -= dt;
			if (d.wait <= 0f) {
				pending.remove(i);
				play(d.sound, d.volume, d.pitch);
			}
		}
	}

	public void clear() {
		pending.clear();
	}

	/** Halbton-Stufe {@code n} als Tonhöhe, 0 = 1,0. */
	static float note(int n) {
		return (float) Math.pow(2.0, n / 12.0);
	}
}
